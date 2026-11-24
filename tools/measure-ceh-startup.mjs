import { spawnSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { appendFileSync, closeSync, fstatSync, openSync, readSync } from 'node:fs';
import { performance } from 'node:perf_hooks';
import { parseArgs } from 'node:util';
import { setTimeout as delay } from 'node:timers/promises';

const pkg = 'id.ac.ceh';
const activity = `${pkg}/.MainActivity`;
const homeHeadline = 'Temukan acara teknologi dan belajar';
const header = 'session,utc,serial,avd,api,renderer,fixture_label,mode,run,status,total_time_ms,card_observed_upper_bound_ms,pid_before,pid_after,activity_before,activity_after,reason\n';
const usage = `Gunakan: node tools/measure-ceh-startup.mjs --serial emulator-PORT --api 26|35
         --label "Label fixture persis" --csv PATH [--renderer vulkan|opengl] [--cold-only]

Perlu adb di PATH, AVD CEH_TEST_API_<api>, jaringan default tervalidasi,
dan CEH dengan Beranda serta kartu fixture sudah terlihat tanpa menggulir.
Label harus sama persis dengan text atau content-desc di hierarki UIAutomator.
Gunakan dataset dan konfigurasi yang sama; renderer hanya metadata operator.

Menambahkan 5 pengukuran dingin dan 5 hangat ke CSV (folder harus sudah ada).
Opsi --cold-only melewatkan pengukuran hangat.
TotalTime berasal dari am start -W. Waktu kartu adalah batas atas dari host:
termasuk ADB, penantian idle UIAutomator, dump, baca XML, dan interval polling;
bukan waktu render presisi. Jaringan diperiksa saat prasyarat, bukan impor live.
Keluar dengan kode 1 jika prasyarat gagal atau ada pengukuran gagal.`;

function adb(serial, label, args, timeout = 5_000) {
  const result = spawnSync('adb', ['-s', serial, ...args], {
    encoding: 'utf8', timeout, maxBuffer: 4 * 1024 * 1024, windowsHide: true,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  if (result.error || result.status !== 0) {
    throw new Error(`${label}: ${result.error?.code ?? result.signal ?? `exit ${result.status}`}`);
  }
  return result.stdout;
}

function resumedActivity(serial) {
  const output = adb(serial, 'dumpsys activity', ['shell', 'dumpsys', 'activity', 'activities']);
  const records = new Set();
  for (const [, token, fields] of output.matchAll(/(?:topResumedActivity|mResumedActivity)\s*[:=]\s*ActivityRecord\{([a-f\d]+)\s+([^\r\n}]+)/gi)) {
    if (fields.split(/\s+/).some(field => field === activity || field === `${pkg}/${pkg}.MainActivity`)) {
      records.add(token);
    }
  }
  if (records.size !== 1) throw new Error('ActivityRecord MainActivity yang resumed tidak dapat diverifikasi');
  return [...records][0];
}

function pid(serial) {
  const value = adb(serial, 'pidof CEH', ['shell', 'pidof', pkg]).trim();
  if (!/^[1-9]\d*$/.test(value)) throw new Error('PID tunggal CEH tidak dapat diverifikasi');
  return value;
}

const entities = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" };
function decode(value) {
  return value.replace(/&(#x[\da-f]+|#\d+|amp|lt|gt|quot|apos);/gi, (_, entity) => {
    if (entity[0] !== '#') return entities[entity];
    return String.fromCodePoint(entity[1].toLowerCase() === 'x'
      ? Number.parseInt(entity.slice(2), 16) : Number(entity.slice(1)));
  });
}

function hasHomeCard(xml, label) {
  if (!/^\s*(?:<\?xml[^>]*>\s*)?<hierarchy\b[\s\S]*<\/hierarchy>\s*$/.test(xml)) {
    throw new Error('XML UIAutomator tidak lengkap');
  }
  // UIAutomator emits nested nodes with double-quoted, XML-escaped attributes.
  const stack = [];
  let home = false;
  let card = false;
  for (const [tag, attrs] of xml.matchAll(/<node\b([^>]*?)>|<\/node>/g)) {
    if (tag === '</node>') {
      stack.pop();
      continue;
    }
    const node = Object.fromEntries([...attrs.matchAll(/([\w-]+)="([^"]*)"/g)]
      .map(([, key, value]) => [key, decode(value)]));
    if (!tag.endsWith('/>')) stack.push(node);
    if (node.package !== pkg) continue;
    const bounds = node.bounds?.match(/^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$/);
    if (!bounds || +bounds[3] <= +bounds[1] || +bounds[4] <= +bounds[2]) continue;
    home ||= node.text === homeHeadline || (node.selected === 'true'
      && node['resource-id'] === `${pkg}:id/homeFragment`);
    if (node.enabled === 'true' && (node.text === label || node['content-desc'] === label)) {
      const target = node.clickable === 'true' ? node : [...stack].reverse().find(parent => parent.clickable === 'true');
      card ||= target?.package === pkg && target.enabled === 'true';
    }
  }
  return home && card;
}

function observeCard(serial, label, deadline) {
  const path = `/data/local/tmp/ceh-startup-${randomUUID()}.xml`;
  const remaining = () => {
    const ms = Math.ceil(deadline - performance.now());
    if (ms <= 0) throw new Error('Batas waktu observasi tercapai');
    return Math.min(ms, 6_000);
  };
  try {
    adb(serial, 'uiautomator dump', ['shell', 'uiautomator', 'dump', path], remaining());
    const xml = adb(serial, 'exec-out cat', ['exec-out', 'cat', path], remaining());
    return hasHomeCard(xml, label) ? performance.now() : null;
  } finally {
    try {
      adb(serial, 'hapus dump', ['shell', 'rm', '-f', path], 2_000);
    } catch (error) {
      console.warn(`${error.message}; dump mungkin tersisa: ${path}`);
    }
  }
}

function preflight(serial, api, avd, label) {
  if (adb(serial, 'get-state', ['get-state']).trim() !== 'device') throw new Error('ADB tidak online');
  const actualAvd = adb(serial, 'emu avd name', ['emu', 'avd', 'name']).replace(/\r/g, '').replace(/\n+$/, '');
  if (actualAvd !== avd && actualAvd !== `${avd}\nOK`) throw new Error(`AVD harus tepat ${avd}`);
  if (adb(serial, 'getprop SDK', ['shell', 'getprop', 'ro.build.version.sdk']).trim() !== api) {
    throw new Error(`API perangkat harus ${api}`);
  }
  const networks = adb(serial, 'dumpsys connectivity', ['shell', 'dumpsys', 'connectivity']);
  const defaultNet = networks.match(/^\s*Active default network:\s*(\d+)[ \t]*\r?$/m)?.[1];
  if (!defaultNet || !networks.split(/\r?\n/).some(line => line.includes('NetworkAgentInfo')
    && line.includes(`network{${defaultNet}}`) && /\bVALIDATED\b/.test(line))) {
    throw new Error('Jaringan default tervalidasi tidak dapat dibuktikan');
  }
  resumedActivity(serial);
  if (observeCard(serial, label, performance.now() + 10_000) === null) {
    throw new Error('Buka Beranda dan pastikan kartu fixture terlihat dan dapat diketuk');
  }
}

async function measure(serial, label, mode, row) {
  if (mode === 'cold') {
    adb(serial, 'am force-stop', ['shell', 'am', 'force-stop', pkg]);
  } else {
    row.pidBefore = pid(serial);
    row.activityBefore = resumedActivity(serial);
  }

  const started = performance.now();
  const output = adb(serial, 'am start -W', ['shell', 'am', 'start', '-W', '-f',
    mode === 'warm' ? '0x10008000' : '0x10000000', '-a', 'android.intent.action.MAIN',
    '-c', 'android.intent.category.LAUNCHER', '-n', activity], 15_000);
  row.total = output.match(/^TotalTime:[ \t]*(\d+)[ \t]*\r?$/m)?.[1] ?? '';
  if (!/^Status:[ \t]*ok[ \t]*\r?$/m.test(output) || row.total === '') {
    throw new Error('am start tidak melaporkan Status: ok dan TotalTime yang sah');
  }

  const deadline = started + 25_000;
  let last = 'Kartu fixture aktif di Beranda belum terlihat';
  while (performance.now() < deadline) {
    try {
      const observed = observeCard(serial, label, deadline);
      if (observed !== null && observed <= deadline) {
        row.observed = Math.ceil(observed - started);
        break;
      }
    } catch (error) {
      last = error.message;
    }
    await delay(Math.max(0, Math.min(200, deadline - performance.now())));
  }
  if (mode === 'warm') {
    row.pidAfter = pid(serial);
    row.activityAfter = resumedActivity(serial);
    if (row.pidAfter !== row.pidBefore) throw new Error('PID berubah; bukan startup hangat');
    if (row.activityAfter === row.activityBefore) throw new Error('ActivityRecord tidak berganti');
  }
  if (row.observed === undefined) throw new Error(`Observasi habis waktu: ${last}`);
}

function csvRow(values) {
  return values.map(value => {
    const field = String(value ?? '');
    return /[",\r\n]/.test(field) ? `"${field.replace(/"/g, '""')}"` : field;
  }).join(',') + '\n';
}

async function main() {
  const { values } = parseArgs({ options: {
    serial: { type: 'string' }, api: { type: 'string' }, label: { type: 'string' },
    csv: { type: 'string' }, renderer: { type: 'string' },
    'cold-only': { type: 'boolean' }, help: { type: 'boolean', short: 'h' },
  } });
  if (values.help) return console.log(usage);
  const { serial, api, label, csv, renderer = '' } = values;
  if (!/^emulator-\d+$/.test(serial ?? '') || !['26', '35'].includes(api)
    || !label?.trim() || !csv?.trim() || !['', 'vulkan', 'opengl'].includes(renderer)) {
    throw new Error('Argumen tidak valid; lihat --help');
  }
  const avd = `CEH_TEST_API_${api}`;
  preflight(serial, api, avd, label);

  const fd = openSync(csv, 'a+');
  try {
    const { size } = fstatSync(fd);
    if (size === 0) {
      appendFileSync(fd, header);
    } else {
      const first = Buffer.alloc(Buffer.byteLength(header));
      const end = Buffer.alloc(1);
      readSync(fd, first, 0, first.length, 0);
      readSync(fd, end, 0, 1, size - 1);
      if (first.toString() !== header || end[0] !== 10) throw new Error('Header atau akhir CSV tidak cocok');
    }
    const session = randomUUID();
    for (const mode of values['cold-only'] ? ['cold'] : ['cold', 'warm']) {
      for (let run = 1; run <= 5; run += 1) {
        const row = {};
        const utc = new Date().toISOString();
        let status = 'ok';
        let reason = '';
        try {
          await measure(serial, label, mode, row);
        } catch (error) {
          status = 'failed';
          reason = error.message;
          process.exitCode = 1;
        }
        appendFileSync(fd, csvRow([session, utc, serial, avd, api, renderer, label, mode, run, status,
          row.total, row.observed, row.pidBefore, row.pidAfter, row.activityBefore, row.activityAfter, reason]));
        console.log(`${mode} ${run}/5: ${status}${reason ? ` — ${reason}` : ''}`);
      }
    }
  } finally {
    closeSync(fd);
  }
}

main().catch(error => {
  console.error(error.message);
  process.exitCode = 1;
});
