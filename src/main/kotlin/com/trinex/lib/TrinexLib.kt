package com.trinex.lib

import com.hypixel.hytale.logger.HytaleLogger
import com.hypixel.hytale.server.core.plugin.JavaPlugin
import com.hypixel.hytale.server.core.plugin.JavaPluginInit
import com.trinex.lib.messenger.Messenger

class TrinexLib(init: JavaPluginInit) : JavaPlugin(init) {

    val messenger = Messenger("TrinexLib")
    private val logger = HytaleLogger.forEnclosingClass()

    init {
        logger.atInfo().log("Hello from " + this.name + " version " + this.manifest.version)
    }

    override fun setup() {
        logger.atInfo().log("Setting up plugin " + this.name)
    }

    companion object {
    }
}