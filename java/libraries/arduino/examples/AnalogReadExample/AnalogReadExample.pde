/**
 * Analog Read Example
 *
 * Reads an analog sensor on A0 and uses the value
 * to control the background brightness.
 *
 * Circuit: potentiometer connected to A0.
 * Arduino must be running StandardFirmata.
 */

import processing.arduino.*;

Arduino arduino;

void setup() {
  size(400, 300);
  arduino = new Arduino(this);
}

void draw() {
  // Read the sensor (0-1023)
  int sensor = arduino.analogRead(0);

  // Map to brightness (0-255)
  float brightness = map(sensor, 0, 1023, 0, 255);
  background(brightness);

  // Display the value
  fill(brightness > 128 ? 0 : 255);
  textAlign(CENTER, CENTER);
  textSize(32);
  text("A0: " + sensor, width/2, height/2);
}
