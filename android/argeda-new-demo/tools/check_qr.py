"""Проверяет QR-коды из assets/qrcode.js: матрицы сверяются с библиотекой qrcode (та же версия и маска),
а итоговые коды распознаются через OpenCV, как их увидит сканер на кассе.

Запуск: <python с пакетами qrcode, numpy, opencv-python-headless> tools/check_qr.py  (нужен node)
"""
import json
import subprocess
from pathlib import Path

import cv2
import numpy as np
import qrcode
from qrcode.util import QRData, MODE_8BIT_BYTE

JS = Path(__file__).resolve().parents[1] / "app/src/main/assets/qrcode.js"
TEXTS = ["A", "A-10482-7F3K", "R-411", "https://argeda.ru/", "Пушкино, ул. Лермонтова, 2",
         "A-410-TEST-QWERTYUIOPASDFGHJKLZXCVBNM-1234567890", "x" * 84, "y" * 106]

runner = f"""
global.window = {{}};
require({json.dumps(str(JS))});
const texts = {json.dumps(TEXTS, ensure_ascii=False)};
const rows = m => m.map(r => r.map(b => b ? 1 : 0).join(''));
const forced = [];
for (const t of texts) for (let mask = 0; mask < 8; mask++) {{
  const r = window.QR.qrMatrix(t, mask);
  forced.push({{ t, mask, v: r.version, m: rows(r.modules) }});
}}
const auto = texts.map(t => {{ const r = window.QR.qrMatrix(t); return {{ t, m: rows(r.modules) }}; }});
console.log(JSON.stringify({{ forced, auto }}));
"""
res = json.loads(subprocess.run(["node", "-e", runner], capture_output=True, text=True, check=True).stdout)

bad = 0
for r in res["forced"]:
    q = qrcode.QRCode(version=r["v"], error_correction=qrcode.constants.ERROR_CORRECT_M, border=0, mask_pattern=r["mask"])
    q.add_data(QRData(r["t"].encode("utf-8"), mode=MODE_8BIT_BYTE))
    q.make(fit=False)
    ref = ["".join("1" if b else "0" for b in row) for row in q.get_matrix()]
    if ref != r["m"]:
        bad += 1
        print("матрица не совпала:", r["t"][:30], "версия", r["v"], "маска", r["mask"])
print(f"сверено матриц: {len(res['forced'])}, расхождений: {bad}")

det = cv2.QRCodeDetector()
read_ok = 0
for r in res["auto"]:
    m = np.array([[0 if c == "1" else 255 for c in row] for row in r["m"]], dtype=np.uint8)
    img = cv2.resize(np.pad(m, 4, constant_values=255), None, fx=10, fy=10, interpolation=cv2.INTER_NEAREST)
    text, _, _ = det.detectAndDecode(img)
    ok = text == r["t"]
    read_ok += ok
    print("распознан" if ok else "НЕ распознан", repr(r["t"][:40]), "→", repr(text[:40]))
print(f"распознано: {read_ok} из {len(res['auto'])}")
