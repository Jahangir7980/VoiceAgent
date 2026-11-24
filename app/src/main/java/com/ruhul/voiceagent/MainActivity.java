package com.ruhul.voiceagent;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Setup screen: permissions, Gemini key, model, and a typed test box. */
public class MainActivity extends Activity {

    private TextView status;
    private EditText keyField, modelField, langField, testField;
    private CheckBox shotsBox;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Voice Agent");

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, dp(40));
        scroll.addView(root);

        status = new TextView(this);
        status.setTextSize(16);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.addView(status);

        // Step 1
        root.addView(heading("ধাপ ১: অনুমতি দিন"));
        root.addView(note("কল করা আর কন্ট্যাক্ট থেকে নাম খোঁজার অনুমতি।"));
        root.addView(button("কল, কন্ট্যাক্ট ও মাইকের অনুমতি দিন", v -> requestPermissions(new String[]{
                Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS,
                Manifest.permission.RECORD_AUDIO}, 1)));

        // Step 2
        root.addView(heading("ধাপ ২: Accessibility চালু করুন"));
        root.addView(note("এটাই অ্যাপকে লেখা, বাটনে চাপ দেওয়া আর স্ক্রল করার ক্ষমতা দেয়। "
                + "খোলার পর 'Installed apps' / 'Downloaded apps' এ গিয়ে 'Voice Agent সহকারী' চালু করুন।"));
        root.addView(button("Accessibility সেটিংস খুলুন", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        root.addView(note("⚠️ চালু করা না গেলে বা ধূসর দেখালে (Android 13+): নিচের বাটনে অ্যাপ ইনফো খুলুন → "
                + "উপরের ডানদিকে ⋮ মেনু → 'Allow restricted settings' চাপুন → তারপর আবার ধাপ ২ করুন।"));
        root.addView(button("অ্যাপ ইনফো খুলুন (restricted settings)", v -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        }));

        // Step 3
        root.addView(heading("ধাপ ৩: Gemini সেটিংস"));
        root.addView(note("aistudio.google.com/apikey থেকে ফ্রি API key নিয়ে এখানে পেস্ট করুন।"));
        keyField = field("Gemini API key", Prefs.apiKey(this));
        keyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        root.addView(keyField);
        root.addView(note("মডেলের নাম (না জানলে নিচের 'মডেল তালিকা' বাটন চাপুন। 'flash' বা 'flash-lite' ভালো ও সস্তা):"));
        modelField = field("মডেল", Prefs.model(this));
        root.addView(modelField);
        root.addView(button("মডেল তালিকা দেখাও", v -> showModels()));
        root.addView(note("কথা শোনার ভাষা (bn-BD = বাংলা বাংলাদেশ, bn-IN = বাংলা ভারত, en-US = ইংরেজি):"));
        langField = field("ভাষা", Prefs.lang(this));
        root.addView(langField);
        shotsBox = new CheckBox(this);
        shotsBox.setText("স্ক্রিনের ছবিও Gemini-কে পাঠাও (ভালো বোঝে, একটু বেশি ডেটা লাগে)");
        shotsBox.setChecked(Prefs.screenshots(this));
        root.addView(shotsBox);
        root.addView(button("সেভ করুন", v -> {
            save();
            Toast.makeText(this, "সেভ হয়েছে ✅", Toast.LENGTH_SHORT).show();
        }));

        // Step 4
        root.addView(heading("কীভাবে ব্যবহার করবেন"));
        root.addView(note("• স্ক্রিনের পাশে নীল 🎤 বাটন থাকবে। যেকোনো অ্যাপে থাকা অবস্থায় চাপ দিন আর বলুন।\n"
                + "• লাল 👂 = শুনছে, কমলা ⏳ = কাজ করছে।\n"
                + "• কাজ থামাতে বাটনে লম্বা চাপ দিন। বাটন টেনে সরানো যায়।\n\n"
                + "উদাহরণ:\n"
                + "  \"ইউটিউব খোলো\"  •  \"ব্যাক যাও\"  •  \"নিচে স্ক্রল করো\"\n"
                + "  \"রহিমকে কল করো\"  •  \"পুলিশকে কল করো\"\n"
                + "  \"এই বক্সে লেখো আমি আসছি, তারপর সেন্ড চাপো\"\n"
                + "  \"Next বাটনে চাপ দাও\"  •  \"দ্বিতীয় অপশনটা সিলেক্ট করো\"\n"
                + "  \"ইউটিউবে বাংলা নাটক সার্চ করো\""));

        // Test box
        root.addView(heading("টেস্ট (মুখে না বলে লিখে চালান)"));
        testField = field("যেমন: সেটিং খোলো", "");
        root.addView(testField);
        root.addView(button("চালাও", v -> {
            save();
            String t = testField.getText().toString().trim();
            AgentService s = AgentService.instance;
            if (s == null) { Toast.makeText(this, "আগে ধাপ ২ (Accessibility) চালু করুন", Toast.LENGTH_LONG).show(); return; }
            if (t.isEmpty()) return;
            moveTaskToBack(true);   // go to the previous screen so the agent can work on it
            main.postDelayed(() -> s.handleCommand(t), 900);
        }));

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = AgentService.instance != null;
        boolean key = !Prefs.apiKey(this).isEmpty();
        String s;
        if (on && key) s = "✅ সব ঠিক আছে! পাশে নীল 🎤 বাটনে চাপ দিয়ে কথা বলুন।";
        else if (!on) s = "⛔ Accessibility এখনো চালু হয়নি (ধাপ ২)।";
        else s = "⚠️ সহজ কমান্ড চলবে, কিন্তু বাকিগুলোর জন্য Gemini API key দিন (ধাপ ৩)।";
        status.setText(s);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(on && key ? 0xFFE3F6E5 : 0xFFFFEBEE);
        status.setBackground(bg);
    }

    private void save() {
        Prefs.save(this, keyField.getText().toString(), modelField.getText().toString(),
                langField.getText().toString().trim().isEmpty() ? Prefs.DEFAULT_LANG : langField.getText().toString().trim(),
                shotsBox.isChecked());
    }

    private void showModels() {
        String key = keyField.getText().toString().trim();
        if (key.isEmpty()) { Toast.makeText(this, "আগে API key দিন", Toast.LENGTH_SHORT).show(); return; }
        Toast.makeText(this, "খুঁজছি…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                List<String> models = Gemini.listModels(key);
                main.post(() -> {
                    if (models.isEmpty()) { Toast.makeText(this, "কোনো মডেল পাওয়া যায়নি", Toast.LENGTH_LONG).show(); return; }
                    String[] arr = models.toArray(new String[0]);
                    new AlertDialog.Builder(this)
                            .setTitle("মডেল বেছে নিন (flash / flash-lite ভালো)")
                            .setItems(arr, (d, which) -> {
                                modelField.setText(arr[which]);
                                save();
                            })
                            .show();
                });
            } catch (Gemini.ApiException e) {
                main.post(() -> Toast.makeText(this, "API key ঠিক নেই (" + e.code + ")", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this, "ইন্টারনেট সমস্যা: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ---------------------------------------------------------------- tiny UI helpers

    private TextView heading(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(18);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(0xFF1E6FE8);
        v.setPadding(0, dp(22), 0, dp(4));
        return v;
    }

    private TextView note(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(14);
        v.setTextColor(0xFF444444);
        v.setPadding(0, dp(4), 0, dp(4));
        return v;
    }

    private Button button(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(0xFF1E6FE8);
        b.setBackground(bg);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(6);
        p.bottomMargin = dp(4);
        b.setLayoutParams(p);
        b.setOnClickListener(l);
        return b;
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        return e;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
