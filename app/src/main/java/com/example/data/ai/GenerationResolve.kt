package com.example.data.ai

/**
 * Decides what the Add Word screen shows after a generation attempt.
 *
 * Pure by construction: the AI [Result] and the offline lookup go in, the outcome comes out,
 * with no network, database or coroutine in the way. The view model only executes the
 * decision, so every combination - success, failure with a sample, failure without one - is
 * unit-tested without mocking HTTP.
 *
 * The order is the fix for the "老师" bug: the offline dictionary is the *fallback*, consulted
 * only after the online attempt has actually failed. A missing sample must never prevent an
 * online attempt that was never made.
 */
sealed interface GenerationOutcome {
    data class Ready(val data: GeneratedWordData) : GenerationOutcome
    data class Failed(val message: String) : GenerationOutcome
}

fun resolveGeneration(
    query: String,
    aiResult: Result<GeneratedWordData>,
    sampleFor: (String) -> GeneratedWordData?,
): GenerationOutcome {
    aiResult.fold(
        onSuccess = { return GenerationOutcome.Ready(it) },
        onFailure = { error ->
            sampleFor(query)?.let { return GenerationOutcome.Ready(it) }
            val reason = error.message ?: "Failed to generate word data"
            return GenerationOutcome.Failed(
                "$reason No offline sample for \"$query\" either - " +
                    "check your connection and try again, or add it by hand."
            )
        },
    )
}
