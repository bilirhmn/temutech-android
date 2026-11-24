import { execFile } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { performance } from 'node:perf_hooks';
import { setTimeout as delay } from 'node:timers/promises';
import { parseArgs } from 'node:util';

const pkg = 'id.ac.ceh';
const avd = 'CEH_TEST_API_35';
const fixture = 'CEH11 Alpha';
const profileTitle = 'Profil cloud';
const firebaseAccount = 'Akun Firebase';
const profileName = 'Nama tampilan';
const profileSave = 'Simpan profil';
const profileFailed = 'Profil cloud belum dapat dibaca atau disimpan. Acara lokal tetap tersedia.';
const loginIntro = 'Masuk untuk menyimpan acara, mengonfirmasi kehadiran, dan mengelola acara milikmu.';
const registerIntro = 'Buat akun dengan email dan kata sandi.';
const authErrors = [
  'Masukkan alamat email yang valid.',
  'Kata sandi harus berisi minimal 6 karakter.',
  'Email atau kata sandi tidak cocok. Periksa keduanya dan coba lagi.',
  'Masuk dan daftar belum tersedia pada versi ini.',
  'Tidak dapat terhubung. Periksa koneksi internet dan coba lagi.',
  'Permintaan tidak dapat diselesaikan. Coba lagi.',
  'Email ini sudah digunakan. Masuk atau gunakan email lain.',
  'Kata sandi terlalu lemah. Gunakan kata sandi yang lebih kuat.',
  'Terlalu banyak percobaan. Tunggu sebentar, lalu coba lagi.',
];
const usage = `Gunakan: node tools/verify-ceh-cloud-live.mjs --live --serial emulator-PORT

Node.js 20+ dan adb di PATH. Gunakan hanya AVD ${avd} (API 35),
APK id.ac.ceh tepercaya yang sudah dipasang, dan app/google-services.json lokal yang
cocok dengan APK. Pastikan proyek Spark, Email/Password serta Firestore live sudah
aktif, dan aturan yang dimaksud sudah dipublikasikan. Skrip tidak memeriksa paket
billing atau mengubah konfigurasi cloud. Jangan interaksi dengan AVD saat berjalan.
Mulai dari aplikasi tanpa sesi (termasuk demo), layar tidak terkunci, mode pesawat
mati, jaringan default tervalidasi, dan ${fixture} terlihat di Beranda tanpa filter.

Mendaftarkan, masuk ulang, dan menyimpan profil dua akun sintetis melalui UI.
REST memeriksa owner read serta penolakan tamu dan akses lintas akun hanya pada
profil run ini. Setelah cold relaunch online sebagai A, uji cold relaunch luring
untuk sesi Firebase dan kartu Room (bukan cache profil Firestore). Mode pesawat
dan Wi-Fi AVD dikembalikan pada penutupan normal atau interupsi yang tertangani.
Akun Auth dihapus sebisa mungkin; admin harus menghapus dokumen profil sintetis
berdasarkan nama tampilan yang dilaporkan. Tidak ada artefak XML atau screenshot.
Jangan gunakan trace/debug jaringan atau OS saat menjalankan skrip ini.`;

class CheckError extends Error {}
function requireCheck(condition, message) {
  if (!condition) throw new CheckError(message);
}
const describe = error => error instanceof CheckError ? error.message : 'Kesalahan internal (detail mentah disembunyikan)';

