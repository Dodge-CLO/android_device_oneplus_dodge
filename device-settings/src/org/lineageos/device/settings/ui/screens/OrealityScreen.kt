/*
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.device.settings.ui.screens

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.android.axion.compose.preferences.PreferenceGroup
import com.android.axion.compose.preferences.SwitchPreference
import com.oplus.audio.effectcenter.IOplusEffectService
import org.lineageos.device.settings.R
import org.lineageos.device.settings.ui.CustomSeekBar
import org.lineageos.device.settings.ui.IconListPreference
import org.lineageos.device.settings.ui.OnResume
import org.lineageos.device.settings.ui.SettingsScroll
import org.lineageos.device.settings.ui.arrayOptions

private const val TAG = "OrealityScreen"
private const val SERVICE_PACKAGE = "com.oplus.audio.effectcenter"
private const val ACTION_BIND = "com.oplus.audioxeffect.controlservice.START"
private const val MODE_CUSTOM = 6
private const val EQ_MIN = -6
private const val EQ_MAX = 6
private val BAND_LABELS = listOf(
    "31 Hz", "62 Hz", "125 Hz", "250 Hz", "500 Hz",
    "1 kHz", "2 kHz", "4 kHz", "8 kHz", "16 kHz",
)

/** Stock presets, indexed by preset type - 1 (type 0 is the user's custom curve). */
private val PRESETS = listOf(
    intArrayOf(2, 3, 1, -1, 1, 2, 3, 2, 1, 1),      // Pop
    intArrayOf(3, 4, 2, -2, 0, 3, 4, 2, 1, 0),      // Rock
    intArrayOf(5, 5, 3, 0, 1, 2, 3, 4, 3, 2),       // Electronic
    intArrayOf(1, 1, 0, -1, 0, 2, 3, 2, 0, 1),      // Classical
    intArrayOf(-3, -2, -1, 2, 3, 4, 5, 3, 2, 1),    // Vocal enhancement
    intArrayOf(6, 5, 5, 3, 1, 0, 0, 0, 0, 0),       // Bass boost
    intArrayOf(0, 0, 0, 0, 1, 2, 4, 5, 6, 5),       // Treble boost
)

private fun isHeadsetOn(context: Context): Boolean {
    val am = context.getSystemService(AudioManager::class.java)
    return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        when (it.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET -> true
            else -> false
        }
    }
}

@Composable
fun OrealityScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    var service by remember { mutableStateOf<IOplusEffectService?>(null) }
    var enabled by remember { mutableStateOf(false) }
    var mode by remember { mutableIntStateOf(0) }
    var preset by remember { mutableIntStateOf(0) }
    val gains = remember { mutableStateListOf<Int>().apply { repeat(BAND_LABELS.size) { add(0) } } }

    fun refresh(s: IOplusEffectService?) {
        if (s == null) return
        try {
            val headset = isHeadsetOn(context)
            enabled = s.enabled
            mode = s.effectCategory
            preset = s.getCustomPresetEQType(headset)
            val g = s.getCustomGeqBandGains(headset)
            if (g != null && g.size == gains.size) {
                g.forEachIndexed { i, v -> gains[i] = v.coerceIn(EQ_MIN, EQ_MAX) }
            }
        } catch (e: RemoteException) {
            Log.e(TAG, "AudioEffectCenter query failed", e)
        } catch (e: RuntimeException) {
            Log.e(TAG, "AudioEffectCenter query failed", e)
        }
    }

    fun call(block: (IOplusEffectService) -> Unit) {
        val s = service ?: return
        try {
            block(s)
        } catch (e: RemoteException) {
            Log.e(TAG, "AudioEffectCenter call failed", e)
        } catch (e: RuntimeException) {
            Log.e(TAG, "AudioEffectCenter call failed", e)
        }
    }

    DisposableEffect(Unit) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val s = IOplusEffectService.Stub.asInterface(binder)
                service = s
                refresh(s)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
            }
        }
        val intent = Intent(ACTION_BIND).setPackage(SERVICE_PACKAGE)
        val bound = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot bind to AudioEffectCenter", e)
            false
        }
        onDispose {
            if (bound) context.unbindService(connection)
            service = null
        }
    }
    OnResume { refresh(service) }

    val connected = service != null
    val modeOptions = arrayOptions(context, R.array.oreality_mode_entries, R.array.oreality_mode_values)
    val presetOptions = arrayOptions(
        context, R.array.oreality_eq_preset_entries, R.array.oreality_eq_preset_values,
    )

    SettingsScroll(contentPadding) {
        PreferenceGroup(title = stringResource(R.string.oreality_title)) {
            item {
                SwitchPreference(
                    title = stringResource(R.string.oreality_title),
                    summary = stringResource(
                        if (connected) R.string.oreality_enable_summary
                        else R.string.oreality_unavailable,
                    ),
                    checked = enabled,
                    enabled = connected,
                    onCheckedChange = { on ->
                        call { it.setEnabled(on) }
                        enabled = on
                    },
                )
            }
            item {
                IconListPreference(
                    title = stringResource(R.string.oreality_mode_title),
                    options = modeOptions,
                    value = mode.toString(),
                    enabled = connected && enabled,
                    icon = R.drawable.ic_settings_device,
                    onValueChange = { v ->
                        call { it.setEffectCategory(v.toInt()) }
                        mode = v.toInt()
                    },
                )
            }
        }

        if (mode == MODE_CUSTOM) {
            PreferenceGroup(title = stringResource(R.string.oreality_eq_title)) {
                item {
                    IconListPreference(
                        title = stringResource(R.string.oreality_eq_preset_title),
                        options = presetOptions,
                        value = preset.toString(),
                        enabled = connected && enabled,
                        icon = R.drawable.ic_settings_device,
                        onValueChange = { v ->
                            val p = v.toInt()
                            if (p in 1..PRESETS.size) {
                                PRESETS[p - 1].forEachIndexed { i, g -> gains[i] = g }
                            }
                            call { it.setCustomGeqBandGains(gains.toIntArray(), isHeadsetOn(context), p) }
                            preset = p
                        },
                    )
                }
                BAND_LABELS.forEachIndexed { i, label ->
                    item {
                        CustomSeekBar(
                            title = label,
                            value = gains[i],
                            min = EQ_MIN,
                            max = EQ_MAX,
                            defaultValue = 0,
                            enabled = connected && enabled,
                            formatValue = { if (it > 0) "+$it" else it.toString() },
                            onValueChange = { v ->
                                gains[i] = v
                                // Moving a band turns the curve into the user's custom one.
                                call { it.setCustomGeqBandGains(gains.toIntArray(), isHeadsetOn(context), 0) }
                                preset = 0
                            },
                        )
                    }
                }
            }
        }
    }
}
