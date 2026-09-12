package com.kai.kghook

/**
 * 模块行为配置。
 *
 * 单独抽出来放在非 hook 包下，让模块界面也能读取展示，
 * 而不会因此在 UI 线程触发 Xposed 相关类的加载。
 */
object TabConfig {

    /** 目标应用包名，同时用于 LSPosed 作用域校验 */
    const val TARGET_PACKAGE = "com.kugou.android"

    const val TARGET_APP_NAME = "酷狗音乐"

    /** 要隐藏的底部 Tab（按 content-desc / 文本匹配，不依赖混淆 id） */
    val HIDE_TAB_NAMES = listOf("视频", "K歌", "福利")

    /** 保留下来的底部 Tab */
    val KEEP_TAB_NAMES = listOf("首页", "我的")
}
