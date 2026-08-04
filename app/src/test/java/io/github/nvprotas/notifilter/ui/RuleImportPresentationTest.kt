package io.github.nvprotas.notifilter.ui

import io.github.nvprotas.notifilter.data.RuleBackupError
import io.github.nvprotas.notifilter.data.RuleBackupException
import io.github.nvprotas.notifilter.domain.FilterRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleImportPresentationTest {
    @Test
    fun `preview counts current imported and duplicate rules`() {
        val preview = buildRuleImportPreview(
            currentRules = listOf(rule("existing")),
            importedRules = listOf(
                rule("existing"),
                rule("new"),
                rule("new"),
            ),
        )

        assertEquals(
            RuleImportPreview(
                importedRuleCount = 3,
                currentRuleCount = 1,
                duplicateCount = 2,
            ),
            preview,
        )
    }

    @Test
    fun `replacement copy names the exact destructive consequence`() {
        val message = ruleImportReplacementMessage(currentRuleCount = 17)

        assertEquals(
            "Замена удалит 17 текущих правил и восстановит правила из файла.",
            message,
        )
        assertTrue(ruleImportDuplicateMessage(4).contains("4"))
    }

    @Test
    fun `validation errors give a specific recovery message`() {
        val unsupported = RuleBackupException(
            error = RuleBackupError.UNSUPPORTED_VERSION,
            declaredVersion = 9,
            message = "unsupported",
        )
        val invalidRule = RuleBackupException(
            error = RuleBackupError.INVALID_RULE,
            ruleNumber = 3,
            message = "invalid",
        )

        assertEquals(
            "Версия резервной копии 9 не поддерживается.",
            ruleImportErrorMessage(unsupported),
        )
        assertEquals(
            "Правило 3 в резервной копии содержит недопустимые данные.",
            ruleImportErrorMessage(invalidRule),
        )
    }

    private fun rule(pattern: String) = FilterRule(
        packageName = "com.example",
        pattern = pattern,
    )
}
