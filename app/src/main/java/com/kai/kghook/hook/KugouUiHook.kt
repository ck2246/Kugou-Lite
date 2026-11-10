package com.kai.kghook.hook

import android.app.Activity
import android.content.res.Resources
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.kai.kghook.LiteConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * 酷狗音乐界面精简：底部导航、首页顶部频道栏、首页快捷入口卡片、信息流广告。
 *
 * 实际结构（来自真机 UI dump）：
 * ```
 * MediaActivity
 *  ├ LinearLayout @id/bottom_tab_layout          底部导航，5 个 RelativeLayout 子项
 *  │    每项自带 content-desc：首页/视频/K歌/福利/我的
 *  ├ TingMainSwipeTabScrollContainer @id/urw      顶部频道栏（真机类名，不是原生 HorizontalScrollView）
 *  │    └ LinearLayout @id/none → FrameLayout（2 个子：频道容器 + 一个 GONE 的指示器）
 *  │         └ LinearLayout @id/ful1，1859px 宽，9 个无 id 的 RelativeLayout：
 *  │              推荐/乐库/听书/儿童/AI唱/刷歌/听播/AI写歌/短剧
 *  │              标签在内部 TextView @id/fv_0 上，选中项的 content-desc 是「推荐已选中」
 *  └ ViewGroup @id/d3q → … → RecyclerView android:id/list   首页是一堆「卡片」item
 *       ├ item0 LinearLayout @id/g2p0            账户信息 + 快捷入口（两者并列！）
 *       │    └ RelativeLayout @id/ei31 → QuickCardRecycleView @id/e3w1   ← 要隐藏
 *       │         猜你喜欢 / 每日推荐 / 排行榜 / 百万收藏
 *       ├ item1 「根据《一直很安静》推荐」卡片
 *       └ item2 「音频·听书」卡片
 *            └ TingCardRecycleView @id/e3w1       ← 同一个 id 被复用！不能只按 id 匹配
 * ```
 *
 * 四个关键设计取舍：
 * 1. **文案优先、id 兜底**：混淆 id 每版都变，产品文案不会；id 只用来加速定位，失效时按特征全树搜索。
 * 2. **常驻布局监听**：首页卡片在 RecyclerView 里，滚走会被回收、滚回来重新绑定，
 *    一次性设 GONE 必然失效。而任何「把我们的隐藏改回去」的操作都会触发 requestLayout，
 *    所以监听全局布局就能兜住一切复位，代价是每轮几次遍历（微秒级）。
 * 3. **顶部频道栏用白名单**：只保留 推荐/乐库，其余全隐藏。这样 dump 里看不到的
 *    听播/AI写歌/短剧（在可视区外被 uiautomator 裁掉，但 View 树里存在）以及酷狗
 *    以后新增的频道都会被自动处理，不用逐个维护黑名单。
 * 4. **不能全信 dump 的层级**：uiautomator 会把无文案的中间层折叠掉，也会把可视区外的
 *    节点整个丢弃。所以定位到容器后要自适应下钻，真机结构以日志里的结构快照为准。
 */
internal object KugouUiHook {

    /** 常驻监听的重扫间隔：太密白耗 CPU，太疏会让重新绑定的卡片闪现 */
    private const val APPLY_THROTTLE_MS = 100L

    /** 单个 View 子树最多收集多少条文案，防止在大列表上失控 */
    private const val MAX_LABELS = 48

    /** 注入后这段时间内不报告警：界面还没长齐，此时的「没找到」全是误报 */
    private const val WARN_AFTER_MS = 5000L

    /**
     * 定位「栏」容器时最多往下钻几层。
     *
     * 真机上 urw 里套了好几层无 id 的壳：
     * urw → LinearLayout#none（1 个子）→ FrameLayout（2 个子）→ LinearLayout#ful1（9 个频道），
     * 这些壳大多既没文案也没 id，dump 和日志里都看不出来，只能逐层钻。
     */
    private const val MAX_UNWRAP_DEPTH = 6

    /** 下钻时最多访问多少个节点，防止 id 意外解析到一个巨大的容器时把 UI 线程拖死 */
    private const val UNWRAP_NODE_BUDGET = 300

    /**
     * 结构快照的打印时机。
     *
     * 不能「第一次改动成功就打」：首页是一层层长出来的，快捷卡片先到位时
     * 底部导航还没创建，快照里就会缺一大块（真机日志已经出现过这种情况）。
     * 改成等界面长齐后无条件打一次。
     */
    private const val SNAPSHOT_DELAY_MS = 6000L

    @Volatile
    private var installed = false

    /** 结构快照只打一次，免得刷屏 */
    @Volatile
    private var dumped = false

    /** 资源 id 解析缓存：进程内资源表不变，每个名字查一次就够 */
    private val idCache = ConcurrentHashMap<String, Int>()

    /** 同类告警只报一次 */
    private val warned = ConcurrentHashMap.newKeySet<String>()

    /** 注入时刻，配合 [WARN_AFTER_MS] 抑制启动阶段的误报 */
    @Volatile
    private var injectedAt = 0L

