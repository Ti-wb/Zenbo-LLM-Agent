package com.robot.asus.kira;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

/** App-private SharedPreferences implementation of the device tool-call journal. */
final class SharedPreferencesToolCallJournal implements ToolCallJournal {
    private static final String PREFS = "agent_gateway_tool_journal";
    private static final String ENTRY_PREFIX = "tool:";

    private final SharedPreferences preferences;

    SharedPreferencesToolCallJournal(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override public synchronized boolean recordDispatched(Entry entry) {
        String key = key(entry.sessionId, entry.callId);
        Entry existing = readExisting(key);
        if (preferences.contains(key) && existing == null) return false;
        if (existing != null) return sameIdentity(existing, entry);
        return preferences.edit().putString(key, encode(entry)).commit();
    }

    @Override public synchronized boolean recordTerminal(
            Entry entry,
            JSONObject terminalResult,
            long terminalAt
    ) {
        String key = key(entry.sessionId, entry.callId);
        Entry existing = readExisting(key);
        if (preferences.contains(key) && existing == null) return false;
        Entry base = existing != null ? existing : entry;
        if (!sameIdentity(base, entry)) return false;
        if (base.isTerminal()) {
            return base.terminalResult.toString().equals(terminalResult.toString());
        }
        Entry terminal = base.withTerminal(terminalResult, terminalAt);
        if (!isValidEntry(terminal)) return false;
        return preferences.edit()
                .putString(key, encode(terminal))
                .commit();
    }

    @Override public synchronized boolean markAcknowledged(String sessionId, String callId) {
        return updateDeliveryState(sessionId, callId, DeliveryState.ACKNOWLEDGED);
    }

    @Override public synchronized boolean markAbandoned(String sessionId, String callId) {
        String key = key(sessionId, callId);
        Entry existing = readExisting(key);
        if (existing == null || !existing.isTerminal()) return false;
        if (existing.deliveryState == DeliveryState.ACKNOWLEDGED
                || existing.deliveryState == DeliveryState.ABANDONED) {
            return true;
        }
        return preferences.edit()
                .putString(key, encode(existing.withDeliveryState(
                        DeliveryState.ABANDONED
                )))
                .commit();
    }

    @Override public synchronized boolean markAbandoned(
            String sessionId,
            String callId,
            long retainedAt
    ) {
        String key = key(sessionId, callId);
        Entry existing = readExisting(key);
        if (existing == null || !existing.isTerminal()) return false;
        if (existing.deliveryState == DeliveryState.ACKNOWLEDGED) return true;
        Entry abandoned = existing.withAbandonedAt(retainedAt);
        if (existing.deliveryState == DeliveryState.ABANDONED
                && existing.terminalAt >= abandoned.terminalAt) {
            return true;
        }
        return preferences.edit().putString(key, encode(abandoned)).commit();
    }

    @Override public synchronized Entry get(String sessionId, String callId) {
        return readExisting(key(sessionId, callId));
    }

    @Override public synchronized List<Entry> loadSession(String sessionId) {
        String prefix = ENTRY_PREFIX + sessionId + ":";
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<String, ?> stored : preferences.getAll().entrySet()) {
            if (!stored.getKey().startsWith(prefix)) continue;
            if (!(stored.getValue() instanceof String)) return null;
            Entry entry = decode((String) stored.getValue());
            if (entry == null
                    || !sessionId.equals(entry.sessionId)
                    || !stored.getKey().equals(key(entry.sessionId, entry.callId))) {
                return null;
            }
            entries.add(entry);
        }
        entries.sort(Comparator
                .comparingLong((Entry entry) -> entry.dispatchedAt)
                .thenComparing(entry -> entry.callId));
        return entries;
    }

