package com.minis.pd2546root

import android.content.Context
import rikka.shizuku.Shizuku

/**
 * 用 Shizuku 拿到的 uid=2000(shell) 执行命令。
 * Shizuku 13 把 newProcess 收回成私有方法，这里走反射，参数和签名都没变。
 */
object ShizukuRunner {

    /** 需要推送到 /data/local/tmp 的文件，顺序无所谓 */
    val PAYLOAD = listOf(
        "reroot-local.sh",
        "timer98260",
        "ksud",
        "probe",
        "fake_shell.sh",
        "ksu_stage.sh",
        "symbols.txt",
    )

    private val newProcess: java.lang.reflect.Method by lazy {
        Shizuku::class.java
            .getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            .apply { isAccessible = true }
    }

    fun start(cmd: String): Process =
        newProcess.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process

    fun run(cmd: String): String {
        val p = start(cmd)
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        return out
    }

    /** 把 asset 直接灌进远端进程的 stdin，避开私有目录互相不可读的问题 */
    fun pushAsset(ctx: Context, asset: String, remote: String) {
        ctx.assets.open(asset).use { pushStream(it, remote) }
    }

    /** 任意来源（assets / 别的 App 的 nativeLibraryDir / APK 里的 entry）都走这里 */
    fun pushStream(input: java.io.InputStream, remote: String) {
        val p = start("cat > $remote")
        p.outputStream.use { out -> input.copyTo(out, 64 * 1024) }
        val rc = p.waitFor()
        require(rc == 0) { "写入 $remote 失败，rc=$rc" }
    }
}
