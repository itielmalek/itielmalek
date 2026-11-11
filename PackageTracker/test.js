const assert = require('node:assert');
process.env.DATA_DIR = require('node:os').tmpdir() + '/pt-test-' + Date.now();
const { detectArrival, htmlToText, fill } = require('./server.js');
const kw = ['הגיעה', 'delivered'];
assert.strictEqual(detectArrival(htmlToText('<div>החבילה <b>הגיעה</b> לנקודת האיסוף</div>'), kw), 'הגיעה');
assert.strictEqual(detectArrival('Status: DELIVERED', kw), 'delivered');
assert.strictEqual(detectArrival('בדרך אליך', kw), null);
assert.strictEqual(fill('https://x/?t={tracking}&p={phone}', { tracking: 'AB 1', phone: '050' }, true), 'https://x/?t=AB%201&p=050');
console.log('ok'); process.exit(0);
