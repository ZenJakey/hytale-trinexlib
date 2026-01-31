package com.trinex.lib

import com.hypixel.hytale.component.ComponentType
import com.hypixel.hytale.logger.HytaleLogger
import com.hypixel.hytale.server.core.plugin.JavaPlugin
import com.hypixel.hytale.server.core.plugin.JavaPluginInit
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore
import com.trinex.lib.api.energy.EnergyComponent
import com.trinex.lib.api.energy.EnergyInitializer
import com.trinex.lib.api.energy.EnergySystem
import com.trinex.lib.api.energy.device.DefaultDeviceType
import com.trinex.lib.api.energy.device.EnergyDeviceTypeRegistry
import com.trinex.lib.messenger.Messenger

class TrinexLib(
    init: JavaPluginInit,
) : JavaPlugin(init) {
    private val logger = HytaleLogger.forEnclosingClass()
    val config = this.withConfig(Config.CODEC)
    val messenger = Messenger("TrinexLib")
    lateinit var energyComponentType: ComponentType<ChunkStore?, EnergyComponent>

    init {
        instance = this
    }

    override fun setup() {
        logger.atInfo().log("Setting up plugin " + this.name)
        energyComponentType =
            this.chunkStoreRegistry.registerComponent(EnergyComponent::class.java, "EnergyComponent", EnergyComponent.CODEC)
        this.chunkStoreRegistry.registerSystem(EnergySystem())
        this.chunkStoreRegistry.registerSystem(EnergyInitializer())
        EnergyDeviceTypeRegistry.register("Default", "Default", DefaultDeviceType())
    }

    companion object {
        var instance: TrinexLib? = null

        fun get(): TrinexLib = instance ?: throw IllegalStateException("TrinexLib not initialized")
    }
}
