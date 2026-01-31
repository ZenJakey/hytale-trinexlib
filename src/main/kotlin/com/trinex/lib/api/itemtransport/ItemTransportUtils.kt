package com.trinex.lib.api.itemtransport

import com.hypixel.hytale.component.CommandBuffer
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.math.util.ChunkUtil
import com.hypixel.hytale.math.vector.Vector3i
import com.hypixel.hytale.server.core.universe.world.World
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore
import com.trinex.lib.TrinexLib

object ItemTransportUtils {
    data class TransportNetwork(
        val transports: Set<ItemTransportComponent>,
    )

    data class PathEndpoint(
        val access: ItemContainerAccess,
        val side: BlockSide,
        val filter: ItemFilter?,
        val maxTransfer: Int,
    )

    data class PathGroup(
        val capacity: Int,
        val endpoints: List<PathEndpoint>,
    )

    data class SideContainer(
        val access: ItemContainerAccess,
        val side: BlockSide,
        val filter: ItemFilter?,
    )

    private data class NetworkCache(
        var tick: Long,
        val byComponent: MutableMap<ItemTransportComponent, TransportNetwork>,
    )

    private data class PathCache(
        var tick: Long,
        val bySource: MutableMap<ItemTransportComponent, List<PathGroup>>,
    )

    private data class NetworkProcessCache(
        var tick: Long,
        val processed: MutableSet<TransportNetwork>,
    )

    private val networkCacheByWorld = java.util.WeakHashMap<World, NetworkCache>()
    private val pathCacheByWorld = java.util.WeakHashMap<World, PathCache>()
    private val processedNetworksByWorld = java.util.WeakHashMap<World, NetworkProcessCache>()

    fun getAdjacentTransportComponents(
        transport: ItemTransportComponent,
        wc: WorldChunk,
        commandBuffer: CommandBuffer<ChunkStore?>,
    ): Set<ItemTransportComponent> =
        buildSet {
            val world = wc.world ?: return@buildSet
            val positions =
                transport.occupiedPositions?.takeIf { it.isNotEmpty() }
                    ?: transport.blockPosition3d?.let { setOf(it) }
                    ?: emptySet()
            val portPositions = transport.portPositions

            for (basePos in positions) {
                if (portPositions != null && !portPositions.contains(basePos)) continue
                for (side in BlockSide.values()) {
                    val pos = basePos.clone().add(side.offset)
                    val neighborRef = getBlockComponentEntityAtWorldPos(world, pos) ?: continue
                    val neighbor =
                        commandBuffer.getComponent(neighborRef, TrinexLib.get().itemTransportComponentType) ?: continue
                    if (neighbor == transport) continue
                    add(neighbor)
                }
            }
        }

    fun getAdjacentContainers(
        transport: ItemTransportComponent,
        wc: WorldChunk,
        commandBuffer: CommandBuffer<ChunkStore?>,
    ): List<SideContainer> {
        val world = wc.world ?: return emptyList()
        val positions =
            transport.occupiedPositions?.takeIf { it.isNotEmpty() }
                ?: transport.blockPosition3d?.let { setOf(it) }
                ?: emptySet()
        val portPositions = transport.portPositions
        val results = LinkedHashSet<SideContainer>()

        for (basePos in positions) {
            if (portPositions != null && !portPositions.contains(basePos)) continue
            for (side in BlockSide.values()) {
                val pos = basePos.clone().add(side.offset)
                val access = ItemContainerAccessRegistry.getAccess(world, pos, commandBuffer) ?: continue
                results.add(SideContainer(access, side, transport.getSideFilter(side)))
            }
        }

        return results.toList()
    }

    fun getNetwork(
        transport: ItemTransportComponent,
        wc: WorldChunk,
        commandBuffer: CommandBuffer<ChunkStore?>,
    ): TransportNetwork {
        val world = wc.world ?: return TransportNetwork(emptySet())
        val worldTick = world.tick
        val cache = networkCacheByWorld.getOrPut(world) { NetworkCache(-1L, mutableMapOf()) }
        if (cache.tick != worldTick) {
            cache.tick = worldTick
            cache.byComponent.clear()
        }

        cache.byComponent[transport]?.let { return it }

        val visited = mutableSetOf<ItemTransportComponent>()
        val queue = ArrayDeque<ItemTransportComponent>()
        queue.add(transport)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            val neighbors = getAdjacentTransportComponents(current, wc, commandBuffer)
            for (neighbor in neighbors) {
                if (!visited.contains(neighbor)) {
                    queue.add(neighbor)
                }
            }
        }

