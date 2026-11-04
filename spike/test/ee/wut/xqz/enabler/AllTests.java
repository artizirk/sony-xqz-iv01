package ee.wut.xqz.enabler;

public final class AllTests {
    private AllTests() {
    }

    public static void main(String[] args) {
        MonitorProtocolTest.run();
        RetryBackoffTest.run();
        HeartbeatLoopTest.run();
        RunStateTest.run();
        System.out.println("All tests passed");
    }
}
