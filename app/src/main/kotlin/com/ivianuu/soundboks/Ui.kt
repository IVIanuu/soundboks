/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.soundboks

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.ContentAlpha
import androidx.compose.material.Icon
import androidx.compose.material.LocalContentColor
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.accompanist.flowlayout.FlowRow
import com.ivianuu.essentials.ScopeManager
import com.ivianuu.essentials.app.AppVisibleScope
import com.ivianuu.essentials.compose.action
import com.ivianuu.essentials.coroutines.parForEach
import com.ivianuu.essentials.data.DataStore
import com.ivianuu.essentials.flowInScope
import com.ivianuu.essentials.repeatInScope
import com.ivianuu.essentials.resource.Resource
import com.ivianuu.essentials.resource.collectAsResourceState
import com.ivianuu.essentials.resource.getOrElse
import com.ivianuu.essentials.resource.getOrNull
import com.ivianuu.essentials.ui.AppColors
import com.ivianuu.essentials.ui.common.VerticalList
import com.ivianuu.essentials.ui.dialog.TextInputScreen
import com.ivianuu.essentials.ui.layout.center
import com.ivianuu.essentials.ui.material.AppBar
import com.ivianuu.essentials.ui.material.ListItem
import com.ivianuu.essentials.ui.material.Scaffold
import com.ivianuu.essentials.ui.material.guessingContentColorFor
import com.ivianuu.essentials.ui.material.incrementingStepPolicy
import com.ivianuu.essentials.ui.navigation.Navigator
import com.ivianuu.essentials.ui.navigation.Presenter
import com.ivianuu.essentials.ui.navigation.RootScreen
import com.ivianuu.essentials.ui.navigation.Ui
import com.ivianuu.essentials.ui.navigation.push
import com.ivianuu.essentials.ui.popup.PopupMenuButton
import com.ivianuu.essentials.ui.popup.PopupMenuItem
import com.ivianuu.essentials.ui.prefs.ScaledPercentageUnitText
import com.ivianuu.essentials.ui.prefs.SingleChoiceToggleButtonGroupListItem
import com.ivianuu.essentials.ui.prefs.SliderListItem
import com.ivianuu.essentials.ui.resource.ResourceBox
import com.ivianuu.injekt.Provide

@Provide val soundboksAppColors = AppColors(
  primary = Color(0xFFF19066),
  secondary = Color(0xFFE66767)
)

@Provide class HomeScreen : RootScreen

