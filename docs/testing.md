# 集成测试

本文说明需要真实设备、隔离媒体服务器及外部音频样本的仪器测试环境。构建方法见 [README](../README.md#构建)。

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
