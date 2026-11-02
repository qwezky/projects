#!/usr/bin/env bash
# 让隧道 E2E 测试能跑：生成一把持久的一次性测试密钥，并把授权行加进 authorized_keys。
#
# 为什么用持久路径而不是 /tmp：临时目录重启即清空，测试工具会重新生成一把新密钥，
# 于是 authorized_keys 里那行就成了"指向不存在密钥的孤儿"—— 现象是测试静默跳过或
# Auth fail，而 authorized_keys 看起来一切正常。（踩过。）
#
# 授权行的选项与 App 那把一致：逐项收窄 + 只允许转发到监测端口，
# **刻意不用 restrict**（它隐含 no-port-forwarding，会让"连上却打不开"）。
set -euo pipefail

KEY="${1:-$HOME/.local/state/server-monitor/e2e-key}"
AK="$HOME/.ssh/authorized_keys"

[ -f "$KEY" ] || ssh-keygen -t rsa -b 4096 -N "" -C "dsysmon-e2e" -f "$KEY" >/dev/null
chmod 600 "$KEY"; chmod 644 "$KEY.pub"

BLOB="$(cut -d' ' -f2 "$KEY.pub")"
OPTS='no-pty,no-agent-forwarding,no-X11-forwarding,no-user-rc,permitopen="127.0.0.1:8899",command="internal-sftp"'
LINE="$OPTS ssh-rsa $BLOB dsysmon-e2e"

# 幂等：按公钥前 40 字符判断（不能用 grep 'ssh-rsa'，那会匹配到别的 rsa key）
HEAD="${BLOB:0:40}"
if grep -qF "$HEAD" "$AK"; then
    echo "已授权（无需改动）：$KEY"
else
    cp -f "$AK" "$AK.bak-$(date +%Y%m%d-%H%M%S)-dsysmon-e2e"
    printf '%s\n' "$LINE" >> "$AK"
    chmod 600 "$AK"
    echo "已追加授权行到 $AK"
fi
echo "密钥: $KEY"
echo "指纹: $(ssh-keygen -lf "$KEY.pub" | awk '{print $2}')"
echo
echo "跑隧道 E2E："
echo "  cd ~/android-dev/dsysmon-app && ./gradlew testDebugUnitTest --tests '*SshTunnelE2ETest*' \\"
echo "    --tests '*DashboardFileE2ETest*' --rerun-tasks -Dtool.key='$KEY' -Dtool.host=<服务器IPv6>"
