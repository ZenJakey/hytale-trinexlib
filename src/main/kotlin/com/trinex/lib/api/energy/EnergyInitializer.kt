package com.trinex.lib.api.energy

import com.hypixel.hytale.component.AddReason
import com.hypixel.hytale.component.CommandBuffer
import com.hypixel.hytale.component.Ref
import com.hypixel.hytale.component.RemoveReason
import com.hypixel.hytale.component.Store
import com.hypixel.hytale.component.query.Query
import com.hypixel.hytale.component.system.RefSystem
import com.hypixel.hytale.math.util.ChunkUtil
import com.hypixel.hytale.math.vector.Vector3i
import com.hypixel.hytale.server.core.modules.block.BlockModule
import com.hypixel.hytale.server.core.universe.world.World
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore
import com.trinex.lib.TrinexLib

class EnergyInitializer : RefSystem<ChunkStore?>() {
    override fun onEntityAdded(
        p0: Ref<ChunkStore?>,
        p1: AddReason,
        p2: Store<ChunkStore?>,
        p3: CommandBuffer<ChunkStore?>,
    ) {
        val info = p3.getComponent(p0, BlockModule.BlockStateInfo.getComponentType()) ?: return
        val energyComponent = p3.getComponent(p0, TrinexLib.get().energyComponentType) ?: return
        val wc = p3.getComponent(info.chunkRef, WorldChunk.getComponentType()) ?: return

        val i = info.index
        val x = ChunkUtil.worldCoordFromLocalCoord(wc.x, ChunkUtil.xFromBlockInColumn(i))
        val y = ChunkUtil.yFromBlockInColumn(i)
        val z = ChunkUtil.worldCoordFromLocalCoord(wc.z, ChunkUtil.zFromBlockInColumn(i))

        val vector = Vector3i(x, y, z)
        energyComponent.blockPosition3d = vector
        val world = wc.world ?: return
        if (energyComponent.networkId.isBlank()) {
            val blockType = wc.getBlockType(x, y, z)
            if (blockType != null) {
                energyComponent.networkId = blockType.id
            }
        }
        energyComponent.occupiedPositions = collectOccupiedPositions(world, vector, p0)
    }

    override fun onEntityRemove(
        p0: Ref<ChunkStore?>,
        p1: RemoveReason,
        p2: Store<ChunkStore?>,
        p3: CommandBuffer<ChunkStore?>,
    ) {}

    override fun getQuery(): Query<ChunkStore?> = Query.and(TrinexLib.get().energyComponentType)

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
                val neighborRef = getBlockComponentEntityAtWorldPos(world, neighborPos.x, neighborPos.y, neighborPos.z) ?: continue
                if (neighborRef != targetRef) continue
                if (!visited.contains(neighborPos)) {
                    queue.add(neighborPos)
                }
            }
        }

        return visited
    }

    private fun getBlockComponentEntityAtWorldPos(
        world: World,
        x: Int,
        y: Int,
        z: Int,
    ): Ref<ChunkStore?>? {
        val chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(x, z)) ?: return null
        return chunk.getBlockComponentEntity(x, y, z)
    }
}
