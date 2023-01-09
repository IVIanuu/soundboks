package com.ivianuu.soundboks.domain

import com.ivianuu.essentials.app.AppForegroundScope
import com.ivianuu.essentials.app.ScopeWorker
import com.ivianuu.essentials.coroutines.parForEach
import com.ivianuu.essentials.data.DataStore
import com.ivianuu.essentials.logging.Logger
import com.ivianuu.essentials.logging.log
import com.ivianuu.injekt.Inject
import com.ivianuu.injekt.Provide
import com.ivianuu.soundboks.data.SOUNDBOKS_CHANNEL_ID
import com.ivianuu.soundboks.data.SOUNDBOKS_CHANNEL_SERVICE_ID
import com.ivianuu.soundboks.data.SOUNDBOKS_SOUND_PROFILE_ID
import com.ivianuu.soundboks.data.SOUNDBOKS_SOUND_PROFILE_SERVICE_ID
import com.ivianuu.soundboks.data.SOUNDBOKS_TEAM_UP_MODE_ID
import com.ivianuu.soundboks.data.SOUNDBOKS_TEAM_UP_MODE_SERVICE_ID
import com.ivianuu.soundboks.data.SOUNDBOKS_VOLUME_ID
import com.ivianuu.soundboks.data.SOUNDBOKS_VOLUME_SERVICE_ID
import com.ivianuu.soundboks.data.SoundboksConfig
import com.ivianuu.soundboks.data.SoundboksPrefs
import com.ivianuu.soundboks.data.debugName
import com.ivianuu.soundboks.data.soundboksVolumeBytes
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.mapLatest

@Provide fun soundboksConfigSynchronizer(
  logger: Logger,
  pref: DataStore<SoundboksPrefs>,
  remote: SoundboksRemote,
  repository: SoundboksRepository
) = ScopeWorker<AppForegroundScope> {
  repository.soundbokses.collectLatest { soundbokses ->
    soundbokses.parForEach { soundboks ->
      pref.data
        .mapLatest { it.configs[soundboks.address] ?: SoundboksConfig() }
        .collectLatest { config ->
          remote.withSoundboks(soundboks.address) { applyConfig(config) }
        }
    }
  }
}

private suspend fun SoundboksServer.applyConfig(
  config: SoundboksConfig,
  @Inject logger: Logger
) {
  log { "${device.debugName()} -> apply config $config" }

  send(
    SOUNDBOKS_VOLUME_SERVICE_ID,
    SOUNDBOKS_VOLUME_ID,
    soundboksVolumeBytes(config.volume)
  )

  send(
    SOUNDBOKS_SOUND_PROFILE_SERVICE_ID,
    SOUNDBOKS_SOUND_PROFILE_ID,
    config.soundProfile.bytes
  )

  send(
    SOUNDBOKS_CHANNEL_SERVICE_ID,
    SOUNDBOKS_CHANNEL_ID,
    config.channel.bytes
  )

  send(
    SOUNDBOKS_TEAM_UP_MODE_SERVICE_ID,
    SOUNDBOKS_TEAM_UP_MODE_ID,
    config.teamUpMode.bytes
  )
}
