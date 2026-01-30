package com.trinex.lib.api.device

import com.hypixel.hytale.logger.HytaleLogger
import com.trinex.lib.api.energy.EnergyDeviceType

object EnergyDeviceTypeRegistry {
    private val logger = HytaleLogger.forEnclosingClass()
    private val devices = mutableMapOf<String, EnergyDeviceType>()

    fun register(
        group: String,
        type: String,
        device: EnergyDeviceType,
    ) {
        logger.atInfo().log("Registering device $group:$type")
        devices["$group:$type"] = device
    }

    fun get(type: String): EnergyDeviceType? = devices[type]
}