    @Override public synchronized List<Entry> loadAll() {
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<String, ?> stored : preferences.getAll().entrySet()) {
            if (!stored.getKey().startsWith(ENTRY_PREFIX)) continue;
            if (!(stored.getValue() instanceof String)) return null;
            Entry entry = decode((String) stored.getValue());
            if (entry == null || !stored.getKey().equals(key(entry.sessionId, entry.callId))) {
                return null;
            }
            entries.add(entry);
        }
        entries.sort(Comparator
                .comparing((Entry entry) -> entry.sessionId)
                .thenComparingLong(entry -> entry.dispatchedAt)
                .thenComparing(entry -> entry.callId));
        return entries;
    }

    @Override public synchronized boolean prune(long now, long minimumRetentionMillis) {
        SharedPreferences.Editor editor = preferences.edit();
        boolean changed = false;
        for (Map.Entry<String, ?> stored : preferences.getAll().entrySet()) {
            if (!stored.getKey().startsWith(ENTRY_PREFIX)) continue;
            if (!(stored.getValue() instanceof String)) return false;
            Entry entry = decode((String) stored.getValue());
            if (entry == null
                    || !stored.getKey().equals(key(entry.sessionId, entry.callId))) {
                return false;
            }
            if (!entry.isTerminal()
                    || entry.terminalAt + minimumRetentionMillis >= now
                    || !(entry.deliveryState == DeliveryState.ACKNOWLEDGED
                    || entry.deliveryState == DeliveryState.ABANDONED)) {
                continue;
            }
            editor.remove(stored.getKey());
            changed = true;
        }
        return !changed || editor.commit();
    }

    private boolean updateDeliveryState(
            String sessionId,
            String callId,
            DeliveryState state
    ) {
        String key = key(sessionId, callId);
        Entry existing = readExisting(key);
        if (existing == null || !existing.isTerminal()) return false;
        if (existing.deliveryState == state) return true;
        return preferences.edit()
                .putString(key, encode(existing.withDeliveryState(state)))
                .commit();
    }

    private Entry readExisting(String key) {
        try {
            String encoded = preferences.getString(key, null);
            return encoded == null ? null : decode(encoded);
        } catch (ClassCastException error) {
            return null;
        }
    }

    private static String key(String sessionId, String callId) {
        return ENTRY_PREFIX + sessionId + ":" + callId;
    }

    private static boolean sameIdentity(Entry left, Entry right) {
        return left.sessionId.equals(right.sessionId)
                && left.callId.equals(right.callId)
                && left.turnId.equals(right.turnId)
                && left.owner.equals(right.owner)
                && left.name.equals(right.name);
    }

    private static String encode(Entry entry) {
        try {
            JSONObject json = new JSONObject()
                    .put("sessionId", entry.sessionId)
                    .put("callId", entry.callId)
                    .put("turnId", entry.turnId)
                    .put("owner", entry.owner)
                    .put("name", entry.name)
                    .put("dispatchedAt", entry.dispatchedAt)
                    .put("terminalAt", entry.terminalAt)
                    .put("deliveryState", entry.deliveryState.name());
            if (entry.terminalResult != null) {
                json.put("terminalResult", entry.terminalResult);
            }
            return json.toString();
        } catch (Exception error) {
            throw new IllegalStateException("Could not encode tool-call journal", error);
        }
    }

    private static Entry decode(String encoded) {
        try {
            JSONObject json = new JSONObject(encoded);
            Object terminalValue = json.opt("terminalResult");
            if (json.has("terminalResult")
                    && !(terminalValue instanceof JSONObject)) {
                return null;
            }
            JSONObject terminal = (JSONObject) terminalValue;
            Entry entry = new Entry(
                    json.getString("sessionId"),
                    json.getString("callId"),
                    json.getString("turnId"),
                    json.getString("owner"),
                    json.getString("name"),
                    json.getLong("dispatchedAt"),
                    terminal,
                    json.optLong("terminalAt", 0L),
                    DeliveryState.valueOf(json.optString("deliveryState", "NONE"))
            );
            return isValidEntry(entry) ? entry : null;
        } catch (Exception error) {
            return null;
        }
    }

    static boolean isValidEntry(Entry entry) {
        ToolManifestSpec.Definition tool = findTool(entry.name);
        if (!isCanonicalUuid(entry.sessionId)
                || !isCanonicalUuid(entry.callId)
                || !isCanonicalUuid(entry.turnId)
                || !("native".equals(entry.owner) || "web".equals(entry.owner))
                || tool == null
                || !entry.owner.equals(tool.owner)
                || entry.dispatchedAt < 0L
                || entry.terminalAt < 0L) {
            return false;
        }
        if (!entry.isTerminal()) {
            return entry.terminalAt == 0L
                    && entry.deliveryState == DeliveryState.NONE;
        }
        return entry.terminalAt > 0L
                && entry.deliveryState != DeliveryState.NONE
                && isValidToolUpdate(
                entry.owner,
                entry.name,
                entry.terminalResult,
                false
        );
    }

    private static ToolManifestSpec.Definition findTool(String name) {
        if (name == null) return null;
        for (ToolManifestSpec.Definition definition
                : ToolManifestSpec.definitions()) {
            if (name.equals(definition.name)) return definition;
        }
        return null;
    }

    static boolean isValidToolUpdate(
            String owner,
            String name,
            JSONObject update,
            boolean allowAccepted
    ) {
        ToolManifestSpec.Definition tool = findTool(name);
        if (tool == null
                || owner == null
                || !owner.equals(tool.owner)
                || update == null) {
            return false;
        }
        if (!hasOnlyKeys(
                update,
                "status",
                "updatedAt",
                "output",
                "error"
        )) {
            return false;
        }
        Object statusValue = update.opt("status");
        Object updatedAtValue = update.opt("updatedAt");
        if (!(statusValue instanceof String)
                || !(updatedAtValue instanceof String)
                || !isValidTimestamp((String) updatedAtValue)) {
            return false;
        }
        String status = (String) statusValue;
        boolean hasOutput = update.has("output");
        boolean hasError = update.has("error");
        if ("accepted".equals(status)) {
            return allowAccepted && !hasOutput && !hasError;
        }
        if ("succeeded".equals(status)) {
            Object outputValue = update.opt("output");
            return hasOutput
                    && !hasError
                    && outputValue instanceof JSONObject
                    && outputValue.toString()
                    .getBytes(StandardCharsets.UTF_8).length <= 16 * 1024
                    && isValidToolOutput(
                    (JSONObject) outputValue,
                    tool
            );
        }
        if (!("failed".equals(status) || "rejected".equals(status))
                || hasOutput
                || !hasError) {
            return false;
        }
        JSONObject error = update.optJSONObject("error");
        return error != null && isValidError(error);
    }

    private static boolean isValidToolOutput(
            JSONObject output,
            ToolManifestSpec.Definition tool
    ) {
        for (String required : tool.requiredResults) {
            if (!output.has(required) || output.isNull(required)) return false;
        }
        java.util.Iterator<String> keys = output.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            ToolManifestSpec.Property property = null;
            for (ToolManifestSpec.Property candidate
                    : tool.resultProperties) {
                if (key.equals(candidate.name)) {
                    property = candidate;
                    break;
                }
            }
            if (property == null
                    || !isValidProperty(output.opt(key), property)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidProperty(
            Object value,
            ToolManifestSpec.Property property
    ) {
        if ("boolean".equals(property.type)) {
            return value instanceof Boolean;
        }
        if ("string".equals(property.type)) {
            return value instanceof String
                    && (property.allowedValues.isEmpty()
                    || property.allowedValues.contains(value));
        }
        if (!("integer".equals(property.type)
                || "number".equals(property.type))
                || !(value instanceof Number)) {
            return false;
        }
        double number = ((Number) value).doubleValue();
        if (Double.isNaN(number) || Double.isInfinite(number)) return false;
        if ("integer".equals(property.type)
                && number != Math.rint(number)) {
            return false;
        }
        return (property.minimum == null
                || number >= property.minimum.doubleValue())
                && (property.maximum == null
                || number <= property.maximum.doubleValue());
    }

    private static boolean isValidError(JSONObject error) {
        if (!hasOnlyKeys(error, "code", "message", "retryable")) {
            return false;
        }
        Object code = error.opt("code");
        Object message = error.opt("message");
        Object retryable = error.opt("retryable");
        return code instanceof String
                && ((String) code).matches("^[A-Z][A-Z0-9_]{1,63}$")
                && message instanceof String
                && !((String) message).isEmpty()
                && ProtocolStrings.length((String) message) <= 512
                && retryable instanceof Boolean;
    }

    private static boolean hasOnlyKeys(
            JSONObject value,
            String... allowed
    ) {
        java.util.Iterator<String> keys = value.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            boolean found = false;
            for (String candidate : allowed) {
                if (candidate.equals(key)) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private static boolean isValidTimestamp(String value) {
        if (value == null
                || !value.matches(
                "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{3})?Z"
        )) {
            return false;
        }
        String pattern = value.length() == 20
                ? "yyyy-MM-dd'T'HH:mm:ss'Z'"
                : "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
        format.setLenient(false);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        ParsePosition position = new ParsePosition(0);
        Date parsed = format.parse(value, position);
        return parsed != null && position.getIndex() == value.length();
    }

    private static boolean isCanonicalUuid(String value) {
        if (value == null) return false;
        try {
            return UUID.fromString(value).toString().equals(
                    value.toLowerCase(Locale.US)
            );
        } catch (IllegalArgumentException error) {
            return false;
        }
    }
}
