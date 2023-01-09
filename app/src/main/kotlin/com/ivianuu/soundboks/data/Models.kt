/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.soundboks.data

import android.bluetooth.BluetoothDevice
import com.ivianuu.essentials.android.prefs.DataStoreModule
import com.ivianuu.essentials.lerp
import com.ivianuu.injekt.Provide
import kotlinx.serialization.Serializable
import java.util.*

data class Soundboks(val address: String, val name: String)

fun BluetoothDevice.toSoundboks() = Soundboks(address, alias ?: name)

fun BluetoothDevice.isSoundboks() = (alias ?: name).let {
  it.startsWith("#", ignoreCase = true)
}

fun BluetoothDevice.debugName() = "[${alias ?: name} ~ $address]"

fun Soundboks.debugName() = "[$name ~ $address]"

@Serializable data class SoundboksPrefs(
  val configs: Map<String, SoundboksConfig> = emptyMap(),
  val selectedSoundbokses: Set<String> = emptySet()
) {
  companion object {
    @Provide val prefModule = DataStoreModule("soundboks_prefs") { SoundboksPrefs() }
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

data class SoundboksState(val isConnected: Boolean = false)

val SOUNDBOKS_CHANNEL_ID = UUID.fromString("7d0d651e-62ae-4ef2-a727-0e8f3e9b4dfb")

val SOUNDBOKS_CHANNEL_SERVICE_ID = UUID.fromString("3bbed7cf-287c-4333-9abf-2f0fbf161c79")

enum class SoundChannel(val bytes: ByteArray) {
  LEFT(byteArrayOf(1)), MONO(byteArrayOf(0)), RIGHT(byteArrayOf(2))
}

val SOUNDBOKS_SOUND_PROFILE_SERVICE_ID = UUID.fromString("3bbed7cf-287c-4333-9abf-2f0fbf161c79")
val SOUNDBOKS_SOUND_PROFILE_ID = UUID.fromString("57a394fb-6d89-4105-8f07-bf730338a9b2")

enum class SoundProfile(val bytes: ByteArray) {
  BASS(byteArrayOf(1)), POWER(byteArrayOf(0)), INDOOR(byteArrayOf(2))
}

val SOUNDBOKS_TEAM_UP_MODE_SERVICE_ID = UUID.fromString("46c69d1b-7194-46f0-837c-ab7a6b94566f")
val SOUNDBOKS_TEAM_UP_MODE_ID = UUID.fromString("37bffa18-7f5a-4c8d-8a2d-362866cedfad")

enum class TeamUpMode(val bytes: ByteArray) {
  SOLO(byteArrayOf(115, 111, 108, 111)),
  HOST(byteArrayOf(104, 111, 115, 116)),
  JOIN(byteArrayOf(106, 111, 105, 110))
}

val SOUNDBOKS_VOLUME_SERVICE_ID = UUID.fromString("445b9ffb-348f-4e1b-a417-3559b8138390")
val SOUNDBOKS_VOLUME_ID = UUID.fromString("7649b19f-c605-46e2-98f8-6c1808e0cfb4")

fun soundboksVolumeBytes(volume: Float) = byteArrayOf(
  lerp(0, 255, volume)
    .let { if (it > 127) it - 256 else it }
    .toByte()
)
