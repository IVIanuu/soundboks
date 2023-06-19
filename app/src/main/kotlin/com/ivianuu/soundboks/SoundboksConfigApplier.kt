package com.ivianuu.soundboks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import com.ivianuu.essentials.app.AppForegroundScope
import com.ivianuu.essentials.app.ScopeWorker
import com.ivianuu.essentials.compose.launchComposition
import com.ivianuu.essentials.coroutines.ScopedCoroutineScope
import com.ivianuu.essentials.data.DataStore
import com.ivianuu.essentials.lerp
import com.ivianuu.essentials.logging.Logger
import com.ivianuu.essentials.logging.log
import com.ivianuu.injekt.Provide
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.map
import java.util.*

@Provide fun soundboksConfigApplier(
  logger: Logger,
  scope: ScopedCoroutineScope<AppForegroundScope>,
  pref: DataStore<SoundboksPrefs>,
  remote: SoundboksRemote,
  repository: SoundboksRepository
) = ScopeWorker<AppForegroundScope> {
  scope.launchComposition {
    repository.soundbokses.collectAsState(emptyList()).value.forEach { soundboks ->
      key(soundboks) {
        val config = remember {
          pref.data
            .map { it.configs[soundboks.address] ?: SoundboksConfig() }
        }.collectAsState(null).value

        if (config != null) {
          @Composable fun <T> SoundboksCharacteristicUpdater(
            tag: String,
            serviceId: UUID,
            characteristicId: UUID,
            value: T,
            toByteArray: (T) -> ByteArray
          ) {
            LaunchedEffect(config.pin, value) {
              remote.withSoundboks<Unit>(soundboks.address, config.pin) {
                logger.log { "${device.debugName()} apply $tag $value" }

                send(
                  serviceId,
                  characteristicId,
                  toByteArray(value)
                )

                awaitCancellation()
              }
            }
          }

          SoundboksCharacteristicUpdater(
            tag = "volume",
            serviceId = UUID.fromString("445b9ffb-348f-4e1b-a417-3559b8138390"),
            characteristicId = UUID.fromString("7649b19f-c605-46e2-98f8-6c1808e0cfb4"),
            value = config.volume
          ) { soundboksVolumeBytes(it) }

          SoundboksCharacteristicUpdater(
            tag = "sound profile",
            serviceId = UUID.fromString("3bbed7cf-287c-4333-9abf-2f0fbf161c79"),
            characteristicId = UUID.fromString("57a394fb-6d89-4105-8f07-bf730338a9b2"),
            value = config.soundProfile
          ) { it.bytes }

          SoundboksCharacteristicUpdater(
            tag = "channel",
            serviceId = UUID.fromString("3bbed7cf-287c-4333-9abf-2f0fbf161c79"),
            characteristicId = UUID.fromString("7d0d651e-62ae-4ef2-a727-0e8f3e9b4dfb"),
            value = config.channel
          ) { it.bytes }

          SoundboksCharacteristicUpdater(
            tag = "team up mode",
            serviceId = UUID.fromString("46c69d1b-7194-46f0-837c-ab7a6b94566f"),
            characteristicId = UUID.fromString("37bffa18-7f5a-4c8d-8a2d-362866cedfad"),
            value = config.teamUpMode
          ) { it.bytes }
        }
      }
    }
  }
}

private fun soundboksVolumeBytes(volume: Float) = byteArrayOf(
  lerp(0, 255, volume)
    .let { if (it > 127) it - 256 else it }
    .toByte()
)
