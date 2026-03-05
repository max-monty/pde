package processing.mode.java.livemode;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

import processing.app.ui.Toolkit;


/**
 * A collapsible side panel for displaying live variable values.
 * Replaces the inline variable overlay with a cleaner, dedicated panel.
 * Color-coded by type: green=numbers, amber=strings, cyan=booleans, gray=other.
 */
public class LiveVariablePanel extends JPanel {
  private static final int HEADER_HEIGHT = Toolkit.zoom(28);
  private static final int ROW_HEIGHT = Toolkit.zoom(22);
  private static final int COLLAPSED_WIDTH = Toolkit.zoom(24);
  private static final int EXPANDED_WIDTH = Toolkit.zoom(200);

  // Colors
  private static final Color BG_COLOR = new Color(30, 32, 38);
  private static final Color HEADER_BG = new Color(38, 40, 48);
  private static final Color HEADER_TEXT = new Color(160, 165, 180);
  private static final Color SEPARATOR = new Color(55, 55, 60);
  private static final Color NAME_COLOR = new Color(140, 145, 165);

  // Type colors
  private static final Color NUMERIC_COLOR = new Color(86, 196, 126);
  private static final Color STRING_COLOR = new Color(230, 180, 80);
  private static final Color BOOL_COLOR = new Color(100, 190, 230);
  private static final Color DEFAULT_COLOR = new Color(170, 170, 190);

  // Fonts
  private static final Font HEADER_FONT =
    new Font(Font.SANS_SERIF, Font.BOLD, Toolkit.zoom(11));
  private static final Font NAME_FONT =
    new Font(Font.SANS_SERIF, Font.PLAIN, Toolkit.zoom(11));
  private static final Font VALUE_FONT =
    new Font(Font.MONOSPACED, Font.BOLD, Toolkit.zoom(11));
  private static final Font EMPTY_FONT =
    new Font(Font.SANS_SERIF, Font.ITALIC, Toolkit.zoom(11));
  private static final Font COUNT_FONT =
    new Font(Font.SANS_SERIF, Font.PLAIN, Toolkit.zoom(10));

  private boolean collapsed = false;
  private VariableInspector inspector;
  private LiveModeController liveModeController;
  private int currentTab = 0;

  // Cached variable data for painting
  private volatile List<VarEntry> displayVars = new ArrayList<>();

  // Editing state
  private JTextField editField;
  private String editingVarName;
  private int editingRowIndex = -1;

  // Alt+drag scrubbing state
  private boolean varScrubbing = false;
  private int varScrubStartX;
  private String varScrubName;
  private Number varScrubStartValue;
  private boolean varScrubIsFloat;
  private int hoveredRow = -1;


  private static class VarEntry {
    final String name;
    String value;
    final Color valueColor;

    VarEntry(String name, String value, Color valueColor) {
      this.name = name;
      this.value = value;
      this.valueColor = valueColor;
    }
  }


  public LiveVariablePanel() {
    setLayout(null); // absolute positioning for edit field overlay
    setBackground(BG_COLOR);
    setOpaque(true);
    setPreferredSize(new Dimension(EXPANDED_WIDTH, 100));
    setMinimumSize(new Dimension(COLLAPSED_WIDTH, 100));

    addMouseListener(new MouseAdapter() {
      @Override
      public void mousePressed(MouseEvent e) {
        if (e.getY() <= HEADER_HEIGHT) {
          toggleCollapse();
          return;
        }
        if (collapsed) return;

        int row = getRowAtY(e.getY());
        List<VarEntry> vars = displayVars;
        if (row < 0 || row >= vars.size()) return;
        VarEntry var = vars.get(row);

        if (e.isAltDown()) {
          // Start Alt+drag scrubbing on numeric values
          try {
            if (var.value.contains(".")) {
              varScrubStartValue = Float.parseFloat(var.value);
              varScrubIsFloat = true;
            } else {
              varScrubStartValue = Integer.parseInt(var.value);
              varScrubIsFloat = false;
            }
            varScrubbing = true;
            varScrubStartX = e.getX();
            varScrubName = var.name;
            setCursor(new Cursor(Cursor.E_RESIZE_CURSOR));
            e.consume();
          } catch (NumberFormatException nfe) {
            // Not a number, ignore
          }
          return;
        }

        // Boolean toggle: single click toggles value
        if ("true".equals(var.value) || "false".equals(var.value)) {
          String newVal = "true".equals(var.value) ? "false" : "true";
          if (liveModeController != null) {
            liveModeController.sendSetVariable(var.name, newVal);
          }
          return;
        }

        // Click to edit: show text field
        startEditing(row, var);
      }

      @Override
      public void mouseReleased(MouseEvent e) {
        if (varScrubbing) {
          varScrubbing = false;
          setCursor(Cursor.getDefaultCursor());
          e.consume();
        }
      }
    });

    addMouseMotionListener(new MouseMotionAdapter() {
      @Override
      public void mouseDragged(MouseEvent e) {
        if (varScrubbing && liveModeController != null) {
          int dx = e.getX() - varScrubStartX;
          float increment = e.isShiftDown() ? 0.1f : 1.0f;
          String newVal;
          if (varScrubIsFloat) {
            float v = varScrubStartValue.floatValue() + dx * increment * 0.1f;
            newVal = String.format(java.util.Locale.US, "%.1f", v);
          } else {
            int v = varScrubStartValue.intValue() + (int)(dx * increment);
            newVal = String.valueOf(v);
          }
          liveModeController.sendSetVariable(varScrubName, newVal);
          e.consume();
        }
      }

      @Override
      public void mouseMoved(MouseEvent e) {
        int row = getRowAtY(e.getY());
        if (row != hoveredRow) {
          hoveredRow = row;
          if (e.isAltDown() && row >= 0) {
            setCursor(new Cursor(Cursor.E_RESIZE_CURSOR));
          } else {
            setCursor(Cursor.getDefaultCursor());
          }
          repaint();
        }
      }
    });

    setVisible(false);
  }


