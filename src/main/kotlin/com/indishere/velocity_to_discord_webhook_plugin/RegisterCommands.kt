/* Command Registration @ Velocity to Discord Webhooks Plugin */

package com.indishere.velocity_to_discord_webhook_plugin

import org.slf4j.Logger
import com.velocitypowered.api.command.SimpleCommand


/**
 * Registers Velocity commands and wires execution to parser/runtime.
 * NO parsing logic, NO permissions, NO execution logic.
 */


class RegisterCommands(
    private val logger: Logger,
    private val getConfig: () -> PluginConfig,
    private val runtime: CommandRuntime
) : SimpleCommand {

    private val parser = CommandParser()
    private val permissions = PermissionsManager()
    override fun execute(invocation: SimpleCommand.Invocation) {
        val source = invocation.source()
        val args = invocation.arguments()

        val cfg = getConfig()
        val verbose = cfg.loggingMode == LoggingMode.DEBUG || cfg.loggingMode == LoggingMode.FINE

        if (verbose) {
            logger.debug("/vdiscord invoked by={} args={}", sourceDebugName(source), args.joinToString(" "))
        }

        // Parse command
        val parsed = parser.parse(args)

        // Check permissions
        val permCheck = permissions.checkPermission(source, parsed)
        if (!permCheck.allowed) {
            source.sendMessage(net.kyori.adventure.text.Component.text(permCheck.reason!!))
            return
        }

        // Execute
        runtime.execute(source, parsed)
    }

    override fun suggest(invocation: SimpleCommand.Invocation): List<String> {
        return runtime.suggest(invocation.source(), invocation.arguments())
    }

    private fun sourceDebugName(source: com.velocitypowered.api.command.CommandSource): String {
        return (source as? com.velocitypowered.api.proxy.Player)?.username ?: source.toString()
    }
}
