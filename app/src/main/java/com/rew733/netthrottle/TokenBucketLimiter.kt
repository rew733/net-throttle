package com.rew733.netthrottle

class TokenBucketLimiter(initialBytesPerSecond: Long) {

    @Volatile
    private var bytesPerSecond: Long = initialBytesPerSecond.coerceAtLeast(1024L)
    private var maxBurstBytes: Double = (bytesPerSecond * 0.25).coerceAtLeast(1500.0)
    private var availableTokens: Double = maxBurstBytes
    private var lastRefillTimestamp: Long = System.nanoTime()
    private val lock = Any()

    fun updateRate(newBytesPerSecond: Long) {
        synchronized(lock) {
            bytesPerSecond = newBytesPerSecond.coerceAtLeast(1024L)
            maxBurstBytes = (bytesPerSecond * 0.25).coerceAtLeast(1500.0)
            if (availableTokens > maxBurstBytes) {
                availableTokens = maxBurstBytes
            }
        }
    }

    fun consume(bytesCount: Int) {
        if (bytesCount <= 0) return
        val totalWireBytes = bytesCount + 40 // يشمل ترويسة IP + TCP/UDP الفعلية

        while (true) {
            var sleepMillis = 0L
            synchronized(lock) {
                val now = System.nanoTime()
                val elapsedSeconds = (now - lastRefillTimestamp) / 1_000_000_000.0
                if (elapsedSeconds > 0) {
                    availableTokens = (availableTokens + elapsedSeconds * bytesPerSecond)
                        .coerceAtMost(maxBurstBytes)
                    lastRefillTimestamp = now
                }

                if (availableTokens >= totalWireBytes) {
                    availableTokens -= totalWireBytes
                    return
                } else {
                    val deficit = totalWireBytes - availableTokens
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
