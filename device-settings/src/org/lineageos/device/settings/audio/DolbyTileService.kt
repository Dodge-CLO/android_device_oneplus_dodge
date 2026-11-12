/*
 * SPDX-FileCopyrightText: 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.device.settings.audio

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class DolbyTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    override fun onClick() {
        super.onClick()
        AudioEffects.toggleDolby(this)
        update()
    }

    private fun update() {
        qsTile?.apply {
            state = if (AudioEffects.getMode(this@DolbyTileService) == AudioEffects.MODE_DOLBY) {
                Tile.STATE_ACTIVE
            } else {
                Tile.STATE_INACTIVE
            }
            updateTile()
        }
    }
}
