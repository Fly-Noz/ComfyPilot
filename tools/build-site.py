#!/usr/bin/env python3
"""把 docs/release/ 下的法律文档编译成 site/ 下的静态页。

单一来源：隐私政策、用户协议只在 Markdown 里维护，网页由本脚本生成，
避免出现「仓库里的文档」和「官网上的页面」两份内容互相打架。

用法：
    python tools/build-site.py

生成：
    site/privacy.html   ← docs/release/privacy-policy.zh-CN.md
    site/terms.html     ← docs/release/user-agreement.zh-CN.md

脚本只覆盖这两个文件，index.html 是手写的，不会被改动。
"""

from __future__ import annotations

import html
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# —— 备案通过后，把备案号填在这里再重新构建 ——
ICP_NUMBER = ""
ICP_LINK = "https://beian.miit.gov.cn/"

# Markdown 文件名 → 站内页面名
PAGE_MAP = {
    "privacy-policy.zh-CN.md": "privacy.html",
    "user-agreement.zh-CN.md": "terms.html",
}


# ---------------------------------------------------------------- inline


def inline(text: str) -> str:
    """处理行内语法：自动链接、行内代码、加粗、站内链接。"""
    autolinks: list[str] = []
    codes: list[str] = []

    def stash_autolink(m: re.Match) -> str:
        autolinks.append(m.group(1))
        return f"\x00A{len(autolinks) - 1}\x00"

    def stash_code(m: re.Match) -> str:
        codes.append(m.group(1))
        return f"\x00C{len(codes) - 1}\x00"

    text = re.sub(r"<(https?://[^>\s]+)>", stash_autolink, text)
    text = re.sub(r"`([^`]+)`", stash_code, text)
    text = html.escape(text, quote=False)
    text = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", text)

    def link(m: re.Match) -> str:
        label, href = m.group(1), m.group(2)
        href = PAGE_MAP.get(href, href)
        external = href.startswith("http")
        rel = ' target="_blank" rel="noopener"' if external else ""
        return f'<a href="{href}"{rel}>{label}</a>'

    text = re.sub(r"\[([^\]]+)\]\(([^)\s]+)\)", link, text)

    for i, url in enumerate(autolinks):
        text = text.replace(f"\x00A{i}\x00", f'<a href="{url}" target="_blank" rel="noopener">{url}</a>')
    for i, code in enumerate(codes):
        text = text.replace(f"\x00C{i}\x00", f"<code>{html.escape(code)}</code>")
    return text


# ---------------------------------------------------------------- blocks

FENCE = re.compile(r"^```(\w*)\s*$")
HEADING = re.compile(r"^(#{1,6})\s+(.*)$")
UL = re.compile(r"^\s*[-*+]\s+(.*)$")
OL = re.compile(r"^\s*\d+\.\s+(.*)$")
HR = re.compile(r"^\s*(?:---|\*\*\*|___)\s*$")
QUOTE = re.compile(r"^>\s?(.*)$")
TABLE_SEP = re.compile(r"^\s*\|[\s:|-]+\|\s*$")
TABLE_ROW = re.compile(r"^\s*\|.*\|\s*$")


def split_row(line: str) -> list[str]:
    return [c.strip() for c in line.strip().strip("|").split("|")]


def convert(md: str) -> tuple[str, str, str]:
    """返回 (标题, 正文 HTML, 日期行)。"""
    lines = md.splitlines()
    title = ""
    metas: list[str] = []
    body: list[str] = []
    i = 0
    n = len(lines)

    # 首个 h1 作为页面标题；紧随其后的「生效日期 / 最近更新」行作为副标题
    while i < n:
        m = HEADING.match(lines[i])
        if m and len(m.group(1)) == 1 and not title:
            title = m.group(2).strip()
            i += 1
            continue
        if title and not lines[i].strip():
            i += 1
            continue
        if title and lines[i].startswith(("生效日期", "最近更新")):
            metas.append(lines[i].strip())
            i += 1
            continue
        break

    meta = " · ".join(metas)

    while i < n:
        line = lines[i]

        if not line.strip():
            i += 1
            continue

        # 代码块
        fence = FENCE.match(line)
        if fence:
            lang = fence.group(1)
            i += 1
            buf = []
            while i < n and not FENCE.match(lines[i]):
                buf.append(lines[i])
                i += 1
            i += 1
            cls = f' class="language-{lang}"' if lang else ""
            body.append(f"<pre><code{cls}>{html.escape(chr(10).join(buf))}</code></pre>")
            continue

        # 标题
        h = HEADING.match(line)
        if h:
            level = len(h.group(1))
            body.append(f"<h{level}>{inline(h.group(2).strip())}</h{level}>")
            i += 1
            continue

        # 分隔线
        if HR.match(line) and len(line.strip()) >= 3:
            body.append("<hr />")
            i += 1
            continue

        # 表格
        if TABLE_ROW.match(line) and i + 1 < n and TABLE_SEP.match(lines[i + 1]):
            head = split_row(line)
            i += 2
            rows = []
            while i < n and TABLE_ROW.match(lines[i]):
                rows.append(split_row(lines[i]))
                i += 1
            out = ["<table>", "<thead><tr>"]
            out += [f"<th>{inline(c)}</th>" for c in head]
            out.append("</tr></thead><tbody>")
            for row in rows:
                out.append("<tr>" + "".join(f"<td>{inline(c)}</td>" for c in row) + "</tr>")
            out.append("</tbody></table>")
            body.append("".join(out))
            continue

        # 引用块
        if QUOTE.match(line):
            buf = []
            while i < n and QUOTE.match(lines[i]):
                buf.append(QUOTE.match(lines[i]).group(1))
                i += 1
            body.append("<blockquote>" + convert_inline_paragraphs(buf) + "</blockquote>")
            continue

        # 无序列表
        if UL.match(line):
            items = []
            while i < n and UL.match(lines[i]):
                items.append(UL.match(lines[i]).group(1))
                i += 1
            body.append("<ul>" + "".join(f"<li>{inline(x)}</li>" for x in items) + "</ul>")
            continue

        # 有序列表
        if OL.match(line):
            items = []
            while i < n and OL.match(lines[i]):
                items.append(OL.match(lines[i]).group(1))
                i += 1
            body.append("<ol>" + "".join(f"<li>{inline(x)}</li>" for x in items) + "</ol>")
            continue

        # 段落
        buf = []
        while i < n and lines[i].strip() and not (
            FENCE.match(lines[i])
            or HEADING.match(lines[i])
            or QUOTE.match(lines[i])
            or UL.match(lines[i])
            or OL.match(lines[i])
            or (HR.match(lines[i]) and len(lines[i].strip()) >= 3)
            or (TABLE_ROW.match(lines[i]) and i + 1 < n and TABLE_SEP.match(lines[i + 1]))
        ):
            buf.append(lines[i].strip())
            i += 1
        body.append(f"<p>{inline(' '.join(buf))}</p>")

    return title, "\n".join(body), meta


