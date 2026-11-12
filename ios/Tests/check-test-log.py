"""Assert that the C05 XCTest suite really ran, not only compiled."""
import re
import sys
from pathlib import Path

source = Path(__file__).with_name("SessionIsolationTests.swift")
names = re.findall(r"func (test\w+)\(", source.read_text(encoding="utf-8"))
if not names:
    raise SystemExit("C05: no test methods in the actual test source")
text = Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace")
lines = text.splitlines()
missing = [name for name in names if not any(
    "SessionIsolationTests" in line and name in line and " passed " in line
    for line in lines
)]
counts = [(int(total), int(failed)) for total, failed in re.findall(
    r"Executed (\d+) tests?, with (\d+) failures?", text
)]
if missing or not any(total >= len(names) and failed == 0 for total, failed in counts):
    print("C05 XCTest execution gate failed:", ", ".join(missing) or "no nonempty passing test summary")
    raise SystemExit(1)
print(f"C05: all {len(names)} named behavioral tests executed and passed")
