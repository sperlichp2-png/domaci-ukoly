package cz.sperlich.domaciukoly;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

/** Zápis dnů s úkoly jako celodenních událostí do kalendáře v telefonu. */
public class CalendarHelper {

    public static class Cal {
        public long id;
        public String name, account;
    }

    public static boolean hasPerm(Context c) {
        return c.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
                && c.checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    public static List<Cal> list(Context c) {
        List<Cal> out = new ArrayList<>();
        String[] proj = {
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        };
        try (Cursor cur = c.getContentResolver().query(CalendarContract.Calendars.CONTENT_URI, proj,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + ">=?",
                new String[]{String.valueOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)}, null)) {
            if (cur != null) while (cur.moveToNext()) {
                Cal k = new Cal();
                k.id = cur.getLong(0);
                k.name = cur.getString(1);
                k.account = cur.getString(2);
                out.add(k);
            }
        }
        return out;
    }

    public static String title(Store.Day d, String kind) {
        boolean sick = Store.SICK.equals(kind);
        Set<String> subj = new LinkedHashSet<>();
        for (Store.Item it : d.items) if (!it.subject.isEmpty() && !it.subject.equals("Info")) subj.add(it.subject);
        if (subj.isEmpty()) return sick ? "Učivo 1.B (nemoc)" : "Domácí úkoly 1.B";
        return (sick ? "Učivo 1.B – " : "DÚ 1.B – ") + String.join(", ", subj);
    }

    public static String description(Context c, Store.Day d, String kind) {
        StringBuilder sb = new StringBuilder();
        for (Store.Item it : d.items) {
            sb.append(Store.done(c, it.id) ? "✓ " : "• ");
            if (!it.subject.isEmpty()) sb.append(it.subject).append(": ");
            sb.append(it.text);
            if (!it.link.isEmpty()) sb.append(" ").append(it.link);
            String n = Store.note(c, it.id);
            if (!n.isEmpty()) sb.append("\n   Poznámka: ").append(n);
            sb.append('\n');
        }
        sb.append("\nZdroj: ").append(Store.urlFor(c, kind));
        return sb.toString();
    }

    static long[] range(String iso) {
        String[] p = iso.split("-");
        Calendar u = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        u.clear();
        u.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]));
        long start = u.getTimeInMillis();
        return new long[]{start, start + 86400000L};
    }

    static boolean exists(ContentResolver cr, long eventId) {
        try (Cursor cur = cr.query(CalendarContract.Events.CONTENT_URI,
                new String[]{CalendarContract.Events._ID, CalendarContract.Events.DELETED},
                CalendarContract.Events._ID + "=?", new String[]{String.valueOf(eventId)}, null)) {
            return cur != null && cur.moveToFirst() && cur.getInt(1) == 0;
        }
    }

    /** Vytvoří nebo aktualizuje událost dne, pokud se od posledního zápisu něco změnilo. */
    public static boolean upsertIfChanged(Context c, long calId, Store.Day d, String kind) {
        String t = title(d, kind), desc = description(c, d, kind);
        String sig = Parser.sha1(t + "\n" + desc);
        ContentResolver cr = c.getContentResolver();
        boolean ex = d.eventId > 0 && exists(cr, d.eventId);
        if (ex && sig.equals(d.calSig)) return false;

        long[] r = range(d.date);
        ContentValues v = new ContentValues();
        v.put(CalendarContract.Events.DTSTART, r[0]);
        v.put(CalendarContract.Events.DTEND, r[1]);
        v.put(CalendarContract.Events.ALL_DAY, 1);
        v.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC");
        v.put(CalendarContract.Events.TITLE, t);
        v.put(CalendarContract.Events.DESCRIPTION, desc);
        if (ex) {
            cr.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, d.eventId), v, null, null);
        } else {
            if (calId <= 0) return false;
            v.put(CalendarContract.Events.CALENDAR_ID, calId);
            v.put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_FREE);
            Uri u = cr.insert(CalendarContract.Events.CONTENT_URI, v);
            if (u == null) return false;
            d.eventId = ContentUris.parseId(u);
        }
        d.calSig = sig;
        return true;
    }

    /** Po úpravě poznámek / odškrtnutí přepíše události, které už v kalendáři jsou. */
    public static void refreshExisting(Context c) {
        if (!hasPerm(c)) return;
        synchronized (Store.LOCK) {
            long calId = Store.prefs(c).getLong("calId", -1);
            for (String kind : new String[]{Store.HW, Store.SICK}) {
                Store st = Store.load(c, kind);
                boolean changed = false;
                for (Store.Day d : st.days.values()) {
                    if (d.eventId <= 0) continue;
                    try {
                        changed |= upsertIfChanged(c, calId, d, kind);
                    } catch (Exception ignored) {
                    }
                }
                if (changed) st.save(c);
            }
        }
    }

    /** Záloha bez oprávnění: otevře kalendářovou aplikaci s předvyplněnou událostí. */
    public static Intent insertIntent(Context c, Store.Day d, String kind) {
        long[] r = range(d.date);
        return new Intent(Intent.ACTION_INSERT)
                .setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, r[0])
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, r[1])
                .putExtra(CalendarContract.Events.TITLE, title(d, kind))
                .putExtra(CalendarContract.Events.DESCRIPTION, description(c, d, kind))
                .putExtra(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_FREE);
    }
}
