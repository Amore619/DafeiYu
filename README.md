# 大肥鱼（DafeiYu）

一个轻量的 Android 聊天客户端：界面按 DeepSeek 手机端的交互习惯重做，直连大模型 API，**无需任何中间服务器**。默认对接 DeepSeek，也可连接任何 OpenAI 兼容的第三方模型。支持照片/文件导入、上下文预警、对话导出续聊、多 Key 管理、聊天记录自动备份。

- 包名：`com.dafeiyu.chat`
- 版本：`1.0.0`
- 最低系统：Android 11（API 30）
- 目标系统：Android 15（API 35）
- 技术栈：Java + Android 原生 View，无 AndroidX、无第三方依赖、无 WebView
- 默认 API：`https://api.deepseek.com`，默认模型 `deepseek-flash`；可自定义端点与模型
- 许可证：[MIT](LICENSE)

> **本项目为第三方客户端，与 DeepSeek 官方无任何隶属关系。** 使用前请自行准备 API Key，API 计费与内容规则以对应服务商为准。

## 功能一览

| 能力 | 说明 |
|------|------|
| 直连 API | 对接 DeepSeek，兼容任意 OpenAI 格式端点，SSE 流式输出 |
| 导入附件 | 照片 + PDF/Word/PPT/表格/文本/电子书，按类型识别解析 |
| 上下文预警 | 剩余约 10 轮提醒，右上角常驻剩余轮数，上限可手动输入 |
| 导出续聊 | 一键导出 `.dfy`，导入后可无缝接着聊 |
| 多模型 / 多 Key | 自定义 baseUrl + 模型名；保存多个 Key 随时切换 |
| 自动备份 | 退到后台自动备份到手机公共目录，卸载不丢，支持一键恢复 |
| 隐私安全 | Key 用 Android Keystore 加密，数据只存本机 |

## 已实现的功能

### 1. 复刻 DeepSeek 手机端界面，并直连 API

- 首次启动引导：填入自己的 DeepSeek API Key 后即可使用。
- 欢迎页：大肥鱼 Logo、四个建议问题、导入历史对话入口。
- 主界面：左侧历史对话抽屉、中间消息流、底部圆角输入卡。
- 消息样式：用户蓝色气泡、AI 无边框正文、Markdown 渲染（标题、粗体、列表、引用、表格、代码块、链接）。
- 深度思考：可折叠的思考过程，显示“已深度思考（用时 N 秒）”，流式输出。
- 联网搜索：展示“正在联网搜索… / 已搜索 N 个来源”，可展开来源列表并点击打开网页。
- 模型切换：`V4.1 Flash`（支持图片/文件）与 `V4 Pro`（纯文本深度推理）。
- 思考强度：快速 / 标准 / 极致，对应 `low / high / max`。
- 界面跟随系统深色模式；状态栏/导航栏透明并处理手势区域。
- 历史对话：本地保存、自动标题、重命名、删除、按今天/最近 7 天/更早分组。

### 2. 照片和文件导入

- 照片：系统 Photo Picker（Android 13+）或多选文件选择器，最多 10 张；自动压缩、纠正 EXIF 方向。
- 文件：PDF、Word、PPT、Excel、TXT、Markdown、CSV、JSON、代码、EPUB/MOBI 等。
- 图片以 `user_data` 上传到 DeepSeek Files API，文档以 `text_recognition` 上传，纯文本/表格/电子书以 `user_data` 上传。
- 上传时显示进度；上传失败可重试，不会产生半截消息。
- 图片和文件会保存在应用私有目录，聊天记录与附件一起导出。

### 3. 上下文长度预警与剩余轮数

- 右上角常驻胶囊：`≈ N 轮`，实时显示按当前节奏还能聊多少轮。
- 默认在剩余 **10 轮** 时预警：输入框上方出现黄色提示条，并弹出一次提醒对话框。
- 剩余 ≤3 轮时切换为红色高饱和警示。
- 预警轮数可在设置中改为 5 / 10 / 15 / 20。
- 上下文上限可选 128K / 256K / 512K / 1M（DeepSeek 当前 API 为 1M）。
- 点右上角胶囊打开“上下文”面板：已使用 Token、百分比、上限、平均每轮 Token、已聊轮数、还能聊多少轮，并提供“导出备份”“开启新对话”“调整上限”。
- 估算逻辑：
  - 优先使用 API 返回的真实 `prompt_tokens + completion_tokens`；
  - 没有真实用量时，用本地中英文/代码混合估算；
  - 预留 `max_tokens + 4096` 给下一轮输出与系统提示；
  - 平均每轮按真实总用量 ÷ 已完成轮数计算，最低按 260 Token/轮兜底；
  - 因此长文件、长回答、图片会明显减少剩余轮数，估算偏保守。

