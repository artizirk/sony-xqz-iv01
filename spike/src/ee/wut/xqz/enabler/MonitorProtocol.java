package ee.wut.xqz.enabler;

import java.nio.charset.StandardCharsets;

/** Protocol constants and wire-format helpers for the XQZ-IV01 control device. */
public final class MonitorProtocol {
    public static final int VENDOR_ID = 0x054C;
    public static final int PRODUCT_ID = 0x0E48;
    public static final int HEARTBEAT_MS = 400;
    public static final int READ_TIMEOUT_MS = 500;
    public static final int VENDOR_INTERFACE_CLASS = 0xFF;
    public static final int VENDOR_INTERFACE_SUBCLASS = 0x01;
    public static final int VENDOR_INTERFACE_PROTOCOL = 0x01;
    public static final int ENDPOINT_IN = 0x81;
    public static final int ENDPOINT_OUT = 0x02;
    public static final byte[] SHOW = "show".getBytes(StandardCharsets.UTF_8);
    public static final byte[] STOP = "stop".getBytes(StandardCharsets.UTF_8);

    private MonitorProtocol() {
    }

    public static boolean isMonitor(int vendorId, int productId) {
        return vendorId == VENDOR_ID && productId == PRODUCT_ID;
    }

    public static boolean isVendorInterface(int interfaceClass, int subclass, int protocol) {
        return interfaceClass == VENDOR_INTERFACE_CLASS
                && subclass == VENDOR_INTERFACE_SUBCLASS
                && protocol == VENDOR_INTERFACE_PROTOCOL;
    }

    public static String decode(byte[] buffer, int length) {
        return new String(buffer, 0, length, StandardCharsets.UTF_8).trim();
    }

    public static boolean isFlip(String text) {
        return text != null && text.contains("flip");
    }
}
