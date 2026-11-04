package ee.wut.xqz.enabler;

public final class RunStateTest {
    private RunStateTest() {
    }

    public static void run() {
        check(!RunState.STOPPED.isActive(), "stopped is inactive");
        check(RunState.RUNNING.isActive(), "running is active");
        check(RunState.WAITING_DEVICE.isWaiting(), "waiting for device is waiting");
        check(RunState.WAITING_PERMISSION.isWaiting(), "waiting for permission is waiting");
        check(RunState.RETRYING.isWaiting(), "retrying is waiting");
        check(!RunState.RUNNING.isWaiting(), "running is not waiting");
        check(!RunState.STOPPED.isWaiting(), "stopped is not waiting");

        check(RunState.mayPublish(true, false), "publishes state while active");
        check(!RunState.mayPublish(false, false), "does not publish state after stop");
        check(!RunState.mayPublish(true, true), "does not publish state while stopping");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
