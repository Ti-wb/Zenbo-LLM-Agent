package com.robot.asus.kira;

import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** In-memory Android preference substitute; no Android runtime or disk access. */
final class MemoryPreferences implements SharedPreferences {
    private final Map<String, Object> values = new HashMap<>();
    boolean failCommits;
    @Override public synchronized Map<String, ?> getAll() { return new HashMap<>(values); }
    @Override public synchronized String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
    @Override public synchronized Set<String> getStringSet(String key, Set<String> fallback) { return (Set<String>) values.getOrDefault(key, fallback); }
    @Override public synchronized int getInt(String key, int fallback) { return (Integer) values.getOrDefault(key, fallback); }
    @Override public synchronized long getLong(String key, long fallback) { return (Long) values.getOrDefault(key, fallback); }
    @Override public synchronized float getFloat(String key, float fallback) { return (Float) values.getOrDefault(key, fallback); }
    @Override public synchronized boolean getBoolean(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
    @Override public synchronized boolean contains(String key) { return values.containsKey(key); }
    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
    @Override public Editor edit() {
        return new Editor() {
            private final Map<String, Object> added = new HashMap<>();
            private final Set<String> removed = new HashSet<>();
            private boolean clear;
            private Editor put(String key, Object value) { added.put(key, value); return this; }
            @Override public Editor putString(String key, String value) { return put(key, value); }
            @Override public Editor putStringSet(String key, Set<String> value) { return put(key, value); }
            @Override public Editor putInt(String key, int value) { return put(key, value); }
            @Override public Editor putLong(String key, long value) { return put(key, value); }
            @Override public Editor putFloat(String key, float value) { return put(key, value); }
            @Override public Editor putBoolean(String key, boolean value) { return put(key, value); }
            @Override public Editor remove(String key) { removed.add(key); return this; }
            @Override public Editor clear() { clear = true; return this; }
            @Override public boolean commit() {
                synchronized (MemoryPreferences.this) {
                    if (clear) values.clear();
                    for (String key : removed) values.remove(key);
                    values.putAll(added);
                }
                return !failCommits;
            }
            @Override public void apply() { commit(); }
        };
    }
}
