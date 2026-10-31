/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.device.settings.oreality

import android.os.Bundle
import androidx.activity.ComponentActivity
import org.lineageos.device.settings.ui.DeviceSettingsDest
import org.lineageos.device.settings.ui.setDeviceSettingsContent

class OrealityActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setDeviceSettingsContent(DeviceSettingsDest.Oreality)
    }
}
