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
    }

    override fun onEntityRemove(
        p0: Ref<ChunkStore?>,
        p1: RemoveReason,
        p2: Store<ChunkStore?>,
        p3: CommandBuffer<ChunkStore?>,
    ) {}

    override fun getQuery(): Query<ChunkStore?> = Query.and(TrinexLib.get().energyComponentType)
}
