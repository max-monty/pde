/**
 * Button Example
 *
 * Reads a digital pushbutton on pin 2 and shows
 * its state on screen.
 *
 * Circuit: pushbutton connected to pin 2 with pull-down resistor.
 * Arduino must be running StandardFirmata.
 */

import processing.arduino.*;

Arduino arduino;

void setup() {
  size(400, 300);
  arduino = new Arduino(this);
  arduino.pinMode(2, Arduino.INPUT);
}

void draw() {
  int buttonState = arduino.digitalRead(2);

  if (buttonState == Arduino.HIGH) {
    background(0, 200, 100);
    fill(255);
    text("PRESSED", width/2, height/2);
  } else {
    background(100);
    fill(200);
    text("Released", width/2, height/2);
  }

  textAlign(CENTER, CENTER);
  textSize(32);
}
