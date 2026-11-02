# android-transfer —— 手机 ↔ 服务器：SSH/SFTP 传文件 + 局域网 HTTP 分享

一个 Compose 写的文件传输 App：手机和服务器之间用 SFTP 双向传，手机自己的文件
还能开一个只读 HTTP 服务给同局域网的其他设备下载。密钥在 App 内生成，连接地址能自动发现。

```
            ┌──────────── 手机 App ────────────┐
            │ ① 走哪条路 / 我的公钥            │
            │ ② 测试连接                       │
            │ ③ 从服务器下载（浏览器远端目录）  │
            │ ④ 已下载的文件                   │
            │ ⑤ 上传到服务器（文件 / 文本 / 分享）
            └───────┬───────────────┬─────────┘
        SFTP over SSH        HTTP（仅局域网）
                    │               │
              服务器 sshd      浏览器 / 其他设备
```

## 功能

- **上传**：文件选择器、文本、系统分享入口（从别的 App 分享文件过来直接传）
- **下载**：浏览服务器远端目录，逐个下载；支持断点/进度显示
- **已下载**：本地文件列表，可再次分享/打开
- **HTTP 分享**：把手机某个目录用只读 HTTP 暴露给同一 Wi-Fi，浏览器直接下
- **密钥管理**：App 内生成 RSA-4096，界面直接给出要追加到服务器 `authorized_keys` 的那一行
- **选路**：`auto`（Wi-Fi → 直连；移动数据/VPN → 隧道）/ `direct` / `tunnel`

## 三条路径，为什么需要选

同一把 key、同一台服务器，差别只在「怎么到那儿」：

| 路径 | 做法 | 实测 |
|---|---|---|
| `direct` | SSH 直连服务器 IPv6:22 | 校园网：丢包 0%、RTT 30ms、跑满 |
| `tunnel` | SSH 装进 WebSocket，走 CDN 的 443 | 服务器侧 1.06 MB/s，任何网络都能连 |
| `auto` | 按当前网络类型选 | Wi-Fi/有线 → 直连；移动数据/VPN/未知 → 隧道 |

移动数据下直连不一定「连不上」，而是**连上了但很慢**（丢包 10–35%、cwnd 被压到 1、
吞吐 0.1–0.5 Mbit/s）。所以不能只做「连不上才退隧道」，还要按网络类型预先选路，
并在传输过程中发现太慢就换路（见 `RouteWatchdog`）。

## 构建

需要 Android SDK 36、JDK 17：

```bash
# 本地有 SDK 时
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew :app:assembleDebug
```

或直接用 Android Studio 打开本目录。产物在 `app/build/outputs/apk/debug/`。

版本号在 `app/build.gradle.kts` 的 `versionCode` / `versionName`。**交付文件名要带版本与
哈希**：文件名永远相同的话，手机上从「下载」目录点开同名旧文件，装完还是老版本，
而 debug 包大小又很接近，肉眼分辨不出来。

## 首次连接

1. App「走哪条路」卡片 → 生成密钥 → 复制那行公钥
2. 在服务器上追加（`restrict` 会禁掉端口转发，需要端口转发时按需逐项收窄）：

   ```
   no-pty,no-agent-forwarding,no-X11-forwarding,no-user-rc ssh-ed25519 AAAA… transfer-app
   ```

3. 填服务器地址（或让 App 自动发现）、用户名、远端目录
4. 「测试连接」→ 上传/下载

## 安全设计

- 私钥只落在 App 私有目录 `filesDir/ssh/`，并在备份规则里排除（不进云备份）
- 生成的密钥无口令；App 不读取系统里已有的私钥
- 服务器侧建议按需收窄 `authorized_keys` 选项（`no-pty`、`no-agent-forwarding`、
  `no-X11-forwarding`、`no-user-rc`），需要端口转发才加 `permitopen`
- 隧道模式只做 SSH 转发，不额外开公网端口
- HTTP 分享只在局域网监听，且**只读**（只实现 GET/HEAD），不提供上传或目录穿越

## 工程结构

```
app/src/main/java/dev/example/transfer/
  MainActivity.kt / NavigationKeys.kt
  ui/main/           Compose 界面与 ViewModel
  data/              配置、路由选择、上传仓库、看门狗
  net/               JSch 封装、SFTP、WebSocket、端口转发、地址发现、HTTP 服务、下载器
  theme/             主题
app/src/test/        单元测试（路径拼接、路由、地址解析等）
```

## 已知边界

- 服务器必须能从手机访问到（IPv6 直连或域名 + WebSocket 隧道）
- 隧道模式下大文件传输可能触发 MAC 校验失败（实测 ≥64KB 不稳定），所以批量传输优先直连；
  App 里有一个「保守传输模式」开关用于 A/B 排查
- HTTP 分享不加密，只适合可信局域网
- 只支持 SSH 密钥登录（不支持密码），服务器需允许 `publickey`
- 自动地址发现依赖服务器侧可读的 IPv6 信息来源；发现失败时手工填地址
