package com.kai.kghook.hook

import com.kai.kghook.LiteConfig
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed 模块入口。
 *
 * 类的全限定名登记在 `src/main/assets/xposed_init`，框架启动宿主进程时按该文件反射实例化，
 * 因此这个类和它的包名一旦改动，必须同步更新 xposed_init 与 keepRules/rules.keep。
 */
class HookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            LiteConfig.TARGET_PACKAGE -> KugouUiHook.install(lpparam)
            SELF_PACKAGE -> hookSelf(lpparam)
        }
    }

    /**
     * 把 [com.kai.kghook.ModuleStatus.isModuleActive] 的返回值改成 true，
     * 模块自己的界面就能显示“已激活”。需要在 LSPosed 作用域里同时勾选 kghook 自身。
     */
    private fun hookSelf(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "com.kai.kghook.ModuleStatus",
                lpparam.classLoader,
                "isModuleActive",
                XC_MethodReplacement.returnConstant(true)
            )
            XLog.i("已注入模块自身，界面将显示“已激活”")
        }.onFailure {
            XLog.e("注入模块自身失败: $it")
        }
    }

    private companion object {
        const val SELF_PACKAGE = "com.kai.kghook"
    }
}
