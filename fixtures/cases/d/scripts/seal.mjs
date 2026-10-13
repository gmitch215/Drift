import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const out = join(here, '..', 'selection.json');
const scratch = process.argv[2];
if (!scratch) throw new Error('usage: seal.mjs <scratch dir for blobless clones>');
mkdirSync(scratch, { recursive: true });

const QUERY = 'language:php stars:1000..8000 archived:false fork:false';
const SEARCH_TAKE = 60;
const POOL_SIZE = 12;
const PIN_BEFORE = '2021-07-01';
const TARGET = '7.4.33';

const run = (cmd, args, opts = {}) => execFileSync(cmd, args, { encoding: 'utf8', maxBuffer: 1 << 26, ...opts });
const sha = (s) => createHash('sha256').update(s).digest('hex');

const found = JSON.parse(run('gh', ['api', '-X', 'GET', 'search/repositories', '-f', `q=${QUERY}`,
    '-f', 'sort=stars', '-f', 'order=desc', '-f', `per_page=${SEARCH_TAKE}`])).items
    .map((r) => ({ repo: r.full_name, stars: r.stargazers_count, fetchedAt: r.pushed_at }));

const ver = (v) => v.split('.').map((x) => parseInt(x, 10));
const cmp = (a, b) => {
    for (let i = 0; i < 3; i++) {
        const d = (a[i] ?? 0) - (b[i] ?? 0);
        if (d) return d;
    }
    return 0;
};
const comparator = (c, v) => {
    c = c.trim();
    if (c === '' || c === '*') return true;
    let m = c.match(/^(>=|<=|>|<|!=|==|=)?\s*v?(\d+(?:\.\d+)*)(?:\.\*)?$/);
    if (c.endsWith('.*')) {
        const p = ver(c.slice(0, -2).replace(/^v/, ''));
        return p.every((x, i) => v[i] === x);
    }
    if (m) {
        const op = m[1] ?? '=';
        const t = ver(m[2]);
        const d = cmp(v, t);
        return { '>=': d >= 0, '<=': d <= 0, '>': d > 0, '<': d < 0, '!=': d !== 0, '==': d === 0, '=': d === 0 }[op];
    }
    m = c.match(/^\^v?(\d+(?:\.\d+)*)$/);
    if (m) {
        const t = ver(m[1]);
        const up = t[0] > 0 ? [t[0] + 1, 0, 0] : [0, (t[1] ?? 0) + 1, 0];
        return cmp(v, t) >= 0 && cmp(v, up) < 0;
    }
    m = c.match(/^~v?(\d+(?:\.\d+)*)$/);
    if (m) {
        const t = ver(m[1]);
        const up = t.length >= 3 ? [t[0], t[1] + 1, 0] : [t[0] + 1, 0, 0];
        return cmp(v, t) >= 0 && cmp(v, up) < 0;
    }
    return null;
};
const admits = (constraint, target) => {
    const v = ver(target);
    let unknown = false;
    for (const branch of constraint.replace(/\|\|/g, '|').split('|')) {
        const parts = branch.replace(/,/g, ' ').replace(/(>=|<=|>|<|!=|==|=)\s+/g, '$1').trim().split(/\s+/);
        const rs = parts.map((p) => comparator(p, v));
        if (rs.includes(null)) { unknown = true; continue; }
        if (rs.every(Boolean)) return true;
    }
    return unknown ? null : false;
};