function verifier(serial) {
  let signal;
  let cleaning = false;
  let stage = 'prasyarat';
  let config;
  let accounts = [];
  let radios;
  let appMayHaveTestSession = false;
  function checkpoint() {
    if (signal && !cleaning) throw new CheckError('Dihentikan atas permintaan operator');
  }

  // Pipe all outputs, including errors. Never propagate execFile error messages,
  // command strings, XML or HTTP payloads into evidence.
  function adb(label, args, { timeout = 15_000, stdin } = {}) {
    checkpoint();
    return new Promise((resolve, reject) => {
      const child = execFile('adb', ['-s', serial, ...args], {
        timeout, maxBuffer: 2 * 1024 * 1024, windowsHide: true,
      }, (error, stdout) => {
        if (error) reject(new CheckError(`${label}: ADB gagal atau habis waktu`));
        else resolve(stdout);
      });
      child.stdin.on('error', () => {}); // Callback above reports process failures.
      child.stdin.end(stdin);
    });
  }
  const shell = (label, ...args) => adb(label, ['shell', '-T', ...args]);

  async function target() {
    requireCheck((await adb('status perangkat', ['get-state'])).trim() === 'device', 'Perangkat ADB tidak siap');
    const name = (await adb('nama AVD', ['emu', 'avd', 'name'])).replace(/\r/g, '').trimEnd();
    requireCheck(name === avd || name === `${avd}\nOK`, `AVD harus tepat ${avd}`);
    requireCheck((await shell('API perangkat', 'getprop', 'ro.build.version.sdk')).trim() === '35', 'Perangkat bukan API 35');
    requireCheck((await shell('boot perangkat', 'getprop', 'sys.boot_completed')).trim() === '1', 'Boot AVD belum selesai');
    requireCheck((await shell('pengguna Android aktif', 'am', 'get-current-user')).trim() === '0', 'Gunakan pengguna utama AVD');
  }

  async function firebaseConfig() {
    let json;
    try {
      json = JSON.parse(await readFile(new URL('../app/google-services.json', import.meta.url), 'utf8'));
    } catch {
      throw new CheckError('app/google-services.json lokal tidak dapat dibaca');
    }
    const client = Array.isArray(json?.client)
      ? json.client.find(item => item?.client_info?.android_client_info?.package_name === pkg) : null;
    const project = json?.project_info?.project_id;
    const appId = client?.client_info?.mobilesdk_app_id;
    const key = client?.api_key?.[0]?.current_key;
    requireCheck(typeof project === 'string' && /^[a-z][a-z0-9-]{4,62}$/.test(project)
      && !project.startsWith('demo-') && typeof appId === 'string' && /^1:\d+:android:[a-f\d]+$/.test(appId)
      && typeof key === 'string' && /^AIza[\w-]{35}$/.test(key), 'Konfigurasi Firebase Android live tidak valid');
    for (const [resource, value] of [['project_id', project], ['google_app_id', appId], ['google_api_key', key]]) {
      const output = await shell('baca resource Firebase APK', 'cmd', 'overlay', 'lookup', '--user', '0', pkg, `${pkg}:string/${resource}`);
      requireCheck(output.trim() === value, 'Resource Firebase APK tidak cocok dengan konfigurasi lokal');
    }
    return { project, key };
  }

  const has = (nodes, text) => nodes.some(node => labelIs(node, text));
  const action = (nodes, text) => unique(nodes.filter(node => labelIs(node, text)).map(clickable).filter(Boolean));
  // BottomNavigationView reports the tab's FrameLayout as non-clickable in its accessibility tree.
  const tab = (nodes, name) => unique(nodes.filter(node => node['resource-id'] === `${pkg}:id/${name}Fragment`
    && node.enabled === 'true' && node.focusable === 'true'));
  const editable = (nodes, password = false) => unique(nodes.filter(node => node.class === 'android.widget.EditText'
    && node.password === String(password) && node.enabled === 'true'));
  const activeProfile = nodes => has(nodes, firebaseAccount) && has(nodes, profileTitle) && action(nodes, 'Keluar');
  const guestProfile = nodes => has(nodes, 'Tamu') && !has(nodes, firebaseAccount) && !has(nodes, 'Akun demo') && action(nodes, 'Masuk');
  const homeCard = nodes => tab(nodes, 'home')?.selected === 'true' && action(nodes, fixture);

  async function ui() {
    // The PTY is the dump destination, not /sdcard or a temporary XML file.
    // No output is written by this script even if accessibility exposes field text.
    const output = await adb('hierarki UI', ['shell', '-tt', 'uiautomator', 'dump', '/dev/tty'], { timeout: 15_000 });
    return parseUi(output);
  }

  async function waitFor(label, observe, timeout = 30_000) {
    const deadline = performance.now() + timeout;
    let failed = false;
    while (performance.now() < deadline) {
      checkpoint();
      let result;
      try { result = await observe(); } catch (error) {
        if (!(error instanceof CheckError)) throw error;
        failed = true;
      }
      // Terminal UI errors are returned, not thrown inside the retried observation.
      if (result?.failure) throw new CheckError(`${label}: ${result.failure}`);
      if (result && performance.now() <= deadline) return result;
      await delay(500);
    }
    throw new CheckError(`${label}: habis waktu${failed ? ' (ada kegagalan observasi ADB/UI)' : ''}`);
  }
  const observeUi = (label, inspect, timeout) => waitFor(label, async () => inspect(await ui()), timeout);
  async function tap(node) {
    const [x, y, right, bottom] = node.rect;
    await shell('ketuk elemen UI', 'input', 'tap', String(Math.floor((x + right) / 2)), String(Math.floor((y + bottom) / 2)));
  }
  async function enterText(node, value) {
    requireCheck(/^[A-Za-z0-9@._+! -]+$/.test(value), 'Karakter input sintetis tidak didukung');
    await tap(node);
    // Feed the device shell over stdin: no secret in host argv, env, or files.
    // Spaces use Android input's %s encoding; the allowlist excludes quotes and %.
    await adb('isi kolom UI', ['shell', '-T', 'sh'], { stdin: `input text '${value.replaceAll(' ', '%s')}'\n` });
  }
  async function hideKeyboard() {
    const shown = async () => {
      const output = await shell('status keyboard', 'dumpsys', 'input_method');
      const flag = output.match(/\bmInputShown=(true|false)\b/)?.[1];
      requireCheck(flag, 'Status keyboard tidak dapat dibuktikan');
      return flag === 'true';
    };
    if (await shown()) {
      await shell('tutup keyboard', 'input', 'keyevent', 'KEYCODE_BACK');
      await waitFor('keyboard tertutup', async () => !(await shown()), 8_000);
    }
  }
  async function openProfile() {
    const profile = await observeUi('tab Profil tersedia', nodes => tab(nodes, 'profile'));
    if (profile.selected !== 'true') await tap(profile);
    await observeUi('tab Profil dipilih', nodes => tab(nodes, 'profile')?.selected === 'true');
  }
  async function signOut() {
    await tap(await observeUi('aksi keluar pada profil Firebase', activeProfile));
    await observeUi('profil tamu sesudah keluar', guestProfile);
    appMayHaveTestSession = false;
  }

  function authScreen(nodes, inspect) {
    const failure = authErrors.find(text => has(nodes, text));
    return failure ? { failure } : inspect(nodes);
  }
  async function authenticateUi(account, register) {
    await tap(await observeUi('aksi masuk pada profil tamu', guestProfile));
    const loginReady = await observeUi('form Masuk', nodes => authScreen(nodes, current => {
      if (!has(current, loginIntro) || !editable(current) || !editable(current, true)) return null;
      return current;
    }));
    if (register) {
      const switchMode = action(loginReady, 'Belum punya akun? Daftar');
      requireCheck(switchMode, 'Aksi beralih ke Daftar tidak tersedia');
      await tap(switchMode);
    }
    const intro = register ? registerIntro : loginIntro;
    const email = await observeUi('kolom email kosong', nodes => authScreen(nodes, current => {
      const field = editable(current);
      return has(current, intro) && ['', 'Email'].includes(field?.text) ? field : null;
    }));
    await enterText(email, account.email);
    await hideKeyboard();
    const password = await observeUi('email sintetis terisi dan kolom sandi tersedia', nodes => authScreen(nodes, current =>
      editable(current)?.text === account.email && editable(current, true)));
    await enterText(password, account.password);
    await hideKeyboard();
    const submit = await observeUi('aksi autentikasi siap', nodes => authScreen(nodes, current =>
      has(current, intro) && editable(current)?.text === account.email && editable(current, true)
        && action(current, register ? 'Daftar' : 'Masuk')));
    appMayHaveTestSession = true;
    if (register) account.registrationAttempted = true;
    await tap(submit); // Do not retry writes or registration taps on ambiguous failures.
    await observeUi('kembali ke profil Firebase', nodes => authScreen(nodes, activeProfile), 45_000);
  }

  async function saveProfileUi(account) {
    const field = await observeUi('profil baru siap diedit', nodes => {
      if (has(nodes, profileFailed)) return { failure: 'Profil cloud tidak dapat dimuat' };
      const input = editable(nodes);
      return activeProfile(nodes) && ['', profileName].includes(input?.text) && has(nodes, profileSave) ? input : null;
    });
    await enterText(field, account.displayName);
    await hideKeyboard();
    const save = await observeUi('nama tampilan sintetis terisi', nodes => activeProfile(nodes)
      && editable(nodes)?.text === account.displayName && action(nodes, profileSave));
    account.saveAttempted = true;
    await tap(save);
    await observeUi('konfirmasi simpan profil di UI', nodes => has(nodes, profileFailed)
      ? { failure: 'Profil cloud tidak dapat disimpan' }
      : activeProfile(nodes) && has(nodes, 'Nama tampilan tersimpan di cloud.') && editable(nodes)?.text === account.displayName, 45_000);
    account.profileSaved = true;
  }

  async function request(label, url, options) {
    checkpoint();
    try {
      const result = await fetch(url, { ...options, redirect: 'error', signal: AbortSignal.timeout(12_000) });
      return { code: result.status, body: await result.json() };
    } catch {
      throw new CheckError(`${label}: transport, waktu tunggu, atau respons JSON gagal`);
    }
  }
  function identity(operation, payload) {
    const url = new URL(`https://identitytoolkit.googleapis.com/v1/accounts:${operation}`);
    url.searchParams.set('key', config.key);
    return request(`Identity Toolkit ${operation}`, url, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload),
    });
  }
  async function signInRest(account) {
    const { code, body } = await identity('signInWithPassword', {
      email: account.email, password: account.password, returnSecureToken: true,
    });
    const message = body?.error?.message;
    const backendCode = typeof message === 'string' && /^[A-Z][A-Z_0-9]{2,70}$/.test(message) ? `: ${message}` : '';
    requireCheck(code === 200, `Masuk REST gagal (HTTP ${code})${backendCode}`);
    requireCheck(body?.email?.toLowerCase() === account.email && typeof body.localId === 'string'
      && /^[A-Za-z0-9_-]{1,128}$/.test(body.localId) && typeof body.idToken === 'string' && body.idToken.length > 0
      && (!account.uid || account.uid === body.localId), 'Identitas sintetis REST tidak cocok');
    account.uid = body.localId;
    account.token = body.idToken;
  }
  async function profileRequest(account, actor, patch = false) {
    requireCheck(accounts.includes(account) && account.saveAttempted && account.uid
      && (!actor || accounts.includes(actor) && actor.token), 'Permintaan Firestore di luar lingkup profil run');
    const name = `projects/${config.project}/databases/(default)/documents/users/${account.uid}`;
    const url = new URL(`https://firestore.googleapis.com/v1/${name}`);
    const headers = actor ? { Authorization: `Bearer ${actor.token}` } : {};
    if (patch) {
      requireCheck(account.ownerRead && actor && actor !== account && actor.uid !== account.uid, 'Uji update tidak terbatas pada profil sintetis lain');
      url.searchParams.set('currentDocument.exists', 'true');
      url.searchParams.set('updateMask.fieldPaths', 'displayName');
      headers['Content-Type'] = 'application/json';
    }
    const { code, body } = await request('Firestore profil sintetis', url, {
      method: patch ? 'PATCH' : 'GET', headers,
      // Use the existing value so a permissive ruleset cannot change profile content.
      body: patch ? JSON.stringify({ fields: { displayName: { stringValue: account.displayName } } }) : undefined,
    });
    return { code, body, name };
  }
  function sameProfile(account, { code, body, name }) {
    return code === 200 && body?.name === name && Object.keys(body.fields ?? {}).length === 1
      && body.fields?.displayName?.stringValue === account.displayName;
  }
  async function ownerRead(account) {
    const result = await profileRequest(account, account);
    requireCheck(sameProfile(account, result), `Baca owner ${account.label}: dokumen server tidak cocok (HTTP ${result.code})`);
    account.ownerRead = true;
    account.profileSaved = true;
  }
  async function denied(label, response) {
    const { code, body } = await response;
    requireCheck(code === 403 && body?.error?.status === 'PERMISSION_DENIED', `${label}: diharapkan PERMISSION_DENIED (HTTP aktual ${code})`);
  }

  async function coldLaunch() {
    await shell('hentikan proses CEH', 'am', 'force-stop', '--user', '0', pkg);
    const output = await adb('luncurkan MainActivity', ['shell', '-T', 'am', 'start', '--user', '0', '-W', '-n', `${pkg}/.MainActivity`], { timeout: 45_000 });
    requireCheck(/^Status:\s*ok\s*$/m.test(output) && !/^(?:Error|Warning):/m.test(output), 'Peluncuran MainActivity tidak terkonfirmasi');
  }
  const setting = async name => (await shell('baca pengaturan jaringan', 'settings', 'get', 'global', name)).trim();
  async function networkIs(online) {
    const dump = await shell('status konektivitas AVD', 'dumpsys', 'connectivity');
    const active = dump.match(/^\s*Active default network:[ \t]*([^\r\n]+)$/m)?.[1]?.trim();
    if (!online) return active === 'none' && await setting('airplane_mode_on') === '1' && await setting('wifi_on') === '0';
    return /^\d+$/.test(active ?? '') && dump.split(/\r?\n/).some(line => line.includes('NetworkAgentInfo')
      && line.includes(`network{${active}}`) && /\bVALIDATED\b/.test(line));
  }
  async function restoreRadios() {
    if (!radios) return;
    // Recheck the serial before any restoration command; never touch a replacement AVD.
    await target();
    const errors = [];
    // Do not skip the Wi-Fi restore even if disabling airplane mode fails.
    try { await shell('pulihkan mode pesawat', 'cmd', 'connectivity', 'airplane-mode', 'disable'); }
    catch (error) { errors.push(error); }
    try { await shell('pulihkan Wi-Fi', 'svc', 'wifi', radios.wifi === '1' ? 'enable' : 'disable'); }
    catch (error) { errors.push(error); }
    await waitFor('radio awal kembali', async () => await setting('airplane_mode_on') === '0' && await setting('wifi_on') === radios.wifi, 20_000);
    radios = undefined;
    if (errors.length) throw errors[0];
  }

  async function cleanup() {
    cleaning = true;
    let ok = true;
    try { await restoreRadios(); } catch (error) {
      ok = false;
      console.error(`Pemulihan jaringan AVD: ${describe(error)}. Periksa mode pesawat (mati) dan Wi-Fi awal (${radios?.wifi ?? 'sudah dipulihkan'}).`);
    }
    if (appMayHaveTestSession) {
      try {
        await target();
        await coldLaunch();
        await openProfile();
        const state = await observeUi('sesi UI untuk pembersihan', nodes => activeProfile(nodes) ? 'firebase' : guestProfile(nodes) ? 'guest' : null, 15_000);
        if (state === 'firebase') await signOut();
      } catch (error) {
        ok = false;
        console.error(`Keluar dari sesi tes di AVD tidak terkonfirmasi: ${describe(error)}`);
      }
    }
    for (const account of accounts.filter(item => item.registrationAttempted)) {
      try {
        // Recover an account even when the UI registration result was ambiguous.
        if (!account.token) {
          await waitFor('masuk REST untuk pembersihan Auth', async () => {
            await signInRest(account);
            return true;
          }, 25_000);
        }
        if (account.saveAttempted && !account.profileSaved) {
          try {
            const result = await profileRequest(account, account);
            account.profileSaved = sameProfile(account, result);
            if (!account.profileSaved && result.code !== 404) throw new CheckError('Isi profil server tidak terkonfirmasi');
          } catch {
            ok = false;
            console.warn(`Admin: periksa kemungkinan dokumen profil dengan displayName persis "${account.displayName}".`);
          }
        }
        const { code, body } = await identity('delete', { idToken: account.token });
        requireCheck(code === 200 && !body?.error, `hapus Auth gagal (HTTP ${code})`);
        console.log(`Pembersihan Auth ${account.label}: dihapus`);
      } catch (error) {
        ok = false;
        console.error(`Pembersihan Auth ${account.label} tidak terkonfirmasi (${describe(error)}); periksa akun sintetis ${account.email}`);
        if (account.saveAttempted && !account.profileSaved) {
          console.warn(`Admin: periksa kemungkinan dokumen profil dengan displayName persis "${account.displayName}".`);
        }
      } finally {
        account.password = account.token = undefined;
      }
      if (account.profileSaved) console.log(`Admin: hapus dokumen sintetis users dengan displayName persis "${account.displayName}".`);
    }
    return ok;
  }

  return async function run() {
    const interrupt = () => { signal ??= 'SIGINT'; };
    const terminate = () => { signal ??= 'SIGTERM'; };
    process.on('SIGINT', interrupt);
    process.on('SIGTERM', terminate);
    let status = 0;
    try {
      await target();
      config = await firebaseConfig();
      requireCheck(await setting('airplane_mode_on') === '0' && await networkIs(true), 'Butuh mode pesawat mati dan jaringan default tervalidasi');
      await observeUi('buka aplikasi dan pastikan fixture dapat diketuk di Beranda', homeCard);
      await openProfile();
      await observeUi('profil tamu (jangan gunakan sesi demo/Firebase yang sudah ada)', guestProfile);
      const nonce = randomBytes(10).toString('hex');
      accounts = ['A', 'B'].map(label => ({
        label, email: `ceh-live-${nonce}-${label.toLowerCase()}@example.com`,
        password: `CeH9!${randomBytes(18).toString('hex')}`, displayName: `CEH Live ${nonce} ${label}`,
      }));
      for (const account of accounts) {
        stage = `Daftar UI ${account.label}`;
        await authenticateUi(account, true);
        stage = `Masuk ulang UI ${account.label}`;
        await signOut();
        await authenticateUi(account, false);
        await signInRest(account);
        requireCheck(accounts.every(other => other === account || other.uid !== account.uid), 'UID akun sintetis harus berbeda');
        stage = `Simpan profil UI dan baca owner REST ${account.label}`;
        await saveProfileUi(account);
        await ownerRead(account);
        console.log(`LULUS ${account.label}: daftar, keluar, masuk ulang dan simpan profil di UI; baca owner dari server REST`);
        await signOut();
      }
      stage = 'Rules Firestore live pada dua dokumen sintetis';
      const [a, b] = accounts;
      for (const [owner, other] of [[a, b], [b, a]]) {
        await denied(`Tamu baca ${owner.label}`, profileRequest(owner));
        await denied(`${other.label} baca ${owner.label}`, profileRequest(owner, other));
        await denied(`${other.label} update ${owner.label}`, profileRequest(owner, other, true));
        await ownerRead(owner);
      }
      console.log('LULUS rules live: GET tamu ditolak pada kedua profil; GET/PATCH lintas akun ditolak dua arah');

      stage = 'cold relaunch online A';
      await authenticateUi(a, false);
      await coldLaunch();
      await openProfile();
      await observeUi('sesi Firebase dan profil A termuat online setelah restart', nodes => activeProfile(nodes)
        && editable(nodes)?.text === a.displayName && !has(nodes, profileFailed), 45_000);
      console.log('LULUS cold relaunch online: sesi Firebase dan nama profil A');

      stage = 'cold relaunch luring A';
      const wifi = await setting('wifi_on');
      requireCheck(['0', '1'].includes(wifi) && await setting('airplane_mode_on') === '0', 'Status radio awal tidak stabil atau tidak didukung');
      radios = { wifi }; // Arm restoration before the first possible mutation.
      if (wifi === '1') {
        await shell('matikan Wi-Fi khusus AVD', 'svc', 'wifi', 'disable');
        await waitFor('Wi-Fi mati sebelum mode pesawat', async () => await setting('wifi_on') === '0', 15_000);
      }
      await shell('aktifkan mode pesawat khusus AVD', 'cmd', 'connectivity', 'airplane-mode', 'enable');
      await waitFor('mode pesawat, Wi-Fi mati, dan tidak ada jaringan default', () => networkIs(false), 20_000);
      await coldLaunch();
      await observeUi('fixture Room terlihat di Beranda luring', homeCard);
      requireCheck(await networkIs(false), 'Jaringan muncul saat memeriksa fixture');
      await openProfile();
      await observeUi('sesi Firebase aktif di Profil luring', activeProfile);
      requireCheck(await networkIs(false), 'Jaringan muncul saat memeriksa sesi');
      console.log('LULUS cold relaunch luring: CEH11 Alpha dari cache lokal dan sesi Firebase masih aktif; bukan cache profil cloud');
    } catch (error) {
      status = 1;
      console.error(`TERHENTI [${stage}]: ${describe(error)}`);
    } finally {
      try { if (!await cleanup()) status = 1; }
      catch (error) { status = 1; console.error(`Pembersihan tidak tuntas: ${describe(error)}`); }
      process.removeListener('SIGINT', interrupt);
      process.removeListener('SIGTERM', terminate);
    }
    return signal ? (signal === 'SIGINT' ? 130 : 143) : status;
  };
}

