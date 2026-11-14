/*
 * Copyright (C) 2018-2024 crDroid Android Project
 * Copyright (C) 2025 AlphaDroid
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lineageos.device.settings.ui.screens

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemProperties
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.android.axion.compose.preferences.ClickablePreference
import com.android.axion.compose.preferences.PreferenceGroup
import com.android.axion.compose.preferences.SwitchPreference
import org.lineageos.device.settings.Constants
import org.lineageos.device.settings.DeviceSettings
import org.lineageos.device.settings.R
import org.lineageos.device.settings.display.AodBrightnessController
import org.lineageos.device.settings.display.DisplayModeController
import org.lineageos.device.settings.display.HbmController
import org.lineageos.device.settings.display.PwmController
import org.lineageos.device.settings.display.SunlightBoostController
import org.lineageos.device.settings.fastcharge.FastChargeController
import org.lineageos.device.settings.ui.IconListPreference
import org.lineageos.device.settings.ui.OnResume
import org.lineageos.device.settings.ui.PrefIcon
import org.lineageos.device.settings.ui.appLabel
import org.lineageos.device.settings.ui.arrayOptions
import org.lineageos.device.settings.ui.defaultPrefs
import org.lineageos.device.settings.ui.picker.AppPickerDialog
import org.lineageos.device.settings.ui.picker.HbmWarningDialog
import org.lineageos.device.settings.ui.picker.PackGridPreference
import org.lineageos.device.settings.ui.SettingsScroll
import org.lineageos.device.settings.utils.FileUtils

private const val TAG = "DeviceSettings"
private const val KEY_SHOW_HBM_WARNING = "hbm_warning"
private const val UDFPS_ICONS_PKG = "com.crdroid.udfps.icons"
private const val UDFPS_ANIMATIONS_PKG = "com.crdroid.udfps.animations"

/** Index and label of each entry of [array] in a UDFPS resource pack; empty without the pack. */
private fun udfpsPackOptions(context: Context, pkg: String, array: String): List<Pair<String, String>> =
    try {
        val res = context.packageManager.getResourcesForApplication(pkg)
        val id = res.getIdentifier(array, "array", pkg)
        if (id == 0) {
            emptyList()
        } else {
            res.getStringArray(id).mapIndexed { i, name -> i.toString() to udfpsLabel(name) }
        }
    } catch (e: PackageManager.NameNotFoundException) {
        emptyList()
    }

/** Raw string-array [array] of a UDFPS resource pack; empty without the pack. */
private fun udfpsPackNames(context: Context, pkg: String, array: String): List<String> =
    try {
        val res = context.packageManager.getResourcesForApplication(pkg)
        val id = res.getIdentifier(array, "array", pkg)
        if (id == 0) emptyList() else res.getStringArray(id).toList()
    } catch (e: PackageManager.NameNotFoundException) {
        emptyList()
    }

/** Turns a drawable name such as "udfps_icon_oneplus_red" into "Oneplus red". */
private fun udfpsLabel(name: String): String {
    if (name.contains(' ')) return name
    val words = name.removePrefix("udfps_icon_").removePrefix("udfps_").removePrefix("fod_")
        .replace('_', ' ').trim()
    return words.replaceFirstChar { it.uppercase() }.ifEmpty { name }
}

private fun systemInt(context: Context, key: String): Int =
    Settings.System.getIntForUser(context.contentResolver, key, 0, UserHandle.USER_CURRENT)

private fun putSystemInt(context: Context, key: String, value: Int) {
    Settings.System.putIntForUser(context.contentResolver, key, value, UserHandle.USER_CURRENT)
}

