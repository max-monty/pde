package processing.mode.java.livemode;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;

import processing.app.ui.Toolkit;


/**
 * A compact playback control panel for live mode.
 * Features: step back, pause/play, step forward, restart, and frame counter.
 */
public class TimelinePanel extends JPanel {
  private static final int HEIGHT = Toolkit.zoom(36);

  // Colors
  private static final Color BG_COLOR = new Color(35, 35, 38);
  private static final Color TEXT_COLOR = new Color(180, 180, 185);
  private static final Color TEXT_DIM = new Color(100, 100, 105);
  private static final Color BTN_COLOR = new Color(190, 190, 195);
  private static final Color BTN_HOVER = new Color(255, 255, 255);
  private static final Color LIVE_DOT = new Color(255, 60, 60);
  private static final Color SEPARATOR = new Color(55, 55, 60);
  private static final Color BTN_BG_HOVER = new Color(70, 70, 75);

  // Cached fonts
  private static final Font LIVE_FONT = new Font(Font.SANS_SERIF, Font.BOLD, Toolkit.zoom(10));
  private static final Font FRAME_FONT = new Font(Font.MONOSPACED, Font.PLAIN, Toolkit.zoom(11));

  private boolean paused = false;
  private int currentFrame = 0;

  // Button hover states: -1=none, 0=back, 1=pause, 2=forward, 3=restart
  private int hoveredButton = -1;

  // Live indicator animation
  private float liveAlpha = 1.0f;
  private boolean liveAlphaGrowing = false;
  private Timer pulseTimer;

  private TimelineListener listener;


  public interface TimelineListener {
    void onPauseToggle(boolean paused);
    void onStepForward();
    void onStepBackward();
    void onRestart();
  }


  public TimelinePanel() {
    setPreferredSize(new Dimension(100, HEIGHT));
    setBackground(BG_COLOR);
    setOpaque(true);
    setFocusable(false);

    // Pulse timer for the live indicator
    pulseTimer = new Timer(50, e -> {
      if (liveAlphaGrowing) {
        liveAlpha += 0.04f;
        if (liveAlpha >= 1.0f) { liveAlpha = 1.0f; liveAlphaGrowing = false; }
      } else {
        liveAlpha -= 0.04f;
        if (liveAlpha <= 0.3f) { liveAlpha = 0.3f; liveAlphaGrowing = true; }
      }
      repaint(0, 0, Toolkit.zoom(80), HEIGHT);
    });

    addMouseListener(new MouseAdapter() {
      @Override
      public void mousePressed(MouseEvent e) {
        int btn = getButtonAt(e.getX(), e.getY());
        if (btn >= 0) {
          handleButtonClick(btn);
        }
      }
    });

    addMouseMotionListener(new MouseMotionAdapter() {
      @Override
      public void mouseMoved(MouseEvent e) {
        int btn = getButtonAt(e.getX(), e.getY());
        if (btn != hoveredButton) {
          hoveredButton = btn;
          repaint();
        }
      }
    });

    setVisible(false);
  }


  private void handleButtonClick(int btn) {
    if (listener == null) return;
    switch (btn) {
      case 0: listener.onStepBackward(); break;
      case 1:
        paused = !paused;
        listener.onPauseToggle(paused);
        repaint();
        break;
      case 2: listener.onStepForward(); break;
      case 3: listener.onRestart(); break;
    }
  }


  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2 = (Graphics2D) g;
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

    int w = getWidth();
    int h = getHeight();

    // Top separator line
    g2.setColor(SEPARATOR);
    g2.fillRect(0, 0, w, 1);

    // Layout measurements
    int pad = Toolkit.zoom(8);
    int liveWidth = Toolkit.zoom(58);
    int btnSize = Toolkit.zoom(24);
    int btnGap = Toolkit.zoom(2);

    // 1. Paint live indicator
    paintLiveIndicator(g2, pad, h);

    // 2. Paint playback buttons
    int btnX = liveWidth + pad;
    int btnY = (h - btnSize) / 2;
    paintShapeButton(g2, btnX, btnY, btnSize, 0);
    btnX += btnSize + btnGap;
    paintShapeButton(g2, btnX, btnY, btnSize, 1);
    btnX += btnSize + btnGap;
    paintShapeButton(g2, btnX, btnY, btnSize, 2);
    btnX += btnSize + btnGap;
    paintShapeButton(g2, btnX, btnY, btnSize, 3);

