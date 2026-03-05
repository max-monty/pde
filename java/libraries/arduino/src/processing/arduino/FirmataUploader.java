package processing.arduino;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Uploads StandardFirmata firmware to an Arduino board using arduino-cli.
 * Downloads arduino-cli automatically if not already installed.
 */
public class FirmataUploader {

  private static final String ARDUINO_CLI_VERSION = "1.1.1";

  // StandardFirmata sketch source
  private static final String STANDARD_FIRMATA =
    "#include <Firmata.h>\n" +
    "#include <Servo.h>\n" +
    "// StandardFirmata - see Arduino IDE → File → Examples → Firmata\n" +
    "// This is uploaded automatically by the Processing Arduino library.\n";

  private final String port;
  private final String fqbn;  // Fully Qualified Board Name
  private final PrintStream out;

  public FirmataUploader(String port, String fqbn, PrintStream out) {
    this.port = port;
    this.fqbn = fqbn;
    this.out = out;
  }

  public FirmataUploader(String port, PrintStream out) {
    this(port, null, out);
  }


  /**
   * Upload StandardFirmata to the connected board.
   * Downloads arduino-cli if needed, detects board type, compiles and uploads.
   *
   * @return true if upload succeeded
   */
  public boolean upload() {
    try {
      String cli = findOrInstallCli();
      if (cli == null) {
        out.println("[Arduino] Failed to find or install arduino-cli.");
        return false;
      }

      // Install the Firmata library
      out.println("[Arduino] Installing Firmata library...");
      run(cli, "lib", "install", "Firmata");
      run(cli, "lib", "install", "Servo");

      // Detect board if FQBN not specified
      String boardFqbn = fqbn;
      if (boardFqbn == null) {
        boardFqbn = detectBoard(cli);
        if (boardFqbn == null) {
          out.println("[Arduino] Could not detect board type. " +
            "Please specify the board FQBN.");
          return false;
        }
      }

      // Install the core for this board
      String coreName = boardFqbn.substring(0, boardFqbn.lastIndexOf(':'));
      out.println("[Arduino] Installing board core: " + coreName);
      run(cli, "core", "install", coreName);

      // Upload StandardFirmata example from the Firmata library
      out.println("[Arduino] Compiling and uploading StandardFirmata to " +
        port + " (" + boardFqbn + ")...");
      String firmataPath = findFirmataSketch(cli);
      if (firmataPath == null) {
        out.println("[Arduino] Could not find StandardFirmata sketch.");
        return false;
      }

      String[] result = runCapture(cli, "compile", "--upload",
        "-b", boardFqbn, "-p", port, firmataPath);

      if (result[0].contains("error") || result[1].contains("Error")) {
        out.println("[Arduino] Upload failed:");
        out.println(result[0]);
        out.println(result[1]);
        return false;
      }

      out.println("[Arduino] StandardFirmata uploaded successfully!");
      out.println("[Arduino] You can now run your Processing sketch.");

      // Wait for the board to reset after upload
      try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
      return true;

    } catch (Exception e) {
      out.println("[Arduino] Upload error: " + e.getMessage());
      return false;
    }
  }


  /**
   * Find StandardFirmata sketch in the arduino-cli library path.
   */
  private String findFirmataSketch(String cli) {
    try {
      String[] result = runCapture(cli, "config", "dump", "--format", "text");
      // Look in standard library locations
      Path userDir = Paths.get(System.getProperty("user.home"),
        "Arduino", "libraries", "Firmata", "examples", "StandardFirmata");
      if (Files.exists(userDir)) return userDir.toString();

      // Also check Documents/Arduino on macOS
      userDir = Paths.get(System.getProperty("user.home"),
        "Documents", "Arduino", "libraries", "Firmata", "examples", "StandardFirmata");
      if (Files.exists(userDir)) return userDir.toString();

      // Try the cli's data directory
      String[] configResult = runCapture(cli, "config", "get", "directories.data");
      if (configResult[0] != null && !configResult[0].isEmpty()) {
        Path dataDir = Paths.get(configResult[0].trim(),
          "libraries", "Firmata", "examples", "StandardFirmata");
        if (Files.exists(dataDir)) return dataDir.toString();
      }
    } catch (Exception e) {
      // Fall through
    }
    return null;
  }


  /**
   * Detect the board type using arduino-cli board list.
   */
  private String detectBoard(String cli) {
    try {
      String[] result = runCapture(cli, "board", "list", "--format", "text");
      String output = result[0];
      // Look for our port in the output
      for (String line : output.split("\n")) {
        if (line.contains(port)) {
          // Try to extract FQBN from the line
          Pattern fqbnPattern = Pattern.compile("(arduino:\\w+:\\w+)");
          Matcher m = fqbnPattern.matcher(line);
          if (m.find()) {
            String detected = m.group(1);
            out.println("[Arduino] Detected board: " + detected);
            return detected;
          }
        }
      }
      // Default to Arduino Uno if we can't detect
      out.println("[Arduino] Board type not detected, defaulting to Arduino Uno.");
      return "arduino:avr:uno";
    } catch (Exception e) {
      return "arduino:avr:uno";
    }
  }


