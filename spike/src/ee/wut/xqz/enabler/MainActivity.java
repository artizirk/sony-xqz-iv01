package ee.wut.xqz.enabler;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION = "ee.wut.xqz.enabler.USB_PERMISSION";
    private static final int REQ_NOTIFICATIONS = 1;

    private UsbManager usbManager;
    private TextView statusView;
    private Button startButton;
    private Button stopButton;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean receiverRegistered;
    private int basePadding;

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (granted && isOurDevice(device)) {
                startHeartbeat();
            } else {
                toast(getString(R.string.toast_permission_denied));
            }
        }
    };

    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            handler.postDelayed(this, 1_000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usbManager = getSystemService(UsbManager.class);
        setContentView(buildUi());
        registerPermissionReceiver();
    }

    private ScrollView buildUi() {
        basePadding = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(R.string.app_title);
        title.setTextSize(24);
        title.setGravity(Gravity.START);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView help = new TextView(this);
        help.setText(R.string.help_text);
        help.setTextSize(16);
        help.setPadding(0, dp(16), 0, dp(16));
        root.addView(help);

        statusView = new TextView(this);
        statusView.setTextSize(16);
        statusView.setPadding(0, dp(8), 0, dp(16));
        root.addView(statusView);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        startButton = new Button(this);
        startButton.setText(R.string.start);
        startButton.setOnClickListener(v -> onStartClicked());
        stopButton = new Button(this);
        stopButton.setText(R.string.stop);
        stopButton.setOnClickListener(v -> stopHeartbeat());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        buttons.addView(startButton, lp);
        buttons.addView(stopButton, new LinearLayout.LayoutParams(lp));
        root.addView(buttons);

        TextView foot = new TextView(this);
        foot.setText(R.string.footer);
        foot.setTextSize(14);
        root.addView(foot);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        scroll.setPadding(basePadding, basePadding, basePadding, basePadding);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(basePadding + left, basePadding + top,
                    basePadding + right, basePadding + bottom);
            return insets;
        });
        return scroll;
    }

    private void onStartClicked() {
        if (usbManager == null) {
            toast(getString(R.string.toast_usb_unavailable));
            return;
        }
        UsbDevice device = findDevice();
        if (device == null) {
            toast(getString(R.string.toast_monitor_not_connected));
            return;
        }
        if (usbManager.hasPermission(device)) {
            startHeartbeat();
            return;
        }
        requestUsbPermission(device);
    }

    private void requestUsbPermission(UsbDevice device) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()), flags);
        statusView.setText(R.string.status_waiting_permission);
        usbManager.requestPermission(device, pi);
    }

    private void startHeartbeat() {
        if (NotificationPermission.shouldAsk(this)) {
            NotificationPermission.ask(this, REQ_NOTIFICATIONS);
        }
        try {
            startForegroundService(new Intent(this, HeartbeatService.class));
            HeartbeatService.setUserEnabled(this, true);
            statusView.setText(R.string.status_starting);
        } catch (Throwable t) {
            toast(getString(R.string.toast_start_failed, t.getMessage()));
        }
    }

    private void stopHeartbeat() {
        HeartbeatService.requestStop(this);
        statusView.setText(R.string.status_stopped);
    }

    private UsbDevice findDevice() {
        if (usbManager == null) return null;
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            if (isOurDevice(d)) return d;
        }
        return null;
    }

    private static boolean isOurDevice(UsbDevice d) {
        return d != null
                && MonitorProtocol.isMonitor(d.getVendorId(), d.getProductId());
    }

    private boolean hasPermission(UsbDevice d) {
        return d != null && usbManager != null && usbManager.hasPermission(d);
    }

    private void updateStatus() {
        if (statusView == null) return;
        UsbDevice device = findDevice();
        boolean present = device != null;
        boolean canGrant = present && !hasPermission(device);
        RunState state = HeartbeatService.getState();

        String heartbeat;
        switch (state) {
            case WAITING_DEVICE:
                heartbeat = getString(R.string.heartbeat_waiting_device);
                break;
            case WAITING_PERMISSION:
                heartbeat = getString(canGrant
                        ? R.string.heartbeat_waiting_permission
                        : R.string.heartbeat_waiting_usb_access);
                break;
            case RETRYING:
                heartbeat = getString(R.string.heartbeat_reconnecting);
                break;
            case RUNNING:
                heartbeat = getString(R.string.heartbeat_running);
                break;
            case STOPPED:
            default:
                heartbeat = getString(R.string.heartbeat_stopped);
                break;
        }
        if (state.isActive() && !NotificationPermission.areEnabled(this)) {
            heartbeat += " " + getString(R.string.heartbeat_notifications_off);
        }
        statusView.setText(getString(R.string.status_format,
                getString(present ? R.string.monitor_connected : R.string.monitor_disconnected),
                heartbeat));
        startButton.setEnabled(state == RunState.STOPPED
                || (state == RunState.WAITING_PERMISSION && canGrant));
        stopButton.setEnabled(state.isActive());
    }

    private void registerPermissionReceiver() {
        IntentFilter f = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(permissionReceiver, f);
        }
        receiverRegistered = true;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_NOTIFICATIONS
                && (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED)) {
            toast(getString(R.string.toast_notifications_blocked));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        handler.post(statusTick);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(statusTick);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(statusTick);
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
