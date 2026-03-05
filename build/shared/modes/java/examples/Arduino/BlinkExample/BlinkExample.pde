/**
 * Blink Example
 *
 * Blinks the built-in LED on pin 13 every second.
 * This is the "Hello World" of Arduino.
 *
 * Circuit: no external circuit needed (uses built-in LED).
 * Arduino must be running StandardFirmata.
 */

import processing.arduino.*;

Arduino arduino;
boolean ledOn = false;
int lastToggle;

void setup() {
  size(300, 200);
  arduino = new Arduino(this);
  arduino.pinMode(13, Arduino.OUTPUT);
  lastToggle = millis();
}

void draw() {
  // Toggle LED every 1000ms
  if (millis() - lastToggle > 1000) {
    ledOn = !ledOn;
    arduino.digitalWrite(13, ledOn ? Arduino.HIGH : Arduino.LOW);
    lastToggle = millis();
  }

  // Visual feedback
  background(ledOn ? color(255, 200, 0) : 50);
  fill(255);
  textAlign(CENTER, CENTER);
  textSize(24);
  text(ledOn ? "LED ON" : "LED OFF", width/2, height/2);
}
