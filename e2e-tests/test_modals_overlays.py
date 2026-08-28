"""
test_modals_overlays.py - Regression tests for Settings, Help, and Export modal dialogs.
"""

import pytest
from helpers import save_screenshot, check_no_horizontal_overflow


def test_settings_modal_flow(desktop_page):
    """Test opening Settings modal, inspecting rules, and closing."""
    page = desktop_page

    # Open Settings
    page.locator("#settings-btn").click(no_wait_after=True)
    page.wait_for_timeout(300)

    modal = page.locator("#settings-modal")
    assert modal.is_visible()
    assert modal.get_attribute("aria-hidden") == "false" or not modal.get_attribute("aria-hidden")

    # Verify Archetype Rules section is present
    rules_table = page.locator("#archetype-rules-list")
    assert rules_table.is_visible()

    save_screenshot(page, "modal_settings_open")

    # Close via close button
    page.locator("#settings-modal-close").click(no_wait_after=True)
    page.wait_for_timeout(300)
    assert not modal.is_visible() or modal.get_attribute("aria-hidden") == "true"


def test_help_guide_modal_flow(desktop_page):
    """Test opening Help Guide modal, inspecting shortcuts & archetypes, and closing."""
    page = desktop_page

    # Open Help Guide
    page.locator("#help-btn").click(no_wait_after=True)
    page.wait_for_timeout(300)

    modal = page.locator("#help-modal")
    assert modal.is_visible()

    save_screenshot(page, "modal_help_open")

    # Close via Escape key
    page.keyboard.press("Escape")
    page.wait_for_timeout(300)
    assert not modal.is_visible() or modal.get_attribute("aria-hidden") == "true"


def test_export_hub_modal_flow(desktop_page):
    """Test opening Export Hub modal and verifying export format cards."""
    page = desktop_page

    # Open Export Hub
    page.locator("#export-btn").click(no_wait_after=True)
    page.wait_for_timeout(300)

    modal = page.locator("#export-modal")
    assert modal.is_visible()

    # Check for all export cards (Architecture, Review, Metrics, Standalone HTML)
    cards = page.locator(".export-type-card")
    assert cards.count() >= 4

    save_screenshot(page, "modal_export_hub_open")

    # Close via close button
    page.locator("#export-modal-close").click(no_wait_after=True)
    page.wait_for_timeout(300)
    assert not modal.is_visible() or modal.get_attribute("aria-hidden") == "true"
