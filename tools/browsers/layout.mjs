import { createReadStream, existsSync, mkdirSync, statSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { createRequire } from 'node:module';
import { extname, join, normalize } from 'node:path';
import { parseArgs } from 'node:util';
import { chromium, firefox, webkit } from 'playwright';

const engines = { chromium, firefox, webkit };
const types = {
    '.html': 'text/html',
    '.css': 'text/css',
    '.js': 'text/javascript',
    '.json': 'application/json',
    '.png': 'image/png',
    '.svg': 'image/svg+xml',
};

const supports = {
    'interpolate-size': 'interpolate-size: allow-keywords',
    'corner-shape': 'corner-shape: squircle',
    'position-anchor': 'position-anchor: --a',
    'field-sizing': 'field-sizing: content',
    'text-box-trim': 'text-box-trim: trim-both',
};

const media = {
    narrow: '(max-width: 599px)',
    wide: '(min-width: 1024px)',
    landscape: '(orientation: landscape)',
    'hover-hover': '(hover: hover)',
    'pointer-fine': '(pointer: fine)',
    'reduced-motion': '(prefers-reduced-motion: reduce)',
    dark: '(prefers-color-scheme: dark)',
};

const { values } = parseArgs({
    options: {
        dir: { type: 'string' },
        url: { type: 'string' },
        out: { type: 'string' },
        engine: { type: 'string', multiple: true },
        viewport: { type: 'string', multiple: true },
        select: { type: 'string', multiple: true },
        headed: { type: 'boolean', default: false },
        timeout: { type: 'string', default: '30000' },
    },
});
const names = values.engine?.length ? values.engine : Object.keys(engines);
const viewports = (values.viewport?.length ? values.viewport : ['390x844', '768x1024', '1280x800'])
    .map((v) => v.match(/^(\d+)x(\d+)(?:@(\d+(?:\.\d+)?))?$/))
    .map((m) => m && { width: Number(m[1]), height: Number(m[2]), dpr: Number(m[3] ?? 1) });
if (
    !values.out ||
    !(values.dir || values.url) ||
    names.some((n) => !engines[n]) ||
    viewports.some((v) => !v) ||
    values.select?.some((s) => !/^[\w-]+=.+/.test(s))
) {
    console.error(
        'usage: layout.mjs (--dir DIR | --url URL) --out DIR [--engine NAME]... ' +
            '[--viewport WxH[@DPR]]... [--select ID=SELECTOR]...',
    );
    process.exit(2);
}
mkdirSync(values.out, { recursive: true });
const timeout = Number(values.timeout);
const playwrightVersion = createRequire(import.meta.url)('playwright/package.json').version;

let server;
let url = values.url;
if (values.dir) {
    server = createServer((req, res) => {
        const path = normalize(decodeURIComponent(new URL(req.url, 'http://x').pathname));
        const file = join(values.dir, path.endsWith('/') ? `${path}index.html` : path);
        if (
            !file.startsWith(normalize(values.dir)) ||
            !existsSync(file) ||
            !statSync(file).isFile()
        ) {
            res.writeHead(404).end();
            return;
        }
        res.writeHead(200, { 'content-type': types[extname(file)] ?? 'application/octet-stream' });
        createReadStream(file).pipe(res);
    });
    await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
    url = `http://127.0.0.1:${server.address().port}/`;
}

const num = (n) => String(Number(n.toFixed(2)));

const measure = (page, picks) =>
    page.evaluate(
        async ({ supports, media, picks }) => {
            await document.fonts.ready;
            await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
            const nodes = picks
                ? picks.map(([id, selector]) => [id, document.querySelector(selector)])
                : [...document.querySelectorAll('[data-drift]')].map((n) => [n.dataset.drift, n]);
            return {
                boxes: nodes.map(([id, n]) => [id, n && n.getBoundingClientRect().toJSON()]),
                supports: Object.entries(supports).map(([k, d]) => [k, CSS.supports(d)]),
                media: Object.entries(media).map(([k, q]) => [k, matchMedia(q).matches]),
                inner: [innerWidth, innerHeight],
                dpr: devicePixelRatio,
            };
        },
        { supports, media, picks },
    );

const capsule = (name, version, vp, got) => {
    const source = `playwright ${playwrightVersion} ${name}`;
    const attrs = [
        ['browser.engine.name', name],
        ['browser.engine.version', version],
        ['browser.playwright.version', playwrightVersion],
        ['browser.viewport.width', String(got.inner[0])],
        ['browser.viewport.height', String(got.inner[1])],
        ['browser.dpr', num(got.dpr)],
    ];
    for (const [k, v] of got.supports) attrs.push([`browser.css.supports.${k}`, String(v)]);
    for (const [k, v] of got.media) attrs.push([`browser.media.${k}`, String(v)]);
    for (const [id, b] of got.boxes) {
        for (const f of ['x', 'y', 'width', 'height']) {
            attrs.push([`browser.layout.${id}.${f}`, b ? num(b[f]) : 'missing']);
        }
    }
    return {
        schema: 1,
        label: `${name}@${vp.width}x${vp.height}`,
        attributes: attrs
            .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
            .map(([path, value]) => ({ path, source, stability: 'static', value })),
        probes: [],
    };
};

const picks = values.select?.map((s) => [s.slice(0, s.indexOf('=')), s.slice(s.indexOf('=') + 1)]);
let failed = false;
for (const name of names) {
    const browser = await engines[name].launch({ headless: !values.headed });
    try {
        for (const vp of viewports) {
            const context = await browser.newContext({
                viewport: { width: vp.width, height: vp.height },
                deviceScaleFactor: vp.dpr,
            });
            const page = await context.newPage();
            try {
                await page.goto(url, { waitUntil: 'load', timeout });
                const c = capsule(name, browser.version(), vp, await measure(page, picks));
                const file = join(values.out, `${name}-${vp.width}x${vp.height}.json`);
                writeFileSync(file, `${JSON.stringify(c)}\n`);
                console.log(file);
            } catch (e) {
                failed = true;
                console.error(`${name} ${vp.width}x${vp.height}: ${e.message.split('\n')[0]}`);
            } finally {
                await context.close();
            }
        }
    } finally {
        await browser.close();
    }
}
server?.close();
process.exit(failed ? 1 : 0);
