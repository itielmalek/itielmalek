'use strict';
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const webpush = require('web-push');

const PORT = process.env.PORT || 3000;
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data');
const DB_FILE = path.join(DATA_DIR, 'db.json');
const PUBLIC = path.join(__dirname, 'public');
fs.mkdirSync(DATA_DIR, { recursive: true });

const DEFAULT_KEYWORDS = ['הגיעה', 'הגיע ליעד', 'מוכנה לאיסוף', 'מוכן לאיסוף', 'ממתינה לאיסוף', 'נמסרה', 'נמסר', 'delivered', 'ready for pickup', 'arrived'];

// ---------- storage ----------
let db = { settings: {}, packages: [], subscriptions: [], vapid: null, lastScan: null };
try { db = { ...db, ...JSON.parse(fs.readFileSync(DB_FILE, 'utf8')) }; } catch {}
db.settings = { urlTemplate: '', method: 'GET', body: '', keywords: DEFAULT_KEYWORDS, intervalHours: 12, ...db.settings };
if (!db.vapid) db.vapid = webpush.generateVAPIDKeys();
function save() {
  const tmp = DB_FILE + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(db, null, 2));
  fs.renameSync(tmp, DB_FILE);
}
save();
webpush.setVapidDetails(process.env.VAPID_SUBJECT || 'mailto:admin@example.com', db.vapid.publicKey, db.vapid.privateKey);

// ---------- checking ----------
function fill(tpl, pkg, encode) {
  const e = encode ? encodeURIComponent : (x) => x;
  return tpl.replaceAll('{tracking}', e(pkg.tracking)).replaceAll('{phone}', e(pkg.phone));
}
function htmlToText(html) {
  return html.replace(/<script[\s\S]*?<\/script>/gi, ' ').replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<[^>]+>/g, ' ').replace(/&nbsp;/g, ' ').replace(/&quot;/g, '"').replace(/&amp;/g, '&')
    .replace(/\s+/g, ' ').trim();
}
function detectArrival(text, keywords) {
  const low = text.toLowerCase();
  return keywords.find((k) => k && low.includes(k.toLowerCase())) || null;
}
async function checkPackage(pkg) {
  const s = db.settings;
  if (!s.urlTemplate) throw new Error('לא הוגדר קישור מעקב בהגדרות');
  const url = fill(s.urlTemplate, pkg, true);
  const opts = { method: s.method, redirect: 'follow', signal: AbortSignal.timeout(30000),
    headers: { 'User-Agent': 'Mozilla/5.0 (PackageTracker)', 'Accept-Language': 'he,en;q=0.8' } };
  if (s.method === 'POST') {
    opts.body = fill(s.body || '', pkg, false);
    opts.headers['Content-Type'] = opts.body.trim().startsWith('{') ? 'application/json' : 'application/x-www-form-urlencoded';
  }
  const res = await fetch(url, opts);
  const text = htmlToText(await res.text());
  if (!res.ok) throw new Error('HTTP ' + res.status);
  return { match: detectArrival(text, s.keywords), snippet: text.slice(0, 300) };
}

let scanning = false;
async function scanAll(manual = false) {
  if (scanning) return { skipped: true };
  scanning = true;
  const arrivedNow = [];
  try {
    for (const pkg of db.packages.filter((p) => p.status === 'tracking')) {
      pkg.lastChecked = new Date().toISOString();
      try {
        const r = await checkPackage(pkg);
        pkg.lastError = null;
        pkg.lastSnippet = r.snippet;
        if (r.match) {
          pkg.status = 'arrived';
          pkg.arrivedAt = new Date().toISOString();
          pkg.matched = r.match;
          arrivedNow.push(pkg);
        }
      } catch (err) { pkg.lastError = String(err.message || err); }
      await new Promise((r) => setTimeout(r, 1500)); // be polite to the tracking site
    }
    db.lastScan = new Date().toISOString();
    save();
    if (arrivedNow.length) await notify(arrivedNow);
  } finally { scanning = false; }
  return { arrived: arrivedNow.length };
}
async function notify(pkgs) {
  const names = pkgs.map((p) => p.name || p.tracking).join(', ');
  const payload = JSON.stringify({
    title: pkgs.length === 1 ? 'חבילה הגיעה ליעד 📦' : `${pkgs.length} חבילות הגיעו ליעד 📦`,
    body: names, url: '/#arrived',
  });
  const dead = [];
  await Promise.all(db.subscriptions.map((sub) => webpush.sendNotification(sub, payload).catch((e) => {
    if (e.statusCode === 404 || e.statusCode === 410) dead.push(sub.endpoint);
  })));
  if (dead.length) { db.subscriptions = db.subscriptions.filter((s) => !dead.includes(s.endpoint)); save(); }
}

