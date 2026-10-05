"""确认选定任务实际执行测试，不能把 disabled target / 全 skipped 当作回归通过。"""
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

for directory in sys.argv[1:]:
    files = list(Path(directory).glob("*.xml"))
    assert files, f"No JUnit reports: {directory}"
    executed = 0
    for file in files:
        suite = ET.parse(file).getroot()
        assert int(suite.get("failures", "0")) == 0 and int(suite.get("errors", "0")) == 0, file
        executed += sum(test.find("skipped") is None for test in suite.iter("testcase"))
    assert executed > 0, f"No executed test cases: {directory}"
    print(f"{directory}: {executed} tests executed")
