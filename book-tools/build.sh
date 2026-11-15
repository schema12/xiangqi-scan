#!/usr/bin/env bash
# 构建期工具:把仓库自带 XQF 棋谱转换为激进专用开局库 SQLite 资产。
# 脚本只在开发机运行,产物 app/src/main/assets/databases/aggressive_book_v1.obk 随 git 提交;
# 本目录与脚本本身不进入 APK。
#
# 用法:
#   ./book-tools/build.sh <XQF目录> <Pikafish.exe> <NNUE文件> <输出.obk>
# 示例:
#   ./book-tools/build.sh \
#     app/src/main/assets/XQF \
#     /f/work-ai/_xq_build_env/pikafish/Pikafish-Windows-x86-64-universal.exe \
#     app/src/main/assets/pikafish/pikafish-2026-09-06.nnue \
#     app/src/main/assets/databases/aggressive_book_v1.obk
#
# 依赖:JDK 17(android.util.Log 使用桌面垫片;sqlite-jdbc 自动经阿里云镜像下载)
set -e

# 桌面 JDK:优先 JAVA_HOME(自动转 POSIX),否则沿用 PATH
if [ -n "$JAVA_HOME" ] && [ -x "$(cygpath -u "$JAVA_HOME" 2>/dev/null || echo "$JAVA_HOME")/bin/javac" ]; then
  export PATH="$(cygpath -u "$JAVA_HOME" 2>/dev/null || echo "$JAVA_HOME")/bin:$PATH"
fi
echo "[tool] javac = $(command -v javac) ($("$(command -v javac)" -version 2>&1))"

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
APP="$REPO/app"
RUN="/f/work-ai/_xq_build_env/booktool_run"
rm -rf "$RUN"
mkdir -p "$RUN/classes" "$RUN/android/util"

JBBP="$(find "$HOME/.gradle/caches/modules-2" -name 'jbbp-3.0.0.jar' 2>/dev/null | head -1 || true)"
if [ -z "$JBBP" ]; then
  echo "[tool] jbbp jar not found in gradle cache"
  exit 1
fi
SQLITE="$RUN/sqlite-jdbc.jar"
if [ ! -f "$SQLITE" ]; then
  echo "[tool] downloading sqlite-jdbc via aliyun mirror"
  curl -sL --max-time 120 -o "$SQLITE" \
    "https://maven.aliyun.com/repository/public/org/xerial/sqlite-jdbc/3.45.1.0/sqlite-jdbc-3.45.1.0.jar"
fi
SLF4J_API="$RUN/slf4j-api.jar"
SLF4J_NOP="$RUN/slf4j-nop.jar"
if [ ! -f "$SLF4J_API" ]; then
  curl -sL --max-time 60 -o "$SLF4J_API" \
    "https://maven.aliyun.com/repository/public/org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar"
fi
if [ ! -f "$SLF4J_NOP" ]; then
  curl -sL --max-time 60 -o "$SLF4J_NOP" \
    "https://maven.aliyun.com/repository/public/org/slf4j/slf4j-nop/2.0.9/slf4j-nop-2.0.9.jar"
fi

# 桌面垫片:android.util.Log 空实现(仓库源码在桌面 JVM 引用它)
cat > "$RUN/android/util/Log.java" <<'EOF'
package android.util;
public class Log {
    public static int d(String tag, String msg) { return 0; }
    public static int d(String tag, String msg, Throwable t) { return 0; }
    public static int e(String tag, String msg) { return 0; }
    public static int e(String tag, String msg, Throwable t) { return 0; }
    public static int i(String tag, String msg) { return 0; }
    public static int i(String tag, String msg, Throwable t) { return 0; }
    public static int w(String tag, String msg) { return 0; }
    public static int w(String tag, String msg, Throwable t) { return 0; }
    public static int v(String tag, String msg) { return 0; }
    public static int wtf(String tag, String msg) { return 0; }
    public static int wtf(String tag, Throwable t) { return 0; }
}
EOF

echo "[tool] compiling"
javac -encoding UTF-8 -nowarn \
  -cp "$(cygpath -w "$JBBP" 2>/dev/null || echo "$JBBP")" \
  -sourcepath "$APP/src/main/java" \
  -d "$(cygpath -w "$RUN/classes" 2>/dev/null || echo "$RUN/classes")" \
  "$RUN/android/util/Log.java" \
  "$HERE/src/booktools/BookBuilder.java"

CP="$(cygpath -w "$RUN/classes" 2>/dev/null || echo "$RUN/classes");$(cygpath -w "$JBBP" 2>/dev/null || echo "$JBBP");$(cygpath -w "$SQLITE" 2>/dev/null || echo "$SQLITE");$(cygpath -w "$SLF4J_API" 2>/dev/null || echo "$SLF4J_API");$(cygpath -w "$SLF4J_NOP" 2>/dev/null || echo "$SLF4J_NOP")"
echo "[tool] running BookBuilder"
java -Xmx3g -Dfile.encoding=UTF-8 -cp "$CP" booktools.BookBuilder "$1" "$2" "$3" "$4"
