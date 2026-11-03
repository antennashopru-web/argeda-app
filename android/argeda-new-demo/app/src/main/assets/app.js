'use strict';
// ARGEDA — приложение маркета и мастерской в Пушкино. Каталог, цены, заказы и запись на ремонт —
// с сервера argeda.ru (/api/app/v1). Последние ответы сохраняются на телефоне, чтобы без сети
// открывались главная, разделы, товары, избранное и заказы; плашка «Нет связи» уходит сама, когда сеть вернулась.

const A = window.Android || null;   // мост с Android; в обычном браузере его нет
const API = 'https://argeda.ru/api/app/v1';
const SITE = 'https://argeda.ru/';
const VERSION = '1.15';
const VERSION_CODE = 16;
const TEST_BUILD = false;  // боевая сборка: заказы уходят в магазин, демо-оценок и тестового режима нет. true — только для сборки разработчика
const DEF = { phone: '+79939000077', pickup: { address: 'Пушкино, ул. Лермонтова, 2', hours: '10:00–20:00' }, consent: { version: '2026-10-12', url: 'https://argeda.ru/privacy' }, features: { courier: true } };
// Пункты выдачи — запас на случай, если /config не ответил. Основной список (id, адрес, часы) — pickup_points из /config; id совпадают с сервером
const DEF_POINTS = [
  { id: 'lermontova-2', name: 'на Лермонтова', address: 'Пушкино, ул. Лермонтова, 2', hours: '10:00–20:00', workshop: true },
  { id: 'prosvescheniya-13-k2', name: 'на Просвещения', address: 'Пушкино, ул. Просвещения, 13 к2', hours: '10:00–20:00' },
  { id: 'antona-vetchinova-4', name: 'на Антона Ветчинова', address: 'Пушкино, ул. Антона Ветчинова, 4, подъезд 5', hours: '10:00–20:00' },
  { id: 'chehova-33', name: 'на Чехова', address: 'Пушкино, ул. Чехова, 33', hours: '10:00–20:00' },
  { id: 'vokzalnaya-10', name: 'на Вокзальной', address: 'Пушкино, ул. Вокзальная, 10', hours: '10:00–22:00' },
  { id: 'naberezhnaya-35-k2', name: 'на Набережной', address: 'Пушкино, ул. Набережная, 35 к2, мкр Новая Деревня', hours: '10:00–22:00' },
];
// Цвета значков берутся из темы (app.css), поэтому одинаково работают в светлой и тёмной
const BLUE = 'var(--blue)', SKY = '#7FD3F2', INK = 'var(--ink)', GREEN = 'var(--ok-text)', VIOLET = 'var(--violet)', CORAL = 'var(--coral-ic)', GREY = 'var(--muted-ic)';
const MIN = 60 * 1000, HOUR = 60 * MIN, DAY = 24 * HOUR;

// ---------- Состояние (хранится на телефоне) ----------
const KEY = 'argeda.app.v2';
const load = k => { try { return JSON.parse(localStorage.getItem(k)) || null; } catch (e) { return null; } };
const old = load('argeda.app.v1') || {};
const S = Object.assign({ cart: [], fav: [], snap: {}, orders: [], delivery: 'pickup', courierAddr: '', phone: old.phone || '', name: '', email: '', model: old.model || '', token: '', user: null, test: TEST_BUILD, payKey: '', hist: [], theme: 'auto', point: '', unsel: [], vibro: true, pay: 'auto', pendFav: [] }, load(KEY) || {});
if (!TEST_BUILD) S.test = false;
const ui = { catTab: 'goods', prob: 'battery', svc: null, day: 0, time: null, install: {}, query: '', sort: 'popular', gallery: 0, descOpen: false, flt: { model: false, today: false, pmin: '', pmax: '', brand: [] }, searchTab: 'all', fltCtx: '' };
function save() { try { localStorage.setItem(KEY, JSON.stringify(S)); } catch (e) { /* без хранилища работаем в памяти */ } }

// ---------- Утилиты ----------
const $ = (s, r = document) => r.querySelector(s);
const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const money = n => n == null ? '—' : Math.round(n).toLocaleString('ru-RU') + ' ₽';
const digits = s => s.replace(/\D/g, '').replace(/^[78]/, '').slice(0, 10);
const maskPhone = s => { const d = digits(s); let r = '+7'; if (d.length) r += ' (' + d.slice(0, 3); if (d.length >= 3) r += ') ' + d.slice(3, 6); if (d.length >= 6) r += '-' + d.slice(6, 8); if (d.length >= 8) r += '-' + d.slice(8, 10); return r; };
const e164 = s => '+7' + digits(s);
const fmtPhone = p => maskPhone(p || '');
const MON = ['янв', 'фев', 'мар', 'апр', 'мая', 'июн', 'июл', 'авг', 'сен', 'окт', 'ноя', 'дек'];
const WD = ['Вс', 'Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб'];
const iso = d => d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
function fmtWhen(at) {
  if (!at) return '';
  const d = new Date(at), today = new Date(); today.setHours(0, 0, 0, 0);
  const day = new Date(d); day.setHours(0, 0, 0, 0);
  const diff = Math.round((day - today) / 86400000);
  const t = d.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
  return (diff === 0 ? 'Сегодня' : diff === -1 ? 'Вчера' : d.getDate() + ' ' + MON[d.getMonth()]) + ', ' + t;
}
function fmtDay(dateIso) {
  const [y, m, d] = dateIso.split('-').map(Number), x = new Date(y, m - 1, d), t = new Date(); t.setHours(0, 0, 0, 0);
  const diff = Math.round((x - t) / 86400000);
  return diff === 0 ? 'сегодня' : diff === 1 ? 'завтра' : WD[x.getDay()].toLowerCase() + ', ' + d + ' ' + MON[m - 1];
}
const uid = () => 'app-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
const plural = (n, a, b, c) => { const m = n % 10, h = n % 100; return m === 1 && h !== 11 ? a : m >= 2 && m <= 4 && (h < 10 || h >= 20) ? b : c; };

function ic(name, color = INK, size = 24, sw = 2, fill = false) {
  return `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="${fill ? 'currentColor' : 'none'}" stroke="currentColor" style="color:${color}" stroke-width="${sw}" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICONS[name]}</svg>`;
}
const heart = (on, size = 18) => ic('heart', on ? CORAL : 'currentColor', size, 2, on);
// Рейтинг звёздами: пять иконок, дробная часть — закрашенная доля последней звезды
function stars(value, size = 14) {
  const v = Math.max(0, Math.min(5, +value || 0));
  let s = `<span class="stars" aria-label="Оценка ${String(Math.round(v * 10) / 10).replace('.', ',')} из 5">`;
  for (let i = 1; i <= 5; i++) {
    const pct = Math.max(0, Math.min(1, v - (i - 1))) * 100;
    s += `<span class="star" style="width:${size}px;height:${size}px"><span class="s-bg">${ic('star', '#D7DEEA', size, 0, true)}</span><span class="s-fg" style="width:${pct}%">${ic('star', '#F5A623', size, 0, true)}</span></span>`;
  }
  return s + '</span>';
}
const PETAL = 'M0,-1C-4,-6 -3,-13 3,-16C8,-18.5 13,-15 12.5,-10.5C9,-13 4.5,-11.5 3.5,-7C3,-4.5 2,-2 0,-1Z';
const petals = fill => [0, 60, 120, 180, 240, 300].map(a => `<path fill="${fill}" transform="rotate(${a})" d="${PETAL}"/>`).join('');
const logo = size => `<svg width="${size}" height="${size}" viewBox="0 0 48 48"><defs><linearGradient id="lg${size}" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#7FD3F2"/><stop offset="1" stop-color="#1668D9"/></linearGradient></defs><rect width="48" height="48" rx="13" fill="url(#lg${size})"/><g transform="translate(24 24) scale(.95)">${petals('#fff')}</g><circle cx="24" cy="24" r="2.2" fill="#1668D9"/></svg>`;
const pinwheel = size => `<svg width="${size}" height="${size}" viewBox="-20 -20 40 40">${petals('#fff')}</svg>`;
const img = (src, alt = '') => src ? `<img src="${esc(src)}" alt="${esc(alt)}" loading="lazy" onerror="this.classList.add('broken')">` : `<span class="noimg">${ic('bag', '#C5CEDC', 34)}</span>`;

// ---------- Сервер ----------
async function api(path, opts = {}) {
  const headers = { Accept: 'application/json' };
  if (opts.body) headers['Content-Type'] = 'application/json';
  if (S.test) headers['X-Argeda-Test'] = '1';
  if (S.token) headers.Authorization = 'Bearer ' + S.token;
  Object.assign(headers, opts.headers || {});
  const ctrl = new AbortController(), timer = setTimeout(() => ctrl.abort(), opts.timeout || 20000);
  let r;
  try {
    r = await fetch(API + path, { method: opts.method || (opts.body ? 'POST' : 'GET'), headers, body: opts.body ? JSON.stringify(opts.body) : undefined, signal: ctrl.signal });
  } catch (e) {
    wentOffline();
    const err = new Error('Нет связи с сервером. Проверьте интернет и попробуйте ещё раз.'); err.offline = true; throw err;
  } finally { clearTimeout(timer); }
  cameOnline();
  const data = await r.json().catch(() => null);
  if (r.status === 401 && S.token) { S.token = ''; S.user = null; save(); }
  if (!r.ok) {
    const err = new Error(data && data.error && data.error.message || 'Сервер ответил ошибкой ' + r.status);
    err.code = data && data.error && data.error.code; err.status = r.status; throw err;
  }
  return data;
}

// ---------- Связь ----------
// Пока сервера нет (нет интернета или он лежит) — плашка сверху, экраны живут сохранённым.
// Связь вернулась (ответил любой запрос, событие online или удачная проверка) — плашка уходит, данные обновляются,
// отложенные действия (избранное, корзина) досылаются.
const NET = { online: !(A && A.isOnline && !A.isOnline()) && navigator.onLine !== false, timer: null };
function showNet() { const n = $('#net'); if (n) n.classList.toggle('show', !NET.online); }
function wentOffline() {
  if (!NET.online) return;
  NET.online = false; showNet();
  clearInterval(NET.timer);
  NET.timer = setInterval(netPing, 20000); // раз в 20 с тихо пробуем сервер
}
function cameOnline() {
  if (NET.online) return;
  NET.online = true; showNet();
  clearInterval(NET.timer); NET.timer = null;
  Object.keys(ERR).forEach(k => { delete ERR[k]; });
  Object.keys(MEM).forEach(k => { MEM[k].at = 0; }); // всё сохранённое считаем устаревшим — экраны подтянут свежее
  flushPending();
  refresh();
}
function netPing() { api('/config', { timeout: 8000 }).then(d => { if (d) { MEM.config = { data: d, at: Date.now() }; } }).catch(() => {}); }
window.addEventListener('offline', wentOffline);
window.addEventListener('online', () => { netPing(); });
// Оформление, запись и оплата без сети невозможны — говорим сразу, а не после ошибки
function needNet(what) { if (NET.online) return false; toast((what || 'Это') + ' получится, когда появится интернет'); return true; }

// Данные для экранов: отдаём то, что уже есть (из памяти или сохранённое на телефоне),
// а свежие подгружаем; когда пришли — перерисовываем экран, если он их показывает
const MEM = {}, LOADING = new Set(), ERR = {};
const DISK_KEY = 'argeda.cache.v2', DISK = load(DISK_KEY) || {};
let used = new Set();
function need(key, path, ttl, persist) {
  used.add(key);
  let m = MEM[key];
  if (!m && persist && DISK[key]) m = MEM[key] = { data: DISK[key].data, at: 0 };
  const fresh = m && Date.now() - m.at < ttl;
  const failedRecently = ERR[key] && Date.now() - ERR[key].at < 15000;
  if (!fresh && !LOADING.has(key) && !failedRecently) {
    LOADING.add(key);
    api(path).then(d => {
      MEM[key] = { data: d, at: Date.now() }; delete ERR[key];
      if (persist) { DISK[key] = { data: d, at: Date.now() }; saveDisk(); }
    }).catch(e => { ERR[key] = { e, at: Date.now() }; })
      .finally(() => { LOADING.delete(key); if (used.has(key)) refresh(); });
  }
  return m ? m.data : null;
}
// На телефоне держим не больше 120 ответов — старые уходят первыми
function saveDisk() {
  const keys = Object.keys(DISK);
  if (keys.length > 120) keys.sort((a, b) => (DISK[a].at || 0) - (DISK[b].at || 0)).slice(0, keys.length - 120).forEach(k => { delete DISK[k]; });
  try { localStorage.setItem(DISK_KEY, JSON.stringify(DISK)); } catch (e) { /* место кончилось — живём без запаса */ }
}
const loading = key => LOADING.has(key);
const errText = key => ERR[key] ? ERR[key].e.message : '';
function forget(prefix) { Object.keys(MEM).forEach(k => { if (k.startsWith(prefix)) delete MEM[k]; }); }

const cfg = () => {
  const c = need('config', '/config', HOUR, true) || {};
  return { phone: c.phone || DEF.phone, pickup: Object.assign({}, DEF.pickup, c.pickup || {}), consent: c.consent || DEF.consent,
    features: Object.assign({}, DEF.features, c.features || {}), min_version: c.min_version || 1, apk_url: c.apk_url,
    // Появятся, когда их начнёт отдавать сервер: рейтинг мастерской, гарантия на ремонт, срок курьера
    rating: c.rating || null, repair_warranty: c.repair_warranty || '', courier: c.courier || null,
    // Свои пункты с сервера; иначе — список с сайта, а координаты главного пункта — из pickup
    points: c.pickup_points && c.pickup_points.length ? c.pickup_points
      : DEF_POINTS.map(p => p.workshop && c.pickup ? Object.assign({}, p, { lat: c.pickup.lat, lon: c.pickup.lon }) : p) };
};
const short = a => String(a || '').replace(/^(г\.\s*)?Пушкино,\s*/, '');
const closeOf = p => 'до ' + (String(p.hours || '').split(/[–-]/)[1] || '20:00').trim();
// Пункт, куда покупатель забирает заказы (выбирает сам), и пункт с мастерской
const points = () => cfg().points;
const curPoint = () => points().find(p => p.id === S.point) || points()[0];
// Старые id пунктов (до 1.10) серверу неизвестны — сбрасываем выбор, чтобы заказ не получил bad_pickup
if (S.point && !DEF_POINTS.some(p => p.id === S.point)) { S.point = ''; save(); }
const workshop = () => points().find(p => p.workshop) || points()[0];
const closeAt = () => closeOf(curPoint());
const shortAddr = () => short(curPoint().address);
const workshopAddr = () => short(workshop().address);
const latestClose = () => points().reduce((m, p) => closeOf(p) > m ? closeOf(p) : m, 'до 00:00');
const cats = () => need('cats', '/catalog/categories', HOUR, true) || [];
// Сколько всего товаров в продаже — для подсказки в поиске каталога
const allCount = () => { const d = need('all', '/catalog/products?limit=1', HOUR, true); return d ? d.total : 0; };
const catById = id => cats().find(c => c.id === id);

// Короткие карточки товаров запоминаем, чтобы карточка товара открывалась сразу
const SHORT = {};
const remember = items => (items || []).forEach(p => { SHORT[p.id] = p; });
const prod = id => (MEM['p:' + id] && MEM['p:' + id].data) || SHORT[id] || S.snap[id] || null;
function snap(id) {
  const p = prod(id);
  if (p) S.snap[id] = { id: p.id, name: p.name, slug: p.slug, url: p.url, seller: p.seller, price: p.price, old_price: p.old_price, image: p.image || (p.images && p.images[0]), in_stock: p.in_stock, availability: p.availability, preorder_days: p.preorder_days, install_service_id: p.install_service_id, category_id: p.category_id, fits: p.fits };
}
function pruneSnaps() {
  const keep = new Set(S.fav.concat(S.cart.filter(l => l.kind === 'p').map(l => l.id)));
  Object.keys(S.snap).forEach(id => { if (!keep.has(id)) delete S.snap[id]; });
}

// Постраничные списки товаров: раздел, поиск
// Первая страница последних 30 списков хранится на телефоне: без сети раздел открывается с товарами
const LISTS = {}, LD_KEY = 'argeda.lists.v1', LD = load(LD_KEY) || {};
function listOf(key, query) {
  let L = LISTS[key];
  if (!L) {
    L = LISTS[key] = { query, items: [], total: null, page: 0, loading: false, done: false, err: '' };
    const c = LD[key];
    if (c && c.items && c.items.length) { L.items = c.items; L.total = c.total; L.fromCache = true; remember(c.items); }
  }
  return L;
}
function loadMore(key) {
  const L = LISTS[key];
  if (!L || L.loading || L.done) return;
  L.loading = true; L.err = '';
  api(`/catalog/products?${L.query}&page=${L.page + 1}&limit=40`).then(d => {
    remember(d.items);
    const page = d.page || L.page + 1;
    L.items = L.fromCache ? (d.items || []) : L.items.concat(d.items || []); L.fromCache = false; L.total = d.total; L.page = page;
    L.done = !d.items || !d.items.length || L.items.length >= d.total;
    if (page === 1) {
      LD[key] = { items: L.items.slice(0, 40), total: L.total, at: Date.now() };
      const keys = Object.keys(LD);
      if (keys.length > 30) keys.sort((a, b) => (LD[a].at || 0) - (LD[b].at || 0)).slice(0, keys.length - 30).forEach(k => { delete LD[k]; });
      try { localStorage.setItem(LD_KEY, JSON.stringify(LD)); } catch (e) { /* без запаса */ }
    }
  }).catch(e => { L.err = e.message; }).finally(() => {
    L.loading = false;
    if (LISTS.active !== key) return;
    // На экране поиска поле ввода в фокусе — обновляем только результаты
    if (stack[stack.length - 1].name === 'search' && searchRender) searchRender(); else refresh();
  });
}

