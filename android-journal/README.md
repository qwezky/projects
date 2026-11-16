# android-journal —— 手机 → 服务器：存档投递 + 待办查看

一个 Compose 写的「存档」App：把文本和图片丢进服务器的收件目录，触发服务器归档，
再按日期区间把抽出来的待办拉回手机看。上传两条通道：SFTP（有 IPv6 时最快）和
HTTP（没有 IPv6 也能用）。

```
   手机（分享 / 选择文件）
          │
          ├─ SFTP over SSH ────────► 服务器收件目录
          └─ HTTP（转发的 HTTPS）──► 同一个目录
                                          │
                                    触发归档（SFTP: exec 固定字符串 / HTTP: POST）
                                          │
                              服务器：清洗 → OCR → 抽取待办
                                          │
   手机 ③ 待办  ◄────────── todos/index.jsonl（只读）
```

## 功能

- **投递存档**：选文本/图片，或从别的 App 分享过来；只收白名单类型，其余当场拒绝并说明
- **整批触发**：一批全传成功后才触发一次归档（失败就不触发，避免半批进库）
- **看待办**：把 `todos/index.jsonl` 拉回本地，按日期区间筛，**只读不改**服务端
- **选路**：`自动`（有公网 IPv6 先直连，否则直接 HTTP）/ `直连` / `HTTP` / `隧道`
- **密钥**：App 内生成 RSA-4096；服务器那条授权行用 `command=` 收窄成「只能 SFTP + 只能发一个固定字符串」

## 两条通道，为什么都要

| 路径 | 做法 | 适合 |
|---|---|---|
| `直连` | SSH 直连服务器 IPv6:22 | 有公网 IPv6 且链路好时最快 |
| `HTTP` | 走服务器上的文件服务（HTTPS + 设备头鉴权） | 没有 IPv6、或隧道里 SSH 大包会坏时 |
| `隧道` | SSH 装进 WebSocket 走 CDN 443 | 只救急：大文件会被中间那跳弄坏（MAC 校验失败） |

触发归档这件事两条路都做得到，但机制不同：

- SFTP：在同一条会话上开 exec 通道发**固定字面量** `archive-trigger`，服务端 forced command 匹配到就执行；
- HTTP：`POST /api/archive/trigger`，服务端跑同一个归档命令。

## 实现上的两个取舍

**1. 传输层是个接口。** `RemoteClient` 由 `SftpClient` 与 `HttpTransferClient` 实现，
上传/续传/读索引/触发归档都是一份代码，选路只发生在连接层。

**2. 上传是「先落手机缓存，再按文件传」。** SAF 给的流常常每次只吐几 KB，
直接灌进 SSH 会退化成一个写一个来回；先整份落到 cacheDir 再传，快一个量级，
还天然支持续传（远端已有字节 + 本地 sha256 可核对）。

## 构建

```bash
# 需要 Android SDK（local.properties 写 sdk.dir，或设 ANDROID_HOME）
./gradlew :app:assembleDebug
```

## 服务器侧

- sshd + 一条受限公钥（`restrict,command="…/transfer-gateway"`），网关只放行 sftp 子系统和 `archive-trigger`
- HTTP 文件服务需要：`/api/stat`、`/api/put`（支持 `offset` 续传）、`/api/read`、
  `/api/archive/trigger`，以及 `/api/hostinfo`（返回服务器公网 IPv6，用来选直连地址）

## 已知边界

- 归档本身在服务器做（OCR/抽取），App 只管投递与查看；服务端没装那套流水线时，投递会成功但没有待办。
- `todos/index.jsonl` 是只读视图，改待办要回到服务器那边。
- HTTP 通道的吞吐受那条 HTTPS 链路限制，和服务器上传带宽同量级。
