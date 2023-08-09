package com.ivianuu.soundboks

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.media.AudioManager
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ivianuu.essentials.AppContext
import com.ivianuu.essentials.AppScope
import com.ivianuu.essentials.Scoped
import com.ivianuu.essentials.SystemService
import com.ivianuu.essentials.cast
import com.ivianuu.essentials.compose.compositionFlow
import com.ivianuu.essentials.coroutines.ScopedCoroutineScope
import com.ivianuu.essentials.coroutines.onCancel
import com.ivianuu.essentials.coroutines.sharedResource
import com.ivianuu.essentials.coroutines.use
import com.ivianuu.essentials.data.DataStore
import com.ivianuu.essentials.logging.Logger
import com.ivianuu.essentials.logging.log
import com.ivianuu.essentials.permission.PermissionManager
import com.ivianuu.essentials.result.catch
import com.ivianuu.essentials.safeAs
import com.ivianuu.essentials.util.BroadcastsFactory
import com.ivianuu.injekt.Provide
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@Provide @Scoped<AppScope> class SoundboksRepository(
  private val appContext: AppContext,
  private val audioManager: @SystemService AudioManager,
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
    if (!remember { permissionManager.permissionState(soundboksPermissions) }.collectAsState(false).value)
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
      LaunchedEffect(soundbokses) { logger.log { "soundbokses changed $soundbokses" } }

    soundbokses.toList()
  }.shareIn(scope, SharingStarted.WhileSubscribed(0, 0), 1)

  val playingSoundboks: Flow<Soundboks?> = compositionFlow {
    if (!remember { permissionManager.permissionState(soundboksPermissions) }.collectAsState(false).value)
      return@compositionFlow null

    produceState<Soundboks?>(null) {
      broadcastsFactory(
        BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
        BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED,
        "android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED"
      )
        .onStart<Any?> { emit(Unit) }
        .mapLatest {
          if (!audioManager.isBluetoothA2dpOn) null
          else a2Dp.use {
            it.javaClass.getDeclaredMethod("getActiveDevice")
              .invoke(it)
              .safeAs<BluetoothDevice?>()
              ?.let { it.toSoundboks() }
          }
        }
        .collect { value = it }
    }.value
  }

  private val a2Dp = scope.sharedResource<BluetoothA2dp>(
    sharingStarted = SharingStarted.WhileSubscribed(1000, 0),
    create = {
      suspendCancellableCoroutine { cont ->
        bluetoothManager.adapter.getProfileProxy(
          appContext,
          object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
              catch { cont.resume(proxy.cast()) }
            }

            override fun onServiceDisconnected(profile: Int) {
            }
          },
          BluetoothProfile.A2DP
        )
      }
    },
    release = { proxy ->
      catch {
        bluetoothManager.adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
      }
    }
  )
}
