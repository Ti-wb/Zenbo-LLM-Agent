package com.robot.asus.kira;

import android.content.Context;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.util.Log;

import com.asus.robotframework.API.RobotAPI;
import com.asus.robotframework.API.RobotFace;
import com.asus.robotframework.API.RobotCommand;
import com.koushikdutta.async.http.Multimap;
import com.koushikdutta.async.http.body.UrlEncodedFormBody;
import com.koushikdutta.async.http.server.AsyncHttpServer;
import com.koushikdutta.async.http.server.AsyncHttpServerRequest;
import com.koushikdutta.async.http.server.AsyncHttpServerResponse;
import com.koushikdutta.async.http.server.HttpServerRequestCallback;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * AsyncHttpServer-based implementation of the robot HTTP API.
 * Currently runs alongside NanoHTTPD on a separate port for testing.
 */
public class AsyncRobotApiServer {

    private static final String TAG = "AsyncRobotApiServer";

    private final AsyncHttpServer server = new AsyncHttpServer();
    private final Context context;
    private final RobotAPI robotAPI;
    private final Handler mainHandler;

    private boolean started = false;

    public AsyncRobotApiServer(Context context, RobotAPI robotAPI) {
        this.context = context;
        this.robotAPI = robotAPI;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void start(int port) {
        if (started) {
            Log.w(TAG, "AsyncRobotApiServer already started");
            return;
        }

        // Serve the single-file app index (built into assets/app/index.html) at "/".
        server.get("/", (req, res) -> {
            try (InputStream is = context.getAssets().open("app/index.html")) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] data = new byte[8192];
                int nRead;
                while ((nRead = is.read(data, 0, data.length)) != -1) {
                    buffer.write(data, 0, nRead);
                }
                buffer.flush();
                String html = buffer.toString("UTF-8");
                addCorsHeaders(req, res);
                res.code(200);
                res.getHeaders().set("Content-Type", "text/html; charset=utf-8");
                res.send(html);
            } catch (IOException e) {
                Log.e(TAG, "Failed to serve app/index.html", e);
                addCorsHeaders(req, res);
                res.code(500);
                res.send("Could not load app/index.html");
            }
        });

        // Health check
        server.get("/health", new HttpServerRequestCallback() {
            @Override
            public void onRequest(AsyncHttpServerRequest request, AsyncHttpServerResponse response) {
                addCorsHeaders(request, response);
                response.code(200);
                response.getHeaders().set("Content-Type", "application/json");
                response.send("{\"status\":\"ok\"}");
            }
        });

