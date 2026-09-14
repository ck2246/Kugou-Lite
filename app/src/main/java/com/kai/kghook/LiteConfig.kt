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

    /** 主界面 Activity；酷狗改版后往这里追加候选类名即可，其它逻辑不用动 */
    val ACTIVITY_CANDIDATES = listOf(
        "com.kugou.android.app.MediaActivity"
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
}
