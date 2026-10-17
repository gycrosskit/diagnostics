#!/usr/bin/env bash
# 仅在远程 Ubuntu runner 编译；宿主不从源码或本地工程生产替代 HAR。
set -euo pipefail
[[ "$(uname -s)" == Linux && ( "${GITHUB_ACTIONS:-}" == true || -n "${JENKINS_URL:-}" ) ]] || {
  echo 'OHOS SDK HAR builds require a remote GitHub Actions or Jenkins Linux runner' >&2; exit 2;
}
TASK_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TASK_OUTPUT="$TASK_ROOT/build/ohos-sdk"
mkdir -p "$TASK_OUTPUT"
if [[ -z "${OHOS_CLT_HOME:-}" ]]; then
  : "${OHOS_CLT_URL:?Provide the official Huawei Linux x64 CLT download URL}"
  : "${OHOS_CLT_SHA256:?Provide the reviewed official CLT SHA-256}"
  python3 - <<'CHECK'
import os, re
from urllib.parse import urlsplit
url = urlsplit(os.environ['OHOS_CLT_URL'])
if url.scheme != 'https' or url.hostname not in {'dl.developer.huawei.com', 'developer.huawei.com'}:
    raise SystemExit('CLT download must use an official Huawei HTTPS origin')
if not re.fullmatch('[a-f0-9]{64}', os.environ['OHOS_CLT_SHA256']):
    raise SystemExit('CLT SHA-256 must be an exact lowercase digest')
CHECK
  curl --fail --location --proto '=https' --retry 3 "$OHOS_CLT_URL" -o "$TASK_OUTPUT/clt.zip"
  printf '%s  %s\n' "$OHOS_CLT_SHA256" "$TASK_OUTPUT/clt.zip" | sha256sum --check --strict
  mkdir -p "$TASK_OUTPUT/tools"
  unzip -q "$TASK_OUTPUT/clt.zip" -d "$TASK_OUTPUT/tools"
  OHOS_CLT_HOME="$TASK_OUTPUT/tools/command-line-tools"
fi
export DEVECO_SDK_HOME="$OHOS_CLT_HOME/sdk"
export DEVECO_NODE_HOME="$OHOS_CLT_HOME/tool/node"
export PATH="$OHOS_CLT_HOME/tool/node/bin:$OHOS_CLT_HOME/bin:$PATH"
[[ -x "$OHOS_CLT_HOME/tool/node/bin/node" && -f "$OHOS_CLT_HOME/bin/hvigorw" ]] || {
  echo 'Official CLT tool layout missing' >&2; exit 2;
}
node --version
bash "$OHOS_CLT_HOME/bin/hvigorw" --version
cd "$TASK_ROOT/ohos-sdk"
# 先只解析 core；vendor 尚未发布的同版本 core 只在生产工程中用本轮真 HAR 覆盖。
ohpm install
(cd core && ohpm install)
bash "$OHOS_CLT_HOME/bin/hvigorw" --mode module -p product=default -p module=DiagnosticsCore@default -p buildMode=release assembleHar --no-daemon
python3 "$TASK_ROOT/scripts/verify-ohos-sdk.py" --source "$TASK_ROOT/ohos-sdk/core" --copy-to "$TASK_OUTPUT/DiagnosticsCore.har"
TASK_PACKAGE_BACKUP="$(mktemp)"
cp oh-package.json5 "$TASK_PACKAGE_BACKUP"
trap 'cp "$TASK_PACKAGE_BACKUP" "$TASK_ROOT/ohos-sdk/oh-package.json5"; rm -f "$TASK_PACKAGE_BACKUP"' EXIT
export TASK_OUTPUT
python3 - <<'OVERRIDE'
import json, os
from pathlib import Path
file = Path('oh-package.json5')
package = json.loads(file.read_text())
package['overrides'] = {'@gycrosskit/diagnostics-core': 'file:' + os.environ['TASK_OUTPUT'] + '/DiagnosticsCore.har'}
file.write_text(json.dumps(package, indent=2) + '\n')
OVERRIDE
ohpm install --all
for TASK_MODULE in Bugly Umeng; do
  bash "$OHOS_CLT_HOME/bin/hvigorw" --mode module -p product=default -p "module=Diagnostics${TASK_MODULE}@default" -p buildMode=release assembleHar --no-daemon
  TASK_DIRECTORY="$(printf '%s' "$TASK_MODULE" | tr '[:upper:]' '[:lower:]')"
  python3 "$TASK_ROOT/scripts/verify-ohos-sdk.py" --source "$TASK_ROOT/ohos-sdk/$TASK_DIRECTORY" --copy-to "$TASK_OUTPUT/Diagnostics${TASK_MODULE}.har"
done
(cd "$TASK_OUTPUT" && sha256sum DiagnosticsCore.har DiagnosticsBugly.har DiagnosticsUmeng.har > SHA256SUMS)
git -C "$TASK_ROOT" rev-parse HEAD > "$TASK_OUTPUT/SOURCE_SHA"
# 此目录只有真实 Hvigor 产物；Release 上传由发布任务单独授权。
