import { spawn } from 'node:child_process';
import { appendFileSync, existsSync, mkdirSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

export const TREE = process.env.DRANGLER_TREE;
if (!TREE) throw new Error('set DRANGLER_TREE to a drangler checkout with node_modules');
export const FIXTURE = `${TREE}/tests/e2e/fixture-worker`;

export async function startWorker(port = 8899, runner = 'bunx') {
	const dir = join(tmpdir(), `p5-lab-${Date.now().toString(36)}-${port}`);
	mkdirSync(dir, { recursive: true });
	const logFile = join(dir, 'dev.log');
	const args =
		runner === 'bunx'
			? ['bunx', ['wrangler']]
			: [`${TREE}/node_modules/.bin/wrangler`, []];
	const dev = spawn(
		args[0],
		[
			...args[1],
			'dev',
			'-c',
			`${FIXTURE}/wrangler.jsonc`,
			'--port',
			String(port),
			'--inspector-port',
			String(port + 1000),
			'--persist-to',
			join(dir, 'state'),
			'--local'
		],
		{
			cwd: FIXTURE,
			env: { ...process.env, WRANGLER_LOG: 'debug' },
			stdio: ['ignore', 'pipe', 'pipe'],
			detached: true
		}
	);
	let exit = null;
	dev.on('exit', (code, signal) => {
		exit = { code, signal };
		appendFileSync(logFile, `\nwrangler exited code=${code} signal=${signal}\n`);
	});
	const append = (c) => appendFileSync(logFile, String(c));
	dev.stdout.on('data', append);
	dev.stderr.on('data', append);
	const stop = () => {
		try {
			process.kill(-dev.pid, 'SIGTERM');
		} catch {}
	};
	const started = Date.now();
	for (;;) {
		if (exit) throw new Error(`wrangler exited ${JSON.stringify(exit)}`);
		if (Date.now() - started > 180_000) {
			stop();
			throw new Error('wrangler not ready in 180s');
		}
		const log = existsSync(logFile) ? readFileSync(logFile, 'utf8') : '';
		const ready = /Ready on (https?:\/\/[^\s]+)/.exec(log);
		if (ready) {
			const origin = ready[1].replace(/\/+$/, '');
			for (let i = 0; i < 40; i++) {
				try {
					await fetch(`${origin}/serve?site=probe`, { signal: AbortSignal.timeout(3000) });
					return { origin, stop, logFile };
				} catch {
					await new Promise((r) => setTimeout(r, 500));
				}
			}
			stop();
			throw new Error('ready but never answered');
		}
		await new Promise((r) => setTimeout(r, 400));
	}
}

export const blockMs = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
export const sleepMs = (ms) => new Promise((r) => setTimeout(r, ms));
