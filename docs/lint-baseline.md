# Lint 基线复核

本文记录一次完整的 Lint 风险复核结果，替代 2026-09-11 交接文档中"0 错误、36 警告"的旧记录。旧数字与当前实测不一致，原因未逐条追溯（AGP/Lint 版本与依赖版本都会影响计数），所以以本文件的实测基线为准，后续变动只需 diff 本表。

## 复核环境与复现

| 项 | 值 |
| --- | --- |
| 复核日期 | 2026-09-19 |
| 命令 | `./gradlew :app:lintDebug` |
| AGP / 内置 Lint | 9.3.0 / lint-gradle 32.3.0 |
| JDK | JBR 21（Android Studio 随附） |
| 报告 | `app/build/reports/lint-results-debug.{html,sarif}` |

复核前的实测值为 **0 错误、25 警告**（与交接文档记录的 36 条不同）。处理后的当前基线：

```
0 errors, 11 warnings
  7  NewerVersionAvailable
  3  ModifierParameter
  1  OldTargetApi
```

第一次处理后的计数是 12。后来新增 JVM 协议测试时，同一个 `mockwebserver:5.3.2` 又作为 `testImplementation` 声明了一次（此前只在 `androidTestImplementation` 中），同一条依赖更新提醒因此多报一次，变成 13。2026-09-19 又有两个依赖升级落地（`org.jetbrains:annotations` 26.0.2 → 26.1.0、`org.slf4j:slf4j-nop` 2.0.17 → 2.0.19，均在单测复跑通过后提交），因此现在是 11。

## 一、已修复：10 条 UseKtx

机械等价替换，无行为变化：9 处 `Uri.parse(x)` → `x.toUri()`，1 处 `SharedPreferences.edit().put…().apply()` → `edit { }`。涉及
`FolderSettings.kt`、`LibraryViewModel.kt`（2 处）、`LocalMetadata.kt`（2 处）、`LuSoundApp.kt`、`MediaScanner.kt`、`MetadataRepository.kt`、`MetadataWorker.kt`、`NcmDataSource.kt`。

## 二、已抑制：3 条，均有书面理由

抑制不是消音，这三个是"照 Lint 建议改反而会错"的情形，理由同时写在了代码/清单注释里。

| 规则 | 位置 | 为什么不能按建议修改 |
| --- | --- | --- |
| `GetInstance`（ECB 加密） | `ncm/NcmContainer.kt` `decryptAes` | ECB 是 NCM 容器格式本身的规定（固定密钥、无 IV），换成 CBC/GCM 会直接解不开文件；被加密的是容器密钥与元数据，不是用户数据。 |
| `ExportedService` | `AndroidManifest.xml` `PlaybackService` | `android:exported="true"` 是 Media3 `MediaSessionService` 的官方形态，用于系统媒体恢复与外部控制器发现；凭据不会因此外泄，因为 `PlaybackService.onConnect` 拒绝任何非本应用且非受信任的控制器，音频会话命令也只对本包开放。 |
| `DataExtractionRules` | `AndroidManifest.xml` `application` | Lint 要求补 `android:fullBackupContent` 以覆盖 API 26–30。但该应用已设 `android:allowBackup="false"`，云备份与设备迁移在所有受支持版本上一律关闭，补上那份配置是死配置。 |

## 三、保留观察：11 条，不抑制、不盲改

保留即可见。这三类是真实信号，但当前不值得为"清零"而动手。

**`NewerVersionAvailable`（7 条）** — 以下依赖有更新版本（`mockwebserver` 在 unit test 与 androidTest 各声明一次，故计两条）：
`org.jellyfin.sdk:jellyfin-core:1.8.12`、`com.squareup.okhttp3:okhttp:5.3.2`、
`org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1`、`io.coil-kt.coil3:coil-network-okhttp:3.3.0`、
`io.coil-kt.coil3:coil-compose:3.3.0`、`com.squareup.okhttp3:mockwebserver:5.3.2`（×2）。
已升级并因此消失的：`org.jetbrains:annotations` 26.0.2 → 26.1.0、`org.slf4j:slf4j-nop` 2.0.17 → 2.0.19。

`mockwebserver` 5.5.0 **不能**单独升级：它依赖 `mockwebserver3:5.5.0`，后者依赖 `okhttp:5.5.0`，Gradle 会把整个 unit test classpath 的 okhttp 解析到 5.5.0（连同 Jellyfin SDK、Coil、media3 的子依赖一起移动）。那等于在测试 classpath 上做了一次真实的 okhttp 升级，而它正是三个协议的行为决定者——必须与 okhttp 本体的升级、以及随后的真实隔离服务复跑一起做。
其中 OkHttp 与 kotlinx-serialization 直接决定三个协议的请求与解析行为，Jellyfin SDK 决定其 API 形状，批量升级会把"版本风险"混进"功能改动"里。应由一次独立的依赖升级提交处理，并在升级后用真实隔离服务（Navidrome 0.63.2 / Jellyfin 10.11.11）复跑集成测试。
复查触发：任何一次依赖升级提交；或某个依赖出现安全公告。

**`ModifierParameter`（3 条）** — 均在 vendored 的 `com/convx/.../FloatingTabBar.kt`（`:551`、`:784`、`:901`）的 `tabBarContentModifier` 参数。
这是上游 API 命名，改名为 `modifier` 会与上游基线（`1e2237d9…`）分叉，抬高后续重新移植的成本，而实际风险为零。
复查触发：上游自行改名，或决定不再跟随上游。

**`OldTargetApi`（1 条）** — `targetSdk = 34`，而 `compileSdk = 37`。
升 `targetSdk` 会立即改变运行时行为（前台服务、通知、边到边、后台启动限制等），必须配真机验证，属于 V02/V03 的范围而不是 Lint 清理工作。当前分发走 GitHub Releases 而非商店，没有商店的 targetSdk 硬性期限压力。
复查触发：开始 V02/V03 兼容性验收时，一并把 `targetSdk` 提到 35+。

## 四、维护约定

- 新增警告应当要么修掉、要么按第二节的格式补理由，不要静默进基线。
- 本文件的计数一旦变化，改数字的同时要写清"为什么多/少了"。
- 不要为了得到 0 而批量 suppress：`OldTargetApi` 之类的信号一旦被抑制，就不会在升 targetSdk 时提醒任何人。
