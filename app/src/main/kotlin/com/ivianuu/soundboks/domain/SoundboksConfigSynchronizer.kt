package com.ivianuu.soundboks.domain

import com.ivianuu.essentials.app.AppForegroundScope
import com.ivianuu.essentials.app.ScopeWorker
import com.ivianuu.essentials.coroutines.parForEach
import com.ivianuu.essentials.data.DataStore
import com.ivianuu.essentials.lerp
import com.ivianuu.essentials.logging.Logger
import com.ivianuu.essentials.logging.log
import com.ivianuu.injekt.Inject
import com.ivianuu.injekt.Provide
import com.ivianuu.soundboks.data.SoundboksConfig
import com.ivianuu.soundboks.data.SoundboksPrefs
import com.ivianuu.soundboks.data.debugName
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.*

context(Logger, SoundboksRemote, SoundboksRepository) @Provide fun soundboksConfigApplier(
  pref: DataStore<SoundboksPrefs>
) = ScopeWorker<AppForegroundScope> {
  soundbokses.collectLatest { soundbokses ->
    soundbokses.parForEach { soundboks ->
      withSoundboks(soundboks.address) {
        pref.data
          .map { it.configs[soundboks.address] ?: SoundboksConfig() }
          .distinctUntilChanged()
          .collectLatest { config ->
            applyConfig(config)
          }
      }
    }
  }
}

context(Logger) private suspend fun SoundboksServer.applyConfig(config: SoundboksConfig) {
  log { "${device.debugName()} -> apply config $config" }

  send(
    UUID.fromString("445b9ffb-348f-4e1b-a417-3559b8138390"),
    UUID.fromString("7649b19f-c605-46e2-98f8-6c1808e0cfb4"),
    soundboksVolumeBytes(config.volume)
  )

  send(
    UUID.fromString("3bbed7cf-287c-4333-9abf-2f0fbf161c79"),
    UUID.fromString("57a394fb-6d89-4105-8f07-bf730338a9b2"),
    config.soundProfile.bytes
  )

  send(
    UUID.fromString("3bbed7cf-287c-4333-9abf-2f0fbf161c79"),
    UUID.fromString("7d0d651e-62ae-4ef2-a727-0e8f3e9b4dfb"),
    config.channel.bytes
  )

  send(
    UUID.fromString("46c69d1b-7194-46f0-837c-ab7a6b94566f"),
    UUID.fromString("37bffa18-7f5a-4c8d-8a2d-362866cedfad"),
    config.teamUpMode.bytes
  )
}

private fun soundboksVolumeBytes(volume: Float) = byteArrayOf(
  lerp(0, 255, volume)
    .let { if (it > 127) it - 256 else it }
    .toByte()
)
