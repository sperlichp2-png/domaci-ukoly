package cz.sperlich.domaciukoly;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.util.Linkify;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    static final int REQ_NOTIF = 1, REQ_CAL = 2;

    static final int BG = Color.parseColor("#F6F3EE"), CARD = Color.WHITE, INK = Color.parseColor("#23201C"),
            MUTED = Color.parseColor("#7A7268"), LINE = Color.parseColor("#E7E1D8"),
            ACCENT = Color.parseColor("#D9532B"), ACCENT_SOFT = Color.parseColor("#FBE6DD"), OK = Color.parseColor("#2F8F5B");

    static final String[] DAY_LONG = {"", "neděle", "pondělí", "úterý", "středa", "čtvrtek", "pátek", "sobota"};

    final Handler ui = new Handler(Looper.getMainLooper());
    LinearLayout list, chipRow, tabRow;
    TextView sickChip;
    String tab = Store.HW, fallbackKind = Store.HW;
    ScrollView scroll;
    TextView status, banner;
    String filter = "all";
    boolean scrolledOnce = false, notesDirty = false, refreshing = false;
    Runnable afterCalPerm;
    Store.Day fallbackDay;

    // ------------------------------------------------------------------

    int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    GradientDrawable round(int fill, int stroke, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    SharedPreferences prefs() {
        return Store.prefs(this);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // ---- hlavička ----
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(16), dp(14), dp(16), dp(8));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Domácí úkoly · 1.B");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(INK);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        titleRow.addView(iconButton("⟳", "Zkontrolovat stránku", v -> refresh(true)));
        titleRow.addView(iconButton("🌐", "Originál na stránkách školy", v -> openOriginal()));
        titleRow.addView(iconButton("⚙", "Nastavení", v -> showSettings()));
        head.addView(titleRow);

        status = new TextView(this);
        status.setTextColor(MUTED);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        head.addView(status);

        LinearLayout sickRow = new LinearLayout(this);
        sickRow.setPadding(0, dp(10), 0, 0);
        sickChip = new TextView(this);
        sickChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sickChip.setTypeface(Typeface.DEFAULT_BOLD);
        sickChip.setPadding(dp(14), dp(7), dp(14), dp(7));
        sickChip.setOnClickListener(v -> toggleSick());
        sickRow.addView(sickChip);
        head.addView(sickRow);

        tabRow = new LinearLayout(this);
        tabRow.setPadding(0, dp(8), 0, 0);
        tabRow.addView(tabChip("📚 Domácí úkoly", Store.HW));
        tabRow.addView(tabChip("🏠 Učivo pro nemocné", Store.SICK));
        head.addView(tabRow);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        chipRow = new LinearLayout(this);
        chipRow.setPadding(0, dp(10), 0, 0);
        chipRow.addView(chip("Vše", "all"));
        chipRow.addView(chip("Nesplněné", "open"));
        chipRow.addView(chip("Od dneška", "upcoming"));
        TextView sortChip = chip("", "sort");
        sortChip.setOnClickListener(v -> {
            prefs().edit().putBoolean("newestFirst", !prefs().getBoolean("newestFirst", false)).apply();
            scrolledOnce = false;
            render();
        });
        chipRow.addView(sortChip);
        hs.addView(chipRow);
        head.addView(hs);
        root.addView(head);

        View sep = new View(this);
        sep.setBackgroundColor(LINE);
        root.addView(sep, new LinearLayout.LayoutParams(-1, dp(1)));

        // ---- obsah ----
        scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(8), dp(12), dp(80));

        banner = new TextView(this);
        banner.setBackground(round(ACCENT_SOFT, ACCENT, 14));
        banner.setTextColor(INK);
        banner.setPadding(dp(14), dp(12), dp(14), dp(12));
        banner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        banner.setVisibility(View.GONE);
        banner.setOnClickListener(v -> {
            prefs().edit().putLong(seenKey(), System.currentTimeMillis()).apply();
            render();
        });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.setMargins(0, dp(6), 0, dp(4));
        content.addView(banner, bl);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        content.addView(list);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        applyTabExtra(getIntent());
        Sync.ensureChannel(this);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
        try {
            Sync.schedule(this);
        } catch (Exception e) {
            Toast.makeText(this, "Kontrolu na pozadí se nepodařilo naplánovat.", Toast.LENGTH_LONG).show();
        }
        render();
        refresh(false);
    }

    void applyTabExtra(Intent i) {
        if (i == null) return;
        if (i.hasExtra("setSick")) prefs().edit().putBoolean("sick", i.getBooleanExtra("setSick", false)).apply();
        String t = i.getStringExtra("tab");
        if (Store.SICK.equals(t) && Store.sick(this)) tab = Store.SICK;
        else if (Store.HW.equals(t)) tab = Store.HW;
        scrolledOnce = false;
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        applyTabExtra(i);
        render();
        if (i.hasExtra("setSick")) refresh(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (notesDirty) {
            notesDirty = false;
            final android.content.Context app = getApplicationContext();
            new Thread(() -> CalendarHelper.refreshExisting(app)).start();
        }
    }

    // ------------------------------------------------------------------

    TextView iconButton(String txt, String desc, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(txt);
        t.setContentDescription(desc);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setTextColor(INK);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(Color.TRANSPARENT, LINE, 12));
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(42), dp(42));
        lp.setMargins(dp(6), 0, 0, 0);
        t.setLayoutParams(lp);
        return t;
    }

    TextView chip(String label, String key) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTag(key);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(14), dp(7), dp(14), dp(7));
        t.setOnClickListener(v -> {
            filter = key;
            render();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, 0, dp(8), 0);
        t.setLayoutParams(lp);
        return t;
    }

    String seenKey() {
        return Store.HW.equals(tab) ? "seenAt" : "seenAt_" + tab;
    }

    TextView tabChip(String label, String key) {
        TextView t = chip(label, key);
        t.setOnClickListener(v -> {
            tab = key;
            scrolledOnce = false;
            render();
        });
        return t;
    }

    void toggleSick() {
        boolean on = !Store.sick(this);
        prefs().edit().putBoolean("sick", on).apply();
        tab = on ? Store.SICK : Store.HW;
        scrolledOnce = false;
        render();
        if (on) {
            Toast.makeText(this, "Hlídám i učivo pro nemocné a přidám záložku.", Toast.LENGTH_SHORT).show();
            refresh(false);
        }
    }

    void styleChips() {
        boolean sick = Store.sick(this);
        if (!sick) tab = Store.HW;
        sickChip.setText(sick ? "🤒 Viki je nemocná – vypnout" : "🤒 Viki je nemocná");
        sickChip.setBackground(round(sick ? ACCENT : CARD, sick ? ACCENT : LINE, 20));
        sickChip.setTextColor(sick ? Color.WHITE : INK);
        tabRow.setVisibility(sick ? View.VISIBLE : View.GONE);
        for (int i = 0; i < tabRow.getChildCount(); i++) {
            TextView t = (TextView) tabRow.getChildAt(i);
            boolean on = tab.equals(t.getTag());
            t.setBackground(round(on ? ACCENT_SOFT : CARD, on ? ACCENT : LINE, 20));
            t.setTextColor(INK);
        }
        for (int i = 0; i < chipRow.getChildCount(); i++) {
            TextView t = (TextView) chipRow.getChildAt(i);
            if ("sort".equals(t.getTag())) {
                boolean desc = prefs().getBoolean("newestFirst", false);
                t.setText(desc ? "⇅ Nejnovější nahoře" : "⇅ Nejstarší nahoře");
                t.setBackground(round(ACCENT_SOFT, ACCENT, 20));
                t.setTextColor(INK);
                continue;
            }
            boolean on = filter.equals(t.getTag());
            t.setBackground(round(on ? INK : CARD, on ? INK : LINE, 20));
            t.setTextColor(on ? BG : INK);
        }
    }

    static int subjectColor(String s) {
        switch (s) {
            case "ČJ": case "Čtení": case "Čtení a psaní": return Color.parseColor("#2F6FD0");
            case "M": return Color.parseColor("#1F9A6A");
            case "Psaní": return Color.parseColor("#8A4FD0");
            case "Info": return Color.parseColor("#C98A0C");
            case "PRV": return Color.parseColor("#0F8F9A");
            case "AJ": return Color.parseColor("#D04F7A");
            default: return Color.parseColor("#6B6B6B");
        }
    }

    static String todayIso() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
    }

    static Calendar parse(String iso) {
        String[] p = iso.split("-");
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]));
        return c;
    }

    // ------------------------------------------------------------------

    void refresh(boolean manual) {
        if (refreshing) return;
        refreshing = true;
        status.setText("Kontroluji stránku…");
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            Sync.Outcome o;
            try {
                o = Sync.run(app);
            } catch (Throwable t) {
                o = new Sync.Outcome();
                o.message = "Chyba: " + t.getMessage();
            }
            final Sync.Outcome res = o;
            ui.post(() -> {
                refreshing = false;
                render();
                if (manual) Toast.makeText(this, res.message, Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    void render() {
        styleChips();
        Store st;
        synchronized (Store.LOCK) {
            st = Store.load(this, tab);
        }

        if (!refreshing) {
            String s = st.lastCheck > 0
                    ? "Zkontrolováno " + new SimpleDateFormat("d. M. H:mm", new Locale("cs")).format(new Date(st.lastCheck))
                    : "Zatím nezkontrolováno";
            if (!st.lastError.isEmpty()) s += "\n⚠ " + st.lastError;
            status.setText(s);
        }

        long seenAt = prefs().getLong(seenKey(), 0);
        int fresh = 0;
        for (Store.Day d : st.days.values())
            for (Store.Item it : d.items) if (it.firstSeen > seenAt) fresh++;
        if (fresh > 0) {
            String w = Store.SICK.equals(tab) ? (fresh == 1 ? "nová položka učiva" : fresh < 5 ? "nové položky učiva" : "nových položek učiva")
                    : fresh == 1 ? "nový úkol" : fresh < 5 ? "nové úkoly" : "nových úkolů";
            banner.setText("🔔 " + fresh + " " + w + " od poslední návštěvy · klepnutím označíš jako přečtené");
            banner.setVisibility(View.VISIBLE);
        } else banner.setVisibility(View.GONE);

        list.removeAllViews();
        String today = todayIso();
        View scrollTarget = null;
        String lastWeek = null;
        int shown = 0;

        boolean desc = prefs().getBoolean("newestFirst", false);
        List<Store.Day> ordered = new ArrayList<>(st.days.values());
        if (desc) java.util.Collections.reverse(ordered);
        for (Store.Day d : ordered) {
            if (filter.equals("upcoming") && d.date.compareTo(today) < 0) continue;
            List<Store.Item> items = new ArrayList<>();
            for (Store.Item it : d.items) {
                if (filter.equals("open") && Store.done(this, it.id)) continue;
                items.add(it);
            }
            if (items.isEmpty() && (filter.equals("open") || !d.items.isEmpty())) continue;
            shown++;

            if (!d.week.isEmpty() && !d.week.equals(lastWeek)) {
                TextView wk = new TextView(this);
                wk.setText(("Týden " + d.week).toUpperCase(new Locale("cs")));
                wk.setTextColor(MUTED);
                wk.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                wk.setTypeface(Typeface.DEFAULT_BOLD);
                wk.setLetterSpacing(0.06f);
                wk.setPadding(dp(4), dp(18), 0, dp(4));
                list.addView(wk);
                lastWeek = d.week;
            }
            View card = dayCard(d, items, today, seenAt);
            list.addView(card);
            if (!desc && scrollTarget == null && d.date.compareTo(today) >= 0) scrollTarget = card;
        }

        if (shown == 0) {
            TextView e = new TextView(this);
            e.setText(st.days.isEmpty() ? (Store.SICK.equals(tab) ? "Zatím žádné učivo.\nKlepni na ⟳ pro kontrolu stránky."
                    : "Zatím žádné úkoly.\nKlepni na ⟳ pro kontrolu stránky.")
                    : "Nic tu není – vše hotovo 🎉");
            e.setTextColor(MUTED);
            e.setGravity(Gravity.CENTER);
            e.setPadding(0, dp(48), 0, dp(48));
            list.addView(e);
        }

        if (!scrolledOnce && shown > 0) {
            scrolledOnce = true;
            final View target = scrollTarget;
            scroll.post(() -> {
                if (desc) scroll.scrollTo(0, 0);
                else if (target != null) scroll.scrollTo(0, Math.max(0, target.getTop() - dp(8)));
                else scroll.fullScroll(View.FOCUS_DOWN);
            });
        }
    }

    View dayCard(Store.Day d, List<Store.Item> items, String today, long seenAt) {
        boolean isToday = d.date.equals(today);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(CARD, isToday ? ACCENT : LINE, 16));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.setMargins(0, dp(6), 0, dp(6));
        card.setLayoutParams(cl);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(14), dp(10), dp(10), dp(6));
        Calendar c = parse(d.date);
        TextView date = new TextView(this);
        SpannableStringBuilder ds = new SpannableStringBuilder();
        ds.append(c.get(Calendar.DAY_OF_MONTH) + ". " + (c.get(Calendar.MONTH) + 1) + ".");
        ds.setSpan(new StyleSpan(Typeface.BOLD), 0, ds.length(), 0);
        ds.setSpan(new RelativeSizeSpan(1.15f), 0, ds.length(), 0);
        int st = ds.length();
        ds.append("\n").append(DAY_LONG[c.get(Calendar.DAY_OF_WEEK)]).append(isToday ? " · dnes" : "");
        ds.setSpan(new ForegroundColorSpan(MUTED), st, ds.length(), 0);
        ds.setSpan(new RelativeSizeSpan(0.85f), st, ds.length(), 0);
        date.setText(ds);
        date.setTextColor(INK);
        head.addView(date, new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(iconButton("👁", "Náhled původního zadání", v -> showSource(d)));
        head.addView(iconButton(d.eventId > 0 ? "📅✓" : "📅", "Přidat do kalendáře", v -> addToCalendar(d)));
        card.addView(head);

        for (Store.Item it : items) card.addView(itemRow(it, it.firstSeen > seenAt));
        if (items.isEmpty()) {
            for (String r : d.raw) {
                TextView t = new TextView(this);
                t.setText(r);
                t.setTextColor(INK);
                t.setPadding(dp(14), dp(4), dp(14), dp(4));
                card.addView(t);
            }
        }
        View pad = new View(this);
        card.addView(pad, new LinearLayout.LayoutParams(-1, dp(6)));
        return card;
    }

    View itemRow(Store.Item it, boolean isNew) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(8), dp(6), dp(14), dp(6));

        View line = new View(this);
        line.setBackgroundColor(LINE);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, dp(1));
        ll.setMargins(dp(6), 0, 0, dp(6));
        row.addView(line, ll);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.TOP);
        CheckBox cb = new CheckBox(this);
        boolean done = Store.done(this, it.id);
        cb.setChecked(done);
        top.addView(cb, new LinearLayout.LayoutParams(-2, -2));

        TextView tv = new TextView(this);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setTextColor(done ? MUTED : INK);
        tv.setPadding(0, dp(8), 0, 0);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (!it.subject.isEmpty()) {
            sb.append(" ").append(it.subject).append(" ");
            sb.setSpan(new BackgroundColorSpan(subjectColor(it.subject)), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(Color.WHITE), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new RelativeSizeSpan(0.8f), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append("  ");
        }
        sb.append(it.text);
        if (!it.link.isEmpty()) sb.append("\n").append(it.link);
        if (isNew) {
            int s = sb.length();
            sb.append("  NOVÉ ");
            sb.setSpan(new ForegroundColorSpan(ACCENT), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(Typeface.BOLD), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new RelativeSizeSpan(0.75f), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        tv.setText(sb);
        Linkify.addLinks(tv, Linkify.WEB_URLS);
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        if (done) tv.setPaintFlags(tv.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        top.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(top);

        cb.setOnCheckedChangeListener((b, checked) -> {
            prefs().edit().putBoolean("done_" + it.id, checked).apply();
            tv.setTextColor(checked ? MUTED : INK);
            tv.setPaintFlags(checked ? tv.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG
                    : tv.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            notesDirty = true;
        });

        EditText note = new EditText(this);
        note.setHint("Poznámka…");
        note.setText(Store.note(this, it.id));
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        note.setTextColor(INK);
        note.setHintTextColor(MUTED);
        note.setBackground(round(BG, LINE, 10));
        note.setPadding(dp(10), dp(6), dp(10), dp(6));
        note.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        note.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable e) {
                prefs().edit().putString("note_" + it.id, e.toString().trim()).apply();
                notesDirty = true;
            }
        });
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-1, -2);
        nl.setMargins(dp(40), dp(4), 0, dp(2));
        row.addView(note, nl);
        return row;
    }

    // ------------------------------------------------------------------

    void openOriginal() {
        startActivity(new Intent(this, WebActivity.class).putExtra("url", Store.urlFor(this, tab)));
    }

    void showSource(Store.Day d) {
        StringBuilder sb = new StringBuilder();
        if (!d.raw.isEmpty()) for (String r : d.raw) sb.append("• ").append(r).append('\n');
        else for (Store.Item it : d.items)
            sb.append("• ").append(it.subject.isEmpty() ? "" : it.subject + ": ").append(it.text).append('\n');
        TextView t = new TextView(this);
        t.setText(sb.toString().trim());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTextColor(INK);
        t.setTextIsSelectable(true);
        t.setPadding(dp(22), dp(10), dp(22), dp(4));
        Linkify.addLinks(t, Linkify.WEB_URLS);
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        Calendar c = parse(d.date);
        new AlertDialog.Builder(this)
                .setTitle("Zadání – " + DAY_LONG[c.get(Calendar.DAY_OF_WEEK)] + " " + c.get(Calendar.DAY_OF_MONTH) + ". " + (c.get(Calendar.MONTH) + 1) + ".")
                .setView(sv)
                .setPositiveButton("Zavřít", null)
                .setNeutralButton("Otevřít originál", (x, y) -> openOriginal())
                .show();
    }

    // ---------------- kalendář ----------------

    void addToCalendar(Store.Day d) {
        long calId = prefs().getLong("calId", -1);
        final String kind = tab;
        if (!CalendarHelper.hasPerm(this) || calId <= 0) {
            fallbackDay = d;
            fallbackKind = kind;
            pickCalendar(() -> addToCalendar(d));
            return;
        }
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            boolean ok = false;
            String err = null;
            synchronized (Store.LOCK) {
                Store st = Store.load(app, kind);
                Store.Day sd = st.days.get(d.date);
                if (sd != null) {
                    try {
                        sd.calSig = ""; // vynutit zápis
                        ok = CalendarHelper.upsertIfChanged(app, calId, sd, kind);
                        st.save(app);
                    } catch (Exception e) {
                        err = e.getMessage();
                    }
                }
            }
            final boolean fok = ok;
            final String ferr = err;
            ui.post(() -> {
                Toast.makeText(this, fok ? "Uloženo do kalendáře „" + prefs().getString("calName", "") + "“"
                        : "Nepodařilo se zapsat do kalendáře" + (ferr != null ? ": " + ferr : ""), Toast.LENGTH_SHORT).show();
                render();
            });
        }).start();
    }

    void pickCalendar(Runnable after) {
        if (!CalendarHelper.hasPerm(this)) {
            afterCalPerm = after;
            requestPermissions(new String[]{android.Manifest.permission.READ_CALENDAR,
                    android.Manifest.permission.WRITE_CALENDAR}, REQ_CAL);
            return;
        }
        List<CalendarHelper.Cal> cals;
        try {
            cals = CalendarHelper.list(this);
        } catch (Exception e) {
            cals = new ArrayList<>();
        }
        if (cals.isEmpty()) {
            Toast.makeText(this, "V telefonu jsem nenašel kalendář, do kterého jde zapisovat.", Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[cals.size()];
        for (int i = 0; i < cals.size(); i++) names[i] = cals.get(i).name + "\n" + cals.get(i).account;
        final List<CalendarHelper.Cal> fc = cals;
        new AlertDialog.Builder(this)
                .setTitle("Do kterého kalendáře?")
                .setItems(names, (dlg, which) -> {
                    CalendarHelper.Cal k = fc.get(which);
                    prefs().edit().putLong("calId", k.id).putString("calName", k.name).apply();
                    if (after != null) after.run();
                })
                .setNegativeButton("Zrušit", null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != REQ_CAL) return;
        if (CalendarHelper.hasPerm(this)) {
            Runnable a = afterCalPerm;
            afterCalPerm = null;
            pickCalendar(a);
        } else if (fallbackDay != null) {
            try {
                startActivity(CalendarHelper.insertIntent(this, fallbackDay, fallbackKind));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "Kalendářová aplikace nenalezena.", Toast.LENGTH_SHORT).show();
            }
            fallbackDay = null;
        }
    }

    // ---------------- nastavení ----------------

    TextView sectionLabel(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(INK);
        t.setPadding(0, dp(14), 0, dp(4));
        return t;
    }

    TextView hint(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextColor(MUTED);
        return t;
    }

    void showSettings() {
        SharedPreferences p = prefs();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(4), dp(22), dp(8));

        box.addView(sectionLabel("Adresa stránky s úkoly"));
        EditText url = new EditText(this);
        url.setText(Store.url(this));
        url.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(url);

        box.addView(sectionLabel("Kontrolovat na pozadí každou"));
        RadioGroup rg = new RadioGroup(this);
        rg.setOrientation(RadioGroup.HORIZONTAL);
        int[] hrs = {1, 3, 6};
        int cur = p.getInt("hours", 1);
        for (int h : hrs) {
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(h == 1 ? "1 h" : h + " h");
            rb.setTag(h);
            rg.addView(rb);
            if (h == cur) rb.setChecked(true);
        }
        box.addView(rg);

        box.addView(sectionLabel("Rodinný kalendář"));
        TextView calBtn = new TextView(this);
        String cn = p.getString("calName", "");
        calBtn.setText(cn.isEmpty() ? "Vybrat kalendář…" : "📅 " + cn + "  (změnit)");
        calBtn.setTextColor(Color.parseColor("#2F6FD0"));
        calBtn.setPadding(0, dp(6), 0, dp(6));
        box.addView(calBtn);

        Switch auto = new Switch(this);
        auto.setText("Nové dny přidávat do kalendáře automaticky");
        auto.setChecked(p.getBoolean("autoCal", false));
        box.addView(auto);
        box.addView(hint("Zapni jen na jednom telefonu – jinak by se události v rodinném kalendáři zdvojily."));

        box.addView(sectionLabel("Baterie (Xiaomi / Poco)"));
        box.addView(hint("Aby kontrola běžela i na pozadí, povol v nastavení aplikace Autostart a u baterie zvol „Bez omezení“."));
        TextView bat = new TextView(this);
        bat.setText("Otevřít nastavení aplikace");
        bat.setTextColor(Color.parseColor("#2F6FD0"));
        bat.setPadding(0, dp(6), 0, dp(6));
        bat.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {
            }
        });
        box.addView(bat);

        TextView test = new TextView(this);
        test.setText("Poslat zkušební upozornění");
        test.setTextColor(Color.parseColor("#2F6FD0"));
        test.setPadding(0, dp(6), 0, dp(6));
        test.setOnClickListener(v -> Sync.notify(this, 9, "Zkušební upozornění", "Takhle přijde zpráva o novém úkolu."));
        box.addView(test);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Nastavení")
                .setView(sv)
                .setPositiveButton("Uložit", (x, y) -> {
                    int h = 1;
                    View sel = rg.findViewById(rg.getCheckedRadioButtonId());
                    if (sel != null && sel.getTag() instanceof Integer) h = (Integer) sel.getTag();
                    String u = url.getText().toString().trim();
                    p.edit().putString("url", u.equals(Store.DEFAULT_URL) ? "" : u)
                            .putInt("hours", h)
                            .putBoolean("autoCal", auto.isChecked())
                            .apply();
                    try {
                        Sync.schedule(this);
                    } catch (Exception ignored) {
                    }
                    if (auto.isChecked() && p.getLong("calId", -1) <= 0) pickCalendar(() -> refresh(false));
                    else if (auto.isChecked()) refresh(false);
                })
                .setNegativeButton("Zrušit", null)
                .create();
        calBtn.setOnClickListener(v -> {
            dlg.dismiss();
            pickCalendar(this::showSettings);
        });
        dlg.show();
    }
}
