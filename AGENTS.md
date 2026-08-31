# YknBoss 项目工作约定

本文件适用于整个 `C:\Users\y\IdeaProjects\yknboss` 项目。后续 Codex 或其他自动化代理在本项目内工作时，应以这里记录的环境、命令和验证流程为准，并保留用户尚未提交的修改。

## 固定环境

- 操作系统：Windows。
- JDK：JDK 25，目录为 `C:\java\25`，Java 可执行文件为 `C:\java\25\bin\java.exe`。
- 构建系统：项目自带 Gradle Wrapper；禁止用系统中不确定版本的 Gradle 替代 `gradlew.bat`。
- Gradle 缓存：固定使用项目目录下的 `.gradle-user-home`。
- 目标服务端：Paper 1.21.10 及以上。
- 默认服务端目录：`F:\mc服务端\Paper-1.21.10-Inf-Skyblock`。
- 插件依赖：`libs\bettermodel-3.4.1-paper.jar`、`libs\Multiverse-Core-5.7.3.jar`；Paper API 与 ProGuard 由 Gradle/Maven 仓库解析。

## 网络与权限

- 项目级 `.codex\config.toml` 已在 `workspace-write` 沙箱中启用网络，供 Gradle/Maven 下载依赖。
- 项目已经在用户 Codex 配置中标记为可信。项目网络配置通常在重新加载工作区或新建任务后生效。
- 若上层 managed policy 仍显示网络受限，应按正常权限流程请求网络访问，不得绕过沙箱或泄露凭据。
- 若 Codex 沙箱内的 JDK 25 在关闭依赖 JAR 时抛出 `ZipFileSystemProvider.removeFileSystem` / `AccessDeniedException`，应通过已限定为 `.\deploy.cmd` 的批准命令前缀重跑同一入口；不要删除依赖缓存，也不要临时改写编译类路径。
- 写入服务端目录属于工作区外操作。只有用户明确要求替换/部署时才执行完整部署；普通开发验证只运行 `-BuildOnly`。

## 唯一发布入口

在项目根目录运行：

```powershell
.\deploy.cmd
```

该命令固定使用 JDK 25，依次完成干净构建、ProGuard 混淆、BetterModel 模型烘焙与打包、产物校验、旧文件备份和服务端部署。完整部署前必须关闭目标服务端；脚本会检查 Java 进程、服务端端口以及 Paper remap 文件占用，检测到运行状态时应立即失败。

只构建、混淆和验证，不修改服务端：

```powershell
.\deploy.cmd -BuildOnly
```

部署到另一个兼容服务端时可显式指定：

```powershell
.\deploy.cmd -ServerRoot 'D:\path\to\paper-server'
```

底层等价构建命令如下，排查 Gradle 问题时才单独使用：

```powershell
$env:JAVA_HOME = 'C:\java\25'
$env:GRADLE_USER_HOME = (Join-Path (Get-Location) '.gradle-user-home')
$env:PATH = 'C:\java\25\bin;' + $env:PATH
.\gradlew.bat --no-daemon clean obfuscate betterModelPack craftEnginePack itemsAdderPack
```

## 发布产物

- 混淆插件：`build\libs\YknBoss-<version>-obfuscated.jar`。
- BetterModel 包：`build\distributions\YknBoss-BetterModel-Pack-<version>.zip`。
- CraftEngine 包：`build\distributions\YknBoss-CraftEngine-Pack-<version>.zip`。
- ItemsAdder 包：`build\distributions\YknBoss-ItemsAdder-Pack-<version>.zip`。
- ProGuard 映射：`build\obfuscation\YknBoss-<version>-mapping.txt`，仅用于排查混淆后的堆栈，不部署到服务端。
- 部署备份：目标服务端 `backups\yknboss-deploy\<时间>-<版本>`；不要无故删除备份，也不要把备份 JAR 留在 `plugins` 树内。

