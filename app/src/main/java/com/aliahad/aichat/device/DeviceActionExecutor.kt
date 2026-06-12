package com.aliahad.aichat.device

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.aliahad.aichat.activity.OfficeAccessibilityService
import com.aliahad.aichat.core.ActionRisk
import com.aliahad.aichat.core.DeviceAction
import com.aliahad.aichat.core.DeviceActionKind
import com.aliahad.aichat.data.ActionAuditEntity
import com.aliahad.aichat.data.AppDatabase
import com.aliahad.aichat.settings.AppSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.util.UUID

data class DeviceActionResult(
    val actionId: String,
    val success: Boolean,
    val message: String,
)

interface DeviceActionExecutor {
    suspend fun executePlan(
        actions: List<DeviceAction>,
        confirmedActionIds: Set<String> = emptySet(),
    ): List<DeviceActionResult>
}

class PolicyControlledDeviceActionExecutor(
    private val context: Context,
    private val database: AppDatabase,
    private val settings: AppSettingsRepository,
) : DeviceActionExecutor {
    private val auditDao = database.actionAuditDao()
    private val keyguard = context.getSystemService(KeyguardManager::class.java)

    override suspend fun executePlan(
        actions: List<DeviceAction>,
        confirmedActionIds: Set<String>,
    ): List<DeviceActionResult> {
        require(actions.size <= 10) { "Device action plans are limited to 10 steps" }
        require(!keyguard.isDeviceLocked) { "Unlock the phone before running device actions" }
        val allowlist = settings.actionAllowlist.first()
        return withTimeout(60_000) {
            actions.map { action ->
                executeOne(action, allowlist, action.id in confirmedActionIds)
            }
        }
    }

    private suspend fun executeOne(
        action: DeviceAction,
        allowlist: Set<String>,
        confirmed: Boolean,
    ): DeviceActionResult {
        val risk = ActionPolicyEngine.classify(action)
        val packageName = action.packageName
        val rejection = when {
            packageName != null && ActionPolicyEngine.isBlockedPackage(packageName) ->
                "This app category is blocked by the device-action policy"
            packageName != null && packageName !in allowlist ->
                "Add $packageName to the action allowlist first"
            risk == ActionRisk.BLOCKED -> "This action is blocked"
            risk == ActionRisk.SENSITIVE && !confirmed -> "Confirmation is required"
            else -> null
        }
        val auditId = UUID.randomUUID().toString()
        auditDao.upsert(
            ActionAuditEntity(
                id = auditId,
                actionKind = action.kind,
                packageName = packageName,
                target = action.target,
                risk = risk,
                planJson = action.toAuditJson(),
                result = rejection,
                success = rejection?.let { false },
                createdAt = System.currentTimeMillis(),
                completedAt = rejection?.let { System.currentTimeMillis() },
            ),
        )
        if (rejection != null) return DeviceActionResult(action.id, false, rejection)

        val success = when (action.kind) {
            DeviceActionKind.OPEN_APP -> openApp(requireNotNull(packageName))
            DeviceActionKind.OPEN_URI -> openUri(requireNotNull(action.target), packageName)
            else -> OfficeAccessibilityService.active()?.perform(action) == true
        }
        val message = if (success) "Action completed" else "Target was not found or changed"
        auditDao.upsert(
            ActionAuditEntity(
                id = auditId,
                actionKind = action.kind,
                packageName = packageName,
                target = action.target,
                risk = risk,
                planJson = action.toAuditJson(),
                result = message,
                success = success,
                createdAt = System.currentTimeMillis(),
                completedAt = System.currentTimeMillis(),
            ),
        )
        return DeviceActionResult(action.id, success, message)
    }

    private fun openApp(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    private fun openUri(value: String, packageName: String?): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(value))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (packageName != null) intent.setPackage(packageName)
        if (intent.resolveActivity(context.packageManager) == null) return false
        context.startActivity(intent)
        return true
    }
}

object ActionPolicyEngine {
    private val blockedPackageTerms = listOf(
        "bank",
        "wallet",
        "password",
        "authenticator",
        "securitycenter",
        "permissioncontroller",
    )

    fun classify(action: DeviceAction): ActionRisk = when (action.kind) {
        DeviceActionKind.SEND,
        DeviceActionKind.DELETE,
        DeviceActionKind.PURCHASE,
        DeviceActionKind.CHANGE_PERMISSION,
        DeviceActionKind.CHANGE_ACCOUNT,
        DeviceActionKind.HEALTH_WRITE -> ActionRisk.SENSITIVE
        DeviceActionKind.OPEN_APP,
        DeviceActionKind.OPEN_URI,
        DeviceActionKind.TAP_NODE,
        DeviceActionKind.SET_TEXT,
        DeviceActionKind.SCROLL,
        DeviceActionKind.BACK,
        DeviceActionKind.HOME -> ActionRisk.LOW
    }

    fun isBlockedPackage(packageName: String): Boolean =
        blockedPackageTerms.any(packageName.lowercase()::contains)
}

private fun DeviceAction.toAuditJson(): String =
    """{"id":"${id.json()}","kind":"${kind.name}","package":"${packageName.orEmpty().json()}","target":"${target.orEmpty().json()}"}"""

private fun String.json(): String =
    replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
