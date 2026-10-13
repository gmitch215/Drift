import { createHash } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const sha = (b) => createHash('sha256').update(b).digest('hex');
const sealedText = readFileSync(join(root, 'sealed.json'));
const sealed = JSON.parse(sealedText);
const outcomes = JSON.parse(readFileSync(join(root, 'outcomes.json'), 'utf8'));

const ustar = (buf) => {
    const files = {};
    for (let p = 0; p + 512 <= buf.length; ) {
        const name = buf.toString('latin1', p, p + 100).replace(/\0.*$/s, '');
        if (!name) break;
        const size = parseInt(buf.toString('latin1', p + 124, p + 135), 8);
        files[name] = buf.subarray(p + 512, p + 512 + size);
        p += 512 + Math.ceil(size / 512) * 512;
    }
    return files;
};

const rows = sealed.order.map((id, index) => {
    const cand = sealed.candidates.find((c) => c.id === id);
    const out = outcomes[id];
    if (!out) throw new Error(`no outcome recorded for ${id}`);
    const row = { id, order: index + 1, url: cand.url, causeKey: cand.causeKey, ...out };
    const file = join(root, `${id}.driftcase`);
    if (existsSync(file)) {
        const bytes = readFileSync(file);
        const files = ustar(bytes);
        const cert = JSON.parse(files['certificate.json'].toString('utf8'));
        const ranking = JSON.parse(files['hypotheses/ranking.json'].toString('utf8'));
        const verdict = cert.verdict;
        const paths = ranking.candidates.map((c) => c.path);
        const rank = paths.indexOf(cand.causeKey);
        const named = [...(verdict.members ?? []), ...(verdict.minimalSet ?? [])];
        row.archive = `${id}.driftcase`;
        row.archiveSha256 = sha(bytes);
        row.verdict = verdict.label;
        row.reason = verdict.reason ?? null;
        row.mechanism = verdict.mechanism ?? null;
        row.minimalSet = verdict.minimalSet ?? [];
        row.members = verdict.members ?? [];
        row.experiments = (cert.experiments ?? []).length;
        row.trials = cert.budget.trials;
        row.seconds = cert.budget.seconds;
        row.causeRank = rank < 0 ? null : rank + 1;
        row.rankedCandidates = paths.length;
        row.causeKeyNamedByVerdict = named.includes(cand.causeKey);
    }
    return row;
});

const results = { schema: 1, sealedSha256: sha(sealedText), rows };
const canon = (v) =>
    JSON.stringify(
        v,
        (_, x) =>
            x && typeof x === 'object' && !Array.isArray(x)
                ? Object.fromEntries(Object.entries(x).sort(([p], [q]) => (p < q ? -1 : 1)))
                : x,
        '\t',
    ) + '\n';
writeFileSync(join(root, 'results.json'), canon(results));
for (const r of rows) {
    console.log(
        [r.order, r.id, r.reconstructible ? 'reconstructed' : 'not-reconstructible', r.verdict ?? '-', r.causeRank ?? '-'].join('\t'),
    );
}
