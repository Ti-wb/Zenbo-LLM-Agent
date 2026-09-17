package com.robot.asus.kira;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.content.ContextCompat;

public class BootCompletedReceiver extends BroadcastReceiver {

    private static final String TAG = "BootCompletedReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.i(TAG, "Boot completed, starting the background robot runtime.");

            // Start the foreground service so the robot API + HTTP/WS servers are available.
            Intent serviceIntent = new Intent(context, RobotApiService.class);
            ContextCompat.startForegroundService(context, serviceIntent);

            // Foreground UI belongs to the user: boot keeps the runtime available without
            // replacing a system screen or another app with MainActivity.
        }
    }
}
