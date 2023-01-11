package com.ivianuu.soundboks.domain

import com.ivianuu.injekt.Provide
import java.util.*

context(SoundboksRemote) @Provide class SoundboksUsecases {
  suspend fun powerOff(address: String) = withSoundboks(address) {
    send(
      UUID.fromString("445b9ffb-348f-4e1b-a417-3559b8138390"),
      UUID.fromString("11ad501d-fa86-43cc-8d92-5a27ee672f1a"),
      byteArrayOf(0)
    )
  }
}
