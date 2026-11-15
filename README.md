# HA TV Dashboard

Android-TV-App, die das **Standard-Dashboard von Home Assistant** mit der Fernbedienung bedienbar macht – wie auf einer Apple-TV-Box: Eine Karte ist immer aktiv und mit einem Rahmen markiert, das Steuerkreuz wechselt zwischen den Karten.

Läuft auf der **waipu.tv Box** (Android TV 14) und dem **Fire TV Stick 4K** (Fire OS 7/8). Nicht auf Geräten mit Vega OS.

## Bedienung

| Taste | Funktion |
|---|---|
| Steuerkreuz | zwischen den Karten wechseln, die aktive Karte trägt einen Rahmen |
| OK (kurz) | Standardfunktion: Licht/Schalter umschalten, Rollladen auf/zu, Szene/Skript ausführen, Bereich öffnen |
| OK (lang, ≥ 500 ms) | Detailansicht mit Reglern und Schaltflächen |
| OK (doppelt, < 300 ms) | zweite Funktion (siehe unten) |
| Zurück | eine Ebene zurück; auf der Startseite zweimal zum Beenden |

**Doppelklick je Domain:** Licht → 100 %, Rollladen → Stopp, Medien → Play/Pause, Klima → ein/aus, Saugroboter → zur Basis, sonst → alle Entitäten des Geräts.
Auf Karten mit Doppelklick-Funktion wartet ein einfacher Druck 300 ms auf einen möglichen zweiten Druck. Karten ohne Doppelklick reagieren sofort.

**Detailansicht:** links/rechts stellen Helligkeit, Position, Solltemperatur, Lautstärke oder Lüfterstufe ein (gesendet wird, sobald die Taste kurz ruht), OK schaltet.

## Ebenen

Das Standard-Dashboard hat in HA keine gespeicherte Konfiguration, HA erzeugt es automatisch. Die App baut es genauso aus Bereichen, Geräten und Entitäten nach:

1. **Home**: eine Kachel je Bereich (mit „x an“ und Temperatur), Entitäten ohne Bereich nach Domain, Einstellungen
2. **Bereich**: Entitäten nach Domain gruppiert (Licht, Rollläden, Klima …)
3. **Details**: Regler, Schaltflächen und Attribute

Ausgeblendet werden versteckte Entitäten sowie Konfigurations- und Diagnose-Entitäten, wie in HA.

Wenn du das Dashboard in HA später „übernimmst“ und bearbeitest, liest die App stattdessen die gespeicherten Views und Karten (`tile`, `button`, `entities`, `glance`, Stacks, Grids, Sections, `heading`, `area` …) inklusive `tap_action`, `hold_action` und `double_tap_action`.

## Einrichtung

1. App starten. Sie zeigt einen QR-Code.
2. Mit dem Handy scannen (gleiches WLAN) und eintragen:
   - Home-Assistant-Adresse, z. B. `http://homeassistant.local:8123` oder `http://192.168.x.y:8123`
   - Long-Lived Access Token (HA → Profil → Sicherheit → Langlebige Zugangstoken)
   - Rahmenfarbe der aktiven Karte (Standard: HA-Blau)
3. Speichern. Die App verbindet sich sofort.

Tipp: Für den Fernseher einen eigenen HA-Benutzer ohne Admin-Rechte anlegen und dessen Token verwenden.
Die Einstellungen sind später über die Kachel **Einstellungen** auf der Startseite erreichbar.

## Bauen

Voraussetzungen: Android Studio (aktuelle Version) mit Android SDK 35.

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Oder in Android Studio öffnen und *Build → Build APK(s)*.

## Installieren

Auf beiden Geräten zuerst die Entwickleroptionen aktivieren (Einstellungen → Geräteinfo → 7× auf „Build“ bzw. „Fire TV Stick“ klicken) und **ADB-Debugging** einschalten.

```bash
adb connect <IP-der-Box>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Alternativ die APK z. B. per Downloader-App (Fire TV) oder „Send Files to TV“ übertragen und installieren.

## Technik

- Kotlin, Jetpack Compose for TV (`androidx.tv:tv-material`)
- Home-Assistant-WebSocket-API: `auth`, `subscribe_entities` (Live-Zustände), `config/area_registry/list`, `config/device_registry/list`, `config/entity_registry/list`, `lovelace/config`, `call_service`
- OkHttp WebSocket mit automatischem Reconnect
- Einrichtung über NanoHTTPD und QR-Code (ZXing)

| Datei | Inhalt |
|---|---|
| `data/HaClient.kt` | WebSocket-Client, Auth, Zustands-Diffs |
| `data/PageBuilder.kt` | Ebenen aus Registry bzw. Lovelace-Konfiguration |
| `data/Domains.kt` | Standardaktionen (kurz/lang/doppelt), Texte, Icons |
| `ui/PressHandler.kt` | Auswertung kurz/lang/doppelt der OK-Taste |
| `ui/PageScreen.kt` | Kachel-Raster mit Fokusrahmen und Fokus-Wiederherstellung |
| `ui/DetailScreen.kt` | Detailansicht mit Regler |
| `ui/SetupScreen.kt` | QR-Einrichtung |
