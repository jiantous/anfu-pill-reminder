package com.jian.pillreminder.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jian.pillreminder.ui.components.MedBadge
import com.jian.pillreminder.ui.theme.medColorAt

/** 项目地址。改了这里记得同步 README。 */
const val PROJECT_URL = "https://github.com/jiantous/anfu-pill-reminder"

/**
 * 版本发布页。
 *
 * 安服**不能自动检查更新**——没申请网络权限，做不到。所以只能把人送到这个页面
 * 自己看。打开网页和发邮件都是把 Intent 交给别的 App 处理，不需要本应用联网，
 * 这跟"自己去下载一个版本号"是两回事。
 */
const val RELEASES_URL = "$PROJECT_URL/releases"

/** 反馈邮箱。 */
const val FEEDBACK_EMAIL = "jiantous@outlook.com"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    versionName: String,
    onOpenProjectPage: () -> Unit,
    onOpenReleases: () -> Unit,
    onSendFeedback: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            Spacer(Modifier.height(24.dp))

            // 应用图标：复用药品徽标组件画一个胶囊，和桌面启动图标呼应
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                MedBadge(
                    iconIndex = 1, // 胶囊
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                    size = 88.dp
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "安服",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "版本 $versionName",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "取「安心服药」之意",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(28.dp))

            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("隐私", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "安服没有申请网络权限——不是「承诺不上传」，是技术上做不到。" +
                            "没有服务器，也不用注册账号。拍说明书用的是手机本地识别引擎，" +
                            "照片识别完立刻丢弃。所有数据只存在这台手机上。\n\n" +
                            "下面的按钮是把网址或邮件交给浏览器、邮件 App 去处理，" +
                            "不会带上你的用药数据。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider(Modifier.padding(vertical = 14.dp))
                    Text("检查新版本", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    // 说清楚为什么要手动：不联网是刻意的取舍，不是偷懒
                    Text(
                        "安服不联网，没法自动提示更新。新版本都发在 GitHub 的 " +
                            "Releases 页，想看有没有更新就点下面。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(
                        onClick = onOpenReleases,
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) {
                        Icon(Icons.Filled.Update, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("看看有没有新版本")
                    }

                    HorizontalDivider(Modifier.padding(vertical = 14.dp))
                    Text("提建议 / 报问题", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "用得不顺手、想要什么功能，或者提醒没响，都欢迎告诉我。" +
                            "描述一下当时的情况和手机型号会更好排查。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onSendFeedback,
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) {
                        Icon(Icons.Filled.MailOutline, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("发邮件反馈")
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        FEEDBACK_EMAIL,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )

                    HorizontalDivider(Modifier.padding(vertical = 14.dp))
                    Text("开源", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "代码完全公开，MIT 协议。欢迎查看或改进。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onOpenProjectPage,
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) {
                        Icon(Icons.Filled.Code, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("在 GitHub 上查看源码")
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // 免责：涉及用药，必须显眼
            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("免责说明", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "安服是辅助记录与提醒工具，不是医疗器械，不提供任何医疗建议。" +
                            "吃什么药、吃多少、吃多久，请完全遵照医生嘱咐和药品说明书。\n\n" +
                            "提醒依赖手机系统的闹钟与通知机制。若系统省电策略限制了本应用，" +
                            "或手机关机、静音、被强制停止，提醒可能延迟或不出现。" +
                            "请勿将本应用作为唯一的用药保障手段。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
