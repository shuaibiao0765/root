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

/** 前缀用于宽松匹配（不同批次的 -abogki 后缀会变），完整串用于界面显示。 */
const val TARGET_KERNEL = "6.12.58-android16-6-gdd6fd5c98918"
const val TARGET_KERNEL_FULL = "6.12.58-android16-6-gdd6fd5c98918-abogki543627742-4k"

/** reroot-local.sh 的 [n/6] 阶段，顺序即进度 */
val STEP_TITLES = listOf(
    "环境检查",
    "启动 exploit",
    "清 vr funcs",
    "扫 kprobe 桶",
    "截断 notifier 链",
    "生成 kallsyms",
    "ksud late-load",
)

data class DeviceInfo(
    val model: String = "—",
    val soc: String = "—",
    val android: String = "—",
    val kernel: String = "",
    val target: String = TARGET_KERNEL_FULL,
)

class RootState {
    var shizuku by mutableStateOf(ShizukuState.NOT_RUNNING)
    var managers by mutableStateOf<List<ManagerCatalog.Candidate>>(emptyList())
    var selectedPackage by mutableStateOf<String?>(null)
    var imported by mutableStateOf<KsudImporter.Imported?>(null)
    var pickerVisible by mutableStateOf(false)
    var device by mutableStateOf(DeviceInfo())
    var running by mutableStateOf(false)
    var sheetVisible by mutableStateOf(false)
    var forceUnverified by mutableStateOf(false)
    var keepAwake by mutableStateOf(true)
    var exitCode by mutableStateOf<Int?>(null)
    var currentStep by mutableStateOf(-1)
    var stepFailed by mutableStateOf(false)
    var runStartedAt by mutableStateOf(0L)
    var elapsedSec by mutableStateOf(0)
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

    val chosenManager: ManagerCatalog.Candidate?
        get() = managers.firstOrNull { it.packageName == selectedPackage }

    /** 真正交给 reroot-local.sh 的管理器包名 */
    val managerPackage: String
        get() = imported?.packageName?.takeIf { it.isNotBlank() }
            ?: selectedPackage
            ?: "me.weishu.kernelsu"

    val managerLabel: String
        get() {
            imported?.let {
                return if (it.raw) "自定义 ksud（裸二进制）" else "${it.label} ${it.version}（导入）"
            }
            chosenManager?.let { return "${it.label} ${it.version}" }
            return "内置 ksud（KernelSU 32707）"
        }

    val managerSummary: String
        get() {
            imported?.let {
                return if (it.raw) {
                    "裸 ksud 二进制 · 包名用 ${managerPackage}"
                } else {
                    "${it.packageName} · ${it.version} · 来自导入的 APK"
                }
            }
            chosenManager?.let { c ->
                return if (c.hasKsud) {
                    "${c.packageName} · ${c.version} · 使用它自带的 libksud.so"
                } else {
                    "${c.packageName} · ${c.version} · 没带 ksud，仍用内置"
                }
            }
            return if (managers.isEmpty()) {
                "未检测到已安装的管理器，可点这里导入 APK"
            } else {
                "检测到 ${managers.size} 个已安装的管理器，默认用内置 ksud"
            }
        }
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
    val onOpenPicker: () -> Unit,
    val onDismissPicker: () -> Unit,
    val onSelectManager: (String?) -> Unit,
    val onImportApk: () -> Unit,
    val onRescan: () -> Unit,
    val onRefreshDevice: () -> Unit,
    val onPullDeviceLog: () -> Unit,
)

class MainActivity : ComponentActivity() {

    private val state = RootState()

