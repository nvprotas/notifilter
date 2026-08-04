package io.github.nvprotas.notifilter.data

import io.github.nvprotas.notifilter.domain.FilterRule
import io.github.nvprotas.notifilter.domain.MatchTarget
import io.github.nvprotas.notifilter.domain.RuleAction
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RuleBackupCodecTest {
    @Test
    fun `round trip preserves functional fields and display order`() {
        val rules = listOf(
            FilterRule(
                id = 42,
                packageName = "com.example.shop",
                pattern = "sale|discount",
                target = MatchTarget.TITLE,
                action = RuleAction.BLOCK,
                ignoreCase = false,
                enabled = true,
                createdAt = 900,
            ),
            FilterRule(
                id = 7,
                packageName = null,
                pattern = "important",
                target = MatchTarget.BODY,
                action = RuleAction.ALLOW,
                ignoreCase = true,
                enabled = false,
                createdAt = 100,
            ),
        )

        val decoded = RuleBackupCodec.decode(RuleBackupCodec.encode(rules)).rules

        assertEquals(rules.map(::functionalFields), decoded.map(::functionalFields))
        assertTrue(decoded.all { it.id == 0L && it.createdAt == 0L })
    }

    @Test
    fun `empty rule set produces a valid backup`() {
        val decoded = RuleBackupCodec.decode(RuleBackupCodec.encode(emptyList()))

        assertTrue(decoded.rules.isEmpty())
    }

    @Test
    fun `malformed and unrelated documents are rejected`() {
        assertError(RuleBackupError.MALFORMED_DOCUMENT, "{".toByteArray())
        assertError(
            RuleBackupError.UNSUPPORTED_FORMAT,
            document(format = "other-app").toByteArray(),
        )
    }

    @Test
    fun `unsupported version reports declared version`() {
        val error = assertError(
            RuleBackupError.UNSUPPORTED_VERSION,
            document(version = 99).toByteArray(),
        )

        assertEquals(99, error.declaredVersion)
    }

    @Test
    fun `invalid rule rejects the complete backup`() {
        val invalid = JSONObject()
            .put("packageName", "com.example")
            .put("pattern", "[")
            .put("target", "TITLE")
            .put("action", "BLOCK")
            .put("ignoreCase", true)
            .put("enabled", true)
        val error = assertError(
            RuleBackupError.INVALID_RULE,
            document(rules = JSONArray().put(validRule()).put(invalid)).toByteArray(),
        )

        assertEquals(2, error.ruleNumber)
    }

    @Test
    fun `wrong property types and enum values are rejected`() {
        val wrongBoolean = validRule().put("enabled", "true")
        assertError(
            RuleBackupError.INVALID_RULE,
            document(rules = JSONArray().put(wrongBoolean)).toByteArray(),
        )

        val wrongTarget = validRule().put("target", "EVERYWHERE")
        assertError(
            RuleBackupError.INVALID_RULE,
            document(rules = JSONArray().put(wrongTarget)).toByteArray(),
        )
    }

    @Test
    fun `byte and rule count safety limits are enforced`() {
        assertError(
            RuleBackupError.TOO_LARGE,
            ByteArray(RuleBackupCodec.MAX_BACKUP_BYTES + 1),
        )

        val rules = JSONArray()
        repeat(RuleBackupCodec.MAX_RULES + 1) { rules.put(validRule()) }
        assertError(
            RuleBackupError.TOO_MANY_RULES,
            document(rules = rules).toByteArray(),
        )
    }

    @Test
    fun `invalid UTF-8 is rejected`() {
        assertError(
            RuleBackupError.MALFORMED_DOCUMENT,
            byteArrayOf(0xC3.toByte(), 0x28),
        )
    }

    private fun assertError(error: RuleBackupError, bytes: ByteArray): RuleBackupException {
        val thrown = runCatching { RuleBackupCodec.decode(bytes) }.exceptionOrNull()
        assertTrue(thrown is RuleBackupException)
        return (thrown as RuleBackupException).also { assertEquals(error, it.error) }
    }

    private fun document(
        format: String = RuleBackupCodec.FORMAT,
        version: Int = RuleBackupCodec.VERSION,
        rules: JSONArray = JSONArray(),
    ): String = JSONObject()
        .put("format", format)
        .put("version", version)
        .put("rules", rules)
        .toString()

    private fun validRule(): JSONObject = JSONObject()
        .put("packageName", JSONObject.NULL)
        .put("pattern", "sale")
        .put("target", "ALL_TEXT")
        .put("action", "BLOCK")
        .put("ignoreCase", true)
        .put("enabled", true)

    private fun functionalFields(rule: FilterRule) = listOf(
        rule.packageName,
        rule.pattern,
        rule.target,
        rule.action,
        rule.ignoreCase,
        rule.enabled,
    )
}
