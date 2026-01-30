package com.trinex.lib.api.energy

import com.hypixel.hytale.codec.Codec
import com.hypixel.hytale.codec.KeyedCodec
import com.hypixel.hytale.codec.builder.BuilderCodec
import com.hypixel.hytale.component.Component
import com.hypixel.hytale.component.ComponentType
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore
import com.trinex.lib.TrinexLib

class EnergyComponent(
    var transferSpeed: Long = 256,
    var energyCapacity: Long = 1024,
    var energy: Long = 0,
    var energyGenerationRatePerSecond: Long = 0,
    var energyConsumptionRatePerSecond: Long = 0,
    var isActive: Boolean = false,
    var deviceType: String = "",
) : Component<ChunkStore?> {
    override fun clone(): Component<ChunkStore?> =
        EnergyComponent(
            transferSpeed,
            energyCapacity,
            energy,
            energyGenerationRatePerSecond,
            energyConsumptionRatePerSecond,
            isActive,
            deviceType,
        )

    // returns the "extra" amount not added to the source
    fun addEnergy(amount: Long): Long {
        val remainingCapacity = energyCapacity - energy
        val amountToAdd = minOf(remainingCapacity, amount)
        energy += amountToAdd
        return (amount - amountToAdd)
    }

    // returns the amount removed from the source
    fun removeEnergy(amount: Long): Long {
        val amountToRemove = minOf(energy, amount)
        energy -= amountToRemove
        return amountToRemove
    }

    companion object {
        fun getComponentType(): ComponentType<ChunkStore?, EnergyComponent> = TrinexLib.get().energyComponentType

        val CODEC =
            BuilderCodec
                .builder(EnergyComponent::class.java, { EnergyComponent() })
                .append(
                    KeyedCodec("TransferSpeed", Codec.LONG),
                    { d, v -> d.transferSpeed = v },
                    { d -> d.transferSpeed },
                ).add()
                .append(
                    KeyedCodec("EnergyCapacity", Codec.LONG),
                    { d, v -> d.energyCapacity = v },
                    { d -> d.energyCapacity },
                ).add()
                .append(
                    KeyedCodec("Energy", Codec.LONG),
                    { d, v -> d.energy = v },
                    { d -> d.energy },
                ).add()
                .append(
                    KeyedCodec("EnergyGenerationRatePerSecond", Codec.LONG),
                    { d, v -> d.energyGenerationRatePerSecond = v },
                    { d -> d.energyGenerationRatePerSecond },
                ).add()
                .append(
                    KeyedCodec("EnergyConsumptionRatePerSecond", Codec.LONG),
                    { d, v -> d.energyConsumptionRatePerSecond = v },
                    { d -> d.energyConsumptionRatePerSecond },
                ).add()
                .append(
                    KeyedCodec("IsActive", Codec.BOOLEAN),
                    { d, v -> d.isActive = v },
                    { d -> d.isActive },
                ).add()
                .append(
                    KeyedCodec("DeviceType", Codec.STRING),
                    { d, v -> d.deviceType = v },
                    { d -> d.deviceType },
                ).add()
                .build()
    }
}
