package cz.sperlich.domaciukoly;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Stažení stránky, porovnání s uloženými úkoly, upozornění a kalendář. */
public class Sync {

    public static final String CHANNEL = "ukoly";
    static final int JOB_ID = 1;

    public static class Outcome {
        public boolean ok;
        public int added;
        public String message = "";
    }

    // ---------------- plánování ----------------

    public static void schedule(Context c) {
        int hours = Store.prefs(c).getInt("hours", 1);
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        JobInfo ji = new JobInfo.Builder(JOB_ID, new ComponentName(c, CheckJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(hours * 3600_000L, 15 * 60_000L)
                .setPersisted(true)
                .build();
        JobInfo existing = js.getPendingJob(JOB_ID);
        if (existing != null && existing.getIntervalMillis() == ji.getIntervalMillis()) return;
        js.schedule(ji);
    }

    // ---------------- hlavní běh ----------------

    public static Outcome run(Context c) {
        Outcome out = new Outcome();
        Outcome hw = runOne(c, Store.HW);
        out.ok = hw.ok;
        out.added = hw.added;
        out.message = hw.message;
        if (Store.sick(c)) {
            Outcome sk = runOne(c, Store.SICK);
            out.added += sk.added;
            out.message += "\nUčivo pro nemocné: " + sk.message;
        }
        return out;
    }

    /** Zkontroluje jednu stránku (domácí úkoly nebo učivo pro nemocné). */
    public static Outcome runOne(Context c, String name) {
        boolean sick = Store.SICK.equals(name);
        Outcome out = new Outcome();
        String html;
        try {
            html = fetch(Store.urlFor(c, name));
        } catch (Exception e) {
            synchronized (Store.LOCK) {
                Store st = Store.load(c, name);
                st.lastError = "Stránku se nepodařilo načíst (" + e.getClass().getSimpleName() + ").";
                st.save(c);
            }
            out.message = "Stránku se nepodařilo načíst. Zkontroluj připojení.";
            return out;
        }

        Parser.Result r = Parser.parse(html, Calendar.getInstance());
        List<Store.Item> added = new ArrayList<>();
        Map<Store.Item, String> addedDate = new HashMap<>();
        boolean pageChangedUnparsed = false;

        synchronized (Store.LOCK) {
            Store st = Store.load(c, name);
            long now = System.currentTimeMillis();
            String hash = Parser.sha1(r.plain);

            if (r.days.isEmpty()) {
                if (st.initialized && !st.lastHash.isEmpty() && !hash.equals(st.lastHash)) pageChangedUnparsed = true;
                st.lastError = "Na stránce jsem nenašel žádné dny – formát se možná změnil.";
            } else {
                st.lastError = "";
                for (Parser.PDay pd : r.days) {
                    Store.Day old = st.days.get(pd.date);
                    Store.Day nd = new Store.Day();
                    nd.date = pd.date;
                    nd.week = pd.week;
                    nd.raw = new ArrayList<>(pd.raw);
                    Map<String, Store.Item> oldItems = new HashMap<>();
                    if (old != null) {
                        nd.eventId = old.eventId;
                        nd.calSig = old.calSig;
                        if (nd.week.isEmpty()) nd.week = old.week;
                        for (Store.Item it : old.items) oldItems.put(it.id, it);
                    }
                    for (Parser.PItem pi : pd.items) {
                        String id = (sick ? "n" : "") + pd.date + "_"
                                + Parser.sha1(Parser.strip(pi.subject + "|" + pi.text + "|" + pi.link)).substring(0, 10);
                        Store.Item it = oldItems.get(id);
                        if (it == null) {
                            it = new Store.Item();
                            it.id = id;
                            it.firstSeen = st.initialized ? now : 0;
                        }
                        it.subject = pi.subject;
                        it.text = pi.text;
                        it.link = pi.link;
                        if (!st.known.contains(id)) {
                            st.known.add(id);
                            if (st.initialized) {
                                added.add(it);
                                addedDate.put(it, pd.date);
                            }
                        }
                        nd.items.add(it);
                    }
                    st.days.put(pd.date, nd);
                }
                st.initialized = true;
            }
            st.lastHash = hash;
            st.lastCheck = now;

            // kalendář: aktualizace existujících událostí + automatické přidávání (jen domácí úkoly)
            if (CalendarHelper.hasPerm(c)) {
                long calId = Store.prefs(c).getLong("calId", -1);
                boolean auto = !sick && Store.prefs(c).getBoolean("autoCal", false);
                String today = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Calendar.getInstance().getTime());
                for (Parser.PDay pd : r.days) {
                    Store.Day d = st.days.get(pd.date);
                    if (d == null) continue;
                    boolean want = d.eventId > 0 || (auto && calId > 0 && d.date.compareTo(today) >= 0);
                    if (!want) continue;
                    try {
                        CalendarHelper.upsertIfChanged(c, calId, d, name);
                    } catch (Exception ignored) {
                    }
                }
            }
            st.save(c);
        }

        if (!added.isEmpty()) notifyNew(c, added, addedDate, sick);
        else if (pageChangedUnparsed) notify(c, sick ? 4 : 2,
                sick ? "Stránka s učivem pro nemocné se změnila" : "Stránka s úkoly se změnila",
                "Nepodařilo se rozpoznat jednotlivé dny – otevři aplikaci a podívej se na originál.");

        out.ok = !r.days.isEmpty();
        out.added = added.size();
        out.message = r.days.isEmpty() ? "Na stránce jsem nenašel dny."
                : added.isEmpty() ? "Nic nového." : "Nové položky: " + added.size();
        return out;
    }

    static String fetch(String url) throws Exception {
        String u = url;
        for (int i = 0; i < 5; i++) {
            HttpURLConnection con = (HttpURLConnection) new URL(u).openConnection();
            con.setConnectTimeout(20000);
            con.setReadTimeout(30000);
            con.setInstanceFollowRedirects(false);
            con.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36");
            con.setRequestProperty("Accept-Language", "cs-CZ,cs;q=0.9");
            int code = con.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = con.getHeaderField("Location");
                con.disconnect();
                if (loc == null) throw new Exception("redirect");
                u = new URL(new URL(u), loc).toString();
                continue;
            }
            if (code != 200) {
                con.disconnect();
                throw new Exception("HTTP " + code);
            }
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                return new String(bo.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                con.disconnect();
            }
        }
        throw new Exception("too many redirects");
    }

