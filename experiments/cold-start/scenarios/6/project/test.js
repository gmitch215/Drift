const { money } = require('tinyfmt')

const s = money(1234.5)
const ok = s === '$1,234.50'
console.log(ok ? 'PASS' : 'FAIL ' + s)
if (!ok) process.exit(1)
