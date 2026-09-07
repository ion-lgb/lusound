# 琉声 LuSound

Android 原生 Kotlin 本地与私有云音乐播放器。当前阶段为 0.3.0：本地播放与 Navidrome / Subsonic / Jellyfin 接入。

## 当前功能

- MediaStore 扫描标准音频、搜索、歌曲/专辑/艺术家/文件夹分类、创建歌单和添加/移除歌曲；暂时离线的存储卷保留歌单关系。
- SAF 文件授权导入，持久保留 URI 权限；不生成音频副本。媒体库在前台观察系统媒体变化并刷新。
- Media3 后台播放、媒体通知、音频焦点、耳机断开暂停、队列、进度跳转、随机和循环。
- Navidrome / Subsonic Token + Salt 鉴权、完整音乐库和只读服务器歌单同步、在线流播放及封面、本地与在线音乐混合队列。联网时由 WorkManager 约每 6 小时同步，支持手动同步；失败保留上次成功的数据。
- Jellyfin 官方 Kotlin SDK 1.8.12：账号登录、加密访问令牌、音乐库与只读音频歌单同步、原文件流播放及封面；保留歌单中的重复歌曲。
- 系统均衡器入口绑定实际音频会话；必须先播放，设备需提供系统音效控制面板。
- 从 Convx 迁移的 backdrop 渲染源码及 LiquidSlider 拖动动效，悬浮播放栏、封面叠加背景、深浅主题跟随系统。当前不是 Convx 全部页面的完整复刻。
- Android 8–11：半透明材质；Android 12：实时模糊；Android 13+：实时模糊和折射。低版本封面背景不具备系统实时模糊。

## 编译

最低 API 26，target API 34，compile API 37。Gradle Wrapper 9.5.0、AGP 9.3.0；使用 Android Studio 自带 JBR（Java 17+，本机使用 JBR 25）。Kotlin 业务代码输出 JVM 17 字节码。

1. 安装 Android Studio 和 SDK Platform 37、Build Tools 36.0.0；通过 SDK Manager 接受许可证。
2. 用 Android Studio 打开目录，配置本机 `local.properties` 中的 `sdk.dir`。此文件不应提交。
3. 设置 `JAVA_HOME` 指向 Android Studio 的 JBR，然后执行：

```sh
./gradlew :app:assembleDebug :app:lintDebug
```

仪器测试使用 API 33+ 设备，覆盖真实 MediaStore、Room 升级、本地音频与服务器流播放。云集成测试需要隔离的真实 Navidrome（验证版本 0.63.2），请从官方 Releases 下载对应平台二进制并校验 SHA-256。在单独终端运行：

```sh
python3 scripts/navidrome-fixture.py /absolute/path/to/navidrome
```

测试服务只绑定本机 4534 端口，自动生成 WAV 和歌单，使用专用测试账号，不连接用户音乐库。看到 Fixture ready 后，解锁测试设备并运行（替换设备序列号）：

```sh
adb -s emulator-5554 reverse tcp:4534 tcp:4534
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
```

Jellyfin 测试另外需要一个全新的隔离服务，监听 `127.0.0.1:8097`。下载官方 Jellyfin 10.11.11 与 Jellyfin FFmpeg，在独立临时目录启动服务；为测试音乐目录准备 WAV 与 `folder.png` 封面。在该服务的 `config/network.xml` 中设置 `InternalHttpPort` / `PublicHttpPort` 为 8097，`LocalNetworkAddresses` 为 `127.0.0.1`，然后运行：

```sh
/path/to/jellyfin --datadir /tmp/jellyfin-test/data --configdir /tmp/jellyfin-test/config --cachedir /tmp/jellyfin-test/cache --logdir /tmp/jellyfin-test/log --nowebclient --ffmpeg /path/to/ffmpeg
# 另一终端：仅允许配置尚未完成启动向导的隔离服务
python3 scripts/configure-jellyfin-fixture.py /absolute/path/to/test-music
adb -s emulator-5554 reverse tcp:8097 tcp:8097
```

同时启动两个测试服务后执行完整仪器测试。仅运行本地测试时，可添加 `-Pandroid.testInstrumentationRunnerArguments.class=app.lusound.LibraryPlaybackTest,app.lusound.NavigationTest,app.lusound.DatabaseMigrationTest`。

完成后在两个服务终端按 Ctrl-C。Navidrome 临时库自动删除；Jellyfin 测试目录由运行者清理。请使用专门的模拟器或测试设备；测试会写入测试歌单。

国内网络可用 `./gradlew -PuseAliyun=true :app:assembleDebug` 启用阿里云 Google/Maven Central 镜像。Gradle 分发包仍需网络可达；镜像不保证覆盖全部依赖。不要把镜像下载失败解释成编译成功。

## 签名

Release 不复用 debug 签名。通过以下环境变量提供密钥：

