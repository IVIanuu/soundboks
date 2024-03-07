package com.ivianuu.soundboks

import android.annotation.*
import android.bluetooth.*
import com.ivianuu.essentials.*
import com.ivianuu.essentials.coroutines.*
import com.ivianuu.essentials.logging.*
import com.ivianuu.injekt.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.*
import splitties.coroutines.*
import java.util.*
import kotlin.time.*
import kotlin.time.Duration.Companion.milliseconds

@Provide @Scoped<AppScope> class SoundboksRemote(
  private val logger: Logger,
  private val serverFactory: (String, Int?) -> SoundboksServer,
  scope: ScopedCoroutineScope<AppScope>
) {
  private val servers = scope.sharedResource<Pair<String, Int?>, SoundboksServer>(
    create = { serverFactory(it.first, it.second) },
    release = { _, server -> server.close() }
  )

  suspend fun <R> withSoundboks(
    address: String,
    pin: Int? = null,
    connectTimeout: Duration = Duration.INFINITE,
    block: suspend SoundboksServer.() -> R
  ): R? = servers.use(address to pin) { server ->
    withTimeoutOrNull(connectTimeout) {
      server.isConnected.first { it }
    } ?: return@use null

    raceOf(
      { block(server) },
      {
        server.isConnected.first { !it }
        logger.d { "${server.device.debugName()} $pin cancel with soundboks" }
      }
    ).unsafeCast()
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
  private val scope: ScopedCoroutineScope<AppScope>
) {
  val isConnected = MutableSharedFlow<Boolean>(
    replay = 1,
    extraBufferCapacity = Int.MAX_VALUE
  )

  val device = bluetoothManager.adapter.getRemoteDevice(address)

  private val writeLock = Mutex()
  private val writeLimiter = RateLimiter(1, 200.milliseconds)
  private val writeResults = EventFlow<Pair<BluetoothGattCharacteristic, Int>>()

  private val gatt: BluetoothGatt = bluetoothManager.adapter
    .getRemoteDevice(address)
    .connectGatt(
      appContext,
      true,
      object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
          super.onConnectionStateChange(gatt, status, newState)
          val isConnected = newState == BluetoothProfile.STATE_CONNECTED
          logger.d { "${device.debugName()} $pin connection state changed $isConnected $newState" }
          if (isConnected)
            gatt.discoverServices()
          else
            this@SoundboksServer.isConnected.tryEmit(false)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
          super.onServicesDiscovered(gatt, status)
          logger.d { "${device.debugName()} $pin services discovered" }
          scope.launch {
            if (pin != null) {
              logger.d { "send pin $pin" }
              writeCharacteristic(
                serviceId = UUID.fromString("F5C26570-64EC-4906-B998-6A7302879A2B"),
                characteristicId = UUID.fromString("49535343-8841-43f4-a8d4-ecbe34729bb3"),
                value = "aup${pin}".toByteArray()
              )
            }

            logger.d { "${device.debugName()} $pin ready" }
            isConnected.emit(true)
          }
        }

        override fun onCharacteristicWrite(
          gatt: BluetoothGatt,
          characteristic: BluetoothGattCharacteristic,
          status: Int
        ) {
          super.onCharacteristicWrite(gatt, characteristic, status)
          writeResults.tryEmit(characteristic to status)
        }
      },
      BluetoothDevice.TRANSPORT_LE
    )

  init {
    logger.d { "${device.debugName()} $pin init" }
  }

  suspend fun writeCharacteristic(
    serviceId: UUID,
    characteristicId: UUID,
    value: ByteArray
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

    suspend fun writeImpl(attempt: Int) {
      logger.d { "${device.debugName()} $pin send sid $serviceId cid $characteristicId -> ${value.contentToString()} attempt $attempt" }
      characteristic.value = value
      gatt.writeCharacteristic(characteristic)
      withTimeoutOrNull(200.milliseconds) {
        writeResults.first { it.first == characteristic }
      } ?: run { if (attempt < 5) writeImpl(attempt + 1) }
    }

    writeLock.withLock {
      writeLimiter.acquire()
      writeImpl(1)
    }
  }

  suspend fun close() = withContext(coroutineContexts.io) {
    logger.d { "${device.debugName()} $pin close" }
    catch { gatt.disconnect() }
    catch { gatt.close() }
  }
}

suspend fun SoundboksRemote.powerOff(address: String) = withSoundboks(address) {
  writeCharacteristic(
    UUID.fromString("445b9ffb-348f-4e1b-a417-3559b8138390"),
    UUID.fromString("11ad501d-fa86-43cc-8d92-5a27ee672f1a"),
    byteArrayOf(0)
  )
}