// ---------- Корзина ----------
const cartCount = () => S.cart.reduce((n, l) => n + (l.kind === 'p' ? l.qty : 1), 0);
const inCart = id => S.cart.some(l => l.kind === 'p' && l.id === id);
// Выбор товаров галочками: к оформлению идут только отмеченные (по умолчанию все)
const isSel = id => !S.unsel.includes(id);
const selLines = () => S.cart.filter(l => l.kind === 'p' && isSel(l.id));
const cartBody = () => ({
  lines: S.cart.filter(l => l.kind === 'p' && isSel(l.id)).map(l => ({ product_id: l.id, qty: l.qty })),
  services: S.cart.filter(l => l.kind === 's' && isSel(l.for)).map(l => ({ service_id: l.service_id, for_product_id: l.for })),
  delivery: S.delivery === 'courier' ? { type: 'courier', address: S.courierAddr.trim() } : { type: 'pickup', point_id: curPoint().id, address: curPoint().address },
});
function addToCart(id, withInstall) {
  snap(id); buzz();
  const line = S.cart.find(l => l.kind === 'p' && l.id === id);
  if (line) line.qty++; else S.cart.push({ kind: 'p', id, qty: 1 });
  const svc = withInstall && installService(id);
  if (svc && !S.cart.some(l => l.kind === 's' && l.for === id)) S.cart.push({ kind: 's', for: id, service_id: svc.id, name: svc.name, price: svc.price_from });
  S.payKey = ''; save(); syncCart();
}
function installService(id) {
  const p = prod(id);
  if (!p || !p.install_service_id) return null;
  const rs = need('rs:', '/repair/services', 30 * MIN, true);
  return rs && rs.services.find(s => s.id === p.install_service_id) || null;
}
// Корзину пересчитывает сервер: цены, наличие и итог — только его
function quoteFor() {
  const sig = JSON.stringify(cartBody());
  const q = MEM.quote;
  if (q && q.sig === sig && Date.now() - q.at < MIN) return q;
  if (!LOADING.has('quote') && cartBody().lines.length) {
    LOADING.add('quote');
    api('/orders/quote', { body: JSON.parse(sig) })
      .then(d => { MEM.quote = { sig, data: d, at: Date.now() }; delete ERR.quote; })
      .catch(e => { ERR.quote = { e, at: Date.now() }; })
      .finally(() => { LOADING.delete('quote'); if (stack[stack.length - 1].name === 'cart') refresh(); });
  }
  return q && q.sig === sig ? q : null;
}

// Цена курьера для плитки «Курьер»: тот же расчёт корзины, но с доставкой. null — пока не знаем
function courierPrice() {
  if (!cfg().features.courier || !cartBody().lines.length) return null;
  const body = Object.assign(cartBody(), { delivery: { type: 'courier' } }), sig = JSON.stringify(body);
  const q = MEM.cq;
  if (q && q.sig === sig) return q.price;
  if (!LOADING.has('cq') && !(ERR.cq && ERR.cq.sig === sig)) {
    LOADING.add('cq');
    api('/orders/quote', { body })
      .then(d => { MEM.cq = { sig, price: d.delivery && d.delivery.price != null ? d.delivery.price : d.totals ? d.totals.delivery : null }; })
      .catch(e => { ERR.cq = { e, sig, at: Date.now() }; })
      .finally(() => { LOADING.delete('cq'); if (stack[stack.length - 1].name === 'cart') refresh(); });
  }
  return q ? q.price : null;
}

// ---------- Общие корзина и избранное с сайтом (после входа) ----------
// Корзина на сервере — одна на сайт и приложение: PUT /cart заменяет её целиком
let cartPush = null;
const cartLines = () => S.cart.filter(l => l.kind === 'p').map(l => ({ product_id: l.id, qty: l.qty }));
function syncCart() {
  if (!S.token) return;
  clearTimeout(cartPush);
  cartPush = setTimeout(() => { api('/cart', { method: 'PUT', body: { lines: cartLines() } }).catch(() => { /* повторим при следующем изменении */ }); }, 800);
}
// Избранное на сервере: без сети действие ждёт в очереди и уходит, когда связь вернётся
function favAdd(id) { favSend({ op: 'add', id }); }
function favDel(id) { favSend({ op: 'del', id }); }
function favSend(x) {
  if (!S.token) return;
  const p = x.op === 'add' ? api('/favorites', { body: { product_id: x.id } }) : api('/favorites?product_id=' + encodeURIComponent(x.id), { method: 'DELETE' });
  p.catch(e => { if (e.offline) { S.pendFav = (S.pendFav || []).filter(y => y.id !== x.id).concat([x]); save(); } });
}
function flushPending() {
  if (!S.token) return;
  const q = S.pendFav || [];
  if (q.length) { S.pendFav = []; save(); q.forEach(favSend); }
  if (S.cart.some(l => l.kind === 'p')) syncCart();
}
// merge=true после входа: избранное и корзина телефона сливаются с сайтом; иначе сайт — источник истины
async function pullShared(merge) {
  if (!S.token) return;
  try {
    if (merge && S.fav.length) await api('/favorites', { body: { ids: S.fav } });
    const f = await api('/favorites');
    if (f && Array.isArray(f.ids)) S.fav = f.ids.map(String);
    const c = await api('/cart'), srv = (c && c.lines) || [];
    if (merge) {
      srv.forEach(l => { const line = S.cart.find(x => x.kind === 'p' && x.id === l.product_id); if (line) line.qty = Math.max(line.qty, l.qty || 1); else S.cart.push({ kind: 'p', id: l.product_id, qty: l.qty || 1 }); });
      await api('/cart', { method: 'PUT', body: { lines: cartLines() } });
    } else {
      const ids = new Set(srv.map(l => l.product_id));
      S.cart = S.cart.filter(l => (l.kind === 'p' && ids.has(l.id)) || (l.kind === 's' && ids.has(l.for)));
      srv.forEach(l => { const line = S.cart.find(x => x.kind === 'p' && x.id === l.product_id); if (line) line.qty = l.qty || 1; else S.cart.push({ kind: 'p', id: l.product_id, qty: l.qty || 1 }); });
    }
    S.unsel = S.unsel.filter(id => S.cart.some(l => l.kind === 'p' && l.id === id));
    save(); refresh();
  } catch (e) { /* без сети живём с тем, что на телефоне */ }
}

// ---------- Заказы и записи ----------
const STATUS = { new: 'Принят', confirmed: 'Подтверждён', in_work: 'Собираем', ready: 'Готов к выдаче', done: 'Выдан', cancelled: 'Отменён', booked: 'Вы записаны' };
const RANK = { cancelled: -1, new: 0, booked: 0, confirmed: 1, in_work: 2, ready: 3, done: 4 };
// Этап шага по его названию — чтобы отметки на линии совпадали со статусом заказа, даже если сервер не обновил флаги шагов
function stepStatus(title) {
  const t = (title || '').toLowerCase();
  if (t.includes('выдан')) return 'done';
  if (t.includes('готов')) return 'ready';
  if (t.includes('собир') || t.includes('работ')) return 'in_work';
  if (t.includes('подтвер')) return 'confirmed';
  return 'new';
}
function fromOrder(o) {
  const rank = RANK[o.status] != null ? RANK[o.status] : 0;
  return { key: o.number, id: o.id, kind: 'shop', number: o.number, status: o.status, statusText: o.status_text || STATUS[o.status] || o.status,
    created: o.created_at, lines: o.lines || [], services: o.services || [], totals: o.totals || {}, delivery: o.delivery || { type: 'pickup' },
    pickup_code: o.pickup_code, test: !!o.test,
    payment_method: o.payment_method || (typeof o.payment === 'string' ? o.payment : '') || 'on_receipt',
    payment_status: o.payment_status || (o.payment && o.payment.status) || null,
    payment_url: (o.payment && o.payment.url) || o.payment_url || '',
    steps: (o.steps || []).map(s => ({ t: s.title, s: fmtWhen(s.at), done: !!s.done || rank >= RANK[stepStatus(s.title)] })) };
}
// Сервер пока не знает пункт заказа — берём его из заказа, сохранённого на телефоне
function keepPoint(x, local) {
  if (local && local.delivery && local.delivery.point_id && !(x.delivery && x.delivery.point_id)) x.delivery = Object.assign({}, x.delivery, { point_id: local.delivery.point_id, address: local.delivery.address });
  return x;
}
// Пункт заказа: что прислал сервер или что сохранили при оформлении; иначе выбранный сейчас
// Как оплачен заказ — по данным сервера, а не одной фразой «при получении»
function payText(o) {
  const ps = o.payment_status;
  if (ps === 'paid') return 'оплачено онлайн';
  if (ps === 'refunded') return 'оплата возвращена';
  if (ps === 'pending' || ps === 'failed') return 'ожидает оплаты онлайн';
  return 'оплата при получении';
}
function orderPoint(o) {
  const d = o.delivery || {};
  return points().find(p => p.id === d.point_id) || (d.address ? { address: d.address, hours: curPoint().hours } : curPoint());
}
function fromBooking(b) {
  const st = String(b.status || 'new').toLowerCase();
  return { key: b.number, id: b.id, kind: 'repair', number: b.number, status: st === 'new' ? 'booked' : st, statusText: st === 'new' ? 'Вы записаны' : STATUS[st] || st,
    created: b.created_at || new Date().toISOString(), title: b.model || 'Ремонт', sub: b.service_name || 'Ремонт', price_from: b.price_from,
    when: b.date ? fmtDay(b.date) + (b.time ? ' в ' + b.time : '') : '', test: !!b.test,
    steps: [{ t: 'Запись принята', s: fmtWhen(b.created_at || Date.now()), done: true }, { t: 'Бесплатная диагностика', s: 'Назовём точную цену до начала работ', done: false },
      { t: 'Ремонт и проверка', s: b.duration ? 'Срок ' + b.duration : 'Срок скажем после диагностики', done: false }, { t: 'Готово — платите после ремонта', s: 'Сообщим, когда можно забирать', done: false }] };
}
function orderTitle(o) {
  if (o.kind === 'repair') return o.title;
  const first = o.lines[0] || o.services[0];
  return first ? first.name : 'Заказ ' + o.number;
}
function orderSub(o) {
  if (o.kind === 'repair') return o.sub;
  const n = o.lines.reduce((s, l) => s + (l.qty || 1), 0);
  return (n ? n + ' ' + plural(n, 'товар', 'товара', 'товаров') : '') + (o.services.length ? (n ? ' и ' : '') + 'услуга мастера' : '') || o.statusText;
}
function saveOrder(o) { S.orders = [o].concat(S.orders.filter(x => x.key !== o.key)).slice(0, 50); save(); }
function allOrders() {
  const map = new Map();
  S.orders.forEach(o => map.set(o.key, o));
  if (S.token) {
    (need('my-orders', '/orders', MIN, true) || []).forEach(o => { map.set(o.number, keepPoint(fromOrder(o), map.get(o.number))); });
    (need('my-bookings', '/repair/bookings', MIN, true) || []).forEach(b => {
      // В списке записей сервер не присылает модель — берём её из записи, сохранённой на телефоне
      const local = map.get(b.number), x = fromBooking(b);
      if (local && local.kind === 'repair') { if (!b.model) x.title = local.title; x.created = local.created || x.created; }
      if (!local || local.kind === 'repair') map.set(b.number, x);
    });
  }
  return [...map.values()].sort((a, b) => String(b.created).localeCompare(String(a.created)));
}
const statusPill = o => o.status === 'ready' ? '<span class="pill ok"><i></i>Готов к выдаче</span>'
  : o.status === 'cancelled' ? '<span class="pill soft">Отменён</span>' : o.status === 'done' ? '<span class="pill soft">Выдан</span>'
    : `<span class="pill wait"><i></i>${esc(o.statusText)}</span>`;
const testPill = o => o.test ? '<span class="pill test">Тест</span>' : '';

// ---------- Мост с Android ----------
const setLight = on => { try { A && A.setLight(on); } catch (e) { /* в браузере нечего делать */ } };
// Короткая вибрация на важные события, если включена в настройках
function buzz() { if (S.vibro === false) return; try { navigator.vibrate && navigator.vibrate(12); } catch (e) { /* нет вибромотора */ } }
function openExternal(url) { if (A) location.href = url; else window.open(url, '_blank'); }
function deviceModel() {
  if (S.model) return S.model;
  try { if (A) return A.deviceModel(); } catch (e) { /* ниже запасной вариант */ }
  return 'Samsung SM-A015F';   // в браузере без Android — как на телефоне Galaxy A01
}

// ---------- Модель телефона: ремонт, подбор запчастей, «подходит ли» ----------
const MAKER = /^(samsung|xiaomi|huawei|honor|realme|oppo|vivo|tecno|infinix|google|motorola|nokia|oneplus|zte|apple)\s+/i;
const stripMaker = s => String(s || '').trim().replace(MAKER, '');
// Код Samsung из Build.MODEL: «SM-A015F» → «A015F» — так модели подписаны в названиях запчастей
const modelCode = s => { const m = String(s || '').match(/\bSM-([A-Z]\d{3,4}[A-Z]{0,2})\b/i); return m ? m[1].toUpperCase() : ''; };
// Сервер путает «Xiaomi Redmi Note 8» с другими Redmi — отправляем модель без «Xiaomi»
const modelForServer = () => deviceModel().replace(/^xiaomi\s+(?=(redmi|poco|mi)\b)/i, '');
function repairInfo() { const m = modelForServer(); return { key: 'rs:' + m, data: need('rs:' + m, '/repair/services?model=' + encodeURIComponent(m), 30 * MIN, true), model: m }; }
// Понятное название: сервер узнаёт коды Samsung («SM-A015F» → «Samsung Galaxy A01»)
function phoneName() {
  const r = repairInfo().data;
  return r && r.model_found && r.model ? r.model : deviceModel();
}
const phoneShort = () => stripMaker(phoneName());
// По какому запросу искать товары для телефона покупателя
const phoneQuery = () => modelCode(deviceModel()) || phoneShort();
const normModel = s => stripMaker(String(s).toLowerCase().replace(/ё/g, 'е').replace(/[^a-zа-я0-9]+/g, ' ').trim()).replace(/^galaxy /, '');
// true — точно подходит к телефону покупателя; false — список совместимости есть, но телефона в нём нет; null — сказать нечего
function fitsMine(p) {
  const code = modelCode(deviceModel());
  if (code && new RegExp('\\b' + code + '\\b').test(p.name || '')) return true;
  if (!p.fits || !p.fits.length) return null;
  const mine = normModel(phoneName());
  return mine.length > 1 && p.fits.some(f => normModel(f) === mine);
}
// Модель в названии запчасти: «… Galaxy A01 (A015F) …» → «A015F»; иначе первая строка «Подходит для»
function partQuery(p) {
  const m = String(p.name || '').match(/\(([A-Z]{1,2}\d{3,4}[A-Z]{0,2})(?:\/[A-Z0-9]+)*\)/);
  return m ? m[1] : p.fits && p.fits[0] ? p.fits[0] : '';
}

// ---------- Навигация ----------
const view = $('#view'), dock = $('#dock');
let stack = [{ name: 'home' }];
function go(name, arg, opts = {}) {
  if (opts.reset) stack = [{ name, arg }];
  else if (opts.replace) stack[stack.length - 1] = { name, arg };
  else stack.push({ name, arg });
  ui.gallery = 0; ui.descOpen = false;
  render();
}
function back() {
  if ($('#sheet').classList.contains('open')) { closeSheet(); return true; }
  if (stack.length > 1) { stack.pop(); render(); return true; }
  if (stack[0].name !== 'home') { stack = [{ name: 'home' }]; render(); return true; }
  return false;
}
window.appBack = back;
// Возврат из браузера после оплаты: deep link ru.argeda.design://order/A-номер открывает этот заказ
window.appOpenOrder = num => {
  if (!num) return;
  try { if ($('#sheet').classList.contains('open')) closeSheet(); } catch (e) { /* нет шита */ }
  const o = allOrders().find(x => x.key === num || x.number === num);
  if (o && o.id) forget('o:' + o.id);
  stack = [{ name: 'home' }, { name: 'order', arg: o ? o.key : num }];
  render();
};
// Ссылка сайта (App Links): https://argeda.ru/product/…, /catalog, /search?q=, /oplata/…, /account/orders → тот же экран в приложении
window.appOpenUrl = url => {
  let u; try { u = new URL(url); } catch (e) { return; }
  const parts = u.pathname.split('/').filter(Boolean).map(x => { try { return decodeURIComponent(x); } catch (e) { return x; } });
  const [a, b] = parts;
  try { if ($('#sheet').classList.contains('open')) closeSheet(); } catch (e) { /* нет шита */ }
  if (a === 'product' && b) { openProductBySlug(b); return; }
  if (a === 'catalog') {
    const c = b && cats().find(x => x.slug === b);
    if (c) { stack = [{ name: 'home' }, { name: 'list', arg: c.id }]; render(); } else go('catalog', null, { reset: true });
    return;
  }
  if (a === 'search') { ui.query = u.searchParams.get('q') || ''; stack = [{ name: 'home' }]; go('search'); return; }
  if (a === 'oplata') {
    // /oplata/425?v=uspeh, /oplata/uspeh?nomer=425 — номер цифрами, без «A-»
    const raw = (b === 'uspeh' || b === 'oshibka') ? (u.searchParams.get('nomer') || '') : (b || '');
    const num = /^\d+$/.test(raw) ? 'A-' + raw : raw;
    if (num) window.appOpenOrder(num); else { stack = [{ name: 'home' }, { name: 'orders' }]; render(); }
    return;
  }
  if (a === 'account' && b === 'orders') { stack = [{ name: 'home' }, { name: 'orders' }]; render(); return; }
  go('home', null, { reset: true });
};
// Товар по адресу сайта: slug уже знакомого товара, иначе спрашиваем сервер, иначе — поиск по словам из адреса
async function openProductBySlug(slug) {
  const hit = Object.values(SHORT).find(p => p.slug === slug) || Object.values(S.snap).find(p => p.slug === slug);
  if (hit) { stack = [{ name: 'home' }, { name: 'product', arg: hit.id }]; render(); return; }
  try {
    const p = await api('/catalog/products/' + encodeURIComponent(slug));
    if (p && p.id) { MEM['p:' + p.id] = { data: p, at: Date.now() }; remember([p]); stack = [{ name: 'home' }, { name: 'product', arg: p.id }]; render(); return; }
  } catch (e) { /* по slug сервер не нашёл — ищем словами */ }
  ui.query = slug.replace(/-/g, ' '); stack = [{ name: 'home' }]; go('search');
};
// Возврат в приложение после оплаты (из браузера/Custom Tab, любым способом): сразу обновляем заказ
window.appResumed = () => {
  if (!NET.online) netPing();
  const cur = stack[stack.length - 1];
  if (cur && cur.name === 'order') {
    const o = allOrders().find(x => x.key === cur.arg);
    if (o && o.id && o.payment_status === 'pending') { forget('o:' + o.id); refresh(); }
  }
};

let nearBottom = null, onScroll = null, payPoll = null;
function render(keepScroll) {
  const y = view.scrollTop;
  const cur = stack[stack.length - 1];
  used = new Set(); LISTS.active = null; nearBottom = null; onScroll = null;
  if (payPoll) { clearTimeout(payPoll); payPoll = null; }
  const r = SCREENS[cur.name](cur.arg);
  view.innerHTML = r.html;
  view.scrollTop = keepScroll ? y : 0;
  dock.innerHTML = r.tab ? dockHtml(r.tab) : '';
  dock.classList.toggle('hide', !r.tab);
  setLight(!!r.darkTop || isDark());
  if (r.after) r.after();
  if (onScroll) onScroll();
}
const refresh = () => { if (!document.activeElement || document.activeElement.tagName !== 'INPUT' || !view.contains(document.activeElement)) render(true); };
view.addEventListener('scroll', () => {
  if (nearBottom && view.scrollTop + view.clientHeight > view.scrollHeight - 700) nearBottom();
  if (onScroll) onScroll();
}, { passive: true });

