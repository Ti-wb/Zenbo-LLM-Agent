package com.robot.asus.kira;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

public class MainActivity extends Activity implements GeckoSession.PermissionDelegate {

    private static final String TAG = "MainActivity";
    private static final int AUDIO_PERMISSION_REQUEST_CODE = 1;
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 2;
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 3;
    private static volatile MainActivity foregroundActivity;

    private GeckoView mGeckoView;
    private GeckoSession mGeckoSession;
    private GeckoRuntime mGeckoRuntime;
    private View runtimeRecovery;
    private final Handler readinessHandler = new Handler(Looper.getMainLooper());
    private boolean rendererLoaded;
    private final Runnable showRecovery = () -> {
        if (!rendererLoaded && runtimeRecovery != null) runtimeRecovery.setVisibility(View.VISIBLE);
    };
    private final Runnable loadWhenReady = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed() || rendererLoaded) return;
            if (RobotApiService.isLocalRuntimeReady()) {
                String bootstrapSecret = RobotApiService.issueRendererBootstrapSecret();
                if (bootstrapSecret != null) {
                    rendererLoaded = true;
                    readinessHandler.removeCallbacks(showRecovery);
                    if (runtimeRecovery != null) runtimeRecovery.setVisibility(View.GONE);
                    mGeckoSession.loadUri("http://127.0.0.1:8787/#bootstrapToken=" + bootstrapSecret);
                    return;
                }
                readinessHandler.postDelayed(this, 250L);
            } else {
                readinessHandler.postDelayed(this, 250L);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        enterImmersiveMode();
        startRobotService();

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST_CODE);
            }
        }

        mGeckoView = findViewById(R.id.geckoview);
        runtimeRecovery = findViewById(R.id.runtime_recovery);
        findViewById(R.id.runtime_retry).setOnClickListener(view -> retryRuntime());
        mGeckoSession = new GeckoSession();

        mGeckoSession.setPermissionDelegate(this);

        mGeckoRuntime = GeckoRuntimeHolder.get(this);

        mGeckoSession.open(mGeckoRuntime);
        mGeckoView.setSession(mGeckoSession);
        readinessHandler.post(loadWhenReady);
        readinessHandler.postDelayed(showRecovery, 15_000L);
    }

    private void startRobotService() {
        Intent intent = new Intent(this, RobotApiService.class);
        ContextCompat.startForegroundService(this, intent);
    }

    private void retryRuntime() {
        readinessHandler.removeCallbacks(loadWhenReady);
        readinessHandler.removeCallbacks(showRecovery);
        rendererLoaded = false;
        if (runtimeRecovery != null) runtimeRecovery.setVisibility(View.GONE);
        stopService(new Intent(this, RobotApiService.class));
        readinessHandler.postDelayed(() -> {
            startRobotService();
            readinessHandler.post(loadWhenReady);
            readinessHandler.postDelayed(showRecovery, 15_000L);
        }, 500L);
    }

    private void loadFreshRenderer() {
        readinessHandler.removeCallbacks(loadWhenReady);
        readinessHandler.removeCallbacks(showRecovery);
        rendererLoaded = false;
        readinessHandler.post(loadWhenReady);
        readinessHandler.postDelayed(showRecovery, 15_000L);
    }

    @Override
    protected void onResume() {
        super.onResume();
        foregroundActivity = this;
        // Keep the display awake only while this activity owns the foreground.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        RobotApiService.setRendererForeground(true);
    }

    @Override
    protected void onPause() {
        if (foregroundActivity == this) foregroundActivity = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        RobotApiService.setRendererForeground(false);
        super.onPause();
    }

    @Override
    public void onMediaPermissionRequest(@NonNull GeckoSession session, @NonNull String uri,
                                         GeckoSession.PermissionDelegate.MediaSource[] video, @NonNull GeckoSession.PermissionDelegate.MediaSource[] audio,
                                         @NonNull GeckoSession.PermissionDelegate.MediaCallback callback) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION_REQUEST_CODE);
        } else {
            if (audio != null && audio.length > 0) {
                callback.grant(null, audio[0]);
            } else {
                callback.reject();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == AUDIO_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadFreshRenderer();
            }
        } else if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            // The foreground service is started regardless; this permission only
            // controls whether Android 13+ shows its notification to the user.
        } else if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            DeviceHardware hardware = RobotApiService.getDeviceHardware();
            if (hardware != null) hardware.onCameraPermissionResult(grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED);
        }
    }

    /** The native Activity owns Android permission prompts; the Web renderer cannot grant them. */
    static boolean requestNativeCameraPermission() {
        MainActivity activity = foregroundActivity;
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        activity.runOnUiThread(() -> {
            if (foregroundActivity == activity) ActivityCompat.requestPermissions(activity,
                    new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
        });
        return true;
    }

    @Override
    protected void onDestroy() {
        readinessHandler.removeCallbacksAndMessages(null);
        if (mGeckoSession != null) mGeckoSession.close();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersiveMode();
    }

    private void enterImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }
}
