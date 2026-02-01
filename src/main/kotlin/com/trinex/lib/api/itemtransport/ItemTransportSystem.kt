package com.trinex.lib.api.itemtransport

import com.hypixel.hytale.component.ArchetypeChunk
import com.hypixel.hytale.component.CommandBuffer
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.component.query.Query
import com.hypixel.hytale.component.system.tick.EntityTickingSystem
import com.hypixel.hytale.logger.HytaleLogger
import com.hypixel.hytale.server.core.inventory.ItemStack
import com.hypixel.hytale.server.core.inventory.container.ItemContainer
import com.hypixel.hytale.server.core.modules.block.BlockModule
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore
import com.trinex.lib.TrinexLib

class ItemTransportSystem : EntityTickingSystem<ChunkStore?>() {
    private val logger = HytaleLogger.forEnclosingClass()

    private data class EndpointState(
        val endpoint: ItemTransportUtils.PathEndpoint,
        var remaining: Int,
    )

    private data class ItemRequest(
        val endpointState: EndpointState,
        val template: ItemStack?,
        val matchMetadata: Boolean,
        var remaining: Int,
    )

    override fun tick(
        dt: Float,
        index: Int,
        archetypeChunk: ArchetypeChunk<ChunkStore?>,
        store: Store<ChunkStore?>,
        commandBuffer: CommandBuffer<ChunkStore?>,
    ) {
        val transport = archetypeChunk.getComponent(index, TrinexLib.get().itemTransportComponentType) ?: return
        val stateInfo = archetypeChunk.getComponent(index, BlockModule.BlockStateInfo.getComponentType()) ?: return
        val wc = commandBuffer.getComponent(stateInfo.chunkRef, WorldChunk.getComponentType()) ?: return
        val world = wc.world ?: return
        val network = ItemTransportUtils.getNetwork(transport, wc, commandBuffer)
        if (ItemTransportUtils.markNetworkProcessed(world, network)) {
            processNetwork(network, wc, commandBuffer, dt)
        }
    }

    override fun getQuery(): Query<ChunkStore?> = Query.and(TrinexLib.get().itemTransportComponentType)

    private fun processNetwork(
        network: ItemTransportUtils.TransportNetwork,
        wc: WorldChunk,
        commandBuffer: CommandBuffer<ChunkStore?>,
        dt: Float,
    ) {
        for (transport in network.transports) {
            processTransport(transport, wc, commandBuffer, dt)
        }
    }

    private fun processTransport(
        transport: ItemTransportComponent,
        wc: WorldChunk,
        commandBuffer: CommandBuffer<ChunkStore?>,
        dt: Float,
    ) {
        if (transport.itemsPerSecond > 0) {
            transport.transferBuffer =
                minOf(transport.transferBuffer + transport.itemsPerSecond * dt, transport.itemsPerSecond.toDouble())
        } else {
            transport.transferBuffer = 0.0
        }

        val sources =
            ItemTransportUtils
                .getAdjacentContainers(transport, wc, commandBuffer)
                .filter { transport.getSideMode(it.side) == ItemTransportMode.PULL }
        if (sources.isEmpty()) return

        val endpoints = buildEndpointStates(transport, wc, commandBuffer)
        if (endpoints.isEmpty()) return

        val transferBudget = transport.transferBuffer.toInt()
        var remainingTransfer = minOf(transferBudget, transport.itemsPerSecond)
        if (remainingTransfer <= 0) return

        val requests = buildRequests(endpoints)
        if (requests.isEmpty()) return

        val endpointCount = requests.size
        val startOffset =
            if (transport.pushRoundRobinOffset >=
                endpointCount
            ) {
                transport.pushRoundRobinOffset % endpointCount
            } else {
                transport.pushRoundRobinOffset
            }
        var nextOffset = startOffset

        for (i in 0 until endpointCount) {
            if (remainingTransfer <= 0) break
            val index = (startOffset + i) % endpointCount
            val request = requests[index]
            val moved = fulfillRequest(request, sources, remainingTransfer, transport)
            if (moved > 0) {
                remainingTransfer -= moved
                nextOffset = (index + 1) % endpointCount
            }
        }

        if (remainingTransfer != minOf(transferBudget, transport.itemsPerSecond)) {
            val moved = minOf(transferBudget, transport.itemsPerSecond) - remainingTransfer
            transport.transferBuffer = maxOf(0.0, transport.transferBuffer - moved)
        }
        transport.pushRoundRobinOffset = nextOffset
    }

