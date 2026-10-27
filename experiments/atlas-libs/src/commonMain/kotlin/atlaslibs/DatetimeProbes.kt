package atlaslibs

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DateTimePeriod
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.daysUntil
import kotlinx.datetime.format
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.monthsUntil
import kotlinx.datetime.offsetAt
import kotlinx.datetime.periodUntil
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.until

val datetimeProbes = listOf(
    Probe("datetime.instant-parse") {
        val inputs = listOf(
            "2020-01-01T00:00:00Z",
            "2020-01-01T00:00:00.123456789Z",
            "2020-01-01T00:00:00.1234567891Z",
            "2020-01-01T00:00:00,5Z",
            "+10000-01-01T00:00:00Z",
            "-0001-12-31T23:59:59Z",
            "0000-01-01T00:00:00Z",
            "2020-01-01T24:00:00Z",
            "2020-02-30T00:00:00Z",
            "2016-12-31T23:59:60Z",
            "2020-01-01t00:00:00z",
            "2020-01-01T00:00:00+01:00",
            "2020-01-01T00:00:00+01:00:30",
            "2020-01-01T00:00:00+0100",
            "2020-01-01T00:00Z",
            "2020-01-01 00:00:00Z",
            "2020-01-01T00:00:00",
            "20200101T000000Z",
        )
        for (s in inputs) attempt("parse[$s]") { Instant.parse(s) }
    },
    Probe("datetime.instant-range") {
        line("min", minInstant)
        line("max", maxInstant)
        line("distant-past", Instant.DISTANT_PAST)
        line("distant-future", Instant.DISTANT_FUTURE)
        line("min-epoch-seconds", minInstant.epochSeconds)
        line("max-epoch-seconds", maxInstant.epochSeconds)
        attempt("fromEpochSeconds[min-1]") { Instant.fromEpochSeconds(minInstant.epochSeconds - 1) }
        attempt("fromEpochSeconds[max+1]") { Instant.fromEpochSeconds(maxInstant.epochSeconds + 1) }
        attempt("fromEpochSeconds[long-max]") { Instant.fromEpochSeconds(Long.MAX_VALUE) }
        attempt("fromEpochMilliseconds[long-max]") { Instant.fromEpochMilliseconds(Long.MAX_VALUE) }
        attempt("fromEpochMilliseconds[long-min]") { Instant.fromEpochMilliseconds(Long.MIN_VALUE) }
        attempt("max+1ns") { maxInstant + 1.nanoseconds }
        attempt("min-1ns") { minInstant - 1.nanoseconds }
        attempt("max+1ns-saturating") { Instant.fromEpochSeconds(0, Long.MAX_VALUE) }
        attempt("max-minus-min") { maxInstant - minInstant }
        attempt("distant-future-plus-year") { Instant.DISTANT_FUTURE + 365.days }
    },
    Probe("datetime.instant-format") {
        val epochs = listOf(
            0L to 0L,
            -1L to 0L,
            0L to 100L,
            0L to 120_000_000L,
            0L to 123_456_000L,
            0L to 999_999_999L,
            253402300800L to 0L,
            -62167219200L to 0L,
            -62167219201L to 0L,
            -377705116800L to 0L,
            1_000_000_000_000L to 7L,
        )
        for ((s, n) in epochs) attempt("toString[$s,$n]") { Instant.fromEpochSeconds(s, n) }
        attempt("toString[ms-neg]") { Instant.fromEpochMilliseconds(-1) }
        attempt("toString[ms-float]") { Instant.fromEpochMilliseconds(1_700_000_000_123) }
    },
    Probe("datetime.localdate") {
        val inputs = listOf(
            "2020-02-29",
            "2021-02-29",
            "0000-01-01",
            "-0001-01-01",
            "10000-01-01",
            "+10000-01-01",
            "+999999-12-31",
            "+1000000-01-01",
            "-999999-01-01",
            "2020-1-1",
            "2020-01-01T00:00",
            "20200101",
        )
        for (s in inputs) attempt("parse[$s]") { LocalDate.parse(s) }
        attempt("ctor[999999-12-31]") { LocalDate(999_999, 12, 31) }
        attempt("ctor[1000000-1-1]") { LocalDate(1_000_000, 1, 1) }
        attempt("ctor[-999999-1-1]") { LocalDate(-999_999, 1, 1) }
        attempt("ctor[-1000000-1-1]") { LocalDate(-1_000_000, 1, 1) }
        attempt("max+1day") { LocalDate(999_999, 12, 31).plus(1, DateTimeUnit.DAY) }
        attempt("min-1day") { LocalDate(-999_999, 1, 1).plus(-1, DateTimeUnit.DAY) }
        attempt("max-epoch-days") { LocalDate(999_999, 12, 31).toEpochDays() }
        attempt("min-epoch-days") { LocalDate(-999_999, 1, 1).toEpochDays() }
        attempt("fromEpochDays[long-max]") { LocalDate.fromEpochDays(Long.MAX_VALUE) }
        attempt("dow[0000-01-01]") { LocalDate(0, 1, 1).dayOfWeek }
        attempt("dow[1582-10-15]") { LocalDate(1582, 10, 15).dayOfWeek }
        attempt("dayOfYear[2024-12-31]") { LocalDate(2024, 12, 31).dayOfYear }
        attempt("dayOfYear[1900-12-31]") { LocalDate(1900, 12, 31).dayOfYear }
        attempt("dayOfYear[2000-12-31]") { LocalDate(2000, 12, 31).dayOfYear }
        attempt("localdatetime[+10000]") { LocalDateTime.parse("+10000-01-01T00:00") }
        attempt("localtime[24:00]") { LocalTime.parse("24:00") }
        attempt("localtime[.5]") { LocalTime.parse("12:00:00.5") }
        attempt("localtime[trailing-zeros]") { LocalTime(12, 0, 0, 500_000_000) }
        attempt("localtime[1ns]") { LocalTime(12, 0, 0, 1) }
    },
    Probe("datetime.period") {
        attempt("normalize") {
            DateTimePeriod(
                months = 14, days = 40, hours = 30, minutes = 75, seconds = 3600,
                nanoseconds = 1_500_000_000L,
            )
        }
        attempt("negative-mix") {
            DateTimePeriod(years = 1, months = -14, days = 3, hours = -5, minutes = 5)
        }
        attempt("date-period-months") { DatePeriod(months = 25) }
        attempt("zero") { DateTimePeriod() }
        val inputs = listOf(
            "P1Y2M3DT4H5M6.789S",
            "PT-0.5S",
            "-P1D",
            "P1W",
            "P1Y2W3D",
            "PT0S",
            "P-1Y2M",
            "PT1.5M",
            "PT.5S",
            "P",
            "PT",
            "pt1s",
            "P1.5D",
            "PT0.0000000001S",
            "PT9223372036854775807S",
        )
        for (s in inputs) attempt("parse[$s]") { DateTimePeriod.parse(s) }
        for (s in listOf("P1Y", "P1DT1H", "PT1H")) {
            attempt("dateperiod-parse[$s]") { DatePeriod.parse(s) }
        }
        attempt("duration-to-period") {
            (49.hours + 1.nanoseconds).let { DateTimePeriod(hours = 49, nanoseconds = 1) }
        }
        attempt("toString[big]") { DateTimePeriod(years = Int.MAX_VALUE, months = Int.MAX_VALUE) }
    },
    Probe("datetime.tz-gap-overlap") {
        val cases = listOf(
            "Europe/Berlin" to "2024-03-31T02:30",
            "Europe/Berlin" to "2024-10-27T02:30",
            "Europe/Berlin" to "2024-10-27T03:00",
            "America/New_York" to "2024-03-10T02:30",
            "America/New_York" to "2024-11-03T01:30",
            "Australia/Lord_Howe" to "2024-04-07T01:45",
            "Australia/Lord_Howe" to "2024-10-06T02:15",
            "Pacific/Apia" to "2011-12-30T12:00",
            "Pacific/Apia" to "2011-12-31T12:00",
            "Europe/London" to "2024-03-31T01:30",
            "America/Sao_Paulo" to "2018-11-04T00:30",
        )
        for ((zone, local) in cases) {
            val ldt = LocalDateTime.parse(local)
            attempt("toInstant[$zone,$local]") { ldt.toInstant(TimeZone.of(zone)) }
            attempt("roundtrip[$zone,$local]") {
                TimeZone.of(zone).let { ldt.toInstant(it).toLocalDateTime(it) }
            }
        }
    },
    Probe("datetime.tz-offsets") {
        val cases = listOf(
            "Europe/London" to "1970-01-01T12:00:00Z",
            "Europe/London" to "1968-06-01T12:00:00Z",
            "Europe/Amsterdam" to "1930-01-01T12:00:00Z",
            "Europe/Amsterdam" to "1900-01-01T12:00:00Z",
            "Asia/Kolkata" to "1940-01-01T00:00:00Z",
            "America/Sao_Paulo" to "2019-01-01T12:00:00Z",
            "America/Sao_Paulo" to "2020-01-01T12:00:00Z",
            "Europe/Berlin" to "2037-07-01T12:00:00Z",
            "Europe/Berlin" to "2038-07-01T12:00:00Z",
            "Europe/Berlin" to "2040-07-01T12:00:00Z",
            "America/New_York" to "2050-07-01T12:00:00Z",
            "America/New_York" to "1900-01-01T12:00:00Z",
            "America/New_York" to "1883-11-18T12:00:00Z",
            "Africa/Casablanca" to "2025-03-15T12:00:00Z",
            "Africa/Casablanca" to "2026-03-01T12:00:00Z",
            "Africa/Casablanca" to "2030-03-20T12:00:00Z",
            "Africa/Casablanca" to "2036-07-01T12:00:00Z",
            "Africa/Casablanca" to "2040-07-01T12:00:00Z",
            "Pacific/Kiritimati" to "1994-12-31T12:00:00Z",
            "Pacific/Apia" to "2011-12-29T12:00:00Z",
            "Asia/Kathmandu" to "2000-01-01T12:00:00Z",
            "Europe/Moscow" to "2011-06-01T12:00:00Z",
            "Europe/Moscow" to "2014-12-01T12:00:00Z",
            "Europe/Istanbul" to "2016-09-01T12:00:00Z",
        )
        for ((zone, at) in cases) {
            attempt("offset[$zone,$at]") { TimeZone.of(zone).offsetAt(Instant.parse(at)) }
        }
    },
    Probe("datetime.tz-ids") {
        val ids = listOf(
            "UTC", "Z", "z", "+01:00", "+01", "+0100", "UTC+01:00", "UTC+1", "GMT", "GMT+1",
            "Etc/GMT+5", "America/Argentina/Buenos_Aires", "Asia/Kolkata", "Asia/Calcutta",
            "Europe/Kiev", "Europe/Kyiv", "US/Pacific", "Nonexistent/Zone", "EST", "EST5EDT",
            "Europe/Madrid", "europe/berlin", "Europe/Berlin ", "",
        )
        for (id in ids) attempt("of[$id]") { TimeZone.of(id).id }
        for (id in listOf("Europe/Berlin", "Asia/Calcutta", "Europe/Kiev", "US/Pacific", "EST")) {
            attempt("available[$id]") { id in TimeZone.availableZoneIds }
        }
        attempt("available-count-over-300") { TimeZone.availableZoneIds.size > 300 }
        line("utc-id", TimeZone.UTC.id)
        attempt("offset[+18:00]") { UtcOffset.parse("+18:00") }
        attempt("offset[+18:00:01]") { UtcOffset.parse("+18:00:01") }
        attempt("offset[-19:00]") { UtcOffset.parse("-19:00") }
        attempt("offset-ctor[19h]") { UtcOffset(hours = 19) }
    },
    Probe("datetime.arithmetic") {
        val berlin by lazy { TimeZone.of("Europe/Berlin") }
        val utc = TimeZone.UTC
        attempt("jan31+1month") { LocalDate(2024, 1, 31).plus(1, DateTimeUnit.MONTH) }
        attempt("feb29-1year") { LocalDate(2024, 2, 29).plus(-1, DateTimeUnit.YEAR) }
        attempt("feb29+1year") { LocalDate(2024, 2, 29).plus(1, DateTimeUnit.YEAR) }
        attempt("mar31-1month") { LocalDate(2024, 3, 31).plus(-1, DateTimeUnit.MONTH) }
        val base by lazy { LocalDateTime.parse("2024-03-30T02:30").toInstant(berlin) }
        attempt("berlin+1day") { base.plus(1, DateTimeUnit.DAY, berlin).toLocalDateTime(berlin) }
        attempt("berlin+24h") { base.plus(24, DateTimeUnit.HOUR, berlin).toLocalDateTime(berlin) }
        attempt("berlin+period-1d") {
            base.plus(DateTimePeriod(days = 1), berlin).toLocalDateTime(berlin)
        }
        attempt("berlin-until-days") {
            base.until(base.plus(2, DateTimeUnit.DAY, berlin), DateTimeUnit.DAY, berlin)
        }
        attempt("berlin-periodUntil") {
            base.periodUntil(base.plus(2, DateTimeUnit.DAY, berlin), berlin)
        }
        val a = Instant.parse("2024-01-31T00:00:00Z")
        attempt("utc-until-month") {
            a.until(Instant.parse("2024-02-29T00:00:00Z"), DateTimeUnit.MONTH, utc)
        }
        attempt("utc-until-month-neg") {
            Instant.parse("2024-02-29T00:00:00Z").until(a, DateTimeUnit.MONTH, utc)
        }
        attempt("monthsUntil") { LocalDate(2024, 1, 31).monthsUntil(LocalDate(2024, 2, 29)) }
        attempt("monthsUntil-neg") { LocalDate(2024, 3, 31).monthsUntil(LocalDate(2024, 2, 29)) }
        attempt("daysUntil-wide") {
            LocalDate(-999_999, 1, 1).daysUntil(LocalDate(999_999, 12, 31))
        }
        attempt("until-nanos-wide") { minInstant.until(maxInstant, DateTimeUnit.NANOSECOND, utc) }
        attempt("until-seconds-wide") { minInstant.until(maxInstant, DateTimeUnit.SECOND, utc) }
        attempt("plus-year-max") { Instant.DISTANT_FUTURE.plus(1, DateTimeUnit.YEAR, utc) }
        attempt("plus-century-wide") {
            Instant.parse("2000-01-01T00:00:00Z").plus(1_000_000, DateTimeUnit.YEAR, utc)
        }
        attempt("iso-day-number") { DayOfWeek.SUNDAY.ordinal }
    },
    Probe("datetime.duration") {
        val inputs = listOf(
            "PT1.5S", "1h 30m", "P1DT1H", "-PT0S", "PT0.0000000001S", "PT1H30M", "PT-1H30M",
            "-PT1H30M", "+PT1S", "1.5h", "1.5s 2ms", "Infinity", "-Infinity", "PT0.5", "P1Y",
            "P1M", "PT9223372036854775807S", "PT9223372036.854775807S", "1e3s", "1 s",
        )
        for (s in inputs) attempt("parse[$s]") { Duration.parse(s) }
        attempt("iso[large]") { Duration.INFINITE.toIsoString() }
        attempt("iso[neg]") { (-90).seconds.toIsoString() }
        attempt("iso[nanos]") { 1_234_567_891.nanoseconds.toIsoString() }
        attempt("iso[days]") { 100.days.toIsoString() }
        attempt("string[infinite]") { Duration.INFINITE }
        attempt("string[long-max-ns]") { Long.MAX_VALUE.nanoseconds }
        attempt("string[long-min-ns]") { Long.MIN_VALUE.nanoseconds }
        attempt("string[0.00001s]") { 0.00001.seconds }
        attempt("string[1.5ns]") { 1.5.nanoseconds }
        attempt("string[1e-300s]") { 1e-300.seconds }
        attempt("string[1e300s]") { 1e300.seconds }
        attempt("string[123456789ns]") { 123_456_789.nanoseconds }
        attempt("string[ms-decimals]") {
            Duration.parse("PT1.123456789S").toString(DurationUnit.SECONDS, 9)
        }
        attempt("string[decimals-neg]") { 1.seconds.toString(DurationUnit.SECONDS, -1) }
        attempt("string[decimals-13]") { 1.seconds.toString(DurationUnit.SECONDS, 13) }
        attempt("toDouble-days") { 1.seconds.toDouble(DurationUnit.DAYS).toRawBits().toString(16) }
        attempt("inWholeNanoseconds[max-days]") { Long.MAX_VALUE.days.inWholeNanoseconds }
        attempt("inWholeMilliseconds[infinite]") { Duration.INFINITE.inWholeMilliseconds }
        attempt("toLong[infinite-seconds]") { Duration.INFINITE.toLong(DurationUnit.SECONDS) }
        attempt("toInt[huge]") { 100_000_000_000.seconds.toInt(DurationUnit.SECONDS) }
        attempt("nan") { Double.NaN.seconds }
        attempt("div-zero") { 1.seconds / 0 }
        attempt("div-zero-duration") { 1.seconds / Duration.ZERO }
        attempt("times-nan") { 1.seconds * Double.NaN }
        attempt("rounding-0.5ns") { 0.5.nanoseconds.inWholeNanoseconds }
        attempt("components") {
            (1.days + 25.hours + 1.nanoseconds).toComponents { d, h, m, s, n -> "$d $h $m $s $n" }
        }
    },
    Probe("datetime.format") {
        val i = Instant.parse("2024-03-05T06:07:08.009Z")
        attempt("rfc1123") { i.format(DateTimeComponents.Formats.RFC_1123) }
        attempt("iso-offset") { i.format(DateTimeComponents.Formats.ISO_DATE_TIME_OFFSET) }
        attempt("localdatetime-iso") {
            LocalDateTime.Formats.ISO.format(LocalDateTime.parse("2024-03-05T06:07"))
        }
        attempt("localdatetime-iso-seconds") {
            LocalDateTime.Formats.ISO.format(LocalDateTime.parse("2024-03-05T06:07:00"))
        }
        attempt("localdate-format-neg") { LocalDate.Formats.ISO.format(LocalDate(-1, 1, 1)) }
        attempt("localdate-format-big") { LocalDate.Formats.ISO.format(LocalDate(12345, 1, 1)) }
        attempt("localdate-basic") { LocalDate.Formats.ISO_BASIC.format(LocalDate(2024, 3, 5)) }
        attempt("rfc1123-parse") {
            DateTimeComponents.Formats.RFC_1123
                .parse("Tue, 5 Mar 2024 06:07:08 GMT").toInstantUsingOffset()
        }
        attempt("rfc1123-parse-wrong-weekday") {
            DateTimeComponents.Formats.RFC_1123
                .parse("Mon, 5 Mar 2024 06:07:08 GMT").toInstantUsingOffset()
        }
        attempt("rfc1123-parse-obs-zone") {
            DateTimeComponents.Formats.RFC_1123
                .parse("Tue, 5 Mar 2024 06:07:08 EST").toInstantUsingOffset()
        }
        attempt("components-offset") {
            DateTimeComponents.Formats.ISO_DATE_TIME_OFFSET
                .parse("2024-03-05T06:07:08+05:30").toInstantUsingOffset()
        }
        attempt("components-leap") {
            DateTimeComponents.Formats.ISO_DATE_TIME_OFFSET
                .parse("2016-12-31T23:59:60Z").toInstantUsingOffset()
        }
    },
)

private val minInstant get() = Instant.fromEpochSeconds(-31557014167219200L)
private val maxInstant get() = Instant.fromEpochSeconds(31556889864403199L, 999_999_999L)
