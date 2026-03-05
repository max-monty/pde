/**
 * Fade Example
 *
 * Fades an LED on PWM pin 9 using analogWrite.
 * Click the mouse to change the fade speed.
 *
 * Circuit: LED connected to pin 9 through a 220-ohm resistor.
 * Arduino must be running StandardFirmata.
 */

import processing.arduino.*;

Arduino arduino;
float brightness = 0;
float fadeAmount = 2;

void setup() {
  size(400, 300);
  arduino = new Arduino(this);
}

void draw() {
  // Update brightness
  brightness += fadeAmount;
  if (brightness <= 0 || brightness >= 255) {
    fadeAmount = -fadeAmount;
    brightness = constrain(brightness, 0, 255);
  }

  // Write to LED
  arduino.analogWrite(9, (int) brightness);

  // Visualize
  background(0);
  fill(255, 200, 0, brightness);
  noStroke();
  ellipse(width/2, height/2, 150, 150);

  fill(200);
  textAlign(CENTER);
  textSize(16);
  text("PWM: " + (int) brightness, width/2, height - 30);
}

void mousePressed() {
  // Click to change fade speed
  fadeAmount = map(mouseX, 0, width, 0.5, 10);
}
