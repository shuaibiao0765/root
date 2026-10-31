package com.minis.pd2546root

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ShizukuState { NOT_RUNNING, PERMISSION_REQUIRED, READY }

enum class LogTone { NORMAL, INFO, OK, WARN, ERROR }

data class LogLine(val text: String, val tone: LogTone = LogTone.NORMAL)

/** 内置脚本只针对这一版内核，其他 build 需要自己重算符号表。 */
const val TARGET_KERNEL = "6.12.58-android16-6-gdd6fd5c98918"

data class DeviceInfo(
    val model: String = "—",
    val soc: String = "—",
    val android: String = "—",
    val kernel: String = "",
    val target: String = TARGET_KERNEL,
)

class RootState {
    var shizuku by mutableStateOf(ShizukuState.NOT_RUNNING)
    var device by mutableStateOf(DeviceInfo())
    var running by mutableStateOf(false)
    var sheetVisible by mutableStateOf(false)
    var forceUnverified by mutableStateOf(false)
    var keepAwake by mutableStateOf(true)
    var exitCode by mutableStateOf<Int?>(null)
    var lastRunAt by mutableStateOf("—")
    val logs = mutableStateListOf<LogLine>()

    val kernelMatches: Boolean
        get() = device.kernel.contains(TARGET_KERNEL)

    val kernelKnown: Boolean
        get() = device.kernel.isNotBlank()

    /** 内核已知且（匹配 或 用户强制），不卡在未验证上 */
    val kernelOk: Boolean
        get() = kernelKnown && (kernelMatches || forceUnverified)

    val ready: Boolean
        get() = shizuku == ShizukuState.READY && kernelOk && !running

    val runnable: Boolean
        get() = shizuku == ShizukuState.READY && kernelOk && !running
}

class RootActions(
    val onRun: () -> Unit,
    val onRequestPermission: () -> Unit,
    val onOpenSheet: () -> Unit,
    val onDismissSheet: () -> Unit,
    val onCopyLogs: () -> Unit,
    val onCleanup: () -> Unit,
    val onForceChanged: (Boolean) -> Unit,
    val onKeepAwakeChanged: (Boolean) -> Unit,
)

class MainActivity : ComponentActivity() {

    private val state = RootState()

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { _, result ->
            if (result == PackageManager.PERMISSION_GRANTED) {
                log("[✓] Shizuku 授权成功", LogTone.OK)
                refreshShizuku()
                loadDevice()
            } else {
                log("[!] Shizuku 授权被拒绝", LogTone.ERROR)
                refreshShizuku()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state.device = readDevice()
        applyKeepAwake()

        setContent {
            RootApp(
                state = state,
                actions = RootActions(
                    onRun = ::startRoot,
                    onRequestPermission = ::askPermission,
                    onOpenSheet = { state.sheetVisible = true },
                    onDismissSheet = { if (!state.running) state.sheetVisible = false },
                    onCopyLogs = ::copyLogs,
                    onCleanup = ::cleanup,
                    onForceChanged = { state.forceUnverified = it },
                    onKeepAwakeChanged = {
                        state.keepAwake = it
                        applyKeepAwake()
                    },
                ),
            )
        }

        Shizuku.addBinderReceivedListenerSticky { refreshShizuku() }
        Shizuku.addBinderDeadListener { refreshShizuku() }
        Shizuku.addRequestPermissionResultListener(permissionListener)
        refreshShizuku()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }

    // ---------------- 设备 / Shizuku 状态 ----------------

    private fun readDevice(): DeviceInfo {
        val manufacturer = Build.MANUFACTURER.orEmpty()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        val soc = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null
        }.getOrNull().orEmpty().ifBlank { Build.HARDWARE.orEmpty() }
        return DeviceInfo(
            model = "$manufacturer ${Build.MODEL}".trim(),
            soc = soc.ifBlank { "—" },
            android = "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）",
            kernel = state.device.kernel,
        )
    }

    private fun refreshShizuku() {
        val ping = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        state.shizuku = when {
            !ping -> ShizukuState.NOT_RUNNING
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
                .getOrDefault(false) -> ShizukuState.READY
            else -> ShizukuState.PERMISSION_REQUIRED
        }
        if (state.shizuku == ShizukuState.READY && state.device.kernel.isBlank()) loadDevice()
    }

