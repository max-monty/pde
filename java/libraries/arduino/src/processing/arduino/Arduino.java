package processing.arduino;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import processing.core.PApplet;

import org.firmata4j.IODevice;
import org.firmata4j.IODeviceEventListener;
import org.firmata4j.IOEvent;
import org.firmata4j.Pin;
import org.firmata4j.firmata.FirmataDevice;

import com.fazecast.jSerialComm.SerialPort;


/**
 * Arduino integration for Processing using the Firmata protocol.
 * <p>
 * Students use this class to read sensors and control actuators from
 * Processing sketches — no Arduino IDE or C++ needed. The Arduino must
 * be running StandardFirmata firmware.
 * <p>
 * Usage:
 * <pre>
 * Arduino arduino;
 *
 * void setup() {
 *   size(400, 300);
 *   arduino = new Arduino(this);           // auto-detect board
 *   arduino.pinMode(13, Arduino.OUTPUT);
 * }
 *
 * void draw() {
 *   int sensor = arduino.analogRead(0);    // read A0
 *   float brightness = map(sensor, 0, 1023, 0, 255);
 *   background(brightness);
 *   arduino.digitalWrite(13, sensor > 512 ? Arduino.HIGH : Arduino.LOW);
 * }
 * </pre>
 */
public class Arduino {

  // Pin mode constants (match Arduino/Firmata conventions)
  public static final int INPUT = 0;
  public static final int OUTPUT = 1;
  public static final int ANALOG = 2;
  public static final int PWM = 3;
  public static final int SERVO = 4;
  public static final int I2C = 6;
  public static final int INPUT_PULLUP = 11;

  // Digital value constants
  public static final int LOW = 0;
  public static final int HIGH = 1;

  // Analog pin offset (Arduino Uno: A0=14, A1=15, etc.)
  // Firmata reports analog pins with their physical indices
  private static final int ANALOG_PIN_OFFSET = 14;

  private final PApplet parent;
  private IODevice device;
  private String portName;
  private volatile boolean connected = false;
  private volatile boolean disposed = false;

  // Cached pin values for fast synchronous reads from draw()
  private final ConcurrentHashMap<Integer, Long> pinValues = new ConcurrentHashMap<>();

  // Analog pin mapping: analog channel → physical pin index
  private int[] analogPinMap;


  /**
   * Create an Arduino connection with auto-detection.
   * If the Processing IDE has a port selected (via the Arduino toolbar button),
   * uses that port. Otherwise scans available serial ports and connects to
   * the first board running StandardFirmata.
   *
   * @param parent the Processing sketch (pass 'this')
   */
  public Arduino(PApplet parent) {
    this.parent = parent;
    parent.registerMethod("dispose", this);

    // Check if the IDE specified a port via system property
    String port = System.getProperty("processing.arduino.port");
    if (port == null || port.isEmpty()) {
      port = BoardDetector.findArduinoPort();
    }
    if (port == null) {
      System.err.println("[Arduino] No Arduino detected. Plug in a board and restart.");
      return;
    }
    connect(port);
  }


  /**
   * Create an Arduino connection to a specific serial port.
   *
   * @param parent the Processing sketch (pass 'this')
   * @param portName serial port name (e.g., "/dev/tty.usbmodem14101")
   */
  public Arduino(PApplet parent, String portName) {
    this.parent = parent;
    parent.registerMethod("dispose", this);
    connect(portName);
  }


  /**
   * Create an Arduino connection to a specific port at a given baud rate.
   *
   * @param parent the Processing sketch (pass 'this')
   * @param portName serial port name
   * @param baudRate baud rate (default Firmata uses 57600)
   */
  public Arduino(PApplet parent, String portName, int baudRate) {
    this.parent = parent;
    parent.registerMethod("dispose", this);
    connect(portName);
  }


  private void connect(String portName) {
    this.portName = portName;
    try {
      System.out.println("[Arduino] Connecting to " + portName + "...");

      // Use jSerialComm transport instead of default JSSC (no Apple Silicon support)
      device = new FirmataDevice(new JSerialCommTransport(portName));

      // Set up event listener before starting so we catch the onStart event
      device.addEventListener(new IODeviceEventListener() {
        @Override
        public void onStart(IOEvent event) { }

        @Override
        public void onStop(IOEvent event) {
          connected = false;
        }

        @Override
        public void onPinChange(IOEvent event) {
          Pin pin = event.getPin();
          pinValues.put((int) pin.getIndex(), pin.getValue());
        }

        @Override
        public void onMessageReceive(IOEvent event, String message) { }
      });

      device.start();
      device.ensureInitializationIsDone();

      // Build analog pin mapping
      buildAnalogPinMap();

      connected = true;
      System.out.println("[Arduino] Ready on " + portName +
        " (" + device.getPinsCount() + " pins)");

    } catch (Exception e) {
      System.err.println("[Arduino] Connection failed on " + portName +
        ": " + e.getMessage());
      System.err.println("[Arduino] Make sure StandardFirmata is uploaded to your board.");
      System.err.println("[Arduino] Use: Debug → Arduino → Upload StandardFirmata");
      device = null;
    }
  }


