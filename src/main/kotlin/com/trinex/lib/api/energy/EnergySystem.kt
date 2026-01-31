package com.trinex.lib.api.energy

import com.hypixel.hytale.component.ArchetypeChunk
import com.hypixel.hytale.component.CommandBuffer
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.component.query.Query
import com.hypixel.hytale.component.system.tick.EntityTickingSystem
import com.hypixel.hytale.logger.HytaleLogger
import com.hypixel.hytale.server.core.modules.block.BlockModule
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore
import com.trinex.lib.TrinexLib
import com.trinex.lib.api.device.EnergyDeviceTypeRegistry
import kotlin.math.min

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
        val stateInfo = archetypeChunk.getComponent(index, BlockModule.BlockStateInfo.getComponentType()) ?: return
        val wc = commandBuffer.getComponent(stateInfo.chunkRef, WorldChunk.getComponentType()) ?: return

        when (energyComponent.deviceClassification) {
            EnergyDeviceClassification.PROVIDER -> {
                val maxOutput =
                    EnergyUtils.getAdjacentEnergyComponents(energyComponent, wc, commandBuffer).sumOf {
                        min(energyComponent.transferSpeed, it.transferSpeed)
                    }
                val actualOutput = min(energyComponent.energy, maxOutput)
                val allNeighbors = EnergyUtils.getAllConnectedEnergyComponents(energyComponent, wc, commandBuffer)
                // logger.atInfo().log("")
                // EnergyDeviceClassification.entries.forEach { logger.atInfo().log("${it.name}: ${allNeighbors[it]?.size ?: 0}") }
                // logger.atInfo().log("")
                var remainingEnergy = actualOutput

                // Try to fill consumers first
                allNeighbors[EnergyDeviceClassification.CONSUMER]?.forEach { consumer ->
                    if (remainingEnergy > 0) {
                        remainingEnergy = consumer.addEnergy(remainingEnergy)
                    }
                }

                // Then try to fill storage
                allNeighbors[EnergyDeviceClassification.STORAGE]?.forEach { storage ->
                    if (remainingEnergy > 0) {
                        remainingEnergy = storage.addEnergy(remainingEnergy)
                    }
                }

                // Remove the distributed energy from provider
                energyComponent.removeEnergy(actualOutput - remainingEnergy)
            }

            EnergyDeviceClassification.STORAGE -> {
                val maxOutput =
                    EnergyUtils.getAdjacentEnergyComponents(energyComponent, wc, commandBuffer).sumOf {
                        min(energyComponent.transferSpeed, it.transferSpeed)
                    }
                val actualOutput = min(energyComponent.energy, maxOutput)
                val allNeighbors = EnergyUtils.getAllConnectedEnergyComponents(energyComponent, wc, commandBuffer)

                var remainingEnergy = actualOutput

                // Try to fill consumers first
                allNeighbors[EnergyDeviceClassification.CONSUMER]?.forEach { consumer ->
                    if (remainingEnergy > 0) {
                        remainingEnergy = consumer.addEnergy(remainingEnergy)
                    }
                }

                energyComponent.removeEnergy(actualOutput - remainingEnergy)
            }

            EnergyDeviceClassification.TRANSPORT,
            EnergyDeviceClassification.CONSUMER,
            EnergyDeviceClassification.NONE,
            -> {}
        }

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
