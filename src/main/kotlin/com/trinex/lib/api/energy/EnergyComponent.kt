package com.trinex.lib.api.energy

import com.hypixel.hytale.codec.Codec
import com.hypixel.hytale.codec.KeyedCodec
import com.hypixel.hytale.codec.builder.BuilderCodec
import com.hypixel.hytale.codec.codecs.array.ArrayCodec
import com.hypixel.hytale.component.Component
import com.hypixel.hytale.component.ComponentType
import com.hypixel.hytale.math.vector.Vector3i
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore
import com.trinex.lib.TrinexLib
import java.util.function.IntFunction

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
    var occupiedPositions: MutableSet<Vector3i>? = null,
    var portPositions: Set<Vector3i>? = null,
    var consumerPathOffset: Int = 0,
    var storagePathOffset: Int = 0,
    var consumerRoundRobinOffset: Int = 0,
    var storageRoundRobinOffset: Int = 0,
    var energyDeltaLastTick: Long = 0,
    var lastEnergyDeltaTick: Long = -1,
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
            occupiedPositions,
            portPositions,
            consumerPathOffset,
            storagePathOffset,
            consumerRoundRobinOffset,
            storageRoundRobinOffset,
            energyDeltaLastTick,
            lastEnergyDeltaTick,
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
                .append(
                    KeyedCodec("PortPositions", ArrayCodec(Vector3i.CODEC, IntFunction { size -> vector3iArray(size) })),
                    { d, v -> d.portPositions = v?.toSet() },
                    { d -> d.portPositions?.toTypedArray() },
                ).add()
                .build()

        @Suppress("UNCHECKED_CAST")
        private fun vector3iArray(size: Int): Array<Vector3i> =
            java.lang.reflect.Array.newInstance(Vector3i::class.java, size) as Array<Vector3i>
    }
}