    private fun buildEndpointStates(
        transport: ItemTransportComponent,
        wc: WorldChunk,
        commandBuffer: CommandBuffer<ChunkStore?>,
    ): MutableList<EndpointState> {
        val groups = ItemTransportUtils.getPathGroups(transport, wc, commandBuffer)
        if (groups.isEmpty()) return mutableListOf()
        val byEndpoint = LinkedHashMap<EndpointKey, EndpointState>()
        for (group in groups) {
            for (endpoint in group.endpoints) {
                val key = EndpointKey(endpoint.access, endpoint.side, endpoint.filter)
                val existing = byEndpoint[key]
                val remaining = minOf(endpoint.maxTransfer, transport.itemsPerSecond)
                if (existing == null) {
                    byEndpoint[key] = EndpointState(endpoint, remaining)
                } else if (remaining > existing.remaining) {
                    existing.remaining = remaining
                }
            }
        }
        return byEndpoint.values.toMutableList()
    }

    private fun buildRequests(endpoints: List<EndpointState>): MutableList<ItemRequest> {
        val requests = mutableListOf<ItemRequest>()
        for (endpointState in endpoints) {
            if (endpointState.remaining <= 0) continue
            val endpoint = endpointState.endpoint
            val access = endpoint.access
            val filter = endpoint.filter

            val requested =
                if (access is ItemTransportRequestProvider) {
                    access.getRequestedItems(endpoint.side, endpointState.remaining)
                } else {
                    emptyList()
                }

            val filteredRequested =
                requested.filter { stack ->
                    if (ItemStack.isEmpty(stack)) return@filter false
                    filter == null || filter.allows(stack)
                }

            if (filteredRequested.isNotEmpty()) {
                for (stack in filteredRequested) {
                    val amount = minOf(stack.quantity, endpointState.remaining)

                    @Suppress("DEPRECATION")
                    val hasMetadata = stack.metadata != null
                    requests.add(ItemRequest(endpointState, stack, hasMetadata, amount))
                }
                continue
            }

            val ids = filter?.ids.orEmpty()
            if (ids.isNotEmpty()) {
                val exactIds = ids.filterNot { ItemFilter.isWildcardPattern(it) }
                val hasWildcard = exactIds.size != ids.size
                for (id in exactIds) {
                    if (endpointState.remaining <= 0) break
                    val metadata = if (filter?.matchMetadata == true) filter.findMetadataForId(id) else null
                    val template = ItemStack(id, 1, metadata)
                    val matchMetadata = filter?.matchMetadata == true && metadata != null
                    requests.add(ItemRequest(endpointState, template, matchMetadata, endpointState.remaining))
                }
                if (hasWildcard) {
                    requests.add(ItemRequest(endpointState, null, false, endpointState.remaining))
                }
                continue
            }

            requests.add(ItemRequest(endpointState, null, false, endpointState.remaining))
        }
        return requests
    }

