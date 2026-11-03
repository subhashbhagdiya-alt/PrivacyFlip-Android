package com.privacyflip.app

import android.app.admin.DeviceAdminReceiver

/**
 * Optional Device Admin receiver for the explicit screen-lock action only.
 * This does not grant permission to toggle mobile data or other restricted settings.
 */
class PrivacyFlipAdminReceiver : DeviceAdminReceiver()
