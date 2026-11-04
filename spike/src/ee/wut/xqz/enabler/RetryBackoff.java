package ee.wut.xqz.enabler;

/** Capped exponential retry delays for USB connect failures. */
public final class RetryBackoff {
    private final long initialMs;
    private final long maxMs;
    private long nextMs;

    public RetryBackoff(long initialMs, long maxMs) {
        if (initialMs <= 0 || maxMs < initialMs) {
            throw new IllegalArgumentException("invalid retry range");
        }
        this.initialMs = initialMs;
        this.maxMs = maxMs;
        this.nextMs = initialMs;
    }

    public synchronized long nextDelayMs() {
        long delay = nextMs;
        nextMs = Math.min(nextMs * 2, maxMs);
        return delay;
    }

    public synchronized void reset() {
        nextMs = initialMs;
    }
}
