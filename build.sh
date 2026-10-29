#!/bin/bash
# VidGrab 构建脚本：固定 JDK / SDK / Gradle 用户目录，全部在本项目 .tools 内
export JAVA_HOME=$JAVA_HOME
export ANDROID_HOME=../.tools/android-sdk
export GRADLE_USER_HOME=../.tools/gradle-home
../.tools/gradle-8.9/bin/gradle -p ../VidGrab "$@"
