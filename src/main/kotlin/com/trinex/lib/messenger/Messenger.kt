package com.trinex.lib.messenger

import com.hypixel.hytale.server.core.Message
import com.hypixel.hytale.server.core.command.system.CommandContext

class Messenger(val pluginName: String) {
    fun sendMessage(message: String, ctx: CommandContext) = ctx.sendMessage(Message.raw("[${pluginName}] $message"))
}