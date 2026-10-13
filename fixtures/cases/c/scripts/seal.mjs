import { createHash } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const out = join(here, '..', 'sealed.json');

const candidates = [
    {
        id: 'INC-01', status: 'CONFIRMED', ecosystem: 'other', platform: 'linux',
        url: 'https://github.com/jerryscript-project/jerryscript/pull/2551',
        confirmingUrl: 'https://github.com/jerryscript-project/jerryscript/pull/2551',
        causeKey: 'env.TZ', failingSide: 'b', a: 'TZ=UTC', b: 'TZ=a non-UTC zone',
        pins: { change: '66a5bff4ce', merged: '2018-10-09', passingZone: 'America/Los_Angeles is what the fix enforces' },
    },
    {
        id: 'INC-02', status: 'CONFIRMED', ecosystem: 'Python, runner image', platform: 'linux',
        url: 'https://github.com/actions/runner-images/issues/10781',
        confirmingUrl: 'https://github.com/actions/runner-images/issues/10781',
        causeKey: 'os.release.VERSION_ID', failingSide: 'b', a: '22.04', b: '24.04',
        pins: { aImage: 'ubuntu:22.04', bImage: 'ubuntu:24.04', pythonA: '3.10', pythonB: '3.12', runnerImage: '20241006.1.0', marker: 'EXTERNALLY-MANAGED' },
    },
    {
        id: 'INC-03', status: 'CONFIRMED', ecosystem: 'Node', platform: 'windows',
        url: 'https://github.com/nodejs/node/issues/56137',
        confirmingUrl: 'https://github.com/nodejs/node/issues/56137',
        causeKey: 'tool.node.version', failingSide: 'b', a: '22.11.0', b: '22.12.0',
        pins: { os: 'Windows 11 10.0.26100.0', libuvChange: 'nodejs/node#56224', linux: 'consistent across both versions' },
    },
    {
        id: 'INC-04', status: 'PARTIAL', ecosystem: 'Docker', platform: 'linux',
        url: 'https://github.com/devcontainers/ci/issues/191',
        confirmingUrl: 'https://github.com/devcontainers/ci/issues/191',
        causeKey: 'NEEDS: tool.skopeo.version', failingSide: 'b', a: 'ubuntu-24.04 or upgraded skopeo', b: 'ubuntu-22.04 with skopeo 1.4.1',
        pins: { skopeoB: '1.4.1', skopeoA: null },
    },
    {
        id: 'INC-05', status: 'PARTIAL', ecosystem: 'runner image', platform: 'linux',
        url: 'https://github.com/actions/runner-images/issues/10636',
        confirmingUrl: 'https://github.com/actions/runner-images/issues/10636',
        causeKey: 'os.release.VERSION_ID', failingSide: 'b', a: '22.04', b: '24.04',
        pins: { aImage: 'ubuntu:22.04', bImage: 'ubuntu:24.04', failingCommand: null },
    },
    {
        id: 'INC-06', status: 'CONFIRMED', ecosystem: 'JDK', platform: 'linux',
        url: 'https://github.com/flutter/flutter/issues/156304',
        confirmingUrl: 'https://github.com/flutter/flutter/issues/156304#issuecomment-2396493786',
        causeKey: 'tool.java.version', failingSide: 'b', a: '17', b: '21',
        pins: { flutter: null, androidSdk: null, aImage: null, bImage: null },
    },
    {
        id: 'INC-07', status: 'CONFIRMED', ecosystem: 'Node', platform: 'linux',
        url: 'https://github.com/webpack/webpack/issues/14532',
        confirmingUrl: 'https://github.com/webpack/webpack/issues/14532#issuecomment-947515866',
        causeKey: 'tool.node.version', failingSide: 'b', a: '16 (implied, not measured in the thread)', b: '17.0.0',
        pins: { webpack: '5.59.0', bImage: 'node:17.0.0', aImage: 'node:16 (patch chosen at reconstruction)' },
    },
    {
        id: 'INC-08', status: 'CONFIRMED', ecosystem: 'JDK', platform: 'linux',
        url: 'https://github.com/Shopkeepers/Shopkeepers/issues/886',
        confirmingUrl: 'https://github.com/Shopkeepers/Shopkeepers/issues/886#issuecomment-2142992865',
        causeKey: 'NEEDS: jvm.user.language', failingSide: 'b', a: 'JVM default locale (not stated)', b: 'user.language=tr, user.region=TR',
        pins: { shopkeepers: null, spigot: null },
    },
    {
        id: 'INC-09', status: 'CONFIRMED', ecosystem: 'runner image', platform: 'windows',
        url: 'https://github.com/actions/runner-images/issues/7782',
        confirmingUrl: 'https://github.com/actions/runner-images/issues/7782#issuecomment-1632665024',
        causeKey: 'ci.runner.image.version', failingSide: 'b', a: '20230612.1.0', b: '20230620.1.0',
        pins: { dotnetSdkA: '6.0.408', dotnetSdkB: '6.0.411' },
    },
    {
        id: 'INC-10', status: 'PARTIAL', ecosystem: 'JDK (Docker, musl)', platform: 'linux',
        url: 'https://github.com/AdoptOpenJDK/openjdk-docker/issues/75',
        confirmingUrl: 'https://github.com/AdoptOpenJDK/openjdk-docker/issues/75#issuecomment-436618992',
        causeKey: 'os.release.ID', failingSide: 'b', a: 'ubuntu', b: 'alpine',
        pins: { aImage: 'adoptopenjdk/openjdk11:jdk-11.0.2.9', bImage: 'adoptopenjdk/openjdk11:jdk-11.0.2.9-alpine', arch: 'x86_64' },
    },
    {
        id: 'INC-11', status: 'PARTIAL', ecosystem: 'JDK', platform: 'linux',
        url: 'https://github.com/projectlombok/lombok/issues/3393',
        confirmingUrl: 'https://github.com/projectlombok/lombok/issues/3393#issuecomment-1691067212',
        causeKey: 'tool.java.version', failingSide: 'b', a: 'a JDK below 21 (not stated)', b: '21',
        pins: { lombok: '1.18.26', bJdk: '21 (the thread used an early-access build)', aJdk: '17 chosen at reconstruction' },
    },
    {
        id: 'INC-12', status: 'CONFIRMED', ecosystem: 'Docker', platform: 'linux',
        url: 'https://github.com/prisma/orm/issues/19729',
        confirmingUrl: 'https://github.com/prisma/orm/issues/19729#issuecomment-1591270599',
        causeKey: 'os.release.VERSION_ID', failingSide: 'b', a: '11', b: '12',
        pins: { aImage: 'node:18.16.0-bullseye-slim', bImage: 'node:18.16.0-bookworm-slim', prisma: '4.15.0' },
    },
    {
        id: 'INC-13', status: 'PARTIAL', ecosystem: 'Docker', platform: 'linux',
        url: 'https://github.com/prisma/orm/issues/25817',
        confirmingUrl: 'https://github.com/prisma/orm/issues/25817#issuecomment-2530926082',
        causeKey: 'os.release.VERSION_ID', failingSide: 'b', a: '3.20', b: '3.21',
        pins: { node: '22', alpineA: '3.20', alpineB: '3.21', prisma: null },
    },
    {
        id: 'INC-14', status: 'PARTIAL', ecosystem: 'glibc/musl', platform: 'linux',
        url: 'https://github.com/boazsegev/iodine/issues/156',
        confirmingUrl: 'https://github.com/boazsegev/iodine/issues/156#issuecomment-2543063098',
        causeKey: 'tool.cc.version', failingSide: 'b', a: 'gcc of Alpine 3.20 (not stated)', b: 'gcc 14.2.0-r4 (Alpine 3.21)',
        pins: { aImage: 'ruby:3.3.6-alpine3.20', bImage: 'ruby:3.3.6-alpine3.21', iodine: '0.7.58', apk: 'g++ make' },
    },
];

