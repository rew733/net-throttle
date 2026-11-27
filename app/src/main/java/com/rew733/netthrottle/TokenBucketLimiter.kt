package com.rew733.netthrottle

class TokenBucketLimiter(bytesPerSecond: Long) {

    @Volatile
    private var rateBytesPerSec: Long = bytesPerSecond.coerceAtLeast(512L)

    private var availableTokens: Double = (rateBytesPerSec * 0.25)
    private var lastRefillNanoTime: Long = System.nanoTime()
    private val lock = Object()

    fun updateRate(newBytesPerSecond: Long) {
        synchronized(lock) {
            rateBytesPerSec = newBytesPerSecond.coerceAtLeast(512L)
            val maxBurst = rateBytesPerSec * 0.25
            if (availableTokens > maxBurst) {
                availableTokens = maxBurst
            }
        }
    }

    fun consume(bytes: Int) {
        if (bytes <= 0) return
        var remaining = bytes.toDouble()

        while (remaining > 0.0) {
            var sleepMillis = 0L
            var sleepNanos = 0

            synchronized(lock) {
                val now = System.nanoTime()
                val elapsedSeconds = (now - lastRefillNanoTime) / 1_000_000_000.0
                if (elapsedSeconds > 0.0) {
                    // سقف التراكم ربع ثانية فقط لمنع القفزات المفاجئة في العداد
                    val maxBurst = rateBytesPerSec * 0.25
                    availableTokens = (availableTokens + elapsedSeconds * rateBytesPerSec)
                        .coerceAtMost(maxBurst)
                    lastRefillNanoTime = now
                }

                if (availableTokens >= remaining) {
                    availableTokens -= remaining
                    remaining = 0.0
                } else if (availableTokens >= 1.0) {
                    remaining -= availableTokens
                    availableTokens = 0.0
                } else {
                    val deficit = remaining - availableTokens
                    val waitSeconds = deficit / rateBytesPerSec.toDouble()
                    val totalWaitNanos = (waitSeconds * 1_000_000_000.0).toLong().coerceAtLeast(1_000_000L)
                    sleepMillis = totalWaitNanos / 1_000_000L
                    sleepNanos = (totalWaitNanos % 1_000_000L).toInt()
                }
            }

            if (sleepMillis > 0L || sleepNanos > 0) {
                try {
                    Thread.sleep(sleepMillis, sleepNanos)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }
}
