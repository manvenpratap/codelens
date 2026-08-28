"""
test_navigation.py - Regression tests for primary navigation tabs and viewport responsiveness.
"""

import pytest
from helpers import check_no_horizontal_overflow, save_screenshot, wait_for_scene_ready


def test_primary_tab_navigation(desktop_page):
    """Verify switching between all top-level granular workspace tabs and launching Macro Studio."""
    page = desktop_page

    granular_tabs = [
        ("tab-graph", "graph-view"),
        ("tab-knowledge", "knowledge-view"),
        ("tab-review", "review-view"),
        ("tab-git", "git-view"),
        ("tab-source", "source-view"),
    ]

    for tab_id, panel_id in granular_tabs:
        btn = page.locator(f"#{tab_id}")
        btn.click(no_wait_after=True)
        page.wait_for_timeout(250)

        # Tab button must be active and aria-selected
        assert "active" in (btn.get_attribute("class") or "")
        assert btn.get_attribute("aria-selected") == "true"

        # Corresponding tab panel must be visible and active
        panel = page.locator(f"#{panel_id}")
        assert "active" in (panel.get_attribute("class") or "")
        assert panel.is_visible()

        # Check no horizontal scrolling overflow
        assert check_no_horizontal_overflow(page), f"Horizontal overflow detected on tab {tab_id}"

        # Capture visual screenshot
        save_screenshot(page, f"nav_{tab_id}")

    # Launch Macro Studio section
    studio_btn = page.locator("#btn-open-macro-studio")
    assert studio_btn.is_visible()
    studio_btn.click(no_wait_after=True)
    wait_for_scene_ready(page)

    codebase_panel = page.locator("#codebase-view")
    assert "active" in (codebase_panel.get_attribute("class") or "")
    assert codebase_panel.is_visible()
    assert check_no_horizontal_overflow(page)
    save_screenshot(page, "nav_macro_studio")

    # Return back to granular workspace
    back_btn = page.locator("#btn-studio-back")
    assert back_btn.is_visible()
    back_btn.click(no_wait_after=True)
    page.wait_for_timeout(250)

    # Must return to previous granular view
    assert page.locator("#source-view").is_visible()
    save_screenshot(page, "nav_return_to_workspace")


def test_responsive_viewports_navigation(laptop_page, compact_page):
    """Verify tabs and header responsive layouts on laptop and compact screens."""
    for page, label in [(laptop_page, "laptop_1440"), (compact_page, "compact_1280")]:
        page.locator("#btn-open-macro-studio").click(no_wait_after=True)
        wait_for_scene_ready(page)
        assert check_no_horizontal_overflow(page)
        save_screenshot(page, f"responsive_codebase_{label}")

        page.locator("#btn-studio-back").click(no_wait_after=True)
        page.wait_for_timeout(200)

        page.locator("#tab-knowledge").click(no_wait_after=True)
        page.wait_for_timeout(200)
        assert check_no_horizontal_overflow(page)
        save_screenshot(page, f"responsive_kb_{label}")