  /**
   * Build the analog pin mapping by querying which pins support ANALOG mode.
   */
  private void buildAnalogPinMap() {
    if (device == null) return;
    int pinCount = device.getPinsCount();
    int analogCount = 0;

    // First pass: count analog pins
    for (int i = 0; i < pinCount; i++) {
      Pin pin = device.getPin(i);
      if (pin.supports(Pin.Mode.ANALOG)) {
        analogCount++;
      }
    }

    // Second pass: build mapping (analog channel 0, 1, ... → physical pin)
    analogPinMap = new int[analogCount];
    int channel = 0;
    for (int i = 0; i < pinCount; i++) {
      Pin pin = device.getPin(i);
      if (pin.supports(Pin.Mode.ANALOG)) {
        analogPinMap[channel++] = i;
      }
    }
  }


  // ============================================================
  // PUBLIC API — matches Arduino/Processing conventions
  // ============================================================

  /**
   * Set the mode of a pin.
   *
   * @param pin pin number (0-13 for digital, A0-A5 or 14-19 for analog)
   * @param mode INPUT, OUTPUT, ANALOG, PWM, SERVO, or INPUT_PULLUP
   */
  public void pinMode(int pin, int mode) {
    if (!ensureConnected()) return;
    try {
      Pin p = device.getPin(pin);
      p.setMode(toFirmataMode(mode));
    } catch (Exception e) {
      // Silently ignore — pin may not support the requested mode
    }
  }


  /**
   * Read a digital pin.
   *
   * @param pin pin number
   * @return HIGH (1) or LOW (0)
   */
  public int digitalRead(int pin) {
    if (!ensureConnected()) return LOW;
    try {
      return (int) device.getPin(pin).getValue();
    } catch (Exception e) {
      return LOW;
    }
  }


  /**
   * Write to a digital pin.
   *
   * @param pin pin number
   * @param value HIGH or LOW
   */
  public void digitalWrite(int pin, int value) {
    if (!ensureConnected()) return;
    try {
      Pin p = device.getPin(pin);
      if (p.getMode() != Pin.Mode.OUTPUT) {
        p.setMode(Pin.Mode.OUTPUT);
      }
      p.setValue(value);
    } catch (Exception e) {
      // Silently ignore write errors during normal operation
    }
  }


  /**
   * Read an analog input pin.
   *
   * @param channel analog channel number (0 for A0, 1 for A1, etc.)
   * @return value from 0 to 1023
   */
  public int analogRead(int channel) {
    if (!ensureConnected()) return 0;
    try {
      int physicalPin = analogChannelToPin(channel);
      Pin p = device.getPin(physicalPin);
      if (p.getMode() != Pin.Mode.ANALOG) {
        p.setMode(Pin.Mode.ANALOG);
      }
      return (int) p.getValue();
    } catch (Exception e) {
      return 0;
    }
  }


  /**
   * Write an analog (PWM) value to a pin.
   *
   * @param pin pin number (must support PWM: 3, 5, 6, 9, 10, 11 on Uno)
   * @param value PWM duty cycle from 0 to 255
   */
  public void analogWrite(int pin, int value) {
    if (!ensureConnected()) return;
    try {
      Pin p = device.getPin(pin);
      if (p.getMode() != Pin.Mode.PWM) {
        p.setMode(Pin.Mode.PWM);
      }
      p.setValue(value);
    } catch (Exception e) {
      // Silently ignore write errors during normal operation
    }
  }


  /**
   * Write a servo angle.
   *
   * @param pin pin number
   * @param angle servo angle from 0 to 180
   */
  public void servoWrite(int pin, int angle) {
    if (!ensureConnected()) return;
    try {
      Pin p = device.getPin(pin);
      if (p.getMode() != Pin.Mode.SERVO) {
        p.setMode(Pin.Mode.SERVO);
      }
      p.setValue(Math.max(0, Math.min(180, angle)));
    } catch (Exception e) {
      // Silently ignore write errors during normal operation
    }
  }


