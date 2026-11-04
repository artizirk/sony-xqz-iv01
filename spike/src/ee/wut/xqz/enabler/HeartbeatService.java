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

public final class HeartbeatService extends Service {
    private static final String TAG = "XQZEnabler";
    private static final String CHANNEL_ID = "xqz_heartbeat";
    private static final String ACTION_STOP = "ee.wut.xqz.enabler.STOP";
    private static final int NOTIF_ID = 1;
    private static final long WAIT_POLL_MS = 2_000;
    private static final long WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L;
    private static final long WAKE_LOCK_RENEW_MS = 60_000;
    private static final long FLIP_TEXT_MS = 1_500;

    private static volatile boolean serviceActive;
    private static volatile RunState state = RunState.STOPPED;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Object workerLock = new Object();
    private final Object cleanupLock = new Object();
    private final Object wakeLockLock = new Object();
    private final RetryBackoff backoff = new RetryBackoff(300, 5_000);

    private volatile boolean stopping;
    private volatile boolean sessionStop;
    private volatile Thread worker;
    private volatile Thread reader;
    private volatile UsbDeviceConnection connection;
    private volatile UsbInterface usbInterface;
    private volatile UsbEndpoint epIn;
    private volatile UsbEndpoint epOut;
    private volatile long nextRetryDelayMs = 300;
    private volatile String currentText;
    private boolean receiverRegistered;
    private volatile PowerManager.WakeLock wakeLock;

    private final Runnable wakeLockRenewal = this::renewWakeLock;

    public static RunState getState() {
        return state;
    }

    /** Whether the user has asked the service to be on; used by the attach handler. */
    public static boolean isUserEnabled(Context context) {
        return Settings.get(context).getBoolean(Settings.ENABLED, false);
    }

    public static void setUserEnabled(Context context, boolean enabled) {
        // commit(), not apply(): a Stop followed by process death must stay stopped.
        Settings.get(context).edit().putBoolean(Settings.ENABLED, enabled).commit();
    }