// Все вкладки подписаны, открытая выделена заливкой
function dockHtml(active) {
  const items = [['home', 'home', 'Главная'], ['catalog', 'grid', 'Каталог'], ['repair', 'wrench', 'Ремонт'], ['cart', 'bag', 'Корзина'], ['profile', 'user', 'Профиль']];
  const n = cartCount();
  return items.map(([tab, icon, label]) => {
    const b = tab === 'cart' && n ? `<i class="badge">${n}</i>` : '';
    return `<button class="dk${tab === active ? ' on' : ''}" data-a="nav" data-x="${tab}"${tab === active ? ' aria-current="page"' : ''}>${ic(icon, 'currentColor', 22)}<span>${label}</span>${b}</button>`;
  }).join('');
}

// ---------- Тема: как в телефоне, светлая или тёмная ----------
const darkQuery = window.matchMedia ? matchMedia('(prefers-color-scheme: dark)') : null;
function phoneIsDark() {
  try { if (A && A.isNight) return A.isNight(); } catch (e) { /* ниже — по браузеру */ }
  return !!(darkQuery && darkQuery.matches);
}
const isDark = () => S.theme === 'dark' || (S.theme !== 'light' && phoneIsDark());
function applyTheme() {
  const dark = isDark();
  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
  try { if (A && A.setNight) A.setNight(dark); } catch (e) { /* старая сборка без моста темы */ }
}
// Android зовёт это, когда в телефоне переключили тему
window.appTheme = () => { applyTheme(); render(true); };
if (darkQuery && darkQuery.addEventListener) darkQuery.addEventListener('change', () => window.appTheme());
applyTheme();

// ---------- Общие куски ----------
// Звезда рейтинга — иконки генерируются отдельно, эту добавляем на месте
if (typeof ICONS !== 'undefined' && !ICONS.star) ICONS.star = '<path d="m12 2 3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01z"/>';
if (typeof ICONS !== 'undefined' && !ICONS.dish) ICONS.dish = '<path d="M4 12a8 8 0 0 1 8 8M4 16a4 4 0 0 1 4 4"/><circle cx="5" cy="19" r="1"/><path d="M13 7a5 5 0 0 1 4 8l-9-9a5 5 0 0 1 5 1z"/>';
// Наличие — главный довод ARGEDA, поэтому оно на каждой карточке
function availLine(p) {
  if (p.availability === 'preorder') return `<span class="av pre">Под заказ${p.preorder_days ? ' · ' + p.preorder_days + ' дн.' : ''}</span>`;
  if (p.in_stock === false) return '<span class="av none">Нет в наличии</span>';
  return '<span class="av today">Сегодня в пункте</span>';
}
const discOf = p => p.old_price && p.old_price > p.price ? Math.round((1 - p.price / p.old_price) * 100) : 0;
// Рейтинг товара — только настоящий, с сервера: rating {value, count} или null
function ratingOf(p) {
  if (p && p.rating && p.rating.count) return { value: +p.rating.value || 0, count: p.rating.count };
  return null;
}
// Отзывы с сайта: GET /catalog/products/{id}/reviews?page=N — первая страница грузится сама, дальше по кнопке
const REV_MORE = {};
function reviewsOf(p) {
  if (!p || !p.id || !ratingOf(p)) return { items: [], has_more: false, loading: false };
  const key = 'rv:' + p.id, d = need(key, '/catalog/products/' + encodeURIComponent(p.id) + '/reviews?page=1', 10 * MIN);
  const extra = REV_MORE[p.id] || { items: [], page: 1, has_more: null, loading: false };
  const raw = ((d && d.items) || []).concat(extra.items);
  const items = raw.map(x => ({ id: x.id, name: x.author || 'Покупатель', value: +x.rating || 0, text: x.text || '', date: x.created_at, photos: Array.isArray(x.photos) ? x.photos : [], reply: x.reply || '' }));
  const has_more = extra.has_more != null ? extra.has_more : !!(d && d.has_more);
  return { items, has_more, loading: (!d && loading(key)) || extra.loading, total: d && d.total };
}
function loadMoreReviews(id) {
  const x = REV_MORE[id] || (REV_MORE[id] = { items: [], page: 1, has_more: null, loading: false });
  if (x.loading) return;
  x.loading = true; refresh();
  api('/catalog/products/' + encodeURIComponent(id) + '/reviews?page=' + (x.page + 1))
    .then(d => { x.items = x.items.concat((d && d.items) || []); x.page++; x.has_more = !!(d && d.has_more); })
    .catch(() => { x.has_more = false; }).finally(() => { x.loading = false; refresh(); });
}
function revDate(x) {
  if (!x.date) return '';
  try { return new Date(x.date).toLocaleDateString('ru-RU'); } catch (e) { return ''; }
}
// Карточка товара как на маркетплейсе: скидка, рейтинг, крупная цена и зачёркнутая старая
function pcard(p) {
  const added = inCart(p.id), fav = S.fav.includes(p.id), disc = discOf(p), r = ratingOf(p);
  const rating = r ? `<div class="rate">${ic('star', '#F5A623', 12, 0, true)}${String(r.value).replace('.', ',')} <span>· ${r.count}</span></div>` : '';
  return `<div class="pc tap" data-a="go" data-x="product|${esc(p.id)}"><div class="pc-img">${img(p.image)}<button class="fv${fav ? ' on' : ''}" data-a="fav" data-x="${esc(p.id)}" aria-label="${fav ? 'Убрать из избранного' : 'В избранное'}">${heart(fav)}</button>${disc ? `<span class="disc">−${disc}%</span>` : ''}</div>
${availLine(p)}${rating}<div class="pc-name">${esc(p.name)}</div>${fitsMine(p) ? `<div class="fit">${ic('check', GREEN, 13, 3)}Подходит к вашему</div>` : ''}
<div class="pc-row"><span class="pr">${money(p.price)}${p.old_price ? `<s>${money(p.old_price)}</s>` : ''}</span>
<button class="add${added ? ' in' : ''}" data-a="add" data-x="${esc(p.id)}" aria-label="В корзину">${ic(added ? 'check' : 'plus', '#fff', 18, 2.5)}</button></div></div>`;
}
const skelCards = n => Array.from({ length: n }, () => '<div class="pc skel"><div class="pc-img"></div><div class="sk-l"></div><div class="sk-l s"></div></div>').join('');
const errBox = (text, key) => `<div class="card errbox">${ic('help', CORAL, 22)}<span>${esc(text)}</span><button class="btn outline sm" data-a="retry" data-x="${esc(key || '')}">Повторить</button></div>`;
const header = (title, right = '') => `<div class="top"><button class="icon-btn white" data-a="back" aria-label="Назад">${ic('back', INK, 22)}</button><span class="h">${esc(title)}</span>${right || '<span style="width:44px"></span>'}</div>`;
// Лента товаров с прокруткой вбок: пусто — не показываем, пока грузится — заглушки
function strip(title, items, opts = {}) {
  if (items && !items.length) return '';
  return `<div class="sec-h${opts.cls ? ' ' + opts.cls : ''}"><h3>${esc(title)}${opts.sub ? `<small>${esc(opts.sub)}</small>` : ''}</h3>${opts.all ? `<button data-a="${opts.all[0]}" data-x="${esc(opts.all[1])}">Все</button>` : ''}</div>
<div class="hs">${items ? items.map(pcard).join('') : skelCards(3)}</div>`;
}
// Товары по запросу (без постраничной подгрузки) — для лент «Для вашего телефона», «Похожие»
function picks(key, query, ttl = 30 * MIN) {
  const d = need(key, '/catalog/products?' + query, ttl, true);
  if (d) remember(d.items);
  return d ? d.items || [] : ERR[key] ? [] : null;
}

// Лента «Рекомендуем» внизу главной. Сортировка «популярные» на сервере пока идёт по алфавиту,
// поэтому берём товары из случайных мест крупных разделов и чередуем разделы: подряд не идут одинаковые
const FEED = { items: [], ids: new Set(), loading: false, err: '', pages: {} };
const FEED_MAX = 400, FEED_BATCH = 8, FEED_PER = 4;
function loadFeed() {
  const cs = cats().filter(c => !c.parent_id && c.count >= 100);
  if (FEED.loading || FEED.items.length >= FEED_MAX || !cs.length) return;
  FEED.loading = true; FEED.err = '';
  // Большие разделы попадаются чаще, но не забивают ленту: вес — корень из числа товаров
  const w = cs.map(c => Math.sqrt(c.count)), sum = w.reduce((a, b) => a + b, 0);
  const pick = () => { let r = Math.random() * sum, k = 0; while (k < cs.length - 1 && r > w[k]) { r -= w[k]; k++; } return cs[k]; };
  const reqs = Array.from({ length: FEED_BATCH }, () => {
    const c = pick(), pages = Math.max(1, Math.ceil(c.count / FEED_PER)), used = FEED.pages[c.id] || (FEED.pages[c.id] = new Set());
    let pg = 1;
    for (let t = 0; t < 6; t++) { pg = 1 + Math.floor(Math.random() * pages); if (!used.has(pg)) break; }
    used.add(pg);
    return api(`/catalog/products?category=${encodeURIComponent(c.id)}&limit=${FEED_PER}&page=${pg}`);
  });
  Promise.allSettled(reqs).then(res => {
    const lists = res.filter(r => r.status === 'fulfilled').map(r => r.value.items || []);
    if (!lists.length) { FEED.err = res[0].reason ? res[0].reason.message : 'Не получилось загрузить товары'; return; }
    const out = [];
    for (let i = 0; i < FEED_PER; i++) lists.forEach(l => { const p = l[i]; if (p && p.image && !FEED.ids.has(p.id)) { FEED.ids.add(p.id); out.push(p); } });
    remember(out);
    FEED.items = FEED.items.concat(out);
  }).finally(() => {
    FEED.loading = false;
    if (stack[stack.length - 1].name === 'home') refresh();
  });
}
function feedHtml() {
  const tail = FEED.err ? errBox(FEED.err, 'feed') : FEED.loading || !FEED.items.length ? `<div class="grid">${skelCards(4)}</div>`
    : FEED.items.length >= FEED_MAX ? '<div class="more">Это всё на сегодня — загляните в каталог</div>' : '';
  return `<div class="sec-h"><h3>Рекомендуем<small>Товары из всех разделов каталога</small></h3></div>
${FEED.items.length ? `<div class="grid">${FEED.items.map(pcard).join('')}</div>` : ''}${tail}`;
}

// ---------- Услуги: единый поиск (контракт /search, запасной путь /repair/services) ----------
const SVCFOR = {}, SVC_BY_ID = {};
let _repairAll = null;
const SVC_DIR_ICON = { 'Ремонт телефонов': 'phone-dev', 'Ремонт техники': 'wrench', 'Спутниковое ТВ': 'store', 'Эфирное ТВ': 'store', 'Видеонаблюдение': 'shield', 'Триколор': 'store', 'Настройка и установка': 'wrench' };
const svcIcon = s => SVC_DIR_ICON[s.direction] || 'wrench';
const PHONE_RX = /iphone|samsung|xiaomi|redmi|poco|honor|huawei|realme|vivo|oppo|pixel|galaxy|телефон|смартфон|айфон|самсунг|ксиоми|сяоми|хонор|хуавей|реалми|редми/i;
const normService = s => ({ id: s.id, name: s.name, direction: s.direction || s.direction_name || 'Услуга', price_from: s.price_from, duration: s.duration || '', type: s.type || (s.problem ? 'workshop' : 'both'), problem: s.problem || '', needs_model: s.needs_model != null ? s.needs_model : !!s.problem, image: s.image || '', kind: 'service' });
function repairAsServices(list, q) {
  const isPhone = PHONE_RX.test(q), words = q.toLowerCase().split(/\s+/).filter(w => w.length >= 4);
  return list.filter(x => isPhone || words.some(w => x.name.toLowerCase().includes(w)))
    .map(x => normService({ id: x.id, name: x.name, direction: 'Ремонт телефонов', price_from: x.price_from, duration: x.duration, type: 'workshop', problem: x.problem }));
}
const rememberServices = items => items.forEach(s => { SVC_BY_ID[s.id] = s; });
function servicesFor(q) {
  const key = q.toLowerCase();
  let s = SVCFOR[key];
  if (s) return s;
  s = SVCFOR[key] = { items: null, total: 0, loading: true, err: '' };
  const done = () => { s.loading = false; if (s.items) rememberServices(s.items); if (stack[stack.length - 1].name === 'search' && searchRender) searchRender(); };
  api('/search?type=services&limit=24&q=' + encodeURIComponent(q))
    .then(d => { const it = ((d.services && d.services.items) || d.items || []).map(normService); s.items = it; s.total = (d.services && d.services.total) || it.length; done(); })
    .catch(e => {
      // Единого поиска ещё нет — показываем услуги ремонта телефонов под запрос
      if (e.status === 404 || e.status === 405) {
        const use = list => { s.items = repairAsServices(list, q); s.total = s.items.length; };
        if (_repairAll) { use(_repairAll); done(); }
        else api('/repair/services').then(r => { _repairAll = r.services || []; use(_repairAll); }).catch(() => { s.items = []; }).finally(done);
      } else { s.err = e.message; s.items = []; done(); }
    });
  return s;
}
function scard(s) {
  const badge = s.type === 'onsite' ? '<span class="sbadge out">Выезд мастера</span>' : s.type === 'both' ? '<span class="sbadge">Мастерская или выезд</span>' : '<span class="sbadge">В мастерской</span>';
  const ph = s.image ? `<span class="ph">${img(s.image)}</span>` : `<span class="ph si">${ic(svcIcon(s), BLUE, 24)}</span>`;
  return `<button class="card srvc tap" data-a="service" data-x="${esc(s.id)}">${ph}<span class="mid"><div class="n">${esc(s.name)}</div><div class="d">${esc(s.direction)}${s.duration ? ' · ' + esc(s.duration) : ''}</div>${badge}<span class="pr">от ${money(s.price_from)}</span></span></button>`;
}

// Баннеры главной: фоны сделаны в Figma, тексты и цены — живые, с сервера
const PETAL_SVG = size => `<svg width="${size}" height="${size}" viewBox="-20 -20 40 40" aria-hidden="true">${petals('#fff')}</svg>`;
function svcOf(problem) {
  const rs = need('rs:', '/repair/services', 30 * MIN, true);
  const list = rs ? rs.services.filter(s => s.problem === problem).sort((a, b) => a.price_from - b.price_from) : [];
  return list[0] || null;
}
const durText = s => s && s.duration ? s.duration.replace(/^от\s+/, 'от ') : '';
function bannerList() {
  const remotes = cats().find(c => !c.parent_id && /пульт/i.test(c.name));
  const scr = svcOf('screen'), bat = svcOf('battery');
  const svcLine = (s, what) => s ? `${what} ${durText(s)} · от ${money(s.price_from)}` : 'Запишитесь — платите после ремонта';
  return [
    { key: 'screen', img: 'img/banners/screen.jpg', kicker: 'Ремонт', h: 'Разбит экран?', p: svcLine(scr, 'Замена') },
    { key: 'battery', img: 'img/banners/battery.jpg', kicker: 'Ремонт', h: 'Быстро садится?', p: svcLine(bat, 'Замена аккумулятора') },
    remotes && { key: 'remotes', img: 'img/banners/remotes.jpg', kicker: 'Каталог', h: 'Пульты для любого ТВ', p: `${remotes.count.toLocaleString('ru-RU')} ${plural(remotes.count, 'модель', 'модели', 'моделей')} в каталоге` },
    { key: 'pickup', img: 'img/banners/pickup.jpg', kicker: 'Самовывоз', h: 'Бесплатно в любом пункте', p: `${points().length} ${plural(points().length, 'пункт', 'пункта', 'пунктов')} в Пушкино · ${latestClose()}` },
    { key: 'sellers', img: 'img/banners/sellers.jpg', kicker: 'Продавцам', h: 'Продавайте на ARGEDA', p: 'Свой магазин и пункты выдачи в Пушкино' },
  ].filter(Boolean);
}
let SRV_BANNERS = [];
const SHADE = 'linear-gradient(90deg, rgba(11,22,48,.96) 0%, rgba(11,22,48,.8) 46%, rgba(11,22,48,0) 80%)';
function bannerHtml(b) {
  const bg = b.img ? `background-image:${SHADE},url('${esc(b.img)}');background-size:cover;background-position:right center`
    : 'background-image:radial-gradient(120% 140% at 100% 0%, #3E8BF0 0%, #1668D9 38%, #0E2A47 100%)';
  const deco = b.img ? '' : `<span class="wm">${PETAL_SVG(150)}</span><span class="pin">${ic('pin', '#fff', 30)}</span>`;
  return `<button class="promo tap" data-a="banner" data-x="${esc(b.key)}" style="${bg}">${deco}<span class="pill glass">${esc(b.kicker)}</span><div class="h">${esc(b.h)}</div><div class="p">${esc(b.p)}</div></button>`;
}
const CAT_ICONS = [['пульт', 'remote', 'c-blue', BLUE], ['диспле', 'phone-dev', 'c-violet', VIOLET], ['аккумул', 'battery', 'c-green', GREEN], ['запчаст', 'chip', 'c-blue', BLUE],
  ['заряд', 'zap', 'c-coral', CORAL], ['кабел', 'zap', 'c-coral', CORAL], ['аксессуар', 'shield', 'c-violet', VIOLET], ['инструмент', 'wrench', 'c-green', GREEN],
  ['бытов', 'home', 'c-coral', CORAL], ['аудио', 'remote', 'c-violet', VIOLET], ['компьют', 'card', 'c-blue', BLUE], ['электрик', 'zap', 'c-blue', BLUE],
  ['электрон', 'phone-dev', 'c-violet', VIOLET], ['безопас', 'shield', 'c-blue', BLUE], ['климат', 'drop', 'c-blue', BLUE], ['для дома', 'home', 'c-green', GREEN],
  ['продукт', 'gift', 'c-coral', CORAL], ['чай', 'gift', 'c-coral', CORAL], ['спорт', 'route', 'c-green', GREEN], ['строит', 'wrench', 'c-coral', CORAL],
  ['сад', 'drop', 'c-green', GREEN], ['авто', 'truck', 'c-violet', VIOLET], ['детств', 'gift', 'c-violet', VIOLET], ['оборуд', 'store', 'c-blue', BLUE], ['распрод', 'gift', 'c-coral', CORAL]];
