package com.rew733.netthrottle

class TokenBucketLimiter(initialBytesPerSecond: Long) {

    @Volatile
    private var bytesPerSecond: Long = initialBytesPerSecond.coerceAtLeast(1024L)
    private var availableTokens: Double = bytesPerSecond.toDouble()
    private var lastRefillTimestamp: Long = System.nanoTime()
    private val lock = Any()

    fun updateRate(newBytesPerSecond: Long) {
        synchronized(lock) {
            bytesPerSecond = newBytesPerSecond.coerceAtLeast(1024L)
            if (availableTokens > bytesPerSecond) {
                availableTokens = bytesPerSecond.toDouble()
            }
        }
    }

    fun consume(bytesCount: Int) {
        if (bytesCount <= 0) return

        while (true) {
            var sleepMillis = 0L
            synchronized(lock) {
                val now = System.nanoTime()
                val elapsedSeconds = (now - lastRefillTimestamp) / 1_000_000_000.0
                if (elapsedSeconds > 0) {
                    availableTokens = (availableTokens + elapsedSeconds * bytesPerSecond)
                        .coerceAtMost(bytesPerSecond.toDouble())
                    lastRefillTimestamp = now
                }

                if (availableTokens >= bytesCount) {
                    availableTokens -= bytesCount
                    return
                } else {
                    val deficit = bytesCount - availableTokens
                    sleepMillis = ((deficit / bytesPerSecond) * 1000.0).toLong().coerceAtLeast(1L)
                }
            }
            try {
                Thread.sleep(sleepMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }
}
