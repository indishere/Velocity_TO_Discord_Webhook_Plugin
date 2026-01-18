/* Permissions Manager @ Velocity to Discord Webhooks Plugin */

package com.indishere.velocity_to_discord_webhook_plugin

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.command.CommandSource


/**
 * Centralized permission checks.
 * NO parsing, NO execution, NO filesystem access.
 */


class PermissionsManager {

    data class PermissionResult(
        val allowed: Boolean,
        val reason: String? = null
    ) {
        companion object {
            fun allow() = PermissionResult(true)
            fun deny(reason: String) = PermissionResult(false, reason)
        }
    }

    fun checkPermission(source: CommandSource, command: CommandParser.ParsedCommand): PermissionResult {
        val isConsole = source !is Player

        // Console has all permissions
        if (isConsole) {
            return PermissionResult.allow()
        }

        // Base permission for all commands
        if (!source.hasPermission("vdiscord.use")) {
            return PermissionResult.deny("You do not have permission to use this plugin. (vdiscord.use)")
        }

        return when (command) {
            is CommandParser.ParsedCommand.Info -> PermissionResult.allow()
            is CommandParser.ParsedCommand.Help -> PermissionResult.allow()

            is CommandParser.ParsedCommand.Reload -> {
                if (!source.hasPermission("vdiscord.reload")) {
                    PermissionResult.deny("You do not have permission to reload. (vdiscord.reload)")
                } else {
                    PermissionResult.allow()
                }
            }

            is CommandParser.ParsedCommand.Stats -> {
                if (!source.hasPermission("vdiscord.stats")) {
                    PermissionResult.deny("You do not have permission to view stats. (vdiscord.stats)")
                } else {
                    PermissionResult.allow()
                }
            }

            is CommandParser.ParsedCommand.Send -> {
                if (!source.hasPermission("vdiscord.send")) {
                    return PermissionResult.deny("Missing permission: vdiscord.send")
                }

                // Check 'from' permission
                if (command.from != null && !source.hasPermission("vdiscord.send.from")) {
                    return PermissionResult.deny("Missing permission: vdiscord.send.from")
                }

                // Check 'to' permission
                if (command.to != null && !source.hasPermission("vdiscord.send.to")) {
                    return PermissionResult.deny("Missing permission: vdiscord.send.to")
                }

                // Message-specific permissions are checked in runtime after resolving the file
                PermissionResult.allow()
            }
        }
    }

    /**
     * Check permission for a specific message file.
     * Called by runtime after file resolution.
     */
    fun checkMessagePermission(source: CommandSource, fileBase: String): PermissionResult {
        val isConsole = source !is Player
        if (isConsole) {
            return PermissionResult.allow()
        }

        val perm = "vdiscord.send.message.$fileBase"
        return if (source.hasPermission(perm)) {
            PermissionResult.allow()
        } else {
            PermissionResult.deny("Missing permission: $perm")
        }
    }

    /**
     * Check permission for raw message sending.
     */
    fun checkRawMessagePermission(source: CommandSource): PermissionResult {
        val isConsole = source !is Player
        if (isConsole) {
            return PermissionResult.allow()
        }

        return if (source.hasPermission("vdiscord.send.message.raw")) {
            PermissionResult.allow()
        } else {
            PermissionResult.deny("Missing permission: vdiscord.send.message.raw")
        }
    }
}
