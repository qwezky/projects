#!/usr/bin/env bash
# 读风扇转速（联想 Legion Y7000 2019 / BHCN 固件；其它机型偏移可能不同）—— 只读
#
# 原理：DSDT 里 OperationRegion(ERAX, SystemMemory, 0xFF00D400, 0xFF) 的字段表：
#   FANS  = 字节偏移 6    （风扇1）
#   FA2S  = 字节偏移 254  （风扇2）
# 值 ×100 = rpm（ACPI 里 WMAA 方法就是 Multiply(FANS, 100)）。
# 通过内核自带的 ec_sys 把 EC RAM 暴露成只读文件来读；不加 write_support 就写不了 EC。
#
# 用法：
#   sudo bash fan-rpm.sh            # 读一次
#   sudo bash fan-rpm.sh watch 60   # 每秒一行，读 60 秒
set -u
IO=/sys/kernel/debug/ec/ec0/io

if [ "$(id -u)" -ne 0 ]; then
  echo "需要 root：sudo bash fan-rpm.sh [watch 60]" >&2
  exit 1
fi

modprobe ec_sys 2>/dev/null || true
if [ ! -e "$IO" ]; then
  echo "找不到 $IO —— ec_sys 没加载？(试试 sudo modprobe ec_sys)" >&2
  exit 1
fi

pkg() {   # coretemp 包温（毫摄氏度）
  local d
  for d in /sys/class/hwmon/hwmon*; do
    if [ "$(cat "$d/name" 2>/dev/null)" = "coretemp" ]; then
      cat "$d/temp1_input" 2>/dev/null; return
    fi
  done
  echo ""
}

byte() {  # 读 EC 偏移 $1 的一个字节
  dd if="$IO" bs=1 skip="$1" count=1 2>/dev/null | od -An -tu1 | tr -d ' \n'
}

line() {
  local f1 f2 p
  f1=$(byte 6); f2=$(byte 254); p=$(pkg)
  f1=${f1:-?}; f2=${f2:-?}
  if [ -n "$p" ]; then p="$((p / 1000)).$(((p % 1000) / 100))°C"; else p="?"; fi
  if [ "$f1" != "?" ]; then f1="$f1 ($((f1 * 100)) rpm)"; else f1="?"; fi
  if [ "$f2" != "?" ]; then f2="$f2 ($((f2 * 100)) rpm)"; else f2="?"; fi
  printf '%s  风扇1=%-18s 风扇2=%-18s 包温=%s\n' "$(date +%T)" "$f1" "$f2" "$p"
}

if [ "${1:-}" = "watch" ]; then
  n="${2:-60}"
  for _ in $(seq "$n"); do line; sleep 1; done
else
  line
fi
