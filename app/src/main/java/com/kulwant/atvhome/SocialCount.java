package com.kulwant.atvhome;

import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Public follower counts read from each profile's own web page, so no API keys or logins are
 * needed. Pages are scanned in chunks and dropped as soon as the number is found. Every method
 * returns a short count such as "12.5K", or null on any failure.
 */
final class SocialCount {

    private static final String BROWSER =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36";
    // Instagram and Facebook only put the counts in the page for link-preview crawlers
    private static final String PREVIEW = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)";

    // YouTube: the About panel's own field (featured channels on the page use a different shape)
    private static final Pattern YOUTUBE = Pattern.compile("\"subscriberCountText\":\"([^\"]+?) subscribers?\"");
    // Instagram: og:description "98K Followers, 961 Following, ..."
    private static final Pattern INSTAGRAM = Pattern.compile("content=\"([0-9][0-9.,]*[KMB]?) Followers");
    // Facebook: og:description "Kulwant Nagi. 92,256 followers &#xb7; ..."
    private static final Pattern FACEBOOK = Pattern.compile("([0-9][0-9.,]*[KMB]?) followers");

    private SocialCount() {}

    static String youtube(String handle) {
        String h = handle.startsWith("@") ? handle : "@" + handle;
        return fetch("https://www.youtube.com/" + h + "/about", BROWSER, YOUTUBE);
    }

    static String instagram(String user) {
        return fetch("https://www.instagram.com/" + strip(user) + "/", PREVIEW, INSTAGRAM);
    }

    static String facebook(String page) {
        return fetch("https://www.facebook.com/" + strip(page), PREVIEW, FACEBOOK);
    }

    private static String strip(String s) {
        return s.replace("@", "").replaceAll("/+$", "");
    }

    private static String fetch(String url, String agent, Pattern p) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            c.setRequestProperty("User-Agent", agent);
            c.setRequestProperty("Accept-Language", "en-US,en");   // otherwise Facebook may answer in Punjabi
            if (c.getResponseCode() != 200) return null;
            try (Reader r = new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buf = new char[64 * 1024];
                StringBuilder win = new StringBuilder();
                int n;
                while ((n = r.read(buf)) > 0) {
                    win.append(buf, 0, n);
                    Matcher m = p.matcher(win);
                    if (m.find()) return compact(m.group(1));
                    // keep a tail so a match split across chunks is still found
                    if (win.length() > 512) win.delete(0, win.length() - 512);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.disconnect();
        }
        return null;
    }

    /** "92,256" -> "92.3K"; already short values ("98K", "12.5K") pass through. */
    static String compact(String s) {
        String digits = s.replace(",", "");
        if (!digits.matches("\\d+")) return s;
        long v = Long.parseLong(digits);
        if (v < 1000) return digits;
        String unit = v < 1_000_000 ? "K" : "M";
        double d = v / (v < 1_000_000 ? 1e3 : 1e6);
        String t = d < 100 ? String.format(java.util.Locale.US, "%.1f", d) : String.valueOf(Math.round(d));
        return (t.endsWith(".0") ? t.substring(0, t.length() - 2) : t) + unit;
    }
}
