"""
test_layout_overlaps.py - Automated bounding-box collision detection to verify 0 UI element overlaps
across all screens, viewports, and visualizer modes in CodeLens.
"""

import pytest
from helpers import check_element_overlaps, wait_for_scene_ready, save_screenshot


VIEWPORTS = [
    ({"width": 1920, "height": 1080}, "desktop_1920x1080"),
    ({"width": 1440, "height": 900}, "laptop_1440x900"),
    ({"width": 1280, "height": 800}, "compact_1280x800"),
]


@pytest.mark.parametrize("viewport,vp_name", VIEWPORTS)
def test_header_and_navigation_no_overlaps(browser_instance, viewport, vp_name):
    """Verify header components and tab bar elements never overlap each other."""
    context = browser_instance.new_context(viewport=viewport)
    page = context.new_page()
    page.goto("http://localhost:7878")
    page.wait_for_load_state("domcontentloaded")
    page.wait_for_timeout(300)

    header_elements = [
        ".logo",
        "#btn-open-project",
        "#header-scan-bar",
        ".stats-row",
        "#header-actions",
    ]

    overlaps = check_element_overlaps(page, header_elements)
    save_screenshot(page, f"overlap_header_{vp_name}")
    assert len(overlaps) == 0, f"Detected header overlaps on {vp_name}: {overlaps}"

    nav_tabs = [
        "#tab-graph",
        "#tab-knowledge",
        "#tab-codebase",
        "#tab-review",
        "#tab-git",
        "#tab-source",
    ]
    tab_overlaps = check_element_overlaps(page, nav_tabs)
    assert len(tab_overlaps) == 0, f"Detected tab bar overlaps on {vp_name}: {tab_overlaps}"
    context.close()


@pytest.mark.parametrize("viewport,vp_name", VIEWPORTS)
def test_codebase_viz_floating_hud_no_overlaps(browser_instance, viewport, vp_name):
    """Verify Codebase Viz floating HUD controls (top bar, bottom toolbar, legend, inspector) don't collide."""
    context = browser_instance.new_context(viewport=viewport)
    page = context.new_page()
    page.goto("http://localhost:7878")
    page.wait_for_load_state("domcontentloaded")
    page.wait_for_timeout(300)

    page.locator("#tab-codebase").click(no_wait_after=True)
    wait_for_scene_ready(page)

    # Test City 3D
    floating_elements = [
        "#codebase-hud-bar",
        "#codebase-canvas-toolbar",
        "#codebase-community-legend",
        "#codebase-alt-inspector",
    ]

    overlaps = check_element_overlaps(page, floating_elements)
    save_screenshot(page, f"overlap_codebase_city3d_{vp_name}")
    assert len(overlaps) == 0, f"Detected Codebase Viz HUD overlaps on {vp_name}: {overlaps}"

    # Test 2D Graph view in Codebase tab
    page.locator("#codebase-level-selector .level-pill[data-level='graph2d']").click(no_wait_after=True)
    wait_for_scene_ready(page)
    overlaps_2d = check_element_overlaps(page, floating_elements)
    save_screenshot(page, f"overlap_codebase_graph2d_{vp_name}")
    assert len(overlaps_2d) == 0, f"Detected Codebase 2D Graph HUD overlaps on {vp_name}: {overlaps_2d}"

    context.close()


@pytest.mark.parametrize("viewport,vp_name", VIEWPORTS)
def test_graph_tab_hud_no_overlaps(browser_instance, viewport, vp_name):
    """Verify 2D Graph tab HUD actions, camera controls, minimap, and inspector don't collide."""
    context = browser_instance.new_context(viewport=viewport)
    page = context.new_page()
    page.goto("http://localhost:7878")
    page.wait_for_load_state("domcontentloaded")
    page.wait_for_timeout(300)

    page.locator("#tab-graph").click(no_wait_after=True)
    wait_for_scene_ready(page)

    floating_elements = [
        ".graph-hud-actions",
        ".graph-camera-controls",
        ".graph-depth-pills",
        "#graph-minimap-wrap",
        "#graph-community-legend",
        "#inspector-sidebar",
    ]

    overlaps = check_element_overlaps(page, floating_elements)
    save_screenshot(page, f"overlap_graph_tab_{vp_name}")
    assert len(overlaps) == 0, f"Detected Graph tab HUD overlaps on {vp_name}: {overlaps}"
    context.close()


def test_modal_dialogs_no_overlaps(desktop_page):
    """Verify inputs, buttons, and sections inside modals have clean non-colliding layouts."""
    page = desktop_page

    # Open Settings Modal
    page.locator("#settings-btn").click()
    page.wait_for_timeout(300)

    form_elements = [
        "#rule-form-pattern",
        "#rule-form-label",
        "#rule-form-badge",
        "#rule-form-category",
        "#rule-form-color",
        "#btn-save-archetype-rule",
        "#btn-cancel-archetype-rule",
    ]

    overlaps = check_element_overlaps(page, form_elements)
    save_screenshot(page, "overlap_modal_settings_form")
    assert len(overlaps) == 0, f"Detected overlaps in Settings rule form: {overlaps}"

    page.locator("#settings-modal-close").click()
    page.wait_for_timeout(200)
