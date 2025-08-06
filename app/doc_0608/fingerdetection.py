import cv2
import numpy as np

def detect_finger_and_distance(image, overlay_box):
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    blurred = cv2.GaussianBlur(gray, (7, 7), 0)
    edges = cv2.Canny(blurred, 50, 150)

    contours, _ = cv2.findContours(edges, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

    for cnt in contours:
        area = cv2.contourArea(cnt)
        if area > 2000:  # Skip small noise
            x, y, w, h = cv2.boundingRect(cnt)

            # Check if contour lies within overlay
            ox, oy, ow, oh = overlay_box
            if x > ox and y > oy and x + w < ox + ow and y + h < oy + oh:
                # Draw rectangle (debug)
                cv2.rectangle(image, (x, y), (x+w, y+h), (0, 255, 0), 2)

                # Distance Estimation
                overlay_width = ow
                if w < 0.4 * overlay_width:
                    return "Too Far", (x, y, w, h)
                elif w > 0.9 * overlay_width:
                    return "Too Close", (x, y, w, h)
                else:
                    return "Perfect", (x, y, w, h)

    return "No finger detected", None