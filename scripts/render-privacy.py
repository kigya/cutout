#!/usr/bin/env python3
"""Render the policy's small Markdown subset as a script-free GitHub Pages page."""
from html import escape
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def inline(text: str) -> str:
    return re.sub(r'\[([^\]]+)\]\((https://[^\s)]+)\)', r'<a href="\2">\1</a>', escape(text, quote=True))

def render(source: str) -> str:
    blocks = []
    for paragraph in source.strip().split('\n\n'):
        if paragraph.startswith('## '):
            blocks.append('<h2>' + inline(paragraph[3:]) + '</h2>')
        elif paragraph.startswith('# '):
            blocks.append('<h1>' + inline(paragraph[2:]) + '</h1>')
        else:
            blocks.append('<p>' + inline(paragraph.replace('\n', ' ')) + '</p>')
    return '''<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark"><meta name="description" content="How Cutout processes images, diagnostics and feedback on Android and iOS.">
<title>Cutout privacy policy</title>
<style>
:root{font:17px/1.65 system-ui,sans-serif;color:#17191f;background:#f5f6f8;color-scheme:light dark}
body{max-width:780px;margin:0 auto;padding:48px 24px 80px}header{color:#2259cf;font-weight:750;letter-spacing:.04em}h1{font-size:2.2rem;line-height:1.2}h2{margin-top:2.2em;line-height:1.3}a{color:#2259cf;text-underline-offset:.18em}a:focus-visible{outline:3px solid #2259cf;outline-offset:4px}footer{margin-top:48px;border-top:1px solid #ccd1dc;padding-top:24px}
@media(prefers-color-scheme:dark){:root{color:#f5f6f8;background:#101114}header,a{color:#8db1ff}footer{border-color:#363a44}}
</style></head><body><header>CUTOUT</header><main>\n''' + '\n'.join(blocks) + '''\n</main><footer><a href="https://github.com/kigya/cutout">Cutout on GitHub</a></footer></body></html>\n'''

if __name__ == '__main__':
    output = ROOT / 'privacy/index.html'
    output.parent.mkdir(exist_ok=True)
    output.write_text(render((ROOT / 'PRIVACY.md').read_text()))
