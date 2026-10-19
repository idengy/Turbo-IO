# Turbo-IO Android 工具链环境(2026-10-18)。全部装在项目 local/ 下,不装全局。
# 用法:source custom/scripts/env.sh   然后可直接跑 adb / apktool / apksigner / zipalign / android-addon/build.sh
ROOT="$(cd "$(dirname "${BASH_SOURCE:-$0}")/../.." && pwd)"
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_SDK_ROOT="$ROOT/local/android-sdk"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export REA_JADX_MCP_JAR="$ROOT/local/tools/rea-jadx/jadx-headless-mcp-0.7.1-all.jar"
export REA_ADB_PATH="$ANDROID_SDK_ROOT/platform-tools/adb"
export REA_APKTOOL_COMMAND="$ROOT/local/tools/apktool/apktool"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/build-tools/36.0.0:$ROOT/local/tools/apktool:$PATH"
