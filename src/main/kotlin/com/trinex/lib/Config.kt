package com.trinex.lib

import com.hypixel.hytale.codec.KeyedCodec
import com.hypixel.hytale.codec.builder.BuilderCodec
import com.trinex.lib.api.energy.EnergyDefinitions

object Config {
    var energyDefinitions = EnergyDefinitions

    val CODEC: BuilderCodec<Config> =
        BuilderCodec
            .builder(Config::class.java, { Config })
            .append(
                KeyedCodec("EnergyDefinitions", EnergyDefinitions.CODEC),
                { data, value -> data.energyDefinitions = value },
                { data -> data.energyDefinitions },
            ).add()
            .build()
}
