const stdlibNumber = 'Double and Float toString differ between targets in the standard library (KT-21235, KT-35422, KT-24975); the library prints what toString returns'

export const rules = [
    {
        probe: /^datetime\./,
        when: (v) => Object.values(v).some((x) => /^err:(IllegalTimeZoneException|IllegalStateException)$/.test(x)) &&
            Object.entries(v).every(([t, x]) => t === 'linuxX64' || !/^err:/.test(x)),
        cls: 'unspecified',
        why: 'the container has no tz database; TimeZone.of documents the Linux search path and IllegalTimeZoneException when no zone is found',
    },
    {
        probe: /^datetime\.tz-ids$/,
        label: /EST\]$/,
        cls: 'unspecified',
        why: 'region ids come from the platform database; TimeZone.of says how each platform acquires them',
    },
    {
        probe: /^datetime\.tz-offsets$/,
        label: /Casablanca/,
        cls: 'unspecified',
        why: 'the README says the time zone data of a platform may be outdated; JS and Wasm read @js-joda/timezone',
    },
    { probe: /^datetime\.duration$/, label: /toDouble/, cls: 'no-correctness-claim', why: stdlibNumber },
    {
        probe: /^json\.(numbers|element-accessors|defaults-nulls)$/,
        cls: 'no-correctness-claim',
        why: stdlibNumber,
    },
    {
        probe: /^json\.special-floats$/,
        cls: 'no-correctness-claim',
        why: 'String.toDouble accepts different spellings per target (KT-72508 says the pages list no accepted formats); -0.0 prints as 0 on JS',
    },
    {
        probe: /^io\.utf8-malformed$/,
        label: /^decodeToString-throw/,
        cls: 'no-correctness-claim',
        why: 'every target throws CharacterCodingException or a subclass, which is what decodeToString documents',
    },
    {
        probe: /^io\.utf8-malformed$/,
        label: /^decodeToString\[/,
        cls: 'unspecified',
        why: 'decodeToString says "Malformed byte sequences are replaced by the replacement char" without a count',
    },
    {
        probe: /^io\.utf8-malformed$/,
        label: /^encodeToByteArray/,
        cls: 'unspecified',
        why: 'encodeToByteArray says "Any malformed char sequence is replaced by the replacement byte sequence" and does not define the bytes',
    },
    {
        probe: /^io\.decimal-long$/,
        cls: 'no-correctness-claim',
        why: 'Kotlin/JS prints a Long template inside an inlined lambda through a double (same value read back exact with toString); a compiler defect, see build/kotlin-js-issues.md',
    },
    {
        probe: /^coroutines\.timeout-messages$/,
        cls: 'no-correctness-claim',
        why: 'Kotlin/JS prints a null cause as undefined in a string template',
    },
]

export function classify(probe, label, values) {
    for (const r of rules) {
        if (!r.probe.test(probe)) continue
        if (r.label && !r.label.test(label)) continue
        if (r.when && !r.when(values)) continue
        return r
    }
    return { cls: 'unclassified', why: '' }
}
