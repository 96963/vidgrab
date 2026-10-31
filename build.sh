#!/bin/bash
# VidGrab 构建脚本
# 工具链默认位于仓库同级的 .tools/，可用环境变量覆盖：
#   VIDGRAB_TOOLS / ANDROID_HOME / GRADLE_USER_HOME / JAVA_HOME
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
TOOLS="${VIDGRAB_TOOLS:-$(dirname "$ROOT")/.tools}"

export ANDROID_HOME="${ANDROID_HOME:-$TOOLS/android-sdk}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$TOOLS/gradle-home}"

GRADLE="$TOOLS/gradle-8.9/bin/gradle"
[ -x "$GRADLE" ] || { echo "未找到 Gradle：$GRADLE（可用 VIDGRAB_TOOLS 指定工具目录）" >&2; exit 1; }

exec "$GRADLE" -p "$ROOT" "$@"
