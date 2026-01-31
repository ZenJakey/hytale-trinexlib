package com.trinex.lib.api.energy

import com.hypixel.hytale.codec.Codec
import com.hypixel.hytale.codec.KeyedCodec
import com.hypixel.hytale.codec.builder.BuilderCodec
import com.hypixel.hytale.component.Component
import com.hypixel.hytale.component.ComponentType
import com.hypixel.hytale.math.vector.Vector3i
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore
import com.trinex.lib.TrinexLib

class EnergyComponent(
    var transferSpeed: Long = 256,
    var energyCapacity: Long = 0,
    var energy: Long = 0,
    var energyGenerationPerTick: Long = 0,
    var energyConsumptionPerTick: Long = 0,
    var isActive: Boolean = false,
    var deviceType: String = "Default:Default",
    var deviceClassification: EnergyDeviceClassification = EnergyDeviceClassification.NONE,
    var blockPosition3d: Vector3i? = null,
) : Component<ChunkStore?> {
    override fun clone(): Component<ChunkStore?> =
        EnergyComponent(
            transferSpeed,
            energyCapacity,
            energy,
            energyGenerationPerTick,
            energyConsumptionPerTick,
            isActive,
            deviceType,
            deviceClassification,
            blockPosition3d,
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
                    KeyedCodec("EnergyGenerationPerTick", Codec.LONG),
                    { d, v -> d.energyGenerationPerTick = v },
                    { d -> d.energyGenerationPerTick },
                ).add()
                .append(
                    KeyedCodec("EnergyConsumptionPerTick", Codec.LONG),
                    { d, v -> d.energyConsumptionPerTick = v },
                    { d -> d.energyConsumptionPerTick },
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
                .append(
                    KeyedCodec("DeviceClassification", Codec.STRING),
                    { d, v -> d.deviceClassification = EnergyDeviceClassification.valueOf(v) },
                    { d -> d.deviceClassification.name },
                ).add()
                .append(
                    KeyedCodec("BlockPosition3d", Vector3i.CODEC),
                    { d, v -> d.blockPosition3d = v },
                    { d -> d.blockPosition3d },
                ).add()
                .build()
    }
}
