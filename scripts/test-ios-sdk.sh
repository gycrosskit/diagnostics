#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}"
# 行为替身不启动真实 SDK；随后真实 Pod 编译核对锁定厂商 API 和独立消费。
bash ios-sdk/tests/run.sh
pod lib lint GYDiagnosticsSdk.podspec --allow-warnings --platforms=ios --use-static-frameworks
for spec in GYDiagnosticsBugly GYDiagnosticsUmeng; do
  pod lib lint "${spec}.podspec" --allow-warnings --platforms=ios \
    --use-static-frameworks --include-podspecs=GYDiagnosticsSdk.podspec --fail-fast
done