    /**
     * 被本模块设成 GONE 的 View。
     *
     * 记下来的目的是能「撤销」：顶部频道栏 @id/urw 在「我的」页会换成 音乐/关注，
     * 如果酷狗复用了同一批子 View，不恢复就会让那个页面少一项。
     * 用弱引用集合，既能记住归属又不会泄露酷狗的 View。
     */
    private val hiddenByUs: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())

    /** 是否已用 id 成功定位过快捷卡片；定位过就不必再做昂贵的全树特征搜索 */
    @Volatile
    private var cardLocatedById = false

    /** 已挂载的守卫，避免同一个 Activity 挂多份监听 */
    private val guards = mutableListOf<UiGuard>()

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (installed) return
        installed = true
        injectedAt = SystemClock.uptimeMillis()

        // 酷狗是多进程应用（播放服务、推送、桌面歌词等），只有主进程有这些界面
        if (lpparam.processName != LiteConfig.TARGET_PACKAGE) {
            XLog.i("跳过非主进程: ${lpparam.processName}")
            return
        }
        XLog.i("已进入酷狗主进程，开始注入")

        var hooked = false
        LiteConfig.ACTIVITY_CANDIDATES.forEach { className ->
            val clazz = runCatching { lpparam.classLoader.loadClass(className) }.getOrNull()
            if (clazz == null) {
                XLog.w("找不到主界面类: $className（酷狗可能已改版，需在 LiteConfig 里补候选类名）")
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
                        // 换肤、夜间模式、长辈/精简模式切换都可能重建界面，回前台时补一次
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
        applyAll(activity)
        if (first) attachGuard(activity)
    }

    /**
     * 常驻守卫。
     *
     * 全局布局回调发生在 measure/layout 之后、draw 之前，所以首轮就能在画面出现前完成隐藏，
     * 用户看不到 Tab 闪现；后续轮次则负责对付 RecyclerView 重新绑定和酷狗自己的复位。
     */
    private fun attachGuard(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        val observer = decor.viewTreeObserver
        if (observer == null || !observer.isAlive) {
            XLog.w("ViewTreeObserver 不可用，退化为延时重试")
            postFallbackRetries(decor, activity)
            return
        }
        if (guards.any { it.isFor(activity) }) return

        val guard = UiGuard(activity)
        guards += guard
        observer.addOnGlobalLayoutListener(guard)
        XLog.i("已挂载常驻布局守卫")
    }

    private fun postFallbackRetries(decor: View, activity: Activity) {
        longArrayOf(300L, 1000L, 2500L).forEach { delay ->
            decor.postDelayed({
                if (activity.isFinishing || activity.isDestroyed) return@postDelayed
                applyAll(activity)
            }, delay)
        }
    }

    private class UiGuard(private val activity: Activity) : ViewTreeObserver.OnGlobalLayoutListener {

        private var lastApply = 0L

        fun isFor(other: Activity) = other === activity

        override fun onGlobalLayout() {
            if (activity.isFinishing || activity.isDestroyed) {
                detach()
                return
            }
            val now = SystemClock.uptimeMillis()
            // lastApply == 0 表示首轮，必须立刻执行，赶在第一帧绘制之前
            if (lastApply != 0L && now - lastApply < APPLY_THROTTLE_MS) return
            lastApply = now

            applyAll(activity)
            // 快照不依赖「本轮有没有改动」：等界面长齐后无条件打一次就够
            if (!dumped && now - injectedAt >= SNAPSHOT_DELAY_MS) {
                dumped = true
                dumpStructure(activity)
            }
        }

        private fun detach() {
            guards -= this
            val o = activity.window?.decorView?.viewTreeObserver
            if (o != null && o.isAlive) o.removeOnGlobalLayoutListener(this)
        }
    }

    /** @return 本轮是否真的改动了什么 */
    private fun applyAll(activity: Activity): Boolean {
        return try {
            val res = activity.resources
            var changed = applyBottomBar(activity, res)

            // 顶部频道栏和快捷卡片用的都是混淆 id，而且都可能存在多个同 id 实例
            // （urw 被听书系列模块共用、e3w1 被听书卡片复用），
            // 所以合并成一次全树遍历把两组候选一起捧回来，别每条规则各扫一遍
            val root = activity.window?.decorView as? ViewGroup
            if (root != null) {
                val wanted = LinkedHashMap<String, Int>()
                idOf(res, LiteConfig.TOP_BAR_ID)?.let { wanted[LiteConfig.TOP_BAR_ID] = it }
                idOf(res, LiteConfig.CARD_ID)?.let { wanted[LiteConfig.CARD_ID] = it }
                idOf(res, LiteConfig.AD_VIEW_ID)?.let { wanted[LiteConfig.AD_VIEW_ID] = it }
                LiteConfig.AD_CONTAINER_ID_NAMES.forEach { name ->
                    idOf(res, name)?.let { wanted[name] = it }
                }
                val found = if (wanted.isEmpty()) emptyMap() else collectByIds(root, wanted)

                // 两个 or 的左操作数都会求值，不会因为前一个成功就跳过后面的处理
                changed = applyTopBar(activity, res, found[LiteConfig.TOP_BAR_ID].orEmpty()) or changed
                changed = applyQuickCard(res, found[LiteConfig.CARD_ID].orEmpty(), root) or changed

                val adViews = ArrayList<View>()
                found[LiteConfig.AD_VIEW_ID]?.let { adViews += it }
                LiteConfig.AD_CONTAINER_ID_NAMES.forEach { name -> found[name]?.let { adViews += it } }
                changed = applyAdViews(res, adViews, root) or changed
            }
            changed
        } catch (e: Throwable) {
            XLog.e("精简逻辑异常（已吞掉，不影响酷狗运行）: $e")
            false
        }
    }

    /** 一次全树遍历收集多个 id 的 View；findViewById 只返回第一个，这里需要全部 */
    private fun collectByIds(root: ViewGroup, wanted: Map<String, Int>): Map<String, List<View>> {
        val out = wanted.keys.associateWith { ArrayList<View>() }
        walk(root) { v ->
            val id = v.id
            if (id == View.NO_ID) return@walk
            wanted.forEach { (name, wantedId) -> if (id == wantedId) out.getValue(name) += v }
        }
        return out
    }

    // ---------------------------------------------------------------- 底部导航

    /**
     * 统一的隐藏 / 恢复原语。
     *
     * 只有「本模块亲手设成 GONE」的 View 才会被恢复，酷狗自己隐藏的绝不碰，
     * 免得把人家有意藏起来的东西又给显示出来。
     */
    private fun setHidden(
        view: View,
        hide: Boolean,
        res: Resources,
        tag: String,
        labels: Set<String>
    ): Boolean {
        val name = labels.joinToString("/").ifEmpty { entryName(res, view.id) }
        if (hide) {
            if (view.visibility == View.GONE) return false
            view.visibility = View.GONE
            hiddenByUs += view
            XLog.i("$tag：已隐藏「$name」id=${entryName(res, view.id)}")
            return true
        }
        if (view.visibility != View.GONE || !hiddenByUs.contains(view)) return false
        view.visibility = View.VISIBLE
        hiddenByUs -= view
        XLog.i("$tag：规则不再命中，已恢复「$name」")
        return true
    }

    private fun applyBottomBar(activity: Activity, res: Resources): Boolean = safe("底部导航") {
        val container = findBar(
            activity, res,
            idName = LiteConfig.BOTTOM_BAR_ID,
            minChildren = 3,
            knownNames = setOf("首页", "我的") + LiteConfig.BOTTOM_HIDE_NAMES,
            requireHorizontalLinear = true
        ) ?: return@safe false

        val fallbackIds = LiteConfig.BOTTOM_HIDE_IDS.mapNotNull { idOf(res, it) }.toSet()
        var changed = false
        var hidden = 0
        var kept = 0

        for (i in 0 until container.childCount) {
            val tab = container.getChildAt(i) ?: continue
            val labels = labelsOf(tab)
            val byName = labels.any { label -> LiteConfig.BOTTOM_HIDE_NAMES.any { matchesName(label, it) } }
            val byId = tab.id != View.NO_ID && tab.id in fallbackIds
            if (byName || byId) {
                hidden++
                if (setHidden(tab, true, res, "底部导航", labels)) changed = true
            } else {
                if (setHidden(tab, false, res, "底部导航", labels)) changed = true
                // 只对可点击的子项做均分，别把底部栏里的装饰性 View 也拉伸
                if (tab.isClickable) {
                    kept++
                    if (expandToFill(tab)) changed = true
                }
            }
        }

        if (hidden == 0) {
            warnOnce(
                "bottom-no-match",
                "底部栏 ${container.javaClass.simpleName}#${entryName(res, container.id)} 里没匹配到目标 Tab" +
                        "（childCount=${container.childCount}），请对照结构快照更新 LiteConfig.BOTTOM_HIDE_NAMES"
            )
            return@safe false
        }

        // 布局若写死了 weightSum（例如 5），隐藏 3 个后剩下的只会各占 1/5，右侧留大片空白。
        // 置 0 让 LinearLayout 按「可见子 View 的权重之和」自动计算，两个 Tab 正好各占一半。
        if (container is LinearLayout && container.weightSum > 0f) {
            container.weightSum = 0f
            changed = true
            XLog.i("底部栏 weightSum 原本为固定值，已重置为自动计算")
        }
        if (changed) XLog.i("底部导航：本轮隐藏 $hidden 个、保留 $kept 个")
        changed
    }

    /** 把保留下来的 Tab 改成 width=0 + weight=1；本来就是均分的直接跳过，省一次重新布局 */
    private fun expandToFill(tab: View): Boolean {
        val lp = tab.layoutParams as? LinearLayout.LayoutParams ?: return false
        if (lp.width == 0 && lp.weight == 1f) return false
        lp.width = 0
        lp.weight = 1f
        tab.layoutParams = lp
        return true
    }

    // ------------------------------------------------------------ 顶部频道栏

    private fun applyTopBar(activity: Activity, res: Resources, raw: List<View>): Boolean = safe("顶部频道栏") {
        val keepNames = LiteConfig.TOP_KEEP_NAMES
        val locateNames = LiteConfig.TOP_LOCATE_NAMES.toSet()
        val idName = LiteConfig.TOP_BAR_ID

        // 真机上 urw 只有一个直接子 View，9 个频道埋在更深的无 id 壳里；
        // 同 id 还可能有多个实例（这个 id 被听书系列模块共用），所以逐个下钻、逐个判定
        var containers = raw.mapNotNull { (it as? ViewGroup)?.let { vg -> unwrapBar(vg, idName, 2, locateNames) } }

        if (containers.isEmpty()) {
            if (idOf(res, idName) != null) {
                // id 能解析就说明没改版，完全信任它：找不到只是频道还没填进来，
                // 交给守卫下一轮重试，千万别在这里退化到全树搜索——那会在等待期每 100ms 白跑一次
                logOnce("top-not-ready", "顶部频道栏 $idName 尚未就绪（同 id 实例 ${raw.size} 个），等待下一轮")
                // 频道已经长出来了却钻不到装子项的那一层，说明中间还套着壳。
                // 把子树打出来，否则根本不知道卡在第几层；只在过了启动期后打一次，不刷屏
                val grown = raw.filterIsInstance<ViewGroup>()
                    .filter { labelsOf(it, MAX_LABELS).any { l -> locateNames.any { matchesName(l, it) } } }
                if (grown.isNotEmpty() &&
                    SystemClock.uptimeMillis() - injectedAt >= WARN_AFTER_MS &&
                    warned.add("top-subtree")
                ) {
                    XLog.w("顶部频道栏 $idName 里已有频道文案，但没钻到装子项的那一层，子树如下：")
                    grown.forEach { dumpTree(it, 0, 4) }
                }
                return@safe false
            }
            // 只有 id 彻底解析不到（酷狗改版）才按特征在全树搜索
            val root = activity.window?.decorView as? ViewGroup
            containers = listOfNotNull(searchBar(root, 2, locateNames, requireHorizontalLinear = false))
            if (containers.isEmpty()) {
                warnOnce(
                    "top-not-found",
                    "没找到顶部频道栏（id=$idName 已失效），请对照结构快照更新 LiteConfig.TOP_BAR_ID"
                )
                return@safe false
            }
            warnOnce("top-by-feature", "id=$idName 已失效，改用特征搜索定位顶部频道栏")
        }

        var changed = false
        for (container in containers) {
            // 关键判定：@id/urw 在「我的」页也有，里面是 音乐/关注。
            // 只有容器里确实出现了白名单频道时，才认定这是首页频道栏；
            // 不是首页的那份要把之前藏起来的恢复回去，因为酷狗可能复用同一批子 View。
            val childLabels = (0 until container.childCount).map { labelsOf(container.getChildAt(it)) }
            val isHomeBar = childLabels.any { labels -> keepNames.any { keep -> labels.any { matchesName(it, keep) } } }

            var hidden = 0
            for (i in childLabels.indices) {
                val child = container.getChildAt(i) ?: continue
                val labels = childLabels[i]
                val keep = keepNames.any { name -> labels.any { matchesName(it, name) } }
                val shouldHide = isHomeBar && !keep
                if (shouldHide) hidden++
                if (setHidden(child, shouldHide, res, "顶部频道栏", labels)) changed = true
            }
            if (hidden > 0) {
                logOnce(
                    "top-$isHomeBar-$hidden",
                    "顶部频道栏 ${container.javaClass.simpleName}：首页频道栏=$isHomeBar，" +
                            "隐藏 $hidden 个、保留 ${keepNames.joinToString("/")}"
                )
            }
        }
        changed
    }

    // -------------------------------------------------------- 首页快捷入口卡片

    private fun applyQuickCard(res: Resources, raw: List<View>, root: ViewGroup): Boolean = safe("快捷入口卡片") {
        val keywords = LiteConfig.CARD_MATCH_KEYWORDS
        var candidates = raw
        if (candidates.isNotEmpty()) cardLocatedById = true

        // 只有在 id 彻底定位不到（酷狗改版）时才退化成全树找 RecyclerView；
        // 一旦成功定位过就别再搜了——全树扫描每 100ms 跑一次代价太大
        if (candidates.isEmpty() && !cardLocatedById) {
            val scrollables = ArrayList<View>()
            collectMatching(root, scrollables) { isRecyclerLike(it) }
            candidates = scrollables
            if (scrollables.isNotEmpty()) {
                warnOnce(
                    "card-by-class",
                    "按 ${LiteConfig.CARD_ID} 没定位到快捷入口卡片，已改用 RecyclerView 特征匹配（候选 ${scrollables.size} 个）"
                )
            }
        }

        if (candidates.isEmpty()) {
            if (idOf(res, LiteConfig.CARD_ID) == null) {
                warnOnce(
                    "card-not-found",
                    "没找到快捷入口卡片（id=${LiteConfig.CARD_ID} 已失效），请对照结构快照更新 LiteConfig.CARD_ID"
                )
            } else {
                logOnce("card-not-ready", "快捷入口卡片 ${LiteConfig.CARD_ID} 尚未就绪，等待下一轮")
            }
            return@safe false
        }

        // 同 id 会被「音频·听书」卡片（TingCardRecycleView）复用，必须按文案区分；
        // 没命中的那个交给 setHidden 恢复
        var changed = false
        var hidden = 0
        for (view in candidates) {
            if (!matchesKeywords(view, keywords)) {
                if (setHidden(view, false, res, "快捷入口卡片", labelsOf(view))) changed = true
                continue
            }
            hidden++
            // 往上爬到真正该收掉的那一层：只 GONE 卡片自己，外壳的 margin/padding 会留下一条空白
            val target = climbToHideTarget(view, res)
            if (setHidden(view, true, res, "快捷入口卡片", labelsOf(view))) changed = true
            if (target !== view && setHidden(target, true, res, "快捷入口卡片（外壳）", labelsOf(view))) {
                changed = true
            }
        }

        if (hidden == 0) {
            warnOnce(
                "card-no-keyword",
                "找到 ${candidates.size} 个候选卡片，但没有一个命中 " +
                        "${keywords.joinToString("/")}，请对照结构快照更新 LiteConfig.CARD_MATCH_KEYWORDS"
            )
        }
        changed
    }

    /**
     * 从卡片往上找真正该隐藏的那一层。
     *
     * 只 GONE 卡片本身会留下一条空白：它外面通常还套着「卡片壳」（背景 + 内边距 + 可能的标题行），
     * 壳的高度虽然写的是 WRAP_CONTENT，但壳自己的 margin 和 padding 不会跟着消失。
     *
     * 往上爬的三道护栏，任何一道命中就停：
     *  - 超过 [LiteConfig.CARD_MAX_CLIMB] 层，或者祖先还没参与过布局（尺寸全 0，此时判不准）
     *  - 祖先是列表容器（RecyclerView），那是整页骨架，不能动
     *  - 祖先里「真正占着地方」的兄弟 View 超过 [LiteConfig.CARD_CLIMB_MAX_SIBLINGS] 个，
     *    或者兄弟子树里出现 [LiteConfig.CARD_PROTECT_NAMES]，说明这一层混装了别的功能
     *    （首页 item @id/g2p0 里账户信息 @id/bxu0 就和卡片壳并列）
     */
    private fun climbToHideTarget(card: View, res: Resources): View {
        var target = card
        var hop = 0
        while (hop < LiteConfig.CARD_MAX_CLIMB) {
            val parent = target.parent as? ViewGroup ?: break
            if (isRecyclerLike(parent)) break
            // 还没布局完的时候所有尺寸都是 0，兄弟护栏会把什么都拦下来，
            // 这一轮先只隐藏卡片自己，下一轮布局完了再爬
            if (parent.width == 0 && parent.height == 0 && parent.visibility != View.GONE) break

            // 只数占着地方的兄弟：真机上卡片壳 @id/ei31 有 5 个子 View，
            // 其中 4 个是 0x0 的 GONE ViewStub，算进去就永远爬不上去
            val occupying = (0 until parent.childCount)
                .mapNotNull { parent.getChildAt(it) }
                .filter { it !== target }
                .filter { it.visibility != View.GONE && it.width > 0 && it.height > 0 }
            if (occupying.size > LiteConfig.CARD_CLIMB_MAX_SIBLINGS) break

            val touchesProtected = occupying.any { other ->
                labelsOf(other, MAX_LABELS).any { label ->
                    LiteConfig.CARD_PROTECT_NAMES.any { label.contains(it) }
                }
            }
            if (touchesProtected) break
            logOnce(
                "climb-$hop",
                "快捷入口卡片：从 #${entryName(res, card.id)} 上爬第 ${hop + 1} 层到 " +
                        "${parent.javaClass.simpleName}#${entryName(res, parent.id)}" +
                        "（占位兄弟 ${occupying.size} 个）"
            )
            target = parent
            hop++
        }
        return target
    }

    private fun matchesKeywords(view: View, keywords: List<String>): Boolean {
        val labels = labelsOf(view, MAX_LABELS)
        return keywords.any { keyword -> labels.any { it.contains(keyword) } }
    }

    // ------------------------------------------------------------ 信息流广告

    /**
     * 隐藏信息流广告（[LiteConfig.AD_VIEW_ID] / [LiteConfig.AD_CONTAINER_ID_NAMES] / [LiteConfig.AD_CONTAINER_CLASS_HINTS]）。
     *
     * 两条定位路径互补：混淆 id 命中快，类名兜底稳——`AdMonitorLinearLayout`
     * 是酷狗自己的广告监控容器，类名没混淆，比 `clw1` 这种 id 活得久。
     *
     * 广告挂在 RecyclerView 的 item 里，滚走会被回收、滚回来重新绑定，
     * 所以和快捷卡片一样不能一次性设 GONE，必须交给常驻守卫每轮重扫；
     * 也正因为反复设置，[setHidden] 才要记归属、能恢复。
     */
    private fun applyAdViews(res: Resources, byId: List<View>, root: ViewGroup): Boolean = safe("信息流广告") {
        val candidates = LinkedHashSet<View>(byId)
        if (candidates.isEmpty()) {
            // id 解析不到（酷狗改版）才按类名全树兜底。
            // 守卫每 100ms 跑一轮，id 有效时再走一遍全树是纯粹浪费
            walk(root) { v ->
                val name = v.javaClass.name
                if (LiteConfig.AD_CONTAINER_CLASS_HINTS.any { name.contains(it) }) candidates += v
            }
            if (candidates.isNotEmpty()) {
                warnOnce(
                    "ad-by-class",
                    "按 id=${LiteConfig.AD_VIEW_ID} 没定位到广告容器，已改用类名匹配" +
                            "（${LiteConfig.AD_CONTAINER_CLASS_HINTS.joinToString("/")}，" +
                            "命中 ${candidates.size} 个）"
                )
            }
        }
        if (candidates.isEmpty()) return@safe false

        var changed = false
        var matched = 0
        for (view in candidates) {
            matched++
            val labels = labelsOf(view, 6)
            if (setHidden(view, true, res, "信息流广告", labels)) changed = true

            if (!LiteConfig.AD_HIDE_SINGLE_CHILD_WRAPPER) continue
            val wrapper = view.parent as? ViewGroup ?: continue
            // 只收「纯包装」：整层就这一个子 View，且它不是列表容器本身。
            // childCount > 1 的容器可能还混装正经内容，绝不能顺着往上收
            if (wrapper.childCount != 1 || isRecyclerLike(wrapper)) continue
            if (setHidden(wrapper, true, res, "信息流广告（外壳）", labels)) changed = true
        }
        if (changed) logOnce("ad-hidden", "信息流广告：命中 $matched 处，已隐藏")
        changed
    }

    // ---------------------------------------------------------------- 通用工具

    /**
     * 找一个横向排列的「栏」容器。
     *
     * 先用 id 精确定位；id 失效时按特征在全树搜索：
     * [requireHorizontalLinear] 为 true 时只认横向 LinearLayout（底部导航），
     * 为 false 时放宽到任意可横向滚动的 ViewGroup（顶部频道栏是 HorizontalScrollView）。
     */
    private fun findBar(
        activity: Activity,
        res: Resources,
        idName: String,
        minChildren: Int,
        knownNames: Set<String>,
        requireHorizontalLinear: Boolean
    ): ViewGroup? {
        idOf(res, idName)?.let { id ->
            val view = activity.findViewById<View>(id)
            // id 能解析就说明没改版，完全信任它：
            // 容器为空或压根没找到，都只是「内容还没填进来」，交给守卫下一轮重试，
            // 千万别在这里退化到全树搜索——那会在等待期每 100ms 白跑一次
            return if (view is ViewGroup) unwrapBar(view, idName, minChildren, knownNames) else null
        }
        // 只有 id 彻底解析不到（酷狗改版）才按特征在全树搜索
        val root = activity.window?.decorView as? ViewGroup ?: return null
        return searchBar(root, minChildren, knownNames, requireHorizontalLinear)
    }

    /**
     * 往下钻到「真正装着子项的那一层」。
     *
     * 真机结构比想象的绕：urw → LinearLayout#none（1 个子）→ FrameLayout（2 个子：
     * 频道容器 LinearLayout#ful1 + 一个 GONE 的指示器）→ 9 个频道项。
     * 所以「只在 childCount == 1 时下钻」会卡在 FrameLayout（它有 2 个子），
     * 「数够 minChildren 就收手」也会停在壳上，两种写法都错过真正的频道层。
     *
     * 唯一可靠的判据是文案：给每一层打分（直接子 View 里有多少个带 [knownNames]），
     * 在整个子树里取分数最高的那一层。频道容器能拿到 9 分，外层壳最多 1 分
     * （它的单个子 View 把整棵子树的文案全带上了，而打分只看一层）。
     *
     * @return 得分最高的容器；全子树一个文案都没匹上时退到最浅的达标层，都不存在则返回 null
     */
    private fun unwrapBar(
        container: ViewGroup,
        idName: String,
        minChildren: Int,
        knownNames: Set<String>
    ): ViewGroup? {
        var best: ViewGroup? = null
        var bestScore = 0
        // 一个文案都没匹上时的保底（酷狗可能改了文案）：最浅的子 View 数达标层
        var fallback: ViewGroup? = null
        var budget = UNWRAP_NODE_BUDGET

        fun visit(node: ViewGroup, depth: Int) {
            if (depth > MAX_UNWRAP_DEPTH || budget <= 0) return
            budget--
            if (node.childCount >= minChildren) {
                val score = namedChildren(node, knownNames)
                // 严格大于 + DFS 先序：同分时取最浅的那一层
                if (score > bestScore) {
                    bestScore = score
                    best = node
                }
                if (fallback == null) fallback = node
            }
            for (i in 0 until node.childCount) {
                val child = node.getChildAt(i) as? ViewGroup ?: continue
                visit(child, depth + 1)
            }
        }

        visit(container, 0)

        val picked = if (bestScore > 0) best else fallback
        if (picked != null && picked !== container) {
            logOnce(
                "unwrap-$idName",
                "$idName（${container.javaClass.simpleName}）往下钻到 " +
                        "${picked.javaClass.simpleName}#${entryName(container.resources, picked.id)}" +
                        " childCount=${picked.childCount}，其中 $bestScore 个带已知文案"
            )
        }
        return picked
    }

    /** 直接子 View 里有多少个带已知文案（只看一层，不递归） */
    private fun namedChildren(vg: ViewGroup, knownNames: Set<String>): Int {
        var named = 0
        for (i in 0 until vg.childCount) {
            val child = vg.getChildAt(i) ?: continue
            if (labelsOf(child).any { label -> knownNames.any { matchesName(label, it) } }) named++
        }
        return named
    }

    private fun searchBar(
        root: ViewGroup?,
        minChildren: Int,
        knownNames: Set<String>,
        requireHorizontalLinear: Boolean
    ): ViewGroup? {
        if (root == null || root.visibility == View.GONE) return null
        if (looksLikeBar(root, minChildren, knownNames, requireHorizontalLinear)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is ViewGroup) {
                searchBar(child, minChildren, knownNames, requireHorizontalLinear)?.let { return it }
            }
        }
        return null
    }

    private fun looksLikeBar(
        vg: ViewGroup,
        minChildren: Int,
        knownNames: Set<String>,
        requireHorizontalLinear: Boolean
    ): Boolean {
        if (vg.childCount < minChildren) return false
        val typeOk = if (requireHorizontalLinear) {
            vg is LinearLayout && vg.orientation == LinearLayout.HORIZONTAL
        } else {
            vg is HorizontalScrollView || (vg is LinearLayout && vg.orientation == LinearLayout.HORIZONTAL)
        }
        if (!typeOk) return false

        var named = 0
        for (i in 0 until vg.childCount) {
            val child = vg.getChildAt(i) ?: return false
            if (!child.isClickable) return false
            if (labelsOf(child).any { label -> knownNames.any { matchesName(label, it) } }) named++
        }
        return named >= 2
    }

    /** 取一个 View 子树里所有可识别文案：各级 content-desc + 各级 TextView 文本 */
    private fun labelsOf(v: View?, limit: Int = MAX_LABELS): Set<String> {
        val out = LinkedHashSet<String>()
        if (v != null) collectLabels(v, out, limit)
        return out
    }

    private fun collectLabels(v: View, out: MutableSet<String>, limit: Int) {
        if (out.size >= limit) return
        v.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
        if (v is TextView) {
            v.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                if (out.size >= limit) return
                v.getChildAt(i)?.let { collectLabels(it, out, limit) }
            }
        }
    }

    /** 收集子树里所有指定 id 的 View（findViewById 只返回第一个，这里需要全部） */
    private fun collectById(root: ViewGroup, id: Int): List<View> {
        val out = ArrayList<View>()
        walk(root) { if (it.id == id) out += it }
        return out
    }

    private fun collectMatching(root: ViewGroup, out: MutableList<View>, predicate: (View) -> Boolean) {
        walk(root) { if (predicate(it)) out += it }
    }

    /** 酷狗可能用了 RecyclerView 子类，类名不一定含 RecyclerView，所以往上翻父类链 */
    private fun isRecyclerLike(v: View): Boolean {
        var clazz: Class<*>? = v.javaClass
        var depth = 0
        while (clazz != null && depth < 6) {
            if (clazz.name.contains("RecyclerView")) return true
            clazz = clazz.superclass
            depth++
        }
        return false
    }

    private fun walk(root: View, action: (View) -> Unit) {
        action(root)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                root.getChildAt(i)?.let { walk(it, action) }
            }
        }
    }

    /**
     * 名字匹配，容忍无障碍描述里的状态后缀。
     * 例如选中的频道，TextView 的 content-desc 是「推荐已选中」而不是「推荐」。
     */
    private fun matchesName(label: String, name: String): Boolean {
        if (label.equals(name, ignoreCase = true)) return true
        return label.removeSuffix("已选中").trim().equals(name, ignoreCase = true)
    }

    /** 解析资源 id，结果缓存；解析不到返回 null 并只告警一次 */
    private fun idOf(res: Resources, name: String): Int? {
        val cached = idCache[name]
        if (cached != null) return if (cached == 0) null else cached
        val id = res.getIdentifier(name, "id", LiteConfig.TARGET_PACKAGE)
        idCache[name] = id
        if (id == 0) {
            XLog.w("资源 id 未找到: $name（酷狗版本可能已变更，将改用特征搜索）")
            return null
        }
        XLog.i("资源 id 解析: $name -> $id")
        return id
    }

    private inline fun safe(tag: String, block: () -> Boolean): Boolean = try {
        block()
    } catch (e: Throwable) {
        XLog.e("$tag 处理异常（已吞掉，不影响酷狗运行）: $e")
        false
    }

    private fun warnOnce(key: String, message: String) {
        if (SystemClock.uptimeMillis() - injectedAt < WARN_AFTER_MS) return
        if (warned.add(key)) XLog.w(message)
    }

    /** 同一件事只说一次，给那些会被 100ms 一轮反复触发的信息日志用 */
    private fun logOnce(key: String, message: String) {
        if (warned.add(key)) XLog.i(message)
    }

    // ---------------------------------------------------------------- 诊断输出

    /** 把三处目标的结构打进日志，酷狗改版时照着改 LiteConfig 就行 */
    private fun dumpStructure(activity: Activity) {
        try {
            val res = activity.resources
            val version = runCatching {
                activity.packageManager.getPackageInfo(LiteConfig.TARGET_PACKAGE, 0).versionName
            }.getOrNull()

            XLog.i("========== 结构快照 ==========")
            XLog.i("酷狗版本: ${version ?: "未知"}  Activity: ${activity.javaClass.name}")

            dumpBar(
                activity, res, "底部导航", LiteConfig.BOTTOM_BAR_ID,
                setOf("首页", "我的") + LiteConfig.BOTTOM_HIDE_NAMES
            )
            dumpBar(
                activity, res, "顶部频道栏", LiteConfig.TOP_BAR_ID,
                LiteConfig.TOP_LOCATE_NAMES.toSet()
            )
            dumpCards(activity, res)

            XLog.i("---- 内容区顶层（深度 3）----")
            activity.findViewById<ViewGroup>(android.R.id.content)?.let { dumpTree(it, 0, 3) }
            if (LiteConfig.DUMP_FULL_TREE) {
                XLog.i("---- 全量视图树（诊断用，定位广告容器后应关闭 DUMP_FULL_TREE）----")
                (activity.window?.decorView as? ViewGroup)?.let { dumpTree(it, 0, 7) }
            }
            XLog.i("==============================")
        } catch (e: Throwable) {
            XLog.e("结构快照输出失败: $e")
        }
    }

    private fun dumpBar(
        activity: Activity,
        res: Resources,
        title: String,
        idName: String,
        knownNames: Set<String>
    ) {
        val root = activity.window?.decorView as? ViewGroup
        val id = idOf(res, idName)
        // 不能用 findViewById：它只给第一个，而同 id 可能存在多个实例（urw 就是）
        val instances = if (id != null && root != null) collectById(root, id) else emptyList()
        if (instances.isEmpty()) {
            XLog.w("$title: 未找到容器（id=$idName 解析=${id ?: "失败"}）")
            return
        }
        XLog.i("$title: id=$idName 同 id 实例 ${instances.size} 个")
        instances.forEachIndexed { index, raw ->
            if (raw !is ViewGroup) {
                XLog.w("  [$index] 不是 ViewGroup: ${raw.javaClass.name}")
                return@forEachIndexed
            }
            // 快照要展示真正装着子项的那一层，所以走一遍和隐藏逻辑完全相同的下钻
            val view = unwrapBar(raw, idName, 2, knownNames)
            if (view == null) {
                XLog.w("  [$index] ${raw.javaClass.name}#$idName 钻不到装子项的那一层，子树如下：")
                dumpTree(raw, 0, 4)
                return@forEachIndexed
            }
            val extra = if (view is LinearLayout) " weightSum=${view.weightSum}" else ""
            val descended = if (view === raw) "" else "（已从 $idName 下钻）"
            XLog.i(
                "  [$index] ${view.javaClass.name}#$idName$descended" +
                        " childCount=${view.childCount}$extra ${boxOf(view)}"
            )
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i) ?: continue
                XLog.i(
                    "      [$i] ${child.javaClass.simpleName}#${entryName(res, child.id)}" +
                            " vis=${visibilityName(child.visibility)} ${child.width}x${child.height}" +
                            " labels=${labelsOf(child, 6).joinToString("/").take(50)}"
                )
            }
        }
    }

    private fun dumpCards(activity: Activity, res: Resources) {
        val root = activity.window?.decorView as? ViewGroup ?: return
        val id = idOf(res, LiteConfig.CARD_ID)
        val matches = if (id != null) collectById(root, id) else emptyList()
        XLog.i("快捷入口卡片: id=${LiteConfig.CARD_ID} 解析=${id ?: "失败"}，同 id 实例 ${matches.size} 个")
        matches.forEachIndexed { index, view ->
            val hit = matchesKeywords(view, LiteConfig.CARD_MATCH_KEYWORDS)
            val brief = labelsOf(view, MAX_LABELS).joinToString("/").take(80)
            XLog.i(
                "  [$index] ${view.javaClass.simpleName}#${entryName(res, view.id)}" +
                        " vis=${visibilityName(view.visibility)} 命中=$hit ${boxOf(view)} labels=$brief"
            )
            if (!hit) return@forEachIndexed
            // 命中的那个把祖先链打出来：隐藏后留下的空白只可能来自这几层的
            // padding / margin，或者某一层写死了高度，光看卡片自己判断不出来
            val target = climbToHideTarget(view, res)
            var node: View = view
            for (level in 0 until 4) {
                val parent = node.parent as? View ?: break
                val mark = if (parent === target) "  ← 隐藏目标" else ""
                XLog.i(
                    "      上$level ${parent.javaClass.simpleName}#${entryName(res, parent.id)}" +
                            " vis=${visibilityName(parent.visibility)} ${boxOf(parent)}$mark"
                )
                dumpSiblings(parent as? ViewGroup, node, res)
                node = parent
            }
        }
    }

    /** 打出同层兄弟的尺寸：空白到底是哪一层贡献的，对比一下高度就清楚了 */
    private fun dumpSiblings(parent: ViewGroup?, self: View, res: Resources) {
        if (parent == null) return
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i) ?: continue
            if (child === self) continue
            XLog.i(
                "          兄弟[$i] ${child.javaClass.simpleName}#${entryName(res, child.id)}" +
                        " vis=${visibilityName(child.visibility)} ${boxOf(child)}" +
                        " labels=${labelsOf(child, 8).joinToString("/").take(60)}"
            )
        }
    }

    /** 尺寸 + 内边距 + 外边距 + LayoutParams，排查「隐藏后留空白」全靠这几项 */
    private fun boxOf(v: View): String {
        val lp = v.layoutParams
        val margin = (lp as? ViewGroup.MarginLayoutParams)?.let {
            " margin=${it.leftMargin},${it.topMargin},${it.rightMargin},${it.bottomMargin}"
        } ?: ""
        return "${v.width}x${v.height} pad=${v.paddingLeft},${v.paddingTop},${v.paddingRight},${v.paddingBottom}" +
                "$margin lp=${lp?.javaClass?.simpleName} w=${lp?.width} h=${lp?.height}"
    }

    private fun dumpTree(vg: ViewGroup, depth: Int, maxDepth: Int) {
        if (depth >= maxDepth) return
        val res = vg.resources
        for (i in 0 until vg.childCount) {
            val child = vg.getChildAt(i) ?: continue
            // childCount 必须打：中间套了几层壳、子项到底挂在哪一层，全靠它才能看出来
            XLog.i(
                "  ".repeat(depth + 1) + "${child.javaClass.simpleName}#${entryName(res, child.id)}" +
                        " vis=${visibilityName(child.visibility)} ${child.width}x${child.height}" +
                        " children=${if (child is ViewGroup) child.childCount else 0}" +
                        " labels=${labelsOf(child, 4).joinToString("/").take(30)}"
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
