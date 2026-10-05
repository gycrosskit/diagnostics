#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${VERSION:?Pass the immutable candidate version}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export GROUP=com.github.gycrosskit.diagnostics
# 本任务独占的生成仓库；归档不夹带旧版本或SNAPSHOT文件。
rm -rf build/maven
bash gradlew --no-daemon --max-workers=1 -Dorg.gradle.parallel=false publishAllPublicationsToStagingRepository
python3 jitpack-metadata.py build/maven/com/github/gycrosskit/diagnostics
publications=diagnostics-core,diagnostics-core-android,diagnostics-core-jvm,diagnostics-core-iosarm64,diagnostics-core-iossimulatorarm64,diagnostics-core-iosx64,diagnostics-core-ohosarm64,diagnostics-dingtalk,diagnostics-dingtalk-android,diagnostics-dingtalk-jvm,diagnostics-dingtalk-iosarm64,diagnostics-dingtalk-iossimulatorarm64,diagnostics-dingtalk-iosx64,diagnostics-dingtalk-ohosarm64
python3 verification/check-maven.py build/maven com.github.gycrosskit.diagnostics "$VERSION" diagnostics-core,diagnostics-dingtalk ios_arm64,ios_x64,ios_simulator_arm64,ohos_arm64 "$publications"
mkdir -p build/prebuilt
COPYFILE_DISABLE=1 tar --no-xattrs -czf build/prebuilt/diagnostics-maven.tar.gz -C build/maven com
(cd build/prebuilt && shasum -a 256 diagnostics-maven.tar.gz) > build/prebuilt/SHA256SUMS
cat build/prebuilt/SHA256SUMS
