#!/usr/bin/env python3
"""Макет приложения ARGEDA в раскладке маркетплейса (как у WB), в цветах argeda.ru.
Собирает design/wb-style/wb-style.html — 4 экрана (.screen) для импорта в Figma через html_to_figma.
Цвета взяты с сайта argeda.ru: синий #1668D9, тёмно-синий #0B1630, голубой #7FD3F2.
"""
import base64
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
FONTS = ROOT / 'app' / 'src' / 'main' / 'assets' / 'fonts'
OUT = Path(__file__).resolve().parent / 'wb-style.html'


def uri(p):
    return 'data:font/woff2;base64,' + base64.b64encode(p.read_bytes()).decode()


FACES = ''.join(
    f"@font-face{{font-family:'{fam}';font-weight:{w};font-display:block;src:url({uri(FONTS / f)}) format('woff2')}}"
    for fam, files, ws in [('Onest', ['onest-cyrillic-0.woff2', 'onest-latin-1.woff2'], [400, 500, 600, 700]),
                           ('Unbounded', ['unbounded-cyrillic-2.woff2', 'unbounded-latin-3.woff2'], [700])]
    for f in files for w in ws)

# минимальный набор иконок (inline svg, stroke=currentColor)
I = {
 'home': '<path d="M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z"/>',
 'grid': '<rect x="4" y="4" width="6.5" height="6.5" rx="1.5"/><rect x="13.5" y="4" width="6.5" height="6.5" rx="1.5"/><rect x="4" y="13.5" width="6.5" height="6.5" rx="1.5"/><rect x="13.5" y="13.5" width="6.5" height="6.5" rx="1.5"/>',
 'wrench': '<path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94z"/>',
 'bag': '<path d="M6 2 3 6v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6l-3-4z"/><path d="M3 6h18M16 10a4 4 0 0 1-8 0"/>',
 'user': '<circle cx="12" cy="7" r="4"/><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/>',
 'heart': '<path d="M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7z"/>',
 'star': '<path d="m12 2 3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01z"/>',
 'check': '<path d="M20 6 9 17l-5-5"/>',
 'plus': '<path d="M12 5v14M5 12h14"/>',
 'minus': '<path d="M5 12h14"/>',
 'chev': '<path d="m9 18 6-6-6-6"/>',
 'bell': '<path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9M13.73 21a2 2 0 0 1-3.46 0"/>',
 'pin': '<path d="M20 10c0 6-8 12-8 12s-8-6-8-12a8 8 0 0 1 16 0z"/><circle cx="12" cy="10" r="3"/>',
 'cam': '<path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"/><circle cx="12" cy="13" r="4"/>',
 'search': '<circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/>',
 'truck': '<path d="M1 3h15v13H1zM16 8h4l3 3v5h-7"/><circle cx="5.5" cy="18.5" r="2.5"/><circle cx="18.5" cy="18.5" r="2.5"/>',
 'shield': '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>',
 'phone': '<path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.8 19.8 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.8 19.8 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72c.13.96.36 1.9.7 2.81a2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45c.9.34 1.85.57 2.81.7A2 2 0 0 1 22 16.92z"/>',
 'cog': '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>',
 'wallet': '<path d="M21 12V7H5a2 2 0 0 1 0-4h14v4"/><path d="M3 5v14a2 2 0 0 0 2 2h16v-5"/><path d="M18 12a2 2 0 0 0 0 4h4v-4z"/>',
 'card': '<rect x="2" y="5" width="20" height="14" rx="2"/><path d="M2 10h20"/>',
 'trash': '<path d="M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/>',
 'share': '<circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><path d="m8.6 13.5 6.8 4M15.4 6.5 8.6 10.5"/>',
 'home2': '<path d="M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z"/>',
 'bell2': '<path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9"/>',
 'dish': '<path d="M12 22a9 9 0 0 1-9-9M3 13a9 9 0 0 1 9-9"/><circle cx="12" cy="13" r="2"/><path d="m13.5 11.5 5-5"/>',
}


def ic(name, color='currentColor', size=24, sw=2, fill=False):
    return (f'<svg width="{size}" height="{size}" viewBox="0 0 24 24" fill="{"currentColor" if fill else "none"}" '
            f'stroke="currentColor" style="color:{color}" stroke-width="{sw}" stroke-linecap="round" stroke-linejoin="round">{I[name]}</svg>')


