package com.robot.asus.kira;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.asus.robotframework.API.RobotAPI;
import com.asus.robotframework.API.RobotCallback;
import com.asus.robotframework.API.RobotCmdState;
import com.asus.robotframework.API.RobotErrorCode;
import com.asus.robotframework.API.RobotFace;
import com.asus.robotframework.API.Utility;
import com.asus.robotframework.API.results.DetectFaceResult;
import com.asus.robotframework.API.results.DetectPersonResult;
import com.asus.robotframework.API.results.FaceResult;
import com.asus.robotframework.API.results.GesturePointResult;
import com.asus.robotframework.API.results.RecognizePersonResult;
import com.asus.robotframework.API.results.TrackingResult;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;

public class RobotApiService extends Service {

    private static final String TAG = "RobotApiService";
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "RobotApiServiceChannel";
    // API 23 CYPRESS firmware also reports completed quick taps as type 1.
    private static final int FIRMWARE_QUICK_HEAD_TOUCH = 1;
    private static volatile boolean localRuntimeReady;
    private static volatile LocalRuntimeServer activeLocalRuntime;
    private static volatile RobotApiService activeRobotService;
    private static volatile boolean rendererForeground;

    private RobotAPI robotAPI;
    private boolean robotApiInitialized;
    private final Handler displayHandler = new Handler(Looper.getMainLooper());
    private GatewaySettings gatewaySettings;
    private DeviceCredentialStore credentialStore;
    private RobotGateway robotGateway;
    private DeviceHardware deviceHardware;
    private RemoteSessionCoordinator sessionCoordinator;
    private LocalRuntimeServer localRuntimeServer;
    private BroadcastReceiver screenEventReceiver;
    private BroadcastReceiver batteryReceiver;
    private SensorManager sensorManager;
    private SensorEventListener headTouchListener;

    private void sendEvent(String event, JSONObject data) {
        Log.d(TAG, "Sending robot event type '" + event + "'");
        if (sessionCoordinator != null) {
            sessionCoordinator.publishRobotEvent(event, data);
        }
    }

