package com.trinex.lib

import com.hypixel.hytale.component.Component
import com.hypixel.hytale.component.ComponentType
import com.hypixel.hytale.logger.HytaleLogger
import com.hypixel.hytale.server.core.plugin.JavaPlugin
import com.hypixel.hytale.server.core.plugin.JavaPluginInit
import com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerBlockState
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore
import com.trinex.lib.api.energy.EnergyComponent
import com.trinex.lib.api.energy.EnergyInitializer
import com.trinex.lib.api.energy.EnergySystem
import com.trinex.lib.api.energy.device.DefaultDeviceType
import com.trinex.lib.api.energy.device.EnergyDeviceTypeRegistry
import com.hypixel.hytale.server.core.inventory.container.ItemContainer
import com.hypixel.hytale.builtin.crafting.state.ProcessingBenchState
import com.trinex.lib.api.itemtransport.BlockSide
import com.trinex.lib.api.itemtransport.ContextualItemContainerAccess
import com.trinex.lib.api.itemtransport.ItemContainerAccess
import com.trinex.lib.api.itemtransport.ItemContainerAccessMode
import com.trinex.lib.api.itemtransport.ItemContainerAccessRegistry
import com.trinex.lib.api.itemtransport.ItemTransportComponent
import com.trinex.lib.api.itemtransport.ItemTransportInitializer
import com.trinex.lib.api.itemtransport.ItemTransportSystem
import com.trinex.lib.api.itemtransport.ItemTransportUtils
import com.trinex.lib.messenger.Messenger

class TrinexLib(
    init: JavaPluginInit,
) : JavaPlugin(init) {
    private val logger = HytaleLogger.forEnclosingClass()
    private val processingBenchOutputField =
        runCatching {
            ProcessingBenchState::class.java.getDeclaredField("outputContainer").apply { isAccessible = true }
        }.getOrNull()
    val config = this.withConfig(Config.CODEC)
    val messenger = Messenger("TrinexLib")
    lateinit var energyComponentType: ComponentType<ChunkStore?, EnergyComponent>
    lateinit var itemTransportComponentType: ComponentType<ChunkStore?, ItemTransportComponent>

    init {
        instance = this
    }

    override fun setup() {
        logger.atInfo().log("Setting up plugin " + this.name)
        energyComponentType =
            this.chunkStoreRegistry.registerComponent(EnergyComponent::class.java, "EnergyComponent", EnergyComponent.CODEC)
        itemTransportComponentType =
            this.chunkStoreRegistry.registerComponent(
                ItemTransportComponent::class.java,
                "ItemTransportComponent",
                ItemTransportComponent.CODEC,
            )
        this.chunkStoreRegistry.registerSystem(EnergySystem())
        this.chunkStoreRegistry.registerSystem(EnergyInitializer())
        this.chunkStoreRegistry.registerSystem(ItemTransportSystem())
        this.chunkStoreRegistry.registerSystem(ItemTransportInitializer())
        EnergyDeviceTypeRegistry.register("Default", "Default", DefaultDeviceType())

        ItemContainerAccessRegistry.register { world, position, commandBuffer ->
            fun resolveAccessAt(pos: com.hypixel.hytale.math.vector.Vector3i): ItemContainerAccess? {
                val ref = ItemTransportUtils.getBlockComponentEntityAtWorldPos(world, pos) ?: return null
                val archetype = commandBuffer.getArchetype(ref)
                for (i in archetype.minIndex until archetype.length()) {
                    val type = archetype.get(i) ?: continue
                    if (!ItemContainerBlockState::class.java.isAssignableFrom(type.typeClass)) continue
                    @Suppress("UNCHECKED_CAST")
                    val componentType =
                        type as ComponentType<
                            ChunkStore?,
                            Component<ChunkStore?>,
                        >
                    val component = commandBuffer.getComponent(ref, componentType) ?: continue
                    val state = component as? ItemContainerBlockState ?: continue
                    return if (state is ProcessingBenchState) {
                        val output =
                            processingBenchOutputField
                                ?.get(state) as? ItemContainer
                        object : ContextualItemContainerAccess {
                            override fun getContainer(side: BlockSide): ItemContainer? = state.itemContainer

                            override fun getContainer(
                                side: BlockSide,
                                mode: ItemContainerAccessMode,
                            ): ItemContainer? =
                                when (mode) {
                                    ItemContainerAccessMode.PULL -> output ?: state.itemContainer
                                    ItemContainerAccessMode.PUSH -> state.itemContainer
                                }
                        }
                    } else {
                        object : ItemContainerAccess {
                            override fun getContainer(side: BlockSide) = state.itemContainer
                        }
                    }
                }
                return null
            }

            resolveAccessAt(position)?.let { return@register it }
            for (side in BlockSide.values()) {
                val neighborPos = position.clone().add(side.offset)
                resolveAccessAt(neighborPos)?.let { return@register it }
            }
            null
        }
    }

    companion object {
        var instance: TrinexLib? = null

        fun get(): TrinexLib = instance ?: throw IllegalStateException("TrinexLib not initialized")
    }
}
