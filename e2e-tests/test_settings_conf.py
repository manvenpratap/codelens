"""
test_settings_conf.py - Automated tests for .conf configuration import, export, and deployment restore.
"""

import json
import urllib.request
import pytest
from helpers import save_screenshot, check_no_horizontal_overflow


def test_api_config_endpoints():
    """Verify backend REST configuration endpoints."""
    # 1. GET /api/config
    req = urllib.request.Request("http://localhost:7878/api/config")
    with urllib.request.urlopen(req) as response:
        assert response.status == 200
        data = json.loads(response.read().decode("utf-8"))
        assert "port" in data
        assert "theme" in data
        assert "nodeBaseRadius" in data

    # 2. GET /api/config/export
    req_export = urllib.request.Request("http://localhost:7878/api/config/export")
    with urllib.request.urlopen(req_export) as response:
        assert response.status == 200
        text = response.read().decode("utf-8")
        assert "server.port" in text
        assert "graph.nodeBaseRadius" in text
        assert "ui.theme" in text

    # 3. POST /api/config/import
    custom_conf = """
# Custom deployment settings
server.port=7878
ui.theme=light
graph.nodeBaseRadius=18
graph.repulsion=420
review.cyclomaticComplexityThreshold=20
scan.excludePatterns=build, target, dist
""".strip().encode("utf-8")

    req_import = urllib.request.Request(
        "http://localhost:7878/api/config/import",
        data=custom_conf,
        headers={"Content-Type": "text/plain; charset=utf-8"},
        method="POST"
    )
    with urllib.request.urlopen(req_import) as response:
        assert response.status == 200
        imported = json.loads(response.read().decode("utf-8"))
        assert imported["status"] == "ok"
        assert imported["config"]["theme"] == "light"
        assert imported["config"]["nodeBaseRadius"] == 18
        assert imported["config"]["repulsion"] == 420


def test_settings_modal_conf_ui(desktop_page):
    """Verify Settings modal Deployment Configuration (.conf) UI buttons and interactions."""
    page = desktop_page
    page.goto("http://localhost:7878")
    page.wait_for_load_state("domcontentloaded")
    page.wait_for_timeout(300)

    # Open Settings Modal
    page.locator("#settings-btn").click(no_wait_after=True)
    page.wait_for_timeout(300)

    modal = page.locator("#settings-modal")
    assert modal.is_visible()

    # Check Export, Import, and Save to Server buttons are present
    export_btn = page.locator("#btn-export-conf")
    assert export_btn.is_visible()

    import_label = page.locator("#btn-import-conf-label")
    assert import_label.is_visible()

    save_btn = page.locator("#btn-save-server-conf")
    assert save_btn.is_visible()

    # Click Save to Server
    save_btn.click(no_wait_after=True)
    page.wait_for_timeout(400)

    status = page.locator("#deployment-config-status-text")
    assert "saved" in status.inner_text().lower() or "active" in status.inner_text().lower() or "codelens.conf" in status.inner_text().lower()

    save_screenshot(page, "settings_conf_modal")

    # Close modal
    page.locator("#settings-modal-close").click(no_wait_after=True)
    page.wait_for_timeout(200)
    assert not modal.is_visible()
