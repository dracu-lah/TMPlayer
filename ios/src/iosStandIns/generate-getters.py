#!/usr/bin/env python3
"""Kotlin's property style for the Media3 stand ins' Java getters.

Media3 is Java: Android's player reads its getters as properties (player.currentPosition) and
overrides them as functions (override fun getUri()). The stand ins in kotlin/androidx/media3 keep
Java's functions, so the overrides compile; this writes the property reads, as extension
properties in each package that reads them, where Kotlin finds them without an import.

Run after changing a stand in:  python3 ios/src/iosStandIns/generate-getters.py
"""
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
STAND_INS = os.path.join(HERE, "kotlin", "androidx", "media3")
# The packages of upstream's code that read Media3's getters as properties.
READERS = ["com.tmplayer.player"]

DECLARATION = re.compile(r"^(\s*)(?:(?:public|open|abstract|data|sealed|fun|enum|annotation)\s+)*(class|interface|object)\s+(\w+)")
GETTER = re.compile(r"^(\s*)(?:(?:open|abstract)\s+)*fun\s+(get|is)([A-Z]\w*)\(\)\s*:\s*([\w.<>?, ]+?)\s*(?:=|\{|$)")
SETTER = re.compile(r"^(\s*)(?:(?:open|abstract)\s+)*fun\s+set([A-Z]\w*)\(\w+:\s*([\w.<>?, ]+)\)")
PACKAGE = re.compile(r"^package\s+([\w.]+)")


def scan():
    """(owner, getter name, property, return type, has setter) for every public zero-argument getter."""
    found = []
    for root, _, files in os.walk(STAND_INS):
        for name in sorted(files):
            if not name.endswith(".kt"):
                continue
            package = None
            stack = []  # (indent, name)
            nested = {}
            getters = []
            setters = set()
            for line in open(os.path.join(root, name), encoding="utf-8"):
                if package is None:
                    m = PACKAGE.match(line)
                    if m:
                        package = m.group(1)
                    continue
                if re.match(r"^\s*(private|internal|protected|override)\b", line):
                    continue
                m = DECLARATION.match(line)
                if m and not line.strip().startswith("//"):
                    indent = len(m.group(1))
                    while stack and stack[-1][0] >= indent:
                        stack.pop()
                    stack.append((indent, m.group(3)))
                    owner = ".".join(n for _, n in stack[:-1])
                    nested.setdefault(owner, set()).add(m.group(3))
                    continue
                m = GETTER.match(line)
                if m:
                    indent = len(m.group(1))
                    while stack and stack[-1][0] >= indent:
                        stack.pop()
                    if stack and stack[-1][0] == indent - 4:
                        owner = ".".join(n for _, n in stack)
                        getters.append((owner, m.group(2), m.group(3), m.group(4).strip()))
                    continue
                m = SETTER.match(line)
                if m:
                    indent = len(m.group(1))
                    while stack and stack[-1][0] >= indent:
                        stack.pop()
                    if stack and stack[-1][0] == indent - 4:
                        setters.add((".".join(n for _, n in stack), m.group(2)))
            for owner, kind, rest, kind_type in getters:
                if not owner or "Companion" in owner:
                    continue
                prop = ("is" + rest) if kind == "is" else rest[0].lower() + rest[1:]
                qualified = qualify(kind_type, package, owner, nested)
                found.append((f"{package}.{owner}", f"{kind}{rest}", prop, qualified, (owner, rest) in setters))
    return found


def qualify(kind_type, package, owner, nested):
    """A type as the generated file must name it: a class nested in the owner gets the owner's path."""
    def one(match):
        word = match.group(0)
        path = owner
        while True:
            if word in nested.get(path, set()):
                return f"{package}.{path}.{word}" if path else f"{package}.{word}"
            if not path:
                return word
            path = path.rpartition(".")[0]
    return re.sub(r"\b[A-Z]\w*\b", one, kind_type)


def main():
    found = scan()
    packages = set()
    others = set()
    for root, _, files in os.walk(STAND_INS):
        for name in files:
            if name.endswith(".kt"):
                for line in open(os.path.join(root, name), encoding="utf-8"):
                    m = PACKAGE.match(line)
                    if m:
                        packages.add(m.group(1))
                    m = re.match(r"^import\s+([\w.]+)\s*$", line)
                    if m and not m.group(1).startswith("androidx.media3") and not m.group(1).startswith("kotlinx.cinterop") \
                            and not m.group(1).startswith("platform.") and not m.group(1).startswith("com.tmplayer.ios"):
                        others.add(m.group(1))
    seen = set()
    for reader in READERS:
        lines = [
            f"package {reader}",
            "",
            "// Generated by ios/src/iosStandIns/generate-getters.py from the Media3 stand ins; do not edit.",
            "// Media3's Java getters as the Kotlin properties Android's player reads them as.",
            "",
        ] + [f"import {package}.*" for package in sorted(packages)] + [f"import {name}" for name in sorted(others)] + [
            "",
        ]
        for receiver, getter, prop, kind_type, settable in sorted(found):
            key = (receiver, prop)
            if key in seen:
                continue
            seen.add(key)
            if settable:
                setter = "set" + getter[2:] if getter.startswith("is") else "set" + getter[3:]
                lines.append(f"internal var {receiver}.{prop}: {kind_type}")
                lines.append(f"    get() = {getter}()")
                lines.append(f"    set(value) {{ {setter}(value) }}")
            else:
                lines.append(f"internal val {receiver}.{prop}: {kind_type} get() = {getter}()")
        seen.clear()
        folder = os.path.join(HERE, "kotlin", *reader.split("."))
        os.makedirs(folder, exist_ok=True)
        with open(os.path.join(folder, "Media3Getters.kt"), "w", encoding="utf-8") as out:
            out.write("\n".join(lines) + "\n")
        print(f"{reader}: {len(found)} getters")


if __name__ == "__main__":
    main()
