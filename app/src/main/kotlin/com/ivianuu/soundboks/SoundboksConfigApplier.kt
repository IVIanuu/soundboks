package com.ivianuu.soundboks

import android.bluetooth.BluetoothDevice
import androidx.compose.runtime.*
import app.cash.molecule.*
import co.touchlab.kermit.*
import com.ivianuu.essentials.*
import com.ivianuu.essentials.app.*
import com.ivianuu.essentials.broadcast.BroadcastHandler
import com.ivianuu.essentials.compose.*
import com.ivianuu.essentials.coroutines.ScopedCoroutineScope
import com.ivianuu.essentials.data.DataStore
import com.ivianuu.injekt.Provide
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

@Provide @Eager<AppScope> class SoundboksConfigApplier(
  private val logger: Logger,
  private val repository: SoundboksRepository,
  private val prefsDataStore: DataStore<SoundboksPrefs>,
  private val remote: SoundboksRemote,
  private val scope: ScopedCoroutineScope<AppScope>,
  private val scopeManager: ScopeManager
) {
  private var broadcastJob by mutableStateOf<Job?>(null)
  @Provide val broadcastRunner
    get() = BroadcastHandler(BluetoothDevice.ACTION_ACL_CONNECTED) {
      if (scopeManager.scopeOfOrNull<AppVisibleScope>().first() != null) return@BroadcastHandler

      val device = it.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)!!
      if (!device.isSoundboks()) return@BroadcastHandler

      broadcastJob?.cancel()
      broadcastJob = scope.launch {
        logger.d { "apply configs broadcast" }
        delay(30.seconds)
        logger.d { "stop apply configs broadcast" }
        broadcastJob = null
      }
    }

  init {
    scope.launchMolecule(RecompositionMode.Immediate, {}) { Apply() }
  }

  @Composable private fun Apply() {
    if (broadcastJob == null &&
      scopeManager.scopeOfOrNull<AppVisibleScope>().collect(null) == null)
      return

    DisposableEffect(true) {
      logger.d { "apply configs" }
      onDispose { logger.d { "stop apply configs" } }
    }

    repository.soundbokses.collect(null)?.forEach { soundboks ->
      key(soundboks) {
        val config = prefsDataStore.data
          .map { it.configs[soundboks.address] ?: SoundboksConfig() }
          .collect(null)

        if (config != null) {
          @Composable fun <T> SoundboksCharacteristic(
            tag: String,
            serviceId: UUID,
            characteristicId: UUID,
            value: T,
            toByteArray: (T) -> ByteArray
          ) {
            val writeLock = remember { Mutex() }

            LaunchedEffect(config.pin, value) {
              remote.withSoundboks(soundboks.address, config.pin) {
                writeLock.withLock {
                  logger.d { "${device.debugName()} apply $tag $value" }
                  withContext(NonCancellable) {
                    writeCharacteristic(serviceId, characteristicId, toByteArray(value))
                  }
                }
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
}