    private void sendEvent(String event, String data) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("data", data);
            Log.d(TAG, "Sending robot event type '" + event + "'");
            if (sessionCoordinator != null) {
                sessionCoordinator.publishRobotEvent(event, obj);
            }
        } catch (JSONException e) {
            Log.e(TAG, "sendEvent: JSONException", e);
        }
    }


    @Override
    public void onCreate() {
        super.onCreate();
        activeRobotService = this;

        createNotificationChannel();
        registerScreenEventReceiver();

        // Keep the runtime alive without a call-style overlay covering the device UI.
        Intent contentIntent = new Intent(this, MainActivity.class);
        contentIntent.setAction(Intent.ACTION_MAIN);
        contentIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        contentIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

        PendingIntent contentPendingIntent = PendingIntent.getActivity(
                this,
                0,
                contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Zenbo API Service")
                .setContentText("Robot API server is running.")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentPendingIntent)
                .build();

        startForeground(NOTIFICATION_ID, notification);

        // The renderer and remote session runtime must be available even when the
        // vendor RobotAPI is missing, incompatible, or slow to initialize.
        gatewaySettings = new GatewaySettings(getApplicationContext());
        credentialStore = new DeviceCredentialStore(getApplicationContext());
        robotGateway = new RobotGateway();
        deviceHardware = new DeviceHardware(getApplicationContext(), robotGateway);
        deviceHardware.setMotionAllowed(gatewaySettings.isMotionEnabled());
        deviceHardware.setForeground(rendererForeground);
        sessionCoordinator = new RemoteSessionCoordinator(gatewaySettings, credentialStore, robotGateway);
        sessionCoordinator.setDeviceHardware(deviceHardware);
        localRuntimeServer = new LocalRuntimeServer(
                getApplicationContext(),
                gatewaySettings,
                credentialStore,
                sessionCoordinator,
                robotGateway,
                deviceHardware
        );
        sessionCoordinator.setLocalPublisher(localRuntimeServer::publish);
        registerBatteryReceiver();
        try {
            localRuntimeServer.start(8787);
            activeLocalRuntime = localRuntimeServer;
            localRuntimeReady = true;
        } catch (IOException error) {
            localRuntimeReady = false;
            Log.e(TAG, "Local runtime failed to bind loopback port 8787", error);
        }
        sessionCoordinator.start();

        RobotCallback robotCallback = new RobotCallback() {
            @Override
            public void initComplete() {
                super.initComplete();
                Log.i(TAG, "RobotAPI initialized; attaching native robot gateway.");
                robotGateway.attach(robotAPI);
                displayHandler.post(() -> {
                    if (activeRobotService != RobotApiService.this) return;
                    robotApiInitialized = true;
                    configureRendererForeground();
                });
                sendEvent("initComplete", new JSONObject());
            }

            @Override
            public void onDetectFaceResult(List<DetectFaceResult> resultList) {
                super.onDetectFaceResult(resultList);
                sendEvent("onDetectFaceResult", resultList.toString());
            }

            @Override
            public void onDetectPersonResult(List<DetectPersonResult> resultList) {
                super.onDetectPersonResult(resultList);
                sendEvent("onDetectPersonResult", resultList.toString());
            }

            @Override
            public void onFaceResult(List<FaceResult> resultList) {
                super.onFaceResult(resultList);
                sendEvent("onFaceResult", resultList.toString());
            }

            @Override
            public void onFaceResult(int cmd, int serial, List<FaceResult> resultList) {
                super.onFaceResult(cmd, serial, resultList);
                JSONObject obj = new JSONObject();
                try {
                    obj.put("cmd", cmd);
                    obj.put("serial", serial);
                    obj.put("resultList", resultList.toString());
                } catch (JSONException e) {
                    Log.e(TAG, "onFaceResult: JSONException", e);
                }
                sendEvent("onFaceResultWithCmd", obj);
            }

            @Override
            public void onGesturePoint(GesturePointResult result) {
                super.onGesturePoint(result);
                sendEvent("onGesturePoint", result.toString());
            }

            @Override
            public void onRecognizePersonResult(List<RecognizePersonResult> resultList) {
                super.onRecognizePersonResult(resultList);
                sendEvent("onRecognizePersonResult", resultList.toString());
            }

            @Override
            public void onResult(int cmd, int serial, RobotErrorCode err_code, Bundle result) {
                super.onResult(cmd, serial, err_code, result);
                displayHandler.post(() -> robotGateway.onCommandResult(serial, err_code, result));
                JSONObject obj = new JSONObject();
                try {
                    obj.put("cmd", cmd);
                    obj.put("serial", serial);
                    obj.put("err_code", err_code.toString());
                    obj.put("result", result == null ? JSONObject.NULL : result.toString());
                } catch (JSONException e) {
                    Log.e(TAG, "onResult: JSONException", e);
                }
                sendEvent("onResult", obj);
            }

            @Override
            public void onStateChange(int cmd, int serial, RobotErrorCode err_code, RobotCmdState state) {
                super.onStateChange(cmd, serial, err_code, state);
                JSONObject obj = new JSONObject();
                try {
                    obj.put("cmd", cmd);
                    obj.put("serial", serial);
                    obj.put("err_code", err_code.toString());
                    obj.put("state", state.toString());
                } catch (JSONException e) {
                    Log.e(TAG, "onStateChange: JSONException", e);
                }
                // Match SDK dispatch on the main thread so a fast completion cannot
                // arrive before lookAtUser has returned its serial to RobotGateway.
                displayHandler.post(() -> {
                    robotGateway.onCommandStateChanged(serial, state, err_code);
                    sendEvent("onStateChange", obj);
                });
            }

            @Override
            public void onTrackingResult(List<TrackingResult> resultList) {
                super.onTrackingResult(resultList);
                sendEvent("onTrackingResult", resultList.toString());
            }

            @Override
            public void onTrackingResult(int cmd, int serial, List<TrackingResult> resultList) {
                super.onTrackingResult(cmd, serial, resultList);
                JSONObject obj = new JSONObject();
                try {
                    obj.put("cmd", cmd);
                    obj.put("serial", serial);
                    obj.put("resultList", resultList.toString());
                } catch (JSONException e) {
                    Log.e(TAG, "onTrackingResult: JSONException", e);
                }
                sendEvent("onTrackingResultWithCmd", obj);
            }
        };

        RobotCallback.Listen listenCallback = new RobotCallback.Listen() {
            @Override
            public void onFinishRegister() {
                sendEvent("onFinishRegister", new JSONObject());
            }

            @Override
            public void onVoiceDetect(JSONObject jsonObject) {
                deviceHardware.onVoiceEvent(jsonObject);
                // Background SDK voice events must not interrupt Settings, HOME, or another app.
                // The renderer is opened only by an explicit user launch or notification tap.
                sendEvent("onVoiceDetect", jsonObject);
            }

            @Override
            public void onSpeakComplete(String s, String s1) {
                JSONObject obj = new JSONObject();
                try {
                    obj.put("utterance", s);
                    obj.put("error_code", s1);
                } catch (JSONException e) {
                    Log.e(TAG, "onSpeakComplete: JSONException", e);
                }
                sendEvent("onSpeakComplete", obj);
            }

            @Override
            public void onEventUserUtterance(JSONObject jsonObject) {
                deviceHardware.onVoiceEvent(jsonObject);
                sendEvent("onEventUserUtterance", jsonObject);
            }

            @Override
            public void onResult(JSONObject jsonObject) {
                deviceHardware.onVoiceEvent(jsonObject);
                sendEvent("onDsdResult", jsonObject);
            }

            @Override
            public void onRetry(JSONObject jsonObject) {
                sendEvent("onRetry", jsonObject);
            }
        };

        try {
            robotAPI = new RobotAPI(getApplicationContext(), robotCallback);
            robotAPI.robot.registerListenCallback(listenCallback);
            registerHeadTouchSensor();
        } catch (Throwable error) {
            Log.e(TAG, "RobotAPI initialization failed; local runtime remains available", error);
            sendEvent("robotUnavailable", error.getClass().getSimpleName());
        }
    }

    private void registerHeadTouchSensor() {
        if (headTouchListener != null) return;
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        Sensor capacityTouch = sensorManager != null
                ? sensorManager.getDefaultSensor(Utility.SensorType.CAPACITY_TOUCH)
                : null;
        if (capacityTouch == null) {
            Log.w(TAG, "Zenbo capacity touch sensor is unavailable");
            return;
        }
        headTouchListener = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent event) {
                if (!rendererForeground || event.values.length == 0) return;
                if (isHeadInteraction(event.values[0])) {
                    sendEvent("HeadPress", new JSONObject());
                }
            }

            @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }
        };
        if (!sensorManager.registerListener(headTouchListener, capacityTouch, SensorManager.SENSOR_DELAY_NORMAL)) {
            Log.w(TAG, "Could not register Zenbo capacity touch listener");
            headTouchListener = null;
        }
    }

    static boolean isHeadInteraction(float eventType) {
        // Each classified touch arrives with its duration; dispatch only once from this listener.
        return eventType == FIRMWARE_QUICK_HEAD_TOUCH
                || eventType == Utility.CapEventType.CAP_EVENT_SHORT
                || eventType == Utility.CapEventType.CAP_EVENT_MEDIUM;
    }

    /**
     * Listen for system screen and shutdown events and forward them into the
     * WebSocket event pipeline so the web UI can pause/resume listening.
     */
    private void registerScreenEventReceiver() {
        if (screenEventReceiver != null) {
            return;
        }
        screenEventReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                Log.i(TAG, "Screen/power event received: " + action);

                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    if (deviceHardware != null) deviceHardware.setForeground(false);
                    sendEvent("ScreenOff", new JSONObject());
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    sendEvent("ScreenOn", new JSONObject());
                } else if (Intent.ACTION_SHUTDOWN.equals(action)) {
                    if (deviceHardware != null) deviceHardware.setForeground(false);
                    sendEvent("DeviceShutdown", new JSONObject());
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SHUTDOWN);
        registerReceiver(screenEventReceiver, filter);
    }

    private void registerBatteryReceiver() {
        final String usbStateAction = "android.hardware.usb.action.USB_STATE";
        batteryReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (usbStateAction.equals(intent.getAction())) {
                    if (intent.hasExtra("connected")) robotGateway.updateUsbConnection(intent.getBooleanExtra("connected", false));
                    return;
                }
                if (sessionCoordinator == null || !Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) return;
                BatteryState reading = BatteryState.fromReading(
                        intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                        intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
                        intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN),
                        intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true),
                        intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1));
                robotGateway.updatePowerConnection(reading.powerConnected);
                sessionCoordinator.updateBattery(reading);
            }
        };
        // The sticky initial broadcast provides the first reading; later changes use the same receiver.
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(usbStateAction);
        registerReceiver(batteryReceiver, filter);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Robot API Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (activeRobotService == this) activeRobotService = null;
        displayHandler.removeCallbacksAndMessages(null);
        robotApiInitialized = false;
        localRuntimeReady = false;
        activeLocalRuntime = null;
        if (batteryReceiver != null) {
            unregisterReceiver(batteryReceiver);
            batteryReceiver = null;
        }
        if (screenEventReceiver != null) {
            try {
                unregisterReceiver(screenEventReceiver);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Screen receiver already unregistered", e);
            }
            screenEventReceiver = null;
        }
        if (sensorManager != null && headTouchListener != null) {
            sensorManager.unregisterListener(headTouchListener);
            headTouchListener = null;
        }
        if (sessionCoordinator != null) {
            sessionCoordinator.stop();
            sessionCoordinator = null;
        }
        if (localRuntimeServer != null) {
            localRuntimeServer.stop();
            localRuntimeServer = null;
        }
        if (deviceHardware != null) deviceHardware.close();
        if (robotGateway != null) robotGateway.detach();
        if (robotAPI != null) {
            robotAPI.release();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static boolean isLocalRuntimeReady() {
        return localRuntimeReady;
    }

    /** Activity lifecycle controls display ownership; the service remains the sole SDK owner. */
    public static void setRendererForeground(boolean foreground) {
        rendererForeground = foreground;
        RobotApiService service = activeRobotService;
        if (service != null && service.deviceHardware != null) service.deviceHardware.setForeground(foreground);
        if (service != null && foreground) service.displayHandler.post(service::configureRendererForeground);
    }

    public static DeviceHardware getDeviceHardware() {
        RobotApiService service = activeRobotService;
        return service == null ? null : service.deviceHardware;
    }

    private void configureRendererForeground() {
        if (activeRobotService != this || !rendererForeground || !robotApiInitialized || robotAPI == null) return;
        try {
            // ASUS requires commands after initComplete/onResume, not beside the RobotAPI constructor.
            robotAPI.robot.setPressOnHeadAction(false);
            robotAPI.robot.setVoiceTrigger(false);
            // ASUS's custom-UI lifecycle API only hides its expression window, without speech or motion.
            robotAPI.robot.setExpression(RobotFace.HIDEFACE);
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not configure the foreground renderer's robot UI", error);
        }
    }

    public static String issueRendererBootstrapSecret() {
        LocalRuntimeServer runtime = activeLocalRuntime;
        return runtime != null ? runtime.issueBootstrapSecret() : null;
    }
}
