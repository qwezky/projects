#!/usr/bin/env bash
# 编译「DSH 客户端（电脑版）」并打成单个可执行 jar（JSch 一起塞进去）
#
# ⚠️ manifest 里的 Multi-Release: true 不能丢：JSch 是 Multi-Release jar，
#    丢了它 JVM 会忽略 META-INF/versions/15/，ed25519 私钥会报"认证失败"。
#
# 用法：bash build-jar.sh [版本号]   产物：dist/DshClient-<版本>.jar
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

# 优先用 JAVA_HOME，其次用 PATH 里的 javac（需要 JDK 17+）
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JAVAC="$JAVA_HOME/bin/javac"; JAR="$JAVA_HOME/bin/jar"
else
  JAVAC="$(command -v javac || true)"; JAR="$(command -v jar || true)"
fi
[ -x "$JAVAC" ] || { echo "✗ 找不到 javac（装 JDK 17+ 或设 JAVA_HOME）" >&2; exit 1; }
[ -x "$JAR" ]   || { echo "✗ 找不到 jar（装 JDK 17+ 或设 JAVA_HOME）" >&2; exit 1; }

# JSch：先在 Gradle / Maven 缓存里找，也可以用 JSCH_JAR 显式指定
if [ -n "${JSCH_JAR:-}" ]; then
  JSCH="$JSCH_JAR"
else
  JSCH="$(find "$HOME/.gradle" "$HOME/.m2" -name 'jsch-*.jar' 2>/dev/null | head -1)" || true
fi
[ -f "${JSCH:-}" ] || { echo "✗ 找不到 jsch jar；用 JSCH_JAR=/path/to/jsch.jar 指定" >&2; exit 1; }

VER="${1:-1.0.0}"
# 版本号写进源码常量：界面标题、启动日志、--help/--version 都从它取，避免"文件名是新的、跑的是旧的"
SRC_MAIN="src/dev/example/dshclient/DshClient.java"
if grep -q 'static final String VERSION' "$SRC_MAIN"; then
  sed -i "s/static final String VERSION = \"[^\"]*\"/static final String VERSION = \"${VER}\"/" "$SRC_MAIN"
  echo "==> 版本号注入源码常量：$(grep -o 'static final String VERSION = "[^"]*"' "$SRC_MAIN" | head -1)"
fi
echo "==> 编译（目标 Java 17）"
rm -rf build dist
mkdir -p build/classes build/stage dist
"$JAVAC" --release 17 -encoding UTF-8 -cp "$JSCH" -d build/classes $(find src -name '*.java')

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
