package com.ivianuu.soundboks

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import com.ivianuu.essentials.AppContext
import com.ivianuu.essentials.Scoped
import com.ivianuu.essentials.coroutines.CoroutineContexts
import com.ivianuu.essentials.coroutines.RateLimiter
import com.ivianuu.essentials.coroutines.RefCountedResource
import com.ivianuu.essentials.coroutines.ScopedCoroutineScope
import com.ivianuu.essentials.coroutines.race
import com.ivianuu.essentials.coroutines.withResource
import com.ivianuu.essentials.logging.Logger
import com.ivianuu.essentials.logging.log
import com.ivianuu.essentials.result.catch
import com.ivianuu.essentials.time.milliseconds
import com.ivianuu.essentials.ui.UiScope
import com.ivianuu.injekt.Inject
import com.ivianuu.injekt.Provide
import com.ivianuu.injekt.android.SystemService
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.*

@Provide @Scoped<UiScope> class SoundboksRemote(
  private val logger: Logger,
  private val serverFactory: (String, Int?) -> SoundboksServer
) {
  private val servers = RefCountedResource<Pair<String, Int?>, SoundboksServer>(
    create = { serverFactory(it.first, it.second) },
    release = { _, server -> server.close() }
  )

  suspend fun <R> withSoundboks(
    address: String,
    pin: Int? = null,
    block: suspend SoundboksServer.() -> R
  ): R? = servers.withResource(address to pin) {
    race(
      {
        it.isConnected.first()
        block(it)
      },
      {
        it.isConnected.first { it }
        it.isConnected.first { !it }
        logger.log { "${it.device.debugName()} $pin cancel with soundboks" }
      }
    ) as? R
  }
}

@SuppressLint("MissingPermission")
@Provide class SoundboksServer(
  address: String,
  private val pin: Int? = null,
  appContext: AppContext,
  bluetoothManager: @SystemService BluetoothManager,
  private val coroutineContexts: CoroutineContexts,
  private val logger: Logger,
  private val scope: ScopedCoroutineScope<UiScope>
) {
  val isConnected = MutableSharedFlow<Boolean>(
    replay = 1,
    extraBufferCapacity = Int.MAX_VALUE,
    onBufferOverflow = BufferOverflow.SUSPEND
  )

  val device = bluetoothManager.adapter.getRemoteDevice(address)

  private val sendLock = Mutex()
  private val sendLimiter = RateLimiter(1, 300.milliseconds)

  private val gatt: BluetoothGatt = bluetoothManager.adapter
    .getRemoteDevice(address)
    .connectGatt(
      appContext,
      true,
      object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
          super.onConnectionStateChange(gatt, status, newState)
          val isConnected = newState == BluetoothProfile.STATE_CONNECTED
          logger.log { "${device.debugName()} $pin connection state changed $isConnected $newState" }
          if (isConnected)
            gatt.discoverServices()
          else
            this@SoundboksServer.isConnected.tryEmit(false)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
          super.onServicesDiscovered(gatt, status)
          logger.log { "${device.debugName()} $pin services discovered" }
          scope.launch {
            if (pin != null) {
              sendLimiter.acquire()
              logger.log { "send pin $pin" }
              send(
                serviceId = UUID.fromString("F5C26570-64EC-4906-B998-6A7302879A2B"),
                characteristicId = UUID.fromString("49535343-8841-43f4-a8d4-ecbe34729bb3"),
                message = "aup${pin}".toByteArray()
              )
            }

            sendLimiter.acquire()

            logger.log { "${device.debugName()} $pin ready" }
            isConnected.tryEmit(true)
          }
        }
      },
      BluetoothDevice.TRANSPORT_LE
    )

  init {
    logger.log { "${device.debugName()} $pin init" }
  }

  suspend fun send(
    serviceId: UUID,
    characteristicId: UUID,
    message: ByteArray
  ) = withContext(coroutineContexts.io) {
    val service = gatt.getService(serviceId) ?: error(
      "${device.debugName()} $pin service not found $serviceId $characteristicId ${
        gatt.services.map {
          it.uuid
        }
      }"
    )
    val characteristic = service.getCharacteristic(characteristicId)
      ?: error("${device.debugName()} characteristic not found $serviceId $characteristicId")
    sendLock.withLock {
      logger.log { "${device.debugName()} $pin send sid $serviceId cid $characteristicId -> ${message.contentToString()}" }
      characteristic.value = message
      sendLimiter.acquire()
      gatt.writeCharacteristic(characteristic)
    }
  }

  suspend fun close() = withContext(coroutineContexts.io) {
    logger.log { "${device.debugName()} $pin close" }
    catch { gatt.disconnect() }
    catch { gatt.close() }
  }
}
