# 琉声 LuSound：未完成功能与开发交接

## 交接基线

- 整理日期：2026-09-11。
- 核对版本：v0.10.0，主分支提交 `2e761de6bce53baf6eea8c0420ff58033110fcfd`。
- [公开仓库](https://github.com/ion-lgb/lusound) · [已发布安装包](https://github.com/ion-lgb/lusound/releases/tag/v0.10.0)。
- 本清单依据最初需求、后续确认范围及当前源码整理；本轮只核对代码和已有验证记录，没有重新运行应用测试。
- 构建、使用和协议支持说明见 [README](../README.md)，模块边界与必须保住的不变量见 [architecture.md](architecture.md)，集成测试环境见 [testing.md](testing.md)，来源与许可证见 [NOTICE](../NOTICE)。

**状态含义**：“未实现”表示没有对应完整功能；“部分完成”表示已有可用基础但未达到原始完整目标；“待验证”表示已有实现，缺少指定环境的验收；“可选扩展”不属于已承诺交付的缺口。

**优先级建议**：P1 为原始核心需求或接手后优先验证项；P2 为补全体验；P3 为另行确认范围的扩展。优先级是接手建议，不是新的开发授权或工期承诺。

## 已完成的基础能力

以下能力已经存在，接手时应复用，不应重复搭建：

- MediaStore 标准音频扫描；SAF 单文件、目录授权及递归导入；歌曲、专辑、艺术家、文件夹、本地歌单和搜索。
- Media3 前台播放服务、通知控制、音频焦点、耳机拔出暂停、随机/循环、进度跳转及混合来源队列。
- NCM 标准容器内存解密、播放及 seek；不输出明文音频副本。
- Navidrome / Subsonic Token + Salt 接入、Jellyfin 官方 SDK 接入；音乐库及只读服务器歌单同步。
- Plex 手动 Token 接入及协议测试。注意：这不等同于真实 Plex 服务器验收完成。
- 联网歌词与封面匹配、结果缓存、FLAC 内嵌歌词、同步歌词跳转及重新匹配。
- Convx 默认界面与对应动效的源码移植和业务适配；深浅主题、玻璃导航、共享封面、手势播放器、歌词及队列动画。
- GitHub 主分支合并、仓库公开、签名 Release / Debug APK 和校验文件发布。

## 一、原始需求中仍未完成的功能

### F01 · QQ 音乐 QMC 系列读取与播放

- **状态 / 优先级**：未实现 / P1。
- **现状**：扫描器可识别 `qmc` 前缀扩展名并将其计为不支持文件；没有解密数据源，不能播放。
- **待做**：分别确认 `.qmc0`、`.qmc3`、`.qmcflac` 及 QMCv2 的具体变体、输入条件和可支持范围；实现可定位、按需读取的数据源，并接入混合音乐库。
- **入口**：[DocumentTreeScanner.kt](../app/src/main/kotlin/app/lusound/library/DocumentTreeScanner.kt)、[NcmDataSource.kt](../app/src/main/kotlin/app/lusound/ncm/NcmDataSource.kt)、[PlaybackService.kt](../app/src/main/kotlin/app/lusound/playback/PlaybackService.kt)。NCM 只能作为数据源接入方式的参考，不能视为 QMC 算法实现。
- **验收**：每个声称支持的变体均以有权使用的真实样本验证导入、元数据、连续播放、前后 seek、损坏文件报错及无明文音频落盘。依赖密钥的变体须先明确合法输入方式，不以提取账号凭据作为默认方案。

### F02 · 酷狗 KGM / KGMA 读取与播放

- **状态 / 优先级**：未实现 / P1。
- **现状**：仅识别扩展名；没有容器解析与解密播放实现。
- **待做**：确认格式版本、必要输入和依赖许可，再实现流式读取、元数据映射和错误处理。
- **入口**：与 F01 相同；建议按格式分开实现，复用播放路由。
- **验收**：每种支持格式具备真实样本播放、随机偏移读取、seek 和损坏输入验证；原文件不变，不产生明文副本。

### F03 · 跨应用加密歌曲自动发现与持续更新

- **状态 / 优先级**：部分完成 / P1。
- **现状**：用户可以手动选择文件或授权目录；目录内有效 NCM 可与标准歌曲混合导入。尚无网易云 / QQ / 酷狗下载目录的自动发现流程，SAF 目录更新依赖手动重扫。
- **待做**：在系统允许读取的范围内提供来源目录发现、授权引导、扫描状态及更新策略；按新文件、删除、权限失效分别处理。应复用现有目录导入，避免建立第二套音乐库。
- **入口**：[DocumentTreeScanner.kt](../app/src/main/kotlin/app/lusound/library/DocumentTreeScanner.kt)、[LibraryViewModel.kt](../app/src/main/kotlin/app/lusound/library/LibraryViewModel.kt)、[FolderSettings.kt](../app/src/main/kotlin/app/lusound/ui/FolderSettings.kt)。
- **验收**：授权目录内新增歌曲可按约定策略出现，失效授权有恢复入口，失败不清空已有库；显示真实扫描范围和不支持格式。
- **平台边界**：不能把“自动发现”承诺成普通权限可遍历其他应用私有目录。当前没有 Root / Shizuku，也没有绕过 `Android/data` 限制的实现；这类权限扩展不是已批准范围。

### F04 · Koel 原生服务器接入

- **状态 / 优先级**：未实现 / P1。
- **现状**：连接类型只有 Subsonic、Jellyfin 和 Plex，没有 Koel 客户端或配置入口。
- **待做**：先选定要支持的 Koel 版本并核对其实际 API / 鉴权；实现登录、歌曲、封面、歌单、流地址及凭据更新，然后接入现有同步和队列。
- **入口**：[CloudRepository.kt](../app/src/main/kotlin/app/lusound/cloud/CloudRepository.kt)、[ServerDatabase.kt](../app/src/main/kotlin/app/lusound/cloud/ServerDatabase.kt)、[ServerSettings.kt](../app/src/main/kotlin/app/lusound/ui/ServerSettings.kt)。
- **验收**：真实 Koel 服务完成首次及重复同步、流播放、seek、失效登录恢复；同步失败保留旧数据。
- **需求修正**：原提示词把 Koel 与 Subsonic 合并描述，当前实现不假定 Koel 原生兼容 Subsonic。若具体部署确实提供兼容端点，应单独验证，不能据此宣称原生支持。

### F05 · Plex PIN / 扫码或链接授权登录

- **状态 / 优先级**：部分完成 / P2。
- **现状**：可填写服务器地址和手动 Token；没有浏览器授权、PIN / QR 流程或账号下服务器选择器。
- **待做**：实现授权发起、用户确认、状态查询、过期与取消处理，以及授权后的服务器选择；保留手动 Token 入口。
- **入口**：[PlexClient.kt](../app/src/main/kotlin/app/lusound/cloud/PlexClient.kt)、[PlexApi.kt](../app/src/main/kotlin/app/lusound/cloud/PlexApi.kt)、[ServerSettings.kt](../app/src/main/kotlin/app/lusound/ui/ServerSettings.kt)。
- **验收**：用真实账号完成授权、取消、超时和令牌失效测试；日志与链接中不泄漏 Token。
- **前置条件**：项目所有者此前没有可用于验收的 Plex 服务器，接手者需准备测试账号和服务；不要在公开 Issue 中提交凭据。

### F06 · Jellyfin 远端设备投播

- **状态 / 优先级**：未实现 / P1（原需求明确包含投播）。
- **现状**：Jellyfin 音频在手机本地 Media3 播放，没有设备发现、目标选择和远端会话控制。
- **待做**：先确定投播的含义：Jellyfin 远端会话控制、Google Cast 或 DLNA 是不同能力，不能混为一个接口。确认目标后实现发现、授权、播放控制与状态回传。
- **入口**：[JellyfinClient.kt](../app/src/main/kotlin/app/lusound/cloud/JellyfinClient.kt)、[PlaybackService.kt](../app/src/main/kotlin/app/lusound/playback/PlaybackService.kt)、[PlayerScreen.kt](../app/src/main/kotlin/app/lusound/ui/PlayerScreen.kt)。
- **验收**：真实目标设备可播放、暂停、seek、切歌；断开时状态明确；区分手机与目标设备的音量和播放进度。

### F07 · 统一的播放来源状态与音质信息

- **状态 / 优先级**：部分完成 / P2（来源模型、容器归一与音质字段及展示已落地；离线可用性状态未做）。
- **现状**：来源已经模型化。`tracks` 现在用 `sourceKind`（`MEDIASTORE` / `DOCUMENT` / `DOCUMENT_TREE` / `CLOUD`）加 `sourceRef`（授权目录 URI 或服务器 ID）表达来源，取代了原先把来源类型、目录 URI、服务器 ID 三种含义挤进一个 `origin` 字符串、再由五处代码按字符串前缀反解的做法。容器标签也统一了：MediaStore 上报的 MIME 子类型（`mpeg`、`mp4`、`x-wav`）在写入前归一为 `mp3`、`m4a`、`wav`。
  音质字段已加入（v6 迁移，三列可空）：`bitrateKbps`、`sampleRateHz`、`bitDepth`。**有数据才填，未知即未知**——显示层由 `qualityLabel()` 渲染，一无所知时显示"音质未知"，绝不从容器名推断（`.flac` 不等于 16 bit/44.1 kHz，`.mp3` 不等于 320 kbps）。当前的数据来源：MediaStore 的 `bitrate` 列（API 30+，**零额外 I/O**）、文档/目录导入时已打开的 `MediaMetadataRetriever`（码率；采样率需 API 31+）、以及 Android 16 / T 扩展 15+ 上的 `samplerate`、`bits_per_sample` 列。播放器标题下方显示"容器 · 音质"。
  仍然缺少：FLAC 位深（元数据通道已经解析 `FlacStreamMetadata`，本可零额外 I/O 取得，但那是 metadata 层、写入 `tracks` 属跨层，需要先决定接缝放哪；`readAudioDocument` 里的 `MediaMetadataRetriever` 不提供位深）；离线可用性状态。
- **待做**：决定 FLAC 位深的取值位置（在 metadata 层解析后回写 `tracks` 需要一条明确的接缝，或改成在导入路径用 Media3 读一次文件头）；补离线可用性状态。云端音质已完成：Subsonic 的 `bitRate`（kbps）与 `samplingRate`（Hz）、Plex 的 `Media.bitrate`（kbps）、Jellyfin 的 `MediaSources[].Bitrate` 与音频流的 `SampleRate`/`BitDepth`（bps 统一换算为 kbps，换算只在 `bitrateKbps()` 一处发生）。
- **入口**：[TrackSource.kt](../app/src/main/kotlin/app/lusound/library/TrackSource.kt)、[TrackQuality.kt](../app/src/main/kotlin/app/lusound/library/TrackQuality.kt)、[TrackQualityMigration.kt](../app/src/main/kotlin/app/lusound/library/TrackQualityMigration.kt)、[TrackSourceMigration.kt](../app/src/main/kotlin/app/lusound/library/TrackSourceMigration.kt)、[LibraryDatabase.kt](../app/src/main/kotlin/app/lusound/library/LibraryDatabase.kt)、[MediaScanner.kt](../app/src/main/kotlin/app/lusound/library/MediaScanner.kt)、[PlayerScreen.kt](../app/src/main/kotlin/app/lusound/ui/PlayerScreen.kt)、[CloudRepository.kt](../app/src/main/kotlin/app/lusound/cloud/CloudRepository.kt)。
- **验收**：本地、NCM 和多服务器歌曲混排时来源与格式准确；音质未知显示未知，不能仅凭扩展名推断码率或位深。来源拆分、容器归一与音质展示规则已在 JVM 层验证（`TrackSourceTest`、`TrackQualityTest`、两个迁移测试）；**数据库迁移 5→6 与各来源的实际取值仍需真机确认**，用例已加入 `DatabaseMigrationTest`，见 [testing.md](testing.md)。

## 二、未添加的可选补全功能

以下条目目前没有实现，但不应全部算作原始需求违约。是否纳入下一版本应由接手者与项目所有者确认。

| 编号 | 功能 / 优先级 | 当前边界、待做与验收 | 主要代码入口 |
| --- | --- | --- | --- |
| E01 | 本地旁置 LRC / P2 | 已实现：同目录 `.lrc` 发现覆盖 `file://`、SAF 授权目录（`DOCUMENT_TREE`）、MediaStore 与单文件导入四种来源；名称匹配（`Song.lrc` 优先于 `Song.mp3.lrc`，扩展名大小写无关）、编码解码（UTF-8/BOM、UTF-16 BOM、严格 UTF-8 校验失败回退 GB18030）与有界读取（>2 MB 报错）。优先级：内嵌 > 旁置 > 联网。**待真机验证**：SAF 同级发现、MediaStore 分支（API 29+ 与 29 以下两条路径）。注意 API 33+ 只有 `READ_MEDIA_AUDIO`，读不到非音频的 `.lrc`，SAF 目录是可靠路径。 | `metadata/SidecarLyrics.kt`、`metadata/LocalMetadata.kt` |
| E02 | 非 FLAC 内嵌歌词 / P2 | 已实现：容器按文件头嗅探（`fLaC` / `ID3` / MP4）而非按 `tracks.container` 判断，因此错误扩展名不会决定是否读取标签。支持 FLAC VorbisComment、ID3v2 `USLT`/`ULT`（v2.2/2.3/2.4，四种文本编码）与 `TXXX` 回退、M4A/MP4 `©lyr` 与 Apple freeform。**明确不支持**：ID3 `SYLT`（二进制时间戳，无法在此环境验证，故不伪装支持）、Ogg/Opus/WMA/AIFF 歌词容器、`.wav` 内的 ID3 chunk。 | `metadata/EmbeddedLyrics.kt` |
| E03 | 歌词及封面手动选择 / P2 | 已有自动匹配与重新匹配，没有候选列表、手动指定 LRC / 图片或歌词时间偏移编辑。验证用户选择可持久保存并明确覆盖规则。 | `metadata/MetadataRepository.kt`、`metadata/MetadataStore.kt`、`ui/LyricsSheet.kt` |
| E04 | 跨来源文件去重 / P2 | **已按「隐藏 + 手动清理」实施**（所有者确认后）：新增可空 `identityKey` 列（v7 只追加迁移），身份取自「文件名 + 目录 + 大小 + 整秒修改时间」——目录参与是为了不让不同目录里同名同大小同时间的**不同**文件被当成同一个；整秒是因为媒体索引只报到秒而文档报到毫秒。资料库只展示代表行（显式导入 > 媒体索引 > 服务器），被隐藏的行保留在库中，歌单引用在读取时改指向代表行，因此**不会丢歌也不会丢歌单位置**；清理只在用户于「设置」中确认后执行，且先把歌单引用改指过去再删。**待真机验证**：两种来源指向同一真实文件时是否真的归到一组（依赖两个提供程序对目录的拼写一致）。 | `library/FileIdentity.kt`、`library/TrackIdentityMigration.kt`、`library/LibraryViewModel.kt`、`ui/SettingsContent.kt` |
| E05 | 进程结束后的播放恢复 / P2 | 服务存活时可后台播放；尚无持久化队列、歌曲位置与进度恢复。应验证进程重建后按用户操作恢复，不自动意外出声，并处理已失效文件/服务器。 | `playback/PlaybackService.kt`、`ui/PlayerScreen.kt` |
| E06 | 本地歌单重命名与手动排序 / P2 | 已实现：本地歌单重命名（服务器歌单不提供入口）与歌单内上移/下移。重排采用事务内 `clearPlaylist` + 按新位置重插，绕开 `(playlistId, position)` 联合主键的瞬时冲突，并把整个列表重写为连续的 `0..n-1`；重复歌曲按位置索引处理，绝不去重。播放队列排序是另一套实现，未受影响。**待真机验证**：新增仪器用例 `PlaylistEditingTest` 仅编译通过，从未执行。 | `library/PlaylistOrder.kt`、`library/LibraryViewModel.kt`、`ui/ConvxLibrary.kt` |
| E07 | 离线音频下载 / P3 | 目前只缓存元数据、封面和歌词，没有下载任务、磁盘配额及断网音频播放管理。若加入，需单独确定允许缓存的媒体范围；不得改变加密本地文件不输出明文的约束。 | `playback/PlaybackService.kt`、`cloud/CloudRepository.kt` |
| E08 | 服务器转码及码率选择 / P3 | Jellyfin / Plex 使用原文件流；无转码会话与用户码率选择。需实际验证格式不兼容、seek、会话停止及服务端资源回收。 | `cloud/JellyfinClient.kt`、`cloud/PlexClient.kt`、`cloud/CloudHttp.kt` |
| E09 | 服务器歌单双向编辑 / P3 | 云歌单只读，原需求的同步已实现。若新增远端写入，需实现权限、冲突及失败反馈，保留重复条目；不能把本地删除等同于远端删除。 | `cloud/CloudRepository.kt`、各协议客户端、`library/LibraryViewModel.kt` |
| E10 | Subsonic Basic Auth / P3 | 现有 Token + Salt 已满足原需求“Basic Auth 或 Token”中的一种；没有 Basic 模式。仅在目标服务确有需要时增加并验证，不能列为基础接入未完成。 | `cloud/SubsonicClient.kt`、`cloud/CloudHttp.kt` |
| E11 | 应用内均衡器 / P3 | 已能打开绑定当前音频会话的系统音效面板；无自带 EQ、预设和参数保存。只有要求跨设备一致调节时才需要新增；验证音频会话更换及参数恢复。 | `MainActivity.kt`、`playback/PlaybackService.kt`、`ui/SettingsContent.kt` |
| E12 | Convx Canvas / V2 / DIY 样式 / P3 | 先前明确选择仅完整移植默认界面。这些替代样式尚未接入，不属于默认移植的遗漏；重新纳入前需确认样式范围及来源许可。 | `ui/PlayerScreen.kt`、`ui/PlayerTransition.kt`、`com/convx/music/ui/` |
| E13 | 手动主题与玻璃效果配置 / P3 | 当前跟随系统主题并按 Android 版本选择材质；没有独立深浅模式、背景/效果强度等设置。增加后需验证持久化和低版本能力边界。 | `ui/LuSoundTheme.kt`、`ui/Glass.kt`、`ui/SettingsContent.kt` |
| E14 | Plexamp 遥控 / P3 | 当前接入的是 Plex Media Server，不控制 Plexamp 客户端。若需要，单独定义发现、会话和远端控制范围，不能与 F05 登录混为一项。 | `cloud/PlexClient.kt`、`playback/PlaybackService.kt` |

表中相对代码入口均位于 `app/src/main/kotlin/app/lusound/`，E12 的 `com/convx/` 路径位于 `app/src/main/kotlin/`。

### E04 方案（已实施，见上表 E04 行）

**问题**：同一份文件可能因 MediaStore 扫描、SAF 目录导入、两个重叠授权目录而各留一条记录；删除其中一条会级联删掉它的歌单条目。

**已落地的设计**：身份 = 「文件名 + 目录 + 大小 + 整秒修改时间」的哈希（可空列 `identityKey`，v7 只追加迁移）；库只展示代表行，其余隐藏；歌单引用在读取时改指向代表行；清理需用户在设置中显式确认，且先把引用改指过去再删。

**刻意保留的保守之处**（每一条都是为了"宁可不合组"）：目录参与身份（避免把不同目录的同名同大小同时间文件当成同一个，那会导致播放错文件）；时间只比到整秒（媒体索引只给秒，文档给毫秒，同一文件必须两边算出一个身份）；身份不完整（缺大小或时间）就返回 null，不做任何归组。合组失败只是回到"显示两条"的旧状态，而错误合组会隐藏一首歌并让歌单播到别的文件。

**验证状态**：纯逻辑（身份计算、代表选择、遮蔽传播、重指向）已在 JVM 上测；**真机验证仍缺**——需要一份真实文件同时被媒体索引与授权目录看到，确认两者对目录的拼写一致从而真的归到一组。

## 三、待验收与发布工程事项

这些项目不等于“功能完全没做”，但接手时不能直接视为生产验收通过。

| 编号 | 事项 / 优先级 | 缺口与完成条件 |
| --- | --- | --- |
| V01 | Plex 真实服务器验收 / P1 | 手动 Token 功能只通过 MockWebServer 协议测试。需真实 PMS 验证登录凭据、库/歌单分页、封面、seek、重复项及令牌过期；见 `PlexProtocolTest`、`PlexPaginationTest`。 |
| V02 | Android 8–12 兼容性 / P1 | minSdk 为 26，已有版本分支；记录中的完整测试运行于 API 37。需补低版本安装、媒体权限、SAF、NCM、系统 EQ、通知及材质降级实测。 |
| V03 | 厂商后台行为 / P1 | PMA110 已验证覆盖安装及冷启动，尚不能等同于长时播放通过。需补锁屏、Doze、网络切换、蓝牙/耳机、音频焦点和后台同步测试。 |
| V04 | 多协议及大库压力 / P2 | Navidrome 0.63.2 / Jellyfin 10.11.11 有真实服务记录；其他兼容实现和大规模音乐库需另测。验证长列表、同步耗时、内存、取消及失败数据保留。 |
| V05 | NCM 更多真实变体 / P2 | 已有真实样本与边界测试，但不保证所有 NCM 兼容。扩展样本矩阵前先登记音频载荷、容器差异、失败原因，不能仅按扩展名宣称支持。 |
| V06 | UI 动效与可访问性补充验收 / P2 | 已有默认流程、小屏、大字体、键盘及关闭动画测试。尚无全机型性能和 TalkBack 完整验收记录；应按默认样式逐页检查，而非直接断言像素级全部一致。 |
| V07 | 签名密钥移交 / P1 | 已发布签名 APK，但私钥不在仓库。要继续覆盖更新，需由所有者通过安全渠道移交同一签名密钥及必要配置；不要写进本文件或公开仓库。新密钥签出的同包名应用不能直接覆盖现有版本。 |
| V08 | CI 构建与发布流水线 / P2 | 已新增 `.github/workflows/ci.yml`：每次推送与合并请求执行 `assembleDebug` + `testDebugUnitTest` + `lintDebug` 并上传报告。需要密钥、设备、样本或外部服务的步骤（`assembleRelease`、仪器测试）仍为手动，这是有意保留的边界，不是遗漏。 |
| V09 | Lint 与客户端版本维护 / P2 | 已完成风险复核并登记基线，见 [lint-baseline.md](lint-baseline.md)：实测为 0 错误、11 警告（交接时记录的 36 条与实测不符，以实测为准）。两处硬编码 `0.7.0` 已修正——`JellyfinClient.kt` 的 `ClientInfo` 与 `CloudHttp.kt` 的 `X-Plex-Version` 均改用 `BuildConfig.VERSION_NAME`，并有单元测试防止再次硬编码。剩余 11 条为依赖更新、vendored 上游命名与 `targetSdk`，逐条理由及复查触发条件见该文件（含 `mockwebserver` 为何不能单独升级）。 |

已有测试清单和样本准备见 [testing.md](testing.md)。测试音频不随仓库分发；云测试必须使用隔离服务，不能直接指向用户正式音乐库。

## 四、接手时必须保持的已确认决策

1. **最低 Android 8.0 / API 26**：最初提示词写 API 24，后续已明确接受保留 Android 8.0 支持。Android 7 不再是当前必须补齐的兼容目标。
2. **Compose 与 Convx 默认界面**：已确认默认样式优先，并已迁移源码与动效，不需要倒退改为 XML。Canvas / V2 / DIY 以及上游 YouTube、账号、社交、AI 不属于本轮范围。
3. **加密音频内存处理**：现有实现向 Media3 提供解密后的压缩音频，由 Media3 解码；这符合不落明文音频文件的目标，无需为了原提示词的“PCM 注入”字样重写成另一套解码器。
4. **NCM 固定格式常量**：当前实现内置公开固定格式常量，此前已确认采用该方案；不读取其他应用登录凭据。支持清单必须对应真实验证过的格式。
5. **元数据查询授权**：已允许发送标题、歌手、专辑、时长用于匹配；不上传音频、路径或服务器凭据。当前实际接入 LRCLIB、MusicBrainz / Cover Art Archive；Apple iTunes 只是此前候选，不是遗漏的必选接口。
6. **许可证**：LuSound / Convx 衍生源码按 GPL-3.0 分发，部分组件另有 Apache-2.0 / LGPL-3.0 声明。保留源码版权头及 NOTICE，不能仅保留“灵感来源”而移除源码许可义务。
7. **现有数据与队列**：数据库迁移需保留本地歌单和服务器映射；云歌单和播放队列中的重复歌曲可能是用户有意加入，不可按歌曲 ID 全局去重。

## 五、建议接手顺序

1. 拉取上述基线，按 README 构建，准备独立测试设备、服务及有权使用的样本；先处理 V07 签名移交与 V01–V03 验收。
2. 从 F01–F04 中选定核心能力逐项开发；先明确格式/API 和可获得的验证条件，再承诺支持范围。
3. 确定远端投播协议后处理 F06，准备 Plex 测试环境后处理 F05，统一整理 F07 展示。
4. 按产品优先级选择 E01–E06 等体验补全；E07–E14 需另行确认，避免将可选扩展当成默认交付。
5. 每项完成时更新本清单状态、相应支持说明和验收证据；合并前运行受影响的真实集成测试，发布时保留 APK 与源码版本对应关系。

本文件是交接时的未完成清单，不代表上述任务已经开始实施，也不包含新增功能的工期估算。

## 六、接手进展记录

### 2026-09-19：可离线验证的工程基线

本轮只做降低后续工作成本的地基，未改动播放、扫描、协议与界面行为。唯一的对外可见变化是 Jellyfin / Plex 请求头里的客户端版本号由硬编码的 `0.7.0` 更正为实际版本。

| 改动 | 证据 |
| --- | --- |
| 新增 JVM 单元测试源集 `app/src/test/kotlin/`：6 个测试类、48 项，覆盖 LRC 解析与歌词匹配、元数据指纹、NCM 位置异或解密与随机访问等价性、加密扩展名判定、服务器地址规范化与凭据放置规则 | `./gradlew :app:testDebugUnitTest` → 48 项全部通过，合计约 0.4 秒，无设备、无网络、无样本 |
| `metadata/Lyrics.kt` 增加 `decodeLyricHtml` 接缝，使 LRC 语法可脱离 Android 框架验证；生产路径仍用 Android 的 HTML 实体解码 | `LyricsTest` 13 项 |
| 修正 V09 的两处版本硬编码（`JellyfinClient.kt`、`CloudHttp.kt`） | `CloudAuthTest` 断言 `X-Plex-Version == BuildConfig.VERSION_NAME` |
| 新增 `.github/workflows/ci.yml`（assembleDebug + 单元测试 + lint） | 本地以相同三个任务验证通过；CI 平台上的首次运行结论尚未取得 |
| Lint：修复 10 条 UseKtx、3 条按书面理由抑制、登记基线 | `./gradlew :app:lintDebug` → 0 错误、警告 25 → 13；见 [lint-baseline.md](lint-baseline.md) |

本轮**未验证**，因此不作任何通过声明：19 项仪器测试、真实 NCM 与 FLAC 样本、Navidrome / Jellyfin / Plex 真实服务、API 26–32 真机、`assembleRelease` 与签名、任何性能结论。

下一步：V07 签名移交（需所有者提供，代码侧无法推进）→ F07 来源与音质重构（`Track.origin` 与 `format` 的语义拆分，见评估结论）→ 其余缺口按确认范围推进。

### 2026-09-19（第二轮）：F07 来源模型与容器归一

把 `Track` 的表达方式从"字符串约定"改成"显式列"，因为 F03 与 E04 都建立在一个可靠的来源模型之上。

| 改动 | 证据 |
| --- | --- |
| `Track.origin` 拆为 `sourceKind` + `sourceRef`；`Track.format` 归一为 `container`，三条导入路径（MediaStore MIME 子类型、文件扩展名、服务器容器名）统一到一套词汇 | 数据库版本 4→5；`TrackSourceTest` 6 项 |
| 新增 `TRACK_SOURCE_MIGRATION`（4→5），纯 Java SQLite 上验证了回填正确性、子表数据存活与迁移后表结构与 Room 导出 schema 一致 | `TrackSourceMigrationTest` 8 项；8 项全绿 |
| 迁移必须重建 `tracks`，而它是两个 `ON DELETE CASCADE` 外键的父表；已用测试固定住"SQLite 在 DROP TABLE 时会隐式删除并级联"这一前提，迁移因此先把子表暂存再恢复 | `droppingTheTracksTableCascadesIntoItsChildren` |
| 仪器侧迁移用例补齐：1→5、2→5，以及新的 4→5（含重复歌曲条目与元数据缓存存活） | `DatabaseMigrationTest` 3 项，**待真机执行** |

本轮**未在真机验证**：数据库迁移 4→5 的 Room 运行时校验、歌曲行新文案在真实曲库上的观感。JVM 测试无法替代这两项。

下一步：F07 的第二半（码率 / 采样率 / 位深字段）或 F03/E04（来源模型已就绪）；V07 仍需所有者提供签名密钥。

### 2026-09-19（第三轮）：并行三条工作流 + 离线协议测试

三个子代理在隔离副本中并行开发，主代理负责范围核对、合并与全量验证。

| 工作流 | 结果 | 证据 |
| --- | --- | --- |
| 协议层离线测试（原缺口：Subsonic / Jellyfin 完全没有离线协议测试） | 新增 21 项：`HttpRetryTest`（重试策略、User-Agent）、`SubsonicClientTest`、`PlexClientTest`、`JellyfinClientTest`（含官方 SDK 的真实报文形状） | 断言的是**请求序列与每请求鉴权参数**，不只是返回快照；四类协议各自的失败路径均断言行走到此为止 |
| E01 + E02 歌词来源 | 新增 29 项 JVM 测试（`SidecarLyricsTest` 11、`EmbeddedLyricsTest` 18） | 见上文 E01/E02 行；容器嗅探与 ID3 USLT 解码均在 JVM 上以合成数据验证 |
| E06 歌单重命名与重排 | 新增 8 项（`PlaylistOrderTest`）+ 1 项仪器用例 | 见上文 E06 行 |

主代理在合并阶段修掉的问题（都不在代理自身验证范围内）：

| 问题 | 说明 |
| --- | --- |
| **lint 直接失败构建** | 新歌词代码使用 Media3 `@UnstableApi` 而未 opt-in，产生 32 条 `UnsafeOptInUsageError`（error 级）。代理只跑了 `testDebugUnitTest`/`compileDebugKotlin`，两者都不会触发 lint。修法：该文件加 `@file:androidx.annotation.OptIn(...)`。**注意 Media3 的标记是普通 Java 注解，必须用 `androidx.annotation.OptIn`，Kotlin 的 `@file:OptIn` 不被 lint 识别（实测反而多一条）**。 |
| 重排的竞态数据丢失 | `reorderPlaylistEntry` 原在事务外读条目，用户"移动后立即加歌"时 `clearPlaylist` 会静默删掉刚插入的条目。已要求代理把读取移入事务。 |
| 首方代码新增 lint 警告 | `TrackRow` 的 `modifier` 默认值不是纯 `Modifier`，违反 Compose 约定；已把行自身布局移入函数内、调用方 modifier 叠加其上。 |
| 代理验证命令本身有坑 | PowerShell 中 `-Dorg.gradle.jvmargs=...` **不加引号会被当成任务名**，Gradle 报 `Task '.gradle.jvmargs=-Xmx2500m' not found`；必须写成 `"-Dorg.gradle.jvmargs=-Xmx2500m"`。三个代理中有两个踩到。 |

**合并后实测**：JVM 单测 **144 项全绿**（86 → 144）；`assembleDebug`、`assembleDebugAndroidTest` 通过；lint **0 错误 / 13 警告**（仍是已登记的三类）。仪器测试 25 项已编译，**无一执行**。

**本轮未验证**：全部新增仪器用例（真实 SAF 同级发现、MediaStore 旁置歌词、真实 MP3/M4A 歌词读取、歌单重命名与重排在真机上的行为与布局）、旁置歌词在 API 33+ 权限下的实际可达性、MP4 尾部 `moov` 的 seek 读取在真实 ffmpeg 文件上的表现。

### 2026-09-19（第四轮）：F07 后半——音质字段与诚实展示

| 改动 | 证据 |
| --- | --- |
| 新增三列 `bitrateKbps` / `sampleRateHz` / `bitDepth`（可空）与 **v5 → v6 迁移**；这次只追加列，因此不触碰级联子表，与 v4 → v5 的重建形成对照 | `TrackQualityMigrationTest` 4 项（真 SQLite）：旧行数据保留、子表未受影响、新列可写可读、迁移后表结构与 Room 导出的 6.json 完全一致 |
| 音质展示规则：按单位逐个渲染已知项，一无所知时显示"音质未知"，**任何情况下都不从容器名推断** | `TrackQualityTest` 9 项，含"六种容器 + 空容器都不产生任何数字"的对照 |
| 取值来源（全部零额外 I/O）：MediaStore `bitrate`（API 30+）、导入时已打开的 `MediaMetadataRetriever`（码率；采样率 API 31+）、Android 16/T 扩展 15+ 的 `samplerate` 与 `bits_per_sample` | 播放器标题下新增 `player_quality` 行（`容器 · 音质`） |
| 仪器侧迁移用例补齐 5→6，并同步更新 1→6、2→6、4→6 的迁移链 | `DatabaseMigrationTest` 4 项，**待真机执行** |

**过程记录（值得记住的两个坑）**：lint 的 `InlinedApi` 抓出了我两处错误假设——`MediaMetadataRetriever.METADATA_KEY_SAMPLERATE` 是 **API 31**（不是记忆中的 17），MediaStore 的 `SAMPLERATE` / `BITS_PER_SAMPLE` 是 **T 扩展等级 15**（不是普通 API 36）。改成正确的守卫后 lint 仍不接受扩展等级的判断（它对被扩展门控的字段无法验证），最终按本文件既有的列名字面量风格书写，既保留新设备上的取值能力，又不引入任何抑制。

**本轮未验证**：三个来源在真机上的实际取值（MediaStore 对 PCM WAV 报什么码率、MMR 对 WAV 是否给采样率）、播放器新增行的排版。云端歌曲的音质映射尚未实现。

### 2026-09-19（第五轮）：F07 收尾——云端音质映射

三个协议的响应里本来就带着音质，只是从没被解析。现在都映射进 `tracks` 的三列，**单位在协议边界统一**（Subsonic 与 Plex 本来给 kbps，Jellyfin 给 bps，只在 `bitrateKbps()` 一处换算）：

| 协议 | 取值 | 说明 |
| --- | --- | --- |
| Subsonic | `bitRate`（kbps）、`samplingRate`（Hz） | 直接反序列化进 `RemoteSong`；无位深字段 |
| Plex | `Media.bitrate`（kbps） | 无采样率/位深（需另行请求 streams） |
| Jellyfin | `MediaSources[].Bitrate`、音频流 `SampleRate` / `BitDepth` / `BitRate` | 流级数值优先于容器级；需在 `fields` 里加 `MEDIA_STREAMS`，同一次响应返回，不增加请求 |

协议测试相应加强：断言每首歌映射出的音质值、**缺字段时必须留空**、以及 Jellyfin 确实请求了 `MediaStreams`（Retrofit 对列表参数是重复 `fields` 而非逗号拼接，测试已按实际线格式断言）。

**过程记录**：给 Jellyfin 的测试夹具补 `MediaStreams` 后测试立刻红了——SDK 的 `MediaStream` 模型有 9 个必填字段。没有靠猜，而是临时写了一个 JVM 测试打印 SDK 序列化器的描述符，直接读出必填字段清单（`IsInterlaced`/`IsDefault`/`IsForced`/`IsHearingImpaired`/`Type`/`Index`/`IsExternal`/`IsTextSubtitleStream`/`SupportsExternalStream`），补全后即绿，临时测试已删除。这条"用序列化器描述符问出必填字段"的手法对任何 kotlinx-serialization 夹具都适用。

**本轮未验证**：三家真实服务器返回的音质数值（协议测试用的是自造夹具）、播放器音质行的排版。

### 2026-09-19（第六轮）：FLAC 位深 + 服务器凭据路径接缝

| 改动 | 证据 |
| --- | --- |
| 新增 `FlacStreamInfo.kt`：从 FLAC 自身的 STREAMINFO 块读出采样率与位深。这是**唯一在所有受支持 API 级别都可用**的位深来源（MediaStore 的 `bits_per_sample` 要 T 扩展 15 / Android 16，`MediaMetadataRetriever` 从不给位深） | `FlacStreamInfoTest` 7 项：合成 FLAC 头（含手工位打包）→ Media3 读回 44100/16、96000/24、8000/8；非 FLAC、截断、首块不是 STREAMINFO、块长度离谱都返回 null |
| 导入路径接入：`readAudioDocument` 对 `flac` 容器探一次文件头，取到的采样率与位深**覆盖** retriever 的数值（更权威）。这是**每个导入文件一次**，不是每次扫描库一次 | 与云端、MediaStore 的取值在 `Track` 三列汇合，显示层不区分来源 |
| `SavedServerInterceptor` 增加可测试接缝（`ServerCredentials` / `LocatedServer` / `serverCredentials()`），凭据规则、地址范围检查与拒绝行为一字未改 | `SavedServerInterceptorTest` 19 项，此前这条链路 **0 覆盖**：无标记直通、服务器已移除显式失败、三种协议各自的凭据放置（Subsonic 的 `t`/`s`、Plex 的 `X-Plex-Token`、Jellyfin 的 `X-Emby-Token`）、标记必被剥离、越界地址在三种协议下都被拒、未知协议被拒 |
| 依赖升级：`org.jetbrains:annotations` → 26.1.0、`org.slf4j:slf4j-nop` → 2.0.19 | lint 警告 13 → **11**，单测每次升级后复跑 |

**安全相关观察（已用测试固定，未修改行为）**：当服务器地址是根路径（`https://host/`）时，Plex 与 Jellyfin 的范围检查只要求路径以 base 开头，因此**该主机上的任意路径都会被附上凭据**；Subsonic 额外要求路径以 `rest/` 开头。凭据仍然出不了配置的 scheme/host/port，且应用自己构造的 URL 全部在范围内（Plex 媒体路径另有 `/library/` 校验），所以这不是当前可达的漏洞；但"根路径 base"下的检查确实比 Subsonic 宽松，已由 `plexAndJellyfinCurrentlyAcceptAnyPathOnTheConfiguredRootHost` 固定住。收紧它属于安全行为变更，需要真机与真实服务器验证后再做。

**本轮未验证**：FLAC 取值在真机上对真实文件的读数（JVM 用合成头验证了打包与解析）、NCM 内 FLAC 载荷的位深（载荷在容器内，需经解密流读取，尚未实现）、凭据接缝的端到端（真机 Keystore 路径只编译通过）。

### 2026-09-19（第七轮）：删除范围规则的离线守护

库自己删除歌曲只有三处：MediaStore 扫描、目录重扫、服务器同步。删一行会级联删除它的歌单条目与歌词缓存，而这三条规则此前**只由仪器测试覆盖**——也就是在没有设备时从不运行。现在规则集中到 `LibraryReconciliation.kt` 的三个纯函数，调用点不变（事务、分块删除都留在原处），由 **16 项 JVM 测试**守住：

| 规则 | 为什么它重要 |
| --- | --- |
| 一次扫描只能让「它刚读的那个来源」的行失效 | SAF 导入的行不会被 MediaStore 扫描误删，两个重叠授权目录互不越界，两台服务器上同 ID 的歌互不影响 |
| 未挂载卷上的行**永不**删除 | 拔掉 SD 卡时提供程序什么都不返回，若当成删除就会把用户的歌与歌单位置一起丢掉 |
| 对 `sourceRef` 精确匹配，不匹配就什么都不删 | 宁可少删：下次扫描还能纠正，误删无法挽回 |
| 只对**确实缺失**的行查询卷状态 | 卷探针要走系统服务且可能拒答；顺序调整后，仍在库里的行永远不会导致整次对账失败（结果集不变，副作用更少） |

重构后 `replaceMediaLibrary` / `replaceDocumentTree` / `CloudRepository.persist` 的行为与原先逐字等价，只是判定被提取成可测的纯函数。

**本轮未验证**：提取后的调用点在真机上的实际删除行为（原有仪器用例 `LibraryPlaybackTest`、`DocumentTreeTest` 仍是对账语义的权威，但未执行）。

### 2026-09-19（第八轮）：E04 跨来源去重（所有者确认后按「隐藏 + 手动清理」实施）

| 改动 | 证据 |
| --- | --- |
| 文件身份 `fileIdentityKey(name, folder, size, lastModifiedMs)`：目录参与、时间只比整秒、身份不完整即返回 null | `FileIdentityTest` 13 项，含"不同目录同名同大小同时间的两个文件必须分开"与"亚秒差异必须相同"两条对立断言 |
| 代表选择与遮蔽传播：`representativeTrack`（显式导入 > 媒体索引 > 服务器，优先级相同时按 URI 稳定）与 `representativeByUri` / `shadowedUris` | 同一批测试覆盖三行合一、单行不成组、无身份永不归组、顺序无关的稳定性 |
| 库只显示代表行；**歌单条目在读取时改指向代表行**，所以隐藏不会让歌从歌单里消失 | `LibraryViewModel.tracks` / `entries`；设计上"隐藏是无损且可逆的" |
| 清理是显式动作：设置里显示"清理 N 条重复记录"并需确认；删除前先在**同一事务**内把歌单引用改指过去（否则级联会删掉歌单条目） | `LibraryViewModel.removeDuplicateRows` + `LibraryDao.retargetEntries`；仪器用例断言重指向不与 `(playlistId, position)` 主键冲突 |
| v6 → v7 只追加 `identityKey` 迁移；仪器迁移链同步更新为 1/2/4/5/6 → 7 | `TrackIdentityMigrationTest` 4 项（真 SQLite + 与导出的 7.json 比对）；`DatabaseMigrationTest` 增至 5 项（**待真机**） |

**过程记录**：实现过程中发现两处必须处理的现实差异——媒体索引的 `DATE_MODIFIED` 是**秒**、文档的 `LAST_MODIFIED` 是**毫秒**（不统一则同一文件算不出同一个身份），以及仅凭"名字+大小+时间"合组有可能把**不同**文件当成同一个（后果是歌单播错文件）。前者改为统一比整秒，后者改为把目录也纳入身份。两处都写进了代码注释与测试。

**本轮未验证**：真机上两种来源指向同一真实文件时是否真的归到一组（取决于两个提供程序对目录的拼写是否一致；不一致时只是不归组，不会误合）；新增仪器用例全部未执行。

### 2026-09-19（第九轮）：把同步映射与元数据缓存规则搬进 JVM 覆盖

延续第八轮的思路：把"错了会丢数据或显示错内容"的决策从仪器测试手里搬到每次构建都跑的 JVM 测试里。

| 改动 | 证据 |
| --- | --- |
| 服务器同步的映射与歌单决策提取为纯函数：`cloudTracksOf`、`removedCloudPlaylistIds`、`existingCloudPlaylist` | `CloudSyncPlanTest` 13 项：URI 键、来源标记、秒→毫秒、容器归一、音质透传、封面走服务器标记、**空歌曲 ID 在构造任何 URI 之前就被拒绝**；远端已消失的歌单才删，**没有 remoteId 的本地歌单永不删除**（否则一次空快照会清空所有歌单）；重复同步只改名不新增 |
| 元数据缓存规则提取为纯函数：`freshCachedMetadata`、`mergeMetadata` | `MetadataMergeTest` 12 项：内嵌 > 旁置 > 缓存远端；**只有远端来源能作为回退**（文件里已经消失的本地来源不得被缓存续命）；版本不匹配或提供程序"无版本"一律重读；无歌词的行即使版本一致也重读（`.lrc` 可能新出现而不改动音频）；checkedAt/error 延续 |

**过程中的一次关键取舍**：本可以顺手把 `cloudTrackUri` 改成纯字符串实现以便在 JVM 上直接测，但它产出的是**已持久化的库键**（既有行的 URI），改写会让含特殊字符的歌曲 ID 生成不同的键。改为把 URI 构造器**作为参数注入**：生产路径一字未改（默认仍是 `android.net.Uri.Builder`），测试用替身验证映射把正确的服务器 ID 与歌曲 ID 传了进去，而真实 URI 形状仍由仪器测试覆盖。

**本轮未验证**：提取后的同步与缓存路径在真机/真实服务器上的端到端行为（`CloudSyncPlanTest`、`MetadataMergeTest` 用的是自造输入）。

### 2026-09-19（第十轮）：两处守卫的离线覆盖，并收尾

| 改动 | 证据 |
| --- | --- |
| 封面下载地址白名单提取为纯函数 `isSupportedCoverUrl(HttpUrl)`。该地址来自第三方 JSON，属不可信输入——放宽即可让应用以自己的网络身份去取任意主机 | `CoverUrlGuardTest` 7 项：只允许 HTTPS 上的 Cover Art Archive / archive.org 及其子域；`notarchive.org`、`archive.org.evil.example.com`、`coverartarchive.org.evil.example.com`、带凭据的地址、非 HTTPS 全部拒绝。同时记录了一条判断依据：**信任边界是主机而不是端口**，所以同一被信任主机的非标准端口不算绕过 |
| NCM 载荷格式校验提取为纯函数 `payloadMatchesDeclaredFormat(format, signature)`。容器的"格式"只是声明，损坏或伪造的容器可以声称 flac 而装别的 | `NcmPayloadSignatureTest` 6 项：FLAC 认 `fLaC`、MP3 认 ID3 或 11 位帧同步（`0xff 0xfb`/`0xff 0xe0` 通过，`0xff 0x1f`/`0xfe 0xfb` 拒绝）；声明与载荷不符、不支持的格式、过短签名一律 false——顺带修掉了原先直接索引 `audio[0]/audio[1]` 的写法 |

### 收尾状态

本清单中**可在无设备、无服务器、无样本条件下推进**的部分已经做完，全部以本地可复现的证据确认（当前：**254 项 JVM 单测**、`assembleDebug`、`assembleDebugAndroidTest`、lint **0 错误 / 11 警告**）。

仍然需要外部条件的（不是代码问题，是资源问题）：**全部 27 项仪器测试从未执行**（需要真机 + 两个隔离服务 + 外部样本，脚本 `scripts/run-instrumented-tests.ps1` 已就绪）；**V07 签名私钥**仍需所有者通过安全渠道移交，否则做不出可覆盖安装的包；F01/F02（QMC / KGM）需要有权使用的真实样本与合法输入方式确认；F03/F04/F05/F06 需要平台决策、真实 Koel/Plex 服务器或账号。
