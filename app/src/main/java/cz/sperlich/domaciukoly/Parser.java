package cz.sperlich.domaciukoly;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Převede HTML stránky třídy (Google Sites) na seznam dnů s úkoly.
 * Nepoužívá nic z Androidu, takže jde testovat i obyčejnou Javou.
 *
 * Očekávaný tvar textu (zhruba):
 *   Po 9. 11.
 *   ČJ: rozstříhat kartičky ...
 *   M: Matýsek str. 35 ...
 *   Út 10. 11. – ČJ: ...
 */
public class Parser {

    public static class PItem {
        public String subject = "";
        public String text = "";
        public String link = "";
    }

    public static class PDay {
        public String date;          // RRRR-MM-DD
        public String week = "";
        public final List<PItem> items = new ArrayList<>();
        public final List<String> raw = new ArrayList<>();
    }

    public static class Result {
        public final List<PDay> days = new ArrayList<>();
        public String plain = "";
    }

    static final int F = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    static final String WD = "(pondělí|pondeli|úterý|utery|středa|streda|čtvrtek|ctvrtek|pátek|patek|sobota|neděle|nedele|po|út|ut|st|čt|ct|pá|pa|so|ne)";

    static final Pattern DAY = Pattern.compile(
            "^" + WD + "?\\.?,?\\s*(\\d{1,2})\\s*\\.\\s*(\\d{1,2})\\s*\\.?(?:\\s*(\\d{4})\\b)?\\s*[:\\-–—]?\\s*(.*)$", F);

    static final Pattern WEEK = Pattern.compile(
            "(\\d{1,2})\\.\\s*(?:(\\d{1,2})\\.)?\\s*[–—-]\\s*(\\d{1,2})\\.\\s*(\\d{1,2})\\.");

    static final Pattern SUBJ = Pattern.compile(
            "^(Čtení a psaní|Čtení|Cteni|ČJ|CJ|Čj|Český jazyk|Psaní|Psani|Matematika|Mat\\.?|M|PRV|Prvouka|AJ|Angličtina|Anglictina|HV|VV|PČ|TV|Info|INFO|Informace|Pozn\\.?|Poznámka|Upozornění|Video)\\s*[:\\-–—]\\s*(.*)$", F);

    static final Pattern URL = Pattern.compile("https?://[^\\s<>\"]+");

    static final String[] IGNORE = {
            "domácí úkoly", "domovská stránka", "učivo pro nemocné a nepřítomné děti", "archiv",
            "skip to main content", "skip to navigation", "přeskočit na hlavní obsah", "přeskočit na navigaci",
            "search this site", "vyhledávání na tomto webu", "embedded files", "vložené soubory",
            "google sites", "copy heading link", "kopírovat odkaz na nadpis", "search", "hledat"
    };

    static final String[] STOP = {
            "archiv", "report abuse", "nahlásit zneužití", "google sites", "page details",
            "podrobnosti o stránce", "page updated", "stránka byla aktualizována"
    };

    // ------------------------------------------------------------------

    public static Result parse(String html, Calendar now) {
        Result res = new Result();
        List<String> lines = toLines(htmlToText(html));
        Map<String, PDay> byDate = new LinkedHashMap<>();
        Set<String> ignore = new HashSet<>();
        for (String s : IGNORE) ignore.add(s);

        StringBuilder plain = new StringBuilder();
        String week = "";
        PDay cur = null;

        for (String line : lines) {
            String low = line.toLowerCase(Locale.ROOT);
            if (!byDate.isEmpty() && isStop(low)) break;
            if (ignore.contains(low)) continue;
            if (line.length() > 600) continue;
            plain.append(line).append('\n');

            Matcher d = DAY.matcher(line);
            if (d.matches()) {
                String wd = d.group(1);
                String rest = d.group(5) == null ? "" : d.group(5).trim();
                int day = Integer.parseInt(d.group(2));
                int mon = Integer.parseInt(d.group(3));
                Integer year = d.group(4) != null ? Integer.valueOf(d.group(4)) : null;
                boolean header = (wd != null || rest.isEmpty()) && day >= 1 && day <= 31 && mon >= 1 && mon <= 12;
                if (header) {
                    String date = resolveDate(day, mon, year, wd, now);
                    if (date != null) {
                        PDay pd = byDate.get(date);
                        if (pd == null) {
                            pd = new PDay();
                            pd.date = date;
                            pd.week = week;
                            byDate.put(date, pd);
                        }
                        cur = pd;
                        rest = rest.replaceFirst("^[:\\-–—\\s]+", "");
                        if (!rest.isEmpty()) addItem(cur, rest);
                        continue;
                    }
                }
            }

            Matcher w = WEEK.matcher(line);
            if (line.length() < 160 && w.find()
                    && (w.start() < 3 || low.contains("týden") || low.contains("tyden") || low.contains("úkoly"))) {
                String m1 = w.group(2) != null ? w.group(2) + "." : "";
                week = w.group(1) + "." + (m1.isEmpty() ? "" : " " + m1) + "–" + w.group(3) + ". " + w.group(4) + ".";
                cur = null;
                continue;
            }

            if (cur != null) addItem(cur, line);
        }

        for (PDay pd : byDate.values()) {
            if (!pd.items.isEmpty() || !pd.raw.isEmpty()) res.days.add(pd);
        }
        res.plain = plain.toString();
        return res;
    }

