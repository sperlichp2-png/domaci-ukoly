#!/usr/bin/env python3
"""Hlídání stránek třídy 1.B (ZŠ Mlýnská) pro Home Assistant.

Použití:  python3 /config/skola/ukoly.py ukoly|nemocne
Vypíše JSON pro command_line senzor. Jen standardní knihovna Pythonu.
"""
import datetime as dt
import hashlib
import html as htmllib
import json
import os
import re
import sys
import unicodedata
import urllib.parse
import urllib.request

URLS = {
    "ukoly": "https://sites.google.com/zsmlynska.cz/1b-halickova-2026/dom%C3%A1c%C3%AD-%C3%BAkoly?authuser=0",
    "nemocne": "https://sites.google.com/zsmlynska.cz/1b-halickova-2026/u%C4%8Divo-pro-nemocn%C3%A9-a-nep%C5%99%C3%ADtomn%C3%A9-d%C4%9Bti?authuser=0",
}
DIR = os.path.dirname(os.path.abspath(__file__))
KEEP_DAYS = 12          # kolik posledních dnů poslat do HA
DAYS_CZ = ["pondělí", "úterý", "středa", "čtvrtek", "pátek", "sobota", "neděle"]
DAYS_SHORT = ["Po", "Út", "St", "Čt", "Pá", "So", "Ne"]

F = re.I | re.U
WD = r"(pondělí|pondeli|úterý|utery|středa|streda|čtvrtek|ctvrtek|pátek|patek|sobota|neděle|nedele|po|út|ut|st|čt|ct|pá|pa|so|ne)"
DAY = re.compile(r"^" + WD + r"?[.,:]?\s*(\d{1,2})\s*\.\s*(\d{1,2})\s*\.?(?:\s*(\d{4})\b)?\s*[:\-–—]?\s*(.*)$", F)
WEEK = re.compile(r"(\d{1,2})\.\s*(?:(\d{1,2})\.)?\s*[–—-]\s*(\d{1,2})\.\s*(\d{1,2})\.")
SUBJ = re.compile(r"^(Aktuální informace pro rodiče|Informace pro rodiče|Čtení a psaní|Čtení|Cteni|ČJ|CJ|Čj|Český jazyk|Psaní|Psani|Matematika|Mat\.?|M|PRV|Prvouka|AJ|Angličtina|Anglictina|PČ/VV/TV/HV|PČ|VV|TV|HV|Info|INFO|Informace|Pozn\.?|Poznámka|Upozornění|Video)\s*[:\-–—]\s*(.*)$", F)
URL_RE = re.compile(r"https?://[^\s<>\"]+")
IGNORE = {"domácí úkoly", "domovská stránka", "učivo pro nemocné a nepřítomné děti", "archiv",
          "skip to main content", "skip to navigation", "přeskočit na hlavní obsah", "přeskočit na navigaci",
          "search this site", "vyhledávání na tomto webu", "embedded files", "vložené soubory",
          "google sites", "copy heading link", "kopírovat odkaz na nadpis", "search", "hledat"}
STOP = ["archiv", "report abuse", "nahlásit zneužití", "google sites", "page details",
        "podrobnosti o stránce", "page updated", "stránka byla aktualizována"]


