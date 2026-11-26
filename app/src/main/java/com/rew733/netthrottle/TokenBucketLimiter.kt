package com.rew733.netthrottle

class TokenBucketLimiter(initialBytesPerSecond: Long) {

    @Volatile
    private var bytesPerSecond: Long = initialBytesPerSecond.coerceAtLeast(1024L)

    private var availableTokens: Double = 0.0
    private var lastRefillNano: Long = System.nanoTime()
    private val lock = Object()

    fun updateRate(newBytesPerSecond: Long) {
        synchronized(lock) {
            bytesPerSecond = newBytesPerSecond.coerceAtLeast(1024L)
            val maxBurst = bytesPerSecond * 0.2
            if (availableTokens > maxBurst) {
                availableTokens = maxBurst
            }
        }
    }

    fun consume(bytes: Int) {
        if (bytes <= 0) return
        val totalCost = (bytes + 54).toDouble()

        while (true) {
            var sleepMillis = 0L
            var sleepNanos = 0

            synchronized(lock) {
                refill()
                if (availableTokens >= totalCost) {
                    availableTokens -= totalCost
                    return
                }
                val deficit = totalCost - availableTokens
                val waitNanos = ((deficit / bytesPerSecond.toDouble()) * 1_000_000_000.0).toLong()
                if (waitNanos <= 0L) {
                    availableTokens = 0.0
                    return
                }
                sleepMillis = waitNanos / 1_000_000L
                sleepNanos = (waitNanos % 1_000_000L).toInt()
            }

            try {
                Thread.sleep(sleepMillis, sleepNanos)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun refill() {
        val now = System.nanoTime()
        val elapsedNanos = now - lastRefillNano
        if (elapsedNanos > 0L) {
            val added = (elapsedNanos / 1_000_000_000.0) * bytesPerSecond.toDouble()
            val maxBurst = bytesPerSecond * 0.2
            availableTokens = (availableTokens + added).coerceAtMost(maxBurst)
            lastRefillNano = now
        }
    }
}
