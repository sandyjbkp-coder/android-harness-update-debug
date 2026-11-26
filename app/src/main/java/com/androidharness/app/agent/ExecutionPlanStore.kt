package com.androidharness.app.agent

import android.content.Context
import com.androidharness.app.agent.CollaborativePlanBuilder.ExecutionPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages storage and retrieval of execution plans across sessions.
 */
class ExecutionPlanStore(
    private val context: Context
) {
    private val plans = ConcurrentHashMap<String, StoredExecutionPlan>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class StoredExecutionPlan(
        val plan: ExecutionPlan,
        val createdAt: Long = System.currentTimeMillis(),
        val lastAccessed: Long = System.currentTimeMillis(),
        val accessCount: Int = 0,
        val isExecuted: Boolean = false
    )

    suspend fun storePlan(
        taskId: String,
        plan: ExecutionPlan,
        contextStore: ConversationContextStore? = null
    ) = withContext(Dispatchers.IO) {
        val stored = StoredExecutionPlan(
            plan = plan,
            lastAccessed = System.currentTimeMillis(),
            accessCount = 1
        )
        plans[taskId] = stored
        saveToDisk(taskId, stored)
    }

    fun getPlan(taskId: String): StoredExecutionPlan? = plans[taskId]

    fun getLatestPlan(taskId: String): ExecutionPlan? = plans[taskId]?.plan

    fun markPlanExecuted(taskId: String): Boolean {
        val p = plans[taskId] ?: return false
        plans[taskId] = p.copy(isExecuted = true)
        return true
    }

    fun recordAccess(taskId: String) {
        val p = plans[taskId] ?: return
        plans[taskId] = p.copy(lastAccessed = System.currentTimeMillis(), accessCount = p.accessCount + 1)
    }

    fun cleanupOldPlans(maxAgeHours: Int = 24): List<String> {
        val cutoff = System.currentTimeMillis() - (maxAgeHours * 60 * 60 * 1000)
        val cleaned = plans.filter { (_, plan) -> plan.isExecuted || plan.lastAccessed < cutoff }
        cleaned.keys.forEach { plans.remove(it) }
        return cleaned.keys.toList()
    }

    fun getPlanStats(): PlanStats {
        val totalPlans = plans.size
        val executedPlans = plans.values.count { it.isExecuted }
        val activePlans = totalPlans - executedPlans
        val oldestPlan = plans.values.minByOrNull { it.createdAt }
        val newestPlan = plans.values.maxByOrNull { it.createdAt }
        val avgAccess = if (totalPlans > 0) plans.values.map { it.accessCount.toDouble() }.average() else 0.0
        return PlanStats(
            totalPlans = totalPlans,
            executedPlans = executedPlans,
            activePlans = activePlans,
            oldestPlanAge = oldestPlan?.let { System.currentTimeMillis() - it.createdAt } ?: 0L,
            newestPlanAge = newestPlan?.let { System.currentTimeMillis() - it.createdAt } ?: 0L,
            averageAccessCount = avgAccess
        )
    }

    private fun saveToDisk(taskId: String, stored: StoredExecutionPlan) {
        runCatching {
            val planFile = File(context.filesDir, "plans/$taskId.json")
            planFile.parentFile?.mkdirs()
            planFile.writeText(json.encodeToString(stored))
        }
    }

    fun loadFromDisk(taskId: String): StoredExecutionPlan? {
        return try {
            val planFile = File(context.filesDir, "plans/$taskId.json")
            if (!planFile.exists()) return null
            json.decodeFromString<StoredExecutionPlan>(planFile.readText())
        } catch (_: Exception) {
            null
        }
    }
}

@Serializable
data class PlanStats(
    val totalPlans: Int,
    val executedPlans: Int,
    val activePlans: Int,
    val oldestPlanAge: Long,
    val newestPlanAge: Long,
    val averageAccessCount: Double
)
