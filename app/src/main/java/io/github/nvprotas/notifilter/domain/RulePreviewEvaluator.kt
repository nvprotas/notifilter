package io.github.nvprotas.notifilter.domain

sealed interface RulePreviewResult {
    data object Computing : RulePreviewResult

    data object Unavailable : RulePreviewResult

    data class Invalid(
        val validationError: String,
    ) : RulePreviewResult

    data class Available(
        val entries: List<RulePreviewEntry>,
    ) : RulePreviewResult {
        val activeCount: Int = entries.size
        val directMatchCount: Int = entries.count(RulePreviewEntry::directMatch)
        val removalCount: Int = entries.count(RulePreviewEntry::willBeRemoved)
    }
}

data class RulePreviewEntry(
    val sample: ActiveNotificationSample,
    val directMatch: Boolean,
    val baselineDecision: FilterDecision,
    val proposedDecision: FilterDecision,
    val willBeRemoved: Boolean,
) {
    val decisionChanged: Boolean =
        baselineDecision.shouldBlock != proposedDecision.shouldBlock
}

object RulePreviewEvaluator {
    fun evaluate(
        savedRules: List<FilterRule>,
        existingRule: FilterRule?,
        draft: FilterRule,
        filteringEnabled: Boolean,
        activeState: ActiveNotificationsState,
    ): RulePreviewResult {
        val validationError = RuleMatcher.validationError(draft.pattern)
        if (validationError != null) return RulePreviewResult.Invalid(validationError)
        if (activeState !is ActiveNotificationsState.Available) {
            return RulePreviewResult.Unavailable
        }

        val effectiveDraft = draft.copy(
            id = existingRule?.id ?: PREVIEW_RULE_ID,
            packageName = draft.packageName?.trim()?.takeIf(String::isNotEmpty),
        )
        val proposedRules = savedRules
            .filterNot { existingRule != null && it.id == existingRule.id }
            .plus(effectiveDraft)
        val baselineMatcher = RuleMatcher.compile(savedRules)
        val proposedMatcher = RuleMatcher.compile(proposedRules)
        val directMatcher = RuleMatcher.compile(
            listOf(effectiveDraft.copy(id = DIRECT_MATCH_RULE_ID, enabled = true)),
        )

        val entries = activeState.notifications.map { sample ->
            val directDecision = directMatcher.evaluate(sample.content)
            val baselineDecision = baselineMatcher.evaluate(sample.content)
            val proposedDecision = proposedMatcher.evaluate(sample.content)
            RulePreviewEntry(
                sample = sample,
                directMatch = directDecision.matchedRuleId == DIRECT_MATCH_RULE_ID,
                baselineDecision = baselineDecision,
                proposedDecision = proposedDecision,
                willBeRemoved = filteringEnabled &&
                    sample.eligibleForFiltering &&
                    proposedDecision.shouldBlock,
            )
        }.sortedWith(
            compareByDescending<RulePreviewEntry>(RulePreviewEntry::willBeRemoved)
                .thenByDescending(RulePreviewEntry::directMatch)
                .thenByDescending { it.sample.postedAt },
        )

        return RulePreviewResult.Available(entries)
    }

    private const val PREVIEW_RULE_ID = Long.MIN_VALUE + 1L
    private const val DIRECT_MATCH_RULE_ID = Long.MIN_VALUE
}
