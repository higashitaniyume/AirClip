package com.airclip.service

import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.airclip.AirClipApp
import com.airclip.R
import com.airclip.core.sync.PublishSource
import com.airclip.ui.ClipboardRelayActivity

/**
 * 方案 B: pull down the shade, tap once, the clipboard is on the PC.
 *
 * A tile has no window, so it can never read the clipboard itself — it launches
 * [ClipboardRelayActivity], and the fact that the launch came from a tile tap is exactly what makes
 * both the background activity start and the subsequent read legal.
 */
class ClipboardTileService : TileService() {

    /** Called every time the tile becomes visible, which is the only moment its label matters. */
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val intent = ClipboardRelayActivity.sendIntent(this, PublishSource.TILE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34 made the Intent overload throw; only the PendingIntent form is allowed now.
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    REQUEST_SEND,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val runtime = AirClipApp.runtime(applicationContext)
        val running = runtime.isRunning.value
        val paused = runtime.isPaused.value
        val connected = runtime.peers.value.count { it.isConnected }

        tile.state = if (running && !paused) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.subtitle = when {
            !running -> getString(R.string.toast_service_off)
            paused -> getString(R.string.home_state_paused)
            connected > 0 -> getString(R.string.home_state_connected, connected)
            else -> getString(R.string.home_state_offline)
        }
        tile.updateTile()
    }

    private companion object {
        const val REQUEST_SEND = 11
    }
}
