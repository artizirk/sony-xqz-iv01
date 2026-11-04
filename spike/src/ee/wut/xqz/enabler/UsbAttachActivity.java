package ee.wut.xqz.enabler;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * Entry point for USB_DEVICE_ATTACHED. Android only grants a USB permission for the
 * current attach, so this activity is how a replug can reconnect without the user
 * reopening the app. It is transparent and finishes immediately.
 */
public final class UsbAttachActivity extends Activity {
    private static final String ACTION_USB_PERMISSION =
            "ee.wut.xqz.enabler.ATTACH_USB_PERMISSION";
    private static final long PERMISSION_TIMEOUT_MS = 60_000;
    private static final int REQ_NOTIFICATIONS = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean receiverRegistered;

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (granted && isMonitor(device)) {
                startAndFinish();
            } else {
                Toast.makeText(UsbAttachActivity.this, R.string.toast_permission_denied,
                        Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        UsbDevice device = intent == null
                ? null : intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (!isMonitor(device)) {
            device = findDevice();
        }
        if (!isMonitor(device)) {
            finish();
            return;
        }
        if (!HeartbeatService.isUserEnabled(this)) {
            finish();
            return;
        }
        UsbManager manager = getSystemService(UsbManager.class);
        if (manager == null) {
            finish();
            return;
        }
        if (manager.hasPermission(device)) {
            startAndFinish();
            return;
        }
        registerPermissionReceiver();
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()), flags);
        try {
            manager.requestPermission(device, pi);
        } catch (Throwable t) {
            finish();
            return;
        }
        handler.postDelayed(this::finish, PERMISSION_TIMEOUT_MS);
    }

    private void startAndFinish() {
        boolean wasActive = HeartbeatService.getState().isActive();
        try {
            startForegroundService(new Intent(this, HeartbeatService.class));
            if (!wasActive) {
                Toast.makeText(this, R.string.toast_monitor_starting,
                        Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Toast.makeText(this, getString(R.string.toast_start_failed, t.getMessage()),
                    Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (NotificationPermission.shouldAsk(this)) {
            NotificationPermission.ask(this, REQ_NOTIFICATIONS);
            handler.postDelayed(this::finish, PERMISSION_TIMEOUT_MS);
            return;
        }
        finish();
    }

    private UsbDevice findDevice() {
        UsbManager manager = getSystemService(UsbManager.class);
        if (manager == null) return null;
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (isMonitor(device)) return device;
        }
        return null;
    }

    private static boolean isMonitor(UsbDevice device) {
        return device != null
                && MonitorProtocol.isMonitor(device.getVendorId(), device.getProductId());
    }

    private void registerPermissionReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(permissionReceiver, filter);
        }
        receiverRegistered = true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_NOTIFICATIONS
                && (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, R.string.toast_notifications_blocked,
                    Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (receiverRegistered) {
            try {
                unregisterReceiver(permissionReceiver);
            } catch (Throwable ignored) {
            }
            receiverRegistered = false;
        }
        super.onDestroy();
    }
}
