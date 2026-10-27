import { DatabaseSync } from 'node:sqlite';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const sql = readFileSync(process.argv[2], 'utf8');
const dir = mkdtempSync(join(tmpdir(), 'p5-replay-'));
const t0 = performance.now();
const db = new DatabaseSync(join(dir, 'replay.db'));
db.exec(sql);
db.close();
rmSync(dir, { recursive: true, force: true });
console.log(JSON.stringify({ tmpdir: tmpdir(), ms: Math.round(performance.now() - t0), node: process.version }));
