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

    // Wait for Arduino bootloader to finish after port open triggers DTR reset.
    // Uno bootloader ~1s, Mega ~1.5s. Then flush any garbage bytes.
    try {
      Thread.sleep(1500);
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
    if (port.bytesAvailable() > 0) {
      byte[] discard = new byte[port.bytesAvailable()];
      port.readBytes(discard, discard.length);
    }

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
