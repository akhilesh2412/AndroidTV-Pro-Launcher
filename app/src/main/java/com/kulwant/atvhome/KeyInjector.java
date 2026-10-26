package com.kulwant.atvhome;

import android.content.Context;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import dadb.AdbKeyPair;
import dadb.AdbStream;
import dadb.Dadb;

/**
 * Presses keys on the TV for the phone remote's trackpad and keyboard.
 *
 * Android doesn't let an ordinary app press keys in other apps, but the TV's network ADB (port
 * 5555, already on for this project) runs a shell that may write to the remote control's input
 * device. So the launcher connects to its own ADB once (the TV asks "Allow USB debugging?" the first
 * time) and keeps a shell open. Each press is one shell-builtin write of raw Linux input events to
 * the remote's /dev/input node: no process is started, so it takes a few milliseconds instead of
 * the ~1 s of `input keyevent` on this TV.
 */
final class KeyInjector {

    // phone key name -> Android key name; the remote's own key layout file turns these into the
    // Linux codes to write (they differ between TVs: OK is 28 here, not the usual 353)
    static final Map<String, String> KEYS = new HashMap<>();
    static {
        KEYS.put("up", "DPAD_UP");
        KEYS.put("down", "DPAD_DOWN");
        KEYS.put("left", "DPAD_LEFT");
        KEYS.put("right", "DPAD_RIGHT");
        KEYS.put("ok", "DPAD_CENTER");
        KEYS.put("back", "BACK");
        KEYS.put("home", "HOME");
        KEYS.put("menu", "MENU");
        KEYS.put("playpause", "MEDIA_PLAY_PAUSE");
        KEYS.put("rewind", "MEDIA_REWIND");
        KEYS.put("forward", "MEDIA_FAST_FORWARD");
        KEYS.put("volup", "VOLUME_UP");
        KEYS.put("voldown", "VOLUME_DOWN");
        KEYS.put("mute", "VOLUME_MUTE");
        KEYS.put("backspace", "DEL");
        KEYS.put("enter", "ENTER");
    }

    // US keyboard: each character's Android key name; SHIFTED[i] is PLAIN[i] with Shift held
    private static final String PLAIN = "1234567890-=qwertyuiop[]asdfghjkl;'`\\zxcvbnm,./ ";
    private static final String SHIFTED = "!@#$%^&*()_+QWERTYUIOP{}ASDFGHJKL:\"~|ZXCVBNM<>? ";
    private static final String[] SYMBOLS = {"MINUS", "EQUALS", "LEFT_BRACKET", "RIGHT_BRACKET", "SEMICOLON",
            "APOSTROPHE", "GRAVE", "BACKSLASH", "COMMA", "PERIOD", "SLASH", "SPACE"};
    private static final java.util.Set<Integer> MODIFIERS = new java.util.HashSet<>(
            java.util.Arrays.asList(29, 42, 54, 56, 58, 97, 100, 125, 126));
    private static final String SYMBOL_CHARS = "-=[];'`\\,./ ";

    private final Context c;
    private Dadb adb;
    private volatile AdbStream shell;
    private String device;
    private String lastError;
    /** Android key name -> Linux code the remote can send (from its .kl file and capabilities). */
    private final Map<String, Integer> codes = new HashMap<>();
    private String layoutFile;
    private int dumpLines;

    KeyInjector(Context c) {
        this.c = c.getApplicationContext();
    }

    synchronized boolean ready() {
        try {
            connect();
            return true;
        } catch (Exception e) {
            lastError = describe(e);
            close();
            return false;
        }
    }

    boolean isConnected() {
        return shell != null;
    }

    /** For the status endpoint: which device, layout and codes are in use. */
    synchronized String describe() {
        StringBuilder sb = new StringBuilder(device + " " + layoutFile + " (dumpsys " + dumpLines + " lines)");
        for (String k : new String[]{"up", "ok", "back", "home", "playpause", "enter"}) {
            sb.append(' ').append(k).append('=').append(codes.get(KEYS.get(k)));
        }
        return sb.toString();
    }

    synchronized String lastError() {
        return lastError;
    }

    /** Presses a named key (see KEYS) n times. */
    synchronized void press(String name, int n) throws Exception {
        if (!isConnected()) throw new IllegalStateException("remote not connected");
        String android = KEYS.get(name);
        if (android == null) throw new IllegalArgumentException("unknown key " + name);
        n = Math.max(1, Math.min(n, 20));
        Integer code = codes.get(android);
        if (code == null) {
            // the remote has no such key (e.g. ENTER here): Android's own, slower, way
            StringBuilder cmd = new StringBuilder("input keyevent");
            for (int i = 0; i < n; i++) cmd.append(" KEYCODE_").append(android);
            run(cmd + " &");
            return;
        }
        StringBuilder ev = new StringBuilder();
        for (int i = 0; i < n; i++) tap(ev, code, false);
        write(ev);
    }