    static boolean isStop(String low) {
        for (String s : STOP) {
            if (low.equals(s) || (low.startsWith(s) && low.length() < 60)) return true;
        }
        return false;
    }

    static void addItem(PDay day, String line) {
        String s = line.replaceFirst("^[•\\-–—*·▪◦►‣○●]+\\s*", "").trim();
        if (s.isEmpty()) return;
        day.raw.add(s);

        PItem it = new PItem();
        Matcher u = URL.matcher(s);
        if (u.find()) {
            it.link = u.group().replaceAll("[).,;:]+$", "");
            s = (s.substring(0, u.start()) + " " + s.substring(u.end())).replaceAll("\\s{2,}", " ").trim();
        }
        boolean video = false;
        Matcher sm = SUBJ.matcher(s);
        if (sm.matches()) {
            video = strip(sm.group(1)).equals("video");
            it.subject = normSubject(sm.group(1));
            s = sm.group(2).trim();
        }
        s = s.replaceAll("[\\s:–—-]+$", "").trim();
        if (s.isEmpty()) {
            if (it.link.isEmpty()) return;
            s = video ? "Video" : "Odkaz";
        }
        it.text = s;
        for (PItem o : day.items) {
            if (o.text.equals(it.text) && o.subject.equals(it.subject) && o.link.equals(it.link)) return;
            if (!it.link.isEmpty() && it.link.equals(o.link)) return;
        }
        day.items.add(it);
    }

    static String normSubject(String s) {
        String n = strip(s).replace(".", "").trim();
        switch (n) {
            case "cj": case "cesky jazyk": return "ČJ";
            case "cteni": return "Čtení";
            case "cteni a psani": return "Čtení a psaní";
            case "psani": return "Psaní";
            case "m": case "mat": case "matematika": return "M";
            case "prv": case "prvouka": return "PRV";
            case "aj": case "anglictina": return "AJ";
            case "hv": return "HV";
            case "vv": return "VV";
            case "pc": return "PČ";
            case "tv": return "TV";
            default: return "Info";
        }
    }

