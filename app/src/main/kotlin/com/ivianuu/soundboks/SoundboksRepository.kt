package com.ivianuu.soundboks

import android.annotation.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.media.*
import androidx.compose.runtime.*
import app.cash.molecule.*
import com.ivianuu.essentials.*
import com.ivianuu.essentials.compose.*
import com.ivianuu.essentials.coroutines.*
import com.ivianuu.essentials.data.*
import com.ivianuu.essentials.logging.*
import com.ivianuu.essentials.permission.*
import com.ivianuu.essentials.util.*
import com.ivianuu.injekt.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.coroutines.*
import kotlin.time.Duration.Companion.seconds

@Provide @Scoped<AppScope> class SoundboksRepository(
  private val appContext: AppContext,
  private val audioManager: @SystemService AudioManager,
  private val bluetoothManager: @SystemService BluetoothManager,
  private val broadcastManager: BroadcastManager,
  coroutineScope: ScopedCoroutineScope<AppScope>,
  private val logger: Logger,
  permissionManager: PermissionManager,
  private val prefsDataStore: DataStore<SoundboksPrefs>,
  private val remote: SoundboksRemote,
) {
  @SuppressLint("MissingPermission")
  val soundbokses: Flow<List<Soundboks>> = moleculeFlow {
    if (!permissionManager.permissionState(soundboksPermissions).state(false))
      return@moleculeFlow emptyList()

    var soundbokses by remember { mutableStateOf(emptySet<Soundboks>()) }

    soundbokses.forEach { soundboks ->
      key(soundboks.address) {
        LaunchedEffect(true) {
          prefsDataStore.data
            .map { it.configs[soundboks.address]?.pin }
            .distinctUntilChanged()
            .collectLatest { pin ->
              remote.withSoundboks<Unit>(soundboks.address, pin, 30.seconds) {
                onCancel {
                  if (!isConnected.first())
                    soundbokses -= soundboks
                }
              } ?: run {
                knownSoundbokses -= soundboks
                soundbokses -= soundboks
              }
            }
        }
      }
    }

    LaunchedEffect(true) {
      broadcastManager.broadcasts(
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
      soundbokses += bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
        .filter { it.isSoundboks() }
        .map { it.toSoundboks() } +
          knownSoundbokses

      val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
          super.onScanResult(callbackType, result)
          if (result.device.isSoundboks()) {
            val soundboks = result.device.toSoundboks()
            soundbokses += soundboks
            knownSoundbokses += soundboks
          }
        }
      }

      logger.d { "start scan" }
      bluetoothManager.adapter.bluetoothLeScanner.startScan(
        emptyList(),
        ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
        callback
      )
      onDispose {
        logger.d { "stop scan" }
        bluetoothManager.adapter.bluetoothLeScanner.stopScan(callback)
      }
    }

    LaunchedEffect(soundbokses) { logger.d { "soundbokses changed $soundbokses" } }

    soundbokses.toList()
  }.shareIn(coroutineScope, SharingStarted.WhileSubscribed(0, 0), 1)

  val playingSoundboks: Flow<Soundboks?> = moleculeFlow {
    if (!permissionManager.permissionState(soundboksPermissions).state(false))
      return@moleculeFlow null

    broadcastManager.broadcasts(
      BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
      BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED,
      "android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED"
    )
      .onStart<Any?> { emit(Unit) }
      .mapLatest {
        if (!audioManager.isBluetoothA2dpOn) null
        else a2Dp.use(Unit) {
          it.javaClass.getDeclaredMethod("getActiveDevice")
            .invoke(it)
            .safeAs<BluetoothDevice?>()
            ?.let { it.toSoundboks() }
        }
      }
      .state(null)
  }

  private val a2Dp = coroutineScope.sharedResource<Unit, BluetoothA2dp>(
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
    release = { _, proxy ->
      catch {
        bluetoothManager.adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
      }
    }
  )

  companion object {
    private val knownSoundbokses = mutableSetOf<Soundboks>()
  }
}
