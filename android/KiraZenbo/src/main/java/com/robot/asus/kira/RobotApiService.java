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

    private RobotAPI robotAPI;
    private AndroidAsyncEventServer asyncEventServer;
    private AsyncRobotApiServer asyncRobotApiServer;
    private BroadcastReceiver screenEventReceiver;

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
        Log.d(TAG, "Sending event '" + event + "' with data: " + data.toString());
        if (asyncEventServer != null) {
            asyncEventServer.sendEvent(event, data);
        }
    }

    private void sendEvent(String event, String data) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("data", data);
            Log.d(TAG, "Sending event '" + event + "' with data: " + obj.toString());
            if (asyncEventServer != null) {
                asyncEventServer.sendEvent(event, obj);
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

        RobotCallback robotCallback = new RobotCallback() {
            @Override
            public void initComplete() {
                super.initComplete();
                Log.i(TAG, "RobotAPI initialized, starting async servers.");
                asyncEventServer = new AndroidAsyncEventServer();
                asyncEventServer.start(8790);

                    // Start AsyncHttpServer-based API on the legacy HTTP port (8787).
                    asyncRobotApiServer = new AsyncRobotApiServer(getApplicationContext(), robotAPI);
                asyncRobotApiServer.start(8787);

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

        robotAPI = new RobotAPI(getApplicationContext(), robotCallback);
        robotAPI.robot.registerListenCallback(listenCallback);
        robotAPI.robot.setPressOnHeadAction(false);
        robotAPI.robot.setVoiceTrigger(false);
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
        if (screenEventReceiver != null) {
            try {
                unregisterReceiver(screenEventReceiver);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Screen receiver already unregistered", e);
            }
            screenEventReceiver = null;
        }
        if (asyncEventServer != null) {
            asyncEventServer.stop();
            asyncEventServer = null;
        }
        if (asyncRobotApiServer != null) {
            asyncRobotApiServer.stop();
            asyncRobotApiServer = null;
        }
        if (robotAPI != null) {
            robotAPI.release();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
