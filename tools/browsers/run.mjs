import {
	createReadStream,
	existsSync,
	readFileSync,
	statSync,
	writeFileSync,
	mkdirSync,
} from 'node:fs';
import { createServer } from 'node:http';
import { extname, join, normalize } from 'node:path';
import { parseArgs } from 'node:util';
import { chromium, firefox, webkit } from 'playwright';

const engines = { chromium, firefox, webkit };
const types = {
	'.html': 'text/html',
	'.js': 'text/javascript',
	'.wasm': 'application/wasm',
	'.json': 'application/json',
	'.png': 'image/png',
	'.ico': 'image/x-icon',
	'.webmanifest': 'application/manifest+json',
	'.map': 'application/json',
};

const { values } = parseArgs({
	options: {
		dist: { type: 'string' },
		out: { type: 'string' },
		engine: { type: 'string', multiple: true },
		reference: { type: 'string' },
		locale: { type: 'string', default: 'en-US' },
		headed: { type: 'boolean', default: false },
		timeout: { type: 'string', default: '120000' },
	},
});
const dist = values.dist;
const out = values.out;
const timeout = Number(values.timeout);
const names = values.engine?.length ? values.engine : Object.keys(engines);
if (!dist || !out || names.some((n) => !engines[n])) {
	console.error(
		'usage: run.mjs --dist DIR --out DIR [--engine chromium|firefox|webkit]... [--reference FILE]',
	);
	process.exit(2);
}
mkdirSync(out, { recursive: true });

const probeIds = (text) =>
	text
		.split('\n')
		.filter((l) => l.startsWith('@@ '))
		.map((l) => l.slice(3));
const expected = values.reference ? probeIds(readFileSync(values.reference, 'utf8')) : null;

const server = createServer((req, res) => {
	const path = normalize(decodeURIComponent(new URL(req.url, 'http://x').pathname));
	const file = join(dist, path.endsWith('/') ? `${path}index.html` : path);
	if (!file.startsWith(normalize(dist)) || !existsSync(file) || !statSync(file).isFile()) {
		res.writeHead(404).end();
		return;
	}
	res.writeHead(200, { 'content-type': types[extname(file)] ?? 'application/octet-stream' });
	createReadStream(file).pipe(res);
});
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
const url = `http://127.0.0.1:${server.address().port}/`;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const tap = async (page, selector) => {
	const box = await page.locator(selector).first().boundingBox({ timeout });
	if (!box) throw new Error(`no box for ${selector}`);
	await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
	await page.mouse.down();
	await sleep(100);
	await page.mouse.up();
};
const text = async (page, selector) =>
	(await page.locator(selector).first().textContent({ timeout })) ?? '';

async function run(name) {
	const result = {
		engine: name,
		failures: [],
		consoleErrors: [],
		pageErrors: [],
		failedRequests: [],
	};
	const browser = await engines[name].launch({ headless: !values.headed });
	try {
		result.version = browser.version();
		const page = await (
			await browser.newContext({
				viewport: { width: 1280, height: 900 },
				...(values.locale && { locale: values.locale }),
			})
		).newPage();
		const consoleAll = [];
		page.on('console', (m) => {
			consoleAll.push(`${m.type()}: ${m.text()}`);
			if (m.type() === 'error') result.consoleErrors.push(m.text());
		});
		page.on('pageerror', (e) => result.pageErrors.push(String(e.message ?? e)));
		page.on('requestfailed', (r) =>
			result.failedRequests.push(`${r.url()} ${r.failure()?.errorText}`),
		);
		result.userAgent = await (async () => {
			await page.goto('about:blank');
			return page.evaluate(() => navigator.userAgent);
		})();
		result.language = await page.evaluate(() => [navigator.language, ...navigator.languages]);
		await page.goto(url, { waitUntil: 'load' });
		try {
			await page.waitForFunction(() => globalThis.driftDevice, null, { timeout });
		} catch (e) {
			result.failures.push(
				`no this-device transcript within ${timeout} ms: ${e.message.split('\n')[0]}`,
			);
			await sleep(500);
			result.console = consoleAll.slice(0, 40);
			await page.screenshot({ path: join(out, `${name}.png`) });
			return result;
		}
		const device = await page.evaluate(() => globalThis.driftDevice);
		result.computeMillis = device.millis;
		result.paint = await page.evaluate(() =>
			Object.fromEntries(
				performance.getEntriesByType('paint').map((p) => [p.name, Math.round(p.startTime)]),
			),
		);
		result.loadMillis = await page.evaluate(() => {
			const [n] = performance.getEntriesByType('navigation');
			return n ? Math.round(n.loadEventEnd) : null;
		});
		const header = device.transcript.split('\n', 2);
		result.kotlinVersion = header[0].replace('# kotlin.version = ', '');
		result.recordedTarget = header[1].replace('# kotlin.target = ', '');
		const transcript = device.transcript.replace(
			/^(# kotlin\.target = ).*$/m,
			`$1wasm-${name}`,
		);
		writeFileSync(join(out, `wasm-${name}.txt`), transcript);
		const ids = probeIds(transcript);
		result.probes = ids.length;
		if (expected && JSON.stringify(ids) !== JSON.stringify(expected)) {
			result.failures.push('probe ids differ from the reference transcript');
		}

		result.canvas = await page.evaluate(() => {
			const host = [...document.querySelectorAll('div')].find((d) => d.shadowRoot);
			const c = host.shadowRoot.querySelector('canvas');
			return {
				width: c.width,
				height: c.height,
				css: [c.clientWidth, c.clientHeight],
				inner: [innerWidth, innerHeight],
				dpr: devicePixelRatio,
				body: [document.body.clientWidth, document.body.clientHeight],
			};
		});
		await page.waitForSelector('text=/measured in \\d+ ms/', { timeout });
		result.measuredLabel = await text(page, 'text=/measured in \\d+ ms/');
		result.count = await text(page, '[id="count"]');
		result.deviceHeader = await text(page, '[id="device-header"]');
		if (!/probes differ between targets/.test(result.count))
			result.failures.push('matrix count missing');
		await page.screenshot({ path: join(out, `${name}.png`) });

		await tap(page, '[id="mode:predict"]');
		await page.mouse.move(600, 300);
		for (
			let i = 0;
			i < 40 && (await page.locator('[id="choice:unknown"]').count()) === 0;
			i++
		) {
			await page.mouse.wheel(0, 200);
			await sleep(100);
		}
		await page.waitForSelector('[id="prompt"]', { timeout });
		result.predictPrompt = await text(page, '[id="prompt"]');
		await tap(page, '[id="choice:unknown"]');
		await page.waitForSelector('[id="verdict"]', { timeout });
		result.predictVerdict = await text(page, '[id="verdict"]');
		await page.mouse.wheel(0, -5000);
		await page.waitForSelector('[id="score"]', { timeout });
		result.predictScore = await text(page, '[id="score"]');
		if (!/1 skipped/.test(result.predictScore))
			result.failures.push(`predict score: ${result.predictScore}`);
		await page.screenshot({ path: join(out, `${name}-predict.png`) });
	} catch (e) {
		result.failures.push(`${e.message.split('\n')[0]}`);
	} finally {
		await browser.close();
	}
	return result;
}

const results = [];
for (const name of names) {
	const r = await run(name);
	results.push(r);
	writeFileSync(join(out, `${name}.json`), `${JSON.stringify(r, null, 2)}\n`);
	console.log(JSON.stringify(r));
}
server.close();
process.exit(results.every((r) => r.failures.length === 0) ? 0 : 1);
