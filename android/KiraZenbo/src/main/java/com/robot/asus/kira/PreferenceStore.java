package com.robot.asus.kira;

import android.content.SharedPreferences;

/** Small synchronous preference boundary so durable settings can be tested without Android. */
interface PreferenceStore {
    interface Editor {
        Editor putString(String key, String value);

        Editor putBoolean(String key, boolean value);

        Editor putLong(String key, long value);

        boolean commit();
    }

    boolean contains(String key);

    String getString(String key, String fallback);

    boolean getBoolean(String key, boolean fallback);

    long getLong(String key, long fallback);

    Editor edit();
}

final class SharedPreferenceStore implements PreferenceStore {
    private final SharedPreferences preferences;

    SharedPreferenceStore(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    @Override public boolean contains(String key) {
        return preferences.contains(key);
    }

    @Override public String getString(String key, String fallback) {
        return preferences.getString(key, fallback);
    }

    @Override public boolean getBoolean(String key, boolean fallback) {
        return preferences.getBoolean(key, fallback);
    }

    @Override public long getLong(String key, long fallback) {
        return preferences.getLong(key, fallback);
    }

    @Override public Editor edit() {
        SharedPreferences.Editor editor = preferences.edit();
        return new Editor() {
            @Override public Editor putString(String key, String value) {
                editor.putString(key, value);
                return this;
            }

            @Override public Editor putBoolean(String key, boolean value) {
                editor.putBoolean(key, value);
                return this;
            }

            @Override public Editor putLong(String key, long value) {
                editor.putLong(key, value);
                return this;
            }

            @Override public boolean commit() {
                return editor.commit();
            }
        };
    }
}
