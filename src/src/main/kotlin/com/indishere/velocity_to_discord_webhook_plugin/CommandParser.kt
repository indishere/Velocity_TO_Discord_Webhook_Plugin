/* Command Parser @ Velocity to Discord Webhooks Plugin */

package com.indishere.velocity_to_discord_webhook_plugin


import java.util.Locale


/**
 * Responsible for parsing raw command input and producing typed command objects.
 * NO filesystem access, NO permissions, NO config reads, NO Discord calls.
 */


class CommandParser {

    sealed interface ParsedCommand {
        object Info : ParsedCommand
        object Help : ParsedCommand
        object Reload : ParsedCommand
        object Stats : ParsedCommand
        data class Send(
            val from: String?,
            val to: String?,
            val messageToken: String
        ) : ParsedCommand
    }

    fun parse(args: Array<String>): ParsedCommand {
        if (args.isEmpty()) {
            return ParsedCommand.Info
        }

        return when (args[0].lowercase(Locale.ROOT)) {
            "help" -> ParsedCommand.Help
            "reload" -> ParsedCommand.Reload
            "stats" -> ParsedCommand.Stats
            "send", "message", "msg" -> parseSend(args.drop(1).toTypedArray())
            else -> throw IllegalArgumentException("Unknown subcommand: ${args[0]}")
        }
    }

    private fun parseSend(args: Array<String>): ParsedCommand.Send {
        if (args.isEmpty()) {
            throw IllegalArgumentException("Usage: /vdiscord send [from <name>] [to <webhook>] <message>")
        }

        var index = 0
        var fromName: String? = null
        var toName: String? = null

        // Parse optional flags
        while (index < args.size) {
            when (args[index].lowercase(Locale.ROOT)) {
                "from" -> {
                    if (index + 1 >= args.size) {
                        throw IllegalArgumentException("Missing value after 'from'.")
                    }
                    val (value, nextIndex) = parseQuotedOrSingle(args, index + 1)
                    fromName = value
                    index = nextIndex
                }
                "to" -> {
                    if (index + 1 >= args.size) {
                        throw IllegalArgumentException("Missing value after 'to'.")
                    }
                    val (value, nextIndex) = parseQuotedOrSingle(args, index + 1)
                    toName = value
                    index = nextIndex
                }
                else -> break
            }
        }

        // Remaining args are the message token
        val messageToken = parseMessageToken(args.drop(index).toTypedArray())
        if (messageToken.isBlank()) {
            throw IllegalArgumentException("Message token is required.")
        }

        return ParsedCommand.Send(
            from = fromName,
            to = toName,
            messageToken = messageToken
        )
    }

    /**
     * Parse a single argument, handling quoted strings with simple escaping (\\\" or \\\').
     * Returns the parsed value and the index to continue parsing from.
     */
    private fun parseQuotedOrSingle(args: Array<String>, startIndex: Int): Pair<String, Int> {
        if (startIndex >= args.size) {
            throw IllegalArgumentException("Expected argument at index $startIndex")
        }

        val first = args[startIndex]
        val quoteChar = when {
            first.startsWith('"') -> '"'
            first.startsWith('\'') -> '\''
            else -> return first to (startIndex + 1) // Not quoted, return as-is
        }

        val builder = StringBuilder()
        var tokenIndex = startIndex
        var token = first
        var offset = 1 // Skip the opening quote in the first token

        var iterations = 0
        while (true) {
            if (++iterations > 1000) {
                throw IllegalArgumentException("Quote parsing exceeded safety limit.")
            }
            while (offset < token.length) {
                val ch = token[offset]
                if (ch == '\\' && offset + 1 < token.length) {
                    val next = token[offset + 1]
                    if (next == quoteChar || next == '\\') {
                        builder.append(next)
                        offset += 2
                        continue
                    }
                }
                if (ch == quoteChar) {
                    return builder.toString() to (tokenIndex + 1)
                }
                builder.append(ch)
                offset++
            }

            tokenIndex++
            if (tokenIndex >= args.size) {
                throw IllegalArgumentException("Unclosed quote in argument starting at position $startIndex")
            }
            builder.append(' ')
            token = args[tokenIndex]
            offset = 0
        }
    }

    /**
     * Parse the message token, which can be:
     * - A quoted string (raw message)
     * - An unquoted token (alias or filename)
     */
    private fun parseMessageToken(args: Array<String>): String {
        if (args.isEmpty()) {
            return ""
        }

        val first = args[0]

        // Check if it's a quoted string (raw message)
        if (first.startsWith('"') || first.startsWith('\'')) {
            val (value, nextIndex) = parseQuotedOrSingle(args, 0)
            if (nextIndex != args.size) {
                throw IllegalArgumentException("Unexpected arguments after quoted message.")
            }
            if (value.isBlank()) {
                throw IllegalArgumentException("Message content cannot be empty.")
            }
            return "RAW:$value"
        }

        // Not quoted - could be alias or filename
        // If it contains spaces without quotes, that's an error
        if (args.size > 1) {
            throw IllegalArgumentException("Message name contains spaces but is not quoted. Use quotes: \"${args.joinToString(" ")}\"")
        }

        return args[0]
    }
}
