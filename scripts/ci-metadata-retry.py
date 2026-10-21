#!/usr/bin/env python3
"""只识别当前 metadata 任务完全由 Eazytec TCP 超时构成的失败原因树。"""
from pathlib import Path
import re
import sys


def retry_url(log):
    if '* What went wrong:' not in log:
        return None
    failure = log.rsplit('* What went wrong:', 1)[1].split('* Try:', 1)[0]
    lines = [line for line in failure.splitlines() if line.strip()]
    if not lines or lines[0].strip() != "Execution failed for task ':diagnostics-core:transformCommonMainDependenciesMetadata'.":
        return None
    causes = []
    for line in lines[1:]:
        match = re.fullmatch(r'(\s*)> (.+)', line)
        if not match:
            return None
        causes.append((len(match[1]), match[2]))
    leaves = [text for i, (indent, text) in enumerate(causes)
              if i + 1 == len(causes) or causes[i + 1][0] <= indent]
    if not leaves or not all(re.fullmatch(r'Connect to maven\.eazytec-cloud\.com:443 \[[^]]+\] failed: Connect timed out', text) for text in leaves):
        return None
    urls = [match[1] for _, text in causes
            if (match := re.fullmatch(r"Could not GET '(https://maven\.eazytec-cloud\.com/nexus/repository/maven-public/[A-Za-z0-9._/-]+)'\.", text))]
    return urls[0] if urls else None


if __name__ == '__main__':
    url = retry_url(Path(sys.argv[1]).read_text())
    if url is None:
        sys.exit(1)
    print(url)
