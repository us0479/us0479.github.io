"""Extract SQL text-block constants from BankWatch Java sources and emit psql PREPARE statements.

The SQL body is taken verbatim from the Java file. Only named parameters (:name) are rewritten to
PostgreSQL positional parameters ($n) with explicit types so the server performs real binding.
Usage: python3 -I sqlprep.py <project-root> > prepared.sql
Prepared names are <class>_<constant> in lower case, e.g. detectionservice_candidates.
"""
import re
import sys
from pathlib import Path

TYPES = {
    "from": "timestamptz", "to": "timestamptz", "time": "timestamptz", "bucket": "timestamptz",
    "anchorAt": "timestamptz", "beforeAt": "timestamptz",
    "threshold": "integer", "version": "integer", "fetchLimit": "integer",
    "rule": "bigint", "account": "bigint", "alert": "bigint", "anchorId": "bigint", "event": "bigint",
    "actor": "bigint", "incident": "bigint", "cause": "bigint", "previous": "bigint", "beforeId": "bigint",
    "reason": "text", "resolution": "text", "action": "text", "type": "text", "device": "text",
    "customer": "text", "login": "text", "newDevice": "boolean",
}
BLOCK = re.compile(r'static final String (\w+) = """\n(.*?)"""', re.S)
PARAM = re.compile(r"(?<![:\w]):([A-Za-z]\w*)")
FILES = [
    "src/main/java/kr/ac/bankwatch/event/EventService.java",
    "src/main/java/kr/ac/bankwatch/detection/DetectionService.java",
    "src/main/java/kr/ac/bankwatch/detection/AlertQueryService.java",
    "src/main/java/kr/ac/bankwatch/incident/IncidentClaimService.java",
    "src/main/java/kr/ac/bankwatch/incident/IncidentActionService.java",
]


def prepare(name, sql):
    order = []

    def sub(match):
        key = match.group(1)
        if key not in TYPES:
            raise SystemExit(f"unknown parameter {key} in {name}")
        if key not in order:
            order.append(key)
        return f"${order.index(key) + 1}"

    body = PARAM.sub(sub, sql).strip()
    types = ", ".join(TYPES[k] for k in order)
    head = f"PREPARE {name}({types}) AS" if order else f"PREPARE {name} AS"
    return f"-- params: {', '.join(order) or '-'}\n{head}\n{body};\n", order


def main(root):
    root = Path(root)
    out = []
    for rel in FILES:
        text = (root / rel).read_text(encoding="utf-8")
        cls = Path(rel).stem.lower()
        for const, sql in BLOCK.findall(text):
            if const in ("SOURCE", "ITEMS", "COUNTS", "SUMMARY"):
                continue  # fragments composed at runtime; exercised separately
            stmt, _ = prepare(f"{cls}_{const.lower()}", sql)
            out.append(stmt)
    sys.stdout.write("\n".join(out))


if __name__ == "__main__":
    main(sys.argv[1])
