#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEST_DIR="$(mktemp -d /tmp/gyc-ios-sdk-tests.XXXXXX)"
trap 'rm -rf "${TEST_DIR}"' EXIT
module() {
  local name="$1"; shift
  xcrun swiftc -emit-library -emit-module -enable-testing -module-name "${name}" \
    -module-cache-path "${TEST_DIR}/module-cache" -I "${TEST_DIR}" -L "${TEST_DIR}" "$@" \
    -Xlinker -rpath -Xlinker "${TEST_DIR}" \
    -o "${TEST_DIR}/lib${name}.dylib" -emit-module-path "${TEST_DIR}/${name}.swiftmodule"
}
module GYDiagnosticsSdk "${ROOT}/ios-sdk/Core/GYDiagnosticSdk.swift"
for name in UMCommon UMAPM BuglyPro TDOS_Diagnose; do module "${name}" "${ROOT}/ios-sdk/tests/Stubs/${name}.swift"; done
module GYDiagnosticsUmeng "${ROOT}/ios-sdk/Umeng/GYUmengDiagnosticSdk.swift" -lGYDiagnosticsSdk -lUMCommon -lUMAPM
module GYDiagnosticsBugly "${ROOT}/ios-sdk/Bugly/GYBuglyDiagnosticSdk.swift" "${ROOT}/ios-sdk/Bugly/Logs/GYBuglyLogs.swift" \
  -lGYDiagnosticsSdk -lBuglyPro -lTDOS_Diagnose
xcrun swiftc -parse-as-library -module-cache-path "${TEST_DIR}/module-cache" -I "${TEST_DIR}" -L "${TEST_DIR}" \
  -lGYDiagnosticsSdk -lGYDiagnosticsUmeng -lGYDiagnosticsBugly -lUMCommon -lUMAPM -lBuglyPro -lTDOS_Diagnose \
  -Xlinker -rpath -Xlinker "${TEST_DIR}" "${ROOT}/ios-sdk/tests/NativeSdkTestMain.swift" -o "${TEST_DIR}/tests"
for mode in bugly-crash-only bugly-both bugly-log-only bugly-log-failure bugly-disabled bugly-performance bugly-invalid \
  umeng umeng-logs umeng-log-only umeng-disabled umeng-performance umeng-invalid; do
  "${TEST_DIR}/tests" "${mode}"
done
echo "iOS native diagnostic SDK tests passed."
