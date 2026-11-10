package com.kai.kghook

/**
 * 精简规则配置。
 *
 * 放在非 hook 包下，模块界面也能读取展示，不会因此在 UI 线程触发 Xposed 类加载。
 *
 * 原则：**文案优先于混淆 id**。文案（推荐/乐库/猜你喜欢…）是产品功能名，跨版本稳定；
 * 混淆 id（urw、e3w1、d3y…）每次发版都可能变，只用来加速定位，找不到时一律按特征兜底搜索。
 */
object LiteConfig {

    const val TARGET_PACKAGE = "com.kugou.android"

    const val TARGET_APP_NAME = "酷狗音乐"

    /**
     * 主界面 Activity。
     *
     * 开屏改道和界面精简都指向它，酷狗改版换主界面时只改这一处。
     */
    const val MAIN_ACTIVITY = "com.kugou.android.app.MediaActivity"

    /** 主界面候选类名；酷狗改版后往这里追加，其它逻辑不用动 */
    val ACTIVITY_CANDIDATES = listOf(MAIN_ACTIVITY)

    // ---------------- 开屏广告 ----------------

    /**
     * 是否跳过开屏广告。
     *
     * 打开后，从桌面启动时第一个活动直接是 [MAIN_ACTIVITY]，开屏页连同广告都不出现。
     */
    const val SKIP_SPLASH_AD = true

    /**
     * 开屏页候选类名。
     *
     * 真机界面分析抓到的启动类是 `...splash.foresplash.ForeNoBgSplashActivity`，
     * 同包下通常还有带背景图的 ForeSplashActivity 和旧版 SplashActivity。
     */
    val SPLASH_CANDIDATES = listOf(
        "com.kugou.android.app.splash.foresplash.ForeNoBgSplashActivity",
        "com.kugou.android.app.splash.foresplash.ForeSplashActivity",
        "com.kugou.android.app.splash.SplashActivity",
    )

    // ---------------- 底部导航 ----------------

    /** 底部栏容器，这个 id 没被混淆，是最稳的锚点 */
    const val BOTTOM_BAR_ID = "bottom_tab_layout"

    /** 要隐藏的底部 Tab 文案 */
    val BOTTOM_HIDE_NAMES = listOf("视频", "K歌", "福利")

    /** 保留的底部 Tab，仅用于界面展示 */
    val BOTTOM_KEEP_NAMES = listOf("首页", "我的")

    /** 当前版本的混淆 id 兜底：d3y=视频、d3u=K歌、d3t=福利 */
    val BOTTOM_HIDE_IDS = listOf("d3y", "d3u", "d3t")

    // ---------------- 首页顶部频道栏 ----------------

    /**
     * 频道栏容器 id，混淆名。
     *
     * 真机日志里它的类名是 `TingMainSwipeTabScrollContainer`（“Ting”=听书系列），
     * 说明这个 id 在酷狗内部被多个模块共用，同一时刻可能存在多个实例，
     * 所以定位时必须把同 id 的实例全收集回来逐个判定，不能只用 findViewById 拿第一个。
     */
    const val TOP_BAR_ID = "urw"

    /**
     * 频道白名单：只保留这些，其余（听书/儿童/AI唱/刷歌/听播/AI写歌/短剧…）全部隐藏。
     * 用白名单而不是黑名单，酷狗以后新增频道会被自动隐藏，不用改代码。
     */
    val TOP_KEEP_NAMES = listOf("推荐", "乐库")

    /**
     * 仅用于「认出哪一层才是真正装着频道项的容器」，不参与隐藏判定。
     *
     * 真机上 urw 里面套了好几层无 id 的壳（ScrollView → LinearLayout → …），
     * 只能靠子 View 里出现的频道文案来判断钻到没到位；这里列得越全越好认，
     * 隐藏与否一律只看 [TOP_KEEP_NAMES]。
     */
    val TOP_LOCATE_NAMES = listOf(
        "推荐", "乐库", "听书", "儿童", "AI唱", "刷歌", "听播", "AI写歌", "短剧"
    )

    // ---------------- 首页快捷入口卡片 ----------------

    /**
     * 卡片内容容器（RecyclerView）的混淆 id。
     *
     * 真机日志证实酷狗复用了这个 id：首页快捷卡片是 `QuickCardRecycleView#e3w1`，
     * 「音频·听书」卡片是 `TingCardRecycleView#e3w1`，
     * 所以只按 id 匹配会误伤，必须再用下面的文案过滤。
     */
    const val CARD_ID = "e3w1"

    /** 子树里命中任意一个文案才隐藏，用来把「猜你喜欢」卡片和同 id 的听书卡片区分开 */
    val CARD_MATCH_KEYWORDS = listOf("猜你喜欢", "每日推荐", "排行榜", "百万收藏")

    /**
     * 隐藏卡片时从卡片本身往上一起收掉的层数。
     *
     * 只 GONE 卡片自己会留空白：它外面还套着「卡片壳」（背景 + 内边距 + 可能的标题行），
     * 壳的高度虽然是 WRAP_CONTENT，但 margin 和 padding 不会跟着消失。
     */
    const val CARD_MAX_CLIMB = 2