    /**
     * Types text as if on a keyboard (US layout). Characters the remote has no key for (here q and
     * `) go through `input text` instead, in order. Only printable ASCII is typed: the text ends up
     * in a shell, where control characters (Ctrl-C, newlines) could end the command and start another.
     */
    synchronized void type(String text) throws Exception {
        if (!isConnected()) throw new IllegalStateException("remote not connected");
        text = text.replaceAll("[^\\x20-\\x7e]", "");
        Integer shiftCode = codes.get("SHIFT_LEFT");
        StringBuilder ev = new StringBuilder(), slow = new StringBuilder();
        int chars = 0;
        for (char ch : text.toCharArray()) {
            int i = PLAIN.indexOf(ch);
            boolean shift = false;
            if (i < 0) {
                i = SHIFTED.indexOf(ch);
                shift = true;
            }
            Integer code = i < 0 ? null : codes.get(keyName(PLAIN.charAt(i)));
            if (code == null || (shift && shiftCode == null)) {
                if (ev.length() > 0) {
                    write(ev);
                    ev.setLength(0);
                }
                slow.append(ch);
                continue;
            }
            if (slow.length() > 0) {
                inputText(slow);
                slow.setLength(0);
            }
            if (shift) event(ev, 1, shiftCode, 1);
            tap(ev, code, false);
            if (shift) {
                event(ev, 1, shiftCode, 0);
                event(ev, 0, 0, 0);
            }
            if (++chars % 16 == 0) {   // keep each shell line short
                write(ev);
                ev.setLength(0);
            }
        }
        if (ev.length() > 0) write(ev);
        if (slow.length() > 0) inputText(slow);
    }

    private static String keyName(char plain) {
        if (Character.isLetterOrDigit(plain)) return String.valueOf(Character.toUpperCase(plain));
        return SYMBOLS[SYMBOL_CHARS.indexOf(plain)];
    }

    /** `input text` runs in order after the key writes because the shell is sequential. */
    private void inputText(CharSequence s) throws Exception {
        // printable ASCII only (type() already filters; kept here so this can never take anything else)
        String t = s.toString().replaceAll("[^\\x20-\\x7e]", "").replace(" ", "%s").replace("'", "'\\''");
        run("input text '" + t + "'");
    }

