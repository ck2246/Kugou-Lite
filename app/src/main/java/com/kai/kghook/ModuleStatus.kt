package com.kai.kghook

/**
 * 模块激活状态探针。
 *
 * 自身永远返回 false；当 LSPosed 把本模块注入到 kghook 自己的进程后，
 * [com.kai.kghook.hook.HookEntry] 会把该方法替换成返回 true，界面据此显示激活状态。
 */
object ModuleStatus {

    fun isModuleActive(): Boolean = false
}
