"""
helpers.py - Common utilities, geometry intersection engines, and visual assertion helpers
for the CodeLens Playwright E2E & Visual Regression Test Suite.
"""

import os
from typing import List, Dict, Any, Optional

BASE_URL = os.environ.get("CODELENS_URL", "http://localhost:7878")
SCREENSHOT_DIR = os.path.join(os.path.dirname(__file__), "screenshots")
os.makedirs(SCREENSHOT_DIR, exist_ok=True)


def get_bounding_boxes(page, selectors: List[str]) -> List[Dict[str, Any]]:
    """Retrieve rendered bounding client rects for a list of selectors."""
    return page.evaluate("""(selectors) => {
        const results = [];
        for (const sel of selectors) {
            const elements = document.querySelectorAll(sel);
            elements.forEach((el, index) => {
                const style = window.getComputedStyle(el);
                if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') {
                    return;
                }
                const rect = el.getBoundingClientRect();
                if (rect.width > 0 && rect.height > 0) {
                    results.push({
                        selector: sel,
                        index: index,
                        id: el.id || '',
                        className: el.className || '',
                        x: rect.x,
                        y: rect.y,
                        left: rect.left,
                        top: rect.top,
                        right: rect.right,
                        bottom: rect.bottom,
                        width: rect.width,
                        height: rect.height,
                        zIndex: parseInt(style.zIndex, 10) || 0
                    });
                }
            });
        }
        return results;
    }""", selectors)


def check_element_overlaps(page, selectors: List[str], allowed_overlap_tolerance_px: float = 2.0) -> List[Dict[str, Any]]:
    """
    Check if any visible floating UI elements specified in selectors overlap each other.
    Returns a list of detected overlaps with their bounding rects and intersection area.
    """
    boxes = get_bounding_boxes(page, selectors)
    overlaps = []

    for i in range(len(boxes)):
        for j in range(i + 1, len(boxes)):
            b1 = boxes[i]
            b2 = boxes[j]

            # Calculate overlap rectangle
            x_overlap = max(0.0, min(b1["right"], b2["right"]) - max(b1["left"], b2["left"]))
            y_overlap = max(0.0, min(b1["bottom"], b2["bottom"]) - max(b1["top"], b2["top"]))

            if x_overlap > allowed_overlap_tolerance_px and y_overlap > allowed_overlap_tolerance_px:
                area = x_overlap * y_overlap
                overlaps.append({
                    "element1": f"{b1['selector']} (id: {b1['id']})",
                    "element2": f"{b2['selector']} (id: {b2['id']})",
                    "box1": b1,
                    "box2": b2,
                    "overlapWidth": x_overlap,
                    "overlapHeight": y_overlap,
                    "overlapArea": area
                })

    return overlaps


def check_no_horizontal_overflow(page) -> bool:
    """Ensure the root page does not overflow horizontally."""
    return page.evaluate("() => document.documentElement.scrollWidth <= window.innerWidth + 2")


def wait_for_scene_ready(page, timeout_ms: int = 2000):
    """Wait for WebGL, 2D Canvas, or SVG visualization rendering to settle."""
    page.wait_for_timeout(350)


def save_screenshot(page, filename: str) -> str:
    """Save a full viewport screenshot to the screenshots directory."""
    path = os.path.join(SCREENSHOT_DIR, f"{filename}.png")
    page.screenshot(path=path, full_page=False, timeout=5000, animations="disabled")
    return path
