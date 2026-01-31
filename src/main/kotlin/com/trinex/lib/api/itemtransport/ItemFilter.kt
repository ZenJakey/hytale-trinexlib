package com.trinex.lib.api.itemtransport

import com.hypixel.hytale.codec.Codec
import com.hypixel.hytale.codec.KeyedCodec
import com.hypixel.hytale.codec.builder.BuilderCodec
import com.hypixel.hytale.codec.codecs.map.MapCodec
import com.hypixel.hytale.server.core.inventory.ItemStack
import org.bson.BsonDocument
import java.util.HashMap

data class ItemFilter(
    var mode: ItemFilterMode = ItemFilterMode.WHITELIST,
    var ids: MutableSet<String> = mutableSetOf(),
    var matchMetadata: Boolean = false,
    var metadataById: MutableMap<String, BsonDocument> = mutableMapOf(),
) {
    fun allows(stack: ItemStack?): Boolean {
        if (stack == null || ItemStack.isEmpty(stack)) {
            return false
        }
        val matched = matches(stack)
        return if (mode == ItemFilterMode.WHITELIST) matched else !matched
    }

    fun matches(stack: ItemStack): Boolean {
        val itemId = stack.itemId
        if (!ids.contains(itemId)) {
            return false
        }
        if (!matchMetadata) {
            return true
        }
        val expectedMeta = metadataById[itemId] ?: return true

        @Suppress("DEPRECATION")
        val actualMeta = stack.metadata
        return expectedMeta == actualMeta
    }

    companion object {
        val ALLOW_ALL = ItemFilter(mode = ItemFilterMode.BLACKLIST)

        val CODEC =
            BuilderCodec
                .builder(ItemFilter::class.java, { ItemFilter() })
                .append(
                    KeyedCodec("Mode", Codec.STRING),
                    { d, v -> d.mode = ItemFilterMode.valueOf(v) },
                    { d -> d.mode.name },
                ).add()
                .append(
                    KeyedCodec("Ids", Codec.STRING_ARRAY),
                    { d, v -> d.ids = v?.toMutableSet() ?: mutableSetOf() },
                    { d -> d.ids.toTypedArray() },
                ).add()
                .append(
                    KeyedCodec("MatchMetadata", Codec.BOOLEAN),
                    { d, v -> d.matchMetadata = v },
                    { d -> d.matchMetadata },
                ).add()
                .append(
                    KeyedCodec("MetadataById", MapCodec(Codec.BSON_DOCUMENT, ::HashMap, false)),
                    { d, v -> d.metadataById = v ?: mutableMapOf() },
                    { d -> d.metadataById },
                ).add()
                .build()
    }
}