  /**
   * Shorthand for digitalRead/analogRead based on context.
   * If pin < ANALOG_PIN_OFFSET, reads digital. Otherwise reads analog.
   *
   * @param pin pin number
   * @return pin value
   */
  public int read(int pin) {
    if (pin >= ANALOG_PIN_OFFSET || (analogPinMap != null && pin < analogPinMap.length)) {
      // Treat as analog channel if it's a small number (0-5) or >= 14
      int channel = pin >= ANALOG_PIN_OFFSET ? pin - ANALOG_PIN_OFFSET : pin;
      return analogRead(channel);
    }
    return digitalRead(pin);
  }


  /**
   * Shorthand for digitalWrite/analogWrite.
   * If value is 0 or 1, writes digital. Otherwise writes PWM.
   *
   * @param pin pin number
   * @param value value to write
   */
  public void write(int pin, int value) {
    if (value == LOW || value == HIGH) {
      digitalWrite(pin, value);
    } else {
      analogWrite(pin, value);
    }
  }


  // ============================================================
  // UTILITY METHODS
  // ============================================================

  /**
   * List all available serial ports.
   *
   * @return array of port names
   */
  public static String[] list() {
    return BoardDetector.listPorts();
  }


  /**
   * Upload StandardFirmata firmware to the Arduino on the given port.
   * Downloads arduino-cli automatically if not already installed.
   *
   * @param port serial port name
   * @return true if upload succeeded
   */
  public static boolean uploadFirmata(String port) {
    return new FirmataUploader(port, System.out).upload();
  }


  /**
   * Upload StandardFirmata firmware, specifying the board type.
   *
   * @param port serial port name
   * @param fqbn Fully Qualified Board Name (e.g., "arduino:avr:uno")
   * @return true if upload succeeded
   */
  public static boolean uploadFirmata(String port, String fqbn) {
    return new FirmataUploader(port, fqbn, System.out).upload();
  }


  /**
   * Check if the Arduino is connected and communicating.
   *
   * @return true if connected
   */
  public boolean isConnected() {
    return connected && device != null && device.isReady();
  }


  /**
   * Get the serial port name this Arduino is connected to.
   *
   * @return port name, or null if not connected
   */
  public String getPortName() {
    return portName;
  }


  /**
   * Get the number of pins on the connected board.
   *
   * @return pin count, or 0 if not connected
   */
  public int getPinCount() {
    if (device == null) return 0;
    return device.getPinsCount();
  }


  /**
   * Get the number of analog input channels.
   *
   * @return analog channel count
   */
  public int getAnalogChannelCount() {
    return analogPinMap != null ? analogPinMap.length : 0;
  }


  /**
   * Get the underlying firmata4j IODevice for advanced use.
   *
   * @return the IODevice, or null if not connected
   */
  public IODevice getDevice() {
    return device;
  }


  /**
   * Clean up resources. Called automatically by Processing on sketch exit.
   */
  public void dispose() {
    if (disposed) return;
    disposed = true;
    if (device != null) {
      try {
        device.stop();
      } catch (Exception e) {
        // Ignore cleanup errors
      }
      device = null;
    }
    connected = false;
  }


  // ============================================================
  // INTERNAL HELPERS
  // ============================================================

  private boolean ensureConnected() {
    if (device == null || !connected) {
      return false;
    }
    return true;
  }


  /**
   * Convert analog channel number (0, 1, 2...) to physical pin index.
   */
  private int analogChannelToPin(int channel) {
    if (analogPinMap != null && channel >= 0 && channel < analogPinMap.length) {
      return analogPinMap[channel];
    }
    // Fallback: assume standard Uno mapping (A0=14, A1=15, ...)
    return channel + ANALOG_PIN_OFFSET;
  }


  /**
   * Convert our mode constants to firmata4j Pin.Mode.
   */
  private static Pin.Mode toFirmataMode(int mode) {
    switch (mode) {
      case INPUT: return Pin.Mode.INPUT;
      case OUTPUT: return Pin.Mode.OUTPUT;
      case ANALOG: return Pin.Mode.ANALOG;
      case PWM: return Pin.Mode.PWM;
      case SERVO: return Pin.Mode.SERVO;
      case I2C: return Pin.Mode.I2C;
      case INPUT_PULLUP: return Pin.Mode.PULLUP;
      default: return Pin.Mode.INPUT;
    }
  }
}
