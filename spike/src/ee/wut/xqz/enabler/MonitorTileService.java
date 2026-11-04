package ee.wut.xqz.enabler;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

public final class MonitorTileService extends TileService {
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        RunState state = HeartbeatService.getState();
        if (state == RunState.RUNNING) {
            HeartbeatService.requestStop(this);
            refreshLater();
            return;
        }
        if (state.isActive()) {
            if (state == RunState.WAITING_PERMISSION) {
                openApp();
            } else {
                try {
                    startForegroundService(new Intent(this, HeartbeatService.class));
                    HeartbeatService.setUserEnabled(this, true);
                } catch (Throwable t) {
                    Toast.makeText(this, getString(R.string.toast_start_failed, t.getMessage()),
                            Toast.LENGTH_SHORT).show();
                }
            }
            refreshLater();
            return;
        }

        UsbDevice device = findDevice();
        if (device == null) {
            Toast.makeText(this, R.string.toast_monitor_not_connected,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        UsbManager manager = getSystemService(UsbManager.class);
        if (manager == null || !manager.hasPermission(device)) {
            Toast.makeText(this, R.string.toast_open_app_for_usb,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (NotificationPermission.shouldAsk(this)) {
            Toast.makeText(this, R.string.toast_enable_notifications,
                    Toast.LENGTH_SHORT).show();
            openApp();
            return;
        }

        try {
            startForegroundService(new Intent(this, HeartbeatService.class));
            HeartbeatService.setUserEnabled(this, true);
        } catch (Throwable t) {
            Toast.makeText(this, getString(R.string.toast_start_failed, t.getMessage()),
                    Toast.LENGTH_SHORT).show();
        }
        refreshLater();
    }

    private void refreshLater() {
        handler.postDelayed(this::updateTile, 700);
    }

    private void openApp() {
        try {
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) {
        }
    }

    private UsbDevice findDevice() {
        UsbManager manager = getSystemService(UsbManager.class);
        if (manager == null) return null;
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (MonitorProtocol.isMonitor(device.getVendorId(), device.getProductId())) {
                return device;
            }
        }
        return null;
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        RunState state = HeartbeatService.getState();
        tile.setState(state == RunState.RUNNING ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(getString(R.string.tile_label));
        tile.setSubtitle(getString(subtitleFor(state)));
        tile.updateTile();
    }

    private static int subtitleFor(RunState state) {
        switch (state) {
            case RUNNING:
                return R.string.tile_running;
            case WAITING_DEVICE:
                return R.string.tile_waiting_device;
            case WAITING_PERMISSION:
                return R.string.tile_waiting_permission;
            case RETRYING:
                return R.string.tile_retrying;
            case STOPPED:
            default:
                return R.string.tile_stopped;
        }
    }

    /** Ask the system to bind the tile so its state/subtitle refresh. */
    public static void requestUpdate(Context context) {
        try {
            TileService.requestListeningState(context,
                    new ComponentName(context, MonitorTileService.class));
        } catch (Throwable ignored) {
        }
    }
}
