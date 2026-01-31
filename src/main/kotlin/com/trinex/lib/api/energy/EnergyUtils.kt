package com.trinex.lib.api.energy

import com.hypixel.hytale.component.CommandBuffer
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.logger.HytaleLogger
import com.hypixel.hytale.math.util.ChunkUtil
import com.hypixel.hytale.math.vector.Vector3i
import com.hypixel.hytale.server.core.universe.world.World
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore
import com.trinex.lib.TrinexLib

object EnergyUtils {
    val logger = HytaleLogger.forEnclosingClass()

    fun getAdjacentEnergyComponents(
        energyComponent: EnergyComponent,
        wc: WorldChunk,
        commandBuffer: CommandBuffer<ChunkStore?>,
    ): Set<EnergyComponent> =
        buildSet {
            val world = wc.world ?: return@buildSet

            for (dir in Vector3i.BLOCK_SIDES) {
                val pos = energyComponent.blockPosition3d?.clone()?.add(dir) ?: continue
                val neighborRef = getBlockComponentEntityAtWorldPos(world, pos.x, pos.y, pos.z) ?: continue
                val neighborEnergy = commandBuffer.getComponent(neighborRef, TrinexLib.get().energyComponentType) ?: continue
                add(neighborEnergy)
            }
        }

    private fun getBlockComponentEntityAtWorldPos(
        world: World,
        x: Int,
        y: Int,
        z: Int,
    ): Ref<ChunkStore?>? {
        // skip unloaded chunks if you don’t want to load them
        val chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(x, z)) ?: return null
        return chunk.getBlockComponentEntity(x, y, z)
    }

    fun getAllConnectedEnergyComponents(
        energyComponent: EnergyComponent,
        wc: WorldChunk,
        commandBuffer: CommandBuffer<ChunkStore?>,
    ): Map<EnergyDeviceClassification, Set<EnergyComponent>> {
        val resultMap = mutableMapOf<EnergyDeviceClassification, MutableSet<EnergyComponent>>()
        val visited = mutableSetOf<EnergyComponent>()
        val queue = mutableSetOf(energyComponent)

        while (queue.isNotEmpty()) {
            val current = queue.first()
            queue.remove(current)
            visited.add(current)

            if (current != energyComponent) {
                resultMap.getOrPut(current.deviceClassification) { mutableSetOf() }.add(current)
            }

            // Only traverse through TRANSPORT components
            val canExpand =
                (current == energyComponent) ||
                    (current.deviceClassification == EnergyDeviceClassification.TRANSPORT)
            if (!canExpand) continue

            val neighbors = getAdjacentEnergyComponents(current, wc, commandBuffer)
            for (neighbor in neighbors) {
                if (!visited.contains(neighbor)) {
                    queue.add(neighbor)
                }
            }
        }

        return resultMap
    }
}
