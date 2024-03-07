/*
 * Copyright 2022 Manuel Wrage. Use of this source code is governed by the Apache 2.0 license.
 */

package com.ivianuu.soundboks

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.*
import androidx.compose.material.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.*
import arrow.fx.coroutines.*
import com.google.accompanist.flowlayout.*
import com.ivianuu.essentials.compose.*
import com.ivianuu.essentials.data.*
import com.ivianuu.essentials.resource.*
import com.ivianuu.essentials.ui.app.*
import com.ivianuu.essentials.ui.common.*
import com.ivianuu.essentials.ui.dialog.*
import com.ivianuu.essentials.ui.material.*
import com.ivianuu.essentials.ui.navigation.*
import com.ivianuu.essentials.ui.prefs.*
import com.ivianuu.injekt.*

@Provide val soundboksAppColors = AppColors(
  primary = Color(0xFFF19066),
  secondary = Color(0xFFE66767)
)

@Provide class HomeScreen : RootScreen {
  @Provide companion object {
    @Provide fun ui(
      navigator: Navigator,
      prefsDataStore: DataStore<SoundboksPrefs>,
      repository: SoundboksRepository,
      remote: SoundboksRemote
    ) = Ui<HomeScreen> {
      val prefs = prefsDataStore.data.scopedState(SoundboksPrefs())

      ScreenScaffold(
        topBar = {
          AppBar(
            title = { Text("Soundboks") },
            actions = {
              DropdownMenuButton {
                DropdownMenuItem(onClick = scopedAction {
                  prefs.selectedSoundbokses.parMap { remote.powerOff(it) }
                }) { Text("Power off") }
              }
            }
          )
        }
      ) {
        val soundbokses = repository.soundbokses.scopedResourceState()

        val connectedSoundbokses = soundbokses
          .getOrElse { emptyList() }
          .mapNotNullTo(mutableSetOf()) { soundboks ->
            key(soundboks) {
              state(false, prefs.configs[soundboks.address]?.pin) {
                remote.withSoundboks<Unit>(soundboks.address, prefs.configs[soundboks.address]?.pin) {
                  isConnected.collect { value = it }
                }
              }
                .let { if (it) soundboks.address else null }
            }
          }

        val playingSoundboks = repository.playingSoundboks.scopedState(null)?.address

        ResourceBox(soundbokses) { value ->
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
                    soundbokses.getOrNull()?.map { it.address }?.toSet() ?: emptySet()

                  SoundboksChip(
                    selected = allSoundbokses.all { it in prefs.selectedSoundbokses },
                    active = allSoundbokses.all { it in connectedSoundbokses },
                    playing = false,
                    onClick = scopedAction {
                      prefsDataStore.updateData {
                        copy(
                          selectedSoundbokses = if (allSoundbokses.all { it in selectedSoundbokses }) emptySet()
                          else allSoundbokses
                        )
                      }
                    },
                    onLongClick = null
                  ) {
                    Text("ALL")
                  }

                  value.forEach { soundboks ->
                    suspend fun toggleSoundboksSelection(longClick: Boolean) {
                      prefsDataStore.updateData {
                        copy(
                          selectedSoundbokses = if (!longClick) setOf(soundboks.address)
                          else selectedSoundbokses.toMutableSet().apply {
                            if (soundboks.address in this) remove(soundboks.address)
                            else add(soundboks.address)
                          }
                        )
                      }
                    }

                    SoundboksChip(
                      selected = soundboks.address in prefs.selectedSoundbokses,
                      active = soundboks.address in connectedSoundbokses,
                      playing = soundboks.address == playingSoundboks,
                      onClick = scopedAction { toggleSoundboksSelection(false) },
                      onLongClick = scopedAction { toggleSoundboksSelection(true) }
                    ) {
                      Text(soundboks.name)
                    }
                  }
                }
              }

              if (prefs.selectedSoundbokses.isEmpty()) {
                item {
                  Text("Select a soundboks to edit")
                }
              } else {
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

                item {
                  SliderListItem(
                    value = config.volume,
                    onValueChange = scopedAction { volume ->
                      updateConfig { copy(volume = volume) }
                    },
                    stepPolicy = incrementingStepPolicy(0.05f),
                    title = { Text("Volume") },
                    valueText = { ScaledPercentageUnitText(it) }
                  )
                }

                item {
                  SingleChoiceToggleButtonGroupListItem(
                    selected = config.soundProfile,
                    values = SoundProfile.entries,
                    onSelectionChanged = scopedAction { value ->
                      updateConfig { copy(soundProfile = value) }
                    },
                    title = { Text("Sound profile") }
                  )
                }

                item {
                  SingleChoiceToggleButtonGroupListItem(
                    selected = config.channel,
                    values = SoundChannel.entries,
                    onSelectionChanged = scopedAction { value ->
                      updateConfig { copy(channel = value) }
                    },
                    title = { Text("Channel") }
                  )
                }

                item {
                  SingleChoiceToggleButtonGroupListItem(
                    selected = config.teamUpMode,
                    values = TeamUpMode.entries,
                    onSelectionChanged = scopedAction { value ->
                      updateConfig { copy(teamUpMode = value) }
                    },
                    title = { Text("Team up mode") }
                  )
                }

                item {
                  ListItem(
                    onClick = scopedAction {
                      val pin = navigator.push(
                        TextInputScreen(keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                      )?.toIntOrNull() ?: return@scopedAction
                      updateConfig { copy(pin = if (pin.toString().length == 4) pin else null) }
                    },
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
          .alpha(animateFloatAsState(if (active) 1f else ContentAlpha.disabled).value),
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
              Icon(Icons.Default.VolumeUp, null)

            content()
          }
        }
      }
    }
  }
}
