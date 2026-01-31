package com.trinex.lib.api.itemtransport

import com.hypixel.hytale.server.core.inventory.container.ItemContainer

interface ItemContainerAccess {
    // Return a container view restricted to the slots accessible from this side.
    fun getContainer(side: BlockSide): ItemContainer?
}
