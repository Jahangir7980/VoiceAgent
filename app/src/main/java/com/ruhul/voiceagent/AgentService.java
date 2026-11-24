package com.ruhul.voiceagent;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The heart of the app. Runs as an Accessibility Service so it can read the screen,
 * type into fields, press buttons and swipe – in any app.
 * Shows a floating mic bubble: tap = speak a command, long-press = stop.
 */
public class AgentService extends AccessibilityService {

    public static volatile AgentService instance;

    private static final int MAX_STEPS = 10;
    private static final int COLOR_IDLE = 0xFF1E6FE8;
    private static final int COLOR_LISTEN = 0xFFE53935;
    private static final int COLOR_THINK = 0xFFFB8C00;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final List<AccessibilityNodeInfo> nodes = new ArrayList<>();
    private final List<String> chat = new ArrayList<>();

    private WindowManager wm;
    private TextView bubble;
    private GradientDrawable bubbleBg;
    private WindowManager.LayoutParams lp;
    private TextToSpeech tts;

    private volatile boolean busy = false;
    private volatile boolean cancel = false;
    private int taskId = 0;

    /** Autopilot = keep working on the task until the user long-presses the mic. */
    private volatile boolean autopilot = false;
    private static final int AUTOPILOT_MAX_STEPS = 400;
    private String lastCommand = "";
    private int idleRounds = 0;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS && tts != null) {
                int r = tts.setLanguage(new Locale("bn", "IN"));
                if (r < 0) r = tts.setLanguage(new Locale("bn", "BD"));
                if (r < 0) tts.setLanguage(Locale.getDefault());
            }
        });
        showBubble();
        toast("Voice Agent চালু হয়েছে। নীল মাইকে চাপ দিয়ে কথা বলুন।");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { /* not needed */ }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        instance = null;
        cancel = true;
        try { if (bubble != null) wm.removeView(bubble); } catch (Exception ignored) { }
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
        bg.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- floating bubble

    private void showBubble() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        bubble = new TextView(this);
        bubble.setText("🎤");
        bubble.setTextSize(24);
        bubble.setGravity(Gravity.CENTER);
        bubbleBg = new GradientDrawable();
        bubbleBg.setShape(GradientDrawable.OVAL);
        bubbleBg.setColor(COLOR_IDLE);
        bubbleBg.setStroke(dp(2), 0xFFFFFFFF);
        bubble.setBackground(bubbleBg);
        bubble.setElevation(dp(6));

        int size = dp(58);
        lp = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(8);
        lp.y = dp(320);

        bubble.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY; int startX, startY; long downTime; boolean moved;
            final Runnable longPress = () -> { moved = true; stopTask(); };

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY();
                        startX = lp.x; startY = lp.y;
                        downTime = System.currentTimeMillis(); moved = false;
                        main.postDelayed(longPress, 700);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8)) {
                            if (!moved) main.removeCallbacks(longPress);
                            moved = true;
                            lp.x = startX + (int) dx;
                            lp.y = startY + (int) dy;
                            try { wm.updateViewLayout(bubble, lp); } catch (Exception ignored) { }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        main.removeCallbacks(longPress);
                        if (!moved) onBubbleTap();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        main.removeCallbacks(longPress);
                        return true;
                }
                return false;
            }
        });
        try {
            wm.addView(bubble, lp);
        } catch (Exception e) {
            toast("মাইক বাটন দেখানো গেল না: " + e.getMessage());
        }
    }

    private void setState(int color, String icon) {
        main.post(() -> {
            if (bubble == null) return;
            bubbleBg.setColor(color);
            bubble.setText(icon);
        });
    }

    private void onBubbleTap() {
        if (busy) { stopTask(); return; }
        if (tts != null) tts.stop();
        setState(COLOR_LISTEN, "👂");
        Intent i = new Intent(this, ListenActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        startActivity(i);
    }

    /** Called by ListenActivity with what the user said (null if nothing). */
    public void onListenFinished(String text) {
        if (text == null || text.trim().isEmpty()) {
            setState(COLOR_IDLE, "🎤");
            return;
        }
        handleCommand(text);
    }

    private void stopTask() {
        cancel = true;
        autopilot = false;
        taskId++;
        busy = false;
        if (tts != null) tts.stop();
        setState(COLOR_IDLE, "🎤");
        toast("থামিয়ে দিলাম");
    }

    // ---------------------------------------------------------------- command entry

    /** Entry point for a spoken or typed command. Must be called on the main thread. */
    public void handleCommand(String raw) {
        if (busy) { toast("আগের কাজ চলছে… থামাতে মাইকে লম্বা চাপ দিন"); return; }
        String cmd = cleanCommand(raw);
        toast("আপনি: " + raw);
        if (cmd.isEmpty()) { setState(COLOR_IDLE, "🎤"); return; }
        if (handleModeCommand(cmd)) return;
        autopilot = false;
        if (tryLocal(cmd)) { setState(COLOR_IDLE, "🎤"); return; }
        lastCommand = cmd;
        runAgent(cmd);
    }

    /** Autopilot, "continue", and standing-instruction commands. Returns true if handled. */
    private boolean handleModeCommand(String cmd) {
        String c = cmd.toLowerCase(Locale.ROOT);

        // Standing instructions: "এখন থেকে ... এভাবে ভাববে / উত্তর দেবে"
        if (has(c, "নির্দেশনা মুছ", "নির্দেশ মুছ", "clear instruction", "আগের নিয়ম বাদ")) {
            Prefs.setInstructions(this, "");
            say("আপনার আগের নির্দেশনা মুছে দিলাম।");
            setState(COLOR_IDLE, "🎤");
            return true;
        }
        if (has(c, "এখন থেকে", "সবসময়", "সব সময়", "from now on", "always ")) {
            String old = Prefs.instructions(this);
            String merged = old.isEmpty() ? cmd : old + "\n" + cmd;
            if (merged.length() > 1500) merged = merged.substring(merged.length() - 1500);
            Prefs.setInstructions(this, merged);
            say("ঠিক আছে, এখন থেকে এভাবেই করবো।");
            setState(COLOR_IDLE, "🎤");
            return true;
        }

        boolean autoWords = has(c, "অটো পাইলট", "অটোপাইলট", "autopilot", "auto pilot",
                "করেই যাও", "করতে থাকো", "চালিয়ে যাও", "চালাতে থাকো", "থামবে না", "keep going", "keep doing");
        boolean continueOnly = has(c, "কন্টিনিউ", "continue", "চালিয়ে যাও", "আবার করো", "আগের কাজ");
        if (autoWords || continueOnly) {
            // Strip the mode words; whatever remains is the task (or reuse the last task).
            String task = cmd.replaceAll("(?i)(অটো ?পাইলট( মোড)?( অন| চালু)?( করো)?|auto ?pilot( mode)?( on)?|এভাবে|এইভাবে|ওইভাবে|"
                    + "করেই যাও|করতে থাকো|চালিয়ে যাও|চালাতে থাকো|থামবে না|কন্টিনিউ( করো)?|continue|keep going|keep doing|"
                    + "আবার করো|আগের কাজ(টা)?)", " ").replaceAll("[,।.!]", " ").replaceAll("\\s+", " ").trim();
            if (task.length() < 3) task = lastCommand;
            if (task == null || task.isEmpty()) {
                say("কোন কাজটা চালিয়ে যাবো, সেটা বলুন।");
                setState(COLOR_IDLE, "🎤");
                return true;
            }
            lastCommand = task;
            autopilot = autoWords;
            if (autopilot) say("অটো পাইলট চালু। থামাতে মাইকে লম্বা চাপ দিন।");
            runAgent(task);
            return true;
        }
        return false;
    }

    private static String cleanCommand(String s) {
        String t = toLatinDigits(s.trim());
        // remove wake words at the start
        String[] wakes = {"হেই জিমিনি", "হে জিমিনি", "ওকে জিমিনি", "জিমিনি", "জেমিনি", "জেমিনাই",
                "hey gemini", "ok gemini", "gemini", "hey agent", "এজেন্ট"};
        String low = t.toLowerCase(Locale.ROOT);
        for (String w : wakes) {
            if (low.startsWith(w)) {
                t = t.substring(w.length()).replaceFirst("^[\\s,।.!]+", "");
                break;
            }
        }
        return t.trim();
    }

    private static String toLatinDigits(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (ch >= '০' && ch <= '৯') b.append((char) ('0' + (ch - '০')));
            else b.append(ch);
        }
        return b.toString();
    }

    // ---------------------------------------------------------------- local (free) commands

    private static final Map<String, String> APP_ALIASES = new HashMap<>();
    static {
        String[][] a = {
                {"ইউটিউব", "youtube"}, {"ইউটিউব মিউজিক", "youtube music"},
                {"হোয়াটসঅ্যাপ", "whatsapp"}, {"হোয়াটসঅ্যাপ", "whatsapp"}, {"হোয়াটস অ্যাপ", "whatsapp"},
                {"ফেসবুক", "facebook"}, {"মেসেঞ্জার", "messenger"}, {"ম্যাসেঞ্জার", "messenger"},
                {"সেটিং", "settings"}, {"সেটিংস", "settings"}, {"সেটিংস অ্যাপ", "settings"},
                {"ক্যামেরা", "camera"}, {"জিমেইল", "gmail"}, {"জিমেল", "gmail"}, {"ক্রোম", "chrome"},
                {"টিকটক", "tiktok"}, {"ইনস্টাগ্রাম", "instagram"}, {"ইন্সটাগ্রাম", "instagram"},
                {"গ্যালারি", "gallery"}, {"ফটো", "photos"}, {"ফটোস", "photos"},
                {"ক্যালকুলেটর", "calculator"}, {"প্লে স্টোর", "play store"}, {"প্লেস্টোর", "play store"},
                {"ম্যাপ", "maps"}, {"ম্যাপস", "maps"}, {"মেসেজ", "messages"}, {"ম্যাসেজ", "messages"},
                {"ফোন", "phone"}, {"কল", "phone"}, {"ডায়ালার", "phone"}, {"ঘড়ি", "clock"},
                {"ক্যালেন্ডার", "calendar"}, {"ফাইল", "files"}, {"ফাইলস", "files"},
                {"টেলিগ্রাম", "telegram"}, {"ইমো", "imo"}, {"স্পটিফাই", "spotify"},
                {"জিমিনি", "gemini"}, {"গুগল", "google"}, {"কন্টাক্ট", "contacts"},
                {"কন্ট্যাক্ট", "contacts"}, {"ক্যাপকাট", "capcut"}, {"স্ন্যাপচ্যাট", "snapchat"},
        };
        for (String[] p : a) APP_ALIASES.put(p[0], p[1]);
    }

    private static boolean has(String s, String... words) {
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }

    /** Handles simple commands on the phone itself, without using the Gemini quota. */
    private boolean tryLocal(String cmd) {
        String c = cmd.toLowerCase(Locale.ROOT).replaceAll("[।.!?,]", " ").replaceAll("\\s+", " ").trim();
        // Multi-step commands always go to the AI.
        if (has(c, "তারপর", "তার পর", "এরপর", "এবং", " আর ", "then", " and ", "লিখ", "টাইপ", "type", "write",
                "সার্চ", "search", "খুঁজ", "সিলেক্ট", "select", "পাঠাও", "send")) return false;
        String[] words = c.split(" ");
        if (words.length > 7) return false;

        // Emergency
        boolean callWord = has(c, "কল", "ফোন", "call", "dial", "ডায়াল");
        if (callWord && has(c, "পুলিশ", "police", "ইমার্জেন্সি", "emergency", "112", "100 ", "অ্যাম্বুলেন্স", "ambulance")) {
            dialEmergency();
            return true;
        }

        // Navigation
        if (words.length <= 3) {
            if (has(c, "ব্যাক", "পিছনে", "পেছনে", "back")) { performGlobalAction(GLOBAL_ACTION_BACK); return true; }
            if (has(c, "হোম", "home")) { performGlobalAction(GLOBAL_ACTION_HOME); return true; }
            if (has(c, "রিসেন্ট", "recent")) { performGlobalAction(GLOBAL_ACTION_RECENTS); return true; }
            if (has(c, "নোটিফিকেশন", "notification")) { performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS); return true; }
            if (has(c, "নিচে", "নীচে", "scroll down", "স্ক্রল ডাউন", "স্ক্রল করো")) { swipeScreen("down"); return true; }
            if (has(c, "উপরে", "ওপরে", "scroll up", "স্ক্রল আপ")) { swipeScreen("up"); return true; }
            if (has(c, "স্ক্রিনশট", "screenshot") && Build.VERSION.SDK_INT >= 28) {
                performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT); return true;
            }
        }

        // Call someone: "রহিমকে কল করো", "call rahim", "01712345678 এ ফোন করো"
        if (callWord && has(c, "করো", "কর", "দাও", "দেও", "call", "dial", "ডায়াল")
                && !has(c, "খোলো", "খুলো", "ওপেন", "open")) {
            String digits = c.replaceAll("[^0-9+]", "");
            if (digits.replace("+", "").length() >= 3) { callNumber(digits); return true; }
            String name = c.replaceAll("(কল|ফোন|করো|কর|দাও|দেও|call|dial|ডায়াল|একটা|একটু|প্লিজ|please|এর কাছে|নাম্বারে|নম্বরে|to)", " ")
                    .trim();
            name = name.replaceAll("(কে|রে|এ)$", "").replaceAll("\\s+", " ").trim();
            if (name.isEmpty()) return false;
            String num = findContactNumber(name);
            if (num != null) { say(name + " কে কল করছি"); callNumber(num); return true; }
            return false; // let the AI try a Latin-spelled name
        }

        // Open an app: "ইউটিউব খোলো", "open youtube"
        if (has(c, "খোলো", "খুলো", "খোল", "ওপেন", "open", "চালু করো", "চালাও")) {
            String name = c.replaceAll("(খোলো|খুলো|খোল|ওপেন করো|ওপেন কর|ওপেন|open|চালু করো|চালাও|অ্যাপটা|অ্যাপ|app|টা|প্লিজ|please)", " ")
                    .replaceAll("\\s+", " ").trim();
            if (name.isEmpty()) return false;
            return openApp(name);
        }
        return false;
    }

    // ---------------------------------------------------------------- phone helpers

    private boolean openApp(String spoken) {
        String n = spoken.toLowerCase(Locale.ROOT).trim();
        if (APP_ALIASES.containsKey(n)) n = APP_ALIASES.get(n);
        else {
            for (Map.Entry<String, String> e : APP_ALIASES.entrySet()) {
                if (n.contains(e.getKey())) { n = e.getValue(); break; }
            }
        }
        try {
            Intent special = null;
            if (n.equals("settings") || n.equals("setting")) special = new Intent(Settings.ACTION_SETTINGS);
            else if (n.equals("phone") || n.equals("dialer")) special = new Intent(Intent.ACTION_DIAL);
            else if (n.equals("camera")) special = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
            if (special != null) {
                special.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(special);
                return true;
            }
        } catch (Exception ignored) { }

        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(q, 0);
        String best = null; int bestScore = 0;
        String nNoSpace = n.replace(" ", "");
        for (ResolveInfo ri : apps) {
            String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT).trim();
            String labelNs = label.replace(" ", "");
            String pkg = ri.activityInfo.packageName.toLowerCase(Locale.ROOT);
            int score = 0;
            if (label.equals(n) || labelNs.equals(nNoSpace)) score = 100;
            else if (label.startsWith(n)) score = 80;
            else if (label.contains(n) || labelNs.contains(nNoSpace)) score = 60;
            else if (n.length() >= 3 && n.contains(label) && label.length() >= 3) score = 50;
            else if (nNoSpace.length() >= 3 && pkg.contains(nNoSpace)) score = 40;
            if (score > bestScore) { bestScore = score; best = ri.activityInfo.packageName; }
        }
        if (best == null) return false;
        Intent launch = pm.getLaunchIntentForPackage(best);
        if (launch == null) return false;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
        return true;
    }

    private String findContactNumber(String name) {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        String[] proj = {ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER};
        try (Cursor cur = getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                new String[]{"%" + name + "%"}, null)) {
            if (cur != null && cur.moveToFirst()) return cur.getString(1);
        } catch (Exception ignored) { }
        return null;
    }

    private static boolean isEmergency(String num) {
        String d = num.replaceAll("[^0-9]", "");
        return d.equals("112") || d.equals("100") || d.equals("101") || d.equals("102")
                || d.equals("108") || d.equals("999") || d.equals("911") || d.equals("1091");
    }

    private void callNumber(String number) {
        if (isEmergency(number)) { dialEmergency(number); return; }
        boolean canCall = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED;
        Intent i = new Intent(canCall ? Intent.ACTION_CALL : Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception e) { toast("কল করা গেল না: " + e.getMessage()); }
    }

    private void dialEmergency() { dialEmergency("112"); }

    /** Android does not let apps place emergency calls by themselves, so we open the dialer ready to go. */
    private void dialEmergency(String number) {
        Intent i = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception ignored) { }
        say(number + " নম্বর ডায়াল করে রেখেছি, সবুজ কল বাটনে চাপ দিন।");
    }

    // ---------------------------------------------------------------- AI agent loop

    private static final String SYSTEM_PROMPT =
            "You are a voice assistant that operates the user's Android phone through accessibility.\n"
            + "The user speaks Bengali (sometimes English or mixed). Each turn you get: the user's command, "
            + "the actions already done for it, and the CURRENT screen as a numbered element list "
            + "(and sometimes a screenshot).\n"
            + "Reply with ONLY a JSON object:\n"
            + "{\"say\": \"short Bengali sentence to speak aloud, or empty\", \"actions\": [ ... ], \"done\": true|false}\n"
            + "Action objects:\n"
            + "{\"type\":\"click\",\"id\":N}\n"
            + "{\"type\":\"long_click\",\"id\":N}\n"
            + "{\"type\":\"type\",\"id\":N,\"text\":\"...\",\"enter\":false}   (id may be omitted = focused field; replaces field text)\n"
            + "{\"type\":\"tap\",\"x\":0.0-1.0,\"y\":0.0-1.0}   (fraction of screen width/height; only if no element fits)\n"
            + "{\"type\":\"scroll\",\"direction\":\"up|down|left|right\",\"id\":N}   (id optional; 'down' shows content further below)\n"
            + "{\"type\":\"open_app\",\"name\":\"English app name, e.g. YouTube\"}\n"
            + "{\"type\":\"call\",\"name\":\"contact name in English letters as it is likely saved\",\"number\":\"digits if known\"}\n"
            + "{\"type\":\"back\"} {\"type\":\"home\"} {\"type\":\"recents\"} {\"type\":\"notifications\"} {\"type\":\"wait\"}\n"
            + "Rules:\n"
            + "- Do exactly what the user asked, nothing more. Use only ids from the CURRENT list.\n"
            + "- You may chain several actions that all use elements on the current screen (e.g. type then click Send).\n"
            + "- After an action that changes the screen (open_app, click that navigates), stop and set done=false to see the new screen.\n"
            + "- Set done=true when the task is finished, or when the user is only chatting/asking a question "
            + "(then actions=[] and say=your helpful answer in Bengali, max 3 sentences).\n"
            + "- Type text exactly as the user wants it, in the language/script they asked for.\n"
            + "- If the task needs something irreversible the user did NOT clearly ask for (sending to someone else, "
            + "payment, deleting, buying), do not do it: ask in 'say' and set done=true.\n"
            + "- Never type passwords, OTPs, PINs or card numbers. Ask the user to do that part.\n"
            + "- If you are stuck or the element is not there, try scrolling once, otherwise explain in 'say' with done=true.\n"
            + "- If the user asks a question about what is on the screen (a question, quiz, text, image, video), "
            + "read the screen/screenshot carefully, think it through, and answer in 'say'. If they asked you to "
            + "also select/type the answer, do that with actions.\n"
            + "- 'say' must be short, natural Bengali. While working you may leave it empty.";

    private void runAgent(String command) {
        String key = Prefs.apiKey(this);
        if (key.isEmpty()) {
            say("প্রথমে Voice Agent অ্যাপ খুলে Gemini API key দিন।");
            setState(COLOR_IDLE, "🎤");
            return;
        }
        busy = true;
        cancel = false;
        idleRounds = 0;
        final int myTask = ++taskId;
        setState(COLOR_THINK, "⏳");
        step(myTask, command, new ArrayList<>(), 0);
    }

    private void step(int myTask, String command, List<String> done, int n) {
        if (cancel || myTask != taskId) return;
        if (n >= (autopilot ? AUTOPILOT_MAX_STEPS : MAX_STEPS)) { finishTask(myTask, command, "কাজটা পুরো শেষ করতে পারলাম না। আরেকটু সহজ করে বলবেন?"); return; }

        final String screen = dumpScreen();
        if (Prefs.screenshots(this) && Build.VERSION.SDK_INT >= 30) {
            captureScreen(img -> bg.execute(() -> think(myTask, command, done, n, screen, img)));
        } else {
            bg.execute(() -> think(myTask, command, done, n, screen, null));
        }
    }

    /** Runs on background thread. */
    private void think(int myTask, String command, List<String> done, int n, String screen, String img) {
        if (cancel || myTask != taskId) return;
        StringBuilder u = new StringBuilder();
        String instr = Prefs.instructions(this);
        if (!instr.isEmpty()) {
            u.append("The user's standing instructions (follow them in how you think and answer):\n")
                    .append(instr).append("\n\n");
        }
        if (autopilot) {
            u.append("AUTOPILOT MODE IS ON: keep working on the task continuously and repeat it when one round "
                    + "finishes (e.g. next video, next item, next question). Do NOT set done=true unless the task is "
                    + "truly impossible or needs the user. The user stops you manually.\n\n");
        }
        if (!chat.isEmpty()) {
            u.append("Recent conversation:\n");
            for (String line : chat) u.append(line).append('\n');
            u.append('\n');
        }
        u.append("User command: ").append(command).append("\n");
        u.append("Step ").append(n + 1).append(". Actions already done for this command:\n");
        if (done.isEmpty()) u.append("(none)\n");
        for (String d : done) u.append("- ").append(d).append('\n');
        u.append("\nCURRENT SCREEN:\n").append(screen);

        String answer;
        try {
            answer = Gemini.generate(Prefs.apiKey(this), Prefs.model(this), SYSTEM_PROMPT, u.toString(), img);
        } catch (Gemini.ApiException e) {
            String msg;
            if (e.code == 429) msg = "Gemini-এর সীমা শেষ হয়ে গেছে, বা খুব দ্রুত চাওয়া হচ্ছে। একটু পরে আবার বলুন।";
            else if (e.code == 400 || e.code == 401 || e.code == 403) msg = "Gemini API key বা সেটিংসে সমস্যা আছে। অ্যাপ খুলে key টা দেখে নিন।";
            else if (e.code == 404) msg = "মডেলের নামটা ঠিক নেই। অ্যাপ খুলে 'মডেল তালিকা' থেকে একটা বেছে নিন।";
            else msg = "Gemini থেকে উত্তর পাইনি। আবার চেষ্টা করুন।";
            main.post(() -> finishTask(myTask, command, msg));
            return;
        } catch (Exception e) {
            main.post(() -> finishTask(myTask, command, "ইন্টারনেট সংযোগ দেখে আবার চেষ্টা করুন।"));
            return;
        }

        JSONObject plan;
        try {
            plan = parseJson(answer);
        } catch (Exception e) {
            String fallback = answer == null ? "" : answer.trim();
            main.post(() -> finishTask(myTask, command, fallback.isEmpty() ? "বুঝতে পারলাম না, আবার বলুন।" : fallback));
            return;
        }
        main.post(() -> executePlan(myTask, command, done, n, plan));
    }

    private static JSONObject parseJson(String s) throws Exception {
        String t = s.trim();
        int a = t.indexOf('{'), b = t.lastIndexOf('}');
        if (a < 0 || b <= a) throw new Exception("no json");
        return new JSONObject(t.substring(a, b + 1));
    }

    private void executePlan(int myTask, String command, List<String> done, int n, JSONObject plan) {
        if (cancel || myTask != taskId) return;
        final String sayText = plan.optString("say", "").trim();
        final boolean finished = plan.optBoolean("done", true);
        final JSONArray actions = plan.optJSONArray("actions");
        final int count = actions == null ? 0 : actions.length();

        if (!finished && !sayText.isEmpty()) toast(sayText);

        if (autopilot) {
            idleRounds = count == 0 ? idleRounds + 1 : 0;
            if (finished && !sayText.isEmpty()) say(sayText);
            // Keep the history short so long autopilot runs don't grow the prompt forever.
            while (done.size() > 15) done.remove(0);
        }

        runActions(myTask, actions, 0, done, () -> {
            if (cancel || myTask != taskId) return;
            if (autopilot && idleRounds < 3) {
                main.postDelayed(() -> step(myTask, command, done, n + 1), finished ? 2500 : 1300);
            } else if (finished || count == 0) {
                finishTask(myTask, command, sayText);
            } else {
                main.postDelayed(() -> step(myTask, command, done, n + 1), 1300);
            }
        });
    }

    private void runActions(int myTask, JSONArray actions, int i, List<String> done, Runnable then) {
        if (cancel || myTask != taskId) return;
        if (actions == null || i >= actions.length()) { then.run(); return; }
        JSONObject a = actions.optJSONObject(i);
        String result = a == null ? "skipped invalid action" : exec(a);
        done.add(result);
        long delay = 600;
        if (a != null) {
            String t = a.optString("type");
            if (t.equals("open_app") || t.equals("call")) delay = 1800;
            if (t.equals("wait")) delay = 1500;
        }
        main.postDelayed(() -> runActions(myTask, actions, i + 1, done, then), delay);
    }

    private void finishTask(int myTask, String command, String sayText) {
        if (myTask != taskId) return;
        busy = false;
        setState(COLOR_IDLE, "🎤");
        chat.add("User: " + command);
        if (sayText != null && !sayText.isEmpty()) {
            chat.add("Assistant: " + sayText);
            say(sayText);
        }
        while (chat.size() > 10) chat.remove(0);
    }

    // ---------------------------------------------------------------- executing one action

    private AccessibilityNodeInfo nodeById(JSONObject a) {
        if (!a.has("id")) return null;
        int id = a.optInt("id", -1);
        if (id < 0 || id >= nodes.size()) return null;
        AccessibilityNodeInfo node = nodes.get(id);
        try { node.refresh(); } catch (Exception ignored) { }
        return node;
    }

    private String exec(JSONObject a) {
        String type = a.optString("type", "");
        try {
            switch (type) {
                case "click": {
                    AccessibilityNodeInfo node = nodeById(a);
                    if (node == null) return "click FAILED: id " + a.opt("id") + " not found";
                    clickNode(node, false);
                    return "clicked [" + a.opt("id") + "] " + label(node);
                }
                case "long_click": {
                    AccessibilityNodeInfo node = nodeById(a);
                    if (node == null) return "long_click FAILED: id not found";
                    clickNode(node, true);
                    return "long-clicked [" + a.opt("id") + "] " + label(node);
                }
                case "type": {
                    String text = a.optString("text", "");
                    AccessibilityNodeInfo node = nodeById(a);
                    if (node == null) node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                    if (node == null) return "type FAILED: no text field";
                    if (!node.isFocused()) {
                        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                    Bundle args = new Bundle();
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                    boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                    if (!ok) {
                        // Fallback: find an editable child/parent
                        AccessibilityNodeInfo edit = findEditable(node);
                        if (edit != null) ok = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                    }
                    if (a.optBoolean("enter", false) && Build.VERSION.SDK_INT >= 30) {
                        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
                    }
                    return (ok ? "typed \"" : "type FAILED for \"") + text + "\"";
                }
                case "tap": {
                    int[] wh = screenSize();
                    float x = (float) a.optDouble("x", 0.5) * wh[0];
                    float y = (float) a.optDouble("y", 0.5) * wh[1];
                    tapAt(x, y, false);
                    return "tapped at " + a.optDouble("x") + "," + a.optDouble("y");
                }
                case "scroll": {
                    String dir = a.optString("direction", "down");
                    AccessibilityNodeInfo node = nodeById(a);
                    boolean ok = false;
                    if (node != null) {
                        AccessibilityNodeInfo s = node;
                        while (s != null && !s.isScrollable()) s = s.getParent();
                        if (s != null) {
                            int act = (dir.equals("up") || dir.equals("left"))
                                    ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                                    : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
                            ok = s.performAction(act);
                        }
                    }
                    if (!ok) swipeScreen(dir);
                    return "scrolled " + dir;
                }
                case "open_app": {
                    String name = a.optString("name", "");
                    boolean ok = openApp(name);
                    return ok ? "opened app " + name : "open_app FAILED: '" + name + "' not installed/found";
                }
                case "call": {
                    String number = a.optString("number", "").replaceAll("[^0-9+]", "");
                    String name = a.optString("name", "");
                    if (number.isEmpty() && !name.isEmpty()) {
                        String found = findContactNumber(name);
                        if (found == null) {
                            // try each word separately
                            for (String part : name.split(" ")) {
                                if (part.length() >= 3) { found = findContactNumber(part); if (found != null) break; }
                            }
                        }
                        if (found != null) number = found;
                    }
                    if (number.isEmpty()) return "call FAILED: contact '" + name + "' not found";
                    callNumber(number);
                    return "calling " + name + " " + number;
                }
                case "back": performGlobalAction(GLOBAL_ACTION_BACK); return "pressed back";
                case "home": performGlobalAction(GLOBAL_ACTION_HOME); return "pressed home";
                case "recents": performGlobalAction(GLOBAL_ACTION_RECENTS); return "opened recents";
                case "notifications": performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS); return "opened notifications";
                case "wait": return "waited";
                default: return "unknown action " + type;
            }
        } catch (Exception e) {
            return type + " FAILED: " + e.getMessage();
        }
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo n) {
        if (n == null) return null;
        if (n.isEditable()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findEditable(n.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    private void clickNode(AccessibilityNodeInfo node, boolean longClick) {
        int action = longClick ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK;
        AccessibilityNodeInfo n = node;
        while (n != null && !(longClick ? n.isLongClickable() : n.isClickable())) n = n.getParent();
        if (n != null && n.performAction(action)) return;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        tapAt(r.exactCenterX(), r.exactCenterY(), longClick);
    }

    private void tapAt(float x, float y, boolean longPress) {
        Path p = new Path();
        p.moveTo(Math.max(1, x), Math.max(1, y));
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, longPress ? 800 : 60))
                .build();
        dispatchGesture(g, null, null);
    }

    private void swipeScreen(String dir) {
        int[] wh = screenSize();
        float w = wh[0], h = wh[1];
        float x1 = w / 2, x2 = w / 2, y1 = h / 2, y2 = h / 2;
        switch (dir) {
            case "up": y1 = h * 0.30f; y2 = h * 0.72f; break;   // see content above
            case "left": x1 = w * 0.25f; x2 = w * 0.80f; break;
            case "right": x1 = w * 0.80f; x2 = w * 0.25f; break;
            default: y1 = h * 0.72f; y2 = h * 0.30f; break;     // down: see content below
        }
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 350))
                .build();
        dispatchGesture(g, null, null);
    }

    @SuppressWarnings("deprecation")
    private int[] screenSize() {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return new int[]{dm.widthPixels, dm.heightPixels};
    }

    // ---------------------------------------------------------------- reading the screen

    private String dumpScreen() {
        for (AccessibilityNodeInfo old : nodes) { try { old.recycle(); } catch (Exception ignored) { } }
        nodes.clear();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "(screen could not be read)\n";
        int[] wh = screenSize();
        StringBuilder sb = new StringBuilder();
        sb.append("App package: ").append(root.getPackageName())
                .append("  Screen: ").append(wh[0]).append("x").append(wh[1]).append(" px\n");
        walk(root, sb, 0);
        if (nodes.isEmpty()) sb.append("(no readable elements – use the screenshot and 'tap')\n");
        return sb.toString();
    }

    private void walk(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 45 || nodes.size() >= 160) return;
        if (!n.isVisibleToUser()) return;
        if (getPackageName().contentEquals(String.valueOf(n.getPackageName()))) return;

        CharSequence text = n.getText();
        CharSequence desc = n.getContentDescription();
        CharSequence hint = n.getHintText();
        boolean useful = n.isClickable() || n.isEditable() || n.isCheckable() || n.isLongClickable()
                || n.isScrollable() || (text != null && text.length() > 0) || (desc != null && desc.length() > 0);
        if (useful) {
            int id = nodes.size();
            nodes.add(AccessibilityNodeInfo.obtain(n));
            String cls = String.valueOf(n.getClassName());
            cls = cls.substring(cls.lastIndexOf('.') + 1);
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            sb.append('[').append(id).append("] ").append(cls);
            if (text != null && text.length() > 0) sb.append(" \"").append(trim(text)).append('"');
            if (desc != null && desc.length() > 0) sb.append(" desc=\"").append(trim(desc)).append('"');
            if (hint != null && hint.length() > 0) sb.append(" hint=\"").append(trim(hint)).append('"');
            String vid = n.getViewIdResourceName();
            if (vid != null) sb.append(" id=").append(vid.substring(vid.indexOf('/') + 1));
            sb.append(" {");
            if (n.isClickable()) sb.append("click ");
            if (n.isEditable()) sb.append("edit ");
            if (n.isCheckable()) sb.append(n.isChecked() ? "checked " : "unchecked ");
            if (n.isSelected()) sb.append("selected ");
            if (n.isScrollable()) sb.append("scroll ");
            if (n.isFocused()) sb.append("focused ");
            if (!n.isEnabled()) sb.append("disabled ");
            sb.append("} at ").append(r.centerX()).append(',').append(r.centerY()).append('\n');
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo child = n.getChild(i);
            walk(child, sb, depth + 1);
        }
    }

    private static String trim(CharSequence cs) {
        String s = cs.toString().replace('\n', ' ').replace('"', '\'');
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    private static String label(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) t = n.getContentDescription();
        return t == null ? "" : "\"" + trim(t) + "\"";
    }

    interface ShotCallback { void done(String jpegB64OrNull); }

    private void captureScreen(ShotCallback cb) {
        if (Build.VERSION.SDK_INT < 30) { cb.done(null); return; }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, bg, new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult result) {
                    String out = null;
                    HardwareBuffer hb = result.getHardwareBuffer();
                    try {
                        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                        if (hw != null) {
                            Bitmap soft = hw.copy(Bitmap.Config.ARGB_8888, false);
                            int w = 540;
                            int h = Math.round(soft.getHeight() * (w / (float) soft.getWidth()));
                            Bitmap small = Bitmap.createScaledBitmap(soft, w, h, true);
                            ByteArrayOutputStream bos = new ByteArrayOutputStream();
                            small.compress(Bitmap.CompressFormat.JPEG, 60, bos);
                            out = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
                            small.recycle(); soft.recycle(); hw.recycle();
                        }
                    } catch (Exception ignored) {
                    } finally {
                        hb.close();
                    }
                    cb.done(out);
                }

                @Override
                public void onFailure(int errorCode) { cb.done(null); }
            });
        } catch (Exception e) {
            cb.done(null);
        }
    }

    // ---------------------------------------------------------------- speaking & helpers

    private void say(String text) {
        toast(text);
        if (tts != null) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime());
    }

    private void toast(String msg) {
        main.post(() -> Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show());
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