- `LUSOUND_KEYSTORE`：绝对路径
- `LUSOUND_STORE_PASSWORD`
- `LUSOUND_KEY_ALIAS`
- `LUSOUND_KEY_PASSWORD`

```sh
./gradlew :app:assembleRelease
```

本地 `keystore/` 保存本次测试发布密钥和 `signing.env`，均已排除版本控制；测试 release 身份不是商店正式发布身份。请自行妥善保管，丢失密钥无法签署可覆盖安装的更新。不要公开此目录。

标准输出路径：`app/build/outputs/apk/debug/app-debug.apk`、`app/build/outputs/apk/release/app-release.apk`。

## 加密格式与服务器支持

| 功能 | 当前状态 |
| --- | --- |
| MP3 / FLAC / WAV / M4A 等标准音频 | 使用 Media3 与设备支持的编解码器 |
| NCM | 未接入 |
| QMC0 / QMC3 / QMCFLAC / QMCv2 | 未接入 |
| KGM / KGMA | 未接入 |
| Navidrome / Subsonic | 已接入，Token + Salt 鉴权；Navidrome 0.63.2 实测 |
| Jellyfin | 已接入官方 SDK，真实服务 10.11.11 验证；原文件流播放 |
| Koel 原生 API / Plex | 尚未接入 |

导入已识别的加密扩展名会明确报错，不会伪装为可播放歌曲。后续需要逐格式验证真实样本、密钥来源和随机读取能力；不保证仅凭扩展名可播放。解密后的压缩音频应通过内存数据源交给 Media3 解码，不把解密与 PCM 解码混为一步，也不写明文缓存。

当前不假设 Koel 原生 API 兼容 Subsonic；其他服务后续逐一接入。网络请求统一携带 LuSound User-Agent，Subsonic 密码与 Jellyfin 访问令牌经 Android Keystore AES-GCM 加密存储，播放条目只保存歌曲 ID，鉴权在请求时生成。

普通权限无法突破其他应用的私有存储限制；不提供 Root/Shizuku 功能。仅处理用户有权访问和播放的媒体；不提供硬编码破解密钥或提取其他应用账号凭据的功能。是否写出副本并不能单独决定使用是否合法。

## 许可证与来源

项目按 GPL-3.0 分发，参见 `LICENSE`。移植基线与来源见 `NOTICE`。`com.convx.music.ui.component.backdrop` 保留上游结构、版权头及必要 API 兼容修改；其包含的 Kyant0 源码遵循 Apache-2.0（`licenses/Apache-2.0.txt`）。LuSound 新业务代码位于 `app.lusound`，未引入 Convx 的 YouTube/歌词/社交网络模块。

## 服务器配置

设置 → 添加音乐服务器 → 选择 Subsonic 或 Jellyfin，输入名称、服务器根地址、用户名和密码，然后点击“连接并同步”。

- 公网示例：`https://music.example.com/`；部署在子路径时填完整根路径，如 `https://example.com/navidrome/`。
- 局域网示例：`http://192.168.1.20:4533/`。手机需能访问该地址；HTTP 仅适用于受信任的局域网。
- 地址不要填写 `/rest`、登录页或歌曲链接。出于凭据保护，不自动跟随重定向，请填写最终地址。
- 首次成功同步后，歌曲显示在资料库，服务器歌单显示在“歌单”并标注只读。可把在线歌曲添加到本地歌单或播放队列。
- “编辑”支持名称和密码更新；变更地址或账号请新增连接。移除连接会清除该来源的本地映射，不修改远端库。
- 目前缓存元数据，不提供离线音频下载。后台同步时间由系统调度，强行停止应用后需重新打开。

Jellyfin 填写服务根地址（例如 `http://192.168.1.20:8096/`），不要附加 `/web`。登录后保存访问令牌；服务器撤销会话后在“编辑”中重新输入密码。Jellyfin 播放使用原始音频，格式须由设备 / Media3 支持。

![Jellyfin 配置](docs/jellyfin-config.png)
![Jellyfin 连接成功](docs/jellyfin-connected.png)

下图为模拟器连接本机隔离 Navidrome 的真实界面；`127.0.0.1:4534` 通过 adb reverse 映射，仅用于测试：

![添加服务器](docs/server-config.png)
![连接并同步成功](docs/server-connected.png)

## 验证状态

0.3.0 已完成 Debug 与测试签名 Release 构建，lint 为 0 错误、23 条警告（主要为依赖更新与清单建议）。API37 模拟器上的六项真实集成测试覆盖数据库 1→3 / 2→3 升级与原有 Subsonic 凭据保留、本地播放和导航、添加服务器、同步与鉴权失败保留数据、封面读取、在线播放与 seek、在线到本地的混合队列切换。Release 已覆盖安装到模拟器并成功冷启动。

可安装包和 SHA-256 校验文件位于 `dist/`，该目录不参与版本控制。尚未覆盖所有 Android 版本、厂商后台限制或其他 Subsonic 实现。Jellyfin 目前不提供转码或远端设备投播。
