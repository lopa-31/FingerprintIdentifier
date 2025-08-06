import cv2
import numpy as np

def analyze_brightness(image_path, bright_spot_thresh=240, spot_area_thresh=300):
    # Load image
    img = cv2.imread(image_path)
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)

    # --------- GLOBAL BRIGHTNESS SCORE ----------
    mean_brightness = np.mean(gray)

    # --------- BRIGHT SPOT DETECTION ------------
    _, bright_mask = cv2.threshold(gray, bright_spot_thresh, 255, cv2.THRESH_BINARY)
    bright_spot_area = cv2.countNonZero(bright_mask)

    # Optional: Highlight bright spots in red (for visualization)
    output = img.copy()
    contours, _ = cv2.findContours(bright_mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    for cnt in contours:
        area = cv2.contourArea(cnt)
        if area > spot_area_thresh:
            x, y, w, h = cv2.boundingRect(cnt)
            cv2.rectangle(output, (x, y), (x+w, y+h), (0, 0, 255), 2)  # red box

    # ---------- INTERPRETATION ----------
    brightness_quality = "OK"
    if mean_brightness < 80:
        brightness_quality = "Too Dark"
    elif mean_brightness > 200:
        brightness_quality = "Too Bright"

    has_glare = bright_spot_area > spot_area_thresh
    glare_quality = "Glare Detected" if has_glare else "No Glare"

    # ---------- METRICS ----------
    result = {
        "mean_brightness": round(mean_brightness, 2),
        "brightness_quality": brightness_quality,
        "bright_spot_area": int(bright_spot_area),
        "glare_quality": glare_quality
    }

    return result, output