const rows = [];
for (const f of found) {
    const dir = join(scratch, f.repo.replace('/', '__'));
    const row = { repo: f.repo, stars: f.stars, url: `https://github.com/${f.repo}` };
    try {
        run('git', ['clone', '--quiet', '--bare', '--filter=blob:none', row.url, dir]);
        const tags = run('git', ['-C', dir, 'for-each-ref', '--format=%(refname:short)|%(creatordate:short)|%(objectname)|%(*objectname)',
            'refs/tags']).trim().split('\n').filter(Boolean).map((l) => {
            const [name, date, obj, deref] = l.split('|');
            const commit = run('git', ['-C', dir, 'rev-list', '-n', '1', name]).trim();
            const cdate = run('git', ['-C', dir, 'log', '-1', '--format=%cs', commit]).trim();
            return { name, date: cdate, commit };
        }).filter((t) => /^v?\d+\.\d+(\.\d+)?$/.test(t.name) && t.date < PIN_BEFORE)
            .sort((a, b) => (a.date < b.date ? 1 : a.date > b.date ? -1 : (a.name < b.name ? 1 : -1)));
        if (!tags.length) { row.drop = 'no stable tag dated before ' + PIN_BEFORE; rows.push(row); continue; }
        const pin = tags[0];
        row.pin = pin;
        let composer;
        try { composer = JSON.parse(run('git', ['-C', dir, 'show', `${pin.commit}:composer.json`])); }
        catch { row.drop = 'no composer.json at the pin'; rows.push(row); continue; }
        row.composerType = composer.type ?? 'library';
        row.requirePhp = composer.require?.php ?? null;
        row.phpunit = composer['require-dev']?.['phpunit/phpunit'] ?? composer.require?.['phpunit/phpunit'] ?? null;
        const tree = run('git', ['-C', dir, 'ls-tree', '--name-only', pin.commit]).split('\n');
        row.hasTests = tree.some((n) => /^(tests?|phpunit\.xml(\.dist)?)$/.test(n));
        if (row.composerType !== 'library') row.drop = `composer type ${row.composerType}`;
        else if (!row.requirePhp) row.drop = 'no require.php';
        else if (admits(row.requirePhp, TARGET) !== true) row.drop = `require.php ${row.requirePhp} does not admit ${TARGET} (or not parsed: ${admits(row.requirePhp, TARGET)})`;
        else if (!row.phpunit) row.drop = 'no phpunit/phpunit in require or require-dev';
        else if (!row.hasTests) row.drop = 'no tests directory or phpunit.xml at the pin';
    } catch (e) {
        row.drop = 'clone or inspect failed: ' + String(e.message).split('\n')[0];
    }
    rows.push(row);
}

const eligible = rows.filter((r) => !r.drop).slice(0, POOL_SIZE);
for (const c of eligible) c.urlSha256 = sha(c.url);
const order = [...eligible].sort((x, y) => (x.urlSha256 < y.urlSha256 ? -1 : 1)).map((c) => c.repo);
const sealed = {
    schema: 1,
    case: 'd',
    query: QUERY,
    searchTake: SEARCH_TAKE,
    poolSize: POOL_SIZE,
    selectionRule: [
        `the pool is the first ${POOL_SIZE} repositories, in search order (stars descending), of the first ${SEARCH_TAKE} results of the GitHub repository search "${QUERY}" that pass every filter below`,
        `pin: the latest stable release tag (vX.Y or X.Y.Z) whose commit date is before ${PIN_BEFORE}; ties go to the later tag name`,
        `filter 1: composer.json exists at the pin and its type is library or absent`,
        `filter 2: require.php at the pin admits ${TARGET}`,
        'filter 3: phpunit/phpunit is in require or require-dev at the pin',
        'filter 4: the pin has a tests or test directory, or a phpunit.xml(.dist), at the repository root',
        'order is ascending SHA-256 of the UTF-8 repository URL, compared as lowercase hex',
        'candidates run in that order; a candidate is never skipped, and one that cannot be built is recorded as not-reconstructible with the reason',
        'a candidate is reconstructible when its suite installs and passes (exit 0, no failed or errored test) on PHP 7.4 in a Linux container with network access to Packagist and GitHub only for composer, and that suite then runs to completion on PHP 8.3 (failures there are results, not a reason to skip)',
        'the case is the first reconstructible candidate in the order, whatever the prediction or the score turns out to be',
        'no rule, weight, probe or alias is added or edited between the frozen prediction and the score; rules learned from the misses go in a separate follow-up list',
    ],
    candidates: eligible,
    order,
    droppedByFilter: rows.filter((r) => r.drop).map((r) => ({ repo: r.repo, stars: r.stars, reason: r.drop })),
    notReachedInPool: rows.filter((r) => !r.drop).slice(POOL_SIZE).map((r) => r.repo),
};
const canon = (v) => JSON.stringify(v, (_, x) => (x && typeof x === 'object' && !Array.isArray(x)
    ? Object.fromEntries(Object.entries(x).sort(([p], [q]) => (p < q ? -1 : 1))) : x), '\t') + '\n';
const text = canon(sealed);
writeFileSync(out, text);
writeFileSync(out.replace(/\.json$/, '.json.sha256'), `${sha(text)}  selection.json\n`);
console.log(`${sha(text)}  selection.json`);
console.log(order.join('\n'));
