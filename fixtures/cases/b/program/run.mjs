import { execFileSync } from 'node:child_process'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))

if (process.env.DRIFT_RUNTIME_PLATFORM === 'wasm') {
	await import(join(here, 'wasm', 'resume-offset.mjs'))
} else {
	process.stdout.write(execFileSync(join(here, 'native', 'resume-offset.kexe')))
}
