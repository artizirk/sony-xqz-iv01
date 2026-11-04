package ee.wut.xqz.enabler;

import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

public final class MonitorTileService extends TileService {
    private static final int VID = 0x054C;
    private static final int PID = 0x0E48;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        if (HeartbeatService.isRunning()) {
            stopService(new Intent(this, HeartbeatService.class));
            handler.postDelayed(this::updateTile, 700);
            return;
        }

        UsbDevice device = findDevice();
        if (device == null) {
            Toast.makeText(this, "Connect the XQZ monitor", Toast.LENGTH_SHORT).show();
            return;
        }
        UsbManager manager = getSystemService(UsbManager.class);
        if (manager == null || !manager.hasPermission(device)) {
            Toast.makeText(this, "Open XQZ Enabler to allow USB access",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            startForegroundService(new Intent(this, HeartbeatService.class));
        } catch (Throwable t) {
            Toast.makeText(this, "Could not start: " + t.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
        handler.postDelayed(this::updateTile, 700);
    }

    private UsbDevice findDevice() {
        UsbManager manager = getSystemService(UsbManager.class);
        if (manager == null) return null;
        for (UsbDevice d : manager.getDeviceList().values()) {
            if (d.getVendorId() == VID && d.getProductId() == PID) return d;
        }
        return null;
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(HeartbeatService.isRunning() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel("XQZ monitor");
        tile.updateTile();
    }
}
