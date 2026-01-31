package com.trinex.lib.api.itemtransport

import com.hypixel.hytale.component.AddReason
import com.hypixel.hytale.component.CommandBuffer
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.RemoveReason
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.component.query.Query
import com.hypixel.hytale.component.system.RefSystem
import com.hypixel.hytale.logger.HytaleLogger
import com.hypixel.hytale.math.util.ChunkUtil
import com.hypixel.hytale.math.vector.Vector3i
import com.hypixel.hytale.server.core.modules.block.BlockModule
import com.hypixel.hytale.server.core.universe.world.World
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore
import com.trinex.lib.TrinexLib

class ItemTransportInitializer : RefSystem<ChunkStore?>() {
    private val logger = HytaleLogger.forEnclosingClass()

    override fun onEntityAdded(
        p0: Ref<ChunkStore?>,
        p1: AddReason,
        p2: Store<ChunkStore?>,
        p3: CommandBuffer<ChunkStore?>,
    ) {
        val info = p3.getComponent(p0, BlockModule.BlockStateInfo.getComponentType()) ?: return
        val transport = p3.getComponent(p0, TrinexLib.get().itemTransportComponentType) ?: return
        val wc = p3.getComponent(info.chunkRef, WorldChunk.getComponentType()) ?: return

        val i = info.index
        val x = ChunkUtil.worldCoordFromLocalCoord(wc.x, ChunkUtil.xFromBlockInColumn(i))
        val y = ChunkUtil.yFromBlockInColumn(i)
        val z = ChunkUtil.worldCoordFromLocalCoord(wc.z, ChunkUtil.zFromBlockInColumn(i))

        val vector = Vector3i(x, y, z)
        transport.blockPosition3d = vector
        val world = wc.world ?: return
        transport.occupiedPositions = collectOccupiedPositions(world, vector, p0)
        logNeighborComponents(world, vector, p3)
    }

    override fun onEntityRemove(
        p0: Ref<ChunkStore?>,
        p1: RemoveReason,
        p2: Store<ChunkStore?>,
        p3: CommandBuffer<ChunkStore?>,
    ) {}

    override fun getQuery(): Query<ChunkStore?> = Query.and(TrinexLib.get().itemTransportComponentType)

    private fun collectOccupiedPositions(
        world: World,
        origin: Vector3i,
        targetRef: Ref<ChunkStore?>,
    ): MutableSet<Vector3i> {
        val visited = mutableSetOf<Vector3i>()
        val queue = ArrayDeque<Vector3i>()
        queue.add(origin)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue

            for (dir in Vector3i.BLOCK_SIDES) {
                val neighborPos = current.clone().add(dir)
                val neighborRef =
                    ItemTransportUtils.getBlockComponentEntityAtWorldPos(world, neighborPos.x, neighborPos.y, neighborPos.z)
                        ?: continue
                if (neighborRef != targetRef) continue
                if (!visited.contains(neighborPos)) {
                    queue.add(neighborPos)
                }
            }
        }

        return visited
    }

    private fun logNeighborComponents(
        world: World,
        origin: Vector3i,
        commandBuffer: CommandBuffer<ChunkStore?>,
    ) {
        for (dir in Vector3i.BLOCK_SIDES) {
            val neighborPos = origin.clone().add(dir)
            val neighborRef =
                ItemTransportUtils.getBlockComponentEntityAtWorldPos(world, neighborPos.x, neighborPos.y, neighborPos.z)
                    ?: continue
            val archetype = commandBuffer.getArchetype(neighborRef)
            val names = mutableListOf<String>()
            for (i in archetype.minIndex until archetype.length()) {
                val type = archetype.get(i) ?: continue
                names.add(type.typeClass.name)
            }
            logger
                .atInfo()
                .log(
                    "ItemTransport neighbor at (%d,%d,%d) dir=%s components=%s",
                    neighborPos.x,
                    neighborPos.y,
                    neighborPos.z,
                    dir,
                    names.joinToString(prefix = "[", postfix = "]"),
                )
        }
    }
}
