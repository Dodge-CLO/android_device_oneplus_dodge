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
import androidx.compose.foundation.layout.ColumnScope
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
import com.android.axion.compose.preferences.ClickablePreference
import com.android.axion.compose.preferences.PreferenceGroup
import com.android.axion.compose.preferences.SwitchPreference
import com.oplus.audio.effectcenter.IOplusEffectService
import org.lineageos.device.settings.R
import org.lineageos.device.settings.audio.AudioEffects
import org.lineageos.device.settings.audio.DolbyController
import org.lineageos.device.settings.ui.CustomSeekBar
import org.lineageos.device.settings.ui.IconListPreference
import org.lineageos.device.settings.ui.OnResume
import org.lineageos.device.settings.ui.SettingsScroll
import org.lineageos.device.settings.ui.arrayOptions

private const val TAG = "AudioEffectsScreen"

@Composable
fun AudioEffectsScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    var mode by remember { mutableIntStateOf(AudioEffects.getMode(context)) }
    OnResume { mode = AudioEffects.getMode(context) }
    val modeOptions = arrayOptions(
        context, R.array.audio_effects_mode_entries, R.array.audio_effects_mode_values,
    )

    SettingsScroll(contentPadding) {
        PreferenceGroup(title = stringResource(R.string.audio_effects_title)) {
            item {
                IconListPreference(
                    title = stringResource(R.string.audio_effects_mode_title),
                    options = modeOptions,
                    value = mode.toString(),
                    icon = R.drawable.ic_settings_device,
                    onValueChange = { v ->
                        mode = v.toInt()
                        AudioEffects.setMode(context, mode)
                    },
                )
            }
        }
        when (mode) {
            AudioEffects.MODE_OREALITY -> OrealityControls()
            AudioEffects.MODE_DOLBY -> DolbyControls()
        }
    }
}

// ----- O-Reality -----

private const val OREALITY_MODE_CUSTOM = 6
private const val OREALITY_EQ_MIN = -6
private const val OREALITY_EQ_MAX = 6
private val OREALITY_BANDS = listOf(
    "31 Hz", "62 Hz", "125 Hz", "250 Hz", "500 Hz",
    "1 kHz", "2 kHz", "4 kHz", "8 kHz", "16 kHz",
)

