package processing.arduino;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;

import org.firmata4j.Parser;
import org.firmata4j.transport.TransportInterface;

import java.io.IOException;


/**
 * A jSerialComm-based transport for firmata4j.
 * Replaces the default JSSC transport which lacks Apple Silicon support.
 */
class JSerialCommTransport implements TransportInterface {

  private static final int BAUD_RATE = 57600;
  // Firmata protocol version response starts with 0xF9
  private static final byte FIRMATA_VERSION_REPORT = (byte) 0xF9;

  private final SerialPort port;
  private Parser parser;

  JSerialCommTransport(String portName) {
    this.port = SerialPort.getCommPort(portName);
  }

  @Override
  public void start() throws IOException {
    port.setBaudRate(BAUD_RATE);
    port.setComPortTimeouts(
      SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 100, 0);

    if (!port.openPort()) {
      throw new IOException("Cannot open port " + port.getSystemPortName());
    }

    // Opening the port triggers a DTR reset on the Arduino.
    // Wait for the bootloader to finish, but use adaptive timing:
    // poll for Firmata data arriving (which means StandardFirmata is ready)
    // rather than always waiting the worst-case time.
    waitForFirmata();

    port.addDataListener(new SerialPortDataListener() {
      @Override
      public int getListeningEvents() {
        return SerialPort.LISTENING_EVENT_DATA_AVAILABLE;
      }

      @Override
      public void serialEvent(SerialPortEvent event) {
        if (event.getEventType() != SerialPort.LISTENING_EVENT_DATA_AVAILABLE) {
          return;
        }
        int available = port.bytesAvailable();
        if (available <= 0) return;
        byte[] buf = new byte[available];
        int read = port.readBytes(buf, available);
        if (read > 0 && parser != null) {
          parser.parse(buf);
        }
      }
    });
  }


  /**
   * Wait for the Arduino bootloader to finish and StandardFirmata to start.
   * Polls for incoming data every 100ms instead of blindly sleeping.
   * Most boards are ready in 500-1200ms; times out at 2000ms.
   */
  private void waitForFirmata() {
    long deadline = System.currentTimeMillis() + 2000;
    boolean gotData = false;

    try {
      // Short initial wait for bootloader to start
      Thread.sleep(300);

      while (System.currentTimeMillis() < deadline) {
        int available = port.bytesAvailable();
        if (available > 0) {
          // Data arrived — check if it looks like Firmata
          byte[] peek = new byte[available];
          port.readBytes(peek, available);
          for (byte b : peek) {
            if (b == FIRMATA_VERSION_REPORT) {
              gotData = true;
              break;
            }
          }
          if (gotData) {
            // Firmata is talking — flush remaining bootloader garbage
            Thread.sleep(50);
            if (port.bytesAvailable() > 0) {
              byte[] discard = new byte[port.bytesAvailable()];
              port.readBytes(discard, discard.length);
            }
            return;
          }
          // Got data but not Firmata — bootloader is still running, keep waiting
        }
        Thread.sleep(100);
      }
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }

    // Timeout: flush whatever is in the buffer and hope for the best
    if (port.bytesAvailable() > 0) {
      byte[] discard = new byte[port.bytesAvailable()];
      port.readBytes(discard, discard.length);
    }
  }


  @Override
  public void stop() throws IOException {
    port.removeDataListener();
    port.closePort();
  }

  @Override
  public void write(byte[] bytes) throws IOException {
    int written = port.writeBytes(bytes, bytes.length);
    if (written < 0) {
      throw new IOException("Failed to write to " + port.getSystemPortName());
    }
  }

  @Override
  public void setParser(Parser parser) {
    this.parser = parser;
  }
}