def dock(active):
    items = [('home', 'home', 'Главная'), ('grid', 'Каталог', 'Каталог'), ('wrench', 'Услуги', 'Услуги'), ('bag', 'Корзина', 'Корзина'), ('user', 'Профиль', 'Профиль')]
    out = []
    for icon, _, label in items:
        on = label == active
        badge = '<i class="dbadge">3</i>' if label == 'Корзина' else ''
        out.append(f'<div class="dk{" on" if on else ""}">{ic(icon, "#0B1630" if on else "#AEB8CC", 22)}<span>{label}</span>{badge}</div>')
    return f'<div class="dock">{"".join(out)}</div>'


def pcard(img, name, price, old, disc, avail, rating, fav=False):
    return f'''<div class="pc">
<div class="pc-img"><div class="ph"></div><div class="fv{' on' if fav else ''}">{ic('heart', '#E2501F' if fav else '#0E1726', 16, 2, fav)}</div>{f'<span class="disc">−{disc}%</span>' if disc else ''}</div>
<div class="av {avail[0]}">{avail[1]}</div>
<div class="rate">{ic('star', '#F5A623', 12, 0, True)}{rating[0]} <span>· {rating[1]}</span></div>
<div class="pc-name">{name}</div>
<div class="pc-row"><span class="pr">{price}&nbsp;₽{f'<s>{old}&nbsp;₽</s>' if old else ''}</span><button class="add">{ic('plus', '#fff', 18, 2.5)}</button></div>
</div>'''


PROD = [
    ('Аккумулятор для Samsung Galaxy A01 (A015F) Battery Collection', '529', '790', 33, ('today', 'Сегодня в пункте'), ('4,8', '27')),
    ('Дисплей для iPhone 11 (A2221) в сборе с тачскрином, Черный', '1 357', None, 0, ('today', 'Сегодня в пункте'), ('4,9', '54')),
    ('Пульт для всех телевизоров Samsung, с гарантией 1 год', '390', '560', 30, ('today', 'Сегодня в пункте'), ('4,7', '112')),
    ('Защитное стекло «Премиум» для Samsung Galaxy A01/M01', '92', '150', 38, ('pre', 'Под заказ · 2 дн.'), ('4,6', '31')),
]


def home():
    chips = ''.join(f'<div class="chip">{t}</div>' for t in ['Запчасти', 'Пульты', 'Ремонт', 'Видеонаблюдение', 'Домофоны'])
    cards = ''.join(pcard('', *p, fav=(i == 0)) for i, p in enumerate(PROD * 2))
    stories = ''.join(f'<div class="story"><div class="sph c{i}"></div><span>{t}</span></div>'
                      for i, t in enumerate(['Разбит экран', 'Видео-наблюдение', 'Триколор', 'Домофон', 'Скидки']))
    return f'''<div class="screen" data-name="1 · Главная">
<div class="statusbar"><span>9:41</span><span class="sbr"></span></div>
<div class="hdr">
  <div class="hrow"><div class="brand">{ic('home2', '#1668D9', 22)}<b>ARGEDA</b></div><div class="hpin">{ic('pin', '#1668D9', 15)}ул. Лермонтова, 2 {ic('chev', '#8A93A6', 14)}</div><div class="hbell">{ic('bell', '#0E1726', 20)}</div></div>
  <div class="search">{ic('search', '#8A93A6', 20)}<span>Товар, запчасть, услуга или модель</span><div class="scan">{ic('cam', '#7FD3F2', 18)}</div></div>
  <div class="chips">{chips}</div>
</div>
<div class="stories">{stories}</div>
<div class="grid">{cards}</div>
{dock('Главная')}
</div>'''


