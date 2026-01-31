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

    private data class NetworkCache(
        var tick: Long,
        val byComponent: MutableMap<EnergyComponent, EnergyNetwork>,
    )

    data class EnergyNetwork(
        val byClassification: Map<EnergyDeviceClassification, Set<EnergyComponent>>,
    )

    data class PathEndpoint(
        val component: EnergyComponent,
        val maxTransfer: Long,
    )

    data class PathGroup(
        val capacity: Long,
        val consumers: List<PathEndpoint>,
        val storages: List<PathEndpoint>,
    )

    private data class PathCache(
        var tick: Long,
        val bySource: MutableMap<EnergyComponent, List<PathGroup>>,
    )

    private data class ProviderOutputCache(
        var tick: Long,
        val byProvider: MutableMap<EnergyComponent, Long>,
    )

    private data class ReceiveCapacityCache(
        var tick: Long,
        val byComponent: MutableMap<EnergyComponent, Long>,
    )

    private data class NetworkProcessCache(
        var tick: Long,
        val processed: MutableSet<EnergyNetwork>,
    )

    private val networkCacheByWorld = java.util.WeakHashMap<World, NetworkCache>()
    private val pathCacheByWorld = java.util.WeakHashMap<World, PathCache>()
    private val providerOutputCacheByWorld = java.util.WeakHashMap<World, ProviderOutputCache>()
    private val receiveCapacityCacheByWorld = java.util.WeakHashMap<World, ReceiveCapacityCache>()
    private val processedNetworksByWorld = java.util.WeakHashMap<World, NetworkProcessCache>()

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
        // skip unloaded chunks if you don't want to load them
        val chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(x, z)) ?: return null
        return chunk.getBlockComponentEntity(x, y, z)
    }

    fun getProviderMaxOutput(
        energyComponent: EnergyComponent,
        wc: WorldChunk,
        commandBuffer: CommandBuffer<ChunkStore?>,
    ): Long {
        val world = wc.world ?: return 0
        val worldTick = world.tick
        val cache = providerOutputCacheByWorld.getOrPut(world) { ProviderOutputCache(-1L, mutableMapOf()) }
        if (cache.tick != worldTick) {
            cache.tick = worldTick
            cache.byProvider.clear()
        }

        cache.byProvider[energyComponent]?.let { return it }

        val maxOutput =
            getAdjacentEnergyComponents(energyComponent, wc, commandBuffer).sumOf {
                minOf(energyComponent.transferSpeed, it.transferSpeed)
            }
        cache.byProvider[energyComponent] = maxOutput
        return maxOutput
    }

    fun getReceiveCapacity(
        energyComponent: EnergyComponent,
        wc: WorldChunk,
        commandBuffer: CommandBuffer<ChunkStore?>,
    ): Long {
        val world = wc.world ?: return 0
        val worldTick = world.tick
        val cache = receiveCapacityCacheByWorld.getOrPut(world) { ReceiveCapacityCache(-1L, mutableMapOf()) }
        if (cache.tick != worldTick) {
            cache.tick = worldTick
            cache.byComponent.clear()
        }

        cache.byComponent[energyComponent]?.let { return it }

        val neighbors = getAdjacentEnergyComponents(energyComponent, wc, commandBuffer)
        val capacity =
            neighbors.sumOf { neighbor ->
                when (neighbor.deviceClassification) {
                    EnergyDeviceClassification.TRANSPORT,
                    EnergyDeviceClassification.PROVIDER,
                    EnergyDeviceClassification.STORAGE,
                    -> minOf(energyComponent.transferSpeed, neighbor.transferSpeed)
                    else -> 0
                }
            }
        cache.byComponent[energyComponent] = capacity
        return capacity
    }

    fun getNetwork(
        energyComponent: EnergyComponent,
        wc: WorldChunk,
        commandBuffer: CommandBuffer<ChunkStore?>,
    ): EnergyNetwork {
        val world = wc.world ?: return EnergyNetwork(emptyMap())
        val worldTick = world.tick
        val cache = networkCacheByWorld.getOrPut(world) { NetworkCache(-1L, mutableMapOf()) }
        if (cache.tick != worldTick) {
            cache.tick = worldTick
            cache.byComponent.clear()
        }

        cache.byComponent[energyComponent]?.let { return it }

        val visited = mutableSetOf<EnergyComponent>()
        val queue = ArrayDeque<EnergyComponent>()
        queue.add(energyComponent)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue

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

        val resultMap = mutableMapOf<EnergyDeviceClassification, MutableSet<EnergyComponent>>()
        for (component in visited) {
            resultMap.getOrPut(component.deviceClassification) { mutableSetOf() }.add(component)
        }

        val network = EnergyNetwork(resultMap)
        for (component in visited) {
            cache.byComponent[component] = network
        }

        return network
    }

    fun markNetworkProcessed(
        world: World,
        network: EnergyNetwork,
    ): Boolean {
        val worldTick = world.tick
        val cache = processedNetworksByWorld.getOrPut(world) { NetworkProcessCache(-1L, mutableSetOf()) }
        if (cache.tick != worldTick) {
            cache.tick = worldTick
            cache.processed.clear()
        }

        if (cache.processed.contains(network)) {
            return false
        }
        cache.processed.add(network)
        return true
    }

    fun getPathGroups(
        energyComponent: EnergyComponent,
        wc: WorldChunk,
        commandBuffer: CommandBuffer<ChunkStore?>,
    ): List<PathGroup> {
        val world = wc.world ?: return emptyList()
        val worldTick = world.tick
        val cache = pathCacheByWorld.getOrPut(world) { PathCache(-1L, mutableMapOf()) }
        if (cache.tick != worldTick) {
            cache.tick = worldTick
            cache.bySource.clear()
        }

        cache.bySource[energyComponent]?.let { return it }

        val groups = mutableListOf<PathGroup>()
        val neighbors = getAdjacentEnergyComponents(energyComponent, wc, commandBuffer)
        for (neighbor in neighbors) {
            val capacity = minOf(energyComponent.transferSpeed, neighbor.transferSpeed)
            if (capacity <= 0) continue
            val group =
                if (neighbor.deviceClassification != EnergyDeviceClassification.TRANSPORT) {
                    buildDirectPathGroup(neighbor, capacity)
                } else {
                    buildTransportPathGroup(energyComponent, neighbor, capacity, wc, commandBuffer)
                }
            if (group != null && (group.consumers.isNotEmpty() || group.storages.isNotEmpty())) {
                groups.add(group)
            }
        }

        cache.bySource[energyComponent] = groups
        return groups
    }

    fun getAllConnectedEnergyComponents(
        energyComponent: EnergyComponent,
        wc: WorldChunk,
        commandBuffer: CommandBuffer<ChunkStore?>,
    ): Map<EnergyDeviceClassification, Set<EnergyComponent>> {
        val network = getNetwork(energyComponent, wc, commandBuffer)
        val resultMap = mutableMapOf<EnergyDeviceClassification, MutableSet<EnergyComponent>>()
        for ((classification, members) in network.byClassification) {
            for (member in members) {
                if (member != energyComponent) {
                    resultMap.getOrPut(classification) { mutableSetOf() }.add(member)
                }
            }
        }

        return resultMap
    }

    private fun buildDirectPathGroup(
        neighbor: EnergyComponent,
        capacity: Long,
    ): PathGroup? {
        val consumers = mutableListOf<PathEndpoint>()
        val storages = mutableListOf<PathEndpoint>()
        when (neighbor.deviceClassification) {
            EnergyDeviceClassification.CONSUMER -> consumers.add(PathEndpoint(neighbor, capacity))
            EnergyDeviceClassification.STORAGE -> storages.add(PathEndpoint(neighbor, capacity))
            else -> return null
        }
        return PathGroup(capacity, consumers, storages)
    }

    private fun buildTransportPathGroup(
        source: EnergyComponent,
        start: EnergyComponent,
        capacity: Long,
        wc: WorldChunk,
        commandBuffer: CommandBuffer<ChunkStore?>,
    ): PathGroup? {
        val endpointLimits = mutableMapOf<EnergyComponent, Long>()
        val visitedTransports = mutableSetOf<EnergyComponent>()
        val queue = ArrayDeque<Pair<EnergyComponent, Long>>()
        queue.add(start to capacity)
        visitedTransports.add(start)

        while (queue.isNotEmpty()) {
            val (current, currentLimit) = queue.removeFirst()
            val neighbors = getAdjacentEnergyComponents(current, wc, commandBuffer)
            for (neighbor in neighbors) {
                if (neighbor == source) continue
                val nextLimit = minOf(currentLimit, neighbor.transferSpeed)
                if (neighbor.deviceClassification == EnergyDeviceClassification.TRANSPORT) {
                    if (visitedTransports.add(neighbor)) {
                        queue.add(neighbor to nextLimit)
                    }
                } else {
                    val existing = endpointLimits[neighbor]
                    if (existing == null || nextLimit > existing) {
                        endpointLimits[neighbor] = nextLimit
                    }
                }
            }
        }

        if (endpointLimits.isEmpty()) return null

        val consumers = mutableListOf<PathEndpoint>()
        val storages = mutableListOf<PathEndpoint>()
        for ((endpoint, limit) in endpointLimits) {
            if (limit <= 0) continue
            when (endpoint.deviceClassification) {
                EnergyDeviceClassification.CONSUMER -> consumers.add(PathEndpoint(endpoint, limit))
                EnergyDeviceClassification.STORAGE -> storages.add(PathEndpoint(endpoint, limit))
                else -> {}
            }
        }

        return PathGroup(capacity, consumers, storages)
    }
}
