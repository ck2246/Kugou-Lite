package com.kai.kghook.hook

import com.kai.kghook.LiteConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Proxy

/**
 * 广告曝光监测静音。
 *
 * [LiteConfig.AD_MONITOR_CLASS] 这个控件本身**不加载广告**，它只做曝光统计：
 * ```
 * onWindowVisibilityChanged(VISIBLE) → l() 起 1 秒周期轮询
 *     → getGlobalVisiblePercent() → GlobalVisibilityObserver.a(百分比)
 * ```
 * 也就是说，就算把广告 View 藏了，只要这个控件的窗口可见性还在变，
 * 它就每秒算一次可见比例、每秒打一条 `m2.D` 日志。
 *
 * 这里做两件事，互为主备：
 * 1. 清空起轮询的私有方法，从根上断掉 1 秒心脏跳动；
 * 2. 把可见比例改成恒 0，万一方法名改了没清掉，上报出去的也只会是"不可见"。
 *
 * 注意它管不到"广告还发不发请求"——请求发生在广告位的数据绑定里，不在这个类。
 */
internal object KugouAdMonitorHook {

    @Volatile
    private var installed = false

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (installed) return
        installed = true

        if (lpparam.processName != LiteConfig.TARGET_PACKAGE) {
            XLog.i("广告监测静音：跳过非主进程 ${lpparam.processName}")
            return
        }

        val clazz = runCatching { lpparam.classLoader.loadClass(LiteConfig.AD_MONITOR_CLASS) }
            .getOrNull()
        if (clazz == null) {
            XLog.w(
                "找不到广告监测类 ${LiteConfig.AD_MONITOR_CLASS}（酷狗可能已改版），" +
                        "曝光轮询不会被静音，但广告容器仍会被隐藏"
            )
            return
        }

        // 1) 断掉 1 秒轮询。方法名混淆成 l，换版本可能变，失败只损失这点优化
        val tickHooked = runCatching {
            XposedHelpers.findAndHookMethod(
                clazz,
                LiteConfig.AD_MONITOR_TICK_METHOD,
                XC_MethodReplacement.DO_NOTHING
            )
            true
        }.onFailure {
            XLog.w("清空轮询方法 ${LiteConfig.AD_MONITOR_TICK_METHOD}() 失败: $it")
        }.getOrDefault(false)

        // 2) 兜底：可见比例恒为 0。名字没混淆，比 1) 稳
        val percentHooked = runCatching {
            XposedHelpers.findAndHookMethod(
                clazz,
                LiteConfig.AD_MONITOR_PERCENT_METHOD,
                XC_MethodReplacement.returnConstant(0f)
            )
            true
        }.onFailure {
            XLog.w("改写 ${LiteConfig.AD_MONITOR_PERCENT_METHOD}() 失败: $it")
        }.getOrDefault(false)

        // 3) 把曝光监听器换成空实现。
        //    全仓库唯一的调用点是 SongCommentAdProcessor.u(CommentEntity, int)，
        //    换掉它等于从效果上删掉那次调用；而且必须换不能置空——控件里
        //    `f176975f.a(...)` 是无保护的直接调用，置空会让轮询一触发就 NPE
        val observerMuted = if (LiteConfig.AD_MONITOR_MUTE_OBSERVER) {
            runCatching { muteObserver(clazz) }
                .onFailure { XLog.w("替换曝光监听器失败: $it") }
                .getOrDefault(false)
        } else {
            false
        }

        if (tickHooked || percentHooked || observerMuted) {
            XLog.i(
                "广告曝光监测已静音: 轮询=$tickHooked 可见比例=$percentHooked 监听器=$observerMuted"
            )
        } else {
            XLog.e("广告曝光监测三条 hook 都没挂上，该控件的 1 秒轮询仍在运行")
        }
    }

    /**
     * 把 [LiteConfig.AD_MONITOR_OBSERVER_METHOD] 收到的监听器替换成空实现。
     *
     * 用动态代理而不是"把字段置空"：
     * `AdMonitorLinearLayout.a.call()` 里是 `f176975f.a(百分比)`，
     * 没有任何空判断，置空一旦赶上轮询就是每秒一次 NPE。
     * 代理对象能正常接收调用、什么都不做，既断掉上报又不改变控件假设。
     *
     * 接口类型从方法签名反射取，不写死类名，酷狗换实现类也不受影响。
     */
    private fun muteObserver(clazz: Class<*>): Boolean {
        val declared = clazz.declaredMethods
            .firstOrNull { it.name == LiteConfig.AD_MONITOR_OBSERVER_METHOD }
            ?: return false
        val iface = declared.parameterTypes.firstOrNull() ?: return false
        if (!iface.isInterface) return false

        val noop = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, method, _ ->
            when (method.name) {
                "equals" -> false
                "hashCode" -> System.identityHashCode(iface)
                "toString" -> "kghook:noop-visibility-observer"
                else -> null
            }
        }

        XposedHelpers.findAndHookMethod(
            clazz,
            LiteConfig.AD_MONITOR_OBSERVER_METHOD,
            iface,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.args[0] = noop
                }
            }
        )
        return true
    }
}
