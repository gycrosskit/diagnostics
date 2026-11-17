#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
operation="${1:-}"; shift || true
[[ "${1:-}" == --platform && $# -ge 2 ]] || { echo 'Usage: scripts/build.sh compile|package --platform android|ios|harmony|maven' >&2; exit 2; }
platform="$2"; shift 2
if [[ "$platform" == harmony && -n "${HARMONY_CLT_HOME:-}" ]]; then
  export DEVECO_SDK_HOME="$HARMONY_CLT_HOME/sdk"
  export DEVECO_NODE_HOME="$HARMONY_CLT_HOME/tool/node"
  export NODE_HOME="$DEVECO_NODE_HOME"
  export PATH="$NODE_HOME/bin:$HARMONY_CLT_HOME/ohpm/bin:$HARMONY_CLT_HOME/bin:$PATH"
fi
if [[ "$operation" == compile ]]; then
  [[ $# == 0 ]] || { echo 'Unexpected arguments' >&2; exit 2; }
  case "$platform" in
    android) tasks=(:diagnostics-core:compileDebugKotlinAndroid :diagnostics-dingtalk:compileDebugKotlinAndroid :diagnostics-ktor:compileDebugKotlinAndroid :diagnostics-okhttp:compileDebugKotlinAndroid :diagnostics-bugly:compileDebugKotlinAndroid :diagnostics-umeng:compileDebugKotlinAndroid) ;;
    ios) tasks=(:diagnostics-core:compileKotlinIosArm64 :diagnostics-core:compileKotlinIosSimulatorArm64 :diagnostics-dingtalk:compileKotlinIosArm64 :diagnostics-dingtalk:compileKotlinIosSimulatorArm64 :diagnostics-ktor:compileKotlinIosArm64 :diagnostics-ktor:compileKotlinIosSimulatorArm64) ;;
    harmony) tasks=(:diagnostics-core:compileKotlinOhosArm64 :diagnostics-dingtalk:compileKotlinOhosArm64 :diagnostics-ktor:compileKotlinOhosArm64 :diagnostics-kuikly:compileKotlinOhosArm64) ;;
    *) echo 'Unsupported compile platform' >&2; exit 2 ;;
  esac
  [[ ${#tasks[@]} -gt 0 ]] || { echo 'No production target on this platform' >&2; exit 2; }
  args=(--no-daemon --max-workers=1 -Dorg.gradle.parallel=false)
  [[ ! -f scripts/ci-repositories.gradle ]] || args+=(--init-script "$PWD/scripts/ci-repositories.gradle")
  bash gradlew "${args[@]}" "${tasks[@]}"
  if [[ "$platform" == ios ]]; then
    export GYC_IOS_SOURCE="$PWD"
    for native_pod in GYDiagnosticsNative GYDiagnosticsSdk GYDiagnosticsBugly GYDiagnosticsUmeng; do
      native_output="$PWD/build/native-ios-compile/$native_pod"
      mkdir -p "$native_output"
      export GYC_IOS_POD="$native_pod"
      cat > "$native_output/Podfile" <<'POD'
install! 'cocoapods', :integrate_targets => false
platform :ios, '15.0'
use_frameworks! :linkage => :static
target 'NativeCompile' do
  pod ENV.fetch('GYC_IOS_POD'), :path => ENV.fetch('GYC_IOS_SOURCE')
  if %w[GYDiagnosticsBugly GYDiagnosticsUmeng].include?(ENV.fetch('GYC_IOS_POD'))
    pod 'GYDiagnosticsSdk', :path => ENV.fetch('GYC_IOS_SOURCE')
  end
end
POD
      (cd "$native_output" && pod install)
      xcodebuild -project "$native_output/Pods/Pods.xcodeproj" -scheme "$native_pod" \
        -configuration Debug -sdk iphoneos -destination 'generic/platform=iOS' \
        -derivedDataPath "$native_output/DerivedData" CODE_SIGNING_ALLOWED=NO IPHONEOS_DEPLOYMENT_TARGET=15.0 build
    done
  fi
  if [[ "$platform" == harmony ]]; then
    export OHOS_CLT_HOME="${OHOS_CLT_HOME:-${HARMONY_CLT_HOME:-}}"
    exec bash scripts/build-ohos-sdk.sh compile
  fi
  exit 0
fi
[[ "$operation" == package ]] || { echo 'Unsupported operation' >&2; exit 2; }
if [[ "$platform" == maven ]]; then exec bash scripts/archive-prebuilt.sh "$@"; fi
[[ "$platform" == harmony ]] || { echo "Unsupported package platform" >&2; exit 2; }
[[ "${1:-}" == --module && $# == 2 ]] || { echo 'Diagnostics packaging requires --module core|bugly|umeng' >&2; exit 2; }
case "$2" in
  core) module=DiagnosticsCore ;;
  bugly) module=DiagnosticsBugly ;;
  umeng) module=DiagnosticsUmeng ;;
  *) echo 'Unsupported Diagnostics module' >&2; exit 2 ;;
esac
cd ohos-sdk
if [[ "${HARMONY_DEPENDENCIES_READY:-false}" != true ]]; then "${HARMONY_OHPM:-ohpm}" install --all; fi
exec bash "${HARMONY_HVIGOR:-hvigorw}" --mode module -p "module=$module@default" -p product=default -p buildMode=release assembleHar --no-daemon