        val network = TransportNetwork(visited)
        for (component in visited) {
            cache.byComponent[component] = network
        }
        return network
    }

    fun markNetworkProcessed(
        world: World,
        network: TransportNetwork,
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
        transport: ItemTransportComponent,
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

        cache.bySource[transport]?.let { return it }

        val groups = mutableListOf<PathGroup>()
        val directEndpoints =
            getAdjacentContainers(transport, wc, commandBuffer)
                .filter { transport.getSideMode(it.side) == ItemTransportMode.PUSH }
                .map { endpoint -> PathEndpoint(endpoint.access, endpoint.side.opposite(), endpoint.filter, transport.itemsPerSecond) }

        for (endpoint in directEndpoints) {
            groups.add(PathGroup(transport.itemsPerSecond, listOf(endpoint)))
        }

        val neighbors = getAdjacentTransportComponents(transport, wc, commandBuffer)
        for (neighbor in neighbors) {
            val capacity = minOf(transport.itemsPerSecond, neighbor.itemsPerSecond)
            if (capacity <= 0) continue
            val group = buildTransportPathGroup(transport, neighbor, capacity, wc, commandBuffer)
            if (group != null && group.endpoints.isNotEmpty()) {
                groups.add(group)
            }
        }

        cache.bySource[transport] = groups
        return groups
    }

    private fun buildTransportPathGroup(
        source: ItemTransportComponent,
        start: ItemTransportComponent,
        capacity: Int,
        wc: WorldChunk,
        commandBuffer: CommandBuffer<ChunkStore?>,
    ): PathGroup? {
        val endpointLimits = mutableMapOf<EndpointKey, Int>()
        val visitedTransports = mutableSetOf<ItemTransportComponent>()
        val queue = ArrayDeque<Pair<ItemTransportComponent, Int>>()
        queue.add(start to capacity)
        visitedTransports.add(start)

        while (queue.isNotEmpty()) {
            val (current, currentLimit) = queue.removeFirst()
            val pushEndpoints =
                getAdjacentContainers(current, wc, commandBuffer)
                    .filter { current.getSideMode(it.side) == ItemTransportMode.PUSH }
                    .map { endpoint ->
                        PathEndpoint(endpoint.access, endpoint.side.opposite(), endpoint.filter, currentLimit)
                    }
            for (endpoint in pushEndpoints) {
                val key = EndpointKey(endpoint.access, endpoint.side, endpoint.filter)
                val existing = endpointLimits[key]
                if (existing == null || currentLimit > existing) {
                    endpointLimits[key] = currentLimit
                }
            }

            val neighbors = getAdjacentTransportComponents(current, wc, commandBuffer)
            for (neighbor in neighbors) {
                if (neighbor == source) continue
                val nextLimit = minOf(currentLimit, neighbor.itemsPerSecond)
                if (visitedTransports.add(neighbor)) {
                    queue.add(neighbor to nextLimit)
                }
            }
        }

        if (endpointLimits.isEmpty()) return null

        val endpoints =
            endpointLimits.map { (key, limit) ->
                PathEndpoint(key.access, key.side, key.filter, limit)
            }
        return PathGroup(capacity, endpoints)
    }

    fun getBlockComponentEntityAtWorldPos(
        world: World,
        pos: Vector3i,
    ): Ref<ChunkStore?>? = getBlockComponentEntityAtWorldPos(world, pos.x, pos.y, pos.z)

    fun getBlockComponentEntityAtWorldPos(
        world: World,
        x: Int,
        y: Int,
        z: Int,
    ): Ref<ChunkStore?>? {
        val chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(x, z)) ?: return null
        return chunk.getBlockComponentEntity(x, y, z)
    }

    private data class EndpointKey(
        val access: ItemContainerAccess,
        val side: BlockSide,
        val filter: ItemFilter?,
    )
}