    private fun loadDevice() {
        lifecycleScope.launch(Dispatchers.IO) {
            val kernel = runCatching { ShizukuRunner.run("uname -r") }
                .getOrNull().orEmpty().trim()
            val soc = runCatching { ShizukuRunner.run("getprop ro.soc.model") }
                .getOrNull().orEmpty().trim()
            withContext(Dispatchers.Main) {
                state.device = state.device.copy(
                    kernel = kernel,
                    soc = soc.ifBlank { state.device.soc },
                )
            }
        }
    }

    private fun askPermission() {
        runCatching { Shizuku.requestPermission(0) }
            .onFailure { log("[!] 请求权限失败：${it.message}", LogTone.ERROR) }
    }

    private fun applyKeepAwake() {
        if (state.keepAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ---------------- 日志 ----------------

    private fun log(line: String, tone: LogTone? = null) {
        val resolved = tone ?: toneOf(line)
        runOnUiThread { state.logs.add(LogLine(line, resolved)) }
    }

    private fun toneOf(line: String): LogTone = when {
        line.startsWith("[✓]") || line.contains("KSU_ROOT_OK") -> LogTone.OK
        line.startsWith("[!]") || line.contains("失败") || line.contains("error") -> LogTone.ERROR
        line.startsWith("[*]") -> LogTone.INFO
        line.startsWith("    ") -> LogTone.NORMAL
        else -> LogTone.NORMAL
    }

    private fun copyLogs() {
        val text = state.logs.joinToString("\n") { it.text }
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("PD2546 log", text))
        }
        log("[*] 日志已复制到剪贴板", LogTone.INFO)
    }

    // ---------------- 一键流程 ----------------

    private fun startRoot() {
        if (state.running) return
        if (state.shizuku == ShizukuState.NOT_RUNNING) {
            log("[!] Shizuku 没连上：先把 Shizuku 启动起来", LogTone.ERROR)
            state.sheetVisible = true
            return
        }
        if (state.shizuku == ShizukuState.PERMISSION_REQUIRED) {
            askPermission()
            return
        }
        if (!state.kernelOk) {
            log("[!] 内核未验证：勾选“强制运行”后再试", LogTone.WARN)
            state.sheetVisible = true
            return
        }

        state.running = true
        state.sheetVisible = true
        state.logs.clear()
        state.exitCode = null
        state.lastRunAt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(Date())

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                log("[*] 推送 payload 到 /data/local/tmp", LogTone.INFO)
                for (name in ShizukuRunner.PAYLOAD) {
                    ShizukuRunner.pushAsset(this@MainActivity, name, "/data/local/tmp/$name")
                    log("    ✓ $name", LogTone.NORMAL)
                }
                log("[*] 开始执行 reroot-local.sh（几分钟，中途重启属正常）", LogTone.INFO)
                log("", LogTone.NORMAL)

                val process = ShizukuRunner.start(
                    "chmod 755 /data/local/tmp/timer98260 /data/local/tmp/ksud " +
                        "/data/local/tmp/probe /data/local/tmp/fake_shell.sh " +
                        "/data/local/tmp/reroot-local.sh; " +
                        "sh /data/local/tmp/reroot-local.sh 2>&1",
                )
                process.inputStream.bufferedReader().forEachLine { line -> log(line) }
                val rc = process.waitFor()

                state.exitCode = rc
                log("", LogTone.NORMAL)
                log("[*] 脚本退出码 $rc", LogTone.INFO)
                if (rc == 0) {
                    log("[✓] KSU_ROOT_OK root 已就绪（u:r:ksu:s0）", LogTone.OK)
                } else {
                    log("[!] 未成功，看上面最后几行；重启后再点一次通常就行", LogTone.ERROR)
                }
            } catch (t: Throwable) {
                log("[!] ${t.javaClass.simpleName}: ${t.message}", LogTone.ERROR)
            } finally {
                withContext(Dispatchers.Main) { state.running = false }
            }
        }
    }

    private fun cleanup() {
        if (state.shizuku != ShizukuState.READY) {
            log("[!] 需要 Shizuku 授权才能清理", LogTone.ERROR)
            state.sheetVisible = true
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val names = ShizukuRunner.PAYLOAD.joinToString(" ") { "/data/local/tmp/$it" }
            val out = runCatching { ShizukuRunner.run("rm -f $names; echo CLEANED") }
                .getOrElse { "ERR ${it.message}" }
            withContext(Dispatchers.Main) {
                if (out.contains("CLEANED")) {
                    log("[✓] 已清理 /data/local/tmp 下的 payload", LogTone.OK)
                } else {
                    log("[!] 清理失败：${out.trim()}", LogTone.ERROR)
                }
                state.sheetVisible = true
            }
        }
    }
}
