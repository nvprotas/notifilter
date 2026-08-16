package io.github.nvprotas.notifilter.domain

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException

data class HistoryExclusion(
    val id: Long = 0,
    val packageName: String? = null,
    val pattern: String? = null,
    val target: MatchTarget = MatchTarget.ALL_TEXT,
    val ignoreCase: Boolean = true,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)

object HistoryExclusionValidator {
    private val packageNamePattern = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")

    fun normalize(rule: HistoryExclusion): HistoryExclusion = rule.copy(
        packageName = rule.packageName?.trim()?.takeIf(String::isNotEmpty),
        pattern = rule.pattern?.trim()?.takeIf(String::isNotEmpty),
    )

    fun validationError(rule: HistoryExclusion): String? {
        val normalized = normalize(rule)
        if (normalized.packageName == null && normalized.pattern == null) {
            return "Выберите приложение или введите регулярное выражение"
        }
        normalized.packageName?.let { packageName ->
            if (packageName.length > MAX_PACKAGE_NAME_LENGTH || !packageNamePattern.matches(packageName)) {
                return "Некорректное имя пакета приложения"
            }
        }
        normalized.pattern?.let { pattern ->
            return RuleMatcher.validationError(pattern)
        }
        return null
    }

    private const val MAX_PACKAGE_NAME_LENGTH = 255
}

class HistoryExclusionMatcher private constructor(
    private val rules: List<CompiledExclusion>,
) {
    fun matches(notification: NotificationContent): Boolean = matchingRule(notification) != null

    fun matchingRule(notification: NotificationContent): HistoryExclusion? = rules.firstOrNull { rule ->
        (rule.source.packageName == null || rule.source.packageName == notification.packageName) &&
            (rule.pattern == null || rule.pattern.matcher(
                notification.textFor(rule.source.target).take(RuleMatcher.MAX_NOTIFICATION_TEXT_LENGTH),
            ).find())
    }?.source

    companion object {
        val EMPTY = HistoryExclusionMatcher(emptyList())

        fun compile(rules: List<HistoryExclusion>): Result<HistoryExclusionMatcher> = runCatching {
            HistoryExclusionMatcher(
                rules.asSequence()
                    .filter(HistoryExclusion::enabled)
                    .map(HistoryExclusionValidator::normalize)
                    .map { rule ->
                        val validationError = HistoryExclusionValidator.validationError(rule)
                        require(validationError == null) { validationError.orEmpty() }
                        CompiledExclusion(
                            source = rule,
                            pattern = rule.pattern?.let { pattern -> compilePattern(pattern, rule.ignoreCase) },
                        )
                    }
                    .toList(),
            )
        }

        private fun compilePattern(pattern: String, ignoreCase: Boolean): Pattern {
            val flags = if (ignoreCase) Pattern.CASE_INSENSITIVE else 0
            try {
                return Pattern.compile(pattern, flags)
            } catch (error: PatternSyntaxException) {
                throw IllegalArgumentException(error.description, error)
            }
        }
    }

    private data class CompiledExclusion(
        val source: HistoryExclusion,
        val pattern: Pattern?,
    )
}
