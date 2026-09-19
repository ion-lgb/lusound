# 测试

本文说明测试环境：第一节是不需要设备的 JVM 单元测试，其余部分说明需要真实设备、隔离媒体服务器及外部音频样本的仪器测试。构建方法见 [README](../README.md#构建)。

## JVM 单元测试

纯逻辑测试位于 `app/src/test/kotlin/`，随普通构建运行，不需要设备、网络或外部样本：

```sh
./gradlew :app:testDebugUnitTest
```

覆盖范围：LRC 解析与歌词候选匹配（`LyricsTest`、`MetadataMatchTest`）、旁置歌词的名称匹配与编码解码（`SidecarLyricsTest`）、内嵌歌词的容器嗅探与 ID3 `USLT` 解码（`EmbeddedLyricsTest`）、元数据指纹（`MetadataFingerprintTest`）、**元数据缓存与合并规则**（`MetadataMergeTest`）、**库删除范围规则**（`LibraryReconciliationTest`）、**服务器同步的映射与歌单决策**（`CloudSyncPlanTest`）、**跨来源文件身份与去重选择**（`FileIdentityTest`）、NCM 容器头部解析与封面跳过（`NcmHeaderTest`）、NCM 位置异或解密与随机访问等价性（`NcmCryptoTest`）、加密扩展名判定（`EncryptedAudioTest`）、服务器地址规范化与凭据放置规则（`CloudAuthTest`）、**服务器标记到凭据的解析链路**（`SavedServerInterceptorTest`）、四类协议的请求序列与失败路径（`HttpRetryTest`、`SubsonicClientTest`、`PlexClientTest`、`JellyfinClientTest`）、来源与容器模型（`TrackSourceTest`）、音质单位换算与"未知即未知"的展示规则（`TrackQualityTest`）、FLAC 头部读出采样率与位深（`FlacStreamInfoTest`）、歌单重排（`PlaylistOrderTest`）、数据库迁移 SQL（`TrackSourceMigrationTest`、`TrackQualityMigrationTest`、`TrackIdentityMigrationTest`）。

约定：只放不触碰 Android 框架的逻辑。需要 `Context`、内容提供程序、解码器、Keystore、真实服务或真实音频的验证一律放仪器测试。`metadata/Lyrics.kt` 的 `decodeLyricHtml` 就是为这条边界留的接缝：`parseLyrics` / `usableLyrics` 接受一个行解码函数，单元测试传恒等函数，生产路径仍使用 Android 的 HTML 实体解码。

`CloudAuthTest` 同时断言 `X-Plex-Version` 等请求头取自 `BuildConfig.VERSION_NAME`，因此版本号再次被硬编码时该测试会失败。

### 迁移测试为什么能跑在 JVM 上

`TrackSourceMigrationTest` 用 `org.xerial:sqlite-jdbc`（仅测试依赖，不进 APK）在内存里建出 v4 结构、执行 `trackSourceMigration()` 返回的真实语句，然后断言回填结果、子表数据存活、以及迁移后的 `tracks` 表与 `app/schemas/.../5.json` 里 Room 导出的列定义完全一致。最后一条尤其重要：手写迁移 SQL 与 Room 的期望只要有一处不符，在设备上只表现为首次启动崩溃。

两个必须记住的边界：

- 该依赖内置的 SQLite 比任何 Android 版本都新，键级行为可能不同，**真机上的 `DatabaseMigrationTest` 才是权威**。
- 迁移 SQL 里的容器映射与 `audioContainer()` 是有意重复的两份；`sqlBackfillAgreesWithAudioContainerForEveryKnownInput` 会在两者分叉时失败，改一处必须改另一处。

### 协议客户端为什么能跑在 JVM 上

四个协议测试类用 `com.squareup.okhttp3:mockwebserver`（仅测试依赖）在进程内起一个假服务器，把**真实的** `SubsonicClient` / `PlexClient` / `JellyfinClient` 与 `baseHttpClient()` 接上去。它们断言的是请求序列（分页偏移、在哪里停止、请求了哪些 `fields`）与每个请求上的鉴权参数，而不只是返回的对象，因此"少发一次请求""把凭据发到错误位置""漏声明一个字段"这类问题能被抓住。Jellyfin 能这样测是因为 `JellyfinClient` 复用同一个 OkHttp 工厂与 `server.baseUrl`，无需伪造 SDK 内部实现。

夹具必须保持"服务器形状"，否则 SDK 解码失败只会表现为一句笼统的请求失败。Jellyfin 的 `MediaStream` 有 9 个必填字段、`MediaSourceInfo` 有 17 个；与其猜，不如临时写一个 JVM 测试打印 `MediaStream.serializer().descriptor` 的必填项——描述符会直接给出清单。

### 凭据链路也能跑在 JVM 上

`SavedServerInterceptor` 原来直接依赖 Room 与 Keystore，因此"服务器标记 → 取哪台服务器 → 附哪种凭据 → 什么情况下拒绝"这条安全关键链路曾经完全没有测试。现在它依赖一个 `ServerCredentials` 接缝（`find(id)` 返回 `LocatedServer(server, secret)`，`secret` 是回调，仍在用到处才解密），生产侧由 `serverCredentials(ServerDao, CredentialVault)` 适配。`SavedServerInterceptorTest` 用假查询与假解密驱动**真实的**拦截器，因此不需要设备、Room 或 Keystore。

## 服务环境

仪器测试使用 API 33+ 设备，覆盖真实 MediaStore、Room 升级、本地音频与服务器流播放。云集成测试需要隔离的真实 Navidrome（验证版本 0.63.2），请从官方 Releases 下载对应平台二进制并校验 SHA-256。在单独终端运行：

```sh
python3 scripts/navidrome-fixture.py /absolute/path/to/navidrome
```

测试服务只绑定本机 4534 端口，自动生成 WAV 和歌单，使用专用测试账号，不连接用户音乐库。看到 Fixture ready 后，解锁测试设备并运行（替换设备序列号）：

```sh
adb -s emulator-5554 reverse tcp:4534 tcp:4534
```

Jellyfin 测试另外需要一个全新的隔离服务，监听 `127.0.0.1:8097`。下载官方 Jellyfin 10.11.11 与 Jellyfin FFmpeg，在独立临时目录启动服务；为测试音乐目录准备 WAV 与 `folder.png` 封面。在该服务的 `config/network.xml` 中设置 `InternalHttpPort` / `PublicHttpPort` 为 8097，`LocalNetworkAddresses` 为 `127.0.0.1`，然后运行：

```sh
/path/to/jellyfin --datadir /tmp/jellyfin-test/data --configdir /tmp/jellyfin-test/config --cachedir /tmp/jellyfin-test/cache --logdir /tmp/jellyfin-test/log --nowebclient --ffmpeg /path/to/ffmpeg
# 另一终端：仅允许配置尚未完成启动向导的隔离服务
python3 scripts/configure-jellyfin-fixture.py /absolute/path/to/test-music
adb -s emulator-5554 reverse tcp:8097 tcp:8097
```

同时启动两个测试服务后执行完整仪器测试。Plex 的三项协议测试使用进程内 MockWebServer，无需 Plex 账号；覆盖分页、令牌请求头、同步失败保留数据及 Media3 对测试 WAV 的真实播放，但不替代真实 Plex Media Server 验收。单独运行可添加 `-Pandroid.testInstrumentationRunnerArguments.class=app.lusound.PlexProtocolTest,app.lusound.PlexPaginationTest`。仅运行本地测试时，可添加 `-Pandroid.testInstrumentationRunnerArguments.class=app.lusound.LibraryPlaybackTest,app.lusound.NavigationTest,app.lusound.DatabaseMigrationTest,app.lusound.DocumentTreeTest,app.lusound.QueueEditingTest`。

完成后在两个服务终端按 Ctrl-C。Navidrome 临时库自动删除；Jellyfin 测试目录由运行者清理。请使用专门的模拟器或测试设备；测试会写入测试歌单。

## 元数据样本

真实文件验证使用《半岛铁盒》（内嵌封面与歌词）和《布拉格广场》（缺失项在线补齐），原始文件哈希保持不变。运行 `MetadataIntegrationTest` 前，将这两类具有完整标签的已授权 FLAC 样本放到隔离测试设备的 `/sdcard/Android/data/app.lusound.debug/files/standard-embedded.flac` 与 `standard-missing.flac`；测试会访问 LRCLIB、MusicBrainz / Cover Art Archive。样本、缓存和音频均不进入仓库或 APK。

内嵌歌词扩展到 MP3 / M4A 后，同一目录还需要两份样本，缺失时对应用例会明确报出要推送的文件名：

| 文件 | 要求 |
| --- | --- |
| `standard-uslt.mp3` | 带 ID3v2 `USLT` 的真实 MP3，至少 2 行歌词 |
| `standard-lyrics.m4a` | 带 `©lyr` 的真实 M4A，至少 2 行歌词 |

旁置 `.lrc` 的 SAF 场景不需要样本：用例自行通过 `MANAGE_DOCUMENTS` 建立目录并生成 WAV 与同名 `.lrc`。

## 一条命令跑设备侧测试

`scripts/run-instrumented-tests.ps1` 把下面这些手工步骤（解析设备、安装两个 APK、推送样本、`adb reverse`、运行 instrumentation、拉取截图）合成一条命令，并在开始前检查它无法自己提供的前置条件（两个 fixture 端口是否在监听、样本是否齐全）：

```powershell
# Windows 默认禁止运行 .ps1，用这一种形式最省事
powershell -ExecutionPolicy Bypass -File scripts/run-instrumented-tests.ps1 -Samples C:\samples -Build
powershell -ExecutionPolicy Bypass -File scripts/run-instrumented-tests.ps1 -Class app.lusound.NcmPlaybackTest,app.lusound.QueueEditingTest
```

它不会替你启动 fixture（那两个服务是长期运行且依平台而异），只检测并提示启动命令。**注意顺序**：脚本先装 APK 再推样本，因为调试包首次安装才会创建外部 files 目录；也不要在这两步之间执行会卸载应用的操作（例如 `connectedAndroidTest`），那会一并删掉样本。

已验证的部分：脚本在 Windows PowerShell 5.1 下可解析、能从 `local.properties` 正确解析出 adb 路径、无设备或设备名不存在时会立刻退出并给出可执行提示。**未验证**：无设备可用，安装/推送/`adb reverse`/执行/拉取截图的正常路径从未跑过。

## NCM 与界面验证

完整测试需要额外提供一份有权使用的真实 NCM 样本（至少 70 KB、时长超过 3 秒并含封面），样本不随源码或 APK 分发。在安装 debug APK 后执行：

```sh
adb -s emulator-5554 shell mkdir -p /sdcard/Android/data/app.lusound.debug/files
adb -s emulator-5554 push '/absolute/path/to/sample.ncm' /sdcard/Android/data/app.lusound.debug/files/ncm-sample.ncm
```

NcmContainerTest / NcmPlaybackTest 覆盖损坏数据、超大长度拒绝、非对齐偏移读取、目录导入、封面、实际解码与 seek，并校验加密样本内容未变。测试只复制加密文件，不复制解密后的音频。尚未验证所有 NCM 变体；QMC/KGM 不在此次支持范围。

PlayerMotionTest 使用真实播放服务验证中途反向与播放连续性；ConvxLibraryMotionTest 覆盖专辑共享封面往返、搜索焦点与标题收起。VisualFlowTest 使用同一份外部 NCM 样本，记录资料库、播放器、队列、设置、服务器表单及键盘状态；不会把样本打包进 APK。视觉检查同时覆盖深色常规尺寸与浅色 360dp / 1.3 倍字体，输入时保持当前字段和提交操作可见。

## 执行完整测试

启动两个服务后，先构建并安装测试应用，再复制上述外部样本，最后运行仪器测试。建议使用专用模拟器，避免影响个人数据。

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# 按上述样本要求复制 NCM 和两份 FLAC 文件后执行：
adb -s emulator-5554 shell am instrument -w app.lusound.debug.test/androidx.test.runner.AndroidJUnitRunner
```

使用 Gradle 的 `connectedDebugAndroidTest` 时，应用安装及卸载由测试任务管理，外部样本可能随卸载删除；重新运行前需重新准备样本。
