# Phonebot

Ein modularer Lernroboter, bei dem ein **Android-Telefon das Gehirn und die
Kamera** ist – und bei dem der **Kern eine kleine Programmierumgebung auf dem
Telefon** ist. Man schreibt einfache, ereignisbasierte **Wenn-Dann-Regeln**
(wie bei Scratch, aber textbasiert), die das Kamerabild auswerten und die
Motoren steuern. Beispiel:

```
if cam.line.left:  mot.left = 110; mot.right = 45
if cam.line.right: mot.left = 45;  mot.right = 110
if cam.line.lost:  mot.left = 0;   mot.right = 0
```

Das Telefon schaut nach unten, leitet aus dem Bild **Kontext-Variablen** ab
(`cam.line.left/center/right`, `cam.line.error`, …) und schickt die vom Programm
berechneten Motorwerte über **USB-OTG** als Textbefehle an einen **ESP32**, der
nur die zwei Getriebemotoren über einen Treiber (TB6612) ansteuert.

Ein Linienfolger ist damit nur **ein mögliches Programm** – nicht fest
verdrahtet. Darum geht es im Projekt (zu ~80 %): Kinder/Schüler **programmieren
das Verhalten selbst**. Später erweiterbar zu einer kleinen
**Robotik-Lernplattform** (Ultraschall, Beschleunigungssensor, Kreuzungen) –
gut für einen Jugend-forscht-Kurs.

