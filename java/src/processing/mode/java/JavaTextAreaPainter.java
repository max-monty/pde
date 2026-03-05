/* -*- mode: java; c-basic-offset: 2; indent-tabs-mode: nil -*- */

/*
Part of the Processing project - http://processing.org
Copyright (c) 2012-16 The Processing Foundation

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License version 2
as published by the Free Software Foundation.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software Foundation, Inc.
51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
*/

package processing.mode.java;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

import javax.swing.SwingUtilities;

import processing.app.SketchCode;
import processing.app.syntax.PdeTextAreaPainter;
import processing.app.syntax.TextAreaDefaults;
import processing.app.ui.Toolkit;
import processing.mode.java.livemode.LiveModeController;
import processing.mode.java.livemode.VariableInspector;
import processing.mode.java.tweak.ColorControlBox;
import processing.mode.java.tweak.ColorSelector;
import processing.mode.java.tweak.Handle;
import processing.mode.java.tweak.Settings;


/**
 * Customized line painter to handle tweak mode and live number scrubbing.
 */
public class JavaTextAreaPainter extends PdeTextAreaPainter {

  public JavaTextAreaPainter(final JavaTextArea textArea, TextAreaDefaults defaults) {
    super(textArea, defaults);

    // TweakMode code
    tweakMode = false;
    cursorType = Cursor.DEFAULT_CURSOR;

    // Live number scrubbing — works during normal editing with Alt+drag
    setupLiveScrubbing();

    // Track window activation to prevent scrub-on-focus-transfer
    // (On macOS, Option+clicking from sketch window hides the sketch)
    SwingUtilities.invokeLater(() -> {
      Window win = SwingUtilities.getWindowAncestor(this);
      if (win != null) {
        win.addWindowListener(new WindowAdapter() {
          @Override
          public void windowActivated(WindowEvent e) {
            lastWindowActivated = System.currentTimeMillis();
          }
        });
      }
    });
  }

  // Grace period: ignore Alt+click within 300ms of window activation
  private volatile long lastWindowActivated = 0;


  // . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .

  // LIVE NUMBER SCRUBBING

  private boolean scrubbing = false;
  private int scrubStartX;
  private int scrubLine;
  private int scrubStartOffset; // char offset in document where the number starts
  private int scrubEndOffset;   // char offset where the number ends
  private String scrubOrigValue;
  private String scrubType; // "int" or "float"
  private int scrubDecimalPlaces;
  private float scrubIncrement;
  private Number scrubCurrentValue;

  // Hover highlight for scrubbing affordance
  private int hoverNumLine = -1;
  private int hoverNumStart = -1;
  private int hoverNumEnd = -1;