### 4. 一键导出、导入后无缝续聊

- 顶栏下载图标：一键导出到 `下载/大肥鱼/`，完成后可直接分享。
- 设置/侧栏也可“导出到指定位置”。
- 导出格式为 `.dfy`（本质是 ZIP）：
  - `manifest.json`：完整会话、消息、思考、工具调用、引用、附件元数据；
  - `attachments/`：图片和原始文件；
  - `对话.md`：人类可读的 Markdown 备份。
- 导入入口：欢迎页“导入历史对话”、侧栏“导入对话”、设置“导入对话”。
- 导入行为：
  - 直接替换当前屏幕中的会话，消息、时间戳、标题、思考内容、附件都恢复；
  - 旧对话仍保留在本地历史里，不会丢失；
  - 导入后输入框立即可以继续聊，视觉上就是原对话的延续；
  - 导入的会话使用新的本地 id，避免覆盖已有记录。
- 附件 `file_id` 会保留；同一 DeepSeek 账号下可直接继续引用。若更换账号导致附件失效，重新添加附件即可（历史文字与回答不受影响）。

### 5. 连接其他模型（OpenAI 兼容）

- 设置中可自定义 **API 地址（baseUrl）** 与 **模型名**，支持 `http://` 与 `https://`（本地部署模型也可连）。
- 自动识别是否为 DeepSeek 端点：仅对 DeepSeek 发送 `thinking`、`reasoning_effort`、`tools`、`title` 等专有参数；其他模型只发送通用参数，避免不兼容报错。
- 适配层会处理不同服务在参数命名、流式字段上的差异。

### 6. 多 Key 管理

- 设置页「切换 / 管理 API Key」可保存多组配置，每组包含名称、API 地址、模型与密钥。
- 密钥按槽位用 Android Keystore 加密存储，元数据（名称/地址/模型）单独保存；删除时自动重排槽位，避免错位。
- 顶栏显示当前使用的 Key，一键切换；兼容旧版本的单 Key 配置，升级后无需重新填写。

### 7. 聊天记录自动备份与恢复

- **自动备份**：默认开启，退到后台时把所有对话（含附件）打包写入手机公共目录 `下载/大肥鱼/`，**卸载 App 不会被删除**；保留最近 2 份，10 分钟节流。
- **手动备份**：设置 → 数据 →「立即备份全部对话」，一键备份并可分享。
- **一键恢复**：设置或欢迎页「从完整备份恢复」，选中 `.dfy` 备份即可恢复全部对话；按原对话 id 去重，不会产生副本，可接着聊。
- 备份包内含 `全部对话.md` 可读副本，用任意文本编辑器都能查看。
- 换机流程：把旧机 `下载/大肥鱼/` 的备份文件传到新机 → 新机安装 App → 从完整备份恢复。

### 8. 稳定性加固

- 全局崩溃捕获：崩溃时写日志到 `下载/大肥鱼-崩溃日志.txt`，便于排查。
- 所有网络回调切回主线程执行，避免跨线程更新 UI 崩溃。
- UI 线程安全性、输入高度动态钳制、畸形 Markdown 渲染兜底，针对小米 HyperOS / Android 15 做了适配。

## 构建方式

### 环境

- JDK 17
- Android SDK Platform 35 + Build-Tools 35.0.0
- Gradle 8.9 及以上（AGP 8.7.3）

### 命令

```bash
# 在项目根目录（Windows 用 gradlew.bat）
./gradlew assembleDebug
# 或
./gradlew assembleRelease
```

