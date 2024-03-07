/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.soundboks

import android.Manifest
import com.ivianuu.essentials.app.*
import com.ivianuu.essentials.permission.*
import com.ivianuu.injekt.*

@Provide class SoundboksBluetoothConnectPermission : RuntimePermission(
  permissionName = Manifest.permission.BLUETOOTH_CONNECT,
  title = "Bluetooth connect"
)

@Provide class SoundboksBluetoothScanPermission : RuntimePermission(
  permissionName = Manifest.permission.BLUETOOTH_SCAN,
  title = "Bluetooth scan"
)

@Provide class SoundboksLocationPermission : RuntimePermission(
  permissionName = Manifest.permission.ACCESS_FINE_LOCATION,
  title = "Location"
)

val soundboksPermissions = listOf(
  SoundboksBluetoothConnectPermission::class,
  SoundboksBluetoothScanPermission::class,
  SoundboksLocationPermission::class
)

@Provide fun soundboksPermissionRequestWorker(
  permissionManager: PermissionManager
) = ScopeWorker<AppVisibleScope> {
  permissionManager.requestPermissions(soundboksPermissions)
}
