package ee.wut.xqz.enabler;

/** Device-independent heartbeat and reply-read loops. */
public final class HeartbeatLoop {
    public static final int MAX_CONSECUTIVE_WRITE_FAILURES = 3;
    public static final int READ_ERROR_BACKOFF_MS = 200;
    private static final int RX_BUFFER_SIZE = 64;

    private HeartbeatLoop() {
    }

    public interface Link {
        int writeShow();

        int read(byte[] buffer, int timeoutMs);
    }

    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public interface StopSignal {
        boolean stopped();
    }

    public interface RxListener {
        void onRx(String text);
    }

    public static final class WriteFailure extends Exception {
        public WriteFailure(String message) {
            super(message);
        }
    }

    public static void heartbeat(Link link, Sleeper sleeper, StopSignal stop, long intervalMs)
            throws InterruptedException, WriteFailure {
        int consecutiveFailures = 0;
        while (!stop.stopped()) {
            int written = link.writeShow();
            if (written < 0) {
                consecutiveFailures++;
                if (consecutiveFailures >= MAX_CONSECUTIVE_WRITE_FAILURES) {
                    throw new WriteFailure("show failed " + consecutiveFailures + " times");
                }
            } else {
                consecutiveFailures = 0;
            }
            sleeper.sleep(intervalMs);
        }
    }

    public static void read(Link link, StopSignal stop, RxListener listener, int timeoutMs,
            Sleeper sleeper) {
        byte[] buffer = new byte[RX_BUFFER_SIZE];
        while (!stop.stopped()) {
            int read;
            try {
                read = link.read(buffer, timeoutMs);
            } catch (RuntimeException e) {
                return;
            }
            if (read > 0) {
                listener.onRx(MonitorProtocol.decode(buffer, read));
            } else {
                try {
                    sleeper.sleep(READ_ERROR_BACKOFF_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
