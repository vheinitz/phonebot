# Phonebot – Kommunikationsprotokoll (Telefon ↔ ESP32)

Einfaches, textbasiertes Protokoll über USB-Serial. Ein Befehl pro Zeile,
abgeschlossen mit `\n`. Bewusst menschenlesbar, damit man es mit einem seriellen
Terminal von Hand testen kann.

- **Baudrate:** 115200, 8N1
- **Richtung der Befehle:** Telefon → ESP32
- **Richtung der Antworten:** ESP32 → Telefon
- **Wertebereich Geschwindigkeit:** `-255 … 255` (Betrag = PWM, Vorzeichen = Drehrichtung)

## Befehle (Telefon → ESP32)

| Befehl              | Bedeutung                                              |
|---------------------|--------------------------------------------------------|
| `SET,LEFT,120`      | Linkes Rad auf Sollwert 120 setzen                     |
| `SET,RIGHT,135`     | Rechtes Rad auf Sollwert 135 setzen                    |
| `SET,LEFT,-80`      | Linkes Rad rückwärts (für Drehen auf der Stelle)       |
| `STOP`              | Beide Motoren sofort anhalten (Bremse)                 |
| `MODE,AUTO`         | Autonomer Modus (App regelt via Kamera/PID)            |
| `MODE,MANUAL`       | Handbetrieb; ESP32 hält beim Umschalten an             |
| `PING`              | Verbindungstest                                        |
| `STATUS`            | Aktuelle Sollwerte abfragen                            |

## Antworten (ESP32 → Telefon)

| Antwort                        | Auslöser                         |
|--------------------------------|----------------------------------|
| `ACK`                          | Befehl angenommen (SET/STOP/MODE)|
| `PONG`                         | Antwort auf `PING`               |
| `STATUS LEFT=120 RIGHT=135`    | Antwort auf `STATUS`             |

## Sicherheit: Kommunikations-Timeout (Watchdog)

Empfängt der ESP32 **länger als 400 ms keinen gültigen Befehl**, stoppt er die
Motoren selbständig. So bleibt der Roboter nicht unkontrolliert in Bewegung,
wenn die App abstürzt oder das Kabel sich löst. Die App sendet deshalb auch im
Stillstand regelmäßig (z. B. alle 100 ms) ihren aktuellen Sollwert oder `PING`.

## Beispiel-Sitzung

```
→ MODE,AUTO
← ACK
→ SET,LEFT,120  SET,RIGHT,135
← ACK
← ACK
→ SET,LEFT,118  SET,RIGHT,132
← ACK
← ACK
→ STOP
← ACK
```

> Hinweis: Der Simulator in `simulator/` erzeugt genau diesen `SET,LEFT/RIGHT`-Strom
> im Protokoll-Panel — man sieht dort live, was die App an den ESP32 schicken würde.