def convert_inline_paragraphs(lines: list[str]) -> str:
    parts, buf = [], []
    for line in lines:
        if line.strip():
            buf.append(line.strip())
        elif buf:
            parts.append(f"<p>{inline(' '.join(buf))}</p>")
            buf = []
    if buf:
        parts.append(f"<p>{inline(' '.join(buf))}</p>")
    return "".join(parts)


# ---------------------------------------------------------------- template

TEMPLATE = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>{title} · ComfyPilot</title>
<meta name="description" content="ComfyPilot 官方{kind}。" />
<link rel="icon" href="assets/icon.png" />
<link rel="stylesheet" href="assets/style.css" />
</head>
<body>

<header class="site-header">
  <div class="wrap">
    <a class="brand" href="index.html"><img src="assets/icon.png" alt="" />ComfyPilot</a>
    <nav class="nav">
      <a href="index.html">首页</a>
      <a href="privacy.html"{p_active}>隐私政策</a>
      <a href="terms.html"{t_active}>用户协议</a>
      <a href="https://github.com/Fly-Noz/ComfyPilot" target="_blank" rel="noopener">GitHub</a>
    </nav>
  </div>
</header>

<main class="doc">
  <div class="wrap narrow">
    <div class="doc-header">
      <a class="back" href="index.html">← 返回首页</a>
      <h1>{title}</h1>
      <div class="meta">{meta}</div>
    </div>
    <article>
{body}
    </article>
  </div>
</main>

<footer class="site-footer">
  <div class="wrap">
    <div class="cols">
      <div>
        <a class="brand" href="index.html"><img src="assets/icon.png" alt="" />ComfyPilot</a>
        <p>开源的 ComfyUI 手机客户端 · Android / HarmonyOS<br />GPL-3.0 · 个人开发者</p>
      </div>
      <nav>
        <a href="index.html">首页</a>
        <a href="privacy.html">隐私政策</a>
        <a href="terms.html">用户协议</a>
        <a href="https://github.com/Fly-Noz/ComfyPilot" target="_blank" rel="noopener">GitHub</a>
        <a href="https://gitee.com/Fly-Noz/ComfyPilot" target="_blank" rel="noopener">Gitee</a>
      </nav>
    </div>
    <div class="legal">
      <span>© 2026 ComfyPilot</span>
      <span>联系：soaraboveclouds@163.com</span>
{icp_line}
    </div>
  </div>
</footer>

</body>
</html>
"""


def icp_block() -> str:
    if not ICP_NUMBER:
        return '      <!-- 备案通过后填入：<span><a href="' + ICP_LINK + '" target="_blank" rel="noopener">' + "ICP 备案号" + "</a></span> -->"
    return (
        f'      <span><a href="{ICP_LINK}" target="_blank" rel="noopener">{ICP_NUMBER}</a></span>'
    )


def main() -> None:
    jobs = [
        ("docs/release/privacy-policy.zh-CN.md", "site/privacy.html", "隐私政策", "隐私政策", ""),
        ("docs/release/user-agreement.zh-CN.md", "site/terms.html", "用户协议", "", ' aria-current="page"'),
    ]
    for src, dst, kind, p_active, t_active in jobs:
        md = (ROOT / src).read_text(encoding="utf-8")
        title, body, meta = convert(md)
        page = TEMPLATE.format(
            title=html.escape(title),
            kind=kind,
            body="      " + body.replace("\n", "\n      "),
            meta=html.escape(meta),
            p_active=p_active,
            t_active=t_active,
            icp_line=icp_block(),
        )
        (ROOT / dst).write_text(page, encoding="utf-8")
        print(f"{src} -> {dst}  ({len(page)} bytes)")


if __name__ == "__main__":
    main()
