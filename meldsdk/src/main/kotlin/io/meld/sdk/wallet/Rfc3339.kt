package io.meld.sdk.wallet

/**
 * Epoch milliseconds for an RFC 3339 date-time with a `Z` or `±hh:mm` offset and up to nine fraction
 * digits, or null. Hand-rolled because minSdk 24 has no `java.time`, and desugaring would be forced on
 * every app that installs the SDK. Digits past milliseconds are dropped.
 */
internal fun parseRfc3339(value: String): Long? {
    val parts = RFC_3339.matchEntire(value)?.groupValues ?: return null
    val year = parts[1].toInt()
    val month = parts[2].toInt()
    val day = parts[3].toInt()
    val hour = parts[4].toInt()
    val minute = parts[5].toInt()
    val second = parts[6].toInt()
    if (month !in 1..12 || day !in 1..daysInMonth(year, month) || hour > 23 || minute > 59 || second > 59) return null
    val millis = parts[7].padEnd(3, '0').take(3).toInt()
    val offsetSeconds = if (parts[8] == "Z") {
        0
    } else {
        val offsetHour = parts[10].toInt()
        val offsetMinute = parts[11].toInt()
        if (offsetHour > 23 || offsetMinute > 59) return null
        (if (parts[9] == "-") -1 else 1) * (offsetHour * 3_600 + offsetMinute * 60)
    }
    val seconds = daysFromCivil(year, month, day) * 86_400 + hour * 3_600 + minute * 60 + second - offsetSeconds
    return seconds * 1_000 + millis
}

private val RFC_3339 = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|([+-])(\d{2}):(\d{2}))""")

private fun daysInMonth(year: Int, month: Int): Int = when (month) {
    2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

/** Days since 1970-01-01 in the proleptic Gregorian calendar (Howard Hinnant's days_from_civil). */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yearOfEra = y - era * 400
    val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146_097L + dayOfEra - 719_468
}
