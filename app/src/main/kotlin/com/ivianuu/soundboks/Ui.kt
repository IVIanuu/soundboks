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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.ContentAlpha
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
import com.ivianuu.essentials.app.AppForegroundState
import com.ivianuu.essentials.compose.action
import com.ivianuu.essentials.coroutines.infiniteEmptyFlow
import com.ivianuu.essentials.coroutines.parForEach
import com.ivianuu.essentials.data.DataStore
import com.ivianuu.essentials.resource.Resource
import com.ivianuu.essentials.resource.collectAsResourceState
import com.ivianuu.essentials.resource.getOrElse
import com.ivianuu.essentials.resource.getOrNull
import com.ivianuu.essentials.ui.AppColors
import com.ivianuu.essentials.ui.common.UiRenderer
import com.ivianuu.essentials.ui.common.VerticalList
import com.ivianuu.essentials.ui.dialog.TextInputScreen
import com.ivianuu.essentials.ui.layout.center
import com.ivianuu.essentials.ui.material.Button
import com.ivianuu.essentials.ui.material.ListItem
import com.ivianuu.essentials.ui.material.Scaffold
import com.ivianuu.essentials.ui.material.TopAppBar
import com.ivianuu.essentials.ui.material.esButtonColors
import com.ivianuu.essentials.ui.material.guessingContentColorFor
import com.ivianuu.essentials.ui.material.incrementingStepPolicy
import com.ivianuu.essentials.ui.navigation.Model
import com.ivianuu.essentials.ui.navigation.Navigator
import com.ivianuu.essentials.ui.navigation.RootScreen
import com.ivianuu.essentials.ui.navigation.Ui
import com.ivianuu.essentials.ui.navigation.push
import com.ivianuu.essentials.ui.popup.PopupMenuButton
import com.ivianuu.essentials.ui.popup.PopupMenuItem
import com.ivianuu.essentials.ui.prefs.ScaledPercentageUnitText
import com.ivianuu.essentials.ui.prefs.SliderListItem
import com.ivianuu.essentials.ui.resource.ResourceBox
import com.ivianuu.injekt.Inject
import com.ivianuu.injekt.Provide
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

@Provide val soundboksAppColors = AppColors(
  primary = Color(0xFFF19066),
  secondary = Color(0xFFE66767)
)

@Provide class HomeScreen : RootScreen

@Provide val homeUi = Ui<HomeScreen, HomeModel> { model ->
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Soundboks") },
        actions = {
          PopupMenuButton {
            PopupMenuItem(onSelected = model.powerOff) { Text("Power off") }
          }
        }
      )
    }
  ) {
    ResourceBox(model.soundbokses) { value ->
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
                model.soundbokses.getOrNull()?.map { it.address }?.toSet() ?: emptySet()

              SoundboksChip(
                selected = allSoundbokses.all { it in model.selectedSoundbokses },
                active = allSoundbokses.all { it in model.connectedSoundbokses },
                onClick = model.toggleAllSoundboksSelections,
                onLongClick = null
              ) {
                Text("ALL")
              }

              value.forEach { soundboks ->
                SoundboksChip(
                  selected = soundboks.address in model.selectedSoundbokses,
                  active = soundboks.address in model.connectedSoundbokses,
                  onClick = { model.toggleSoundboksSelection(soundboks, false) },
                  onLongClick = { model.toggleSoundboksSelection(soundboks, true) }
                ) {
                  Text(soundboks.name)
                }
              }
            }
          }

          if (model.selectedSoundbokses.isEmpty()) {
            item {
              Text("Select a soundboks to edit")
            }
          } else {
            item {
              SliderListItem(
                value = model.config.volume,
                onValueChange = model.updateVolume,
                stepPolicy = incrementingStepPolicy(0.05f),
                title = { Text("Volume") },
                valueText = { ScaledPercentageUnitText(it) }
              )
            }

            item {
              ToggleButtonGroup(
                selected = model.config.soundProfile,
                values = SoundProfile.values().toList(),
                onSelectionChanged = model.updateSoundProfile,
                title = "Sound profile"
              )
            }

            item {
              ToggleButtonGroup(
                selected = model.config.channel,
                values = SoundChannel.values().toList(),
                onSelectionChanged = model.updateChannel,
                title = "Channel"
              )
            }

            item {
              ToggleButtonGroup(
                selected = model.config.teamUpMode,
                values = TeamUpMode.values().toList(),
                onSelectionChanged = model.updateTeamUpMode,
                title = "Team up mode"
              )
            }

            item {
              ListItem(
                modifier = Modifier.clickable(onClick = model.updatePin),
                title = { Text("Pin") }
              )
            }
          }
        }
      }
    }
  }
}

@Composable private fun <T> ToggleButtonGroup(
  selected: T,
  values: List<T>,
  onSelectionChanged: (T) -> Unit,
  title: String,
  @Inject renderer: UiRenderer<T>
) {
  Column(
    modifier = Modifier
      .height(88.dp)
      .padding(horizontal = 16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.subtitle1
    )

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceEvenly
    ) {
      values.forEach { value ->
        val targetBackgroundColor = if (value == selected) MaterialTheme.colors.secondary
        else LocalContentColor.current.copy(alpha = ContentAlpha.disabled)
        val backgroundColor by animateColorAsState(targetBackgroundColor)
        val contentColor by animateColorAsState(guessingContentColorFor(targetBackgroundColor))
        Button(
          colors = ButtonDefaults.esButtonColors(
            backgroundColor = backgroundColor,
            contentColor = contentColor
          ),
          onClick = { onSelectionChanged(value) }
        ) {
          Text(renderer(value))
        }
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun SoundboksChip(
  selected: Boolean,
  active: Boolean,
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
    Box(
      modifier = Modifier
        .combinedClickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = LocalIndication.current,
          onClick = onClick,
          onLongClick = onLongClick
        )
        .padding(horizontal = 8.dp, vertical = 8.dp),
      contentAlignment = Alignment.Center
    ) {
      CompositionLocalProvider(
        LocalTextStyle provides MaterialTheme.typography.button,
        content = content
      )
    }
  }
}

data class HomeModel(
  val soundbokses: Resource<List<Soundboks>>,
  val selectedSoundbokses: Set<String>,
  val connectedSoundbokses: Set<String>,
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

@Provide fun homeModel(
  appForegroundState: Flow<AppForegroundState>,
  navigator: Navigator,
  pref: DataStore<SoundboksPrefs>,
  repository: SoundboksRepository,
  remote: SoundboksRemote
) = Model {
  val prefs by pref.data.collectAsState(SoundboksPrefs())

  val soundbokses by remember {
    appForegroundState
      .flatMapLatest {
        if (it == AppForegroundState.FOREGROUND) repository.soundbokses
        else infiniteEmptyFlow()
      }
  }.collectAsResourceState()

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

  val connectedSoundbokses = soundbokses
    .getOrElse { emptyList() }
    .mapNotNullTo(mutableSetOf()) { soundboks ->
      key(soundboks) {
        val isConnected by produceState(false) {
          remote.withSoundboks<Unit>(soundboks.address, prefs.configs[soundboks.address]?.pin) {
            isConnected.collect { value = it }
          }
        }

        if (isConnected) soundboks.address else null
      }
    }

  HomeModel(
    soundbokses = soundbokses,
    selectedSoundbokses = prefs.selectedSoundbokses,
    connectedSoundbokses = connectedSoundbokses,
    toggleSoundboksSelection = action { soundboks, longClick ->
      pref.updateData {
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
      pref.updateData {
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
