# Domácí úkoly 1.B

Android aplikace, která hlídá stránku třídy 1.B (ZŠ Mlýnská, Google Sites) a:

- řadí úkoly chronologicky po dnech (s týdny), u každého úkolu je poznámka a odškrtnutí „hotovo“,
- upozorní notifikací, když přibude nový úkol (kontrola na pozadí každou 1 / 3 / 6 h),
- 👁 ukáže původní zadání dne, 🌐 otevře originální stránku přímo v aplikaci,
- 📅 zapíše den jako celodenní událost do vybraného kalendáře v telefonu (např. rodinného Google kalendáře);
  volitelně automaticky. Při změně úkolů nebo poznámek se událost aktualizuje.

## Instalace

APK se sestaví automaticky po každém pushi (GitHub Actions) a je ke stažení v sekci **Releases** jako `DomaciUkoly.apk`.
Aplikace je podepsaná pevným klíčem (`app/ukoly.keystore`), takže nové verze jdou nainstalovat přes starou.

## Tipy

- Xiaomi / Poco: v nastavení aplikace povol **Autostart** a baterii **Bez omezení**, jinak MIUI/HyperOS kontrolu na pozadí uspává.
- Automatické přidávání do kalendáře zapni jen na jednom telefonu, aby se události nezdvojovaly.