    /**
     * 往上爬时，祖先里「真正占着地方」的兄弟 View 超过这个数就停手。
     *
     * 只数 `vis != GONE && width > 0 && height > 0` 的：真机上卡片壳 @id/ei31 有 5 个子 View，
     * 但其中 4 个是 0x0 的 GONE ViewStub（酷狗预留的占位），把它们算进去就永远爬不上去，
     * 壳自己的 32px 上边距会留下一条空白。
     *
     * 首页 item @id/g2p0 里账户信息（@id/bxu0，1272x91）和卡片壳是并列的，
     * 这一层会被这道护栏加上 [CARD_PROTECT_NAMES] 一起拦住，账户信息不会被吃掉。
     */
    const val CARD_CLIMB_MAX_SIBLINGS = 3

    /** 祖先的其它子树里出现这些文案就停手，这是第二道护栏，防止误伤账户区 */
    val CARD_PROTECT_NAMES = listOf("会员中心", "免费领会员券", "登录", "消息", "设置")

    // ---------------- 信息流广告 ----------------

    /** 评论流广告容器 id（真机值 0x7f0aa526，反编译对应 AdMonitorLinearLayout） */
    const val AD_VIEW_ID = "clw1"

    /**
     * TME 广告位容器 id（真机视图树 + aapt2 dump resources 交叉确认）。
     *
     * 首页 banner 的真实层级是
     * `RecyclerView > FrameLayout > tme_ad_container > FrameLayout > LinearLayout(「广告」角标)`，
     * 所以隐藏 `tme_ad_container` 就能把图片、标题、角标整块收掉。
     * 这两个 id 是语义命名、没被混淆，比按类名找稳得多。
     */
    val AD_CONTAINER_ID_NAMES = listOf(
        "tme_ad_container",
        "tme_ad_loading_next_ad_container",
    )

    /** 广告容器的类名特征（id 解析不到时的兜底） */
    val AD_CONTAINER_CLASS_HINTS = listOf(
        "AdMonitorLinearLayout",
        "DynamicRelativeLayout",
        "MineVipAdvertisingView",
    )

    /** 只装一个子 View 的纯包装外壳是否一并收掉，避免留白 */
    const val AD_HIDE_SINGLE_CHILD_WRAPPER = true

    // ---------------- 广告曝光监测 ----------------

    /** 曝光监测控件类名（未混淆，比 id 稳） */
    const val AD_MONITOR_CLASS =
        "com.kugou.android.netmusic.discovery.advertise.widget.AdMonitorLinearLayout"

    /** 起 1 秒轮询的私有方法（混淆名），清空它可省掉每秒探测与日志 */
    const val AD_MONITOR_TICK_METHOD = "l"

    /** 可见比例计算（未混淆），兜底恒返回 0 */
    const val AD_MONITOR_PERCENT_METHOD = "getGlobalVisiblePercent"

    /** 曝光回调注册方法；全仓库唯一调用点在 SongCommentAdProcessor.u */
    const val AD_MONITOR_OBSERVER_METHOD = "setGlobalVisibilityObserver"

    /** 是否把曝光监听器替换成空实现（必须换不能置空，控件里调用无空判断） */
    const val AD_MONITOR_MUTE_OBSERVER = true

    // ---------------- 广告总开关（TME SDK）----------------

    /**
     * 是否从腾讯音乐广告 SDK 的判定处把所有广告位判成关闭。
     *
     * 门面类 `com.tencentmusic.ad.TMEAds` 提供按广告位的布尔判定，
     * 改成恒 false 等于告诉酷狗"这些广告位都没开"。
     */
    const val AD_DISABLE_TME = true

    /** TME 广告 SDK 门面类 */
    const val TME_FACADE_CLASS = "com.tencentmusic.ad.TMEAds"

    /** 需要判成 false 的布尔判定（只回答"能不能用"，不破坏数据结构） */
    val TME_FALSE_GATES = listOf(
        "checkPosIdOpen(Ljava/lang/String;)Z",
        "checkSplashInterval(Ljava/lang/String;Z)Z",
        "getDynamicFloorEcpmStatus(Ljava/lang/String;)Z",
    )

    /**
     * 诊断开关：界面长齐后把整棵视图树打进日志。
     *
     * 只在排查"某个广告位到底是什么控件"时临时打开，定位完必须关掉（日志会暴涨）。
     */
    const val DUMP_FULL_TREE = false

    // ---------------- 开屏广告 Dialog ----------------

    /**
     * 开屏广告 Dialog 类（混淆名）。
     *
     * 真机 + 反编译确认：`rc.a extends android.app.Dialog`，由 SplashActivity 弹出，
     * 方法只有 `show()` / `dismiss()` / `onAttachedToWindow()` 这一套。
     * 也就是说开屏广告**不是一个 Activity，而是盖在开屏页上的全屏 Dialog**——
     * 这正是之前按 Activity 类名怎么 hook 都拦不住的原因。
     */
    val SPLASH_DIALOG_CANDIDATES = listOf("rc.a")

    /**
     * 是否用「基类 Activity.onCreate + 类名含 splash」做通用改道。
     *
     * 酷狗的清单声明了 `com.kugou.android.app.splash.SplashActivity`，
     * 但模块注入时 `loadClass` 拿不到它（插件化/壳机制），按名 hook 会落空。
     * 挂在框架基类上就不依赖具体类能否提前加载，运行时按实例的真实类名判断。
     */
    const val SPLASH_BY_CLASS_NAME_HINT = true

    /** 通用改道用的类名特征（小写匹配） */
    const val SPLASH_CLASS_NAME_HINT = "splash"

    /** 命中这些子串的 Activity 不改道：车机开屏属于另一条业务线，改了会让车机模式起不来 */
    val SPLASH_CLASS_NAME_SKIP = listOf("hicar")
}
