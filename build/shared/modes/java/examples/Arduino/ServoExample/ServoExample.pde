/**
 * Servo Example
 *
 * Controls a servo motor on pin 9 using the mouse position.
 * Move the mouse left and right to sweep the servo.
 *
 * Circuit: servo signal wire connected to pin 9.
 * Arduino must be running StandardFirmata.
 */

import processing.arduino.*;

Arduino arduino;
int angle = 90;

void setup() {
  size(400, 300);
  arduino = new Arduino(this);
}

void draw() {
  // Map mouse X to servo angle (0-180)
  angle = (int) map(mouseX, 0, width, 0, 180);
  angle = constrain(angle, 0, 180);
  arduino.servoWrite(9, angle);

  // Draw servo visualization
  background(30);
  fill(200);
  textAlign(CENTER);
  textSize(18);
  text("Servo Angle: " + angle + "°", width/2, 40);

  // Draw servo arm
  pushMatrix();
  translate(width/2, height/2 + 30);
  stroke(200);
  strokeWeight(3);
  noFill();
  arc(0, 0, 150, 150, PI, TWO_PI);

  float rad = radians(180 - angle);
  float armX = cos(rad) * 70;
  float armY = -sin(rad) * 70;
  stroke(255, 100, 100);
  strokeWeight(4);
  line(0, 0, armX, armY);

  fill(255, 100, 100);
  noStroke();
  ellipse(armX, armY, 12, 12);
  ellipse(0, 0, 10, 10);
  popMatrix();
}