    private fun fulfillRequest(
        request: ItemRequest,
        sources: List<ItemTransportUtils.SideContainer>,
        limit: Int,
        transport: ItemTransportComponent,
    ): Int {
        if (limit <= 0) return 0
        if (request.remaining <= 0) return 0
        val endpoint = request.endpointState.endpoint
        val destination =
            when (val access = endpoint.access) {
                is ContextualItemContainerAccess -> access.getContainer(endpoint.side, ItemContainerAccessMode.PUSH)
                else -> access.getContainer(endpoint.side)
            } ?: return 0
        val sourceCount = sources.size
        val startOffset =
            if (transport.pullRoundRobinOffset >=
                sourceCount
            ) {
                transport.pullRoundRobinOffset % sourceCount
            } else {
                transport.pullRoundRobinOffset
            }
        var nextOffset = startOffset

        var remainingRequest = minOf(limit, request.remaining, endpoint.maxTransfer, request.endpointState.remaining)
        var moved = 0

        for (i in 0 until sourceCount) {
            if (remainingRequest <= 0) break
            val index = (startOffset + i) % sourceCount
            val source = sources[index]
            val container =
                when (val access = source.access) {
                    is ContextualItemContainerAccess -> access.getContainer(source.side.opposite(), ItemContainerAccessMode.PULL)
                    else -> access.getContainer(source.side.opposite())
                } ?: continue
            val movedFromSource = pullForRequest(container, source.filter, destination, request, endpoint.filter, remainingRequest)
            if (movedFromSource > 0) {
                remainingRequest -= movedFromSource
                moved += movedFromSource
                nextOffset = (index + 1) % sourceCount
            }
        }

        if (moved > 0) {
            transport.pullRoundRobinOffset = nextOffset
            request.remaining -= moved
            request.endpointState.remaining -= moved
        }
        return moved
    }

    private fun pullForRequest(
        source: ItemContainer,
        filter: ItemFilter?,
        destination: ItemContainer,
        request: ItemRequest,
        endpointFilter: ItemFilter?,
        limit: Int,
    ): Int {
        if (limit <= 0) return 0
        val capacity = source.capacity.toInt()
        var moved = 0

        for (slotIndex in 0 until capacity) {
            if (moved >= limit) break
            val slot = slotIndex.toShort()
            val stack = source.getItemStack(slot) ?: continue
            if (ItemStack.isEmpty(stack)) continue
            if (filter != null && !filter.allows(stack)) continue
            if (endpointFilter != null && !endpointFilter.allows(stack)) continue
            if (!matchesRequest(stack, request)) continue

            val remaining = limit - moved
            var tryAmount = minOf(stack.quantity, remaining, request.remaining)
            if (tryAmount <= 0) continue
            var query = stack.withQuantity(tryAmount)
            if (query == null || !destination.canAddItemStack(query, false, true)) {
                if (tryAmount > 1) {
                    tryAmount = 1
                    query = stack.withQuantity(tryAmount)
                }
                if (query == null || !destination.canAddItemStack(query, false, true)) continue
            }

            val removed = source.removeItemStackFromSlot(slot, tryAmount, false, true)
            if (!removed.succeeded()) continue
            val output = removed.output ?: continue
            val add = destination.addItemStack(output, false, false, true)
            if (!add.succeeded()) {
                logger.atWarning().log("Failed to insert item stack into destination container.")
                source.addItemStack(output, false, false, true)
                break
            }
            moved += tryAmount
        }

        return moved
    }

    private fun matchesRequest(
        stack: ItemStack,
        request: ItemRequest,
    ): Boolean {
        val template = request.template ?: return true
        return if (request.matchMetadata) {
            @Suppress("DEPRECATION")
            val templateMeta = template.metadata
            @Suppress("DEPRECATION")
            val stackMeta = stack.metadata
            if (templateMeta == null) {
                stack.itemId.equals(template.itemId, ignoreCase = true)
            } else {
                stack.itemId.equals(template.itemId, ignoreCase = true) && templateMeta == stackMeta
            }
        } else {
            stack.itemId.equals(template.itemId, ignoreCase = true)
        }
    }

    private data class EndpointKey(
        val access: ItemContainerAccess,
        val side: BlockSide,
        val filter: ItemFilter?,
    )
}
