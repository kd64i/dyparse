package com.jn.dyparse

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

object DouyinRequestLimiter {
    private const val MAX_JITTER_MS = 2_500L
    private const val MIN_JITTER_MS = 180L
    private const val RISK_FORCE_STOP_COUNT = 5

    private val mutex = Mutex()
    private var lastRequestAtMs = 0L
    private var cooldownUntilMs = 0L
    private var riskFailureStreak = 0

    data class RiskState(
        val consecutiveFailures: Int,
        val cooldownMs: Long
    )

    suspend fun acquire(baseIntervalMs: Long) {
        val waitMs = mutex.withLock {
            val now = SystemClock.elapsedRealtime()
            val cooldownWaitMs = (cooldownUntilMs - now).coerceAtLeast(0L)
            val spacingWaitMs = (lastRequestAtMs + jitteredIntervalMs(baseIntervalMs) - now)
                .coerceAtLeast(0L)
            val totalWaitMs = max(cooldownWaitMs, spacingWaitMs)
            lastRequestAtMs = now + totalWaitMs
            totalWaitMs
        }

        if (waitMs > 0) {
            delay(waitMs)
        }
    }

    suspend fun <T> run(baseIntervalMs: Long, block: suspend () -> T): T {
        acquire(baseIntervalMs)
        return try {
            val result = block()
            reportSuccess()
            result
        } catch (throwable: Throwable) {
            if (isLikelyRisk(throwable)) {
                reportRiskFailure()
            }
            throw throwable
        }
    }

    suspend fun reportSuccess() {
        mutex.withLock {
            riskFailureStreak = 0
            // 成功即放行：清除残留冷却，避免上一次误判拖慢后续请求
            cooldownUntilMs = 0L
        }
    }

    suspend fun reportRiskFailure(): RiskState {
        return mutex.withLock {
            riskFailureStreak += 1
            val cooldownMs = riskCooldownMs(riskFailureStreak)
            val now = SystemClock.elapsedRealtime()
            cooldownUntilMs = max(cooldownUntilMs, now + cooldownMs)
            RiskState(riskFailureStreak, cooldownMs)
        }
    }

    fun riskStopThreshold(userThreshold: Int): Int {
        return if (userThreshold > 0) {
            min(userThreshold, RISK_FORCE_STOP_COUNT)
        } else {
            RISK_FORCE_STOP_COUNT
        }
    }

    fun isLikelyRisk(throwable: Throwable?): Boolean {
        var current = throwable
        while (current != null) {
            if (isLikelyRiskText(current.message.orEmpty())) {
                return true
            }
            current = current.cause
        }
        return false
    }

    fun isLikelyRiskStatus(statusCode: Int): Boolean {
        return statusCode == 403 ||
            statusCode == 408 ||
            statusCode == 409 ||
            statusCode == 418 ||
            statusCode == 429
    }

    fun isLikelyRiskBody(body: String): Boolean {
        return isLikelyRiskText(body.take(2_000))
    }

    fun jitteredIntervalMs(baseIntervalMs: Long): Long {
        val base = baseIntervalMs.coerceAtLeast(0L)
        if (base == 0L) {
            return 0L
        }

        val jitterMax = when {
            base < 500L -> 500L
            base < 2_000L -> max(MIN_JITTER_MS, base / 2)
            else -> min(MAX_JITTER_MS, base / 3)
        }
        return base + Random.nextLong(jitterMax + 1)
    }

    private fun riskCooldownMs(streak: Int): Long {
        return when (streak.coerceAtLeast(1)) {
            1 -> 8_000L
            2 -> 18_000L
            3 -> 35_000L
            4 -> 60_000L
            else -> 90_000L
        }
    }

    private fun isLikelyRiskText(text: String): Boolean {
        if (text.isBlank()) {
            return false
        }

        val normalized = text.lowercase()
        return normalized.contains("429") ||
            normalized.contains("403") ||
            normalized.contains("\u98ce\u63a7") ||
            normalized.contains("\u9a8c\u8bc1\u7801") ||
            normalized.contains("\u5b89\u5168\u9a8c\u8bc1") ||
            normalized.contains("\u8bbf\u95ee\u8fc7\u4e8e\u9891\u7e41") ||
            normalized.contains("\u8bf7\u6c42\u8fc7\u4e8e\u9891\u7e41") ||
            normalized.contains("verify_ticket") ||
            normalized.contains("captcha_verify") ||
            normalized.contains("captcha-verify") ||
            normalized.contains("sec_verify") ||
            normalized.contains("verify_status") ||
            normalized.contains("byted_acrawler") ||
            normalized.contains("blocked")
    }
}
