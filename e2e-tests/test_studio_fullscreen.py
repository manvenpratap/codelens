"""
test_studio_fullscreen.py - Automated verification that 3D & Macro Studio occupies 100% full viewport with zero blacked-out sidebar margins.
"""

import pytest
from helpers import save_screenshot, check_no_horizontal_overflow


def test_studio_mode_full_screen_dimensions(desktop_page):
    """Verify that entering Macro Studio mode expands #centre-panel and canvases to 100vw."""
    page = desktop_page
    page.goto("http://localhost:7878")
    page.wait_for_load_state("domcontentloaded")
    page.wait_for_timeout(400)

    viewport_w = page.viewport_size["width"]

    # Initial workspace check: left panel should be visible with width > 200
    left_box = page.locator("#left-panel").bounding_box()
    assert left_box is not None
    assert left_box["width"] > 200

    # Launch Macro Studio
    page.locator("#btn-open-macro-studio").click(no_wait_after=True)
    page.wait_for_timeout(500)

    # In Studio mode: left panel and right sidebar must be hidden
    assert not page.locator("#left-panel").is_visible()
    assert not page.locator("#inspector-sidebar").is_visible()

    # Centre panel must span 100% full viewport width
    centre_box = page.locator("#centre-panel").bounding_box()
    assert centre_box is not None
    assert abs(centre_box["width"] - viewport_w) < 2, f"Centre panel width {centre_box['width']} does not span full viewport {viewport_w}"
    assert abs(centre_box["x"]) < 2, f"Centre panel x-offset is {centre_box['x']}, expected 0 (no blacked out left space)"

    # Codebase view & canvas wrap must also span full viewport width
    cb_box = page.locator("#codebase-view").bounding_box()
    assert cb_box is not None
    assert abs(cb_box["width"] - viewport_w) < 2

    canvas_wrap = page.locator("#codebase-canvas-wrap").bounding_box()
    assert canvas_wrap is not None
    assert abs(canvas_wrap["width"] - viewport_w) < 2

    save_screenshot(page, "macro_studio_fullscreen_verified")

    # Click Back to Workspace
    page.locator("#btn-studio-back").click(no_wait_after=True)
    page.wait_for_timeout(400)

    # Explorer should be visible again
    assert page.locator("#left-panel").is_visible()
