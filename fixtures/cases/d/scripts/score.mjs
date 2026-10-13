import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const read = (f) => readFileSync(join(root, f), 'utf8');
const sha = (s) => createHash('sha256').update(s).digest('hex');
const canon = (v) => JSON.stringify(v, (_, x) => (x && typeof x === 'object' && !Array.isArray(x)
    ? Object.fromEntries(Object.entries(x).sort(([p], [q]) => (p < q ? -1 : 1))) : x), '\t') + '\n';

const prediction = JSON.parse(read('prediction.json'));
const protocol = JSON.parse(read('score-protocol.json'));
const predicted = [
    ...prediction['will-break'].map((f) => ({ rule: f.rule, group: 'will-break', severity: f.severity, basis: f.basis })),
    ...prediction['may-break'].map((f) => ({ rule: f.rule, group: 'may-break', severity: f.severity, basis: f.basis })),
    ...prediction.unknown.map((f) => ({ rule: f.rule, group: 'unknown', severity: f.severity, basis: f.basis })),
];

const summary = (log) => {
    const ok = log.match(/^OK \((\d+) tests, (\d+) assertions\)/m);
    if (ok) return { status: 'pass', tests: +ok[1], assertions: +ok[2], failed: 0, errors: 0 };
    const bad = log.match(/^Tests: (\d+), Assertions: (\d+)(?:, Errors: (\d+))?(?:, Failures: (\d+))?/m);
    if (bad) return { status: 'fail', tests: +bad[1], assertions: +bad[2], errors: +(bad[3] ?? 0), failed: +(bad[4] ?? 0) };
    return { status: 'did-not-complete' };
};
const oldRun = summary(read('runs/jwt-old-test.log'));
const newRun = summary(read('runs/jwt-new-test.log'));
const emitNew = summary(read('runs/jwt-new-emit.log'));
const lockHeld = summary(read('runs/jwt-lockheld-test.log'));
if (oldRun.status !== 'pass') throw new Error('the baseline is not green');

const newFailures = newRun.status === 'pass' ? [] : null;
if (newFailures === null) throw new Error('the new stack has failing tests; attribute them by hand before scoring');

const rows = predicted.map((p) => ({
    ...p,
    pattern: protocol.patterns[p.rule],
    outcome: 'FP',
    attributedTests: [],
}));
const score = {
    schema: 1,
    case: 'd',
    candidate: 'lcobucci/jwt 4.1.4',
    predictionSha256: sha(read('prediction.json')),
    scoreProtocolSha256: sha(read('score-protocol.json')),
    stacks: { old: 'PHP 7.4.33, Composer 2.10.3', new: 'PHP 8.3.35, Composer 2.10.3' },
    baseline: oldRun,
    migrated: { installStep: read('runs/jwt-new-install-used.txt').trim().replace('install step used: ', ''), ...newRun },
    testLevel: {
        newFailingTests: 0,
        TP: rows.filter((r) => r.outcome === 'TP').length,
        FP: rows.filter((r) => r.outcome === 'FP').length,
        MISS: 0,
        willBreakPredicted: prediction['will-break'].length,
        mayBreakPredicted: prediction['may-break'].length,
    },
    rows,
    emittedOnly: { scan: 'suite rerun with deprecations, notices and warnings fatal and error_reporting=-1', ...emitNew, note: 'no deprecation, notice or warning was emitted by the project or its dependencies on PHP 8.3' },
    migrationFindings: [
        {
            id: 'lock-install-refused',
            step: '1-install',
            text: 'composer install from the committed lock on PHP 8.3 refused: phpspec/prophecy 1.12.2 (locked, required by phpunit 9.5.0) requires php ^7.2 || ~8.0, <8.1',
            predictedByCatalog: false,
            reason: 'no rule reads a locked package whose own php constraint excludes the target; the capture held project.composer.phpspec/prophecy = 1.12.2 and project.composer.phpunit/phpunit = 9.5.0',
        },
        {
            id: 'update-moves-60-packages',
            step: '2-update',
            text: 'composer update changed 60 packages, added 4 and removed 5 (phpunit 9.5.0 to 9.6.38, mikey179/vfsstream 1.6.8 to 1.6.12, phpspec/prophecy removed); the scored run used these versions',
            predictedByCatalog: false,
            reason: 'dependency drift is outside what a capture of today can say about a target stack',
        },
        {
            id: 'allow-plugins',
            step: 'both stacks',
            text: 'composer 2.10.3 refuses the lock because composer.json has no allow-plugins block (composer 2.2 behaviour); both stacks retried with --no-plugins',
            predictedByCatalog: 'not applicable (composer version did not change between the stacks)',
            reason: 'the capture lists project.composer.json.plugins.unlisted with 4 plugins; rule composer-2-2-allow-plugins-default-empty reads a composer version change, and the version was the same on both sides',
        },
    ],
    supplementary: {
        label: 'not scored; run after the protocol was frozen, to separate the PHP change from the dependency change',
        lockHeldOnPhp83: {
            command: 'composer install --no-plugins --ignore-platform-reqs from the same lock, then vendor/bin/phpunit (protocol step 3, run out of order)',
            result: lockHeld.status,
            note: 'phpunit 9.5.0 starts; after 63 of 280 tests, PHP stops with a fatal error: mikey179/vfsstream 1.6.8 vfsStreamDirectory::getIterator() is incompatible with IteratorAggregate::getIterator(): Traversable (PHP 8.1 tentative return types)',
            predictedByCatalog: false,
            reason: 'no rule covers tentative return types or #[ReturnTypeWillChange]',
        },
    },
    score: 'On the frozen protocol the prediction had 0 true positives, 12 false positives and 0 misses, because the migrated suite had no failing test. The one real PHP 8 failure (the vfsStream fatal) appears only with the old dependency set held, and the catalog has no rule for it.',
};
const text = canon(score);
writeFileSync(join(root, 'score.json'), text);
console.log(sha(text), 'score.json');
console.log(JSON.stringify(score.testLevel), JSON.stringify(score.migrated), JSON.stringify(score.supplementary.lockHeldOnPhp83.result));