    // ---------------- upozornění ----------------

    static final String[] DAYS = {"", "Ne", "Po", "Út", "St", "Čt", "Pá", "So"};

    public static String shortDate(String iso) {
        try {
            String[] p = iso.split("-");
            Calendar c = Calendar.getInstance();
            c.clear();
            c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]));
            return DAYS[c.get(Calendar.DAY_OF_WEEK)] + " " + Integer.parseInt(p[2]) + ". " + Integer.parseInt(p[1]) + ".";
        } catch (Exception e) {
            return iso;
        }
    }

    static void notifyNew(Context c, List<Store.Item> added, Map<Store.Item, String> dates, boolean sick) {
        Map<String, StringBuilder> byDay = new LinkedHashMap<>();
        List<Store.Item> sorted = new ArrayList<>(added);
        sorted.sort((a, b) -> dates.get(a).compareTo(dates.get(b)));
        for (Store.Item it : sorted) {
            String d = dates.get(it);
            StringBuilder sb = byDay.get(d);
            if (sb == null) {
                sb = new StringBuilder();
                byDay.put(d, sb);
            } else sb.append("; ");
            sb.append(it.subject.isEmpty() ? "" : it.subject + " – ").append(it.text);
        }
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, StringBuilder> e : byDay.entrySet()) {
            if (text.length() > 0) text.append('\n');
            text.append(shortDate(e.getKey())).append(": ").append(e.getValue());
        }
        String title = sick
                ? "Učivo pro nemocné 1.B (" + added.size() + ")"
                : added.size() == 1 ? "Nový úkol 1.B" : "Nové úkoly 1.B (" + added.size() + ")";
        notify(c, sick ? 3 : 1, title, text.toString());
    }

    public static void ensureChannel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Nové úkoly", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Upozornění na nové domácí úkoly");
        nm.createNotificationChannel(ch);
    }

    public static void notify(Context c, int id, String title, String text) {
        if (Build.VERSION.SDK_INT >= 33
                && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ensureChannel(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        open.putExtra("tab", (id == 3 || id == 4) ? Store.SICK : Store.HW);
        PendingIntent pi = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(id, n);
    }
}
