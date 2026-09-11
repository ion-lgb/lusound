# 琉声 LuSound：未完成功能与开发交接

## 交接基线

- 整理日期：2026-09-11。
- 核对版本：v0.10.0，主分支提交 `2e761de6bce53baf6eea8c0420ff58033110fcfd`。
- [公开仓库](https://github.com/ion-lgb/lusound) · [已发布安装包](https://github.com/ion-lgb/lusound/releases/tag/v0.10.0)。
- 本清单依据最初需求、后续确认范围及当前源码整理；本轮只核对代码和已有验证记录，没有重新运行应用测试。
- 构建、使用和协议支持说明见 [README](../README.md)，集成测试环境见 [testing.md](testing.md)，来源与许可证见 [NOTICE](../NOTICE)。

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

- **状态 / 优先级**：部分完成 / P2。
- **现状**：歌曲行显示格式及“本地 / 在线”，NCM 显示“来源：网易云”。没有完整的本地 / 离线下载 / 在线状态模型；播放器与队列没有统一音质详情。Track 尚无码率、采样率、位深字段。
- **待做**：区分来源、离线可用性和实际音质；补充有数据依据的字段与展示规则。没有下载功能时，不应显示已离线下载。
- **入口**：[LibraryDatabase.kt](../app/src/main/kotlin/app/lusound/library/LibraryDatabase.kt)、[LuSoundApp.kt](../app/src/main/kotlin/app/lusound/ui/LuSoundApp.kt)、[PlayerScreen.kt](../app/src/main/kotlin/app/lusound/ui/PlayerScreen.kt)、[QueueDialog.kt](../app/src/main/kotlin/app/lusound/ui/QueueDialog.kt)。
- **验收**：本地、NCM 和多服务器歌曲混排时状态准确；音质未知显示未知，不能仅凭扩展名推断码率或位深。需要新增数据库字段时提供迁移。

## 二、未添加的可选补全功能

以下条目目前没有实现，但不应全部算作原始需求违约。是否纳入下一版本应由接手者与项目所有者确认。

| 编号 | 功能 / 优先级 | 当前边界、待做与验收 | 主要代码入口 |
| --- | --- | --- | --- |
| E01 | 本地旁置 LRC / P2 | 已有 LRC 解析与展示，但没有同目录歌词文件发现。增加授权目录内关联，验证同名、多版本、编码、offset 与权限失效。 | `metadata/Lyrics.kt`、`metadata/LocalMetadata.kt`、`library/DocumentTreeScanner.kt` |
| E02 | 非 FLAC 内嵌歌词 / P2 | 当前只读取 FLAC 歌词标签。补 MP3 ID3 / M4A 等实际支持格式，按格式提供真实样本；不能把已有内嵌封面读取误认为已支持歌词。 | `metadata/LocalMetadata.kt` |
| E03 | 歌词及封面手动选择 / P2 | 已有自动匹配与重新匹配，没有候选列表、手动指定 LRC / 图片或歌词时间偏移编辑。验证用户选择可持久保存并明确覆盖规则。 | `metadata/MetadataRepository.kt`、`metadata/MetadataStore.kt`、`ui/LyricsSheet.kt` |
| E04 | 跨来源文件去重 / P2 | MediaStore 与 SAF、重叠授权目录可能形成多条记录。需设计文件身份及来源优先级，删除一个来源不能误删另一个来源或歌单引用。 | `library/LibraryDatabase.kt`、`library/MediaScanner.kt`、`library/DocumentTreeScanner.kt` |
| E05 | 进程结束后的播放恢复 / P2 | 服务存活时可后台播放；尚无持久化队列、歌曲位置与进度恢复。应验证进程重建后按用户操作恢复，不自动意外出声，并处理已失效文件/服务器。 | `playback/PlaybackService.kt`、`ui/PlayerScreen.kt` |
| E06 | 本地歌单重命名与手动排序 / P2 | 已有创建、删除、加歌和移除；无歌单重命名及歌单内排序入口。播放队列排序已实现，不能混为同一功能。验证修改持久化且不影响其他歌单。 | `library/LibraryViewModel.kt`、`library/LibraryDatabase.kt`、`ui/LuSoundApp.kt` |
| E07 | 离线音频下载 / P3 | 目前只缓存元数据、封面和歌词，没有下载任务、磁盘配额及断网音频播放管理。若加入，需单独确定允许缓存的媒体范围；不得改变加密本地文件不输出明文的约束。 | `playback/PlaybackService.kt`、`cloud/CloudRepository.kt` |
| E08 | 服务器转码及码率选择 / P3 | Jellyfin / Plex 使用原文件流；无转码会话与用户码率选择。需实际验证格式不兼容、seek、会话停止及服务端资源回收。 | `cloud/JellyfinClient.kt`、`cloud/PlexClient.kt`、`cloud/CloudHttp.kt` |
| E09 | 服务器歌单双向编辑 / P3 | 云歌单只读，原需求的同步已实现。若新增远端写入，需实现权限、冲突及失败反馈，保留重复条目；不能把本地删除等同于远端删除。 | `cloud/CloudRepository.kt`、各协议客户端、`library/LibraryViewModel.kt` |
| E10 | Subsonic Basic Auth / P3 | 现有 Token + Salt 已满足原需求“Basic Auth 或 Token”中的一种；没有 Basic 模式。仅在目标服务确有需要时增加并验证，不能列为基础接入未完成。 | `cloud/SubsonicClient.kt`、`cloud/CloudHttp.kt` |
| E11 | 应用内均衡器 / P3 | 已能打开绑定当前音频会话的系统音效面板；无自带 EQ、预设和参数保存。只有要求跨设备一致调节时才需要新增；验证音频会话更换及参数恢复。 | `MainActivity.kt`、`playback/PlaybackService.kt`、`ui/SettingsContent.kt` |
| E12 | Convx Canvas / V2 / DIY 样式 / P3 | 先前明确选择仅完整移植默认界面。这些替代样式尚未接入，不属于默认移植的遗漏；重新纳入前需确认样式范围及来源许可。 | `ui/PlayerScreen.kt`、`ui/PlayerTransition.kt`、`com/convx/music/ui/` |
| E13 | 手动主题与玻璃效果配置 / P3 | 当前跟随系统主题并按 Android 版本选择材质；没有独立深浅模式、背景/效果强度等设置。增加后需验证持久化和低版本能力边界。 | `ui/LuSoundTheme.kt`、`ui/Glass.kt`、`ui/SettingsContent.kt` |
| E14 | Plexamp 遥控 / P3 | 当前接入的是 Plex Media Server，不控制 Plexamp 客户端。若需要，单独定义发现、会话和远端控制范围，不能与 F05 登录混为一项。 | `cloud/PlexClient.kt`、`playback/PlaybackService.kt` |

表中相对代码入口均位于 `app/src/main/kotlin/app/lusound/`，E12 的 `com/convx/` 路径位于 `app/src/main/kotlin/`。

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
| V08 | CI 构建与发布流水线 / P2 | 当前仓库无 `.github/workflows`，构建、测试和附件发布为手动流程。若新增 CI，分开处理无需私有样本的检查与需服务/样本的集成测试，签名凭据只能使用秘密存储。 |
| V09 | Lint 与客户端版本维护 / P2 | 现有报告为 0 错误、36 警告，需按风险复核。源码核对发现 `JellyfinClient.kt` 的 `ClientInfo` 版本仍为 `0.7.0`；HTTP User-Agent 已使用 BuildConfig 版本，两者不一致，应另行修正并验证。 |

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
