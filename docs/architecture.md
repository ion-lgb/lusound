# 架构与不变量

本文是接手用的代码地图：模块边界、数据流，以及**改代码时必须保住的不变量**。功能缺口与验收状态见 [HANDOVER.md](HANDOVER.md)，测试环境见 [testing.md](testing.md)，Lint 基线见 [lint-baseline.md](lint-baseline.md)。

## 模块与规模

```
app/lusound      47 文件   3,801 行   业务本体
com/convx        59 文件   8,029 行   vendored UI（占 Kotlin 总量 68%）

cloud      608    library   382    metadata   400
ncm        237    playback   72    ui       1,979
```

依赖方向单一，不允许反向：

```
Compose UI ──> ViewModel ──> CloudRepository / MetadataRepository ──> Room + OkHttp/Retrofit
                     │
                     └──> MediaController ──> PlaybackService(MediaSessionService)
                                                   │
                                                   └─ ResolvingDataSource ──> NcmRoutingDataSource ──> NcmDataSource（内存解密）
                                                          └─ lusound:// ──> 服务器流地址（播放时才解析）
```

## 三条关键数据流

**本地库**：`MediaStore` 扫描 / SAF 目录扫描 / 单文件导入 → `Track` → Room `tracks`。
三种入口都必须写入 `sourceKind` + `sourceRef`（见 [TrackSource.kt](../app/src/main/kotlin/app/lusound/library/TrackSource.kt)）与归一化的 `container`。删除只在**完整扫描成功后**按来源做差集，扫描失败时一行都不删。

**云端库**：`CloudRepository.save/sync` → 各协议客户端 `readLibrary()` → `CloudSnapshot` → 覆盖式写入 `tracks` + `playlists`/`playlist_entries`。同步失败保留上次成功数据（`syncError` 单独记录）。

**播放**：`Track` → `toMediaItem()` → Media3。NCM 走 `ncm://` 自定义 scheme 进 `NcmDataSource`；在线歌曲走 `lusound://<serverId>/<songId>`，由 `ResolvingDataSource` 在加载时换取真实流地址，因此**过期流地址不会落库**，但服务器 ID 与歌曲 ID 会（这不是凭据）。

**歌词/封面**：`MetadataRepository.loadLocal()`（文件内嵌）→ `enrich()`（联网补齐 LRCLIB / MusicBrainz + Cover Art Archive）。结果按 `fingerprint`（曲目身份哈希）+ `sourceRevision`（文件版本）双键缓存，避免文件被替换后继续用旧匹配。

## 必须保住的不变量

这些不是风格偏好，破坏了就会出现静默数据损坏或只在真机上复现的崩溃。

1. **加密音频不落明文。** NCM 的压缩音频只在内存中解密后交给 Media3 解码（`NcmDataSource`）。封面可以单独缓存，音频不行。不要为了「方便」引入临时音频文件。
2. **凭据只进请求头/查询参数，且只在配置范围内。** `authenticate()` / `authenticatePlex()` 都先校验请求的 scheme/host/port/path 属于该服务器，再附加凭据；客户端不跟随重定向（`CloudHttp.kt`）。错误信息与日志必须脱敏（`PlexClient`、`SubsonicClient` 已做）。
   **已记录的宽松点**：当 base 是根路径（`https://host/`）时，Plex 与 Jellyfin 的路径检查只要求"以 base 开头"，因此该主机上任意路径都会带上凭据，而 Subsonic 额外要求 `rest/`。凭据出不了配置的 scheme/host/port，且应用自建的 URL 全部在范围内，所以当前不可达；这条行为已由 `SavedServerInterceptorTest.plexAndJellyfinCurrentlyAcceptAnyPathOnTheConfiguredRootHost` 固定，收紧它属于安全行为变更，需真机与真实服务器验证后再做。
3. **`tracks` 是外键父表，重建它会级联删空子表。** `playlist_entries.trackUri` 与 `track_metadata.uri` 都是 `ON DELETE CASCADE`，而 SQLite 的 `DROP TABLE` 等价于隐式 `DELETE FROM`。**任何重建 `tracks` 的迁移都必须先暂存子表再恢复**，见 `TrackSourceMigration.kt` 与它对应的测试。这条有专门的测试固定住前提（`droppingTheTracksTableCascadesIntoItsChildren`）。
4. **手写迁移 SQL 必须与 Room 从实体生成的 schema 一致。** 不一致只在真机首次启动时崩。`TrackSourceMigrationTest.migratedTracksTableMatchesTheExportedVersionFiveSchema` 在 JVM 上比对 `app/schemas/.../5.json`，新增迁移时照抄这个模式。
5. **容器映射有两份实现。** Kotlin 侧 `audioContainer()` 用于新扫描，SQL 侧 `trackSourceMigration()` 的 `CASE` 用于老数据回填。`sqlBackfillAgreesWithAudioContainerForEveryKnownInput` 会在两者分叉时失败——改一处必须改另一处。
6. **NCM 容器里的长度字段是小端。** 读侧对 `readInt()` 结果做 `Integer.reverseBytes`。写合成样本或调试时按大端会全线失败。
7. **数据库迁移必须保留本地歌单与服务器映射**；云歌单与播放队列里的重复歌曲可能是用户有意加入，**不能按歌曲 ID 全局去重**（`PlaylistEntry` 的主键是 `(playlistId, position)`，`position` 参与主键，所以重排必须避免瞬时主键冲突）。
   **删除范围规则**：库自己删除歌曲只有三处（MediaStore 扫描、目录重扫、服务器同步），规则集中在 `LibraryReconciliation.kt` 并且有 JVM 测试守着——一次扫描只能让「它刚刚读的那个来源」的行失效；未挂载卷上的行**永不**删除（拔卡不等于删歌）；对 `sourceRef` 是精确匹配，不匹配就什么都不删（宁可少删，下次扫描还能纠正）。删一行会级联掉它的歌单条目与歌词缓存，所以这些规则比大多数测试更值得守。
