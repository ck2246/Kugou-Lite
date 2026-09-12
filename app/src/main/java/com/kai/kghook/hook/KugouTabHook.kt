package com.kai.kghook.hook

import android.app.Activity
import android.content.res.Resources
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
import com.kai.kghook.TabConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 隐藏酷狗音乐底部导航里的「视频 / K歌 / 福利」三个 Tab。
 *
 * 从 UI dump 得到的实际结构：
 * ```
 * LinearLayout  @id/bottom_tab_layout        <- 未混淆，跨版本最稳的锚点
 *   ├ RelativeLayout @id/d47  content-desc="首页"
 *   ├ RelativeLayout @id/d3y  content-desc="视频"   (内含 @id/c00 ImageView + @id/c07 TextView)
 *   ├ RelativeLayout @id/d3u  content-desc="K歌"
 *   ├ RelativeLayout @id/d3t  content-desc="福利"
 *   └ RelativeLayout @id/d44  content-desc="我的"
 * ```
 * 这三个 Tab 既不是 Activity 也不是 Fragment，只是底部栏里的普通 ViewGroup，
 * 对应页面由酷狗自己在点击时按需构建，所以这里从 View 层入手。
 *
 * 识别策略按可靠性分级，逐级兜底：
 * 1. 容器：`bottom_tab_layout`（未混淆）；
 * 2. Tab 身份：`contentDescription` + 内部 TextView 文本，混淆 id 每版都变，文案不会；
 * 3. 混淆 id：`d3y / d3u / d3t`，仅在文案匹配失败时兜底；
 * 4. 容器兜底：连 `bottom_tab_layout` 都改名时，在 View 树里按特征搜索底部栏。
 */
internal object KugouTabHook {

    val TARGET_PACKAGE = TabConfig.TARGET_PACKAGE

    /** 主界面 Activity；若某天酷狗改了类名，往这里追加候选即可，不用改其它逻辑 */
    private val ACTIVITY_CANDIDATES = arrayOf(
        "com.kugou.android.app.MediaActivity"
    )

    private const val TAB_CONTAINER_ID = "bottom_tab_layout"

    /** 当前版本对应的混淆 id，只作兜底用 */
    private val HIDE_ID_NAMES = arrayOf("d3y", "d3u", "d3t")

    /** 兜底搜索时用来确认「这确实是底部导航栏」的已知 Tab 名 */
    private val KNOWN_TAB_NAMES = setOf("首页", "视频", "K歌", "福利", "我的")

    private val HIDE_NAMES = TabConfig.HIDE_TAB_NAMES.toSet()

    /** 布局监听最多尝试的次数，避免极端情况下无限空转 */
    private const val MAX_LAYOUT_PASS = 40

    /** 布局监听之外的兜底重试时点，覆盖“底部栏极晚才异步插入”的情况 */
    private val FALLBACK_DELAYS_MS = longArrayOf(500L, 1500L, 3000L)

    /** 资源 id 缓存的未解析哨兵值 */
    private const val UNRESOLVED = -2

    @Volatile
    private var installed = false

    /** 结构日志只打一次，免得刷屏 */
    @Volatile
    private var dumped = false

    /** 「容器里没匹配到 Tab」的告警也只打一次 */
    @Volatile
    private var warnedNoMatch = false

    /** 资源 id 解析结果缓存，避免每个布局轮次重复查表 */
    @Volatile
    private var cachedContainerId = UNRESOLVED

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (installed) return
        installed = true

        // 酷狗是多进程应用（播放服务、推送、桌面歌词等），只有主进程有底部导航
        if (lpparam.processName != TARGET_PACKAGE) {
            XLog.i("跳过非主进程: ${lpparam.processName}")
            return
        }
        XLog.i("已进入酷狗主进程，开始注入")