def profile():
    svc = ''.join(f'<div class="srv"><div class="srvic c{i}">{ic(ico, "#1668D9", 24)}</div><span>{t}</span></div>'
                  for i, (ico, t) in enumerate([('wrench', 'Ремонт'), ('dish', 'Спутник. ТВ'), ('cam', 'Видео'), ('bell2', 'Домофоны'), ('home2', 'Недвижи-мость')]))
    orders = ''.join(f'''<div class="prow"><div class="pic c{i}">{ic(ico, '#1668D9', 20)}</div><div class="pl"><div class="pt">{t}</div></div><div class="pe">{e}</div>{ic('chev', '#AEB8CC', 18)}</div>'''
                     for i, (ico, t, e) in enumerate([('bag', 'Мои заказы', '5'), ('heart', 'Избранное', '12'), ('star', 'Мои отзывы', '3'), ('pin', 'Пункт выдачи', '')]))
    return f'''<div class="screen" data-name="2 · Профиль">
<div class="statusbar"><span>9:41</span><span class="sbr"></span></div>
<div class="phead">
  <div class="pname">Артак {ic('chev', '#AEB8CC', 20)}</div>
  <div class="pbell">{ic('bell', '#0E1726', 22)}</div>
</div>
<div class="pquick">
  <div class="pq"><div class="pqic">{ic('user', '#1668D9', 22)}</div>Ваши данные</div>
  <div class="pq"><div class="pqic">{ic('card', '#1668D9', 22)}</div>Оплата</div>
  <div class="pq"><div class="pqic">{ic('cog', '#1668D9', 22)}</div>Настройки</div>
</div>
<div class="walrow">
  <div class="wal"><div class="ws">Баллы ARGEDA</div><div class="wv">0</div><button class="wbtn">Как копить</button></div>
  <div class="wal"><div class="ws">Активных заказов</div><div class="wv">1</div><div class="wsub">A-417 · собираем</div></div>
</div>
<div class="promoB">{ic('truck', '#fff', 26)}<div><b>Бесплатный самовывоз</b><span>6 пунктов в Пушкино · сегодня до 22:00</span></div></div>
<div class="sec">Сервисы ARGEDA</div>
<div class="srvrow">{svc}</div>
<div class="sec">Покупки</div>
<div class="plist">{orders}</div>
{dock('Профиль')}
</div>'''


def settings():
    def group(title, rows):
        r = ''.join(rows)
        return f'<div class="gtitle">{title}</div><div class="glist">{r}</div>'

    def row(ic_name, label, sub='', right='', toggle=None):
        rt = ''
        if toggle is not None:
            rt = f'<div class="tg{" on" if toggle else ""}"><i></i></div>'
        elif right:
            rt = f'<span class="rv">{right}</span>{ic("chev", "#AEB8CC", 18)}'
        else:
            rt = ic('chev', '#AEB8CC', 18)
        s = f'<div class="rsub">{sub}</div>' if sub else ''
        icn = f'<div class="ric">{ic(ic_name, "#1668D9", 20)}</div>' if ic_name else ''
        return f'<div class="srow">{icn}<div class="rl"><div class="rt">{label}</div>{s}</div>{rt}</div>'

    theme = '''<div class="gtitle">Приложение</div><div class="glist">
<div class="srow col"><div class="rl"><div class="rt">Тема оформления</div></div>
<div class="seg"><div class="son">Системная</div><div>Светлая</div><div>Тёмная</div></div></div>''' + \
        row('bell2', 'Вибрация', 'Телефон отзовётся на важные события', toggle=True) + '</div>'
    return f'''<div class="screen" data-name="3 · Настройки">
<div class="statusbar"><span>9:41</span><span class="sbr"></span></div>
<div class="shead">{ic('chev', '#1668D9', 22, 2).replace('m9 18 6-6-6-6', 'm15 18-6-6 6-6')}<span>Настройки</span><span style="width:24px"></span></div>
{group('Уведомления', [row('bell', 'Push-уведомления', 'Статусы заказов и записей', right='Вкл')])}
{group('Безопасность', [row('shield', 'Подтверждение заказов', 'Кодом или отпечатком', right='Выкл'), row('phone', 'Ваши устройства')])}
{group('Пункт и доставка', [row('pin', 'Пункт выдачи', '', right='Лермонтова, 2'), row('truck', 'Адрес для курьера')])}
{theme}
<div class="dbtns"><button class="db">Выйти из профиля</button><button class="db red">Удалить профиль</button></div>
</div>'''


