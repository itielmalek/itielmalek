const $ = (s) => document.querySelector(s);
let state = { packages: [], settings: {} };
const api = (url, method = 'GET', body) => fetch(url, { method, headers: { 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined }).then((r) => r.json());
const esc = (s) => String(s ?? '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const fmt = (d) => d ? new Date(d).toLocaleString('he-IL') : '—';

function card(p, buttons) {
  return `<div class="pkg ${p.status}"><div><div class="t">${esc(p.name || p.tracking)}</div>
    <div class="muted">${esc(p.tracking)} · ${esc(p.phone)}</div>
    ${p.arrivedAt ? `<div class="muted">הגיעה: ${fmt(p.arrivedAt)}</div>` : `<div class="muted">נבדקה: ${fmt(p.lastChecked)}</div>`}
    ${p.lastError ? `<div class="err">שגיאה: ${esc(p.lastError)}</div>` : ''}</div>
    <div>${buttons}</div></div>`;
}
const btn = (act, id, label, cls = '') => `<button class="${cls}" data-act="${act}" data-id="${id}">${label}</button>`;

function render() {
  const by = (s) => state.packages.filter((p) => p.status === s);
  const list = (el, items, f, empty) => { $(el).innerHTML = items.length ? items.map(f).join('') : `<p class="muted">${empty}</p>`; };
  list('#trackingList', by('tracking'), (p) => card(p, btn('del', p.id, '🗑', 'secondary')), 'אין חבילות במעקב');
  list('#arrivedList', by('arrived'), (p) => card(p, btn('collected', p.id, '✔ נאסף', 'ok')), 'אין חבילות שמחכות לאיסוף');
  list('#collectedList', by('collected'), (p) => card(p, btn('restore', p.id, 'החזר למעקב', 'secondary') + btn('del', p.id, '🗑', 'danger')), 'עדיין לא נאספו חבילות');
  $('#b-tracking').textContent = by('tracking').length ? ` (${by('tracking').length})` : '';
  $('#b-arrived').textContent = by('arrived').length || '';
  $('#status').textContent = state.scanning ? 'סורק…' : `סריקה אחרונה: ${fmt(state.lastScan)}`;
  const f = $('#settingsForm'), s = state.settings;
  if (!f.dataset.init) {
    f.dataset.init = 1;
    f.intervalHours.value = s.intervalHours; f.urlTemplate.value = s.urlTemplate; f.method.value = s.method;
    f.body.value = s.body; f.keywords.value = (s.keywords || []).join('\n');
    $('#ivLabel').textContent = s.intervalHours; $('#bodyLbl').hidden = s.method !== 'POST';
  }
}
async function load() { state = await api('/api/state'); render(); return state; }

document.addEventListener('click', async (e) => {
  const b = e.target.closest('[data-act]');
  if (b) {
    const { act, id } = b.dataset;
    if (act === 'del') { if (!confirm('למחוק?')) return; await api('/api/packages/' + id, 'DELETE'); }
    else await api(`/api/packages/${id}/${act}`, 'POST');
    return load();
  }
  const t = e.target.closest('nav [data-tab]');
  if (t) showTab(t.dataset.tab);
});
function showTab(name) {
  document.querySelectorAll('.tab').forEach((s) => (s.hidden = s.id !== 'tab-' + name));
  document.querySelectorAll('nav button').forEach((n) => n.classList.toggle('active', n.dataset.tab === name));
  history.replaceState(null, '', '#' + name);
}
$('#addForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const d = Object.fromEntries(new FormData(e.target));
  await api('/api/packages', 'POST', { phone: d.phone, tracking: d.tracking, name: d.name });
  e.target.reset(); load();
});
$('#bulkBtn').onclick = async () => {
  const ta = $('#addForm textarea'); if (!ta.value.trim()) return;
  const r = await api('/api/packages', 'POST', { bulk: ta.value }); ta.value = ''; alert(`נוספו ${r.added} חבילות`); load();
};
$('#scanBtn').onclick = async (e) => {
  e.target.disabled = true; e.target.textContent = 'סורק…';
  await api('/api/scan', 'POST'); e.target.disabled = false; e.target.textContent = '🔄 סרוק עכשיו';
  await load(); if (state.packages.some((p) => p.status === 'arrived')) showTab('arrived');
};
const sf = $('#settingsForm');
sf.intervalHours.oninput = () => ($('#ivLabel').textContent = sf.intervalHours.value);
sf.method.onchange = () => ($('#bodyLbl').hidden = sf.method.value !== 'POST');
sf.addEventListener('submit', async (e) => {
  e.preventDefault();
  await api('/api/settings', 'PUT', { intervalHours: +sf.intervalHours.value, urlTemplate: sf.urlTemplate.value, method: sf.method.value, body: sf.body.value, keywords: sf.keywords.value.split('\n') });
  alert('נשמר'); load();
});

// ---- push ----
const b64 = (s) => Uint8Array.from(atob((s + '='.repeat((4 - (s.length % 4)) % 4)).replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0));
$('#pushBtn').onclick = async () => {
  const msg = $('#pushMsg');
  try {
    if (!('serviceWorker' in navigator) || !('PushManager' in window)) throw new Error('הדפדפן לא תומך בהתראות (באייפון יש להוסיף למסך הבית קודם)');
    if ((await Notification.requestPermission()) !== 'granted') throw new Error('ההרשאה נדחתה');
    const reg = await navigator.serviceWorker.ready;
    const sub = (await reg.pushManager.getSubscription()) || await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: b64(state.vapidPublicKey) });
    await api('/api/subscribe', 'POST', sub.toJSON());
    msg.textContent = '✔ התראות הופעלו';
  } catch (err) { msg.textContent = '✖ ' + err.message; }
};
$('#testPushBtn').onclick = async () => { const r = await api('/api/test-push', 'POST'); $('#pushMsg').textContent = `נשלחה ל-${r.sent} מכשירים`; };

if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js');
showTab(location.hash.slice(1) || 'tracking');
load(); setInterval(load, 30000);
