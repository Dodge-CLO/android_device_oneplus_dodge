#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

# AAPT
PRODUCT_AAPT_CONFIG := normal
PRODUCT_AAPT_PREF_CONFIG := xxxhdpi

# Camera
PRODUCT_PACKAGES += \
    libapsfixup

# Alert slider
PRODUCT_PACKAGES += \
    KeyHandler \
    DeviceSettings \
    tri-state-key-calibrate

# AirPods
PRODUCT_PACKAGES += \
    PenguinPods

# Audio
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/configs/audio/audio_effects_config.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio/sku_sun/audio_effects_config.xml \
    $(LOCAL_PATH)/configs/audio/audio_module_config_primary.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio/audio_module_config_primary.xml \
    $(LOCAL_PATH)/configs/audio/audio_policy_volumes.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio_policy_volumes.xml \
    $(LOCAL_PATH)/configs/audio/default_volume_tables.xml:$(TARGET_COPY_OUT_VENDOR)/etc/default_volume_tables.xml \
    $(LOCAL_PATH)/configs/media/media_codecs_c2_audio.xml:$(TARGET_COPY_OUT_VENDOR)/etc/media_codecs_c2_audio.xml

PRODUCT_PRODUCT_PROPERTIES += \
    audio.spatializer.pose_predictor_type=1 \
    ro.audio.spatializer_channel_count_param=0x100100ba \
    ro.audio.spatializer_type=oplus \
    ro.oplus.audio.dolby_multi_config=true \
    ro.oplus.audio.effect.speaker_geq=true \
    ro.oplus.audio.effect.type=audiox_merge

# Dolby (controlled from DeviceSettings instead of LunarisDolby)
# Speaker profiles trimmed to stock OnePlus levels; listed first so it wins over the shared file
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/configs/dolby/dax-default.xml:$(TARGET_COPY_OUT_VENDOR)/etc/dolby/dax-default.xml

TARGET_DOLBY_LUNARIS_APP := false
TARGET_DOLBY_CODEC2_AIDL := true
$(call inherit-product, vendor/oneplus/dolby/config.mk)

# Boot animation
TARGET_SCREEN_HEIGHT := 3168
TARGET_SCREEN_WIDTH := 1440

# Camera
TARGET_EXCLUDES_APERTURE := true

# Display
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/configs/display/displayconfig.xml:$(TARGET_COPY_OUT_VENDOR)/etc/displayconfig/display_id_4630946756802996883.xml

# Fingerprint
TARGET_HAS_UDFPS := true
PRODUCT_PACKAGES += \
    UdfpsAnimations \
    UdfpsIcons

# Keyboard
TARGET_USES_PENGUIN_LATINIME := false

# Keymint
PRODUCT_PACKAGES += \
    android.hardware.security.keymint3-service.strongbox.nxp \
    android.hardware.weaver-service.nxp

PRODUCT_SOONG_NAMESPACES += \
    hardware/nxp/keymint/generic \
    hardware/nxp/weaver/generic

# Overlays
DEVICE_PACKAGE_OVERLAYS += \
    $(LOCAL_PATH)/overlay-lineage

PRODUCT_PACKAGES += \
    EuiccPolicyResTarget \
    FrameworksResTargetEuicc \
    KeyHandlerResTarget \
    OPlusFrameworksResTarget \
    OPlusGameSpaceResTarget \
    OPlusSettingsProviderResTarget \
    OPlusSettingsResTarget \
    OPlusSystemUIResTarget

# NFC
PRODUCT_PACKAGES += \
    android.hardware.nfc-service.nxp

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/configs/nfc/libnfc-mtp-SN220.conf_23821:$(TARGET_COPY_OUT_ODM)/etc/libnfc-mtp-SN220.conf_23821 \
    $(LOCAL_PATH)/configs/nfc/libnfc-mtp-SN220.conf_23893:$(TARGET_COPY_OUT_ODM)/etc/libnfc-mtp-SN220.conf_23893 \
    $(LOCAL_PATH)/configs/nfc/libnfc-nci.conf:$(TARGET_COPY_OUT_VENDOR)/etc/libnfc-nci.conf

# Regional properties
REGIONAL_PROP_FILES := $(wildcard $(LOCAL_PATH)/properties/*/*.prop)

PRODUCT_COPY_FILES += $(foreach f,$(REGIONAL_PROP_FILES), \
    $(f):$(TARGET_COPY_OUT_ODM)/etc/$(patsubst $(LOCAL_PATH)/properties/%,%,$(f)) \
    $(f):$(TARGET_COPY_OUT_RECOVERY)/root/vendor/odm/etc/$(patsubst $(LOCAL_PATH)/properties/%,%,$(f)))

# SecureElement
PRODUCT_PACKAGES += \
    SecureElementResTarget_Vendor

PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/configs/permissions/com.android.se.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/com.android.se.xml

PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.hardware.se.omapi.ese.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.se.omapi.ese.xml \
    frameworks/native/data/etc/android.hardware.se.omapi.uicc.xml:$(TARGET_COPY_OUT_VENDOR)/etc/permissions/android.hardware.se.omapi.uicc.xml \

#SurfaceFlinger Refresh Rate
$(call soong_config_set,surfaceflinger,frame_rate_category_high,120)
$(call soong_config_set,surfaceflinger,frame_rate_category_min,60)

# Soong namespaces
PRODUCT_SOONG_NAMESPACES += \
    $(LOCAL_PATH)

# Telephony
PRODUCT_COPY_FILES += \
    frameworks/native/data/etc/android.hardware.telephony.euicc.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/permissions/android.hardware.telephony.euicc.xml

# Vibrator
$(call soong_config_set,qti_vibrator,effect_lib,libqtivibratoreffect.oplus.dodge)

PRODUCT_PACKAGES += \
    libqtivibratoreffect.oplus.dodge

PRODUCT_PRODUCT_PROPERTIES += \
    persist.sys.haptic_profile=op13crisp

# ProXDR
PRODUCT_PRODUCT_PROPERTIES += \
    persist.sys.feature.hdr_vision_app=1 \
    persist.sys.feature.localhdr_version=2 \
    persist.sys.feature.screen_multi_bits=10 \
    persist.sys.feature.support.edrlistener=true \
    persist.sys.feature.uhdr.support=true

# Inherit from the common OEM chipset makefile.
$(call inherit-product, device/oneplus/sm8750-common/common.mk)

# Gralloc
$(call soong_config_set_bool,qtidisplay,camera_yuv_420_888_nv12,true)

# Real-time 1080p120 camera configs, declared before dodge-vendor.mk so they win
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/camera/oplus_camera_config:$(TARGET_COPY_OUT_ODM)/etc/camera/config/oplus_camera_config \
    $(LOCAL_PATH)/camera/camera_unit_feature_config.protobuf:$(TARGET_COPY_OUT_ODM)/etc/camera/config/camera_unit_feature_config.protobuf

# Inherit from the proprietary files makefile.
$(call inherit-product, vendor/oneplus/dodge/dodge-vendor.mk)

# OPlus Camera
$(call inherit-product-if-exists, vendor/oplus/camera/dodge/opluscamera.mk)
