package com.sodre90.cmuxremote.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sodre90.cmuxremote.R
import com.sodre90.cmuxremote.data.ConnectionSlot
import com.sodre90.cmuxremote.data.ConnectionStatus
import com.sodre90.cmuxremote.ui.theme.AppColors

/** How prominently a [ConnectionStatusStripModel] should read. */
enum class ConnectionTone {
    /** On the preferred transport; deliberately quiet. */
    NORMAL,

    /** On, or moving to, the Tailscale fallback -- worth noticing, not an error. */
    FALLBACK,

    /** Neither transport worked. */
    ERROR,
}

/**
 * What [ConnectionStatusStrip] renders for a given [ConnectionStatus]. Split
 * out as plain data so the mapping is unit-testable without a Compose runtime
 * (same split as [com.sodre90.cmuxremote.ui.sessions.sortedByAttention]).
 */
data class ConnectionStatusStripModel(
    @StringRes val labelRes: Int,
    val detail: String?,
    val tone: ConnectionTone,
    val busy: Boolean,
)

/**
 * Returns null for [ConnectionStatus.Unknown] -- before the first attempt
 * there is nothing truthful to say, and an empty strip on cold start would
 * just push the list down for a frame.
 *
 * [ConnectionStatus.FallingBack] splits on whether the relay actually failed:
 * a null reason means it was skipped inside [com.sodre90.cmuxremote.data.RelayHealth]'s
 * penalty window, which is precisely the "why is it on Tailscale when the
 * relay is fine?" case this strip exists to explain.
 */
fun connectionStatusStrip(status: ConnectionStatus): ConnectionStatusStripModel? = when (status) {
    is ConnectionStatus.Unknown -> null

    is ConnectionStatus.Connecting -> ConnectionStatusStripModel(
        labelRes = when (status.slot) {
            ConnectionSlot.RELAY -> R.string.connection_connecting_relay
            ConnectionSlot.DIRECT -> R.string.connection_connecting_direct
        },
        detail = null,
        tone = if (status.slot == ConnectionSlot.RELAY) ConnectionTone.NORMAL else ConnectionTone.FALLBACK,
        busy = true,
    )

    is ConnectionStatus.Connected -> ConnectionStatusStripModel(
        labelRes = when (status.slot) {
            ConnectionSlot.RELAY -> R.string.connection_connected_relay
            ConnectionSlot.DIRECT -> R.string.connection_connected_direct
        },
        detail = null,
        tone = if (status.slot == ConnectionSlot.RELAY) ConnectionTone.NORMAL else ConnectionTone.FALLBACK,
        busy = false,
    )

    is ConnectionStatus.FallingBack -> ConnectionStatusStripModel(
        labelRes = if (status.reason == null) {
            R.string.connection_relay_skipped
        } else {
            R.string.connection_falling_back
        },
        detail = status.reason,
        tone = ConnectionTone.FALLBACK,
        busy = true,
    )

    is ConnectionStatus.Failed -> ConnectionStatusStripModel(
        labelRes = R.string.connection_failed,
        detail = status.detail,
        tone = ConnectionTone.ERROR,
        busy = false,
    )
}

/**
 * One-line banner naming the transport currently serving the app, and the
 * reason whenever it has fallen off the relay. Both transports' failure
 * detail arrives here already merged (see
 * [com.sodre90.cmuxremote.data.BothTransportsFailedException]).
 */
@Composable
fun ConnectionStatusStrip(status: ConnectionStatus, modifier: Modifier = Modifier) {
    val model = connectionStatusStrip(status) ?: return
    val color = when (model.tone) {
        ConnectionTone.NORMAL -> MaterialTheme.colorScheme.onSurfaceVariant
        ConnectionTone.FALLBACK -> AppColors.WaitingAccent
        ConnectionTone.ERROR -> MaterialTheme.colorScheme.error
    }
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (model.busy) {
            CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 2.dp, color = color)
        } else {
            Box(Modifier.size(8.dp).background(color, CircleShape))
        }
        Text(
            text = stringResource(model.labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        model.detail?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
