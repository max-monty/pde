# Processing Live — Bret Victor Vision for Processing 4

A fork of Processing 4 implementing Bret Victor's "Inventing on Principle" vision: immediate, live feedback as students write code.

## Quick Start

```bash
pde
```

Or manually:

```bash
cd /Users/mmontgomery14/Developer/playground/processing4
JAVA_HOME=.jdk/jdk-17.0.15+6/Contents/Home ./gradlew run
```

## Building

```bash
cd /Users/mmontgomery14/Developer/playground/processing4
JAVA_HOME=.jdk/jdk-17.0.15+6/Contents/Home ./gradlew build -x :java:gradle:test
```

## Running Tests

The automated test suite covers all 5 features (21 tests):

```bash
cd /Users/mmontgomery14/Developer/playground/processing4
CLASSPATH=$(JAVA_HOME=.jdk/jdk-17.0.15+6/Contents/Home ./gradlew -q app:printClasspath) \
  .jdk/jdk-17.0.15+6/Contents/Home/bin/java -cp "$CLASSPATH" /tmp/TestFeatures.java
```

Expected output: `Results: 21 passed, 0 failed`

### What the tests cover

| Feature | Tests | What's tested |
|---------|-------|---------------|
| Live Preview | 8 | Code injection into setup/draw, global variable detection, draw-only sketches, static sketch rejection, import handling, preprocessor compatibility, braces-in-strings, exit cleanup |
| Number Scrubbing | 5 | Integer detection, float detection, negative number detection, edge cases (spaces, strings, comments), hex/long literal rejection |
| Timeline Scrubbing | 3 | Controller start/stop lifecycle, UDP command protocol (pause/resume/step/seek), frame count reporting |
| State Visualization | 4 | Inspector start/stop lifecycle, variable update reception via UDP, batch message parsing, clear functionality |
| Instant Error Feedback | 1 | Preprocessor resilience with malformed input |

## Features & How to Use Them

### 1. Live Preview

**Toggle:** Click the **Live** button in the toolbar (concentric circles icon) or press **Cmd+L**. Also in **Debug** menu > **Live Preview**.

**What it does:** The sketch auto-recompiles and relaunches ~500ms after you stop typing. No need to press Run.

**What you should see:**
- Timeline panel appears at the bottom of the editor
- Sketch window stays open and updates as you edit
- If code has errors, the last good sketch keeps running (canvas never goes blank)

### 2. Number Scrubbing

**How:** Hold **Alt/Option** and hover over any numeric literal. Then **Alt+click and drag** left/right.

**What you should see:**
- Dashed blue underline on the number when hovering
- Small "drag" tooltip above the number
- Cursor changes to horizontal resize arrow
- While dragging, the number updates in real-time
- Integers change by 1 per pixel, floats by their smallest decimal place

**Note:** Hex literals (`0xFF0000`) and long literals (`100L`) are intentionally ignored.

### 3. Timeline Scrubbing

**Where:** Dark panel at the bottom of the editor (visible in live mode).

**What you should see:**
- Pulsing red "LIVE" indicator on the left
- Playback buttons (back, pause/play, forward)
- Timeline track with red thumb — drag to scrub through frames
- Frame counter on the right

### 4. State Visualization (Inline Variable Values)

**How:** Activates automatically in live mode for sketches with global variables.

**What you should see:** Dark pill-shaped annotations in the right margin showing variable values in real-time.

- Green = numbers, Amber = strings, Cyan = booleans
- Colored left accent bar matches value type
- Values update every frame

**Test sketch:**

```processing
int x = 0;
float speed = 2.5;
boolean growing = true;

void setup() {
  size(400, 300);
}

void draw() {
  background(0);
  x++;
  if (x > width) x = 0;
  ellipse(x, 150, 30, 30);
}
```

You should see `x -> 0`, `speed -> 2.5`, `growing -> true` updating live.

### 5. Instant Error Feedback

**How:** Just type — errors appear as you type.

**What you should see:**
- Red squiggly underlines on problematic code
- Subtle red background tint on error lines
- Inline error messages in small red text at the end of lines
- Blue underlines/tint for warnings
- Hover for full error tooltip

## Architecture

### Modified Files

| File | What it does |
|------|-------------|
| `java/src/processing/mode/java/JavaEditor.java` | Live mode toggle, debounced relaunch, timeline/variable wiring |
| `java/src/processing/mode/java/JavaTextAreaPainter.java` | Number scrubbing interaction + hover highlight, variable overlay painting |
| `java/src/processing/mode/java/JavaToolbar.java` | Live button in toolbar |
| `java/src/processing/mode/java/JavaMode.java` | `handleLiveLaunch()` — inject, build, restore |
| `java/src/processing/mode/java/livemode/LiveCodeInjector.java` | Injects UDP timeline + variable reporting code into sketches |
| `java/src/processing/mode/java/livemode/LiveModeController.java` | Editor-side UDP controller for timeline commands |
| `java/src/processing/mode/java/livemode/VariableInspector.java` | Receives variable values from running sketch via UDP |
| `java/src/processing/mode/java/livemode/TimelinePanel.java` | Custom-painted timeline scrubber panel |
| `app/src/processing/app/syntax/PdeTextAreaPainter.java` | Inline error messages, error line background tints |
| `build/shared/lib/toolbar/live.svg` | Toolbar icon for live mode |

### How Live Mode Works

1. User toggles live mode (Cmd+L or toolbar button)
2. Editor injects UDP communication code into the sketch via `LiveCodeInjector`
3. Sketch is built and launched in a separate JVM
4. Sketch sends frame counts back to editor via UDP (timeline)
5. Sketch sends variable values back via UDP (state visualization)
6. Editor sends pause/resume/step/seek commands to sketch via UDP
7. On code change, editor waits 500ms, then stops and relaunches with new code
8. Original source is always restored after build (editor never sees injected code)
