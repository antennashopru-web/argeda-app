'use strict';
// QR-код для выдачи заказа на кассе: побайтовый режим, уровень коррекции M, версии 1–6 (до 106 байт).
// Проверено: матрицы совпадают с библиотекой qrcode, коды читает OpenCV (tools/check_qr.py).
(function () {
  // Версия: [всего кодовых слов, байт коррекции на блок, число блоков]
  const VER = { 1: [26, 10, 1], 2: [44, 16, 1], 3: [70, 26, 1], 4: [100, 18, 2], 5: [134, 24, 2], 6: [172, 16, 4] };

  // Поле Галуа GF(256) с образующим многочленом 0x11D
  const EXP = new Array(512), LOG = new Array(256);
  for (let i = 0, x = 1; i < 255; i++) { EXP[i] = x; LOG[x] = i; x <<= 1; if (x & 0x100) x ^= 0x11D; }
  for (let i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
  const mul = (a, b) => (a && b ? EXP[LOG[a] + LOG[b]] : 0);

  function rsGenerator(n) {
    let g = [1];
    for (let i = 0; i < n; i++) {
      const next = new Array(g.length + 1).fill(0);
      for (let j = 0; j < g.length; j++) { next[j] ^= g[j]; next[j + 1] ^= mul(g[j], EXP[i]); }
      g = next;
    }
    return g;
  }
  function rsRemainder(data, n) {
    const g = rsGenerator(n), res = new Array(n).fill(0);
    for (const b of data) {
      const f = b ^ res.shift(); res.push(0);
      for (let i = 0; i < n; i++) res[i] ^= mul(g[i + 1], f);
    }
    return res;
  }

  function codewords(bytes, v) {
    const [total, ecLen, blocks] = VER[v], dataLen = total - ecLen * blocks;
    const bits = [];
    const put = (val, len) => { for (let i = len - 1; i >= 0; i--) bits.push((val >>> i) & 1); };
    put(4, 4); put(bytes.length, 8); bytes.forEach(b => put(b, 8));
    put(0, Math.min(4, dataLen * 8 - bits.length));
    while (bits.length % 8) bits.push(0);
    const data = [];
    for (let i = 0; i < bits.length; i += 8) data.push(bits.slice(i, i + 8).reduce((a, b) => (a << 1) | b, 0));
    for (let pad = 0xEC; data.length < dataLen; pad ^= 0xEC ^ 0x11) data.push(pad);
    // В версиях 1–6 при уровне M все блоки одной длины
    const per = dataLen / blocks, dBlocks = [], eBlocks = [];
    for (let b = 0; b < blocks; b++) {
      const d = data.slice(b * per, (b + 1) * per);
      dBlocks.push(d); eBlocks.push(rsRemainder(d, ecLen));
    }
    const out = [];
    for (let i = 0; i < per; i++) dBlocks.forEach(d => out.push(d[i]));
    for (let i = 0; i < ecLen; i++) eBlocks.forEach(e => out.push(e[i]));
    return out;
  }

  const MASKS = [
    (x, y) => (x + y) % 2 === 0, (x, y) => y % 2 === 0, (x, y) => x % 3 === 0, (x, y) => (x + y) % 3 === 0,
    (x, y) => (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0, (x, y) => (x * y) % 2 + (x * y) % 3 === 0,
    (x, y) => ((x * y) % 2 + (x * y) % 3) % 2 === 0, (x, y) => ((x + y) % 2 + (x * y) % 3) % 2 === 0,
  ];

  function build(v, cw, mask) {
    const n = 17 + 4 * v;
    const m = Array.from({ length: n }, () => new Array(n).fill(false));
    const fn = Array.from({ length: n }, () => new Array(n).fill(false));
    const set = (x, y, dark) => { m[y][x] = dark; fn[y][x] = true; };
    for (let i = 0; i < n; i++) { set(6, i, i % 2 === 0); set(i, 6, i % 2 === 0); }
    for (const [cx, cy] of [[3, 3], [n - 4, 3], [3, n - 4]]) {
      for (let dy = -4; dy <= 4; dy++) for (let dx = -4; dx <= 4; dx++) {
        const x = cx + dx, y = cy + dy, d = Math.max(Math.abs(dx), Math.abs(dy));
        if (x >= 0 && x < n && y >= 0 && y < n) set(x, y, d !== 2 && d !== 4);
      }
    }
    if (v > 1) {
      const a = n - 7;
      for (let dy = -2; dy <= 2; dy++) for (let dx = -2; dx <= 2; dx++) set(a + dx, a + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
    }
    // Формат: уровень M (00) и номер маски, код БЧХ
    let data = mask, rem = data;
    for (let i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
    const fmt = ((data << 10) | rem) ^ 0x5412, bit = i => ((fmt >>> i) & 1) === 1;
    for (let i = 0; i <= 5; i++) set(8, i, bit(i));
    set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8));
    for (let i = 9; i < 15; i++) set(14 - i, 8, bit(i));
    for (let i = 0; i < 8; i++) set(n - 1 - i, 8, bit(i));
    for (let i = 8; i < 15; i++) set(8, n - 15 + i, bit(i));
    set(8, n - 8, true);
    // Данные змейкой снизу вверх, парами столбцов справа налево
    let i = 0;
    for (let right = n - 1; right >= 1; right -= 2) {
      if (right === 6) right = 5;
      for (let vert = 0; vert < n; vert++) {
        for (let j = 0; j < 2; j++) {
          const x = right - j, up = ((right + 1) & 2) === 0, y = up ? n - 1 - vert : vert;
          if (fn[y][x]) continue;
          const dark = i < cw.length * 8 ? ((cw[i >>> 3] >>> (7 - (i & 7))) & 1) === 1 : false;
          i++;
          m[y][x] = dark !== MASKS[mask](x, y);
        }
      }
    }
    return m;
  }

  // Штраф по стандарту: серии, квадраты 2×2, узоры как у поисковых меток, баланс цветов
  function penalty(m) {
    const n = m.length;
    let p = 0, dark = 0;
    const runs = line => {
      let s = 0, len = 1;
      for (let i = 1; i <= line.length; i++) {
        if (i < line.length && line[i] === line[i - 1]) len++;
        else { if (len >= 5) s += len - 2; len = 1; }
      }
      return s;
    };
    const finder = line => {
      let s = 0;
      const pat = [true, false, true, true, true, false, true];
      for (let i = 0; i + 7 <= line.length; i++) {
        if (!pat.every((b, k) => line[i + k] === b)) continue;
        const before = i >= 4 && [1, 2, 3, 4].every(k => !line[i - k]);
        const after = i + 11 <= line.length && [7, 8, 9, 10].every(k => !line[i + k]);
        if (before || after) s += 40;
      }
      return s;
    };
    for (let y = 0; y < n; y++) {
      const row = m[y], col = m.map(r => r[y]);
      p += runs(row) + runs(col) + finder(row) + finder(col);
      for (let x = 0; x < n; x++) {
        if (row[x]) dark++;
        if (y < n - 1 && x < n - 1 && row[x] === row[x + 1] && row[x] === m[y + 1][x] && row[x] === m[y + 1][x + 1]) p += 3;
      }
    }
    p += Math.floor(Math.abs(dark * 20 - n * n * 10) / (n * n)) * 10;
    return p;
  }

  function qrMatrix(text, forceMask) {
    const bytes = Array.from(new TextEncoder().encode(text));
    const v = [1, 2, 3, 4, 5, 6].find(x => bytes.length <= [14, 26, 42, 62, 84, 106][x - 1]);
    if (!v) throw new Error('Слишком длинный текст для QR');
    const cw = codewords(bytes, v);
    let best = null;
    for (let mask = 0; mask < 8; mask++) {
      if (forceMask !== undefined && mask !== forceMask) continue;
      const m = build(v, cw, mask), p = penalty(m);
      if (!best || p < best.p) best = { m, p, mask, v };
    }
    return { version: best.v, mask: best.mask, modules: best.m };
  }

  function qrSvg(text, px, color) {
    const { modules } = qrMatrix(text), n = modules.length, q = 2, k = px / (n + q * 2);
    let d = '';
    modules.forEach((row, y) => row.forEach((on, x) => { if (on) d += `M${((x + q) * k).toFixed(2)} ${((y + q) * k).toFixed(2)}h${k.toFixed(2)}v${k.toFixed(2)}h-${k.toFixed(2)}z`; }));
    return `<svg width="${px}" height="${px}" viewBox="0 0 ${px} ${px}" role="img" aria-label="QR-код"><rect width="${px}" height="${px}" fill="#fff"/><path d="${d}" fill="${color || '#0B1630'}"/></svg>`;
  }

  window.QR = { qrMatrix, qrSvg };
})();
