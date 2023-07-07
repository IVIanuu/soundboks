package com.ivianuu.soundboks

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ivianuu.essentials.AppScope
import com.ivianuu.essentials.Scoped
import com.ivianuu.essentials.cast
import com.ivianuu.essentials.compose.compositionFlow
import com.ivianuu.essentials.coroutines.ScopedCoroutineScope
import com.ivianuu.essentials.coroutines.onCancel
import com.ivianuu.essentials.data.DataStore
import com.ivianuu.essentials.logging.Logger
import com.ivianuu.essentials.logging.log
import com.ivianuu.essentials.permission.PermissionManager
import com.ivianuu.essentials.util.BroadcastsFactory
import com.ivianuu.injekt.Provide
import com.ivianuu.injekt.android.SystemService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn

@Provide @Scoped<AppScope> class SoundboksRepository(
  private val bluetoothManager: @SystemService BluetoothManager,
  private val broadcastsFactory: BroadcastsFactory,
  private val logger: Logger,
  permissionManager: PermissionManager,
  private val prefsDataStore: DataStore<SoundboksPrefs>,
  private val remote: SoundboksRemote,
  scope: ScopedCoroutineScope<AppScope>
) {
  @SuppressLint("MissingPermission")
  val soundbokses: Flow<List<Soundboks>> = compositionFlow {
    if (!remember { permissionManager.permissionState(soundboksPermissionKeys) }.collectAsState(false).value)
      return@compositionFlow emptyList()

    var soundbokses by remember { mutableStateOf(emptySet<Soundboks>()) }

    soundbokses.forEach { soundboks ->
      key(soundboks.address) {
        LaunchedEffect(true) {
          prefsDataStore.data
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

    LaunchedEffect(true) {
      broadcastsFactory(
        BluetoothAdapter.ACTION_STATE_CHANGED,
        BluetoothDevice.ACTION_BOND_STATE_CHANGED,
        BluetoothDevice.ACTION_ACL_CONNECTED,
        BluetoothDevice.ACTION_ACL_DISCONNECTED
      )
        .onStart<Any?> { emit(Unit) }
        .map {
          bluetoothManager.adapter.bondedDevices
            .filter {
              it.isSoundboks() &&
                  BluetoothDevice::class.java.getDeclaredMethod("isConnected").invoke(it).cast()
            }
            .map { it.toSoundboks() }
        }
        .collect { soundbokses += it }
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
  }.shareIn(scope, SharingStarted.WhileSubscribed(), 1)
}
