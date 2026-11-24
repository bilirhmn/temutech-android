import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import net from 'node:net';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const jar = process.argv[2];
if (!jar) throw new Error('Gunakan: node tools/check-firestore-rules.mjs <jar emulator resmi>');
const project = 'demo-ceh-rules';
const host = '127.0.0.1';
const port = 8765;
const origin = `http://${host}:${port}`;
const root = `/v1/projects/${project}/databases/(default)/documents`;
const rules = fileURLToPath(new URL('../firestore.rules', import.meta.url));

// Fail before running requests if the port belongs to any existing process.
const socket = net.createServer();
socket.listen(port, host);
await once(socket, 'listening');
await new Promise((resolve, reject) => socket.close(error => error ? reject(error) : resolve()));
const emulator = spawn('java', ['-jar', jar, '--host', host, '--port', String(port), '--project_id', project, '--rules', rules],
  { stdio: ['ignore', 'inherit', 'inherit'] });
const exited = new Promise(resolve => {
  emulator.once('exit', resolve);
  emulator.once('error', resolve);
});

// Unsigned tokens are only for these synthetic identities against the loopback emulator.
function token(uid) {
  const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  const now = Math.floor(Date.now() / 1000);
  return `${encode({ alg: 'none', typ: 'JWT' })}.${encode({ sub: uid, user_id: uid, aud: project,
    iss: `https://securetoken.google.com/${project}`, iat: now, exp: now + 600,
    firebase: { sign_in_provider: 'password', identities: {} } })}.`;
}
const fields = displayName => ({ displayName: { stringValue: displayName } });
async function check(label, expected, method, path, uid, values) {
  const response = await fetch(origin + root + path, {
    method,
    signal: AbortSignal.timeout(30_000),
    headers: { ...(uid ? { Authorization: `Bearer ${token(uid)}` } : {}), 'Content-Type': 'application/json' },
    body: values ? JSON.stringify({ fields: values }) : undefined,
  });
  const body = await response.json();
  assert.equal(response.status, expected, `${label}: ${body.error?.status ?? 'unexpected response'}`);
  console.log(`PASS ${label}: HTTP ${response.status}`);
  return body;
}

try {
  let ready = false;
  for (let i = 0; i < 40 && emulator.exitCode === null; i++) {
    try { await fetch(origin, { signal: AbortSignal.timeout(750) }); ready = true; break; }
    catch { await delay(250); }
  }
  assert.ok(ready, 'Emulator Firestore belum siap.');
  await check('tamu tidak dapat menulis', 403, 'PATCH', '/users/a', undefined, fields('Tamu'));
  await check('pemilik A membuat profil', 200, 'PATCH', '/users/a', 'a', fields('Uji A'));
  await check('pemilik B membuat profil', 200, 'PATCH', '/users/b', 'b', fields('Uji B'));
  assert.equal((await check('pemilik A membaca profil', 200, 'GET', '/users/a', 'a')).fields.displayName.stringValue, 'Uji A');
  await check('tamu tidak dapat membaca', 403, 'GET', '/users/a');
  await check('B tidak dapat membaca A', 403, 'GET', '/users/a', 'b');
  await check('A tidak dapat menimpa B', 403, 'PATCH', '/users/b', 'a', fields('Tidak sah'));
  await check('A tidak dapat menambahkan field lain', 403, 'PATCH', '/users/a', 'a', { ...fields('Uji A'), extra: { stringValue: 'fixture' } });
  await check('nama kosong ditolak', 403, 'PATCH', '/users/a', 'a', fields(''));
  await check('nama terlalu panjang ditolak', 403, 'PATCH', '/users/a', 'a', fields('a'.repeat(81)));
  await check('kueri daftar ditolak', 403, 'GET', '/users', 'a');
  await check('jalur lain ditolak', 403, 'PATCH', '/other/a', 'a', fields('Uji A'));
  await check('hapus dari klien ditolak', 403, 'DELETE', '/users/a', 'a');
  await check('pemilik dapat memperbarui profil', 200, 'PATCH', '/users/a', 'a', fields('Uji A baru'));
} finally {
  emulator.kill();
  await exited;
}