function catIcon(name) {
  const n = String(name).toLowerCase();
  const hit = CAT_ICONS.find(([k]) => n.includes(k));
  return hit ? hit.slice(1) : ['grid', 'c-blue', BLUE];
}
const count = n => n.toLocaleString('ru-RU') + ' ' + plural(n, 'товар', 'товара', 'товаров');
// «среди 35 421 товара», «среди 35 427 товаров»
const countGen = n => n.toLocaleString('ru-RU') + ' ' + (n % 10 === 1 && n % 100 !== 11 ? 'товара' : 'товаров');
// История поиска: 8 последних запросов, без повторов
function rememberQuery(v) {
  const t = v.trim();
  if (t.length < 2) return;
  S.hist = [t].concat(S.hist.filter(h => h.toLowerCase() !== t.toLowerCase())).slice(0, 8); save();
}
// Фильтры списка → параметры запроса
const filtersOn = () => !!cfg().features.filters;
// Онлайн-оплата: включает сервер (features.online_payment). В тестовой сборке показываем для предпросмотра (демо).
const payOn = () => !!cfg().features.online_payment;
const payShow = () => payOn() || S.test;
// Выбранный способ: явный выбор покупателя или по умолчанию онлайн, когда сервер его поддерживает
const effPay = () => (S.pay === 'online' || S.pay === 'on_receipt') ? S.pay : (payOn() ? 'online' : 'on_receipt');
// Запрос ссылки на оплату уже созданного заказа и открытие её в браузере телефона
async function startPay(key) {
  const o = allOrders().find(x => x.key === key);
  if (!o) return;
  if (o.demoPay) { saveOrder(Object.assign({}, o, { payment_status: 'paid', demoPay: false })); refresh(); buzz(); toast('Оплата прошла успешно (демо)'); return; }
  if (!o.id) { toast('Заказ ещё не сохранён на сервере'); return; }
  try {
    const d = await api('/orders/' + encodeURIComponent(o.id) + '/pay', { method: 'POST' });
    const url = d && (d.url || (d.payment && d.payment.url));
    if (url) { forget('o:' + o.id); openExternal(url); toast('Открываю оплату…'); }
    else toast('Сервер не вернул ссылку на оплату');
  } catch (e) {
    if (o.payment_url) { openExternal(o.payment_url); toast('Открываю оплату…'); }
    else toast(e.offline ? e.message : 'Оплата недоступна: ' + e.message);
  }
}
function fltQuery() {
  const f = ui.flt;
  let q = f.model && phoneQuery() ? '&q=' + encodeURIComponent(phoneQuery()) : '';
  if (filtersOn()) {
    if (f.today) q += '&in_stock=1';
    if (+f.pmin > 0) q += '&price_min=' + (+f.pmin);
    if (+f.pmax > 0) q += '&price_max=' + (+f.pmax);
    if (f.brand.length) q += '&brand=' + encodeURIComponent(f.brand.join(','));
  }
  return q;
}
const fltCount = () => (ui.flt.model ? 1 : 0) + (filtersOn() ? (ui.flt.today ? 1 : 0) + (+ui.flt.pmin > 0 || +ui.flt.pmax > 0 ? 1 : 0) + (ui.flt.brand.length ? 1 : 0) : 0);
// В поиске у товаров свой запрос q, поэтому «мою модель» не подмешиваем — только сортировка, цена и наличие
function searchParams() {
  const f = ui.flt; let q = '&sort=' + ui.sort;
  if (filtersOn()) { if (f.today) q += '&in_stock=1'; if (+f.pmin > 0) q += '&price_min=' + (+f.pmin); if (+f.pmax > 0) q += '&price_max=' + (+f.pmax); if (f.brand.length) q += '&brand=' + encodeURIComponent(f.brand.join(',')); }
  return q;
}
const searchFltKey = () => ui.sort + (filtersOn() ? '|' + (ui.flt.today ? 1 : 0) + (+ui.flt.pmin || 0) + '-' + (+ui.flt.pmax || 0) + '|' + ui.flt.brand.join(',') : '');
const searchFltCount = () => filtersOn() ? (ui.flt.today ? 1 : 0) + (+ui.flt.pmin > 0 || +ui.flt.pmax > 0 ? 1 : 0) + (ui.flt.brand.length ? 1 : 0) : 0;
// В плитке раздела — фото самого популярного товара: узнаётся быстрее значка
function catThumb(c) {
  const d = need('ci:' + c.id, `/catalog/products?category=${encodeURIComponent(c.id)}&limit=1`, DAY, true);
  const p = d && d.items && d.items[0];
  if (p && p.image) return `<span class="ph">${img(p.image)}</span>`;
  const [i, cl, col] = catIcon(c.name);
  return `<span class="sq ${cl}">${ic(i, col, 22)}</span>`;
}
const PROBS = [
  { id: 'screen', icon: 'crack', c: 'c-violet', col: VIOLET, n: 'Разбит экран' }, { id: 'battery', icon: 'battery', c: 'c-green', col: GREEN, n: 'Быстро садится' },
  { id: 'port', icon: 'zap', c: 'c-coral', col: CORAL, n: 'Не заряжается' }, { id: 'water', icon: 'drop', c: 'c-blue', col: BLUE, n: 'Попала вода' },
  { id: 'camera', icon: 'camera', c: 'c-violet', col: VIOLET, n: 'Камера' }, { id: 'other', icon: 'dots', c: 'c-blue', col: INK, n: 'Другое' },
];

