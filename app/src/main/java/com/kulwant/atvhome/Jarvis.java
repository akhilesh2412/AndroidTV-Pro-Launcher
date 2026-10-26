package com.kulwant.atvhome;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * Jarvis for the launcher, v3.12.1.
 *
 * Everything in this file is new. No existing launcher look or logic is changed
 * here; HomeActivity only gains one mic pill and delegates to this class.
 *
 * Flow: mic pill (tap) -> full-screen overlay -> auto-listen (mic) ->
 *   local command (app open / volume / time) needs no AI and no key, or
 *   the chosen AI provider for a general question -> big Q&amp;A text on
 *   screen + the same answer spoken with TTS.
 *
 * AI keys are NOT in the code. Each provider's key lives in the launcher's
 * own SharedPreferences ("home" prefs) and is read fresh on every question,
 * so changing provider or key needs no APK update: hold OK on the mic pill
 * to choose a provider and save its key, and the next question already
 * uses it. Default provider is Groq (free, no billing/card): Gemini kept
 * asking this user for billing, Groq's free key (gsk_...) does not.
 */
final class Jarvis {

    static final int REQ_MIC = 1001;

    /** HomeActivity supplies app launching; Jarvis never touches the app lists. */
    interface Opener {
        /** Returns the matched app label, or null if no app matches. */
        String openApp(String spokenName);
        void openSettings();
        /** direction: +1 louder, -1 quieter, 0 mute/unmute. */
        void adjustVolume(int direction);
    }

    private static final int MODE_IDLE = 0;
    private static final int MODE_LISTENING = 1;
    private static final int MODE_THINKING = 2;
    private static final int MODE_SPEAKING = 3;

    // Providers: 0 = Groq (default, free, no billing), 1 = Gemini, 2 = OpenRouter free.
    private static final String PREF_PROVIDER = "jarvis_provider";
    private static final String[] PROVIDER_NAMES = {"Groq", "Gemini", "OpenRouter"};
    private static final String[] PROVIDER_KEYS = {"jarvis_groq_key", "jarvis_gemini_key", "jarvis_openrouter_key"};
    private static final String[] GEMINI_MODELS = {"gemini-2.0-flash", "gemini-2.5-flash"};
    // Groq free models (Oct 2026 free list first): gpt-oss-120b is the current
    // free flagship, gpt-oss-20b the small free one, llama-3.3 kept as a legacy
    // fallback in case it returns to the free list. 404 on one model tries the next.
    private static final String[] GROQ_MODELS = {"openai/gpt-oss-120b", "openai/gpt-oss-20b", "llama-3.3-70b-versatile"};
    // OpenRouter: the "openrouter/free" router picks any currently-free model,
    // so no single :free slug can go stale and break Jarvis.
    private static final String[] OPENROUTER_MODELS = {"openrouter/free"};

    private final Activity act;
    private final Opener opener;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    private FrameLayout panel;
    private OrbView orb;
    private TextView statusText;
    private TextView questionText;
    private TextView answerText;
    private TextToSpeech tts;
    private boolean ttsReady;
    private String pendingSpeak;
    private SpeechRecognizer recognizer;
    private boolean open;
    private boolean listening;

    Jarvis(Activity act, Opener opener) {
        this.act = act;
        this.opener = opener;
        this.prefs = act.getSharedPreferences("home", Activity.MODE_PRIVATE);
        buildPanel();
    }

    View getPanel() {
        return panel;
    }

    boolean isOpen() {
        return open;
    }

    int getProvider() {
        int p = prefs.getInt(PREF_PROVIDER, 0);
        return p >= 0 && p < PROVIDER_NAMES.length ? p : 0;
    }

    String getKey() {
        return getKeyFor(getProvider());
    }

    String getKeyFor(int provider) {
        return prefs.getString(PROVIDER_KEYS[provider], "");
    }

    String providerName(int provider) {
        return PROVIDER_NAMES[provider];
    }

    // ---------------------------------------------------------------- overlay

