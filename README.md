# Harness Android

DeepSeek harness 的原生安卓客户端。Kotlin + Jetpack Compose 实现界面，OkHttp 连接你已有的 harness 服务。Markdown 使用原生 TextView 渲染，没有 WebView、Electron 或内嵌网页。

**Android 8.0 及以上。** 使用手机可以访问的服务器 `IP:端口` 或 HTTP/HTTPS 域名，支持局域网、公网及 ZeroTier、Tailscale 等网络。

## 下载与连接

1. 打开 [Releases](https://github.com/Banben07/dsh_Android/releases)，下载最新开发预览中的 `app-debug.apk` 并安装。
2. 确认手机能够访问服务器；使用局域网时连接相应网络，使用 VPN 时按需连接 VPN。
3. 启动 Harness，填写例如 `http://192.168.1.10:3000` 或 `https://harness.example.com`。不带协议的 `IP:端口` 默认使用 HTTP。
4. 首次连接需要 harness 启动时输出的登录 `token`。可以分别填写地址与 Token，或粘贴完整的 `http://IP:端口/?token=…` 链接。如果启动链接使用 `127.0.0.1`，请换成手机实际可访问的服务器地址，保留 token。
5. 打开已有会话，或选择工作空间和代理预设创建新会话。

使用 ZeroTier 时，手机连接与服务器互通的 ZeroTier 网络，服务地址填写服务器的 ZeroTier IP 和 harness 端口，例如 `http://10.147.17.10:3000`。客户端无需额外配置 VPN 协议。

这里填写的是 **harness 的登录 Token**，不是 DeepSeek API Key。模型凭据、工具和工作文件继续由服务器管理。连接地址保存在本机；登录 Cookie 按地址隔离，使用 Android Keystore 加密；启动 Token 不保存。应用直接使用手机当前的网络连接。

HTTPS 反向代理需要同时转发 HTTP 请求与 `/api/remote.mux` WebSocket，并保留登录响应的 `Set-Cookie`。当前客户端使用服务根路径，暂不支持 `/harness/` 等子路径部署。

如果收到 403，检查服务的 `trustedHosts` 是否包含你填写的 IP 和端口；如果收到 401，用服务当前的启动链接重新登录。该客户端不会更改服务器的监听地址、信任配置或认证策略。

## 已实现

- 原生手机与平板布局，系统深浅色主题。
- 会话列表、本地标题/目录搜索、工作空间筛选、已归档会话查看。
- 新建会话、服务器目录、代理预设、重命名会话。
- 模型与思考强度选择，实时回复与可展开的思考过程。
- Markdown、代码、表格、文字选择复制。
- 工具调用参数与执行结果，失败状态，超长结果折叠。
- 排队发送、引导当前任务、停止执行。
- 单次操作允许/拒绝、单选/多选/自定义问题回答、计划确认。
- Cookie 登录，WebSocket 心跳，断线重连、全量快照替换、历史分页与流片段序号检查。
- 切换会话保留草稿。发送失败保留输入，不自动重发可能已送达的请求。

应用进入后台约 8 秒后释放连接以节省电量，服务器任务继续执行；回到前台重新同步。当前版本不提供后台通知、图片/文件上传、原生终端或第三方插件专用面板。已有图片/文件消息会显示附件标记。此版本是开发预览，使用 debug 签名；不同干净构建环境的 debug 签名可能不同，更新时若签名冲突需先卸载旧版，再重新登录。

## 协议兼容性

按照 [DeepSeek harness](https://github.com/deepseek-ai/deepseek-harness) 的实际 Connection/Gateway 和 Session Controller 协议实现，并用本机安装的 `0.1.6-alpha.2` 生成描述符校验请求。harness 仍在快速变化；不保证旧版 SSE 或未来不兼容协议可直接连接。

| 功能 | 接口 |
| --- | --- |
| 登录 | `GET /?token=…` 换取 authority-bound Cookie |
| 普通请求 | `POST /api/<namespace>/<method>`，`client-request`/`server-response` 信封 |
| 实时数据 | `/api/remote.mux` WebSocket；`$events` ready 后读取基线 |
| 会话 | `session/list/create/follow/page/prompt/cancel/rename` |
| 实时状态 | `session/control`、`workspace/follow` |
| 模型与预设 | `session/modelCatalog/selectModel`、`agentPresets/list` |
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

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。`core` 为独立 JVM 模块，协议测试使用本地 MockWebServer，覆盖 Cookie 交换、RPC 信封、WebSocket、取消、断流、历史与流式消息衔接，不调用真实模型。

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

本项目为独立客户端，非 DeepSeek 官方安卓应用。
