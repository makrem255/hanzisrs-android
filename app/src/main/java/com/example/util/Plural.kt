package com.example.util

/**
 * Picks the noun that goes with [count].
 *
 * ## Why this is its own file
 *
 * It existed once, as `private fun plural` in `DashboardAggregator`, which is the only file
 * allowed to call it. That is why the word-detail sheet ended up hand-rolling `"day(s)"` and
 * `"review(s)"`, and why the settings screen says `"$dueCount words due"` and tells a learner
 * with one word due that they have "1 words". Three sites, three spellings, no shared rule.
 *
 * `NotificationHelper` had already fixed the identical defect in its own string, in isolation,
 * with a comment explaining that a learner with exactly one card due - the most likely moment
 * to be reminded at all - was told they had "1 words". Two files fixing one bug is how the
 * third site got written.
 *
 * ## Why not "day(s)"
 *
 * It is not a shorter way of saying the same thing. `(s)` is a rendering of the author's
 * uncertainty rather than of the number, so it reads "1 day(s)" to a learner who has one word
 * due, in a sentence whose entire purpose is to be a statement of fact.
 */
fun plural(count: Int, one: String, many: String): String = if (count == 1) one else many