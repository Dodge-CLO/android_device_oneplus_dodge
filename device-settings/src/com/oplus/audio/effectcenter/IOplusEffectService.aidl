package com.oplus.audio.effectcenter;

import com.oplus.audio.effectcenter.IEffectServiceCallback;

// Reconstructed control interface of the stock OPlus AudioEffectCenter (O-Reality).
// Method order defines the transaction codes: keep it exactly as is.
interface IOplusEffectService {
    void setEnabled(boolean enabled);
    boolean getEnabled();
    void setMusicIeqPreset(int preset);
    int getMusicIeqPreset();
    void setEffectCategory(int category);
    int getEffectCategory();
    void setMusicGeqBandGains(in int[] gains);
    int[] getMusicGeqBandGains();
    boolean isBtDeviceSupported(String address);
    void setParameter(String key, String value);
    String getParameters(String key);
    void registerCallback(IEffectServiceCallback callback);
    void unRegisterCallback(IEffectServiceCallback callback);
    void setCustomGeqBandGains(in int[] gains, boolean isHeadsetOn, int presetEQType);
    int[] getCustomGeqBandGains(boolean isHeadsetOn);
    int getCustomPresetEQType(boolean isHeadsetOn);
}