// ---------- Экраны ----------
const SCREENS = {
  home() {
    const c = cfg(), h = need('home', '/catalog/home', 10 * MIN, true), all = cats();
    if (h) remember(h.in_stock_today);
    const orders = allOrders(), o = orders.find(x => !['done', 'cancelled'].includes(x.status));
    const orderCard = o ? `<button class="card repair tap" data-a="go" data-x="order|${esc(o.key)}">
<div class="row between"><span class="kicker">${o.kind === 'repair' ? 'Моя запись' : 'Мой заказ'}</span>${statusPill(o)}</div>
<div class="row" style="gap:12px;margin-top:12px"><div class="sq c-blue">${ic(o.kind === 'repair' ? 'wrench' : 'bag', BLUE, 22)}</div>
<div style="min-width:0;flex:1"><div class="t">${esc(orderTitle(o))}</div><div class="s">${esc(o.kind === 'repair' ? o.when || o.sub : orderSub(o))} · ${esc(o.number)}</div></div>
${o.status === 'ready' && o.pickup_code ? `<span class="qrb">${ic('scan', '#fff', 18)}QR</span>` : ''}</div></button>`
      : `<button class="card repair tap" data-a="nav" data-x="repair"><div class="row" style="gap:12px"><div class="sq c-blue">${ic('wrench', BLUE, 22)}</div>
<div><div class="t">Ремонт телефонов</div><div class="s">Запишитесь — платите после ремонта</div></div></div></button>`;
    // Истории-кружки: быстрый вход в услуги и верхние разделы — как у WB/Ozon
    const roots = all.filter(x => !x.parent_id && x.count > 0).sort((a, b) => b.count - a.count);
    const stories = `<button class="story" data-a="nav" data-x="repair"><span class="sph grad">${ic('wrench', '#fff', 24)}</span><span>Услуги</span></button>`
      + roots.slice(0, 8).map(x => { const [i, cl, col] = catIcon(x.name); return `<button class="story" data-a="go" data-x="list|${esc(x.id)}"><span class="sph ${cl}">${ic(i, col, 24)}</span><span>${esc(x.name)}</span></button>`; }).join('');
    // Сервер может прислать свои баннеры (картинка + действие); старые «баннеры-товары» не показываем
    SRV_BANNERS = h ? (h.banners || []).filter(b => b.image && b.action).map((b, i) => ({ key: 'srv:' + i, img: b.image, kicker: b.kicker || 'ARGEDA', h: b.title, p: b.subtitle || '', action: b.action })) : [];
    const banners = `<div class="banners">${SRV_BANNERS.concat(bannerList()).map(bannerHtml).join('')}</div>`;
    const today = h ? h.in_stock_today : null;
    const q = phoneQuery(), forPhone = q ? picks('feed:' + q, `q=${encodeURIComponent(q)}&limit=12`) : [];
    return {
      tab: 'home', darkTop: false,
      html: `<div class="mhead">
<div class="mrow"><div class="mbrand">${logo(28)}<span>ARGEDA</span></div>
<button class="maddr" data-a="pickup">${ic('pin', BLUE, 15)}<span>${esc(shortAddr())}</span>${ic('chev-r', GREY, 14)}</button>
</div>
<div class="search light msearch"><button style="display:contents" data-a="go" data-x="search|">${ic('search', '#8A93A6', 20)}<span>Товар, запчасть, услуга или модель</span></button>
</div></div>
<div class="scr mhome">
<div class="stories">${stories}</div>
${orderCard}
${banners}
${!h && ERR.home ? errBox(errText('home'), 'home') : ''}
${forPhone && forPhone.length < 2 ? '' : strip('Для вашего ' + phoneShort(), forPhone, { sub: 'Запчасти и аксессуары, которые подходят', all: ['searchFor', q] })}
${strip('Есть в пункте сегодня', today, { all: ['nav', 'catalog'] })}
${TEST_BUILD && S.test ? '<div class="testline">Тестовая сборка: заказы и записи помечаются «тест» и в магазин не уходят</div>' : ''}
${feedHtml()}</div>`,
      after() {
        // Лента внизу главной подгружается, пока листают — как у маркетплейсов
        nearBottom = loadFeed;
        if (!FEED.items.length && !FEED.err) loadFeed();
      },
    };
  },

  catalog() {
    const goods = ui.catTab === 'goods';
    let body;
    if (goods) {
      const roots = cats().filter(c => !c.parent_id && c.count > 0).sort((a, b) => b.count - a.count);
      body = `${roots.length ? `<div class="cats">${roots.map(c => `<button class="card cat tap" data-a="go" data-x="list|${esc(c.id)}">${catThumb(c)}<span><div class="n">${esc(c.name)}</div><div class="d">${count(c.count)}</div></span></button>`).join('')}</div>`
          : ERR.cats ? errBox(errText('cats'), 'cats') : `<div class="cats">${Array.from({ length: 6 }, () => '<div class="card cat skel"></div>').join('')}</div>`}`;
    } else {
      const rs = need('rs:', '/repair/services', 30 * MIN, true);
      body = rs ? rs.services.map(s => { const p = PROBS.find(x => x.id === s.problem) || PROBS[5];
        return `<button class="card svc tap" data-a="probSvc" data-x="${esc(s.problem)}|${esc(s.id)}"><span class="sq ${p.c}">${ic(p.icon, p.col, 22)}</span>
<span class="mid"><div class="n">${esc(s.name)}</div><div class="d">${esc(s.duration)} · платите после ремонта</div><div class="p">от ${money(s.price_from)}</div></span></button>`; }).join('')
        : ERR['rs:'] ? errBox(errText('rs:'), 'rs:') : '<div class="loading">Загружаем услуги…</div>';
    }
    return {
      tab: 'catalog',
      html: `<div class="scr light"><div class="title"><h1>Каталог</h1></div>
<div class="search light"><button style="display:contents" data-a="go" data-x="search|">${ic('search', GREY, 20)}<span>${allCount() ? 'Поиск среди ' + countGen(allCount()) : 'Поиск по всему каталогу'}</span></button></div>
<div class="seg"><button class="${goods ? 'on' : ''}" data-a="catTab" data-x="goods">Товары</button><button class="${goods ? '' : 'on'}" data-a="catTab" data-x="services">Услуги мастеров</button></div>
${body}</div>`,
    };
  },

  list(cat) {
    const c = catById(cat), all = cats();
    const kids = all.filter(x => x.parent_id === cat && x.count > 0).sort((a, b) => b.count - a.count);
    ui.fltCtx = 'category=' + encodeURIComponent(cat);
    const fq = fltQuery(), key = 'cat:' + cat + ':' + ui.sort + fq;
    const L = listOf(key, `category=${encodeURIComponent(cat)}&sort=${ui.sort}${fq}`);
    LISTS.active = key;
    const sorts = [['popular', 'Популярные'], ['price_asc', 'Дешевле'], ['price_desc', 'Дороже']]
      .map(([k, t]) => `<button class="chip${ui.sort === k ? ' on' : ''}" data-a="sort" data-x="${k}">${t}</button>`).join('');
    // Фильтры: «для моего телефона» работает через поиск сервера; наличие и цена — когда сервер включит features.filters
    const nf = fltCount();
    const filters = `<button class="chip flt${nf ? ' on' : ''}" data-a="filters">${ic('filter', nf ? '#fff' : INK, 16)}Фильтры${nf ? `<span class="cnt">${nf}</span>` : ''}</button>
<button class="chip flt${ui.flt.model ? ' on' : ''}" data-a="fltModel">${ic('phone-dev', ui.flt.model ? '#fff' : INK, 16)}Для ${esc(phoneShort())}</button>`;
    const sub = kids.length ? `<div class="chips scroll subcats">${kids.map(k => `<button class="chip" data-a="go" data-x="list|${esc(k.id)}">${esc(k.name)}<small>${k.count.toLocaleString('ru-RU')}</small></button>`).join('')}</div>` : '';
    const body = L.items.length ? `<div class="grid">${L.items.map(pcard).join('')}</div>` + (L.done ? '' : `<div class="more">${L.loading ? 'Загружаем ещё…' : L.err ? errBox(L.err, 'list') : `<button class="btn outline" data-a="more">Показать ещё</button>`}</div>`)
      : L.err ? errBox(L.err, 'list') : L.done ? `<div class="empty"><div class="sq c-blue">${ic('grid', BLUE, 32)}</div><h3>${nf ? 'Ничего не подошло' : 'Здесь пока пусто'}</h3><p>${nf ? 'Попробуйте убрать фильтры или спросите мастера — подберём по телефону.' : 'В этом разделе нет товаров в продаже.'}</p>${nf ? '<button class="btn outline" data-a="fltReset">Сбросить фильтры</button>' : ''}</div>`
        : `<div class="grid">${skelCards(6)}</div>`;
    return {
      html: `<div class="scr light nodock">${header(c ? c.name : 'Каталог')}
${L.total != null ? `<div class="lcount">${count(L.total)}</div>` : ''}${sub}<div class="chips scroll">${filters}${sorts}</div>${body}</div>`,
      after() { if ((!L.items.length || L.fromCache) && !L.loading && !L.done && !L.err) loadMore(key); nearBottom = () => loadMore(key); },
    };
  },

  search() {
    return {
      html: `<div class="scr light nodock"><div class="top"><button class="icon-btn white" data-a="back" aria-label="Назад">${ic('back', INK, 22)}</button>
<div class="search light" style="margin:0;flex:1;height:48px">${ic('search', GREY, 20)}<input id="q" type="search" placeholder="Товар, запчасть или модель" value="${esc(ui.query)}" enterkeyhint="search" autocomplete="off"></div></div>
<div id="hints"></div><div id="results"></div></div>`,
      after() {
        const q = $('#q');
        let timer, histTimer;
        // Пока поле пустое — «Вы искали» и «Часто ищут»; с текстом — разделы каталога и товары
        const hints = v => {
          if (v) {
            const words = v.toLowerCase().split(/\s+/).filter(w => w.length >= 3);
            const hit = words.length ? cats().filter(c => c.count > 0 && words.some(w => c.name.toLowerCase().includes(w.slice(0, Math.max(3, w.length - 2))))).sort((a, b) => b.count - a.count).slice(0, 3) : [];
            return hit.length ? `<div class="card sugg">${hit.map(c => `<button data-a="go" data-x="list|${esc(c.id)}">${ic('grid', BLUE, 18)}<span>Раздел <b>${esc(c.name)}</b></span><em>${count(c.count)}</em></button>`).join('')}</div>` : '';
          }
          const short = phoneShort();
          const popular = [`аккумулятор ${short}`, `дисплей ${short}`, 'пульт Samsung', 'зарядка Type-C', 'защитное стекло'];
          return (S.hist.length ? `<div class="hint-h"><span class="kicker">Вы искали</span><button data-a="histClear">Очистить</button></div>
<div class="hint">${S.hist.map(h => `<button data-a="hint" data-x="${esc(h)}">${ic('clock', GREY, 15)}${esc(h)}</button>`).join('')}</div>` : '')
            + `<div class="hint-h"><span class="kicker">Часто ищут</span></div><div class="hint">${popular.map(h => `<button data-a="hint" data-x="${esc(h)}">${esc(h)}</button>`).join('')}</div>`;
        };
        const show = () => {
          const v = q.value.trim();
          $('#hints').innerHTML = hints(v.length >= 2 ? v : '');
          if (v.length < 2) { $('#results').innerHTML = v ? '<div class="lcount">Введите хотя бы 2 буквы</div>' : ''; nearBottom = null; return; }
          const tab = ui.searchTab, key = 'q:' + v.toLowerCase() + ':' + searchFltKey();
          ui.fltCtx = 'q=' + encodeURIComponent(v);
          const L = listOf(key, 'q=' + encodeURIComponent(v) + searchParams());
          LISTS.active = key;
          const svc = servicesFor(v);
          // Товары подгружаем на вкладках «Всё» и «Товары»
          if (tab !== 'services') { nearBottom = () => loadMore(key); if ((!L.items.length || L.fromCache) && !L.loading && !L.done && !L.err) loadMore(key); }
          else nearBottom = null;
          // Запрос, по которому что-то нашлось, запоминаем в «Вы искали»
          if (L.items.length) { clearTimeout(histTimer); histTimer = setTimeout(() => { if (q.value.trim() === v) rememberQuery(v); }, 1500); }
          const ns = svc.items ? svc.items.length : 0;
          const tabs = `<div class="seg sm">${[['all', 'Всё'], ['products', 'Товары'], ['services', 'Услуги']].map(([k, l]) => `<button class="${tab === k ? 'on' : ''}" data-a="stab" data-x="${k}">${l}${k === 'services' && ns ? ` <i>${ns}</i>` : ''}</button>`).join('')}</div>`;
          const schips = `<div class="chips scroll srch-flt"><button class="chip flt${searchFltCount() ? ' on' : ''}" data-a="filters">${ic('filter', searchFltCount() ? '#fff' : INK, 16)}Фильтры${searchFltCount() ? `<span class="cnt">${searchFltCount()}</span>` : ''}</button>${[['popular', 'Популярные'], ['price_asc', 'Дешевле'], ['price_desc', 'Дороже']].map(([k, t]) => `<button class="chip${ui.sort === k ? ' on' : ''}" data-a="sort" data-x="${k}">${t}</button>`).join('')}</div>`;
          const prodBlock = () => {
            if (L.items.length) return schips + `<div class="lcount">Товары · ${count(L.total)}</div><div class="grid">${L.items.map(pcard).join('')}</div>` + (L.done ? '' : `<div class="more">${L.loading ? 'Загружаем ещё…' : `<button class="btn outline" data-a="more">Показать ещё</button>`}</div>`);
            if (L.err) return errBox(L.err, 'search');
            if (!L.done) return schips + `<div class="grid">${skelCards(4)}</div>`;
            return searchFltCount() ? schips + `<div class="empty sm"><p>По выбранным фильтрам товаров нет.</p><button class="btn outline" data-a="fltReset">Сбросить фильтры</button></div>` : '';
          };
          const empty = `<div class="empty"><div class="sq c-blue">${ic('search', BLUE, 32)}</div><h3>Ничего не нашли</h3><p>Попробуйте другое слово или модель телефона. Или позвоните — мастер подберёт деталь.</p>
<button class="btn" data-a="probSvc" data-x="other|">${ic('wrench', INK, 18)}Записаться на диагностику</button><button class="btn outline" data-a="call">${ic('phone', INK, 18)}Позвонить</button></div>`;
          let body;
          if (tab === 'products') {
            body = prodBlock() || empty;
          } else if (tab === 'services') {
            body = svc.loading && !svc.items ? `<div class="lcount">Ищем услуги…</div>`
              : svc.items && svc.items.length ? `<div class="slist" style="margin-top:12px">${svc.items.map(scard).join('')}</div>`
                : `<div class="empty"><div class="sq c-blue">${ic('wrench', BLUE, 32)}</div><h3>Услуг по запросу нет</h3><p>Опишите задачу — перезвоним и подскажем. Или выберите услугу в разделе «Ремонт».</p><button class="btn" data-a="nav" data-x="repair">${ic('wrench', '#fff', 18)}Все услуги</button><button class="btn outline" data-a="call">${ic('phone', INK, 18)}Позвонить</button></div>`;
          } else {
            const sv = svc.items && svc.items.length ? `<div class="sec-h" style="margin:18px 20px 10px"><h3 style="font:700 18px 'Manrope'">Услуги · ${svc.items.length}</h3>${svc.items.length > 3 ? `<button data-a="stab" data-x="services">Все</button>` : ''}</div><div class="slist">${svc.items.slice(0, 3).map(scard).join('')}</div>` : '';
            const pb = prodBlock();
            body = (sv + pb) || ((svc.loading || L.loading) ? `<div class="grid">${skelCards(4)}</div>` : empty);
          }
          $('#results').innerHTML = tabs + body;
        };
        searchRender = show;
        q.addEventListener('input', () => { ui.query = q.value; clearTimeout(timer); timer = setTimeout(show, 350); });
        q.addEventListener('keydown', e => { if (e.key === 'Enter') { if (q.value.trim().length >= 2) rememberQuery(q.value.trim()); q.blur(); } });
        show();
        if (!ui.query) setTimeout(() => q.focus(), 50);
      },
    };
  },

  product(id) {
    const full = need('p:' + id, '/catalog/products/' + encodeURIComponent(id), 10 * MIN, true);
    const p = full || prod(id);
    if (!p) {
      return { html: `<div class="scr light nodock">${header('Товар')}${ERR['p:' + id] ? errBox(errText('p:' + id), 'p:' + id) : '<div class="loading">Открываем товар…</div>'}</div>` };
    }
    const fav = S.fav.includes(id), svc = installService(id);
    const inst = !!(svc && ui.install[id]), sum = (p.price || 0) + (inst ? svc.price_from : 0), added = inCart(id);
    const pre = p.availability === 'preorder';
    const avail = !p.in_stock && !pre ? '<span class="pill soft">Нет в наличии</span>' : pre ? `<span class="pill wait"><i></i>Под заказ${p.preorder_days ? ' · ' + p.preorder_days + ' дн.' : ''}</span>` : '<span class="pill ok"><i></i>В наличии</span>';
    const c = cfg();
    const info = pre
      ? `<div class="r"><span class="sq c-blue" style="width:40px;height:40px">${ic('store', BLUE, 20)}</span><span><div class="t">В пункт выдачи ${p.preorder_days ? 'через ' + p.preorder_days + ' ' + plural(p.preorder_days, 'день', 'дня', 'дней') : 'под заказ'}</div><div class="s">${esc(shortAddr())} · самовывоз бесплатно</div></span></div>`
      : `<div class="r"><span class="sq c-green" style="width:40px;height:40px">${ic('store', GREEN, 20)}</span><span><div class="t">Самовывоз сегодня — бесплатно</div><div class="s">${esc(shortAddr())} · ${closeAt()}${points().length > 1 ? ` · <button class="linkb" data-a="pickup" style="padding:0;font-size:13px">ещё ${points().length - 1} ${plural(points().length - 1, 'пункт', 'пункта', 'пунктов')}</button>` : ''}</div></span></div>`
        + (c.features.courier ? `<div class="r"><span class="sq c-blue" style="width:40px;height:40px">${ic('truck', BLUE, 20)}</span><span><div class="t">Курьер по Пушкино</div><div class="s">Позвоним перед выездом</div></span></div>` : '');
    const upsell = svc ? `<button class="card upsell${inst ? ' on' : ''}" data-a="upsell" data-x="${esc(id)}"><span class="sq c-blue">${ic('wrench', BLUE, 22)}</span>
<span><div class="t">Установить у мастера</div><div class="s">${esc(svc.duration)} · от ${money(svc.price_from)}</div></span><span class="tg"><i></i></span></button>` : '';
    const images = (p.images && p.images.length ? p.images : [p.image]).filter(Boolean);
    const gallery = images.length ? `<div class="gal" id="gal">${images.map(src => `<div class="gi">${img(src, p.name)}</div>`).join('')}</div>${images.length > 1 ? `<div class="dots">${images.map((_, i) => `<i${i === ui.gallery ? ' class="on"' : ''}></i>`).join('')}</div>` : ''}`
      : `<div class="gal"><div class="gi">${img('')}</div></div>`;
    const attrs = full && full.attributes && full.attributes.length ? `<div class="kicker" style="margin-top:20px">Характеристики</div><div class="card attrs">${full.attributes.map(a => `<div class="ar"><span>${esc(a.name)}</span><b>${esc(a.value)}</b></div>`).join('')}</div>` : '';
    const desc = full && full.description ? `<div class="kicker" style="margin-top:20px">Описание</div><div class="desc${ui.descOpen ? ' open' : ''}">${esc(full.description)}</div>${full.description.length > 220 && !ui.descOpen ? '<button class="linkb" data-a="desc">Показать полностью</button>' : ''}` : '';
    // Главный вопрос покупателя запчасти — «подойдёт ли к моему телефону?» — сразу под ценой
    const mine = fitsMine(p);
    const fits = mine ? `<div class="fitbox yes">${ic('check', GREEN, 22, 2.6)}<span>Подходит к вашему ${esc(phoneShort())}<small>Сверили с моделью этого телефона</small></span></div>`
      : p.fits && p.fits.length ? `<div class="fitbox no"><span>Подходит для</span><div class="row">${p.fits.map(f => `<span class="pill soft">${esc(f)}</span>`).join('')}</div>
<small>Ваш телефон — ${esc(phoneShort())}. Не уверены? <button class="linkb" data-a="call">Спросите мастера</button></small></div>` : '';
    const warranty = (full && full.attributes || []).find(a => /гарант/i.test(a.name));
    const other = p.seller && p.seller.id && p.seller.id !== 'argeda';
    const seller = other ? `<span class="pill soft">Продавец ${esc(p.seller.name)}</span>` : '';
    // Товар продавца маркетплейса: кто продаёт и как ему написать (чат с продавцом — на сайте)
    const sellerRow = other ? `<div class="r"><span class="sq c-violet" style="width:40px;height:40px">${ic('store', VIOLET, 20)}</span><span style="flex:1"><div class="t">Продавец: ${esc(p.seller.name)}</div><div class="s">Отправляет в пункт выдачи ARGEDA · <button class="linkb" data-a="sellerChat" data-x="${esc(id)}" style="padding:0;font-size:13px">написать продавцу</button></div></span></div>` : '';
    const rt = ratingOf(full || p);
    const rating = rt ? `<button class="rating" data-a="moreRev" data-x="${esc(id)}">${stars(rt.value, 15)}<b>${String(rt.value).replace('.', ',')}</b><span>· ${rt.count} ${plural(rt.count, 'оценка', 'оценки', 'оценок')}</span></button>` : '';
    // Отзывы и рейтинг: с сервера, если он их отдаёт; пока нет — в тестовой сборке демо (помечено)
    ui.revAll = ui.revAll || {};
    const reviews = (() => {
      const rv = reviewsOf(full || p);
      if (!rt) return full ? `<div class="sec-h rev-h"><h3>Отзывы</h3></div>
<div class="card revempty">${ic('star', GREY, 26, 0, true)}<div><div class="t">Оценок пока нет</div><div class="s">Купите и оцените товар — ваш отзыв увидят другие покупатели.</div></div></div>` : '';
      const val = rt ? rt.value : 0, count = rt ? rt.count : rv.items.length;
      const dist = [0, 0, 0, 0, 0];
      if (rv.items.length) rv.items.forEach(x => dist[Math.max(1, Math.min(5, Math.round(x.value))) - 1]++);
      else { dist[4] = Math.round(count * 0.68); dist[3] = Math.round(count * 0.2); dist[2] = Math.round(count * 0.08); dist[1] = Math.round(count * 0.03); dist[0] = Math.max(0, count - dist[4] - dist[3] - dist[2] - dist[1]); }
      const maxd = Math.max(1, ...dist);
      const bars = [5, 4, 3, 2, 1].map(st => `<div class="rbar"><span>${st}</span>${ic('star', '#F5A623', 11, 0, true)}<i><b style="width:${Math.round(dist[st - 1] / maxd * 100)}%"></b></i><em>${dist[st - 1]}</em></div>`).join('');
      const all = ui.revAll[id], shown = all ? rv.items : rv.items.slice(0, 3);
      const list = shown.map(x => `<div class="rev"><div class="rev-top"><span class="rev-av">${esc((x.name || 'П').slice(0, 1))}</span><div class="rev-id"><div class="rev-nm">${esc(x.name || 'Покупатель')}</div>${stars(x.value, 12)}</div><span class="rev-dt">${esc(revDate(x))}</span></div>${x.text ? `<div class="rev-tx">${esc(x.text)}</div>` : ''}${x.photos.length ? `<div class="rev-ph">${x.photos.slice(0, 6).map(u => img(u)).join('')}</div>` : ''}${x.reply ? `<div class="rev-rp"><b>Ответ продавца</b>${esc(x.reply)}</div>` : ''}</div>`).join('');
      const more = rv.loading && !rv.items.length ? '<div class="lcount">Загружаем отзывы…</div>'
        : !all && rv.items.length > 3 ? `<button class="btn outline sm" data-a="moreRev" data-x="${esc(id)}">Показать все ${count}</button>`
          : rv.has_more ? `<button class="btn outline sm" data-a="moreRevPage" data-x="${esc(id)}">${rv.loading ? 'Загружаем…' : 'Ещё отзывы'}</button>` : all && rv.items.length > 3 ? `<button class="btn outline sm" data-a="moreRev" data-x="${esc(id)}">Свернуть</button>` : '';
      return `<div class="sec-h rev-h"><h3>Отзывы</h3></div>
<div class="card rev-sum"><div class="rev-big"><div class="v">${String(Math.round(val * 10) / 10).replace('.', ',')}</div>${stars(val, 16)}<div class="s">${count} ${plural(count, 'оценка', 'оценки', 'оценок')}</div></div><div class="rev-bars">${bars}</div></div>
<div class="revs">${list}</div>${more}`;
    })();
    // Ниже карточки: та же модель телефона (стекло, крышка, аккумулятор) и похожие товары
    const pq = full ? partQuery(full) : '';
    const sameModel = pq ? picks('pm:' + pq, `q=${encodeURIComponent(pq)}&limit=13`) : [];
    const similar = full && full.category_id ? picks('pc:' + full.category_id, `category=${encodeURIComponent(full.category_id)}&limit=13`) : [];
    const notThis = list => list && list.filter(x => x.id !== id).slice(0, 12);
    const line = () => S.cart.find(x => x.kind === 'p' && x.id === id);
    const buy = added
      ? `<div class="pstep"><button data-a="qty" data-x="${esc(id)}|-1" aria-label="Меньше">${ic(line().qty > 1 ? 'minus' : 'trash', INK, 18)}</button><span>${line().qty}</span><button data-a="qty" data-x="${esc(id)}|1" aria-label="Больше">${ic('plus', INK, 18)}</button></div>
<button class="btn dark" data-a="nav" data-x="cart">${ic('bag', 'currentColor', 20)}В корзине</button>`
      : `<div class="sum"><div class="s">${inst ? 'Товар + установка' : 'Цена'}</div><div class="v">${money(sum)}</div></div>
<button class="btn" data-a="toCart" data-x="${esc(id)}" ${!p.in_stock && !pre ? 'disabled' : ''}>${ic('bag', '#fff', 20)}${!p.in_stock && !pre ? 'Нет в наличии' : 'В корзину'}</button>`;
    return {
      html: `<div class="scr nodock"><div class="pimg"><div class="bar"><button class="icon-btn white" data-a="back" aria-label="Назад">${ic('back', '#0E1726', 22)}</button>
<div><button class="icon-btn white" data-a="share" data-x="${esc(id)}" aria-label="Поделиться">${ic('share', '#0E1726', 20)}</button>
<button class="icon-btn white${fav ? ' fav-on' : ''}" data-a="fav" data-x="${esc(id)}" aria-label="${fav ? 'Убрать из избранного' : 'В избранное'}">${heart(fav, 20)}</button></div></div>
${gallery}</div>
<div class="pbody"><div class="row" style="gap:8px;flex-wrap:wrap">${avail}${warranty ? `<span class="pill blue">${ic('shield', BLUE, 14)}Гарантия ${esc(warranty.value)}</span>` : ''}${seller}</div>
<div class="ptitle">${esc(p.name)}</div>${rating}<div class="price">${money(p.price)}${p.old_price ? `<s>${money(p.old_price)}</s>` : ''}</div>
${fits}<div class="card info">${info}${sellerRow}</div>${upsell}${attrs}${desc}${!full && loading('p:' + id) ? '<div class="loading sm">Загружаем фото и характеристики…</div>' : ''}</div>
${reviews}
${pq ? strip('Для этой же модели', notThis(sameModel), { cls: 'more-h', sub: 'Стёкла, крышки, аккумуляторы и другое' }) : ''}
${full && full.category_id ? strip('Похожие товары', notThis(similar), { cls: 'more-h' }) : ''}</div>
<div class="bbar">${buy}</div>`,
      after() {
        const g = $('#gal');
        if (g) g.addEventListener('scroll', () => {
          const i = Math.round(g.scrollLeft / g.clientWidth);
          if (i !== ui.gallery) { ui.gallery = i; document.querySelectorAll('.pimg .dots i').forEach((d, k) => d.classList.toggle('on', k === i)); }
        }, { passive: true });
      },
    };
  },

  cart() {
    if (!S.cart.length) {
      return { html: `<div class="scr light nodock">${header('Корзина')}<div class="empty"><div class="sq c-blue">${ic('bag', BLUE, 32)}</div><h3>Корзина пуста</h3>
<p>Добавьте запчасть — и мастер сразу установит её, если нужно.</p><button class="btn" data-a="nav" data-x="catalog">В каталог</button></div></div>` };
    }
    const q = quoteFor(), qd = q && q.data;
    const qLine = id => qd && qd.lines.find(l => l.product_id === id);
    const warns = id => (qd ? qd.warnings.filter(w => w.product_id === id) : []).map(w => w.code === 'price_changed' ? `Цена изменилась: было ${money(w.old)}` : w.message || 'Проверьте товар').join(' · ');
    const lines = S.cart.map(l => {
      if (l.kind === 'p') {
        const p = S.snap[l.id] || prod(l.id) || need('p:' + l.id, '/catalog/products/' + encodeURIComponent(l.id), 10 * MIN, true) || { name: 'Товар', price: 0 }, ql = qLine(l.id), sel = isSel(l.id);
        // «Нет в продаже» только для отмеченного товара, которого нет в пересчёте; снятые товары сервер не считает
        const gone = sel && qd && !ql, price = ql ? ql.sum : (p.price || 0) * l.qty, old = (ql && ql.old_price) || p.old_price;
        const disc = old && old > (ql ? ql.price : p.price) ? Math.round((1 - (ql ? ql.price : p.price) / old) * 100) : 0;
        const note = gone ? '<div class="warn">Товар больше не продаётся — уберите его</div>' : warns(l.id) ? `<div class="warn">${esc(warns(l.id))}</div>`
          : ql && ql.availability === 'preorder' ? '<div class="av pre" style="margin-top:4px">Под заказ</div>' : '<div class="av today" style="margin-top:4px">Сегодня в пункте</div>';
        // Установку мастером можно добавить прямо из корзины
        const svc = installService(l.id), hasSvc = S.cart.some(x => x.kind === 's' && x.for === l.id);
        const inst = svc && !hasSvc && !gone ? `<button class="inst" data-a="addInst" data-x="${esc(l.id)}">${ic('wrench', BLUE, 14)}Установить у мастера · от ${money(svc.price_from)}</button>` : '';
        return `<div class="card item${gone ? ' gone' : ''}${sel ? '' : ' unsel'}"><button class="cck${sel ? ' on' : ''}" data-a="csel" data-x="${esc(l.id)}" aria-label="Выбрать">${sel ? ic('check', '#fff', 13, 3) : ''}</button><button class="ph" data-a="go" data-x="product|${esc(l.id)}">${img(p.image)}</button><div class="mid"><div class="cpr">${money(price)}${old ? `<s>${money(old)}</s>` : ''}${disc ? `<span class="cd">−${disc}%</span>` : ''}</div><div class="n">${esc(ql ? ql.name : p.name)}</div>${note}${inst}
<div class="cbot"><div class="step">${ic('minus', INK, 16).replace('<svg', `<svg data-a="qty" data-x="${esc(l.id)}|-1"`)}<b>${l.qty}</b>${ic('plus', INK, 16).replace('<svg', `<svg data-a="qty" data-x="${esc(l.id)}|1"`)}</div><button class="icon-btn" data-a="qty" data-x="${esc(l.id)}|-${l.qty}" aria-label="Убрать">${ic('trash', GREY, 18)}</button></div></div></div>`;
      }
      return `<div class="card item svc-line"><span class="cck-sp"></span><div class="ph svc-ph">${ic('wrench', BLUE, 26)}</div><div class="mid"><div class="n">${esc(l.name)}</div><div class="s">Мастер ARGEDA · от ${money(l.price)}</div></div>
<button class="icon-btn" data-a="rmSvc" data-x="${esc(l.for)}" aria-label="Убрать">${ic('trash', GREY, 18)}</button></div>`;
    }).join('');
    const prods = S.cart.filter(l => l.kind === 'p'), allSel = prods.length && prods.every(l => isSel(l.id));
    const master = prods.length ? `<div class="call"><button class="cck${allSel ? ' on' : ''}" data-a="selAll" aria-label="Выбрать всё">${allSel ? ic('check', '#fff', 13, 3) : ''}</button>Выбрать всё<button class="icon-btn" data-a="clear" aria-label="Очистить" style="margin-left:auto">${ic('trash', '#6A7487', 20)}</button></div>` : '';
    // Что пригодится к заказу: товары для той же модели из других разделов
    const first = S.cart.find(l => l.kind === 'p'), fp = first && (S.snap[first.id] || prod(first.id));
    const pq = fp ? partQuery(fp) : '';
    const extra = pq ? picks('pm:' + pq, `q=${encodeURIComponent(pq)}&limit=13`) : [];
    const extraItems = extra && extra.filter(x => !inCart(x.id) && (!fp.category_id || x.category_id !== fp.category_id)).slice(0, 10);
    const courier = courierPrice();
    const pickup = S.delivery === 'pickup', c = cfg();
    const where = pickup
      ? `<button class="card addr" data-a="pickup">${ic('pin', BLUE, 18)}<span style="flex:1">${esc(shortAddr())} · ${closeAt()}</span>${ic('chev-r', GREY, 18)}</button>`
      : `<div class="card field">${ic('pin', BLUE, 18)}<input id="addr" placeholder="Улица, дом, квартира" value="${esc(S.courierAddr)}" autocomplete="street-address"></div>`;
    const t = qd ? qd.totals : null;
    const selL = selLines(), nSel = selL.length;
    const localGoods = selL.reduce((s, l) => s + ((S.snap[l.id] || {}).price || 0) * l.qty, 0);
    const n = selL.reduce((s, l) => s + l.qty, 0);
    const delivery = !pickup && t ? (t.delivery ? money(t.delivery) : 'Уточним при звонке') : pickup ? 'Бесплатно' : '—';
    const total = nSel ? (t ? t.total : localGoods) : 0;
    const state = !nSel ? '' : qd ? '' : ERR.quote ? `<div class="warn box">${esc(errText('quote'))} Показаны цены, сохранённые на телефоне.</div>` : '<div class="lcount">Сверяем цены и наличие…</div>';
    const payMethod = payShow() ? effPay() : 'on_receipt';
    const payBlock = payShow() ? `<div class="sec-h"><h3>Оплата</h3></div>
<div class="opts"><button class="opt${payMethod === 'online' ? ' on' : ''}" data-a="pay" data-x="online"><span class="sq c-blue" style="width:38px;height:38px">${ic('card', BLUE, 20)}</span><div class="t">Онлайн${!payOn() && S.test ? ' · демо' : ''}</div><div class="s">Картой или СБП — спишется сейчас</div></button>
<button class="opt${payMethod === 'on_receipt' ? ' on' : ''}" data-a="pay" data-x="on_receipt"><span class="sq c-green" style="width:38px;height:38px">${ic('store', GREEN, 20)}</span><div class="t">При получении</div><div class="s">Картой или наличными</div></button></div>` : '';
    return {
      html: `<div class="scr light nodock">${header('Корзина')}
${master}${lines}${state}
<div class="sec-h"><h3>Как получить</h3></div>
<div class="opts"><button class="opt${pickup ? ' on' : ''}" data-a="delivery" data-x="pickup"><span class="sq c-green" style="width:38px;height:38px">${ic('store', GREEN, 20)}</span><div class="t">Самовывоз</div><div class="s">Бесплатно · ${points().length} ${plural(points().length, 'пункт', 'пункта', 'пунктов')}</div></button>
${c.features.courier ? `<button class="opt${pickup ? '' : ' on'}" data-a="delivery" data-x="courier"><span class="sq c-blue" style="width:38px;height:38px">${ic('truck', BLUE, 20)}</span><div class="t">Курьер${courier != null ? ' · ' + (courier ? money(courier) : 'бесплатно') : ''}</div><div class="s">${c.courier && c.courier.eta ? esc(c.courier.eta) : 'По Пушкино'}</div></button>` : ''}</div>
${where}
${payBlock}
<div class="card summary"><div class="r">Товары, ${n}&nbsp;шт.<b>${money(nSel ? (t ? t.goods : localGoods) : 0)}</b></div>${t && t.services ? `<div class="r">Установка<b>${money(t.services)}</b></div>` : ''}
<div class="r">${pickup ? 'Самовывоз' : 'Доставка'}<b${pickup ? ' class="free"' : ''}>${nSel ? delivery : '—'}</b></div>
<div class="r tot">Итого<b>${money(total)}</b></div></div>
<p class="note">${payMethod === 'online' ? 'Оплата онлайн — картой или СБП, на защищённой странице банка. Неоплаченный заказ отменяется через 30 минут. ' : 'Оплата при получении — картой или наличными. '}Цены и итог подтверждает магазин.</p>
${pq ? strip('Пригодится к заказу', extraItems, { cls: 'more-h', sub: 'Для той же модели телефона' }) : ''}</div>
<div class="bbar"><button class="btn" data-a="checkout" ${nSel && qd && qd.lines.length ? '' : 'disabled'}>${nSel ? (payMethod === 'online' ? `Перейти к оплате · ${money(total)}` : `К оформлению · ${money(total)}`) : 'Выберите товары'}</button></div>`,
      after() {
        const a = $('#addr');
        if (a) a.addEventListener('change', () => { S.courierAddr = a.value; S.payKey = ''; save(); refresh(); });
        if (a) a.addEventListener('input', () => { S.courierAddr = a.value; save(); });
      },
    };
  },

  order(key) {
    let o = allOrders().find(x => x.key === key);
    // Заказа нет в списке (например, пришли по ссылке с сайта) — спрашиваем сервер по номеру
    if (!o && S.token) { const d = need('o:' + key, '/orders/' + encodeURIComponent(key), 30 * 1000); if (d && d.number) o = fromOrder(d); }
    if (!o && S.token && (loading('o:' + key) || loading('my-orders') || !MEM['my-orders'])) return { html: `<div class="scr light nodock">${header('Заказ ' + key)}<div class="loading">Открываем заказ…</div></div>` };
    if (!o) return { html: `<div class="scr light nodock">${header('Заказ')}<div class="empty"><h3>Заказ не найден</h3><p>Возможно, он оформлен на другом телефоне. Войдите с номером и паролем от сайта, чтобы увидеть все заказы.</p></div></div>` };
    if (S.token && o.kind === 'shop' && o.id) {
      const fresh = need('o:' + o.id, '/orders/' + encodeURIComponent(o.id), 30 * 1000);
      if (fresh) {
        const local = o;
        o = keepPoint(fromOrder(fresh), o);
        // Демо-оплату (тестовая сборка) сервер не знает — сохраняем её поверх ответа
        if (local.demoPay) { o.demoPay = true; if (!o.payment_status) o.payment_status = local.payment_status; }
        if (S.orders.some(x => x.key === o.key)) saveOrder(o);
      }
    }
    const c = cfg();
    const op = orderPoint(o);
    const head = o.status === 'ready' ? { h: 'Можно забирать', s: `Пункт выдачи ARGEDA · ${esc(short(op.address))}<br>${closeOf(op)} · ${payText(o)}` }
      : o.kind === 'repair' ? { h: o.when ? 'Ждём вас ' + esc(o.when) : 'Вы записаны', s: `Мастерская ARGEDA · ${esc(workshopAddr())}<br>Платите после ремонта` }
        : o.status === 'done' ? { h: 'Заказ выдан', s: 'Спасибо, что выбрали ARGEDA!' } : o.status === 'cancelled' ? { h: 'Заказ отменён', s: o.payment_method === 'online' && o.payment_status !== 'paid' ? 'Оплата не поступила за 30 минут. Соберите заказ заново — мы поможем' : 'Если это ошибка — позвоните нам' }
          : { h: o.status === 'in_work' ? 'Собираем заказ' : 'Заказ оформлен', s: o.delivery.type === 'courier' ? 'Курьер привезёт заказ — перед выездом позвоним' : `Соберём заказ и сообщим, когда можно забирать<br>Пункт выдачи: ${esc(short(op.address))}, ${closeOf(op)}` };
    const isReady = o.status === 'ready';
    const qr = isReady && o.pickup_code
      ? `${QR.qrSvg(o.pickup_code, 120)}<div><div class="kicker">Код выдачи</div><div class="code">${esc(o.number)}</div><div class="s">Покажите QR на кассе — заказ выдадут без поиска по номеру</div></div>`
      : `<div class="noqr">${ic(isReady && o.kind !== 'repair' ? 'check' : 'clock', isReady && o.kind !== 'repair' ? GREEN : GREY, 32)}</div><div><div class="kicker">${o.kind === 'repair' ? 'Номер записи' : 'Номер заказа'}</div><div class="code">${esc(o.number)}</div><div class="s">${o.kind === 'repair' ? 'Назовите номер мастеру при визите' : isReady ? 'Заказ готов — назовите номер на кассе при получении' : 'QR-код для выдачи появится, когда заказ будет готов'}</div></div>`;
    const steps = o.steps.map((s, i) => `<div class="r">${i < o.steps.length - 1 ? `<span class="ln${o.steps[i + 1].done ? '' : ' wait'}"></span>` : ''}
<span class="c${s.done ? '' : ' wait'}">${s.done ? ic('check', '#fff', 14, 3) : ''}</span><div><div class="t${s.done ? '' : ' wait'}">${esc(s.t)}</div><div class="s">${esc(s.s)}</div></div></div>`).join('');
    let items = '';
    if (o.kind === 'shop' && (o.lines.length || o.services.length)) {
      items = `<div class="card items"><div class="row between"><span class="kicker">В заказе</span><span class="u16">${money(o.totals.total)}</span></div>
${o.lines.map(l => `<div class="ol"><span>${esc(l.name || 'Товар')}${l.qty > 1 ? ' × ' + l.qty : ''}</span><b>${money(l.sum != null ? l.sum : (l.price || 0) * (l.qty || 1))}</b></div>`).join('')}
${o.services.map(s => `<div class="ol"><span>${esc(s.name)}</span><b>${money(s.price)}</b></div>`).join('')}
<div class="s" style="margin-top:8px">${o.delivery.type === 'courier' ? 'Доставка курьером' : 'Самовывоз, ' + esc(short(op.address))} · ${payText(o)}</div></div>`;
    } else if (o.kind === 'repair') {
      items = `<div class="card items"><div class="row between"><span class="kicker">Ремонт</span>${o.price_from ? `<span class="u16">от ${money(o.price_from)}</span>` : ''}</div><div class="ol"><span>${esc(o.title)} · ${esc(o.sub)}</span></div></div>`;
    }
    // Карточка онлайн-оплаты: оплачено / ждёт оплаты / не прошло
    const ps = o.payment_status;
    const payCard = (o.kind !== 'shop' || !ps || ps === 'none') ? ''
      : ps === 'paid' ? `<div class="card payc paid">${ic('check', GREEN, 24, 3)}<div><div class="t">Оплачено онлайн</div><div class="s">Оплата получена, спасибо!</div></div></div>`
        : ps === 'refunded' ? `<div class="card payc paid">${ic('card', GREEN, 24)}<div><div class="t">Оплата возвращена</div><div class="s">Деньги вернулись на карту.</div></div></div>`
          : `<div class="card payc"><div class="row" style="gap:12px"><span class="sq c-blue" style="width:40px;height:40px">${ic('card', BLUE, 20)}</span><div><div class="t">${ps === 'failed' ? 'Оплата не прошла' : 'Ожидает оплаты'}</div><div class="s">${ps === 'failed' ? 'Попробуйте оплатить ещё раз — неоплаченный заказ отменяется через 30 минут' : 'Оплатите картой или СБП в течение 30 минут — потом заказ может быть отменён'}</div></div></div>
<button class="btn" data-a="payNow" data-x="${esc(o.key)}" style="margin-top:12px">${ic('card', '#fff', 18)}${ps === 'failed' ? 'Оплатить снова' : 'Оплатить ' + money((o.totals && o.totals.total) || 0)}</button></div>`;
    return {
      html: `<div class="scr light nodock">${header((o.kind === 'repair' ? 'Запись ' : 'Заказ ') + o.number, `<button class="icon-btn white" data-a="call" aria-label="Позвонить">${ic('help', INK, 20)}</button>`)}
<div class="ready"><div class="wm">${pinwheel(220)}</div><div class="row" style="gap:8px">${statusPill(o)}${testPill(o)}</div><div class="h">${head.h}</div><div class="s">${head.s}</div></div>
${o.test ? '<div class="testline" style="margin:12px 20px 0">Тестовый заказ: в магазин не отправлен</div>' : ''}
${payCard}
<div class="card qr">${qr}</div><div class="card tl">${steps}</div>
<div class="two"><button class="btn outline" data-a="route" data-x="${esc(o.kind === 'repair' ? 'workshop' : op.id || '')}">${ic('route', INK, 18)}Маршрут</button><button class="btn outline" data-a="call">${ic('phone', INK, 18)}Позвонить</button></div>
${items}</div>`,
      after() {
        // Пока заказ ждёт онлайн-оплату — тихо опрашиваем сервер, чтобы поймать «оплачено»
        if (ps === 'pending' && o.id && !o.demoPay) {
          payPoll = setTimeout(() => { forget('o:' + o.id); refresh(); }, 5000);
        }
      },
    };
  },

  orders() {
    const list = allOrders();
    const body = list.length ? list.map(o => `<button class="card olist tap" data-a="go" data-x="order|${esc(o.key)}"><span class="sq c-blue">${ic(o.kind === 'repair' ? 'wrench' : 'bag', BLUE, 22)}</span>
<span style="flex:1;min-width:0"><div class="ot">${esc(o.number)} · ${esc(orderTitle(o))}</div><div class="os">${esc(o.kind === 'repair' ? o.when || o.sub : orderSub(o))}</div></span><span class="col">${statusPill(o)}${testPill(o)}</span></button>`).join('')
      : `<div class="empty"><div class="sq c-blue">${ic('bag', BLUE, 32)}</div><h3>Заказов пока нет</h3><p>${S.token ? 'Здесь появятся заказы из приложения и с сайта.' : 'Войдите с номером и паролем от argeda.ru, чтобы увидеть и заказы с сайта.'}</p>${S.token ? '' : '<button class="btn dark" data-a="login">Войти</button>'}</div>`;
    return { html: `<div class="scr light nodock">${header('Мои заказы')}${S.token && (loading('my-orders') || loading('my-bookings')) ? '<div class="lcount">Обновляем…</div>' : ''}${body}</div>` };
  },

  favs() {
    const items = S.fav.map(id => prod(id) || need('p:' + id, '/catalog/products/' + encodeURIComponent(id), 10 * MIN, true)).filter(Boolean);
    const body = items.length ? `<div class="grid">${items.map(pcard).join('')}</div>`
      : `<div class="empty"><div class="sq c-coral">${ic('heart', CORAL, 32)}</div><h3>Пока пусто</h3><p>Нажмите на сердечко в карточке товара, чтобы сохранить его.</p></div>`;
    return { html: `<div class="scr light nodock">${header('Избранное')}${body}</div>` };
  },

  repair() {
    const R = repairInfo(), rs = R.data, model = deviceModel();
    const days = nextDays();
    if (ui.day >= days.length) ui.day = 0;
    const date = iso(days[ui.day].date);
    const sl = need('slots:' + date, '/repair/slots?date=' + date, MIN);
    const slots = sl ? sl.slots.filter(t => ui.day > 0 || laterToday(t)) : [];
    if (!slots.includes(ui.time)) ui.time = slots[0] || null;
    const services = rs ? rs.services : [];
    const ofProb = id => services.filter(s => s.problem === id).sort((a, b) => a.price_from - b.price_from);
    const cur = ofProb(ui.prob);
    if (!cur.some(s => s.id === ui.svc)) ui.svc = cur.length ? cur[0].id : null;
    const svc = services.find(s => s.id === ui.svc);
    const tiles = PROBS.map(p => { const list = ofProb(p.id), from = list.length ? 'от ' + money(list[0].price_from) : rs ? 'Узнать цену' : '…';
      return `<button class="card pb${p.id === ui.prob ? ' on' : ''}" data-a="prob" data-x="${p.id}"><span class="mk">${ic('check', '#fff', 13, 3)}</span>
<span class="sq ${p.c}" style="width:38px;height:38px">${ic(p.icon, p.col, 20)}</span><span><div class="n">${p.n}</div><div class="p">${from}</div></span></button>`; }).join('');
    const svcList = cur.length > 1 ? `<div class="card svcs">${cur.map(s => `<button class="sv${s.id === ui.svc ? ' on' : ''}" data-a="svc" data-x="${esc(s.id)}"><span class="rd"></span><span style="flex:1"><div class="n">${esc(s.name)}</div><div class="d">${esc(s.duration)}</div></span><b>от ${money(s.price_from)}</b></button>`).join('')}</div>` : '';
    const dchips = days.map((d, i) => `<button class="chip${i === ui.day ? ' on' : ''}" style="height:54px" data-a="day" data-x="${i}">${d.label}<small>${d.small}</small></button>`).join('');
    const tchips = !sl ? (ERR['slots:' + date] ? `<div class="muted">${esc(errText('slots:' + date))}</div>` : '<div class="muted">Смотрим свободное время…</div>')
      : slots.length ? slots.map(t => `<button class="chip${t === ui.time ? ' on' : ''}" data-a="time" data-x="${t}">${t}</button>`).join('')
        : '<div class="muted">На этот день свободного времени нет — выберите другой</div>';
    return {
      tab: 'repair',
      html: `<div class="scr light repair-scr"><div class="title"><h1>Ремонт</h1><button class="icon-btn white" data-a="go" data-x="orders|" aria-label="Мои записи">${ic('clock', INK, 20)}</button></div>
<div class="sub">Мастерская ARGEDA · ${esc(workshopAddr())}</div>
<div class="trust">${trustChips()}</div>
<div class="dev"><span class="sq" style="background:rgba(255,255,255,.1)">${ic('phone-dev', SKY, 22)}</span>
<span style="min-width:0"><div class="k">Ваш телефон</div><div class="t">${esc(rs && rs.model_found && rs.model ? rs.model : model)}</div><div class="s">${rs && !rs.model_found ? 'Цены «от» — точную назовём после диагностики' : S.model ? 'Указали вручную' : 'Определили автоматически'}</div></span><button class="ch" data-a="model">Другой</button></div>
${!rs && ERR[R.key] ? errBox(errText(R.key), R.key) : ''}
<div class="sec-h"><h3>Что случилось?</h3></div><div class="probs">${tiles}</div>${svcList}
<div class="sec-h"><h3>Когда удобно?</h3></div><div class="chips">${dchips}</div>
<div class="chips scroll" style="margin-top:8px">${tchips}</div></div>
<div class="card book"><div style="min-width:0"><div class="t">${esc(svc ? svc.name : 'Диагностика')}</div><div class="s">${svc ? 'от ' + money(svc.price_from) + (svc.duration ? ' · ' + esc(svc.duration) : '') + ' · ' : ''}диагностика бесплатно</div></div>
<button class="btn" data-a="book" ${ui.time && svc ? '' : 'disabled'}>Записаться</button></div>`,
    };
  },

  profile() {
    const orders = allOrders(), nOrders = orders.length, nFav = S.fav.length, c = cfg();
    const active = orders.find(o => !['done', 'cancelled'].includes(o.status));
    const ready = orders.find(o => o.status === 'ready');
    const name = S.user ? (S.user.name || fmtPhone(S.user.phone)) : 'Гость';
    const sub = S.user ? fmtPhone(S.user.phone) : 'Войдите с паролем от argeda.ru — увидите заказы с сайта';
    const q3 = (icon, label, act, x = '') => `<button class="pq" data-a="${act}" data-x="${x}"><span class="pqic">${ic(icon, BLUE, 22)}</span>${label}</button>`;
    // Сервисы ARGEDA — весь бизнес, не только товары
    const SERVICES = [['wrench', 'Ремонт', 'nav', 'repair'], ['dish', 'Спутник. ТВ', 'searchFor', 'спутниковое ТВ'], ['camera', 'Видео', 'searchFor', 'видеонаблюдение'], ['bell', 'Домофоны', 'searchFor', 'домофон']];
    const services = SERVICES.map(([i, t, a, x], k) => `<button class="srv" data-a="${a}" data-x="${esc(x)}"><span class="srvic c${k % 4}">${ic(i, k % 4 === 1 ? VIOLET : k % 4 === 2 ? CORAL : k % 4 === 3 ? GREEN : BLUE, 24)}</span><span>${t}</span></button>`).join('');
    const row = (icon, label, act, x = '', extra = '') => `<button data-a="${act}" data-x="${x}"><span class="sq c-blue" style="width:38px;height:38px">${ic(icon, BLUE, 20)}</span><span class="lbl">${label}</span>${extra}${ic('chev-r', GREY, 18)}</button>`;
    const promo = ready ? `<button class="card readyq tap" data-a="go" data-x="order|${esc(ready.key)}"><span class="sq c-green">${ic('scan', GREEN, 22)}</span><span style="flex:1;min-width:0"><div class="t">Заказ ${esc(ready.number)} готов к выдаче</div><div class="s">Покажите QR на кассе · ${esc(short(orderPoint(ready).address))}</div></span>${ic('chev-r', GREY, 18)}</button>`
      : `<button class="promo prod tap" data-a="pickup" style="margin:12px 16px 0"><span class="im" style="background:rgba(255,255,255,.12)">${ic('truck', '#fff', 26)}</span><div class="tx"><div class="h">Бесплатный самовывоз</div><div class="p">${points().length} ${plural(points().length, 'пункт', 'пункта', 'пунктов')} в Пушкино · ${closeAt()}</div></div></button>`;
    return {
      tab: 'profile',
      html: `<div class="scr light"><div class="phead"><div class="pname">${esc(name)}${S.user ? '' : ''}</div></div>
<div class="psub">${esc(sub)}</div>
<div class="pquick">${q3('user', S.user ? 'Мои данные' : 'Войти', S.user ? 'about' : 'login')}${q3('card', 'Оплата', 'payInfo')}${q3('zap', 'Настройки', 'go', 'settings|')}</div>
<div class="walrow"><button class="wal tap" data-a="go" data-x="${active ? 'order|' + esc(active.key) : 'orders|'}"><div class="ws">Активных заказов</div><div class="wv">${orders.filter(o => !['done', 'cancelled'].includes(o.status)).length}</div><div class="wsub">${active ? esc(active.number) + ' · ' + esc(active.statusText.toLowerCase()) : 'нет'}</div></button></div>
${promo}
<div class="sec-h" style="margin:22px 20px 10px"><h3>Сервисы ARGEDA</h3></div>
<div class="srvrow">${services}</div>
<div class="sec-h" style="margin:22px 20px 10px"><h3>Покупки</h3></div>
<div class="card menu">${row('bag', 'Мои заказы', 'go', 'orders|', `<em>${nOrders}</em>`)}${row('heart', 'Избранное', 'go', 'favs|', `<em>${nFav}</em>`)}${row('pin', 'Пункты выдачи', 'pickup', '', `<em>${esc(short(curPoint().address).replace(/^ул\.\s*/, ''))}</em>`)}${row('phone', 'Позвонить', 'call', '', `<em>${esc(fmtPhone(c.phone))}</em>`)}</div>
${S.user ? `<div style="margin:16px 20px 0"><button class="btn outline" data-a="logout">Выйти из профиля</button></div>` : `<div style="margin:16px 20px 0"><button class="btn dark" data-a="login">Войти как на сайте</button></div>`}
${TEST_BUILD && S.test ? '<div class="testline">Тестовый режим включён — заказы помечаются «тест». Выключить в Настройках.</div>' : ''}</div>`,
    };
  },

  // Отдельный экран настроек — как у маркетплейсов
  settings() {
    const c = cfg();
    const grp = (title, rows) => `<div class="gtitle">${esc(title)}</div><div class="card menu glist">${rows}</div>`;
    const nav = (icon, label, act, x = '', sub = '', right = '') => `<button data-a="${act}" data-x="${x}"><span class="sq c-blue" style="width:38px;height:38px">${ic(icon, BLUE, 20)}</span><span class="lbl">${label}${sub ? `<small>${esc(sub)}</small>` : ''}</span>${right ? `<em>${esc(right)}</em>` : ''}${ic('chev-r', GREY, 18)}</button>`;
    const tgl = (icon, label, sub, on, act) => `<button data-a="${act}"><span class="sq c-blue" style="width:38px;height:38px">${ic(icon, BLUE, 20)}</span><span class="lbl">${label}${sub ? `<small>${esc(sub)}</small>` : ''}</span><span class="tg${on ? ' on' : ''}"><i></i></span></button>`;
    const themeSeg = `<div class="srow col"><div class="lbl" style="padding-left:0">Тема оформления</div><div class="seg">${[['auto', 'Системная'], ['light', 'Светлая'], ['dark', 'Тёмная']].map(([k, t]) => `<button class="${S.theme === k ? 'on' : ''}" data-a="setTheme" data-x="${k}">${t}</button>`).join('')}</div></div>`;
    return {
      html: `<div class="scr light nodock">${header('Настройки')}
${grp('Пункт и доставка', nav('pin', 'Пункты выдачи', 'pickup', '', '', short(curPoint().address).replace(/^ул\.\s*/, '')))}
<div class="gtitle">Приложение</div><div class="card menu glist">${themeSeg}${tgl('bell', 'Вибрация', 'Отклик на важные события', S.vibro !== false, 'vibro')}</div>
${TEST_BUILD ? `<div class="gtitle">Тестовая сборка</div><div class="card menu glist">${tgl('shield', 'Тестовый режим', 'Заказы с пометкой «тест», в магазин не уходят', S.test, 'testMode')}</div>` : ''}
<div style="margin:22px 20px 0">${S.user ? `<button class="btn outline" data-a="logout">Выйти из профиля</button>` : `<button class="btn dark" data-a="login">Войти как на сайте</button>`}</div>
<div style="margin:16px 20px 0"><button class="btn outline" data-a="about">${ic('help', INK, 18)}О приложении ${VERSION}</button></div>
${A && A.checkUpdate ? `<div style="margin:12px 20px 0"><button class="btn outline" data-a="checkUpdate">${ic('zap', INK, 18)}Проверить обновления</button></div>` : ''}</div>`,
    };
  },
};

