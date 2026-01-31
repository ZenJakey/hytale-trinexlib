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
import kotlin.math.max

class EnergySystem : EntityTickingSystem<ChunkStore?>() {
    private val logger = HytaleLogger.forEnclosingClass()

    private data class DistributionState(
        val remainingEnergy: Long,
        val pathOffset: Int,
        val endpointOffset: Int,
    )

    private fun distributeAlongPaths(
        groups: List<EnergyUtils.PathGroup>,
        remainingEnergy: Long,
        pathOffset: Int,
        endpointOffset: Int,
        groupCapacities: LongArray,
        endpointRemaining: MutableMap<EnergyComponent, Long>,
        endpointsSelector: (EnergyUtils.PathGroup) -> List<EnergyUtils.PathEndpoint>,
    ): DistributionState {
        if (remainingEnergy <= 0 || groups.isEmpty()) {
            return DistributionState(remainingEnergy, pathOffset, endpointOffset)
        }

        val groupCount = groups.size
        var remaining = remainingEnergy
        var nextPathOffset = pathOffset % groupCount
        var nextEndpointOffset = endpointOffset

        for (i in 0 until groupCount) {
            val groupIndex = (nextPathOffset + i) % groupCount
            val group = groups[groupIndex]
            val endpoints = endpointsSelector(group)
            if (endpoints.isEmpty()) continue

            val remainingBeforeGroup = remaining
            var groupRemaining = min(remaining, groupCapacities[groupIndex])
            if (groupRemaining <= 0) continue

            val count = endpoints.size
            val endpointCaps = LongArray(count) { idx ->
                val component = endpoints[idx].component
                min(endpoints[idx].maxTransfer, endpointRemaining[component] ?: 0L)
            }
            var idx = if (nextEndpointOffset >= count) nextEndpointOffset % count else nextEndpointOffset
            var sentAny = false

            while (groupRemaining > 0) {
                var sentInCycle = false
                var visited = 0
                while (groupRemaining > 0 && visited < count) {
                    val available = endpointCaps[idx]
                    if (available > 0) {
                        val toTry = min(groupRemaining, available)
                        val remainder = endpoints[idx].component.addEnergy(toTry)
                        val sent = toTry - remainder
                        if (sent > 0) {
                            endpointCaps[idx] = available - sent
                            val component = endpoints[idx].component
                            endpointRemaining[component] = (endpointRemaining[component] ?: 0L) - sent
                            groupRemaining -= sent
                            remaining -= sent
                            sentAny = true
                            sentInCycle = true
                        }
                    }
                    idx = (idx + 1) % count
                    visited++
                }
                if (!sentInCycle) break
            }

            if (sentAny) {
                val used = remainingBeforeGroup - remaining
                groupCapacities[groupIndex] = groupCapacities[groupIndex] - used
                nextEndpointOffset = idx
                nextPathOffset = (groupIndex + 1) % groupCount
            }
            if (remaining <= 0) break
        }

        return DistributionState(remaining, nextPathOffset, nextEndpointOffset)
    }

    private fun buildEndpointRemaining(
        groups: List<EnergyUtils.PathGroup>,
        wc: WorldChunk,
        commandBuffer: CommandBuffer<ChunkStore?>,
        endpointsSelector: (EnergyUtils.PathGroup) -> List<EnergyUtils.PathEndpoint>,
    ): MutableMap<EnergyComponent, Long> {
        val remaining = mutableMapOf<EnergyComponent, Long>()
        for (group in groups) {
            for (endpoint in endpointsSelector(group)) {
                if (remaining.containsKey(endpoint.component)) continue
                val capacity = EnergyUtils.getReceiveCapacity(endpoint.component, wc, commandBuffer)
                remaining[endpoint.component] = capacity
            }
        }
        return remaining
    }

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
                val pathGroups = EnergyUtils.getPathGroups(energyComponent, wc, commandBuffer)
                val groupCapacities = LongArray(pathGroups.size) { idx -> pathGroups[idx].capacity }
                val maxOutput = pathGroups.sumOf { it.capacity }
                val actualOutput = min(energyComponent.energy, maxOutput)
                var remainingEnergy = actualOutput
                val consumerRemaining = buildEndpointRemaining(pathGroups, wc, commandBuffer) { it.consumers }

