#!/usr/bin/env python3
"""Баннеры главной: фоны для приложения и макет для Figma.

Фоны сгенерированы в Figma и лежат в design/banners/src. Там же, в Figma,
баннеры собраны редактируемыми слоями:
https://www.figma.com/design/TVBqluczHoapdZ1aGJu4uk Скрипт ужимает их для приложения (assets/img/banners) и
собирает design/banners/banners.html — те же баннеры с текстом, чтобы
загрузить их в Figma редактируемыми слоями.

Текст и цены в приложении берутся с сервера при показе; здесь они нужны
только для макета.

Запуск: python3 tools/build_banners.py
"""
import base64
import html
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / 'design' / 'banners' / 'src'
OUT_APP = ROOT / 'app' / 'src' / 'main' / 'assets' / 'img' / 'banners'
OUT_HTML = ROOT / 'design' / 'banners' / 'banners.html'
FONTS = ROOT / 'app' / 'src' / 'main' / 'assets' / 'fonts'

# Порядок и тексты — как в приложении (app.js, BANNERS)
BANNERS = [
    ('screen', 'Ремонт', 'Разбит экран?', 'Замена от 40 минут · от 1 500 ₽'),
    ('battery', 'Ремонт', 'Быстро садится?', 'Замена аккумулятора от 30 минут · от 1 500 ₽'),
    ('remotes', 'Каталог', 'Пульты для любого ТВ', '7 196 моделей в каталоге'),
    ('photo', 'Запчасти', 'Найдём деталь по фото', 'Сфотографируйте — подберём замену'),
    ('pickup', 'Самовывоз', 'Заберите сегодня', 'ул. Лермонтова, 2 · до 20:00 · бесплатно'),
    ('sellers', 'Продавцам', 'Продавайте на ARGEDA', 'Свой магазин и пункты выдачи в Пушкино'),
]
APP_WIDTH = 960
PETAL = 'M0,-1C-4,-6 -3,-13 3,-16C8,-18.5 13,-15 12.5,-10.5C9,-13 4.5,-11.5 3.5,-7C3,-4.5 2,-2 0,-1Z'
PICKUP_DECO = ('<svg class="wm" width="300" height="300" viewBox="-20 -20 40 40">'
               + ''.join(f'<path fill="#fff" transform="rotate({a})" d="{PETAL}"/>' for a in range(0, 360, 60)) + '</svg>'
               '<span class="pin"><svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">'
               '<path d="M20 10c0 6-8 12-8 12s-8-6-8-12a8 8 0 0 1 16 0z"/><circle cx="12" cy="10" r="3"/></svg></span>')


def build_app_images():
    OUT_APP.mkdir(parents=True, exist_ok=True)
    for src in sorted(SRC.glob('*.jpg')):
        im = Image.open(src).convert('RGB')
        h = round(im.height * APP_WIDTH / im.width)
        out = OUT_APP / src.name
        im.resize((APP_WIDTH, h), Image.LANCZOS).save(out, quality=78, optimize=True, progressive=True)
        print(f'{out.relative_to(ROOT)}: {out.stat().st_size // 1024} КБ')


def data_uri(path, mime):
    return f'data:{mime};base64,' + base64.b64encode(path.read_bytes()).decode()


def build_figma_html():
    faces = ''.join(
        f"@font-face{{font-family:'{fam}';font-weight:{w};src:url({data_uri(FONTS / f, 'font/woff2')}) format('woff2');}}"
        for fam, files, weights in [('Onest', ['onest-cyrillic-0.woff2', 'onest-latin-1.woff2'], [500, 600]),
                                    ('Unbounded', ['unbounded-cyrillic-2.woff2', 'unbounded-latin-3.woff2'], [700])]
        for f in files for w in weights)
    cards = []
    for key, kicker, title, sub in BANNERS:
        src = SRC / f'{key}.jpg'
        bg = f"url({data_uri(src, 'image/jpeg')}) right center / cover" if src.exists() else 'radial-gradient(120% 140% at 100% 0%, #3E8BF0 0%, #1668D9 38%, #0B1630 100%)'
        # У самовывоза нет фото: вместо него фирменная вертушка и значок места
        deco = '' if src.exists() else PICKUP_DECO
        cards.append(f'''<div class="bn" data-name="Баннер · {html.escape(title)}" style="background:linear-gradient(90deg, rgba(11,22,48,.96) 0%, rgba(11,22,48,.78) 46%, rgba(11,22,48,0) 78%), {bg}">{deco}
<span class="k">{html.escape(kicker)}</span><div class="h">{html.escape(title)}</div><div class="p">{html.escape(sub)}</div></div>''')
    OUT_HTML.write_text(f'''<!doctype html><html lang="ru"><head><meta charset="utf-8"><title>ARGEDA — баннеры</title>
<style>{faces}
body{{margin:0;padding:40px;background:#F2F5FA;display:flex;flex-direction:column;gap:32px;font-family:'Onest',sans-serif}}
.bn{{width:680px;height:288px;border-radius:48px;padding:40px;box-sizing:border-box;color:#fff;display:flex;flex-direction:column;justify-content:center;align-items:flex-start;background-color:#0B1630}}
.bn{{position:relative;overflow:hidden}}
.wm{{position:absolute;right:-40px;top:-6px;opacity:.12}}
.pin{{position:absolute;right:86px;top:84px;width:120px;height:120px;border-radius:60px;background:rgba(255,255,255,.14);display:flex;align-items:center;justify-content:center}}
.k{{position:relative;display:inline-block;padding:10px 22px;border-radius:28px;background:rgba(255,255,255,.14);font:600 22px 'Onest'}}
.h{{position:relative;margin-top:20px;max-width:390px;font:700 38px/1.18 'Unbounded'}}
.p{{position:relative;margin-top:12px;max-width:390px;font:600 24px/1.3 'Onest';color:#7FD3F2}}
</style></head><body>
{chr(10).join(cards)}
</body></html>''', encoding='utf-8')
    print(f'{OUT_HTML.relative_to(ROOT)}: {OUT_HTML.stat().st_size // 1024} КБ')


if __name__ == '__main__':
    build_app_images()
    build_figma_html()
