package com.robot.asus.kira;

import android.content.Context;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;

public class GeckoRuntimeHolder {
    private static GeckoRuntime sGeckoRuntime;

    public static synchronized GeckoRuntime get(Context context) {
        if (sGeckoRuntime == null) {
            GeckoRuntimeSettings.Builder settingsBuilder = new GeckoRuntimeSettings.Builder()
                    .aboutConfigEnabled(BuildConfig.DEBUG)
                    .consoleOutput(BuildConfig.DEBUG)
                    .debugLogging(false)
                    .remoteDebuggingEnabled(BuildConfig.DEBUG);
            sGeckoRuntime = GeckoRuntime.create(context.getApplicationContext(), settingsBuilder.build());
        }
        return sGeckoRuntime;
    }
}
