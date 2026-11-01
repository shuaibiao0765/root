package com.minis.pd2546root

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * 找设备上已安装的 KernelSU 家族管理器，并解析它们的 libksud.so。
 * 思路和 ghostlock 的 ManagerScanner 一致：谁带 libksud.so，谁就能当 root 管理器；
 * 但“是不是管理器”和“能不能拿到 ksud”分开判断——很多管理器把 ksud 打包在 APK 里
 * （extractNativeLibs=false），nativeLibraryDir 下并没有文件。
 */
object ManagerCatalog {

    /** 内置的 KernelSU 家族包名，顺序即优先级 */
    val BUILTIN = listOf(
        "me.weishu.kernelsu.pr",
        "me.weishu.kernelsu",
        "com.resukisu.resukisu",
        "org.bakasu.bakasu",
        "com.kowx712.supermanager",
    )

    /** 包名里带这些词的也当管理器，防止 fork 用了自定义包名 */
    private val NAME_HINTS = listOf("kernelsu", "sukisu", "bakasu", "kowsu", "supermanager", "ksunext")

    data class Candidate(
        val packageName: String,
        val label: String,
        val versionName: String,
        val versionCode: Long,
        val libPath: String?,
        val builtin: Boolean,
    ) {
        val hasKsud: Boolean get() = libPath != null

        /** 版本号：优先 versionName，没有就用 versionCode */
        val version: String
            get() = versionName.takeIf { it.isNotBlank() }?.let { "v$it" }
                ?: ("v" + versionCode)
    }

    fun looksLikeManager(packageName: String): Boolean =
        packageName in BUILTIN || NAME_HINTS.any { packageName.contains(it, ignoreCase = true) }

    /** 扫描非系统应用；已知 KSU 家族、包名像管理器、或真带 libksud.so 的都算 */
    fun scan(ctx: Context, selfPackage: String): List<Candidate> {
        val pm = ctx.packageManager
        return pm.getInstalledPackages(0)
            .asSequence()
            .mapNotNull { pi -> pi.applicationInfo?.let { pi to it } }
            .filter { (pi, _) -> pi.packageName != selfPackage }
            .filter { (_, ai) -> (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { (pi, ai) ->
                val lib = ai.nativeLibraryDir?.let { dir -> File(dir, "libksud.so") }
                Candidate(
                    packageName = pi.packageName,
                    label = runCatching { ai.loadLabel(pm).toString() }
                        .getOrDefault(pi.packageName),
                    versionName = pi.versionName.orEmpty(),
                    versionCode = pi.longVersionCode,
                    libPath = lib?.takeIf { it.isFile }?.absolutePath,
                    builtin = pi.packageName in BUILTIN,
                )
            }
            .filter { it.builtin || it.hasKsud || looksLikeManager(it.packageName) }
            .sortedWith(
                compareByDescending<Candidate> { it.builtin }
                    .thenByDescending { it.hasKsud }
                    .thenBy { it.label.lowercase() },
            )
            .toList()
    }
}

/**
 * 取 ksud 的两条路：
 *  1. 管理器 APK —— ksud 以 lib/arm64-v8a/libksud.so 打包（名字叫 .so，其实是 aarch64 ELF 可执行文件）
 *  2. 裸二进制 —— 官方 release 页直接给 ksud-aarch64-linux-android
 */
object KsudImporter {

    /** 不同管理器/打包方式的落点，按顺序找 */
    val ENTRIES = listOf(
        "lib/arm64-v8a/libksud.so",
        "lib/arm64/libksud.so",
        "lib/arm64-v8a/ksud",
        "assets/ksud",
        "assets/libksud.so",
        "assets/bin/ksud",
    )

    data class Imported(
        val packageName: String,
        val label: String,
        val versionName: String,
        val file: File,
        val raw: Boolean,
    ) {
        val version: String
            get() = when {
                raw -> "裸 ksud"
                versionName.isNotBlank() -> "v$versionName"
                else -> "已导入"
            }
    }

    fun stage(ctx: Context, uri: Uri): Imported {
        val dir = File(ctx.cacheDir, "ksud").apply { mkdirs() }
        // 只留一份，避免旧文件占空间
        dir.listFiles()?.forEach { runCatching { it.delete() } }

        val tmp = File(dir, "import.bin")
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
        } ?: error("读不到所选文件")

        if (!tmp.isFile || tmp.length() < 4096) error("文件太小，既不是 APK 也不是 ksud")

        // 裸 ELF：直接当 ksud 用
        if (isElf(tmp)) {
            return Imported(packageName = "", label = "自定义 ksud", versionName = "", file = tmp, raw = true)
        }

        val entry = runCatching {
            ZipFile(tmp).use { zip -> ENTRIES.firstOrNull { zip.getEntry(it) != null } }
        }.getOrNull() ?: error("APK 里没找到 ksud（找过：${ENTRIES.joinToString("、")}）")

        val info = ctx.packageManager.getPackageArchiveInfo(tmp.absolutePath, 0)
            ?: error("解析 APK 失败")
        val ai = info.applicationInfo
        ai?.sourceDir = tmp.absolutePath
        ai?.publicSourceDir = tmp.absolutePath
        val label = ai?.let { runCatching { it.loadLabel(ctx.packageManager).toString() }.getOrNull() }
            ?: info.packageName

        val apk = File(dir, "imported.apk")
        runCatching { tmp.renameTo(apk) }

        return Imported(
            packageName = info.packageName,
            label = label,
            versionName = info.versionName.orEmpty(),
            file = if (apk.isFile) apk else tmp,
            raw = false,
        )
    }

    fun openKsud(apk: File): Pair<ZipFile, InputStream> {
        val zip = ZipFile(apk)
        val entry = ENTRIES.firstOrNull { zip.getEntry(it) != null } ?: run {
            zip.close()
            error("APK 里没找到 ksud")
        }
        return zip to zip.getInputStream(entry)
    }

    private fun isElf(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(4)
            input.read(head) == 4 &&
                head[0] == 0x7F.toByte() && head[1] == 'E'.code.toByte() &&
                head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte()
        }
    }.getOrDefault(false)
}
