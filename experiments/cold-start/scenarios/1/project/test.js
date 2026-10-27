const orders = [
	{ id: 1, status: 'paid' },
	{ id: 2, status: 'open' },
]
const groups = Object.groupBy(orders, (o) => o.status)
const ok = Object.keys(groups).sort().join(',') === 'open,paid'
console.log(ok ? 'PASS' : 'FAIL')
if (!ok) process.exit(1)
