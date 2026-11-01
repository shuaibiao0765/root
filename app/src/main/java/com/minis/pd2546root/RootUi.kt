package com.minis.pd2546root

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private enum class RootScreen { Main, Advanced }

@Composable
fun RootApp(state: RootState, actions: RootActions) {
    MiuixTheme(colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        var screen by remember { mutableStateOf(RootScreen.Main) }
        // 执行中的秒表，给进度条旁边的“已用”用
        LaunchedEffect(state.running) {
            while (state.running) {
                state.elapsedSec = ((System.currentTimeMillis() - state.runStartedAt) / 1000).toInt()
                kotlinx.coroutines.delay(1000)
            }
        }
        when (screen) {
            RootScreen.Main -> MainScreen(
                state = state,
                actions = actions,
                onOpenAdvanced = { screen = RootScreen.Advanced },
                // miuix 的 OverlayBottomSheet 不会在调用处渲染：
                // 它把状态注册进 Scaffold 提供的 LocalDialogStates，由 Scaffold 的 popupHost 画出来。
                // 放在 Scaffold 外面就会注册进一个没人渲染的孤立列表 —— 点了没反应、也不报错。
                sheet = { RootSheet(state = state, actions = actions) },
            )

            RootScreen.Advanced -> AdvancedScreen(
                state = state,
                actions = actions,
                onBack = { screen = RootScreen.Main },
                sheet = { RootSheet(state = state, actions = actions) },
            )
        }
    }
}

// ---------------- 主屏 ----------------

@Composable
private fun MainScreen(
    state: RootState,
    actions: RootActions,
    onOpenAdvanced: () -> Unit,
    sheet: @Composable () -> Unit = {},
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = "PD2546 Root", scrollBehavior = scrollBehavior) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "status") {
                StatusCard(state = state, actions = actions)
            }
            item(key = "log") {
                InlineLog(state, actions)
            }
            item(key = "device") {
                DeviceCard(state.device)
            }
            item(key = "settings") {
                Card {
                    ArrowPreference(
                        title = "根管理器",
                        summary = state.managerSummary,
                        onClick = actions.onOpenPicker,
                    )
                    InstalledManagerLines(state)
                    ArrowPreference(
                        title = "清理设备端文件",
                        summary = "删除 /data/local/tmp 下的 6 个 payload",
                        onClick = actions.onCleanup,
                    )
                    SwitchPreference(
                        checked = state.forceUnverified,
                        onCheckedChange = actions.onForceChanged,
                        title = "强制在未验证内核上运行",
                        summary = "内核与内置符号表不匹配时也执行，容易失败或重启",
                    )
                    SwitchPreference(
                        checked = state.keepAwake,
                        onCheckedChange = actions.onKeepAwakeChanged,
                        title = "执行时保持唤醒",
                        summary = "跑脚本期间不锁屏",
                    )
                }
            }
            item(key = "advanced") {
                Card {
                    ArrowPreference(
                        title = "高级选项",
                        summary = "执行步骤、payload 清单与目标内核",
                        onClick = onOpenAdvanced,
                    )
                }
            }
            item(key = "run") {
                RunButton(state = state, actions = actions)
            }
        }
        // 关键：浮层要在 Scaffold 的 content 里组合，才会真的被 popupHost 画出来
        sheet()
    }
}

// ---------------- 常驻日志（主屏滚动区） ----------------

/**
 * 执行过程直接挂在主屏上，不再依赖 OverlayBottomSheet。
 * 之前的浮层一打开就闪退过，而且盖住界面什么都看不见；
 * 这里是固定高度的一块滚动区，跟着日志自动滚到底。
 */
