#!/usr/bin/env bash
set -e

# HUI-TOUCH - скрипт сборки
# Работает как локально (если установлен Android SDK + JDK 17 + Gradle 8.11+),
# так и в CI (GitHub Actions)

echo "=== HUI-TOUCH Build Script ==="
echo "Проверка окружения..."

# Проверка Java
if ! command -v java >/dev/null 2>&1; then
  echo "ERROR: Java не найден. Установите JDK 17: https://adoptium.net/"
  exit 1
fi
java -version

# Проверка Android SDK
if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ]; then
  echo "WARNING: ANDROID_HOME не установлен. Попытка найти sdkmanager..."
  if ! command -v sdkmanager >/dev/null 2>&1; then
    echo "ERROR: Android SDK не найден."
    echo "Установите Android Studio или cmdline-tools:"
    echo "  https://developer.android.com/studio#command-tools"
    echo "Затем:"
    echo "  sdkmanager --install \"platforms;android-35\" \"build-tools;35.0.0\" \"ndk;27.0.12077973\" \"cmake;3.22.1\""
    exit 1
  fi
fi

# Проверка Gradle
if [ -f "./gradlew" ]; then
  GRADLE_CMD="./gradlew"
  chmod +x ./gradlew
  echo "Используется ./gradlew"
else
  if ! command -v gradle >/dev/null 2>&1; then
    echo "ERROR: Gradle не найден. Установите Gradle 8.11.1 или используйте wrapper:"
    echo "  gradle wrapper --gradle-version 8.11.1"
    exit 1
  fi
  GRADLE_CMD="gradle"
  echo "Используется system gradle"
fi

# Проверка NDK
echo "Проверка NDK..."
if [ -n "$ANDROID_HOME" ]; then
  SDKMGR="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
  if [ ! -x "$SDKMGR" ]; then
    SDKMGR="sdkmanager"
  fi
  if command -v $SDKMGR >/dev/null 2>&1 || [ -x "$SDKMGR" ]; then
    echo "Установка необходимых пакетов SDK (если отсутствуют)..."
    yes | $SDKMGR --install "platforms;android-35" "build-tools;35.0.0" "ndk;27.0.12077973" "cmake;3.22.1" || echo "SDK packages уже установлены или ошибка (продолжаем)"
  fi
fi

echo ""
echo "=== Сборка debug APK ==="
$GRADLE_CMD assembleDebug --no-daemon --stacktrace

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK_PATH" ]; then
  mkdir -p dist
  cp "$APK_PATH" "dist/HuiTouch-1.0.0-debug.apk"
  echo ""
  echo "✓ Сборка успешна!"
  echo "APK: $APK_PATH"
  echo "Копия: dist/HuiTouch-1.0.0-debug.apk"
  ls -lh dist/*.apk
else
  echo "ERROR: APK не найден по пути $APK_PATH"
  exit 1
fi
