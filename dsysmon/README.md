# dsysmon —— 单文件服务器监测面板（终端 + 网页 + 手机）

一个只读的服务器体检工具：CPU、内存、电池、磁盘、网络、风扇、温度、GPU、进程、
服务与日志异常，一次看全。终端快照、JSON、自刷新监视、服务端渲染的手机盘面都用
同一份采集结果。

**设计原则：只在被调用时运行**，不装服务、不写系统配置、不需要 sudo、不常驻。

```
dsysmon                 一次性快照（默认，约 1.2 秒）
dsysmon -w              每 3 秒刷新，Ctrl-C 退出
dsysmon --json          完整 JSON，给脚本或 jq 用
dsysmon --serve         起 HTTP 端点，手机上直接开盘面
```

## 快速开始

```bash
install -m 755 dsysmon.py ~/.local/bin/dsysmon

dsysmon                       # 看一眼现在什么情况
dsysmon -w                    # 持续盯着（跑大任务时开着）
dsysmon --report              # 展开全部温度传感器与全部挂载点
dsysmon --disk-detail         # 额外跑 du -x -d1 / 看根目录占用 Top
dsysmon --json | jq .mem      # 取单个指标
dsysmon --save                # 存一份快照（权限 600）
dsysmon --alert-at disk,mem   # 只关心磁盘和内存时的退出码
```

退出码方便接进别的脚本：

| 码 | 含义 |
|---|---|
| 0 | 正常 |
| 1 | 有 WARN |
| 2 | 有 CRIT（或参数写错） |

```bash
# 例：磁盘快满时再做点别的
dsysmon --alert-at disk --no-color >/dev/null || echo "磁盘需要注意"
```

`--no-alert-exit` 可让退出码恒为 0。

## 监测项目

- **CPU**：总使用率、逐核、型号、实时频率、governor、负载均值、运行进程数
- **内存/交换**：已用/可用、cached/buffers/dirty、swap 用量、换页速率（页/秒）、
  开机以来换页量
- **电池/电源**：当前总电量（Wh 与百分比）、满电与设计容量、健康度、循环次数、
  电池功率、预计可用时间、外接电源状态、累计放电/充入电量
- **磁盘**：各挂载点容量与 inode 用量、块设备累计读写、`--disk-detail` 时的目录占用
- **网络**：各接口速率与累计流量、TCP 状态分布、监听端口、对外连接端口
- **风扇**：两个风扇的 rpm、包温（数据来自 root 服务 `fan-rpmd` 写的 `/run/fan-rpm`，
  带新鲜度检查；内核不提供 pwm，转速只能这样拿）
- **温度/GPU**：CPU 各核与封装、PCH、NVMe、无线；NVIDIA 的温度/利用率/显存/功耗
- **进程**：按 CPU 与按内存各 Top N
- **服务与日志**：内核 OOM 击杀、cgroup OOM、降频/过热、错误日志突发、失败单元、
  Docker 容器

### 电池读数为 0 是怎么回事

`power_now` 只在电池真正吞吐时才非零。插着电且已充满时整机由适配器供电，电池既不充
也不放，读数是 0 —— 这是正常现象，面板会说明原因，不会误报成故障。想看插电时的真实
整机功耗，需要墙上功率计。

放电时如果机型只报 `current_now` 而没有 `power_now`（或读数为 0），会用
`电压 × 电流` 换算，或退回用两次采样的电量差值推算，并在面板上标注来源。

### 累计电量的口径

单次快照只是一个读数，给不出「用了多少电」。要累计必须存上一次读数与时间点，
所以工具会在 `~/.local/state/server-monitor/battery-state.json` 记一个很小的状态：

- **只在被调用时统计**，不后台采样，也不是常驻服务
- 累计的是「两次调用之间」的电量变化之和，跨重启归零
- 间隔小于 5 秒且电量没变、或间隔超过 2 小时（休眠）的那一段不计入，
  但**已累计的历史不会被清零**
- 不想让它写文件就用 `--no-battery-track`

## 阈值

| 项目 | WARN | CRIT | 覆盖参数 |
|---|---|---|---|
| 内存使用率 | 85% | 95% | `--mem-warn` / `--mem-crit` |
| 交换使用率 | 50% | 85% | 改 `DEFAULTS` |
| 换页速率（页/秒） | 200 | 2000 | 改 `DEFAULTS` |
| 磁盘使用率 | 85% | 95% | `--disk-warn` / `--disk-crit` |
| inode 使用率 | 85% | 95% | 改 `DEFAULTS` |
| CPU 温度 | 80°C | 90°C | `--temp-warn` / `--temp-crit` |
| GPU 温度 | 80°C | 90°C | `--gpu-warn` / `--gpu-crit` |
| 负载 / vCPU | 100% | 200% | `--cpu-warn` / `--cpu-crit` |
| 错误日志突发（近 15 分钟） | 10 条 | 50 条 | 改 `DEFAULTS` |
| 电池电量（仅放电时判定） | 25% | 10% | 改 `DEFAULTS` |

