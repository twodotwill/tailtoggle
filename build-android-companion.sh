#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
ANDROID_SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$ROOT_DIR/android-sdk}}"
GRADLE_BIN="${GRADLE_BIN:-}"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-14742923_latest.zip"

if [ -z "$GRADLE_BIN" ]; then
  if command -v gradle >/dev/null 2>&1; then
    GRADLE_BIN="$(command -v gradle)"
  elif [ -x "$HOME/.gradle/wrapper/dists/gradle-8.11.1-bin/bpt9gzteqjrbo1mjrsomdt32c/gradle-8.11.1/bin/gradle" ]; then
    GRADLE_BIN="$HOME/.gradle/wrapper/dists/gradle-8.11.1-bin/bpt9gzteqjrbo1mjrsomdt32c/gradle-8.11.1/bin/gradle"
  else
    printf '%s\n' "No Gradle executable found. Set GRADLE_BIN=/path/to/gradle." >&2
    exit 1
  fi
fi

if [ -z "${JAVA_HOME:-}" ] && [ -d /usr/lib/jvm/java-17-openjdk-amd64 ]; then
  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
fi

if [ ! -x "$ANDROID_SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]; then
  mkdir -p "$ROOT_DIR/.android-sdk-tmp" "$ANDROID_SDK_DIR/cmdline-tools"
  curl -L --fail -o "$ROOT_DIR/.android-sdk-tmp/cmdline-tools.zip" "$CMDLINE_TOOLS_URL"
  rm -rf "$ROOT_DIR/.android-sdk-tmp/cmdline-tools" "$ANDROID_SDK_DIR/cmdline-tools/latest"
  unzip -q "$ROOT_DIR/.android-sdk-tmp/cmdline-tools.zip" -d "$ROOT_DIR/.android-sdk-tmp"
  mkdir -p "$ANDROID_SDK_DIR/cmdline-tools/latest"
  mv "$ROOT_DIR/.android-sdk-tmp/cmdline-tools/"* "$ANDROID_SDK_DIR/cmdline-tools/latest/"
fi

export ANDROID_HOME="$ANDROID_SDK_DIR"
export ANDROID_SDK_ROOT="$ANDROID_SDK_DIR"

yes | "$ANDROID_SDK_DIR/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null
"$ANDROID_SDK_DIR/cmdline-tools/latest/bin/sdkmanager" \
  "platform-tools" \
  "platforms;android-35" \
  "build-tools;35.0.0" >/dev/null

cd "$ROOT_DIR/android-companion"
"$GRADLE_BIN" :app:assembleDebug

mkdir -p "$ROOT_DIR/dist"
cp "$ROOT_DIR/android-companion/app/build/outputs/apk/debug/app-debug.apk" "$ROOT_DIR/dist/tailtoggle-companion-debug.apk"
printf '%s\n' "Built $ROOT_DIR/dist/tailtoggle-companion-debug.apk"
