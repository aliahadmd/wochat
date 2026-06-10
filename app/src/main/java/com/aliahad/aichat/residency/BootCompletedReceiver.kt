package com.aliahad.aichat.residency

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_USER_UNLOCKED
        ) {
            return
        }
        val userManager = context.getSystemService(UserManager::class.java)
        if (userManager.isUserUnlocked) ModelResidencyService.start(context)
    }
}
