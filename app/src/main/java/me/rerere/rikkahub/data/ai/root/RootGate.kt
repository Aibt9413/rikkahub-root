package me.rerere.rikkahub.data.ai.root

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * 全局 root 确认闸门：工具执行前调用 [request]，UI 层观察 [current] 并调用 [resolve]。
 * 所有等待都有超时，没人确认时按拒绝处理。
 */
class RootGate {

    data class Request(
        val id: String = UUID.randomUUID().toString(),
        val toolName: String,
        val command: String,
        val risk: RootRisk,
        val reasons: List<String>,
        val protectedHit: String?,
        val reviewNote: String? = null,
        val phrase: String = randomPhrase(),
        val createdAt: Long = System.currentTimeMillis(),
    )

    data class Decision(
        val allowed: Boolean,
        val autoApproved: Boolean = false,
        val reason: String = "",
    )

    private val _current = MutableStateFlow<Request?>(null)
    val current: StateFlow<Request?> = _current.asStateFlow()

    private val waiting = mutableMapOf<String, CompletableDeferred<Decision>>()

    /** S0 直接放行；其余等待 UI 确认，超时按拒绝。 */
    suspend fun request(
        toolName: String,
        command: String,
        risk: RootRisk,
        reasons: List<String>,
        protectedHit: String? = null,
        reviewNote: String? = null,
        timeoutMs: Long = 5 * 60 * 1000L,
    ): Decision {
        if (protectedHit != null) return Decision(false, reason = "命中保护区：$protectedHit")
        if (risk == RootRisk.S0) return Decision(true, reason = "只读操作")
        val request = Request(
            toolName = toolName,
            command = command,
            risk = risk,
            reasons = reasons,
            protectedHit = protectedHit,
            reviewNote = reviewNote,
        )
        val deferred = CompletableDeferred<Decision>()
        synchronized(waiting) { waiting[request.id] = deferred }
        _current.value = request
        val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: Decision(false, reason = "确认超时")
        synchronized(waiting) { waiting.remove(request.id) }
        if (_current.value?.id == request.id) _current.value = null
        return result
    }

    fun resolve(requestId: String, decision: Decision) {
        val deferred = synchronized(waiting) { waiting[requestId] } ?: return
        deferred.complete(decision)
    }

    fun cancelAll(reason: String = "已取消") {
        val snapshot = synchronized(waiting) {
            val copy = waiting.values.toList()
            waiting.clear()
            copy
        }
        snapshot.forEach { it.complete(Decision(false, reason = reason)) }
        _current.value = null
    }

    companion object {
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        fun randomPhrase(length: Int = 4): String =
            (1..length).map { ALPHABET.random() }.joinToString("")
    }
}
