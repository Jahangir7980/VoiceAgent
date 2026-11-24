package com.ruhul.voiceagent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Minimal Gemini REST client (no extra libraries). Call from a background thread. */
public final class Gemini {
    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/";

    public static class ApiException extends Exception {
        public final int code;
        public ApiException(int code, String msg) { super(msg); this.code = code; }
    }

    private Gemini() {}

    /** Set to false once a model rejects the thinkingConfig field. */
    private static volatile boolean useThinkingConfig = true;

    /**
     * Sends a request and returns the model's text answer. imageJpegB64 may be null.
     * Retries once on temporary server errors (500/503/empty answer).
     */
    public static String generate(String apiKey, String model, String system,
                                  String userText, String imageJpegB64) throws Exception {
        try {
            return generateOnce(apiKey, model, system, userText, imageJpegB64);
        } catch (ApiException e) {
            if (e.code == 400 && useThinkingConfig && e.getMessage() != null
                    && e.getMessage().toLowerCase().contains("thinking")) {
                useThinkingConfig = false;   // this model doesn't accept it; try plain request
                return generateOnce(apiKey, model, system, userText, imageJpegB64);
            }
            if (e.code == 0 || e.code == 500 || e.code == 502 || e.code == 503 || e.code == 504) {
                Thread.sleep(1500);
                return generateOnce(apiKey, model, system, userText, imageJpegB64);
            }
            throw e;
        } catch (java.net.SocketTimeoutException e) {
            return generateOnce(apiKey, model, system, userText, imageJpegB64);
        }
    }

    /** Short human-readable reason taken from a Gemini error body. */
    public static String shortReason(Exception e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        try {
            JSONObject err = new JSONObject(m).optJSONObject("error");
            if (err != null) m = err.optString("status", "") + ": " + err.optString("message", "");
        } catch (Exception ignored) { }
        m = m.replace('\n', ' ');
        return m.length() > 160 ? m.substring(0, 160) + "…" : m;
    }

    private static String generateOnce(String apiKey, String model, String system,
                                       String userText, String imageJpegB64) throws Exception {
        JSONObject body = new JSONObject();

        JSONObject sys = new JSONObject();
        sys.put("parts", new JSONArray().put(new JSONObject().put("text", system)));
        body.put("systemInstruction", sys);

        JSONArray parts = new JSONArray();
        parts.put(new JSONObject().put("text", userText));
        if (imageJpegB64 != null) {
            JSONObject inline = new JSONObject();
            inline.put("mimeType", "image/jpeg");
            inline.put("data", imageJpegB64);
            parts.put(new JSONObject().put("inlineData", inline));
        }
        JSONObject content = new JSONObject();
        content.put("role", "user");
        content.put("parts", parts);
        body.put("contents", new JSONArray().put(content));

        JSONObject gen = new JSONObject();
        gen.put("responseMimeType", "application/json");
        gen.put("temperature", 0.2);
        if (useThinkingConfig) {
            // Less "thinking" = much faster answers for simple phone actions.
            gen.put("thinkingConfig", new JSONObject().put("thinkingLevel", "low"));
        }
        body.put("generationConfig", gen);

        String resp = http("POST", BASE + "models/" + model + ":generateContent", apiKey, body.toString());
        JSONObject json = new JSONObject(resp);
        JSONArray cands = json.optJSONArray("candidates");
        if (cands == null || cands.length() == 0) {
            JSONObject fb = json.optJSONObject("promptFeedback");
            String why = fb != null ? fb.optString("blockReason", "no answer") : "no answer";
            throw new ApiException(0, "Empty answer (" + why + ")");
        }
        JSONObject c0 = cands.getJSONObject(0).optJSONObject("content");
        if (c0 == null) throw new ApiException(0, "No content");
        JSONArray outParts = c0.optJSONArray("parts");
        StringBuilder sb = new StringBuilder();
        if (outParts != null) {
            for (int i = 0; i < outParts.length(); i++) {
                JSONObject p = outParts.getJSONObject(i);
                if (p.optBoolean("thought", false)) continue;
                sb.append(p.optString("text", ""));
            }
        }
        return sb.toString();
    }

    /** Lists model names that support generateContent. */
    public static List<String> listModels(String apiKey) throws Exception {
        String resp = http("GET", BASE + "models?pageSize=200", apiKey, null);
        JSONArray arr = new JSONObject(resp).optJSONArray("models");
        List<String> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.getJSONObject(i);
            JSONArray methods = m.optJSONArray("supportedGenerationMethods");
            boolean ok = false;
            if (methods != null) {
                for (int j = 0; j < methods.length(); j++) {
                    if ("generateContent".equals(methods.optString(j))) ok = true;
                }
            }
            String name = m.optString("name", "");
            if (name.startsWith("models/")) name = name.substring(7);
            if (ok && name.contains("gemini")) out.add(name);
        }
        return out;
    }

    private static String http(String method, String url, String apiKey, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(20000);
        c.setReadTimeout(40000);
        c.setRequestProperty("x-goog-api-key", apiKey);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
        String text = read(is);
        c.disconnect();
        if (code >= 400) throw new ApiException(code, text);
        return text;
    }

    private static String read(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toString("UTF-8");
    }
}
