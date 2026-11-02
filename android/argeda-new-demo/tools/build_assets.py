"""Готовит общие файлы для assets: шрифты (woff2 локально) и icons.js из иконок макета.

Запуск: python3 tools/build_assets.py ../../design/app-v1/src
"""
import json
import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"
SRC = Path(sys.argv[1])
DESIGN = ROOT.parents[1] / "design/app-v1/build_design.py"

# Шрифты: только кириллица и латиница, файлы кладём рядом, без base64
css = (SRC / "fonts/fonts.css").read_text()
faces, seen = [], {}
for subset, face in re.findall(r"/\* ([a-z-]+) \*/\s*(@font-face \{.*?\})", css, re.S):
    if subset not in ("cyrillic", "latin"):
        continue
    url = re.search(r"url\((https://[^)]+)\)", face).group(1)
    if url not in seen:
        family = re.search(r"font-family: '([^']+)'", face).group(1).lower()
        name = f"{family}-{subset}-{len(seen)}.woff2"
        (ASSETS / "fonts" / name).write_bytes(urllib.request.urlopen(url).read())
        seen[url] = name
    faces.append(face.replace(url, "fonts/" + seen[url]).replace("font-display: swap;", "font-display: block;"))
(ASSETS / "fonts.css").write_text("\n".join(faces) + "\n")

# Иконки берём из макета, чтобы приложение и Figma совпадали
src = DESIGN.read_text()
start = src.index("P = {")
end = src.index("\n}\n", start) + 3
ns = {}
exec(src[start:end], ns)
(ASSETS / "icons.js").write_text("// Сгенерировано tools/build_assets.py из макета — не править вручную\n"
                                 "window.ICONS = " + json.dumps(ns["P"], ensure_ascii=False, indent=0) + ";\n")
print("fonts:", len(seen), "faces:", len(faces), "icons:", len(ns["P"]))

# QR-код для выдачи заказа строит сам app/src/main/assets/qrcode.js (проверка: tools/check_qr.py)
