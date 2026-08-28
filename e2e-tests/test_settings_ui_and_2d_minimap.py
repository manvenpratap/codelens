import pytest

def test_settings_modal_enhanced_ui_and_width(desktop_page):
    page = desktop_page
    page.goto("http://localhost:7878")
    page.wait_for_load_state("domcontentloaded")
    page.wait_for_timeout(500)

    # Open Settings modal
    settings_btn = page.locator("#settings-btn")
    settings_btn.click()
    page.wait_for_selector("#settings-modal", state="visible")

    # Verify settings dialog width is enhanced (>= 900px on desktop)
    modal_dialog = page.locator(".settings-dialog")
    box = modal_dialog.bounding_box()
    assert box is not None
    assert box["width"] >= 900, f"Settings dialog width {box['width']} should be >= 900px"

    # Verify settings-grid-row exists for 2-column readability
    grid_rows = page.locator(".settings-grid-row")
    assert grid_rows.count() >= 3, "Expected at least 3 settings grid rows for multi-column layout"

    # Verify all icons in settings are SVGs
    icons = page.locator("#settings-modal svg.svg-icon")
    assert icons.count() >= 5, "Expected SVG icons inside settings modal"

    # Close settings modal
    close_btn = page.locator("#settings-modal-close")
    close_btn.click()
    page.wait_for_selector("#settings-modal", state="hidden")

    # Verify footer buttons use SVG icons
    footer_left_icon = page.locator("#footer-toggle-left svg.svg-icon")
    footer_right_icon = page.locator("#footer-toggle-right svg.svg-icon")
    assert footer_left_icon.count() == 1
    assert footer_right_icon.count() == 1


def test_2d_codebase_graph_minimap_and_bloom(desktop_page):
    page = desktop_page
    page.goto("http://localhost:7878")
    page.wait_for_load_state("domcontentloaded")
    page.wait_for_timeout(500)

    # Switch to Codebase Macro studio tab
    codebase_tab = page.locator('.workspace-tab[data-tab="codebase"]')
    if codebase_tab.count() > 0:
        codebase_tab.click()
        page.wait_for_timeout(600)

        # Switch to 2D Graph (Blooming Tree) level pill
        graph2d_pill = page.locator('#codebase-level-selector .level-pill[data-level="graph2d"]')
        if graph2d_pill.count() > 0:
            graph2d_pill.click()
            page.wait_for_timeout(800)

            # Verify 2D codebase minimap wrap is in DOM
            cb_minimap_wrap = page.locator("#codebase-minimap-wrap")
            assert cb_minimap_wrap.count() == 1, "Expected #codebase-minimap-wrap in DOM"

            # Verify canvas has rendered nodes
            cb_canvas = page.locator("#codebase-canvas-wrap canvas")
            assert cb_canvas.count() >= 1, "Expected rendered canvas in 2D codebase view"
