/*
 * SPDX-FileCopyrightText: 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.device.settings.audio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.RemoteException
import android.os.UserHandle
import android.util.Log
import com.oplus.audio.effectcenter.IOplusEffectService

/**
 * Only one sound enhancement engine runs at a time: O-Reality (the OnePlus AudioX effect,
 * driven by AudioEffectCenter) or Dolby Atmos (DAP). The chosen mode is stored here and
 * enforced at boot, on playback start and on output changes, because AudioEffectCenter
 * restores its own state and the global DAP effect can lose its settings when audio restarts.
 * All engine calls run on one background thread.
 */
object AudioEffects {
    const val MODE_OFF = 0
    const val MODE_OREALITY = 1
    const val MODE_DOLBY = 2

    private const val TAG = "AudioEffects"
    private const val PREFS = "audio_effects"
    private const val KEY_MODE = "mode"
    private const val KEY_LAST_OTHER_MODE = "last_other_mode"
    private const val REAPPLY_DELAY_MS = 500L

    const val OREALITY_PACKAGE = "com.oplus.audio.effectcenter"
    const val OREALITY_ACTION = "com.oplus.audioxeffect.controlservice.START"

    private val thread = HandlerThread(TAG).apply { start() }
    private val handler = Handler(thread.looper)
    private val reapply = Runnable { reapplyNow() }
    private var appContext: Context? = null
    private var monitoring = false

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getMode(context: Context): Int = prefs(context).getInt(KEY_MODE, MODE_OFF)

    fun setMode(context: Context, mode: Int) {
        val p = prefs(context)
        val old = p.getInt(KEY_MODE, MODE_OFF)
        val edit = p.edit().putInt(KEY_MODE, mode)
        if (mode == MODE_DOLBY && old != MODE_DOLBY) edit.putInt(KEY_LAST_OTHER_MODE, old)
        edit.apply()
        apply(context)
    }

    /** Toggles Dolby, returning to the previous mode when it is switched off (QS tile). */
    fun toggleDolby(context: Context) {
        if (getMode(context) == MODE_DOLBY) {
            setMode(context, prefs(context).getInt(KEY_LAST_OTHER_MODE, MODE_OREALITY))
        } else {
            setMode(context, MODE_DOLBY)
        }
    }

    /** Enforces the stored mode; on first run, adopts O-Reality's current state instead. */
    fun apply(context: Context) {
        val app = context.applicationContext
        appContext = app
        handler.post {
            if (!prefs(app).contains(KEY_MODE)) {
                adoptInitialMode(app)
                return@post
            }
            when (getMode(app)) {
                MODE_DOLBY -> {
                    DolbyController.get(app).setEnabled(true)
                    setOrealityEnabled(app, false)
                }
                MODE_OREALITY -> {
                    DolbyController.get(app).disable()
                    setOrealityEnabled(app, true)
                }
                else -> {
                    DolbyController.get(app).disable()
                    setOrealityEnabled(app, false)
                }
            }
        }
    }

    /** Re-applies the mode whenever playback starts or the output device changes. */
    fun startMonitoring(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (monitoring) return
        monitoring = true
        val am = app.getSystemService(AudioManager::class.java)
        am.registerAudioPlaybackCallback(object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                if (configs.any { it.isActive }) scheduleReapply()
            }
        }, handler)
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<AudioDeviceInfo>) {
                scheduleReapply(deviceChanged = true)
            }

            override fun onAudioDevicesRemoved(removed: Array<AudioDeviceInfo>) {
                scheduleReapply(deviceChanged = true)
            }
        }, handler)
    }

    private var pendingDeviceChange = false

    private fun scheduleReapply(deviceChanged: Boolean = false) {
        if (deviceChanged) pendingDeviceChange = true
        handler.removeCallbacks(reapply)
        handler.postDelayed(reapply, REAPPLY_DELAY_MS)
    }

    private fun reapplyNow() {
        val app = appContext ?: return
        val deviceChanged = pendingDeviceChange
        pendingDeviceChange = false
        when (getMode(app)) {
            MODE_DOLBY -> {
                DolbyController.get(app).reapply()
                if (deviceChanged) setOrealityEnabled(app, false)
            }
            MODE_OFF -> if (deviceChanged) setOrealityEnabled(app, false)
        }
    }

    private fun adoptInitialMode(app: Context) {
        withOreality(app) { service ->
            val enabled = service?.let {
                try {
                    it.enabled
                } catch (e: RemoteException) {
                    false
                }
            } ?: false
            prefs(app).edit()
                .putInt(KEY_MODE, if (enabled) MODE_OREALITY else MODE_OFF)
                .apply()
        }
    }

    private fun setOrealityEnabled(app: Context, enabled: Boolean) {
        withOreality(app) { service ->
            try {
                if (service != null && service.enabled != enabled) service.setEnabled(enabled)
            } catch (e: RemoteException) {
                Log.e(TAG, "AudioEffectCenter call failed", e)
            } catch (e: RuntimeException) {
                Log.e(TAG, "AudioEffectCenter call failed", e)
            }
        }
    }

    /** Binds AudioEffectCenter for one call; [block] gets null when it is not installed. */
    private fun withOreality(app: Context, block: (IOplusEffectService?) -> Unit) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                handler.post {
                    block(IOplusEffectService.Stub.asInterface(binder))
                    try {
                        app.unbindService(this)
                    } catch (e: IllegalArgumentException) {
                        // already unbound
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {}
        }
        val intent = Intent(OREALITY_ACTION).setPackage(OREALITY_PACKAGE)
        val bound = try {
            app.bindServiceAsUser(intent, connection, Context.BIND_AUTO_CREATE, UserHandle.SYSTEM)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Cannot bind to AudioEffectCenter", e)
            false
        }
        if (!bound) {
            try {
                app.unbindService(connection)
            } catch (e: IllegalArgumentException) {
                // never bound
            }
            block(null)
        }
    }
}