一票即 CRIT：内核 OOM 击杀、cgroup OOM、systemd 失败单元、单进程 CPU ≥ 90%。

## 网页盘面（手机浏览器可直接看）

```bash
dsysmon --serve --port 8899
# 启动时会打印带令牌的盘面 URL
```

- 单页 + 可折叠卡片（原生 `<details>`，**不引任何脚本**），服务端渲染；
  改样式不用重装任何客户端，刷新即可
- 默认只接受 **IPv6 + 本机回环**；远程 IPv4 一律 403（回环放行是因为
  SSH 本地转发是从服务器自己的 `127.0.0.1` 发起连接的）
- 认证：32 字节随机令牌，`secrets.compare_digest` 比较；令牌文件权限 600；
  写日志前替换成 `***`
- 端点**只读**：读 `/proc`、`/sys`、df、journalctl，没有任何写操作接口
- 所有 HTML 转义，页面无外部资源、无可执行脚本

常驻（用户级 systemd，不需要 sudo）：

```bash
mkdir -p ~/.config/systemd/user
cp dsysmon-http.service ~/.config/systemd/user/
systemctl --user daemon-reload && systemctl --user enable --now dsysmon-http
cat ~/.local/state/server-monitor/token   # 32 字符，客户端首次连接时填一次
```

`--dashboard-file` 会每 20 秒把盘面写成一个静态文件，便于通过 SFTP 同步到外部查看
（手机在运营商网段、够不到服务器 IPv6 时的兜底路径）。

**为什么默认关掉配对端点**：`/api/pair` 是唯一一个不需要认证就能换到令牌的入口。
配对码有 40 bit、一次性，但可以被无限次尝试，撞中一次就拿到永久令牌，而服务端没有
失败锁定。关掉之后攻击面回到「必须知道 32 字符令牌」。需要时单独起
`dsysmon --serve --pair`。

## 几处刻意的设计（都是踩过的坑）

1. **CPU% 用 `/proc/<pid>/stat` 差分两次采样**，不用 `ps -o pcpu`。后者是进程生命期
   均值，短命进程会算出 `200%`，还会把 `ps` 自己排到榜首。
2. **日志计数剔除工具自身的输出**。自己跑过的诊断命令（命令行常带 "oom"/"error"）
   会进日志，按文本 grep 会算成系统故障，形成自指噪声。内核 OOM 则直接读
   `/proc/vmstat` 的 `oom_kill` 计数器。
3. **盘面模板不用 `%`-格式化**，也不留未替换的占位符：CSS 里满是 `100%`，
   `%`-格式化会不断踩坑；占位符漏替换会把 `__NAV__` 直接印在页面上。
   现在渲染后若有残留占位符直接抛异常。
4. **错误日志看「近 15 分钟突发」而非 24 小时总量**。开机瞬间的 ACPI/BIOS 报错一次
   能刷几十上百条，用总量判断会每次开机都误报。
5. **换页速率与 swap 占用率分开看**。占用率 93% 但速率是 0，说明那些页已沉在 swap
   里不再动，不拖慢性能；真伤性能的是「持续换入换出」。两个都报。
6. **电池只在「拔电且正在放电」时告警**。插着电时电量 8% 是在充电，不是故障。

## 覆盖范围与限制

- 读的是 `/proc`、`/sys`、`df`、`ps`、`journalctl`、`sensors`、`nvidia-smi`、`docker`。
  缺哪个就跳过哪项（没有 NVIDIA 卡就不显示 GPU 段），不会崩
- `journalctl` 权限受限时会标注「不可读」，不会假装是 0
- 温度优先用 `lm-sensors`（有 label 和临界值），没有才退回 `/sys/class/thermal`
- 没有 `iostat`/`sar` 的历史能力：这是「现在怎么样」的工具，不是趋势库
- 插电时的整机功耗软件拿不到，需要墙上功率计

## 风扇转速

本机风扇由 EC 独占控制（没有 `pwm`、没有 `platform_profile`），转速只能从 EC RAM
读，而读它要 root。所以由 root 常驻服务 `fan-rpmd` 每秒把值写进 world-readable 的
`/run/fan-rpm`：

```
<风扇1rpm> <风扇2rpm> <包温°C> <unix 时间戳>      例：1400 1200 43.0 1792068741
```

dsysmon 只读这个文件，**并检查时间戳新鲜度**（超过 10 秒就显示「数据已过期」，
而不是继续显示死值）。实现见本仓库的 `thermal/`。

## 文件

```
dsysmon.py              全部实现（单文件，无第三方依赖）
dsysmon-http.service    用户级 systemd 单元（可选常驻）
e2e-authorize.sh        一次性测试密钥授权（可选，用于端到端验证）
```

依赖：Python 3.9+，标准库；`sensors` / `nvidia-smi` / `docker` / `journalctl` 有就用。