    // 3. Paint frame counter after buttons
    int frameX = btnX + btnSize + pad;
    paintFrameCounter(g2, frameX, h);
  }


  private void paintLiveIndicator(Graphics2D g2, int x, int h) {
    int dotSize = Toolkit.zoom(8);
    int dotY = (h - dotSize) / 2;
    int dotX = x + Toolkit.zoom(4);

    if (paused) {
      g2.setColor(new Color(230, 180, 60));
      g2.fillOval(dotX, dotY, dotSize, dotSize);
      g2.setFont(LIVE_FONT);
      g2.setColor(new Color(200, 170, 80));
      g2.drawString("PAUSED", dotX + dotSize + Toolkit.zoom(4), dotY + dotSize - 1);
    } else {
      int alpha = (int)(liveAlpha * 255);
      g2.setColor(new Color(LIVE_DOT.getRed(), LIVE_DOT.getGreen(), LIVE_DOT.getBlue(), alpha));
      g2.fillOval(dotX, dotY, dotSize, dotSize);
      g2.setFont(LIVE_FONT);
      g2.setColor(new Color(TEXT_COLOR.getRed(), TEXT_COLOR.getGreen(), TEXT_COLOR.getBlue(), alpha));
      g2.drawString("LIVE", dotX + dotSize + Toolkit.zoom(4), dotY + dotSize - 1);
    }
  }


  private void paintShapeButton(Graphics2D g2, int x, int y, int size, int btnId) {
    boolean hover = (hoveredButton == btnId);

    if (hover) {
      g2.setColor(BTN_BG_HOVER);
      g2.fillRoundRect(x, y, size, size, Toolkit.zoom(4), Toolkit.zoom(4));
    }

    g2.setColor(hover ? BTN_HOVER : BTN_COLOR);
    int cx = x + size / 2;
    int cy = y + size / 2;
    int s = Toolkit.zoom(5);

    switch (btnId) {
      case 0: // step backward
        int[] bxPts = {cx + s/2, cx - s + 1, cx + s/2};
        int[] byPts = {cy - s, cy, cy + s};
        g2.fillPolygon(bxPts, byPts, 3);
        g2.fillRect(cx - s, cy - s, Toolkit.zoom(2), s * 2);
        break;
      case 1: // play/pause
        if (paused) {
          int[] pxPts = {cx - s/2, cx - s/2, cx + s};
          int[] pyPts = {cy - s, cy + s, cy};
          g2.fillPolygon(pxPts, pyPts, 3);
        } else {
          int barW = Toolkit.zoom(3);
          int gap = Toolkit.zoom(2);
          g2.fillRect(cx - gap - barW, cy - s, barW, s * 2);
          g2.fillRect(cx + gap, cy - s, barW, s * 2);
        }
        break;
      case 2: // step forward
        int[] fxPts = {cx - s/2, cx + s - 1, cx - s/2};
        int[] fyPts = {cy - s, cy, cy + s};
        g2.fillPolygon(fxPts, fyPts, 3);
        g2.fillRect(cx + s - 1, cy - s, Toolkit.zoom(2), s * 2);
        break;
      case 3: // restart
        Stroke oldStroke = g2.getStroke();
        g2.setStroke(new BasicStroke(Toolkit.zoom(2), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int arcR = s - 1;
        g2.drawArc(cx - arcR, cy - arcR, arcR * 2, arcR * 2, 45, 270);
        int arrowTipX = cx + (int)(arcR * Math.cos(Math.toRadians(45)));
        int arrowTipY = cy - (int)(arcR * Math.sin(Math.toRadians(45)));
        int as = Toolkit.zoom(3);
        int[] axPts = {arrowTipX, arrowTipX - as, arrowTipX + as/2};
        int[] ayPts = {arrowTipY, arrowTipY + as, arrowTipY + as};
        g2.fillPolygon(axPts, ayPts, 3);
        g2.setStroke(oldStroke);
        break;
    }
  }


  private int getButtonAt(int mx, int my) {
    int pad = Toolkit.zoom(8);
    int liveWidth = Toolkit.zoom(58);
    int btnSize = Toolkit.zoom(24);
    int btnGap = Toolkit.zoom(2);
    int btnY = (getHeight() - btnSize) / 2;

    int btnX = liveWidth + pad;
    for (int i = 0; i < 4; i++) {
      if (mx >= btnX && mx < btnX + btnSize && my >= btnY && my < btnY + btnSize) {
        return i;
      }
      btnX += btnSize + btnGap;
    }
    return -1;
  }


  private void paintFrameCounter(Graphics2D g2, int x, int h) {
    g2.setFont(FRAME_FONT);
    FontMetrics fm = g2.getFontMetrics();
    int ty = (h + fm.getAscent() - fm.getDescent()) / 2;

    g2.setColor(TEXT_DIM);
    g2.drawString("f", x, ty);
    g2.setColor(paused ? new Color(230, 180, 80) : TEXT_COLOR);
    g2.drawString(String.valueOf(currentFrame), x + fm.stringWidth("f "), ty);
  }


  // ---- Public API ----

  public void setTimelineListener(TimelineListener listener) {
    this.listener = listener;
  }


  public void updateFrame(int frame) {
    if (frame == this.currentFrame) return;
    this.currentFrame = frame;
    repaint();
  }


  public void reset() {
    currentFrame = 0;
    paused = false;
    repaint();
  }


  @Override
  public void setVisible(boolean visible) {
    super.setVisible(visible);
    if (visible) {
      pulseTimer.start();
    } else {
      pulseTimer.stop();
    }
  }


  public boolean isPaused() {
    return paused;
  }

  public int getCurrentFrame() {
    return currentFrame;
  }

  public void setPaused(boolean paused) {
    this.paused = paused;
    repaint();
  }
}
