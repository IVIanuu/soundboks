package com.ivianuu.soundboks.domain

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import com.ivianuu.essentials.AppScope
import com.ivianuu.essentials.coroutines.combine
import com.ivianuu.essentials.coroutines.onCancel
import com.ivianuu.essentials.logging.Logger
import com.ivianuu.essentials.logging.invoke
import com.ivianuu.essentials.permission.PermissionManager
import com.ivianuu.injekt.Provide
import com.ivianuu.injekt.android.SystemService
import com.ivianuu.injekt.common.Scoped
import com.ivianuu.injekt.coroutines.IOContext
import com.ivianuu.injekt.coroutines.NamedCoroutineScope
import com.ivianuu.soundboks.data.Soundboks
import com.ivianuu.soundboks.data.debugName
import com.ivianuu.soundboks.data.isSoundboks
import com.ivianuu.soundboks.data.toSoundboks
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Provide @Scoped<AppScope> class SoundboksRepository(
  private val bluetoothManager: @SystemService BluetoothManager,
  private val context: IOContext,
  private val logger: Logger,
  private val permissionManager: PermissionManager,
  private val remote: SoundboksRemote,
  private val scope: NamedCoroutineScope<AppScope>
) {
  val soundbokses: Flow<List<Soundboks>> = permissionManager.permissionState(soundboksPermissionKeys)
    .flatMapLatest {
      if (!it) flowOf(emptyList())
      else combine(
        bleSoundbokses(),
        bondedSoundbokses()
      )
        .map {
          (it.a + it.b)
            .distinctBy { it.address }
            .sortedBy { it.name }
        }
        .onStart { emit(emptyList()) }
    }
    .flowOn(context)
    .shareIn(scope, SharingStarted.WhileSubscribed(2000), 1)
    .distinctUntilChanged()

  private val foundSoundbokses = mutableSetOf<Soundboks>()
  private val soundboksLock = Mutex()

  @SuppressLint("MissingPermission")
  private fun bleSoundbokses(): Flow<List<Soundboks>> = callbackFlow<List<Soundboks>> {
    val soundbokses = mutableListOf<Soundboks>()

    fun handleSoundboks(soundboks: Soundboks, isConnected: Boolean) {
      launch {
        suspend fun add() {
          logger { "${soundboks.debugName()} add soundboks" }
          soundbokses += soundboks
          trySend(soundbokses.toList())
        }

        soundboksLock.withLock {
          foundSoundbokses += soundboks
          if (soundbokses.any { it.address == soundboks.address })
            return@launch
          else if (isConnected)
            add()
        }

        remote.withSoundboks(soundboks.address) {
          onCancel(block = {
            if (!isConnected) add()
            awaitCancellation()
          }) {
            logger { "${soundboks.debugName()} remove soundboks" }
            soundboksLock.withLock {
              soundbokses.removeAll { it.address == soundboks.address }
              trySend(soundbokses.toList())
            }
          }
        }
      }
    }

    bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
      .filter { it.isSoundboks() }
      .forEach { handleSoundboks(it.toSoundboks(), true) }

    foundSoundbokses.forEach { handleSoundboks(it, false) }

    val callback = object : ScanCallback() {
      override fun onScanResult(callbackType: Int, result: ScanResult) {
        super.onScanResult(callbackType, result)
        if (result.device.isSoundboks())
          handleSoundboks(result.device.toSoundboks(), true)
      }
    }

    logger { "start scan" }
    bluetoothManager.adapter.bluetoothLeScanner.startScan(callback)
    awaitClose {
      logger { "stop scan" }
      bluetoothManager.adapter.bluetoothLeScanner.stopScan(callback)
    }
  }

  @SuppressLint("MissingPermission")
  private fun bondedSoundbokses(): Flow<List<Soundboks>> = remote.bondedDeviceChanges()
    .onStart<Any> { emit(Unit) }
    .map {
      bluetoothManager.adapter?.bondedDevices
        ?.filter { it.isSoundboks() }
        ?.map { it.toSoundboks() }
        ?: emptyList()
    }
    .flatMapLatest { soundbokses ->
      if (soundbokses.isEmpty()) flowOf(emptyList())
      else combine(
        soundbokses
          .map { soundboks ->
            remote.isConnected(soundboks.address)
              .map { soundboks to it }
          }
      )
        .map {
          it
            .filter { it.second }
            .map { it.first }
        }
    }
}
