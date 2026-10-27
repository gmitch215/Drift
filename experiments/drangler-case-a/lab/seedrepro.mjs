import { readFileSync } from 'node:fs';
import http from 'node:http';
import { blockMs, sleepMs, startWorker } from './lib.mjs';

const [armsArg, repsArg, bodyFile] = process.argv.slice(2);
const arms = armsArg.split(',').map((a) => {
	const [mode, d] = a.split(':');
	return { mode, d: Number(d) };
});
const reps = Number(repsArg);
const lines = readFileSync(bodyFile, 'utf8').split('\n').filter((l) => l.trim() !== '');
const body = JSON.stringify(lines);
const w = await startWorker(8899, process.env.RUNNER ?? 'bunx');
const origin = w.origin;
console.log(JSON.stringify({ origin, bodyBytes: body.length, statements: lines.length, node: process.version }));

const postFetch = async (url, headers) => {
	const resp = await fetch(url, { method: 'POST', headers, body, signal: AbortSignal.timeout(180000) });
	const text = await resp.text();
	return { status: resp.status, len: text.length };
};

const postHttp = (url) =>
	new Promise((resolve, reject) => {
		const q = http.request(
			new URL(url),
			{ method: 'POST', agent: false, headers: { 'content-type': 'application/json', 'content-length': Buffer.byteLength(body) } },
			(m) => {
				let t = '';
				m.on('data', (c) => (t += c));
				m.on('end', () => resolve({ status: m.statusCode, len: t.length }));
			}
		);
		q.on('error', reject);
		q.end(body);
	});

let n = 0;
try {
	for (let r = 0; r < reps; r++) {
		for (const arm of arms) {
			n++;
			await sleepMs(Math.random() * 1000);
			await fetch(`${origin}/serve?site=probe`);
			const t0 = performance.now();
			if (arm.mode === 'sleep') await sleepMs(arm.d * 1000);
			else blockMs(arm.d * 1000);
			const t1 = performance.now();
			const url = `${origin}/seed?site=r${n}`;
			let res;
			try {
				const json = { 'content-type': 'application/json' };
				const got =
					arm.mode === 'newconn'
						? await postHttp(url)
						: await postFetch(url, arm.mode === 'blockclose' ? { ...json, connection: 'close' } : json);
				res = { ok: true, ...got };
			} catch (e) {
				res = { ok: false, err: String(e.message), cause: e.cause ? `${e.cause.code ?? ''} ${e.cause.message}` : (e.code ?? null) };
			}
			console.log(JSON.stringify({ n, rep: r, mode: arm.mode, d: arm.d, idleMs: Math.round(t1 - t0), ...res, postMs: Math.round(performance.now() - t1) }));
		}
	}
} finally {
	w.stop();
}
