package com.trinex.lib.api.energy

import com.hypixel.hytale.component.ArchetypeChunk
import com.hypixel.hytale.component.CommandBuffer
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.component.query.Query
import com.hypixel.hytale.component.system.tick.EntityTickingSystem
import com.hypixel.hytale.logger.HytaleLogger
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore
import com.trinex.lib.TrinexLib
import com.trinex.lib.api.device.EnergyDeviceTypeRegistry

class EnergySystem : EntityTickingSystem<ChunkStore?>() {
    private val logger = HytaleLogger.forEnclosingClass()

    override fun tick(
        dt: Float,
        index: Int,
        archetypeChunk: ArchetypeChunk<ChunkStore?>,
        store: Store<ChunkStore?>,
        commandBuffer: CommandBuffer<ChunkStore?>,
    ) {
        val energyComponent = archetypeChunk.getComponent(index, TrinexLib.get().energyComponentType) ?: return

        val deviceType = energyComponent.deviceType
        val device = EnergyDeviceTypeRegistry.get(deviceType)
        if (device == null) {
            logger.atSevere().log("No device registered with DeviceType: $deviceType")
            return
        }
        device.onTick(energyComponent, dt, index, archetypeChunk, store, commandBuffer)
    }

    override fun getQuery(): Query<ChunkStore?> = Query.and(TrinexLib.get().energyComponentType)
}
