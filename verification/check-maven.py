"""验证 core 与可选渠道的完整 staging 树；不能只检查 core 而漏掉 dingtalk。"""
import hashlib
import json
import sys
from pathlib import Path

repo = Path(sys.argv[1] if len(sys.argv) > 1 else "build/maven")
modules = list(repo.rglob("*.module"))
expected = {"diagnostics-core", "diagnostics-core-android", "diagnostics-core-jvm", "diagnostics-core-iosarm64",
            "diagnostics-core-iossimulatorarm64", "diagnostics-core-iosx64", "diagnostics-core-ohosarm64",
            "diagnostics-dingtalk", "diagnostics-dingtalk-android", "diagnostics-dingtalk-jvm", "diagnostics-dingtalk-iosarm64",
            "diagnostics-dingtalk-iossimulatorarm64", "diagnostics-dingtalk-iosx64"}
actual = set()
versions = set()
for module in modules:
    data = json.loads(module.read_text())
    component = data["component"]
    assert component["group"] == "com.github.gycrosskit.diagnostics", component
    actual.add(module.parent.parent.name)
    versions.add(component["version"])
    if "url" in component:
        assert (module.parent / component["url"]).resolve().is_file()
    for variant in data["variants"]:
        redirect = variant.get("available-at")
        if redirect:
            assert (module.parent / redirect["url"]).resolve().is_file(), redirect
        for entry in variant.get("files", []):
            file = (module.parent / entry["url"]).resolve()
            assert file.is_file(), file
            assert file.stat().st_size == entry["size"], file
            assert hashlib.sha256(file.read_bytes()).hexdigest() == entry["sha256"], file
assert expected == actual, (expected - actual, actual - expected)
assert len(versions) == 1, versions
assert len(list(repo.rglob("*.aar"))) == 2, "Both Android AARs required"
print(f"Maven metadata: {len(modules)} modules, both Android AARs and core/dingtalk native variants passed")
