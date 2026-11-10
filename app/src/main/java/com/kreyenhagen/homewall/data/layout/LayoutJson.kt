package com.kreyenhagen.homewall.data.layout

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Reads and writes `wall_layout.json`. The file is meant to be edited by hand, so it's written
 * with one hold per line, and unknown keys are an error rather than being silently dropped on
 * the next save.
 */
object LayoutJson {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    sealed interface ParseResult {
        data class Ok(val layout: WallLayout) : ParseResult
        data class Failed(val problems: List<String>) : ParseResult
    }

    /** Parses and validates (§6.1). Every problem found is listed. */
    fun parse(text: String): ParseResult {
        val layout = try {
            json.decodeFromString<WallLayout>(text)
        } catch (e: SerializationException) {
            return ParseResult.Failed(listOf("Not a valid layout file: ${e.message}"))
        } catch (e: IllegalArgumentException) {
            return ParseResult.Failed(listOf("Not a valid layout file: ${e.message}"))
        }
        val problems = LayoutValidator.validate(layout)
        return if (problems.isEmpty()) ParseResult.Ok(layout) else ParseResult.Failed(problems)
    }

    fun encode(layout: WallLayout): String {
        val root = json.encodeToJsonElement(layout) as JsonObject
        return buildString {
            append("{\n")
            root.entries.forEachIndexed { i, (key, value) ->
                append("  \"").append(key).append("\": ")
                appendValue(value)
                if (i < root.size - 1) append(',')
                append('\n')
            }
            append("}\n")
        }
    }

    /** Arrays of objects get one element per line; everything else stays on one line. */
    private fun StringBuilder.appendValue(value: JsonElement) {
        if (value is JsonArray && value.isNotEmpty() && value.all { it is JsonObject }) {
            append("[\n")
            value.forEachIndexed { i, element ->
                append("    ").append(element.toString())
                if (i < value.size - 1) append(',')
                append('\n')
            }
            append("  ]")
        } else {
            append(value.toString())
        }
    }
}
