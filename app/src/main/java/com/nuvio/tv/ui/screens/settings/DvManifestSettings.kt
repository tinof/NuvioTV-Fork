@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.player.DvManifestFolder
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

// ROADMAP DV4.8: the saved manifest folder for converted HLG tracks, next to the conversion setting.

/** The manifest rows work only where the conversion row works and with the conversion on. */
internal fun dvManifestRowsEnabled(parentEnabled: Boolean, stripMode: Boolean, convertOn: Boolean): Boolean =
    parentEnabled && !stripMode && convertOn

internal sealed interface DvManifestUrlInput {
    data class Save(val url: String) : DvManifestUrlInput
    data object Clear : DvManifestUrlInput
    data object Invalid : DvManifestUrlInput
}

/** What the folder dialog's Save does with [text]; Invalid keeps the dialog open and writes nothing. */
internal fun dvManifestUrlInput(text: String): DvManifestUrlInput {
    if (text.isBlank()) return DvManifestUrlInput.Clear
    return DvManifestFolder.normalize(text)?.let { DvManifestUrlInput.Save(it) } ?: DvManifestUrlInput.Invalid
}

@Composable
internal fun DvManifestRows(
    settings: PlayerSettings,
    enabled: Boolean,
    onUpdate: PlaybackSettingsUpdate,
    onOpenDialog: (PlaybackDialog) -> Unit
) {
    SettingsToggleRow(
        title = stringResource(R.string.playback_dv_manifest_title),
        subtitle = stringResource(R.string.playback_dv_manifest_sub),
        checked = settings.dvManifestEnabled,
        onToggle = { onUpdate { setDvManifestEnabled(!settings.dvManifestEnabled) } },
        enabled = enabled
    )
    SettingsActionRow(
        title = stringResource(R.string.playback_dv_manifest_url_title),
        subtitle = null,
        value = settings.dvManifestUrl.ifBlank { stringResource(R.string.playback_dv_manifest_url_not_set) },
        enabled = enabled && settings.dvManifestEnabled,
        onClick = { onOpenDialog(PlaybackDialog.DV_MANIFEST_URL) }
    )
}

@Composable
internal fun DvManifestUrlDialog(
    initialUrl: String,
    onUpdate: PlaybackSettingsUpdate,
    onDismiss: () -> Unit
) {
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    var invalid by remember { mutableStateOf(false) }
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(10.dp)

    LaunchedEffect(Unit) {
        inputFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.playback_dv_manifest_url_title),
        subtitle = stringResource(R.string.playback_dv_manifest_url_dialog_sub),
        width = 700.dp,
        suppressFirstKeyUp = false
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            Card(
                onClick = { inputFocusRequester.requestFocus() },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { isInputFocused = it.isFocused || it.hasFocus },
                colors = CardDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated,
                    focusedContainerColor = NuvioTheme.colors.BackgroundElevated
                ),
                border = CardDefaults.border(
                    border = Border(
                        border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                        shape = shape
                    ),
                    focusedBorder = Border(
                        border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                        shape = shape
                    )
                ),
                shape = CardDefaults.shape(shape),
                scale = CardDefaults.scale(focusedScale = 1f)
            ) {
                Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.md)) {
                    BasicTextField(
                        value = url,
                        onValueChange = {
                            url = it
                            invalid = false
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(inputFocusRequester)
                            .onKeyEvent { keyEvent ->
                                keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                                    keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                            },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioTheme.colors.TextPrimary),
                        cursorBrush = SolidColor(if (isInputFocused) NuvioTheme.colors.Primary else Color.Transparent),
                        decorationBox = { innerTextField ->
                            if (url.isBlank()) {
                                Text(
                                    text = "https://example.org/dv/",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NuvioTheme.colors.TextTertiary
                                )
                            }
                            innerTextField()
                        }
                    )
                }
            }

            if (invalid) {
                Text(
                    text = stringResource(R.string.playback_dv_manifest_url_invalid),
                    color = NuvioTheme.colors.Error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                DialogButton(text = stringResource(R.string.action_cancel), onClick = onDismiss)
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
                DialogButton(text = stringResource(R.string.action_clear)) {
                    onUpdate { setDvManifestUrl("") }
                    onDismiss()
                }
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
                DialogButton(text = stringResource(R.string.action_save)) {
                    // Validate before dispatching: the update runs later, so its result cannot keep the dialog open.
                    when (val input = dvManifestUrlInput(url)) {
                        DvManifestUrlInput.Invalid -> invalid = true
                        DvManifestUrlInput.Clear -> {
                            onUpdate { setDvManifestUrl("") }
                            onDismiss()
                        }
                        is DvManifestUrlInput.Save -> {
                            onUpdate { setDvManifestUrl(input.url) }
                            onDismiss()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundElevated,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            focusedContentColor = NuvioTheme.colors.Primary
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(10.dp))
    ) {
        Text(text)
    }
}
