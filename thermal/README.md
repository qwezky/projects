# thermal —— 从 EC 读风扇转速，以及可重复的热负载基线

一台风扇不受内核 `pwm` 控制的笔记本（联想 Legion Y7000 2019 / BHCN 固件）上，
**只读地**把两个风扇的真实转速暴露出来，并提供一个「CPU + GPU 满载 → 每秒记录温度/频率/功耗」
的基线工具，用来验证散热改造、风扇起转策略、重负载前的预热是否真的有效。

```
fan-rpm.sh      一次性读（需要 root）：直读 EC RAM 的两个字节 → rpm
fan-rpmd.py     常驻小服务：每秒把转速写进 world-readable 的 /run/fan-rpm
load.py         热负载基线：8 线程 CPU + 持续推理，输出 CSV
```

## 为什么这么绕：风扇转速只在 EC 里

内核侧没有 `pwm`、没有 `platform_profile`，`ideapad` 的 `fan_mode` 是空壳
（写 1、写 4 都不生效，读回恒 0）。转速只能从 EC RAM 读：

| 字段 | 偏移 | 换算 |
|---|---|---|
| `FANS` | 6 | ×100 = 风扇 1 rpm |
| `FA2S` | 254 | ×100 = 风扇 2 rpm |

入口是 `ec_sys` 暴露的 `/sys/kernel/debug/ec/ec0/io`，权限 `0400 root`。
所以要么每次 `sudo`，要么挂一个 root 小服务把**只有转速这几个数**写出来 ——
不用放宽 debugfs 权限，也不用加免密 sudo。

## 用法

### 1. 读一次 / 盯一会儿（需要 root）

```bash
sudo bash fan-rpm.sh            # 一行：风扇1 / 风扇2 / 包温
sudo bash fan-rpm.sh watch 60   # 每秒一行，共 60 秒
```

`ec_sys` 没加载时脚本会 `modprobe ec_sys`。**不加 `write_support`**，所以这个入口只能读，
写不了 EC。

### 2. 常驻（把转速写到 /run/fan-rpm）

```bash
sudo install -m 755 fan-rpmd.py /usr/local/sbin/fan-rpmd.py
sudo cp fan-rpmd.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now fan-rpmd
cat /run/fan-rpm        # 例：1400 1200 43.0 1792068741
```

输出一行：`<风扇1rpm> <风扇2rpm> <包温°C> <unix 时间戳>`，权限 0644，
先写 `.tmp` 再 `os.replace`，读者不会看到半行。**消费者必须校验时间戳**：
超过几秒没更新就当作「读不到」，而不是继续显示一个死值（本仓库的 `dsysmon` 就是这么做的）。

`fan-rpmd.service` 的加固：`NoNewPrivileges`、`ProtectSystem=full`、
`ReadWritePaths=/run`、`PrivateTmp`。脚本必须装在 root 拥有、其他用户不可写的位置，
否则等于多了一条提权路径。

### 3. 热负载基线（不需要 root）

```bash
python3 load.py                      # 默认 240 秒满载 + 60 秒降温
HEAVY_SECONDS=600 HEAVY_THREADS=4 python3 load.py
```

- CPU：`nice -n 5 yes` × N 线程
- GPU：反复请求一个 OpenAI 兼容端点（默认本机 `127.0.0.1:8080` 的 llama.cpp），
  用 `LLM_URL` / `LLM_MODEL` 改；没有端点或没有 N 卡时 GPU 列留空，CPU 部分照跑
- 开跑前会先等机器稳定（包温 ≤46°C、load <0.6、GPU <15W 连续 2 分钟）
- 输出 `/tmp/heavy-cpu-gpu.csv`：`elapsed,pkg_c,freq_max_mhz,gpu_w,gpu_util`

## 实测得到的结论（同一台机器）

这些数字是这个项目存在的理由，也是「预热」策略的依据：

- 空载 45–53°C 时两个风扇字节都是 **0（根本不转）**，要到约 **53°C** 才起转
- 从 0 到 1400 rpm 需要 **24–42 秒的持续负载** —— 所以「温度一到就停」的预热会在
  1.5 秒内退出，风扇还没转起来，等于白做
- 冷机直跑大图 OCR：峰值 **63°C**、单秒最大温升 **+7°C**
- 预热到风扇已转（1400 rpm）再跑同一张图：峰值 **51°C**、单秒 **+2°C**
- 只跑 CPU 到不了高温（8 线程也就 53°C 平台期）；真正的热源是 GPU（约 50W，与 CPU 共热管）

所以预热脚本必须有一个 **`min_seconds`（≥30 秒）** 的下限，不能只按温度判断。

## 风险与回滚

- **读 EC 是只读操作**，不改任何风扇设置；脚本不加载 `ec_sys` 的 `write_support` 参数
- 但 `ec_sys` 本身是一个内核调试接口：不要在用完的机器上长期保留 `write_support=1`
  （本项目的脚本从不设置它）
- `fan-rpmd` 以 root 运行。卸载：

  ```bash
  sudo systemctl disable --now fan-rpmd
  sudo rm /etc/systemd/system/fan-rpmd.service /usr/local/sbin/fan-rpmd.py /run/fan-rpm
  sudo systemctl daemon-reload
  ```

- `load.py` 会让 CPU/GPU 长时间满载、机器明显发热。笔记本上注意散热与电池，
  不要在被子里/背包里跑；带 `finally` 清理，Ctrl-C 也会收掉负载进程
- 偏移量（6 / 254）是**按机型和固件**定的。换机器先自己验证：如果一个字节始终为 0
  而机器明显转着，多半是偏移不对，别把 0 当结论

## 文件

```
fan-rpm.sh          读一次/循环读（root）
fan-rpmd.py         常驻：写 /run/fan-rpm（root）
fan-rpmd.service    systemd 单元
load.py             CPU+GPU 热负载基线
```
