package com.kai.kghook.hook

import com.kai.kghook.LiteConfig
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 广告总开关：在腾讯音乐广告 SDK 的判定处，把所有广告位判成「未开启」。
 *
 * 依据（反编译 `酷狗音乐_20.6.0.apk` 得到，不是猜的）：
 * - 酷狗 20.6.0 的广告由 `com.tencentmusic.ad`（腾讯音乐广告 SDK）承载，
 *   该 SDK 在 dex 里有 2017 个类，适配器网络为 `ams / mad / madams`；
 * - 门面类 `com.tencentmusic.ad.TMEAds` 提供按广告位的布尔判定：
 *   `checkPosIdOpen(String)Z`、`checkSplashInterval(String, Z)Z`、
 *   `getDynamicFloorEcpmStatus(String)Z`；
 * - 这些方法只回答「这个广告位能不能用」，返回 false 时调用方走「不展示」分支，
 *   不会破坏任何数据结构，是最安全的全局干预点。
 *
 * 为什么不去 hook 各广告形式的加载方法：`AMSSplashAdapter`、`AMSNativeAdAdapter`、
 * `RewardVideoController` 这些类只覆盖单一形式，要靠它们去尽就得逐个跟进新版本；
 * 而门面判定是所有形式共用的一道闸。
 *
 * 视觉层由 [KugouUiHook] 的「广告容器隐藏」兜底：万一某个版本绕过了判定，
 * 容器仍然会被按类名藏掉（代价是空白，不会露出广告）。
 */
internal object KugouAdKillHook {

    @Volatile
    private var installed = false

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (installed) return
        installed = true

        if (lpparam.processName != LiteConfig.TARGET_PACKAGE) {
            XLog.i("广告总开关：跳过非主进程 ${lpparam.processName}")
            return
        }
        if (!LiteConfig.AD_DISABLE_TME) {
            XLog.i("广告总开关已关闭（LiteConfig.AD_DISABLE_TME = false），只保留视觉层隐藏")
            return
        }

        val clazz = runCatching { lpparam.classLoader.loadClass(LiteConfig.TME_FACADE_CLASS) }
            .getOrNull()
        if (clazz == null) {
            XLog.w(
                "找不到 ${LiteConfig.TME_FACADE_CLASS}（酷狗可能换了广告 SDK），" +
                        "广告总开关不会生效，只剩视觉层隐藏"
            )
            return
        }

        var ok = 0
        LiteConfig.TME_FALSE_GATES.forEach { spec ->
            val parsed = parseMethodSpec(spec) ?: return@forEach
            val (name, params) = parsed
            val argTypes = Array<Class<*>>(params.size) { i ->
                resolveType(lpparam.classLoader, params[i]) ?: return@forEach
            }
            val hooked = runCatching {
                XposedHelpers.findAndHookMethod(
                    clazz,
                    name,
                    *argTypes,
                    XC_MethodReplacement.returnConstant(false)
                )
                true
            }.onFailure {
                XLog.w("关闭广告位判定 $spec 失败: $it")
            }.getOrDefault(false)
            if (hooked) {
                ok++
                XLog.i("广告位判定已关闭: TMEAds#$name")
            }
        }

        if (ok == 0) {
            XLog.e("广告总开关一条都没挂上，广告能否去掉只能依赖视觉层隐藏")
        } else {
            XLog.i("广告总开关就绪：$ok/${LiteConfig.TME_FALSE_GATES.size} 条判定被改为 false")
        }
    }

    /** 把 `name(Ljava/lang/String;Z)Z` 拆成方法名和参数描述符列表 */
    private fun parseMethodSpec(spec: String): Pair<String, List<String>>? {
        val open = spec.indexOf('(')
        val close = spec.lastIndexOf(')')
        if (open <= 0 || close <= open) return null
        val name = spec.substring(0, open)
        val body = spec.substring(open + 1, close)
        val params = ArrayList<String>()
        var i = 0
        while (i < body.length) {
            var j = i
            when (body[i]) {
                '[' -> {
                    // 数组：吞掉所有维度再吞一个类型
                    while (j < body.length && body[j] == '[') j++
                    if (j < body.length && body[j] == 'L') {
                        j = body.indexOf(';', j).let { if (it < 0) body.length else it + 1 }
                    } else {
                        j++
                    }
                }
                'L' -> {
                    val end = body.indexOf(';', j)
                    j = if (end < 0) body.length else end + 1
                }
                else -> j++
            }
            params.add(body.substring(i, j))
            i = j
        }
        return name to params
    }

    /** 描述符 → Class，取不到返回 null（该类会被跳过，不影响其它 hook） */
    private fun resolveType(loader: ClassLoader, desc: String): Class<*>? = runCatching {
        when (desc) {
            "Z" -> Boolean::class.javaPrimitiveType
            "B" -> Byte::class.javaPrimitiveType
            "S" -> Short::class.javaPrimitiveType
            "C" -> Char::class.javaPrimitiveType
            "I" -> Int::class.javaPrimitiveType
            "J" -> Long::class.javaPrimitiveType
            "F" -> Float::class.javaPrimitiveType
            "D" -> Double::class.javaPrimitiveType
            "[Z" -> BooleanArray::class.java
            "[B" -> ByteArray::class.java
            "[S" -> ShortArray::class.java
            "[C" -> CharArray::class.java
            "[I" -> IntArray::class.java
            "[J" -> LongArray::class.java
            "[F" -> FloatArray::class.java
            "[D" -> DoubleArray::class.java
            else -> when {
                desc.startsWith("[L") -> loader.loadClass(desc.substring(1, desc.length - 1).replace('/', '.'))
                desc.startsWith("L") -> loader.loadClass(desc.substring(1, desc.length - 1).replace('/', '.'))
                else -> null
            }
        }
    }.getOrNull()
}
