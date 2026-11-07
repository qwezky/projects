#!/usr/bin/env bash
# 打一个"干净"的 Windows 包：只有运行必需的三个东西。
#   DshClient.jar   主程序
#   run-cef.bat     唯一启动器
#   cef/            JCEF 运行时依赖（7 个 jar）
# 用法：bash package.sh 2.16.15
set -euo pipefail
ver="${1:?用法: bash package.sh <版本号>}"
cd "$(dirname "$0")"

[ -f dist/DshClient.jar ] || { echo "先跑 build-jar.sh"; exit 1; }
out="dist/DshClient-${ver}-clean.zip"
rm -f "$out"
python3 - "$out" <<'PY'
import os, sys, zipfile, hashlib
out = sys.argv[1]
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    z.write('dist/DshClient.jar', 'DshClient.jar')
    z.write('run-cef.bat', 'run-cef.bat')
    for extra in ('cleanup-residue.bat', 'debug.bat'):
        if os.path.exists(extra):
            z.write(extra, extra)
    for f in sorted(os.listdir('cef')):
        if f.endswith('.jar'):
            z.write(os.path.join('cef', f), 'cef/' + f)
print('size  ', os.path.getsize(out))
print('sha256', hashlib.sha256(open(out, 'rb').read()).hexdigest())
print('内容  :', ', '.join(zipfile.ZipFile(out).namelist()))
PY