@Composable
private fun InlineLog(state: RootState, actions: RootActions) {
    Card(insideMargin = PaddingValues(14.dp)) {
        Column(Modifier.fillMaxWidth()) {
            StepHeader(state)
            LogPanel(
                lines = state.logs,
                modifier = Modifier.fillMaxWidth().height(340.dp),
            )
            if (state.logs.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                TextButton(
                    text = "复制这 ${state.logs.size} 行日志",
                    onClick = actions.onCopyLogs,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// ---------------- 状态卡 ----------------

private data class StatusSpec(
    val background: Color,
    val title: String,
    val summary: String,
    val icon: ImageVector,
    val tint: Color,
)

@Composable
private fun statusSpec(state: RootState): StatusSpec {
    val dark = isSystemInDarkTheme()
    val greenBg = if (dark) Color(0xFF1A3825) else Color(0xFFDFFAE4)
    val amberBg = if (dark) Color(0xFF3E2F1B) else Color(0xFFFFF0DB)
    val redBg = if (dark) Color(0xFF3A1B1B) else Color(0xFFFFE3E3)
    val green = Color(0xFF36D167)
    val amber = Color(0xFFF5A623)
    val red = Color(0xFFF72727)

    return when {
        state.running -> StatusSpec(
            greenBg,
            if (state.stepFailed) "执行中断" else "执行中…",
            "第 ${state.currentStep.coerceAtLeast(0) + 1}/${STEP_TITLES.size} 步 · " +
                STEP_TITLES.getOrElse(state.currentStep.coerceAtLeast(0)) { STEP_TITLES.first() },
            Icons.Rounded.CheckCircleOutline, green,
        )

        state.shizuku == ShizukuState.NOT_RUNNING -> StatusSpec(
            redBg, "Shizuku 未连接",
            "先用无线调试启动 Shizuku，再回到这里点我",
            Icons.Rounded.RemoveCircleOutline, red,
        )

        state.shizuku == ShizukuState.PERMISSION_REQUIRED -> StatusSpec(
            amberBg, "需要授权 Shizuku",
            "点这里请求授权，在 Shizuku 弹窗里点允许",
            Icons.Rounded.ErrorOutline, amber,
        )

        !state.kernelKnown -> StatusSpec(
            amberBg, "正在读取内核",
            "读取 uname -r 后判断是否在支持列表里",
            Icons.Rounded.ErrorOutline, amber,
        )

        !state.kernelOk -> StatusSpec(
            amberBg, "内核不匹配",
            "内置脚本针对 \n$TARGET_KERNEL_FULL\n可到设置里强制继续",
            Icons.Rounded.ErrorOutline, amber,
        )

        state.kernelMatches -> StatusSpec(
            greenBg, "已就绪",
            "内核匹配、Shizuku 已授权，点下面的按钮开始",
            Icons.Rounded.CheckCircleOutline, green,
        )

        else -> StatusSpec(
            amberBg, "强制模式",
            "内核不在支持列表，已按你的设置继续",
            Icons.Rounded.ErrorOutline, amber,
        )
    }
}

@Composable
private fun StatusCard(state: RootState, actions: RootActions) {
    val spec = statusSpec(state)
    Card(
        colors = CardDefaults.defaultColors(color = spec.background),
        onClick = {
            when {
                state.shizuku == ShizukuState.NOT_RUNNING || state.shizuku == ShizukuState.PERMISSION_REQUIRED ->
                    actions.onRequestPermission()

                else -> Unit
            }
        },
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Tilt,
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 110.dp)) {
            Icon(
                imageVector = spec.icon,
                contentDescription = null,
                tint = spec.tint.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 27.dp, y = 31.dp)
                    .size(110.dp),
            )
            Column(Modifier.padding(start = 16.dp, top = 14.dp, end = 120.dp, bottom = 14.dp)) {
                Text(text = spec.title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(1.dp))
                Text(text = spec.summary, fontSize = 15.sp)
            }
        }
    }
}

// ---------------- 设备信息 ----------------

@Composable
private fun DeviceCard(device: DeviceInfo) {
    Card(insideMargin = PaddingValues(16.dp)) {
        SelectionContainer {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                InfoItem("设备", device.model)
                InfoItem("芯片", device.soc)
                InfoItem("系统", device.android)
                InfoItem("内核", device.kernel.ifBlank { "—" })
            }
        }
    }
}

@Composable
private fun InfoItem(title: String, value: String) {
    Column {
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = value,
            modifier = Modifier.padding(top = 2.dp),
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.68f),
        )
    }
}

// ---------------- 执行按钮 ----------------

