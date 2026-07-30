package io.github.nvprotas.notifilter.domain

/**
 * A local rule applied to notifications from [packageName]. A null package
 * means that the rule applies to every application.
 */
data class FilterRule(
    val id: Long = 0,
    val packageName: String? = null,
    val pattern: String,
    val target: MatchTarget = MatchTarget.ALL_TEXT,
    val action: RuleAction = RuleAction.BLOCK,
    val ignoreCase: Boolean = true,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)

enum class MatchTarget {
    TITLE,
    BODY,
    ALL_TEXT,
}

enum class RuleAction {
    ALLOW,
    BLOCK,
}

data class NotificationContent(
    val packageName: String,
    val title: String,
    val body: String,
) {
    fun textFor(target: MatchTarget): String = when (target) {
        MatchTarget.TITLE -> title
        MatchTarget.BODY -> body
        MatchTarget.ALL_TEXT -> buildString {
            append(title)
            if (title.isNotEmpty() && body.isNotEmpty()) append('\n')
            append(body)
        }
    }
}

data class FilterDecision(
    val shouldBlock: Boolean,
    val matchedRuleId: Long? = null,
    val matchedRulePattern: String? = null,
) {
    companion object {
        val ALLOW = FilterDecision(shouldBlock = false)
    }
}
