#!/usr/bin/env bash
#
# 打包未签名 iOS IPA。
#
# 产物：ios/build/ComfyPilot-unsigned.ipa
# 这个包没有签名，不能直接安装；需要安装方自行签名。
#
set -euo pipefail

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "错误：打包 iOS 需要 macOS。" >&2
  exit 1
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IOS="$ROOT/ios"
BUILD="$IOS/build"
ARCHIVE="$BUILD/ComfyPilot.xcarchive"
FRAMEWORK="$ROOT/shared/build/bin/iosArm64/releaseFramework"

for tool in xcodegen xcodebuild zip shasum; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "错误：找不到 $tool。请先安装 Xcode 与 XcodeGen。" >&2
    exit 1
  fi
done

echo "==> 编译共享层 iosArm64 release framework"
cd "$ROOT"
# checkout 出来的 gradlew 可能没有执行位；直接交给 bash，避免 Permission denied。
bash "$ROOT/gradlew" :shared:linkReleaseFrameworkIosArm64 --no-daemon

if [[ ! -d "$FRAMEWORK/ComfyCore.framework" ]]; then
  echo "错误：没有找到 $FRAMEWORK/ComfyCore.framework" >&2
  exit 1
fi

echo "==> 生成 Xcode 工程"
cd "$IOS"
xcodegen generate

echo "==> 编译并归档未签名 App"
rm -rf "$ARCHIVE"
xcodebuild archive \
  -project ComfyPilot.xcodeproj \
  -scheme ComfyPilot \
  -configuration Release \
  -destination "generic/platform=iOS" \
  -archivePath "$ARCHIVE" \
  -derivedDataPath "$BUILD/DerivedData" \
  KMP_FRAMEWORK_DIR="$FRAMEWORK" \
  CODE_SIGNING_ALLOWED=NO \
  CODE_SIGNING_REQUIRED=NO \
  CODE_SIGN_IDENTITY="" \
  PROVISIONING_PROFILE_SPECIFIER="" \
  ONLY_ACTIVE_ARCH=NO \
  SKIP_INSTALL=NO

APP="$ARCHIVE/Products/Applications/ComfyPilot.app"
if [[ ! -d "$APP" ]]; then
  echo "错误：归档里没有 ComfyPilot.app" >&2
  exit 1
fi

echo "==> 组装 Payload"
rm -rf "$BUILD/Payload"
mkdir -p "$BUILD/Payload"
cp -R "$APP" "$BUILD/Payload/"

IPA="$BUILD/ComfyPilot-unsigned.ipa"
rm -f "$IPA"
(
  cd "$BUILD"
  zip -qry "$(basename "$IPA")" Payload
)

echo "==> 完成"
shasum -a 256 "$IPA"
echo "$IPA"
echo "注意：IPA 未签名，安装前需自行签名。"