    /** 选一个管理器 APK 导入：任意 KernelSU 版本（含 canary）都行 */
    private val pickApk = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(::importApk) }

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
                    onOpenPicker = { state.pickerVisible = true },
                    onDismissPicker = { state.pickerVisible = false },
                    onSelectManager = { pkg ->
                        state.selectedPackage = pkg
                        state.imported = null
                        state.pickerVisible = false
                    },
                    onRescan = { refreshManagers() },
                    onRefreshDevice = { refreshDevice() },
                    onPullDeviceLog = { pullDeviceLog() },
                    onImportApk = {
                        runCatching { pickApk.launch(arrayOf("application/vnd.android.package-archive", "*/*")) }
                            .onFailure { log("[!] 打开文件选择器失败：${it.message}", LogTone.ERROR) }
                    },
                ),
            )
        }

        refreshManagers()
        Shizuku.addBinderReceivedListenerSticky { refreshShizuku() }
        Shizuku.addBinderDeadListener { refreshShizuku() }
        Shizuku.addRequestPermissionResultListener(permissionListener)
        refreshShizuku()
    }

    override fun onResume() {
        super.onResume()
        refreshManagers()
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
            // System.getProperty("os.version") 就是 uname -r，本地就能读，不需要 Shizuku
            kernel = runCatching {
                System.getProperty("os.version")?.trim().orEmpty()
            }.getOrDefault(""),
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
                    kernel = kernel.ifBlank { state.device.kernel },
                    soc = soc.ifBlank { state.device.soc },
                )
                if (state.device.kernel.isBlank()) {
                    log("[!] 读不到内核版本：Shizuku 的 uname -r 没返回内容", LogTone.ERROR)
                }
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

    private val stepRegex = Regex("""\[(\d)\s*/\s*\d\]\s*(.+)""")

    private fun log(line: String, tone: LogTone? = null) {
        val resolved = tone ?: toneOf(line)
        val step = stepRegex.find(line)
        runOnUiThread {
            if (step != null) {
                // 脚本里是 [1/7]..[7/7]，内部用 0 基下标
                val index = step.groupValues[1].toIntOrNull()?.minus(1)
                if (index != null && index in STEP_TITLES.indices) {
                    state.currentStep = index
                    state.stepFailed = false
                }
            }
            state.logs.add(LogLine(line, resolved))
        }
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
        state.currentStep = 0
        state.stepFailed = false
        state.runStartedAt = System.currentTimeMillis()
        state.elapsedSec = 0
        state.lastRunAt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(Date())

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                log("[*] 推送 payload 到 /data/local/tmp", LogTone.INFO)
                for (name in ShizukuRunner.PAYLOAD) {
                    if (name == "ksud") continue // 由所选管理器决定版本，下面单独推
                    ShizukuRunner.pushAsset(this@MainActivity, name, "/data/local/tmp/$name")
                    log("    ✓ $name", LogTone.NORMAL)
                }
                pushKsud { log(it, LogTone.NORMAL) }
                log("[-] 管理器：${state.managerPackage}", LogTone.INFO)
                log("[*] 开始执行 reroot-local.sh（几分钟，中途重启属正常）", LogTone.INFO)
                log("", LogTone.NORMAL)

                val env = buildString {
                    append("PD_PKG='").append(state.managerPackage).append("' ")
                    if (state.forceUnverified) append("FORCE=1 ")
                }
                val process = ShizukuRunner.start(
                    "chmod 755 /data/local/tmp/timer98260 /data/local/tmp/ksud " +
                        "/data/local/tmp/probe /data/local/tmp/fake_shell.sh " +
                        "/data/local/tmp/ksu_stage.sh /data/local/tmp/reroot-local.sh; " +
                        env + "sh /data/local/tmp/reroot-local.sh 2>&1",
                )
                var gotLine = false
                process.inputStream.bufferedReader().forEachLine { line ->
                    gotLine = true
                    log(line)
                }
                val rc = process.waitFor()

                state.exitCode = rc
                log("", LogTone.NORMAL)
                log("[*] 脚本退出码 $rc", LogTone.INFO)
                if (rc == 0) {
                    runOnUiThread {
                        state.currentStep = STEP_TITLES.lastIndex
                        state.stepFailed = false
                    }
                    log("[✓] KSU_ROOT_OK root 已就绪（u:r:ksu:s0）", LogTone.OK)
                } else {
                    runOnUiThread { state.stepFailed = true }
                    log("[!] 未成功，看上面最后几行；重启后再点一次通常就行", LogTone.ERROR)
                    // 兜底：不管 UI 那边有没有显示出来，都把设备端两份日志贴进来
                    if (!gotLine) log("[!] 一条输出都没收到，直接抓设备端日志：", LogTone.WARN)
                    dumpDeviceLogs()
                }
            } catch (t: Throwable) {
                log("[!] ${t.javaClass.simpleName}: ${t.message}", LogTone.ERROR)
            } finally {
                withContext(Dispatchers.Main) { state.running = false }
            }
        }
    }

    // ---------------- 根管理器 ----------------

    private fun refreshManagers() {
        lifecycleScope.launch(Dispatchers.IO) {
            val list = runCatching {
                ManagerCatalog.scan(this@MainActivity, packageName)
            }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) { state.managers = list }
        }
    }

    /** 拉取设备端真实状态：fakeR.log、残留进程、内核与 SELinux —— 卡在第二步时用它 */
    private fun pullDeviceLog() {
        if (state.shizuku != ShizukuState.READY) {
            log("[!] 需要先授权 Shizuku 才能读取设备端状态", LogTone.WARN)
            state.sheetVisible = true
            return
        }
        state.sheetVisible = true
        lifecycleScope.launch(Dispatchers.IO) {
            log("[*] 读取设备端状态…", LogTone.INFO)
            val cmd = "echo '--- uname -r ---'; uname -r; " +
                "echo '--- getenforce ---'; getenforce 2>&1; " +
                "echo '--- timer98260 进程 ---'; ps -A 2>/dev/null | grep '[t]imer98260' || echo '(无)'; " +
                "echo '--- fakeR.log 最后 40 行 ---'; tail -n 40 /data/local/tmp/fakeR.log 2>&1; " +
                "echo '--- run.log 最后 40 行 ---'; tail -n 40 /data/local/tmp/run.log 2>&1"
            try {
                val out = ShizukuRunner.run(cmd)
                if (out.isBlank()) {
                    log("    (没有输出)", LogTone.WARN)
                } else {
                    out.lineSequence().forEach { line -> log(line) }
                }
            } catch (t: Throwable) {
                log("[!] 读取失败：${t.javaClass.simpleName}: ${t.message}", LogTone.ERROR)
            }
            log("[*] 读取完毕", LogTone.INFO)
        }
    }

    /**
     * 失败兜底：直接把设备端 run.log / fakeR.log 的尾巴拉进日志区。
     * 目的是「不管脚本那边的输出有没有传过来，界面上必须有东西可看」。
     */
    private fun dumpDeviceLogs() {
        runCatching {
            val out = ShizukuRunner.run(
                "echo '--- run.log 最后 30 行 ---'; tail -n 30 /data/local/tmp/run.log 2>&1; " +
                    "echo '--- fakeR.log 最后 25 行 ---'; tail -n 25 /data/local/tmp/fakeR.log 2>&1; " +
                    "echo '--- 进程 / 内核 ---'; " +
                    "ps -A 2>/dev/null | grep '[t]imer98260' || echo '(timer98260 未运行)'; " +
                    "uname -r; getenforce 2>&1",
            )
            out.lineSequence().forEach { l -> log("    $l") }
        }.onFailure { t ->
            log("[!] 抓设备端日志失败：${t.javaClass.simpleName}: ${t.message}", LogTone.WARN)
        }
    }

    /** 手动刷新设备信息：本地 os.version + Shizuku 的补充信息 */
    private fun refreshDevice() {
        state.device = readDevice()
        refreshManagers()
        if (state.shizuku == ShizukuState.READY) {
            loadDevice()
        } else {
            log("[*] 设备信息已从系统读取（Shizuku 未授权，跳过 uname 校验）", LogTone.INFO)
        }
        state.sheetVisible = true
    }

    private fun importApk(uri: android.net.Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val imported = KsudImporter.stage(this@MainActivity, uri)
                withContext(Dispatchers.Main) {
                    state.imported = imported
                    state.selectedPackage = imported.packageName
                    state.pickerVisible = false
                    log("[✓] 已导入 ${imported.label} ${imported.version}", LogTone.OK)
                    log("    ${imported.packageName} · 运行时用它的 libksud.so", LogTone.NORMAL)
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    log("[!] 导入失败：${t.message}", LogTone.ERROR)
                }
            }
        }
    }

    /** 按选择把 ksud 推到 /data/local/tmp/ksud：导入的 APK > 已装管理器的 libksud.so > 内置 */
    private fun pushKsud(out: (String) -> Unit) {
        val imported = state.imported
        val candidate = state.chosenManager
        val libPath = candidate?.libPath

        when {
            imported != null && imported.raw && imported.file.isFile -> {
                imported.file.inputStream().use {
                    ShizukuRunner.pushStream(it, "/data/local/tmp/ksud")
                }
                out("    ✓ ksud ← 导入的裸二进制（${imported.file.length() / 1024} KB）")
            }

            imported != null && imported.file.isFile -> {
                val (zip, stream) = KsudImporter.openKsud(imported.file)
                try {
                    ShizukuRunner.pushStream(stream, "/data/local/tmp/ksud")
                } finally {
                    zip.close()
                }
                out("    ✓ ksud ← 导入的 ${imported.label} ${imported.version}")
            }

            libPath != null -> {
                java.io.File(libPath).inputStream().use {
                    ShizukuRunner.pushStream(it, "/data/local/tmp/ksud")
                }
                out("    ✓ ksud ← ${candidate?.label} ${candidate?.version} 的 libksud.so")
            }

            else -> {
                ShizukuRunner.pushAsset(this, "ksud", "/data/local/tmp/ksud")
                out("    ✓ ksud ← 内置（KernelSU 32707）")
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
