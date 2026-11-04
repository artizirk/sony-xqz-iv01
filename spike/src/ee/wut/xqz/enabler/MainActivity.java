package ee.wut.xqz.enabler;

import android.Manifest;
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
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION = "ee.wut.xqz.enabler.USB_PERMISSION";
    private static final int VID = 0x054C;
    private static final int PID = 0x0E48;

    private UsbManager usbManager;
    private TextView statusView;
    private Button startButton;
    private Button stopButton;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean receiverRegistered;

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (granted && isOurDevice(device)) {
                startHeartbeat();
            } else {
                toast("USB permission denied");
            }
        }
    };

    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usbManager = getSystemService(UsbManager.class);
        setContentView(buildUi());
        registerPermissionReceiver();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private ScrollView buildUi() {
        int pad = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("XQZ Monitor Enabler");
        title.setTextSize(24);
        title.setGravity(Gravity.START);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView help = new TextView(this);
        help.setText(
                "1. Turn the monitor's screen switch ON and connect it to the phone.\n\n"
                + "2. Turn OFF DeX auto-start:\n"
                + "Settings > Connected devices > Samsung DeX >\n"
                + "\"Auto start when HDMI is connected\" = off.\n\n"
                + "3. Tap Start and allow USB access.\n\n"
                + "4. Open the Camera app. The monitor should mirror the screen.\n\n"
                + "If DeX starts instead, switch to Screen mirroring from the "
                + "notification, or turn off DeX auto-start and reconnect.\n\n"
                + "The service keeps running and reconnects when the monitor is "
                + "replugged. Add the XQZ monitor tile to Quick Settings for "
                + "one-tap toggling.\n\n"
                + "No MediaProjection, no audio, no FLIP handling; mirroring is "
                + "provided by One UI.");
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
        startButton.setText("Start");
        startButton.setOnClickListener(v -> onStartClicked());
        stopButton = new Button(this);
        stopButton.setText("Stop");
        stopButton.setOnClickListener(v -> stopHeartbeat());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        buttons.addView(startButton, lp);
        buttons.addView(stopButton, new LinearLayout.LayoutParams(lp));
        root.addView(buttons);

        TextView foot = new TextView(this);
        foot.setText("\nIf the camera preview is black on the monitor, the camera app is "
                + "probably blocking screen capture; that is the signal to try the "
                + "MediaProjection path.");
        foot.setTextSize(14);
        root.addView(foot);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private void onStartClicked() {
        UsbDevice device = findDevice();
        if (device == null) {
            toast("Monitor not connected (054c:0e48)");
            return;
        }
        if (usbManager == null) {
            toast("USB host service unavailable");
            return;
        }
        if (usbManager.hasPermission(device)) {
            startHeartbeat();
            return;
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()), flags);
        statusView.setText("Waiting for USB permission…");
        usbManager.requestPermission(device, pi);
    }

    private void startHeartbeat() {
        try {
            startForegroundService(new Intent(this, HeartbeatService.class));
            statusView.setText("Heartbeat starting…");
        } catch (Throwable t) {
            toast("Could not start service: " + t.getMessage());
        }
    }

    private void stopHeartbeat() {
        stopService(new Intent(this, HeartbeatService.class));
        statusView.setText("Heartbeat stopped");
    }

    private UsbDevice findDevice() {
        if (usbManager == null) return null;
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            if (isOurDevice(d)) return d;
        }
        return null;
    }

    private static boolean isOurDevice(UsbDevice d) {
        return d != null && d.getVendorId() == VID && d.getProductId() == PID;
    }

    private boolean hasPermission(UsbDevice d) {
        return d != null && usbManager != null && usbManager.hasPermission(d);
    }

    private void updateStatus() {
        if (statusView == null) return;
        UsbDevice device = findDevice();
        boolean present = device != null;
        boolean running = HeartbeatService.isRunning();
        boolean waiting = running && HeartbeatService.isWaiting();
        boolean canGrant = present && !hasPermission(device);
        String heartbeat = !running ? "stopped"
                : (waiting ? (canGrant ? "waiting (allow USB)" : "waiting") : "running");
        statusView.setText("Monitor: " + (present ? "connected" : "not connected")
                + "\nHeartbeat: " + heartbeat);
        startButton.setEnabled(!running || (waiting && canGrant));
        stopButton.setEnabled(running);
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
