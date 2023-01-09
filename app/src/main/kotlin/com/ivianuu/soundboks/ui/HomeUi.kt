/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.soundboks.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ContentAlpha
import androidx.compose.material.LocalContentColor
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.google.accompanist.flowlayout.FlowRow
import com.ivianuu.essentials.app.AppForegroundState
import com.ivianuu.essentials.coroutines.combine
import com.ivianuu.essentials.coroutines.infiniteEmptyFlow
import com.ivianuu.essentials.data.DataStore
import com.ivianuu.essentials.logging.Logger
import com.ivianuu.essentials.resource.Resource
import com.ivianuu.essentials.resource.getOrNull
import com.ivianuu.essentials.state.action
import com.ivianuu.essentials.state.bind
import com.ivianuu.essentials.state.bindResource
import com.ivianuu.essentials.ui.common.SimpleListScreen
import com.ivianuu.essentials.ui.dialog.ListKey
import com.ivianuu.essentials.ui.material.ListItem
import com.ivianuu.essentials.ui.material.guessingContentColorFor
import com.ivianuu.essentials.ui.material.incrementingStepPolicy
import com.ivianuu.essentials.ui.navigation.Model
import com.ivianuu.essentials.ui.navigation.ModelKeyUi
import com.ivianuu.essentials.ui.navigation.Navigator
import com.ivianuu.essentials.ui.navigation.RootKey
import com.ivianuu.essentials.ui.navigation.push
import com.ivianuu.essentials.ui.prefs.ScaledPercentageUnitText
import com.ivianuu.essentials.ui.prefs.SliderListItem
import com.ivianuu.essentials.ui.resource.ResourceBox
import com.ivianuu.injekt.Provide
import com.ivianuu.soundboks.data.SoundChannel
import com.ivianuu.soundboks.data.SoundProfile
import com.ivianuu.soundboks.data.Soundboks
import com.ivianuu.soundboks.data.SoundboksConfig
import com.ivianuu.soundboks.data.SoundboksPrefs
import com.ivianuu.soundboks.data.TeamUpMode
import com.ivianuu.soundboks.data.merge
import com.ivianuu.soundboks.domain.SoundboksRemote
import com.ivianuu.soundboks.domain.SoundboksRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@Provide object HomeKey : RootKey

@OptIn(ExperimentalFoundationApi::class)
@Provide val homeUi = ModelKeyUi<HomeKey, HomeModel> {
  SimpleListScreen("Soundboks") {
    item {
      ResourceBox(soundbokses) { value ->
        FlowRow(
          modifier = Modifier
            .padding(8.dp),
          mainAxisSpacing = 8.dp,
          crossAxisSpacing = 8.dp
        ) {
          @Composable fun Chip(
            selected: Boolean,
            active: Boolean,
            onClick: () -> Unit,
            onLongClick: (() -> Unit)?,
            content: @Composable () -> Unit
          ) {
            val backgroundColor = if (selected) MaterialTheme.colors.secondary
            else LocalContentColor.current.copy(alpha = ContentAlpha.medium)
            Surface(
              modifier = Modifier
                .defaultMinSize(minWidth = 120.dp, minHeight = 56.dp)
                .alpha(if (active) 1f else ContentAlpha.disabled),
              shape = RoundedCornerShape(50),
              color = backgroundColor,
              contentColor = guessingContentColorFor(backgroundColor)
            ) {
              Box(
                modifier = Modifier
                  .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = LocalIndication.current,
                    onClick = onClick,
                    onLongClick = onLongClick
                  ),
                contentAlignment = Alignment.Center
              ) {
                content()
              }
            }
          }

          val allSoundbokses =
            soundbokses.getOrNull()?.map { it.soundboks.address }?.toSet() ?: emptySet()
          Chip(
            selected = allSoundbokses.all { it in selectedSoundbokses },
            active = true,
            onClick = toggleAllSoundboksSelections,
            onLongClick = null
          ) {
            Text("ALL")
          }

          value.forEach { soundboks ->
            Chip(
              selected = soundboks.soundboks.address in selectedSoundbokses,
              active = soundboks.isConnected,
              onClick = { toggleSoundboksSelection(soundboks, false) },
              onLongClick = { toggleSoundboksSelection(soundboks, true) }
            ) {
              Text(soundboks.soundboks.name)
            }
          }
        }
      }
    }

    if (selectedSoundbokses.isEmpty()) {
      item {
        Text("Select a soundboks to edit")
      }
    } else {
      item {
        SliderListItem(
          value = config.volume,
          onValueChange = updateVolume,
          stepPolicy = incrementingStepPolicy(0.05f),
          title = { Text("Volume") },
          valueText = { ScaledPercentageUnitText(it) }
        )
      }

      item {
        ListItem(
          modifier = Modifier.clickable(onClick = updateSoundProfile),
          title = { Text("Sound profile") },
          subtitle = { Text(config.soundProfile.name) }
        )
      }

      item {
        ListItem(
          modifier = Modifier.clickable(onClick = updateChannel),
          title = { Text("Channel") },
          subtitle = { Text(config.channel.name) }
        )
      }

      item {
        ListItem(
          modifier = Modifier.clickable(onClick = updateTeamUpMode),
          title = { Text("Team up") },
          subtitle = { Text(config.teamUpMode.name) }
        )
      }
    }
  }
}

