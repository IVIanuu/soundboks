package com.ivianuu.soundboks.domain

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import com.ivianuu.essentials.app.AppForegroundScope
import com.ivianuu.essentials.app.ScopeWorker
import com.ivianuu.essentials.compose.bind
import com.ivianuu.essentials.compose.launchState
import com.ivianuu.essentials.coroutines.ExitCase
import com.ivianuu.essentials.coroutines.guarantee
import com.ivianuu.essentials.data.DataStore
import com.ivianuu.essentials.lerp
import com.ivianuu.essentials.logging.Logger
import com.ivianuu.essentials.logging.invoke
import com.ivianuu.injekt.Inject
import com.ivianuu.injekt.Provide
import com.ivianuu.injekt.coroutines.NamedCoroutineScope
import com.ivianuu.soundboks.data.SoundChannel
import com.ivianuu.soundboks.data.SoundProfile
import com.ivianuu.soundboks.data.SoundboksConfig
import com.ivianuu.soundboks.data.SoundboksPrefs
import com.ivianuu.soundboks.data.TeamUpMode
import com.ivianuu.soundboks.data.debugName
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.*
import kotlin.reflect.KMutableProperty0

@Provide fun soundboksConfigApplier(
  logger: Logger,
  scope: NamedCoroutineScope<AppForegroundScope>,
  pref: DataStore<SoundboksPrefs>,
  remote: SoundboksRemote,
  repository: SoundboksRepository
) = ScopeWorker<AppForegroundScope> {
  scope.launchState(emitter = {}) {
    repository.soundbokses.bind(emptyList()).forEach { soundboks ->
      key(soundboks) {
        LaunchedEffect(true) {
          remote.withSoundboks(soundboks.address) {
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
  }
}

class Cache {
  var lastPin: Int? = null
  var lastVolume: Float? = null
  var lastSoundProfile: SoundProfile? = null
  var lastChannel: SoundChannel? = null
  var lastTeamUpMode: TeamUpMode? = null
}

suspend fun SoundboksServer.applyConfig(
  config: SoundboksConfig,
  cache: Cache,
  @Inject logger: Logger
) {
  logger { "${device.debugName()} apply config $config" }

  suspend fun <T> sendIfChanged(
    tag: String,
    property: KMutableProperty0<T?>,
    serviceId: UUID,
    characteristicId: UUID,
    value: T,
    message: ByteArray
  ) {
    if (property.get() != value) {
      logger { "${device.debugName()} apply $tag $value" }
      guarantee(
        block = {
          send(serviceId, characteristicId, message)
          property.set(value)
        },
        finalizer = {
          if (it !is ExitCase.Completed) {
            logger { "${device.debugName()} apply failed $it $tag $value" }
            property.set(null)
          }
        }
      )
    } else {
      logger { "${device.debugName()} skip $tag $value" }
    }
  }

  // reset everything on pin changes
  if (config.pin != cache.lastPin) {
    logger { "reset everything pin has changed ${config.pin}" }
    cache.lastVolume = null
    cache.lastSoundProfile = null
    cache.lastChannel = null
    cache.lastTeamUpMode = null
    cache.lastPin = config.pin
  }

  if (config.pin != null) {
    sendIfChanged(
      tag = "pin",
      property = cache::lastPin,
      serviceId = UUID.fromString("F5C26570-64EC-4906-B998-6A7302879A2B"),
      characteristicId = UUID.fromString("49535343-8841-43f4-a8d4-ecbe34729bb3"),
      value = config.pin,
      message = "aup${config.pin}".toByteArray()
    )
  } else {
    cache.lastPin = null
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
