package ee.wut.xqz.enabler;

import android.content.Context;
import android.content.SharedPreferences;

/** Shared preference keys used across the activity, service, and attach handler. */
final class Settings {
    static final String FILE = "xqz_settings";
    static final String ENABLED = "enabled";
    static final String NOTIFICATION_ASKED = "notification_asked";

    private Settings() {
    }

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
