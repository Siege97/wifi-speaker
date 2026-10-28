package com.wifispeaker;

import android.content.Context;
import android.content.SharedPreferences;

/** Preferências salvas do app. */
final class Prefs {
    private Prefs() {}

    static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("wifispeaker", Context.MODE_PRIVATE);
    }

    /** Buffer de reprodução em ms; 0 significa "usar o padrão". */
    static int bufferMs(Context c) {
        SharedPreferences p = sp(c);
        return p.getBoolean("buf_default", true) ? 0 : p.getInt("buf_ms", 300);
    }
}
