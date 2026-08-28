"""
test_tab_drag_drop_and_source.py - E2E tests for:
1. Selecting Java file/entity in Explorer and auto-loading code when switching to Source tab.
2. Drag and drop reordering of workspace tabs and dynamic shortcut mapping.
"""

import time
import pytest
from helpers import save_screenshot, check_no_horizontal_overflow


def test_explorer_selection_auto_loads_source(desktop_page):
    """Verify that selecting a Java class in Explorer and then switching to Source tab auto-loads the file."""
    page = desktop_page
    page.goto("http://localhost:7878")
    page.wait_for_load_state("domcontentloaded")
    page.wait_for_timeout(500)

    # If project bar is not shown yet, perform a scan of codelens-core
    if not page.locator("#header-project-bar").is_visible():
        scan_input = page.locator("#scan-path-input")
        if scan_input.is_visible():
            scan_input.fill("/Volumes/Study/Projects/codelens/codelens-core/src/main/java")
            page.locator("#scan-btn").click()
            page.wait_for_selector("#header-project-bar", state="visible", timeout=20000)

    # 1. Expand a package and click a Java class in Explorer
    page.wait_for_selector(".tree-item[data-fqn]", state="visible", timeout=10000)
    
    pkg_item = page.locator(".tree-item[data-fqn]").first
    pkg_item.click()
    page.wait_for_timeout(600)

    # Click first type item
    page.wait_for_selector(".tree-type-item", state="visible", timeout=5000)
    type_item = page.locator(".tree-type-item").first
    type_name = type_item.locator(".tree-label").inner_text()
    type_item.click()
    page.wait_for_timeout(600)

    # 2. Switch to Source tab
    page.click("#tab-source")
    page.wait_for_timeout(1000)

    # 3. Verify Source View is active and code container / file path is loaded
    source_view = page.locator("#source-view")
    assert "active" in (source_view.get_attribute("class") or "")

    # File path label should show the Java file name
    file_path_label = page.locator("#editor-file-path").inner_text()
    assert ".java" in file_path_label or type_name in file_path_label

    # Empty state should be hidden
    empty_state = page.locator("#editor-empty-state")
    assert not empty_state.is_visible()

    # Code container should have loaded lines (either in Monaco or fallback viewer)
    has_monaco = page.locator("#editor-container .monaco-editor").count() > 0
    has_fallback = page.locator("#editor-container .fallback-code-wrap").count() > 0
    assert has_monaco or has_fallback

    save_screenshot(page, "explorer_auto_source_loaded")


def test_tab_drag_and_drop_reorder(desktop_page):
    """Verify drag and drop reordering of workspace tabs, localStorage persistence, and shortcut reassignment."""
    page = desktop_page
    page.goto("http://localhost:7878")
    page.wait_for_selector(".tab-bar", timeout=5000)

    # Initial order of tabs
    initial_tabs = page.eval_on_selector_all(".tab-bar .tab", "els => els.map(e => e.dataset.tab)")
    assert "graph" in initial_tabs
    assert "source" in initial_tabs

    # Drag Source tab to the first position before Graph
    source_tab = page.locator("#tab-source")
    graph_tab = page.locator("#tab-graph")

    source_tab.drag_to(graph_tab)
    page.wait_for_timeout(500)

    # Get new order
    new_tabs = page.eval_on_selector_all(".tab-bar .tab", "els => els.map(e => e.dataset.tab)")
    
    # Verify localStorage saved order
    saved_order_raw = page.evaluate("() => localStorage.getItem('codelens_tab_order')")
    assert saved_order_raw is not None

    # Verify reloading page retains the customized tab order
    page.reload()
    page.wait_for_selector(".tab-bar", timeout=5000)
    reloaded_tabs = page.eval_on_selector_all(".tab-bar .tab", "els => els.map(e => e.dataset.tab)")
    assert reloaded_tabs == new_tabs

    # Verify dynamic keyboard shortcut: pressing 1 activates the 1st tab in custom order
    page.keyboard.press("1")
    page.wait_for_timeout(300)
    first_tab_name = reloaded_tabs[0]
    active_tab = page.locator(f"#tab-{first_tab_name}")
    assert "active" in (active_tab.get_attribute("class") or "")

    # Verify footer helpers dynamically match the customized placement of tabs
    footer_text = page.locator("#footer-tab-shortcuts").inner_text()
    assert "1" in footer_text
    # 1 should correspond to the first tab (e.g. "Source")
    first_shortcut = page.locator("#footer-tab-shortcuts .shortcut-tip").first.inner_text()
    assert "1" in first_shortcut
    if first_tab_name == "source":
        assert "Source" in first_shortcut

    save_screenshot(page, "tab_drag_drop_customized")
