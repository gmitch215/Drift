targets: js, jvm, linuxX64, wasmJs

| library | probes | lines compared | lines that differ |
| --- | ---: | ---: | ---: |
| coroutines | 5 | 50 | 1 |
| datetime | 11 | 260 | 67 |
| io | 3 | 161 | 16 |
| json | 6 | 223 | 66 |

| class | lines |
| --- | ---: |
| no-correctness-claim | 81 |
| unspecified | 69 |

| probe | line | class | values by target |
| --- | --- | --- | --- |
| datetime.tz-gap-overlap | toInstant[Europe/Berlin,2024-03-31T02:30] | unspecified | js,jvm,wasmJs: `ok:2024-03-31T01:30:00Z`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-gap-overlap | roundtrip[Europe/Berlin,2024-03-31T02:30] | unspecified | js,jvm,wasmJs: `ok:2024-03-31T03:30`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-gap-overlap | toInstant[Europe/Berlin,2024-10-27T02:30] | unspecified | js,jvm,wasmJs: `ok:2024-10-27T00:30:00Z`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-gap-overlap | roundtrip[Europe/Berlin,2024-10-27T02:30] | unspecified | js,jvm,wasmJs: `ok:2024-10-27T02:30`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-gap-overlap | toInstant[Europe/Berlin,2024-10-27T03:00] | unspecified | js,jvm,wasmJs: `ok:2024-10-27T02:00:00Z`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-gap-overlap | roundtrip[Europe/Berlin,2024-10-27T03:00] | unspecified | js,jvm,wasmJs: `ok:2024-10-27T03:00`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-gap-overlap | toInstant[America/New_York,2024-03-10T02:30] | unspecified | js,jvm,wasmJs: `ok:2024-03-10T07:30:00Z`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-gap-overlap | roundtrip[America/New_York,2024-03-10T02:30] | unspecified | js,jvm,wasmJs: `ok:2024-03-10T03:30`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-gap-overlap | toInstant[America/New_York,2024-11-03T01:30] | unspecified | js,jvm,wasmJs: `ok:2024-11-03T05:30:00Z`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-gap-overlap | roundtrip[America/New_York,2024-11-03T01:30] | unspecified | js,jvm,wasmJs: `ok:2024-11-03T01:30`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-gap-overlap | toInstant[Australia/Lord_Howe,2024-04-07T01:45] | unspecified | js,jvm,wasmJs: `ok:2024-04-06T14:45:00Z`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-gap-overlap | roundtrip[Australia/Lord_Howe,2024-04-07T01:45] | unspecified | js,jvm,wasmJs: `ok:2024-04-07T01:45`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-gap-overlap | toInstant[Australia/Lord_Howe,2024-10-06T02:15] | unspecified | js,jvm,wasmJs: `ok:2024-10-05T15:45:00Z`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-gap-overlap | roundtrip[Australia/Lord_Howe,2024-10-06T02:15] | unspecified | js,jvm,wasmJs: `ok:2024-10-06T02:45`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-gap-overlap | toInstant[Pacific/Apia,2011-12-30T12:00] | unspecified | js,jvm,wasmJs: `ok:2011-12-30T22:00:00Z`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-gap-overlap | roundtrip[Pacific/Apia,2011-12-30T12:00] | unspecified | js,jvm,wasmJs: `ok:2011-12-31T12:00`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-gap-overlap | toInstant[Pacific/Apia,2011-12-31T12:00] | unspecified | js,jvm,wasmJs: `ok:2011-12-30T22:00:00Z`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-gap-overlap | roundtrip[Pacific/Apia,2011-12-31T12:00] | unspecified | js,jvm,wasmJs: `ok:2011-12-31T12:00`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-gap-overlap | toInstant[Europe/London,2024-03-31T01:30] | unspecified | js,jvm,wasmJs: `ok:2024-03-31T01:30:00Z`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-gap-overlap | roundtrip[Europe/London,2024-03-31T01:30] | unspecified | js,jvm,wasmJs: `ok:2024-03-31T02:30`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-gap-overlap | toInstant[America/Sao_Paulo,2018-11-04T00:30] | unspecified | js,jvm,wasmJs: `ok:2018-11-04T03:30:00Z`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-gap-overlap | roundtrip[America/Sao_Paulo,2018-11-04T00:30] | unspecified | js,jvm,wasmJs: `ok:2018-11-04T01:30`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-offsets | offset[Europe/London,1970-01-01T12:00:00Z] | unspecified | js,jvm,wasmJs: `ok:+01:00`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-offsets | offset[Europe/London,1968-06-01T12:00:00Z] | unspecified | js,jvm,wasmJs: `ok:+01:00`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-offsets | offset[Europe/Amsterdam,1930-01-01T12:00:00Z] | unspecified | js,jvm,wasmJs: `ok:Z`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-offsets | offset[Europe/Amsterdam,1900-01-01T12:00:00Z] | unspecified | js,jvm,wasmJs: `ok:Z`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-offsets | offset[Asia/Kolkata,1940-01-01T00:00:00Z] | unspecified | js,jvm,wasmJs: `ok:+05:30`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-offsets | offset[America/Sao_Paulo,2019-01-01T12:00:00Z] | unspecified | js,jvm,wasmJs: `ok:-02:00`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-offsets | offset[America/Sao_Paulo,2020-01-01T12:00:00Z] | unspecified | js,jvm,wasmJs: `ok:-03:00`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-offsets | offset[Europe/Berlin,2037-07-01T12:00:00Z] | unspecified | js,jvm,wasmJs: `ok:+02:00`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-offsets | offset[Europe/Berlin,2038-07-01T12:00:00Z] | unspecified | js,jvm,wasmJs: `ok:+02:00`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-offsets | offset[Europe/Berlin,2040-07-01T12:00:00Z] | unspecified | js,jvm,wasmJs: `ok:+02:00`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-offsets | offset[America/New_York,2050-07-01T12:00:00Z] | unspecified | js,jvm,wasmJs: `ok:-04:00`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-offsets | offset[America/New_York,1900-01-01T12:00:00Z] | unspecified | js,jvm,wasmJs: `ok:-05:00`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-offsets | offset[America/New_York,1883-11-18T12:00:00Z] | unspecified | js,jvm,wasmJs: `ok:-04:56:02`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-offsets | offset[Africa/Casablanca,2025-03-15T12:00:00Z] | unspecified | js,jvm,wasmJs: `ok:Z`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-offsets | offset[Africa/Casablanca,2026-03-01T12:00:00Z] | unspecified | js,jvm,wasmJs: `ok:Z`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-offsets | offset[Africa/Casablanca,2030-03-20T12:00:00Z] | unspecified | js,wasmJs: `ok:Z`<br>jvm: `ok:+01:00`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-offsets | offset[Africa/Casablanca,2036-07-01T12:00:00Z] | unspecified | js,wasmJs: `ok:Z`<br>jvm: `ok:+01:00`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-offsets | offset[Africa/Casablanca,2040-07-01T12:00:00Z] | unspecified | js,wasmJs: `ok:Z`<br>jvm: `ok:+01:00`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-offsets | offset[Pacific/Kiritimati,1994-12-31T12:00:00Z] | unspecified | js,jvm,wasmJs: `ok:+14:00`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-offsets | offset[Pacific/Apia,2011-12-29T12:00:00Z] | unspecified | js,jvm,wasmJs: `ok:-10:00`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-offsets | offset[Asia/Kathmandu,2000-01-01T12:00:00Z] | unspecified | js,jvm,wasmJs: `ok:+05:45`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-offsets | offset[Europe/Moscow,2011-06-01T12:00:00Z] | unspecified | js,jvm,wasmJs: `ok:+04:00`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-offsets | offset[Europe/Moscow,2014-12-01T12:00:00Z] | unspecified | js,jvm,wasmJs: `ok:+03:00`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-offsets | offset[Europe/Istanbul,2016-09-01T12:00:00Z] | unspecified | js,jvm,wasmJs: `ok:+03:00`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-ids | of[Etc/GMT+5] | unspecified | js,jvm,wasmJs: `ok:Etc/GMT+5`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-ids | of[America/Argentina/Buenos_Aires] | unspecified | js,jvm,wasmJs: `ok:America/Argentina/Buenos_Aires`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-ids | of[Asia/Kolkata] | unspecified | js,jvm,wasmJs: `ok:Asia/Kolkata`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-ids | of[Asia/Calcutta] | unspecified | js,jvm,wasmJs: `ok:Asia/Calcutta`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-ids | of[Europe/Kiev] | unspecified | js,jvm,wasmJs: `ok:Europe/Kiev`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-ids | of[Europe/Kyiv] | unspecified | js,jvm,wasmJs: `ok:Europe/Kyiv`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-ids | of[US/Pacific] | unspecified | js,jvm,wasmJs: `ok:US/Pacific`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-ids | of[EST] | unspecified | js,wasmJs: `ok:EST`<br>jvm,linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-ids | of[EST5EDT] | unspecified | js,jvm,wasmJs: `ok:EST5EDT`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-ids | of[Europe/Madrid] | unspecified | js,jvm,wasmJs: `ok:Europe/Madrid`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.tz-ids | available[Europe/Berlin] | unspecified | js,jvm,wasmJs: `ok:true`<br>linuxX64: `err:IllegalStateException` |
| datetime.tz-ids | available[Asia/Calcutta] | unspecified | js,jvm,wasmJs: `ok:true`<br>linuxX64: `err:IllegalStateException` |
| datetime.tz-ids | available[Europe/Kiev] | unspecified | js,jvm,wasmJs: `ok:true`<br>linuxX64: `err:IllegalStateException` |
| datetime.tz-ids | available[US/Pacific] | unspecified | js,jvm,wasmJs: `ok:true`<br>linuxX64: `err:IllegalStateException` |
| datetime.tz-ids | available[EST] | unspecified | js,wasmJs: `ok:true`<br>jvm: `ok:false`<br>linuxX64: `err:IllegalStateException` |
| datetime.tz-ids | available-count-over-300 | unspecified | js,jvm,wasmJs: `ok:true`<br>linuxX64: `err:IllegalStateException` |
| datetime.arithmetic | berlin+1day | unspecified | js,jvm,wasmJs: `ok:2024-03-31T03:30`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.arithmetic | berlin+24h | unspecified | js,jvm,wasmJs: `ok:2024-03-31T03:30`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.arithmetic | berlin+period-1d | unspecified | js,jvm,wasmJs: `ok:2024-03-31T03:30`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.arithmetic | berlin-until-days | unspecified | js,jvm,wasmJs: `ok:2`<br>linuxX64: `err:IllegalTimeZoneException` |
| datetime.arithmetic | berlin-periodUntil | unspecified | js,jvm,wasmJs: `ok:P2D`<br>linuxX64: `err:IllegalTimeZoneException` |
| json.numbers | encode-double[444b1ae4d6e2ef50] | no-correctness-claim | js,wasmJs: `ok:1e+21`<br>jvm,linuxX64: `ok:1.0E21` |
| json.numbers | primitive-content[444b1ae4d6e2ef50] | no-correctness-claim | js,wasmJs: `ok:1e+21`<br>jvm,linuxX64: `ok:1.0E21` |
| json.numbers | encode-double[3e7ad7f29abcaf48] | no-correctness-claim | js,wasmJs: `ok:1e-7`<br>jvm,linuxX64: `ok:1.0E-7` |
| json.numbers | primitive-content[3e7ad7f29abcaf48] | no-correctness-claim | js,wasmJs: `ok:1e-7`<br>jvm,linuxX64: `ok:1.0E-7` |
| json.numbers | encode-double[1] | no-correctness-claim | js,wasmJs: `ok:5e-324`<br>jvm,linuxX64: `ok:4.9E-324` |
| json.numbers | primitive-content[1] | no-correctness-claim | js,wasmJs: `ok:5e-324`<br>jvm,linuxX64: `ok:4.9E-324` |
| json.numbers | encode-double[7fefffffffffffff] | no-correctness-claim | js,wasmJs: `ok:1.7976931348623157e+308`<br>jvm,linuxX64: `ok:1.7976931348623157E308` |
| json.numbers | primitive-content[7fefffffffffffff] | no-correctness-claim | js,wasmJs: `ok:1.7976931348623157e+308`<br>jvm,linuxX64: `ok:1.7976931348623157E308` |
| json.numbers | encode-double[4059000000000000] | no-correctness-claim | js: `ok:100`<br>jvm,linuxX64,wasmJs: `ok:100.0` |
| json.numbers | primitive-content[4059000000000000] | no-correctness-claim | js: `ok:100`<br>jvm,linuxX64,wasmJs: `ok:100.0` |
| json.numbers | encode-double[4202a05f20000000] | no-correctness-claim | js: `ok:10000000000`<br>jvm,linuxX64: `ok:1.0E10`<br>wasmJs: `ok:10000000000.0` |
| json.numbers | primitive-content[4202a05f20000000] | no-correctness-claim | js: `ok:10000000000`<br>jvm,linuxX64: `ok:1.0E10`<br>wasmJs: `ok:10000000000.0` |
| json.numbers | encode-double[-8000000000000000] | no-correctness-claim | js: `ok:0`<br>jvm,linuxX64,wasmJs: `ok:-0.0` |
| json.numbers | primitive-content[-8000000000000000] | no-correctness-claim | js: `ok:0`<br>jvm,linuxX64,wasmJs: `ok:-0.0` |
| json.numbers | encode-double[437b69b4ba630f35] | no-correctness-claim | js: `ok:123456789012345680`<br>jvm,linuxX64: `ok:1.2345678901234568E17`<br>wasmJs: `ok:123456789012345680.0` |
| json.numbers | primitive-content[437b69b4ba630f35] | no-correctness-claim | js: `ok:123456789012345680`<br>jvm,linuxX64: `ok:1.2345678901234568E17`<br>wasmJs: `ok:123456789012345680.0` |
| json.numbers | encode-double[4340000000000000] | no-correctness-claim | js: `ok:9007199254740992`<br>jvm,linuxX64: `ok:9.007199254740992E15`<br>wasmJs: `ok:9007199254740992.0` |
| json.numbers | primitive-content[4340000000000000] | no-correctness-claim | js: `ok:9007199254740992`<br>jvm,linuxX64: `ok:9.007199254740992E15`<br>wasmJs: `ok:9007199254740992.0` |
| json.numbers | encode-double[10000000000000] | no-correctness-claim | js,wasmJs: `ok:2.2250738585072014e-308`<br>jvm,linuxX64: `ok:2.2250738585072014E-308` |
| json.numbers | primitive-content[10000000000000] | no-correctness-claim | js,wasmJs: `ok:2.2250738585072014e-308`<br>jvm,linuxX64: `ok:2.2250738585072014E-308` |
| json.numbers | encode-double[4480f0cf064dd592] | no-correctness-claim | js,wasmJs: `ok:1e+22`<br>jvm,linuxX64: `ok:1.0E22` |
| json.numbers | primitive-content[4480f0cf064dd592] | no-correctness-claim | js,wasmJs: `ok:1e+22`<br>jvm,linuxX64: `ok:1.0E22` |
| json.numbers | encode-double[44b52d02c7e14af6] | no-correctness-claim | js: `ok:1e+23`<br>jvm,linuxX64: `ok:1.0E23`<br>wasmJs: `ok:9.999999999999999e+22` |
| json.numbers | primitive-content[44b52d02c7e14af6] | no-correctness-claim | js: `ok:1e+23`<br>jvm,linuxX64: `ok:1.0E23`<br>wasmJs: `ok:9.999999999999999e+22` |
| json.numbers | encode-double[447c7e83209e90b2] | no-correctness-claim | js: `ok:8.41e+21`<br>jvm,linuxX64: `ok:8.41E21`<br>wasmJs: `ok:8.409999999999999e+21` |
| json.numbers | primitive-content[447c7e83209e90b2] | no-correctness-claim | js: `ok:8.41e+21`<br>jvm,linuxX64: `ok:8.41E21`<br>wasmJs: `ok:8.409999999999999e+21` |
| json.numbers | encode-double[3f0a36e2eb1c432d] | no-correctness-claim | js,wasmJs: `ok:0.00005`<br>jvm,linuxX64: `ok:5.0E-5` |
| json.numbers | primitive-content[3f0a36e2eb1c432d] | no-correctness-claim | js,wasmJs: `ok:0.00005`<br>jvm,linuxX64: `ok:5.0E-5` |
| json.numbers | encode-double[3f1a36e2eb1c432d] | no-correctness-claim | js,wasmJs: `ok:0.0001`<br>jvm,linuxX64: `ok:1.0E-4` |
| json.numbers | primitive-content[3f1a36e2eb1c432d] | no-correctness-claim | js,wasmJs: `ok:0.0001`<br>jvm,linuxX64: `ok:1.0E-4` |
| json.numbers | encode-double[4132d68700000000] | no-correctness-claim | js: `ok:1234567`<br>jvm,linuxX64,wasmJs: `ok:1234567.0` |
| json.numbers | primitive-content[4132d68700000000] | no-correctness-claim | js: `ok:1234567`<br>jvm,linuxX64,wasmJs: `ok:1234567.0` |
| json.numbers | encode-double[42a674e79cb6b200] | no-correctness-claim | js: `ok:12345678912345`<br>jvm,linuxX64: `ok:1.2345678912345E13`<br>wasmJs: `ok:12345678912345.0` |
| json.numbers | primitive-content[42a674e79cb6b200] | no-correctness-claim | js: `ok:12345678912345`<br>jvm,linuxX64: `ok:1.2345678912345E13`<br>wasmJs: `ok:12345678912345.0` |
| json.numbers | encode-double[3ee4f8b588e368f1] | no-correctness-claim | js,wasmJs: `ok:0.00001`<br>jvm,linuxX64: `ok:1.0E-5` |
| json.numbers | primitive-content[3ee4f8b588e368f1] | no-correctness-claim | js,wasmJs: `ok:0.00001`<br>jvm,linuxX64: `ok:1.0E-5` |
| json.numbers | encode-double[4] | no-correctness-claim | js,wasmJs: `ok:2e-323`<br>jvm,linuxX64: `ok:2.0E-323` |
| json.numbers | primitive-content[4] | no-correctness-claim | js,wasmJs: `ok:2e-323`<br>jvm,linuxX64: `ok:2.0E-323` |
| json.numbers | encode-double[419d6f34547e6b75] | no-correctness-claim | js,wasmJs: `ok:123456789.12345679`<br>jvm,linuxX64: `ok:1.2345678912345679E8` |
| json.numbers | primitive-content[419d6f34547e6b75] | no-correctness-claim | js,wasmJs: `ok:123456789.12345679`<br>jvm,linuxX64: `ok:1.2345678912345679E8` |
| json.numbers | encode-float[4b800000] | no-correctness-claim | js: `ok:16777216`<br>jvm,linuxX64: `ok:1.6777216E7`<br>wasmJs: `ok:16777216.0` |
| json.numbers | encode-float[7f7fffff] | no-correctness-claim | js,wasmJs: `ok:3.4028235e+38`<br>jvm,linuxX64: `ok:3.4028235E38` |
| json.numbers | encode-float[800000] | no-correctness-claim | js,wasmJs: `ok:1.1754944e-38`<br>jvm,linuxX64: `ok:1.1754944E-38` |
| json.numbers | encode-float[1] | no-correctness-claim | js: `ok:1.4e-45`<br>jvm,linuxX64: `ok:1.4E-45`<br>wasmJs: `ok:1e-45` |
| json.numbers | encode-float[501502f9] | no-correctness-claim | js: `ok:10000000000`<br>jvm,linuxX64: `ok:1.0E10`<br>wasmJs: `ok:10000000000.0` |
| json.numbers | encode-float[3727c5ac] | no-correctness-claim | js,wasmJs: `ok:0.00001`<br>jvm,linuxX64: `ok:1.0E-5` |
| json.numbers | encode-float[4b189680] | no-correctness-claim | js: `ok:10000000`<br>jvm,linuxX64: `ok:1.0E7`<br>wasmJs: `ok:10000000.0` |
| json.numbers | decode-float[1e39] | no-correctness-claim | js: `ok:7f800000`<br>jvm,linuxX64,wasmJs: `err:JsonDecodingException` |
| json.numbers | wide-roundtrip | no-correctness-claim | js: `ok:{"l":9223372036854775807,"d":1e+21,"f":0.1} \| 9223372036854776000 444b1ae4d6e2ef50 3dcccccd`<br>jvm,linuxX64: `ok:{"l":9223372036854775807,"d":1.0E21,"f":0.1} \| 9223372036854775807 444b1ae4d6e2ef50 3dcccccd`<br>wasmJs: `ok:{"l":9223372036854775807,"d":1e+21,"f":0.1} \| 9223372036854775807 444b1ae4d6e2ef50 3dcccccd` |
| json.special-floats | decode-nan-lowercase-allowed | no-correctness-claim | js: `ok:NaN`<br>jvm,linuxX64,wasmJs: `err:JsonDecodingException` |
| json.special-floats | stdlib-toDouble[nan] | no-correctness-claim | js: `ok:7ff8000000000000`<br>jvm,linuxX64,wasmJs: `err:NumberFormatException` |
| json.special-floats | stdlib-toDouble[0x10] | no-correctness-claim | js: `ok:4030000000000000`<br>jvm,linuxX64,wasmJs: `err:NumberFormatException` |
| json.special-floats | encode-negzero | no-correctness-claim | js: `ok:0`<br>jvm,linuxX64,wasmJs: `ok:-0.0` |
| json.element-accessors | double-2.0-int | no-correctness-claim | js: `ok:2`<br>jvm,linuxX64,wasmJs: `err:NumberFormatException` |
| json.element-accessors | double-2.0-intOrNull | no-correctness-claim | js: `ok:2`<br>jvm,linuxX64,wasmJs: `ok:null` |
| json.element-accessors | string-long-max+1-double | no-correctness-claim | js: `ok:9223372036854776000`<br>jvm,linuxX64: `ok:9.223372036854776E18`<br>wasmJs: `ok:9223372036854776000.0` |
| json.element-accessors | long-2^53+1-double | no-correctness-claim | js: `ok:9007199254740992`<br>jvm,linuxX64: `ok:9.007199254740992E15`<br>wasmJs: `ok:9007199254740992.0` |
| json.element-accessors | float-1e39 | no-correctness-claim | js: `ok:1e+39`<br>jvm,linuxX64,wasmJs: `ok:Infinity` |
| json.element-accessors | double-nan-string | no-correctness-claim | js: `ok:NaN`<br>jvm,linuxX64,wasmJs: `ok:null` |
| json.element-accessors | double-big-literal-double | no-correctness-claim | js: `ok:100`<br>jvm,linuxX64,wasmJs: `ok:100.0` |
| json.element-accessors | parse-number-double | no-correctness-claim | js: `ok:12345678901234567000`<br>jvm,linuxX64: `ok:1.2345678901234567E19`<br>wasmJs: `ok:12345678901234567000.0` |
| json.element-accessors | parse-neg-zero-double | no-correctness-claim | js: `ok:0`<br>jvm,linuxX64,wasmJs: `ok:-0.0` |
| json.element-accessors | equals-1-1.0 | no-correctness-claim | js: `ok:true`<br>jvm,linuxX64,wasmJs: `ok:false` |
| json.element-accessors | equals-0.0--0.0 | no-correctness-claim | js: `ok:true`<br>jvm,linuxX64,wasmJs: `ok:false` |
| json.defaults-nulls | map-double-key | no-correctness-claim | js: `ok:{"1":1,"1e+21":2,"0.1":3}`<br>jvm,linuxX64: `ok:{"1.0":1,"1.0E21":2,"0.1":3}`<br>wasmJs: `ok:{"1.0":1,"1e+21":2,"0.1":3}` |
| json.defaults-nulls | map-float-key | no-correctness-claim | js: `ok:{"0.1":1,"10000000000":2}`<br>jvm,linuxX64: `ok:{"0.1":1,"1.0E10":2}`<br>wasmJs: `ok:{"0.1":1,"10000000000.0":2}` |
| io.utf8-malformed | decodeToString-throw[overlong-C0AF] | no-correctness-claim | js,linuxX64,wasmJs: `err:CharacterCodingException`<br>jvm: `err:MalformedInputException` |
| io.utf8-malformed | decodeToString-throw[overlong-E08080] | no-correctness-claim | js,linuxX64,wasmJs: `err:CharacterCodingException`<br>jvm: `err:MalformedInputException` |
| io.utf8-malformed | decodeToString[surrogate-EDA080] | unspecified | js,linuxX64,wasmJs: `ok:fffd fffd fffd`<br>jvm: `ok:fffd` |
| io.utf8-malformed | decodeToString-throw[surrogate-EDA080] | no-correctness-claim | js,linuxX64,wasmJs: `err:CharacterCodingException`<br>jvm: `err:MalformedInputException` |
| io.utf8-malformed | decodeToString-throw[above-10ffff-F4908080] | no-correctness-claim | js,linuxX64,wasmJs: `err:CharacterCodingException`<br>jvm: `err:MalformedInputException` |
| io.utf8-malformed | decodeToString-throw[truncated-E282] | no-correctness-claim | js,linuxX64,wasmJs: `err:CharacterCodingException`<br>jvm: `err:MalformedInputException` |
| io.utf8-malformed | decodeToString-throw[lone-continuation-80] | no-correctness-claim | js,linuxX64,wasmJs: `err:CharacterCodingException`<br>jvm: `err:MalformedInputException` |
| io.utf8-malformed | decodeToString-throw[valid-then-bad] | no-correctness-claim | js,linuxX64,wasmJs: `err:CharacterCodingException`<br>jvm: `err:MalformedInputException` |
| io.utf8-malformed | decodeToString-throw[F8-lead] | no-correctness-claim | js,linuxX64,wasmJs: `err:CharacterCodingException`<br>jvm: `err:MalformedInputException` |
| io.utf8-malformed | decodeToString-throw[lead-then-ascii-E241] | no-correctness-claim | js,linuxX64,wasmJs: `err:CharacterCodingException`<br>jvm: `err:MalformedInputException` |
| io.utf8-malformed | encodeToByteArray-lone-surrogate | unspecified | js,linuxX64,wasmJs: `ok:61 ef bf bd 62`<br>jvm: `ok:61 3f 62` |
| io.decimal-long | readDecimalLong[9223372036854775807] | no-correctness-claim | js: `ok:9223372036854776000 9223372036854775807 max=true min=false hi=2147483647 lo=4294967295 rest=0`<br>jvm,linuxX64,wasmJs: `ok:9223372036854775807 9223372036854775807 max=true min=false hi=2147483647 lo=4294967295 rest=0` |
| io.decimal-long | readDecimalLong[-9223372036854775808] | no-correctness-claim | js: `ok:-9223372036854776000 -9223372036854775808 max=false min=true hi=2147483648 lo=0 rest=0`<br>jvm,linuxX64,wasmJs: `ok:-9223372036854775808 -9223372036854775808 max=false min=true hi=2147483648 lo=0 rest=0` |
| io.decimal-long | attempt-template-parsed | no-correctness-claim | js: `ok:9223372036854776000 9223372036854775807 max=true hi=2147483647`<br>jvm,linuxX64,wasmJs: `ok:9223372036854775807 9223372036854775807 max=true hi=2147483647` |
| io.decimal-long | attempt-template-read-single | no-correctness-claim | js: `ok:9223372036854776000`<br>jvm,linuxX64,wasmJs: `ok:9223372036854775807` |
| io.decimal-long | attempt-template-read-pair | no-correctness-claim | js: `ok:9223372036854776000 9223372036854775807`<br>jvm,linuxX64,wasmJs: `ok:9223372036854775807 9223372036854775807` |
| coroutines.timeout-messages | timeout-is-cancellation | no-correctness-claim | js: `ok:true true undefined`<br>jvm,linuxX64,wasmJs: `ok:true true null` |
