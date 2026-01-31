package com.trinex.lib.api.itemtransport

import com.hypixel.hytale.server.core.inventory.container.ItemContainer

interface ContextualItemContainerAccess : ItemContainerAccess {
    fun getContainer(
        side: BlockSide,
        mode: ItemContainerAccessMode,
    ): ItemContainer?
}
