/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.device.settings.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.lineageos.device.settings.R
import org.lineageos.device.settings.ui.screens.BypassChargingScreen
import org.lineageos.device.settings.ui.screens.MainScreen
import org.lineageos.device.settings.ui.screens.AudioEffectsScreen

enum class DeviceSettingsDest(val route: String) {
    Main("main"),
    Bypass("bypass"),
    Oreality("oreality"),
}

@Composable
fun DeviceSettingsApp(
    start: DeviceSettingsDest,
    onFinish: () -> Unit,
) {
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: start.route
    BackHandler {
        if (!navController.popBackStack()) onFinish()
    }
    DeviceSettingsScaffold(
        title = if (route == DeviceSettingsDest.Oreality.route) {
            stringResource(R.string.audio_effects_title)
        } else null,
        onBack = {
            if (!navController.popBackStack()) onFinish()
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = start.route,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(DeviceSettingsDest.Main.route) {
                MainScreen(
                    contentPadding = padding,
                    onOpenBypass = { navController.navigate(DeviceSettingsDest.Bypass.route) },
                )
            }
            composable(DeviceSettingsDest.Bypass.route) {
                BypassChargingScreen(contentPadding = padding)
            }
            composable(DeviceSettingsDest.Oreality.route) {
                AudioEffectsScreen(contentPadding = padding)
            }
        }
    }
}
