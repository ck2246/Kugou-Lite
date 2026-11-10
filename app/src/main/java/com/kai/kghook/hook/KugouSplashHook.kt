package com.kai.kghook.hook

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import com.kai.kghook.LiteConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 开屏广告跳过：把「启动后的第一个活动」从开屏页改成主界面。
 *
 * 酷狗 20.6.0 实测的真实启动链路（`aapt2 dump xmltree` + 真机日志确认）：
 * ```
 * 桌面点击
 *   → activity-alias `...app.splash.SplashActivity`（targetActivity = MediaActivity）
 *   → MediaActivity 启动
 *   → 弹出全屏广告 Dialog `rc.a`（extends android.app.Dialog）
 *   → 约 4 秒后首帧落地，露出首页
 * ```
 * 两个曾经的误判，写在这里免得再踩：
 * 1. `SplashActivity` **只是 activity-alias，APK 里没有这个类**，注入时 `loadClass` 必然失败，
 *    按类名 hook Activity 是徒劳的（日志里的「开屏候选类不存在」就是它）。
 * 2. 开屏广告**不是 Activity，而是盖在 MediaActivity 上的全屏 Dialog**，
 *    所以真正有效的拦截点是 [hookSplashDialogs] 里的 `onAttachedToWindow`。
 *
 * 现在的做法：Dialog 一挂上窗口就设成透明并在下一帧 dismiss —— 广告零帧可见，
 * 而 show→dismiss 的生命周期完整，酷狗进入主页的回调照常触发（直接拦 show 会把启动卡死）。
 *
 * 三个刻意的设计取舍：
 *
 * 1. **不替换类，只改道**。用 Instrumentation 层替换 Activity 类虽然更"彻底"，
 *    但开屏页的 `onCreate` 里往往还带着酷狗自己的启动初始化（路由、配置、恢复状态），
 *    直接跳过它容易让主界面拿到半初始化状态。这里让 `onCreate` 正常跑完再改道，
 *    初始化照旧、广告不显示。
 *
 * 2. **只拦桌面启动**。开屏页可能被酷狗内部复用（推送、深链、返回栈恢复），
 *    那些场景下改道会把用户的跳转目标吞掉，所以先看 Intent 是不是
 *    `ACTION_MAIN` + `CATEGORY_LAUNCHER`，不是就原样放行。
 *
 * 3. **改道失败就退回原生流程**。目标类不存在、或 startActivity 抛异常时只记日志，
 *    不结束开屏页——宁可看一次广告，也不能把酷狗启动搞崩。
 */
internal object KugouSplashHook {

    /**
     * 改道用的 Intent 标志。
     *
     * CLEAR_TOP 保证栈里如果已经有主界面就直接复用它，不会叠出第二个；
     * SINGLE_TOP 让"已经在最上面"的情况只走 onNewIntent，不重建界面。
     */
    private const val REDIRECT_FLAGS =
        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

    @Volatile
    private var installed = false

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (installed) return
        installed = true

        // 酷狗是多进程应用，开屏页只在主进程
        if (lpparam.processName != LiteConfig.TARGET_PACKAGE) {
            XLog.i("开屏跳过：跳过非主进程 ${lpparam.processName}")
            return
        }
        if (!LiteConfig.SKIP_SPLASH_AD) {
            XLog.i("开屏跳过已关闭（LiteConfig.SKIP_SPLASH_AD = false），启动链路保持原生")
            return
        }

        var hooked = 0
        LiteConfig.SPLASH_CANDIDATES.forEach { className ->
            val clazz = runCatching { lpparam.classLoader.loadClass(className) }.getOrNull()
            if (clazz == null) {
                XLog.i("开屏候选类不存在，跳过: $className")
                return@forEach
            }
            if (hookSplash(clazz)) hooked++
        }

        if (hooked == 0) {
            XLog.w(
                "按名没有命中任何开屏类（酷狗是插件化壳，清单里的 SplashActivity 可能加载不到），" +
                        "将依赖通用改道与开屏 Dialog 拦截"
            )
        } else {
            XLog.i("开屏跳过就绪（按名命中 $hooked 个开屏类，目标 ${LiteConfig.MAIN_ACTIVITY}）")
        }

