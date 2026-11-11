self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));
self.addEventListener('push', (e) => {
  const d = e.data ? e.data.json() : { title: 'חבילה הגיעה', body: '' };
  e.waitUntil(self.registration.showNotification(d.title, { body: d.body, icon: '/icon.svg', badge: '/icon.svg', lang: 'he', dir: 'rtl', data: { url: d.url || '/' } }));
});
self.addEventListener('notificationclick', (e) => {
  e.notification.close();
  e.waitUntil(clients.matchAll({ type: 'window' }).then((cs) => {
    const c = cs[0]; if (c) { c.navigate(e.notification.data.url); return c.focus(); }
    return clients.openWindow(e.notification.data.url);
  }));
});
