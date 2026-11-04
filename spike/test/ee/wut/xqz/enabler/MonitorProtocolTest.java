package ee.wut.xqz.enabler;

import java.nio.charset.StandardCharsets;

public final class MonitorProtocolTest {
    private MonitorProtocolTest() {
    }

    public static void run() {
        check(MonitorProtocol.isMonitor(0x054C, 0x0E48), "matches the monitor VID/PID");
        check(!MonitorProtocol.isMonitor(0x054C, 0x0E49), "rejects the audio device PID");
        check(!MonitorProtocol.isMonitor(0x0000, 0x0E48), "rejects a different vendor");

        check(MonitorProtocol.isVendorInterface(0xFF, 0x01, 0x01),
                "matches the vendor interface");
        check(!MonitorProtocol.isVendorInterface(0x03, 0x01, 0x01),
                "rejects a HID interface");

        check("show".equals(new String(MonitorProtocol.SHOW, StandardCharsets.UTF_8)),
                "SHOW is the raw show command");
        check("stop".equals(new String(MonitorProtocol.STOP, StandardCharsets.UTF_8)),
                "STOP is the raw stop command");
        check(MonitorProtocol.HEARTBEAT_MS == 400, "heartbeat interval is 400 ms");
        check(MonitorProtocol.READ_TIMEOUT_MS >= 250, "reader timeout is at least 250 ms");

        check("flip".equals(MonitorProtocol.decode("flip\r".getBytes(StandardCharsets.UTF_8), 5)),
                "decodes and trims the flip reply");
        check(MonitorProtocol.isFlip("flip"), "detects flip");
        check(MonitorProtocol.isFlip("flip\r\n"), "detects flip with line endings");
        check(MonitorProtocol.isFlip("disp_onflip"), "detects flip in a coalesced reply");
        check(!MonitorProtocol.isFlip("disp_on"), "does not treat disp_on as flip");
        check(!MonitorProtocol.isFlip(""), "does not treat an empty reply as flip");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
