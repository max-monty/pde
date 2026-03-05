/*
  Part of the Processing project - http://processing.org

  Copyright (c) 2013-15 The Processing Foundation
  Copyright (c) 2010-13 Ben Fry and Casey Reas

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

import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.Box;
import javax.swing.JLabel;

import processing.app.Language;
import processing.app.ui.Editor;
import processing.app.ui.EditorButton;
import processing.app.ui.EditorToolbar;


public class JavaToolbar extends EditorToolbar {
  JavaEditor jeditor;

  EditorButton liveButton;
  EditorButton arduinoButton;


  public JavaToolbar(Editor editor) {
    super(editor);
    jeditor = (JavaEditor) editor;
  }


  @Override
  public List<EditorButton> createButtons() {
    List<EditorButton> outgoing = new ArrayList<>();

    // Live Preview as the primary button (top-left)
    liveButton =
      new EditorButton(this, "/lib/toolbar/live",
                       "Live Preview") {
      @Override
      public void actionPerformed(ActionEvent e) {
        jeditor.toggleLive();
        setSelected(jeditor.isLiveMode());
      }
    };
    outgoing.add(liveButton);

    // Arduino button next to Live
    arduinoButton =
      new EditorButton(this, "/lib/toolbar/arduino",
                       "Arduino") {
      @Override
      public void actionPerformed(ActionEvent e) {
        jeditor.showArduinoPortMenu(arduinoButton);
      }
    };
    outgoing.add(arduinoButton);

    // Keep run/stop buttons as hidden references (other code calls activateRun/activateStop)
    runButton = new EditorButton(this,
                                 "/lib/toolbar/run",
                                 Language.text("toolbar.run")) {
      @Override
      public void actionPerformed(ActionEvent e) {
        handleRun(e.getModifiers());
      }
    };
    runButton.setVisible(false);

    stopButton = new EditorButton(this,
                                  "/lib/toolbar/stop",
                                  Language.text("toolbar.stop")) {
      @Override
      public void actionPerformed(ActionEvent e) {
        handleStop();
      }
    };
    stopButton.setVisible(false);

    return outgoing;
  }


  @Override
  public void addModeButtons(Box box, JLabel label) {
    // No mode buttons on the right — Live and Arduino are primary buttons now
  }


  @Override
  public void handleRun(int modifiers) {
    boolean shift = (modifiers & ActionEvent.SHIFT_MASK) != 0;
    if (shift) {
      jeditor.handlePresent();
    } else {
      jeditor.handleRun();
    }
  }


  @Override
  public void handleStop() {
    jeditor.handleStop();
  }


  public void setLiveSelected(boolean selected) {
    liveButton.setSelected(selected);
    repaint();
  }


  public void setArduinoSelected(boolean selected) {
    arduinoButton.setSelected(selected);
    repaint();
  }
}
