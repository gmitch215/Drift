import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { parseArgs } from 'node:util';
import { chromium, firefox, webkit } from 'playwright';

const engines = { chromium, firefox, webkit };
const { values } = parseArgs({
	options: {
		url: { type: 'string' },
		out: { type: 'string' },
		engine: { type: 'string', multiple: true },
		'ignore-https-errors': { type: 'boolean', default: false },
		headed: { type: 'boolean', default: false },
		timeout: { type: 'string', default: '60000' },
	},
});
const timeout = Number(values.timeout);
const names = values.engine?.length ? values.engine : Object.keys(engines);
if (!values.url || !values.out || names.some((n) => !engines[n])) {
	console.error(
		'usage: serve.mjs --url URL --out DIR [--engine NAME]... [--ignore-https-errors] [--headed]',
	);
	process.exit(2);
}
mkdirSync(values.out, { recursive: true });

async function run(name) {
	const result = { engine: name, url: values.url, failures: [], consoleErrors: [], failed: [] };
	const browser = await engines[name].launch({
		headless: !values.headed,
		firefoxUserPrefs: { 'security.enterprise_roots.enabled': true },
	});
	try {
		result.version = browser.version();
		const context = await browser.newContext({ ignoreHTTPSErrors: values['ignore-https-errors'] });
		const page = await context.newPage();
		page.on('console', (m) => m.type() === 'error' && result.consoleErrors.push(m.text()));
		page.on('requestfailed', (r) => result.failed.push(`${r.url()} ${r.failure()?.errorText}`));
		const response = await page.goto(values.url, { waitUntil: 'load', timeout });
		result.status = response?.status();
		await page.waitForFunction(() => globalThis.driftDevice, null, { timeout });
		Object.assign(
			result,
			await page.evaluate(() => ({
				title: document.title,
				protocol: location.protocol,
				secureContext: isSecureContext,
				crossOriginIsolated,
				computeMillis: globalThis.driftDevice.millis,
			})),
		);
		if (result.title !== 'Drift Studio') result.failures.push(`title is ${result.title}`);
		if (result.failed.length) result.failures.push('a request failed');
		await page.screenshot({ path: join(values.out, `serve-${name}.png`) });
	} catch (e) {
		result.failures.push(e.message.split('\n')[0]);
	} finally {
		await browser.close();
	}
	return result;
}

const results = [];
for (const name of names) {
	const r = await run(name);
	results.push(r);
	console.log(JSON.stringify(r));
}
writeFileSync(join(values.out, 'serve.json'), `${JSON.stringify(results, null, 2)}\n`);
process.exit(results.every((r) => r.failures.length === 0) ? 0 : 1);
