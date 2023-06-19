package com.ivianuu.soundboks

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import com.ivianuu.essentials.AppContext
import com.ivianuu.essentials.AppScope
import com.ivianuu.essentials.Scoped
import com.ivianuu.essentials.catch
import com.ivianuu.essentials.coroutines.RateLimiter
import com.ivianuu.essentials.coroutines.RefCountedResource
import com.ivianuu.essentials.coroutines.ScopedCoroutineScope
import com.ivianuu.essentials.coroutines.race
import com.ivianuu.essentials.coroutines.withResource
import com.ivianuu.essentials.logging.Logger
import com.ivianuu.essentials.logging.log
import com.ivianuu.essentials.time.milliseconds
import com.ivianuu.essentials.time.seconds
import com.ivianuu.essentials.util.BroadcastsFactory
import com.ivianuu.injekt.Inject
import com.ivianuu.injekt.Provide
import com.ivianuu.injekt.android.SystemService
import com.ivianuu.injekt.common.IOCoroutineContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.*

@Provide @Scoped<AppScope> class SoundboksRemote(
  private val appContext: AppContext,
  private val bluetoothManager: @SystemService BluetoothManager,
  private val broadcastsFactory: BroadcastsFactory,
  private val coroutineContext: IOCoroutineContext,
  private val logger: Logger,
  private val scope: ScopedCoroutineScope<AppScope>
) {
  private val servers = RefCountedResource<Pair<String, Int?>, SoundboksServer>(
    create = { SoundboksServer(it.first, it.second) },
    release = { _, server -> server.close() }
  )

  fun isConnected(address: String) = bondedDeviceChanges()
    .onStart<Any> { emit(Unit) }
    .map { address.isConnected() }
    .distinctUntilChanged()
    .flowOn(coroutineContext)

  private fun String.isConnected(): Boolean =
    bluetoothManager.adapter.getRemoteDevice(this)
      ?.let {
        BluetoothDevice::class.java.getDeclaredMethod("isConnected").invoke(it) as Boolean
      } ?: false

  suspend fun <R> withSoundboks(
    address: String,
    pin: Int? = null,
    block: suspend SoundboksServer.() -> R
  ): R? = withContext(coroutineContext) {
    servers.withResource(address to pin) {
      race(
        {
          it.ready.first()
          block(it)
        },
        {
          it.ready.first()
          it.connectionState.first { !it }
          logger.log { "${it.device.debugName()} $pin cancel with soundboks" }
        }
      ) as? R
    }
  }

  fun bondedDeviceChanges() = broadcastsFactory(
    BluetoothAdapter.ACTION_STATE_CHANGED,
    BluetoothDevice.ACTION_BOND_STATE_CHANGED,
    BluetoothDevice.ACTION_ACL_CONNECTED,
    BluetoothDevice.ACTION_ACL_DISCONNECTED
  )
}

@SuppressLint("MissingPermission")
class SoundboksServer(
  address: String,
  private val pin: Int? = null,
  @Inject private val appContext: AppContext,
  @Inject private val bluetoothManager: @SystemService BluetoothManager,
  @Inject private val coroutineContext: IOCoroutineContext,
  @Inject private val logger: Logger,
  @Inject private val scope: ScopedCoroutineScope<AppScope>
) {
  val connectionState = MutableSharedFlow<Boolean>(
    replay = 1,
    extraBufferCapacity = Int.MAX_VALUE,
    onBufferOverflow = BufferOverflow.SUSPEND
  )
  val ready = MutableSharedFlow<Unit>(
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
          logger.log { "${device.debugName()} $pin connection state changed $newState" }
          connectionState.tryEmit(isConnected)
          if (isConnected)
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
          super.onServicesDiscovered(gatt, status)
          logger.log { "${device.debugName()} $pin services discovered" }
          scope.launch {
            if (pin != null) {
              logger.log { "send pin $pin" }
              send(
                serviceId = UUID.fromString("F5C26570-64EC-4906-B998-6A7302879A2B"),
                characteristicId = UUID.fromString("49535343-8841-43f4-a8d4-ecbe34729bb3"),
                message = "aup${pin}".toByteArray()
              )
            }
            logger.log { "${device.debugName()} $pin ready" }
            ready.tryEmit(Unit)
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
  ) = withContext(coroutineContext) {
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

  suspend fun close() = withContext(coroutineContext) {
    logger.log { "${device.debugName()} $pin close" }
    catch { gatt.disconnect() }
    catch { gatt.close() }
  }
}
