package com.ivianuu.soundboks.domain

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import com.ivianuu.essentials.AppScope
import com.ivianuu.essentials.coroutines.combine
import com.ivianuu.essentials.coroutines.onCancel
import com.ivianuu.essentials.logging.Logger
import com.ivianuu.essentials.logging.log
import com.ivianuu.essentials.permission.PermissionStateFactory
import com.ivianuu.injekt.Provide
import com.ivianuu.injekt.android.SystemService
import com.ivianuu.injekt.common.Scoped
import com.ivianuu.injekt.coroutines.IOContext
import com.ivianuu.injekt.coroutines.NamedCoroutineScope
import com.ivianuu.soundboks.data.Soundboks
import com.ivianuu.soundboks.data.debugName
import com.ivianuu.soundboks.data.isSoundboks
import com.ivianuu.soundboks.data.toSoundboks
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
  permissionStateFactory: PermissionStateFactory,
  private val remote: SoundboksRemote,
  scope: NamedCoroutineScope<AppScope>
) {
  val soundbokses: Flow<List<Soundboks>> = permissionStateFactory(soundboksPermissionKeys)
    .flatMapLatest {
      if (!it) flowOf(emptyList())
      else combine(
        bleSoundbokses(),
        bondedSoundbokses()
      ).map {
        (it.a + it.b)
          .distinctBy { it.address }
          .sortedBy { it.name }
      }
    }
    .shareIn(scope, SharingStarted.WhileSubscribed(), 1)

  @SuppressLint("MissingPermission")
  private fun bleSoundbokses(): Flow<List<Soundboks>> = callbackFlow {
    val lock = Mutex()

    val soundbokses = mutableListOf<Soundboks>()
    trySend(emptyList())

    fun handleSoundboks(soundboks: Soundboks) {
      launch {
        lock.withLock {
          if (soundbokses.any { it.address == soundboks.address })
            return@launch
        }

        remote.withSoundboks<Unit>(soundboks.address) {
          log { "${soundboks.debugName()} add soundboks" }
          lock.withLock {
            soundbokses += soundboks
            trySend(soundbokses.toList())
          }

          onCancel {
            log { "${soundboks.debugName()} remove soundboks" }
            lock.withLock {
              soundbokses.removeAll { it.address == soundboks.address }
              trySend(soundbokses.toList())
            }
          }
        }
      }
    }

    val callback = object : ScanCallback() {
      override fun onScanResult(callbackType: Int, result: ScanResult) {
        super.onScanResult(callbackType, result)
        if (result.device.name?.startsWith("#") == true)
          handleSoundboks(result.device.toSoundboks())
      }
    }

    log { "start scan" }
    bluetoothManager.adapter.bluetoothLeScanner.startScan(callback)
    awaitClose {
      log { "stop scan" }
      bluetoothManager.adapter.bluetoothLeScanner.stopScan(callback)
    }
  }

  @SuppressLint("MissingPermission")
  private fun bondedSoundbokses() = remote.bondedDeviceChanges()
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
    .distinctUntilChanged()
    .flowOn(context)
}