        // System API - Battery status
        server.get("/api/system/battery", (req, res) -> {
            addCorsHeaders(req, res);

            try {
                IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                Intent batteryStatus = context.registerReceiver(null, ifilter);

                if (batteryStatus == null) {
                    sendBadRequest(res, "Battery status unavailable");
                    return;
                }

                int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                int plugged = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
                int health = batteryStatus.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);
                int temperature = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0); // tenths of a degree C
                int voltage = batteryStatus.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0); // millivolts
                boolean present = batteryStatus.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false);
                String technology = batteryStatus.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);

                float percentage = -1f;
                if (level >= 0 && scale > 0) {
                    percentage = (level * 100f) / scale;
                }

                JSONObject obj = new JSONObject();
                obj.put("level", level);
                obj.put("scale", scale);
                obj.put("percentage", percentage);
                obj.put("status", status);
                obj.put("plugged", plugged);
                obj.put("health", health);
                obj.put("temperature_c", temperature / 10.0);
                obj.put("voltage_mv", voltage);
                obj.put("present", present);
                obj.put("technology", technology != null ? technology : JSONObject.NULL);

                res.code(200);
                res.getHeaders().set("Content-Type", "application/json");
                res.send(obj.toString());
            } catch (JSONException e) {
                Log.e(TAG, "Failed to build battery JSON", e);
                res.code(500);
                res.getHeaders().set("Content-Type", "application/json");
                res.send("{\"error\":\"Failed to read battery status\"}");
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error reading battery status", e);
                res.code(500);
                res.getHeaders().set("Content-Type", "application/json");
                res.send("{\"error\":\"Unexpected error reading battery status\"}");
            }
        });

        // System API - Device info
        server.get("/api/system/device", (req, res) -> {
            addCorsHeaders(req, res);
            try {
                JSONObject obj = new JSONObject();
                obj.put("manufacturer", Build.MANUFACTURER);
                obj.put("brand", Build.BRAND);
                obj.put("model", Build.MODEL);
                obj.put("device", Build.DEVICE);
                obj.put("product", Build.PRODUCT);
                obj.put("hardware", Build.HARDWARE);
                obj.put("android_version", Build.VERSION.RELEASE);
                obj.put("sdk_int", Build.VERSION.SDK_INT);

                res.code(200);
                res.getHeaders().set("Content-Type", "application/json");
                res.send(obj.toString());
            } catch (JSONException e) {
                Log.e(TAG, "Failed to build device JSON", e);
                res.code(500);
                res.getHeaders().set("Content-Type", "application/json");
                res.send("{\"error\":\"Failed to read device info\"}");
            }
        });

        // System API - Connectivity
        server.get("/api/system/connectivity", (req, res) -> {
            addCorsHeaders(req, res);
            try {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm == null) {
                    sendBadRequest(res, "Connectivity service unavailable");
                    return;
                }

                boolean isConnected = false;
                String type = "none";
                boolean isMetered = cm.isActiveNetworkMetered();

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Network activeNetwork = cm.getActiveNetwork();
                    if (activeNetwork != null) {
                        NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
                        if (caps != null) {
                            isConnected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                                type = "wifi";
                            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                                type = "cellular";
                            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                                type = "ethernet";
                            } else {
                                type = "other";
                            }
                        }
                    }
                } else {
                    NetworkInfo info = cm.getActiveNetworkInfo();
                    if (info != null && info.isConnected()) {
                        isConnected = true;
                        switch (info.getType()) {
                            case ConnectivityManager.TYPE_WIFI:
                                type = "wifi";
                                break;
                            case ConnectivityManager.TYPE_MOBILE:
                                type = "cellular";
                                break;
                            case ConnectivityManager.TYPE_ETHERNET:
                                type = "ethernet";
                                break;
                            default:
                                type = "other";
                                break;
                        }
                    }
                }

                JSONObject obj = new JSONObject();
                obj.put("connected", isConnected);
                obj.put("type", type);
                obj.put("metered", isMetered);

                res.code(200);
                res.getHeaders().set("Content-Type", "application/json");
                res.send(obj.toString());
            } catch (JSONException e) {
                Log.e(TAG, "Failed to build connectivity JSON", e);
                res.code(500);
                res.getHeaders().set("Content-Type", "application/json");
                res.send("{\"error\":\"Failed to read connectivity info\"}");
            }
        });

        // System API - Memory
        server.get("/api/system/memory", (req, res) -> {
            addCorsHeaders(req, res);
            try {
                ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                if (am == null) {
                    sendBadRequest(res, "Activity service unavailable");
                    return;
                }

                ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(memInfo);

                JSONObject obj = new JSONObject();
                obj.put("avail_mem", memInfo.availMem);
                obj.put("total_mem", memInfo.totalMem);
                obj.put("low_memory", memInfo.lowMemory);
                obj.put("threshold", memInfo.threshold);

                res.code(200);
                res.getHeaders().set("Content-Type", "application/json");
                res.send(obj.toString());
            } catch (JSONException e) {
                Log.e(TAG, "Failed to build memory JSON", e);
                res.code(500);
                res.getHeaders().set("Content-Type", "application/json");
                res.send("{\"error\":\"Failed to read memory info\"}");
            }
        });

        // System API - Storage
        server.get("/api/system/storage", (req, res) -> {
            addCorsHeaders(req, res);
            try {
                StatFs statFs = new StatFs(Environment.getDataDirectory().getAbsolutePath());

                long blockSize = statFs.getBlockSizeLong();
                long totalBlocks = statFs.getBlockCountLong();
                long availableBlocks = statFs.getAvailableBlocksLong();

                long totalBytes = totalBlocks * blockSize;
                long availableBytes = availableBlocks * blockSize;

                JSONObject obj = new JSONObject();
                obj.put("total_bytes", totalBytes);
                obj.put("available_bytes", availableBytes);

                res.code(200);
                res.getHeaders().set("Content-Type", "application/json");
                res.send(obj.toString());
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Failed to read storage stats", e);
                res.code(500);
                res.getHeaders().set("Content-Type", "application/json");
                res.send("{\"error\":\"Failed to read storage info\"}");
            } catch (JSONException e) {
                Log.e(TAG, "Failed to build storage JSON", e);
                res.code(500);
                res.getHeaders().set("Content-Type", "application/json");
                res.send("{\"error\":\"Failed to serialize storage info\"}");
            }
        });

        // Dialog API
        server.post("/api/dialog/speak", (req, res) -> {
            String text = getParam(req, "text");
            if (text == null || text.isEmpty()) {
                addCorsHeaders(req, res);
                sendBadRequest(res, "Field 'text' is required");
                return;
            }
            // Mirror current implementation (logging only for now).
            Log.d(TAG, "Dialog speak text=" + text);
            addCorsHeaders(req, res);
            sendQueued(res);
        });

        server.post("/api/dialog/start_speak_animation", (req, res) -> {
            mainHandler.post(robotAPI.robot::startFaceSpeakAnimation);
            addCorsHeaders(req, res);
            sendQueued(res);
        });

        server.post("/api/dialog/stop_speak", (req, res) -> {
            mainHandler.post(robotAPI.robot::stopSpeak);
            addCorsHeaders(req, res);
            sendQueued(res);
        });

        server.post("/api/dialog/voice_trigger", (req, res) -> {
            boolean enable = getBooleanParam(req, "enable", false);
            mainHandler.post(() -> robotAPI.robot.setVoiceTrigger(enable));
            addCorsHeaders(req, res);
            sendQueued(res);
        });

        server.post("/api/dialog/head_action", (req, res) -> {
            boolean enable = getBooleanParam(req, "enable", false);
            mainHandler.post(() -> robotAPI.robot.setPressOnHeadAction(enable));
            addCorsHeaders(req, res);
            sendQueued(res);
        });

        // Face API
        server.post("/api/face/expression", (req, res) -> {
            String expressionStr = getParam(req, "expression");
            if (expressionStr == null || expressionStr.isEmpty()) {
                addCorsHeaders(req, res);
                sendBadRequest(res, "Field 'expression' is required");
                return;
            }
            try {
                final RobotFace face = RobotFace.valueOf(expressionStr);
                Log.d(TAG, "Setting expression to " + face);
                mainHandler.post(() -> robotAPI.robot.setExpression(face));
                addCorsHeaders(req, res);
                sendQueued(res);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Invalid expression: " + expressionStr, e);
                addCorsHeaders(req, res);
                sendBadRequest(res, "Invalid 'expression' value");
            }
        });

        server.post("/api/face/expression_and_speak", (req, res) -> {
            String expressionStr = getParam(req, "expression");
            String text = getParam(req, "text");
            if (expressionStr == null || expressionStr.isEmpty() || text == null || text.isEmpty()) {
                addCorsHeaders(req, res);
                sendBadRequest(res, "Fields 'expression' and 'text' are required");
                return;
            }
            try {
                final RobotFace face = RobotFace.valueOf(expressionStr);
                mainHandler.post(() -> robotAPI.robot.setExpression(face, text));
                addCorsHeaders(req, res);
                sendQueued(res);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Invalid expression: " + expressionStr, e);
                addCorsHeaders(req, res);
                sendBadRequest(res, "Invalid 'expression' value");
            }
        });

        // Utility API
        server.post("/api/utility/follow_face", (req, res) -> {
            boolean enablePreview = getBooleanParam(req, "enablePreview", false);
            boolean largePreview = getBooleanParam(req, "largePreview", false);
            mainHandler.post(() -> robotAPI.utility.followFace(enablePreview, largePreview));
            addCorsHeaders(req, res);
            sendQueued(res);
        });

        server.post("/api/utility/follow_object", (req, res) -> {
            mainHandler.post(robotAPI.utility::followObject);
            addCorsHeaders(req, res);
            sendQueued(res);
        });

        server.post("/api/utility/stop_following", (req, res) -> {
            mainHandler.post(robotAPI::cancelCommandAll); //cancelCommand(RobotCommand.FOLLOW_USER));
            addCorsHeaders(req, res);
            sendQueued(res);
        });

        server.post("/api/utility/track_face", (req, res) -> {
            boolean enablePreview = getBooleanParam(req, "enablePreview", false);
            boolean largePreview = getBooleanParam(req, "largePreview", false);
            mainHandler.post(() -> robotAPI.utility.trackFace(enablePreview, largePreview));
            addCorsHeaders(req, res);
            sendQueued(res);
        });

        server.post("/api/utility/look_at_user", (req, res) -> {
            String doaStr = getParam(req, "doa");
            if (doaStr == null || doaStr.isEmpty()) {
                addCorsHeaders(req, res);
                sendBadRequest(res, "Field 'doa' is required");
                return;
            }
            try {
                final float doa = Float.parseFloat(doaStr);
                mainHandler.post(() -> robotAPI.utility.lookAtUser(doa));
                addCorsHeaders(req, res);
                sendQueued(res);
            } catch (NumberFormatException e) {
                Log.e(TAG, "Invalid doa: " + doaStr, e);
                addCorsHeaders(req, res);
                sendBadRequest(res, "Invalid 'doa' value");
            }
        });

        server.post("/api/utility/play_action", (req, res) -> {
            String numberStr = getParam(req, "number");
            if (numberStr == null || numberStr.isEmpty()) {
                addCorsHeaders(req, res);
                sendBadRequest(res, "Field 'number' is required");
                return;
            }
            try {
                final int number = Integer.parseInt(numberStr);
                mainHandler.post(() -> robotAPI.utility.playAction(number));
                addCorsHeaders(req, res);
                sendQueued(res);
            } catch (NumberFormatException e) {
                Log.e(TAG, "Invalid number: " + numberStr, e);
                addCorsHeaders(req, res);
                sendBadRequest(res, "Invalid 'number' value");
            }
        });

        server.post("/api/utility/play_emotional_action", (req, res) -> {
            String faceStr = getParam(req, "face");
            String actionStr = getParam(req, "action");
            if (faceStr == null || faceStr.isEmpty() || actionStr == null || actionStr.isEmpty()) {
                addCorsHeaders(req, res);
                sendBadRequest(res, "Fields 'face' and 'action' are required");
                return;
            }
            try {
                final RobotFace face = RobotFace.valueOf(faceStr);
                final int action = Integer.parseInt(actionStr);
                mainHandler.post(() -> robotAPI.utility.playEmotionalAction(face, action));
                addCorsHeaders(req, res);
                sendQueued(res);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Invalid face or action: " + faceStr + ", " + actionStr, e);
                addCorsHeaders(req, res);
                sendBadRequest(res, "Invalid 'face' or 'action' value");
            }
        });

        server.get("/api/utility/get_blue_light_filter_enable", (req, res) -> {
            // Mirror UtilityApiHandler: return current enable state as a string in "status".
            boolean enabled = robotAPI.utility.getScreenBlueLightFilterEnable();
            addCorsHeaders(req, res);
            sendStatus(res, String.valueOf(enabled));
        });

        server.get("/api/utility/get_blue_light_filter_mode", (req, res) -> {
            String mode = robotAPI.utility.getScreenBlueLightFilterMode();
            addCorsHeaders(req, res);
            sendStatus(res, mode != null ? mode : "");
        });

        server.post("/api/utility/set_blue_light_filter_mode", (req, res) -> {
            String mode = getParam(req, "mode");
            if (mode == null || mode.isEmpty()) {
                addCorsHeaders(req, res);
                sendBadRequest(res, "Field 'mode' is required");
                return;
            }
            mainHandler.post(() -> robotAPI.utility.setScreenBlueLightFilterMode(mode));
            addCorsHeaders(req, res);
            sendQueued(res);
        });

        server.listen(port);
        started = true;
        Log.i(TAG, "AsyncRobotApiServer started on http://127.0.0.1:" + port);
    }

    public void stop() {
        if (!started) return;
        Log.i(TAG, "Stopping AsyncRobotApiServer");
        try {
            server.stop();
        } catch (Exception e) {
            Log.w(TAG, "Error stopping AsyncHttpServer", e);
        }
        started = false;
    }

    // Helpers

    private void sendQueued(AsyncHttpServerResponse res) {
        res.code(200);
        res.getHeaders().set("Content-Type", "application/json");
        res.send("{\"status\":\"queued\"}");
    }

    private void sendStatus(AsyncHttpServerResponse res, String message) {
        res.code(200);
        res.getHeaders().set("Content-Type", "application/json");
        res.send("{\"status\":\"" + message + "\"}");
    }

    private void addCorsHeaders(AsyncHttpServerRequest req, AsyncHttpServerResponse res) {
        String origin = null;
        try {
            origin = req.getHeaders().get("Origin");
        } catch (Exception ignore) {
        }
        if (origin == null || origin.isEmpty()) {
            origin = "*";
        }
        res.getHeaders().set("Access-Control-Allow-Origin", origin);
        res.getHeaders().set("Access-Control-Allow-Credentials", "true");
        res.getHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        res.getHeaders().set("Access-Control-Allow-Headers", "Content-Type, X-Requested-With, Authorization");
    }

    private void sendBadRequest(AsyncHttpServerResponse res, String error) {
        res.code(400);
        res.getHeaders().set("Content-Type", "application/json");
        res.send("{\"error\":\"" + error + "\"}");
    }

    private String getParam(AsyncHttpServerRequest request, String name) {
        // Try form urlencoded body first
        try {
            if (request.getBody() instanceof UrlEncodedFormBody) {
                UrlEncodedFormBody body = (UrlEncodedFormBody) request.getBody();
                Multimap map = body.get();
                if (map != null) {
                    String value = map.getString(name);
                    if (value != null) {
                        return value;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read form body param '" + name + "'", e);
        }

        // Fallback to query string
        try {
            Multimap query = request.getQuery();
            if (query != null) {
                String value = query.getString(name);
                if (value != null) {
                    return value;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read query param '" + name + "'", e);
        }

        return null;
    }

    private boolean getBooleanParam(AsyncHttpServerRequest request, String name, boolean defaultValue) {
        String raw = getParam(request, name);
        if (raw == null) return defaultValue;
        return Boolean.parseBoolean(raw);
    }
}