def cart():
    def citem(img, name, price, old, disc, date, left, qty, sel=True):
        return f'''<div class="citem">
<div class="cck{' on' if sel else ''}">{ic('check', '#fff', 14, 3) if sel else ''}</div>
<div class="cimg"></div>
<div class="cmid">
  <div class="cpr">{price}&nbsp;₽{f'<s>{old}&nbsp;₽</s>' if old else ''}{f'<span class="cd">−{disc}%</span>' if disc else ''}</div>
  <div class="cn">{name}</div>
  <div class="cav">{date}</div>
  {f'<div class="clow">{left}</div>' if left else ''}
  <div class="cbot"><div class="step">{ic('minus', '#0E1726', 16)}<b>{qty}</b>{ic('plus', '#0E1726', 16)}</div><button class="cbuy">В корзине</button></div>
</div></div>'''
    items = (citem('', 'Аккумулятор для Samsung Galaxy A01 (A015F)', '529', '790', 33, 'Сегодня в пункте', '', '1') +
             citem('', 'Пульт для всех телевизоров Samsung', '390', '560', 30, 'Сегодня в пункте', 'Осталось 2 шт', '1') +
             citem('', 'Установка Триколор ТВ под ключ', '2 500', None, 0, 'Выезд мастера · по записи', '', '1', sel=False))
    return f'''<div class="screen" data-name="4 · Корзина">
<div class="statusbar"><span>9:41</span><span class="sbr"></span></div>
<div class="chead"><div class="pin2">{ic('pin', '#1668D9', 18)}Самовывоз · Лермонтова, 2 {ic('chev', '#8A93A6', 16)}</div></div>
<div class="call"><div class="cck on sm">{ic('check', '#fff', 13, 3)}</div>Выбрать всё<div class="cicons">{ic('heart', '#6A7487', 20)}{ic('trash', '#6A7487', 20)}</div></div>
<div class="clist">{items}</div>
<div class="cbar"><div class="cbl"><div class="cbs">2 товара · 919 ₽</div></div><button class="cgo">К оформлению · 919 ₽</button></div>
{dock('Корзина')}
</div>'''