                val consumerState =
                    distributeAlongPaths(
                        pathGroups,
                        remainingEnergy,
                        energyComponent.consumerPathOffset,
                        energyComponent.consumerRoundRobinOffset,
                        groupCapacities,
                        consumerRemaining,
                    ) { it.consumers }
                remainingEnergy = consumerState.remainingEnergy
                energyComponent.consumerPathOffset = consumerState.pathOffset
                energyComponent.consumerRoundRobinOffset = consumerState.endpointOffset

                if (remainingEnergy > 0) {
                    val storageRemaining = buildEndpointRemaining(pathGroups, wc, commandBuffer) { it.storages }
                    val storageState =
                        distributeAlongPaths(
                            pathGroups,
                            remainingEnergy,
                            energyComponent.storagePathOffset,
                            energyComponent.storageRoundRobinOffset,
                            groupCapacities,
                            storageRemaining,
                        ) { it.storages }
                    remainingEnergy = storageState.remainingEnergy
                    energyComponent.storagePathOffset = storageState.pathOffset
                    energyComponent.storageRoundRobinOffset = storageState.endpointOffset
                }

                energyComponent.removeEnergy(actualOutput - remainingEnergy)
            }

            EnergyDeviceClassification.STORAGE -> {
                val network = EnergyUtils.getNetwork(energyComponent, wc, commandBuffer)
                val consumerDemand =
                    network.byClassification[EnergyDeviceClassification.CONSUMER]?.sumOf {
                        max(0, it.energyCapacity - it.energy)
                    } ?: 0
                val providerSupply =
                    network.byClassification[EnergyDeviceClassification.PROVIDER]?.sumOf { provider ->
                        val providerMaxOutput = EnergyUtils.getProviderMaxOutput(provider, wc, commandBuffer)
                        min(provider.energy, providerMaxOutput)
                    } ?: 0

                if (consumerDemand > providerSupply) {
                    val pathGroups = EnergyUtils.getPathGroups(energyComponent, wc, commandBuffer)
                    val groupCapacities = LongArray(pathGroups.size) { idx -> pathGroups[idx].capacity }
                    val maxOutput = pathGroups.sumOf { it.capacity }
                    val actualOutput = min(energyComponent.energy, maxOutput)
                    var remainingEnergy = actualOutput
                    val consumerRemaining = buildEndpointRemaining(pathGroups, wc, commandBuffer) { it.consumers }

                    val consumerState =
                        distributeAlongPaths(
                            pathGroups,
                            remainingEnergy,
                            energyComponent.consumerPathOffset,
                            energyComponent.consumerRoundRobinOffset,
                            groupCapacities,
                            consumerRemaining,
                        ) { it.consumers }
                    remainingEnergy = consumerState.remainingEnergy
                    energyComponent.consumerPathOffset = consumerState.pathOffset
                    energyComponent.consumerRoundRobinOffset = consumerState.endpointOffset

                    energyComponent.removeEnergy(actualOutput - remainingEnergy)
                }
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
            energyComponent.energyDeltaLastTick = energyComponent.energy - energyComponent.previousEnergy
            energyComponent.previousEnergy = energyComponent.energy
            return
        }
        device.onTick(energyComponent, dt, index, archetypeChunk, store, commandBuffer)

        energyComponent.energyDeltaLastTick = energyComponent.energy - energyComponent.previousEnergy
        energyComponent.previousEnergy = energyComponent.energy
    }

    override fun getQuery(): Query<ChunkStore?> = Query.and(TrinexLib.get().energyComponentType)
}
