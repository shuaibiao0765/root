# PD2546 一键 Root（GhostLock 同款 UI）

给未 root 的 vivo 设备用。**界面直接照搬 [YuKongA/ghostlock-app](https://github.com/YuKongA/ghostlock-app)**（Apache-2.0）：
同一套 Miuix（HyperOS 风格）组件、同一个状态卡 + 设备信息卡 + 偏好设置 + 日志浮层的结构，
变的只是把 GhostLock 的 kernel profile 换成本机的 `reroot-local.sh`。

权限仍然走 Shizuku（uid=2000 的 shell）。

## 它做什么

点「一键提权」之后：

1. 申请 Shizuku 权限（第一次会让你在 Shizuku 里点允许）
2. 把 assets 里的 6 个文件推到 `/data/local/tmp`
3. `chmod 755` 后执行 `sh /data/local/tmp/reroot-local.sh`
4. 脚本输出实时刷进底部日志浮层（可复制）

真正的提权步骤（清 vr funcs / 清 pre_handler / 截断 notifier / 生成 kallsyms / `ksud late-load`）
都在 shell 脚本里，App 只负责“一键”+ 把日志显示出来。

成功标志：日志最后出现 `KSU_ROOT_OK  root 已就绪（u:r:ksu:s0）`。

## 界面（v2.0）

- **状态卡**：Shizuku 未连接 / 需要授权 / 正在读内核 / 内核不匹配 / 已就绪 / 执行中，
  配色沿用 GhostLock 的绿-琥珀-红三档，右侧超大水印图标 `offset(27dp, 31dp)` 裁切
- **设备信息卡**：设备 / 芯片 / 系统 / 内核（`uname -r`），可长按选择复制
- **设置**：执行日志、清理 `/data/local/tmp`、强制在未验证内核上运行、执行时保持唤醒
- **执行按钮**：不可执行时点按钮会直接弹日志浮层告诉你怎么解卡
- **日志浮层**：`OverlayBottomSheet`，执行中不可下滑关闭；按行着色（绿=成功 红=失败 黄=警告 蓝=步骤）

## 工具链（必须对齐）

Miuix 0.9.4 的 AAR 元数据写着 `minCompileSdk=37` + `minAndroidGradlePluginVersion=9.1.0`，
所以这套工程锁在：

| 组件 | 版本 |
| --- | --- |
| AGP | 9.1.0（AGP 9 自带 Kotlin 编译，不再 apply kotlin-android） |
| Kotlin / Compose 插件 | 2.4.20 |
| Gradle | 9.7.1 |
| compileSdk / targetSdk | 37 / 37，minSdk 31 |
| Miuix | `miuix-ui` + `miuix-preference` 0.9.4 |
| Shizuku | api + provider 13.1.5 |

## 编译

沙箱里 JVM 起不来（PRoot 下 execmem 被内核拦），所以用 GitHub Actions：

```sh
git push          # 触发 .github/workflows/build.yml
# 产物：Actions → 最新 run → Artifacts → app-release
```

Android Studio 打开也能直接编译（需要 SDK 37）。

## 与 GhostLock 的差异

- 保留原项目里的 `ShizukuRunner`（Shizuku 13 把 `newProcess` 收回私有，走反射）
- 新增「内核不匹配」拦截 + 强制开关：内置符号表只对 `6.12.58-android16-6-gdd6fd5c98918` 有效
- 新增清理功能，避免 `/data/local/tmp` 里留 payload
- 没搬 GhostLock 的 kernel profile 解析、配置导入、CPU 核心组选择——那些跟它的 exploit 绑定