产物：

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release-unsigned.apk
```

用 Android Studio 打开根目录也可以直接 Sync / Run。

> 本工程使用自定义 sourceSets：`app/src` 是 Java 源码根，`app/res` 是资源根，`app/AndroidManifest.xml` 是清单文件。这样可以直接使用当前目录结构，不需要搬动文件。
>
> 已附带 Gradle 8.9 wrapper；首次构建会联网下载 Gradle 与 Android Gradle Plugin。

### 关于 local.properties 与签名

- `local.properties` 含本机 SDK 绝对路径，属于个人环境配置，已加入 `.gitignore`，不会随仓库分发。用 Android Studio 打开项目时会自动生成；命令行构建请自行创建并写入：

  ```properties
  sdk.dir=/你的/Android/Sdk/路径
  ```

- 为安全起见，**本仓库不包含发布签名密钥（`.jks`）**。`assembleRelease` 产出的是未签名 APK（`app-release-unsigned.apk`），需你用自己的密钥签名后才能安装。日常调试可直接用 `assembleDebug` 产出的 `app-debug.apk`（Android 默认调试签名）。

### 国内网络加速（可选）

首次构建若下载 Gradle / 依赖缓慢，可配置镜像：在项目根目录新建 `init.gradle` 指向阿里云/腾讯云镜像，构建时加 `-I init.gradle`；JDK、SDK 也可用华为/腾讯云镜像下载。`init.gradle` 属个人环境配置，建议同样加入忽略列表。

## 目录结构

```
.
├── app/
│   ├── AndroidManifest.xml
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   ├── res/
│   │   ├── drawable/            # 31 个矢量图标
│   │   ├── mipmap-anydpi-v26/   # 自适应图标
│   │   ├── values/              # 浅色配色、字符串、主题
│   │   ├── values-night/        # 深色配色
│   │   └── xml/                 # 网络安全配置
│   └── src/com/dafeiyu/chat/
│       ├── MainActivity.java    # 页面编排、抽屉、设置、引导、导入导出
│       ├── ApiClient.java       # Chat Completions SSE、Files 上传、错误处理
│       ├── MarkdownView.java    # 轻量 Markdown 渲染
│       ├── MessageView.java     # 单条消息（思考/搜索/附件/错误/操作）
│       ├── ExportManager.java   # .dfy ZIP 导出与导入
│       ├── Store.java           # 会话文件存储与索引
│       ├── SecureStore.java     # Android Keystore 加密 API Key
│       ├── Prefs.java           # 设置
│       ├── Conv.java / Msg.java / Att.java / ToolCall.java / Cite.java
│       ├── TokenMeter.java      # Token 估算与剩余轮数
│       ├── FileUtils.java       # 图片压缩、文件复制、MIME
│       ├── ImageLoader.java     # 缩略图 LRU 缓存
│       ├── LogoView.java        # 手绘大肥鱼 Logo
│       ├── TypingDots.java      # 输入动画
│       └── Ui.java              # dp/sp、圆角、水波纹、图标按钮
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── gradle/wrapper/gradle-wrapper.properties
```

## API 细节

- 对话：`POST /chat/completions`，`stream=true`，手动解析 SSE。
- 思考：`thinking: {type:"enabled"}` + `reasoning_effort`。
- 联网：`tools: [{type:"deepseek_web_search"},{type:"deepseek_read_url"}]`，解析 `server_tool_event`、`server_tool_calls`、`annotations`。
- 图片：`POST /files`（`purpose=user_data`），对话中用 `{type:"file", file_id:...}` 引用。
- 文档：`POST /files`（`purpose=text_recognition`），纯文本/表格用 `user_data`。
- 标题：第一轮请求带 `title:{enabled:true}`，读取最终 chunk 的 `delta.title`。
- 多轮工具调用：保留并回传 `server_tool_calls`，开启 tools 时回传历史 `reasoning_content`。
- 取消：关闭连接，保留已生成的部分内容。

## 隐私说明

- API Key 使用 Android Keystore 的 AES/GCM 密钥加密后保存在本机 SharedPreferences。
- 聊天记录、附件、导出包都在应用私有目录或用户选择的导出位置。
- 除了直接请求 `api.deepseek.com`，没有中间服务器、统计 SDK 或广告 SDK。
- 本应用是第三方客户端，与 DeepSeek 官方无隶属关系；DeepSeek API 的计费、内容安全与数据规则以官方为准。

## 说明

- 界面字号、间距按 360dp 宽度设计，已做 insets 适配，可在小米 HyperOS / Android 15 上使用。
- 导出包中的附件会显著增大文件体积（图片会以压缩后的 JPEG 保存）。
- 上下文“轮数”是估算值，真实上限由服务端按 Token 计算；当服务端返回上下文超限时，应用会提示导出并新开对话。
- 连接非 DeepSeek 模型时，联网搜索、深度思考等 DeepSeek 专有能力取决于该模型是否支持，应用会自动裁剪专有参数。

## 许可证

本项目采用 [MIT License](LICENSE)，可自由使用、修改与分发，请保留版权与许可声明。

## 免责声明

- 本项目是**第三方开源客户端**，与 DeepSeek 及任何模型服务商无隶属、授权或背书关系。
- 使用者需自行申请并妥善保管 API Key；由此产生的费用、内容合规与数据安全由使用者自行承担。
- 应用仅在你本机与所选 API 服务之间通信，不经过任何中间服务器；「按 DeepSeek 界面习惯重做」仅为交互参考，相关商标归各自权利人所有。
- 本项目按「现状」提供，不含任何明示或暗示担保，具体见 [LICENSE](LICENSE)。
