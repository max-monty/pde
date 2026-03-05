/* -*- mode: java; c-basic-offset: 2; indent-tabs-mode: nil -*- */

/*
Part of the Processing project - http://processing.org

Copyright (c) 2012-23 The Processing Foundation
Copyright (c) 2004-12 Ben Fry and Casey Reas
Copyright (c) 2001-04 Massachusetts Institute of Technology

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License version 2
as published by the Free Software Foundation.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software Foundation,
Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package processing.mode.java;

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;

import processing.core.PApplet;
import processing.data.StringList;
import processing.app.*;
import processing.app.contrib.*;
import processing.app.syntax.JEditTextArea;
import processing.app.syntax.PdeTextArea;
import processing.app.syntax.PdeTextAreaDefaults;
import processing.app.ui.*;
import processing.app.ui.Toolkit;
import processing.mode.java.debug.Debugger;
import processing.mode.java.debug.LineBreakpoint;
import processing.mode.java.debug.LineHighlight;
import processing.mode.java.debug.LineID;
import processing.mode.java.preproc.ImportStatement;
import processing.mode.java.preproc.PdePreprocessor;
import processing.mode.java.preproc.SourceUtil;
import processing.mode.java.runner.Runner;
import processing.mode.java.tweak.ColorControlBox;
import processing.mode.java.tweak.Handle;
import processing.mode.java.tweak.SketchParser;
import processing.mode.java.tweak.TweakClient;
import processing.mode.java.livemode.LiveModeController;
import processing.mode.java.livemode.LiveVariablePanel;
import processing.mode.java.livemode.TimelinePanel;
import processing.mode.java.livemode.VariableInspector;


public class JavaEditor extends Editor {
  JavaMode jmode;

  // Runner associated with this editor window
  private Runner runtime;

  private boolean runtimeLaunchRequested;
  private final Object runtimeLock = new Object[0];

  // Need to sort through the rest of these additions [fry]

  protected final List<LineHighlight> breakpointedLines = new ArrayList<>();
  protected LineHighlight currentLine; // where the debugger is suspended
  protected final String breakpointMarkerComment = " //<>//";

  JMenu modeMenu;
//  protected JMenuItem inspectorItem;
//  static final int ERROR_TAB_INDEX = 0;

  protected PreprocService preprocService;

  protected Debugger debugger;

  final private InspectMode inspect;
  final private ShowUsage usage;
  final private Rename rename;
  final private ErrorChecker errorChecker;

  // set true to show AST debugging window
  static private final boolean SHOW_AST_VIEWER = false;
  private ASTViewer astViewer;

  // Live Preview mode — auto-run sketch on code change
  private volatile boolean liveMode = false;
  private static final long LIVE_RELAUNCH_DELAY = 300; // ms after last keystroke
  private final ScheduledExecutorService liveScheduler =
    Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "LivePreview");
      t.setDaemon(true);
      return t;
    });
  private volatile ScheduledFuture<?> scheduledLiveRelaunch = null;

  // Timeline scrubbing and variable inspection
  private TimelinePanel timelinePanel;
  private LiveModeController liveModeController;
  private VariableInspector variableInspector;
  private int liveControlPort;
  private int liveVarPort;

  // Tweak hot-swap for live number scrubbing
  private TweakClient liveTweakClient;
  private List<List<Handle>> liveHandles;
  volatile boolean suppressLiveRelaunch = false;

  // Variable side panel for live mode
  private LiveVariablePanel liveVariablePanel;

  /** P5 in decimal; if there are complaints, move to preferences.txt */
  static final int REFERENCE_PORT = 8053;
  // weird to link to a specific location like this, but it's versioned, so:
  static final String REFERENCE_URL =
    "https://github.com/processing/processing4/releases/tag/processing-1300-4.4.0";
  static final String REFERENCE_URL_2 = "https://github.com/processing/processing4/releases/download/processing-1300-4.4.0/processing-4.4.0-reference.zip";
  Boolean useReferenceServer;
  ReferenceServer referenceServer;


  protected JavaEditor(Base base, String path, EditorState state,
                       Mode mode) throws EditorException {
    super(base, path, state, mode);

    jmode = (JavaMode) mode;

    // Add timeline panel below the main split pane
    timelinePanel = new TimelinePanel();
    timelinePanel.setTimelineListener(new TimelinePanel.TimelineListener() {
      @Override
      public void onPauseToggle(boolean paused) {
        if (liveModeController != null) {
          if (paused) liveModeController.sendPause();
          else liveModeController.sendResume();
        }
      }
      @Override
      public void onStepForward() {
        if (liveModeController != null) {
          if (!timelinePanel.isPaused()) {
            timelinePanel.setPaused(true);
            liveModeController.sendPause();
          }
          liveModeController.sendStep();
        }
      }
      @Override
      public void onStepBackward() {
        if (liveModeController != null) {
          if (!timelinePanel.isPaused()) {
            timelinePanel.setPaused(true);
            liveModeController.sendPause();
          }
          liveModeController.sendStepBack();
        }
      }
      @Override
      public void onRestart() {
        timelinePanel.reset();
        doLiveRelaunch();
      }
    });
    getContentPane().add(timelinePanel, java.awt.BorderLayout.SOUTH);

    // Add collapsible variable panel to the right side of the text area
    liveVariablePanel = new LiveVariablePanel();
    Container textParent = textarea.getParent();
    if (textParent instanceof JPanel editorPanel) {
      editorPanel.remove(textarea);
      JPanel textWithVars = new JPanel(new BorderLayout());
      textWithVars.add(textarea, BorderLayout.CENTER);
      textWithVars.add(liveVariablePanel, BorderLayout.EAST);
      editorPanel.add(textWithVars, BorderLayout.CENTER);
      editorPanel.revalidate();
    }

    debugger = new Debugger(this);
    debugger.populateMenu(modeMenu);

    // Add live mode menu item (Cmd+L / Ctrl+L)
    modeMenu.addSeparator();
    JMenuItem liveItem = Toolkit.newJMenuItem("Live Preview", 'L');
    liveItem.addActionListener(e -> toggleLive());
    modeMenu.add(liveItem);

    // Add Arduino submenu
    modeMenu.addSeparator();
    JMenu arduinoMenu = new JMenu("Arduino");
    JMenuItem selectPortItem = new JMenuItem("Select Port\u2026");
    selectPortItem.addActionListener(e -> {
      if (toolbar instanceof JavaToolbar jt) {
        showArduinoPortMenu(jt);
      }
    });
    arduinoMenu.add(selectPortItem);

    JMenuItem autoDetectItem = new JMenuItem("Auto-detect");
    autoDetectItem.addActionListener(e -> selectArduinoPort(null));
    arduinoMenu.add(autoDetectItem);

    arduinoMenu.addSeparator();

    JMenuItem uploadFirmataItem = new JMenuItem("Upload StandardFirmata");
    uploadFirmataItem.addActionListener(e -> uploadStandardFirmata());
    arduinoMenu.add(uploadFirmataItem);

    modeMenu.add(arduinoMenu);

    // Restore Arduino button state from preferences
    String savedPort = Preferences.get("arduino.port");
    if (savedPort != null && !savedPort.isEmpty()) {
      if (toolbar instanceof JavaToolbar jt) {
        jt.setArduinoSelected(true);
      }
    }

    // set breakpoints from marker comments
    for (LineID lineID : stripBreakpointComments()) {
      //System.out.println("setting: " + lineID);
      debugger.setBreakpoint(lineID);
    }
    // setting breakpoints will flag sketch as modified, so override this here
    getSketch().setModified(false);

    preprocService = new PreprocService(this.jmode, this.sketch); 

    usage = new ShowUsage(this, preprocService);
    inspect = new InspectMode(this, preprocService, usage);
    rename = new Rename(this, preprocService, usage);

    if (SHOW_AST_VIEWER) {
      astViewer = new ASTViewer(this, preprocService);
    }

    errorChecker = new ErrorChecker(this::setProblemList, preprocService);

    for (SketchCode code : getSketch().getCode()) {
      Document document = code.getDocument();
      addDocumentListener(document);
    }
    sketchChanged();

    Toolkit.setMenuMnemonics(textarea.getRightClickPopup());

    // ensure completion is hidden when editor loses focus
    addWindowFocusListener(new WindowFocusListener() {
      public void windowLostFocus(WindowEvent e) {
        getJavaTextArea().hideSuggestion();
      }

      public void windowGainedFocus(WindowEvent e) { }
    });
  }


  public PdePreprocessor createPreprocessor(final String sketchName) {
    return PdePreprocessor.builderFor(sketchName).build();
  }


  protected JEditTextArea createTextArea() {
    return new JavaTextArea(new PdeTextAreaDefaults(), this);
  }


  public EditorToolbar createToolbar() {
    return new JavaToolbar(this);
  }


  private int previousTabCount = 1;

  // TODO: this is a clumsy way to get notified when tabs get added/deleted
  // Override the parent call to add hook to the rebuild() method
  public EditorHeader createHeader() {
    return new EditorHeader(this) {
      public void rebuild() {
        super.rebuild();

        // after Rename and New Tab, we may have new .java tabs

        if (preprocService != null) {
          int currentTabCount = sketch.getCodeCount();
          if (currentTabCount != previousTabCount) {
            previousTabCount = currentTabCount;
            sketchChanged();
          }
        }
      }
    };
  }


  @Override
  public EditorFooter createFooter() {
    EditorFooter footer = super.createFooter();
    addErrorTable(footer);
    return footer;
  }


  public Formatter createFormatter() {
    return new AutoFormat();
  }


  // . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .


  public JMenu buildFileMenu() {
    //String appTitle = JavaToolbar.getTitle(JavaToolbar.EXPORT, false);
    String appTitle = Language.text("menu.file.export_application");
    JMenuItem exportApplication = Toolkit.newJMenuItemShift(appTitle, 'E');
    exportApplication.addActionListener(e -> {
      if (sketch.isUntitled() || sketch.isReadOnly()) {
        // Exporting to application will open the sketch folder, which is
        // weird for untitled sketches (that live in a temp folder) and
        // read-only sketches (that live in the examples folder).
        // TODO Better explanation? And some localization too.
        Messages.showMessage("Save First", "Please first save the sketch.");
      } else {
        handleExportApplication();
      }
    });

    var exportPDEZ = new JMenuItem(Language.text("menu.file.export_pdez"));
    exportPDEZ.addActionListener(e -> {
      if (sketch.isUntitled() || sketch.isReadOnly()) {
        Messages.showMessage("Save First", "Please first save the sketch.");
      } else {
        handleExportPDEZ();
      }
    });


    return buildFileMenu(new JMenuItem[] { exportApplication, exportPDEZ });
  }


  public JMenu buildSketchMenu() {
    JMenuItem runItem = Toolkit.newJMenuItem(Language.text("menu.sketch.run"), 'R');
    runItem.addActionListener(e -> handleRun());

    JMenuItem presentItem = Toolkit.newJMenuItemShift(Language.text("menu.sketch.present"), 'R');
    presentItem.addActionListener(e -> handlePresent());

    JMenuItem stopItem = new JMenuItem(Language.text("menu.sketch.stop"));
    stopItem.addActionListener(e -> {
      if (isDebuggerEnabled()) {
        Messages.log("Invoked 'Stop' menu item");
        debugger.stopDebug();
      } else {
        handleStop();
      }
    });

    JMenuItem tweakItem = Toolkit.newJMenuItemShift(Language.text("menu.sketch.tweak"), 'T');
    tweakItem.addActionListener(e -> handleTweak());

    return buildSketchMenu(new JMenuItem[] {
      runItem, presentItem, tweakItem, stopItem
    });
  }


  public JMenu buildHelpMenu() {
    JMenu menu = new JMenu(Language.text("menu.help"));
    JMenuItem item;

    // macOS already has its own about menu
    if (!Platform.isMacOS()) {
      item = new JMenuItem(Language.text("menu.help.about"));
      item.addActionListener(e -> new About(JavaEditor.this));
      menu.add(item);
    }

    item = new JMenuItem(Language.text("menu.help.welcome"));
    item.addActionListener(e -> {
        PDEWelcomeKt.showWelcomeScreen(base);
    });
    menu.add(item);

    item = new JMenuItem(Language.text("menu.help.environment"));
    item.addActionListener(e -> showReference("../environment/index.html"));
    menu.add(item);

    item = new JMenuItem(Language.text("menu.help.reference"));
    item.addActionListener(e -> showReference("index.html"));
    menu.add(item);

    item = Toolkit.newJMenuItemShift(Language.text("menu.help.find_in_reference"), 'F');
    item.addActionListener(e -> {
      if (textarea.isSelectionActive()) {
        handleFindReference();
      } else {
        statusNotice(Language.text("editor.status.find_reference.select_word_first"));
      }
    });
    menu.add(item);

    // Not gonna use "update" since it's more about re-downloading:
    // it doesn't make sense to "update" the reference because it's
    // specific to a version of the software anyway. [fry 221125]
//    item = new JMenuItem(isReferenceDownloaded() ?
//      "menu.help.reference.update" : "menu.help.reference.download");
    item = new JMenuItem(Language.text("menu.help.reference.download"));
    item.addActionListener(e -> new Thread(this::downloadReference).start());
    menu.add(item);

    menu.addSeparator();

    // Report a bug link opener
    item = new JMenuItem(Language.text("menu.help.report"));
    item.addActionListener(e -> Platform.openURL(Language.text("menu.help.report.url")));
    menu.add(item);

    // Ask on the Forum link opener
    item = new JMenuItem(Language.text("menu.help.ask"));
    item.addActionListener(e -> Platform.openURL(Language.text("menu.help.ask.url")));
    menu.add(item);

    menu.addSeparator();

    final JMenu libRefSubmenu = new JMenu(Language.text("menu.help.libraries_reference"));

    // Adding this in case references are included in a core library,
    // or other core libraries are included in the future
    boolean isCoreLibMenuItemAdded =
      addLibReferencesToSubMenu(mode.coreLibraries, libRefSubmenu);

    if (isCoreLibMenuItemAdded && !mode.contribLibraries.isEmpty()) {
      libRefSubmenu.addSeparator();
    }

    boolean isContribLibMenuItemAdded =
      addLibReferencesToSubMenu(mode.contribLibraries, libRefSubmenu);

    if (!isContribLibMenuItemAdded && !isCoreLibMenuItemAdded) {
      JMenuItem emptyMenuItem = new JMenuItem(Language.text("menu.help.empty"));
      emptyMenuItem.setEnabled(false);
      emptyMenuItem.setFocusable(false);
      emptyMenuItem.setFocusPainted(false);
      libRefSubmenu.add(emptyMenuItem);

    } else if (!isContribLibMenuItemAdded && !mode.coreLibraries.isEmpty()) {
      //re-populate the menu to get rid of terminal separator
      libRefSubmenu.removeAll();
      addLibReferencesToSubMenu(mode.coreLibraries, libRefSubmenu);
    }
    menu.add(libRefSubmenu);

    final JMenu toolRefSubmenu = new JMenu(Language.text("menu.help.tools_reference"));
    boolean coreToolMenuItemAdded;
    boolean contribToolMenuItemAdded;

    List<ToolContribution> contribTools = base.getContribTools();
    // Adding this in case a reference folder is added for MovieMaker,
    // or in case other core tools are introduced later.
    coreToolMenuItemAdded = addToolReferencesToSubMenu(base.getCoreTools(), toolRefSubmenu);

    if (coreToolMenuItemAdded && !contribTools.isEmpty())
      toolRefSubmenu.addSeparator();

    contribToolMenuItemAdded = addToolReferencesToSubMenu(contribTools, toolRefSubmenu);

    if (!contribToolMenuItemAdded && !coreToolMenuItemAdded) {
      toolRefSubmenu.removeAll(); // in case a separator was added
      final JMenuItem emptyMenuItem = new JMenuItem(Language.text("menu.help.empty"));
      emptyMenuItem.setEnabled(false);
      emptyMenuItem.setBorderPainted(false);
      emptyMenuItem.setFocusable(false);
      emptyMenuItem.setFocusPainted(false);
      toolRefSubmenu.add(emptyMenuItem);
    }
    else if (!contribToolMenuItemAdded && !contribTools.isEmpty()) {
      // re-populate the menu to get rid of terminal separator
      toolRefSubmenu.removeAll();
      addToolReferencesToSubMenu(base.getCoreTools(), toolRefSubmenu);
    }
    menu.add(toolRefSubmenu);

    menu.addSeparator();
    /*
    item = new JMenuItem(Language.text("menu.help.online"));
    item.setEnabled(false);
    menu.add(item);
    */

    item = new JMenuItem(Language.text("menu.help.getting_started"));
    item.addActionListener(e -> Platform.openURL(Language.text("menu.help.getting_started.url")));
    menu.add(item);

    item = new JMenuItem(Language.text("menu.help.troubleshooting"));
    item.addActionListener(e -> Platform.openURL(Language.text("menu.help.troubleshooting.url")));
    menu.add(item);

    item = new JMenuItem(Language.text("menu.help.faq"));
    item.addActionListener(e -> Platform.openURL(Language.text("menu.help.faq.url")));
    menu.add(item);

    item = new JMenuItem(Language.text("menu.help.foundation"));
    item.addActionListener(e -> Platform.openURL(Language.text("menu.help.foundation.url")));
    menu.add(item);

    item = new JMenuItem(Language.text("menu.help.visit"));
    item.addActionListener(e -> Platform.openURL(Language.text("menu.help.visit.url")));
    menu.add(item);

    return menu;
  }


  //. . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .


  /**
   * Populates the JMenu with JMenuItems, one for each Library that has a
   * reference accompanying it. The JMenuItems open the index.htm/index.html
   * file of the reference in the user's default browser, or the readme.txt in
   * the user's default text editor.
   *
   * @param libsList
   *          A list of the Libraries to be added
   * @param subMenu
   *          The JMenu to which the JMenuItems corresponding to the Libraries
   *          are to be added
   * @return true if and only if any JMenuItems were added; false otherwise
   */
  private boolean addLibReferencesToSubMenu(List<Library> libsList, JMenu subMenu) {
    boolean isItemAdded = false;
    for (Library libContrib : libsList) {
      if (libContrib.hasReference()) {
        JMenuItem libRefItem = new JMenuItem(libContrib.getName());
        libRefItem.addActionListener(arg0 -> showReferenceFile(libContrib.getReferenceIndexFile()));
        subMenu.add(libRefItem);
        isItemAdded = true;
      }
    }
    return isItemAdded;
  }


  /**
   * Populates the JMenu with JMenuItems, one for each Tool that has a reference
   * accompanying it. The JMenuItems open the index.htm/index.html file of the
   * reference in the user's default browser, or the readme.txt in the user's
   * default text editor.
   *
   * @param toolsList
   *          A list of Tools to be added
   * @param subMenu
   *          The JMenu to which the JMenuItems corresponding to the Tools are
   *          to be added
   * @return true if and only if any JMenuItems were added; false otherwise
   */
  private boolean addToolReferencesToSubMenu(List<ToolContribution> toolsList, JMenu subMenu) {
    boolean isItemAdded = false;
    for (ToolContribution toolContrib : toolsList) {
      final File toolRef = new File(toolContrib.getFolder(), "reference/index.html");
      if (toolRef.exists()) {
        JMenuItem libRefItem = new JMenuItem(toolContrib.getName());
        libRefItem.addActionListener(arg0 -> showReferenceFile(toolRef));
        subMenu.add(libRefItem);
        isItemAdded = true;
      }
    }
    return isItemAdded;
  }


  // . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .


  public String getCommentPrefix() {
    return "//";
  }


  // . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .


  /**
   * Handler for Sketch → Export Application
   */
  public void handleExportApplication() {
    if (handleExportCheckModified()) {
      statusNotice(Language.text("export.notice.exporting"));
      ExportPrompt ep = new ExportPrompt(this, () -> {
        try {
          if (jmode.handleExportApplication(getSketch())) {
            Platform.openFolder(sketch.getFolder());
            statusNotice(Language.text("export.notice.exporting.done"));
          }
        } catch (Exception e) {
          statusNotice(Language.text("export.notice.exporting.error"));
          e.printStackTrace();
        }
      });
      ep.trigger();
    }
  }

  /**
   * Handler for File → Export PDEZ
   */
  public void handleExportPDEZ() {
    if (handleExportCheckModified()) {
      var sketch = getSketch();
      var folder = sketch.getFolder().toPath();
      var target = new File(folder + ".pdez").toPath();
      if (Files.exists(target)) {
        try {
          Platform.deleteFile(target.toFile());
        } catch (IOException e) {
          Messages.showError("Export Error", "Could not delete existing file: " + target, e);
        }
      }

      try (var zs = new ZipOutputStream(Files.newOutputStream(target))) {
        Files.walk(folder)
                .filter(path -> !Files.isDirectory(path))
                .forEach(path -> {
                  var zipEntry = new ZipEntry(folder.getParent().relativize(path).toString());
                  try {
                    zs.putNextEntry(zipEntry);
                    Files.copy(path, zs);
                    zs.closeEntry();
                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                });
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      if (Desktop.isDesktopSupported()) {
        var desktop = Desktop.getDesktop();
        if (desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
          desktop.browseFileDirectory(target.toFile());
        } else {
          try {
              desktop.open(target.getParent().toFile());
          } catch (IOException e) {
              throw new RuntimeException(e);
          }
        }
      }
    }
  }

  /**
   * Checks to see if the sketch has been modified, and if so,
   * asks the user to save the sketch or cancel the export.
   * This prevents issues where an incomplete version of the sketch
   * would be exported, and is a fix for
   * <A HREF="https://download.processing.org/bugzilla/157.html">Bug 157</A>
   */
  protected boolean handleExportCheckModified() {
    if (sketch.isReadOnly()) {
      // if the files are read-only, need to first do a "save as".
      Messages.showMessage(Language.text("export.messages.is_read_only"),
                           Language.text("export.messages.is_read_only.description"));
      return false;
    }

    // don't allow if untitled
    if (sketch.isUntitled()) {
      Messages.showMessage(Language.text("export.messages.cannot_export"),
                           Language.text("export.messages.cannot_export.description"));
      return false;
    }

    if (sketch.isModified()) {
      Object[] options = { Language.text("prompt.ok"), Language.text("prompt.cancel") };
      int result = JOptionPane.showOptionDialog(this,
                                                Language.text("export.unsaved_changes"),
                                                Language.text("menu.file.save"),
                                                JOptionPane.OK_CANCEL_OPTION,
                                                JOptionPane.QUESTION_MESSAGE,
                                                null,
                                                options,
                                                options[0]);

      if (result == JOptionPane.OK_OPTION) {
        handleSave(true);

      } else {
        // why it's not CANCEL_OPTION is beyond me (at least on the mac)
        // but f-- it... let's get this shite done...
        //} else if (result == JOptionPane.CANCEL_OPTION) {
        statusNotice(Language.text("export.notice.cancel.unsaved_changes"));
        //toolbar.clear();
        return false;
      }
    }
    return true;
  }


  public void handleRun() {
    if (isDebuggerEnabled()) {
      // Hitting Run while a sketch is running should restart the sketch
      // https://github.com/processing/processing/issues/3623
      if (debugger.isStarted()) {
        debugger.stopDebug();
      }
      // Don't start the sketch paused, continue until a breakpoint or error
      // https://github.com/processing/processing/issues/3096
      debugger.continueDebug();

    } else {
      handleLaunch(false, false);
    }
  }


  public void handlePresent() {
    handleLaunch(true, false);
  }


  public void handleTweak() {
    autoSave();

    if (sketch.isModified()) {
      Messages.showMessage(Language.text("menu.file.save"),
                           Language.text("tweak_mode.save_before_tweak"));
      return;
    }

    handleLaunch(false, true);
  }


  // ---- Live Preview Mode ----

  public boolean isLiveMode() {
    return liveMode;
  }

  public void toggleLive() {
    liveMode = !liveMode;
    if (liveMode) {
      statusNotice("Live Preview enabled");
      // Reduce error checking delay for faster feedback
      errorChecker.setDelay(300);

      // Show timeline panel
      timelinePanel.setVisible(true);
      timelinePanel.reset();

      // Allocate ports for communication with the sketch
      liveControlPort = 0xD000 + (int)(Math.random() * 0x1000);
      liveVarPort = 0xE000 + (int)(Math.random() * 0x1000);

      // Start live mode controller for timeline
      liveModeController = new LiveModeController(liveControlPort);
      liveModeController.setFrameListener(frame -> {
        EventQueue.invokeLater(() -> timelinePanel.updateFrame(frame));
      });
      liveModeController.start();

      // Start variable inspector
      variableInspector = new VariableInspector();
      variableInspector.setListener(() -> {
        EventQueue.invokeLater(() -> {
          // Update the side panel with latest variable data
          if (liveVariablePanel != null && liveVariablePanel.isVisible()) {
            liveVariablePanel.setCurrentTab(getSketch().getCurrentCodeIndex());
            liveVariablePanel.updateVariables();
          }
        });
      });
      variableInspector.start(liveVarPort);

      // Wire variable inspector and controller to the side panel
      liveVariablePanel.setInspector(variableInspector);
      liveVariablePanel.setLiveModeController(liveModeController);
      liveVariablePanel.setCurrentTab(getSketch().getCurrentCodeIndex());
      liveVariablePanel.setVisible(true);

      // Wire controller to painter for global variable scrubbing
      if (getJavaTextArea() != null &&
          getJavaTextArea().getPainter() instanceof JavaTextAreaPainter jtp) {
        jtp.setLiveModeController(liveModeController);
      }

      // Set toolbar button state
      if (toolbar instanceof JavaToolbar jt) {
        jt.setLiveSelected(true);
      }

      // Launch the sketch with live mode code injection
      doLiveLaunch();
    } else {
      statusNotice("Live Preview disabled");
      disableLiveMode();
    }
    // Rebuild toolbar to update button state
    toolbar.repaint();
    // Revalidate layout for timeline panel visibility
    revalidate();
  }

  /**
   * Clean up all live mode resources. Called when disabling live mode.
   */
  private void disableLiveMode() {
    liveMode = false;
    errorChecker.setDelay(650);
    timelinePanel.setVisible(false);

    if (scheduledLiveRelaunch != null) {
      scheduledLiveRelaunch.cancel(false);
      scheduledLiveRelaunch = null;
    }
    // Stop the running sketch
    try {
      synchronized (runtimeLock) {
        if (runtime != null) {
          runtime.close();
          runtime = null;
        }
      }
    } catch (Exception e) {
      // Ignore errors from stopping
    }
    toolbar.deactivateStop();
    toolbar.deactivateRun();
    if (liveModeController != null) {
      liveModeController.stop();
      liveModeController = null;
    }
    if (variableInspector != null) {
      if (getJavaTextArea() != null &&
          getJavaTextArea().getPainter() instanceof JavaTextAreaPainter jtp) {
        jtp.setLiveHandles(null);
      }
      variableInspector.stop();
      variableInspector = null;
    }
    // Hide variable panel
    if (liveVariablePanel != null) {
      liveVariablePanel.setInspector(null);
      liveVariablePanel.setLiveModeController(null);
      liveVariablePanel.setVisible(false);
    }
    // Clear controller from painter
    if (getJavaTextArea() != null &&
        getJavaTextArea().getPainter() instanceof JavaTextAreaPainter jtp) {
      jtp.setLiveModeController(null);
    }
    // Clean up tweak client
    if (liveTweakClient != null) {
      liveTweakClient.shutdown();
      liveTweakClient = null;
    }
    liveHandles = null;
    // Sync toolbar button state
    if (toolbar instanceof JavaToolbar jt) {
      jt.setLiveSelected(false);
    }
    toolbar.repaint();
    revalidate();
  }

  /**
   * Schedule a debounced relaunch of the sketch for live preview mode.
   * Called from sketchChanged() when live mode is active.
   */
  private void scheduleLiveRelaunch() {
    if (scheduledLiveRelaunch != null) {
      scheduledLiveRelaunch.cancel(false);
    }
    scheduledLiveRelaunch = liveScheduler.schedule(() -> {
      if (!liveMode) return;

      // Check if there are errors before relaunching
      List<Problem> currentProblems = getProblems();
      boolean hasErrors = currentProblems != null &&
        currentProblems.stream().anyMatch(Problem::isError);
      if (hasErrors) {
        return; // Don't relaunch if there are compilation errors
      }

      EventQueue.invokeLater(() -> {
        if (!liveMode) return;
        // Stop and relaunch
        doLiveRelaunch();
      });
    }, LIVE_RELAUNCH_DELAY, TimeUnit.MILLISECONDS);
  }

  /**
   * Launch the sketch with live mode code injection.
   * Used for both the initial launch and relaunches. Must be called on EDT.
   */
  private void doLiveLaunch() {
    // Ensure edits are stored
    sketch.ensureExistence();
    for (SketchCode sc : sketch.getCode()) {
      if (sc.getDocument() != null) {
        try {
          sc.setProgram(sc.getDocumentText());
        } catch (BadLocationException ignored) { }
      }
    }

    // Clear old variable data
    if (variableInspector != null) {
      variableInspector.clear();
    }

    // Clean up previous tweak client
    if (liveTweakClient != null) {
      liveTweakClient.shutdown();
      liveTweakClient = null;
    }
    liveHandles = null;

    toolbar.activateRun();

    // Build and launch on background thread with live mode code injection
    new Thread(() -> {
      try {
        List<List<Handle>> outHandles = new ArrayList<>();
        synchronized (runtimeLock) {
          runtimeLaunchRequested = false;
          RunnerListener listener = new RunnerListenerEdtAdapter(JavaEditor.this);
          runtime = jmode.handleLiveLaunch(sketch, listener,
                                           liveControlPort, liveVarPort,
                                           outHandles);
        }
        // Wire up tweak client and handles for hot-swap scrubbing
        if (!outHandles.isEmpty()) {
          TweakClient tc = new TweakClient(liveControlPort);
          for (List<Handle> list : outHandles) {
            for (Handle h : list) {
              h.setTweakClient(tc);
            }
          }
          EventQueue.invokeLater(() -> {
            liveTweakClient = tc;
            liveHandles = outHandles;
            // Pass handles to the painter for scrub mapping
            if (getJavaTextArea().getPainter() instanceof JavaTextAreaPainter jtp) {
              jtp.setLiveHandles(liveHandles);
            }
          });
        }
        // Send initial pings so the sketch learns the editor's reply port.
        // The sketch captures the source port from the first received packet,
        // which enables it to send frame count reports back to the editor.
        for (int delay = 200; delay <= 1000; delay += 200) {
          liveScheduler.schedule(() -> {
            if (liveModeController != null) {
              liveModeController.sendResume();
            }
          }, delay, TimeUnit.MILLISECONDS);
        }
      } catch (Exception e) {
        EventQueue.invokeLater(() -> statusError(e));
      }
    }).start();
  }

  /**
   * Stop the current sketch and relaunch it. Must be called on EDT.
   */
  private void doLiveRelaunch() {
    // Stop the current runtime without clearing the console
    try {
      synchronized (runtimeLock) {
        if (runtimeLaunchRequested) {
          runtimeLaunchRequested = false;
        }
        if (runtime != null) {
          runtime.close();
          runtime = null;
        }
      }
    } catch (Exception e) {
      // Ignore errors from stopping
    }

    doLiveLaunch();
  }

  // ---- End Live Preview Mode ----


  // ---- Arduino Integration ----

  /**
   * Show a popup menu with detected Arduino serial ports.
   * Called when the Arduino toolbar button is clicked.
   */
  public void showArduinoPortMenu(java.awt.Component anchor) {
    JPopupMenu menu = new JPopupMenu();
    String currentPort = Preferences.get("arduino.port");

    // Scan for ports using system commands
    List<String> ports = detectSerialPorts();

    if (ports.isEmpty()) {
      JMenuItem noBoard = new JMenuItem("No Arduino detected");
      noBoard.setEnabled(false);
      menu.add(noBoard);
    } else {
      for (String port : ports) {
        JMenuItem item = new JMenuItem(port);
        if (port.equals(currentPort)) {
          item.setText("\u2713 " + port);  // checkmark
        }
        item.addActionListener(e -> selectArduinoPort(port));
        menu.add(item);
      }
    }

    menu.addSeparator();

    // Auto-detect option
    JMenuItem autoItem = new JMenuItem("Auto-detect");
    if (currentPort == null || currentPort.isEmpty()) {
      autoItem.setText("\u2713 Auto-detect");
    }
    autoItem.addActionListener(e -> selectArduinoPort(null));
    menu.add(autoItem);

    // Manual port entry
    JMenuItem manualItem = new JMenuItem("Enter port manually\u2026");
    manualItem.addActionListener(e -> {
      String input = (String) JOptionPane.showInputDialog(
        this, "Serial port name:", "Arduino Port",
        JOptionPane.PLAIN_MESSAGE, null, null, currentPort);
      if (input != null && !input.trim().isEmpty()) {
        selectArduinoPort(input.trim());
      }
    });
    menu.add(manualItem);

    menu.addSeparator();

    // Rescan
    JMenuItem rescanItem = new JMenuItem("Rescan ports");
    rescanItem.addActionListener(e -> showArduinoPortMenu(anchor));
    menu.add(rescanItem);

    menu.show(anchor, 0, anchor.getHeight());
  }


  /**
   * Set the selected Arduino port and update toolbar state.
   */
  private void selectArduinoPort(String port) {
    if (port == null || port.isEmpty()) {
      Preferences.set("arduino.port", "");
      statusNotice("Arduino: auto-detect mode");
    } else {
      Preferences.set("arduino.port", port);
      statusNotice("Arduino port: " + port);
    }
    // Update toolbar button visual state
    if (toolbar instanceof JavaToolbar jt) {
      jt.setArduinoSelected(port != null && !port.isEmpty());
    }
  }


  /**
   * Detect serial ports likely to be Arduino boards.
   * Uses system commands to find USB serial devices.
   */
  private List<String> detectSerialPorts() {
    List<String> ports = new ArrayList<>();
    try {
      String os = System.getProperty("os.name").toLowerCase();
      ProcessBuilder pb;
      if (os.contains("mac")) {
        pb = new ProcessBuilder("bash", "-c",
          "ls /dev/tty.usb* /dev/cu.usb* 2>/dev/null");
      } else if (os.contains("linux")) {
        pb = new ProcessBuilder("bash", "-c",
          "ls /dev/ttyACM* /dev/ttyUSB* 2>/dev/null");
      } else {
        // Windows — list COM ports from registry
        pb = new ProcessBuilder("cmd", "/c",
          "reg query HKLM\\HARDWARE\\DEVICEMAP\\SERIALCOMM 2>nul");
      }
      pb.redirectErrorStream(true);
      Process p = pb.start();
      try (var reader = new java.io.BufferedReader(
             new java.io.InputStreamReader(p.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          line = line.trim();
          if (!line.isEmpty()) {
            if (os.contains("win")) {
              // Windows reg output: "    \Device\Serial0    REG_SZ    COM3"
              int comIdx = line.lastIndexOf("COM");
              if (comIdx >= 0) {
                ports.add(line.substring(comIdx).trim());
              }
            } else {
              ports.add(line);
            }
          }
        }
      }
      p.waitFor();
    } catch (Exception e) {
      // Silently fail — will show "No Arduino detected"
    }
    return ports;
  }

  /**
   * Upload StandardFirmata to the connected Arduino board.
   * Uses arduino-cli, downloading it if necessary.
   */
  private void uploadStandardFirmata() {
    // Determine port
    String port = Preferences.get("arduino.port");
    if (port == null || port.isEmpty()) {
      List<String> ports = detectSerialPorts();
      if (ports.isEmpty()) {
        statusError("No Arduino board detected. Plug in a board and try again.");
        return;
      }
      port = ports.get(0);
    }

    final String targetPort = port;
    statusNotice("Uploading StandardFirmata to " + targetPort + "...");

    // Stop any running sketch that might be holding the serial port open
    try {
      synchronized (runtimeLock) {
        if (runtime != null) {
          runtime.close();
          runtime = null;
        }
      }
    } catch (Exception ex) { /* ignore */ }

    // Run in background thread to avoid blocking EDT
    new Thread(() -> {
      try {
        // Find or get arduino-cli
        String cli = findArduinoCli();
        if (cli == null) {
          cli = installArduinoCli();
        }
        if (cli == null) {
          EventQueue.invokeLater(() ->
            statusError("Could not find or install arduino-cli."));
          return;
        }

        // Install Arduino AVR core and Firmata library
        final String cliPath = cli;
        runArduinoCommand(cliPath, "core", "install", "arduino:avr");
        runArduinoCommand(cliPath, "lib", "install", "Firmata");
        runArduinoCommand(cliPath, "lib", "install", "Servo");

        // Find the StandardFirmata example sketch
        String firmataPath = findFirmataExample();
        if (firmataPath == null) {
          EventQueue.invokeLater(() ->
            statusError("StandardFirmata example not found. Install the Firmata library."));
          return;
        }

        // Detect board type
        String fqbn = detectBoardFqbn(cliPath, targetPort);
        System.out.println("[Arduino] Detected board: " + fqbn);

        // Compile and upload
        System.out.println("[Arduino] Uploading StandardFirmata from: " + firmataPath);
        String[] result = runArduinoCommandCapture(cliPath,
          "compile", "--upload",
          "-b", fqbn,
          "-p", targetPort,
          firmataPath);

        if (result[1].contains("Error") || result[1].contains("error")) {
          String errMsg = result[1].trim();
          EventQueue.invokeLater(() -> {
            statusError("Upload failed: " + errMsg);
            System.err.println("[Arduino] " + errMsg);
          });
        } else {
          EventQueue.invokeLater(() ->
            statusNotice("StandardFirmata uploaded! You can now run Arduino sketches."));
        }
      } catch (Exception e) {
        EventQueue.invokeLater(() ->
          statusError("Upload error: " + e.getMessage()));
      }
    }, "FirmataUploader").start();
  }


  private String findArduinoCli() {
    // Check PATH
    String path = System.getenv("PATH");
    if (path != null) {
      for (String dir : path.split(File.pathSeparator)) {
        File f = new File(dir, "arduino-cli");
        if (f.exists() && f.canExecute()) return f.getAbsolutePath();
      }
    }
    // Check local install
    File local = new File(System.getProperty("user.home"),
      ".processing/arduino-cli/arduino-cli");
    if (local.exists() && local.canExecute()) return local.getAbsolutePath();
    return null;
  }


  private String installArduinoCli() {
    try {
      statusNotice("Downloading arduino-cli...");
      String arch = System.getProperty("os.arch").toLowerCase();
      String platform = (arch.contains("aarch64") || arch.contains("arm")) ?
        "macOS_ARM64" : "macOS_64bit";
      String url = String.format(
        "https://github.com/arduino/arduino-cli/releases/download/v1.1.1/arduino-cli_1.1.1_%s.tar.gz",
        platform);

      File installDir = new File(System.getProperty("user.home"),
        ".processing/arduino-cli");
      installDir.mkdirs();

      File archive = new File(installDir, "arduino-cli.tar.gz");

      // Download
      ProcessBuilder dl = new ProcessBuilder("curl", "-L", "-o",
        archive.getAbsolutePath(), url);
      dl.inheritIO();
      dl.start().waitFor();

      // Extract
      ProcessBuilder ex = new ProcessBuilder("tar", "xzf",
        archive.getAbsolutePath(), "-C", installDir.getAbsolutePath());
      ex.inheritIO();
      ex.start().waitFor();

      archive.delete();

      File cli = new File(installDir, "arduino-cli");
      if (cli.exists()) {
        cli.setExecutable(true);
        // Initialize config
        runArduinoCommand(cli.getAbsolutePath(), "config", "init", "--overwrite");
        statusNotice("arduino-cli installed.");
        return cli.getAbsolutePath();
      }
    } catch (Exception e) {
      System.err.println("[Arduino] Install failed: " + e.getMessage());
    }
    return null;
  }


  private String findFirmataExample() {
    String home = System.getProperty("user.home");
    String[][] candidates = {
      {home, "Arduino", "libraries", "Firmata", "examples", "StandardFirmata"},
      {home, "Documents", "Arduino", "libraries", "Firmata", "examples", "StandardFirmata"},
      {home, ".arduino15", "libraries", "Firmata", "examples", "StandardFirmata"},
    };
    for (String[] parts : candidates) {
      File dir = new File(String.join(File.separator, parts));
      if (dir.exists() && new File(dir, "StandardFirmata.ino").exists()) {
        return dir.getAbsolutePath();
      }
    }
    return null;
  }


  /**
   * Detect the board FQBN using arduino-cli board list.
   * Falls back to arduino:avr:uno if detection fails.
   */
  private String detectBoardFqbn(String cli, String port) {
    try {
      String[] result = runArduinoCommandCapture(cli, "board", "list", "--json");
      String output = result[0];

      // Find our port in the JSON, then search backward for the nearest "fqbn".
      // JSON structure per detected_port block:
      //   "matching_boards": [{"fqbn": "..."}], "port": {"address": "/dev/..."}
      // So "fqbn" comes BEFORE "address" in the same block.
      String portVariant = port.replace("/dev/cu.", "/dev/tty.");
      int portIdx = output.indexOf("\"" + port + "\"");
      if (portIdx < 0) portIdx = output.indexOf("\"" + portVariant + "\"");

      if (portIdx >= 0) {
        String before = output.substring(0, portIdx);
        int fqbnIdx = before.lastIndexOf("\"fqbn\"");
        if (fqbnIdx >= 0) {
          // Extract the fqbn value: "fqbn": "arduino:avr:mega"
          int colonIdx = output.indexOf(":", fqbnIdx + 5);
          int valStart = output.indexOf("\"", colonIdx) + 1;
          int valEnd = output.indexOf("\"", valStart);
          if (valStart > 0 && valEnd > valStart) {
            String fqbn = output.substring(valStart, valEnd);
            if (fqbn.contains(":")) {
              System.out.println("[Arduino] Auto-detected board: " + fqbn);
              return fqbn;
            }
          }
        }
      }
    } catch (Exception e) {
      System.err.println("[Arduino] Board detection failed: " + e.getMessage());
    }

    System.out.println("[Arduino] Could not auto-detect board, defaulting to arduino:avr:uno");
    return "arduino:avr:uno";
  }


  private void runArduinoCommand(String cli, String... args) {
    try {
      List<String> cmd = new ArrayList<>();
      cmd.add(cli);
      for (String a : args) cmd.add(a);
      ProcessBuilder pb = new ProcessBuilder(cmd);
      pb.redirectErrorStream(true);
      Process p = pb.start();
      try (BufferedReader reader = new BufferedReader(
             new InputStreamReader(p.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          System.out.println("[arduino-cli] " + line);
        }
      }
      p.waitFor();
    } catch (Exception e) {
      System.err.println("[arduino-cli] " + e.getMessage());
    }
  }


  private String[] runArduinoCommandCapture(String cli, String... args) {
    try {
      List<String> cmd = new ArrayList<>();
      cmd.add(cli);
      for (String a : args) cmd.add(a);
      ProcessBuilder pb = new ProcessBuilder(cmd);
      Process p = pb.start();
      StringBuilder stdout = new StringBuilder();
      StringBuilder stderr = new StringBuilder();
      try (BufferedReader outR = new BufferedReader(
             new InputStreamReader(p.getInputStream()));
           BufferedReader errR = new BufferedReader(
             new InputStreamReader(p.getErrorStream()))) {
        String line;
        while ((line = outR.readLine()) != null) {
          stdout.append(line).append("\n");
          System.out.println("[arduino-cli] " + line);
        }
        while ((line = errR.readLine()) != null) {
          stderr.append(line).append("\n");
        }
      }
      p.waitFor();
      return new String[] { stdout.toString(), stderr.toString() };
    } catch (Exception e) {
      return new String[] { "", e.getMessage() };
    }
  }

  // ---- End Arduino Integration ----


  protected void handleLaunch(boolean present, boolean tweak) {
    prepareRun();
    toolbar.activateRun();
    synchronized (runtimeLock) {
      runtimeLaunchRequested = true;
    }
    new Thread(() -> {
      try {
        synchronized (runtimeLock) {
          if (runtimeLaunchRequested) {
            runtimeLaunchRequested = false;
            RunnerListener listener = new RunnerListenerEdtAdapter(JavaEditor.this);
            if (!tweak) {
              runtime = jmode.handleLaunch(sketch, listener, present);
            } else {
              runtime = jmode.handleTweak(sketch, listener, JavaEditor.this);
            }
          }
        }
      } catch (Exception e) {
        EventQueue.invokeLater(() -> statusError(e));
      }
    }).start();
  }


  /**
   * Event handler called when hitting the stop button. Stops a running debug
   * session or performs standard stop action if not currently debugging.
   */
  public void handleStop() {
    if (debugger.isStarted()) {
      debugger.stopDebug();

    } else {
      // Turn off live mode when user explicitly stops
      if (liveMode) {
        disableLiveMode();
      }

      toolbar.activateStop();

      try {
        synchronized (runtimeLock) {
          if (runtimeLaunchRequested) {
            // Cancel the launch before the runtime was created
            runtimeLaunchRequested = false;
          }
          if (runtime != null) {
            // Cancel the launch after the runtime was created
            runtime.close();  // kills the window
            runtime = null;
          }
        }
      } catch (Exception e) {
        statusError(e);
      }

      toolbar.deactivateStop();
      toolbar.deactivateRun();

      // Rebuild toolbar to clear live button state
      toolbar.repaint();

      // focus the PDE again after quitting presentation mode [toxi 030903]
      toFront();
    }
  }


  public void onRunnerExiting(Runner runner) {
    synchronized (runtimeLock) {
      if (this.runtime == runner) {
        // In live mode, don't deactivate run — we'll relaunch
        if (!liveMode) {
          deactivateRun();
        }
      }
    }
  }


//  /** Toggle a breakpoint on the current line. */
//  public void toggleBreakpoint() {
//    toggleBreakpoint(getCurrentLineID().lineIdx());
//  }


  @Override
  public void toggleBreakpoint(int lineIndex) {
    debugger.toggleBreakpoint(lineIndex);
  }


  public boolean handleSaveAs() {
    //System.out.println("handleSaveAs");
    String oldName = getSketch().getCode(0).getFileName();
    //System.out.println("old name: " + oldName);
    boolean saved = super.handleSaveAs();
    if (saved) {
      // re-set breakpoints in first tab (name has changed)
      List<LineBreakpoint> bps = debugger.getBreakpoints(oldName);
      debugger.clearBreakpoints(oldName);
      String newName = getSketch().getCode(0).getFileName();
      //System.out.println("new name: " + newName);
      for (LineBreakpoint bp : bps) {
        LineID line = new LineID(newName, bp.lineID().lineIdx());
        //System.out.println("setting: " + line);
        debugger.setBreakpoint(line);
      }
      // add breakpoint marker comments to source file
      for (SketchCode code : getSketch().getCode()) {
        addBreakpointComments(code.getFileName());
      }

      // set new name of variable inspector
      //inspector.setTitle(getSketch().getName());
    }
    return saved;
  }


  /**
   * Add import statements to the current tab for all packages inside
   * the specified jar file.
   */
  public void handleImportLibrary(String libraryName) {
    // make sure the user didn't hide the sketch folder
    sketch.ensureExistence();

    // import statements into the main sketch file (code[0])
    // if the current code is a .java file, insert into current
    //if (current.flavor == PDE) {
    if (mode.isDefaultExtension(sketch.getCurrentCode())) {
      sketch.setCurrentCode(0);
    }

    Library lib = mode.findLibraryByName(libraryName);
    if (lib == null) {
      statusError("Unable to locate library: "+libraryName);
      return;
    }

    // could also scan the text in the file to see if each import
    // statement is already in there, but if the user has the import
    // commented out, then this will be a problem.
    StringList list = lib.getImports(); // ask the library for its imports
    if (list == null) {
      // Default to old behavior and load each package in the primary jar
      list = Util.packageListFromClassPath(lib.getJarPath());
    }

    StringBuilder sb = new StringBuilder();
//    for (int i = 0; i < list.length; i++) {
    for (String item : list) {
      sb.append("import ");
//      sb.append(list[i]);
      sb.append(item);
      sb.append(".*;\n");
    }
    sb.append('\n');
    sb.append(getText());
    setText(sb.toString());
    setSelection(0, 0);  // scroll to start
    sketch.setModified(true);
  }


  @Override
  public void librariesChanged() {
    preprocService.notifyLibrariesChanged();
  }


  @Override
  public void codeFolderChanged() {
    preprocService.notifyCodeFolderChanged();
  }


  @Override
  public void sketchChanged() {
    errorChecker.notifySketchChanged();
    preprocService.notifySketchChanged();

    // In live mode, schedule a debounced relaunch (unless suppressed during scrubbing)
    if (liveMode && !suppressLiveRelaunch) {
      scheduleLiveRelaunch();
    }
  }


  public void addDocumentListener(Document doc) {
    if (doc != null) {
      doc.addDocumentListener(new DocumentListener() {
        @Override
        public void insertUpdate(DocumentEvent e) {
          sketchChanged();
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
          sketchChanged();
        }

        @Override
        public void changedUpdate(DocumentEvent e) {
          sketchChanged();
        }
      });
    }
  }


  public void showReference(String name) {
    if (useReferenceServer == null) {
      // Because of this, it should be possible to create your own dist
      // that includes the reference by simply adding it to modes/java.
      File referenceZip = new File(mode.getFolder(), "reference.zip");
      if (!referenceZip.exists()) {
        // For Java Mode (the default), check for a reference.zip in the root
        // of the sketchbook folder. If other Modes subclass JavaEditor and
        // don't override this function, it may cause a little trouble.
        referenceZip = getOfflineReferenceFile();
      }
      if (referenceZip.exists()) {
        try {
          referenceServer = new ReferenceServer(referenceZip, REFERENCE_PORT);
          useReferenceServer = true;

        } catch (IOException e) {
          Messages.showWarning("Reference Server Problem", "Error while starting the documentation server.");
        }

      } else {
        useReferenceServer = false;
      }
    }

    if (useReferenceServer) {
      String url = referenceServer.getPrefix() + "reference/" + name;
      Platform.openURL(url);

    } else {
      File file = new File(mode.getReferenceFolder(), name);
      if (file.exists()) {
        showReferenceFile(file);
      } else {
        // Offline reference (temporarily) removed in 4.0 beta 9
        // https://github.com/processing/processing4/issues/524
        Platform.openURL("https://processing.org/reference/" + name);
      }
    }
  }


  private File getOfflineReferenceFile() {
    return new File(Base.getSketchbookFolder(), "reference.zip");
  }


  /*
  private boolean isReferenceDownloaded() {
    return getOfflineReferenceFile().exists();
  }
  */

  private String getReferenceDownloadUrl() {
    String versionName = Base.getVersionName();
    int revisionInt = Base.getRevision();
    String revision = String.valueOf(revisionInt);

    if ("unspecified".equals(versionName) || revisionInt == Integer.MAX_VALUE) {
      return "https://github.com/processing/processing4/releases/download/processing-1300-4.4.0/processing-4.4.0-reference.zip";
    }

    String url = String.format(
            "https://github.com/processing/processing4/releases/download/processing-%s-%s/processing-%s-reference.zip",
            revision, versionName, versionName);
    System.out.println("Generated URL: " + url);
    return url;
  }

  private void downloadReference() {
    try {
      URL source = new URL(getReferenceDownloadUrl());
      HttpURLConnection conn = (HttpURLConnection) source.openConnection();
      HttpURLConnection.setFollowRedirects(true);
      conn.setConnectTimeout(15 * 1000);
      conn.setReadTimeout(60 * 1000);
      conn.setRequestMethod("GET");
      conn.connect();

      int length = conn.getContentLength();
//      float size = (length >> 10) / 1024f;
      //float size = (length / 1000) / 1000f;
//      String msg =
//        "Downloading reference (" + PApplet.nf(size, 0, 1) + " MB)… ";
      String mb = PApplet.nf((length >> 10) / 1024f, 0, 1);
      if (mb.endsWith(".0")) {
        mb = mb.substring(0, mb.length() - 2);  // don't show .0
      }
      ProgressMonitorInputStream input =
        new ProgressMonitorInputStream(this,
          "Downloading reference (" + mb + " MB)… ", conn.getInputStream());
      input.getProgressMonitor().setMaximum(length);
//      ProgressMonitor monitor = input.getProgressMonitor();
//      monitor.setMaximum(length);
      PApplet.saveStream(getOfflineReferenceFile(), input);
      // reset the internal handling for the reference server
      useReferenceServer = null;

    } catch (InterruptedIOException iioe) {
      // download canceled

    } catch (IOException e) {
      Messages.showWarning("Error downloading reference",
        "Could not download the reference. Try again later.", e);
    }
  }


  public void statusError(String what) {
    super.statusError(what);
//    new Exception("deactivating RUN").printStackTrace();
//    toolbar.deactivate(JavaToolbar.RUN);
    toolbar.deactivateRun();
  }


  public void internalCloseRunner() {
    // Added temporarily to dump error log. TODO: Remove this later [mk29]
    //if (JavaMode.errorLogsEnabled) {
    //  writeErrorsToFile();
    //}
    handleStop();
  }


  // . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .

  // Additions from PDE X, Debug Mode, Twerk Mode...


  /**
   * Used instead of the windowClosing event handler, since it's not called on
   * mode switch. Called when closing the editor window. Stops running debug
   * sessions and kills the variable inspector window.
   */
  @Override
  public void dispose() {
    //System.out.println("window dispose");
    // Clean up live mode resources
    disableLiveMode();
    liveScheduler.shutdownNow();

    // quit running debug session
    if (debugger.isEnabled()) {
      debugger.stopDebug();
    }
    debugger.dispose();
    preprocService.dispose();

    inspect.dispose();
    usage.dispose();
    rename.dispose();

    errorChecker.dispose();
    if (astViewer != null) {
      astViewer.dispose();
    }
    super.dispose();
  }


  @Override
  public boolean isDebuggerEnabled() {
    return debugger.isEnabled();
  }


  @Override
  public JMenu buildModeMenu() {
    return modeMenu = new JMenu(Language.text("menu.debug"));
  }


  // handleOpenInternal() only called by the Editor constructor, meaning that
  // this code is all useless. All these things will be in their default state.
//  /**
//   * Event handler called when loading another sketch in this editor.
//   * Clears breakpoints of previous sketch.
//   * @return true if a sketch was opened, false if aborted
//   */
//  @Override
//  protected void handleOpenInternal(String path) throws EditorException {
//    super.handleOpenInternal(path);
//
//    // should already been stopped (open calls handleStop)
//    if (debugger != null) {
//      debugger.clearBreakpoints();
//    }
//    clearBreakpointedLines();
//    variableInspector().reset();
//  }


  /**
   * Extract breakpointed lines from source code marker comments. This removes
   * marker comments from the editor text. Intended to be called on loading a
   * sketch, since re-setting the sketches contents after removing the markers
   * will clear all breakpoints.
   *
   * @return the list of {@link LineID}s where breakpoint marker comments were
   * removed from.
   */
  protected List<LineID> stripBreakpointComments() {
    List<LineID> bps = new ArrayList<>();
    // iterate over all tabs
    Sketch sketch = getSketch();
    for (int i = 0; i < sketch.getCodeCount(); i++) {
      SketchCode tab = sketch.getCode(i);
      String code = tab.getProgram();
      String[] lines = code.split("\\r?\\n"); // newlines not included
      //System.out.println(code);

      // scan code for breakpoint comments
      int lineIdx = 0;
      for (String line : lines) {
        //System.out.println(line);
        if (line.endsWith(breakpointMarkerComment)) {
          LineID lineID = new LineID(tab.getFileName(), lineIdx);
          bps.add(lineID);
          //System.out.println("found breakpoint: " + lineID);
          // got a breakpoint
          //dbg.setBreakpoint(lineID);
          int index = line.lastIndexOf(breakpointMarkerComment);
          lines[lineIdx] = line.substring(0, index);
        }
        lineIdx++;
      }
      //tab.setProgram(code);
      code = PApplet.join(lines, "\n");
      setTabContents(tab.getFileName(), code);
    }
    return bps;
  }


  /**
   * Add breakpoint marker comments to the source file of a specific tab. This
   * acts on the source file on disk, not the editor text. Intended to be
   * called just after saving the sketch.
   *
   * @param tabFilename the tab file name
   */
  protected void addBreakpointComments(String tabFilename) {
    SketchCode tab = getTab(tabFilename);
    if (tab == null) {
      // this method gets called twice when saving sketch for the first time
      // once with new name and another with old(causing NPE). Keep an eye out
      // for potential issues. See #2675. TODO:
      Messages.err("Illegal tab name to addBreakpointComments() " + tabFilename);
      return;
    }
    List<LineBreakpoint> bps = debugger.getBreakpoints(tab.getFileName());

    // load the source file
    ////switched to using methods provided by the SketchCode class
    // File sourceFile = new File(sketch.getFolder(), tab.getFileName());
    //System.out.println("file: " + sourceFile);
    try {
      tab.load();
      String code = tab.getProgram();
      //System.out.println("code: " + code);
      String[] lines = code.split("\\r?\\n"); // newlines not included
      for (LineBreakpoint bp : bps) {
        //System.out.println("adding bp: " + bp.lineID());
        lines[bp.lineID().lineIdx()] += breakpointMarkerComment;
      }
      code = PApplet.join(lines, "\n");
      //System.out.println("new code: " + code);
      tab.setProgram(code);
      tab.save();
    } catch (IOException ex) {
      Messages.err(null, ex);
    }
  }


  @Override
  public boolean handleSave(boolean immediately) {
    // note modified tabs
    final List<String> modified = new ArrayList<>();
    for (int i = 0; i < getSketch().getCodeCount(); i++) {
      SketchCode tab = getSketch().getCode(i);
      if (tab.isModified()) {
        modified.add(tab.getFileName());
      }
    }

    boolean saved = super.handleSave(immediately);
    if (saved) {
      if (immediately) {
        for (String tabFilename : modified) {
          addBreakpointComments(tabFilename);
        }
      } else {
        EventQueue.invokeLater(() -> {
          for (String tabFilename : modified) {
            addBreakpointComments(tabFilename);
          }
        });
      }
    }
    //  if file location has changed, update autosaver
    // autosaver.reloadAutosaveDir();
    return saved;
  }


  /**
   * Set text contents of a specific tab. Updates underlying document and text
   * area. Clears Breakpoints.
   *
   * @param tabFilename the tab file name
   * @param code the text to set
   */
  protected void setTabContents(String tabFilename, String code) {
    // remove all breakpoints of this tab
    debugger.clearBreakpoints(tabFilename);

    SketchCode currentTab = getCurrentTab();

    // set code of tab
    SketchCode tab = getTab(tabFilename);
    if (tab != null) {
      tab.setProgram(code);
      // this updates document and text area
      // TODO: does this have any negative effects? (setting the doc to null)
      tab.setDocument(null);
      setCode(tab);

      // switch back to original tab
      setCode(currentTab);
    }
  }


  public void clearConsole() {
    console.clear();
  }


  public void clearSelection() {
    setSelection(getCaretOffset(), getCaretOffset());
  }


  /**
   * Select a line in the current tab.
   * @param lineIdx 0-based line number
   */
  public void selectLine(int lineIdx) {
    setSelection(getLineStartOffset(lineIdx), getLineStopOffset(lineIdx));
  }


  /**
   * Set the cursor to the start of a line.
   * @param lineIdx 0-based line number
   */
  public void cursorToLineStart(int lineIdx) {
    setSelection(getLineStartOffset(lineIdx), getLineStartOffset(lineIdx));
  }


  /**
   * Set the cursor to the end of a line.
   * @param lineIdx 0-based line number
   */
  public void cursorToLineEnd(int lineIdx) {
    setSelection(getLineStopOffset(lineIdx), getLineStopOffset(lineIdx));
  }


  /**
   * Switch to a tab.
   * @param tabFileName the file name identifying the tab. (as in
   * {@link SketchCode#getFileName()})
   */
  public void switchToTab(String tabFileName) {
    Sketch s = getSketch();
    for (int i = 0; i < s.getCodeCount(); i++) {
      if (tabFileName.equals(s.getCode(i).getFileName())) {
        s.setCurrentCode(i);
        break;
      }
    }
  }


  public Debugger getDebugger() {
    return debugger;
  }


  /**
   * Access the custom text area object.
   * @return the text area object
   */
  public JavaTextArea getJavaTextArea() {
    return (JavaTextArea) textarea;
  }


  public PreprocService getPreprocessingService() {
    return preprocService;
  }


  /**
   * Grab current contents of the sketch window, advance the console, stop any
   * other running sketches, auto-save the user's code... not in that order.
   */
  @Override
  public void prepareRun() {
    autoSave();
    super.prepareRun();
    downloadImports();
    preprocService.cancel();
  }


  /**
   * Downloads libraries that have been imported, that aren't available as a
   * LocalContribution, but that have an AvailableContribution associated with
   * them.
   */
  protected void downloadImports() {
    for (SketchCode sc : sketch.getCode()) {
      if (sc.isExtension("pde")) {
        String tabCode = sc.getProgram();

        List<ImportStatement> imports =  SourceUtil.parseProgramImports(tabCode);

        if (!imports.isEmpty()) {
          ArrayList<String> importHeaders = new ArrayList<>();
          for (ImportStatement importStatement : imports) {
            importHeaders.add(importStatement.getFullMemberName());
          }
          List<AvailableContribution> installLibsHeaders =
            getNotInstalledAvailableLibs(importHeaders);
          if (!installLibsHeaders.isEmpty()) {
            StringBuilder libList = new StringBuilder("Would you like to install them now?");
            for (AvailableContribution ac : installLibsHeaders) {
              libList.append("\n  • ").append(ac.getName());
            }
            int option = Messages.showYesNoQuestion(this,
                Language.text("contrib.import.dialog.title"),
                Language.text("contrib.import.dialog.primary_text"),
                libList.toString());

            if (option == JOptionPane.YES_OPTION) {
              ContributionManager.downloadAndInstallOnImport(base, installLibsHeaders);
            }
          }
        }
      }
    }
  }


  /**
   * Returns a list of AvailableContributions of those libraries that the user
   * wants imported, but that are not installed.
   */
  private List<AvailableContribution> getNotInstalledAvailableLibs(List<String> importHeadersList) {
    Map<String, Contribution> importMap =
      ContributionListing.getInstance().getLibraryExports();
    List<AvailableContribution> libList = new ArrayList<>();
    for (String importHeaders : importHeadersList) {
      int dot = importHeaders.lastIndexOf('.');
      String entry = (dot == -1) ? importHeaders : importHeaders.substring(0,
          dot);

      if (entry.startsWith("java.") ||
          entry.startsWith("javax.") ||
          entry.startsWith("processing.")) {
        continue;
      }

      Library library;
      try {
        library = this.getMode().getLibrary(entry);
        if (library == null) {
          Contribution c = importMap.get(importHeaders);
          if (c instanceof AvailableContribution) {  // also checks null
            libList.add((AvailableContribution) c);// System.out.println(importHeaders
                                                   // + "not found");
          }
        }
      } catch (Exception e) {
        // Not gonna happen (hopefully)
        Contribution c = importMap.get(importHeaders);
        if (c instanceof AvailableContribution) {  // also checks null
          libList.add((AvailableContribution) c);// System.out.println(importHeaders
                                                 // + "not found");
        }
      }
    }
    return libList;
  }


  /**
   * Displays a JDialog prompting the user to save when the user hits
   * run/present/etc.
   */
  protected void autoSave() {
    if (!JavaMode.autoSaveEnabled) {
      return;
    }

    try {
      if (sketch.isModified() && !sketch.isUntitled()) {
        if (JavaMode.autoSavePromptEnabled) {
          final JDialog autoSaveDialog =
            new JDialog(base.getActiveEditor(), getSketch().getName(), true);
          Container container = autoSaveDialog.getContentPane();

          JPanel panelMain = new JPanel();
          panelMain.setBorder(BorderFactory.createEmptyBorder(4, 0, 2, 2));
          panelMain.setLayout(new BoxLayout(panelMain, BoxLayout.PAGE_AXIS));

          JPanel panelLabel = new JPanel(new FlowLayout(FlowLayout.LEFT));
          JLabel label = new JLabel("<html><body>&nbsp;There are unsaved"
                                    + " changes in your sketch.<br />"
                                    + "&nbsp;&nbsp;&nbsp; Do you want to save it before"
                                    + " running? </body></html>");
          label.setFont(new Font(
              label.getFont().getName(),
              Font.PLAIN,
              Toolkit.zoom(label.getFont().getSize() + 1)
          ));
          panelLabel.add(label);
          panelMain.add(panelLabel);
          final JCheckBox dontRedisplay = new JCheckBox("Remember this decision");
          JPanel panelButtons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 2));

          JButton btnRunSave = new JButton("Save and Run");
          btnRunSave.addActionListener(e -> {
            handleSave(true);
            if (dontRedisplay.isSelected()) {
              JavaMode.autoSavePromptEnabled = !dontRedisplay.isSelected();
              JavaMode.defaultAutoSaveEnabled = true;
              jmode.savePreferences();
            }
            autoSaveDialog.dispose();
          });
          panelButtons.add(btnRunSave);

          JButton btnRunNoSave = new JButton("Run, Don't Save");
          btnRunNoSave.addActionListener(e -> {
            if (dontRedisplay.isSelected()) {
              JavaMode.autoSavePromptEnabled = !dontRedisplay.isSelected();
              JavaMode.defaultAutoSaveEnabled = false;
              jmode.savePreferences();
            }
            autoSaveDialog.dispose();
          });
          panelButtons.add(btnRunNoSave);
          panelMain.add(panelButtons);

          JPanel panelCheck = new JPanel();
          panelCheck.setLayout(new FlowLayout(FlowLayout.CENTER, 0, 0));
          panelCheck.add(dontRedisplay);
          panelMain.add(panelCheck);

          container.add(panelMain);

          autoSaveDialog.setResizable(false);
          autoSaveDialog.pack();
          autoSaveDialog.setLocationRelativeTo(base.getActiveEditor());
          autoSaveDialog.setVisible(true);

        } else if (JavaMode.defaultAutoSaveEnabled) {
          handleSave(true);
        }
      }
    } catch (Exception e) {
      statusError(e);
    }
  }


  public void activateRun() {
    debugger.enableMenuItem(false);
    toolbar.activateRun();
  }


  /**
   * Deactivate the Run button. This is called by Runner to notify that the
   * sketch has stopped running, usually in response to an error (or maybe
   * the sketch completing and exiting?) Tools should not call this function.
   * To initiate a "stop" action, call handleStop() instead.
   */
  public void deactivateRun() {
    toolbar.deactivateRun();
    debugger.enableMenuItem(true);
  }


  /*
  protected void activateDebug() {
    activateRun();
  }


  public void deactivateDebug() {
    deactivateRun();
  }
   */


  public void activateContinue() { }

  public void deactivateContinue() { }

  public void activateStep() { }

  public void deactivateStep() { }


  public void toggleDebug() {
//    debugEnabled = !debugEnabled;

    debugger.toggleEnabled();
    rebuildToolbar();
    repaint();  // show/hide breakpoints in the gutter

    /*
    if (debugEnabled) {
      debugItem.setText(Language.text("menu.debug.disable"));
    } else {
      debugItem.setText(Language.text("menu.debug.enable"));
    }
    inspector.setVisible(debugEnabled);

    for (Component item : debugMenu.getMenuComponents()) {
      if (item instanceof JMenuItem && item != debugItem) {
        item.setEnabled(debugEnabled);
      }
    }
    */
  }


  /*
  public void toggleVariableInspector() {
    if (inspector.isVisible()) {
      inspectorItem.setText(Language.text("menu.debug.show_variables"));
      inspector.setVisible(false);
    } else {
//      inspector.setFocusableWindowState(false); // to not get focus when set visible
      inspectorItem.setText(Language.text("menu.debug.show_variables"));
      inspector.setVisible(true);
//      inspector.setFocusableWindowState(true); // allow to get focus again
    }
  }
  */


//  public void showVariableInspector() {
//    tray.setVisible(true);
//  }


//  /**
//   * Set visibility of the variable inspector window.
//   * @param visible true to set the variable inspector visible,
//   * false for invisible.
//   */
//  public void showVariableInspector(boolean visible) {
//    tray.setVisible(visible);
//  }
//
//
//  public void hideVariableInspector() {
//    tray.setVisible(true);
//  }
//
//
//  /** Toggle visibility of the variable inspector window. */
//  public void toggleVariableInspector() {
//    tray.setFocusableWindowState(false); // to not get focus when set visible
//    tray.setVisible(!tray.isVisible());
//    tray.setFocusableWindowState(true); // allow to get focus again
//  }


  /**
   * Set the line to highlight as currently suspended at. Will override the
   * breakpoint color, if set. Switches to the appropriate tab and scroll to
   * the line by placing the cursor there.
   * @param line the line to highlight as current suspended line
   */
  public void setCurrentLine(LineID line) {
    clearCurrentLine();
    if (line == null) {
      // safety, e.g. when no line mapping is found and the null line is used.
      return;
    }
    switchToTab(line.fileName());
    // scroll to line, by setting the cursor
    cursorToLineStart(line.lineIdx());
    // highlight line
    currentLine = new LineHighlight(line.lineIdx(), this);
    currentLine.setMarker(PdeTextArea.STEP_MARKER);
    currentLine.setPriority(10); // fixes current line being hidden by the breakpoint when moved down
  }


  /** Clear the highlight for the debuggers current line. */
  public void clearCurrentLine() {
    if (currentLine != null) {
      currentLine.clear();
      currentLine.dispose();

      // revert to breakpoint color if any is set on this line
      for (LineHighlight hl : breakpointedLines) {
        if (hl.getLineID().equals(currentLine.getLineID())) {
          hl.paint();
          break;
        }
      }
      currentLine = null;
    }
  }


  /**
   * Add highlight for a breakpointed line.
   * @param lineID the line id to highlight as breakpointed
   */
  public void addBreakpointedLine(LineID lineID) {
    LineHighlight hl = new LineHighlight(lineID, this);
    hl.setMarker(PdeTextArea.BREAK_MARKER);
    breakpointedLines.add(hl);
    // repaint current line if it's on this line
    if (currentLine != null && currentLine.getLineID().equals(lineID)) {
      currentLine.paint();
    }
  }


  /**
   * Remove a highlight for a breakpointed line. Needs to be on the current tab.
   * @param lineIdx the line index on the current tab to remove a breakpoint
   * highlight from
   */
  public void removeBreakpointedLine(int lineIdx) {
    LineID line = getLineIDInCurrentTab(lineIdx);
    //System.out.println("line id: " + line.fileName() + " " + line.lineIdx());
    LineHighlight foundLine = null;
    for (LineHighlight hl : breakpointedLines) {
      if (hl.getLineID().equals(line)) {
        foundLine = hl;
        break;
      }
    }
    if (foundLine != null) {
      foundLine.clear();
      breakpointedLines.remove(foundLine);
      foundLine.dispose();
      // repaint current line if it's on this line
      if (currentLine != null && currentLine.getLineID().equals(line)) {
        currentLine.paint();
      }
    }
  }


  /*
  // Remove all highlights for breakpoint lines.
  public void clearBreakpointedLines() {
    for (LineHighlight hl : breakpointedLines) {
      hl.clear();
      hl.dispose();
    }
    breakpointedLines.clear(); // remove all breakpoints
    // fix highlights not being removed when tab names have
    // changed due to opening a new sketch in same editor
    getJavaTextArea().clearGutterText();

    // repaint current line
    if (currentLine != null) {
      currentLine.paint();
    }
  }
  */


  /**
   * Retrieve a {@link LineID} object for a line on the current tab.
   * @param lineIdx the line index on the current tab
   * @return the {@link LineID} object representing a line index on the
   * current tab
   */
  public LineID getLineIDInCurrentTab(int lineIdx) {
    return new LineID(getSketch().getCurrentCode().getFileName(), lineIdx);
  }


  /**
   * Retrieve line of sketch where the cursor currently resides.
   * @return the current {@link LineID}
   */
  public LineID getCurrentLineID() {
    String tab = getSketch().getCurrentCode().getFileName();
    int lineNo = getTextArea().getCaretLine();
    return new LineID(tab, lineNo);
  }


  /**
   * Check whether a {@link LineID} is on the current tab.
   * @param line the {@link LineID}
   * @return true, if the {@link LineID} is on the current tab.
   */
  public boolean isInCurrentTab(LineID line) {
    return line.fileName().equals(getSketch().getCurrentCode().getFileName());
  }


  /**
   * Event handler called when switching between tabs. Loads all line
   * background colors set for the tab.
   * @param code tab to switch to
   */
  @Override
  public void setCode(SketchCode code) {
    Document oldDoc = code.getDocument();

    //System.out.println("tab switch: " + code.getFileName());
    // set the new document in the textarea, etc. need to do this first
    super.setCode(code);

    Document newDoc = code.getDocument();
    if (oldDoc != newDoc) {
      addDocumentListener(newDoc);
    }

    // set line background colors for tab
    final JavaTextArea ta = getJavaTextArea();
    // can be null when setCode is called the first time (in constructor)
    if (ta != null) {
      // clear all gutter text
      ta.clearGutterText();
      // first paint breakpoints
      if (breakpointedLines != null) {
        for (LineHighlight hl : breakpointedLines) {
          if (isInCurrentTab(hl.getLineID())) {
            hl.paint();
          }
        }
      }
      // now paint current line (if any)
      if (currentLine != null) {
        if (isInCurrentTab(currentLine.getLineID())) {
          currentLine.paint();
        }
      }
    }
    if (getDebugger() != null && getDebugger().isStarted()) {
      getDebugger().startTrackingLineChanges();
    }
    if (errorColumn != null) {
      errorColumn.repaint();
    }
    // Update variable panel when switching tabs
    if (liveVariablePanel != null && liveVariablePanel.isVisible()) {
      liveVariablePanel.setCurrentTab(getSketch().getCurrentCodeIndex());
      liveVariablePanel.updateVariables();
    }
  }


  /**
   * Get a tab by its file name.
   * @param filename the filename to search for.
   * @return the {@link SketchCode} object for the tab, or null if not found
   */
  public SketchCode getTab(String filename) {
    Sketch s = getSketch();
    for (SketchCode c : s.getCode()) {
      if (c.getFileName().equals(filename)) {
        return c;
      }
    }
    return null;
  }


  /**
   * Retrieve the current tab.
   * @return the {@link SketchCode} representing the current tab
   */
  public SketchCode getCurrentTab() {
    return getSketch().getCurrentCode();
  }


  /**
   * Access the currently edited document.
   * @return the document object
   */
  public Document currentDocument() {
    return getCurrentTab().getDocument();
  }


  public void statusBusy() {
    statusNotice(Language.text("editor.status.debug.busy"));
  }


  public void statusHalted() {
    statusNotice(Language.text("editor.status.debug.halt"));
  }


  /**
   * Updates the error table in the Error Window.
   * Overridden to handle the fugly import suggestions text.
   */
  @Override
  public void updateErrorTable(List<Problem> problems) {
    errorTable.clearRows();

    for (Problem p : problems) {
      String message = p.getMessage();

      if (p.getClass().equals(JavaProblem.class)) {
        JavaProblem jp = (JavaProblem) p;
        if (JavaMode.importSuggestEnabled &&
            jp.getImportSuggestions() != null &&
            jp.getImportSuggestions().length > 0) {
          message += " (double-click for suggestions)";
        }
      }

      errorTable.addRow(p, message,
                   sketch.getCode(p.getTabIndex()).getPrettyName(),
                   Integer.toString(p.getLineNumber() + 1));
      // Added +1 because lineNumbers internally are 0-indexed
    }
  }


  @Override
  public void errorTableDoubleClick(Object item) {
    if (!item.getClass().equals(JavaProblem.class)) {
      errorTableClick(item);
    }

    JavaProblem p = (JavaProblem) item;

//    MouseEvent evt = null;
    String[] suggs = p.getImportSuggestions();
    if (suggs != null && suggs.length > 0) {
//      String t = p.getMessage() + "(Import Suggestions available)";
//      FontMetrics fm = getFontMetrics(getFont());
//      int x1 = fm.stringWidth(p.getMessage());
//      int x2 = fm.stringWidth(t);
//      if (evt.getX() > x1 && evt.getX() < x2) {
      String[] list = p.getImportSuggestions();
      String className = list[0].substring(list[0].lastIndexOf('.') + 1);
      String[] temp = new String[list.length];
      for (int i = 0; i < list.length; i++) {
        temp[i] = "<html>Import '" +  className + "' <font color=#777777>(" + list[i] + ")</font></html>";
      }
      //        showImportSuggestion(temp, evt.getXOnScreen(), evt.getYOnScreen() - 3 * getFont().getSize());
      Point mouse = MouseInfo.getPointerInfo().getLocation();
      showImportSuggestion(temp, mouse.x, mouse.y);
    } else {
      errorTableClick(item);
    }
  }


  JFrame frmImportSuggest;

  private void showImportSuggestion(String[] list, int x, int y) {
    if (frmImportSuggest != null) {
//      frmImportSuggest.setVisible(false);
//      frmImportSuggest = null;
      return;
    }
    final JList<String> classList = new JList<>(list);
    classList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    frmImportSuggest = new JFrame();

    frmImportSuggest.setUndecorated(true);
    frmImportSuggest.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    JPanel panel = new JPanel();
    panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
    panel.setBackground(Color.WHITE);
    frmImportSuggest.setBackground(Color.WHITE);
    panel.add(classList);
    JLabel label = new JLabel("<html><div alight = \"left\"><font size = \"2\"><br>(Click to insert)</font></div></html>");
    label.setBackground(Color.WHITE);
    label.setHorizontalTextPosition(SwingConstants.LEFT);
    panel.add(label);
    panel.validate();
    frmImportSuggest.getContentPane().add(panel);
    frmImportSuggest.pack();

    classList.addListSelectionListener(e -> {
      if (classList.getSelectedValue() != null) {
        try {
          String t = classList.getSelectedValue().trim();
          Messages.log(t);
          int x1 = t.indexOf('(');
          String impString = "import " + t.substring(x1 + 1, t.indexOf(')')) + ";\n";
          int ct = getSketch().getCurrentCodeIndex();
          getSketch().setCurrentCode(0);
          getTextArea().getDocument().insertString(0, impString, null);
          getSketch().setCurrentCode(ct);
        } catch (BadLocationException ble) {
          Messages.log("Failed to insert import");
          ble.printStackTrace();
        }
      }
      frmImportSuggest.setVisible(false);
      frmImportSuggest.dispose();
      frmImportSuggest = null;
    });

    frmImportSuggest.addWindowFocusListener(new WindowFocusListener() {

      @Override
      public void windowLostFocus(WindowEvent e) {
        if (frmImportSuggest != null) {
          frmImportSuggest.dispose();
          frmImportSuggest = null;
        }
      }

      @Override
      public void windowGainedFocus(WindowEvent e) {

      }
    });

    frmImportSuggest.setLocation(x, y);
    frmImportSuggest.setBounds(x, y, 250, 100);
    frmImportSuggest.pack();
    frmImportSuggest.setVisible(true);
  }


  @Override
  public void applyPreferences() {
    super.applyPreferences();

    if (jmode != null) {
      jmode.loadPreferences();
      Messages.log("Applying prefs");
      // trigger it once to refresh UI
      //pdex.preferencesChanged();
      errorChecker.preferencesChanged();
      sketchChanged();
    }
  }


  // . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .

  // TWEAK MODE

  static final String PREF_TWEAK_PORT = "tweak.port";
  static final String PREF_TWEAK_SHOW_CODE = "tweak.showcode";

  public String[] baseCode;
  TweakClient tweakClient;


  protected void startTweakMode() {
    getJavaTextArea().startTweakMode();
  }


  protected void stopTweakMode(List<List<Handle>> handles) {
    tweakClient.shutdown();
    getJavaTextArea().stopTweakMode();

    // remove space from the code (before and after)
    //removeSpacesFromCode();

    // check which tabs were modified
    boolean[] tweakedTabs = getTweakedTabs(handles);
    boolean modified = anythingTrue(tweakedTabs);

    if (modified) {
      // ask to keep the values
      if (Messages.showYesNoQuestion(this, Language.text("tweak_mode"),
                                     Language.text("tweak_mode.keep_changes.line1"),
                                     Language.text("tweak_mode.keep_changes.line2")) == JOptionPane.YES_OPTION) {
        for (int i = 0; i < sketch.getCodeCount(); i++) {
          if (tweakedTabs[i]) {
            sketch.getCode(i).setModified(true);

          } else {
            // load the saved code of tabs that didn't change
            // (there might be formatting changes that should not be saved)
            sketch.getCode(i).setProgram(sketch.getCode(i).getSavedProgram());
            /* Wild Hack: set document to null so the text editor will refresh
               the program contents when the document tab is being clicked */
            sketch.getCode(i).setDocument(null);

            if (i == sketch.getCurrentCodeIndex()) {
              // this will update the current code
              setCode(sketch.getCurrentCode());
            }
          }
        }

        // save the sketch
        try {
          sketch.save();
        } catch (IOException e) {
          Messages.showWarning("Error", "Could not save the modified sketch.", e);
        }

        // repaint the editor header (show the modified tabs)
        header.repaint();
        textarea.invalidate();

      } else {  // no or canceled = don't keep changes
        loadSavedCode();
        // update the painter to draw the saved (old) code
        textarea.invalidate();
      }
    } else {
      // number values were not modified, but we need to load
      // the saved code because of some formatting changes
      loadSavedCode();
      textarea.invalidate();
    }
  }


  static private boolean anythingTrue(boolean[] list) {
    for (boolean b : list) {
      if (b) return true;
    }
    return false;
  }


  protected void updateInterface(List<List<Handle>> handles,
                              List<List<ColorControlBox>> colorBoxes) {
    getJavaTextArea().updateInterface(handles, colorBoxes);
  }


  static private boolean[] getTweakedTabs(List<List<Handle>> handles) {
    boolean[] outgoing = new boolean[handles.size()];

    for (int i = 0; i < handles.size(); i++) {
      for (Handle h : handles.get(i)) {
        if (h.valueChanged()) {
          outgoing[i] = true;
        }
      }
    }
    return outgoing;
  }


  protected void initBaseCode() {
    SketchCode[] code = sketch.getCode();

    baseCode = new String[code.length];
    for (int i = 0; i < code.length; i++) {
      baseCode[i] = code[i].getSavedProgram();
    }
  }


  protected void initEditorCode(List<List<Handle>> handles) {
    SketchCode[] sketchCode = sketch.getCode();
    for (int tab=0; tab<baseCode.length; tab++) {
        // beautify the numbers
        int charInc = 0;
        String code = baseCode[tab];

        for (Handle n : handles.get(tab)) {
          int s = n.startChar + charInc;
          int e = n.endChar + charInc;
          String newStr = n.strNewValue;
          code = replaceString(code, s, e, newStr);
          n.newStartChar = n.startChar + charInc;
          charInc += n.strNewValue.length() - n.strValue.length();
          n.newEndChar = n.endChar + charInc;
        }

        sketchCode[tab].setProgram(code);
        /* Wild Hack: set document to null so the text editor will refresh
           the program contents when the document tab is being clicked */
        sketchCode[tab].setDocument(null);
      }

    // this will update the current code
    setCode(sketch.getCurrentCode());
  }


  private void loadSavedCode() {
    //SketchCode[] code = sketch.getCode();
    for (SketchCode code : sketch.getCode()) {
      if (!code.getProgram().equals(code.getSavedProgram())) {
        code.setProgram(code.getSavedProgram());
        /* Wild Hack: set document to null so the text editor will refresh
           the program contents when the document tab is being clicked */
        code.setDocument(null);
      }
    }
    // this will update the current code
    setCode(sketch.getCurrentCode());
  }


  /**
   * Replace all numbers with variables and add code to initialize
   * these variables and handle update messages.
   */
  protected boolean automateSketch(Sketch sketch, SketchParser parser) {
    SketchCode[] code = sketch.getCode();

    List<List<Handle>> handles = parser.allHandles;

    if (code.length < 1) {
      return false;
    }

    if (handles.size() == 0) {
      return false;
    }

    int afterSizePos = SketchParser.getAfterSizePos(baseCode[0]);
    if (afterSizePos < 0) {
      return false;
    }

    // get port number from preferences.txt
    int port;
    String portStr = Preferences.get(PREF_TWEAK_PORT);
    if (portStr == null) {
      Preferences.set(PREF_TWEAK_PORT, "auto");
      portStr = "auto";
    }

    if (portStr.equals("auto")) {
      // random port for udp (0xc000 - 0xffff)
      port = (int)(Math.random()*0x3fff) + 0xc000;
    } else {
      port = Preferences.getInteger(PREF_TWEAK_PORT);
    }

    // create the client that will send the new values to the sketch
    tweakClient = new TweakClient(port);
    // update handles with a reference to the client object
    for (int tab=0; tab<code.length; tab++) {
      for (Handle h : handles.get(tab)) {
        h.setTweakClient(tweakClient);
      }
    }

    // Copy current program to interactive program
    // modify the code below, replace all numbers with their variable names
    // loop through all tabs in the current sketch
    for (int tab=0; tab<code.length; tab++) {
      int charInc = 0;
      String c = baseCode[tab];
      for (Handle n : handles.get(tab)) {
        // replace number value with a variable
        c = replaceString(c, n.startChar + charInc, n.endChar + charInc, n.name);
        charInc += n.name.length() - n.strValue.length();
      }
      code[tab].setProgram(c);
    }

    // add the main header to the code in the first tab
    String c = code[0].getProgram();

    // header contains variable declaration, initialization,
    // and OSC listener function
    String header;
    header = """


      /*************************/
      /* MODIFIED BY TWEAKMODE */
      /*************************/


      """;

    // add needed OSC imports and the global OSC object
    header += "import java.net.*;\n";
    header += "import java.io.*;\n";
    header += "import java.nio.*;\n\n";

    // write a declaration for int and float arrays
    int numOfInts = howManyInts(handles);
    int numOfFloats = howManyFloats(handles);
    if (numOfInts > 0) {
      header += "int[] tweakmode_int = new int["+numOfInts+"];\n";
    }
    if (numOfFloats > 0) {
      header += "float[] tweakmode_float = new float["+numOfFloats+"];\n\n";
    }

    // add the server code that will receive the value change messages
//    header += TweakClient.getServerCode(port, numOfInts>0, numOfFloats>0);
    header += "TweakModeServer tweakmode_Server;\n";

    header += "void tweakmode_initAllVars() {\n";
    //for (int i=0; i<handles.length; i++) {
    for (List<Handle> list : handles) {
      //for (Handle n : handles[i]) {
      for (Handle n : list) {
        header += "  " + n.name + " = " + n.strValue + ";\n";
      }
    }
    header += "}\n\n";
    header += "void tweakmode_initCommunication() {\n";
    header += " tweakmode_Server = new TweakModeServer();\n";
    header += " tweakmode_Server.setup();\n";
    header += " tweakmode_Server.start();\n";
    header += "}\n";

    header += "\n\n\n\n\n";

    // add call to our initAllVars and initOSC functions
    // from the setup() function.
    String addToSetup = """



        /* TWEAKMODE */
          tweakmode_initAllVars();
          tweakmode_initCommunication();
        /* TWEAKMODE */

      """;

    afterSizePos = SketchParser.getAfterSizePos(c);
    c = replaceString(c, afterSizePos, afterSizePos, addToSetup);

    // Server code defines a class, so it should go later in the sketch
    String serverCode =
      TweakClient.getServerCode(port, numOfInts>0, numOfFloats>0);
    code[0].setProgram(header + c + serverCode);

    // print out modified code
    String showModCode = Preferences.get(PREF_TWEAK_SHOW_CODE);
    if (showModCode == null) {
      Preferences.setBoolean(PREF_TWEAK_SHOW_CODE, false);
    }

    if (Preferences.getBoolean(PREF_TWEAK_SHOW_CODE)) {
      System.out.println("\nTweakMode modified code:\n");
      for (int i=0; i<code.length; i++) {
        System.out.println("tab " + i + "\n");
        System.out.println("=======================================================\n");
        System.out.println(code[i].getProgram());
      }
    }
    return true;
  }


  static private String replaceString(String str, int start, int end, String put) {
    return str.substring(0, start) + put + str.substring(end);
  }


  //private int howManyInts(ArrayList<Handle> handles[])
  static private int howManyInts(List<List<Handle>> handles) {
    int count = 0;
    for (List<Handle> list : handles) {
      for (Handle n : list) {
        if ("int".equals(n.type) || "hex".equals(n.type) || "webcolor".equals(n.type)) {
          count++;
        }
      }
    }
    return count;
  }


  //private int howManyFloats(ArrayList<Handle> handles[])
  static private int howManyFloats(List<List<Handle>> handles) {
    int count = 0;
    for (List<Handle> list : handles) {
      for (Handle n : list) {
        if ("float".equals(n.type)) {
          count++;
        }
      }
    }
    return count;
  }

  @Override
  public String getSketchDiagnostics() {
    if (debugger.isStarted()) {
      return debugger.getDiagnostics();
    } else if (runtime != null) {
      return Debugger.getDiagnostics(runtime);
    }
    return super.getSketchDiagnostics();
  }
}
