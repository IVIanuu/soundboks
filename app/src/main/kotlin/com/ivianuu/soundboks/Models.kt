/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.soundboks

import android.bluetooth.*
import com.ivianuu.essentials.data.*
import com.ivianuu.injekt.*
import kotlinx.serialization.*

data class Soundboks(val address: String, val name: String)

fun BluetoothDevice.toSoundboks() =
  Soundboks(address, (alias ?: name).removePrefix("SOUNDBOKS "))

fun BluetoothDevice.isSoundboks() = (alias ?: name).let {
  it?.startsWith("#", ignoreCase = true) == true ||
      it?.contains("SOUNDBOKS", ignoreCase = true) == true
}

fun BluetoothDevice.debugName() = "[${alias ?: name} ~ $address]"
  .removePrefix("SOUNDBOKS ")

fun Soundboks.debugName() = "[$name ~ $address]"
  .removePrefix("SOUNDBOKS ")

@Serializable data class SoundboksPrefs(
  val configs: Map<String, SoundboksConfig> = emptyMap(),
  val selectedSoundbokses: Set<String> = emptySet()
) {
  @Provide companion object {
    @Provide val dataStoreModule = DataStoreModule("soundboks_prefs") { SoundboksPrefs() }
  }
}

@Serializable data class SoundboksConfig(
  val volume: Float = 0.5f,
  val soundProfile: SoundProfile = SoundProfile.POWER,
  val channel: SoundChannel = SoundChannel.MONO,
  val teamUpMode: TeamUpMode = TeamUpMode.SOLO,
  val pin: Int? = null
)

fun List<SoundboksConfig>.merge(): SoundboksConfig = when {
  isEmpty() -> SoundboksConfig()
  size == 1 -> single()
  else -> SoundboksConfig(
    volume = map { it.volume }.average().toFloat(),
    soundProfile = if (map { it.soundProfile }.distinct().size == 1) first().soundProfile else SoundProfile.POWER,
    channel = if (map { it.channel }.distinct().size == 1) first().channel else SoundChannel.MONO,
    teamUpMode = if (map { it.teamUpMode }.distinct().size == 1) first().teamUpMode else TeamUpMode.SOLO,
    pin = if (map { it.pin }.distinct().size == 1) first().pin else null
  )
}

enum class SoundChannel { LEFT, MONO, RIGHT }

enum class SoundProfile { BASS, POWER, INDOOR }

enum class TeamUpMode { SOLO, HOST, JOIN }
