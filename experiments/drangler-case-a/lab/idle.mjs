import net from 'node:net';
import { startWorker } from './lib.mjs';

const reps = Number(process.argv[2] ?? 3);
const w = await startWorker(8899, process.env.RUNNER ?? 'bunx');
const u = new URL(w.origin);
console.log(JSON.stringify({ origin: w.origin }));
try {
	for (let i = 0; i < reps; i++) {
		await new Promise((resolve) => {
			const s = net.connect(Number(u.port), u.hostname === 'localhost' ? '127.0.0.1' : u.hostname);
			let buf = '';
			let t0 = 0;
			s.on('connect', () =>
				s.write('GET /serve?site=probe HTTP/1.1\r\nHost: localhost\r\n\r\n')
			);
			s.on('data', (d) => {
				buf += d;
				if (!t0 && buf.includes('</html>')) t0 = performance.now();
			});
			let timer;
			const done = (why) => {
				clearTimeout(timer);
				console.log(JSON.stringify({ rep: i, why, idleMs: t0 ? Math.round(performance.now() - t0) : null }));
				s.destroy();
				resolve();
			};
			s.on('end', () => done('server-fin'));
			s.on('error', (e) => done(`error ${e.code}`));
			timer = setTimeout(() => done('none-in-40s'), 40000);
		});
	}
} finally {
	w.stop();
}
