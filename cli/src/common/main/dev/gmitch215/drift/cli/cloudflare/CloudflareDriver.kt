package dev.gmitch215.drift.cli.cloudflare

internal const val CLOUDFLARE_BEGIN = "=====DRIFT-TRANSCRIPT-BEGIN====="
internal const val CLOUDFLARE_END = "=====DRIFT-TRANSCRIPT-END====="
internal const val CLOUDFLARE_RETRIES = 10

/**
 * Node script that starts `wrangler dev` (or runs `wrangler deploy`), requests the Worker once and
 * prints the body between two marker lines. Drift has no HTTP client and no way to stop a process
 * it started, so Node does both. Arguments: mode, directory, wrangler command, port, timeout in
 * seconds and whether the deploy is a dry run.
 */
internal val CLOUDFLARE_DRIVER = """
const { spawn, spawnSync } = require('node:child_process');
const net = require('node:net');
const { existsSync } = require('node:fs');
const [mode, dir, wrangler, portArg, timeoutArg, dry] = process.argv.slice(1);
const win = process.platform === 'win32';
const limit = Number(timeoutArg) * 1000;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const fail = (m) => {
  throw new Error(m);
};
const pick = () => {
  if (wrangler) return wrangler.split(' ');
  const found = spawnSync('wrangler', ['--version'], { shell: win, encoding: 'utf8' });
  return found.status === 0 ? ['wrangler'] : ['npx', 'wrangler'];
};
const freePort = () =>
  new Promise((resolve) => {
    const s = net.createServer().listen(0, '127.0.0.1', () => {
      const p = s.address().port;
      s.close(() => resolve(p));
    });
  });
const start = (extra) => {
  const [cmd, ...head] = pick();
  return spawn(cmd, [...head, ...extra], {
    cwd: dir,
    shell: win,
    detached: !win,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
};
const stop = (child) => {
  if (child.exitCode !== null) return;
  try {
    if (win) spawnSync('taskkill', ['/pid', String(child.pid), '/t', '/f']);
    else process.kill(-child.pid, 'SIGTERM');
  } catch (e) {}
};
const get = async (url) => {
  const res = await fetch(url, { signal: AbortSignal.timeout(limit) });
  const body = await res.text();
  if (!res.ok) fail('the Worker answered ' + res.status + ': ' + body.slice(0, 300));
  return body;
};
const emit = (text) => {
  console.log('$CLOUDFLARE_BEGIN');
  process.stdout.write(text.endsWith('\n') ? text : text + '\n');
  console.log('$CLOUDFLARE_END');
};
const watch = (child, pattern) =>
  new Promise((resolve, reject) => {
    let log = '';
    const timer = setTimeout(
      () => reject(new Error('wrangler was not ready in time\n' + log.slice(-600))),
      limit,
    );
    const feed = (d) => {
      log += d;
      const m = log.match(pattern);
      if (m) {
        clearTimeout(timer);
        resolve(m);
      }
    };
    child.stdout.on('data', feed);
    child.stderr.on('data', feed);
    child.on('error', (e) => reject(new Error('cannot start wrangler: ' + e.message)));
    child.on('exit', (c) =>
      reject(new Error('wrangler exited with ' + c + '\n' + log.slice(-600))),
    );
  });

(async () => {
  if (!existsSync(dir)) fail('no such worker directory: ' + dir);
  if (mode === 'dev') {
    const port = Number(portArg) || (await freePort());
    const child = start([
      'dev', '--local', '--ip', '127.0.0.1', '--port', String(port),
      '--show-interactive-dev-session', 'false',
    ]);
    try {
      const [, url] = await watch(child, /Ready on (http:\/\/\S+)/);
      console.log('wrangler dev ready on ' + url);
      emit(await get(url));
    } finally {
      stop(child);
    }
    return;
  }
  const child = start(dry === 'true' ? ['deploy', '--dry-run'] : ['deploy']);
  let log = '';
  child.stdout.on('data', (d) => (log += d));
  child.stderr.on('data', (d) => (log += d));
  const code = await new Promise((resolve, reject) => {
    child.on('error', (e) => reject(new Error('cannot start wrangler: ' + e.message)));
    child.on('exit', resolve);
  });
  console.log(log.trim());
  if (code !== 0) fail('wrangler deploy exited with ' + code);
  if (dry === 'true') return;
  const url = (log.match(/https:\/\/\S+\.workers\.dev/) || [])[0];
  if (!url) fail('wrangler deploy printed no workers.dev address');
  for (let i = 0; ; i++) {
    try {
      emit(await get(url));
      return;
    } catch (e) {
      if (i >= $CLOUDFLARE_RETRIES) fail('the deployed Worker never answered: ' + e.message);
      await sleep(3000);
    }
  }
})()
  .catch((e) => {
    console.error('error: ' + e.message);
    process.exitCode = 1;
  })
  .finally(() => setTimeout(() => process.exit(), 2000).unref());
""".trimIndent()
