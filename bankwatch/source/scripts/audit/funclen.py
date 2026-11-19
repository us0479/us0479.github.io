"""Upper-bound function length audit for BankWatch (Java/JS brace blocks, Python AST).

Every brace block that is not a type body (class/record/interface/enum) is measured from the
first line of its header (statement start) to the closing brace line. Any function, method,
lambda, anonymous function or IIFE is such a block, so if all blocks are <= LIMIT the rule holds.
Strings, text blocks, template literals, regex-free comments are skipped.
"""
import ast
import json
import re
import sys
from pathlib import Path

LIMIT = 200
TYPE_RE = re.compile(r"\b(class|record|interface|enum)\b")


def strip_code(src, js):
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        two = src[i:i + 2]
        if two == "//":
            j = src.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i)); i = j; continue
        if two == "/*":
            j = src.find("*/", i + 2); j = n if j < 0 else j + 2
            out.append(re.sub(r"[^\n]", " ", src[i:j])); i = j; continue
        if not js and src[i:i + 3] == '"""':
            j = src.find('"""', i + 3); j = n if j < 0 else j + 3
            out.append(re.sub(r"[^\n]", " ", src[i:j])); i = j; continue
        if c in "\"'" or (js and c == "`"):
            j = i + 1
            depth = 0
            while j < n:
                if src[j] == "\\": j += 2; continue
                if js and c == "`" and src[j:j + 2] == "${":
                    depth += 1; j += 2; continue
                if js and c == "`" and depth and src[j] == "}":
                    depth -= 1; j += 1; continue
                if src[j] == c and not depth: j += 1; break
                if src[j] == "\n" and c != "`": break
                j += 1
            out.append(re.sub(r"[^\n]", " ", src[i:j])); i = j; continue
        out.append(c); i += 1
    return "".join(out)


def brace_blocks(path, js):
    raw = path.read_text(encoding="utf-8")
    src = strip_code(raw, js)
    line_of = []
    ln = 1
    for ch in src:
        line_of.append(ln)
        if ch == "\n": ln += 1
    stack, results = [], []
    for idx, ch in enumerate(src):
        if ch == "{":
            k = idx - 1
            while k >= 0 and src[k] not in ";{}":
                k -= 1
            header = src[k + 1:idx]
            hs = k + 1
            while hs < idx and src[hs].isspace(): hs += 1
            stack.append((idx, header.strip(), line_of[hs] if hs < len(line_of) else line_of[idx]))
        elif ch == "}" and stack:
            start, header, hline = stack.pop()
            end = line_of[idx]
            is_type = bool(TYPE_RE.search(header)) and "(" not in header.split("{")[0][:0] and not header.endswith(")")
            if TYPE_RE.search(header) and not re.search(r"\)\s*(throws [\w., ]+)?$", header):
                is_type = True
            else:
                is_type = False
            results.append({"file": str(path), "start": hline, "end": end, "lines": end - hline + 1,
                            "type_body": is_type, "header": " ".join(header.split())[:90]})
    return results


def python_funcs(path):
    tree = ast.parse(path.read_text(encoding="utf-8"))
    out = []
    for node in ast.walk(tree):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.Lambda)):
            start = min([node.lineno] + [d.lineno for d in getattr(node, "decorator_list", [])])
            out.append({"file": str(path), "start": start, "end": node.end_lineno,
                        "lines": node.end_lineno - start + 1, "type_body": False,
                        "header": getattr(node, "name", "lambda")})
    return out


def main(root):
    root = Path(root)
    blocks = []
    for p in sorted(root.rglob("*")):
        if not p.is_file() or ".mvn" in p.parts or p.name in ("mvnw", "mvnw.cmd"):
            continue
        if p.suffix == ".java": blocks += brace_blocks(p, False)
        elif p.suffix == ".js": blocks += brace_blocks(p, True)
        elif p.suffix == ".py": blocks += python_funcs(p)
    measured = [b for b in blocks if not b["type_body"]]
    over = [b for b in measured if b["lines"] > LIMIT]
    top = sorted(measured, key=lambda b: -b["lines"])[:12]
    rel = lambda b: {**b, "file": str(Path(b["file"]).relative_to(root))}
    report = {"limit": LIMIT, "measured_blocks": len(measured), "over_limit": [rel(b) for b in over],
              "largest": [rel(b) for b in top]}
    print(json.dumps(report, ensure_ascii=False, indent=1))
    return 1 if over else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