    /**
     * Runs one shell command on the TV over a short-lived ADB connection (used by the home
     * screen's Power menu: reboot / sleep / power off, which an ordinary app may not do itself).
     * Success means the command was written to the shell; a reboot kills the connection right
     * after, so the result is never read back. The callback runs on the main thread.
     */
    static void execShell(Context c, String cmd, java.util.function.Consumer<Boolean> done) {
        Context app = c.getApplicationContext();
        new Thread(() -> {
            boolean ok = false;
            Dadb d = null;
            try {
                File priv = new File(app.getFilesDir(), "adbkey"), pub = new File(app.getFilesDir(), "adbkey.pub");
                if (!priv.isFile() || !pub.isFile()) AdbKeyPair.Companion.generate(priv, pub);
                AdbKeyPair keys = AdbKeyPair.Companion.read(priv, pub);
                d = Dadb.Companion.create("127.0.0.1", 5555, keys, 5_000, 0);
                AdbStream sh = d.open("shell:");
                sh.getSink().writeUtf8(cmd + "\n").flush();
                Thread.sleep(300);   // let the shell pick the command up before we hang up
                ok = true;
            } catch (Exception ignored) {
            } finally {
                if (d != null) try {
                    d.close();
                } catch (Exception ignored) {
                }
            }
            if (done != null) {
                boolean r = ok;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> done.accept(r));
            }
        }, "power-shell").start();
    }

    // ---------------------------------------------------------------- internals

    private static void tap(StringBuilder ev, int code, boolean unused) {
        event(ev, 1, code, 1);
        event(ev, 0, 0, 0);
        event(ev, 1, code, 0);
        event(ev, 0, 0, 0);
    }

    /** One 32-bit struct input_event (time is filled in by the kernel), as \xHH escapes. */
    private static void event(StringBuilder ev, int type, int code, int value) {
        for (int i = 0; i < 8; i++) ev.append("\\x00");
        hex(ev, type & 0xFF);
        hex(ev, type >> 8 & 0xFF);
        hex(ev, code & 0xFF);
        hex(ev, code >> 8 & 0xFF);
        for (int i = 0; i < 4; i++) hex(ev, value >> (8 * i) & 0xFF);
    }

    private static void hex(StringBuilder sb, int b) {
        sb.append("\\x").append(Character.forDigit(b >> 4, 16)).append(Character.forDigit(b & 15, 16));
    }

    private void write(CharSequence ev) throws Exception {
        run("print -n '" + ev + "' > " + device);
    }

    private void run(String line) throws Exception {
        connect();
        try {
            shell.getSink().writeUtf8(line + "\n").flush();
        } catch (Exception e) {
            close();   // ADB restarted or the TV went to sleep: reconnect once
            connect();
            shell.getSink().writeUtf8(line + "\n").flush();
        }
    }

    private void connect() throws Exception {
        if (shell != null) return;
        File priv = new File(c.getFilesDir(), "adbkey"), pub = new File(c.getFilesDir(), "adbkey.pub");
        if (!priv.isFile() || !pub.isFile()) AdbKeyPair.Companion.generate(priv, pub);
        AdbKeyPair keys = AdbKeyPair.Companion.read(priv, pub);
        adb = Dadb.Companion.create("127.0.0.1", 5555, keys, 5_000, 0);
        if (device == null) {
            device = findRemote();
            loadLayout();
        }
        shell = adb.open("shell:");
        Thread drain = new Thread(() -> {   // nobody reads the shell's output: throw it away
            try {
                byte[] buf = new byte[4096];
                while (shell != null && shell.getSource().read(buf) >= 0) { /* discard */ }
            } catch (Exception ignored) {
            }
        }, "adb-drain");
        drain.setDaemon(true);
        drain.start();
        lastError = null;
    }

    /** The IR remote's input device (it accepts every key, letters included); event0 if unsure. */
    private String findRemote() throws Exception {
        String out = adb.shell("for d in /sys/class/input/event*; do echo \"$(cat $d/device/name) ${d##*/}\"; done")
                .getAllOutput();
        String fallback = null;
        for (String line : out.split("\n")) {
            int sp = line.lastIndexOf(' ');
            if (sp <= 0) continue;
            String name = line.substring(0, sp).toLowerCase(java.util.Locale.US), node = line.substring(sp + 1).trim();
            if (name.contains("aml_keypad")) return "/dev/input/" + node;
            if (fallback == null && (name.contains("keypad") || name.contains("remote") || name.contains("_ir")))
                fallback = "/dev/input/" + node;
        }
        return fallback != null ? fallback : "/dev/input/event0";
    }

    /**
     * Reads which Linux code produces each Android key on the remote: its key layout file (from
     * `dumpsys input`), limited to the codes the device driver accepts (`getevent -p`).
     */
    private void loadLayout() throws Exception {
        // in `dumpsys input`, each device block lists "Path: /dev/input/eventN" before "KeyLayoutFile:"
        String kl = null;
        boolean inDevice = false;
        String dump = adb.shell("dumpsys input").getAllOutput();
        dumpLines = dump.split("\n").length;
        for (String line : dump.split("\n")) {
            String t = line.trim();
            if (t.startsWith("Path:")) inDevice = t.substring(5).trim().equals(device);
            if (inDevice && t.startsWith("KeyLayoutFile:")) {
                kl = t.substring("KeyLayoutFile:".length()).trim();
                break;
            }
        }
        java.util.Set<Integer> supported = new java.util.HashSet<>();
        for (String tok : adb.shell("getevent -p " + device).getAllOutput().split("\\s+")) {
            if (tok.matches("[0-9a-f]{4}")) supported.add(Integer.parseInt(tok, 16));
        }
        codes.clear();
        if (kl == null) kl = "/system/usr/keylayout/Generic.kl";
        layoutFile = kl;
        for (String line : adb.shell("cat " + kl).getAllOutput().split("\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 3 || !f[0].equals("key") || !f[1].matches("\\d+")) continue;
            int code = Integer.parseInt(f[1]);
            if (!supported.isEmpty() && !supported.contains(code)) continue;
            // Android treats Ctrl/Alt/Shift/Caps/Meta as modifiers whatever the layout says (here
            // KEY_RIGHTCTRL is listed as DPAD_CENTER but does nothing), so use them only as themselves
            if (MODIFIERS.contains(code) && !f[2].matches("(SHIFT|CTRL|ALT|META)_.*|CAPS_LOCK")) continue;
            if (!codes.containsKey(f[2])) codes.put(f[2], code);   // first mapping wins
        }
    }

    private void close() {
        try {
            if (shell != null) shell.close();
        } catch (Exception ignored) {
        }
        try {
            if (adb != null) adb.close();
        } catch (Exception ignored) {
        }
        shell = null;
        adb = null;
    }

    private static String describe(Exception e) {
        String m = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.US);
        if (m.contains("refused")) return "Network debugging is off on the TV";
        if (m.contains("auth") || m.contains("timed out") || m.contains("timeout"))
            return "Accept \"Allow USB debugging\" on the TV (tick Always allow)";
        return "Couldn't reach the TV's debugging service: " + e.getMessage();
    }
}
