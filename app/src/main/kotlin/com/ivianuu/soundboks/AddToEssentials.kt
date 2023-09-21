package com.ivianuu.soundboks

import androidx.bluetooth.BluetoothLe
import androidx.bluetooth.GattCharacteristic
import androidx.bluetooth.GattService
import com.ivianuu.essentials.AppContext
import com.ivianuu.essentials.logging.log
import com.ivianuu.injekt.Provide
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import java.util.UUID
import com.ivianuu.essentials.result.Result
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

@Provide fun bluetoothLe(appContext: AppContext): BluetoothLe = BluetoothLe(appContext)

suspend fun BluetoothLe.GattClientScope.awaitService(serviceId: UUID): GattService = servicesFlow
  .mapNotNull { services ->
    services
      .firstOrNull { it.uuid == serviceId }
  }
  .first()

suspend fun BluetoothLe.GattClientScope.awaitCharacteristic(
  serviceId: UUID,
  characteristicId: UUID
): GattCharacteristic = awaitService(serviceId).getCharacteristic(characteristicId)!!
