package com.trinex.lib.api.itemtransport

import com.hypixel.hytale.component.CommandBuffer
import com.hypixel.hytale.component.Component
import com.hypixel.hytale.component.ComponentType
import com.hypixel.hytale.math.vector.Vector3i
import com.hypixel.hytale.server.core.universe.world.World
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore

fun interface ItemContainerAccessProvider {
    fun getAccess(
        world: World,
        position: Vector3i,
        commandBuffer: CommandBuffer<ChunkStore?>,
    ): ItemContainerAccess?
}

object ItemContainerAccessRegistry {
    private val providers = mutableListOf<ItemContainerAccessProvider>()

    fun register(provider: ItemContainerAccessProvider) {
        providers.add(provider)
    }

    fun unregister(provider: ItemContainerAccessProvider) {
        providers.remove(provider)
    }

    fun <T : Component<ChunkStore?>> registerComponent(
        componentType: ComponentType<ChunkStore?, T>,
        extractor: (T) -> ItemContainerAccess?,
    ): ItemContainerAccessProvider {
        val provider =
            ItemContainerAccessProvider { world, position, commandBuffer ->
                val ref = ItemTransportUtils.getBlockComponentEntityAtWorldPos(world, position) ?: return@ItemContainerAccessProvider null
                val component = commandBuffer.getComponent(ref, componentType) ?: return@ItemContainerAccessProvider null
                extractor(component)
            }
        register(provider)
        return provider
    }

    fun getAccess(
        world: World,
        position: Vector3i,
        commandBuffer: CommandBuffer<ChunkStore?>,
    ): ItemContainerAccess? {
        for (provider in providers) {
            val access = provider.getAccess(world, position, commandBuffer)
            if (access != null) {
                return access
            }
        }
        return null
    }
}
