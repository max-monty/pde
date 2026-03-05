package processing.arduino;

import com.fazecast.jSerialComm.SerialPort;

import java.util.ArrayList;
import java.util.List;


/**
 * Detects Arduino boards connected via USB serial ports.
 * <p>
 * Uses jSerialComm to enumerate ports and identifies likely Arduino
 * boards by their port names and USB vendor/product IDs.
 */
public class BoardDetector {

  // Common Arduino USB vendor IDs
  private static final int ARDUINO_VID = 0x2341;   // Arduino SA
  private static final int ARDUINO_VID2 = 0x2A03;  // Arduino.org
  private static final int SPARKFUN_VID = 0x1B4F;  // SparkFun
  private static final int ADAFRUIT_VID = 0x239A;  // Adafruit
  private static final int CH340_VID = 0x1A86;     // WCH (CH340 clones)
  private static final int CP210X_VID = 0x10C4;    // Silicon Labs (CP2102)
  private static final int FTDI_VID = 0x0403;      // FTDI

  // Common Arduino-like port name patterns
  private static final String[] ARDUINO_PORT_PATTERNS = {
    "usbmodem",      // macOS native Arduino
    "usbserial",     // macOS FTDI/CH340
    "ttyACM",        // Linux native Arduino
    "ttyUSB",        // Linux FTDI/CH340
    "COM"            // Windows
  };


  /**
   * List all available serial port names.
   *
   * @return array of port names
   */
  public static String[] listPorts() {
    SerialPort[] ports = SerialPort.getCommPorts();
    String[] names = new String[ports.length];
    for (int i = 0; i < ports.length; i++) {
      names[i] = ports[i].getSystemPortName();
      // On macOS/Linux, prepend /dev/ if not already there
      if (!names[i].startsWith("/") && !names[i].startsWith("COM")) {
        names[i] = "/dev/" + names[i];
      }
    }
    return names;
  }


  /**
   * Find the first Arduino-like serial port.
   * Checks USB vendor IDs and port name patterns.
   *
   * @return port name, or null if no Arduino detected
   */
  public static String findArduinoPort() {
    SerialPort[] ports = SerialPort.getCommPorts();

    // First pass: look for known Arduino vendor IDs
    for (SerialPort port : ports) {
      int vid = port.getVendorID();
      if (vid == ARDUINO_VID || vid == ARDUINO_VID2 ||
          vid == CH340_VID || vid == CP210X_VID || vid == FTDI_VID ||
          vid == SPARKFUN_VID || vid == ADAFRUIT_VID) {
        return getFullPortName(port);
      }
    }

    // Second pass: look for Arduino-like port names
    for (SerialPort port : ports) {
      String name = port.getSystemPortName();
      for (String pattern : ARDUINO_PORT_PATTERNS) {
        if (name.contains(pattern)) {
          return getFullPortName(port);
        }
      }
    }

    return null;
  }


  /**
   * Find all Arduino-like serial ports.
   *
   * @return list of port info objects
   */
  public static List<PortInfo> findAllArduinoPorts() {
    List<PortInfo> result = new ArrayList<>();
    SerialPort[] ports = SerialPort.getCommPorts();

    for (SerialPort port : ports) {
      int vid = port.getVendorID();
      String name = port.getSystemPortName();

      boolean isArduino = false;
      String boardType = "Unknown";

      // Check vendor IDs
      if (vid == ARDUINO_VID || vid == ARDUINO_VID2) {
        isArduino = true;
        boardType = "Arduino";
      } else if (vid == CH340_VID) {
        isArduino = true;
        boardType = "Arduino (CH340)";
      } else if (vid == CP210X_VID) {
        isArduino = true;
        boardType = "Arduino (CP2102)";
      } else if (vid == FTDI_VID) {
        isArduino = true;
        boardType = "Arduino (FTDI)";
      } else if (vid == SPARKFUN_VID) {
        isArduino = true;
        boardType = "SparkFun";
      } else if (vid == ADAFRUIT_VID) {
        isArduino = true;
        boardType = "Adafruit";
      }

      // Fallback: check port name patterns
      if (!isArduino) {
        for (String pattern : ARDUINO_PORT_PATTERNS) {
          if (name.contains(pattern)) {
            isArduino = true;
            break;
          }
        }
      }

      if (isArduino) {
        result.add(new PortInfo(
          getFullPortName(port),
          port.getDescriptivePortName(),
          boardType,
          vid,
          port.getProductID()
        ));
      }
    }

    return result;
  }


  /**
   * Get the full system path for a serial port.
   */
  private static String getFullPortName(SerialPort port) {
    String name = port.getSystemPortName();
    if (!name.startsWith("/") && !name.startsWith("COM")) {
      return "/dev/" + name;
    }
    return name;
  }


  /**
   * Information about a detected serial port.
   */
  public static class PortInfo {
    public final String portName;
    public final String description;
    public final String boardType;
    public final int vendorId;
    public final int productId;

    PortInfo(String portName, String description, String boardType,
             int vendorId, int productId) {
      this.portName = portName;
      this.description = description;
      this.boardType = boardType;
      this.vendorId = vendorId;
      this.productId = productId;
    }

    @Override
    public String toString() {
      return portName + " (" + boardType + " — " + description + ")";
    }
  }
}