> Die Idee stammt aus einem Gespräch (ChatGPT, „Idee eines Roboters auf dem
> Telefon"). Dieses Repository ist der erste Prototyp dazu.

## Grundprinzip – Aufgabenteilung

```
   ┌──────────────── Telefon (Android) ─────────────────┐      ┌──── ESP32 ────┐
   │ Kamera → Kontext (cam.line.*)                        │ USB  │ Protokoll     │   ┌─ TB6612 ─┐
   │ → DEIN PROGRAMM (Wenn-Dann-Regeln) → mot.left/right  │─OTG─▶│ parsen → PWM  │ ▶ │ 2 Motoren│
   │ → SET,LEFT / SET,RIGHT                               │serial│ Watchdog      │   └──────────┘
   └─────────────────────────────────────────────────────┘      └───────────────┘
   Das "Denken" (und das Programm) bleibt auf dem Telefon. Der ESP32 dreht nur die Räder.
```

## Verzeichnisse

| Pfad                 | Inhalt                                                        |
|----------------------|--------------------------------------------------------------|
| `simulator/`         | **Web-Simulator** – läuft sofort im Browser, ohne Hardware   |
| `firmware/esp32/`    | ESP32-Firmware (Arduino/C++): Protokoll, PWM, Watchdog        |
| `app/`               | Android-App (Kotlin): Camera2, OpenCV-Erkennung, Interpreter |
| `docs/PROTOCOL.md`   | USB-Serial-Protokoll Telefon ↔ ESP32                         |

## 1. Simulator starten (die Programmierumgebung)

Der Simulator **ist** die Programmierumgebung zum Ausprobieren – mit virtuellem
Roboter, simulierter Kamera (ROI → Schwellwert → Linienmitte), einem **Editor
für die Wenn-Dann-Sprache** und einem Live-Protokoll der `SET`-Befehle. So kann
man Programme schreiben und testen, **bevor** Hardware gebaut wird.

Einfach die Datei im Browser öffnen:

```
simulator/index.html
```

(Doppelklick genügt – kein Server, kein Internet nötig.) Rechts ein Programm
schreiben (oder ein Beispiel laden), **„Programm starten"** drücken, Bahn wählen.

### Die Mini-Sprache

Blöcke werden **über Einrückung** gebildet (wie in Python): eine Kopfzeile endet
mit `:`, der Körper steht eingerückt darunter. Für kurze Einzeiler dürfen die
Anweisungen auch mit `;` hinter dem `:` stehen. `#` leitet einen Kommentar ein.

```
loop:
  mot.left = 95
  mot.right = 95
  if cam.line.lost:
    mot.left = 0
    mot.right = 0
```

**Abschnitte / Ereignisse** (nur auf oberster Ebene):

| Block        | läuft …                                                    |
|--------------|------------------------------------------------------------|
| `start:`     | einmal beim Start (z. B. Variablen vorbelegen)             |
| `loop:`      | in jedem Takt (Hauptschleife)                              |
| `key w:`     | wenn Taste `w` gedrückt wird (z. B. BT-Tastatur)           |
| `bstop:`     | wenn der Stopp-Knopf gedrückt wird (hält danach an)        |
| `bat_low:`   | wenn der Akku schwach wird                                 |

**Steuerung & Werte:**

| Ausdruck                  | Bedeutung                                               |
|---------------------------|--------------------------------------------------------|
| `mot.left = …`            | Motor setzen (−255…255, − = rückwärts)                 |
| `speed = 120`             | eigene Variable (merkt sich Werte über Takte)          |
| `if … :` / `else:`        | Verzweigung                                            |
| `for i = 1 to 5:`         | Zählschleife (läuft **synchron**, gut zum Rechnen)     |
| `stop`                    | Programm anhalten                                      |
| `and or not`, `< > == …`  | in Bedingungen; Rechnen mit `+ - * /`                  |

**Lesbarer Kontext** (aus dem Kamerabild abgeleitet):

| Variable                              | Bedeutung                                   |
|---------------------------------------|---------------------------------------------|
| `cam.line.found` / `cam.line.lost`    | Linie sichtbar? / verloren?                 |
| `cam.line.left` / `center` / `right`  | Zone, in der die Linie liegt                |
| `cam.line.far_left` / `far_right`     | Linie weit außen (starke Abweichung)        |
| `cam.line.error`                      | Abweichung von der Mitte, normiert −1 … +1 (+ = rechts) |
| `cam.line.x`                          | dasselbe (Alias)                            |

> In der App ist `cam.line.error` **normiert auf −1…+1** (auflösungsunabhängig).
> Der ältere Web-Simulator rechnet intern noch in Kamera-Pixeln; die Beispiele
> dort nutzen daher größere Faktoren. Für die App einen Faktor ~50 wählen.

Ein klassischer Linienfolger oder ein Proportionalregler sind damit nur
jeweils **ein Beispielprogramm** (im Editor über „Beispiel → Laden" wählbar).

> `for` läuft synchron (alle Durchläufe in einem Takt) – gut zum Rechnen, aber
> nicht für zeitlich gestaffelte Bewegungsfolgen. Dafür käme als Nächstes ein
> `wait <sekunden>` mit einer über Takte fortgesetzten Ausführung in Frage.

## 2. Hardware (erster Aufbau)

- Android-Smartphone (gebraucht reicht)
- ESP32-C3/S3 mit nativem USB (die mitgelieferte Firmware ist für den **C3**
  verdrahtet) – oder klassischer ESP32 mit UART-USB-Adapter
- Motortreiber **TB6612FNG**
- 2 Getriebemotoren (langsam, hohes Übersetzungsverhältnis)
- Zweirad-Chassis mit Stützkugel (Caster), 3D-gedruckte Telefonhalterung
- **Separater Motorakku** (z. B. 4×AA oder 1S-LiPo) – der Telefon-USB liefert
  nicht genug Strom für die Motoren, nur die Logik/Befehle.

## 3. Firmware aufspielen

1. Arduino IDE + ESP32-Board-Support installieren.
2. `firmware/esp32/phonebot_firmware.ino` öffnen.
3. Pin-Belegung oben in der Datei an die eigene Verdrahtung anpassen.
4. Board = ESP32-C3 Dev Module (bzw. eigenes), „USB CDC On Boot: Enabled", Port wählen, hochladen.
5. Test im seriellen Monitor (115200 Baud): `PING` senden → `PONG` kommt zurück.
   `SET,LEFT,120` → `ACK`, linkes Rad dreht. Nach 400 ms ohne Befehl stoppt der
   Roboter automatisch (Watchdog).

## 4. Android-App bauen und nutzen

Die App in `app/` ist lauffähig: Sie **programmiert den Roboter über die
Mini-Sprache direkt auf dem Telefon** (Editor + Start/Stop), wertet das
Kamerabild mit **OpenCV** aus und spricht den ESP32 über USB an. Der ESP32 ist
**optional** – ohne ihn läuft alles weiter, nur die Motorbefehle gehen ins Leere.

Aufbau (Hochformat): kleine Kamera-Vorschau (Knopf schaltet klein/voll/aus),
Telemetrie, Knopfleiste, darunter das **Interpreter-Fenster** (Editor).

- **Kamera: pures Camera2.** Preview + YUV-Analysestrom + RAW-ImageReader in einer
  Session. Über das Menü (⋮) sind **Belichtung** (Zeit + ISO, AE aus) und **Fokus**
  (Dioptrien, AF aus) manuell einstellbar – soweit das Gerät `MANUAL_SENSOR` meldet.
- **RAW-Aufnahme → DNG** (Knopf „RAW" / Menü): ein Einzelbild wird als DNG in die
  Galerie (`Pictures/Phonebot/`) gespeichert; der Vorschaustrom läuft dabei durch.
  Kann das Gerät die 3-Stream-Kombi nicht, fällt die App auf Preview+YUV zurück.
- **Erkennung: OpenCV** (`CvLineDetector`): ROI im unteren Bilddrittel, OTSU-
  Schwellwert, Schwerpunkt → `cam.line.*`.

Bauen:

1. In Android Studio öffnen (Projektwurzel = dieses Verzeichnis).
2. Gradle synchronisieren (lädt OpenCV, `usb-serial-for-android` u. a.).
3. Auf dem Telefon Kamera-Berechtigung erlauben; ESP32 (optional) per OTG
   anstecken und die USB-Abfrage bestätigen.
4. Programm schreiben (oder das vorgeladene nehmen), **Start** drücken, Roboter
   auf eine dunkle Linie auf hellem Untergrund setzen.

> Die Mini-Sprache ist dieselbe wie im Simulator (oben dokumentiert). Der
> Interpreter ist nach Kotlin portiert (`Interpreter.kt`) und per JVM-Test
> abgesichert (`app/src/test/java/de/phonebot/InterpreterTest.kt`).

### Kernklassen (in `app/src/main/java/de/phonebot/`)

| Klasse             | Aufgabe                                                        |
|--------------------|----------------------------------------------------------------|
| `Interpreter`      | Mini-Sprache: Tokenizer, Einrückungs-Parser, Laufzeit (Events) |
| `CvLineDetector`   | OpenCV: ROI → OTSU-Schwellwert → Schwerpunkt → Abweichung (px)  |
| `CameraController` | Camera2: Preview/YUV/RAW, manuelle Belichtung/Fokus, DNG       |
| `CommandProtocol`  | baut `SET/STOP/MODE/PING`, rechnet Werte → Radsollwerte         |
| `UsbSerialLink`    | USB-OTG zum ESP32 (CDC-ACM; eigener Prober für 303A:1001)       |
| `MainActivity`     | verbindet Kamera → OpenCV → Interpreter → Protokoll → USB       |

## Ausblick

- Weiße Linie auf dunklem Untergrund, Kurven, Kreuzungen, Verzweigungen
- Hinderniserkennung (Ultraschall am ESP32, Sensorwert zurück ans Telefon)
- einfache Block-/Skript-Programmierung in der App für den Unterricht
