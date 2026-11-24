package com.ruhul.voiceagent;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.widget.Toast;

import java.util.ArrayList;

/**
 * Invisible screen: opens Google's voice-typing popup, then hands the text
 * to the AgentService and closes, so the previous app is back on screen.
 */
public class ListenActivity extends Activity {
    private static final int REQ = 7;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Prefs.lang(this));
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Prefs.lang(this));
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "বলুন…");
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try {
            startActivityForResult(i, REQ);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "ভয়েস টাইপিং পাওয়া যায়নি। Play Store থেকে Google অ্যাপ ইনস্টল/আপডেট করুন।",
                    Toast.LENGTH_LONG).show();
            AgentService s = AgentService.instance;
            if (s != null) s.onListenFinished(null);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        String heard = null;
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty()) heard = r.get(0);
        }
        final String text = heard;
        finish();
        overridePendingTransition(0, 0);
        // Give the previous app a moment to come back to the front.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            AgentService s = AgentService.instance;
            if (s != null) s.onListenFinished(text);
        }, 700);
    }
}
