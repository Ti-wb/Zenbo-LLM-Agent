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
import android.os.Bundle;
import android.os.IBinder;
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
    private static volatile boolean localRuntimeReady;
    private static volatile LocalRuntimeServer activeLocalRuntime;

    private RobotAPI robotAPI;
    private GatewaySettings gatewaySettings;
    private DeviceCredentialStore credentialStore;
    private RobotGateway robotGateway;
    private RemoteSessionCoordinator sessionCoordinator;
    private LocalRuntimeServer localRuntimeServer;
    private BroadcastReceiver screenEventReceiver;
    private SensorManager sensorManager;
    private SensorEventListener headTouchListener;

    /**
     * Bring the GeckoView UI (MainActivity) to the foreground.
     * This is called in response to user voice activity so that the
     * agent UI is ready when the user starts talking to the robot.
     */
    private void bringUiToForeground() {
        Intent activityIntent = new Intent(this, MainActivity.class);
        activityIntent.setAction(Intent.ACTION_MAIN);
        activityIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        activityIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        );
        startActivity(activityIntent);
    }

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

        createNotificationChannel();
        registerScreenEventReceiver();

        // Build a high-priority foreground notification with a full-screen intent
        // to bring MainActivity (GeckoView UI) to the foreground when the service starts.
        Intent fullScreenIntent = new Intent(this, MainActivity.class);
        fullScreenIntent.setAction(Intent.ACTION_MAIN);
        fullScreenIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        fullScreenIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

        PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(
                this,
                0,
                fullScreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Zenbo API Service")
                .setContentText("Robot API server is running.")
                .setSmallIcon(R.mipmap.ic_launcher) // Replace with your app's icon
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setContentIntent(fullScreenPendingIntent)
                .build();

        startForeground(NOTIFICATION_ID, notification);

        // The renderer and remote session runtime must be available even when the
        // vendor RobotAPI is missing, incompatible, or slow to initialize.
        gatewaySettings = new GatewaySettings(getApplicationContext());
        credentialStore = new DeviceCredentialStore(getApplicationContext());
        robotGateway = new RobotGateway();
        sessionCoordinator = new RemoteSessionCoordinator(
                gatewaySettings,
                credentialStore,
                robotGateway,
                new SharedPreferencesToolCallJournal(getApplicationContext())
        );
        localRuntimeServer = new LocalRuntimeServer(
                getApplicationContext(),
                gatewaySettings,
                credentialStore,
                sessionCoordinator,
                robotGateway
        );
        sessionCoordinator.setLocalPublisher(localRuntimeServer::publish);
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
                JSONObject obj = new JSONObject();
                try {
                    obj.put("cmd", cmd);
                    obj.put("serial", serial);
                    obj.put("err_code", err_code.toString());
                    obj.put("result", result.toString());
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
                sendEvent("onStateChange", obj);
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
                sendEvent("onVoiceDetect", jsonObject);
                // User just made a sound; bring the UI to the foreground so the agent is ready.
                bringUiToForeground();
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
                sendEvent("onEventUserUtterance", jsonObject);
            }

            @Override
            public void onResult(JSONObject jsonObject) {
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
            robotAPI.robot.setPressOnHeadAction(false);
            robotAPI.robot.setVoiceTrigger(false);
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
                if (event.values.length == 0) return;
                int pressType = Math.round(event.values[0]);
                if (pressType == 1 || pressType == Utility.CapEventType.CAP_EVENT_SHORT) {
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
                    sendEvent("ScreenOff", new JSONObject());
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    sendEvent("ScreenOn", new JSONObject());
                } else if (Intent.ACTION_SHUTDOWN.equals(action)) {
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

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Robot API Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
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
        localRuntimeReady = false;
        activeLocalRuntime = null;
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

    public static String issueRendererBootstrapSecret() {
        LocalRuntimeServer runtime = activeLocalRuntime;
        return runtime != null ? runtime.issueBootstrapSecret() : null;
    }
}