    private void buildPanel() {
        panel = new FrameLayout(act);
        panel.setBackgroundColor(0xFF070B12);
        panel.setVisibility(View.GONE);

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int side = Ui.dp(act, 72);
        col.setPadding(side, Ui.dp(act, 48), side, Ui.dp(act, 36));
        panel.addView(col, new FrameLayout.LayoutParams(-1, -1));

        TextView title = Ui.text(act, 600, 14, 0x99FFFFFF);
        title.setSingleLine(true);
        title.setLetterSpacing(0.32f);
        title.setGravity(Gravity.CENTER);
        title.setText("J A R V I S");
        col.addView(title);

        orb = new OrbView(act);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(Ui.dp(act, 230), Ui.dp(act, 230));
        olp.topMargin = Ui.dp(act, 18);
        col.addView(orb, olp);

        statusText = Ui.text(act, 500, 18, 0xCCFFFFFF);
        statusText.setSingleLine(true);
        statusText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = Ui.dp(act, 14);
        col.addView(statusText, slp);

        ScrollView scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams sclp = new LinearLayout.LayoutParams(-1, 0, 1f);
        sclp.topMargin = Ui.dp(act, 26);
        col.addView(scroll, sclp);

        LinearLayout qa = new LinearLayout(act);
        qa.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(qa, new ViewGroup.LayoutParams(-1, -2));

        TextView qLabel = Ui.text(act, 600, 13, 0x99FFFFFF);
        qLabel.setSingleLine(true);
        qLabel.setLetterSpacing(0.22f);
        qLabel.setText("AAPNE POOCHHA");
        qa.addView(qLabel);

        questionText = Ui.text(act, 700, 26, 0xFFFFFFFF);
        questionText.setSingleLine(false);
        questionText.setMaxLines(6);
        questionText.setLineSpacing(0, 1.25f);
        questionText.setText("Jarvis taiyaar hai. Boliye...");
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(-1, -2);
        qlp.topMargin = Ui.dp(act, 8);
        qa.addView(questionText, qlp);

        TextView aLabel = Ui.text(act, 600, 13, 0x99FFFFFF);
        aLabel.setSingleLine(true);
        aLabel.setLetterSpacing(0.22f);
        aLabel.setText("JARVIS KA JAWAB");
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2);
        alp.topMargin = Ui.dp(act, 26);
        qa.addView(aLabel, alp);

        answerText = Ui.text(act, 400, 24, 0xFFFFFFFF);
        answerText.setSingleLine(false);
        answerText.setLineSpacing(0, 1.3f);
        answerText.setText("OK dabayein to dobara sununga, Back dabayein to band ho jaunga.");
        LinearLayout.LayoutParams al2 = new LinearLayout.LayoutParams(-1, -2);
        al2.topMargin = Ui.dp(act, 8);
        qa.addView(answerText, al2);