// Доводы доверять мастерской: только то, что правда. Гарантию и рейтинг пришлёт сервер, когда они появятся в /config
function trustChips() {
  const c = cfg();
  return [[ic('search', GREEN, 15), 'Диагностика бесплатно'], [ic('card', GREEN, 15), 'Оплата после ремонта'],
    c.repair_warranty && [ic('shield', GREEN, 15), 'Гарантия ' + c.repair_warranty],
    c.rating && c.rating.value && [`<span style="color:#F5A623">★</span>`, `${String(c.rating.value).replace('.', ',')} ${c.rating.source ? 'на ' + c.rating.source : ''}`.trim()],
  ].filter(Boolean).map(([i, t]) => `<span>${i}${esc(t)}</span>`).join('');
}

// ---------- Ремонт: дни и время ----------
function nextDays() {
  const out = [];
  for (let i = 0; i < 4; i++) {
    const d = new Date(); d.setHours(0, 0, 0, 0); d.setDate(d.getDate() + i);
    out.push({ date: d, label: i === 0 ? 'Сегодня' : i === 1 ? 'Завтра' : WD[d.getDay()], small: d.getDate() + ' ' + MON[d.getMonth()] });
  }
  return out;
}
function laterToday(t) {
  const [h, m] = t.split(':').map(Number), d = new Date(); d.setHours(h, m, 0, 0);
  return d.getTime() > Date.now() + 30 * MIN;
}

