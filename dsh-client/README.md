# dsh-client —— 用 SSH 把只监听 loopback 的 Web 界面搬到本机

一个单 jar 的 Windows 桌面客户端：在本机起一条 **SSH 本地端口转发**，把服务器上
`127.0.0.1:3080` 的 DSH 界面搬到本机，再**内嵌进客户端页签**（JCEF / Chromium）。
服务端零改动、不多开任何公网端口。

```
客户端页签（JCEF） --> 127.0.0.1:3080 ──SSH 本地转发──> 服务器 127.0.0.1:3080
                                      （直连 IPv6:22 / SSH over WebSocket / 网页隧道）
```

## 功能

- **三条选路**：`direct`（IPv6:22）/ `tunnel`（SSH over WebSocket 443）/ `web`（浏览器直开域名）；
  `auto` 按"本机有无全局 IPv6 + 握手 RTT"判读，失败自动回退。
- **页签**：开始 / 终端 / 日志 / DSH 界面 / WorkBuddy 桌面。
- **内嵌终端**：自研字符矩阵 + ANSI SGR 解析，支持 CJK 宽字符、256 色、滚动回看、复制粘贴。
- **内嵌浏览器**：JCEF；页面里的外链交给系统默认浏览器，DSH 自己的路由留在页签内。
- **下载管理**：静默落盘 + 进度列表 + 断点续传 + 回收站删除；完成判定不依赖 CEF 回调，另有磁盘扫描兜底。
- **主题**：暗 / 亮双主题，令牌驱动，自绘按钮、页签、弹层与设置面板。

## 构建

```bash
bash build-jar.sh 3.0.9        # 需要 JDK 17+ 与 jsch jar（自动在 ~/.gradle 里找）
bash package.sh 3.0.9          # 产出 DshClient-<ver>-clean.zip
```

Windows 端：解压后双击 `run-cef.bat`（用 javaw 后台启动，不留 cmd 窗口）。
排错时用 `debug.bat`（保留控制台，输出 `[term] key` / `[focus]` 等诊断）。

## 目录

```
src/dev/example/dshclient/
  DshClient.java        入口、配置、选路、探测
  ClientFrame.java      主窗口、页签、内嵌接线
  JcefView.java         内嵌浏览器（下载/弹窗/焦点/代理）
  SshTunnel.java        SSH 端口转发（含 SSH over WebSocket）
  RouteProbe.java       路线实测（丢包/速率/RTT）
  RemoteInfo.java       经隧道读取服务器地址与 token
  term/                 终端（屏幕模型 + 面板 + SSH shell）
  ui/                   v2/v3 视觉（主题、页签条、按钮、弹层、设置面板）
```

## 已知边界

- `cef/` 里的 7 个 jar 是运行时依赖，不打进 fat jar；缺了会自动降级为"外开浏览器窗口"。
- `jcef-api` 与 `jcefmaven` 必须**同版本**（当前 132.3.1），否则拉到的原生库和 Java 类对不上。
- 内嵌浏览器是 windowed 渲染（原生子窗口），页签切换时需要显式隐藏组件并交出焦点，
  否则键盘会被隐藏页面抢走——`JcefView.setActive()` 负责这件事。
- 服务器侧若用 `no-pty` / `command="internal-sftp"` 限制 key，终端拿不到 shell；
  `SshShell` 会明确报出这个原因。

## 许可

PolyForm Noncommercial 1.0.0（见仓库根 LICENSE）。