def strip(s):
    return "".join(c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn").lower()


def norm_subject(s):
    n = strip(s).replace(".", "").strip()
    return {"cj": "ČJ", "cesky jazyk": "ČJ", "cteni": "Čtení", "cteni a psani": "Čtení a psaní", "psani": "Psaní",
            "m": "M", "mat": "M", "matematika": "M", "prv": "PRV", "prvouka": "PRV", "aj": "AJ", "anglictina": "AJ",
            "hv": "HV", "vv": "VV", "pc": "PČ", "tv": "TV", "pc/vv/tv/hv": "PČ/VV/TV/HV"}.get(n, "Info")


def guess_subject(s):
    n = strip(s)
    if "matysek" in n or "matematik" in n:
        return "M"
    if "ziva abeceda" in n or re.search(r"\bza\b", n) or "slabik" in n or "cten" in n:
        return "ČJ"
    if "moje prvni psani" in n or re.search(r"\bmpp\b", n) or "pisank" in n:
        return "Psaní"
    if "prvouk" in n:
        return "PRV"
    return ""


def weekday(wd):
    if not wd:
        return -1
    w = strip(wd)
    for i, p in enumerate(["po", "ut", "st", "ct", "pa", "so", "ne"]):
        if w.startswith(p):
            return i
    return -1


def resolve_date(d, m, y, wd, today):
    want = weekday(wd)
    years = [y] if y else [today.year, today.year - 1, today.year + 1]
    best, score = None, None
    for yy in years:
        try:
            c = dt.date(yy, m, d)
        except ValueError:
            continue
        sc = abs((c - today).days) + (0 if want < 0 or c.weekday() == want else 100000)
        if score is None or sc < score:
            best, score = c, sc
    return best


def clean_url(u):
    m = re.match(r"^https?://(?:www\.)?google\.[a-z.]+/url\?(?:.*&)?q=([^&]+)", u)
    return urllib.parse.unquote(m.group(1)) if m else u


def html_to_text(h):
    s = re.sub(r"(?is)<(script|style|noscript|svg|head|template)\b[^>]*>.*?</\1\s*>", " ", h)

    def a_rep(m):
        url = clean_url(htmllib.unescape(m.group(1)))
        inner = m.group(2)
        bad = ("sites.google.com", "accounts.google.com", "support.google.com", "policies.google.com", "google.com/intl")
        if url.startswith("http") and not any(b in url for b in bad) and url not in inner:
            return inner + " " + url + " "
        return inner

    s = re.sub(r"(?is)<a\b[^>]*?href\s*=\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a\s*>", a_rep, s)
    s = re.sub(r"(?is)<[^>]*?(?:youtube(?:-nocookie)?\.com/(?:embed/|watch\?v=)|youtu\.be/)([A-Za-z0-9_-]{11})[^>]*>",
               lambda m: "\nVideo: https://www.youtube.com/watch?v=" + m.group(1) + "\n", s)
    s = re.sub(r"(?i)<br\s*/?>", "\n", s)
    s = re.sub(r"(?i)</?(p|div|li|ul|ol|h[1-6]|tr|td|th|section|header|footer|table|article|nav|main|iframe|blockquote)\b[^>]*>", "\n", s)
    s = re.sub(r"(?s)<[^>]*>", "", s)
    return htmllib.unescape(s)


def to_lines(text):
    out, prev = [], None
    for l in text.split("\n"):
        t = re.sub(r"[\t ]+", " ", l.replace(" ", " ").replace("​", "")).strip()
        if t and t != prev:
            out.append(t)
            prev = t
    return out


def add_item(day, line):
    s = re.sub(r"^[•\-–—*·▪◦►‣○●]+\s*", "", line).strip()
    if not s:
        return
    day["raw"].append(s)
    it = {"predmet": "", "text": "", "odkaz": ""}
    u = URL_RE.search(s)
    if u:
        it["odkaz"] = re.sub(r"[).,;:]+$", "", u.group())
        s = re.sub(r"\s{2,}", " ", s[:u.start()] + " " + s[u.end():]).strip()
    video = False
    m = SUBJ.match(s)
    if m:
        video = strip(m.group(1)) == "video"
        it["predmet"] = norm_subject(m.group(1))
        s = m.group(2).strip()
    s = re.sub(r"[\s:–—-]+$", "", s).strip()
    if not it["predmet"]:
        it["predmet"] = guess_subject(s)
    if not s:
        if not it["odkaz"]:
            return
        s = "Video" if video else "Odkaz"
    it["text"] = s
    for o in day["polozky"]:
        if (o["text"], o["predmet"], o["odkaz"]) == (it["text"], it["predmet"], it["odkaz"]):
            return
        if it["odkaz"] and it["odkaz"] == o["odkaz"]:
            return
    day["polozky"].append(it)


def parse(h, today):
    lines = to_lines(html_to_text(h))
    days, week, cur = {}, "", None
    for line in lines:
        low = line.lower()
        if days and any(low == s or (low.startswith(s) and len(low) < 60) for s in STOP):
            break
        if low in IGNORE or len(line) > 600:
            continue
        m = DAY.match(line)
        if m:
            wd, rest = m.group(1), (m.group(5) or "").strip()
            d, mo = int(m.group(2)), int(m.group(3))
            y = int(m.group(4)) if m.group(4) else None
            if (wd or not rest) and 1 <= d <= 31 and 1 <= mo <= 12:
                date = resolve_date(d, mo, y, wd, today)
                if date:
                    key = date.isoformat()
                    cur = days.setdefault(key, {"datum": key, "tyden": week, "polozky": [], "raw": []})
                    rest = re.sub(r"^[:\-–—\s]+", "", rest)
                    if rest:
                        add_item(cur, rest)
                    continue
        if low.startswith(("týden", "tyden")) and len(line) < 80:
            lab = re.sub(r"(?i)^t[ýy]den\s*[:\-–]?\s*", "", line).strip()
            w = WEEK.search(lab)
            week = (w.group(1) + "." + (" " + w.group(2) + "." if w.group(2) else "") + "–" + w.group(3) + ". " + w.group(4) + ".") if w else lab
            cur = None
            continue
        w = WEEK.search(line)
        if len(line) < 160 and w and (w.start() < 3 or "týden" in low or "tyden" in low or "úkoly" in low):
            week = w.group(1) + "." + (" " + w.group(2) + "." if w.group(2) else "") + "–" + w.group(3) + ". " + w.group(4) + "."
            cur = None
            continue
        if cur is not None:
            add_item(cur, line)
    return [d for d in days.values() if d["polozky"] or d["raw"]]


def fetch(url):
    req = urllib.request.Request(url, headers={
        "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
        "Accept-Language": "cs-CZ,cs;q=0.9"})
    with urllib.request.urlopen(req, timeout=40) as r:
        return r.read().decode("utf-8", "replace")


def main():
    kind = sys.argv[1] if len(sys.argv) > 1 else "ukoly"
    now = dt.datetime.now().astimezone()
    today = now.date()
    state_path = os.path.join(DIR, kind + "_stav.json")
    try:
        with open(state_path, encoding="utf-8") as f:
            st = json.load(f)
    except Exception:
        st = {"videne": [], "posledni_novy": "", "nove": []}
    out = {"kontrola": now.isoformat(timespec="seconds"), "chyba": "", "url": URLS[kind]}
    try:
        days = parse(fetch(URLS[kind]), today)
    except Exception as e:
        out.update({"chyba": "Stránku se nepodařilo načíst: %s" % e, "dny": [], "nove": st.get("nove", []),
                    "posledni_novy": st.get("posledni_novy", ""), "pocet_dni": 0})
        print(json.dumps(out, ensure_ascii=False))
        return

    first_run = not st.get("videne")
    seen = set(st.get("videne", []))
    new_items = []
    for d in days:
        dd = dt.date.fromisoformat(d["datum"])
        d["den"] = DAYS_CZ[dd.weekday()]
        d["kratce"] = "%s %d. %d." % (DAYS_SHORT[dd.weekday()], dd.day, dd.month)
        d["dnes"] = dd == today
        for it in d["polozky"]:
            it["id"] = d["datum"] + "_" + hashlib.sha1(strip(it["predmet"] + "|" + it["text"] + "|" + it["odkaz"]).encode()).hexdigest()[:10]
            if it["id"] not in seen:
                seen.add(it["id"])
                if not first_run:
                    new_items.append(dict(it, datum=d["datum"], kratce=d["kratce"]))
        del d["raw"]
    if new_items:
        st["posledni_novy"] = now.isoformat(timespec="seconds")
        st["nove"] = new_items
    elif first_run:
        st["posledni_novy"] = "start"
    st["videne"] = sorted(seen)[-2000:]
    try:
        with open(state_path, "w", encoding="utf-8") as f:
            json.dump(st, f, ensure_ascii=False)
    except Exception as e:
        out["chyba"] = "Nelze uložit stav: %s" % e

    days.sort(key=lambda d: d["datum"])
    out.update({
        "dny": days[-KEEP_DAYS:],
        "pocet_dni": len(days),
        "nove": st.get("nove", []),
        "posledni_novy": st.get("posledni_novy", ""),
        "chyba": out["chyba"] or ("" if days else "Na stránce jsem nenašel žádné dny – formát se možná změnil."),
    })
    print(json.dumps(out, ensure_ascii=False))


if __name__ == "__main__":
    main()
