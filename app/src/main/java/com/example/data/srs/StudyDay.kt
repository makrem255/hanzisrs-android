package com.example.data.srs

import java.time.Instant
import java.time.ZoneId

/**
 * The one definition of "which study day is this instant in".
 *
 * A streak, a daily total and a "due today" queue are all statements about *days*, and they are
 * only comparable if they agree on where a day ends. So the answer lives here, once, and both
 * sides of the read/write boundary call it.
 *
 * ### Why this file exists
 *
 * There were two implementations, and they disagreed. The write path bucketed a review with
 * `floorDiv(millis, 86_400_000)` — UTC — while the read path converted through the learner's
 * `ZoneId`. The two are the same function only in UTC, so for every learner outside it, a
 * review answered between local midnight and 00:00 UTC was written into one day's bucket and
 * read back out of another. The learner studied, and the dashboard showed nothing.
 *
 * That was not caught by the tests either. `DashboardRepositoryTest` pinned its zone to
 * `ZoneOffset.UTC`, and under UTC the two implementations are byte-for-byte the same, so the
 * suite was green precisely when the bug was invisible. The KDoc above `toEpochDay` went
 * further and stated as fact that "the two must agree" — which a future maintainer would take
 * as a checked invariant rather than re-derive.
 *
 * ### The zone
 *
 * A study day is a *local* day, so the zone is a real dependency and not a detail. It is taken
 * as a parameter everywhere rather than read from `ZoneId.systemDefault()` at the call site, so
 * that a test can state its zone and a device that moves timezones can be told. The device zone
 * is the honest default for now: `UserProfileEntity.timezoneId` exists and is written, but no
 * settings write path reads it back yet, so preferring the device zone is the only claim the
 * code can actually support.
 */
object StudyDay {

    /** Days since 1970-01-01 for [millis], as seen from [zone]. */
    fun epochDayOf(millis: Long, zone: ZoneId): Int =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay().toInt()

    /**
     * The first instant of [epochDay] in [zone], in epoch millis.
     *
     * Resolved through the zone rather than by multiplying by 86,400,000, because a local day is
     * not always 24 hours: a daylight-saving transition makes it 23 or 25.
     */
    fun startOfEpochDayMillis(epochDay: Int, zone: ZoneId): Long =
        java.time.LocalDate.ofEpochDay(epochDay.toLong())
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

    /**
     * The instant [epochDay] ends, in [zone] — that is, the start of the next one.
     *
     * Exclusive rather than inclusive: a query for "everything up to the end of today" wants
     * `<= this`, and a half-open range is the only form that stays correct across a DST change.
     */
    fun endOfEpochDayMillis(epochDay: Int, zone: ZoneId): Long =
        startOfEpochDayMillis(epochDay + 1, zone)
}
