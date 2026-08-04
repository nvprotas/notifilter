package io.github.nvprotas.notifilter.data

import io.github.nvprotas.notifilter.domain.FilterRule
import io.github.nvprotas.notifilter.domain.MatchTarget
import io.github.nvprotas.notifilter.domain.RuleAction
import io.github.nvprotas.notifilter.domain.RuleMatcher
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class RuleBackup(
    val rules: List<FilterRule>,
)

enum class RuleBackupError {
    MALFORMED_DOCUMENT,
    UNSUPPORTED_FORMAT,
    UNSUPPORTED_VERSION,
    TOO_LARGE,
    TOO_MANY_RULES,
    INVALID_RULE,
}

class RuleBackupException(
    val error: RuleBackupError,
    val ruleNumber: Int? = null,
    val declaredVersion: Int? = null,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

object RuleBackupCodec {
    const val FORMAT = "notifilter-rule-backup"
    const val VERSION = 1
    const val MAX_BACKUP_BYTES = 1_048_576
    const val MAX_RULES = 5_000
    const val MAX_PATTERN_LENGTH = RuleMatcher.MAX_PATTERN_LENGTH
    const val MAX_PACKAGE_NAME_LENGTH = 255

    private val packageNamePattern = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")

    fun encode(rules: List<FilterRule>): ByteArray {
        if (rules.size > MAX_RULES) {
            throw failure(
                RuleBackupError.TOO_MANY_RULES,
                "Rule count exceeds the backup limit",
            )
        }

        val encodedRules = JSONArray()
        rules.forEachIndexed { index, rule ->
            validateRule(rule, index)
            encodedRules.put(
                JSONObject()
                    .put("packageName", rule.packageName ?: JSONObject.NULL)
                    .put("pattern", rule.pattern)
                    .put("target", rule.target.name)
                    .put("action", rule.action.name)
                    .put("ignoreCase", rule.ignoreCase)
                    .put("enabled", rule.enabled),
            )
        }

        val bytes = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("rules", encodedRules)
            .toString(2)
            .toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_BACKUP_BYTES) {
            throw failure(RuleBackupError.TOO_LARGE, "Backup exceeds the byte limit")
        }
        return bytes
    }

    fun decode(bytes: ByteArray): RuleBackup {
        if (bytes.size > MAX_BACKUP_BYTES) {
            throw failure(RuleBackupError.TOO_LARGE, "Backup exceeds the byte limit")
        }

        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: Exception) {
            throw failure(
                RuleBackupError.MALFORMED_DOCUMENT,
                "Backup is not valid UTF-8",
                cause = error,
            )
        }

        try {
            val root = JSONObject(text)
            val format = root.requiredString("format")
            if (format != FORMAT) {
                throw failure(RuleBackupError.UNSUPPORTED_FORMAT, "Unrecognized backup format")
            }

            val version = root.requiredInt("version")
            if (version != VERSION) {
                throw RuleBackupException(
                    error = RuleBackupError.UNSUPPORTED_VERSION,
                    declaredVersion = version,
                    message = "Unsupported backup version: $version",
                )
            }

            val encodedRules = root.requiredArray("rules")
            if (encodedRules.length() > MAX_RULES) {
                throw failure(
                    RuleBackupError.TOO_MANY_RULES,
                    "Rule count exceeds the backup limit",
                )
            }

            val rules = buildList(encodedRules.length()) {
                for (index in 0 until encodedRules.length()) {
                    val encodedRule = encodedRules.opt(index)
                    if (encodedRule !is JSONObject) {
                        throw invalidRule(index, "Rule must be a JSON object")
                    }
                    add(decodeRule(encodedRule, index))
                }
            }
            return RuleBackup(rules)
        } catch (error: RuleBackupException) {
            throw error
        } catch (error: JSONException) {
            throw failure(
                RuleBackupError.MALFORMED_DOCUMENT,
                "Malformed rule backup",
                cause = error,
            )
        }
    }

    fun decode(input: InputStream): RuleBackup {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_BACKUP_BYTES) {
                throw failure(RuleBackupError.TOO_LARGE, "Backup exceeds the byte limit")
            }
            output.write(buffer, 0, read)
        }
        return decode(output.toByteArray())
    }

    private fun decodeRule(source: JSONObject, index: Int): FilterRule {
        val packageName = source.requiredNullableString("packageName", index)
        val pattern = source.requiredString("pattern", index)
        val target = source.requiredEnum<MatchTarget>("target", index)
        val action = source.requiredEnum<RuleAction>("action", index)
        val ignoreCase = source.requiredBoolean("ignoreCase", index)
        val enabled = source.requiredBoolean("enabled", index)
        return FilterRule(
            packageName = packageName,
            pattern = pattern,
            target = target,
            action = action,
            ignoreCase = ignoreCase,
            enabled = enabled,
            createdAt = 0L,
        ).also { validateRule(it, index) }
    }

    private fun validateRule(rule: FilterRule, index: Int) {
        if (rule.pattern.isBlank()) {
            throw invalidRule(index, "Pattern must not be blank")
        }
        if (rule.pattern.length > MAX_PATTERN_LENGTH) {
            throw invalidRule(index, "Pattern exceeds the length limit")
        }
        if (RuleMatcher.validationError(rule.pattern) != null) {
            throw invalidRule(index, "Pattern is not a valid supported regular expression")
        }
        rule.packageName?.let { packageName ->
            if (
                packageName.isBlank() ||
                packageName.length > MAX_PACKAGE_NAME_LENGTH ||
                !packageNamePattern.matches(packageName)
            ) {
                throw invalidRule(index, "Application package name is invalid")
            }
        }
    }

    private fun JSONObject.requiredString(name: String, ruleIndex: Int? = null): String {
        if (!has(name) || isNull(name)) missingOrInvalid(name, ruleIndex)
        return opt(name) as? String ?: missingOrInvalid(name, ruleIndex)
    }

    private fun JSONObject.requiredNullableString(name: String, ruleIndex: Int): String? {
        if (!has(name)) missingOrInvalid(name, ruleIndex)
        if (isNull(name)) return null
        return opt(name) as? String ?: missingOrInvalid(name, ruleIndex)
    }

    private fun JSONObject.requiredBoolean(name: String, ruleIndex: Int): Boolean {
        if (!has(name) || isNull(name)) missingOrInvalid(name, ruleIndex)
        return opt(name) as? Boolean ?: missingOrInvalid(name, ruleIndex)
    }

    private fun JSONObject.requiredInt(name: String): Int {
        if (!has(name) || isNull(name)) missingOrInvalid(name, null)
        val value = opt(name)
        val asLong = when (value) {
            is Int -> value.toLong()
            is Long -> value
            else -> missingOrInvalid(name, null)
        }
        if (asLong !in Int.MIN_VALUE..Int.MAX_VALUE) missingOrInvalid(name, null)
        return asLong.toInt()
    }

    private fun JSONObject.requiredArray(name: String): JSONArray {
        if (!has(name) || isNull(name)) missingOrInvalid(name, null)
        return opt(name) as? JSONArray ?: missingOrInvalid(name, null)
    }

    private inline fun <reified T : Enum<T>> JSONObject.requiredEnum(
        name: String,
        ruleIndex: Int,
    ): T {
        val value = requiredString(name, ruleIndex)
        return enumValues<T>().firstOrNull { it.name == value }
            ?: throw invalidRule(ruleIndex, "Unsupported $name value")
    }

    private fun missingOrInvalid(name: String, ruleIndex: Int?): Nothing =
        if (ruleIndex == null) {
            throw failure(
                RuleBackupError.MALFORMED_DOCUMENT,
                "Missing or invalid $name property",
            )
        } else {
            throw invalidRule(ruleIndex, "Missing or invalid $name property")
        }

    private fun invalidRule(index: Int, message: String) = RuleBackupException(
        error = RuleBackupError.INVALID_RULE,
        ruleNumber = index + 1,
        message = "Rule ${index + 1}: $message",
    )

    private fun failure(
        error: RuleBackupError,
        message: String,
        cause: Throwable? = null,
    ) = RuleBackupException(error = error, message = message, cause = cause)
}