// ---------- scheduler ----------
function scheduleLoop() {
  const tick = async () => {
    const hrs = Math.min(12, Math.max(6, Number(db.settings.intervalHours) || 12));
    const due = !db.lastScan || Date.now() - new Date(db.lastScan).getTime() >= hrs * 3600e3;
    if (due) await scanAll().catch((e) => console.error('scan failed', e));
  };
  setInterval(tick, 60e3);
  tick();
}

// ---------- http ----------
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml', '.webmanifest': 'application/manifest+json' };
function json(res, code, obj) { res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' }); res.end(JSON.stringify(obj)); }
function body(req) {
  return new Promise((resolve, reject) => {
    let d = ''; req.on('data', (c) => { d += c; if (d.length > 1e6) reject(new Error('too large')); });
    req.on('end', () => { try { resolve(d ? JSON.parse(d) : {}); } catch (e) { reject(e); } });
  });
}
const str = (v, max = 200) => String(v ?? '').trim().slice(0, max);

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  const p = url.pathname;
  try {
    if (p === '/api/state' && req.method === 'GET')
      return json(res, 200, { settings: db.settings, packages: db.packages, lastScan: db.lastScan, scanning, vapidPublicKey: db.vapid.publicKey });
    if (p === '/api/packages' && req.method === 'POST') {
      const b = await body(req);
      // supports bulk: lines of "phone tracking [name]" via b.bulk
      const items = b.bulk ? str(b.bulk, 50000).split('\n').map((l) => l.trim().split(/[\s,;]+/)).filter((x) => x.length >= 2)
        .map(([phone, tracking, ...n]) => ({ phone, tracking, name: n.join(' ') })) : [b];
      let added = 0;
      for (const it of items) {
        const tracking = str(it.tracking, 80), phone = str(it.phone, 30);
        if (!tracking || !phone) continue;
        db.packages.push({ id: crypto.randomUUID(), tracking, phone, name: str(it.name, 80), status: 'tracking', addedAt: new Date().toISOString() });
        added++;
      }
      save();
      return json(res, added ? 201 : 400, { added });
    }
    let m = p.match(/^\/api\/packages\/([\w-]+)(\/collected|\/restore)?$/);
    if (m) {
      const pkg = db.packages.find((x) => x.id === m[1]);
      if (!pkg) return json(res, 404, { error: 'not found' });
      if (req.method === 'POST' && m[2] === '/collected') { pkg.status = 'collected'; pkg.collectedAt = new Date().toISOString(); }
      else if (req.method === 'POST' && m[2] === '/restore') { pkg.status = 'tracking'; delete pkg.arrivedAt; }
      else if (req.method === 'DELETE') db.packages = db.packages.filter((x) => x !== pkg);
      else return json(res, 405, {});
      save();
      return json(res, 200, { ok: true });
    }
    if (p === '/api/settings' && req.method === 'PUT') {
      const b = await body(req);
      const s = db.settings;
      if (b.urlTemplate !== undefined) s.urlTemplate = str(b.urlTemplate, 1000);
      if (b.method !== undefined) s.method = b.method === 'POST' ? 'POST' : 'GET';
      if (b.body !== undefined) s.body = str(b.body, 2000);
      if (Array.isArray(b.keywords)) s.keywords = b.keywords.map((k) => str(k, 80)).filter(Boolean);
      if (b.intervalHours !== undefined) s.intervalHours = Math.min(12, Math.max(6, Math.round(Number(b.intervalHours)) || 12));
      save();
      return json(res, 200, s);
    }
    if (p === '/api/scan' && req.method === 'POST') return json(res, 200, await scanAll(true));
    if (p === '/api/subscribe' && req.method === 'POST') {
      const sub = await body(req);
      if (!sub.endpoint) return json(res, 400, {});
      db.subscriptions = db.subscriptions.filter((x) => x.endpoint !== sub.endpoint).concat(sub);
      save();
      return json(res, 201, { ok: true });
    }
    if (p === '/api/test-push' && req.method === 'POST') {
      await notify([{ name: 'התראת בדיקה' }]);
      return json(res, 200, { sent: db.subscriptions.length });
    }
    // static
    const file = path.normalize(path.join(PUBLIC, p === '/' ? 'index.html' : p));
    if (!file.startsWith(PUBLIC) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404); return res.end('Not found'); }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream' });
    fs.createReadStream(file).pipe(res);
  } catch (err) { json(res, 500, { error: String(err.message || err) }); }
});

if (require.main === module) {
  server.listen(PORT, () => { console.log(`PackageTracker running on http://localhost:${PORT}`); scheduleLoop(); });
}
module.exports = { detectArrival, htmlToText, fill };