function decodeXml(value) {
  const entities = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" };
  return value.replace(/&(#x[\da-fA-F]+|#\d+|amp|lt|gt|quot|apos);/g, (_, entity) => {
    if (entity[0] !== '#') return entities[entity];
    const code = entity[1] === 'x' ? Number.parseInt(entity.slice(2), 16) : Number(entity.slice(1));
    return code <= 0x10ffff ? String.fromCodePoint(code) : '\uFFFD';
  });
}
function parseUi(output) {
  const xml = output.match(/<hierarchy\b[^>]*>[\s\S]*?<\/hierarchy>/)?.[0];
  requireCheck(xml, 'XML UIAutomator tidak lengkap');
  const nodes = [];
  const stack = [];
  for (const [tag, attrs] of xml.matchAll(/<node\b((?:[^">]|"[^"]*")*)>|<\/node>/g)) {
    if (tag === '</node>') {
      requireCheck(stack.length, 'Hierarki UIAutomator tidak seimbang');
      stack.pop();
      continue;
    }
    const node = Object.fromEntries([...attrs.matchAll(/([\w-]+)="([^"]*)"/g)]
      .map(([, name, value]) => [name, decodeXml(value)]));
    node.parent = stack.at(-1);
    const rect = node.bounds?.match(/^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$/)?.slice(1).map(Number);
    if (rect && rect[2] > rect[0] && rect[3] > rect[1]) node.rect = rect;
    if (node.package === pkg && node.rect) nodes.push(node);
    if (!tag.endsWith('/>')) stack.push(node);
  }
  requireCheck(stack.length === 0, 'Hierarki UIAutomator tidak seimbang');
  return nodes;
}
const labelIs = (node, label) => node.text === label || node['content-desc'] === label;
function unique(items) {
  const set = new Set(items);
  return set.size === 1 ? set.values().next().value : null;
}
function clickable(node) {
  for (let cursor = node; cursor?.package === pkg; cursor = cursor.parent) {
    if (cursor.enabled !== 'true') return null;
    if (cursor.clickable === 'true' && cursor.rect) return cursor;
  }
  return null;
}

async function main() {
  let values;
  try {
    ({ values } = parseArgs({ options: { live: { type: 'boolean' }, help: { type: 'boolean' }, serial: { type: 'string' } } }));
  } catch {
    console.error(usage);
    return 2;
  }
  if (values.help) { console.log(usage); return 0; }
  if (!values.live || !/^emulator-\d{4,5}$/.test(values.serial ?? '')) {
    console.error(usage);
    return 2;
  }
  if (process.env.ADB_SERVER_SOCKET || process.env.ANDROID_ADB_SERVER_ADDRESS || process.env.ANDROID_ADB_SERVER_PORT) {
    console.error('Gunakan server ADB lokal standar, tanpa override alamat/socket/port.');
    return 2;
  }
  return verifier(values.serial)();
}

try { process.exitCode = await main(); }
catch (error) { console.error(describe(error)); process.exitCode = 1; }
