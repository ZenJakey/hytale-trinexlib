package com.trinex.lib.api.itemtransport

import com.hypixel.hytale.math.vector.Vector3i

enum class BlockSide(
    val offset: Vector3i,
) {
    UP(Vector3i.UP),
    DOWN(Vector3i.DOWN),
    NORTH(Vector3i.NORTH),
    SOUTH(Vector3i.SOUTH),
    WEST(Vector3i.WEST),
    EAST(Vector3i.EAST),
    ;

    fun opposite(): BlockSide =
        when (this) {
            UP -> DOWN
            DOWN -> UP
            NORTH -> SOUTH
            SOUTH -> NORTH
            WEST -> EAST
            EAST -> WEST
        }

    companion object {
        fun fromName(name: String): BlockSide? =
            try {
                valueOf(name)
            } catch (ex: IllegalArgumentException) {
                null
            }

        fun fromOffset(offset: Vector3i): BlockSide? =
            values().firstOrNull { it.offset == offset }
    }
}
