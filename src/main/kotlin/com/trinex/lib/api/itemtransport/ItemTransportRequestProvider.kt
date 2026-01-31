package com.trinex.lib.api.itemtransport

import com.hypixel.hytale.server.core.inventory.ItemStack

interface ItemTransportRequestProvider {
    // Return preferred items for this side; quantities are treated as per-tick request caps.
    fun getRequestedItems(
        side: BlockSide,
        limit: Int,
    ): List<ItemStack>
}
