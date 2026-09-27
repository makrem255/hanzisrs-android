package com.example.ui.screens

/**
 * The library's filter chips.
 *
 * An enum rather than the `"ALL"` / `"DUE"` / `"LEARNING"` / `"MASTERED"` string literals it
 * replaced. A filter is compared with `==` and returned from a `when` on a value that came
 * from a `when` on the same strings, so a typo in one place was a branch that silently
 * matched nothing: the chip would render, the filter would appear to do nothing, and there
 * was nothing to catch it.
 */
internal enum class Filter(val label: String) {
    ALL("All words"),
    DUE("Due now"),
    LEARNING("In progress"),
    MASTERED("Mastered")
}