  private int getRowAtY(int y) {
    if (y <= HEADER_HEIGHT) return -1;
    int rowY = HEADER_HEIGHT + Toolkit.zoom(4);
    int row = (y - rowY) / ROW_HEIGHT;
    List<VarEntry> vars = displayVars;
    return (row >= 0 && row < vars.size()) ? row : -1;
  }


  private void startEditing(int row, VarEntry var) {
    cancelEditing();
    int y = HEADER_HEIGHT + Toolkit.zoom(4) + row * ROW_HEIGHT;
    int pad = Toolkit.zoom(8);
    editField = new JTextField(var.value);
    editField.setBackground(new Color(50, 52, 60));
    editField.setForeground(var.valueColor);
    editField.setCaretColor(Color.WHITE);
    editField.setBorder(BorderFactory.createLineBorder(new Color(80, 120, 200)));
    editField.setFont(VALUE_FONT);
    editField.setBounds(pad, y, getWidth() - pad * 2, ROW_HEIGHT);
    editField.selectAll();
    editingVarName = var.name;
    editingRowIndex = row;
    add(editField);
    editField.requestFocusInWindow();

    editField.addActionListener(e -> {
      if (liveModeController != null && editingVarName != null) {
        liveModeController.sendSetVariable(editingVarName, editField.getText().trim());
      }
      cancelEditing();
    });
    editField.addKeyListener(new KeyAdapter() {
      @Override
      public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
          cancelEditing();
        }
      }
    });
    editField.addFocusListener(new FocusAdapter() {
      @Override
      public void focusLost(FocusEvent e) {
        cancelEditing();
      }
    });
    revalidate();
    repaint();
  }


  private void cancelEditing() {
    if (editField != null) {
      remove(editField);
      editField = null;
      editingVarName = null;
      editingRowIndex = -1;
      revalidate();
      repaint();
    }
  }


  public void toggleCollapse() {
    collapsed = !collapsed;
    setPreferredSize(new Dimension(
      collapsed ? COLLAPSED_WIDTH : EXPANDED_WIDTH, 100));
    revalidate();
    repaint();
  }


  public void setInspector(VariableInspector inspector) {
    this.inspector = inspector;
  }


  public void setLiveModeController(LiveModeController controller) {
    this.liveModeController = controller;
  }


  public void setCurrentTab(int tab) {
    this.currentTab = tab;
  }


  public void updateVariables() {
    if (inspector == null) return;

    List<VarEntry> newVars = new ArrayList<>();
    Map<String, Map<String, String>> allVars = inspector.getAllVariables();
    if (allVars != null) {
      for (Map.Entry<String, Map<String, String>> lineEntry : allVars.entrySet()) {
        String key = lineEntry.getKey();
        if (key.startsWith(currentTab + ":")) {
          for (Map.Entry<String, String> varEntry : lineEntry.getValue().entrySet()) {
            String name = varEntry.getKey();
            String value = varEntry.getValue();
            // Deduplicate: only keep latest value per name
            boolean found = false;
            for (VarEntry existing : newVars) {
              if (existing.name.equals(name)) {
                existing.value = value;
                found = true;
                break;
              }
            }
            if (!found) {
              newVars.add(new VarEntry(name, value, getValueColor(value)));
            }
          }
        }
      }
    }

    newVars.sort(Comparator.comparing(v -> v.name));
    displayVars = newVars;
    repaint();
  }


  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2 = (Graphics2D) g;
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

    int w = getWidth();
    int h = getHeight();

    // Left separator line
    g2.setColor(SEPARATOR);
    g2.fillRect(0, 0, 1, h);

    // Header background
    g2.setColor(HEADER_BG);
    g2.fillRect(1, 0, w - 1, HEADER_HEIGHT);

    // Header bottom separator
    g2.setColor(SEPARATOR);
    g2.fillRect(1, HEADER_HEIGHT - 1, w - 1, 1);

    if (collapsed) {
      // Draw chevron to expand
      g2.setFont(HEADER_FONT);
      g2.setColor(HEADER_TEXT);
      int cx = w / 2;
      int cy = HEADER_HEIGHT / 2;
      g2.drawString("\u25C0", cx - 5, cy + 4);
      return;
    }

    // Header text and collapse chevron
    g2.setFont(HEADER_FONT);
    FontMetrics hfm = g2.getFontMetrics();
    int headerTextY = (HEADER_HEIGHT + hfm.getAscent() - hfm.getDescent()) / 2;

    g2.setColor(HEADER_TEXT);
    g2.drawString("Variables", Toolkit.zoom(10), headerTextY);

    // Collapse chevron
    g2.drawString("\u25B6", w - Toolkit.zoom(18), headerTextY);

    // Variable count badge
    List<VarEntry> vars = displayVars;
    if (!vars.isEmpty()) {
      g2.setFont(COUNT_FONT);
      FontMetrics cfm = g2.getFontMetrics();
      String count = String.valueOf(vars.size());
      int countW = cfm.stringWidth(count);
      int badgeX = Toolkit.zoom(80);
      int badgeY = (HEADER_HEIGHT - cfm.getHeight()) / 2;
      g2.setColor(new Color(60, 62, 72));
      g2.fillRoundRect(badgeX, badgeY, countW + Toolkit.zoom(8),
                       cfm.getHeight() + 2, Toolkit.zoom(8), Toolkit.zoom(8));
      g2.setColor(new Color(120, 125, 140));
      g2.drawString(count, badgeX + Toolkit.zoom(4), badgeY + cfm.getAscent());
    }

    // Draw variable rows
    int y = HEADER_HEIGHT + Toolkit.zoom(4);
    int pad = Toolkit.zoom(8);

    for (VarEntry var : vars) {
      if (y + ROW_HEIGHT > h) break;

      // Left accent bar
      g2.setColor(new Color(var.valueColor.getRed(), var.valueColor.getGreen(),
                            var.valueColor.getBlue(), 180));
      g2.fillRoundRect(Toolkit.zoom(4), y + 2, Toolkit.zoom(3), ROW_HEIGHT - 4,
                       Toolkit.zoom(2), Toolkit.zoom(2));

      // Variable name
      g2.setFont(NAME_FONT);
      FontMetrics nfm = g2.getFontMetrics();
      g2.setColor(NAME_COLOR);
      int textY = y + (ROW_HEIGHT + nfm.getAscent() - nfm.getDescent()) / 2;
      g2.drawString(var.name, pad + Toolkit.zoom(4), textY);

      // Value (right-aligned)
      g2.setFont(VALUE_FONT);
      FontMetrics vfm = g2.getFontMetrics();
      String displayValue = var.value;
      int valueW = vfm.stringWidth(displayValue);
      int nameEndX = pad + Toolkit.zoom(4) + nfm.stringWidth(var.name);
      int valueX = w - pad - valueW;

      // Truncate if needed
      if (valueX < nameEndX + Toolkit.zoom(8)) {
        while (displayValue.length() > 3 &&
               w - pad - vfm.stringWidth(displayValue + "..") < nameEndX + Toolkit.zoom(8)) {
          displayValue = displayValue.substring(0, displayValue.length() - 1);
        }
        displayValue = displayValue + "..";
        valueW = vfm.stringWidth(displayValue);
        valueX = w - pad - valueW;
      }

      g2.setColor(var.valueColor);
      g2.drawString(displayValue, valueX, textY);

      y += ROW_HEIGHT;
    }

    // Empty state
    if (vars.isEmpty()) {
      g2.setFont(EMPTY_FONT);
      g2.setColor(new Color(80, 85, 100));
      String msg = "No variables yet";
      FontMetrics efm = g2.getFontMetrics();
      g2.drawString(msg, (w - efm.stringWidth(msg)) / 2,
                    HEADER_HEIGHT + Toolkit.zoom(30));
    }
  }


  private static Color getValueColor(String value) {
    if (value == null || value.isEmpty()) return DEFAULT_COLOR;
    if ("true".equals(value) || "false".equals(value)) return BOOL_COLOR;
    char first = value.charAt(0);
    if (Character.isDigit(first) || first == '-' || first == '.') {
      try {
        Double.parseDouble(value.endsWith("f") || value.endsWith("F")
          ? value.substring(0, value.length() - 1) : value);
        return NUMERIC_COLOR;
      } catch (NumberFormatException e) { /* fall through */ }
    }
    if (value.startsWith("\"") || value.startsWith("'")) return STRING_COLOR;
    return DEFAULT_COLOR;
  }
}