const sha = (s) => createHash('sha256').update(s).digest('hex');
for (const c of candidates) c.urlSha256 = sha(c.url);

const order = [...candidates].sort((x, y) => (x.urlSha256 < y.urlSha256 ? -1 : 1)).map((c) => c.id);
const orderSha256 = sha(order.map((id) => candidates.find((c) => c.id === id).url).join('\n') + '\n');

const sealed = {
    schema: 1,
    case: 'c',
    source: 'the 14 labelled incidents of .claude/batch/research/incidents/incidents.md (survey P2-W3-B)',
    selectionRule: [
        'every incident labelled CONFIRMED or PARTIAL in incidents.md is a candidate; none is dropped or added by the sealer',
        'a candidate is identified by its issue or pull request URL',
        'order is ascending SHA-256 of the UTF-8 URL, compared as lowercase hex',
        'candidates run in that order; a candidate is never skipped, and one that cannot be built is recorded as not-reconstructible with the reason',
        'a candidate is reconstructible when both sides build in Linux containers from pinned public images or package versions and the failing command exits nonzero on side b and zero on side a',
        'the flagship is the first reconstructible candidate in the order, whatever verdict solve returns',
        'no rule, weight or probe is changed after sealing, and results are published whatever they are',
    ],
    candidates,
    order,
    orderSha256,
};

const canon = (v) => JSON.stringify(v, (_, x) => (x && typeof x === 'object' && !Array.isArray(x)
    ? Object.fromEntries(Object.entries(x).sort(([p], [q]) => (p < q ? -1 : 1))) : x), '\t') + '\n';
const text = canon(sealed);
writeFileSync(out, text);
writeFileSync(out + '.sha256', `${sha(text)}  sealed.json\n`);
console.log(`${sha(text)}  sealed.json`);
console.log(order.join(' '));
