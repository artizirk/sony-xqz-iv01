package ee.wut.xqz.enabler;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.nio.charset.StandardCharsets;

public final class HeartbeatService extends Service {
    private static final String TAG = "XQZEnabler";
    private static final String CHANNEL_ID = "xqz_heartbeat";
    private static final String ACTION_STOP = "ee.wut.xqz.enabler.STOP";
    private static final int NOTIF_ID = 1;
    private static final int VID = 0x054C;
    private static final int PID = 0x0E48;
    private static final int HEARTBEAT_MS = 400;

    private static volatile boolean serviceActive;
    private static volatile boolean waiting;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Object workerLock = new Object();
    private volatile boolean stopping;
    private Thread worker;
    private UsbDeviceConnection connection;
    private UsbInterface usbInterface;
    private UsbEndpoint epIn;
    private UsbEndpoint epOut;
    private boolean receiverRegistered;
    private PowerManager.WakeLock wakeLock;

    public static boolean isRunning() {
        return serviceActive;
    }

    public static boolean isWaiting() {
        return waiting;
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device == null || device.getVendorId() != VID || device.getProductId() != PID) {
                return;
            }
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                Log.i(TAG, "monitor detached");
                stopping = true;
                waiting = true;
                updateNotification("Monitor disconnected — waiting for reconnect");
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
                Log.i(TAG, "monitor attached");
                waiting = true;
                updateNotification("Monitor connected — starting…");
                handler.postDelayed(HeartbeatService.this::startWorker, 300);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!serviceActive) {
            try {
                startForeground(NOTIF_ID, buildNotification("Starting…"),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } catch (Throwable t) {
                Log.e(TAG, "startForeground failed", t);
                stopSelf();
                return START_NOT_STICKY;
            }
            serviceActive = true;
            waiting = false;
            acquireWakeLock();
            registerUsbReceiver();
        }
        startWorker();
        return START_NOT_STICKY;
    }

    private void startWorker() {
        synchronized (workerLock) {
            if (!serviceActive) return;
            if (worker != null && worker.isAlive()) return;
            stopping = false;
            waiting = false;
            worker = new Thread(this::runHeartbeat, "xqz-heartbeat");
            worker.start();
        }
    }

    private void runHeartbeat() {
        UsbManager manager = getSystemService(UsbManager.class);
        try {
            UsbDevice device = findDevice(manager);
            if (device == null) {
                waiting = true;
                updateNotification("Waiting for monitor");
                return;
            }
            if (!manager.hasPermission(device)) {
                waiting = true;
                updateNotification("Monitor connected — open XQZ Enabler to allow USB access");
                return;
            }
            UsbDeviceConnection conn = manager.openDevice(device);
            if (conn == null) {
                fail("Could not open USB device");
                return;
            }
            connection = conn;
            findEndpoints(device);
            if (usbInterface == null || epIn == null || epOut == null) {
                fail("No bulk IN/OUT interface");
                return;
            }
            if (!conn.claimInterface(usbInterface, true)) {
                fail("Could not claim interface");
                return;
            }
            waiting = false;
            Log.i(TAG, "connected; heartbeat every " + HEARTBEAT_MS + " ms");
            updateNotification("Running — " + HEARTBEAT_MS + " ms heartbeat");

            Thread reader = new Thread(this::readLoop, "xqz-reader");
            reader.start();

            byte[] show = "show".getBytes(StandardCharsets.UTF_8);
            while (!stopping && serviceActive) {
                int n = conn.bulkTransfer(epOut, show, show.length, 50);
                if (n < 0) Log.w(TAG, "show bulkTransfer failed");
                try {
                    Thread.sleep(HEARTBEAT_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            reader.interrupt();
            try {
                reader.join(300);
            } catch (InterruptedException ignored) {
            }
        } catch (Throwable t) {
            Log.e(TAG, "heartbeat failed", t);
            if (serviceActive) {
                waiting = true;
                updateNotification("Error: " + t.getMessage());
            }
        } finally {
            cleanup();
            synchronized (workerLock) {
                worker = null;
            }
            maybeRestart();
        }
    }

    private void maybeRestart() {
        if (!serviceActive || !waiting) return;
        UsbManager manager = getSystemService(UsbManager.class);
        UsbDevice device = findDevice(manager);
        if (device != null && manager.hasPermission(device)) {
            handler.postDelayed(this::startWorker, 300);
        }
    }

    private UsbDevice findDevice(UsbManager manager) {
        if (manager == null) return null;
        for (UsbDevice d : manager.getDeviceList().values()) {
            if (d.getVendorId() == VID && d.getProductId() == PID) return d;
        }
        return null;
    }

    private void readLoop() {
        byte[] buf = new byte[64];
        while (!stopping && serviceActive && connection != null) {
            try {
                int n = connection.bulkTransfer(epIn, buf, buf.length, 50);
                if (n > 0) {
                    String s = new String(buf, 0, n, StandardCharsets.UTF_8).trim();
                    Log.i(TAG, "RX " + s);
                    if (s.contains("flip")) updateNotification("FLIP pressed");
                }
            } catch (Throwable t) {
                if (!stopping) Log.w(TAG, "read failed", t);
                return;
            }
        }
    }

    private void findEndpoints(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            UsbEndpoint in = null;
            UsbEndpoint out = null;
            for (int j = 0; j < intf.getEndpointCount(); j++) {
                UsbEndpoint ep = intf.getEndpoint(j);
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) in = ep;
                else if (ep.getDirection() == UsbConstants.USB_DIR_OUT) out = ep;
            }
            if (in != null && out != null) {
                usbInterface = intf;
                epIn = in;
                epOut = out;
                return;
            }
        }
    }

    private synchronized void cleanup() {
        try {
            if (connection != null && epOut != null) {
                connection.bulkTransfer(epOut,
                        "stop".getBytes(StandardCharsets.UTF_8), 4, 50);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (connection != null && usbInterface != null) {
                connection.releaseInterface(usbInterface);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (connection != null) connection.close();
        } catch (Throwable ignored) {
        }
        connection = null;
        usbInterface = null;
        epIn = null;
        epOut = null;
    }

    private void fail(String message) {
        Log.e(TAG, message);
        stopping = true;
        waiting = false;
        serviceActive = false;
        updateNotification(message);
        stopSelf();
    }

    private void registerUsbReceiver() {
        if (receiverRegistered) return;
        IntentFilter f = new IntentFilter();
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, f);
        }
        receiverRegistered = true;
    }

    private void unregisterUsbReceiver() {
        if (!receiverRegistered) return;
        try {
            unregisterReceiver(usbReceiver);
        } catch (Throwable ignored) {
        }
        receiverRegistered = false;
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "XQZEnabler:heartbeat");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(4 * 60 * 60 * 1000L);
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                "XQZ monitor heartbeat", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Keeps the XQZ-IV01 display enabled");
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, HeartbeatService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action action = new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi).build();
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("XQZ monitor")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(content)
                .addAction(action)
                .build();
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.notify(NOTIF_ID, buildNotification(text));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroy() {
        serviceActive = false;
        waiting = false;
        stopping = true;
        handler.removeCallbacksAndMessages(null);
        unregisterUsbReceiver();
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(500);
            } catch (InterruptedException ignored) {
            }
        }
        cleanup();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
