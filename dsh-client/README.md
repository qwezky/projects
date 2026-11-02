# dsh-client —— 用 SSH 把只监听 loopback 的 Web 界面搬到本机

一个单 jar 的小客户端：在本机起一条 SSH 本地端口转发，把服务器上
`127.0.0.1:3080` 的 Web 界面映射到本机，然后用浏览器的 `--app=` 模式打开 ——
看起来就是一个独立窗口的桌面客户端，但服务端零改动、不多开任何公网端口。

```
浏览器/Edge --app--> 127.0.0.1:3080  ──SSH 本地转发──>  服务器 127.0.0.1:3080
                                    （直连 IPv6:22 或 SSH over WebSocket）
```

支持三种到服务器的路径，默认自动判读：

| 路径 | 做法 | 适用 |
|---|---|---|
| `direct` | SSH 直连服务器 IPv6:22 | 同网段/低 RTT，最快 |
| `tunnel` | SSH 装进 WebSocket，走 CDN 的 443 | 只有域名可达（NAT / 移动网络 / 屏蔽 22 端口） |
| `web` | 浏览器直接开 `https://dsh.example.com/` | 高 RTT 链路；SSH 的「每请求一连接 × 一个 RTT」太慢时反而更快 |

`auto` 只看两件事：本机有没有全局 IPv6、SSH 握手 RTT 多高。高 RTT（默认 ≥60ms）
先试 `web`，其余直连达标走 `direct`，不达标走 `tunnel`，最后再把可达的路径实测一遍吞吐。

## 构建

需要 JDK 17+ 和 [JSch](https://github.com/mwiede/jsch)（`com.github.mwiede:jsch`）。

```bash
# 方式一：JSch 在 Gradle/Maven 缓存里，脚本自己找
JAVA_HOME=/path/to/jdk17 bash build-jar.sh 1.0.0

# 方式二：显式指定 jar
JSCH_JAR=/path/to/jsch-2.28.8.jar bash build-jar.sh 1.0.0
```

产物：`dist/DshClient-<版本>.jar`（fat jar，JSch 已打进去）。
版本号会写进源码常量，界面标题、`--version`、日志首行都从它取。

manifest 里的 `Multi-Release: true` **不能丢**：JSch 是 multi-release jar，
丢了它 JVM 会忽略 `META-INF/versions/15/`，ed25519 私钥会报「认证失败」。

## 运行

```bash
java -jar dist/DshClient-1.0.0.jar                # 图形界面（默认）
java -jar dist/DshClient-1.0.0.jar --serve        # 只起隧道并打印 URL
java -jar dist/DshClient-1.0.0.jar --print-url    # 只打印 URL，不起通道
java -jar dist/DshClient-1.0.0.jar --probe        # 只探测并判读走哪条路
```

常用参数：

```
--host <IPv6|域名|auto>       默认 auto：经隧道发现服务器当前地址
--token <token|auto|log[:路径]>
                              auto = 经隧道读服务器日志；log = 先试本机日志
--no-discover                 完全不连隧道做发现（离线/排错）
--route auto|direct|tunnel|web
--speedtest                   每条路径真连一遍测吞吐，打完表退出
--speed-min <KB/s>            判「够快」的门槛，默认 1000
--mobile-rtt <ms>             握手 RTT ≥ 它就先试网页，默认 60
--no-open                     起隧道但不自动开浏览器
--help / --version
```

设置会保存到 `~/.dsh-client.properties`，下次启动沿用。

## 为什么需要「发现」

服务器在校园网/宽带的 IPv6 是**临时地址**（`use_tempaddr=2`），认证或重连后会换；
Web 服务的访问 token 每次重启也会变。所以默认不写死：启动时先用隧道连一次服务器，
读回 `/proc/net/if_inet6` 的当前全局地址与 `~/.dsh/web.log` 里的最新 token，
发现失败才退回本机兜底值（`127.0.0.1` / 空 token，适用于客户端就跑在服务器上的场景）。

## 设计取舍（踩过的坑）

- **为什么不直接让 Web 服务监听公网**：那要多一个暴露面，还得处理 TLS 与认证；
  SSH 转发全程加密，页面看到的始终是 loopback。
- **为什么页面里下载东西不会断**：浏览器访问的是 `http://127.0.0.1:3080`，页面与下载同源，
  没有混合内容拦截；同时绕开了某些 CDN 链路上「大文件传到几十 KB 就断」的问题。
- **SSH over WebSocket**：把 SSH 字节流装进 WebSocket 帧，经 CDN 的 443 出去。
  代价是每帧一次封装，收益是只要能打开 HTTPS 就能连 SSH。
- **高 RTT 下 SSH 反而慢**：每个 HTTP 请求都要在 SSH 通道里新开一条 connection，
  一次一个 RTT；手机热点/移动网络下首屏能差一个数量级。所以 `auto` 在高 RTT 时
  优先试 `web`，这不是「降级」，是实测更快的路径。
- **测速要真传数据**：0% 丢包 + 3ms 握手也可能「握手快、传数据慢」，所以判据里加了
  吞吐实测（默认每条路读 192 KB）。
- **看门狗**：`direct` 连续掉线 2 次或刚连上就断，自动切 `tunnel`；`tunnel` 不通再退 `web`。

## 图形界面

- 顶部显示当前路径、服务器地址、token 指纹（前 4 位）与实时吞吐
- 「线路」下拉可手动锁定 `direct / tunnel / web`，改完立即重连
- 日志面板保留每次判读的理由（为什么选了这条），排错时比看结果有用

## 已知边界

- 服务器必须能从某条路径 SSH 进去（直连 / WebSocket 二选一），且允许本地端口转发
- `direct` 依赖客户端有全局 IPv6；没有就自动走 `tunnel`
- `web` 路径由 CDN 终止 TLS，端到端只剩浏览器到 CDN 这一段是 TLS
- 客户端只做转发，不代理其他端口；DSH 的 token 仍由服务端签发
- 首次运行需要在服务器上放好公钥；本项目不生成也不上传私钥
