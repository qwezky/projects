#!/usr/bin/env bash
# 编译「DSH 客户端（电脑版）」并打成单个可执行 jar（JSch 一起塞进去）
#
# ⚠️ manifest 里的 Multi-Release: true 不能丢：JSch 是 Multi-Release jar，
#    丢了它 JVM 会忽略 META-INF/versions/15/，ed25519 私钥会报"认证失败"。
#
# 用法：bash build-jar.sh [版本号]   产物：dist/DshClient-<版本>.jar
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

JAVA_HOME="${JAVA_HOME:-$HOME/android-dev/jdk17}"
JAVAC="$JAVA_HOME/bin/javac"
JAR="$JAVA_HOME/bin/jar"
[ -x "$JAVAC" ] || { echo "✗ 找不到 javac：$JAVAC" >&2; exit 1; }

JSCH="$(ls "$HOME"/.gradle/caches/modules-2/files-2.1/com.github.mwiede/jsch/*/*/jsch-*.jar 2>/dev/null | head -1)"
[ -f "$JSCH" ] || { echo "✗ 找不到 jsch jar" >&2; exit 1; }

VER="${1:-1.0.0}"
# 版本号写进源码常量：界面标题、启动日志、--help/--version 都从它取，避免"文件名是新的、跑的是旧的"
SRC_MAIN="src/dev/example/dshclient/DshClient.java"
if grep -q 'static final String VERSION' "$SRC_MAIN"; then
  sed -i "s/static final String VERSION = \"[^\"]*\"/static final String VERSION = \"${VER}\"/" "$SRC_MAIN"
  echo "==> 版本号注入源码常量：$(grep -o 'static final String VERSION = "[^"]*"' "$SRC_MAIN" | head -1)"
fi
# 可选：cef/ 目录里放了 jcef-api.jar / jcefmaven.jar 时，把它们加进**编译**类路径
# （不打进 fat jar —— 运行时由 run-cef.bat 用 -cp 提供，没它们也能正常启动）
# cef/*.jar 不入库（第三方二进制）：需要时按下面的坐标自行下载，放进 cef/ 再构建
#   org.jcef:jcef-api:132.3.1   +   me.friwi:jcefmaven:132.3.1
#   以及传递依赖 gson / commons-compress / commons-codec / commons-io / commons-lang3
EXTRA_CP=""
for j in cef/*.jar; do
  [ -f "$j" ] && EXTRA_CP="$EXTRA_CP:$j"
done
if [ -z "$EXTRA_CP" ]; then
  echo "✗ cef/ 里没有 jar：内嵌浏览器相关类无法编译。" >&2
  echo "  下载 jcef-api-132.3.1.jar 与 jcefmaven-132.3.1.jar（及 gson / commons-*）放进 cef/ 后重试。" >&2
  echo "  只想编译无内嵌版：先删掉 JcefView.java / JcefGate.java 并移除 ClientFrame 里的相关引用。" >&2
  exit 3
fi
done
echo "==> 编译（目标 Java 17）${EXTRA_CP:+（含 cef 依赖）}"
rm -rf build dist
mkdir -p build/classes build/stage dist
"$JAVAC" --release 17 -encoding UTF-8 -cp "$JSCH$EXTRA_CP" -d build/classes $(find src -name '*.java')

echo "==> 组装 fat jar"
cd build/stage
"$JAR" xf "$JSCH"
rm -f META-INF/MANIFEST.MF
cp -r ../classes/. .
printf 'Main-Class: dev.example.dshclient.DshClient\nMulti-Release: true\n' > /tmp/dsh-client-manifest.txt
OUT="$(cd ../.. && pwd)/dist/DshClient-${VER}.jar"
"$JAR" cfm "$OUT" /tmp/dsh-client-manifest.txt .
rm -f /tmp/dsh-client-manifest.txt
cd ../..
rm -rf build/stage

# 固定名的副本：run.bat 只用这一个名字，升级时它不用改、也不用挑版本
cp "dist/DshClient-${VER}.jar" dist/DshClient.jar
# 历史留档：dist-backup 累积每一版（回档/对比用；以前 rm -rf dist 会把上一版抹掉）
cp "dist/DshClient-${VER}.jar" "dist-backup/DshClient-${VER}.jar" 2>/dev/null || true

echo
echo "=== 产物 ==="
ls -la "dist/DshClient-${VER}.jar" dist/DshClient.jar
echo "  sha256: $(sha256sum "dist/DshClient-${VER}.jar" | cut -d' ' -f1)"