8. **`origin` 已被拆掉**：不要再引入「用一个字符串同时表达来源类型 / 目录 / 服务器」的字段。需要新的来源维度就加显式列。
9. **音质未知就是未知。** `bitrateKbps` / `sampleRateHz` / `bitDepth` 三列可空，NULL 表示「没有任何来源报告过」，显示层必须渲染成「音质未知」。容器名（`flac`、`mp3`、`ncm`）**不能**推出码率、采样率或位深；单位换算只在 `bitrateKbps()` 一处发生（MediaStore 与 Jellyfin 给 bps，Subsonic 与 Plex 给 kbps）。
10. **重复文件是隐藏，不是删除。** 同一文件经不同来源导入会留下多行，靠 `identityKey`（文件名 + 目录 + 大小 + 整秒时间）识别，库只展示代表行（显式导入 > 媒体索引 > 服务器）。被隐藏的行必须留在库里，歌单引用在读取时改指向代表行；删除只能由用户在设置里确认后发生，且必须先在**同一事务**内 `retargetEntries` 再删，否则级联会连歌单条目一起删掉。归组的偏置永远是"宁可不合组"：错误合组会隐藏一首歌并让歌单播到别的文件。
11. **不要用被扩展等级门控的 API 常量。** MediaStore 的 `SAMPLERATE` / `BITS_PER_SAMPLE` 属于 T 扩展等级 15，lint 无法验证这类守卫、会持续报 `InlinedApi`；本项目按该文件既有的列名字面量风格书写并保留守卫（见 `MediaScanner`）。
12. **UI 测试标签是契约。** 现有 `testTag`（`track_<uri>`、`group_<tab>_<key>`、`mini_player`、`player_screen`、`settings_*` 等）被 27 项仪器测试引用，改名等于改契约。
13. **媒体服务导出是有意的**：`PlaybackService.onConnect` 拒绝一切非本应用且非受信任的控制器，音频会话命令只对本包开放。Lint 的 `ExportedService` 警告因此按理由抑制，不要「修」。

## 验证分层

| 层 | 覆盖 | 是否需要设备 |
| --- | --- | --- |
| JVM 单测（`app/src/test`，82 项） | LRC 解析与匹配、元数据指纹、NCM 容器与位置解密、加密扩展名判定、服务器地址/凭据规则、来源与容器模型、迁移 SQL（真 SQLite） | 否 |
| 仪器测试（`app/src/androidTest`，20 项） | MediaStore/SAF 真实扫描、真实媒体服务器、Media3 真实解码、Compose 界面与动效、数据库迁移的 Room 运行时校验 | 是，另需样本与隔离服务 |
| CI（`.github/workflows/ci.yml`） | `assembleDebug` + 单测 + lint | 否 |

判定原则：能被 JVM 覆盖的逻辑不要放进仪器测试；需要真实 Provider、解码器、Keystore、服务器或音频的验证不要假装 JVM 能替代。`metadata/Lyrics.kt` 的 `decodeLyricHtml` 与 `TrackSourceMigrationTest` 读导出 schema 就是为这条边界做的两个接缝。

## vendored 代码边界

`com/convx/**` 是 GPL-3.0 的上游移植（固定基线见 [NOTICE](../NOTICE)），`com/convx/.../component/backdrop/**` 与 `shapes/**` 另含 Kyant0 的 Apache-2.0 源码。两条约定：

- 上游文件尽量保持原样，便于将来重新移植；Lint 对 `ModifierParameter` 的 3 条警告因此保留而不是改名（见 lint-baseline.md）。
- 树里带着本项目未使用的分支（`CanvasBackdrop`、`Combined*Backdrop`、`NavTransitionFreeze`、`LerpContinuousRoundedRectangle` 等）。删它们属于独立的清理任务，不要顺手删——将来要接 Canvas/V2 样式时可能直接用得上。
