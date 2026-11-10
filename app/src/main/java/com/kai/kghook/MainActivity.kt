package com.kai.kghook

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kai.kghook.ui.theme.KghookTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KghookTheme {
                ModuleScreen()
            }
        }
    }
}

@Composable
private fun ModuleScreen() {
    // 该方法在模块注入自身进程后会被 hook 成返回 true，未注入时保持 false
    val activated = remember { ModuleStatus.isModuleActive() }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "酷狗音乐精简",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "跳过开屏广告，并砍掉不用的底部 Tab、首页频道和快捷卡片，减少无谓的构建、布局与绘制。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            StatusCard(activated)
            RulesCard()
            StepsCard()
            TroubleshootCard()

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StatusCard(activated: Boolean) {
    val container = if (activated) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val content = if (activated) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = if (activated) "模块已激活" else "模块未激活",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = content
            )
            Text(
                text = if (activated) {
                    "框架已注入本应用，酷狗（${LiteConfig.TARGET_PACKAGE}）下次冷启动即生效。"
                } else {
                    "尚未被框架注入。请按下方步骤在 LSPosed 中启用本模块，并把自身加入作用域。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = content
            )
        }
    }
}

@Composable
private fun RulesCard() {
    SectionCard(title = "精简规则") {
        SplashRuleBlock()
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        RuleBlock(
            name = "底部导航",
            desc = "隐藏后剩余两个 Tab 自动各占一半宽度。",
            hide = LiteConfig.BOTTOM_HIDE_NAMES,
            keep = LiteConfig.BOTTOM_KEEP_NAMES
        )
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        RuleBlock(
            name = "首页顶部频道",
            desc = "白名单策略：除保留项外全部隐藏，包括需要右滑才露出的听播 / AI写歌 / 短剧，以及酷狗以后新增的频道。",
            hide = listOf("听书", "儿童", "AI唱", "刷歌", "听播", "AI写歌", "短剧"),
            keep = LiteConfig.TOP_KEEP_NAMES
        )
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        RuleBlock(
            name = "首页快捷入口卡片",
            desc = "账户信息下方那一整块横向卡片。同 id 的「音频·听书」卡片不受影响。",
            hide = LiteConfig.CARD_MATCH_KEYWORDS,
            keep = null
        )
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        RuleBlock(
            name = "广告总开关",
            desc = if (LiteConfig.AD_DISABLE_TME) {
                "在腾讯音乐广告 SDK（com.tencentmusic.ad.TMEAds）的广告位判定处直接判成关闭：" +
                    "checkPosIdOpen / checkSplashInterval / getDynamicFloorEcpmStatus 一律返回 false。" +
                    "我的页 banner、信息流、激励、开屏等所有 TME 广告位共用这道闸。"
            } else {
                "已关闭（LiteConfig.AD_DISABLE_TME = false），只保留下方的容器隐藏。"
            },
            hide = listOf("全部广告位"),
            keep = null
        )
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        RuleBlock(
            name = "广告容器（视觉兜底）",
            desc = "万一某版本绕过上述判定，这些广告专用容器仍会被按类名隐藏：" +
                LiteConfig.AD_CONTAINER_CLASS_HINTS.joinToString(" / ") +
                "。只装它一个子 View 的外壳会一并收掉，避免留白。",
            hide = listOf("广告容器", "外壳"),
            keep = null
        )
    }
}

@Composable
private fun SplashRuleBlock() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "开屏广告",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = if (LiteConfig.SKIP_SPLASH_AD) {
                "从桌面启动时把第一个活动直接换成主界面，开屏页（ForeNoBgSplashActivity）连同它加载的广告都不出现；" +
                    "推送、深链等非桌面启动仍走原生流程，避免吞掉跳转目标。"
            } else {
                "已关闭（LiteConfig.SKIP_SPLASH_AD = false），启动链路与官方版一致。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LabelledChips(label = "跳过", names = listOf("开屏页", "开屏广告"), hidden = true)
        LabelledChips(label = "进入", names = listOf("主界面"), hidden = false)
    }
}

@Composable
private fun RuleBlock(name: String, desc: String, hide: List<String>, keep: List<String>?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        keep?.let {
            LabelledChips(label = "保留", names = it, hidden = false)
        }
        LabelledChips(label = "隐藏", names = hide, hidden = true)
    }
}

@Composable
private fun LabelledChips(label: String, names: List<String>, hidden: Boolean) {
    Row {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .width(36.dp)
                .padding(top = 6.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // 一行放不下就换行，用简单的分组避免引入 FlowRow 依赖
            names.chunked(4).forEach { group ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    group.forEach { name ->
                        Chip(name, hidden)
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(name: String, hidden: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (hidden) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        }
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = if (hidden) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun StepsCard() {
    SectionCard(title = "启用步骤") {
        StepText(1, "安装本模块 APK。")
        StepText(2, "LSPosed → 模块 → 找到 kghook → 打开「启用模块」。")
        StepText(3, "在「作用域」里勾选 酷狗音乐（com.kugou.android）。")
        StepText(4, "如需在本页看到「模块已激活」，再把 kghook 自身也勾上（可选）。")
        StepText(5, "强制停止酷狗后重新打开，热启动不会重新走 onCreate。")
    }
}

@Composable
private fun StepText(index: Int, text: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(
            text = "$index.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(20.dp)
        )
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TroubleshootCard() {
    SectionCard(title = "没生效怎么排查") {
        BulletText("模块每次运行都会输出日志：LSPosed → 日志，过滤 kghook；或数据线执行 adb logcat -s kghook。")
        BulletText("日志里有一份「结构快照」，会打印酷狗版本号、底部栏与顶部频道栏每个子项的真实 id 和文案、以及所有同 id 卡片实例。")
        BulletText("酷狗改版后：频道名变了改 LiteConfig 里的文案；混淆 id（urw / e3w1 / d3y…）变了不用管，模块会自动按特征搜索容器。")
        BulletText("顶部频道栏在「我的」页装的是 音乐 / 关注，模块靠白名单判定，不会误伤那个页面。")
    }
}

@Composable
private fun BulletText(text: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(
            text = "·",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(14.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ModuleScreenPreview() {
    KghookTheme {
        Surface(color = Color.White, modifier = Modifier.fillMaxSize()) {
            ModuleScreen()
        }
    }
}