        var hooked = false
        ACTIVITY_CANDIDATES.forEach { className ->
            val clazz = runCatching { lpparam.classLoader.loadClass(className) }.getOrNull()
            if (clazz == null) {
                XLog.w("找不到主界面类: $className（酷狗可能已改版，需更新候选类名）")
                return@forEach
            }
            if (hookActivity(clazz)) hooked = true
        }
        if (!hooked) XLog.e("所有候选主界面类都 hook 失败，模块不会生效")
    }

    private fun hookActivity(clazz: Class<*>): Boolean {
        return runCatching {
            XposedHelpers.findAndHookMethod(
                clazz, "onCreate", Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // findAndHookMethod 会沿父类链查找，可能实际 hook 到基类方法，
                        // 那样酷狗里所有继承同一基类的 Activity 都会进这个回调，必须校验类型
                        if (!clazz.isInstance(param.thisObject)) return
                        onActivityReady(param.thisObject as Activity, first = true)
                    }
                }
            )
            XposedHelpers.findAndHookMethod(
                clazz, "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!clazz.isInstance(param.thisObject)) return
                        // 换肤、夜间模式、长辈/精简模式切换都可能重建底部栏，回前台时校正一次
                        onActivityReady(param.thisObject as Activity, first = false)
                    }
                }
            )
            XLog.i("hook 成功: ${clazz.name}#onCreate / #onResume")
            true
        }.onFailure {
            XLog.e("hook ${clazz.name} 失败: $it")
        }.getOrDefault(false)
    }

    private fun onActivityReady(activity: Activity, first: Boolean) {
        if (applyHideSafely(activity)) return
        // onResume 阶段做轻量校正即可，不必再挂监听
        if (!first) return
        // onCreate 结束时底部栏往往还没 inflate 出来。挂一个全局布局监听，
        // 在它出现的那一轮 traversal 里（绘制之前）立刻隐藏，用户不会看到 Tab 闪现。
        watchUntilReady(activity)
    }

    private fun watchUntilReady(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        val observer = decor.viewTreeObserver
        if (observer == null || !observer.isAlive) {
            postFallbackRetries(decor, activity)
            return
        }

        XLog.i("底部导航尚未创建，已挂布局监听等待其出现")
        observer.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            private var pass = 0

            override fun onGlobalLayout() {
                pass++
                val activityDead = activity.isFinishing || activity.isDestroyed
                if (activityDead || pass > MAX_LAYOUT_PASS || applyHideSafely(activity)) {
                    detach()
                }
            }

            private fun detach() {
                val o = decor.viewTreeObserver
                if (o != null && o.isAlive) o.removeOnGlobalLayoutListener(this)
            }
        })

        // 双保险：监听被别处清空、或底部栏在布局稳定后才异步插入时，再补几次
        postFallbackRetries(decor, activity)
    }

    private fun postFallbackRetries(decor: View, activity: Activity) {
        FALLBACK_DELAYS_MS.forEach { delay ->
            decor.postDelayed({
                if (activity.isFinishing || activity.isDestroyed) return@postDelayed
                applyHideSafely(activity)
            }, delay)
        }
    }

    private fun applyHideSafely(activity: Activity): Boolean {
        return try {
            val ok = applyHide(activity)
            if (ok && !dumped) {
                dumped = true
                dumpStructure(activity)
            }
            ok
        } catch (e: Throwable) {
            XLog.e("隐藏逻辑异常（已吞掉，不影响酷狗运行）: $e")
            false
        }
    }

    /** @return 是否确实隐藏掉了至少一个目标 Tab */
    private fun applyHide(activity: Activity): Boolean {
        val container = findTabContainer(activity) ?: return false
        val res = activity.resources
        val fallbackIds = HIDE_ID_NAMES.mapNotNull { name ->
            res.getIdentifier(name, "id", TARGET_PACKAGE).takeIf { it != 0 }
        }.toSet()

        var hidden = 0
        var kept = 0
        for (i in 0 until container.childCount) {
            val tab = container.getChildAt(i) ?: continue
            val labels = tabLabels(tab)
            val matchedByName = labels.any { label -> HIDE_NAMES.any { it.equals(label, ignoreCase = true) } }
            val matchedById = tab.id != View.NO_ID && tab.id in fallbackIds
            if (matchedByName || matchedById) {
                if (tab.visibility != View.GONE) {
                    tab.visibility = View.GONE
                    XLog.i("已隐藏 Tab: ${labels.joinToString("/")} id=${entryName(res, tab.id)}")
                }
                hidden++
            } else {
                // 只对可点击的子项做均分，避免把底部栏里的装饰性 View 也拉伸
                if (tab.isClickable) expandToFill(tab)
                kept++
            }
        }

        if (hidden == 0) {
            if (!warnedNoMatch) {
                warnedNoMatch = true
                XLog.w(
                    "容器 ${container.javaClass.simpleName}#${entryName(res, container.id)} " +
                            "里没匹配到目标 Tab（childCount=${container.childCount}），"
                            + "请对照结构快照更新 TabConfig.HIDE_TAB_NAMES 或 HIDE_ID_NAMES"
                )
            }
            return false
        }

        // 布局若写死了 weightSum（例如 5），隐藏 3 个后剩下的只会各占 1/5，右侧留大片空白。
        // 置 0 让 LinearLayout 按「可见子 View 的权重之和」自动计算，两个 Tab 正好各占一半。
        if (container is LinearLayout && container.weightSum > 0f) {
            container.weightSum = 0f
            XLog.i("容器 weightSum 原本为固定值，已重置为自动计算")
        }
        XLog.i("本次处理：隐藏 $hidden 个 Tab，保留 $kept 个并已均分宽度")
        return true
    }

    /** 把保留下来的 Tab 改成 width=0 + weight=1；本来就是均分的直接跳过，省一次重新布局 */
    private fun expandToFill(tab: View) {
        val lp = tab.layoutParams as? LinearLayout.LayoutParams ?: return
        if (lp.width == 0 && lp.weight == 1f) return
        lp.width = 0
        lp.weight = 1f
        tab.layoutParams = lp
    }

    private fun findTabContainer(activity: Activity): ViewGroup? {
        val res = activity.resources
        var id = cachedContainerId
        if (id == UNRESOLVED) {
            id = res.getIdentifier(TAB_CONTAINER_ID, "id", TARGET_PACKAGE)
            cachedContainerId = id
            XLog.i("锚点 id 解析: $TAB_CONTAINER_ID -> ${if (id == 0) "未找到" else id.toString()}")
        }
        if (id != 0) {
            val v = activity.findViewById<View>(id)
            // 锚点存在就说明没改版：childCount == 0 只是 Tab 还没塞进来，
            // 返回 null 让上层继续等，不必再去全树搜索（那在卡顿机器上很贵）
            return if (v is ViewGroup && v.childCount > 0) v else null
        }
        // 连 bottom_tab_layout 都改名了，才退化成按特征在 View 树里找
        return searchContainer(activity.window?.decorView as? ViewGroup)
    }

    private fun searchContainer(root: ViewGroup?): ViewGroup? {
        if (root == null) return null
        if (looksLikeTabBar(root)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is ViewGroup && child.visibility != View.GONE) {
                searchContainer(child)?.let { return it }
            }
        }
        return null
    }

    private fun looksLikeTabBar(vg: ViewGroup): Boolean {
        if (vg !is LinearLayout || vg.orientation != LinearLayout.HORIZONTAL) return false
        if (vg.childCount < 3) return false
        var named = 0
        for (i in 0 until vg.childCount) {
            val child = vg.getChildAt(i) ?: return false
            if (!child.isClickable) return false
            if (tabLabels(child).any { it in KNOWN_TAB_NAMES }) named++
        }
        return named >= 2
    }

    /** 取一个 Tab 的所有可识别文案：自身 content-desc + 内部所有 TextView 文本 */
    private fun tabLabels(v: View): Set<String> {
        val out = LinkedHashSet<String>()
        v.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
        collectText(v, out)
        return out
    }

    private fun collectText(v: View, out: MutableSet<String>) {
        if (v is TextView) {
            v.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                v.getChildAt(i)?.let { collectText(it, out) }
            }
        }
    }

    /** 把底部栏与内容区结构打进日志，方便日后酷狗改版时快速定位新 id */
    private fun dumpStructure(activity: Activity) {
        try {
            val res = activity.resources
            val version = runCatching {
                activity.packageManager.getPackageInfo(TARGET_PACKAGE, 0).versionName
            }.getOrNull()

            XLog.i("========== 结构快照 ==========")
            XLog.i("酷狗版本: ${version ?: "未知"}  Activity: ${activity.javaClass.name}")

            val container = findTabContainer(activity)
            if (container == null) {
                XLog.w("未找到底部导航容器")
            } else {
                val extra = if (container is LinearLayout) " weightSum=${container.weightSum}" else ""
                XLog.i(
                    "底部栏: ${container.javaClass.name}#${entryName(res, container.id)}" +
                            " childCount=${container.childCount}$extra"
                )
                for (i in 0 until container.childCount) {
                    val tab = container.getChildAt(i) ?: continue
                    XLog.i(
                        "  [$i] ${tab.javaClass.simpleName}#${entryName(res, tab.id)}" +
                                " vis=${visibilityName(tab.visibility)} labels=${tabLabels(tab)}"
                    )
                }
            }

            XLog.i("---- 内容区顶层（定位页面容器用，深度 3）----")
            activity.findViewById<ViewGroup>(android.R.id.content)?.let { dumpTree(it, 0, 3) }
            XLog.i("==============================")
        } catch (e: Throwable) {
            XLog.e("结构快照输出失败: $e")
        }
    }

    private fun dumpTree(vg: ViewGroup, depth: Int, maxDepth: Int) {
        if (depth >= maxDepth) return
        val res = vg.resources
        for (i in 0 until vg.childCount) {
            val child = vg.getChildAt(i) ?: continue
            XLog.i(
                "  ".repeat(depth + 1) + "${child.javaClass.simpleName}#${entryName(res, child.id)}" +
                        " vis=${visibilityName(child.visibility)} ${child.width}x${child.height}"
            )
            if (child is ViewGroup) dumpTree(child, depth + 1, maxDepth)
        }
    }

    private fun entryName(res: Resources, id: Int): String {
        if (id == View.NO_ID || id == 0) return "none"
        return runCatching { res.getResourceEntryName(id) }.getOrDefault("?")
    }

    private fun visibilityName(visibility: Int): String = when (visibility) {
        View.VISIBLE -> "VISIBLE"
        View.INVISIBLE -> "INVISIBLE"
        View.GONE -> "GONE"
        else -> "?$visibility"
    }
}