  /**
   * Find arduino-cli in PATH, or download and install it.
   */
  private String findOrInstallCli() {
    // Check PATH first
    String found = findInPath("arduino-cli");
    if (found != null) {
      out.println("[Arduino] Found arduino-cli: " + found);
      return found;
    }

    // Check our local install
    Path localCli = getLocalCliPath();
    if (Files.exists(localCli) && Files.isExecutable(localCli)) {
      out.println("[Arduino] Using local arduino-cli: " + localCli);
      return localCli.toString();
    }

    // Download and install
    out.println("[Arduino] arduino-cli not found. Downloading...");
    return downloadCli();
  }


  private Path getLocalCliPath() {
    return Paths.get(System.getProperty("user.home"),
      ".processing", "arduino-cli", "arduino-cli");
  }


  private String downloadCli() {
    try {
      String os = System.getProperty("os.name").toLowerCase();
      String arch = System.getProperty("os.arch").toLowerCase();

      String platform;
      String ext;
      if (os.contains("mac")) {
        platform = arch.contains("aarch64") || arch.contains("arm") ?
          "macOS_ARM64" : "macOS_64bit";
        ext = "tar.gz";
      } else if (os.contains("linux")) {
        platform = arch.contains("aarch64") || arch.contains("arm") ?
          "Linux_ARM64" : "Linux_64bit";
        ext = "tar.gz";
      } else {
        platform = "Windows_64bit";
        ext = "zip";
      }

      String url = String.format(
        "https://github.com/arduino/arduino-cli/releases/download/v%s/arduino-cli_%s_%s.%s",
        ARDUINO_CLI_VERSION, ARDUINO_CLI_VERSION, platform, ext);

      Path installDir = getLocalCliPath().getParent();
      Files.createDirectories(installDir);
      Path archive = installDir.resolve("arduino-cli." + ext);

      out.println("[Arduino] Downloading from: " + url);
      try (InputStream in = new URL(url).openStream()) {
        Files.copy(in, archive, StandardCopyOption.REPLACE_EXISTING);
      }

      // Extract
      if (ext.equals("tar.gz")) {
        ProcessBuilder pb = new ProcessBuilder("tar", "xzf",
          archive.toString(), "-C", installDir.toString());
        pb.inheritIO();
        Process p = pb.start();
        p.waitFor();
      }

      Files.deleteIfExists(archive);

      Path cli = getLocalCliPath();
      if (Files.exists(cli)) {
        cli.toFile().setExecutable(true);
        out.println("[Arduino] Installed arduino-cli to " + cli);

        // Initialize config
        run(cli.toString(), "config", "init", "--overwrite");
        return cli.toString();
      }

      out.println("[Arduino] Download completed but arduino-cli not found at expected path.");
      return null;

    } catch (Exception e) {
      out.println("[Arduino] Download failed: " + e.getMessage());
      return null;
    }
  }


  private static String findInPath(String executable) {
    String path = System.getenv("PATH");
    if (path == null) return null;
    for (String dir : path.split(File.pathSeparator)) {
      File f = new File(dir, executable);
      if (f.exists() && f.canExecute()) return f.getAbsolutePath();
    }
    return null;
  }


  private void run(String... args) {
    try {
      ProcessBuilder pb = new ProcessBuilder(args);
      pb.redirectErrorStream(true);
      Process p = pb.start();
      try (BufferedReader reader = new BufferedReader(
             new InputStreamReader(p.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          out.println("[arduino-cli] " + line);
        }
      }
      p.waitFor();
    } catch (Exception e) {
      out.println("[Arduino] Command failed: " + e.getMessage());
    }
  }


  private String[] runCapture(String... args) {
    try {
      ProcessBuilder pb = new ProcessBuilder(args);
      pb.redirectErrorStream(false);
      Process p = pb.start();

      StringBuilder stdout = new StringBuilder();
      StringBuilder stderr = new StringBuilder();

      try (BufferedReader outReader = new BufferedReader(
             new InputStreamReader(p.getInputStream()));
           BufferedReader errReader = new BufferedReader(
             new InputStreamReader(p.getErrorStream()))) {
        String line;
        while ((line = outReader.readLine()) != null) stdout.append(line).append("\n");
        while ((line = errReader.readLine()) != null) stderr.append(line).append("\n");
      }
      p.waitFor();
      return new String[] { stdout.toString(), stderr.toString() };

    } catch (Exception e) {
      return new String[] { "", e.getMessage() };
    }
  }
}
