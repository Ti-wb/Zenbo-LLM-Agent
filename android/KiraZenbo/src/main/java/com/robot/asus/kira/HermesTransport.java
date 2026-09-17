package com.robot.asus.kira;

import org.json.JSONObject;

/** Native-only Hermes and Zenbo-plugin boundary; the renderer never receives credentials. */
public interface HermesTransport {
    interface ResultCallback {
        void onSuccess(JSONObject result);
        void onError(String code, String message);
    }

    interface BinaryCallback {
        void onSuccess(byte[] bytes, String contentType, String digest, String expiresAt);
        void onError(String code, String message);
    }

    interface Listener {
        void onStateChanged(String state, String detail);
        void onRunEvent(String runId, String type, JSONObject payload);
        void onDeviceToolCall(JSONObject call);
    }

    void start();
    void reload();
    void shutdown();
    JSONObject getStatus();
    String getRemoteSessionId();
    void submitText(String clientTurnId, String text, String language, ResultCallback callback);
    void transcribe(JSONObject input, ResultCallback callback);
    void synthesize(String runId, String text, String language, ResultCallback callback);
    void getRun(String runId, ResultCallback callback);
    void stopRun(String runId, ResultCallback callback);
    void downloadAudio(JSONObject metadata, BinaryCallback callback);
    void reportToolResult(String callId, JSONObject update, ResultCallback callback);
    void reportPlayback(JSONObject update, ResultCallback callback);
}
