/*
 * SPDX-FileCopyrightText: 2026 The PenguinOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.device.settings.slider;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.lineageos.device.settings.Constants;
import org.lineageos.device.settings.R;

/**
 * Brief pill under the status bar that names what the alert slider just switched to,
 * like the stock OnePlus slider dialog.
 */
public final class SliderPopup {
    private static final String TAG = "SliderPopup";
    private static final long TIMEOUT_MS = 1500;

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mHide = this::hide;

    private LinearLayout mView;
    private ImageView mIcon;
    private TextView mLabel;

    public SliderPopup(Context context) {
        mContext = context;
        mWindowManager = context.getSystemService(WindowManager.class);
    }

    /** Shows the popup for a slider result ({@code Constants.MODE_*}). */
    public void show(int mode) {
        final int label = labelFor(mode);
        if (label == 0 || mWindowManager == null) {
            return;
        }
        try {
            if (mView == null) {
                // Built on each show so density and theme changes are picked up.
                createView();
                mWindowManager.addView(mView, layoutParams());
            } else {
                mWindowManager.updateViewLayout(mView, layoutParams());
            }
            mIcon.setImageResource(iconFor(mode));
            mLabel.setText(label);
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to show the slider popup", e);
            mView = null;
            return;
        }
        mHandler.removeCallbacks(mHide);
        mHandler.postDelayed(mHide, TIMEOUT_MS);
    }

    private void hide() {
        if (mView == null) {
            return;
        }
        try {
            mWindowManager.removeViewImmediate(mView);
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to hide the slider popup", e);
        }
        mView = null;
    }

    private void createView() {
        final int pad = dp(14);
        mView = new LinearLayout(mContext);
        mView.setOrientation(LinearLayout.HORIZONTAL);
        mView.setGravity(Gravity.CENTER_VERTICAL);
        mView.setPadding(pad, dp(10), dp(18), dp(10));
        final GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(230, 32, 33, 36));
        background.setCornerRadius(dp(24));
        mView.setBackground(background);

        mIcon = new ImageView(mContext);
        mIcon.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        final LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(22), dp(22));
        iconParams.setMarginEnd(dp(10));
        mView.addView(mIcon, iconParams);

        mLabel = new TextView(mContext);
        mLabel.setTextColor(Color.WHITE);
        mLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        mLabel.setSingleLine(true);
        mView.addView(mLabel);
    }

    private WindowManager.LayoutParams layoutParams() {
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setFitInsetsTypes(0);
        lp.y = topInset() + dp(8);
        lp.windowAnimations = android.R.style.Animation_Toast;
        lp.setTitle(TAG);
        return lp;
    }

    private int topInset() {
        try {
            return mWindowManager.getCurrentWindowMetrics().getWindowInsets()
                    .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()
                            | WindowInsets.Type.displayCutout()).top;
        } catch (RuntimeException e) {
            return dp(32);
        }
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                mContext.getResources().getDisplayMetrics()));
    }

    private static int labelFor(int mode) {
        switch (mode) {
            case Constants.MODE_RING:
            case Constants.MODE_NONE:
                return R.string.notification_slider_ringer_normal;
            case Constants.MODE_VIBRATE:
                return R.string.notification_slider_ringer_vibrate;
            case Constants.MODE_SILENT:
                return R.string.notification_slider_ringer_silent;
            case Constants.MODE_TOTAL_SILENCE:
                return R.string.notification_slider_mode_total_silence;
            case Constants.MODE_ALARMS_ONLY:
                return R.string.notification_slider_mode_alarms_only;
            case Constants.MODE_PRIORITY_ONLY:
                return R.string.notification_slider_mode_priority_only;
            case Constants.MODE_FLASHLIGHT_ON:
                return R.string.notification_slider_flashlight_on;
            case Constants.MODE_FLASHLIGHT_OFF:
                return R.string.notification_slider_flashlight_off;
            case Constants.MODE_FLASHLIGHT_BLINK:
                return R.string.notification_slider_flashlight_blink;
            case Constants.MODE_BRIGHTNESS_AUTO:
                return R.string.notification_slider_brightness_auto;
            case Constants.MODE_BRIGHTNESS_BRIGHT:
                return R.string.notification_slider_brightness_brightest;
            case Constants.MODE_BRIGHTNESS_DARK:
                return R.string.notification_slider_brightness_darkest;
            case Constants.MODE_ROTATION_AUTO:
                return R.string.notification_slider_rotation_auto;
            case Constants.MODE_ROTATION_0:
                return R.string.notification_slider_rotation_portrait;
            case Constants.MODE_ROTATION_90:
                return R.string.notification_slider_rotation_landscape_90;
            case Constants.MODE_ROTATION_270:
                return R.string.notification_slider_rotation_landscape_270;
            default:
                // App launch shows the app itself.
                return 0;
        }
    }

    private static int iconFor(int mode) {
        switch (mode) {
            case Constants.MODE_RING:
            case Constants.MODE_NONE:
                return com.android.internal.R.drawable.ic_audio_ring_notif;
            case Constants.MODE_VIBRATE:
                return com.android.internal.R.drawable.ic_audio_ring_notif_vibrate;
            case Constants.MODE_SILENT:
            case Constants.MODE_TOTAL_SILENCE:
            case Constants.MODE_ALARMS_ONLY:
            case Constants.MODE_PRIORITY_ONLY:
                return com.android.internal.R.drawable.ic_audio_ring_notif_mute;
            case Constants.MODE_FLASHLIGHT_ON:
            case Constants.MODE_FLASHLIGHT_OFF:
            case Constants.MODE_FLASHLIGHT_BLINK:
                return com.android.internal.R.drawable.ic_qs_flashlight;
            default:
                return R.drawable.ic_slider;
        }
    }
}
