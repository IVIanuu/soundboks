package com.ivianuu.soundboks

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import androidx.bluetooth.BluetoothLe
import com.ivianuu.essentials.AppScope
import com.ivianuu.essentials.Scoped
import com.ivianuu.essentials.SystemService
import com.ivianuu.essentials.coroutines.ScopedCoroutineScope
import com.ivianuu.essentials.coroutines.bracket
import com.ivianuu.essentials.coroutines.childCoroutineScope
import com.ivianuu.essentials.coroutines.guarantee
import com.ivianuu.essentials.coroutines.onCancel
import com.ivianuu.essentials.coroutines.race
import com.ivianuu.essentials.coroutines.sharedResource
import com.ivianuu.essentials.coroutines.use
import com.ivianuu.essentials.logging.Logger
import com.ivianuu.essentials.logging.log
import com.ivianuu.essentials.unsafeCast
import com.ivianuu.injekt.Provide
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

@Provide @Scoped<AppScope> class SoundboksRemote(
  private val logger: Logger,
  private val serverFactory: (String, Int?) -> SoundboksServer,
  scope: ScopedCoroutineScope<AppScope>
) {
  private val servers = scope.sharedResource<Pair<String, Int?>, SoundboksServer>(
    sharingStarted = SharingStarted.WhileSubscribed(30.minutes.inWholeMilliseconds, 0),
    create = { serverFactory(it.first, it.second) },
    release = { _, server -> server.close() }
  )

  suspend fun <R> withSoundboks(
    address: String,
    pin: Int? = null,
    block: suspend SoundboksServer.() -> R
  ): R = servers.use(address to pin) { server ->
    server.withClient {
      server.block()
    }
  }
}

@SuppressLint("MissingPermission")
@Provide class SoundboksServer(
  address: String,
  private val pin: Int? = null,
  private val bluetoothLe: BluetoothLe,
  bluetoothManager: @SystemService BluetoothManager,
  private val logger: Logger,
  appScope: ScopedCoroutineScope<AppScope>
) {
  private val scope = appScope.childCoroutineScope()

  val client = MutableSharedFlow<BluetoothLe.GattClientScope?>(
    replay = 1,
    extraBufferCapacity = Int.MAX_VALUE,
    onBufferOverflow = BufferOverflow.SUSPEND
  )
  val isConnected: Flow<Boolean> = client.map { it != null }.distinctUntilChanged()

  val device: BluetoothDevice = bluetoothManager.adapter.getRemoteDevice(address)

  init {
    logger.log { "${device.debugName()} $pin init" }
    scope.launch {
      guarantee(
        block = {
          bluetoothLe.connectGatt(androidx.bluetooth.BluetoothDevice(device)) {
            logger.log { "here is a connection" }
            if (pin != null) {
              logger.log { "send pin $pin" }
              writeCharacteristic(
                characteristic = awaitCharacteristic(
                  serviceId = UUID.fromString("F5C26570-64EC-4906-B998-6A7302879A2B"),
                  characteristicId = UUID.fromString("49535343-8841-43f4-a8d4-ecbe34729bb3"),
                ),
                value = "aup${pin}".toByteArray()
              )
            }

            logger.log { "${device.debugName()} $pin ready" }

            guarantee(
              block = {
                client.emit(this)
                awaitCancellation()
              },
              finalizer = { client.emit(null) }
            )
          }
        },
        finalizer = {
          logger.log { "oh ouch $it" }
        }
      )
    }
  }

  suspend fun <R> withClient(block: suspend BluetoothLe.GattClientScope.() -> R): R =
    client.filterNotNull().first().block()

  suspend fun close() {
    logger.log { "${device.debugName()} $pin close" }
    scope.cancel()
  }
}

suspend fun SoundboksRemote.powerOff(address: String) = withSoundboks(address) {
  withClient {
    writeCharacteristic(
      characteristic = awaitCharacteristic(
        serviceId = UUID.fromString("445b9ffb-348f-4e1b-a417-3559b8138390"),
        characteristicId = UUID.fromString("11ad501d-fa86-43cc-8d92-5a27ee672f1a")
      ),
      value = byteArrayOf(0)
    )
  }
}
