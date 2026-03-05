package processing.mode.java.livemode;

import java.util.*;
import java.util.regex.*;

import processing.app.SketchCode;
import processing.mode.java.tweak.Handle;


/**
 * Injects live mode support code into a Processing sketch before compilation.
 * This includes:
 * - Timeline control (pause/play/step/seek via UDP)
 * - Variable value reporting (sends variable values back to the editor)
 *
 * The injection modifies the sketch's program text directly, following
 * the same pattern as Tweak mode's automateSketch().
 */
public class LiveCodeInjector {

  /**
   * A detected global variable declaration.
   */
  static class VarDecl {
    String type;
    String name;
    int line;  // 0-based line number in the source

    VarDecl(String type, String name, int line) {
      this.type = type;
      this.name = name;
      this.line = line;
    }
  }


  /**
   * Inject live mode code into the sketch (no tweak support).
   */
  public static void inject(SketchCode[] code, int controlPort, int varPort) {
    inject(code, controlPort, varPort, 0, 0, null);
  }


  /**
   * Inject live mode code into the sketch with optional tweak (hot-swap) support.
   * Modifies code[0].getProgram() in place.
   *
   * @param code the sketch code tabs
   * @param controlPort UDP port the sketch listens on for timeline and tweak commands
   * @param varPort UDP port the editor listens on for variable reports
   * @param numInts number of tweakmode_int array elements (0 if no tweak)
   * @param numFloats number of tweakmode_float array elements (0 if no tweak)
   * @param tweakHandles all tweak handles, or null if no tweak support
   */
  public static void inject(SketchCode[] code, int controlPort, int varPort,
                            int numInts, int numFloats,
                            List<List<Handle>> tweakHandles) {
    String program = code[0].getProgram();

    // Only inject if the sketch has a draw() function (animated mode)
    int drawBodyStart = findDrawBodyStart(program);
    if (drawBodyStart < 0) {
      // Static mode sketch — no animation loop, skip injection
      return;
    }

    // Parse global variable declarations (before setup/draw)
    List<VarDecl> globals = findGlobalVariables(program);

    // Find key positions in the source
    int afterSizePos = findAfterSizePos(program);
    int drawBodyEnd = findDrawBodyEnd(program);

    // Build injection code
    String header = buildHeader(controlPort, varPort, globals,
                                numInts, numFloats, tweakHandles);

    // Build setup injection (after size())
    String setupInjection = "\n    _livemode_init();\n";

    // Build draw start injection — if no setup()/size(), also init here
    // When paused, _livemode_check() still runs (processes commands) but
    // we return early to skip the user's draw code. This keeps the event
    // loop alive so step/resume/seek commands always work.
    String drawStartInjection;
    // Build snapshot save/restore code for scrub-while-paused
    StringBuilder snapRestore = new StringBuilder();
    StringBuilder snapSave = new StringBuilder();
    for (VarDecl var : globals) {
      snapRestore.append("    ").append(var.name).append(" = _livemode_snap_").append(var.name).append(";\n");
      snapSave.append("    _livemode_snap_").append(var.name).append(" = ").append(var.name).append(";\n");
    }
    // Track whether this draw is a scrub-redraw via a local flag, so we can
    // skip saving the snapshot for scrub redraws (we want them to keep restoring
    // from the same baseline).
    String scrubBlock =
      "    boolean _livemode_isScrubRedraw = _livemode_scrubRedraw;\n" +
      "    if (_livemode_scrubRedraw) {\n" +
      snapRestore +
      "      _livemode_scrubRedraw = false;\n" +
      "      _livemode_stepOnce = true;\n" +
      "    }\n";
    String snapshotSave = "    if (!_livemode_isScrubRedraw) {\n" + snapSave + "    }\n";

    if (afterSizePos > 0) {
      drawStartInjection =
        "\n    _livemode_check();\n" +
        scrubBlock +
        "    if (_livemode_paused && !_livemode_stepOnce) { delay(16); return; }\n" +
        "    _livemode_stepOnce = false;\n" +
        snapshotSave;
    } else {
      // No setup() or no size() — init lazily at start of draw()
      drawStartInjection =
        "\n    if (!_livemode_initialized) { _livemode_init(); _livemode_initialized = true; }\n" +
        "    _livemode_check();\n" +
        scrubBlock +
        "    if (_livemode_paused && !_livemode_stepOnce) { delay(16); return; }\n" +
        "    _livemode_stepOnce = false;\n" +
        snapshotSave;
    }

    // Build draw end injection (before closing brace of draw)
    String drawEndInjection = buildDrawEndInjection(globals);

    // Apply injections in reverse order (to preserve positions)
    StringBuilder sb = new StringBuilder(program);

    if (drawBodyEnd > 0) {
      sb.insert(drawBodyEnd, drawEndInjection);
    }

    if (drawBodyStart > 0) {
      sb.insert(drawBodyStart, drawStartInjection);
    }

    if (afterSizePos > 0) {
      sb.insert(afterSizePos, setupInjection);
    }

    // Prepend header
    code[0].setProgram(header + sb.toString());
  }