data class UiSoundboks(val soundboks: Soundboks, val isConnected: Boolean)

data class HomeModel(
  val soundbokses: Resource<List<UiSoundboks>>,
  val selectedSoundbokses: Set<String>,
  val toggleSoundboksSelection: (UiSoundboks, Boolean) -> Unit,
  val toggleAllSoundboksSelections: () -> Unit,
  val config: SoundboksConfig,
  val updateSoundProfile: () -> Unit,
  val updateChannel: () -> Unit,
  val updateVolume: (Float) -> Unit,
  val updateTeamUpMode: () -> Unit
)

@Provide fun homeModel(
  appForegroundState: Flow<AppForegroundState>,
  logger: Logger,
  navigator: Navigator,
  pref: DataStore<SoundboksPrefs>,
  remote: SoundboksRemote,
  repository: SoundboksRepository
) = Model {
  val prefs = pref.data.bind(SoundboksPrefs())

  val soundbokses = appForegroundState
    .flatMapLatest {
      if (it == AppForegroundState.FOREGROUND) repository.soundbokses
      else infiniteEmptyFlow()
    }
    .flatMapLatest { soundbokses ->
      if (soundbokses.isEmpty()) flowOf(emptyList())
      else combine(
        soundbokses
          .sortedBy { it.name }
          .map { soundboks ->
            remote.isConnected(soundboks.address)
              .map { UiSoundboks(soundboks, it) }
          }
      )
    }
    .bindResource()

  val config = prefs.selectedSoundbokses
    .map { prefs.configs[it] ?: SoundboksConfig() }
    .merge()

  suspend fun updateConfig(block: SoundboksConfig.() -> SoundboksConfig) {
    pref.updateData {
      copy(
        configs = buildMap {
          putAll(configs)
          selectedSoundbokses.forEach {
            put(it, block(prefs.configs[it] ?: SoundboksConfig()))
          }
        }
      )
    }
  }

  HomeModel(
    soundbokses = soundbokses,
    selectedSoundbokses = prefs.selectedSoundbokses,
    toggleSoundboksSelection = action { soundboks, longClick ->
      pref.updateData {
        copy(
          selectedSoundbokses = if (!longClick) setOf(soundboks.soundboks.address)
          else selectedSoundbokses.toMutableSet().apply {
            if (soundboks.soundboks.address in this) remove(soundboks.soundboks.address)
            else add(soundboks.soundboks.address)
          }
        )
      }
    },
    toggleAllSoundboksSelections = action {
      pref.updateData {
        val allSoundbokses =
          soundbokses.getOrNull()?.map { it.soundboks.address }?.toSet() ?: emptySet()
        copy(
          selectedSoundbokses = if (allSoundbokses.all { it in selectedSoundbokses }) emptySet()
          else allSoundbokses
        )
      }
    },
    config = config,
    updateSoundProfile = action {
      navigator.push(
        ListKey(
          items = SoundProfile.values()
            .map { ListKey.Item(it, it.name) }
        )
      )?.let { updateConfig { copy(soundProfile = it) } }
    },
    updateVolume = action { volume -> updateConfig { copy(volume = volume) } },
    updateChannel = action {
      navigator.push(
        ListKey(
          items = SoundChannel.values()
            .map { ListKey.Item(it, it.name) }
        )
      )?.let { updateConfig { copy(channel = it) } }
    },
    updateTeamUpMode = action {
      navigator.push(
        ListKey(
          items = TeamUpMode.values()
            .map { ListKey.Item(it, it.name) }
        )
      )?.let { updateConfig { copy(teamUpMode = it) } }
    }
  )
}
