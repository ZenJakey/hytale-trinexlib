package com.trinex.lib.api.itemtransport

import com.hypixel.hytale.codec.Codec
import com.hypixel.hytale.codec.KeyedCodec
import com.hypixel.hytale.codec.builder.BuilderCodec
import com.hypixel.hytale.codec.codecs.array.ArrayCodec
import com.hypixel.hytale.codec.codecs.map.MapCodec
import com.hypixel.hytale.component.Component
import com.hypixel.hytale.component.ComponentType
import com.hypixel.hytale.math.vector.Vector3i
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore
import com.trinex.lib.TrinexLib
import java.util.HashMap
import java.util.function.IntFunction

class ItemTransportComponent(
    var itemsPerSecond: Int = 16,
    var transferBuffer: Double = 0.0,
    var networkId: String = "",
    var blockPosition3d: Vector3i? = null,
    var occupiedPositions: MutableSet<Vector3i>? = null,
    var portPositions: Set<Vector3i>? = null,
    var pullRoundRobinOffset: Int = 0,
    var pushRoundRobinOffset: Int = 0,
    var sideModes: MutableMap<BlockSide, ItemTransportMode> = mutableMapOf(),
    var sideFilters: MutableMap<BlockSide, ItemFilter> = mutableMapOf(),
) : Component<ChunkStore?> {
    override fun clone(): Component<ChunkStore?> =
        ItemTransportComponent(
            itemsPerSecond,
            transferBuffer,
            networkId,
            blockPosition3d,
            occupiedPositions?.toMutableSet(),
            portPositions?.toSet(),
            pullRoundRobinOffset,
            pushRoundRobinOffset,
            sideModes.toMutableMap(),
            cloneSideFilters(sideFilters),
        )

    fun getSideMode(side: BlockSide): ItemTransportMode = sideModes[side] ?: ItemTransportMode.DISABLED

    fun setSideMode(
        side: BlockSide,
        mode: ItemTransportMode,
    ) {
        sideModes[side] = mode
    }

    fun getSideFilter(side: BlockSide): ItemFilter? = sideFilters[side]

    fun setSideFilter(
        side: BlockSide,
        filter: ItemFilter?,
    ) {
        if (filter == null) {
            sideFilters.remove(side)
        } else {
            sideFilters[side] = filter
        }
    }

    private fun decodeSideModes(encoded: Map<String, String>?) {
        sideModes.clear()
        if (encoded == null) return
        for ((sideName, modeName) in encoded) {
            val side = BlockSide.fromName(sideName) ?: continue
            val mode =
                try {
                    ItemTransportMode.valueOf(modeName)
                } catch (ex: IllegalArgumentException) {
                    ItemTransportMode.DISABLED
                }
            if (mode != null) {
                sideModes[side] = mode
            }
        }
    }

    private fun encodeSideModes(): MutableMap<String, String> =
        sideModes
            .mapKeys { it.key.name }
            .mapValues { it.value.name }
            .toMutableMap()

    private fun decodeSideFilters(encoded: Map<String, ItemFilter>?) {
        sideFilters.clear()
        if (encoded == null) return
        for ((sideName, filter) in encoded) {
            val side = BlockSide.fromName(sideName) ?: continue
            sideFilters[side] = filter
        }
    }

    private fun encodeSideFilters(): MutableMap<String, ItemFilter> =
        sideFilters
            .mapKeys { it.key.name }
            .toMutableMap()

    companion object {
        fun getComponentType(): ComponentType<ChunkStore?, ItemTransportComponent> = TrinexLib.get().itemTransportComponentType

        val CODEC =
            BuilderCodec
                .builder(ItemTransportComponent::class.java, { ItemTransportComponent() })
                .append(
                    KeyedCodec("ItemsPerSecond", Codec.INTEGER),
                    { d, v -> d.itemsPerSecond = v },
                    { d -> d.itemsPerSecond },
                ).add()
                .append(
                    KeyedCodec("TransferBuffer", Codec.DOUBLE),
                    { d, v -> d.transferBuffer = v },
                    { d -> d.transferBuffer },
                ).add()
                .append(
                    KeyedCodec("NetworkId", Codec.STRING),
                    { d, v -> d.networkId = v },
                    { d -> d.networkId },
                ).add()
                .append(
                    KeyedCodec("BlockPosition3d", Vector3i.CODEC),
                    { d, v -> d.blockPosition3d = v },
                    { d -> d.blockPosition3d },
                ).add()
                .append(
                    KeyedCodec("PortPositions", ArrayCodec(Vector3i.CODEC, IntFunction { size -> vector3iArray(size) })),
                    { d, v -> d.portPositions = v?.toSet() },
                    { d -> d.portPositions?.toTypedArray() },
                ).add()
                .append(
                    KeyedCodec("PullRoundRobinOffset", Codec.INTEGER),
                    { d, v -> d.pullRoundRobinOffset = v },
                    { d -> d.pullRoundRobinOffset },
                ).add()
                .append(
                    KeyedCodec("PushRoundRobinOffset", Codec.INTEGER),
                    { d, v -> d.pushRoundRobinOffset = v },
                    { d -> d.pushRoundRobinOffset },
                ).add()
                .append(
                    KeyedCodec("SideModes", MapCodec(Codec.STRING, ::HashMap, false)),
                    { d, v -> d.decodeSideModes(v) },
                    { d -> d.encodeSideModes() },
                ).add()
                .append(
                    KeyedCodec("SideFilters", MapCodec(ItemFilter.CODEC, ::HashMap, false)),
                    { d, v -> d.decodeSideFilters(v) },
                    { d -> d.encodeSideFilters() },
                ).add()
                .build()

        private fun cloneSideFilters(filters: Map<BlockSide, ItemFilter>): MutableMap<BlockSide, ItemFilter> =
            filters
                .mapValues { (_, filter) ->
                    filter.copy(
                        ids = filter.ids.toMutableSet(),
                        metadataById = filter.metadataById.toMutableMap(),
                    )
                }.toMutableMap()

        @Suppress("UNCHECKED_CAST")
        private fun vector3iArray(size: Int): Array<Vector3i> =
            java.lang.reflect.Array
                .newInstance(Vector3i::class.java, size) as Array<Vector3i>
    }
}
