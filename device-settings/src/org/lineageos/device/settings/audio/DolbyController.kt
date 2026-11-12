/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.device.settings.audio

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.lineageos.device.settings.R
import org.lineageos.device.settings.audio.DolbyAudioEffect.DsParam

/**
 * Holds the global Dolby DAP effect and the user's per-profile settings, which are stored here
 * and pushed to the engine again whenever the effect is (re)created.
 * Ported from the core of LunarisDolby's DolbyRepository.
 */
class DolbyController private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var effect: DolbyAudioEffect? = null

    val available: Boolean
        get() = effect() != null

    private fun profilePrefs(profile: Int): SharedPreferences =
        appContext.getSharedPreferences("$PREFS_PROFILE$profile", Context.MODE_PRIVATE)

    @Synchronized
    private fun effect(): DolbyAudioEffect? {
        effect?.let { current ->
            val ok = try {
                current.hasControl()
            } catch (e: RuntimeException) {
                false
            }
            if (ok) return current
            current.release()
            effect = null
        }
        effect = try {
            DolbyAudioEffect(EFFECT_PRIORITY, 0).also { restore(it) }
        } catch (e: RuntimeException) {
            Log.e(TAG, "Dolby effect unavailable", e)
            null
        }
        return effect
    }

    private inline fun <T> withEffect(default: T, block: (DolbyAudioEffect) -> T): T =
        synchronized(this) {
            val e = effect() ?: return default
            try {
                block(e)
            } catch (ex: RuntimeException) {
                Log.e(TAG, "Dolby call failed", ex)
                default
            }
        }

    private fun restore(e: DolbyAudioEffect) {
        val profile = prefs.getInt(KEY_PROFILE, 0)
        try {
            e.profile = profile
            applyProfile(e, profile)
            e.dsOn = prefs.getBoolean(KEY_ENABLED, false)
        } catch (ex: RuntimeException) {
            Log.e(TAG, "Failed to restore Dolby state", ex)
        }
    }

    private fun applyProfile(e: DolbyAudioEffect, profile: Int) {
        val p = profilePrefs(profile)
        if (!p.contains(KEY_IEQ)) return // never changed: keep the engine's profile defaults
        e.setDapParameter(DsParam.IEQ_PRESET, p.getInt(KEY_IEQ, 0), profile)
        e.setDapParameter(DsParam.HEADPHONE_VIRTUALIZER, p.getBoolean(KEY_HP_VIRT, false), profile)
        e.setDapParameter(DsParam.SPEAKER_VIRTUALIZER, p.getBoolean(KEY_SPK_VIRT, false), profile)
        e.setDapParameter(DsParam.STEREO_WIDENING_AMOUNT, p.getInt(KEY_WIDENING, 32), profile)
        e.setDapParameter(DsParam.DIALOGUE_ENHANCER_ENABLE, p.getBoolean(KEY_DIALOGUE, false), profile)
        e.setDapParameter(DsParam.DIALOGUE_ENHANCER_AMOUNT, p.getInt(KEY_DIALOGUE_AMOUNT, 6), profile)
        e.setDapParameter(DsParam.BASS_ENHANCER_ENABLE, p.getBoolean(KEY_BASS, false), profile)
        e.setDapParameter(DsParam.VOLUME_LEVELER_ENABLE, p.getBoolean(KEY_LEVELER, false), profile)
        p.getString(KEY_GEQ, null)?.split(",")?.mapNotNull { it.toIntOrNull() }
            ?.takeIf { it.size == GEQ_BANDS }
            ?.let { e.setDapParameter(DsParam.GEQ_BAND_GAINS, it.toIntArray(), profile) }
    }

    /** Persists every current engine value of [profile] so it survives effect recreation. */
    private fun snapshot(e: DolbyAudioEffect, profile: Int) {
        val p = profilePrefs(profile)
        if (p.contains(KEY_IEQ)) return
        p.edit()
            .putInt(KEY_IEQ, e.getDapParameter(DsParam.IEQ_PRESET, profile)[0])
            .putBoolean(KEY_HP_VIRT, e.getDapParameter(DsParam.HEADPHONE_VIRTUALIZER, profile)[0] == 1)
            .putBoolean(KEY_SPK_VIRT, e.getDapParameter(DsParam.SPEAKER_VIRTUALIZER, profile)[0] == 1)
            .putInt(KEY_WIDENING, e.getDapParameter(DsParam.STEREO_WIDENING_AMOUNT, profile)[0])
            .putBoolean(KEY_DIALOGUE, e.getDapParameter(DsParam.DIALOGUE_ENHANCER_ENABLE, profile)[0] == 1)
            .putInt(KEY_DIALOGUE_AMOUNT, e.getDapParameter(DsParam.DIALOGUE_ENHANCER_AMOUNT, profile)[0])
            .putBoolean(KEY_BASS, e.getDapParameter(DsParam.BASS_ENHANCER_ENABLE, profile)[0] == 1)
            .putBoolean(KEY_LEVELER, e.getDapParameter(DsParam.VOLUME_LEVELER_ENABLE, profile)[0] == 1)
            .putString(KEY_GEQ, e.getDapParameter(DsParam.GEQ_BAND_GAINS, profile).joinToString(","))
            .apply()
    }

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        withEffect(Unit) { it.dsOn = on }
    }

    fun getProfile(): Int = prefs.getInt(KEY_PROFILE, 0)

    fun setProfile(profile: Int) {
        prefs.edit().putInt(KEY_PROFILE, profile).apply()
        withEffect(Unit) {
            it.profile = profile
            applyProfile(it, profile)
        }
    }

    private fun setParam(key: String, param: DsParam, value: Int) {
        val profile = getProfile()
        withEffect(Unit) {
            snapshot(it, profile)
            it.setDapParameter(param, value, profile)
        }
        profilePrefs(profile).edit().putInt(key, value).apply()
    }

    private fun setParam(key: String, param: DsParam, value: Boolean) {
        val profile = getProfile()
        withEffect(Unit) {
            snapshot(it, profile)
            it.setDapParameter(param, value, profile)
        }
        profilePrefs(profile).edit().putBoolean(key, value).apply()
    }

    private fun readInt(key: String, param: DsParam, default: Int): Int {
        val p = profilePrefs(getProfile())
        if (p.contains(key)) return p.getInt(key, default)
        return withEffect(default) { it.getDapParameter(param, getProfile())[0] }
    }

    private fun readBool(key: String, param: DsParam): Boolean {
        val p = profilePrefs(getProfile())
        if (p.contains(key)) return p.getBoolean(key, false)
        return withEffect(false) { it.getDapParameter(param, getProfile())[0] == 1 }
    }

    fun getIeq() = readInt(KEY_IEQ, DsParam.IEQ_PRESET, 0)
    fun setIeq(v: Int) = setParam(KEY_IEQ, DsParam.IEQ_PRESET, v)
    fun getHeadphoneVirtualizer() = readBool(KEY_HP_VIRT, DsParam.HEADPHONE_VIRTUALIZER)
    fun setHeadphoneVirtualizer(v: Boolean) = setParam(KEY_HP_VIRT, DsParam.HEADPHONE_VIRTUALIZER, v)
    fun getSpeakerVirtualizer() = readBool(KEY_SPK_VIRT, DsParam.SPEAKER_VIRTUALIZER)
    fun setSpeakerVirtualizer(v: Boolean) = setParam(KEY_SPK_VIRT, DsParam.SPEAKER_VIRTUALIZER, v)
    fun getStereoWidening() = readInt(KEY_WIDENING, DsParam.STEREO_WIDENING_AMOUNT, 32)
    fun setStereoWidening(v: Int) = setParam(KEY_WIDENING, DsParam.STEREO_WIDENING_AMOUNT, v)
    fun getDialogue() = readBool(KEY_DIALOGUE, DsParam.DIALOGUE_ENHANCER_ENABLE)
    fun setDialogue(v: Boolean) = setParam(KEY_DIALOGUE, DsParam.DIALOGUE_ENHANCER_ENABLE, v)
    fun getDialogueAmount() = readInt(KEY_DIALOGUE_AMOUNT, DsParam.DIALOGUE_ENHANCER_AMOUNT, 6)
    fun setDialogueAmount(v: Int) = setParam(KEY_DIALOGUE_AMOUNT, DsParam.DIALOGUE_ENHANCER_AMOUNT, v)
    fun getBass() = readBool(KEY_BASS, DsParam.BASS_ENHANCER_ENABLE)
    fun setBass(v: Boolean) = setParam(KEY_BASS, DsParam.BASS_ENHANCER_ENABLE, v)
    fun getVolumeLeveler() = readBool(KEY_LEVELER, DsParam.VOLUME_LEVELER_ENABLE)
    fun setVolumeLeveler(v: Boolean) = setParam(KEY_LEVELER, DsParam.VOLUME_LEVELER_ENABLE, v)

    /** Full 20-band engine curve, in tenths of a dB. */
    fun getGeq(): IntArray {
        profilePrefs(getProfile()).getString(KEY_GEQ, null)
            ?.split(",")?.mapNotNull { it.toIntOrNull() }
            ?.takeIf { it.size == GEQ_BANDS }
            ?.let { return it.toIntArray() }
        return withEffect(IntArray(GEQ_BANDS)) {
            it.getDapParameter(DsParam.GEQ_BAND_GAINS, getProfile())
        }.let { if (it.size == GEQ_BANDS) it else IntArray(GEQ_BANDS) }
    }

    fun setGeq(gains: IntArray) {
        if (gains.size != GEQ_BANDS) return
        val profile = getProfile()
        withEffect(Unit) {
            snapshot(it, profile)
            it.setDapParameter(DsParam.GEQ_BAND_GAINS, gains, profile)
        }
        profilePrefs(profile).edit().putString(KEY_GEQ, gains.joinToString(",")).apply()
    }

    /** The ten shown bands are every other engine band. */
    fun getGeq10(): IntArray = getGeq().filterIndexed { i, _ -> i % 2 == 0 }.toIntArray()

    fun setGeq10(bands: IntArray) {
        val g = IntArray(GEQ_BANDS)
        bands.forEachIndexed { i, v -> if (i * 2 < GEQ_BANDS) g[i * 2] = v }
        for (i in 1 until GEQ_BANDS - 1 step 2) g[i] = (g[i - 1] + g[i + 1]) / 2
        g[GEQ_BANDS - 1] = g[GEQ_BANDS - 2]
        setGeq(g)
    }

    /** Index into R.array.dolby_preset_values whose curve matches the current one, or -1. */
    fun matchingPreset(): Int {
        val current = getGeq10().toList()
        appContext.resources.getStringArray(R.array.dolby_preset_values).forEachIndexed { i, s ->
            val g = s.split(",").mapNotNull { it.trim().toIntOrNull() }
            if (g.size == GEQ_BANDS && g.filterIndexed { j, _ -> j % 2 == 0 } == current) return i
        }
        return -1
    }

    fun applyPreset(index: Int) {
        val values = appContext.resources.getStringArray(R.array.dolby_preset_values)
        val g = values.getOrNull(index)?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: return
        if (g.size == GEQ_BANDS) setGeq(g.toIntArray())
    }

    fun resetProfile() {
        val profile = getProfile()
        withEffect(Unit) { it.resetProfileSpecificSettings(profile) }
        appContext.deleteSharedPreferences("$PREFS_PROFILE$profile")
    }

    /** Turns Dolby off without creating the effect, so other modes never load the engine. */
    @Synchronized
    fun disable() {
        prefs.edit().putBoolean(KEY_ENABLED, false).apply()
        val e = effect ?: return
        try {
            e.dsOn = false
        } catch (ex: RuntimeException) {
            Log.e(TAG, "Dolby call failed", ex)
        }
    }

    /** Pushes the stored profile, settings and on/off state to the engine again. */
    fun reapply() {
        withEffect(Unit) { restore(it) }
    }

    companion object {
        private const val TAG = "DolbyController"
        private const val EFFECT_PRIORITY = 100
        const val GEQ_BANDS = 20
        val BAND_FREQUENCIES = listOf(32, 64, 125, 250, 500, 1000, 2250, 5000, 10000, 19688)

        private const val PREFS = "dolby"
        private const val PREFS_PROFILE = "dolby_profile_"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PROFILE = "profile"
        private const val KEY_IEQ = "ieq"
        private const val KEY_HP_VIRT = "hp_virtualizer"
        private const val KEY_SPK_VIRT = "spk_virtualizer"
        private const val KEY_WIDENING = "stereo_widening"
        private const val KEY_DIALOGUE = "dialogue"
        private const val KEY_DIALOGUE_AMOUNT = "dialogue_amount"
        private const val KEY_BASS = "bass"
        private const val KEY_LEVELER = "volume_leveler"
        private const val KEY_GEQ = "geq"

        @Volatile private var instance: DolbyController? = null

        fun get(context: Context): DolbyController =
            instance ?: synchronized(this) {
                instance ?: DolbyController(context).also { instance = it }
            }
    }
}
