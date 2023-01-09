package com.ivianuu.soundboks.domain

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import com.ivianuu.essentials.AppScope
import com.ivianuu.essentials.permission.PermissionStateFactory
import com.ivianuu.injekt.Provide
import com.ivianuu.injekt.android.SystemService
import com.ivianuu.injekt.common.Scoped
import com.ivianuu.injekt.coroutines.IOContext
import com.ivianuu.soundboks.data.Soundboks
import com.ivianuu.soundboks.data.isSoundboks
import com.ivianuu.soundboks.data.toSoundboks
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

@Provide @Scoped<AppScope> class SoundboksRepository(
  private val bluetoothManager: @SystemService BluetoothManager,
  private val context: IOContext,
  private val permissionStateFactory: PermissionStateFactory,
  private val remote: SoundboksRemote
) {
  val soundbokses: Flow<List<Soundboks>>
    @SuppressLint("MissingPermission")
    get() = permissionStateFactory(soundboksPermissionKeys)
      .flatMapLatest {
        if (!it) flowOf(emptyList())
        else remote.bondedDeviceChanges()
          .onStart<Any> { emit(Unit) }
          .map {
            bluetoothManager.adapter?.bondedDevices
              ?.filter { it.isSoundboks() }
              ?.map { it.toSoundboks() }
              ?: emptyList()
          }
          .distinctUntilChanged()
          .flowOn(context)
      }
}