  /**
   * Find global variable declarations in the sketch code.
   * Looks for type-name patterns before setup() or draw().
   */
  static List<VarDecl> findGlobalVariables(String code) {
    List<VarDecl> vars = new ArrayList<>();

    // Find where setup() or draw() starts (whichever comes first)
    int boundary = code.length();
    int setupPos = findMethodStart(code, "setup");
    int drawPos = findMethodStart(code, "draw");
    if (setupPos >= 0) boundary = Math.min(boundary, setupPos);
    if (drawPos >= 0) boundary = Math.min(boundary, drawPos);

    // Only look at code before setup/draw for global variable declarations
    String globalSection = code.substring(0, boundary);
    String[] lines = globalSection.split("\n");

    // Pattern for variable declarations:
    // type name = ...; or type name; or type name, name2;
    Pattern declPattern = Pattern.compile(
      "^\\s*(?:final\\s+)?" +
      "(int|float|double|boolean|String|color|char|byte|long|short)" +
      "\\s+(\\w+)" +
      "\\s*[=;,]"
    );

    // Also catch array declarations: type[] name
    Pattern arrayPattern = Pattern.compile(
      "^\\s*(?:final\\s+)?" +
      "(int|float|double|boolean|String|color|char|byte|long|short)" +
      "\\[\\]\\s+(\\w+)" +
      "\\s*[=;,]"
    );

    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].trim();
      // Skip comments
      if (line.startsWith("//") || line.startsWith("/*") || line.startsWith("*")) {
        continue;
      }
      // Skip import statements
      if (line.startsWith("import ")) continue;

      Matcher m = declPattern.matcher(lines[i]);
      if (m.find()) {
        vars.add(new VarDecl(m.group(1), m.group(2), i));
        continue;
      }

