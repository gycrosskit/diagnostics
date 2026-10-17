#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEST_ROOT="$(mktemp -d /tmp/gyc-ios-sdk-tests.XXXXXX)"
trap 'rm -rf "${TEST_ROOT}"' EXIT
module() {
  local name="$1"; shift
  xcrun swiftc -emit-library -emit-module -enable-testing -module-name "${name}" \
    -module-cache-path "${TEST_DIR}/module-cache" -I "${TEST_DIR}" -L "${TEST_DIR}" "$@" \
    -Xlinker -rpath -Xlinker "${TEST_DIR}" \
    -o "${TEST_DIR}/lib${name}.dylib" -emit-module-path "${TEST_DIR}/${name}.swiftmodule"
}
for provider in bugly umeng; do
  TEST_DIR="${TEST_ROOT}/${provider}"
  mkdir -p "${TEST_DIR}"
  module GYDiagnosticsSdk "${ROOT}/ios-sdk/Core/GYDiagnosticSdk.swift"
  if [[ "${provider}" == bugly ]]; then
    for name in BuglyProCore TDOS_Diagnose; do module "${name}" "${ROOT}/ios-sdk/tests/Stubs/${name}.swift"; done
    module GYDiagnosticsProvider "${ROOT}/ios-sdk/Bugly/GYBuglyDiagnosticSdk.swift" \
      "${ROOT}/ios-sdk/Bugly/GYDiagnosticSdkProviderFactory.swift" "${ROOT}/ios-sdk/Bugly/Logs/GYBuglyLogs.swift" \
      -lGYDiagnosticsSdk -lBuglyProCore -lTDOS_Diagnose
    xcrun swiftc -parse-as-library -D TEST_BUGLY -module-cache-path "${TEST_DIR}/module-cache" -I "${TEST_DIR}" -L "${TEST_DIR}" \
      -lGYDiagnosticsSdk -lGYDiagnosticsProvider -lBuglyProCore -lTDOS_Diagnose \
      -Xlinker -rpath -Xlinker "${TEST_DIR}" "${ROOT}/ios-sdk/tests/NativeSdkTestMain.swift" -o "${TEST_DIR}/tests"
    for mode in bugly-crash-only bugly-both bugly-log-only bugly-log-failure bugly-disabled bugly-performance bugly-invalid; do "${TEST_DIR}/tests" "${mode}"; done
  else
    for name in UMCommon UMAPMHeadersStub; do module "${name}" "${ROOT}/ios-sdk/tests/Stubs/${name}.swift"; done
    module GYDiagnosticsProvider "${ROOT}/ios-sdk/Umeng/GYUmengDiagnosticSdk.swift" \
      "${ROOT}/ios-sdk/Umeng/GYDiagnosticSdkProviderFactory.swift" -D GY_DIAGNOSTICS_TEST_STUBS -lGYDiagnosticsSdk -lUMCommon -lUMAPMHeadersStub
    xcrun swiftc -parse-as-library -module-cache-path "${TEST_DIR}/module-cache" -I "${TEST_DIR}" -L "${TEST_DIR}" \
      -lGYDiagnosticsSdk -lGYDiagnosticsProvider -lUMCommon -lUMAPMHeadersStub \
      -Xlinker -rpath -Xlinker "${TEST_DIR}" "${ROOT}/ios-sdk/tests/NativeSdkTestMain.swift" -o "${TEST_DIR}/tests"
    for mode in umeng umeng-logs umeng-log-only umeng-disabled umeng-performance umeng-invalid; do "${TEST_DIR}/tests" "${mode}"; done
  fi
done
echo "iOS native diagnostic SDK tests passed."
