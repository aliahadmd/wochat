package com.aliahad.aichat.residency

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import com.aliahad.aichat.memory.MemoryWorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_USER_UNLOCKED
        ) {
            return
        }
        MemoryWorkScheduler.schedule(context)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runCatching { MemoryWorkScheduler.verifyAndRepair(context) }
            } finally {
                pending.finish()
            }
        }
        val userManager = context.getSystemService(UserManager::class.java)
        if (userManager.isUserUnlocked) ModelResidencyService.start(context)
    }
}
