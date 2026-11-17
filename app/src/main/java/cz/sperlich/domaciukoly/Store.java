package cz.sperlich.domaciukoly;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Uložené úkoly (soubor ukoly.json) + nastavení a poznámky (SharedPreferences). */
public class Store {

    public static final Object LOCK = new Object();
    public static final String DEFAULT_URL =
            "https://sites.google.com/zsmlynska.cz/1b-halickova-2026/dom%C3%A1c%C3%AD-%C3%BAkoly?authuser=0";

    public static class Item {
        public String id, subject = "", text = "", link = "";
        public long firstSeen;
    }

    public static class Day {
        public String date, week = "";
        public List<String> raw = new ArrayList<>();
        public List<Item> items = new ArrayList<>();
        public long eventId = -1;
        public String calSig = "";
    }

    public final Map<String, Day> days = new TreeMap<>();
    public final Set<String> known = new HashSet<>();
    public boolean initialized;
    public long lastCheck;
    public String lastHash = "";
    public String lastError = "";

    // ---------------- nastavení ----------------

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("nastaveni", Context.MODE_PRIVATE);
    }

    public static String url(Context c) {
        String u = prefs(c).getString("url", "");
        return u.trim().isEmpty() ? DEFAULT_URL : u.trim();
    }

    public static String note(Context c, String id) {
        return prefs(c).getString("note_" + id, "");
    }

    public static boolean done(Context c, String id) {
        return prefs(c).getBoolean("done_" + id, false);
    }

    // ---------------- soubor ----------------

    static File file(Context c) {
        return new File(c.getFilesDir(), "ukoly.json");
    }

    public static Store load(Context c) {
        Store s = new Store();
        File f = file(c);
        if (!f.exists()) return s;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) break;
                off += n;
            }
            JSONObject o = new JSONObject(new String(b, 0, off, StandardCharsets.UTF_8));
            s.initialized = o.optBoolean("initialized");
            s.lastCheck = o.optLong("lastCheck");
            s.lastHash = o.optString("lastHash");
            s.lastError = o.optString("lastError");
            JSONArray k = o.optJSONArray("known");
            if (k != null) for (int i = 0; i < k.length(); i++) s.known.add(k.getString(i));
            JSONArray ds = o.optJSONArray("days");
            if (ds != null) for (int i = 0; i < ds.length(); i++) {
                JSONObject dj = ds.getJSONObject(i);
                Day d = new Day();
                d.date = dj.getString("date");
                d.week = dj.optString("week");
                d.eventId = dj.optLong("eventId", -1);
                d.calSig = dj.optString("calSig");
                JSONArray r = dj.optJSONArray("raw");
                if (r != null) for (int j = 0; j < r.length(); j++) d.raw.add(r.getString(j));
                JSONArray its = dj.optJSONArray("items");
                if (its != null) for (int j = 0; j < its.length(); j++) {
                    JSONObject ij = its.getJSONObject(j);
                    Item it = new Item();
                    it.id = ij.getString("id");
                    it.subject = ij.optString("subject");
                    it.text = ij.optString("text");
                    it.link = ij.optString("link");
                    it.firstSeen = ij.optLong("firstSeen");
                    d.items.add(it);
                }
                s.days.put(d.date, d);
            }
        } catch (Exception e) {
            // poškozený soubor – začneme znovu
        }
        return s;
    }

    public void save(Context c) {
        try {
            JSONObject o = new JSONObject();
            o.put("initialized", initialized);
            o.put("lastCheck", lastCheck);
            o.put("lastHash", lastHash);
            o.put("lastError", lastError);
            JSONArray k = new JSONArray();
            for (String id : known) k.put(id);
            o.put("known", k);
            JSONArray ds = new JSONArray();
            for (Day d : days.values()) {
                JSONObject dj = new JSONObject();
                dj.put("date", d.date);
                dj.put("week", d.week);
                dj.put("eventId", d.eventId);
                dj.put("calSig", d.calSig);
                JSONArray r = new JSONArray();
                for (String x : d.raw) r.put(x);
                dj.put("raw", r);
                JSONArray its = new JSONArray();
                for (Item it : d.items) {
                    JSONObject ij = new JSONObject();
                    ij.put("id", it.id);
                    ij.put("subject", it.subject);
                    ij.put("text", it.text);
                    ij.put("link", it.link);
                    ij.put("firstSeen", it.firstSeen);
                    its.put(ij);
                }
                dj.put("items", its);
                ds.put(dj);
            }
            o.put("days", ds);
            File tmp = new File(c.getFilesDir(), "ukoly.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(file(c));
        } catch (Exception ignored) {
        }
    }
}
