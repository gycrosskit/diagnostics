#!/usr/bin/env bash
set -euo pipefail
: "${VERSION:?Provide an immutable release tag}"
[[ "$VERSION" =~ ^[0-9][0-9A-Za-z._-]*$ ]] || { echo 'Invalid release tag' >&2; exit 1; }
checksum="$(awk -v version="$VERSION" '$1 == version {print $2}' release-checksums.txt)"
[[ "$checksum" =~ ^[a-f0-9]{64}$ ]] || { echo "No verified checksum for $VERSION" >&2; exit 1; }
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
archive="$staging/diagnostics-maven.tar.gz"
curl -fsSL --retry 3 --connect-timeout 30 --max-time 300 -o "$archive" "https://github.com/gycrosskit/diagnostics/releases/download/$VERSION/diagnostics-maven.tar.gz"
echo "$checksum  $archive" | shasum -a 256 -c -
python3 - "$archive" "$staging/maven" <<'EXTRACT'
import sys, tarfile
from pathlib import Path
archive, destination = sys.argv[1:]
root = Path(destination).resolve()
with tarfile.open(archive) as bundle:
    for item in bundle.getmembers():
        assert item.isfile() or item.isdir(), f"Unexpected archive member: {item.name}"
        assert (root / item.name).resolve().is_relative_to(root), item.name
    bundle.extractall(root)
EXTRACT
modules=diagnostics-core,diagnostics-dingtalk
jvm_limit=()
case "$VERSION" in
  0.1.0|0.2.0-rc.[1-5]) ;; # 网络适配引入前的固定历史版本。
  *) modules="$modules,diagnostics-ktor,diagnostics-okhttp"; jvm_limit=(--max-jvm-major 61) ;;
esac
python3 verification/check-maven.py "$staging/maven" com.github.gycrosskit.diagnostics "$VERSION" "$modules" ios_arm64,ios_x64,ios_simulator_arm64,ohos_arm64 "${jvm_limit[@]}"

publications="$(python3 - "$staging/maven" "$VERSION" <<'PUBLICATIONS'
from pathlib import Path
import sys
print(','.join(sorted(p.parent.parent.name for p in Path(sys.argv[1]).rglob('*.module') if p.parent.name == sys.argv[2])))
PUBLICATIONS
)"
tag_sha="$(git ls-remote --tags https://github.com/gycrosskit/diagnostics.git "refs/tags/$VERSION" "refs/tags/$VERSION^{}" | awk '$2 ~ /\^\{\}$/ {peeled=$1} {direct=$1} END {print peeled ? peeled : direct}')"
[[ "$tag_sha" =~ ^[a-f0-9]{40}$ ]] || { echo 'Cannot resolve immutable tag' >&2; exit 1; }
python3 scripts/check-public-maven.py --repo diagnostics --version "$VERSION" --commit "$tag_sha" --expected-publications "$publications" --output-dir "${CI_DIAGNOSTICS_DIR:-ci-diagnostics}/public"
