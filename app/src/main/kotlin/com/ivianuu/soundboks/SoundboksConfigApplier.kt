package com.ivianuu.soundboks

import android.bluetooth.BluetoothDevice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import com.ivianuu.essentials.AppScope
import com.ivianuu.essentials.Scoped
import com.ivianuu.essentials.app.AppForegroundScope
import com.ivianuu.essentials.app.ScopeComposition
import com.ivianuu.essentials.broadcast.BroadcastHandler
import com.ivianuu.essentials.compose.launchComposition
import com.ivianuu.essentials.coroutines.ScopedCoroutineScope
import com.ivianuu.essentials.data.DataStore
import com.ivianuu.essentials.lerp
import com.ivianuu.essentials.logging.Logger
import com.ivianuu.essentials.logging.log
import com.ivianuu.injekt.Provide
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

fun interface SoundboksConfigApplier : ScopeComposition<AppForegroundScope>

@Provide fun soundboksConfigApplier(
  logger: Logger,
  pref: DataStore<SoundboksPrefs>,
  remote: SoundboksRemote,
  repository: SoundboksRepository
) = SoundboksConfigApplier {
  repository.soundbokses.collectAsState(null).value?.forEach { soundboks ->
    key(soundboks) {
      val config = remember {
        pref.data
          .map { it.configs[soundboks.address] ?: SoundboksConfig() }
      }.collectAsState(null).value

      if (config != null) {
        @Composable fun <T> SoundboksCharacteristic(
          tag: String,
          serviceId: UUID,
          characteristicId: UUID,
          value: T,
          toByteArray: (T) -> ByteArray
        ) {
          LaunchedEffect(config.pin, value) {
            remote.withSoundboks<Unit>(soundboks.address, config.pin) {
              logger.log { "${device.debugName()} apply $tag $value" }
              updateCharacteristic(serviceId, characteristicId, toByteArray(value))
            }
          }
        }

        SoundboksCharacteristic(
          tag = "volume",
          serviceId = UUID.fromString("445b9ffb-348f-4e1b-a417-3559b8138390"),
          characteristicId = UUID.fromString("7649b19f-c605-46e2-98f8-6c1808e0cfb4"),
          value = config.volume
        ) {
          byteArrayOf(
            lerp(0, 255, it)
              .let { if (it > 127) it - 256 else it }
              .toByte()
          )
        }

        SoundboksCharacteristic(
          tag = "sound profile",
          serviceId = UUID.fromString("3bbed7cf-287c-4333-9abf-2f0fbf161c79"),
          characteristicId = UUID.fromString("57a394fb-6d89-4105-8f07-bf730338a9b2"),
          value = config.soundProfile
        ) {
          when (it) {
            SoundProfile.BASS -> byteArrayOf(1)
            SoundProfile.POWER -> byteArrayOf(0)
            SoundProfile.INDOOR -> byteArrayOf(2)
          }
        }

        SoundboksCharacteristic(
          tag = "channel",
          serviceId = UUID.fromString("3bbed7cf-287c-4333-9abf-2f0fbf161c79"),
          characteristicId = UUID.fromString("7d0d651e-62ae-4ef2-a727-0e8f3e9b4dfb"),
          value = config.channel
        ) {
          when (it) {
            SoundChannel.LEFT -> byteArrayOf(1)
            SoundChannel.MONO -> byteArrayOf(0)
            SoundChannel.RIGHT -> byteArrayOf(2)
          }
        }

        SoundboksCharacteristic(
          tag = "team up mode",
          serviceId = UUID.fromString("46c69d1b-7194-46f0-837c-ab7a6b94566f"),
          characteristicId = UUID.fromString("37bffa18-7f5a-4c8d-8a2d-362866cedfad"),
          value = config.teamUpMode
        ) {
          when (it) {
            TeamUpMode.SOLO -> byteArrayOf(115, 111, 108, 111)
            TeamUpMode.HOST -> byteArrayOf(104, 111, 115, 116)
            TeamUpMode.JOIN -> byteArrayOf(106, 111, 105, 110)
          }
        }
      }
    }
  }
}

@Provide fun soundboksBroadcastHandler(
  applierFactory: () -> SoundboksConfigApplier,
  logger: Logger,
  scope: ScopedCoroutineScope<AppScope>
): @Scoped<AppScope> BroadcastHandler {
  var applierJob: Job? = null
  var cancelJob: Job? = null
  return BroadcastHandler(BluetoothDevice.ACTION_ACL_CONNECTED) {
    val device = it.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)!!
    if (!device.isSoundboks()) return@BroadcastHandler

    if (applierJob == null) {
      logger.log { "apply configs" }
      applierJob = scope.launchComposition {
        applierFactory()()
      }
    }

    cancelJob?.cancel()?.also { logger.log { "stop cancel timer" } }
    cancelJob = scope.launch {
      logger.log { "start cancel timer" }
      delay(30.seconds)
      logger.log { "stop apply configs" }
      applierJob?.cancel()
      applierJob = null
    }
  }
}
