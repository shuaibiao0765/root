# PD2546 一键 Root（Miuix 版）

给未 root 的 vivo 设备用，UI 用 [Miuix](https://github.com/miuix-kotlin/miuix)（HyperOS 风格），
权限走 Shizuku（uid=2000 的 shell），提权逻辑就是包里的 `reroot-local.sh`。

## 它做什么

点一下“一键提权”后：

1. 申请 Shizuku 权限（第一次会让你在 Shizuku 里点允许）
2. 把 assets 里的 6 个文件推到 `/data/local/tmp`
3. `chmod 755` 后执行 `sh /data/local/tmp/reroot-local.sh`
4. 把脚本输出实时刷到界面

真正的提权步骤（清 vr funcs / 清 pre_handler / 截断 notifier / 生成 kallsyms / ksud late-load）
都在那个 shell 脚本里，App 只负责“一键”和把日志显示出来。

成功标志：日志最后出现 `KSU_ROOT_OK  root 已就绪（u:r:ksu:s0）`。

## 编译

> ⚠️ 这个工程是在手机上的 Linux 沙箱里写的，那边 JVM 跑不起来（execmem 被内核拦），
> 所以**没有在这里编译验证过**。下面是标准构建方式，编译报错把日志发我。

### 方式 A：Android Studio（推荐）

1. Android Studio 打开本目录
2. 等 Gradle Sync（会自动下 AGP 8.13.2 / Kotlin 2.3.20 / Miuix 0.8.8）
3. `Build → Build APK(s)`，产物在 `app/build/outputs/apk/release/`

### 方式 B：命令行

```sh
gradle wrapper --gradle-version 8.14.3   # 生成 gradlew（首次）
./gradlew assembleRelease
```

需要 JDK 17、Android SDK（compileSdk 36、build-tools 36）。

### 方式 C：GitHub Actions（不用装任何东西）

把仓库推到 GitHub，`.github/workflows/build.yml` 会自动构建，
在 Actions 页面的 Artifacts 里下 `app-release.apk`。

## 版本组合

| 组件 | 版本 |
|---|---|
| AGP | 8.13.2 |
| Kotlin | 2.3.20（含 compose compiler plugin） |
| Gradle | 8.14.3 |
| Miuix | 0.8.8 |
| Shizuku API | 13.1.5 |
| compileSdk / targetSdk | 36 |

## 关键实现点

- **Shizuku 13 把 `newProcess` 改成了 private**，`ShizukuRunner` 用反射调用
  `newProcess(String[], String[], String)`，返回的 `ShizukuRemoteProcess` 就是个 `Process`。
- 推文件不走 `/sdcard`，直接把 asset 写进远端进程 stdin（`cat > /data/local/tmp/xxx`），
  避免 app 私有目录和 shell 用户互不可读。
- 冷重启后 root 失效、Shizuku 也会掉，重新启动 Shizuku 再点一次即可。
- 界面常亮（`FLAG_KEEP_SCREEN_ON`），因为流程要跑几分钟。

## 注意

- 脚本第一步就核对内核 build：不是 `6.12.58-android16-6-gdd6fd5c98918` 会拒绝执行。
  机型不限，内核串一致就行。
- 管理器的 uapi 版本要对：`KernelSU_v3.3.1-27-gdb6263d3_32707`（uapi 6）。
- 这是 per-boot 临时 root，冷重启掉、软重启不掉。