  private void setupLiveScrubbing() {
    addMouseListener(new MouseListener() {
      @Override
      public void mousePressed(MouseEvent e) {
        if (tweakMode) return; // don't interfere with tweak mode
        if (!e.isAltDown()) return; // only Alt+click

        // Don't start scrubbing on the click that activated this window.
        // On macOS, Option+clicking from the sketch window would hide
        // the sketch — this grace period prevents accidental scrub-on-focus.
        if (System.currentTimeMillis() - lastWindowActivated < 300) return;

        // Find what's under the cursor
        int line = textArea.yToLine(e.getY());
        if (line < 0 || line >= textArea.getLineCount()) return;

        int lineStart = textArea.getLineStartOffset(line);
        int offset = textArea.xyToOffset(e.getX(), e.getY());
        if (offset < 0) return;

        String lineText = textArea.getLineText(line);
        if (lineText == null) return;

        int charInLine = offset - lineStart;
        if (charInLine < 0 || charInLine >= lineText.length()) return;

        // Find numeric literal at this position
        int[] numRange = findNumberAt(lineText, charInLine);
        if (numRange == null) return;

        // We found a number — start scrubbing
        scrubbing = true;
        scrubStartX = e.getX();
        scrubLine = line;
        scrubStartOffset = lineStart + numRange[0];
        scrubEndOffset = lineStart + numRange[1];
        scrubOrigValue = lineText.substring(numRange[0], numRange[1]);

        // Determine type and scale increment to number magnitude
        try {
          if (scrubOrigValue.contains(".")) {
            scrubType = "float";
            scrubDecimalPlaces = getDecimalPlaces(scrubOrigValue);
            scrubIncrement = (float)(1.0 / Math.pow(10, scrubDecimalPlaces));
            scrubCurrentValue = Float.parseFloat(scrubOrigValue);
          } else {
            scrubType = "int";
            scrubDecimalPlaces = 0;
            int val = Integer.parseInt(scrubOrigValue);
            scrubCurrentValue = val;
            // Scale increment based on magnitude for easier scrubbing
            int absVal = Math.abs(val);
            if (absVal >= 1000) scrubIncrement = 5.0f;
            else if (absVal >= 100) scrubIncrement = 2.0f;
            else scrubIncrement = 1.0f;
          }
        } catch (NumberFormatException nfe) {
          scrubbing = false;
          return;
        }

        JavaTextAreaPainter.this.setCursor(
          new Cursor(Cursor.E_RESIZE_CURSOR));
        e.consume();
      }

      @Override
      public void mouseReleased(MouseEvent e) {
        if (scrubbing) {
          scrubbing = false;
          setCursor(new Cursor(Cursor.TEXT_CURSOR));
          e.consume();
        }
      }

      @Override public void mouseClicked(MouseEvent e) {}
      @Override public void mouseEntered(MouseEvent e) {}
      @Override public void mouseExited(MouseEvent e) {}
    });

    addMouseMotionListener(new MouseMotionListener() {
      @Override
      public void mouseDragged(MouseEvent e) {
        if (!scrubbing) {
          if (e.isAltDown()) e.consume();
          return;
        }

        int dx = e.getX() - scrubStartX;
        // Shift held = fine scrubbing (1/10th speed)
        float increment = e.isShiftDown() ? scrubIncrement * 0.1f : scrubIncrement;
        float change = dx * increment;

        String newValueStr;
        if ("int".equals(scrubType)) {
          int newVal = scrubCurrentValue.intValue() + (int)change;
          newValueStr = String.valueOf(newVal);
        } else {
          float newVal = scrubCurrentValue.floatValue() + change;
          newValueStr = String.format(java.util.Locale.US,
            "%." + scrubDecimalPlaces + "f", newVal);
        }

        // Try hot-swap via tweak handles (sends UDP, no sketch restart)
        Handle matchedHandle = findMatchingLiveHandle();
        boolean hotSwapped = false;
        if (matchedHandle != null) {
          try {
            if ("int".equals(scrubType)) {
              matchedHandle.setValue(scrubCurrentValue.intValue() + (int)change);
            } else {
              float newVal = scrubCurrentValue.floatValue() + change;
              matchedHandle.setValue(newVal);
            }
            hotSwapped = true;
          } catch (Exception ex) { /* ignore send errors */ }
        } else if (liveModeController != null) {
          // No tweak handle — try global variable scrubbing via SET_VAR
          String varName = findGlobalVarNameAtLine(scrubLine);
          if (varName != null) {
            liveModeController.sendSetVariable(varName, newValueStr);
            hotSwapped = true;
          }
        }

        // Update the editor text (suppress relaunch if hot-swapping)
        JavaEditor editor = getJavaEditor();
        boolean wasSuppress = editor.suppressLiveRelaunch;
        if (hotSwapped) {
          editor.suppressLiveRelaunch = true;
        }
        try {
          javax.swing.text.Document doc = textArea.getDocument();
          int len = scrubEndOffset - scrubStartOffset;
          doc.remove(scrubStartOffset, len);
          doc.insertString(scrubStartOffset, newValueStr, null);
          // Update end offset for new string length
          scrubEndOffset = scrubStartOffset + newValueStr.length();
        } catch (javax.swing.text.BadLocationException ex) {
          scrubbing = false;
        } finally {
          editor.suppressLiveRelaunch = wasSuppress;
        }

        e.consume();
        repaint();
      }

      @Override
      public void mouseMoved(MouseEvent e) {
        if (tweakMode) return;
        // Show resize cursor and highlight when hovering over number with Alt held
        if (e.isAltDown()) {
          int line = textArea.yToLine(e.getY());
          if (line >= 0 && line < textArea.getLineCount()) {
            int lineStart = textArea.getLineStartOffset(line);
            int offset = textArea.xyToOffset(e.getX(), e.getY());
            String lineText = textArea.getLineText(line);
            if (lineText != null && offset >= 0) {
              int charInLine = offset - lineStart;
              if (charInLine >= 0 && charInLine < lineText.length()) {
                int[] numRange = findNumberAt(lineText, charInLine);
                if (numRange != null) {
                  setCursor(new Cursor(Cursor.E_RESIZE_CURSOR));
                  hoverNumLine = line;
                  hoverNumStart = lineStart + numRange[0];
                  hoverNumEnd = lineStart + numRange[1];
                  repaint();
                  return;
                }
              }
            }
          }
          setCursor(new Cursor(Cursor.TEXT_CURSOR));
        }
        // Clear hover when not on a number
        if (hoverNumLine >= 0) {
          hoverNumLine = -1;
          repaint();
        }
      }
    });
  }

