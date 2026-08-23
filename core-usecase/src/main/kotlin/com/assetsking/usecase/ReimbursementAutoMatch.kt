package com.assetsking.usecase

data class ReimbursementMatchCandidate(
    val id: String,
    val remainingCents: Long
)

private sealed interface MatchState {
    data class Unique(val ids: List<String>) : MatchState
    data object Ambiguous : MatchState
}

/**
 * 只在到账金额对应唯一一组未报销余额时返回关联项；无解、歧义或候选过多均交给用户手选。
 */
fun uniqueExactReimbursementMatch(
    candidates: List<ReimbursementMatchCandidate>,
    arrivalCents: Long
): List<String>? {
    if (arrivalCents <= 0L) return null
    val eligible = candidates.filter { it.remainingCents in 1..arrivalCents }
    if (eligible.isEmpty() || eligible.size > 40) return null

    val states = mutableMapOf<Long, MatchState>(0L to MatchState.Unique(emptyList()))
    eligible.forEach { candidate ->
        val snapshot = states.toList()
        snapshot.forEach { (sum, state) ->
            val next = sum + candidate.remainingCents
            if (next > arrivalCents) return@forEach
            val proposed = when (state) {
                is MatchState.Unique -> MatchState.Unique(state.ids + candidate.id)
                MatchState.Ambiguous -> MatchState.Ambiguous
            }
            states[next] = mergeMatchStates(states[next], proposed)
        }
        if (states.size > 50_000) return null
    }

    return (states[arrivalCents] as? MatchState.Unique)?.ids?.takeIf { it.isNotEmpty() }
}

private fun mergeMatchStates(existing: MatchState?, proposed: MatchState): MatchState = when {
    existing == null -> proposed
    existing is MatchState.Unique && proposed is MatchState.Unique && existing.ids == proposed.ids -> existing
    else -> MatchState.Ambiguous
}