    static String strip(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT);
    }

    static int weekday(String wd) {
        if (wd == null) return -1;
        String w = strip(wd);
        if (w.startsWith("po")) return Calendar.MONDAY;
        if (w.startsWith("ut")) return Calendar.TUESDAY;
        if (w.startsWith("st")) return Calendar.WEDNESDAY;
        if (w.startsWith("ct")) return Calendar.THURSDAY;
        if (w.startsWith("pa")) return Calendar.FRIDAY;
        if (w.startsWith("so")) return Calendar.SATURDAY;
        if (w.startsWith("ne")) return Calendar.SUNDAY;
        return -1;
    }

    /** Doplní rok: přednost má rok, kde sedí den v týdnu, a pak nejbližší k dnešku. */
    static String resolveDate(int d, int m, Integer y, String wd, Calendar now) {
        int want = weekday(wd);
        int[] years;
        if (y != null) years = new int[]{y};
        else {
            int ny = now.get(Calendar.YEAR);
            years = new int[]{ny, ny - 1, ny + 1};
        }
        String best = null;
        long bestScore = Long.MAX_VALUE;
        for (int yy : years) {
            Calendar c = Calendar.getInstance();
            c.clear();
            c.setLenient(false);
            try {
                c.set(yy, m - 1, d, 12, 0, 0);
                c.getTimeInMillis();
            } catch (Exception e) {
                continue;
            }
            long diff = Math.abs(c.getTimeInMillis() - now.getTimeInMillis()) / 86400000L;
            boolean match = want < 0 || c.get(Calendar.DAY_OF_WEEK) == want;
            long score = diff + (match ? 0 : 100000);
            if (score < bestScore) {
                bestScore = score;
                best = String.format(Locale.ROOT, "%04d-%02d-%02d", yy, m, d);
            }
        }
        return best;
    }

    // ------------------------------------------------------------------

    static String htmlToText(String html) {
        String s = html.replaceAll("(?is)<(script|style|noscript|svg|head|template)\\b[^>]*>.*?</\\1\\s*>", " ");

        Matcher a = Pattern.compile("(?is)<a\\b[^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a\\s*>").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (a.find()) {
            String url = cleanUrl(decodeEntities(a.group(1)));
            String inner = a.group(2);
            String rep = inner;
            if (url.startsWith("http") && !url.contains("sites.google.com") && !url.contains("accounts.google.com")
                    && !url.contains("support.google.com") && !url.contains("policies.google.com")
                    && !url.contains("google.com/intl") && !inner.contains(url)) {
                rep = inner + " " + url + " ";
            }
            a.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        a.appendTail(sb);
        s = sb.toString();

        Matcher y = Pattern.compile("(?is)<[^>]*?(?:youtube(?:-nocookie)?\\.com/(?:embed/|watch\\?v=)|youtu\\.be/)([A-Za-z0-9_-]{11})[^>]*>").matcher(s);
        sb = new StringBuffer();
        while (y.find()) {
            y.appendReplacement(sb, Matcher.quoteReplacement("\nVideo: https://www.youtube.com/watch?v=" + y.group(1) + "\n"));
        }
        y.appendTail(sb);
        s = sb.toString();

        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</?(p|div|li|ul|ol|h[1-6]|tr|td|th|section|header|footer|table|article|nav|main|iframe|blockquote)\\b[^>]*>", "\n");
        s = s.replaceAll("(?s)<[^>]*>", "");
        return decodeEntities(s);
    }

    static List<String> toLines(String text) {
        List<String> out = new ArrayList<>();
        String prev = null;
        for (String l : text.split("\n")) {
            String t = l.replace('\u00a0', ' ').replace("\u200b", "").replaceAll("[\\t ]+", " ").trim();
            if (t.isEmpty() || t.equals(prev)) continue;
            out.add(t);
            prev = t;
        }
        return out;
    }

    static String cleanUrl(String url) {
        Matcher m = Pattern.compile("^https?://(?:www\\.)?google\\.[a-z.]+/url\\?(?:.*&)?q=([^&]+)").matcher(url);
        if (m.find()) {
            try {
                return URLDecoder.decode(m.group(1), "UTF-8");
            } catch (Exception ignored) {
            }
        }
        return url;
    }

    static String decodeEntities(String s) {
        if (s.indexOf('&') < 0) return s;
        Matcher m = Pattern.compile("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String e = m.group(1);
            String r;
            try {
                if (e.startsWith("#x") || e.startsWith("#X")) r = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
                else if (e.startsWith("#")) r = new String(Character.toChars(Integer.parseInt(e.substring(1))));
                else {
                    switch (e) {
                        case "amp": r = "&"; break;
                        case "lt": r = "<"; break;
                        case "gt": r = ">"; break;
                        case "quot": r = "\""; break;
                        case "apos": r = "'"; break;
                        case "nbsp": r = " "; break;
                        case "ndash": r = "–"; break;
                        case "mdash": r = "—"; break;
                        case "hellip": r = "…"; break;
                        case "bdquo": r = "„"; break;
                        case "ldquo": r = "“"; break;
                        case "rdquo": r = "”"; break;
                        default: r = m.group(); break;
                    }
                }
            } catch (Exception ex) {
                r = m.group();
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(r));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static String sha1(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] b = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
