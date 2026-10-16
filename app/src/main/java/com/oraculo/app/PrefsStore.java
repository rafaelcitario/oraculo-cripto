package com.oraculo.app;

import android.content.SharedPreferences;

/** Liga o {@link Session.Store} ao SharedPreferences do Android. */
final class PrefsStore implements Session.Store {
    private final SharedPreferences prefs;

    PrefsStore(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    @Override public String get(String key) { return prefs.getString(key, null); }
    @Override public void put(String key, String value) { prefs.edit().putString(key, value).apply(); }
    @Override public void remove(String key) { prefs.edit().remove(key).apply(); }
}
