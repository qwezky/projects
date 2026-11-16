# android-transfer —— 手机 ↔ 服务器：文件传输（SFTP + 隧道里的 HTTP）

一个 Compose 写的文件传输 App：手机和服务器之间双向传文件，有 IPv6 就直连 SFTP，
没有就走一条 HTTP 通道（同样是 HTTPS，但不再把 SSH 塞进 WebSocket）。

```
            ┌──────────────── 手机 App ────────────────┐
            │ ① 走哪条路 / 我的公钥 / HTTP 通道参数      │
            │ ② 测试连接                                │
            │ ③ 从服务器下载（目录缓存 + 搜索）          │
            │ ④ 已下载的文件                            │
            │ ⑤ 上传到服务器（文件 / 文本 / 分享入口）    │
            └────┬───────────────────┬─────────────────┘
        SFTP over SSH         HTTP（转发的 HTTPS）
            │                     │
      服务器 sshd          服务器上的文件服务
      （有 IPv6 时最快）     （没有 IPv6 也能用）
```

## 功能

- **上传**：文件选择器、文本、系统分享入口（从别的 App 分享文件过来直接传）
- **下载**：浏览远端目录（先出本地缓存，再后台核对）、逐文件下载、断点续传
- **搜索**：按文件名在服务器上搜（不用一层层点进去），命中可直接下载或跳进目录
- **已下载**：本地文件列表，可再次分享/打开
- **密钥管理**：App 内生成 RSA-4096，界面直接给出要追加到服务器 `authorized_keys` 的那一行
- **选路**：`自动`（有公网 IPv6 先直连，没有就直接 HTTP）/ `直连` / `HTTP` / `隧道`

## 四条路径，为什么需要选

同一台服务器，差别只在「怎么到那儿」：

| 路径 | 做法 | 实测 |
|---|---|---|
| `直连` | SSH 直连服务器 IPv6:22 | 局域网/校园网：丢包 0%、RTT 30ms；调参后 67 MB/s |
| `HTTP` | 走服务器上的文件服务（HTTPS + 自定义头鉴权） | 上传 1.0–2.6 MB/s、下载 1.6–3.1 MB/s，支持 Range/offset 续传 |
| `隧道` | SSH 装进 WebSocket，走 CDN 的 443 | 建会话 ~2s；**≥64KB 的流会被 QUIC 那一跳弄坏**（MAC 校验失败），只适合小操作 |
| `自动` | 本机有公网 IPv6 先直连，否则直接 HTTP；传输中太慢会换路 | — |

移动数据下直连不一定「连不上」，而是**连上了但很慢**（丢包 10–35%、cwnd 被压到 1、
吞吐 0.1–0.5 Mbit/s）。所以不能只做「连不上才退」，还要按网络类型/本机 IPv6 预先选路，
并在传输过程中发现太慢就换路（`RouteWatchdog`：观察 6 秒、至少 512KB、低于 120KB/s 就换）。

## 两个值得说的实现点

**1. 目录缓存 + 只取变更。** 列目录要一个来回，慢链路上就是干等。现在进目录先用本地缓存
立刻画出来，再带 `If-None-Match` 问一次：没变就是 304（没有 body），界面不动；变了才换。
缓存落在 `filesDir/dir-cache.json`（最多 300 个目录，LRU，原子写）。

**2. 传输层是个接口。** `RemoteClient` 由 `SftpClient` 和 `HttpTransferClient` 实现，
上传/下载/续传/列目录/搜索的代码一份，选路只发生在连接层。两条通道的鉴权也不一样：
SFTP 用受限公钥（服务端 `command=` 收窄到只允许 sftp），HTTP 用「设备 ID + 设备密钥」。

## 构建

```bash
# 需要有 Android SDK（local.properties 写 sdk.dir，或设 ANDROID_HOME）
./gradlew :app:assembleDebug
```

产物在 `app/build/outputs/apk/debug/`。

## 服务器侧

App 只负责客户端；服务器需要一个 sshd（直连）和一个 HTTP 文件服务（HTTP 通道）。
HTTP 服务需要实现这几个端点（`X-Trans-Device` + `X-Trans-Token` 鉴权）：

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/stat?path=` | 大小/mtime；不存在 404 |
| GET | `/api/list?path=` | 目录项 + `ETag`；带 `If-None-Match` 未变回 304 |
| GET | `/api/get?path=` | 下载，支持 `Range` |
| PUT | `/api/put?path=&offset=` | 上传，`offset>0` 为续传 |
| POST | `/api/mkdir?path=` | 建目录 |
| GET | `/api/find?q=&base=` | 按文件名搜索 |
| GET | `/api/hostinfo` | 服务器公网 IPv6（做直连候选） |

## 已知边界

- 隧道那条路（SSH over WebSocket）在部分网络上会被中间的 QUIC 跳弄坏大包；本文档里的
  实测数字来自特定链路，换网络要重测。
- HTTP 通道的吞吐受那条 HTTPS 链路限制，和服务器上传带宽同量级。
- 搜索在服务器端是 `find -maxdepth`，深目录可能超时（返回部分结果）。
