/*
 * Phonebot – ESP32 Firmware
 * ---------------------------------------------------------------------------
 * Rolle des ESP32: NUR die Motoren ansteuern. Keine Bildverarbeitung, keine
 * Fahralgorithmen. Das "Denken" (Kamera, Linienerkennung, Programm) passiert
 * auf dem Telefon; der ESP32 bekommt über USB-Serial fertige Sollwerte.
 *
 * ZIELCHIP: ESP32-C3 (nativer USB, VID:PID 303a:1001), Arduino-ESP32-Core 3.x.
 *   - Serial läuft über das native USB (Board-Option "USB CDC On Boot = cdc").
 *     Nur so sieht die Android-App (usb-serial-for-android) die Ausgaben.
 *   - LEDC-API ist die 3.x-Variante: ledcAttach(pin, freq, bits) + ledcWrite(pin, duty).
 *     (Der klassische ESP32/S3 mit Core 2.x nutzte ledcSetup/ledcAttachPin.)
 *
 * Hardware:
 *   - ESP32-C3-Board mit nativem USB
 *   - Motortreiber TB6612FNG
 *   - 2 Getriebemotoren
 *   - Motorstrom aus separater Quelle (Akku), NICHT aus dem Telefon-USB.
 *     Grund: 250–500 mA vom OTG-Port reichen für zwei Motoren (v.a. Anlauf)
 *     nicht aus. Telefon liefert nur Logik/Befehle, der Akku den Fahrstrom.
 *
 * Verdrahtung TB6612FNG  ->  ESP32-C3 (GPIO). Diese Pins sind gültige C3-GPIOs;
 * gemieden: 18/19 (natives USB), 2/8/9 (Strapping), 20/21 (UART0).
 *   PWMA  -> 0       AIN1 -> 1      AIN2 -> 3      (linker Motor)
 *   PWMB  -> 4       BIN1 -> 5      BIN2 -> 6      (rechter Motor)
 *   STBY  -> 7       (HIGH = Treiber aktiv)
 *   VM    -> Motorakku +     VCC -> 3V3 (Logik)     GND gemeinsam!
 *
 * Protokoll: siehe docs/PROTOCOL.md  (ein Befehl pro Zeile, mit '\n')
 * ---------------------------------------------------------------------------
 */

// ---------------- Pin-Belegung (ESP32-C3) ----------------
const int PIN_PWMA = 0, PIN_AIN1 = 1, PIN_AIN2 = 3;      // links
const int PIN_PWMB = 4, PIN_BIN1 = 5, PIN_BIN2 = 6;      // rechts
const int PIN_STBY = 7;

// ---------------- LEDC (PWM) ----------------
const int PWM_FREQ = 20000;          // 20 kHz: oberhalb des Hörbereichs
const int PWM_BITS = 8;              // 8 Bit -> Werte 0..255 (passt zum Protokoll)
// Core 3.x: Kanäle werden automatisch vergeben, ledcWrite adressiert den PIN.

// ---------------- Kommunikations-Watchdog ----------------
// Hört das Telefon auf zu senden (App abgestürzt, Kabel ab), muss der Roboter
// von selbst stoppen statt unkontrolliert weiterzufahren.
const unsigned long CMD_TIMEOUT_MS = 400;
unsigned long lastCmdMs = 0;

// ---------------- Zustand ----------------
int speedLeft = 0, speedRight = 0;   // -255..255 (Vorzeichen = Drehrichtung)
bool autoMode = true;                // informativer Modus (MANUAL/AUTO)

// Zeilenpuffer für nicht-blockierendes Einlesen
char lineBuf[64];
uint8_t lineLen = 0;

// ---------------------------------------------------------------------------
void setup() {
  Serial.begin(115200);              // bei CDCOnBoot=cdc: natives USB-CDC

  pinMode(PIN_AIN1, OUTPUT); pinMode(PIN_AIN2, OUTPUT);
  pinMode(PIN_BIN1, OUTPUT); pinMode(PIN_BIN2, OUTPUT);
  pinMode(PIN_STBY, OUTPUT);
  digitalWrite(PIN_STBY, HIGH);      // Treiber aktivieren

  // LEDC-API des ESP32-Arduino-Core 3.x: Frequenz/Auflösung direkt am Pin.
  ledcAttach(PIN_PWMA, PWM_FREQ, PWM_BITS);
  ledcAttach(PIN_PWMB, PWM_FREQ, PWM_BITS);

  stopMotors();
  lastCmdMs = millis();
}