        // 兜底一：挂在框架基类上，不依赖具体类能否提前加载
        if (LiteConfig.SPLASH_BY_CLASS_NAME_HINT) hookAnyActivity()
        // 兜底二：开屏广告其实是个全屏 Dialog，直接不让它 show
        hookSplashDialogs(lpparam)
    }

    /**
     * 在 `android.app.Activity#onCreate` 上挂通用改道。
     *
     * 酷狗清单里声明了 `...app.splash.SplashActivity`，但模块注入时 `loadClass` 拿不到，
     * 按名 hook 必然落空。挂在框架基类上，则每个 Activity 都会经过这里，
     * 用**运行时真实类名**判断是不是开屏页，绕开插件化壳带来的加载时序问题。
     */
    private fun hookAnyActivity() {
        runCatching {
            XposedHelpers.findAndHookMethod(
                Activity::class.java, "onCreate", Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        val name = activity.javaClass.name
                        if (!name.contains(LiteConfig.SPLASH_CLASS_NAME_HINT, ignoreCase = true)) return
                        // 车机开屏是另一条业务线，改了会让车机模式起不来
                        if (LiteConfig.SPLASH_CLASS_NAME_SKIP.any { name.contains(it, ignoreCase = true) }) return
                        redirect(activity, activity.javaClass)
                    }
                }
            )
            XLog.i("已挂通用 Activity.onCreate 兜底（按类名含 splash 识别开屏页）")
        }.onFailure {
            XLog.e("挂通用 Activity.onCreate 兜底失败: $it")
        }
    }

    /**
     * 拦掉开屏广告的全屏 Dialog。
     *
     * 反编译确认 `rc.a extends android.app.Dialog`，只有 show/dismiss 这套方法。
     * **不能直接拦 show()**：真机验证过，`dismiss()` 的回调才是酷狗"进入主页"的驱动，
     * 把 show 整个掐掉会让开屏永远停住（品牌页卡死不进主界面）。
     *
     * 所以在 `onAttachedToWindow` 之后动手：先把窗口设成全透明，再在下一帧 dismiss。
     * 这样 Dialog 的生命周期是完整的（回调照常触发），但广告一帧都看不见。
     */
    private fun hookSplashDialogs(lpparam: XC_LoadPackage.LoadPackageParam) {
        LiteConfig.SPLASH_DIALOG_CANDIDATES.forEach { className ->
            val clazz = runCatching { lpparam.classLoader.loadClass(className) }.getOrNull()
            if (clazz == null) {
                XLog.w("开屏广告 Dialog 类不存在: $className（改版后请更新 LiteConfig.SPLASH_DIALOG_CANDIDATES）")
                return@forEach
            }
            val ok = runCatching {
                XposedHelpers.findAndHookMethod(
                    clazz, "onAttachedToWindow",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val dialog = param.thisObject as? Dialog ?: return
                            val decor = dialog.window?.decorView ?: return
                            // 先隐身，再让 dismiss 在下一帧发生：回调照常，视觉上没有广告
                            decor.alpha = 0f
                            decor.post {
                                runCatching { if (dialog.isShowing) dialog.dismiss() }
                            }
                            XLog.i("已拦截开屏广告 Dialog($className)：透明并立即 dismiss")
                        }
                    }
                )
                true
            }.onFailure {
                XLog.w("拦截 $className.onAttachedToWindow 失败: $it")
            }.getOrDefault(false)
            if (ok) XLog.i("开屏广告 Dialog 已静音: $className")
        }
    }

    private fun hookSplash(clazz: Class<*>): Boolean = runCatching {
        XposedHelpers.findAndHookMethod(
            clazz, "onCreate", Bundle::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    // findAndHookMethod 会沿父类链查找，可能实际挂在基类的 onCreate 上，
                    // 那样所有继承同类基类的 Activity 都会进这个回调，必须校验类型
                    if (!clazz.isInstance(param.thisObject)) return
                    redirect(param.thisObject as Activity, clazz)
                }
            }
        )
        true
    }.onFailure {
        XLog.e("hook 开屏类 ${clazz.name} 失败: $it")
    }.getOrDefault(false)

    /**
     * 把当前开屏页改道到主界面。
     *
     * 顺序是「先起主界面、再结束自己」：反过来的话会先露出桌面/上一个应用，
     * 再被主界面盖住，闪一下比看广告还难受。
     */
    private fun redirect(activity: Activity, splashClass: Class<*>) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (!isLauncherStart(activity.intent)) {
            XLog.i(
                "开屏页不是桌面启动（action=${activity.intent?.action}，" +
                        "categories=${activity.intent?.categories}），保持原生流程"
            )
            return
        }
        // 目标类不存在就直接放弃：发一个指向不存在类的 Intent 只会换来 ActivityNotFound
        if (runCatching { activity.classLoader.loadClass(LiteConfig.MAIN_ACTIVITY) }.isFailure) {
            XLog.e("主界面类 ${LiteConfig.MAIN_ACTIVITY} 不存在，保留原生开屏流程")
            return
        }

        val target = Intent(Intent.ACTION_MAIN).apply {
            setClassName(LiteConfig.TARGET_PACKAGE, LiteConfig.MAIN_ACTIVITY)
            addFlags(REDIRECT_FLAGS)
        }

        // 关掉页面切换动画：开屏页本来就没画出来，再叠一层动画反而像闪屏。
        // minSdk 36 起 overridePendingTransition 已废弃，改用 34+ 的 overrideActivityTransition，
        // 且必须在 startActivity 之前调用才作用于下一次切换
        runCatching { activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0) }

        val startedAt = SystemClock.uptimeMillis()
        try {
            activity.startActivity(target)
        } catch (e: ActivityNotFoundException) {
            XLog.e("主界面 ${LiteConfig.MAIN_ACTIVITY} 无法启动，保留原生开屏流程: $e")
            return
        } catch (e: Throwable) {
            XLog.e("改道到主界面失败，保留原生开屏流程: $e")
            return
        }

        activity.finish()
        XLog.i(
            "已跳过开屏广告：${splashClass.simpleName} → ${LiteConfig.MAIN_ACTIVITY}" +
                    "（耗时 ${SystemClock.uptimeMillis() - startedAt}ms）"
        )
    }

    /**
     * 是不是「从桌面冷启动」。
     *
     * 桌面的启动 Intent 一定是 `ACTION_MAIN` + `CATEGORY_LAUNCHER`；
     * 推送、深链、应用内跳转都不会同时满足这两条，据此把改道限制在启动首屏。
     */
    private fun isLauncherStart(intent: Intent?): Boolean {
        if (intent == null) return false
        if (intent.action != Intent.ACTION_MAIN) return false
        return intent.categories?.contains(Intent.CATEGORY_LAUNCHER) == true
    }
}
