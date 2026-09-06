# 琉声 LuSound

Android 原生 Kotlin 本地音乐播放器。当前交付阶段为 0.1.0 本地播放首期；私有云与加密格式仍属后续阶段。

## 当前功能

- MediaStore 扫描标准音频、搜索、歌曲/专辑/艺术家/文件夹分类、创建歌单和添加/移除歌曲；暂时离线的存储卷保留歌单关系。
- SAF 文件授权导入，持久保留 URI 权限；不生成音频副本。媒体库在前台观察系统媒体变化并刷新。
- Media3 后台播放、媒体通知、音频焦点、耳机断开暂停、队列、进度跳转、随机和循环。
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
./gradlew :app:connectedDebugAndroidTest
```

仪器测试目前使用 API 33+ 设备，向 MediaStore 写入生成的 WAV，验证扫描、Room、歌单、播放、跳转及暂停；测试结束清理音频。另有使用稳定 testTag 的导航测试。运行前解锁设备。

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
| Navidrome / Koel / Jellyfin / Plex | 未接入，无服务器配置界面或配置截图 |

导入已识别的加密扩展名会明确报错，不会伪装为可播放歌曲。后续需要逐格式验证真实样本、密钥来源和随机读取能力；不保证仅凭扩展名可播放。解密后的压缩音频应通过内存数据源交给 Media3 解码，不把解密与 PCM 解码混为一步，也不写明文缓存。

后续私有云阶段须逐一核验 Koel 的实际 API/版本能力和各服务鉴权方式；不假设所有 Koel 都支持 Subsonic。所有网络音频和封面请求需统一设置 User-Agent，凭据不得写入日志。

普通权限无法突破其他应用的私有存储限制；不提供 Root/Shizuku 功能。仅处理用户有权访问和播放的媒体；不提供硬编码破解密钥或提取其他应用账号凭据的功能。是否写出副本并不能单独决定使用是否合法。

## 许可证与来源

项目按 GPL-3.0 分发，参见 `LICENSE`。移植基线与来源见 `NOTICE`。`com.convx.music.ui.component.backdrop` 保留上游结构、版权头及必要 API 兼容修改；其包含的 Kyant0 源码遵循 Apache-2.0（`licenses/Apache-2.0.txt`）。LuSound 新业务代码位于 `app.lusound`，未引入 Convx 的 YouTube/歌词/社交网络模块。

## 验证状态

2026-09-06 本机完成 Debug 与测试签名 Release 构建，lint 为 0 错误、16 条警告（主要为版本更新提示及清单建议）。Android 17 / API37 模拟器上两项真实集成测试通过，覆盖扫描、重复同步、歌单及卷离线/恢复清理、播放/暂停/seek、全屏播放器、队列切换和导航。Release 已安装并成功冷启动。尚未在 API26–36 真机验证性能、音效面板和厂商后台限制。

可安装包与 SHA-256 校验文件位于 `dist/`。该目录不参与版本控制。
