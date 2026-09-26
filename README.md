# DeepSeek Harness Android

DeepSeek harness 的原生安卓客户端。Kotlin + Jetpack Compose 实现界面，OkHttp 连接你已有的 harness 服务。Markdown 使用原生 TextView 渲染，没有 WebView、Electron 或内嵌网页。

**Android 8.0 及以上。** 使用手机可以访问的服务器 `IP:端口` 或 HTTP/HTTPS 域名，支持局域网、公网及 ZeroTier、Tailscale 等网络。

## 下载与连接

1. 打开 [Releases](https://github.com/Banben07/dsh_Android/releases)，下载最新开发预览中的 `app-debug.apk` 并安装。
2. 确认手机能够访问服务器；使用局域网时连接相应网络，使用 VPN 时按需连接 VPN。
3. 启动 DeepSeek Harness，填写例如 `http://192.168.1.10:3000` 或 `https://harness.example.com`。不带协议的 `IP:端口` 默认使用 HTTP。
4. 首次连接需要 harness 启动时输出的登录 `token`。可以分别填写地址与 Token，或粘贴完整的 `http://IP:端口/?token=…` 链接。如果启动链接使用 `127.0.0.1`，请换成手机实际可访问的服务器地址，保留 token。
5. 打开已有会话，或点击右上角“＋”或侧栏的“新建对话”开始。工作空间、服务器目录和代理预设可在“设置 → 新对话默认设置”中预先保存。

使用 ZeroTier 时，手机连接与服务器互通的 ZeroTier 网络，服务地址填写服务器的 ZeroTier IP 和 harness 端口，例如 `http://10.147.17.10:3000`。客户端无需额外配置 VPN 协议。

这里填写的是 **harness 的登录 Token**，不是 DeepSeek API Key。模型凭据、工具和工作文件继续由服务器管理。连接地址保存在本机；登录 Cookie 按地址隔离，使用 Android Keystore 加密；启动 Token 不保存。应用直接使用手机当前的网络连接。

HTTPS 反向代理需要同时转发 HTTP 请求与 `/api/remote.mux` WebSocket，并保留登录响应的 `Set-Cookie`。当前客户端使用服务根路径，暂不支持 `/harness/` 等子路径部署。

如果收到 403，检查服务的 `trustedHosts` 是否包含你填写的 IP 和端口；如果收到 401，用服务当前的启动链接重新登录。该客户端不会更改服务器的监听地址、信任配置或认证策略。

## 已实现

- 原生手机与平板布局，系统深浅色主题。
- 会话列表、本地标题/目录搜索、工作空间筛选；长按会话选择归档，在“已归档”列表中长按可取消归档，状态同步到服务器。
- 一键新建会话；每个服务器分别保存默认工作空间、目录和代理预设；重命名会话。
- 模型与思考强度选择、实时回复；设置中调节字体大小（80%–150%），即时生效并保存在本机，100% 跟随系统字体大小。
- Markdown、代码、表格、文字选择复制。
- 思考过程与工具调用合并到一个分组，每个会话默认只显示最新一项，较早的过程统一收进可展开的历史列表；正文仍显示在对话中。
- `/` 菜单读取服务器命令目录并执行真实命令，显示结果和失败原因；原生模型选择、文件选择、设置、新会话与会话 ZIP 导出。
- 系统文件选择器选择多个图片或文件，显示缩略图、名称、大小、进度，支持取消和失败后重试。
- 可选的后台回复完成通知，点击通知打开对应会话。
- 设置中的连接测试与本地崩溃记录复制。
- 排队发送、引导当前任务、停止执行。
- 单次操作允许/拒绝、单选/多选/自定义问题回答、计划确认。
- Cookie 登录，WebSocket 心跳，断线重连、全量快照替换、历史分页与流片段序号检查。
- 切换会话保留草稿。发送失败保留输入，不自动重发可能已送达的请求。

### 附件与命令

点击输入框旁的附件按钮可选择图片或任意文件，也可以使用 `/file`。PNG、JPEG、WebP、GIF 作为图片发送；HEIC、SVG 等其他格式按普通文件发送。服务端仍会应用自己的大小、格式和模型能力限制。普通文件通过二进制接口上传，图片直接编码到请求流；不会把整个上传文件读入内存。历史图片可以点击查看。

输入 `/` 查看可用命令。模型选择和文件选择打开原生界面，其余服务器命令通过 `commands/execute` 执行；未知命令会提示错误。`/export` 在系统保存对话框中选择位置后下载会话 ZIP。第三方插件若依赖自定义网页面板，当前只支持它们公开的命令及文本结果。

新建对话立即进入创建页面，服务器确认后即可输入，不再等待整个会话列表刷新或首份历史快照。切换回最近看过的会话时先显示内存中的内容，再同步最新消息；缓存最多保留 6 个会话，并限制文本总量与消息数量。首次打开、缓存被淘汰或进程重启后仍需要加载服务器历史。历史快照整理在后台线程完成，快速切换时会取消过时的命令列表请求。顶栏右侧的“＋”可直接新建对话，发送消息后输入法会立即收起。

文字和附件草稿按会话保留到应用进程结束。发送失败后会保留输入；如果请求已经发出但没有收到确认，请先查看会话，避免重复发送。

### 后台通知与崩溃记录

“后台保持连接”默认开启，通过安卓前台服务维持进程和会话连接。未开启回复通知时，后台服务只负责保活应用进程，不再建立第二条 Harness 实时连接；开启回复通知时才建立后台监听。普通切换应用后沿用原来的实时连接，失效时自动重建；返回前台会识别已经失效的 WebSocket 并立即重试。重连前在后台线程清理失效的空闲 HTTP 连接，不取消正在发送的请求，也不自动重发消息。短暂恢复保留聊天内容，连续失败仍显示错误，不再固定在 8 秒后断开；会显示一条后台连接通知。可以在设置中关闭此功能，关闭时也会关闭回复完成通知。

在设置中开启“回复完成通知”，并允许安卓通知权限。应用会显示一条后台消息连接通知，在退到后台或锁屏时监听完成事件；点击完成提醒返回会话。取消或失败的任务不会显示为完成，首次连接也不会把旧会话当成新回复通知。若系统强制停止应用、网络断开或手机限制后台运行，提醒可能延迟或无法送达。

关闭“后台保持连接”后，应用进入后台约 8 秒后释放实时连接，服务器任务继续执行，回到前台重新同步。开启回复完成通知时会同时开启后台连接。系统强制结束进程或网络实际中断后仍需重连。

如果发生闪退，重新打开应用，在设置中点击“复制上次崩溃记录”。记录包含应用版本、安卓版本、设备型号和异常堆栈，仅保存在本机，并对常见凭据格式进行隐藏；发送给他人前仍可检查内容。系统直接终止进程的情况不一定产生异常记录。根据一加 13T（Android 36）的崩溃记录，已将切换地址时的 TLS 连接池关闭、实时连接关闭和 HTTP 请求取消统一移到后台线程；也已修正网络响应在界面线程读取、流式工具条目标识冲突等问题。修复有自动化回归验证，仍需真机复测。

### HTTP 连接排查

HTTP 和 HTTPS 均已启用，客户端使用安卓系统的当前路由，不额外绑定 Wi-Fi 或蜂窝网络。设置中的“测试连接”不发送 Token，会显示该地址是否响应以及系统是否检测到 VPN。实际连接失败会显示登录、实时连接或会话同步阶段及具体原因。测试显示 HTTP 401 表示网络入口可达、需要登录，并不表示网络不通。

如果手机浏览器能打开同一个 IP:端口而应用仍失败，请保留完整错误和测试结果；仅根据 HTTPS 正常无法确定是 ZeroTier 路由、登录还是 WebSocket 问题。

此版本是开发预览，使用 debug 签名；不同干净构建环境的 debug 签名可能不同，更新时若签名冲突需先卸载旧版，再重新登录。卸载会清除本机设置及草稿，服务器会话不受影响。当前不提供原生终端或第三方插件专用面板。

## 协议兼容性

按照 [DeepSeek harness](https://github.com/deepseek-ai/deepseek-harness) 的实际 Connection/Gateway 和 Session Controller 协议实现，并用本机安装的 `0.1.6-alpha.2` 生成描述符校验请求。harness 仍在快速变化；不保证旧版 SSE 或未来不兼容协议可直接连接。

| 功能 | 接口 |
| --- | --- |
| 登录 | `GET /?token=…` 换取 authority-bound Cookie |
| 普通请求 | `POST /api/<namespace>/<method>`，`client-request`/`server-response` 信封 |
| 实时数据 | `/api/remote.mux` WebSocket；`$events` ready 后读取基线 |
| 会话 | `session/list/create/follow/page/prompt/cancel/rename` |
| 实时状态 | `session/control`、`workspace/follow/archiveSession/unarchiveSession` |
| 模型与预设 | `session/modelCatalog/selectModel`、`agentPresets/list` |
| 附件 | `POST /api/session/uploadFileBinary`、`session/attachment`、图片及文件 prompt parts |
| 命令与导出 | `commands/list/execute`、`GET /api/session.export` |
| 审批与问题 | `$events` waterfall，`$events/result` 带 clientId/eventId 回答 |

参考：[Connection](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/client/connection/README.md)、[API Gateway](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/api-gateway.md)。没有把 harness 假定为 OpenAI Chat Completions 服务。

## GitHub 远程构建

推送到 `main` 或在 [Actions](https://github.com/Banben07/dsh_Android/actions/workflows/android.yml) 页面选择 **Run workflow**。流水线执行协议测试、Android Lint 和 APK 编译，通过后将 APK 保存为 Artifact 并发布带构建编号的预览 Release。构建过程不需要访问你的服务器网络、后端登录 Token 或模型 API Key。

构建版本固定为 JDK 17、Gradle 8.11.1、Android Gradle Plugin 8.9.2、Kotlin 2.1.20、Android SDK 35。Gradle Wrapper 包含 SHA-256 校验。当前工程无需 Android Studio 即可通过 Actions 构建。

## 本地开发与验证

安装 JDK 17、SDK Platform 35、Build-Tools 35.0.0 和 Platform-Tools，设置 `ANDROID_HOME`，或在 `local.properties` 中设置 `sdk.dir`。所有工具都可安装在用户目录，无需 sudo。

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。`core` 为独立 JVM 模块，协议测试使用本地 MockWebServer，覆盖 Cookie 交换、RPC 信封、WebSocket、取消、断流、历史与流式消息衔接、上传字节、命令参数、导出和完成事件；原生界面测试覆盖审批、命令菜单、附件交互和工具折叠，不调用真实模型。

如果本机安装了 harness，可以另外验证请求是否符合后端实际生成的接口：

```bash
node tools/verify-harness-contract.mjs /path/to/harness/node_modules
```

`fixtures/requests.json` 不包含任何真实登录信息或工作区数据。不要将启动链接、Cookie、API Key 或个人签名密钥写入仓库。

## 代码结构

- `core/`：地址解析、HTTP/Cookie 接口、WebSocket、会话事件还原和 JVM 测试。
- `app/`：Jetpack Compose 界面、生命周期与状态管理、Android Keystore Cookie 存储。
- `.github/workflows/android.yml`：测试、编译与预览 APK 发布。
- `tools/verify-harness-contract.mjs`：与实际 harness 描述符的兼容性校验。

应用鲸鱼图标使用 DeepSeek harness 官方 `@deepseek-ai/dsh-web-frontend` 包的 `dist/favicon.svg` 路径数据，转换为原生 Android VectorDrawable。品牌及标识属于 DeepSeek。

本项目为独立客户端，非 DeepSeek 官方安卓应用。
