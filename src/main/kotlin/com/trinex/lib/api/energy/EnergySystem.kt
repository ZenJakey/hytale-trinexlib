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
import com.trinex.lib.api.energy.device.EnergyDeviceTypeRegistry
import kotlin.math.min

class EnergySystem : EntityTickingSystem<ChunkStore?>() {
    private val logger = HytaleLogger.forEnclosingClass()

    private data class ProviderContext(
        val provider: EnergyComponent,
        val pathGroups: List<EnergyUtils.PathGroup>,
        val groupCapacities: LongArray,
        var remainingEnergy: Long,
    )

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
        val startPathOffset = pathOffset % groupCount
        var nextPathOffset = startPathOffset
        var nextEndpointOffset = endpointOffset

        for (i in 0 until groupCount) {
            val groupIndex = (startPathOffset + i) % groupCount
            val group = groups[groupIndex]
            val endpoints = endpointsSelector(group)
            if (endpoints.isEmpty()) continue

            val remainingBeforeGroup = remaining
            var groupRemaining = min(remaining, groupCapacities[groupIndex])
            if (groupRemaining <= 0) continue

            val count = endpoints.size
            val endpointCaps =
                LongArray(count) { idx ->
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
                        val endpoint = endpoints[idx].component
                        val remainder = endpoint.addEnergy(toTry)
                        val sent = toTry - remainder
                        if (sent > 0) {
                            endpointCaps[idx] = available - sent
                            endpointRemaining[endpoint] = (endpointRemaining[endpoint] ?: 0L) - sent
                            endpoint.energyDeltaLastTick += sent
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
        endpoints: Collection<EnergyComponent>,
        wc: WorldChunk,
        commandBuffer: CommandBuffer<ChunkStore?>,
    ): MutableMap<EnergyComponent, Long> {
        val remaining = mutableMapOf<EnergyComponent, Long>()
        for (endpoint in endpoints) {
            val capacity = EnergyUtils.getReceiveCapacity(endpoint, wc, commandBuffer)
            remaining[endpoint] = capacity
        }
        return remaining
    }

    private fun resetNetworkDelta(
        component: EnergyComponent,
        worldTick: Long,
    ) {
        if (component.lastEnergyDeltaTick != worldTick) {
            component.energyDeltaLastTick = 0
            component.lastEnergyDeltaTick = worldTick
        }
    }

    private fun processNetwork(
        network: EnergyUtils.EnergyNetwork,
        wc: WorldChunk,
        commandBuffer: CommandBuffer<ChunkStore?>,
    ) {
        val world = wc.world ?: return
        val worldTick = world.tick
        for (components in network.byClassification.values) {
            for (component in components) {
                resetNetworkDelta(component, worldTick)
            }
        }

        val providers = network.byClassification[EnergyDeviceClassification.PROVIDER].orEmpty()
        val consumers = network.byClassification[EnergyDeviceClassification.CONSUMER].orEmpty()
        val storages = network.byClassification[EnergyDeviceClassification.STORAGE].orEmpty()
        if (providers.isEmpty() && storages.isEmpty()) return

        val consumerRemaining = buildEndpointRemaining(consumers, wc, commandBuffer)
        val storageRemaining = buildEndpointRemaining(storages, wc, commandBuffer)

        val providerContexts =
            providers.map { provider ->
                val pathGroups = EnergyUtils.getPathGroups(provider, wc, commandBuffer)
                val groupCapacities = LongArray(pathGroups.size) { idx -> pathGroups[idx].capacity }
                val maxOutput = pathGroups.sumOf { it.capacity }
                val remainingEnergy = min(provider.energy, maxOutput)
                ProviderContext(provider, pathGroups, groupCapacities, remainingEnergy)
            }

        for (context in providerContexts) {
            if (context.remainingEnergy <= 0) continue
            val initialOutput = context.remainingEnergy
            val consumerState =
                distributeAlongPaths(
                    context.pathGroups,
                    context.remainingEnergy,
                    context.provider.consumerPathOffset,
                    context.provider.consumerRoundRobinOffset,
                    context.groupCapacities,
                    consumerRemaining,
                ) { it.consumers }
            context.remainingEnergy = consumerState.remainingEnergy
            context.provider.consumerPathOffset = consumerState.pathOffset
            context.provider.consumerRoundRobinOffset = consumerState.endpointOffset

            val sent = initialOutput - context.remainingEnergy
            if (sent > 0) {
                context.provider.removeEnergy(sent)
                context.provider.energyDeltaLastTick -= sent
            }
        }

        val consumerRemainingTotal = consumerRemaining.values.sum()
        if (consumerRemainingTotal > 0) {
            for (storage in storages) {
                val pathGroups = EnergyUtils.getPathGroups(storage, wc, commandBuffer)
                if (pathGroups.isEmpty()) continue
                val groupCapacities = LongArray(pathGroups.size) { idx -> pathGroups[idx].capacity }
                val maxOutput = pathGroups.sumOf { it.capacity }
                val remainingEnergy = min(storage.energy, maxOutput)
                if (remainingEnergy <= 0) continue

                val consumerState =
                    distributeAlongPaths(
                        pathGroups,
                        remainingEnergy,
                        storage.consumerPathOffset,
                        storage.consumerRoundRobinOffset,
                        groupCapacities,
                        consumerRemaining,
                    ) { it.consumers }
                val sent = remainingEnergy - consumerState.remainingEnergy
                if (sent > 0) {
                    storage.removeEnergy(sent)
                    storage.energyDeltaLastTick -= sent
                }
                storage.consumerPathOffset = consumerState.pathOffset
                storage.consumerRoundRobinOffset = consumerState.endpointOffset
                if (consumerRemaining.values.sum() <= 0) {
                    break
                }
            }
        } else {
            for (context in providerContexts) {
                if (context.remainingEnergy <= 0) continue
                val storageState =
                    distributeAlongPaths(
                        context.pathGroups,
                        context.remainingEnergy,
                        context.provider.storagePathOffset,
                        context.provider.storageRoundRobinOffset,
                        context.groupCapacities,
                        storageRemaining,
                    ) { it.storages }
                val sent = context.remainingEnergy - storageState.remainingEnergy
                if (sent > 0) {
                    context.provider.removeEnergy(sent)
                    context.provider.energyDeltaLastTick -= sent
                }
                context.remainingEnergy = storageState.remainingEnergy
                context.provider.storagePathOffset = storageState.pathOffset
                context.provider.storageRoundRobinOffset = storageState.endpointOffset
                if (storageRemaining.values.sum() <= 0) {
                    break
                }
            }
        }
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
        val world = wc.world
        if (world != null) {
            resetNetworkDelta(energyComponent, world.tick)
        }
        val network = EnergyUtils.getNetwork(energyComponent, wc, commandBuffer)
        if (world != null && EnergyUtils.markNetworkProcessed(world, network)) {
            processNetwork(network, wc, commandBuffer)
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
