package com.trinex.lib.api.energy

import com.hypixel.hytale.codec.Codec
import com.hypixel.hytale.codec.KeyedCodec
import com.hypixel.hytale.codec.builder.BuilderCodec

object EnergyDefinitions {
    var energyUnit: String = "Joules"
    var energyUnitAbbreviation: String = "J"

    val CODEC: BuilderCodec<EnergyDefinitions> =
        BuilderCodec
            .builder<EnergyDefinitions>(EnergyDefinitions::class.java, { EnergyDefinitions })
            .append(
                KeyedCodec("EnergyUnit", Codec.STRING),
                { data, value -> data.energyUnit = value },
                { data -> data.energyUnit },
            ).add()
            .append(
                KeyedCodec("EnergyUnitAbbreviation", Codec.STRING),
                { data, value -> data.energyUnitAbbreviation = value },
                { data -> data.energyUnitAbbreviation },
            ).add()
            .build()

    private val POWER_PREFIXES = arrayOf("", "k", "M", "G", "T", "P", "E")
    private val POWER_THRESHOLDS =
        arrayOf(1L, 1_000L, 1_000_000L, 1_000_000_000L, 1_000_000_000_000L, 1_000_000_000_000_000L, 1_000_000_000_000_000_000L)

    fun getAbbreviationFor(powerAmount: Long): String {
        var index = POWER_PREFIXES.size - 1
        while (index > 0) {
            if (powerAmount >= POWER_THRESHOLDS[index]) {
                val value = powerAmount.toDouble() / POWER_THRESHOLDS[index]
                return String.format("%.1f%s%s", value, POWER_PREFIXES[index], energyUnitAbbreviation)
            }
            index--
        }
        return "$powerAmount$energyUnitAbbreviation"
    }

    fun getLongFormFor(powerAmount: Long): String {
        var index = POWER_PREFIXES.size - 1
        while (index > 0) {
            if (powerAmount >= POWER_THRESHOLDS[index]) {
                val value = powerAmount.toDouble() / POWER_THRESHOLDS[index]
                return String.format("%.1f%s %s", value, POWER_PREFIXES[index], energyUnit)
            }
            index--
        }
        return "$powerAmount $energyUnit"
    }
}