  /**
   * Find a numeric literal at the given character position in a line.
   * Returns [start, end] offsets within the line, or null.
   */
  private static int[] findNumberAt(String line, int pos) {
    if (pos < 0 || pos >= line.length()) return null;
    char ch = line.charAt(pos);

    // Check if we're on a digit or decimal point or minus sign
    if (!Character.isDigit(ch) && ch != '.' && ch != '-') return null;

    // Walk backward to find start of number
    int start = pos;
    boolean hasDigit = Character.isDigit(ch);
    boolean hasDot = (ch == '.');
    while (start > 0) {
      char prev = line.charAt(start - 1);
      if (Character.isDigit(prev)) {
        hasDigit = true;
        start--;
      } else if (prev == '.' && !hasDot) {
        hasDot = true;
        start--;
      } else if (prev == '-' && start - 1 == 0) {
        start--;
        break;
      } else if (prev == '-') {
        // Check if minus is a negative sign (preceded by operator/delimiter)
        if (start - 2 >= 0) {
          char before = line.charAt(start - 2);
          if (before == '(' || before == ',' || before == '=' ||
              before == '+' || before == '-' || before == '*' ||
              before == '/' || before == ' ' || before == '\t' ||
              before == '{' || before == '[' || before == '<' ||
              before == '>' || before == ':' || before == '?') {
            start--;
            break;
          }
        }
        break;
      } else {
        break;
      }
    }

    // Walk forward to find end of number
    int end = pos + 1;
    while (end < line.length()) {
      char next = line.charAt(end);
      if (Character.isDigit(next)) {
        hasDigit = true;
        end++;
      } else if (next == '.' && !hasDot) {
        hasDot = true;
        end++;
      } else if (next == 'f' || next == 'F') {
        end++; // include trailing 'f' for floats
        break;
      } else {
        break;
      }
    }

    if (!hasDigit) return null;

    // Validate the extracted string is actually a number
    String numStr = line.substring(start, end);

    // Reject hex literals (0x..., 0X...) — scrubbing would corrupt them
    if (numStr.startsWith("0x") || numStr.startsWith("0X") ||
        numStr.startsWith("-0x") || numStr.startsWith("-0X")) {
      return null;
    }

    // Reject long suffixes (100L) — not scrubable
    if (numStr.endsWith("L") || numStr.endsWith("l")) {
      return null;
    }

    // Reject double suffixes (1.0d, 1.0D)
    if (numStr.endsWith("d") || numStr.endsWith("D")) {
      return null;
    }

    // Remove trailing f for validation
    if (numStr.endsWith("f") || numStr.endsWith("F")) {
      numStr = numStr.substring(0, numStr.length() - 1);
    }
    try {
      if (numStr.contains(".")) {
        Float.parseFloat(numStr);
      } else {
        Integer.parseInt(numStr);
      }
    } catch (NumberFormatException e) {
      return null;
    }

    return new int[]{start, end};
  }

