package com.ruhul.voiceagent;

import android.content.Context;
import android.database.Cursor;
import android.provider.ContactsContract;

import java.util.Locale;

/**
 * Finds a contact from a spoken name, even when the user says it in Bengali ("ওমর")
 * and the contact is saved in English ("Omar Faruk"), or the other way round.
 * Works fully offline – no Gemini needed.
 */
public final class ContactMatcher {

    public static final class Match {
        public final String name, number;
        Match(String name, String number) { this.name = name; this.number = number; }
    }

    private ContactMatcher() {}

    public static Match find(Context ctx, String spoken) {
        String target = normalize(spoken);
        if (target.isEmpty()) return null;
        String targetSkel = skeleton(target);

        String[] proj = {ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER};
        Match best = null;
        int bestScore = Integer.MAX_VALUE;
        try (Cursor c = ctx.getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null, null)) {
            if (c == null) return null;
            while (c.moveToNext()) {
                String name = c.getString(0);
                String number = c.getString(1);
                if (name == null || number == null) continue;
                int score = score(name, target, targetSkel);
                if (score < bestScore) { bestScore = score; best = new Match(name, number); }
            }
        } catch (Exception e) {
            return null;
        }
        // Lower = better. Anything above the threshold is too different to trust.
        return bestScore <= 30 ? best : null;
    }

    /** Lower score = closer match. */
    private static int score(String contactName, String target, String targetSkel) {
        String full = normalize(contactName);
        if (full.isEmpty()) return Integer.MAX_VALUE;
        if (full.equals(target)) return 0;

        int best = Integer.MAX_VALUE;
        // Whole name and each single word of the saved name.
        String[] words = full.split(" ");
        String[] candidates = new String[words.length + 1];
        candidates[0] = full;
        System.arraycopy(words, 0, candidates, 1, words.length);

        for (int i = 0; i < candidates.length; i++) {
            String cand = candidates[i];
            if (cand.length() < 2) continue;
            int s;
            if (cand.equals(target)) s = 1;
            else if (cand.startsWith(target) && target.length() >= 3) s = 5;
            else {
                String candSkel = skeleton(cand);
                int skelDist = lev(candSkel, targetSkel);
                int maxSkel = Math.max(candSkel.length(), targetSkel.length());
                if (maxSkel == 0) continue;
                // One-letter difference is allowed only for longer names that start with the same sound,
                // so "Karim" never matches "Rahim".
                boolean sameStart = !candSkel.isEmpty() && !targetSkel.isEmpty()
                        && candSkel.charAt(0) == targetSkel.charAt(0);
                boolean skelOk = skelDist == 0 || (skelDist == 1 && maxSkel >= 3 && sameStart);
                if (!skelOk) continue;
                int fullDist = lev(vowelFold(cand), vowelFold(target));
                s = 10 + skelDist * 8 + fullDist * 3;
            }
            if (i == 1) s -= 1; // first word of the saved name is usually what people say
            best = Math.min(best, s);
        }
        return best;
    }

    /** Lowercase Latin form: Bengali is transliterated, punctuation removed. */
    static String normalize(String s) {
        String t = transliterate(s).toLowerCase(Locale.ROOT);
        t = t.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        return t;
    }

    /** Consonant skeleton with similar sounds merged: "omor" and "omar" → "mr". */
    static String skeleton(String latin) {
        String t = latin.replace(" ", "")
                .replace("chh", "c").replace("ch", "c").replace("sh", "s").replace("kh", "k")
                .replace("gh", "g").replace("jh", "j").replace("th", "t").replace("dh", "d")
                .replace("ph", "f").replace("bh", "b").replace("ng", "n").replace("rh", "r");
        StringBuilder b = new StringBuilder();
        for (char ch : t.toCharArray()) {
            char m;
            switch (ch) {
                case 'a': case 'e': case 'i': case 'o': case 'u': case 'y': case 'h': case 'w': continue;
                case 'v': m = 'b'; break;
                case 'z': m = 'j'; break;
                case 'q': case 'c': m = 'k'; break;
                case 'x': m = 'k'; break;
                default: m = ch;
            }
            if (b.length() > 0 && b.charAt(b.length() - 1) == m) continue; // "mm" → "m"
            b.append(m);
        }
        return b.toString();
    }

    /** a/o and i/e/ee are often swapped between Bengali and English spellings. */
    private static String vowelFold(String s) {
        return s.replace(" ", "").replace("ee", "i").replace("oo", "u")
                .replace('o', 'a').replace('e', 'i').replace('y', 'i').replace('w', 'u');
    }

    private static int lev(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[b.length()];
    }

    // ------------------------------------------------------------------ Bengali → Latin

    private static String cons(char ch) {
        switch (ch) {
            case 'ক': return "k"; case 'খ': return "kh"; case 'গ': return "g"; case 'ঘ': return "gh";
            case 'ঙ': return "ng"; case 'চ': return "ch"; case 'ছ': return "chh"; case 'জ': return "j";
            case 'ঝ': return "jh"; case 'ঞ': return "n"; case 'ট': return "t"; case 'ঠ': return "th";
            case 'ড': return "d"; case 'ঢ': return "dh"; case 'ণ': return "n"; case 'ত': return "t";
            case 'থ': return "th"; case 'দ': return "d"; case 'ধ': return "dh"; case 'ন': return "n";
            case 'প': return "p"; case 'ফ': return "f"; case 'ব': return "b"; case 'ভ': return "bh";
            case 'ম': return "m"; case 'য': return "j"; case 'র': return "r"; case 'ল': return "l";
            case 'শ': return "sh"; case 'ষ': return "sh"; case 'স': return "s"; case 'হ': return "h";
            case 'ড়': return "r"; case 'ঢ়': return "rh"; case 'য়': return "y"; case 'ৎ': return "t";
            default: return null;
        }
    }

    private static String vowelSign(char ch) {
        switch (ch) {
            case 'া': return "a"; case 'ি': return "i"; case 'ী': return "i"; case 'ু': return "u";
            case 'ূ': return "u"; case 'ৃ': return "ri"; case 'ে': return "e"; case 'ৈ': return "oi";
            case 'ো': return "o"; case 'ৌ': return "ou";
            default: return null;
        }
    }

    private static String indepVowel(char ch) {
        switch (ch) {
            case 'অ': return "o"; case 'আ': return "a"; case 'ই': return "i"; case 'ঈ': return "i";
            case 'উ': return "u"; case 'ঊ': return "u"; case 'ঋ': return "ri"; case 'এ': return "e";
            case 'ঐ': return "oi"; case 'ও': return "o"; case 'ঔ': return "ou";
            default: return null;
        }
    }

    static String transliterate(String s) {
        StringBuilder out = new StringBuilder();
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char ch = s.charAt(i);
            String c = cons(ch);
            if (c != null) {
                // Decomposed forms: ড + nukta = ড়, ঢ + nukta = ঢ়, য + nukta = য়
                if (i + 1 < n && s.charAt(i + 1) == '়') {
                    if (ch == 'ড') c = "r";
                    else if (ch == 'ঢ') c = "rh";
                    else if (ch == 'য') c = "y";
                    i++;
                }
                out.append(c);
                char next = i + 1 < n ? s.charAt(i + 1) : ' ';
                boolean nextIsSign = vowelSign(next) != null || next == '্' || next == 'ঁ';
                boolean endOfWord = i + 1 >= n || !isBengaliLetter(next);
                // inherent vowel "o", but usually silent at the end of a word
                if (!nextIsSign && !endOfWord) out.append('o');
                continue;
            }
            String v = vowelSign(ch);
            if (v != null) { out.append(v); continue; }
            v = indepVowel(ch);
            if (v != null) { out.append(v); continue; }
            if (ch == '্' || ch == 'ঁ') continue;
            if (ch == 'ং') { out.append("ng"); continue; }
            if (ch == 'ঃ') { out.append('h'); continue; }
            if (ch >= '০' && ch <= '৯') { out.append((char) ('0' + (ch - '০'))); continue; }
            out.append(ch);
        }
        return out.toString();
    }

    private static boolean isBengaliLetter(char ch) {
        return ch >= 'ঀ' && ch <= '৿';
    }
}