        TextView hint = Ui.text(act, 400, 15, 0x88FFFFFF);
        hint.setSingleLine(true);
        hint.setGravity(Gravity.CENTER);
        hint.setText("OK = dobara boliye   •   Mic pill hold karein = AI provider / key badlein");
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
        hlp.topMargin = Ui.dp(act, 20);
        col.addView(hint, hlp);
    }

    /** Tap on the mic pill: full-screen overlay, animated, auto-listening. */
    void open() {
        if (open) {
            listenAgain();
            return;
        }
        open = true;
        questionText.setText("Jarvis taiyaar hai. Boliye...");
        answerText.setText("OK dabayein to dobara sununga, Back dabayein to band ho jaunga.");
        setMode(MODE_IDLE);
        statusText.setText("Shuru ho raha hai...");
        panel.setVisibility(View.VISIBLE);
        panel.setAlpha(0f);
        panel.setScaleX(0.97f);
        panel.setScaleY(0.97f);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260)
                .setInterpolator(new OvershootInterpolator(1.1f)).start();
        orb.startAnim();
        ensureTts();
        startListening();
    }

    /** OK while the overlay is open: ask another question. */
    void listenAgain() {
        if (!open) return;
        stopRecognizer();
        startListening();
    }

    void close() {
        if (!open) return;
        open = false;
        stopRecognizer();
        if (tts != null) tts.stop();
        orb.stopAnim();
        panel.animate().alpha(0f).setDuration(180)
                .withEndAction(() -> panel.setVisibility(View.GONE)).start();
    }

    /** Overlay closes because an app is opening: let the spoken line finish. */
    private void closeQuietly() {
        if (!open) return;
        open = false;
        stopRecognizer();
        orb.stopAnim();
        panel.setVisibility(View.GONE);
        panel.setAlpha(1f);
    }

    /** HomeActivity.onPause: stop the mic, keep the overlay so OK can retry. */
    void pauseListening() {
        stopRecognizer();
    }

    void destroy() {
        stopRecognizer();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        orb.stopAnim();
    }

    void onMicPermissionResult(boolean granted) {
        if (!open) return;
        if (granted) startListening();
        else {
            setMode(MODE_IDLE);
            statusText.setText("Mic permission nahi mili. Settings me mic ON karein.");
            answerText.setText("Mic permission ke bina Jarvis sun nahi sakta. TV Settings > Apps > Home > Permissions me Microphone ON karein, phir OK dabayein.");
        }
    }

    // ---------------------------------------------------------------- mic + TTS

    private void startListening() {
        if (!open) return;
        if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            setMode(MODE_IDLE);
            statusText.setText("Mic permission chahiye...");
            act.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(act)) {
            setMode(MODE_IDLE);
            statusText.setText("Is TV par voice recognition nahi mila.");
            answerText.setText("Is TV par Google voice recognition available nahi hai. Remote ka mic button bhi isi par chalta hai, isliye pehle mic test karein.");
            return;
        }
        stopRecognizer();
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(act);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle p) {
                    setMode(MODE_LISTENING);
                    statusText.setText("Sun raha hoon... boliye");
                }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float v) { }
                @Override public void onBufferReceived(byte[] b) { }
                @Override public void onEndOfSpeech() {
                    setMode(MODE_THINKING);
                    statusText.setText("Samajh raha hoon...");
                }
                @Override public void onError(int e) {
                    listening = false;
                    if (!open) return;
                    setMode(MODE_IDLE);
                    statusText.setText("Samajh nahi aaya. OK dabayein, dobara boliye.");
                }
                @Override public void onResults(Bundle r) {
                    listening = false;
                    ArrayList<String> list = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (list == null || list.isEmpty() || !open) return;
                    handleHeard(list.get(0));
                }
                @Override public void onPartialResults(Bundle r) { }
                @Override public void onEvent(int t, Bundle b) { }
            });
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            // Hindi (India) recognises Hinglish best on this TV; it also accepts English words.
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN");
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            listening = true;
            recognizer.startListening(i);
        } catch (Exception e) {
            setMode(MODE_IDLE);
            statusText.setText("Mic shuru nahi hua. Dobara OK dabayein.");
        }
    }

    private void stopRecognizer() {
        listening = false;
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) { }
            try { recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
    }

    private void ensureTts() {
        if (tts != null) return;
        try {
            tts = new TextToSpeech(act, status -> {
                if (status != TextToSpeech.SUCCESS) return;
                ttsReady = true;
                Locale hi = new Locale("hi", "IN");
                int r = tts.setLanguage(hi);
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.US);
                }
                tts.setSpeechRate(1.0f);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) {
                        main.post(() -> { if (open) setMode(MODE_SPEAKING); });
                    }
                    @Override public void onDone(String id) {
                        main.post(() -> {
                            if (open) {
                                setMode(MODE_IDLE);
                                statusText.setText("OK dabayein dobara bolne ke liye, Back se band karein.");
                            }
                        });
                    }
                    @Override public void onError(String id) { }
                });
                if (pendingSpeak != null) {
                    String p = pendingSpeak;
                    pendingSpeak = null;
                    speakNow(p);
                }
            });
        } catch (Exception ignored) { }
    }

    private void speak(String text) {
        ensureTts();
        if (tts == null) return;
        if (!ttsReady) {
            pendingSpeak = text;
            return;
        }
        speakNow(text);
    }

    private void speakNow(String text) {
        if (tts == null) return;
        try {
            setMode(MODE_SPEAKING);
            statusText.setText("Bol raha hoon...");
            Bundle b = new Bundle();
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, b, "jarvis-1");
        } catch (Exception ignored) { }
    }

    private void setMode(int mode) {
        orb.setMode(mode);
        if (mode == MODE_LISTENING) statusText.setText("Sun raha hoon... boliye");
        else if (mode == MODE_THINKING) statusText.setText("Soch raha hoon...");
        else if (mode == MODE_SPEAKING) statusText.setText("Bol raha hoon...");
    }

    // ---------------------------------------------------------------- what was said

    private void handleHeard(String heard) {
        questionText.setText(heard);
        String norm = heard.toLowerCase(Locale.ROOT).trim()
                .replaceAll("[.,?!\"']", " ").replaceAll("\\s+", " ").trim();

        // Volume, no AI and no key needed.
        if (norm.contains("volume") || norm.contains("awaaz") || norm.contains("awaz")) {
            if (norm.contains("badhao") || norm.contains("upar") || norm.contains("tez") || norm.contains("badh")) {
                opener.adjustVolume(1);
                answer("Volume badha diya.", "Volume badha diya.");
                return;
            }
            if (norm.contains("kam") || norm.contains("ghata") || norm.contains("neeche") || norm.contains("dheere")) {
                opener.adjustVolume(-1);
                answer("Volume kam kar diya.", "Volume kam kar diya.");
                return;
            }
            if (norm.contains("band") || norm.contains("mute")) {
                opener.adjustVolume(0);
                answer("Awaaz band kar di.", "Awaaz band kar di.");
                return;
            }
        }

        // Time / date, local only.
        if (norm.contains("kitne baje") || norm.contains("time") || norm.contains("samay") || norm.contains("baj rahe")) {
            String t = new SimpleDateFormat("hh:mm a", Locale.US).format(new Date());
            answer("Abhi " + t + " baj rahe hain.", "Abhi " + t + " baj rahe hain.");
            return;
        }
        if (norm.contains("tarikh") || norm.contains("date") || norm.contains("aaj kaun sa din")) {
            String d = new SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.US).format(new Date());
            answer("Aaj " + d + " hai.", "Aaj " + d + " hai.");
            return;
        }

        // "X kholo / open X": app open, no AI and no key needed.
        boolean wantsOpen = norm.contains("kholo") || norm.contains("khol do") || norm.contains("open")
                || norm.contains("chalao") || norm.contains("chalu karo") || norm.contains("start karo");
        if (wantsOpen || norm.contains("settings") || norm.contains("setting")) {
            // Word-by-word removal only: substring removal would break names
            // like WhatsApp ("app") or Kodi ("ko").
            StringBuilder tb = new StringBuilder();
            for (String w : norm.split(" ")) {
                if (w.equals("kholo") || w.equals("khol") || w.equals("do") || w.equals("open")
                        || w.equals("chalao") || w.equals("chalu") || w.equals("karo")
                        || w.equals("start") || w.equals("kripya") || w.equals("please")
                        || w.equals("jarvis") || w.equals("app") || w.equals("ko")
                        || w.equals("the") || w.equals("ek")) continue;
                if (tb.length() > 0) tb.append(' ');
                tb.append(w);
            }
            String target = tb.toString().trim();
            if (target.isEmpty() || target.equals("settings") || target.equals("setting")) {
                opener.openSettings();
                answer("Settings khol raha hoon.", "Settings khol raha hoon.");
                closeQuietly();
                return;
            }
            if (target.contains("wifi") || target.contains("wi-fi")) {
                opener.openSettings();
                answer("Settings khol raha hoon, wahan Wi-Fi milega.", "Settings khol raha hoon.");
                closeQuietly();
                return;
            }
            String label = opener.openApp(target);
            if (label != null) {
                String line = label + " khol raha hoon.";
                answerText.setText(line);
                speak(line);
                // Let the spoken line start before the new app covers the screen.
                main.postDelayed(this::closeQuietly, 1100);
                return;
            }
            answer("Ye app nahi mili: " + target + ". All Apps me naam check karke dobara boliye.",
                    "Ye app nahi mili.");
            return;
        }

        // Everything else is a question -> the chosen provider, key read fresh each time.
        // Default is Groq (free, no billing/card). Gemini is kept as an option,
        // but it was asking this user for billing, so it is no longer the default.
        final int provider = getProvider();
        final String key = getKeyFor(provider).trim();
        if (key.isEmpty()) {
            String how;
            if (provider == 0) how = "Groq key nahi dali hai. Mic pill hold karke Groq chunein, phir console.groq.com se free key (gsk_ se shuru) daaliye. Isme card/billing nahi chahiye.";
            else if (provider == 1) how = "Gemini key nahi dali hai. Mic pill hold karke Gemini chunein, phir key daaliye. Agar Gemini billing maange to Groq par switch karein, wo free hai.";
            else how = "OpenRouter key nahi dali hai. Mic pill hold karke OpenRouter chunein, phir openrouter.ai se free key daaliye.";
            answer(how, "Key nahi dali hai. Mic pill hold karke key daaliye.");
            return;
        }
        setMode(MODE_THINKING);
        statusText.setText("Soch raha hoon...");
        answerText.setText("...");
        final String q = heard;
        new Thread(() -> {
            String reply = askAI(provider, key, q);
            main.post(() -> {
                if (!open) return;
                answer(reply, reply);
            });
        }).start();
    }

    private void answer(String shown, String spoken) {
        answerText.setText(shown);
        speak(spoken);
    }

    // ---------------------------------------------------------------- AI providers

    private String systemPrompt() {
        return "You are Jarvis, a helpful TV voice assistant for a user in Gorakhpur, India. "
                + "Answer in simple Roman Hinglish (Hindi written in English letters, mixed with "
                + "English words as needed), like a friendly person talking. Keep the answer short "
                + "and clear for reading on a TV: at most 4-5 short lines unless the question needs "
                + "a list. Do not use markdown headings.";
    }

    private String askAI(int provider, String key, String question) {
        if (provider == 0) return askOpenAICompatible(
                "https://api.groq.com/openai/v1/chat/completions", key, GROQ_MODELS, "Groq", question, false);
        if (provider == 2) return askOpenAICompatible(
                "https://openrouter.ai/api/v1/chat/completions", key, OPENROUTER_MODELS, "OpenRouter", question, true);
        return askGemini(key, question);
    }

    /** Groq + OpenRouter share the OpenAI chat-completions format. */
    private String askOpenAICompatible(String endpoint, String key, String[] models,
                                       String providerName, String question, boolean isOpenRouter) {
        String prompt = systemPrompt();
        for (String model : models) {
            try {
                URL url = new URL(endpoint);
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("Authorization", "Bearer " + key);
                if (isOpenRouter) {
                    c.setRequestProperty("HTTP-Referer", "https://takeitfood.in");
                    c.setRequestProperty("X-Title", "Jarvis TV Launcher");
                }
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setDoOutput(true);
                String body = "{\"model\":\"" + model + "\",\"messages\":["
                        + "{\"role\":\"system\",\"content\":\"" + jsonEscape(prompt) + "\"},"
                        + "{\"role\":\"user\",\"content\":\"" + jsonEscape(question) + "\"}],"
                        + "\"temperature\":0.7,\"max_tokens\":800}";
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
                int code = c.getResponseCode();
                InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
                String resp = readAll(in);
                if (code >= 200 && code < 300) {
                    String text = extractJsonString(resp, "\"content\"");
                    if (text != null && !text.trim().isEmpty()) return text.trim();
                    return providerName + " se khaali jawab mila. Dobara poochhiye.";
                }
                if (code == 404 || code == 400) continue; // model retired/renamed: try the next
                if (code == 401 || code == 403) {
                    return providerName + " key sahi nahi lag rahi. Mic pill hold karke key dobara check karein.";
                }
                if (code == 429) {
                    return providerName + " ki free limit thodi der ke liye khatam ho gayi hai. Kuch der baad dobara poochhiye.";
                }
                return providerName + " se jawab nahi mila. Thodi der baad dobara poochhiye.";
            } catch (Exception e) {
                return "Internet ya " + providerName + " se connect nahi ho paya. Wi-Fi check karke dobara poochhiye.";
            }
        }
        return providerName + " ka model nahi mila. Mic pill hold karke provider badal ke try karein.";
    }

    private String askGemini(String key, String question) {
        String prompt = systemPrompt() + " User question: " + question;
        String body = "{\"contents\":[{\"parts\":[{\"text\":\"" + jsonEscape(prompt) + "\"}]}],"
                + "\"generationConfig\":{\"temperature\":0.7,\"maxOutputTokens\":800}}";
        for (String model : GEMINI_MODELS) {
            try {
                URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/"
                        + model + ":generateContent?key=" + key);
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setDoOutput(true);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
                int code = c.getResponseCode();
                InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
                String resp = readAll(in);
                if (code >= 200 && code < 300) {
                    String text = extractText(resp);
                    if (text != null && !text.trim().isEmpty()) return text.trim();
                    return "Gemini se khaali jawab mila. Dobara poochhiye.";
                }
                if (code == 404) continue; // model retired/renamed: try the next model
                if (code == 400 || code == 403) {
                    // Gemini's 403 is often the billing/project error this user hit:
                    // do not pay, switch to Groq (free, no billing) instead.
                    return "Gemini ne billing ya key ka error diya hai. Paise mat dijiye, mic pill hold karke Groq chunein aur uski free key daaliye.";
                }
                if (code == 429) {
                    return "Free limit thodi der ke liye khatam ho gayi hai. Kuch der baad dobara poochhiye.";
                }
                return "Gemini se jawab nahi mila. Thodi der baad dobara poochhiye.";
            } catch (Exception e) {
                return "Internet ya Gemini se connect nahi ho paya. Wi-Fi check karke dobara poochhiye.";
            }
        }
        return "Gemini model nahi mila. Mic pill hold karke Groq try karein, wo free hai.";
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        return sb.toString();
    }

    /** First "text": "..." value in the Gemini response, JSON-unescaped. */
    private static String extractText(String json) {
        return extractJsonString(json, "\"text\"");
    }

    /** First <field>: "..." string value in a JSON response, JSON-unescaped. */
    private static String extractJsonString(String json, String field) {
        int idx = json.indexOf(field);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + 6);
        if (colon < 0) return null;
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = start + 1; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '"') return sb.toString();
            if (ch == '\\' && i + 1 < json.length()) {
                char e = json.charAt(++i);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (i + 4 < json.length()) {
                            try { sb.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16)); } catch (Exception ignored) { }
                            i += 4;
                        }
                        break;
                    default: sb.append(e); break;
                }
            } else sb.append(ch);
        }
        return sb.toString();
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    // ---------------------------------------------------------------- key screen

    /** Hold OK on the mic pill: choose a provider, then its key. No APK update needed. */
    void openKeySettings() {
        int current = getProvider();
        String[] items = new String[PROVIDER_NAMES.length];
        for (int i = 0; i < PROVIDER_NAMES.length; i++) {
            String tag = "";
            if (i == 0) tag = " - FREE, no billing/card (recommended)";
            else if (i == 1) tag = " - free tier, kabhi billing maang sakta hai";
            else tag = " - free models, sirf key chahiye";
            items[i] = PROVIDER_NAMES[i] + tag + (i == current ? "  [chuna hua]" : "");
        }
        new AlertDialog.Builder(act)
                .setTitle("Jarvis - AI provider chunein")
                .setItems(items, (d, which) -> {
                    prefs.edit().putInt(PREF_PROVIDER, which).apply();
                    String saved = getKeyFor(which).trim();
                    if (!saved.isEmpty()) {
                        Toast.makeText(act, PROVIDER_NAMES[which] + " set ho gaya, key pehle se saved hai",
                                Toast.LENGTH_LONG).show();
                    } else {
                        openKeyDialog(which);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openKeyDialog(int provider) {
        String where;
        if (provider == 0) where = "Groq key console.groq.com se free me milti hai (API Keys > Create), "
                + "gsk_ se shuru hoti hai. Isme card ya billing nahi chahiye.";
        else if (provider == 1) where = "Gemini key Google AI Studio se milti hai. Agar ye billing maange "
                + "to paise mat dijiye, wapas jakar Groq chunein.";
        else where = "OpenRouter key openrouter.ai > Keys se free me milti hai. "
                + "Free models ke liye card nahi chahiye.";
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(act, 18);
        box.setPadding(pad, pad, pad, pad);
        TextView note = new TextView(act);
        note.setText(where + " Key sirf is TV par save hogi. Save karte hi agla sawaal "
                + "nayi key se chalega, app update ki zaroorat nahi hai.");
        note.setTextSize(15);
        note.setTextColor(0xFFFFFFFF);
        box.addView(note);
        EditText input = new EditText(act);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setHint(PROVIDER_NAMES[provider] + " API key yahan daalein");
        input.setText(getKeyFor(provider));
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0x88FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(act, 14);
        box.addView(input, lp);
        new AlertDialog.Builder(act)
                .setTitle("Jarvis - " + PROVIDER_NAMES[provider] + " Key")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String k = input.getText().toString().trim();
                    prefs.edit().putString(PROVIDER_KEYS[provider], k).apply();
                    Toast.makeText(act, k.isEmpty() ? "Key hata di gayi"
                            : PROVIDER_NAMES[provider] + " key save ho gayi", Toast.LENGTH_LONG).show();
                })
                .setNeutralButton("Key hatao", (d, w) -> {
                    prefs.edit().remove(PROVIDER_KEYS[provider]).apply();
                    Toast.makeText(act, PROVIDER_NAMES[provider] + " key hata di gayi", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------------------------------------------------------------- animated orb

    /** ChatGPT-voice-style orb: pulsing rings while listening, waves while speaking. */
    private static final class OrbView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private android.animation.ValueAnimator anim;
        private float phase;
        private int mode = MODE_IDLE;

        OrbView(Activity act) {
            super(act);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(Ui.dp(act, 2));
        }

        void setMode(int m) {
            mode = m;
            invalidate();
        }

        void startAnim() {
            if (anim != null) return;
            anim = android.animation.ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
            anim.setDuration(2200);
            anim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            anim.addUpdateListener(a -> {
                phase = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }

        void stopAnim() {
            if (anim != null) {
                anim.cancel();
                anim = null;
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float base = Math.min(getWidth(), getHeight()) * 0.30f;
            int core;
            if (mode == MODE_LISTENING) core = 0xFF00D4FF;
            else if (mode == MODE_THINKING) core = 0xFFFFB300;
            else if (mode == MODE_SPEAKING) core = 0xFF9B6DFF;
            else core = 0xFF4D7CFF;

            float pulse = (float) Math.sin(phase);
            // Outer glow rings, breathing with the phase.
            for (int i = 3; i >= 1; i--) {
                ring.setColor(core);
                ring.setAlpha(28 + i * 14);
                c.drawCircle(cx, cy, base + i * Ui.dp(getContext(), 14) + pulse * Ui.dp(getContext(), 6), ring);
            }
            paint.setColor(core);
            paint.setAlpha(255);
            c.drawCircle(cx, cy, base + pulse * Ui.dp(getContext(), 4), paint);
            // Inner white core.
            paint.setColor(0xFFFFFFFF);
            paint.setAlpha(235);
            c.drawCircle(cx, cy, base * 0.42f, paint);

            if (mode == MODE_SPEAKING) {
                // Voice wave bars across the orb.
                paint.setColor(0xFF070B12);
                for (int i = -3; i <= 3; i++) {
                    float h = base * (0.55f + 0.45f * Math.abs((float) Math.sin(phase * 1.6 + i * 0.9f)));
                    float w = Ui.dp(getContext(), 7);
                    float x = cx + i * Ui.dp(getContext(), 15);
                    c.drawRoundRect(x - w / 2, cy - h / 2, x + w / 2, cy + h / 2,
                            Ui.dp(getContext(), 4), Ui.dp(getContext(), 4), paint);
                }
            } else if (mode == MODE_THINKING) {
                // Three dots orbiting the core.
                paint.setColor(0xFFFFFFFF);
                for (int i = 0; i < 3; i++) {
                    double a = phase + i * (Math.PI * 2 / 3);
                    float x = cx + (float) Math.cos(a) * base * 0.78f;
                    float y = cy + (float) Math.sin(a) * base * 0.78f;
                    c.drawCircle(x, y, Ui.dp(getContext(), 6), paint);
                }
            } else if (mode == MODE_LISTENING) {
                // Mic dot in the middle while listening.
                paint.setColor(0xFF070B12);
                c.drawCircle(cx, cy, base * 0.18f, paint);
            }
        }
    }
}