@Composable
fun MainScreen(
    contentPadding: PaddingValues,
    onOpenBypass: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { defaultPrefs(context) }
    val udfpsIcons = remember {
        udfpsPackOptions(context, UDFPS_ICONS_PKG, "udfps_icons")
    }
    val udfpsAnimations = remember {
        udfpsPackOptions(context, UDFPS_ANIMATIONS_PKG, "udfps_animation_titles")
    }
    val udfpsIconPreviews = remember { udfpsPackNames(context, UDFPS_ICONS_PKG, "udfps_icons") }
    val udfpsAnimationPreviews = remember {
        udfpsPackNames(context, UDFPS_ANIMATIONS_PKG, "udfps_animation_previews")
    }
    var udfpsIcon by remember {
        mutableStateOf(systemInt(context, Settings.System.UDFPS_ICON).toString())
    }
    var udfpsAnimation by remember {
        mutableStateOf(systemInt(context, Settings.System.UDFPS_ANIM_STYLE).toString())
    }
    val display = remember { DisplayModeController.getInstance(context) }
    val hbmController = remember { HbmController.getInstance(context) }
    val pwmController = remember { PwmController.getInstance(context) }
    val aodController = remember { AodBrightnessController.getInstance(context) }
    val fastCharge = remember { FastChargeController.getInstance(context) }
    val sunlightBoost = remember { SunlightBoostController.getInstance(context) }

    val defaultUsage = remember {
        context.getString(R.string.config_defaultNotificationSliderUsage)
    }
    var usage by remember {
        mutableStateOf(prefs.getString(Constants.KEY_NOTIF_SLIDER_USAGE, defaultUsage)!!)
    }
    val initialActions = remember {
        val storedTop = prefs.getString(Constants.KEY_NOTIF_SLIDER_ACTION_TOP, "") ?: ""
        val storedMiddle = prefs.getString(Constants.KEY_NOTIF_SLIDER_ACTION_MIDDLE, "") ?: ""
        val storedBottom = prefs.getString(Constants.KEY_NOTIF_SLIDER_ACTION_BOTTOM, "") ?: ""
        if (storedTop.isNotEmpty() && storedMiddle.isNotEmpty() && storedBottom.isNotEmpty()) {
            Triple(storedTop, storedMiddle, storedBottom)
        } else {
            val defaultsId = DeviceSettings.defaultActionsResId(usage)
            val defaults = if (defaultsId != 0) context.resources.getStringArray(defaultsId)
            else emptyArray()
            Triple(
                storedTop.ifEmpty { defaults.getOrNull(0) ?: "" },
                storedMiddle.ifEmpty { defaults.getOrNull(1) ?: "" },
                storedBottom.ifEmpty { defaults.getOrNull(2) ?: "" },
            )
        }
    }
    var actionTop by remember { mutableStateOf(initialActions.first) }
    var actionMiddle by remember { mutableStateOf(initialActions.second) }
    var actionBottom by remember { mutableStateOf(initialActions.third) }
    var appTop by remember {
        mutableStateOf(prefs.getString(Constants.KEY_NOTIF_SLIDER_APP_TOP, "") ?: "")
    }
    var appMiddle by remember {
        mutableStateOf(prefs.getString(Constants.KEY_NOTIF_SLIDER_APP_MIDDLE, "") ?: "")
    }
    var appBottom by remember {
        mutableStateOf(prefs.getString(Constants.KEY_NOTIF_SLIDER_APP_BOTTOM, "") ?: "")
    }

    val pwmWritable = FileUtils.isFileWritable(Constants.NODE_ONEPULSE_PWM)
    val hbmWritable = FileUtils.isFileWritable(Constants.NODE_HBM)
    val aodPresent = FileUtils.isFileWritable(Constants.NODE_AOD_LIGHT_MODE)
    val fastSupported = fastCharge.isSupported

    var pwm by remember { mutableStateOf(pwmController.isPwmEnabled) }
    var hbm by remember { mutableStateOf(hbmController.isHbmEnabled) }
    var sunlight by remember {
        mutableStateOf(prefs.getBoolean(Constants.KEY_SUNLIGHT_BOOST, true))
    }
    var aodMode by remember { mutableStateOf(aodController.mode) }
    var fastOn by remember { mutableStateOf(fastCharge.isFastChargingEnabled) }
    var nightOn by remember { mutableStateOf(fastCharge.isNightModeEnabled) }
    var hapticProfile by remember {
        mutableStateOf(
            SystemProperties.get(Constants.PROP_HAPTIC_PROFILE, Constants.HAPTIC_PROFILE_DEFAULT)
        )
    }
    var showHbmWarning by remember { mutableStateOf(false) }
    var pickingSliderApp by remember { mutableStateOf<String?>(null) }

    fun syncHbmPwm() {
        var pwmEnabled = pwmController.isPwmEnabled
        var hbmEnabled = hbmController.isHbmEnabled
        if (pwmEnabled && hbmEnabled) {
            Log.i(TAG, "PWM is enabled, disabling HBM (PWM has priority)")
            hbmController.disableHbm()
            display.broadcastStateChange()
            hbmEnabled = false
        }
        pwm = pwmEnabled
        hbm = hbmEnabled
    }

    fun currentActions(): IntArray {
        fun parse(v: String) = v.toIntOrNull() ?: 0
        return intArrayOf(parse(actionTop), parse(actionMiddle), parse(actionBottom))
    }

    fun broadcast() {
        DeviceSettings.sendUpdateBroadcast(
            context.applicationContext,
            usage.toIntOrNull() ?: 0,
            currentActions(),
        )
    }

    fun applyUsage(newUsage: String) {
        usage = newUsage
        val defaultsId = DeviceSettings.defaultActionsResId(newUsage)
        if (defaultsId != 0) {
            val defaults = context.resources.getStringArray(defaultsId)
            if (defaults.size == 3) {
                actionTop = defaults[0]
                actionMiddle = defaults[1]
                actionBottom = defaults[2]
            }
        }
        prefs.edit()
            .putString(Constants.KEY_NOTIF_SLIDER_USAGE, newUsage)
            .putString(Constants.KEY_NOTIF_SLIDER_ACTION_TOP, actionTop)
            .putString(Constants.KEY_NOTIF_SLIDER_ACTION_MIDDLE, actionMiddle)
            .putString(Constants.KEY_NOTIF_SLIDER_ACTION_BOTTOM, actionBottom)
            .commit()
        broadcast()
    }

    fun applyAction(index: Int, value: String) {
        when (index) {
            0 -> actionTop = value
            1 -> actionMiddle = value
            2 -> actionBottom = value
        }
        val key = when (index) {
            0 -> Constants.KEY_NOTIF_SLIDER_ACTION_TOP
            1 -> Constants.KEY_NOTIF_SLIDER_ACTION_MIDDLE
            else -> Constants.KEY_NOTIF_SLIDER_ACTION_BOTTOM
        }
        prefs.edit().putString(key, value).apply()
        broadcast()
    }

    OnResume {
        syncHbmPwm()
        sunlight = prefs.getBoolean(Constants.KEY_SUNLIGHT_BOOST, true)
        if (fastSupported) {
            fastOn = fastCharge.isFastChargingEnabled
            nightOn = fastCharge.isNightModeEnabled
        }
        aodMode = aodController.mode
    }

    val appMode = usage == Constants.NOTIF_SLIDER_FOR_APPLAUNCH
    val actionArrays = DeviceSettings.sliderActionArrays(usage)
    val actionOptions = if (actionArrays != null) {
        arrayOptions(context, actionArrays.first, actionArrays.second)
    } else {
        emptyList()
    }
    val noneLabel = stringResource(R.string.notification_slider_app_none)

    SettingsScroll(contentPadding) {
        PreferenceGroup(title = stringResource(R.string.notification_slider_category_title)) {
            item {
                IconListPreference(
                    title = stringResource(R.string.notification_slider_usage_title),
                    options = arrayOptions(
                        context,
                        R.array.notification_slider_usage_entries,
                        R.array.notification_slider_usage_entry_values,
                    ),
                    value = usage,
                    onValueChange = { applyUsage(it) },
                    icon = R.drawable.ic_slider,
                )
            }
            if (!appMode) {
                item {
                    IconListPreference(
                        title = stringResource(R.string.notification_slider_top_position),
                        options = actionOptions,
                        value = actionTop,
                        onValueChange = { applyAction(0, it) },
                        icon = R.drawable.ic_up_icon,
                    )
                }
                item {
                    IconListPreference(
                        title = stringResource(R.string.notification_slider_middle_position),
                        options = actionOptions,
                        value = actionMiddle,
                        onValueChange = { applyAction(1, it) },
                        icon = R.drawable.ic_middle_icon,
                    )
                }
                item {
                    IconListPreference(
                        title = stringResource(R.string.notification_slider_bottom_position),
                        options = actionOptions,
                        value = actionBottom,
                        onValueChange = { applyAction(2, it) },
                        icon = R.drawable.ic_down_icon,
                    )
                }
            } else {
                item {
                    ClickablePreference(
                        title = stringResource(R.string.notification_slider_top_position),
                        summary = appLabel(context, appTop).ifEmpty { noneLabel },
                        customIcon = { PrefIcon(R.drawable.ic_up_icon) },
                        onClick = { pickingSliderApp = Constants.KEY_NOTIF_SLIDER_APP_TOP },
                    )
                }
                item {
                    ClickablePreference(
                        title = stringResource(R.string.notification_slider_middle_position),
                        summary = appLabel(context, appMiddle).ifEmpty { noneLabel },
                        customIcon = { PrefIcon(R.drawable.ic_middle_icon) },
                        onClick = { pickingSliderApp = Constants.KEY_NOTIF_SLIDER_APP_MIDDLE },
                    )
                }
                item {
                    ClickablePreference(
                        title = stringResource(R.string.notification_slider_bottom_position),
                        summary = appLabel(context, appBottom).ifEmpty { noneLabel },
                        customIcon = { PrefIcon(R.drawable.ic_down_icon) },
                        onClick = { pickingSliderApp = Constants.KEY_NOTIF_SLIDER_APP_BOTTOM },
                    )
                }
            }
        }

        if (udfpsIcons.isNotEmpty() || udfpsAnimations.isNotEmpty()) {
            PreferenceGroup(title = stringResource(R.string.udfps_category_title)) {
                if (udfpsIcons.isNotEmpty()) {
                    item {
                        PackGridPreference(
                            title = stringResource(R.string.udfps_icon_title),
                            pkg = UDFPS_ICONS_PKG,
                            options = udfpsIcons,
                            previews = udfpsIconPreviews,
                            value = udfpsIcon,
                            onValueChange = { value ->
                                putSystemInt(context, Settings.System.UDFPS_ICON, value.toInt())
                                udfpsIcon = value
                            },
                            icon = R.drawable.ic_udfps,
                        )
                    }
                }
                if (udfpsAnimations.isNotEmpty()) {
                    item {
                        PackGridPreference(
                            title = stringResource(R.string.udfps_animation_title),
                            pkg = UDFPS_ANIMATIONS_PKG,
                            options = udfpsAnimations,
                            previews = udfpsAnimationPreviews,
                            value = udfpsAnimation,
                            onValueChange = { value ->
                                putSystemInt(context, Settings.System.UDFPS_ANIM_STYLE, value.toInt())
                                udfpsAnimation = value
                            },
                            icon = R.drawable.ic_udfps,
                        )
                    }
                }
            }
        }

        PreferenceGroup(title = stringResource(R.string.display_category_title)) {
            item {
                SwitchPreference(
                    title = stringResource(R.string.onepulse_pwm_mode_title),
                    summary = stringResource(R.string.onepulse_pwm_mode_summary),
                    checked = pwm,
                    enabled = pwmWritable,
                    customIcon = { PrefIcon(R.drawable.ic_pwm) },
                    onCheckedChange = { enable ->
                        if (enable) {
                            if (!display.enablePwm()) return@SwitchPreference
                            Log.i(TAG, "PWM enabled")
                            pwm = true
                            hbm = false
                        } else {
                            if (!display.disablePwm()) return@SwitchPreference
                            Log.i(TAG, "PWM disabled")
                            pwm = false
                        }
                    },
                )
            }
            item {
                SwitchPreference(
                    title = stringResource(R.string.hbm_title),
                    summary = stringResource(R.string.hbm_summary),
                    checked = hbm,
                    enabled = hbmWritable && !pwm,
                    customIcon = { PrefIcon(R.drawable.ic_hbm) },
                    onCheckedChange = { enable ->
                        if (enable && pwmController.isPwmEnabled) {
                            Log.i(TAG, "Cannot enable HBM while PWM is active")
                            return@SwitchPreference
                        }
                        if (enable && prefs.getBoolean(KEY_SHOW_HBM_WARNING, true)) {
                            showHbmWarning = true
                            return@SwitchPreference
                        }
                        if (enable) {
                            if (!display.enableHbm()) return@SwitchPreference
                            Log.i(TAG, "HBM enabled")
                            hbm = true
                        } else {
                            if (!display.disableHbm()) return@SwitchPreference
                            Log.i(TAG, "HBM disabled")
                            hbm = false
                        }
                    },
                )
            }
            item {
                SwitchPreference(
                    title = stringResource(R.string.sunlight_boost_title),
                    summary = stringResource(R.string.sunlight_boost_summary),
                    checked = sunlight,
                    enabled = hbmWritable,
                    customIcon = { PrefIcon(R.drawable.ic_hbm) },
                    onCheckedChange = { enable ->
                        prefs.edit().putBoolean(Constants.KEY_SUNLIGHT_BOOST, enable).commit()
                        sunlightBoost.updateState()
                        sunlight = enable
                    },
                )
            }
            if (aodPresent) {
                item {
                    IconListPreference(
                        title = stringResource(R.string.aod_brightness_title),
                        options = arrayOptions(
                            context,
                            R.array.aod_brightness_entries,
                            R.array.aod_brightness_values,
                        ),
                        value = aodMode,
                        onValueChange = { mode ->
                            if (!aodController.setMode(mode)) {
                                Log.w(TAG, "Failed to set AOD brightness mode=$mode")
                            }
                            aodMode = aodController.mode
                        },
                        icon = R.drawable.ic_aod_brightness,
                    )
                }
            }
        }

        PreferenceGroup(title = stringResource(R.string.vibration_category_title)) {
            item {
                IconListPreference(
                    title = stringResource(R.string.haptic_profile_title),
                    options = arrayOptions(
                        context,
                        R.array.haptic_profile_entries,
                        R.array.haptic_profile_values,
                    ),
                    value = hapticProfile,
                    onValueChange = { profile ->
                        SystemProperties.set(Constants.PROP_HAPTIC_PROFILE, profile)
                        hapticProfile = profile
                        Log.i(TAG, "Haptic profile set to $profile")
                    },
                    icon = R.drawable.ic_vibration,
                )
            }
        }

        PreferenceGroup(title = stringResource(R.string.battery_category_title)) {
            item {
                ClickablePreference(
                    title = stringResource(R.string.bypass_charging_title),
                    summary = stringResource(R.string.bypass_charging_summary),
                    customIcon = { PrefIcon(R.drawable.ic_bypass_charging) },
                    onClick = onOpenBypass,
                )
            }
            item {
                SwitchPreference(
                    title = stringResource(R.string.fast_charging_title),
                    summary = stringResource(R.string.fast_charging_summary),
                    checked = fastOn,
                    enabled = fastSupported,
                    customIcon = { PrefIcon(R.drawable.ic_fast_charging) },
                    onCheckedChange = { enable ->
                        fastCharge.setFastChargingEnabled(enable)
                        fastOn = enable
                        Log.i(
                            TAG,
                            "Fast charging " + if (enable) "enabled (SuperVOOC 100W)" else "disabled",
                        )
                    },
                )
            }
            item {
                SwitchPreference(
                    title = stringResource(R.string.night_charging_title),
                    summary = stringResource(R.string.night_charging_summary),
                    checked = nightOn,
                    enabled = fastSupported && !fastOn,
                    customIcon = { PrefIcon(R.drawable.ic_night_charging) },
                    onCheckedChange = { enable ->
                        fastCharge.setNightModeEnabled(enable)
                        nightOn = enable
                        Log.i(
                            TAG,
                            "Night charging " + if (enable) "enabled" else "disabled",
                        )
                    },
                )
            }
        }

    }

    if (showHbmWarning) {
        HbmWarningDialog(
            onEnable = { dontShowAgain ->
                if (dontShowAgain) {
                    prefs.edit().putBoolean(KEY_SHOW_HBM_WARNING, false).apply()
                }
                if (display.enableHbm()) {
                    hbm = true
                    Log.i(TAG, "HBM enabled via dialog")
                } else {
                    Log.w(TAG, "Failed to enable HBM via dialog")
                }
                showHbmWarning = false
            },
            onCancel = { showHbmWarning = false },
        )
    }

    val pickerKey = pickingSliderApp
    if (pickerKey != null) {
        AppPickerDialog(
            title = stringResource(R.string.notification_slider_app_dialog_title),
            excluded = setOf(context.packageName),
            onPick = { pkg ->
                prefs.edit().putString(pickerKey, pkg).commit()
                when (pickerKey) {
                    Constants.KEY_NOTIF_SLIDER_APP_TOP -> appTop = pkg
                    Constants.KEY_NOTIF_SLIDER_APP_MIDDLE -> appMiddle = pkg
                    Constants.KEY_NOTIF_SLIDER_APP_BOTTOM -> appBottom = pkg
                }
                broadcast()
                pickingSliderApp = null
            },
            onDismiss = { pickingSliderApp = null },
            onClear = {
                prefs.edit().putString(pickerKey, "").commit()
                when (pickerKey) {
                    Constants.KEY_NOTIF_SLIDER_APP_TOP -> appTop = ""
                    Constants.KEY_NOTIF_SLIDER_APP_MIDDLE -> appMiddle = ""
                    Constants.KEY_NOTIF_SLIDER_APP_BOTTOM -> appBottom = ""
                }
                broadcast()
                pickingSliderApp = null
            },
        )
    }
}
