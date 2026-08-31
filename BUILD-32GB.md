# 32GB 机器构建说明

当前工作树已迁移到新机器本地 SSD：

`E:\codex\AssetKing`

不要直接在 SMB 共享路径上构建；项目源码、Gradle 工作目录和发布产物统一放在本地 SSD。

## 环境

- JDK 17
- Android SDK Platform 35
- Android SDK Build-Tools 34.0.0
- 项目自带 Gradle Wrapper（Gradle 8.10.2）

在项目根目录生成本机 SDK 配置，例如：

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
"sdk.dir=$($sdk -replace '\\','\\\\')" | Set-Content -Encoding UTF8 local.properties
```

正式签名所需的 `release-signing` 文件已随工作树复制；不要把其中的密码写入脚本、日志或提交。

## 可观测构建

先确认系统内存充足，再运行：

```powershell
New-Item -ItemType Directory -Force E:\codex\AssetKing\artifacts\build-logs | Out-Null
.\gradlew.bat :app:compileDebugKotlin --console=plain --info --profile 2>&1 | Tee-Object E:\codex\AssetKing\artifacts\build-logs\assets-king-compile-debug.log
```

回显中重点看：

- `BUILD SUCCESSFUL` 或失败任务；
- `executed / up-to-date / from-cache`；
- `full rebuild`、`classpathChanges`；
- `Connection refused` / Kotlin daemon registry；
- `build/reports/profile` 里的 Top-5 任务。

编译通过后再生成快速包：

```powershell
.\gradlew.bat :app:assembleRelease --console=plain --info --profile 2>&1 | Tee-Object E:\codex\AssetKing\artifacts\build-logs\assets-king-assemble-release.log
```

生成的 APK 在 `app\build\outputs\apk\release\app-release.apk`。先校验版本 `0.1.12 / versionCode 13`、正式签名和 `debuggable=false`，再复制到 `release-assets` 供 iQOO 15U 验收。

不要执行 `clean`、`--no-daemon`、关闭增量编译或把 Kotlin daemon 调到 6GB；如果可用内存持续降到约 3GB，应停止构建并保留日志。