    /** Stop from the app/tile: remember the user's choice and destroy the service. */
    public static void requestStop(Context context) {
        setUserEnabled(context, false);
        context.stopService(new Intent(context, HeartbeatService.class));
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (!isMonitor(device)) {
                return;
            }
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                Log.i(TAG, "monitor detached");
                sessionStop = true;
                backoff.reset();
                if (serviceActive) {
                    setState(RunState.WAITING_DEVICE, getString(R.string.notif_waiting_device));
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
                Log.i(TAG, "monitor attached");
                if (serviceActive) {
                    setState(RunState.WAITING_DEVICE, getString(R.string.notif_connected_starting));
                    handler.postDelayed(HeartbeatService.this::startWorker, 300);
                }
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
            setUserEnabled(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!serviceActive) {
            try {
                startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_starting)),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } catch (Throwable t) {
                Log.e(TAG, "startForeground failed", t);
                stopSelf();
                return START_NOT_STICKY;
            }
            serviceActive = true;
            state = RunState.WAITING_DEVICE;
            registerUsbReceiver();
        }
        startWorker();
        return START_NOT_STICKY;
    }

    private void startWorker() {
        synchronized (workerLock) {
            if (!serviceActive || stopping) return;
            if (worker != null && worker.isAlive()) return;
            sessionStop = false;
            worker = new Thread(this::runSession, "xqz-heartbeat");
            worker.start();
        }
    }

    private void runSession() {
        UsbManager manager = getSystemService(UsbManager.class);
        UsbDeviceConnection conn = null;
        UsbInterface intf = null;
        Endpoints endpoints = null;
        Thread readerThread = null;
        try {
            if (manager == null) {
                retry(getString(R.string.notif_usb_unavailable));
                return;
            }
            UsbDevice device = findDevice(manager);
            if (device == null) {
                backoff.reset();
                setState(RunState.WAITING_DEVICE, getString(R.string.notif_waiting_device));
                return;
            }
            if (!manager.hasPermission(device)) {
                backoff.reset();
                setState(RunState.WAITING_PERMISSION, getString(R.string.notif_waiting_permission));
                return;
            }
            conn = manager.openDevice(device);
            if (conn == null) {
                retry(getString(R.string.notif_open_failed));
                return;
            }
            // Publish before any early return so the cleanup path can always close it.
            connection = conn;
            if (isSessionStopped()) {
                return;
            }
            endpoints = findEndpoints(device);
            if (endpoints == null) {
                retry(getString(R.string.notif_no_endpoints));
                return;
            }
            intf = endpoints.usbInterface;
            if (!conn.claimInterface(intf, true)) {
                retry(getString(R.string.notif_claim_failed));
                return;
            }
            if (isSessionStopped()) {
                return;
            }

            usbInterface = intf;
            epIn = endpoints.in;
            epOut = endpoints.out;
            backoff.reset();
            if (isSessionStopped()) {
                return;
            }

            Log.i(TAG, "connected; heartbeat every " + MonitorProtocol.HEARTBEAT_MS + " ms");
            setState(RunState.RUNNING, runningText());
            acquireWakeLock();

            final UsbDeviceConnection sessionConn = conn;
            final HeartbeatLoop.Link link =
                    new UsbLink(sessionConn, endpoints.in, endpoints.out);

            readerThread = new Thread(() -> HeartbeatLoop.read(link, this::isSessionStopped,
                    this::onRx, MonitorProtocol.READ_TIMEOUT_MS, Thread::sleep), "xqz-reader");
            synchronized (workerLock) {
                reader = readerThread;
            }
            readerThread.start();

            HeartbeatLoop.heartbeat(link, Thread::sleep, this::isSessionStopped,
                    MonitorProtocol.HEARTBEAT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (HeartbeatLoop.WriteFailure e) {
            Log.w(TAG, "heartbeat write failed", e);
            if (serviceActive) {
                retry(getString(R.string.notif_heartbeat_failed));
            }
        } catch (Throwable t) {
            Log.e(TAG, "session failed", t);
            if (serviceActive) {
                retry(getString(R.string.notif_session_failed, message(t)));
            }
        } finally {
            cleanupSession(conn, intf, endpoints == null ? null : endpoints.out, readerThread);
            synchronized (workerLock) {
                worker = null;
                reader = null;
            }
            releaseWakeLock();
            scheduleNextAttempt();
        }
    }

    private void scheduleNextAttempt() {
        if (!serviceActive || stopping) {
            return;
        }
        long delay;
        if (state == RunState.RETRYING) {
            delay = nextRetryDelayMs;
        } else if (state == RunState.WAITING_DEVICE || state == RunState.WAITING_PERMISSION) {
            delay = WAIT_POLL_MS;
        } else {
            retry(getString(R.string.notif_connection_ended));
            delay = nextRetryDelayMs;
        }
        handler.postDelayed(this::startWorker, delay);
    }

    private void retry(String message) {
        if (!RunState.mayPublish(serviceActive, stopping)) {
            return;
        }
        nextRetryDelayMs = backoff.nextDelayMs();
        setState(RunState.RETRYING, getString(R.string.notif_retry, message));
        releaseWakeLock();
    }

    private boolean isSessionStopped() {
        return sessionStop || stopping || !serviceActive;
    }

    private void onRx(String text) {
        Log.i(TAG, "RX " + text);
        if (stopping || !serviceActive) {
            return;
        }
        if (MonitorProtocol.isFlip(text)) {
            updateNotification(getString(R.string.notif_flip));
            handler.postDelayed(() -> {
                if (serviceActive && !stopping && currentText != null) {
                    updateNotification(currentText);
                }
            }, FLIP_TEXT_MS);
        }
    }

    private void cleanupSession(UsbDeviceConnection conn, UsbInterface intf, UsbEndpoint out,
            Thread readerThread) {
        sessionStop = true;
        synchronized (cleanupLock) {
            if (conn != null && connection != conn) {
                return;
            }
            if (readerThread != null) {
                join(readerThread, MonitorProtocol.READ_TIMEOUT_MS + 250L);
            }
            if (conn != null) {
                if (out != null) {
                    try {
                        conn.bulkTransfer(out, MonitorProtocol.STOP,
                                MonitorProtocol.STOP.length, 50);
                    } catch (Throwable ignored) {
                    }
                }
                if (intf != null) {
                    try {
                        conn.releaseInterface(intf);
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    conn.close();
                } catch (Throwable ignored) {
                }
            }
            if (readerThread != null) {
                join(readerThread, 250);
            }
            synchronized (workerLock) {
                if (connection == conn) {
                    connection = null;
                    usbInterface = null;
                    epIn = null;
                    epOut = null;
                }
                if (reader == readerThread) {
                    reader = null;
                }
            }
        }
    }

    private static void join(Thread thread, long millis) {
        try {
            thread.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isMonitor(UsbDevice device) {
        return device != null
                && MonitorProtocol.isMonitor(device.getVendorId(), device.getProductId());
    }

    private UsbDevice findDevice(UsbManager manager) {
        if (manager == null) return null;
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (isMonitor(device)) return device;
        }
        return null;
    }

    private Endpoints findEndpoints(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            if (!MonitorProtocol.isVendorInterface(intf.getInterfaceClass(),
                    intf.getInterfaceSubclass(), intf.getInterfaceProtocol())) {
                continue;
            }
            UsbEndpoint in = null;
            UsbEndpoint out = null;
            UsbEndpoint preferredIn = null;
            UsbEndpoint preferredOut = null;
            for (int j = 0; j < intf.getEndpointCount(); j++) {
                UsbEndpoint endpoint = intf.getEndpoint(j);
                if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) {
                    if (in == null) in = endpoint;
                    if (endpoint.getAddress() == MonitorProtocol.ENDPOINT_IN) {
                        preferredIn = endpoint;
                    }
                } else {
                    if (out == null) out = endpoint;
                    if (endpoint.getAddress() == MonitorProtocol.ENDPOINT_OUT) {
                        preferredOut = endpoint;
                    }
                }
            }
            if (preferredIn != null) in = preferredIn;
            if (preferredOut != null) out = preferredOut;
            if (in != null && out != null) {
                return new Endpoints(intf, in, out);
            }
        }
        return null;
    }

    private void registerUsbReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
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

    private void renewWakeLock() {
        synchronized (wakeLockLock) {
            if (state != RunState.RUNNING || !serviceActive || stopping || wakeLock == null) {
                return;
            }
            if (!wakeLock.isHeld()) {
                wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
            }
            handler.removeCallbacks(wakeLockRenewal);
            handler.postDelayed(wakeLockRenewal, WAKE_LOCK_RENEW_MS);
        }
    }

    private void acquireWakeLock() {
        synchronized (wakeLockLock) {
            if (!serviceActive || stopping || state != RunState.RUNNING) {
                return;
            }
            if (wakeLock == null) {
                PowerManager pm = getSystemService(PowerManager.class);
                if (pm == null) return;
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "XQZEnabler:heartbeat");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) {
                wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
            }
            handler.removeCallbacks(wakeLockRenewal);
            handler.postDelayed(wakeLockRenewal, WAKE_LOCK_RENEW_MS);
        }
    }

    private void releaseWakeLock() {
        synchronized (wakeLockLock) {
            handler.removeCallbacks(wakeLockRenewal);
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        }
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notif_channel_desc));
        nm.createNotificationChannel(channel);
    }

    private String runningText() {
        return getString(R.string.notif_running, MonitorProtocol.HEARTBEAT_MS);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, HeartbeatService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action action = new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notif_stop), stopPi).build();
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(getString(R.string.notif_title))
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

    private void setState(RunState newState, String notificationText) {
        if (!RunState.mayPublish(serviceActive, stopping)) {
            return;
        }
        state = newState;
        if (notificationText != null) {
            currentText = notificationText;
            updateNotification(notificationText);
        }
        MonitorTileService.requestUpdate(this);
    }

    private static String message(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isEmpty()
                ? throwable.getClass().getSimpleName() : message;
    }

    @Override
    public void onDestroy() {
        serviceActive = false;
        stopping = true;
        sessionStop = true;
        state = RunState.STOPPED;
        handler.removeCallbacksAndMessages(null);
        unregisterUsbReceiver();

        Thread currentWorker;
        synchronized (workerLock) {
            currentWorker = worker;
        }
        if (currentWorker != null) {
            currentWorker.interrupt();
            join(currentWorker, 1_500);
            if (currentWorker.isAlive()) {
                UsbDeviceConnection active = connection;
                if (active != null) {
                    try {
                        active.close();
                    } catch (Throwable ignored) {
                    }
                }
                join(currentWorker, 500);
            }
        }

        UsbDeviceConnection leftover = connection;
        UsbInterface leftoverInterface = usbInterface;
        UsbEndpoint leftoverOut = epOut;
        Thread leftoverReader = reader;
        if (leftover != null) {
            cleanupSession(leftover, leftoverInterface, leftoverOut, leftoverReader);
        }
        releaseWakeLock();
        wakeLock = null;
        state = RunState.STOPPED;
        currentText = null;
        MonitorTileService.requestUpdate(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static final class Endpoints {
        final UsbInterface usbInterface;
        final UsbEndpoint in;
        final UsbEndpoint out;

        Endpoints(UsbInterface usbInterface, UsbEndpoint in, UsbEndpoint out) {
            this.usbInterface = usbInterface;
            this.in = in;
            this.out = out;
        }
    }

    private static final class UsbLink implements HeartbeatLoop.Link {
        private final UsbDeviceConnection connection;
        private final UsbEndpoint in;
        private final UsbEndpoint out;

        UsbLink(UsbDeviceConnection connection, UsbEndpoint in, UsbEndpoint out) {
            this.connection = connection;
            this.in = in;
            this.out = out;
        }

        @Override
        public int writeShow() {
            try {
                return connection.bulkTransfer(out, MonitorProtocol.SHOW,
                        MonitorProtocol.SHOW.length, 50);
            } catch (Throwable t) {
                return -1;
            }
        }

        @Override
        public int read(byte[] buffer, int timeoutMs) {
            try {
                return connection.bulkTransfer(in, buffer, buffer.length, timeoutMs);
            } catch (Throwable t) {
                return -1;
            }
        }
    }
}