// ---------------------------------------------------------------------------
void loop() {
  readSerial();                      // nicht blockierend: liest vorhandene Zeichen

  // Watchdog: zu lange kein Befehl -> anhalten (Sicherheit).
  if (millis() - lastCmdMs > CMD_TIMEOUT_MS) {
    stopMotors();
  }
}

// ---------------- Motoransteuerung ----------------
// value: -255..255. Betrag = PWM, Vorzeichen = Drehrichtung.
void driveMotor(int in1, int in2, int pwmPin, int value) {
  value = constrain(value, -255, 255);
  bool forward = value >= 0;
  digitalWrite(in1, forward ? HIGH : LOW);
  digitalWrite(in2, forward ? LOW  : HIGH);
  ledcWrite(pwmPin, abs(value));
}

void applyMotors() {
  driveMotor(PIN_AIN1, PIN_AIN2, PIN_PWMA, speedLeft);
  driveMotor(PIN_BIN1, PIN_BIN2, PIN_PWMB, speedRight);
}

void stopMotors() {
  speedLeft = 0; speedRight = 0;
  ledcWrite(PIN_PWMA, 0); ledcWrite(PIN_PWMB, 0);
  // Kurzschlussbremse (beide Eingänge HIGH) für sauberes, schnelles Stehen:
  digitalWrite(PIN_AIN1, HIGH); digitalWrite(PIN_AIN2, HIGH);
  digitalWrite(PIN_BIN1, HIGH); digitalWrite(PIN_BIN2, HIGH);
}

// ---------------- Serielle Eingabe (nicht blockierend) ----------------
void readSerial() {
  while (Serial.available() > 0) {
    char c = (char)Serial.read();
    if (c == '\n' || c == '\r') {
      if (lineLen > 0) {
        lineBuf[lineLen] = '\0';
        handleCommand(lineBuf);
        lineLen = 0;
      }
    } else if (lineLen < sizeof(lineBuf) - 1) {
      lineBuf[lineLen++] = c;
    } else {
      lineLen = 0;                   // Überlauf -> Zeile verwerfen
    }
  }
}

// ---------------- Befehlsinterpreter ----------------
// Unterstützt: SET,LEFT,v  SET,RIGHT,v  STOP  MODE,AUTO  MODE,MANUAL  PING  STATUS
void handleCommand(char *cmd) {
  lastCmdMs = millis();              // jeder gültige Empfang füttert den Watchdog

  // Token per strtok zerlegen (kommagetrennt)
  char *t1 = strtok(cmd, ",");
  if (t1 == nullptr) return;

  if (strcmp(t1, "SET") == 0) {
    char *side = strtok(nullptr, ",");
    char *valS = strtok(nullptr, ",");
    if (side && valS) {
      int v = atoi(valS);
      if      (strcmp(side, "LEFT")  == 0) speedLeft  = v;
      else if (strcmp(side, "RIGHT") == 0) speedRight = v;
      applyMotors();
      Serial.println("ACK");
    }
  } else if (strcmp(t1, "STOP") == 0) {
    stopMotors();
    Serial.println("ACK");
  } else if (strcmp(t1, "MODE") == 0) {
    char *m = strtok(nullptr, ",");
    if (m) {
      autoMode = (strcmp(m, "AUTO") == 0);
      if (!autoMode) stopMotors();   // beim Wechsel in MANUAL sicher anhalten
      Serial.println("ACK");
    }
  } else if (strcmp(t1, "PING") == 0) {
    Serial.println("PONG");
  } else if (strcmp(t1, "STATUS") == 0) {
    Serial.print("STATUS LEFT="); Serial.print(speedLeft);
    Serial.print(" RIGHT=");      Serial.println(speedRight);
  }
  // Unbekannte Befehle werden still ignoriert.
}
