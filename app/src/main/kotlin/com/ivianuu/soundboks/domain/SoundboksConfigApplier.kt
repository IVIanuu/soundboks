package com.ivianuu.soundboks.domain

import com.ivianuu.essentials.app.AppForegroundScope
import com.ivianuu.essentials.app.ScopeWorker
import com.ivianuu.essentials.coroutines.parForEach
import com.ivianuu.essentials.lerp
import com.ivianuu.essentials.logging.Logger
import com.ivianuu.essentials.logging.log
import com.ivianuu.injekt.Provide
import com.ivianuu.soundboks.data.SoundChannel
import com.ivianuu.soundboks.data.SoundProfile
import com.ivianuu.soundboks.data.SoundboksConfig
import com.ivianuu.soundboks.data.SoundboksPrefsContext
import com.ivianuu.soundboks.data.TeamUpMode
import com.ivianuu.soundboks.data.debugName
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*
import kotlin.reflect.KMutableProperty0
import kotlin.reflect.KMutableProperty1

context(Logger, SoundboksRemote, SoundboksPrefsContext, SoundboksRepository)
    @Provide fun soundboksConfigApplier() = ScopeWorker<AppForegroundScope> {
  soundbokses.collectLatest { soundbokses ->
    soundbokses.parForEach { soundboks ->
      withSoundboks(soundboks.address) {
        val cache = Cache()
        pref.data
          .map { it.configs[soundboks.address] ?: SoundboksConfig() }
          .distinctUntilChanged()
          .collectLatest { config ->
            applyConfig(config, cache)
          }
      }
    }
  }
}

class Cache {
  var lastVolume: Float? = null
  var lastSoundProfile: SoundProfile? = null
  var lastChannel: SoundChannel? = null
  var lastTeamUpMode: TeamUpMode? = null
}

context(Logger, SoundboksServer) suspend fun applyConfig(config: SoundboksConfig, cache: Cache) {
  suspend fun <T> sendIfChanged(
    tag: String,
    property: KMutableProperty0<T>,
    serviceId: UUID,
    characteristicId: UUID,
    value: T,
    message: ByteArray
  ) {
    if (property.get() != value) {
      log { "${device.debugName()} apply $tag $value" }
      send(serviceId, characteristicId, message)
      property.set(value)
    }
  }

  sendIfChanged(
    tag = "volume",
    property = cache::lastVolume,
    serviceId = UUID.fromString("445b9ffb-348f-4e1b-a417-3559b8138390"),
    characteristicId = UUID.fromString("7649b19f-c605-46e2-98f8-6c1808e0cfb4"),
    value = config.volume,
    message = soundboksVolumeBytes(config.volume)
  )

  sendIfChanged(
    tag = "sound profile",
    property = cache::lastSoundProfile,
    serviceId = UUID.fromString("3bbed7cf-287c-4333-9abf-2f0fbf161c79"),
    characteristicId = UUID.fromString("57a394fb-6d89-4105-8f07-bf730338a9b2"),
    value = config.soundProfile,
    message = config.soundProfile.bytes
  )

  sendIfChanged(
    tag = "channel",
    property = cache::lastChannel,
    serviceId = UUID.fromString("3bbed7cf-287c-4333-9abf-2f0fbf161c79"),
    characteristicId = UUID.fromString("7d0d651e-62ae-4ef2-a727-0e8f3e9b4dfb"),
    value = config.channel,
    message = config.channel.bytes
  )

  sendIfChanged(
    tag = "team up mode",
    property = cache::lastTeamUpMode,
    serviceId = UUID.fromString("46c69d1b-7194-46f0-837c-ab7a6b94566f"),
    characteristicId = UUID.fromString("37bffa18-7f5a-4c8d-8a2d-362866cedfad"),
    value = config.teamUpMode,
    message = config.teamUpMode.bytes
  )
}

private fun soundboksVolumeBytes(volume: Float) = byteArrayOf(
  lerp(0, 255, volume)
    .let { if (it > 127) it - 256 else it }
    .toByte()
)