CSS = '''
*{box-sizing:border-box;margin:0;padding:0}
body{background:#C9D2E0;font-family:'Onest',sans-serif;display:flex;flex-wrap:wrap;gap:40px;padding:40px}
.screen{width:393px;height:852px;background:#F2F5FA;position:relative;overflow:hidden;border-radius:0;color:#0E1726;display:flex;flex-direction:column}
.statusbar{height:44px;display:flex;align-items:center;justify-content:space-between;padding:0 24px;font:600 15px 'Onest';flex:none}
.statusbar .sbr{width:60px;height:14px;border-radius:7px;background:#0E1726;opacity:.85}
.dock{position:absolute;left:0;right:0;bottom:0;height:78px;background:#fff;border-top:1px solid #EEF2F7;display:flex;align-items:flex-start;padding-top:9px}
.dk{flex:1;display:flex;flex-direction:column;align-items:center;gap:4px;font:600 11px 'Onest';color:#AEB8CC;position:relative}
.dk.on{color:#0B1630}
.dbadge{position:absolute;top:-4px;left:calc(50% + 6px);min-width:17px;height:17px;padding:0 5px;border-radius:9px;background:#FF6A3D;color:#fff;font:700 10px/17px 'Onest';text-align:center}

/* Главная */
.hdr{background:#fff;padding:4px 16px 12px;flex:none}
.hrow{display:flex;align-items:center;gap:10px;height:40px}
.brand{display:flex;align-items:center;gap:7px;font:700 17px 'Unbounded'}
.hpin{margin-left:auto;display:flex;align-items:center;gap:4px;font:500 12px 'Onest';color:#26303F;white-space:nowrap}
.hbell{margin-left:4px}
.search{margin-top:10px;height:50px;border-radius:16px;background:#F2F5FA;display:flex;align-items:center;gap:10px;padding:0 6px 0 14px;color:#8A93A6;font:500 14px 'Onest'}
.search span{flex:1}
.scan{width:38px;height:38px;border-radius:12px;background:#0B1630;display:flex;align-items:center;justify-content:center}
.chips{display:flex;gap:8px;margin-top:12px;overflow:hidden}
.chip{height:34px;padding:0 14px;border-radius:11px;background:#F2F5FA;font:600 13px/34px 'Onest';white-space:nowrap}
.chip:first-child{background:#0B1630;color:#fff}
.stories{display:flex;gap:12px;padding:14px 16px 4px;overflow:hidden}
.story{display:flex;flex-direction:column;align-items:center;gap:6px;width:64px;flex:none}
.sph{width:60px;height:60px;border-radius:30px;background:linear-gradient(135deg,#2C7BEA,#0B1630)}
.sph.c1{background:linear-gradient(135deg,#7FD3F2,#1668D9)}.sph.c2{background:linear-gradient(135deg,#6E4BE8,#1668D9)}.sph.c3{background:linear-gradient(135deg,#12B76A,#0B8A50)}.sph.c4{background:linear-gradient(135deg,#FF6A3D,#C2410C)}
.story span{font:500 11px/1.15 'Onest';color:#26303F;text-align:center}
.grid{flex:1;overflow:hidden;display:grid;grid-template-columns:1fr 1fr;gap:10px;padding:12px 16px 90px;align-content:start}
.pc{background:#fff;border-radius:18px;padding:8px}
.pc-img{position:relative;height:150px;border-radius:12px;background:#F7F9FC;overflow:hidden}
.pc-img .ph{position:absolute;inset:14px;border-radius:8px;background:radial-gradient(circle at 50% 40%,#E6EBF3,#F7F9FC)}
.pc-img .fv{position:absolute;top:6px;right:6px;width:30px;height:30px;border-radius:15px;background:rgba(255,255,255,.92);display:flex;align-items:center;justify-content:center}
.disc{position:absolute;left:6px;top:6px;background:#FF6A3D;color:#fff;font:700 11px 'Onest';padding:3px 7px;border-radius:8px}
.av{margin-top:8px;font:600 11px 'Onest';display:flex;align-items:center;gap:5px}
.av:before{content:'';width:6px;height:6px;border-radius:3px}
.av.today{color:#0B8A50}.av.today:before{background:#12B76A}
.av.pre{color:#B25E00}.av.pre:before{background:#F59E0B}
.rate{margin-top:5px;font:600 12px 'Onest';color:#26303F;display:flex;align-items:center;gap:4px}
.rate span{color:#8A93A6;font-weight:500}
.pc-name{margin-top:5px;font:500 12px/1.3 'Onest';color:#26303F;height:46px;overflow:hidden}
.pc-row{display:flex;align-items:flex-end;justify-content:space-between;margin-top:4px}
.pr{font:700 16px 'Unbounded'}
.pr s{display:block;font:500 11px 'Onest';color:#8A93A6;margin-top:2px}
.add{width:34px;height:34px;border-radius:11px;background:#1668D9;display:flex;align-items:center;justify-content:center;border:0}

/* Профиль */
.phead{display:flex;align-items:center;justify-content:space-between;padding:6px 20px 10px}
.pname{font:700 24px 'Unbounded';display:flex;align-items:center;gap:6px}
.pquick{display:flex;gap:10px;padding:0 16px}
.pq{flex:1;background:#fff;border-radius:16px;padding:14px 6px;display:flex;flex-direction:column;align-items:center;gap:8px;font:600 12px 'Onest';text-align:center}
.pqic{width:44px;height:44px;border-radius:14px;background:#E8F1FD;display:flex;align-items:center;justify-content:center}
.walrow{display:flex;gap:10px;padding:12px 16px 0}
.wal{flex:1;background:#fff;border-radius:16px;padding:14px}
.ws{font:500 12px 'Onest';color:#6A7487}
.wv{font:700 24px 'Unbounded';margin-top:4px}
.wbtn{margin-top:8px;height:34px;padding:0 14px;border-radius:11px;background:#1668D9;color:#fff;border:0;font:600 13px 'Onest'}
.wsub{font:500 12px 'Onest';color:#0B8A50;margin-top:6px}
.promoB{margin:12px 16px 0;background:radial-gradient(120% 140% at 100% 0,#1E4FA8,#0B1630);border-radius:18px;padding:16px;display:flex;align-items:center;gap:12px;color:#fff}
.promoB b{font:700 15px 'Onest'}.promoB span{display:block;font:500 12px 'Onest';color:#AFC0DA;margin-top:3px}
.sec{font:700 17px 'Unbounded';padding:20px 20px 10px}
.srvrow{display:flex;gap:8px;padding:0 16px;overflow:hidden}
.srv{flex:1;display:flex;flex-direction:column;align-items:center;gap:7px;font:500 11px/1.15 'Onest';text-align:center}
.srvic{width:54px;height:54px;border-radius:17px;background:#E8F1FD;display:flex;align-items:center;justify-content:center}
.srvic.c1{background:#E7F8EF;color:#12B76A}.srvic.c2{background:#F0ECFE}.srvic.c3{background:#FFEDE7}.srvic.c4{background:#E8F1FD}
.plist{background:#fff;margin:0 16px;border-radius:18px;padding:4px 16px}
.prow{display:flex;align-items:center;gap:12px;padding:13px 0;font:600 15px 'Onest'}
.prow+.prow{border-top:1px solid #EEF2F7}
.pic{width:40px;height:40px;border-radius:13px;background:#E8F1FD;display:flex;align-items:center;justify-content:center;flex:none}
.pl{flex:1}
.pe{font:500 13px 'Onest';color:#8A93A6}

/* Настройки */
.shead{display:flex;align-items:center;justify-content:space-between;padding:6px 20px 12px;font:700 18px 'Unbounded'}
.shead span:nth-child(2){flex:1;text-align:center}
.gtitle{font:700 13px 'Onest';color:#6A7487;text-transform:uppercase;letter-spacing:.04em;padding:18px 20px 8px}
.glist{background:#fff;margin:0 16px;border-radius:18px;padding:2px 16px}
.srow{display:flex;align-items:center;gap:12px;padding:13px 0;min-height:56px}
.srow+.srow,.srow.col+.srow{border-top:1px solid #EEF2F7}
.ric{width:38px;height:38px;border-radius:12px;background:#E8F1FD;display:flex;align-items:center;justify-content:center;flex:none}
.rl{flex:1}.rt{font:600 15px 'Onest'}.rsub{font:500 12px 'Onest';color:#8A93A6;margin-top:2px}
.rv{font:500 14px 'Onest';color:#8A93A6;margin-right:6px}
.tg{width:48px;height:28px;border-radius:14px;background:#D5DCE7;position:relative;flex:none}
.tg.on{background:#1668D9}.tg i{position:absolute;top:3px;left:3px;width:22px;height:22px;border-radius:11px;background:#fff}.tg.on i{left:23px}
.srow.col{flex-direction:column;align-items:stretch;gap:10px}
.seg{height:44px;border-radius:13px;background:#F2F5FA;display:flex;padding:4px}
.seg div{flex:1;display:flex;align-items:center;justify-content:center;font:600 13px 'Onest';color:#6A7487;border-radius:10px}
.seg .son{background:#0B1630;color:#fff}
.dbtns{padding:22px 16px}
.db{width:100%;height:54px;border-radius:16px;background:#fff;border:0;font:600 15px 'Onest';color:#1668D9;margin-bottom:10px}
.db.red{color:#D92D20}

/* Корзина */
.chead{padding:6px 16px 8px}
.pin2{display:flex;align-items:center;gap:6px;font:600 14px 'Onest';background:#fff;height:46px;border-radius:14px;padding:0 14px}
.call{display:flex;align-items:center;gap:10px;padding:8px 20px;font:600 14px 'Onest'}
.cck{width:24px;height:24px;border-radius:8px;border:2px solid #C9D1DE;display:flex;align-items:center;justify-content:center;flex:none}
.cck.on{background:#1668D9;border-color:#1668D9}
.cck.sm{width:22px;height:22px}
.cicons{margin-left:auto;display:flex;gap:16px}
.clist{flex:1;overflow:hidden;padding:0 16px 170px}
.citem{background:#fff;border-radius:18px;padding:12px;display:flex;gap:10px;margin-bottom:10px}
.cimg{width:84px;height:84px;border-radius:14px;background:radial-gradient(circle at 50% 40%,#E6EBF3,#F7F9FC);flex:none}
.cmid{flex:1;min-width:0}
.cpr{font:700 17px 'Unbounded';display:flex;align-items:baseline;gap:7px}
.cpr s{font:500 12px 'Onest';color:#8A93A6}
.cd{font:700 11px 'Onest';color:#fff;background:#FF6A3D;padding:2px 6px;border-radius:7px}
.cn{font:500 13px/1.3 'Onest';color:#26303F;margin-top:5px;height:34px;overflow:hidden}
.cav{font:600 12px 'Onest';color:#0B8A50;margin-top:4px}
.clow{font:600 12px 'Onest';color:#D92D20;margin-top:3px}
.cbot{display:flex;align-items:center;justify-content:space-between;margin-top:10px}
.step{display:flex;align-items:center;gap:10px;height:36px;padding:0 10px;border-radius:12px;background:#F2F5FA;font:700 15px 'Onest'}
.cbuy{height:36px;padding:0 18px;border-radius:12px;background:#0B1630;color:#fff;border:0;font:700 13px 'Onest'}
.cbar{position:absolute;left:0;right:0;bottom:78px;background:#fff;box-shadow:0 -6px 24px rgba(14,23,38,.08);border-radius:22px 22px 0 0;padding:14px 16px;display:flex;align-items:center;gap:14px}
.cbs{font:500 13px 'Onest';color:#6A7487}
.cgo{flex:1;height:52px;border-radius:16px;background:#1668D9;color:#fff;border:0;font:700 16px 'Onest'}
'''

HTML = f'''<!doctype html><html lang="ru"><head><meta charset="utf-8"><title>ARGEDA — маркетплейс, макет</title>
<style>{FACES}{CSS}</style></head><body>
{home()}{profile()}{settings()}{cart()}
</body></html>'''

OUT.write_text(HTML, encoding='utf-8')
print(f'{OUT}: {OUT.stat().st_size // 1024} КБ')