版本以 `build.gradle.kts` 的 `version` 为唯一来源。准备正式发布的新版本时先更新该版本号，确保 JAR 文件名与 JAR 内 `plugin.yml` 的版本一致。

## 模型文件约定

- 阿努赫可编辑源模型：`modle\endsun\bettermodel\endsun_anuhe.bbmodel`。
- 阿努赫打包模型：`packs\bettermodel\models\endsun_anuhe.bbmodel`，由 `bakeEndsunModel` 自动生成，不直接手工编辑。
- 骨龙打包模型：`packs\bettermodel\models\skeleton_dragon.bbmodel`。
- 上古法典运行时只使用 `SpecialItemManager` 管理的原生书本 `ItemDisplay`，不再显示符文环，也不依赖 BetterModel。不得把高面数书本或符文环重新放回 BetterModel。
- 旧 `BetterModel/models/ancient_codex.bbmodel` 与 `ancient_codex_rings.bbmodel` 均已退役，部署脚本会先备份再移除服务端旧文件。原始 GLTF/BBModel 继续保留在 `modle\flying_magic_book` 作为书本美术素材。
- 阿努赫构建步骤会把 1.5 倍体型烘焙进几何和位置动画，并生成固定总根；不得对已经烘焙的输出再次缩放。
- Blockbench 中完成用户修改后，先保存源模型，再运行构建生成打包模型。

## 每次修改后的验证流程

1. 运行 `.\deploy.cmd -BuildOnly`，确认 Java 25、编译、ProGuard 和 BetterModel 打包全部成功。
2. 确认混淆 JAR 可读取、包含 `plugin.yml`，并且文件名版本与 `plugin.yml` 版本一致。
3. 确认 BetterModel ZIP 同时包含 `models/endsun_anuhe.bbmodel` 和 `models/skeleton_dragon.bbmodel`；涉及特殊物品时，同时确认 CraftEngine 与 ItemsAdder ZIP 含对应物品定义、模型和贴图。
4. 确认阿努赫打包模型只有一个固定总根，根 UUID 为 `7c58f8ac-2c0d-4abe-b64d-1bb08e0fe3aa`，烘焙比例标记为 `1.5`。
5. 记录脚本输出的插件、BetterModel、CraftEngine 与 ItemsAdder 包 SHA-256。不得部署未通过上述校验的产物。
6. 用户明确要求部署时，先关闭服务端，再运行 `.\deploy.cmd`。不要在服务器运行时覆盖插件或 BetterModel 模型。
7. 启动服务端后确认控制台加载的是预期 `YknBoss` 版本，且没有 BetterModel tracker、动画线程、缺失模型或配置解析异常。
8. 游戏内至少验证：骨龙和阿努赫各召唤一次；Boss 会索敌、移动、受伤并正确刷新阶段血条；阿努赫四肢与镰刀不会缩小或错位；P1/P2/P3 转阶段锁血和回血正确；双生蚀影可受伤且血条独立。
9. BetterModel 的 `min-sight` 推荐为 `0`，避免近战距离停止模型更新；部署脚本只会警告，不会擅自覆盖现有 BetterModel 配置。
10. 若运行时验证失败，保留 `logs\latest.log`、混淆 mapping、失败版本 JAR 的 SHA-256，并从 `backups\yknboss-deploy` 恢复上一版后再定位问题。

## 修改纪律

- 优先使用 `rg` 搜索；编辑源码使用补丁，避免覆盖无关的用户修改。
- 不运行 `git reset --hard`、`git checkout --` 或宽范围删除命令。
- 不直接编辑构建目录中的产物，也不把 `build`、`.gradle` 或 `.gradle-user-home` 提交为源码。
- 配置项变更应保留旧配置兼容和默认值；所有玩家可见文本继续放在配置文件中。
- 未经用户明确要求，不启动、停止或重启服务端，不执行完整部署，也不删除服务端世界、插件配置或备份。