@Provide val homeUi = Ui<HomeScreen, HomeState> { state ->
  Scaffold(
    topBar = {
      AppBar(
        title = { Text("Soundboks") },
        actions = {
          PopupMenuButton {
            PopupMenuItem(onSelected = state.powerOff) { Text("Power off") }
          }
        }
      )
    }
  ) {
    ResourceBox(state.soundbokses) { value ->
      VerticalList {
        if (value.isEmpty()) {
          item {
            Text(
              modifier = Modifier
                .fillParentMaxSize()
                .center(),
              text = "No soundbokses found"
            )
          }
        } else {
          item {
            FlowRow(
              modifier = Modifier.padding(8.dp),
              mainAxisSpacing = 8.dp,
              crossAxisSpacing = 8.dp
            ) {
              val allSoundbokses =
                state.soundbokses.getOrNull()?.map { it.address }?.toSet() ?: emptySet()

              SoundboksChip(
                selected = allSoundbokses.all { it in state.selectedSoundbokses },
                active = allSoundbokses.all { it in state.connectedSoundbokses },
                playing = false,
                onClick = state.toggleAllSoundboksSelections,
                onLongClick = null
              ) {
                Text("ALL")
              }

              value.forEach { soundboks ->
                SoundboksChip(
                  selected = soundboks.address in state.selectedSoundbokses,
                  active = soundboks.address in state.connectedSoundbokses,
                  playing = soundboks.address == state.playingSoundboks,
                  onClick = { state.toggleSoundboksSelection(soundboks, false) },
                  onLongClick = { state.toggleSoundboksSelection(soundboks, true) }
                ) {
                  Text(soundboks.name)
                }
              }
            }
          }

          if (state.selectedSoundbokses.isEmpty()) {
            item {
              Text("Select a soundboks to edit")
            }
          } else {
            item {
              SliderListItem(
                value = state.config.volume,
                onValueChange = state.updateVolume,
                stepPolicy = incrementingStepPolicy(0.05f),
                title = { Text("Volume") },
                valueText = { ScaledPercentageUnitText(it) }
              )
            }

            item {
              SingleChoiceToggleButtonGroupListItem(
                selected = state.config.soundProfile,
                values = SoundProfile.entries,
                onSelectionChanged = state.updateSoundProfile,
                title = { Text("Sound profile") }
              )
            }

            item {
              SingleChoiceToggleButtonGroupListItem(
                selected = state.config.channel,
                values = SoundChannel.entries,
                onSelectionChanged = state.updateChannel,
                title = { Text("Channel") }
              )
            }

            item {
              SingleChoiceToggleButtonGroupListItem(
                selected = state.config.teamUpMode,
                values = TeamUpMode.entries,
                onSelectionChanged = state.updateTeamUpMode,
                title = { Text("Team up mode") }
              )
            }

            item {
              ListItem(
                modifier = Modifier.clickable(onClick = state.updatePin),
                title = { Text("Pin") }
              )
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun SoundboksChip(
  selected: Boolean,
  active: Boolean,
  playing: Boolean,
  onClick: () -> Unit,
  onLongClick: (() -> Unit)?,
  content: @Composable () -> Unit
) {
  val targetBackgroundColor = if (selected) MaterialTheme.colors.secondary
  else LocalContentColor.current.copy(alpha = ContentAlpha.disabled)
  val backgroundColor by animateColorAsState(targetBackgroundColor)
  val contentColor by animateColorAsState(guessingContentColorFor(targetBackgroundColor))
  Surface(
    modifier = Modifier
      .height(32.dp)
      .alpha(if (active) 1f else ContentAlpha.disabled),
    shape = RoundedCornerShape(50),
    color = backgroundColor,
    contentColor = contentColor
  ) {
    Row(
      modifier = Modifier
        .combinedClickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = LocalIndication.current,
          onClick = onClick,
          onLongClick = onLongClick
        )
        .padding(horizontal = 8.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.Start)
    ) {
      CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.button) {
        if (playing)
          Icon(R.drawable.ic_volume_up)

        content()
      }
    }
  }
}

data class HomeState(
  val soundbokses: Resource<List<Soundboks>>,
  val selectedSoundbokses: Set<String>,
  val connectedSoundbokses: Set<String>,
  val playingSoundboks: String?,
  val toggleSoundboksSelection: (Soundboks, Boolean) -> Unit,
  val toggleAllSoundboksSelections: () -> Unit,
  val config: SoundboksConfig,
  val updateSoundProfile: (SoundProfile) -> Unit,
  val updateChannel: (SoundChannel) -> Unit,
  val updateVolume: (Float) -> Unit,
  val updateTeamUpMode: (TeamUpMode) -> Unit,
  val updatePin: () -> Unit,
  val powerOff: () -> Unit
)

@Provide fun homePresenter(
  navigator: Navigator,
  prefsDataStore: DataStore<SoundboksPrefs>,
  repository: SoundboksRepository,
  remote: SoundboksRemote,
  scopeManager: ScopeManager
) = Presenter {
  val prefs by prefsDataStore.data.collectAsState(SoundboksPrefs())

  val soundbokses by remember {
    scopeManager.flowInScope<AppVisibleScope, _>(repository.soundbokses)
  }.collectAsResourceState()

  val config = prefs.selectedSoundbokses
    .map { prefs.configs[it] ?: SoundboksConfig() }
    .merge()

  suspend fun updateConfig(block: SoundboksConfig.() -> SoundboksConfig) {
    prefsDataStore.updateData {
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

  val connectedSoundbokses = soundbokses
    .getOrElse { emptyList() }
    .mapNotNullTo(mutableSetOf()) { soundboks ->
      key(soundboks) {
        val isConnected by produceState(false, prefs.configs[soundboks.address]?.pin) {
          scopeManager.repeatInScope<AppVisibleScope> {
            remote.withSoundboks<Unit>(soundboks.address, prefs.configs[soundboks.address]?.pin) {
              isConnected.collect { value = it }
            }
          }
        }

        if (isConnected) soundboks.address else null
      }
    }

  HomeState(
    soundbokses = soundbokses,
    selectedSoundbokses = prefs.selectedSoundbokses,
    connectedSoundbokses = connectedSoundbokses,
    playingSoundboks = repository.playingSoundboks.collectAsState(null).value?.address,
    toggleSoundboksSelection = action { soundboks, longClick ->
      prefsDataStore.updateData {
        copy(
          selectedSoundbokses = if (!longClick) setOf(soundboks.address)
          else selectedSoundbokses.toMutableSet().apply {
            if (soundboks.address in this) remove(soundboks.address)
            else add(soundboks.address)
          }
        )
      }
    },
    toggleAllSoundboksSelections = action {
      prefsDataStore.updateData {
        val allSoundbokses =
          soundbokses.getOrNull()?.map { it.address }?.toSet() ?: emptySet()
        copy(
          selectedSoundbokses = if (allSoundbokses.all { it in selectedSoundbokses }) emptySet()
          else allSoundbokses
        )
      }
    },
    config = config,
    updateSoundProfile = action { value -> updateConfig { copy(soundProfile = value) } },
    updateVolume = action { volume -> updateConfig { copy(volume = volume) } },
    updateChannel = action { value -> updateConfig { copy(channel = value) } },
    updateTeamUpMode = action { value -> updateConfig { copy(teamUpMode = value) } },
    updatePin = action {
      val pin = navigator.push(
        TextInputScreen(keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
      )?.toIntOrNull() ?: return@action
      updateConfig { copy(pin = if (pin.toString().length == 4) pin else null) }
    },
    powerOff = action { prefs.selectedSoundbokses.parForEach { remote.powerOff(it) } }
  )
}
