# DeepSeek Harness Android

DeepSeek Harness 的独立原生 Android 客户端，当前版本 **0.3.9**，支持 **Android 8.0 及以上**。使用 Kotlin、Jetpack Compose 和 OkHttp 实现，不嵌入 WebView 或 Electron。模型调用、工具执行和工作文件由你自己的 Harness 服务器管理。

本项目非 DeepSeek 官方 Android 应用。鲸鱼图标的品牌及标识属于 DeepSeek。

## 下载与连接

1. 前往 [Releases](https://github.com/Banben07/dsh_Android/releases) 下载最新预览版的 `app-debug.apk`。
2. 确认手机能访问 Harness 服务器；局域网、ZeroTier 和 Tailscale 均使用手机已有的网络连接。
3. 填写服务器根地址，例如 `https://harness.example.com` 或 `http://192.0.2.10:3000`。不带协议时默认使用 HTTP。
4. 填写 Harness 启动时提供的登录 Token，或粘贴完整启动链接。这里使用的是 **Harness 登录 Token**，不是模型 API Key。
5. 打开已有会话，或点击右上角“＋”新建会话。工作空间、目录和代理预设可在设置中保存为新对话默认值。

文档及界面中的 `192.0.2.10` 和 `2001:db8::1` 是保留的文档示例地址；`example.com` 是示例域名。使用时请替换为自己的服务器地址。服务暂不支持 `/harness/` 等子路径部署。

HTTPS 反向代理应转发 HTTP 与 `/api/remote.mux` WebSocket，并保留登录响应的 `Set-Cookie`。HTTP 401 表示需要登录；HTTP 403 时请检查服务器 `trustedHosts` 是否允许所填地址与端口。

## 功能

- 原生手机和平板布局、深浅色主题、可调字号、Markdown、代码和表格显示，以及文字复制。
- 会话搜索、工作空间筛选、一键新建、重命名、长按归档与取消归档。
- 模型和思考强度选择、流式回复、排队发送、引导当前任务与停止执行。
- 思考过程和工具调用合并展示，默认显示最新一项，较早过程可展开查看。
- 原生审批、单选、多选、自定义问题回答与计划确认。
- `/` 命令菜单、服务器命令执行、原生文件选择和会话 ZIP 导出。
- 多图片与文件附件、缩略图、上传进度、取消和失败后重试；实际格式与大小限制由服务器决定。
- 可选的回复完成、审批与提问通知，点击通知打开对应会话。
- 连接测试、本机连接日志和崩溃记录复制。

### 会话切换

最近访问的最多三个会话复用同一条主 WebSocket，保留各自订阅并持续接收更新。切换回来无需重新请求完整历史；命令列表和阅读位置也会保留。每个订阅按顺序在工作线程处理快照、增量和分页。

订阅按访问顺序、数据字符预算和后台消息数量淘汰。另保留最多六个会话的显示缓存，总显示文本预算为 200 万字符，单会话最多 500 条显示消息。当前正在阅读的会话不因后台缓存预算被关闭。淘汰或断网后重新打开时先显示缓存，再同步服务器快照。初次读取最近 30 条消息，更早历史可分页加载。

文字与附件草稿按会话保留到进程结束。发送失败时保留输入，应用不会自动重发可能已经送达的消息；请确认服务器是否收到后再重试。

### 后台连接与通知

“后台保持连接”通过 Android 前台服务维持进程。关闭此选项后，应用进入后台约八秒释放主连接，回到前台重新同步。开启回复通知时，服务会建立独立的通知监听连接。

**后台持续在线和通知及时送达受 Android、手机厂商及 VPN 的后台策略影响。当前版本不保证在智能省电模式下持续在线 5–10 分钟。** 前台服务不能保证绕过系统冻结或网络暂停。连接失效后应用会保留显示内容并尝试恢复。

回到前台时会检查原连接，无响应时重新连接。当前版本会先发布 socket 失效状态，再记录断连日志，避免恢复检查读取到过时状态；这项修正没有解决系统限制造成的后台断网。

如果遇到断连，可在设置中复制连接日志；闪退后可复制上次崩溃记录。记录保存在本机并对常见凭据格式进行隐藏，分享前请自行检查是否包含敏感内容。`EOFException` 本身无法确定是服务器心跳超时、VPN 路由中断还是其他链路问题，应结合服务器日志判断。

## 数据与隐私

- 服务器地址保存在本机，登录 Cookie 按地址隔离并使用 Android Keystore 加密；启动 Token 不保存。
- 模型 API Key、工具权限及服务器工作文件由 Harness 后端管理。
- 仓库不需要真实服务器 IP、私人域名、登录 Token、Cookie 或模型凭据即可构建和运行测试。
- `local.properties`、本地构建产物、签名密钥和 IDE 配置不应提交。

## 构建

GitHub [Actions](https://github.com/Banben07/dsh_Android/actions/workflows/android.yml) 在推送 `main` 或手动运行后，执行测试、Android Lint 与 APK 构建，通过后发布预览 Release。构建不访问你的 Harness 服务，也不需要服务器登录凭据或模型 API Key。

本地需要 JDK 17、Android SDK Platform 35、Build-Tools 35.0.0 和 Platform-Tools。设置 `ANDROID_HOME`，或在被 Git 忽略的 `local.properties` 中填写 `sdk.dir`。这些工具可以安装在用户目录，无需 sudo。

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

APK 输出位置：`app/build/outputs/apk/debug/app-debug.apk`。

工程使用 Gradle 8.11.1、Android Gradle Plugin 8.9.2 和 Kotlin 2.1.20。协议测试使用本地 MockWebServer，界面测试使用 Robolectric，不调用真实模型。

开发预览使用 debug 签名。不同构建环境的签名可能不同，签名冲突时无法直接覆盖安装；卸载会清除本机设置与草稿，服务器会话不受影响。

## 协议兼容性与代码结构

客户端实现 Harness 的 Cookie 登录、RPC 信封、`/api/remote.mux` 及会话、文件、命令、审批接口。请求曾通过 Harness `0.1.6-alpha.2` 的实际生成描述符校验；其他版本的兼容性需要验证，不能保证兼容未来的协议变更。

| 目录 | 用途 |
| --- | --- |
| `core/` | 地址、HTTP、WebSocket、会话事件还原和协议测试 |
| `app/` | Compose 界面、会话状态、通知与加密 Cookie 存储 |
| `.github/workflows/android.yml` | 测试、构建和预览发布 |
| `fixtures/requests.json` | 不含真实用户数据的协议请求样例 |
| `tools/verify-harness-contract.mjs` | 与已安装 Harness 描述符核对接口 |

可选的接口校验：

```bash
node tools/verify-harness-contract.mjs /path/to/harness/node_modules
```

参考：[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)、[Connection](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/client/connection/README.md)、[API Gateway](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/api-gateway.md)。

仓库目前未附带开源许可证；公开可见不等于已经授予修改、再分发或商用许可。第三方组件和 DeepSeek 品牌标识分别受其自身授权约束。