      m = arrayPattern.matcher(lines[i]);
      if (m.find()) {
        // Skip arrays for now — toString() isn't useful
        continue;
      }
    }

    return vars;
  }


  /**
   * Find the position in code right after size() call in setup().
   * Returns -1 if not found.
   */
  static int findAfterSizePos(String code) {
    int setupStart = findMethodBodyStart(code, "setup");
    if (setupStart < 0) return -1;

    // Find the end of setup() to bound our search
    int setupEnd = findMethodBodyEnd(code, "setup");

    // Look for size(...) within setup
    Pattern p = Pattern.compile("size\\s*\\(");
    Matcher m = p.matcher(code);
    while (m.find()) {
      if (m.start() < setupStart) continue;
      if (setupEnd > 0 && m.start() > setupEnd) break;
      // Found size() in setup — find the end of the statement
      int pos = m.end();
      int parenCount = 1;
      while (pos < code.length() && parenCount > 0) {
        char c = code.charAt(pos);
        if (c == '(') parenCount++;
        else if (c == ')') parenCount--;
        pos++;
      }
      // Find the semicolon
      while (pos < code.length() && code.charAt(pos) != ';') pos++;
      if (pos < code.length()) return pos + 1;
    }

    // No size() found — insert after the opening brace of setup
    return setupStart;
  }


  /**
   * Find the start of draw()'s body (right after the opening brace).
   */
  static int findDrawBodyStart(String code) {
    return findMethodBodyStart(code, "draw");
  }


  /**
   * Find the end of draw()'s body (right before the closing brace).
   */
  static int findDrawBodyEnd(String code) {
    return findMethodBodyEnd(code, "draw");
  }


  /**
   * Find the character position of a method declaration (e.g. "void setup").
   * Returns position of "void" or -1 if not found.
   */
  private static int findMethodStart(String code, String methodName) {
    Pattern p = Pattern.compile("void\\s+" + methodName + "\\s*\\(\\s*\\)");
    Matcher m = p.matcher(code);
    if (m.find()) return m.start();
    return -1;
  }


  /**
   * Find the position right after the opening brace of a method body.
   */
  private static int findMethodBodyStart(String code, String methodName) {
    int methodPos = findMethodStart(code, methodName);
    if (methodPos < 0) return -1;

    // Find the opening brace
    int pos = methodPos;
    while (pos < code.length() && code.charAt(pos) != '{') pos++;
    return (pos < code.length()) ? pos + 1 : -1;
  }


  /**
   * Find the position of the closing brace of a method body.
   * Correctly skips over string literals and comments to avoid
   * false-matching braces inside them.
   */
  private static int findMethodBodyEnd(String code, String methodName) {
    int bodyStart = findMethodBodyStart(code, methodName);
    if (bodyStart < 0) return -1;

    int braceCount = 1;
    int pos = bodyStart;
    int len = code.length();
    while (pos < len && braceCount > 0) {
      char c = code.charAt(pos);

      // Skip string literals
      if (c == '"') {
        pos++;
        while (pos < len && code.charAt(pos) != '"') {
          if (code.charAt(pos) == '\\') pos++; // skip escaped char
          pos++;
        }
        pos++; // skip closing quote
        continue;
      }

      // Skip char literals
      if (c == '\'') {
        pos++;
        while (pos < len && code.charAt(pos) != '\'') {
          if (code.charAt(pos) == '\\') pos++;
          pos++;
        }
        pos++;
        continue;
      }

      // Skip line comments
      if (c == '/' && pos + 1 < len && code.charAt(pos + 1) == '/') {
        while (pos < len && code.charAt(pos) != '\n') pos++;
        continue;
      }

      // Skip block comments
      if (c == '/' && pos + 1 < len && code.charAt(pos + 1) == '*') {
        pos += 2;
        while (pos + 1 < len && !(code.charAt(pos) == '*' && code.charAt(pos + 1) == '/')) {
          pos++;
        }
        pos += 2; // skip */
        continue;
      }

      if (c == '{') braceCount++;
      else if (c == '}') braceCount--;
      if (braceCount > 0) pos++;
    }
    return braceCount == 0 ? pos : -1;
  }


  /**
   * Build the header code that gets prepended to the sketch.
   * Includes UDP infrastructure for timeline control, variable reporting,
   * and optional tweak (hot-swap) value updates.
   */
  private static String buildHeader(int controlPort, int varPort,
                                    List<VarDecl> globals,
                                    int numInts, int numFloats,
                                    List<List<Handle>> tweakHandles) {
    boolean hasTweak = numInts > 0 || numFloats > 0;
    StringBuilder h = new StringBuilder();
    h.append("\n/* Live Mode Support - injected by Processing Live */\n");
    h.append("import java.net.*;\n");
    h.append("import java.nio.*;\n\n");
    h.append("boolean _livemode_paused = false;\n");
    h.append("boolean _livemode_stepOnce = false;\n");
    h.append("boolean _livemode_scrubRedraw = false;\n");
    h.append("boolean _livemode_initialized = false;\n");
    h.append("int _livemode_targetFrame = -1;\n");
    h.append("int _livemode_frameCount = 0;\n");
    h.append("float _livemode_savedFrameRate = 60;\n");
    h.append("DatagramSocket _livemode_ctlSocket;\n");
    h.append("DatagramSocket _livemode_varSocket;\n");
    h.append("InetAddress _livemode_editorAddr;\n");
    h.append("int _livemode_editorCtlPort = -1;\n");
    h.append("int _livemode_varPort = ").append(varPort).append(";\n");

    // Frame buffer for backward stepping (30-frame ring buffer of pixel arrays)
    h.append("int[][] _livemode_frameBuffer = new int[30][];\n");
    h.append("String[] _livemode_varBuffer = new String[30];\n");
    h.append("int _livemode_fbHead = 0;\n");
    h.append("int _livemode_fbSize = 0;\n");
    h.append("int _livemode_fbDisplayIdx = -1;\n\n");

    // Snapshot variables for scrub-while-paused (one per global variable)
    for (VarDecl var : globals) {
      h.append(var.type).append(" _livemode_snap_").append(var.name).append(";\n");
    }
    if (!globals.isEmpty()) h.append("\n");

    // Tweak arrays for hot-swap value updates
    if (numInts > 0) {
      h.append("int[] tweakmode_int = new int[").append(numInts).append("];\n");
    }
    if (numFloats > 0) {
      h.append("float[] tweakmode_float = new float[").append(numFloats).append("];\n");
    }
    if (hasTweak) h.append("\n");

    // tweakmode_initAllVars — sets original values into the arrays
    if (hasTweak && tweakHandles != null) {
      h.append("void tweakmode_initAllVars() {\n");
      for (List<Handle> list : tweakHandles) {
        for (Handle n : list) {
          h.append("  ").append(n.name).append(" = ").append(n.strValue).append(";\n");
        }
      }
      h.append("}\n\n");
    }

    // _livemode_init
    h.append("void _livemode_init() {\n");
    h.append("  try {\n");
    h.append("    _livemode_ctlSocket = new DatagramSocket(").append(controlPort).append(");\n");
    h.append("    _livemode_ctlSocket.setSoTimeout(1);\n");
    h.append("    _livemode_varSocket = new DatagramSocket();\n");
    h.append("    _livemode_editorAddr = InetAddress.getByName(\"127.0.0.1\");\n");
    h.append("  } catch (Exception e) { e.printStackTrace(); }\n");
    if (hasTweak && tweakHandles != null) {
      h.append("  tweakmode_initAllVars();\n");
    }
    h.append("}\n\n");

    // _livemode_check — drains all pending packets (control + tweak)
    // IMPORTANT: Never uses noLoop()/loop(). Pausing is handled by returning
    // early from draw() so that _livemode_check() always runs and can process
    // resume/step/seek commands even while paused.
    h.append("void _livemode_check() {\n");
    h.append("  if (_livemode_ctlSocket == null) return;\n");
    // Target frame handling for step-backward / seek
    h.append("  if (_livemode_targetFrame > 0 && _livemode_frameCount >= _livemode_targetFrame) {\n");
    h.append("    _livemode_paused = true;\n");
    h.append("    _livemode_targetFrame = -1;\n");
    h.append("    frameRate(_livemode_savedFrameRate);\n");
    h.append("  }\n");
    h.append("  byte[] buf = new byte[64];\n");
    h.append("  try {\n");
    // Drain all pending packets in a loop
    h.append("    while (true) {\n");
    h.append("      DatagramPacket pkt = new DatagramPacket(buf, buf.length);\n");
    h.append("      _livemode_ctlSocket.receive(pkt);\n");
    h.append("      _livemode_editorCtlPort = pkt.getPort();\n");
    // Tweak packets are 12 bytes: [4b type | 4b index | 4b value]
    if (hasTweak) {
      h.append("      if (pkt.getLength() == 12) {\n");
      h.append("        int _tw_type = ByteBuffer.wrap(buf, 0, 4).getInt();\n");
      h.append("        if (_tw_type == 0 || _tw_type == 1) {\n");
      h.append("          int _tw_idx = ByteBuffer.wrap(buf, 4, 4).getInt();\n");
      if (numInts > 0) {
        h.append("          if (_tw_type == 0 && _tw_idx >= 0 && _tw_idx < tweakmode_int.length) {\n");
        h.append("            tweakmode_int[_tw_idx] = ByteBuffer.wrap(buf, 8, 4).getInt();\n");
        h.append("          }\n");
      }
      if (numFloats > 0) {
        h.append("          if (_tw_type == 1 && _tw_idx >= 0 && _tw_idx < tweakmode_float.length) {\n");
        h.append("            tweakmode_float[_tw_idx] = ByteBuffer.wrap(buf, 8, 4).getFloat();\n");
        h.append("          }\n");
      }
      h.append("          if (_livemode_paused) _livemode_scrubRedraw = true;\n");
      h.append("          continue;\n");
      h.append("        }\n");
      h.append("      }\n");
    }
    // Control packets: 1=pause, 2=resume, 3=step, 4=seek, 5=back, 6=setvar
    h.append("      int cmd = buf[0] & 0xFF;\n");
    h.append("      if (cmd == 1) { _livemode_paused = true; }\n");
    h.append("      else if (cmd == 2) { _livemode_paused = false; _livemode_fbDisplayIdx = -1; }\n");
    h.append("      else if (cmd == 3) {\n");
    h.append("        if (_livemode_fbDisplayIdx > 0) {\n");
    // Stepping forward through history buffer
    h.append("          _livemode_fbDisplayIdx--;\n");
    h.append("          if (_livemode_fbDisplayIdx == 0) {\n");
    // Reached the most recent buffered frame — exit history, next step is a normal step
    h.append("            _livemode_fbDisplayIdx = -1;\n");
    h.append("          } else {\n");
    h.append("            int _fbIdx = ((_livemode_fbHead - 1 - _livemode_fbDisplayIdx) % 30 + 30) % 30;\n");
    h.append("            if (_livemode_frameBuffer[_fbIdx] != null) {\n");
    h.append("              loadPixels(); System.arraycopy(_livemode_frameBuffer[_fbIdx], 0, pixels, 0, pixels.length); updatePixels();\n");
    h.append("            }\n");
    h.append("            _livemode_sendStoredVars(_fbIdx);\n");
    h.append("            int _hf = _livemode_frameCount - _livemode_fbDisplayIdx;\n");
    h.append("            if (_livemode_editorCtlPort > 0) {\n");
    h.append("              try { byte[] _fd = new byte[5]; _fd[0] = 10; ByteBuffer.wrap(_fd, 1, 4).putInt(_hf);\n");
    h.append("                _livemode_ctlSocket.send(new DatagramPacket(_fd, _fd.length, _livemode_editorAddr, _livemode_editorCtlPort)); } catch (Exception _e) {}\n");
    h.append("            }\n");
    h.append("          }\n");
    h.append("        } else { _livemode_paused = true; _livemode_stepOnce = true; }\n");
    h.append("      }\n");
    h.append("      else if (cmd == 4 && pkt.getLength() >= 5) {\n");
    h.append("        int _tgt = ByteBuffer.wrap(buf, 1, 4).getInt();\n");
    h.append("        if (_tgt < _livemode_frameCount && _livemode_paused) {\n");
    // Backward seek — display from frame buffer if in range
    h.append("          int _offset = _livemode_frameCount - _tgt;\n");
    h.append("          if (_offset <= _livemode_fbSize) {\n");
    h.append("            _livemode_fbDisplayIdx = _offset;\n");
    h.append("            int _fbIdx = ((_livemode_fbHead - 1 - _livemode_fbDisplayIdx) % 30 + 30) % 30;\n");
    h.append("            if (_livemode_frameBuffer[_fbIdx] != null) {\n");
    h.append("              loadPixels(); System.arraycopy(_livemode_frameBuffer[_fbIdx], 0, pixels, 0, pixels.length); updatePixels();\n");
    h.append("            }\n");
    h.append("            _livemode_sendStoredVars(_fbIdx);\n");
    h.append("            if (_livemode_editorCtlPort > 0) {\n");
    h.append("              try { byte[] _fd = new byte[5]; _fd[0] = 10; ByteBuffer.wrap(_fd, 1, 4).putInt(_tgt);\n");
    h.append("                _livemode_ctlSocket.send(new DatagramPacket(_fd, _fd.length, _livemode_editorAddr, _livemode_editorCtlPort)); } catch (Exception _e) {}\n");
    h.append("            }\n");
    h.append("          }\n");
    h.append("        } else if (_tgt > _livemode_frameCount) {\n");
    // Forward seek — fast-forward
    h.append("          if (_livemode_targetFrame <= 0) _livemode_savedFrameRate = frameRate;\n");
    h.append("          _livemode_targetFrame = _tgt;\n");
    h.append("          _livemode_paused = false;\n");
    h.append("          _livemode_fbDisplayIdx = -1;\n");
    h.append("          frameRate(1000);\n");
    h.append("        }\n");
    h.append("      }\n");
    // CMD_BACK (5) — step backward through frame buffer
    h.append("      else if (cmd == 5) {\n");
    h.append("        if (_livemode_paused && _livemode_fbSize > 0) {\n");
    h.append("          if (_livemode_fbDisplayIdx < 0) _livemode_fbDisplayIdx = 0;\n");
    h.append("          else if (_livemode_fbDisplayIdx < _livemode_fbSize - 1) _livemode_fbDisplayIdx++;\n");
    h.append("          int _fbIdx = ((_livemode_fbHead - 1 - _livemode_fbDisplayIdx) % 30 + 30) % 30;\n");
    h.append("          if (_livemode_frameBuffer[_fbIdx] != null) {\n");
    h.append("            loadPixels(); System.arraycopy(_livemode_frameBuffer[_fbIdx], 0, pixels, 0, pixels.length); updatePixels();\n");
    h.append("          }\n");
    h.append("          _livemode_sendStoredVars(_fbIdx);\n");
    h.append("          int _histFrame = _livemode_frameCount - _livemode_fbDisplayIdx;\n");
    h.append("          if (_livemode_editorCtlPort > 0) {\n");
    h.append("            try { byte[] _fd = new byte[5]; _fd[0] = 10; ByteBuffer.wrap(_fd, 1, 4).putInt(_histFrame);\n");
    h.append("              _livemode_ctlSocket.send(new DatagramPacket(_fd, _fd.length, _livemode_editorAddr, _livemode_editorCtlPort)); } catch (Exception _e) {}\n");
    h.append("          }\n");
    h.append("        }\n");
    h.append("      }\n");
    // CMD_SET_VAR (6) — set a global variable by name
    if (!globals.isEmpty()) {
      h.append("      else if (cmd == 6 && pkt.getLength() > 1) {\n");
      h.append("        String _payload = new String(buf, 1, pkt.getLength() - 1);\n");
      h.append("        String[] _parts = _payload.split(\"\\\\|\", 2);\n");
      h.append("        if (_parts.length == 2) { _livemode_setVar(_parts[0], _parts[1]); }\n");
      h.append("        if (_livemode_paused) _livemode_scrubRedraw = true;\n");
      h.append("      }\n");
    }
    h.append("    }\n");
    h.append("  } catch (Exception e) { /* timeout or error - done draining */ }\n");
    // Send frame count back to editor (throttle during seek to avoid flooding)
    h.append("  if (_livemode_editorCtlPort > 0 && (_livemode_targetFrame <= 0 || _livemode_frameCount % 30 == 0)) {\n");
    h.append("    try {\n");
    h.append("      byte[] data = new byte[5];\n");
    h.append("      data[0] = 10;\n");
    h.append("      ByteBuffer.wrap(data, 1, 4).putInt(_livemode_frameCount);\n");
    h.append("      DatagramPacket pkt = new DatagramPacket(data, data.length,\n");
    h.append("        _livemode_editorAddr, _livemode_editorCtlPort);\n");
    h.append("      _livemode_ctlSocket.send(pkt);\n");
    h.append("    } catch (Exception e) { /* ignore */ }\n");
    h.append("  }\n");
    h.append("}\n\n");

    h.append("void _livemode_sendVar(int tab, int line, String name, String value) {\n");
    h.append("  if (_livemode_varSocket == null) return;\n");
    h.append("  try {\n");
    h.append("    if (value != null && value.length() > 50) {\n");
    h.append("      value = value.substring(0, 47) + \"...\";\n");
    h.append("    }\n");
    h.append("    String msg = \"VAR|\" + tab + \"|\" + line + \"|\" + name + \"|\" + value;\n");
    h.append("    byte[] data = msg.getBytes(\"UTF-8\");\n");
    h.append("    DatagramPacket pkt = new DatagramPacket(data, data.length,\n");
    h.append("      _livemode_editorAddr, _livemode_varPort);\n");
    h.append("    _livemode_varSocket.send(pkt);\n");
    h.append("  } catch (Exception e) { /* ignore */ }\n");
    h.append("}\n\n");

    h.append("void _livemode_sendStoredVars(int _fbIdx) {\n");
    h.append("  String _stored = _livemode_varBuffer[_fbIdx];\n");
    h.append("  if (_stored == null || _livemode_varSocket == null) return;\n");
    h.append("  try {\n");
    h.append("    for (String _line : _stored.split(\"\\n\")) {\n");
    h.append("      if (_line.length() > 0) {\n");
    h.append("        byte[] _d = _line.getBytes(\"UTF-8\");\n");
    h.append("        _livemode_varSocket.send(new DatagramPacket(_d, _d.length, _livemode_editorAddr, _livemode_varPort));\n");
    h.append("      }\n");
    h.append("    }\n");
    h.append("    byte[] _fe = \"FRAME_END\".getBytes(\"UTF-8\");\n");
    h.append("    _livemode_varSocket.send(new DatagramPacket(_fe, _fe.length, _livemode_editorAddr, _livemode_varPort));\n");
    h.append("  } catch (Exception _e) { /* ignore */ }\n");
    h.append("}\n\n");

    h.append("void _livemode_sendFrameEnd() {\n");
    h.append("  if (_livemode_varSocket == null) return;\n");
    h.append("  try {\n");
    h.append("    byte[] data = \"FRAME_END\".getBytes(\"UTF-8\");\n");
    h.append("    DatagramPacket pkt = new DatagramPacket(data, data.length,\n");
    h.append("      _livemode_editorAddr, _livemode_varPort);\n");
    h.append("    _livemode_varSocket.send(pkt);\n");
    h.append("  } catch (Exception e) { /* ignore */ }\n");
    h.append("}\n\n");

    // _livemode_setVar — sets a global variable AND its snapshot by name
    // (for panel editing & global scrubbing). Updating the snapshot ensures
    // that scrub-redraw restore doesn't overwrite the change.
    if (!globals.isEmpty()) {
      h.append("void _livemode_setVar(String _name, String _val) {\n");
      h.append("  try {\n");
      h.append("    switch (_name) {\n");
      for (VarDecl var : globals) {
        h.append("      case \"").append(var.name).append("\": ");
        String parseExpr;
        switch (var.type) {
          case "int": parseExpr = "Integer.parseInt(_val)"; break;
          case "float": parseExpr = "Float.parseFloat(_val)"; break;
          case "double": parseExpr = "Double.parseDouble(_val)"; break;
          case "boolean": parseExpr = "Boolean.parseBoolean(_val)"; break;
          case "String": parseExpr = "_val"; break;
          case "long": parseExpr = "Long.parseLong(_val)"; break;
          case "short": parseExpr = "Short.parseShort(_val)"; break;
          case "byte": parseExpr = "Byte.parseByte(_val)"; break;
          case "char": parseExpr = "_val.charAt(0)"; break;
          case "color": parseExpr = "Integer.parseInt(_val)"; break;
          default: parseExpr = null; break;
        }
        if (parseExpr != null) {
          h.append(var.name).append(" = ").append(parseExpr).append("; ");
          h.append("_livemode_snap_").append(var.name).append(" = ").append(var.name).append(";");
        }
        h.append(" break;\n");
      }
      h.append("    }\n");
      h.append("  } catch (Exception _e) { /* ignore parse errors */ }\n");
      h.append("}\n\n");
    }

    h.append("void _livemode_cleanup() {\n");
    h.append("  if (_livemode_ctlSocket != null) { _livemode_ctlSocket.close(); _livemode_ctlSocket = null; }\n");
    h.append("  if (_livemode_varSocket != null) { _livemode_varSocket.close(); _livemode_varSocket = null; }\n");
    h.append("}\n\n");
    h.append("void exit() {\n");
    h.append("  _livemode_cleanup();\n");
    h.append("  super.exit();\n");
    h.append("}\n");
    h.append("/* End Live Mode Support */\n\n");

    return h.toString();
  }


  /**
   * Build the code injected at the end of draw() to report variable values.
   */
  private static String buildDrawEndInjection(List<VarDecl> globals) {
    StringBuilder sb = new StringBuilder();

    // During scrub-redraws: don't advance frame, don't capture to buffer.
    // Report snapshot values (the "frozen" state) so the variable panel
    // shows stable values and only the scrubbed variable changes.
    // Also restore globals back to snapshot after draw so mutations from
    // user code (like x++) don't persist between scrub-redraws.
    sb.append("\n    if (_livemode_isScrubRedraw) {\n");
    // Restore all globals back to snapshot (undo user code mutations)
    for (VarDecl var : globals) {
      sb.append("      ").append(var.name).append(" = _livemode_snap_").append(var.name).append(";\n");
    }
    if (!globals.isEmpty()) {
      sb.append("      if (_livemode_targetFrame <= 0) {\n");
      for (VarDecl var : globals) {
        sb.append("        _livemode_sendVar(0, ").append(var.line);
        sb.append(", \"").append(var.name).append("\", String.valueOf(");
        sb.append("_livemode_snap_").append(var.name).append("));\n");
      }
      sb.append("        _livemode_sendFrameEnd();\n");
      sb.append("      }\n");
    } else {
      sb.append("      _livemode_sendFrameEnd();\n");
    }
    sb.append("    } else {\n");

    // Normal draw frame: increment counter, capture to ring buffer, report vars
    sb.append("    _livemode_frameCount++;\n");

    // Capture frame pixels into ring buffer for backward stepping
    sb.append("    loadPixels();\n");
    sb.append("    if (_livemode_frameBuffer[_livemode_fbHead] == null || _livemode_frameBuffer[_livemode_fbHead].length != pixels.length)\n");
    sb.append("      _livemode_frameBuffer[_livemode_fbHead] = new int[pixels.length];\n");
    sb.append("    System.arraycopy(pixels, 0, _livemode_frameBuffer[_livemode_fbHead], 0, pixels.length);\n");

    // Also store variable values alongside this frame
    if (!globals.isEmpty()) {
      sb.append("    _livemode_varBuffer[_livemode_fbHead] = ");
      for (int i = 0; i < globals.size(); i++) {
        VarDecl var = globals.get(i);
        if (i > 0) sb.append(" + \"\\n\" + ");
        sb.append("\"VAR|0|").append(var.line).append("|").append(var.name).append("|\" + String.valueOf(").append(var.name).append(")");
      }
      sb.append(";\n");
    } else {
      sb.append("    _livemode_varBuffer[_livemode_fbHead] = \"\";\n");
    }

    sb.append("    _livemode_fbHead = (_livemode_fbHead + 1) % 30;\n");
    sb.append("    if (_livemode_fbSize < 30) _livemode_fbSize++;\n");

    if (globals.isEmpty()) {
      sb.append("    _livemode_sendFrameEnd();\n");
      sb.append("    }\n"); // close else
      return sb.toString();
    }

    // Skip variable reporting during seek (fast-forward) for performance
    sb.append("    if (_livemode_targetFrame <= 0) {\n");
    for (VarDecl var : globals) {
      sb.append("    _livemode_sendVar(0, ");
      sb.append(var.line);
      sb.append(", \"");
      sb.append(var.name);
      sb.append("\", String.valueOf(");
      sb.append(var.name);
      sb.append("));\n");
    }
    sb.append("    _livemode_sendFrameEnd();\n");
    sb.append("    }\n"); // close if targetFrame
    sb.append("    }\n"); // close else (non-scrub)
    return sb.toString();
  }
}
