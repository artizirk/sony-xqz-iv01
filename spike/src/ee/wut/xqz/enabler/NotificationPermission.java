package ee.wut.xqz.enabler;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

/** One-time POST_NOTIFICATIONS prompt shared by every entry point. */
final class NotificationPermission {
    private NotificationPermission() {
    }

    static boolean isGranted(Context context) {
        return Build.VERSION.SDK_INT < 33
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    static boolean areEnabled(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        return nm != null && nm.areNotificationsEnabled();
    }

    static boolean shouldAsk(Context context) {
        return !isGranted(context)
                && !Settings.get(context).getBoolean(Settings.NOTIFICATION_ASKED, false);
    }

    static void ask(Activity activity, int requestCode) {
        Settings.get(activity).edit().putBoolean(Settings.NOTIFICATION_ASKED, true).apply();
        activity.requestPermissions(
                new String[]{Manifest.permission.POST_NOTIFICATIONS}, requestCode);
    }
}