// ---------- Шторка и уведомления ----------
const sheet = $('#sheet'), sheetBg = $('#sheet-bg');
function openSheet(html, after) {
  sheet.innerHTML = '<div class="grip"></div>' + html;
  sheet.classList.add('open'); sheetBg.classList.add('open');
  if (after) after();
}
function closeSheet() { sheet.classList.remove('open'); sheetBg.classList.remove('open'); }
let toastTimer;
function toast(text, label, fn) {
  const t = $('#toast');
  t.innerHTML = esc(text) + (label ? `<button>${label}</button>` : '');
  if (label) t.querySelector('button').onclick = () => { t.classList.remove('show'); fn(); };
  t.style.bottom = dock.classList.contains('hide') ? '110px' : '100px';
  t.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('show'), 2600);
}
// Шторка с номером, именем и комментарием; отправку делает onOk и возвращает текст ошибки или ничего
function contactSheet({ title, text, button, comment, email, onOk }) {
  const c = cfg(), phone = S.user ? fmtPhone(S.user.phone) : S.phone;
  const emailVal = S.email || (S.user && S.user.email) || '';
  openSheet(`<h2>${title}</h2><p>${text}</p>
<div class="field">${ic('phone', BLUE, 18)}<input id="ph" type="tel" inputmode="tel" placeholder="+7 (___) ___-__-__" value="${esc(phone)}" autocomplete="tel"></div>
<div class="field">${ic('user', BLUE, 18)}<input id="nm" placeholder="Имя (необязательно)" value="${esc(S.name || (S.user && S.user.name) || '')}" autocomplete="name"></div>
${email ? `<div class="field">${ic('card', BLUE, 18)}<input id="em" type="email" inputmode="email" placeholder="Почта для чека об оплате" value="${esc(emailVal)}" autocomplete="email"></div>` : ''}
${comment ? `<div class="field">${ic('help', BLUE, 18)}<input id="cm" placeholder="${comment}"></div>` : ''}
<div class="err" id="phErr"></div>
<p class="consent">Нажимая кнопку, вы соглашаетесь на обработку персональных данных — <a href="${esc(c.consent.url)}" data-a="site" data-x="privacy">политика</a>.${S.test ? ' <b>Тестовый режим:</b> в магазин не уйдёт.' : ''}</p>
<button class="btn" id="phOk">${button}</button>`, () => {
    const ph = $('#ph');
    ph.addEventListener('input', () => { ph.value = maskPhone(ph.value); });
    $('#phOk').onclick = async () => {
      const err = $('#phErr');
      if (digits(ph.value).length !== 10) { err.textContent = 'Введите номер полностью: 10 цифр после +7'; err.classList.add('show'); ph.focus(); return; }
      const em = email ? $('#em').value.trim() : '';
      if (email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(em)) { err.textContent = 'Укажите почту — на неё придёт чек об оплате'; err.classList.add('show'); $('#em').focus(); return; }
      S.phone = maskPhone(ph.value); S.name = $('#nm').value.trim(); if (email) S.email = em; save();
      const btn = $('#phOk'); btn.disabled = true; btn.textContent = 'Отправляем…'; err.classList.remove('show');
      const fail = await onOk(Object.assign({ phone: e164(ph.value), name: S.name, comment: comment ? $('#cm').value.trim() : '', consent_version: c.consent.version }, email ? { email: em } : {}));
      if (fail) { err.innerHTML = esc(fail) + `<br><button class="linkb" data-a="call">Позвонить ${esc(fmtPhone(c.phone))}</button>`; err.classList.add('show'); btn.disabled = false; btn.textContent = button; }
    };
  });
}
function successSheet(title, text) {
  openSheet(`<div class="big c-green">${ic('check', GREEN, 30, 3)}</div><h2>${title}</h2><p>${text}</p><button class="btn dark" data-a="closeSheet">Понятно</button>`);
}
// Заявка на выездную услугу: имя, телефон, адрес → POST /services/requests; пока эндпоинта нет — предложим позвонить
function serviceRequest(s) {
  const c = cfg(), phone = S.user ? fmtPhone(S.user.phone) : S.phone;
  openSheet(`<h2>${esc(s.name)}</h2><p>Мастер приедет на адрес. Оставьте контакты — перезвоним, уточним время и цену.</p>
<div class="field">${ic('phone', BLUE, 18)}<input id="srPh" type="tel" inputmode="tel" placeholder="+7 (___) ___-__-__" value="${esc(phone)}" autocomplete="tel"></div>
<div class="field">${ic('user', BLUE, 18)}<input id="srNm" placeholder="Имя (необязательно)" value="${esc(S.name || '')}" autocomplete="name"></div>
<div class="field">${ic('pin', BLUE, 18)}<input id="srAddr" placeholder="Адрес выезда" value="${esc(S.courierAddr)}" autocomplete="street-address"></div>
<div class="err" id="srErr"></div>
<p class="consent">Нажимая кнопку, вы соглашаетесь на обработку персональных данных — <a href="${esc(c.consent.url)}" data-a="site" data-x="privacy">политика</a>.${S.test ? ' <b>Тест:</b> заявка не уйдёт.' : ''}</p>
<button class="btn" id="srOk">Вызвать мастера</button>`, () => {
    const ph = $('#srPh');
    ph.addEventListener('input', () => { ph.value = maskPhone(ph.value); });
    $('#srOk').onclick = async () => {
      const err = $('#srErr');
      if (digits(ph.value).length !== 10) { err.textContent = 'Введите номер полностью: 10 цифр после +7'; err.classList.add('show'); return; }
      const addr = $('#srAddr').value.trim();
      if (!addr) { err.textContent = 'Укажите адрес выезда'; err.classList.add('show'); $('#srAddr').focus(); return; }
      S.phone = maskPhone(ph.value); S.name = $('#srNm').value.trim(); S.courierAddr = addr; save();
      const btn = $('#srOk'); btn.disabled = true; btn.textContent = 'Отправляем…'; err.classList.remove('show');
      try {
        await api('/services/requests', { body: { service_id: s.id, phone: e164(ph.value), name: S.name, address: addr, consent_version: c.consent.version } });
        successSheet('Заявка принята', `Перезвоним по ${esc(fmtPhone(e164(ph.value)))} — уточним время и цену.${S.test ? '<br><br>Тест: заявка не отправлена.' : ''}`);
      } catch (e) {
        err.innerHTML = 'Пока оставить заявку в приложении нельзя. Позвоните — оформим: <button class="linkb" data-a="call">' + esc(fmtPhone(c.phone)) + '</button>';
        err.classList.add('show'); btn.disabled = false; btn.textContent = 'Вызвать мастера';
      }
    };
  });
}
function loginSheet() {
  openSheet(`<h2>Вход</h2><p>Тот же номер и пароль, что на argeda.ru. После входа здесь появятся заказы с сайта.</p>
<div class="field">${ic('phone', BLUE, 18)}<input id="lp" type="tel" inputmode="tel" placeholder="+7 (___) ___-__-__" value="${esc(S.phone)}" autocomplete="tel"></div>
<div class="field">${ic('shield', BLUE, 18)}<input id="lpw" type="password" placeholder="Пароль" autocomplete="current-password"></div>
<div class="err" id="lErr"></div><button class="btn" id="lOk">Войти</button>
<div class="two" style="margin:10px 0 0;padding:0"><button class="btn outline" data-a="site" data-x="register">Регистрация</button><button class="btn outline" data-a="site" data-x="forgot">Забыли пароль?</button></div>`, () => {
    const lp = $('#lp'), pw = $('#lpw');
    lp.addEventListener('input', () => { lp.value = maskPhone(lp.value); });
    const submit = async () => {
      const err = $('#lErr');
      if (digits(lp.value).length !== 10) { err.textContent = 'Введите номер полностью: 10 цифр после +7'; err.classList.add('show'); return; }
      if (!pw.value) { err.textContent = 'Введите пароль'; err.classList.add('show'); pw.focus(); return; }
      const btn = $('#lOk'); btn.disabled = true; btn.textContent = 'Проверяем…'; err.classList.remove('show');
      try {
        const r = await api('/auth/login', { body: { phone: e164(lp.value), password: pw.value, device: deviceModel() } });
        S.phone = maskPhone(lp.value); S.token = r.token; S.user = r.user; save(); forget('my-'); forget('o:'); pullShared(true);
        closeSheet(); toast('Вы вошли — заказы с сайта появятся в «Мои заказы»'); render(true);
      } catch (e) {
        err.textContent = e.code === 'bad_credentials' ? 'Неверный номер или пароль' : e.message;
        err.classList.add('show'); btn.disabled = false; btn.textContent = 'Войти';
      }
    };
    $('#lOk').onclick = submit;
    pw.addEventListener('keydown', e => { if (e.key === 'Enter') submit(); });
    setTimeout(() => (digits(lp.value).length === 10 ? pw : lp).focus(), 100);
  });
}

