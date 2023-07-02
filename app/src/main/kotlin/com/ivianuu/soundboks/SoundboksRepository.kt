package com.ivianuu.soundboks

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import com.ivianuu.essentials.AppScope
import com.ivianuu.essentials.Scoped
import com.ivianuu.essentials.compose.compositionStateFlow
import com.ivianuu.essentials.coroutines.ScopedCoroutineScope
import com.ivianuu.essentials.coroutines.onCancel
import com.ivianuu.essentials.data.DataStore
import com.ivianuu.essentials.data.DataStoreModule
import com.ivianuu.essentials.logging.Logger
import com.ivianuu.essentials.logging.log
import com.ivianuu.essentials.permission.PermissionManager
import com.ivianuu.essentials.ui.UiScope
import com.ivianuu.injekt.Provide
import com.ivianuu.injekt.android.SystemService
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

@Provide @Scoped<UiScope> class SoundboksRepository(
  private val bluetoothManager: @SystemService BluetoothManager,
  private val logger: Logger,
  permissionManager: PermissionManager,
  private val pref: DataStore<SoundboksPrefs>,
  private val remote: SoundboksRemote,
  scope: ScopedCoroutineScope<UiScope>
) {
  @SuppressLint("MissingPermission")
  val soundbokses: StateFlow<List<Soundboks>> = scope.compositionStateFlow {
    if (!permissionManager.permissionState(soundboksPermissionKeys).collectAsState(false).value)
      return@compositionStateFlow emptyList()

    var soundbokses by remember { mutableStateOf(emptySet<Soundboks>()) }

    soundbokses.forEach { soundboks ->
      key(soundboks.address) {
        LaunchedEffect(true) {
          pref.data
            .map { it.configs[soundboks.address]?.pin }
            .distinctUntilChanged()
            .collectLatest { pin ->
              remote.withSoundboks<Unit>(soundboks.address, pin) {
                onCancel {
                  if (!isConnected.first())
                    soundbokses = soundbokses - soundboks
                }
              }
            }
        }
      }
    }

    DisposableEffect(true) {
      soundbokses = soundbokses + bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
        .filter { it.isSoundboks() }
        .map { it.toSoundboks() }

      val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
          super.onScanResult(callbackType, result)
          if (result.device.isSoundboks())
            soundbokses = soundbokses + result.device.toSoundboks()
        }
      }

      logger.log { "start scan" }
      bluetoothManager.adapter.bluetoothLeScanner.startScan(callback)
      onDispose {
        logger.log { "stop scan" }
        bluetoothManager.adapter.bluetoothLeScanner.stopScan(callback)
      }
    }

    if (logger.isLoggingEnabled.value)
      remember(soundbokses) { logger.log { "soundbokses changed $soundbokses" } }

    soundbokses.toList()
  }
}
