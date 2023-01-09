/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.soundboks.domain

import android.Manifest
import com.ivianuu.essentials.app.AppForegroundScope
import com.ivianuu.essentials.app.ScopeWorker
import com.ivianuu.essentials.permission.PermissionRequester
import com.ivianuu.essentials.permission.runtime.RuntimePermission
import com.ivianuu.injekt.Provide
import com.ivianuu.injekt.common.typeKeyOf

@Provide class SoundboksBluetoothConnectPermission : RuntimePermission(
  permissionName = Manifest.permission.BLUETOOTH_CONNECT,
  title = "Bluetooth"
)

@Provide class SoundboksLocationConnectPermission : RuntimePermission(
  permissionName = Manifest.permission.ACCESS_FINE_LOCATION,
  title = "Location"
)

val soundboksPermissionKeys = listOf(
  typeKeyOf<SoundboksBluetoothConnectPermission>(),
  typeKeyOf<SoundboksLocationConnectPermission>()
)

// always request permissions when launching the ui
@Provide fun soundboksPermissionRequestWorker(
  permissionRequester: PermissionRequester
) = ScopeWorker<AppForegroundScope> {
  permissionRequester(soundboksPermissionKeys)
}
