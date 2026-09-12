package com.kai.kghook.hook

import android.util.Log
import de.robv.android.xposed.XposedBridge

/**
 * 双通道日志：一份进 LSPosed 日志，一份进宿主进程 logcat（adb logcat -s kghook）。
 *
 * 酷狗的混淆资源 id 每个版本都可能变，模块“看起来启用了却没效果”时，
 * 唯一的排查手段就是这里的输出，所以任何失败路径都必须留下痕迹。
 */
internal object XLog {

    private const val TAG = "kghook"

    fun i(msg: String) {
        dispatch(msg, false)
    }

    fun w(msg: String) {
        dispatch("[WARN] $msg", false)
    }

    fun e(msg: String) {
        dispatch("[ERROR] $msg", true)
    }

    private fun dispatch(msg: String, error: Boolean) {
        runCatching { XposedBridge.log("$TAG: $msg") }
        runCatching {
            if (error) Log.e(TAG, msg) else Log.i(TAG, msg)
        }
    }
}
