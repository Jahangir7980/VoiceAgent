package com.ruhul.voiceagent;

import android.content.Context;
import android.content.SharedPreferences;

/** Small wrapper around saved settings. */
public final class Prefs {
    public static final String DEFAULT_MODEL = "gemini-3.1-flash-lite";
    public static final String DEFAULT_LANG = "bn-BD";

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("agent", Context.MODE_PRIVATE);
    }

    public static String apiKey(Context c) { return sp(c).getString("api_key", "").trim(); }
    public static String model(Context c) {
        String m = sp(c).getString("model", DEFAULT_MODEL).trim();
        if (m.startsWith("models/")) m = m.substring(7);
        return m.isEmpty() ? DEFAULT_MODEL : m;
    }
    public static String lang(Context c) { return sp(c).getString("lang", DEFAULT_LANG); }
    public static boolean screenshots(Context c) { return sp(c).getBoolean("shots", true); }

    /** The user's own standing instructions ("think like this…"). */
    public static String instructions(Context c) { return sp(c).getString("instr", "").trim(); }
    public static void setInstructions(Context c, String s) { sp(c).edit().putString("instr", s.trim()).apply(); }

    public static void save(Context c, String key, String model, String lang, boolean shots) {
        sp(c).edit()
                .putString("api_key", key.trim())
                .putString("model", model.trim())
                .putString("lang", lang)
                .putBoolean("shots", shots)
                .apply();
    }
}
