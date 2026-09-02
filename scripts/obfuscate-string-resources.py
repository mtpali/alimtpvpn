#!/usr/bin/env python3
"""Randomize MobileTina string resource identifiers for one hardened build."""

from __future__ import annotations

import re
import secrets
import sys
from pathlib import Path


def main() -> None:
    source_root = Path(sys.argv[1] if len(sys.argv) > 1 else "V2rayNG/app/src/main")
    if not source_root.is_dir():
        raise SystemExit(f"missing Android source root: {source_root}")

    text_files = sorted(
        path
        for path in source_root.rglob("*")
        if path.is_file() and path.suffix in {".xml", ".kt", ".java"}
    )
    string_definition = re.compile(r'<string\s+name="(mobiletina_[a-z0-9_]+)"')
    protected_names: set[str] = set()
    occupied_names: set[str] = set()
    for path in text_files:
        text = path.read_text(encoding="utf-8")
        protected_names.update(string_definition.findall(text))
        occupied_names.update(re.findall(r'\bname="([a-z][a-z0-9_]*)"', text))

    if len(protected_names) < 40:
        raise SystemExit(f"unexpected protected string count: {len(protected_names)}")

    replacements: dict[str, str] = {}
    for original in sorted(protected_names):
        while True:
            candidate = f"x{secrets.token_hex(6)}"
            if candidate not in occupied_names:
                occupied_names.add(candidate)
                replacements[original] = candidate
                break

    pattern = re.compile(
        r"(?<![A-Za-z0-9_])(" + "|".join(map(re.escape, sorted(replacements, key=len, reverse=True))) + r")(?![A-Za-z0-9_])"
    )
    for path in text_files:
        original_text = path.read_text(encoding="utf-8")
        rewritten = pattern.sub(lambda match: replacements[match.group(1)], original_text)
        if rewritten != original_text:
            path.write_text(rewritten, encoding="utf-8")

    forbidden = re.compile(r'(?:R\.string\.|@string/|<string\s+name=")mobiletina_')
    survivors = [
        str(path)
        for path in text_files
        if forbidden.search(path.read_text(encoding="utf-8"))
    ]
    if survivors:
        raise SystemExit("unobfuscated MobileTina string identifiers: " + ", ".join(survivors))

    print(f"Randomized {len(replacements)} protected string resource identifiers")


if __name__ == "__main__":
    main()