// ---------- Действия ----------
const ACTIONS = {
  nav(tab) {
    if (tab === 'cart') { go('cart'); return; }
    go(tab, null, { reset: true });
  },
  go(x) { const i = x.indexOf('|'); go(x.slice(0, i), x.slice(i + 1) || undefined); },
  back() { back(); },
  retry(key) {
    if (key === 'feed') { FEED.err = ''; loadFeed(); refresh(); return; }
    if (key === 'list' || key === 'search') { const L = LISTS[LISTS.active]; if (L) { L.err = ''; loadMore(LISTS.active); } if (key === 'search' && searchRender) searchRender(); else refresh(); return; }
    delete ERR[key]; refresh();
  },
  more() { if (LISTS.active) { loadMore(LISTS.active); if (stack[stack.length - 1].name === 'search' && searchRender) searchRender(); else refresh(); } },
  sort(k) { ui.sort = k; render(); },
  add(id) { addToCart(id, false); toast('Добавили в корзину', 'Открыть', () => go('cart')); refresh(); },
  toCart(id) { addToCart(id, !!ui.install[id]); toast('Добавили в корзину', 'Открыть', () => go('cart')); refresh(); },
  upsell(id) { ui.install[id] = !ui.install[id]; refresh(); },
  desc() { ui.descOpen = true; refresh(); },
  moreRev(id) { ui.revAll = ui.revAll || {}; ui.revAll[id] = !ui.revAll[id]; refresh(); },
  moreRevPage(id) { ui.revAll = ui.revAll || {}; ui.revAll[id] = true; loadMoreReviews(id); },
  fav(id) {
    const i = S.fav.indexOf(id);
    if (i >= 0) { S.fav.splice(i, 1); favDel(id); } else { snap(id); S.fav.push(id); favAdd(id); }
    pruneSnaps(); save(); refresh();
    toast(i >= 0 ? 'Убрали из избранного' : 'Сохранили в избранное');
  },
  share(id) {
    const p = prod(id);
    const text = `${p.name} — ${money(p.price)} в ARGEDA, Пушкино. ${p.url || (p.slug ? SITE + 'product/' + encodeURIComponent(p.slug) : SITE)}`;
    try { if (A) { A.share(text); return; } } catch (e) { /* ниже запасной вариант */ }
    if (navigator.share) navigator.share({ text }).catch(() => {}); else toast('Не получилось поделиться');
  },
  qty(x) {
    const [id, d] = x.split('|');
    const line = S.cart.find(l => l.kind === 'p' && l.id === id);
    line.qty += +d; S.payKey = '';
    if (line.qty <= 0) {
      const before = S.cart.slice();
      S.cart = S.cart.filter(l => !(l.kind === 'p' && l.id === id) && !(l.kind === 's' && l.for === id));
      S.unsel = S.unsel.filter(x => x !== id);
      toast('Убрали из корзины', 'Вернуть', () => { S.cart = before; save(); refresh(); });
    }
    pruneSnaps(); save(); syncCart(); refresh();
  },
  rmSvc(id) { S.cart = S.cart.filter(l => !(l.kind === 's' && l.for === id)); S.payKey = ''; save(); refresh(); },
  csel(id) { const i = S.unsel.indexOf(id); if (i >= 0) S.unsel.splice(i, 1); else S.unsel.push(id); S.payKey = ''; save(); refresh(); },
  selAll() { const prods = S.cart.filter(l => l.kind === 'p'); const all = prods.every(l => isSel(l.id)); S.unsel = all ? prods.map(l => l.id) : []; S.payKey = ''; save(); refresh(); },
  clear() {
    const before = S.cart.slice();
    S.cart = []; S.unsel = []; S.payKey = ''; save(); syncCart(); refresh();
    toast('Корзина очищена', 'Вернуть', () => { S.cart = before; save(); refresh(); });
  },
  delivery(x) { S.delivery = x; S.payKey = ''; save(); refresh(); },
  pay(x) { S.pay = x; S.payKey = ''; save(); refresh(); },
  checkout() {
    if (needNet('Оформить заказ')) return;
    if (S.delivery === 'courier' && !S.courierAddr.trim()) { toast('Укажите адрес доставки'); const a = $('#addr'); if (a) a.focus(); return; }
    const q = MEM.quote && MEM.quote.data, t = q ? q.totals : { total: 0 };
    const online = payShow() && effPay() === 'online';
    contactSheet({
      title: 'Подтвердите заказ',
      text: `${money(t.total)} · ${S.delivery === 'pickup' ? 'самовывоз: пункт ' + esc(curPoint().name) + ', ' + esc(shortAddr()) : 'курьер: ' + esc(S.courierAddr)}. ${online ? 'Оплата онлайн — картой или СБП, заказ держим 30 минут до оплаты.' : 'Оплата при получении — картой или наличными.'}`,
      button: online ? 'Оформить и оплатить' : 'Подтвердить заказ', comment: 'Комментарий к заказу (необязательно)', email: online,
      async onOk(contact) {
        // Один ключ на попытку: повторное нажатие после обрыва связи не создаст второй заказ
        if (!S.payKey) { S.payKey = uid(); save(); }
        try {
          // Пока сервер не принимает point_id, пункт дублируем в комментарии — магазин увидит его в заказе
          const pt = S.delivery === 'pickup' ? curPoint() : null;
          const note = pt ? 'Пункт выдачи: ' + short(pt.address) + (contact.comment ? '. ' + contact.comment : '') : contact.comment;
          const o = await api('/orders', { body: Object.assign(cartBody(), contact, { comment: note, payment: online ? 'online' : 'on_receipt' }), headers: { 'Idempotency-Key': S.payKey } });
          const x = fromOrder(o);
          if (pt && !(x.delivery && x.delivery.point_id)) x.delivery = Object.assign({}, x.delivery, { point_id: pt.id, address: pt.address });
          // Если сервер ещё не поднял оплату, в тестовой сборке показываем демо
          if (online && !x.payment_url && !payOn() && S.test) { x.payment_status = 'pending'; x.demoPay = true; }
          saveOrder(x); S.cart = []; S.unsel = []; S.payKey = ''; pruneSnaps(); save(); syncCart(); forget('my-orders');
          closeSheet();
          stack = [{ name: 'home' }, { name: 'order', arg: x.key }];
          render();
          if (online && x.payment_url) { openExternal(x.payment_url); toast('Открываю оплату…'); }
          else if (online && x.demoPay) toast('Заказ создан — осталось оплатить (демо)');
          else if (online) startPay(x.key); // ссылки в ответе не было — запросим её у сервера
          else toast(x.test ? 'Тестовый заказ оформлен — в магазин не отправлен' : 'Заказ оформлен — сообщим, когда можно забирать');
          return '';
        } catch (e) {
          // «Только онлайн»: сервер не создал заказ при оплате при получении
          if (e.status === 409 && e.code === 'online_payment_required') {
            S.pay = 'online'; S.payKey = ''; save(); closeSheet(); render();
            toast('Этот заказ можно оплатить только онлайн. Включил онлайн-оплату — нажмите «Перейти к оплате».');
            return '';
          }
          if (e.code === 'email_required') return 'Для онлайн-оплаты нужна электронная почта — на неё придёт чек. Укажите её и попробуйте ещё раз.';
          return e.offline ? e.message : 'Не получилось оформить заказ: ' + e.message;
        }
      },
    });
  },
  payNow(key) { if (needNet('Оплатить')) return; startPay(key); },
  retryNet() { netPing(); toast('Проверяем связь…'); },
  prob(id) { ui.prob = id; ui.svc = null; if (stack[stack.length - 1].name === 'repair') refresh(); else go('repair', null, { reset: true }); },
  probSvc(x) { const [p, s] = x.split('|'); ui.prob = p; ui.svc = s; go('repair', null, { reset: true }); },
  svc(id) { ui.svc = id; refresh(); },
  day(i) { ui.day = +i; ui.time = null; refresh(); },
  time(t) { ui.time = t; refresh(); },
  book() {
    const model = deviceModel(), R = repairInfo(), rs = R.data, svc = rs && rs.services.find(s => s.id === ui.svc);
    if (!svc || !ui.time) return;
    if (needNet('Записаться')) return;
    const date = iso(nextDays()[ui.day].date), when = fmtDay(date) + ' в ' + ui.time;
    contactSheet({
      title: 'Запись на ремонт',
      text: `${esc(rs.model_found && rs.model ? rs.model : model)} · ${esc(svc.name)}, ${when}. Мастер перезвонит, если понадобится уточнить.`,
      button: 'Записаться на ' + ui.time, comment: 'Что случилось (необязательно)',
      async onOk(contact) {
        try {
          const b = await api('/repair/bookings', { body: Object.assign({ model: rs.model || model, problem: svc.problem, service_id: svc.id, date, time: ui.time }, contact) });
          const x = fromBooking(Object.assign({ date, time: ui.time, model: rs.model || model, duration: svc.duration }, b));
          saveOrder(x); forget('my-'); forget('slots:');
          successSheet('Вы записаны', `Ждём вас ${esc(x.when)}: ${esc(workshopAddr())}. Номер записи ${esc(x.number)}.${x.test ? '<br><br>Тестовый режим: запись в мастерскую не отправлена.' : ''}`);
          refresh();
          return '';
        } catch (e) { return e.offline ? e.message : 'Не получилось записаться: ' + e.message; }
      },
    });
  },
  model() {
    openSheet(`<h2>Ваш телефон</h2><p>Укажите модель, если определилась неточно. Например, Samsung Galaxy A12.</p>
<div class="field">${ic('phone-dev', BLUE, 18)}<input id="md" placeholder="Модель телефона" value="${esc(deviceModel())}"></div>
<button class="btn" id="mdOk">Сохранить</button>${S.model ? '<button class="btn outline" id="mdAuto" style="margin-top:10px">Определить автоматически</button>' : ''}`, () => {
      $('#mdOk').onclick = () => { S.model = $('#md').value.trim(); save(); closeSheet(); refresh(); };
      const auto = $('#mdAuto');
      if (auto) auto.onclick = () => { S.model = ''; save(); closeSheet(); refresh(); };
    });
  },
  // Все пункты выдачи: выбранный — для самовывоза, у каждого маршрут
  pickup() {
    const cur = curPoint();
    openSheet(`<h2>Пункты выдачи</h2><p>Самовывоз бесплатно. Выберите пункт, где удобно забирать заказы.</p>
<div class="pts">${points().map(p => `<div class="pt${p.id === cur.id ? ' on' : ''}"><button class="pt-main" data-a="setPoint" data-x="${esc(p.id)}"><span class="rd"></span>
<span style="flex:1;min-width:0"><div class="t">Пункт ${esc(p.name)}</div><div class="s">${esc(short(p.address))}</div><div class="s">Каждый день ${esc(p.hours)}${p.workshop ? ' · <b>мастерская</b>' : ''}</div></span></button>
<button class="icon-btn" data-a="route" data-x="${esc(p.id)}" aria-label="Маршрут">${ic('route', BLUE, 20)}</button></div>`).join('')}</div>
<button class="btn outline" data-a="call">${ic('phone', INK, 18)}Позвонить ${esc(fmtPhone(cfg().phone))}</button>`);
  },
  setPoint(id) {
    S.point = id; S.payKey = ''; save(); closeSheet(); refresh();
    toast('Заказы будем ждать в пункте ' + curPoint().name);
  },
  call() { location.href = 'tel:' + cfg().phone; },
  route(id) {
    const p = points().find(x => x.id === id) || (id === 'workshop' ? workshop() : curPoint());
    openExternal(p.lat && p.lon ? `https://yandex.ru/maps/?pt=${p.lon},${p.lat}&z=17&l=map` : 'https://yandex.ru/maps/?text=' + encodeURIComponent(p.address));
  },
  site(path) { openExternal(SITE + (path || '')); },
  checkUpdate() { if (A && A.checkUpdate) A.checkUpdate(); },
  // Чат с продавцом живёт на сайте — открываем страницу товара на argeda.ru
  sellerChat(id) { const p = prod(id); openExternal(p && p.url ? p.url : p && p.slug ? SITE + 'product/' + encodeURIComponent(p.slug) : SITE); },
  catTab(x) { ui.catTab = x; refresh(); },
  hint(h) { const q = $('#q'); q.value = h; ui.query = h; q.dispatchEvent(new Event('input')); },
  histClear() { S.hist = []; save(); if (searchRender) searchRender(); },
  searchFor(q) { ui.query = q; go('search'); },
  stab(k) { ui.searchTab = k; if (searchRender) searchRender(); },
  service(id) {
    const s = SVC_BY_ID[id];
    if (!s) return;
    if (s.type === 'onsite') { serviceRequest(s); return; }
    // Услуга в мастерской (ремонт телефона) — открываем «Ремонт» с выбранной проблемой
    if (s.problem) { ui.prob = s.problem; ui.svc = id; }
    go('repair', null, { reset: true });
  },
  banner(key) {
    const srv = SRV_BANNERS.find(b => b.key === key);
    if (srv) { runAction(srv.action); return; }
    if (key === 'screen' || key === 'battery') { ui.prob = key; ui.svc = null; go('repair', null, { reset: true }); return; }
    if (key === 'remotes') { const c = cats().find(x => !x.parent_id && /пульт/i.test(x.name)); if (c) go('list', c.id); return; }
    if (key === 'pickup') ACTIONS.pickup();
    if (key === 'sellers') openExternal('https://seller.argeda.ru/');
  },
  fltModel() { ui.flt.model = !ui.flt.model; render(true); },
  fltReset() { ui.flt = { model: false, today: false, pmin: '', pmax: '', brand: [] }; render(true); },
  filters() {
    const f = ui.flt, more = filtersOn();
    openSheet(`<h2>Фильтры</h2>
<button class="tgrow${f.model ? ' on' : ''}" id="fM"><span class="sq c-blue" style="width:38px;height:38px">${ic('phone-dev', BLUE, 20)}</span><span style="flex:1">Для моего телефона<small>${esc(phoneName())}</small></span><span class="tg${f.model ? ' on' : ''}"><i></i></span></button>
<button class="linkb" data-a="model" style="margin-left:50px">Указать другую модель</button>
${more ? `<button class="tgrow${f.today ? ' on' : ''}" id="fT"><span class="sq c-green" style="width:38px;height:38px">${ic('store', GREEN, 20)}</span><span style="flex:1">Есть в пункте сегодня<small>Без товаров под заказ</small></span><span class="tg${f.today ? ' on' : ''}"><i></i></span></button>
<div class="lbl2">Цена, ₽</div><div class="pair"><div class="field">${'от'}<input id="fPmin" inputmode="numeric" value="${esc(f.pmin)}" placeholder="0"></div><div class="field">${'до'}<input id="fPmax" inputmode="numeric" value="${esc(f.pmax)}" placeholder="любая"></div></div>
<div class="lbl2">Бренд</div><div class="chips wrap" id="fBrands"><span class="muted">Смотрим бренды…</span></div>` : ''}
<button class="btn" id="fOk">Показать</button><button class="btn outline" id="fReset" style="margin-top:10px">Сбросить</button>`, () => {
      const tog = (id, k) => { const el = $(id); if (el) el.onclick = () => { f[k] = !f[k]; el.classList.toggle('on', f[k]); el.querySelector('.tg').classList.toggle('on', f[k]); }; };
      tog('#fM', 'model'); tog('#fT', 'today');
      // Бренды — с сервера, по текущему разделу или запросу (GET /catalog/facets)
      if (more) {
        const box = $('#fBrands');
        const draw = brands => {
          if (!brands.length) { box.innerHTML = '<span class="muted">Брендов в этом списке нет</span>'; return; }
          box.innerHTML = brands.slice(0, 40).map(b => `<button class="chip${f.brand.includes(b.name) ? ' on' : ''}" data-brand="${esc(b.name)}">${esc(b.name)}<small>${(b.count || 0).toLocaleString('ru-RU')}</small></button>`).join('');
          box.querySelectorAll('[data-brand]').forEach(el => { el.onclick = () => { const n = el.dataset.brand, i = f.brand.indexOf(n); if (i >= 0) f.brand.splice(i, 1); else f.brand.push(n); el.classList.toggle('on', i < 0); }; });
        };
        api('/catalog/facets?' + ui.fltCtx + (f.today ? '&in_stock=1' : '')).then(d => draw((d && d.brands) || [])).catch(() => { box.innerHTML = '<span class="muted">Бренды не загрузились</span>'; });
      }
      $('#fOk').onclick = () => {
        if ($('#fPmin')) { f.pmin = $('#fPmin').value.replace(/\D/g, ''); f.pmax = $('#fPmax').value.replace(/\D/g, ''); }
        closeSheet(); render(true);
      };
      $('#fReset').onclick = () => { closeSheet(); ACTIONS.fltReset(); };
    });
  },
  addInst(id) {
    const svc = installService(id);
    if (svc && !S.cart.some(l => l.kind === 's' && l.for === id)) S.cart.push({ kind: 's', for: id, service_id: svc.id, name: svc.name, price: svc.price_from });
    S.payKey = ''; save(); refresh(); toast('Добавили установку у мастера');
  },
  theme() {
    const opt = (k, t) => `<button class="${S.theme === k ? 'on' : ''}" data-a="setTheme" data-x="${k}">${t}</button>`;
    openSheet(`<h2>Оформление</h2><p>Тёмная тема бережёт глаза вечером и экономит заряд на экранах AMOLED.</p>
<div class="themes">${opt('auto', 'Как в телефоне')}${opt('light', 'Светлая')}${opt('dark', 'Тёмная')}</div>`);
  },
  setTheme(k) { S.theme = k; save(); applyTheme(); refresh(); },
  vibro() { S.vibro = S.vibro === false; save(); refresh(); if (S.vibro) buzz(); toast(S.vibro ? 'Вибрация включена' : 'Вибрация выключена'); },
  payInfo() { openSheet(`<div class="big c-green">${ic('card', GREEN, 30)}</div><h2>Оплата</h2><p>${payOn() ? 'Онлайн — картой или СБП на защищённой странице банка при оформлении. Либо при получении: картой или наличными в пункте выдачи, курьеру или мастеру.' : 'Сейчас — при получении: картой или наличными в пункте выдачи, курьеру или мастеру. Онлайн-оплата картой и СБП появится позже.'}</p><button class="btn dark" data-a="closeSheet">Понятно</button>`); },
  login() { loginSheet(); },
  logout() {
    api('/auth/logout', { method: 'POST' }).catch(() => {});
    S.token = ''; S.user = null; save(); forget('my-'); render(true); toast('Вы вышли');
  },
  testMode() {
    S.test = !S.test; save(); forget('my-'); refresh();
    toast(S.test ? 'Тестовый режим включён' : 'Тестовый режим выключен: заказы уходят в магазин');
  },
  about() {
    openSheet(`<div style="margin-bottom:14px">${logo(56)}</div><h2>ARGEDA ${VERSION}</h2><p>Приложение маркета и мастерской ARGEDA в Пушкино: весь каталог сайта, цены и наличие, заказ с самовывозом или курьером, запись на ремонт.${S.test ? '<br><br>Сейчас включён тестовый режим: заказы и записи помечаются «тест» и в магазин не уходят. Выключить можно в профиле.' : ''}</p>
<button class="btn outline" data-a="site" data-x="">Открыть argeda.ru</button>`);
  },
  closeSheet() { closeSheet(); },
};
let searchRender = null;
// Действие баннера с сервера: { type: 'repair' | 'category' | 'product' | 'search' | 'photo' | 'pickup' | 'url', value }
function runAction(a) {
  if (!a) return;
  const v = a.value || '';
  if (a.type === 'repair') { ui.prob = v || ui.prob; ui.svc = null; go('repair', null, { reset: true }); }
  else if (a.type === 'category') go('list', v);
  else if (a.type === 'product') go('product', v);
  else if (a.type === 'search') ACTIONS.searchFor(v);
  else if (a.type === 'pickup') ACTIONS.pickup();
  else if (a.type === 'url' && /^https:\/\//.test(v)) openExternal(v);
}

document.addEventListener('click', e => {
  const el = e.target.closest('[data-a]');
  if (!el || el.disabled) return;
  const fn = ACTIONS[el.dataset.a];
  if (!fn) return;
  e.preventDefault(); e.stopPropagation();
  fn(el.dataset.x || '', el);
});

// Обновления проверяет само приложение (Updater.java по argeda.ru/app/version.json): в браузере проверять нечего

render();
showNet();
if (!NET.online) netPing();
if (S.token) pullShared(false);
