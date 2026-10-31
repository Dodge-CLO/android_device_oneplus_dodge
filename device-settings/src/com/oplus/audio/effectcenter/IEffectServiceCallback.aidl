package com.oplus.audio.effectcenter;

// Wire-compatible with the stock OPlus AudioEffectCenter callback (codes 1..6).
interface IEffectServiceCallback {
    void EffectServiceStatusChangeCallback();
    void ConnectStatusCallback(boolean connected);
    void DsCallback(boolean enabled);
    void ProfileCallback(int profile);
    void IsBtSupported(boolean supported);
    void ConnectDeviceStatusChangeCallback(int status);
}
