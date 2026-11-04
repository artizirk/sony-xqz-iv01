package ee.wut.xqz.enabler;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class HeartbeatLoopTest {
    private HeartbeatLoopTest() {
    }

    public static void run() {
        sendsEveryIntervalUntilStopped();
        stopsAfterConsecutiveWriteFailures();
        resetsFailureCountAfterSuccessfulWrite();
        readsAndDecodesReplies();
        returnsWhenReadThrows();
        throttlesImmediateReadErrors();
        throttlesZeroLengthReads();
    }

    private static void sendsEveryIntervalUntilStopped() {
        FakeLink link = new FakeLink();
        List<Long> sleeps = new ArrayList<>();
        int[] writes = {0};
        boolean[] stopped = {false};
        try {
            HeartbeatLoop.heartbeat(link,
                    millis -> {
                        sleeps.add(millis);
                        if (++writes[0] >= 3) stopped[0] = true;
                    },
                    () -> stopped[0],
                    MonitorProtocol.HEARTBEAT_MS);
        } catch (Exception e) {
            throw new AssertionError("heartbeat should not fail", e);
        }
        check(link.writes == 3, "sends three heartbeats");
        check(sleeps.size() == 3, "sleeps once per heartbeat");
        for (long millis : sleeps) {
            check(millis == MonitorProtocol.HEARTBEAT_MS, "sleeps for the heartbeat interval");
        }
    }

    private static void stopsAfterConsecutiveWriteFailures() {
        FakeLink link = new FakeLink();
        link.nextShowResult = -1;
        boolean failed = false;
        try {
            HeartbeatLoop.heartbeat(link, millis -> {
            }, () -> false, MonitorProtocol.HEARTBEAT_MS);
        } catch (HeartbeatLoop.WriteFailure expected) {
            failed = true;
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
        check(failed, "throws after consecutive write failures");
        check(link.writes == HeartbeatLoop.MAX_CONSECUTIVE_WRITE_FAILURES,
                "stops after the configured failure count");
    }

    private static void resetsFailureCountAfterSuccessfulWrite() {
        FakeLink link = new FakeLink();
        link.showResults.add(-1);
        link.showResults.add(-1);
        link.showResults.add(4);
        link.showResults.add(-1);
        link.showResults.add(-1);
        link.showResults.add(4);
        boolean[] stopped = {false};
        int[] writes = {0};
        try {
            HeartbeatLoop.heartbeat(link,
                    millis -> {
                        if (++writes[0] >= 6) stopped[0] = true;
                    },
                    () -> stopped[0],
                    MonitorProtocol.HEARTBEAT_MS);
        } catch (Exception e) {
            throw new AssertionError("a success must reset the failure count", e);
        }
        check(link.writes == 6, "continues after a successful write resets the count");
    }

    private static void readsAndDecodesReplies() {
        FakeLink link = new FakeLink();
        List<String> replies = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        replies.add("flip\r");
        replies.add("disp_on");
        int[] index = {0};
        link.replySupplier = buffer -> {
            if (index[0] >= replies.size()) return -1;
            byte[] bytes = replies.get(index[0]++).getBytes(StandardCharsets.UTF_8);
            System.arraycopy(bytes, 0, buffer, 0, bytes.length);
            return bytes.length;
        };
        HeartbeatLoop.read(link, () -> index[0] >= replies.size(), seen::add,
                MonitorProtocol.READ_TIMEOUT_MS, millis -> {
                });
        check(seen.size() == 2, "reads both replies");
        check("flip".equals(seen.get(0)), "decodes flip");
        check("disp_on".equals(seen.get(1)), "decodes disp_on");
    }

    private static void returnsWhenReadThrows() {
        FakeLink link = new FakeLink();
        link.replySupplier = buffer -> {
            throw new IllegalStateException("device gone");
        };
        List<String> seen = new ArrayList<>();
        HeartbeatLoop.read(link, () -> false, seen::add, MonitorProtocol.READ_TIMEOUT_MS,
                millis -> {
                });
        check(seen.isEmpty(), "returns without reporting a reply after a read failure");
    }

    private static void throttlesImmediateReadErrors() {
        FakeLink link = new FakeLink();
        link.replySupplier = buffer -> -1;
        List<Long> sleeps = new ArrayList<>();
        boolean[] stopped = {false};
        HeartbeatLoop.read(link, () -> stopped[0], text -> {
        }, MonitorProtocol.READ_TIMEOUT_MS, millis -> {
            sleeps.add(millis);
            stopped[0] = true;
        });
        check(sleeps.size() == 1, "backs off after an immediate read error");
        check(sleeps.get(0) == HeartbeatLoop.READ_ERROR_BACKOFF_MS,
                "uses the read-error backoff");
    }

    private static void throttlesZeroLengthReads() {
        FakeLink link = new FakeLink();
        link.replySupplier = buffer -> 0;
        List<Long> sleeps = new ArrayList<>();
        boolean[] stopped = {false};
        HeartbeatLoop.read(link, () -> stopped[0], text -> {
        }, MonitorProtocol.READ_TIMEOUT_MS, millis -> {
            sleeps.add(millis);
            stopped[0] = true;
        });
        check(sleeps.size() == 1, "backs off after a zero-length read");
        check(sleeps.get(0) == HeartbeatLoop.READ_ERROR_BACKOFF_MS,
                "uses the read-error backoff");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private interface ReplySupplier {
        int read(byte[] buffer);
    }

    private static final class FakeLink implements HeartbeatLoop.Link {
        int writes;
        int nextShowResult = 4;
        final List<Integer> showResults = new ArrayList<>();
        ReplySupplier replySupplier = buffer -> -1;

        @Override
        public int writeShow() {
            int result = showResults.isEmpty() ? nextShowResult : showResults.remove(0);
            writes++;
            return result;
        }

        @Override
        public int read(byte[] buffer, int timeoutMs) {
            return replySupplier.read(buffer);
        }
    }
}
