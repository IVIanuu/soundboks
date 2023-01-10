package com.ivianuu.soundboks.domain

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import com.ivianuu.essentials.AppContext
import com.ivianuu.essentials.AppScope
import com.ivianuu.essentials.catch
import com.ivianuu.essentials.coroutines.RateLimiter
import com.ivianuu.essentials.coroutines.RefCountedResource
import com.ivianuu.essentials.coroutines.withResource
import com.ivianuu.essentials.logging.Logger
import com.ivianuu.essentials.logging.log
import com.ivianuu.essentials.time.milliseconds
import com.ivianuu.essentials.time.seconds
import com.ivianuu.essentials.util.BroadcastsFactory
import com.ivianuu.injekt.Provide
import com.ivianuu.injekt.android.SystemService
import com.ivianuu.injekt.common.Scoped
import com.ivianuu.injekt.coroutines.IOContext
import com.ivianuu.injekt.coroutines.NamedCoroutineScope
import com.ivianuu.soundboks.data.debugName
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.*

@Provide @Scoped<AppScope> class SoundboksRemote(
  private val appContext: AppContext,
  private val bluetoothManager: @SystemService BluetoothManager,
  private val broadcastsFactory: BroadcastsFactory,
  private val context: IOContext,
  private val logger: Logger,
  scope: NamedCoroutineScope<AppScope>
) {
  private val servers = RefCountedResource<String, SoundboksServer>(
    scope = scope,
    timeout = 5.seconds,
    create = { SoundboksServer(it, context, logger, appContext, bluetoothManager) },
    release = { _, server -> server.close() }
  )

  fun isConnected(address: String) = bondedDeviceChanges()
    .onStart<Any> { emit(Unit) }
    .map { address.isConnected() }
    .distinctUntilChanged()
    .flowOn(context)

  private fun String.isConnected(): Boolean =
    bluetoothManager.adapter.getRemoteDevice(this)
      ?.let {
        BluetoothDevice::class.java.getDeclaredMethod("isConnected").invoke(it) as Boolean
      } ?: false

  suspend fun <R> withSoundboks(
    address: String,
    block: suspend SoundboksServer.() -> R
  ): R? = withContext(context) {
    if (!address.isConnected()) null
    else servers.withResource(address, block)
  }

  fun bondedDeviceChanges() = broadcastsFactory(
    BluetoothAdapter.ACTION_STATE_CHANGED,
    BluetoothDevice.ACTION_BOND_STATE_CHANGED,
    BluetoothDevice.ACTION_ACL_CONNECTED,
    BluetoothDevice.ACTION_ACL_DISCONNECTED
  )
}

private val sendLimiter = RateLimiter(1, 100.milliseconds)

@SuppressLint("MissingPermission")
class SoundboksServer(
  address: String,
  private val context: IOContext,
  @Provide private val logger: Logger,
  appContext: AppContext,
  bluetoothManager: BluetoothManager
) {
  private val connectionState = MutableStateFlow(false)
  private val serviceChanges = MutableSharedFlow<Unit>(
    replay = 1,
    extraBufferCapacity = Int.MAX_VALUE,
    onBufferOverflow = BufferOverflow.SUSPEND
  )

  val device = bluetoothManager.adapter.getRemoteDevice(address)

  private val sendLock = Mutex()

  private val gatt = bluetoothManager.adapter
    .getRemoteDevice(address)
    .connectGatt(
      appContext,
      true,
      object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
          super.onConnectionStateChange(gatt, status, newState)
          log { "${device.debugName()} connection state changed $newState" }
          connectionState.value = newState == BluetoothProfile.STATE_CONNECTED
          if (connectionState.value)
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
          super.onServicesDiscovered(gatt, status)
          log { "${device.debugName()} services discovered" }
          serviceChanges.tryEmit(Unit)
        }
      },
      BluetoothDevice.TRANSPORT_LE
    )

  init {
    log { "${device.debugName()} init" }
  }

  suspend fun send(
    serviceId: UUID,
    characteristicId: UUID,
    message: ByteArray
  ) = withContext(context) {
    serviceChanges.first()
    val service =
      gatt.getService(serviceId) ?: error(
        "${device.debugName()} service not found $serviceId $characteristicId ${
          gatt.services.map {
            it.uuid
          }
        }"
      )
    val characteristic = service.getCharacteristic(characteristicId)
      ?: error("${device.debugName()} characteristic not found $serviceId $characteristicId")
    sendLock.withLock {
      if (!message.contentEquals(characteristic.value)) {
        log { "${device.debugName()} send sid $serviceId cid $characteristicId -> ${message.contentToString()}" }
        characteristic.value = message
        sendLimiter.acquire()
        gatt.writeCharacteristic(characteristic)
      }
    }
  }

  suspend fun close() = withContext(context) {
    log { "${device.debugName()} close" }
    catch { gatt.disconnect() }
    catch { gatt.close() }
  }
}