@Composable
private fun RunButton(state: RootState, actions: RootActions) {
    val label = when {
        state.running -> "执行中…"
        state.shizuku == ShizukuState.NOT_RUNNING -> "启动 Shizuku 后可执行"
        state.shizuku == ShizukuState.PERMISSION_REQUIRED -> "授权 Shizuku"
        else -> "一键提权"
    }
    val blocked = !state.running &&
        (state.shizuku != ShizukuState.READY || !state.kernelOk)

    Box {
        TextButton(
            text = label,
            enabled = !blocked && !state.running,
            colors = ButtonDefaults.textButtonColorsPrimary(),
            onClick = {
                if (state.shizuku == ShizukuState.PERMISSION_REQUIRED) {
                    actions.onRequestPermission()
                } else {
                    actions.onRun()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        /* 禁用的 TextButton 不吃点击，盖一层给出可执行的下一步 */
        if (blocked) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable {
                        if (state.shizuku == ShizukuState.PERMISSION_REQUIRED) {
                            actions.onRequestPermission()
                        } else {
                            actions.onOpenSheet()
                        }
                    },
            )
        }
    }
}

// ---------------- 单一浮层：日志 / 根管理器共用 ----------------

@Composable
private fun RootSheet(state: RootState, actions: RootActions) {
    val picker = state.pickerVisible
    val dismiss: () -> Unit = {
        actions.onDismissPicker()
        actions.onDismissSheet()
    }

    OverlayBottomSheet(
        show = picker || state.sheetVisible,
        title = if (picker) "根管理器" else "执行日志",
        allowDismiss = picker || !state.running,
        onDismissRequest = dismiss,
        startAction = {
            if (picker) {
                IconButton(onClick = actions.onRescan) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "重新扫描",
                        tint = MiuixTheme.colorScheme.onBackground,
                    )
                }
            } else {
                IconButton(onClick = actions.onCopyLogs) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = "复制日志",
                        tint = MiuixTheme.colorScheme.onBackground,
                    )
                }
            }
        },
        endAction = {
            IconButton(enabled = picker || !state.running, onClick = dismiss) {
                Icon(imageVector = Icons.Rounded.Close, contentDescription = "关闭")
            }
        },
        content = {
            if (picker) {
                ManagerSheetContent(state, actions)
            } else {
                LogSheetContent(state, actions)
            }
        },
    )
}

@Composable
private fun LogSheetContent(state: RootState, actions: RootActions) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 280.dp, max = 560.dp)
            .navigationBarsPadding(),
    ) {
        StepHeader(state)
        LogPanel(
            lines = state.logs,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

@Composable
private fun StepHeader(state: RootState) {
    val total = STEP_TITLES.size
    val current = state.currentStep
    val accent = MiuixTheme.colorScheme.primary

    val fraction = when {
        current < 0 -> 0f
        state.running && !state.stepFailed -> (current + 0.5f) / total
        state.stepFailed -> (current + 1f) / total
        else -> (current + 1f) / total
    }.coerceIn(0f, 1f)

    val title = when {
        current < 0 -> if (state.logs.isEmpty()) "等待开始" else "准备中"
        state.stepFailed -> "第 ${current + 1}/$total 步失败 · ${STEP_TITLES[current]}"
        state.running -> "第 ${current + 1}/$total 步 · ${STEP_TITLES[current]}"
        state.exitCode == 0 -> "全部 $total 步完成 · root 已就绪"
        else -> "已结束 · 第 ${current + 1}/$total 步"
    }
    val titleColor = when {
        state.stepFailed -> Color(0xFFF72727)
        state.exitCode == 0 && !state.running -> Color(0xFF36D167)
        else -> MiuixTheme.colorScheme.onSurface
    }
    val elapsed = "%02d:%02d".format(state.elapsedSec / 60, state.elapsedSec % 60)

    Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = titleColor,
            )
            Spacer(Modifier.weight(1f))
            if (state.running || state.elapsedSec > 0) {
                Text(
                    text = elapsed,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (state.stepFailed) Color(0xFFF72727) else accent),
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            STEP_TITLES.indices.forEach { i ->
                val color = when {
                    state.stepFailed && i == current -> Color(0xFFF72727)
                    i < current || (state.exitCode == 0 && !state.running) -> Color(0xFF36D167)
                    i == current -> accent
                    else -> MiuixTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                }
                Box(
                    Modifier
                        .size(if (i == current && !state.stepFailed) 9.dp else 6.dp)
                        .clip(CircleShape)
                        .background(color),
                )
            }
        }
    }
}

