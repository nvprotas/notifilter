package io.github.nvprotas.notifilter.domain

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException

/**
 * Immutable and thread-safe matcher. Build a new instance whenever rules
 * change, then reuse it for all incoming notifications.
 *
 * Matching ALLOW rules take precedence over BLOCK rules. This makes it
 * possible to add a broad advertising filter and a narrow useful exception.
 */
class RuleMatcher private constructor(
    private val rules: List<CompiledRule>,
) {
    fun evaluate(notification: NotificationContent): FilterDecision {
        val candidates = rules.asSequence()
            .filter { rule ->
                rule.source.enabled &&
                    (rule.source.packageName == null || rule.source.packageName == notification.packageName)
            }
            .filter { rule ->
                val searchableText = notification
                    .textFor(rule.source.target)
                    .take(MAX_NOTIFICATION_TEXT_LENGTH)
                rule.pattern.matcher(searchableText).find()
            }
            .toList()

        val allowRule = candidates.firstOrNull { it.source.action == RuleAction.ALLOW }
        if (allowRule != null) {
            return FilterDecision(
                shouldBlock = false,
                matchedRuleId = allowRule.source.id,
                matchedRulePattern = allowRule.source.pattern,
            )
        }

        val blockRule = candidates.firstOrNull { it.source.action == RuleAction.BLOCK }
        return if (blockRule == null) {
            FilterDecision.ALLOW
        } else {
            FilterDecision(
                shouldBlock = true,
                matchedRuleId = blockRule.source.id,
                matchedRulePattern = blockRule.source.pattern,
            )
        }
    }

    companion object {
        const val MAX_PATTERN_LENGTH = 512
        const val MAX_NOTIFICATION_TEXT_LENGTH = 8_192

        val EMPTY = RuleMatcher(emptyList())

        fun compile(rules: List<FilterRule>): RuleMatcher {
            val compiled = rules.mapNotNull { rule ->
                compilePattern(rule)?.let { pattern -> CompiledRule(rule, pattern) }
            }
            return RuleMatcher(compiled)
        }

        fun validationError(pattern: String): String? {
            if (pattern.isBlank()) return "Введите регулярное выражение"
            if (pattern.length > MAX_PATTERN_LENGTH) {
                return "Выражение не должно быть длиннее $MAX_PATTERN_LENGTH символов"
            }
            return try {
                Pattern.compile(pattern)
                null
            } catch (error: PatternSyntaxException) {
                error.description.ifBlank { "Некорректное регулярное выражение" }
            }
        }

        private fun compilePattern(rule: FilterRule): Pattern? {
            if (validationError(rule.pattern) != null) return null
            val flags = if (rule.ignoreCase) {
                Pattern.CASE_INSENSITIVE
            } else {
                0
            }
            return try {
                Pattern.compile(rule.pattern, flags)
            } catch (_: PatternSyntaxException) {
                null
            }
        }
    }

    private data class CompiledRule(
        val source: FilterRule,
        val pattern: Pattern,
    )
}
