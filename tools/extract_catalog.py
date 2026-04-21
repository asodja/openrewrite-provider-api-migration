#!/usr/bin/env python3
"""
Scan a Gradle source checkout for methods annotated @ReplacesEagerProperty and emit catalog entries
suitable for pasting into MigratedProperties.java.

Usage:
    python3 extract_catalog.py /path/to/gradle2 > catalog.txt

Output format (one per line):
    fully.qualified.declaring.Type|propertyName|KIND
where KIND is one of SCALAR_PROPERTY, LIST_PROPERTY, SET_PROPERTY, MAP_PROPERTY,
CONFIGURABLE_FILE_COLLECTION, DIRECTORY_PROPERTY, REGULAR_FILE_PROPERTY.
"""
import os
import re
import sys
from pathlib import Path

# Map the first token of the return type to a Kind.
KIND_BY_TYPE = {
    "Property": "SCALAR_PROPERTY",
    "Provider": "SCALAR_PROPERTY",       # read-only lazy — still triggers .get() insertion
    "ListProperty": "LIST_PROPERTY",
    "SetProperty": "SET_PROPERTY",
    "MapProperty": "MAP_PROPERTY",
    "ConfigurableFileCollection": "CONFIGURABLE_FILE_COLLECTION",
    "FileCollection": "CONFIGURABLE_FILE_COLLECTION",  # typically means the backing is CFC
    "DirectoryProperty": "DIRECTORY_PROPERTY",
    "RegularFileProperty": "REGULAR_FILE_PROPERTY",
}

# Match an @ReplacesEagerProperty annotation up to (and not including) the method sig. Accepts
# optional annotation arguments like @ReplacesEagerProperty(replacedAccessors = ...).
ANNOTATION_RE = re.compile(r"@ReplacesEagerProperty(?:\([^)]*\))?")
# Strip Java modifiers that can appear before the return type.
MODIFIERS_RE = re.compile(r"^\s*(?:public|protected|private|abstract|static|final|default|synchronized|native|strictfp|transient)\s+")
# Match a method signature starting at or near the beginning of the text. Assumes modifiers have
# already been stripped. Captures return type and getter name separately.
METHOD_RE = re.compile(
    r"\s*([A-Za-z0-9_.<>?,\s]+?)\s+"          # return type (non-greedy)
    r"(get|is)([A-Z][A-Za-z0-9_]*)\s*\(\s*\)" # no-arg getter
)
PACKAGE_RE = re.compile(r"^\s*package\s+([A-Za-z0-9_.]+)\s*;", re.MULTILINE)
# Declared class/interface (may be nested; we pick the top-level outermost one).
CLASS_RE = re.compile(
    r"^(?:public\s+|abstract\s+|final\s+|sealed\s+|non-sealed\s+)*"
    r"(class|interface|enum|record)\s+([A-Z][A-Za-z0-9_]*)",
    re.MULTILINE,
)


def first_token(return_type: str) -> str:
    """Return the leading identifier of a return type, ignoring whitespace and generics."""
    s = return_type.strip()
    m = re.match(r"([A-Za-z0-9_.]+)", s)
    return m.group(1) if m else ""


def extract(java_file: Path):
    text = java_file.read_text(errors="replace")
    pkg_m = PACKAGE_RE.search(text)
    class_m = CLASS_RE.search(text)
    if not pkg_m or not class_m:
        return
    pkg = pkg_m.group(1)
    cls = class_m.group(2)
    fqn = f"{pkg}.{cls}"

    # For each annotation occurrence, look ahead for the getter method signature.
    for ann_match in ANNOTATION_RE.finditer(text):
        tail = text[ann_match.end() : ann_match.end() + 600]  # look-ahead window
        # Strip leading whitespace + any modifier keywords so the return type is what comes next.
        stripped = tail.lstrip()
        while True:
            mm = MODIFIERS_RE.match(stripped)
            if not mm:
                break
            stripped = stripped[mm.end():]
        m = METHOD_RE.match(stripped)
        if not m:
            continue
        return_type = m.group(1).strip()
        prefix = m.group(2)
        name_suffix = m.group(3)
        # Skip if the annotation is on something that isn't actually the next getter (defensive).
        leading = tail[: m.start()]
        # If the look-ahead includes another @ReplacesEagerProperty before the method, skip — that
        # one will be handled by its own iteration.
        if "@ReplacesEagerProperty" in leading:
            continue
        # Strip generics from the first return-type token
        kind = KIND_BY_TYPE.get(first_token(return_type.split("<", 1)[0].strip()))
        if not kind:
            continue
        prop = name_suffix[0].lower() + name_suffix[1:]
        yield fqn, prop, kind


def main():
    if len(sys.argv) < 2:
        print("usage: extract_catalog.py /path/to/gradle-source", file=sys.stderr)
        sys.exit(2)
    root = Path(sys.argv[1])
    # Walk the whole tree but skip build/ and out/ directories.
    emitted = set()
    for path in root.rglob("*.java"):
        if any(part in ("build", "out", ".gradle") for part in path.parts):
            continue
        try:
            for entry in extract(path):
                emitted.add(entry)
        except Exception as exc:
            print(f"# warn: {path}: {exc}", file=sys.stderr)

    for fqn, prop, kind in sorted(emitted):
        print(f"{fqn}|{prop}|{kind}")


if __name__ == "__main__":
    main()
