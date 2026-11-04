package ee.wut.xqz.enabler;

public final class RetryBackoffTest {
    private RetryBackoffTest() {
    }

    public static void run() {
        RetryBackoff backoff = new RetryBackoff(300, 5000);
        check(backoff.nextDelayMs() == 300, "first retry is 300 ms");
        check(backoff.nextDelayMs() == 600, "second retry is 600 ms");
        check(backoff.nextDelayMs() == 1200, "third retry is 1200 ms");
        check(backoff.nextDelayMs() == 2400, "fourth retry is 2400 ms");
        check(backoff.nextDelayMs() == 4800, "fifth retry is 4800 ms");
        check(backoff.nextDelayMs() == 5000, "retry is capped at 5000 ms");
        check(backoff.nextDelayMs() == 5000, "retry stays capped");

        backoff.reset();
        check(backoff.nextDelayMs() == 300, "reset returns to the first delay");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