  private static int getDecimalPlaces(String numStr) {
    int dotIndex = numStr.indexOf('.');
    if (dotIndex < 0) return 0;
    String afterDot = numStr.substring(dotIndex + 1);
    // Remove trailing 'f'
    if (afterDot.endsWith("f") || afterDot.endsWith("F")) {
      afterDot = afterDot.substring(0, afterDot.length() - 1);
    }
    return afterDot.length();
  }


  /**
   * Find a live tweak Handle matching the current scrub position.
   * Used for hot-swap: sends value via UDP instead of restarting sketch.
   */
  private Handle findMatchingLiveHandle() {
    if (liveHandles == null || !scrubbing) return null;
    int currentTab = getCurrentCodeIndex();
    if (currentTab < 0 || currentTab >= liveHandles.size()) return null;
    for (Handle h : liveHandles.get(currentTab)) {
      if (h.line == scrubLine &&
          h.startChar >= scrubStartOffset - 1 && h.startChar <= scrubStartOffset + 1) {
        return h;
      }
    }
    return null;
  }


  /**
   * Try to find a global variable name on the given line.
   * Matches patterns like: int x = ...; or float speed = ...;
   */
  private String findGlobalVarNameAtLine(int line) {
    if (line < 0 || line >= textArea.getLineCount()) return null;
    String lineText = textArea.getLineText(line);
    if (lineText == null) return null;
    java.util.regex.Matcher m = java.util.regex.Pattern.compile(
      "^\\s*(?:final\\s+)?(?:int|float|double|boolean|String|color|char|byte|long|short)\\s+(\\w+)\\s*="
    ).matcher(lineText);
    return m.find() ? m.group(1) : null;
  }


  // . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .

  // TWEAK MODE


	public boolean tweakMode;
	public List<List<Handle>> handles;
	public List<List<ColorControlBox>> colorBoxes;

	// Variable inspector for inline state display
	private VariableInspector variableInspector;

	// Live tweak handles for hot-swap scrubbing
	private List<List<Handle>> liveHandles;

	// Live mode controller for global variable scrubbing via SET_VAR
	private LiveModeController liveModeController;

	public Handle mouseHandle = null;
	public ColorSelector colorSelector;

	int cursorType;
	BufferedImage cursorImg = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
  Cursor blankCursor;
  // this is a temporary workaround for the CHIP, will be removed
  {
    Dimension cursorSize = java.awt.Toolkit.getDefaultToolkit().getBestCursorSize(16, 16);
    if (cursorSize.width == 0 || cursorSize.height == 0) {
      blankCursor = Cursor.getDefaultCursor();
    } else {
      blankCursor = java.awt.Toolkit.getDefaultToolkit().createCustomCursor(cursorImg, new Point(0, 0), "blank cursor");
    }
  }

	public void setVariableInspector(VariableInspector inspector) {
		this.variableInspector = inspector;
	}

	public void setLiveHandles(List<List<Handle>> handles) {
		this.liveHandles = handles;
	}

	public void setLiveModeController(LiveModeController controller) {
		this.liveModeController = controller;
	}

