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
import androidx.compose.ui.Alignment
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
                text = "酷狗底部导航精简",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "隐藏「视频 / K歌 / 福利」三个 Tab，只留首页和我的，减少底部栏的绘制与布局开销。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            StatusCard(activated)
            ScopeCard()
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
                    "框架已注入本应用，酷狗主界面（${TabConfig.TARGET_PACKAGE}）会在下次冷启动时生效。"
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
private fun ScopeCard() {
    SectionCard(title = "生效范围") {
        InfoRow("目标应用", "${TabConfig.TARGET_APP_NAME}（${TabConfig.TARGET_PACKAGE}）")
        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "隐藏",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(48.dp)
            )
            TabChips(TabConfig.HIDE_TAB_NAMES, hidden = true)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "保留",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(48.dp)
            )
            TabChips(TabConfig.KEEP_TAB_NAMES, hidden = false)
        }
    }
}

@Composable
private fun TabChips(names: List<String>, hidden: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        names.forEach { name ->
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
                    style = MaterialTheme.typography.labelLarge,
                    color = if (hidden) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun StepsCard() {
    SectionCard(title = "启用步骤") {
        StepText(1, "安装本模块 APK。")
        StepText(2, "LSPosed → 模块 → 找到 kghook → 打开「启用模块」。")
        StepText(3, "在「作用域」里勾选 酷狗音乐（com.kugou.android）。")
        StepText(4, "如需在本页看到「模块已激活」，再把 kghook 自身也勾上（可选）。")
        StepText(5, "回到 LSPosed 首页确认已重启 Zygisk，然后强制停止酷狗并重新打开。")
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
        BulletText("模块每次运行都会输出日志，优先看 LSPosed → 日志，过滤关键字 kghook。")
        BulletText("或用数据线执行：adb logcat -s kghook")
        BulletText("日志里有一份「结构快照」，会打印酷狗版本号、底部栏容器与每个 Tab 的真实 id 和文案。")
        BulletText("酷狗改版后如果 Tab 文案变了，改 TabConfig.HIDE_TAB_NAMES 即可；如果连底部栏 id 都变了，模块会自动按特征搜索容器，一般无需改动。")
        BulletText("务必确认酷狗是被「强制停止」后重开的，热启动不会重新走 onCreate。")
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

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(72.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
