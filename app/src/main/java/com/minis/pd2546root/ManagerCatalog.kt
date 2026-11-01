package com.minis.pd2546root

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.io.File

/**
 * 找设备上已安装的 KernelSU 家族管理器，并解析它们的 libksud.so。
 * 思路和 ghostlock 的 ManagerScanner 一致：谁带 libksud.so，谁就能当 root 管理器。
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

    data class Candidate(
        val packageName: String,
        val label: String,
        val versionName: String,
        val libPath: String?,
        val builtin: Boolean,
    ) {
        val hasKsud: Boolean get() = libPath != null
        val version: String get() = versionName.ifBlank { "未知版本" }
    }

    /** 扫描非系统应用；带 libksud.so 的排前面，其次内置家族，最后按名字 */
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
                    libPath = lib?.takeIf { it.isFile }?.absolutePath,
                    builtin = pi.packageName in BUILTIN,
                )
            }
            .sortedWith(
                compareByDescending<Candidate> { it.builtin && it.hasKsud }
                    .thenByDescending { it.hasKsud }
                    .thenBy { it.label.lowercase() },
            )
            .toList()
    }
}

/** 从用户选的 APK 里取 lib/arm64-v8a/libksud.so，随便哪个 KernelSU 版本都能用 */
object KsudImporter {

    const val ENTRY = "lib/arm64-v8a/libksud.so"

    data class Imported(
        val packageName: String,
        val label: String,
        val versionName: String,
        val apk: File,
    ) {
        val version: String get() = versionName.ifBlank { "未知版本" }
    }

    /** 把 SAF 给的 Uri 复制进 cacheDir，顺便拿到包名/版本 */
    fun stage(ctx: Context, uri: android.net.Uri): Imported {
        val dir = File(ctx.cacheDir, "ksud").apply { mkdirs() }
        val apk = File(dir, "imported.apk")
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            apk.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
        } ?: error("读不到所选文件")

        if (!apk.isFile || apk.length() < 1024) error("文件为空或不是 APK")
        if (!containsKsud(apk)) error("这个 APK 里没有 $ENTRY（不是 KernelSU 家族管理器？）")

        val info = ctx.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            ?: error("解析 APK 失败")
        val ai = info.applicationInfo
        ai?.sourceDir = apk.absolutePath
        ai?.publicSourceDir = apk.absolutePath
        val label = ai?.let { runCatching { it.loadLabel(ctx.packageManager).toString() }.getOrNull() }
            ?: info.packageName
        return Imported(
            packageName = info.packageName,
            label = label,
            versionName = info.versionName.orEmpty(),
            apk = apk,
        )
    }

    private fun containsKsud(apk: File): Boolean =
        runCatching { java.util.zip.ZipFile(apk).use { it.getEntry(ENTRY) != null } }
            .getOrDefault(false)

    /** 打开 APK 里的 ksud 流；调用方负责 close ZipFile 之后再用不行，必须边读边写 */
    fun openKsud(apk: File): Pair<java.util.zip.ZipFile, java.io.InputStream> {
        val zip = java.util.zip.ZipFile(apk)
        val entry = zip.getEntry(ENTRY) ?: run {
            zip.close()
            error("APK 里没有 $ENTRY")
        }
        return zip to zip.getInputStream(entry)
    }
}