	@Override
	synchronized public void paint(Graphics gfx) {
		super.paint(gfx);

		// Paint number scrubbing hover highlight (Tangle.js-inspired)
		if (hoverNumLine >= 0 || scrubbing) {
			int line = scrubbing ? scrubLine : hoverNumLine;
			if (line < 0 || line >= textArea.getLineCount()) {
				hoverNumLine = -1;
			} else {
			Graphics2D g2 = (Graphics2D) gfx;
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int start = scrubbing ? scrubStartOffset : hoverNumStart;
			int end = scrubbing ? scrubEndOffset : hoverNumEnd;
			int lineStart = textArea.getLineStartOffset(line);
			int x1 = textArea.offsetToX(line, start - lineStart);
			int x2 = textArea.offsetToX(line, end - lineStart);
			int y = textArea.lineToY(line) + fontMetrics.getDescent();
			int h = fontMetrics.getHeight();

			if (scrubbing) {
				// Active scrubbing: warm highlight + solid underline
				g2.setColor(new Color(68, 102, 255, 35));
				g2.fillRoundRect(x1 - 3, y, x2 - x1 + 6, h, 4, 4);
				g2.setColor(new Color(0, 0, 204, 200));
				g2.fillRect(x1, y + h - 2, x2 - x1, 2);
			} else {
				// Hover: dashed underline in Tangle blue (#46f)
				Stroke oldStroke = g2.getStroke();
				g2.setColor(new Color(68, 102, 255, 180));
				g2.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT,
					BasicStroke.JOIN_MITER, 1.0f, new float[]{3, 2}, 0));
				g2.drawLine(x1, y + h - 1, x2, y + h - 1);
				g2.setStroke(oldStroke);
			}
			} // else (valid line)
		}

		if (tweakMode && handles != null) {
			int currentTab = getCurrentCodeIndex();
			// enable anti-aliasing
			Graphics2D g2d = (Graphics2D)gfx;
			g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                				   RenderingHints.VALUE_ANTIALIAS_ON);

			for (Handle n : handles.get(currentTab)) {
				// update n position and width, and draw it
				int lineStartChar = textArea.getLineStartOffset(n.line);
				int x = textArea.offsetToX(n.line, n.newStartChar - lineStartChar);
				int y = textArea.lineToY(n.line) + fontMetrics.getHeight() + 1;
				int end = textArea.offsetToX(n.line, n.newEndChar - lineStartChar);
				n.setPos(x, y);
				n.setWidth(end - x);
				n.draw(g2d, n==mouseHandle);
			}

			// draw color boxes
			for (ColorControlBox cBox: colorBoxes.get(currentTab)) {
				int lineStartChar = textArea.getLineStartOffset(cBox.getLine());
				int x = textArea.offsetToX(cBox.getLine(), cBox.getCharIndex() - lineStartChar);
				int y = textArea.lineToY(cBox.getLine()) + fontMetrics.getDescent();
				cBox.setPos(x, y+1);
				cBox.draw(g2d);
			}
		}

		// Variable overlay removed — now displayed in LiveVariablePanel side panel
	}


	/**
	 * Paint inline variable values at the right edge of visible lines.
	 * Inspired by Bret Victor's "Learnable Programming" — state is always
	 * visible, never hidden. Values appear as compact annotations in the
	 * right margin with type-aware color coding.
	 */
	private void paintVariableOverlay(Graphics gfx) {
		VariableInspector vi = variableInspector; // local copy to avoid race
		if (vi == null) return;

		int currentTab = getCurrentCodeIndex();
		int firstLine = textArea.getFirstLine();
		int lastLine = firstLine + textArea.getVisibleLines();
		int lineCount = textArea.getLineCount();

		Graphics2D g2 = (Graphics2D) gfx;
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
		                    RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
		                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		Font origFont = gfx.getFont();
		int varFontSize = Math.max(10, (int)(origFont.getSize() * 0.85));
		Font nameFont = new Font(Font.SANS_SERIF, Font.PLAIN, varFontSize);
		Font valueFont = new Font(Font.MONOSPACED, Font.BOLD, varFontSize);

		// Type-aware accent colors
		Color numericColor = new Color(86, 196, 126);   // green for numbers
		Color stringColor = new Color(230, 180, 80);    // amber for strings
		Color boolColor = new Color(100, 190, 230);     // cyan for booleans
		Color defaultColor = new Color(170, 170, 190);  // gray for other types

		for (int line = firstLine; line < lastLine && line < lineCount; line++) {
			Map<String, String> vars = vi.getVariables(currentTab, line);
			if (vars.isEmpty()) continue;

			int rightEdge = getWidth() - Toolkit.zoom(12);
			int y = textArea.lineToY(line) + fontMetrics.getHeight() - 2;

			// Paint each variable right-aligned
			int x = rightEdge;
			for (Map.Entry<String, String> entry : vars.entrySet()) {
				String name = entry.getKey();
				String value = entry.getValue();

				// Determine value type for color coding
				Color valueColor = getValueColor(value, numericColor, stringColor,
				                                  boolColor, defaultColor);

				g2.setFont(nameFont);
				FontMetrics nameFm = g2.getFontMetrics();
				int nameW = nameFm.stringWidth(name);
				int arrowW = nameFm.stringWidth(" \u2192 "); // right arrow
				g2.setFont(valueFont);
				FontMetrics valFm = g2.getFontMetrics();
				int valW = valFm.stringWidth(value);
				int pad = Toolkit.zoom(10);
				int pillW = nameW + arrowW + valW + pad;
				int pillH = Math.max(nameFm.getHeight(), valFm.getHeight()) + Toolkit.zoom(4);

				x -= pillW + Toolkit.zoom(6);
				if (x < getWidth() / 2) break; // don't overlap code

				int pillY = y - pillH + Toolkit.zoom(4);

				// Pill background with subtle shadow
				g2.setColor(new Color(30, 32, 38, 180));
				g2.fillRoundRect(x + 1, pillY + 1, pillW, pillH,
				                 Toolkit.zoom(5), Toolkit.zoom(5));
				g2.setColor(new Color(40, 42, 50, 220));
				g2.fillRoundRect(x, pillY, pillW, pillH,
				                 Toolkit.zoom(5), Toolkit.zoom(5));

				// Left accent bar (color matches value type)
				g2.setColor(new Color(valueColor.getRed(), valueColor.getGreen(),
				                      valueColor.getBlue(), 200));
				g2.fillRoundRect(x, pillY, Toolkit.zoom(3), pillH,
				                 Toolkit.zoom(2), Toolkit.zoom(2));

				// Name (muted)
				int textY = y;
				int textX = x + Toolkit.zoom(7);
				g2.setFont(nameFont);
				g2.setColor(new Color(140, 145, 165));
				g2.drawString(name, textX, textY);
				textX += nameW;

				// Arrow separator
				g2.setColor(new Color(80, 85, 100));
				g2.drawString(" \u2192 ", textX, textY);
				textX += arrowW;

				// Value (type-colored, bold monospace)
				g2.setFont(valueFont);
				g2.setColor(valueColor);
				g2.drawString(value, textX, textY);
			}
		}
		g2.setFont(origFont);
	}

	/**
	 * Determine the display color for a variable value based on its content.
	 */
	private static Color getValueColor(String value, Color numeric,
	                                    Color string, Color bool, Color other) {
		if (value == null || value.isEmpty()) return other;
		if ("true".equals(value) || "false".equals(value)) return bool;
		// Check if numeric (integer or float)
		char first = value.charAt(0);
		if (Character.isDigit(first) || first == '-' || first == '.') {
			try {
				Double.parseDouble(value.endsWith("f") || value.endsWith("F")
				    ? value.substring(0, value.length() - 1) : value);
				return numeric;
			} catch (NumberFormatException e) { /* fall through */ }
		}
		if (value.startsWith("\"") || value.startsWith("'")) return string;
		return other;
	}


	protected void startTweakMode() {
	  addMouseListener(new MouseListener() {

	    @Override
	    public void mouseReleased(MouseEvent e) {
	      if (mouseHandle != null) {
	        mouseHandle.resetProgress();
	        mouseHandle = null;

	        updateCursor(e.getX(), e.getY());
	        repaint();
	      }
	    }

	    @Override
	    public void mousePressed(MouseEvent e) {
	      int currentTab = getCurrentCodeIndex();
	      // check for clicks on number handles
	      for (Handle n : handles.get(currentTab)) {
	        if (n.pick(e.getX(), e.getY())) {
	          cursorType = -1;
	          JavaTextAreaPainter.this.setCursor(blankCursor);
	          mouseHandle = n;
	          mouseHandle.setCenterX(e.getX());
	          repaint();
	          return;
	        }
	      }

	      // check for clicks on color boxes
	      for (ColorControlBox box : colorBoxes.get(currentTab)) {
	        if (box.pick(e.getX(), e.getY())) {
	          if (colorSelector != null) {
	            // we already show a color selector, close it
	            colorSelector.frame.dispatchEvent(new WindowEvent(colorSelector.frame, WindowEvent.WINDOW_CLOSING));
	          }

	          colorSelector = new ColorSelector(box);
	          colorSelector.frame.addWindowListener(new WindowAdapter() {
	            public void windowClosing(WindowEvent e) {
	              colorSelector.frame.setVisible(false);
	              colorSelector = null;
	            }
	          });
	          colorSelector.show(getLocationOnScreen().x + e.getX() + 30,
	                             getLocationOnScreen().y + e.getY() - 130);
	        }
	      }
	    }

      @Override
      public void mouseExited(MouseEvent e) { }

      @Override
      public void mouseEntered(MouseEvent e) { }

      @Override
      public void mouseClicked(MouseEvent e) { }
    });

	  addMouseMotionListener(new MouseMotionListener() {

	    @Override
	    public void mouseMoved(MouseEvent e) {
	      updateCursor(e.getX(), e.getY());

	      if (!Settings.alwaysShowColorBoxes) {
	        showHideColorBoxes(e.getY());
	      }
	    }

	    @Override
	    public void mouseDragged(MouseEvent e) {
	      if (mouseHandle != null) {
	        // set the current drag amount of the arrows
	        mouseHandle.setCurrentX(e.getX());

	        // update code text with the new value
	        updateCodeText();

	        if (colorSelector != null) {
	          colorSelector.refreshColor();
	        }

	        repaint();
	      }
	    }
    });
	  tweakMode = true;
	  setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
		repaint();
	}


	protected void stopTweakMode() {
		tweakMode = false;

		if (colorSelector != null) {
			colorSelector.hide();
			WindowEvent windowEvent =
			  new WindowEvent(colorSelector.frame, WindowEvent.WINDOW_CLOSING);
			colorSelector.frame.dispatchEvent(windowEvent);
		}

		setCursor(new Cursor(Cursor.TEXT_CURSOR));
		repaint();
	}


	protected void updateTweakInterface(List<List<Handle>> handles,
	                                    List<List<ColorControlBox>> colorBoxes) {
		this.handles = handles;
		this.colorBoxes = colorBoxes;

		updateTweakInterfacePositions();
		repaint();
	}


	/**
	* Initialize all the number changing interfaces.
	* synchronize this method to prevent the execution of 'paint' in the middle.
	* (don't paint while we make changes to the text of the editor)
	*/
	private synchronized void updateTweakInterfacePositions() {
		SketchCode[] code = getEditor().getSketch().getCode();
		int prevScroll = textArea.getVerticalScrollPosition();
		String prevText = textArea.getText();

		for (int tab=0; tab<code.length; tab++) {
			String tabCode = getJavaEditor().baseCode[tab];
			textArea.setText(tabCode);
			for (Handle n : handles.get(tab)) {
				int lineStartChar = textArea.getLineStartOffset(n.line);
				int x = textArea.offsetToX(n.line, n.newStartChar - lineStartChar);
				int end = textArea.offsetToX(n.line, n.newEndChar - lineStartChar);
				int y = textArea.lineToY(n.line) + fontMetrics.getHeight() + 1;
				n.initInterface(x, y, end-x, fontMetrics.getHeight());
			}

			for (ColorControlBox cBox : colorBoxes.get(tab)) {
				int lineStartChar = textArea.getLineStartOffset(cBox.getLine());
				int x = textArea.offsetToX(cBox.getLine(), cBox.getCharIndex() - lineStartChar);
				int y = textArea.lineToY(cBox.getLine()) + fontMetrics.getDescent();
				cBox.initInterface(this, x, y+1, fontMetrics.getHeight()-2, fontMetrics.getHeight()-2);
			}
		}

		textArea.setText(prevText);
		textArea.scrollTo(prevScroll, 0);
	}


	/**
	 * Take the saved code of the current tab and replace
	 * all numbers with their current values.
	 * Update TextArea with the new code.
	 */
	public void updateCodeText() {
		int charInc = 0;
		int currentTab = getCurrentCodeIndex();
		SketchCode sc = getEditor().getSketch().getCode(currentTab);
		String code = getJavaEditor().baseCode[currentTab];

		for (Handle n : handles.get(currentTab)) {
			int s = n.startChar + charInc;
			int e = n.endChar + charInc;
			code = replaceString(code, s, e, n.strNewValue);
			n.newStartChar = n.startChar + charInc;
			charInc += n.strNewValue.length() - n.strValue.length();
			n.newEndChar = n.endChar + charInc;
		}

		replaceTextAreaCode(code);
		// update also the sketch code for later
		sc.setProgram(code);
	}


	// don't paint while we do the stuff below
	private synchronized void replaceTextAreaCode(String code) {
	  // by default setText will scroll all the way to the end
	  // remember current scroll position
	  int scrollLine = textArea.getVerticalScrollPosition();
	  int scrollHor = textArea.getHorizontalScrollPosition();
	  textArea.setText(code);
	  textArea.setOrigin(scrollLine, -scrollHor);
	}


	static private String replaceString(String str, int start, int end, String put) {
		return str.substring(0, start) + put + str.substring(end);
	}


	private void updateCursor(int mouseX, int mouseY) {
		int currentTab = getCurrentCodeIndex();
		for (Handle n : handles.get(currentTab)) {
			if (n.pick(mouseX, mouseY)) {
				cursorType = Cursor.W_RESIZE_CURSOR;
				setCursor(new Cursor(cursorType));
				return;
			}
		}

		for (ColorControlBox colorBox : colorBoxes.get(currentTab)) {
			if (colorBox.pick(mouseX, mouseY)) {
				cursorType = Cursor.HAND_CURSOR;
				setCursor(new Cursor(cursorType));
				return;
			}
		}

		if (cursorType == Cursor.W_RESIZE_CURSOR ||
		    cursorType == Cursor.HAND_CURSOR ||
		    cursorType == -1) {
		  cursorType = Cursor.DEFAULT_CURSOR;
			setCursor(new Cursor(cursorType));
		}
	}


	private void showHideColorBoxes(int y) {
	  // display the box if the mouse is in the same line.
	  // always keep the color box of the color selector.
		int currentTab = getCurrentCodeIndex();

		boolean change = false;
		for (ColorControlBox box : colorBoxes.get(currentTab)) {
			if (box.setMouseY(y)) {
				change = true;
			}
		}

		if (colorSelector != null) {
			colorSelector.colorBox.visible = true;
		}

		if (change) {
			repaint();
		}
	}


	// . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .


	private JavaEditor getJavaEditor() {
	  return (JavaEditor) getEditor();
	}


	private int getCurrentCodeIndex() {
	  return getEditor().getSketch().getCurrentCodeIndex();
	}
}
