#!/usr/bin/env python3
"""iOS Swift 静态检查：本地没有编译器时，用它在推送前兜住三类必挂错误。

用法：
    python tools/check_swift.py [目录，默认 ios/ComfyPilot]

检查项（都是真实踩过的坑）：
1. 花括号平衡——脚本替换代码块时残留修饰符/括号，会让 body 提前闭合；
2. `some View` 属性最外层有 if/switch 却没标 @ViewBuilder——会被当成三元
   推断，报 branches have mismatching types；
3. enum case 用了 Swift 关键字（如 `case import`）——关键字不能作标识符。

只做上面三项；语法/类型错误仍要靠 xcodebuild。
"""

from __future__ import annotations

import glob
import io
import os
import re
import sys

KEYWORDS = {
    "associatedtype", "class", "deinit", "enum", "extension", "fileprivate", "func",
    "import", "init", "inout", "internal", "let", "open", "operator", "private",
    "precedencegroup", "protocol", "public", "rethrows", "static", "struct",
    "subscript", "typealias", "var", "break", "case", "catch", "continue", "default",
    "defer", "do", "else", "fallthrough", "for", "guard", "if", "in", "repeat",
    "return", "throw", "switch", "where", "while", "as", "Any", "false", "is", "nil",
    "self", "Self", "super", "throws", "true", "try",
}

VAR_RE = re.compile(r"\s*(?:private |fileprivate |internal |public )?var (\w+): some View \{")
BRANCH_RE = re.compile(r"\s*(if |switch )")


def find_swift_files(base: str) -> list[str]:
    return sorted(glob.glob(os.path.join(base, "*.swift")))


def check_braces(path: str, lines: list[str]) -> list[str]:
    depth = sum(line.count("{") - line.count("}") for line in lines)
    if depth != 0:
        return [f"{path}: 花括号不平衡（最终 depth={depth}）"]
    return []


def check_view_builder(path: str, lines: list[str]) -> list[str]:
    problems: list[str] = []
    for index, line in enumerate(lines):
        match = VAR_RE.match(line)
        if not match or match.group(1) == "body":
            continue
        # 往上跳过注释与空行，看有没有 @ViewBuilder
        cursor = index - 1
        while cursor >= 0 and (
            lines[cursor].strip().startswith("//") or not lines[cursor].strip()
        ):
            cursor -= 1
        if cursor >= 0 and "@ViewBuilder" in lines[cursor]:
            continue
        # 只看属性体最外层（depth == 1）的分支
        depth = line.count("{") - line.count("}")
        for scan in range(index + 1, min(index + 300, len(lines))):
            if depth == 1 and BRANCH_RE.match(lines[scan]):
                problems.append(
                    f"{path}:{scan + 1}: {match.group(1)} 最外层有分支但缺 @ViewBuilder"
                )
                break
            depth += lines[scan].count("{") - lines[scan].count("}")
            if depth <= 0:
                break
    return problems


def check_enum_cases(path: str, lines: list[str]) -> list[str]:
    problems: list[str] = []
    for index, line in enumerate(lines):
        for match in re.finditer(r"\bcase\s+([A-Za-z0-9_,\s()\.`]+)", line):
            for token in match.group(1).split(","):
                name = token.strip().split("(")[0].strip().strip("`")
                if name in KEYWORDS:
                    problems.append(f"{path}:{index + 1}: `case {name}` 用了 Swift 关键字")
    return problems


def main() -> int:
    base = sys.argv[1] if len(sys.argv) > 1 else "ios/ComfyPilot"
    files = find_swift_files(base)
    if not files:
        print(f"没有找到 Swift 文件：{base}")
        return 1

    problems: list[str] = []
    for path in files:
        lines = io.open(path, encoding="utf-8").read().split("\n")
        problems += check_braces(path, lines)
        problems += check_view_builder(path, lines)
        problems += check_enum_cases(path, lines)

    if problems:
        print(f"检查 {len(files)} 个文件，发现 {len(problems)} 个问题：")
        for item in problems:
            print("  " + item)
        return 1
    print(f"检查 {len(files)} 个文件：花括号 / @ViewBuilder / 关键字 case 全部通过")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
