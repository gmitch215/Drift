import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { classify } from './classification.mjs'

const [dir, ...flags] = process.argv.slice(2)
const withMessages = flags.includes('--messages')

const targets = readdirSync(dir)
    .filter((f) => f.endsWith('.txt'))
    .map((f) => f.slice(0, -4))
    .sort()

const parse = (text) => {
    const probes = new Map()
    let current = null
    for (const line of text.split('\n')) {
        if (line.startsWith('#') || line === '') continue
        if (line.startsWith('@@ ')) {
            current = new Map()
            probes.set(line.slice(3), current)
        } else if (current) {
            const at = line.indexOf(' = ')
            current.set(at < 0 ? line : line.slice(0, at), at < 0 ? '' : line.slice(at + 3))
        }
    }
    return probes
}

const data = new Map(targets.map((t) => [t, parse(readFileSync(join(dir, t + '.txt'), 'utf8'))]))
const ids = [...new Set([...data.values()].flatMap((p) => [...p.keys()]))]
const family = (id) => id.split('.')[0]
const stats = new Map()
const classes = new Map()
const rows = []

for (const id of ids) {
    const labels = [...new Set([...data.values()].flatMap((p) => [...(p.get(id)?.keys() ?? [])]))]
    for (const label of labels) {
        const isMessage = label.includes('~msg')
        if (isMessage && !withMessages) continue
        const s = stats.get(family(id)) ?? { probes: new Set(), compared: 0, differ: 0 }
        stats.set(family(id), s)
        s.probes.add(id)
        s.compared++
        const byValue = new Map()
        for (const t of targets) {
            const v = data.get(t).get(id)?.get(label) ?? '(absent)'
            byValue.set(v, [...(byValue.get(v) ?? []), t])
        }
        if (byValue.size > 1) {
            s.differ++
            const values = Object.fromEntries(
                [...byValue].flatMap(([v, ts]) => ts.map((t) => [t, v])),
            )
            const c = classify(id, label, values)
            classes.set(c.cls, (classes.get(c.cls) ?? 0) + 1)
            rows.push({ id, label, cls: c.cls, groups: [...byValue].map(([v, ts]) => ({ v, ts })) })
        }
    }
}

const esc = (s) => s.replaceAll('|', '\\|').replaceAll('\n', '\\n')
const out = []
out.push(`targets: ${targets.join(', ')}`)
out.push('')
out.push('| library | probes | lines compared | lines that differ |')
out.push('| --- | ---: | ---: | ---: |')
for (const [f, s] of [...stats].sort()) {
    out.push(`| ${f} | ${s.probes.size} | ${s.compared} | ${s.differ} |`)
}
out.push('')
out.push('| class | lines |')
out.push('| --- | ---: |')
for (const [c, n] of [...classes].sort()) out.push(`| ${c} | ${n} |`)
out.push('')
out.push('| probe | line | class | values by target |')
out.push('| --- | --- | --- | --- |')
for (const r of rows) {
    const cell = r.groups.map((g) => `${g.ts.join(',')}: \`${esc(g.v)}\``).join('<br>')
    out.push(`| ${r.id} | ${esc(r.label)} | ${r.cls} | ${cell} |`)
}
console.log(out.join('\n'))
process.exit(rows.length > 0 ? 1 : 0)
