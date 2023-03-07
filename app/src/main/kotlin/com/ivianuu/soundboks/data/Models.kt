/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.soundboks.data

import android.bluetooth.BluetoothDevice
import com.ivianuu.essentials.android.prefs.DataStoreModule
import com.ivianuu.essentials.android.prefs.PrefModule
import com.ivianuu.essentials.data.DataStore
import com.ivianuu.injekt.Provide
import kotlinx.serialization.Serializable

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
  companion object {
    @Provide val prefModule = PrefModule { SoundboksPrefs() }
  }
}

@Serializable data class SoundboksConfig(
  val volume: Float = 0.5f,
  val soundProfile: SoundProfile = SoundProfile.POWER,
  val channel: SoundChannel = SoundChannel.MONO,
  val teamUpMode: TeamUpMode = TeamUpMode.SOLO
)

fun List<SoundboksConfig>.merge(): SoundboksConfig = when {
  isEmpty() -> SoundboksConfig()
  size == 1 -> single()
  else -> SoundboksConfig(
    volume = map { it.volume }.average().toFloat(),
    soundProfile = if (map { it.soundProfile }.distinct().size == 1) first().soundProfile else SoundProfile.POWER,
    channel = if (map { it.channel }.distinct().size == 1) first().channel else SoundChannel.MONO,
    teamUpMode = if (map { it.teamUpMode }.distinct().size == 1) first().teamUpMode else TeamUpMode.SOLO
  )
}

enum class SoundChannel(val bytes: ByteArray) {
  LEFT(byteArrayOf(1)), MONO(byteArrayOf(0)), RIGHT(byteArrayOf(2))
}

enum class SoundProfile(val bytes: ByteArray) {
  BASS(byteArrayOf(1)), POWER(byteArrayOf(0)), INDOOR(byteArrayOf(2))
}

enum class TeamUpMode(val bytes: ByteArray) {
  SOLO(byteArrayOf(115, 111, 108, 111)),
  HOST(byteArrayOf(104, 111, 115, 116)),
  JOIN(byteArrayOf(106, 111, 105, 110))
}