/** Stock presets, indexed by preset type - 1 (type 0 is the user's custom curve). */
private val OREALITY_PRESETS = listOf(
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
private fun ColumnScope.OrealityControls() {
    val context = LocalContext.current
    var service by remember { mutableStateOf<IOplusEffectService?>(null) }
    var effectMode by remember { mutableIntStateOf(0) }
    var preset by remember { mutableIntStateOf(0) }
    val gains = remember {
        mutableStateListOf<Int>().apply { repeat(OREALITY_BANDS.size) { add(0) } }
    }

    fun refresh(s: IOplusEffectService?) {
        if (s == null) return
        try {
            val headset = isHeadsetOn(context)
            effectMode = s.effectCategory
            preset = s.getCustomPresetEQType(headset)
            val g = s.getCustomGeqBandGains(headset)
            if (g != null && g.size == gains.size) {
                g.forEachIndexed { i, v -> gains[i] = v.coerceIn(OREALITY_EQ_MIN, OREALITY_EQ_MAX) }
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
        val intent = Intent(AudioEffects.OREALITY_ACTION).setPackage(AudioEffects.OREALITY_PACKAGE)
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

    PreferenceGroup(title = stringResource(R.string.oreality_title)) {
        item {
            IconListPreference(
                title = stringResource(R.string.oreality_mode_title),
                options = modeOptions,
                value = effectMode.toString(),
                enabled = connected,
                icon = R.drawable.ic_settings_device,
                onValueChange = { v ->
                    call { it.setEffectCategory(v.toInt()) }
                    effectMode = v.toInt()
                },
            )
        }
        if (!connected) {
            item {
                ClickablePreference(
                    title = stringResource(R.string.oreality_unavailable),
                    enabled = false,
                    onClick = {},
                )
            }
        }
    }

    if (effectMode == OREALITY_MODE_CUSTOM) {
        PreferenceGroup(title = stringResource(R.string.oreality_eq_title)) {
            item {
                IconListPreference(
                    title = stringResource(R.string.oreality_eq_preset_title),
                    options = presetOptions,
                    value = preset.toString(),
                    enabled = connected,
                    icon = R.drawable.ic_settings_device,
                    onValueChange = { v ->
                        val p = v.toInt()
                        if (p in 1..OREALITY_PRESETS.size) {
                            OREALITY_PRESETS[p - 1].forEachIndexed { i, g -> gains[i] = g }
                        }
                        call { it.setCustomGeqBandGains(gains.toIntArray(), isHeadsetOn(context), p) }
                        preset = p
                    },
                )
            }
            OREALITY_BANDS.forEachIndexed { i, label ->
                item {
                    CustomSeekBar(
                        title = label,
                        value = gains[i],
                        min = OREALITY_EQ_MIN,
                        max = OREALITY_EQ_MAX,
                        defaultValue = 0,
                        enabled = connected,
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

// ----- Dolby Atmos -----

private const val DOLBY_EQ_STEP = 10 // engine gains are tenths of a dB
private const val DOLBY_EQ_MAX_DB = 15

private fun formatFrequency(hz: Int): String =
    if (hz >= 1000) "%.1f kHz".format(hz / 1000f).replace(".0 ", " ") else "$hz Hz"

@Composable
private fun ColumnScope.DolbyControls() {
    val context = LocalContext.current
    val dolby = remember { DolbyController.get(context) }
    var available by remember { mutableStateOf(dolby.available) }
    var profile by remember { mutableIntStateOf(dolby.getProfile()) }
    // Bumped whenever the profile changes or is reset, so every control re-reads its value.
    var revision by remember { mutableIntStateOf(0) }

    OnResume {
        available = dolby.available
        profile = dolby.getProfile()
        revision++
    }

    val profileOptions = arrayOptions(
        context, R.array.dolby_profile_entries, R.array.dolby_profile_values,
    )
    val ieqOptions = arrayOptions(context, R.array.dolby_ieq_entries, R.array.dolby_ieq_values)
    val presetNames = context.resources.getStringArray(R.array.dolby_preset_entries)

    var ieq by remember(revision) { mutableIntStateOf(dolby.getIeq()) }
    var dialogue by remember(revision) { mutableStateOf(dolby.getDialogue()) }
    var dialogueAmount by remember(revision) { mutableIntStateOf(dolby.getDialogueAmount()) }
    var bass by remember(revision) { mutableStateOf(dolby.getBass()) }
    var leveler by remember(revision) { mutableStateOf(dolby.getVolumeLeveler()) }
    var hpVirt by remember(revision) { mutableStateOf(dolby.getHeadphoneVirtualizer()) }
    var spkVirt by remember(revision) { mutableStateOf(dolby.getSpeakerVirtualizer()) }
    var widening by remember(revision) { mutableIntStateOf(dolby.getStereoWidening()) }
    var presetIndex by remember(revision) { mutableIntStateOf(dolby.matchingPreset()) }
    val bands = remember(revision) {
        mutableStateListOf<Int>().apply { dolby.getGeq10().forEach { add(it) } }
    }

    PreferenceGroup(title = stringResource(R.string.dolby_title)) {
        if (!available) {
            item {
                ClickablePreference(
                    title = stringResource(R.string.dolby_unavailable),
                    enabled = false,
                    onClick = {},
                )
            }
        }
        item {
            IconListPreference(
                title = stringResource(R.string.dolby_profile_title),
                options = profileOptions,
                value = profile.toString(),
                enabled = available,
                icon = R.drawable.ic_settings_device,
                onValueChange = { v ->
                    profile = v.toInt()
                    dolby.setProfile(profile)
                    revision++
                },
            )
        }
        item {
            IconListPreference(
                title = stringResource(R.string.dolby_ieq_title),
                options = ieqOptions,
                value = ieq.toString(),
                enabled = available,
                icon = R.drawable.ic_settings_device,
                onValueChange = { v ->
                    ieq = v.toInt()
                    dolby.setIeq(ieq)
                },
            )
        }
    }

    PreferenceGroup(title = stringResource(R.string.dolby_sound_title)) {
        item {
            SwitchPreference(
                title = stringResource(R.string.dolby_dialogue_title),
                summary = stringResource(R.string.dolby_dialogue_summary),
                checked = dialogue,
                enabled = available,
                onCheckedChange = {
                    dialogue = it
                    dolby.setDialogue(it)
                },
            )
        }
        if (dialogue) {
            item {
                CustomSeekBar(
                    title = stringResource(R.string.dolby_dialogue_amount_title),
                    value = dialogueAmount,
                    min = 1,
                    max = 12,
                    defaultValue = 6,
                    enabled = available,
                    onValueChange = {
                        dialogueAmount = it
                        dolby.setDialogueAmount(it)
                    },
                )
            }
        }
        item {
            SwitchPreference(
                title = stringResource(R.string.dolby_bass_title),
                summary = stringResource(R.string.dolby_bass_summary),
                checked = bass,
                enabled = available,
                onCheckedChange = {
                    bass = it
                    dolby.setBass(it)
                },
            )
        }
        item {
            SwitchPreference(
                title = stringResource(R.string.dolby_leveler_title),
                summary = stringResource(R.string.dolby_leveler_summary),
                checked = leveler,
                enabled = available,
                onCheckedChange = {
                    leveler = it
                    dolby.setVolumeLeveler(it)
                },
            )
        }
        item {
            SwitchPreference(
                title = stringResource(R.string.dolby_hp_virtualizer_title),
                summary = stringResource(R.string.dolby_hp_virtualizer_summary),
                checked = hpVirt,
                enabled = available,
                onCheckedChange = {
                    hpVirt = it
                    dolby.setHeadphoneVirtualizer(it)
                },
            )
        }
        item {
            SwitchPreference(
                title = stringResource(R.string.dolby_spk_virtualizer_title),
                summary = stringResource(R.string.dolby_spk_virtualizer_summary),
                checked = spkVirt,
                enabled = available,
                onCheckedChange = {
                    spkVirt = it
                    dolby.setSpeakerVirtualizer(it)
                },
            )
        }
        if (hpVirt || spkVirt) {
            item {
                CustomSeekBar(
                    title = stringResource(R.string.dolby_widening_title),
                    value = widening,
                    min = 4,
                    max = 64,
                    defaultValue = 32,
                    enabled = available,
                    onValueChange = {
                        widening = it
                        dolby.setStereoWidening(it)
                    },
                )
            }
        }
    }

    PreferenceGroup(title = stringResource(R.string.dolby_eq_title)) {
        item {
            IconListPreference(
                title = stringResource(R.string.dolby_eq_preset_title),
                options = presetNames.mapIndexed { i, name -> i.toString() to name } +
                    ("-1" to stringResource(R.string.dolby_eq_custom)),
                value = presetIndex.toString(),
                enabled = available,
                icon = R.drawable.ic_settings_device,
                onValueChange = { v ->
                    val i = v.toInt()
                    if (i >= 0) {
                        dolby.applyPreset(i)
                        dolby.getGeq10().forEachIndexed { b, g -> bands[b] = g }
                    }
                    presetIndex = i
                },
            )
        }
        DolbyController.BAND_FREQUENCIES.forEachIndexed { i, hz ->
            item {
                CustomSeekBar(
                    title = formatFrequency(hz),
                    value = bands[i] / DOLBY_EQ_STEP,
                    min = -DOLBY_EQ_MAX_DB,
                    max = DOLBY_EQ_MAX_DB,
                    defaultValue = 0,
                    enabled = available,
                    formatValue = { if (it > 0) "+$it dB" else "$it dB" },
                    onValueChange = { v ->
                        bands[i] = v * DOLBY_EQ_STEP
                        dolby.setGeq10(bands.toIntArray())
                        presetIndex = dolby.matchingPreset()
                    },
                )
            }
        }
        item {
            ClickablePreference(
                title = stringResource(R.string.dolby_reset_title),
                summary = stringResource(R.string.dolby_reset_summary),
                enabled = available,
                onClick = {
                    dolby.resetProfile()
                    revision++
                },
            )
        }
    }
}
