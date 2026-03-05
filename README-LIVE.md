# Processing Live

A fork of [Processing 4](https://github.com/processing/processing4) that implements Bret Victor's ["Inventing on Principle"](https://vimeo.com/36579366) vision — immediate, live feedback as you write code — plus seamless Arduino hardware integration.

Built for students in a Fundamentals of Computing course who use local machines, terminals, git, and Arduino.

## What's Different From Processing 4

This fork adds two major systems on top of the standard Processing IDE:

### 1. Live Mode

Live mode gives you instant visual feedback as you write code. No more edit-save-run-check cycles.

**Features:**
- **Live Preview** — The sketch auto-recompiles and relaunches ~500ms after you stop typing. Toggle with the **Live** toolbar button or **Cmd+L**.
- **Number Scrubbing** — Hold **Alt/Option** and drag on any number in your code to change it in real-time. The sketch updates as you drag.
- **Timeline Controls** — Pause, step forward/backward through frames, and restart. Appears at the bottom of the editor in live mode.
- **Variable Inspector** — A side panel shows all global variable values updating in real-time. Click values to edit them directly, or Alt+drag numeric values to scrub.
- **Inline Errors** — Error messages appear right at the end of the offending line with red/blue background tints. No need to check the console.
- **Snapshot Scrubbing** — When paused, scrubbing a variable only changes that value. Other variables (like position from `x++`) stay frozen so you can explore one parameter at a time.

### 2. Arduino Integration

Control Arduino boards directly from Processing sketches using a familiar API. No Arduino IDE or C++ required.

**Features:**
- **Arduino Library** — `import processing.arduino.*;` gives you `pinMode()`, `digitalRead()`, `digitalWrite()`, `analogRead()`, `analogWrite()`, `servoWrite()` — all the familiar Arduino functions.
- **Auto-Detection** — Boards are detected automatically by USB vendor ID (Arduino, SparkFun, Adafruit, CH340, FTDI, etc.) and port name patterns across macOS, Linux, and Windows.
- **Toolbar Button** — Click the Arduino icon to select a port or use auto-detect.
- **One-Click Firmware Upload** — **Debug > Arduino > Upload StandardFirmata** downloads `arduino-cli` if needed and uploads the required Firmata firmware to your board. Supports Uno, Mega, and other boards with auto-detection.
- **Example Sketches** — Five examples available under **File > Examples > Arduino**: AnalogRead, Blink, Button, Servo, and Fade.

**How it works:** The Arduino must be running [StandardFirmata](https://docs.arduino.cc/libraries/firmata/) firmware, which lets Processing communicate with it over USB using the Firmata protocol. The library wraps [firmata4j](https://github.com/kurbatov/firmata4j) with a Processing-friendly API and uses [jSerialComm](https://fazecast.github.io/jSerialComm/) for cross-platform serial (including Apple Silicon).

## Quick Start

### Build and Run

```bash
# Requires JDK 17
JAVA_HOME=.jdk/jdk-17.0.15+6/Contents/Home ./gradlew build -x :java:gradle:test
JAVA_HOME=.jdk/jdk-17.0.15+6/Contents/Home ./gradlew run
```

### Try Live Mode

1. Open Processing and write a sketch
2. Click the **Live** button in the toolbar (or press **Cmd+L**)
3. Edit your code — the sketch updates automatically
4. Hold **Alt** and drag on a number to scrub it live
5. Use the timeline controls at the bottom to pause, step, and restart

### Try Arduino

1. Plug in an Arduino board
2. Go to **Debug > Arduino > Upload StandardFirmata** (one-time setup)
3. Open **File > Examples > Arduino > BlinkExample**
4. Click Run

```processing
import processing.arduino.*;

Arduino arduino;

void setup() {
  size(300, 200);
  arduino = new Arduino(this);
  arduino.pinMode(13, Arduino.OUTPUT);
}

void draw() {
  // Blink the LED every second
  boolean on = (millis() / 1000) % 2 == 0;
  arduino.digitalWrite(13, on ? Arduino.HIGH : Arduino.LOW);

  background(on ? color(255, 200, 0) : 50);
  textAlign(CENTER, CENTER);
  textSize(24);
  text(on ? "LED ON" : "LED OFF", width/2, height/2);
}
```

## Files Changed

### Live Mode

| File | Changes |
|------|---------|
| `java/src/processing/mode/java/JavaEditor.java` | Live mode toggle, debounced auto-relaunch, timeline/variable panel wiring, Arduino port selection UI |
| `java/src/processing/mode/java/JavaTextAreaPainter.java` | Number scrubbing (Alt+drag), variable value overlay painting |
| `java/src/processing/mode/java/JavaToolbar.java` | Live and Arduino toolbar buttons |
| `java/src/processing/mode/java/JavaMode.java` | `handleLiveLaunch()` — code injection, build, restore pipeline; Arduino example category |
| `java/src/processing/mode/java/ErrorChecker.java` | Configurable debounce delay (300ms in live mode, 650ms normal) |
| `java/src/processing/mode/java/runner/Runner.java` | Pass Arduino port as JVM system property |
| `app/src/processing/app/syntax/PdeTextAreaPainter.java` | Inline error messages, colored line backgrounds |
| `settings.gradle.kts` | Register Arduino library module |
| `java/build.gradle.kts` | Add Arduino to libraries array |

### New Files — Live Mode

| File | Purpose |
|------|---------|
| `java/src/processing/mode/java/livemode/LiveCodeInjector.java` | Injects UDP infrastructure + variable reporting into sketches before compilation |
| `java/src/processing/mode/java/livemode/LiveModeController.java` | Editor-side UDP for timeline commands + frame counter |
| `java/src/processing/mode/java/livemode/TimelinePanel.java` | Pause/play/step/restart buttons + frame counter |
| `java/src/processing/mode/java/livemode/LiveVariablePanel.java` | Side panel showing variable values with click-to-edit and Alt+drag scrubbing |
| `java/src/processing/mode/java/livemode/VariableInspector.java` | Receives variable values from running sketch via UDP |
| `build/shared/lib/toolbar/live.svg` | Toolbar icon for live mode |

### New Files — Arduino Library

| File | Purpose |
|------|---------|
| `java/libraries/arduino/src/processing/arduino/Arduino.java` | Student-facing API: `pinMode`, `digitalRead`, `digitalWrite`, `analogRead`, `analogWrite`, `servoWrite` |
| `java/libraries/arduino/src/processing/arduino/BoardDetector.java` | USB vendor ID and port name pattern detection for Arduino boards |
| `java/libraries/arduino/src/processing/arduino/JSerialCommTransport.java` | jSerialComm transport for firmata4j (replaces JSSC for Apple Silicon support) |
| `java/libraries/arduino/src/processing/arduino/FirmataUploader.java` | Downloads arduino-cli and uploads StandardFirmata firmware |
| `java/libraries/arduino/build.gradle.kts` | Build config with firmata4j, jSerialComm, SLF4J dependencies |
| `java/libraries/arduino/library.properties` | Processing library metadata |
| `build/shared/lib/toolbar/arduino.svg` | Toolbar icon for Arduino |
| `build/shared/modes/java/examples/Arduino/` | Five example sketches |

## Architecture

### Live Mode Pipeline

1. User toggles live mode (Cmd+L or toolbar button)
2. `LiveCodeInjector` injects UDP communication code into the sketch source
3. Modified sketch is compiled by `JavaBuild`, then original source is restored
4. Sketch launches in a separate JVM and communicates with the editor via two UDP channels:
   - **Control channel**: editor sends pause/resume/step commands, sketch sends frame count
   - **Variable channel**: sketch sends variable name/value pairs each frame
5. On code change, editor waits 500ms, kills current sketch, re-injects, recompiles, relaunches

### Arduino Pipeline

1. Processing sketch calls `new Arduino(this)` which connects via Firmata protocol
2. `JSerialCommTransport` opens the serial port and waits for Arduino bootloader to finish
3. `FirmataDevice` (from firmata4j) handles the Firmata protocol handshake
4. `Arduino.java` exposes familiar methods that map to Firmata pin operations
5. The editor can pass a user-selected port via `-Dprocessing.arduino.port` system property