@Composable
private fun LogPanel(lines: List<LogLine>, modifier: Modifier = Modifier) {
    // 故意不用 LazyColumn / SelectionContainer：这套 Compose 组合在浮层里崩过一次，
    // 现在整段就是一个普通可滚动 Column，行数最多几百行，撑得住。
    val scroll = rememberScrollState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) scroll.animateScrollTo(scroll.maxValue)
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0B1220))
            .padding(12.dp),
    ) {
        if (lines.isEmpty()) {
            Text(
                text = "日志区已就绪，等脚本第一行输出…\n（一行都没有 = 脚本根本没启动，或 Shizuku 没给 shell）",
                color = Color(0xFF9CA3AF),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scroll),
            ) {
                lines.forEach { line ->
                    Text(
                        text = line.text,
                        color = toneColor(line.tone),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}

private fun toneColor(tone: LogTone): Color = when (tone) {
    LogTone.OK -> Color(0xFF5FD68A)
    LogTone.ERROR -> Color(0xFFFF6B6B)
    LogTone.WARN -> Color(0xFFFFC94D)
    LogTone.INFO -> Color(0xFF60A5FA)
    LogTone.NORMAL -> Color(0xFFD1D5DB)
}

// ---------------- 已装管理器的版本（绿色，挂在“根管理器”下面） ----------------

@Composable
private fun InstalledManagerLines(state: RootState) {
    val green = Color(0xFF2FA84F)
    val muted = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.45f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (state.managers.isEmpty()) {
            Text(
                text = "未检测到已安装的 KSU 管理器",
                fontSize = 12.5.sp,
                color = muted,
            )
            Text(
                text = "可以点上面这一行手动导入 APK",
                fontSize = 11.5.sp,
                color = muted,
            )
        } else {
            state.managers.take(5).forEach { candidate ->
                val suffix = when {
                    state.imported?.packageName == candidate.packageName -> " · 已导入"
                    candidate.packageName == state.selectedPackage -> " · 使用中"
                    candidate.hasKsud -> ""
                    else -> " · 无内置 ksud"
                }
                Text(
                    text = "● ${candidate.label} ${candidate.version}$suffix",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = green,
                )
            }
            if (state.managers.size > 5) {
                Text(
                    text = "还有 ${state.managers.size - 5} 个，点上面查看全部",
                    fontSize = 11.5.sp,
                    color = muted,
                )
            }
        }
    }
}

// ---------------- 根管理器选择 ----------------

@Composable
private fun ManagerSheetContent(state: RootState, actions: RootActions) {
    val imported = state.imported
    val chosen = state.selectedPackage

    LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 240.dp, max = 520.dp)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "builtin") {
                Card {
                    ManagerRow(
                        title = "内置 ksud",
                        subtitle = "自带的 KernelSU 32707，兼容老版管理器",
                        selected = imported == null && chosen == null,
                        onClick = { actions.onSelectManager(null) },
                    )
                }
            }

            if (imported != null) {
                item(key = "imported") {
                    Card {
                        ManagerRow(
                            title = if (imported.raw) "自定义 ksud（裸二进制）" else "${imported.label} ${imported.version}",
                            subtitle = if (imported.raw) {
                                "包名沿用当前选择：${state.managerPackage}"
                            } else {
                                "${imported.packageName} · 来自导入的 APK"
                            },
                            selected = imported.packageName.isBlank() || chosen == imported.packageName,
                            onClick = { actions.onSelectManager(imported.packageName) },
                        )
                    }
                }
            }

            if (state.managers.isNotEmpty()) {
                item(key = "installed-title") {
                    Box(Modifier.padding(start = 4.dp, top = 4.dp)) {
                        SmallTitle(text = "已安装的管理器")
                    }
                }
                items(state.managers) { candidate ->
                    Card {
                        ManagerRow(
                            title = "${candidate.label} ${candidate.version}",
                            subtitle = "${candidate.packageName} · " +
                                if (candidate.hasKsud) "带 libksud.so" else "没带 ksud，会用内置",
                            selected = chosen == candidate.packageName && imported == null,
                            onClick = { actions.onSelectManager(candidate.packageName) },
                        )
                    }
                }
            }

            item(key = "import") {
                Card {
                    ArrowPreference(
                        title = "导入管理器 APK…",
                        summary = "任意 KernelSU 版本（含 canary），也可以直接选单个 ksud 二进制",
                        onClick = actions.onImportApk,
                    )
                }
            }

            item(key = "rescan") {
                Card {
                    ArrowPreference(
                        title = "重新扫描已安装的管理器",
                        summary = "装完管理器后点这里刷新版本号",
                        onClick = actions.onRescan,
                    )
                }
            }

            item(key = "hint") {
                Box(Modifier.padding(start = 4.dp, top = 4.dp, end = 4.dp)) {
                    Text(
                        text = "内核模块和管理器必须版本对得上：装了新版管理器就导入新版 APK，" +
                            "否则 root 起来了管理器也可能提示“内核版本不匹配”。",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
    }
}

@Composable
private fun ManagerRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ArrowPreference(
        title = if (selected) "✓ $title" else title,
        summary = subtitle,
        onClick = onClick,
    )
}

// ---------------- 高级选项 ----------------

@Composable
private fun AdvancedScreen(
    state: RootState,
    actions: RootActions,
    onBack: () -> Unit,
    sheet: @Composable () -> Unit = {},
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            SmallTopAppBar(
                title = "高级选项",
                titleColor = MiuixTheme.colorScheme.onSurface,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item {
                Box(Modifier.padding(top = 8.dp)) {
                    SmallTitle(text = "运行状态")
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    CardBody(
                        listOf(
                            "Shizuku" to when (state.shizuku) {
                                ShizukuState.READY -> "已授权"
                                ShizukuState.PERMISSION_REQUIRED -> "已连接，未授权"
                                ShizukuState.NOT_RUNNING -> "未连接"
                            },
                            "内核" to state.device.kernel.ifBlank { "—" },
                            "上次执行" to state.lastRunAt,
                            "退出码" to (state.exitCode?.toString() ?: "—"),
                        ),
                    )
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    ArrowPreference(
                        title = "读取设备端状态",
                        summary = "uname / getenforce / timer98260 进程 / fakeR.log 末尾 40 行",
                        onClick = actions.onPullDeviceLog,
                    )
                }
            }
            item {
                Box(Modifier.padding(bottom = 4.dp)) {
                    SmallTitle(text = "执行步骤")
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    CardBody(
                        listOf(
                            "1 推送" to "把 assets 里 6 个文件灌进 /data/local/tmp",
                            "2 授权" to "chmod 755，用 sh 执行 reroot-local.sh",
                            "3 提权" to "清 vr funcs / pre_handler、截断 notifier、生成 kallsyms",
                            "4 加载" to "ksud late-load 挂上 KernelSU",
                        ),
                    )
                }
            }
            item {
                Box(Modifier.padding(bottom = 4.dp)) {
                    SmallTitle(text = "Payload")
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    CardBody(
                        listOf(
                            "reroot-local.sh" to "主流程脚本",
                            "timer98260" to "触发 fake 凭据页",
                            "ksud" to "KernelSU 用户态加载器",
                            "probe" to "内核地址探测",
                            "fake_shell.sh" to "伪造 shell 上下文",
                            "symbols.txt" to "符号表",
                        ),
                    )
                }
            }
            item {
                Box(Modifier.padding(bottom = 4.dp)) {
                    SmallTitle(text = "说明")
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "目标内核：\n$TARGET_KERNEL_FULL",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "仅在目标 build 上验证过。其他内核需要自己重算符号表，" +
                                "强行执行大概率失败，个别情况会直接重启——这不是 App 崩溃。",
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                        )
                        Text(
                            text = "根权限是 per-boot 的：冷重启失效，软重启不掉。",
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                        )
                    }
                }
            }
        }
        sheet()
    }
}

@Composable
private fun CardBody(rows: List<Pair<String, String>>) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        rows.forEach { (key, value) ->
            Row(Modifier.fillMaxWidth()) {
                Text(text = key, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(
                    text = value,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                )
            }
        }
    }
}
