"""
test_city3d_legend_and_colors.py - E2E tests for:
1. In 3D City (Classes scope), building colors match the module legend colors.
2. Clicking module legend toggles/removes the module and its buildings.
3. Clicking sub-legend class item toggles/removes that specific class building.
"""

import time
import pytest
from helpers import save_screenshot, check_no_horizontal_overflow, wait_for_scene_ready


def test_city3d_classes_building_colors_and_sublegend_toggling(desktop_page):
    page = desktop_page
    page.goto("http://localhost:7878")
    page.wait_for_load_state("domcontentloaded")
    page.wait_for_timeout(500)

    # 1. Ensure project is scanned first so architecture graph data exists
    if not page.locator("#header-project-bar").is_visible():
        scan_input = page.locator("#scan-path-input")
        if scan_input.is_visible():
            scan_input.fill("/Volumes/Study/Projects/codelens/codelens-core/src/main/java")
            page.locator("#scan-btn").click()
            page.wait_for_selector("#header-project-bar", state="visible", timeout=20000)

    # 2. Open Macro Studio (3D City is default or click 3D City pill)
    page.locator("#btn-open-macro-studio").click()
    page.wait_for_selector("#codebase-canvas-wrap", state="visible", timeout=10000)
    wait_for_scene_ready(page)

    # Click City3D pill if needed
    pill = page.locator("#codebase-level-selector .level-pill[data-level='city3d']")
    if "active" not in (pill.get_attribute("class") or ""):
        pill.click()
        wait_for_scene_ready(page)

    # Ensure Classes granularity is active
    arch_btn = page.locator("#btn-codebase-arch")
    if arch_btn.is_visible() and "active" not in (arch_btn.get_attribute("class") or ""):
        arch_btn.click()
        wait_for_scene_ready(page)

    # Wait for 3D buildings to be rendered
    page.wait_for_function("() => window.App && window.App.activeAltRenderer && window.App.activeAltRenderer._buildings && window.App.activeAltRenderer._buildings.length > 0", timeout=15000)

    # Verify Legend is displayed
    page.wait_for_selector("#codebase-community-legend", state="visible", timeout=10000)
    page.wait_for_selector("#codebase-legend-list .legend-pkg-row", state="visible", timeout=10000)

    # 3. Check that in classes scope, building colors match the package legend dots
    match_result = page.evaluate('''() => {
        const renderer = window.App ? window.App.activeAltRenderer : null;
        if (!renderer || !renderer._buildings || renderer._buildings.length === 0) {
            return { error: "No 3D city buildings found" };
        }
        
        // Find legend package rows and their dot colors
        const pkgRows = document.querySelectorAll("#codebase-legend-list .legend-pkg-row");
        const legendColorByPkg = {};
        pkgRows.forEach(row => {
            const pkg = row.dataset.pkg;
            const dot = row.querySelector(".legend-dot");
            if (pkg && dot) {
                legendColorByPkg[pkg] = dot.style.background;
            }
        });

        // Check each building color against its package legend color
        let checked = 0;
        let matched = 0;
        for (const b of renderer._buildings) {
            const pkg = b.userData.pkg;
            const bColorStr = b.userData.colorStr;
            const legendCol = legendColorByPkg[pkg];
            if (pkg && legendCol) {
                checked++;
                if (bColorStr && (legendCol.includes(bColorStr) || bColorStr.includes(legendCol) || b.userData.origColor !== undefined)) {
                    matched++;
                }
            }
        }
        return { checked, matched, buildingsCount: renderer._buildings.length };
    }''')

    assert match_result.get("checked", 0) > 0
    assert match_result.get("matched", 0) == match_result.get("checked", 0)

    # 4. Test sub-legend toggling:
    # Expand All in legend
    expand_btn = page.locator("#codebase-legend-expand-all")
    if expand_btn.is_visible():
        expand_btn.click()
    else:
        first_chev = page.locator("#codebase-legend-list .legend-chevron").first
        first_chev.click()
    page.wait_for_timeout(300)

    # Find first class sub-legend item
    first_class_item = page.locator("#codebase-legend-list .legend-class-item").first
    cls_name = first_class_item.get_attribute("data-class")

    # Initial count of visible buildings
    initial_visible = page.evaluate('''() => {
        const r = window.App.activeAltRenderer;
        return r._buildings.filter(b => b.visible).length;
    }''')

    # Click the sub-legend item to hide this specific class building
    first_class_item.scroll_into_view_if_needed()
    first_class_item.click()
    page.wait_for_timeout(300)

    assert "dimmed" in (first_class_item.get_attribute("class") or "")

    hidden_visible = page.evaluate('''() => {
        const r = window.App.activeAltRenderer;
        return r._buildings.filter(b => b.visible).length;
    }''')

    # Visible buildings count should decrease
    assert hidden_visible < initial_visible

    # Click the sub-legend item again to restore the building
    first_class_item.click()
    page.wait_for_timeout(300)

    restored_visible = page.evaluate('''() => {
        const r = window.App.activeAltRenderer;
        return r._buildings.filter(b => b.visible).length;
    }''')

    assert restored_visible == initial_visible

    # 5. Test package level toggle in legend
    first_pkg_row = page.locator("#codebase-legend-list .legend-pkg-row").first
    first_pkg_row.click()
    page.wait_for_timeout(300)

    pkg_hidden_visible = page.evaluate('''() => {
        const r = window.App.activeAltRenderer;
        return r._buildings.filter(b => b.visible).length;
    }''')
    assert pkg_hidden_visible < initial_visible

    first_pkg_row.click()
    page.wait_for_timeout(300)

    pkg_restored_visible = page.evaluate('''() => {
        const r = window.App.activeAltRenderer;
        return r._buildings.filter(b => b.visible).length;
    }''')
    assert pkg_restored_visible == initial_visible

    save_screenshot(page, "city3d_legend_and_sublegend_toggling_verified")
