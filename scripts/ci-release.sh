#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${VERSION:?Provide an immutable release tag}"
[[ "$VERSION" =~ ^[0-9][0-9A-Za-z._-]*$ ]] || { echo 'Invalid release tag' >&2; exit 1; }
staging="$(mktemp -d)"
preserve_evidence() {
  status=$?
  diagnostics="${CI_DIAGNOSTICS_DIR:-ci-diagnostics}/public"
  mkdir -p "$diagnostics"
  printf 'version=%s\nexit=%s\n' "$VERSION" "$status" > "$diagnostics/result.txt"
  rm -rf "$staging"
  exit "$status"
}
trap preserve_evidence EXIT
# 从不可变版本 Tag 取得提交和 tree，沿用 JitPack 的回执与归档校验。
repo_root="$PWD"
git init -q "$staging/tag"
git -C "$staging/tag" fetch -q --no-tags --depth=1 https://github.com/gycrosskit/diagnostics.git "refs/tags/$VERSION"
git -C "$staging/tag" checkout -q --detach FETCH_HEAD
commit="$(git -C "$staging/tag" rev-parse --verify HEAD)"
(cd "$staging/tag" && bash "$repo_root/jitpack-install.sh" diagnostics "$staging/maven")
modules=diagnostics-core,diagnostics-dingtalk
jvm_limit=()
case "$VERSION" in
  0.1.0|0.2.0-rc.[1-5]) ;; # 网络适配引入前的固定历史版本。
  *) modules="$modules,diagnostics-ktor,diagnostics-okhttp"; jvm_limit=(--max-jvm-major 61) ;;
esac
case "$VERSION" in
  0.1.0|0.2.0-rc.[1-9]|0.2.0-rc.1[0-2]) ;;
  *) modules="$modules,diagnostics-bugly,diagnostics-umeng,diagnostics-kuikly" ;;
esac
python3 verification/check-maven.py "$staging/maven" com.github.gycrosskit.diagnostics "$VERSION" "$modules" ios_arm64,ios_x64,ios_simulator_arm64,ohos_arm64 "${jvm_limit[@]}"

publications="$(python3 - "$staging/maven" "$VERSION" <<'PUBLICATIONS'
from pathlib import Path
import sys
print(','.join(sorted(p.parent.parent.name for p in Path(sys.argv[1]).rglob('*.module') if p.parent.name == sys.argv[2])))
PUBLICATIONS
)"
[[ "$commit" =~ ^[a-f0-9]{40}$ ]] || { echo 'Cannot resolve immutable tag' >&2; exit 1; }
python3 scripts/check-public-maven.py --repo diagnostics --version "$VERSION" --commit "$commit" --expected-publications "$publications" --output-dir "${CI_DIAGNOSTICS_DIR:-ci-diagnostics}/public"
