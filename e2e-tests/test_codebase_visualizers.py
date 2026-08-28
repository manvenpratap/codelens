"""
test_codebase_visualizers.py - Tests all 7 2D & 3D codebase visualizers in Classes and Methods scopes.
"""

import pytest
from helpers import wait_for_scene_ready, save_screenshot, check_no_horizontal_overflow


VISUALIZERS = [
    ("city3d", "3D Software City"),
    ("galaxy3d", "3D Force Galaxy"),
    ("graph2d", "2D Blooming Tree"),
    ("treemap", "Zoomable Treemap"),
    ("sunburst", "Sunburst Radial Hierarchy"),
    ("dsm", "Dependency Structure Matrix"),
    ("chord", "Chord Diagram"),
]


@pytest.mark.parametrize("level_id,name", VISUALIZERS)
def test_visualizer_classes_mode(desktop_page, level_id, name):
    """Test visualizer rendering in Classes architecture mode."""
    page = desktop_page
    page.locator("#tab-codebase").click(no_wait_after=True)
    page.wait_for_timeout(200)

    # Click the visualizer level button if not already selected
    pill = page.locator(f"#codebase-level-selector .level-pill[data-level='{level_id}']")
    if "active" not in (pill.get_attribute("class") or ""):
        pill.click(no_wait_after=True)
    wait_for_scene_ready(page)

    # If granularity selector is visible for this mode, ensure Classes is active
    arch_btn = page.locator("#btn-codebase-arch")
    if arch_btn.is_visible() and "active" not in (arch_btn.get_attribute("class") or ""):
        arch_btn.click(no_wait_after=True)
        wait_for_scene_ready(page)

    assert check_no_horizontal_overflow(page)
    save_screenshot(page, f"viz_{level_id}_classes")


@pytest.mark.parametrize("level_id,name", VISUALIZERS)
def test_visualizer_methods_mode(desktop_page, level_id, name):
    """Test visualizer rendering in Methods detailed mode."""
    page = desktop_page
    page.locator("#tab-codebase").click(no_wait_after=True)
    page.wait_for_timeout(200)

    # Switch to visualizer
    pill = page.locator(f"#codebase-level-selector .level-pill[data-level='{level_id}']")
    if "active" not in (pill.get_attribute("class") or ""):
        pill.click(no_wait_after=True)
    wait_for_scene_ready(page)

    # Switch to Methods granularity if supported
    methods_btn = page.locator("#btn-codebase-methods")
    if methods_btn.is_visible() and "active" not in (methods_btn.get_attribute("class") or ""):
        methods_btn.click(no_wait_after=True)
        wait_for_scene_ready(page)

    assert check_no_horizontal_overflow(page)
    save_screenshot(page, f"viz_{level_id}_methods")
