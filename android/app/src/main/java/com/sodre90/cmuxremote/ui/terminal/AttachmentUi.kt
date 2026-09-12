package com.sodre90.cmuxremote.ui.terminal

import android.content.ClipboardManager
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sodre90.cmuxremote.R
import com.sodre90.cmuxremote.model.AttachRefusal

/**
 * The image URI on the platform clipboard, if what is there is an image. A
 * copied screenshot arrives this way; Compose's own clipboard manager only
 * sees text, so this reads the platform one.
 */
internal fun clipboardImageUri(clipboard: ClipboardManager?): Uri? {
    val clip = clipboard?.primaryClip ?: return null
    if (clipboard.primaryClipDescription?.hasMimeType("image/*") != true) return null
    return (0 until clip.itemCount).firstNotNullOfOrNull { clip.getItemAt(it).uri }
}

/**
 * The attach button and its two sources. A bare "+" like a chat composer's,
 * as narrow as an arrow key: the D-pad row has no room for a worded button
 * next to Paste at phone width. The clipboard entry is offered only while
 * the clipboard holds an image, read each time the menu opens so a copy
 * made while the terminal was on screen is seen.
 */
@Composable
internal fun AttachButton(onFromGallery: () -> Unit, onFromClipboard: (Uri) -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var clipboardImage by remember { mutableStateOf<Uri?>(null) }
    Box {
        OutlinedButton(
            onClick = {
                clipboardImage = clipboardImageUri(context.getSystemService(ClipboardManager::class.java))
                open = true
            },
            contentPadding = KEY_BAR_BUTTON_PADDING,
        ) { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.terminal_attach)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.terminal_attach_from_gallery)) },
                onClick = {
                    open = false
                    onFromGallery()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.terminal_attach_from_clipboard)) },
                enabled = clipboardImage != null,
                onClick = {
                    open = false
                    clipboardImage?.let(onFromClipboard)
                },
            )
        }
    }
}

/**
 * What will be sent, before it is: a thumbnail, the size of the copy that
 * goes by default, and the choice of the original instead. Mirrors the
 * multi-line paste confirmation -- a photo is not something to fire on a
 * mis-tap, least of all a multi-megabyte one on mobile data.
 */
@Composable
internal fun AttachmentDialog(draft: AttachmentDraft, onSend: (original: Boolean) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var sendOriginal by rememberSaveable { mutableStateOf(false) }
    val ready = draft as? AttachmentDraft.Ready
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.terminal_attach_dialog_title)) },
        text = {
            when (draft) {
                AttachmentDraft.Preparing -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Text(stringResource(R.string.terminal_attach_preparing))
                }

                AttachmentDraft.Unreadable -> Text(stringResource(R.string.terminal_attach_unreadable))

                is AttachmentDraft.Ready -> AttachmentPreviewBody(
                    preview = draft.preview,
                    sendOriginal = sendOriginal,
                    onSendOriginalChange = { sendOriginal = it },
                    formatSize = { Formatter.formatShortFileSize(context, it) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(sendOriginal) }, enabled = ready != null) {
                Text(stringResource(R.string.terminal_attach_send))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun AttachmentPreviewBody(
    preview: AttachmentPreview,
    sendOriginal: Boolean,
    onSendOriginalChange: (Boolean) -> Unit,
    formatSize: (Long) -> String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Image(
            bitmap = preview.thumbnail.asImageBitmap(),
            contentDescription = preview.name,
            modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
        )
        Text(
            preview.name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val copy = preview.downscaled
        Text(
            stringResource(
                R.string.terminal_attach_copy_size,
                formatSize(copy.bytes.size.toLong()),
                copy.width,
                copy.height,
            ),
        )
        if (preview.originalFits) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.terminal_attach_original_switch, formatSize(preview.originalBytes)),
                    modifier = Modifier.padding(end = 8.dp),
                )
                Switch(checked = sendOriginal, onCheckedChange = onSendOriginalChange)
            }
            if (sendOriginal) {
                Text(
                    stringResource(R.string.terminal_attach_exif_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (preview.originalBytes > 0) {
            Text(
                stringResource(R.string.terminal_attach_original_too_large, formatSize(preview.originalBytes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The one line the delivery label shows for a finished attach. */
internal fun attachOutcomeTextRes(outcome: AttachOutcome): Int = when {
    outcome.ok -> R.string.terminal_attach_sent
    outcome.reason == AttachRefusal.TOO_LARGE -> R.string.terminal_attach_failed_too_large
    outcome.reason == AttachRefusal.NOT_IMAGE -> R.string.terminal_attach_failed_not_image
    outcome.reason == AttachRefusal.ATTACHMENTS_OFF -> R.string.terminal_attach_failed_off
    else -> R.string.terminal_attach_failed
}
