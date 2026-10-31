package com.minis.pd2546root

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MainActivity : ComponentActivity() {

    private val logs = mutableStateListOf<String>()
    private var running by mutableStateOf(false)
    private var shizukuReady by mutableStateOf(false)
    private var granted by mutableStateOf(false)
    private var kernel by mutableStateOf("")

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { _, result ->
            granted = result == PackageManager.PERMISSION_GRANTED
            if (granted) loadKernel()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { Screen() }

        Shizuku.addBinderReceivedListenerSticky { refreshShizuku() }
        Shizuku.addBinderDeadListener { refreshShizuku() }
        Shizuku.addRequestPermissionResultListener(permissionListener)
        refreshShizuku()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }

    // ---------------- 状态 ----------------

    private fun refreshShizuku() {
        shizukuReady = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        granted = if (shizukuReady) {
            runCatching {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
        } else false
        if (granted) loadKernel()
    }

    private fun loadKernel() {
        lifecycleScope.launch(Dispatchers.IO) {
            val k = runCatching { ShizukuRunner.run("uname -r") }
                .getOrNull().orEmpty().trim()
            withContext(Dispatchers.Main) { kernel = k }
        }
    }

    private fun askPermission() {
        runCatching { Shizuku.requestPermission(0) }
            .onFailure { append("[!] 请求权限失败：${it.message}") }
    }

    private fun append(line: String) {
        runOnUiThread { logs.add(line) }
    }

    // ---------------- 一键流程 ----------------

    private fun startRoot() {
        if (running) return
        if (!shizukuReady) {
            append("[!] Shizuku 没连上：先去把 Shizuku 启动起来")
            return
        }
        if (!granted) {
            askPermission()
            return
        }

        running = true
        logs.clear()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                append("[*] 推送 payload 到 /data/local/tmp")
                for (name in ShizukuRunner.PAYLOAD) {
                    ShizukuRunner.pushAsset(this@MainActivity, name, "/data/local/tmp/$name")
                    append("    ✓ $name")
                }

                append("[*] 开始执行 reroot-local.sh（几分钟，中途重启属正常）\n")
                val p = ShizukuRunner.start(
                    "chmod 755 /data/local/tmp/timer98260 /data/local/tmp/ksud " +
                        "/data/local/tmp/probe /data/local/tmp/fake_shell.sh " +
                        "/data/local/tmp/reroot-local.sh; " +
                        "sh /data/local/tmp/reroot-local.sh 2>&1"
                )
                p.inputStream.bufferedReader().forEachLine { line -> append(line) }
                val rc = p.waitFor()
                append("")
                append("[*] 脚本退出码 $rc")
                if (rc == 0) append("[✓] KSU root 就绪")
            } catch (t: Throwable) {
                append("[!] ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                withContext(Dispatchers.Main) { running = false }
            }
        }
    }

    // ---------------- UI ----------------

    @Composable
    private fun Screen() {
        MiuixTheme {
            Scaffold(topBar = { TopAppBar(title = "PD2546 一键 Root") }) { padding ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp)
                ) {
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            "Shizuku：" + when {
                                !shizukuReady -> "未连接"
                                granted -> "已授权"
                                else -> "已连接，未授权"
                            }
                        )
                        Text("内核：" + kernel.ifBlank { "—" })
                        Text(
                            "目标 build：6.12.58-android16-6-gdd6fd5c98918",
                            fontSize = 11.sp
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = { if (shizukuReady && !granted) askPermission() else startRoot() },
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            when {
                                running -> "运行中…"
                                !granted -> "授权 Shizuku"
                                else -> "一键提权"
                            }
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Card(Modifier.fillMaxWidth().weight(1f)) {
                        val scroll = rememberScrollState()
                        LaunchedEffect(logs.size) {
                            if (scroll.maxValue > 0) scroll.animateScrollTo(scroll.maxValue)
                        }
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(scroll)
                        ) {
                            if (logs.isEmpty()) {
                                Text(
                                    "日志会显示在这里。\n冷重启后 root 会失效，再点一次即可。",
                                    fontSize = 12.sp
                                )
                            }
                            logs.forEach { line ->
                                Text(line, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }
    }
}
