# DeepSeek Harness Android

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Android APK](https://github.com/Banben07/dsh_Android/actions/workflows/android.yml/badge.svg)](https://github.com/Banben07/dsh_Android/actions/workflows/android.yml)

An independent, native Android client for [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness). Built with Kotlin, Jetpack Compose, and OkHttp, with native Markdown rendering and no embedded WebView or Electron.

**Current app version: 0.3.9 · Requires Android 8.0 or later.** Models, tools, and working files remain on your own Harness server. This is a community project, not an official DeepSeek Android app.

## Download and connect

1. Download `app-debug.apk` from the latest preview on the [Releases page](https://github.com/Banben07/dsh_Android/releases).
2. Make sure your phone can reach your Harness server. LAN, ZeroTier, and Tailscale connections use the phone's existing network routing.
3. Enter the server's root URL, such as `https://harness.example.com` or `http://192.0.2.10:3000`. Addresses without a scheme default to HTTP.
4. Enter the login token printed when Harness starts, or paste its complete launch URL. Use the **Harness login token**, not your model provider's API key.
5. Open a conversation or tap **+** in the top-right corner to create one. Configure default workspace, directory, and agent preset in settings.

`192.0.2.10` and `2001:db8::1` are reserved documentation addresses; `example.com` is an example domain. Replace them with your own server address. Deployments under a URL subpath, such as `/harness/`, are currently unsupported.

An HTTPS reverse proxy must forward HTTP requests and the `/api/remote.mux` WebSocket endpoint, and preserve login `Set-Cookie` headers. HTTP 401 indicates that authentication is required. For HTTP 403, check that the server's `trustedHosts` permits the address and port you entered.

## Features

- Native phone and tablet layouts, light and dark themes, adjustable font size, Markdown, code blocks, tables, and text selection.
- Conversation search, workspace filtering, one-tap creation, renaming, and long-press archive/unarchive actions.
- Model and reasoning-effort selection, streaming replies, queued messages, steering, and cancellation.
- A combined reasoning and tool activity group, showing the latest item with expandable earlier activity.
- Native approvals, single-choice and multiple-choice questions, custom answers, and plan confirmation.
- A `/` command menu, server command execution, native file selection, and conversation ZIP export.
- Multiple image and file attachments, previews, upload progress, cancellation, and manual retry. The server enforces file and model limits.
- Optional reply-completion, approval, and question notifications that open the associated conversation.
- Connection checks and locally stored connection and crash diagnostics.

### Conversation switching

Up to three recently visited conversations share the main WebSocket and retain their subscriptions, so they continue receiving updates while another conversation is displayed. Returning to a retained conversation does not fetch its entire history again. Command lists and reading positions are also retained. Each subscription processes snapshots, updates, and history pages in order on a worker dispatcher.

Subscriptions are evicted according to visit order, a character budget, and background message count. A separate display cache retains up to six conversations, with a total budget of two million characters and at most 500 displayed messages per conversation. The currently displayed conversation is exempt from background eviction. After eviction or disconnection, the app shows cached content while fetching a fresh snapshot. Initial snapshots request the most recent 30 messages; older messages can be loaded in pages.

Text and attachment drafts remain available per conversation until the process exits. Failed sends preserve input. Requests that may already have reached the server are never automatically resent; check the conversation before retrying.

### Background connections and notifications

The background-connection option uses an Android foreground service. When disabled, the app releases the main connection approximately eight seconds after entering the background and synchronizes again when reopened. Enabling reply notifications creates a separate notification-listener connection.

**Continuous background connectivity and timely notifications depend on Android, the device manufacturer's power management, and VPN background policies. The app does not guarantee five to ten minutes of connectivity under battery optimization.** A foreground service cannot guarantee uninterrupted execution or network access. The app retains displayed content and attempts to recover when the connection becomes unusable.

Returning to the foreground checks the existing connection and reconnects when needed. Socket failure state is published before diagnostic logging, preventing recovery checks from reading stale state. This does not resolve background network suspension imposed by the operating system.

Connection logs and the most recent crash report can be copied from settings. Reports are stored locally and redact common credential formats; review them before sharing. An `EOFException` alone cannot distinguish a server heartbeat timeout from a VPN or other network failure. Correlate it with server diagnostics.

## Data and privacy

- Server addresses are stored on the device. Login cookies are isolated by address and encrypted with Android Keystore; launch tokens are not persisted.
- The Harness backend manages model API keys, tool permissions, and server-side working files.
- Building and testing this repository requires no personal server IPs, private domains, login tokens, cookies, or model credentials.
- Keep `local.properties`, local build outputs, signing keys, and IDE settings out of version control.

## Build

[GitHub Actions](https://github.com/Banben07/dsh_Android/actions/workflows/android.yml) runs tests, Android Lint, and APK compilation on pushes to `main` or manual workflow dispatch. Successful builds publish a preview release. Builds do not access your Harness server or require login credentials or model API keys.

For local builds, install JDK 17, Android SDK Platform 35, Build-Tools 35.0.0, and Platform-Tools. Set `ANDROID_HOME`, or specify `sdk.dir` in the ignored `local.properties` file. These tools can be installed in a user directory without sudo.

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

The project uses Gradle 8.11.1, Android Gradle Plugin 8.9.2, and Kotlin 2.1.20. Protocol tests use a local MockWebServer; native UI tests use Robolectric. Neither calls a real model.

Preview APKs use debug signing. Different build environments may produce different signing certificates, preventing an in-place upgrade. Uninstalling clears local settings and drafts; conversations stored on the server are unaffected.

## Protocol compatibility and layout

The client implements Harness cookie authentication, RPC envelopes, `/api/remote.mux`, and the conversation, file, command, and approval APIs. Requests were checked against actual generated descriptors from Harness `0.1.6-alpha.2`. Other versions need validation, and compatibility with future protocol changes is not guaranteed.

| Path | Purpose |
| --- | --- |
| `core/` | Address handling, HTTP, WebSocket, conversation reconstruction, and protocol tests |
| `app/` | Compose UI, conversation state, notifications, and encrypted cookie storage |
| `.github/workflows/android.yml` | Verification, compilation, and preview publishing |
| `fixtures/requests.json` | Protocol request examples without real user data |
| `tools/verify-harness-contract.mjs` | Optional validation against installed Harness descriptors |

Optional contract check:

```bash
node tools/verify-harness-contract.mjs /path/to/harness/node_modules
```

References: [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness), [Connection](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/client/connection/README.md), and [API Gateway](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/api-gateway.md).

## License and attribution

Original code and documentation in this project are available under the [MIT License](LICENSE). MIT permits use, modification, distribution, and commercial use, provided its copyright and permission notices are retained.

Third-party dependencies remain subject to their own licenses. The whale icon was adapted from the `dist/favicon.svg` asset in the official Harness `@deepseek-ai/dsh-web-frontend` package. DeepSeek names, trademarks, and artwork remain the property of their respective owners and are not relicensed under this project's MIT License. This project claims no official affiliation or endorsement.
