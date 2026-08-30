package com.airclip.ui.components

import android.content.Context
import android.os.Build
import com.airclip.R
import com.airclip.ui.SystemAccess

/**
 * Offers to add the quick-settings tile. Android 13+ can ask the shade directly; before that the
 * only way in is the shade's own edit mode, so the user is told where to look. Shared by the home
 * checklist and the settings screen, which both need the same wording.
 */
fun requestTile(context: Context, onMessage: (String) -> Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        SystemAccess.requestAddTile(context) { added ->
            onMessage(context.getString(if (added) R.string.settings_tile_added else R.string.settings_tile_manual))
        }
    } else {
        onMessage(context.getString(R.string.settings_tile_manual))
    }
}
