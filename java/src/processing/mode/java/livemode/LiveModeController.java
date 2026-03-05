package processing.mode.java.livemode;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;


/**
 * Controls communication between the editor and a running sketch
 * for live mode features (timeline control, state queries).
 *
 * Uses UDP for low-latency communication, similar to TweakClient.
 */
public class LiveModeController {
  // Commands sent from editor to sketch
  public static final int CMD_PAUSE = 1;
  public static final int CMD_RESUME = 2;
  public static final int CMD_STEP = 3;
  public static final int CMD_SEEK = 4;    // followed by 4-byte frame number
  public static final int CMD_BACK = 5;    // step backward through frame buffer
  public static final int CMD_SET_VAR = 6; // followed by UTF-8 "name|value"

  // Messages sent from sketch to editor
  public static final int MSG_FRAME = 10;  // followed by 4-byte frame number

  private volatile DatagramSocket socket;
  private final int port;
  private final InetAddress localhost;
  private Thread receiveThread;
  private volatile boolean running = false;
  private FrameListener frameListener;


  public interface FrameListener {
    void onFrameUpdate(int frameNumber);
  }


  public LiveModeController(int port) {
    this.port = port;
    InetAddress addr;
    try {
      addr = InetAddress.getByName("127.0.0.1");
    } catch (UnknownHostException e) {
      // Should never happen for literal IP
      throw new RuntimeException(e);
    }
    this.localhost = addr;
  }


  public int getPort() {
    return port;
  }


  public void setFrameListener(FrameListener listener) {
    this.frameListener = listener;
  }


  public void start() {
    try {
      DatagramSocket s = new DatagramSocket();
      s.setSoTimeout(100);
      socket = s;
      running = true;

      receiveThread = new Thread(this::receiveLoop, "LiveModeReceiver");
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
  }


  public void sendPause() {
    sendCommand(CMD_PAUSE);
  }


  public void sendResume() {
    sendCommand(CMD_RESUME);
  }


  public void sendStep() {
    sendCommand(CMD_STEP);
  }


  public void sendSeek(int frame) {
    byte[] data = new byte[5];
    data[0] = (byte) CMD_SEEK;
    ByteBuffer.wrap(data, 1, 4).putInt(frame);
    send(data);
  }


  public void sendStepBack() {
    sendCommand(CMD_BACK);
  }


  public void sendSetVariable(String name, String value) {
    try {
      byte[] payload = (name + "|" + value).getBytes("UTF-8");
      byte[] data = new byte[1 + payload.length];
      data[0] = (byte) CMD_SET_VAR;
      System.arraycopy(payload, 0, data, 1, payload.length);
      send(data);
    } catch (java.io.UnsupportedEncodingException e) {
      // UTF-8 always supported
    }
  }


  private void sendCommand(int cmd) {
    send(new byte[]{(byte) cmd});
  }


  private void send(byte[] data) {
    DatagramSocket s = socket;
    if (s == null || s.isClosed()) return;
    try {
      DatagramPacket packet = new DatagramPacket(data, data.length, localhost, port);
      s.send(packet);
    } catch (IOException e) {
      // Ignore send errors — sketch may have exited
    }
  }


  private void receiveLoop() {
    byte[] buf = new byte[64];
    while (running) {
      DatagramSocket s = socket;
      if (s == null || s.isClosed()) break;
      try {
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        s.receive(packet);

        if (packet.getLength() >= 5 && buf[0] == MSG_FRAME) {
          int frame = ByteBuffer.wrap(buf, 1, 4).getInt();
          if (frame >= 0 && frameListener != null) {
            frameListener.onFrameUpdate(frame);
          }
        }
      } catch (SocketTimeoutException e) {
        // Normal timeout, continue
      } catch (IOException e) {
        if (running) {
          // Socket closed externally — exit loop
          break;
        }
      }
    }
  }
}
