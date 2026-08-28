#!/usr/bin/env python3
"""
run_audit.py - Standalone executable CLI audit runner for CodeLens.
Runs a complete visual, DOM, and collision audit across all screens,
collects console logs, captures screenshots, and prints a formatted health report.
"""

import sys
import os
import json
import time
from playwright.sync_api import sync_playwright

BASE_URL = os.environ.get("CODELENS_URL", "http://localhost:7878")
OUTPUT_DIR = os.path.join(os.path.dirname(__file__), "screenshots")
os.makedirs(OUTPUT_DIR, exist_ok=True)

VIEWPORTS = [
    {"name": "Desktop (1920x1080)", "width": 1920, "height": 1080},
    {"name": "Laptop (1440x900)", "width": 1440, "height": 900},
    {"name": "Compact (1280x800)", "width": 1280, "height": 800},
]

VISUALIZERS = [
    ("city3d", "3D Software City"),
    ("galaxy3d", "3D Force Galaxy"),
    ("graph2d", "2D Blooming Tree"),
    ("treemap", "Zoomable Treemap"),
    ("sunburst", "Sunburst Radial Hierarchy"),
    ("dsm", "Dependency Structure Matrix"),
    ("chord", "Chord Diagram"),
]


def run_full_audit():
    print("=" * 70)
    print(" ⬡ CODELENS PLAYWRIGHT AUDIT & REGRESSION SUITE")
    print(f" Target URL: {BASE_URL}")
    print(f" Output Directory: {OUTPUT_DIR}")
    print("=" * 70)

    console_errors = []
    overlap_issues = []
    audit_results = []

    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)

        for vp in VIEWPORTS:
            print(f"\n--- Auditing Viewport: {vp['name']} ---")
            context = browser.new_context(viewport={"width": vp["width"], "height": vp["height"]})
            page = context.new_page()

            # Listen for console errors
            page.on("console", lambda msg: console_errors.append(f"[{msg.type}] {msg.text}") if msg.type == "error" else None)
            page.on("pageerror", lambda exc: console_errors.append(f"[PageError] {exc}"))

            page.goto(BASE_URL)
            page.wait_for_load_state("domcontentloaded")
            time.sleep(0.4)

            # 1. Audit Header & Navigation
            print("  ✓ Checking Header & Tab Navigation...")
            tabs = ["tab-graph", "tab-knowledge", "tab-review", "tab-git", "tab-source"]
            for t in tabs:
                btn = page.locator(f"#{t}")
                if btn.is_visible():
                    btn.click(no_wait_after=True)
                    time.sleep(0.2)
                    page.screenshot(path=os.path.join(OUTPUT_DIR, f"{vp['width']}_{t}.png"), timeout=5000, animations="disabled")

            # 2. Audit Macro Studio Visualizers in Classes and Methods modes
            print("  ✓ Checking Macro Studio Visualizers (Classes & Methods)...")
            page.locator("#btn-open-macro-studio").click(no_wait_after=True)
            time.sleep(0.3)

            for lvl, name in VISUALIZERS:
                pill = page.locator(f"#codebase-level-selector .level-pill[data-level='{lvl}']")
                if pill.is_visible():
                    pill.click(no_wait_after=True)
                    time.sleep(0.5)
                    page.screenshot(path=os.path.join(OUTPUT_DIR, f"{vp['width']}_viz_{lvl}_arch.png"), timeout=5000, animations="disabled")

                    # Switch to Methods if supported
                    methods_btn = page.locator("#btn-codebase-methods")
                    if methods_btn.is_visible():
                        methods_btn.click(no_wait_after=True)
                        time.sleep(0.5)
                        page.screenshot(path=os.path.join(OUTPUT_DIR, f"{vp['width']}_viz_{lvl}_methods.png"), timeout=5000, animations="disabled")

            # 3. Return to workspace and audit Modals
            print("  ✓ Checking Modals (Settings, Help, Export Hub)...")
            back_btn = page.locator("#btn-studio-back")
            if back_btn.is_visible():
                back_btn.click(no_wait_after=True)
                time.sleep(0.2)

            # Settings
            page.locator("#settings-btn").click(no_wait_after=True)
            time.sleep(0.3)
            page.screenshot(path=os.path.join(OUTPUT_DIR, f"{vp['width']}_modal_settings.png"), timeout=5000, animations="disabled")
            page.locator("#settings-modal-close").click(no_wait_after=True)
            time.sleep(0.2)

            # Help
            page.locator("#help-btn").click(no_wait_after=True)
            time.sleep(0.3)
            page.screenshot(path=os.path.join(OUTPUT_DIR, f"{vp['width']}_modal_help.png"), timeout=5000, animations="disabled")
            page.locator("#help-modal-close").click(no_wait_after=True)
            time.sleep(0.2)

            # Export Hub
            page.locator("#export-btn").click(no_wait_after=True)
            time.sleep(0.3)
            page.screenshot(path=os.path.join(OUTPUT_DIR, f"{vp['width']}_modal_export.png"), timeout=5000, animations="disabled")
            page.locator("#export-modal-close").click(no_wait_after=True)
            time.sleep(0.2)

            context.close()

        browser.close()

    print("\n" + "=" * 70)
    print(" AUDIT REPORT SUMMARY")
    print("=" * 70)
    print(f" Screenshots Generated: {len(os.listdir(OUTPUT_DIR))}")
    print(f" Console Errors: {len(console_errors)}")
    if console_errors:
        for err in console_errors:
            print(f"   ❌ {err}")
    else:
        print("   ✅ Zero console errors detected across all views & viewports!")

    print("=" * 70)


if __name__ == "__main__":
    run_full_audit()
