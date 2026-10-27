import { spawn } from 'node:child_process';
import { once } from 'node:events';

const [armsArg = 'block:5.5,block:6,sleep:6', repsArg = '10'] = process.argv.slice(2);
const undici = process.env.UNDICI ? await import(process.env.UNDICI) : null;
const fetch = undici ? undici.fetch : globalThis.fetch;

const serverSrc = `
import http from 'node:http';
const s = http.createServer((req, res) => {
	req.resume();
	req.on('end', () => res.end('ok'));
});
s.listen(0, '127.0.0.1', () => console.log(s.address().port));
`;
const child = spawn(process.execPath, ['--input-type=module', '-e', serverSrc], {
	stdio: ['ignore', 'pipe', 'inherit']
});
const [chunk] = await once(child.stdout, 'data');
const port = Number(String(chunk).trim());
const origin = `http://127.0.0.1:${port}`;
const body = 'x'.repeat(6 * 1024 * 1024);
const block = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const arms = armsArg.split(',').map((a) => {
	const [mode, d] = a.split(':');
	return { mode, d: Number(d) };
});
const tally = new Map();
for (let r = 0; r < Number(repsArg); r++) {
	for (const arm of arms) {
		await sleep(Math.random() * 1000);
		await (await fetch(`${origin}/`)).text();
		if (arm.mode.startsWith('block')) block(arm.d * 1000);
		else await sleep(arm.d * 1000);
		let ok = true;
		try {
			await (
				await fetch(`${origin}/`, {
					method: 'POST',
					body,
					headers: arm.mode === 'blockclose' ? { connection: 'close' } : {}
				})
			).text();
		} catch (e) {
			ok = false;
		}
		const key = `${arm.mode}:${arm.d}`;
		const t = tally.get(key) ?? { fail: 0, n: 0 };
		t.n++;
		if (!ok) t.fail++;
		tally.set(key, t);
	}
}
console.log(JSON.stringify({ node: process.version, undici: undici ? process.env.UNDICI : process.versions.undici, platform: `${process.platform}-${process.arch}`, results: Object.fromEntries(tally) }));
child.kill();
