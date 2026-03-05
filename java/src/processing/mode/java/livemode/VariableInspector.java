package processing.mode.java.livemode;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Receives variable value updates from a running sketch and stores them
 * for display in the editor. Variable values are sent via UDP from
 * injected code in the sketch.
 *
 * Protocol: Each message is a UTF-8 string in the format:
 *   "VAR|tabIndex|lineNumber|varName|value"
 */
public class VariableInspector {
  private volatile DatagramSocket socket;
  private Thread receiveThread;
  private volatile boolean running = false;

  // Map: "tab:line" -> Map of variable name -> value string
  private final ConcurrentHashMap<String, Map<String, String>> lineVariables =
    new ConcurrentHashMap<>();

  private VariableUpdateListener listener;


  public interface VariableUpdateListener {
    void onVariablesUpdated();
  }


  public VariableInspector() {
  }


  public void start(int port) {
    try {
      DatagramSocket s = new DatagramSocket(port);
      s.setSoTimeout(100);
      socket = s;
      running = true;

      receiveThread = new Thread(this::receiveLoop, "VariableInspector");
      receiveThread.setDaemon(true);
      receiveThread.start();
    } catch (SocketException e) {
      // Ensure socket is cleaned up on error
      DatagramSocket s = socket;
      socket = null;
      if (s != null) s.close();
      e.printStackTrace();
    }
  }


  public void stop() {
    running = false;
    DatagramSocket s = socket;
    socket = null;
    if (s != null) {
      s.close();
    }
    lineVariables.clear();
  }


  public void setListener(VariableUpdateListener listener) {
    this.listener = listener;
  }


  /**
   * Get variable values for a specific line.
   * @param tabIndex the tab index
   * @param line the line number (0-based)
   * @return map of variable name to value string, or empty map
   */
  public Map<String, String> getVariables(int tabIndex, int line) {
    String key = tabIndex + ":" + line;
    Map<String, String> vars = lineVariables.get(key);
    return vars != null ? vars : Collections.emptyMap();
  }


  /**
   * Get a formatted string of all variable values for a line.
   */
  public String getVariableDisplay(int tabIndex, int line) {
    Map<String, String> vars = getVariables(tabIndex, line);
    if (vars.isEmpty()) return null;

    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> entry : vars.entrySet()) {
      if (sb.length() > 0) sb.append("  ");
      sb.append(entry.getKey()).append("=").append(entry.getValue());
    }
    return sb.toString();
  }


  /**
   * Clear all stored variable values (e.g., when sketch restarts).
   */
  public void clear() {
    lineVariables.clear();
  }


  /**
   * Get all stored variable data, keyed by "tab:line".
   */
  public Map<String, Map<String, String>> getAllVariables() {
    return lineVariables;
  }


  private void receiveLoop() {
    byte[] buf = new byte[1024];
    boolean hasUpdates = false;

    while (running) {
      DatagramSocket s = socket;
      if (s == null || s.isClosed()) break;
      try {
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        s.receive(packet);

        String msg = new String(packet.getData(), 0, packet.getLength(), "UTF-8");

        // Parse batch messages (multiple VAR lines separated by newlines)
        String[] lines = msg.split("\n");
        for (String line : lines) {
          if (line.startsWith("VAR|")) {
            parseVariable(line);
            hasUpdates = true;
          } else if (line.equals("FRAME_END")) {
            // End of frame — notify listener
            if (hasUpdates && listener != null) {
              listener.onVariablesUpdated();
              hasUpdates = false;
            }
          }
        }
      } catch (SocketTimeoutException e) {
        // Normal timeout
        if (hasUpdates && listener != null) {
          listener.onVariablesUpdated();
          hasUpdates = false;
        }
      } catch (IOException e) {
        if (running) {
          break;  // Socket closed externally
        }
      }
    }
  }


  private void parseVariable(String msg) {
    // Format: "VAR|tabIndex|lineNumber|varName|value"
    String[] parts = msg.split("\\|", 5);
    if (parts.length < 5) return;

    try {
      int tabIndex = Integer.parseInt(parts[1]);
      int lineNumber = Integer.parseInt(parts[2]);
      String varName = parts[3];
      String value = parts[4];

      // Truncate long values
      if (value.length() > 30) {
        value = value.substring(0, 27) + "...";
      }

      String key = tabIndex + ":" + lineNumber;
      lineVariables.computeIfAbsent(key, k -> new ConcurrentHashMap<>())
                    .put(varName, value);
    } catch (NumberFormatException e) {
      // Ignore malformed messages
    }
  }
}
