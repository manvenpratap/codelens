"""
conftest.py - Pytest fixtures and browser lifecycle management for CodeLens E2E tests.
"""

import pytest
from playwright.sync_api import sync_playwright, Browser, Page
from helpers import BASE_URL, wait_for_scene_ready


@pytest.fixture(scope="session")
def browser_instance():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        yield browser
        browser.close()


@pytest.fixture(scope="function")
def desktop_page(browser_instance: Browser) -> Page:
    """Desktop 1920x1080 viewport"""
    context = browser_instance.new_context(viewport={"width": 1920, "height": 1080})
    page = context.new_page()
    page.goto(BASE_URL)
    page.wait_for_load_state("domcontentloaded")
    page.wait_for_timeout(300)
    wait_for_scene_ready(page)
    yield page
    context.close()


@pytest.fixture(scope="function")
def laptop_page(browser_instance: Browser) -> Page:
    """Laptop 1440x900 viewport"""
    context = browser_instance.new_context(viewport={"width": 1440, "height": 900})
    page = context.new_page()
    page.goto(BASE_URL)
    page.wait_for_load_state("domcontentloaded")
    page.wait_for_timeout(300)
    wait_for_scene_ready(page)
    yield page
    context.close()


@pytest.fixture(scope="function")
def compact_page(browser_instance: Browser) -> Page:
    """Compact Desktop / Small Screen 1280x800 viewport"""
    context = browser_instance.new_context(viewport={"width": 1280, "height": 800})
    page = context.new_page()
    page.goto(BASE_URL)
    page.wait_for_load_state("domcontentloaded")
    page.wait_for_timeout(300)
    wait_for_scene_ready(page)
    yield page
    context.close()
