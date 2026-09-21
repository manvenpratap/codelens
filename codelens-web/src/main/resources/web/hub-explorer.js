/**
 * hub-explorer.js - Hub Node Radial Explorer for CodeLens
 *
 * Provides a dedicated, high-performance radial visualization for "hub nodes"
 * (classes or methods with hundreds of callers or callees, e.g. AuditTrailService with 496 callers).
 *
 * Features:
 * - Aggregates hundreds of connections into clean, color-coded package arcs
 * - Bundled Bézier curves from center to package arcs with thickness proportional to call count
 * - Interactive drill-down: click any package arc to expand into a sub-ring of individual methods
 * - Side drill-down drawer with searchable member list, class-based accordion, and call metrics
 * - Automatic canvas centering shift when drawer opens so diagrams are never obscured
 * - De-cluttered leaf nodes: clean dots when crowded, text labels only on hover or when <= 8
 * - Bidirectional hover linking between drawer items and radial leaf nodes
 * - Dedicated camera controls (Zoom In, Zoom Out, Fit, Reset) + keyboard shortcuts (Esc, /, +, -, F, R)
 * - Full theme support: Midnight Obsidian (dark), Swiss Minimalist (swiss), and Pure Daylight (light)
 */

(function () {
  'use strict';

  class HubExplorer {
    constructor(overlayEl) {
      this._overlay = overlayEl || document.getElementById('hub-explorer-overlay');
      this._canvas = document.getElementById('hub-explorer-canvas');
      this._ctx = this._canvas ? this._canvas.getContext('2d') : null;
      this._data = null;
      this._currentFqn = null;
      this._direction = 'callers'; // 'callers' | 'callees' | 'both'
      this._filterQuery = '';
      this._drawerFilterQuery = '';
      this._selectedGroup = null; // selected HubExplorerGroup
      this._hoveredItem = null;   // { type: 'center' | 'arc' | 'leaf', data: ... }
      this._tooltip = null;
      this._hiddenHUDs = [];

      // History stack for navigation
      this._history = []; // stack of { fqn, direction }
      this._future = [];  // stack of { fqn, direction } for forward navigation

      // Transform & Camera
      this._zoom = 1.0;
      this._panX = 0;
      this._panY = 0;
      this._isPanning = false;
      this._startPan = { x: 0, y: 0 };
      this._dpr = window.devicePixelRatio || 1;
      this._rafId = null;

      // Layout geometry cache
      this._centerPos = { x: 0, y: 0, r: 42 };
      this._arcLayouts = [];
      this._leafNodes = [];

      this._initDOM();
      this._bindEvents();
    }

    isOpen() {
      return !!(this._overlay && this._overlay.style.display !== 'none' && this._overlay.classList.contains('active'));
    }

    goBack() {
      if (this._history.length > 1) {
        const current = this._history.pop();
        this._future.push(current);
        const prev = this._history[this._history.length - 1];
        this.load(prev.fqn, prev.direction, true);
      } else {
        this.close();
      }
    }

    goForward() {
      if (this._future.length > 0) {
        const next = this._future.pop();
        this._history.push(next);
        this.load(next.fqn, next.direction, true);
      }
    }

    _updateHistoryButtons() {
      if (!this._overlay) return;
      const backBtn = this._overlay.querySelector('#btn-hub-nav-back');
      const fwdBtn = this._overlay.querySelector('#btn-hub-nav-forward');
      if (backBtn) {
        const canGoBack = this._history.length > 1;
        backBtn.disabled = !canGoBack;
        backBtn.classList.toggle('disabled', !canGoBack);
      }
      if (fwdBtn) {
        const canGoFwd = this._future.length > 0;
        fwdBtn.disabled = !canGoFwd;
        fwdBtn.classList.toggle('disabled', !canGoFwd);
      }
    }

    _initDOM() {
      if (!this._overlay) return;

      // Tooltip
      this._tooltip = document.createElement('div');
      this._tooltip.className = 'hub-tooltip';
      this._tooltip.style.display = 'none';
      this._overlay.appendChild(this._tooltip);
    }

    _bindEvents() {
      if (!this._overlay) return;

      // History navigation buttons
      const navBackBtn = this._overlay.querySelector('#btn-hub-nav-back');
      if (navBackBtn) navBackBtn.onclick = () => this.goBack();

      const navFwdBtn = this._overlay.querySelector('#btn-hub-nav-forward');
      if (navFwdBtn) navFwdBtn.onclick = () => this.goForward();

      // Empty state buttons
      const emptyBackBtn = this._overlay.querySelector('#btn-hub-empty-back');
      if (emptyBackBtn) emptyBackBtn.onclick = () => this.goBack();

      const emptyGraphBtn = this._overlay.querySelector('#btn-hub-empty-graph');
      if (emptyGraphBtn) emptyGraphBtn.onclick = () => this.close();

      // Back to 2D Graph button
      const backBtn = this._overlay.querySelector('#btn-hub-back');
      if (backBtn) {
        backBtn.onclick = () => this.close();
      }

      // Switch to raw graph button
      const rawGraphBtn = this._overlay.querySelector('#btn-hub-raw-graph');
      if (rawGraphBtn) {
        rawGraphBtn.onclick = () => {
          this.close();
          if (window.switchTab) window.switchTab('graph');
          if (window.App && this._currentFqn) {
            if (this._direction === 'callees') {
              window.App.loadCalleesGraph?.(this._currentFqn);
            } else {
              window.App.loadCallersGraph?.(this._currentFqn);
            }
          }
        };
      }

      // Direction tabs
      const dirTabs = this._overlay.querySelectorAll('.hub-dir-tab');
      dirTabs.forEach(tab => {
        tab.onclick = () => {
          if (tab.classList.contains('disabled')) return;
          dirTabs.forEach(t => t.classList.remove('active'));
          tab.classList.add('active');
          this.setDirection(tab.dataset.dir || 'callers');
        };
      });

      // Global Package search input
      const searchInput = this._overlay.querySelector('#hub-search-input');
      if (searchInput) {
        searchInput.oninput = (e) => {
          this._filterQuery = (e.target.value || '').trim().toLowerCase();
          this._recomputeLayout();
          this._renderDrawer();
          this.requestRender();
        };
      }

      // Drawer toolbar filter input
      const drawerFilter = this._overlay.querySelector('#hub-drawer-filter');
      if (drawerFilter) {
        drawerFilter.oninput = (e) => {
          this._drawerFilterQuery = (e.target.value || '').trim().toLowerCase();
          this._renderDrawer();
        };
      }

      // Drawer close button
      const drawerClose = this._overlay.querySelector('#btn-hub-drawer-close');
      if (drawerClose) {
        drawerClose.onclick = () => {
          this._selectedGroup = null;
          this._drawerFilterQuery = '';
          const df = this._overlay.querySelector('#hub-drawer-filter');
          if (df) df.value = '';
          this._renderDrawer();
          this._recomputeLayout();
          this.requestRender();
        };
      }

      // Prevent tooltip bleed when hovering over drawer
      const drawer = this._overlay.querySelector('#hub-drawer');
      if (drawer) {
        drawer.addEventListener('mouseenter', () => {
          if (this._tooltip) this._tooltip.style.display = 'none';
        });
      }

      // Zoom Controls
      const btnZoomIn = this._overlay.querySelector('#btn-hub-zoom-in');
      if (btnZoomIn) btnZoomIn.onclick = () => this._zoomIn();

      const btnZoomOut = this._overlay.querySelector('#btn-hub-zoom-out');
      if (btnZoomOut) btnZoomOut.onclick = () => this._zoomOut();

      const btnFit = this._overlay.querySelector('#btn-hub-fit');
      if (btnFit) btnFit.onclick = () => this._fitView();

      const btnReset = this._overlay.querySelector('#btn-hub-reset');
      if (btnReset) btnReset.onclick = () => this.resetView();

      // Keyboard navigation
      window.addEventListener('keydown', (e) => {
        if (!this._overlay || this._overlay.style.display === 'none') return;
        const tag = (e.target && e.target.tagName) || '';
        const isInput = tag === 'INPUT' || tag === 'TEXTAREA';

        if (e.key === 'Escape') {
          if (this._selectedGroup) {
            this._selectedGroup = null;
            this._drawerFilterQuery = '';
            const df = this._overlay.querySelector('#hub-drawer-filter');
            if (df) df.value = '';
            this._renderDrawer();
            this._recomputeLayout();
            this.requestRender();
          } else {
            this.close();
          }
          return;
        }

        // History shortcuts
        if (e.key === '[' || (e.altKey && e.key === 'ArrowLeft')) {
          e.preventDefault();
          this.goBack();
          return;
        }
        if (e.key === ']' || (e.altKey && e.key === 'ArrowRight')) {
          e.preventDefault();
          this.goForward();
          return;
        }
        if (e.key === 'Backspace' && !isInput) {
          e.preventDefault();
          this.goBack();
          return;
        }

        if (isInput) return; // Don't intercept shortcuts when user is typing

        if (e.key === '/') {
          e.preventDefault();
          const target = this._selectedGroup ?
            this._overlay.querySelector('#hub-drawer-filter') :
            this._overlay.querySelector('#hub-search-input');
          if (target) {
            target.focus();
            target.select();
          }
        } else if (e.key === '+' || e.key === '=') {
          e.preventDefault();
          this._zoomIn();
        } else if (e.key === '-' || e.key === '_') {
          e.preventDefault();
          this._zoomOut();
        } else if (e.key === 'f' || e.key === 'F') {
          e.preventDefault();
          this._fitView();
        } else if (e.key === 'r' || e.key === 'R') {
          e.preventDefault();
          this.resetView();
        }
      });

      // Canvas mouse & zoom interactions
      if (this._canvas) {
        this._canvas.addEventListener('mousedown', (e) => this._onMouseDown(e));
        this._canvas.addEventListener('dblclick', (e) => this._onDblClick(e));
        window.addEventListener('mousemove', (e) => this._onMouseMove(e));
        window.addEventListener('mouseup', (e) => this._onMouseUp(e));
        this._canvas.addEventListener('wheel', (e) => this._onWheel(e), { passive: false });
        window.addEventListener('resize', () => this._onResize());
      }
    }

    async load(fqn, direction = 'callers', isHistoryNav = false) {
      if (!fqn) return;

      if (!isHistoryNav) {
        const last = this._history[this._history.length - 1];
        if (!last || last.fqn !== fqn || last.direction !== direction) {
          this._history.push({ fqn, direction });
          this._future = [];
        }
      }
      this._updateHistoryButtons();

      this._currentFqn = fqn;
      this._direction = direction;
      this._selectedGroup = null;
      this._filterQuery = '';
      this._drawerFilterQuery = '';

      const searchInput = this._overlay?.querySelector('#hub-search-input');
      if (searchInput) searchInput.value = '';

      const drawerFilter = this._overlay?.querySelector('#hub-drawer-filter');
      if (drawerFilter) drawerFilter.value = '';

      // Update active tab
      const dirTabs = this._overlay?.querySelectorAll('.hub-dir-tab');
      dirTabs?.forEach(tab => {
        tab.classList.toggle('active', tab.dataset.dir === direction);
      });

      this.show();
      this._showLoading(true);

      try {
        const resp = await fetch(`/api/graph/hub-explorer?fqn=${encodeURIComponent(fqn)}&direction=${direction}`);
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        const data = await resp.json();
        this._data = data;
        this._updateHeader();

        // Check for empty state
        const emptyEl = this._overlay?.querySelector('#hub-empty-state');
        const descEl = this._overlay?.querySelector('#hub-empty-desc');
        const totalCallers = data.totalCallers || 0;
        const totalCallees = data.totalCallees || 0;
        const isDirEmpty = (direction === 'callers' && totalCallers === 0) ||
                           (direction === 'callees' && totalCallees === 0) ||
                           (totalCallers === 0 && totalCallees === 0);

        if (emptyEl) {
          if (isDirEmpty) {
            emptyEl.style.display = 'flex';
            if (descEl) {
              if (totalCallers === 0 && totalCallees === 0) {
                descEl.textContent = 'This method has no recorded upstream callers or downstream callees.';
              } else if (direction === 'callers') {
                descEl.textContent = `No upstream callers found. However, it has ${totalCallees} downstream callees. Try switching to Callees view.`;
              } else {
                descEl.textContent = `No downstream callees found. However, it has ${totalCallers} upstream callers. Try switching to Callers view.`;
              }
            }
          } else {
            emptyEl.style.display = 'none';
          }
        }

        this._recomputeLayout();
        this._renderDrawer();
        this.resetView();
      } catch (err) {
        console.error('[HubExplorer] Failed to load hub data:', err);
        this._showError(err.message);
      } finally {
        this._showLoading(false);
      }
    }

    setDirection(dir) {
      if (this._direction === dir) return;
      this._direction = dir;
      if (this._currentFqn) {
        this.load(this._currentFqn, dir);
      }
    }

    show() {
      if (this._overlay) {
        this._overlay.style.display = 'flex';
        this._overlay.classList.add('active');
      }
      document.body.classList.add('hub-explorer-active');
      this._hideHUDs();
      this._onResize();
    }

    close() {
      if (this._overlay) {
        this._overlay.style.display = 'none';
        this._overlay.classList.remove('active', 'drawer-open');
      }
      document.body.classList.remove('hub-explorer-active');
      if (this._tooltip) this._tooltip.style.display = 'none';
      const emptyEl = this._overlay?.querySelector('#hub-empty-state');
      if (emptyEl) emptyEl.style.display = 'none';
      if (this._rafId) {
        cancelAnimationFrame(this._rafId);
        this._rafId = null;
      }
      this._restoreHUDs();
    }

    resetView() {
      this._zoom = 1.0;
      this._panX = 0;
      this._panY = 0;
      this.requestRender();
    }

    _zoomIn() {
      this._zoom = Math.min(4.0, this._zoom * 1.25);
      this.requestRender();
    }

    _zoomOut() {
      this._zoom = Math.max(0.35, this._zoom / 1.25);
      this.requestRender();
    }

    _fitView() {
      this.resetView();
    }

    _hideHUDs() {
      const ids = ['graph-canvas-toolbar', 'graph-community-legend', 'graph-minimap-wrap', 'graph-perf-hud', 'right-expand-strip', 'left-expand-strip'];
      this._hiddenHUDs = [];
      ids.forEach(id => {
        const el = document.getElementById(id);
        if (el && el.style.display !== 'none') {
          this._hiddenHUDs.push({ el, prev: el.style.display });
          el.style.display = 'none';
        }
      });
      document.body.classList.add('hub-explorer-active');
    }

    _restoreHUDs() {
      document.body.classList.remove('hub-explorer-active');
      if (this._hiddenHUDs && this._hiddenHUDs.length > 0) {
        this._hiddenHUDs.forEach(({ el, prev }) => {
          if (el) el.style.display = prev;
        });
        this._hiddenHUDs = [];
      }
    }

    _showLoading(show) {
      const loader = this._overlay?.querySelector('#hub-loading-spinner');
      if (loader) loader.style.display = show ? 'flex' : 'none';
    }

    _showError(msg) {
      const errEl = this._overlay?.querySelector('#hub-error-msg');
      if (errEl) {
        errEl.textContent = `Failed to load hub data: ${msg}`;
        errEl.style.display = 'block';
      }
    }

    _updateHeader() {
      if (!this._data || !this._overlay) return;
      const center = this._data.centerNode || {};
      const titleEl = this._overlay.querySelector('#hub-title-text');
      const pkgEl = this._overlay.querySelector('#hub-title-pkg');
      const callersBadge = this._overlay.querySelector('#hub-badge-callers');
      const calleesBadge = this._overlay.querySelector('#hub-badge-callees');
      const glyphBadge = this._overlay.querySelector('.hub-type-glyph');

      const isClass = center.type === 'CLASS';
      if (glyphBadge) {
        glyphBadge.textContent = isClass ? 'c' : 'm';
        glyphBadge.classList.toggle('is-class', isClass);
      }

      if (titleEl) {
        titleEl.textContent = center.label || (center.id ? center.id.split('.').pop() : 'Unknown');
        titleEl.title = center.label || center.id || '';
      }
      if (pkgEl) {
        pkgEl.textContent = center.packageFqn || center.className || '';
        pkgEl.title = center.packageFqn || center.className || '';
      }
      if (callersBadge) callersBadge.textContent = `${(this._data.totalCallers || 0).toLocaleString()}`;
      if (calleesBadge) calleesBadge.textContent = `${(this._data.totalCallees || 0).toLocaleString()}`;

      // Disable direction tabs if 0 callers or 0 callees
      const tabCallers = this._overlay.querySelector('.hub-dir-tab[data-dir="callers"]');
      const tabCallees = this._overlay.querySelector('.hub-dir-tab[data-dir="callees"]');
      if (tabCallers) {
        tabCallers.classList.toggle('disabled', (this._data.totalCallers || 0) === 0);
      }
      if (tabCallees) {
        tabCallees.classList.toggle('disabled', (this._data.totalCallees || 0) === 0);
      }
    }

    _getActiveGroups() {
      if (!this._data) return [];
      let groups = [];
      if (this._direction === 'callers') {
        groups = this._data.callerGroups || [];
      } else if (this._direction === 'callees') {
        groups = this._data.calleeGroups || [];
      } else {
        // Both
        groups = [...(this._data.callerGroups || []), ...(this._data.calleeGroups || [])];
      }

      if (!this._filterQuery) return groups;

      return groups.filter(g => {
        const matchesPkg = (g.packageFqn && g.packageFqn.toLowerCase().includes(this._filterQuery)) ||
                           (g.shortName && g.shortName.toLowerCase().includes(this._filterQuery));
        const matchesMembers = g.members && g.members.some(m =>
          (m.label && m.label.toLowerCase().includes(this._filterQuery)) ||
          (m.className && m.className.toLowerCase().includes(this._filterQuery))
        );
        return matchesPkg || matchesMembers;
      });
    }

    _recomputeLayout() {
      if (!this._canvas || !this._data) return;

      const rect = this._canvas.getBoundingClientRect();
      const w = rect.width;
      const h = rect.height;

      // When drawer is open, shift center horizontally so the diagram stays centered in the remaining visible space
      const isDrawerOpen = !!this._selectedGroup;
      const drawerWidth = isDrawerOpen ? 360 : 0;
      const visibleW = w - drawerWidth;
      const cx = visibleW / 2;
      const cy = h / 2;

      const availableSize = Math.min(visibleW, h);

      // Adaptive sizing: ensure the entire radial tree (center, arcs, leaves, labels) fits inside visibleW
      const centerR = isDrawerOpen ?
        Math.max(20, Math.min(30, availableSize * 0.06 + 10)) :
        Math.max(26, Math.min(42, availableSize * 0.05 + 18));

      this._centerPos = { x: cx, y: cy, r: centerR };

      const groups = this._getActiveGroups();
      this._arcLayouts = [];
      this._leafNodes = [];

      if (groups.length === 0) return;

      const totalCount = groups.reduce((sum, g) => sum + g.count, 0) || 1;

      // Scale radii adaptively so that the radial diagram is never clipped by the drawer
      const innerRadius = isDrawerOpen ?
        Math.max(50, availableSize * 0.17) :
        Math.max(80, availableSize * 0.23);

      const arcThickness = isDrawerOpen ? 22 : 30;
      const outerRadius = innerRadius + arcThickness;
      const leafGap = isDrawerOpen ? 46 : 68;
      const leafRadius = outerRadius + leafGap;

      let currentAngle = -Math.PI / 2; // Start from top
      const gapAngle = groups.length > 1 ? (Math.PI * 2 * 0.08) / groups.length : 0;
      const availableAngle = Math.PI * 2 - (gapAngle * groups.length);

      groups.forEach((group, idx) => {
        const sweepAngle = Math.max(0.06, (group.count / totalCount) * availableAngle);
        const startAngle = currentAngle;
        const endAngle = currentAngle + sweepAngle;
        const midAngle = (startAngle + endAngle) / 2;

        const color = this._getGroupColor(group, idx);

        this._arcLayouts.push({
          group,
          index: idx,
          innerR: innerRadius,
          outerR: outerRadius,
          startAngle,
          endAngle,
          midAngle,
          color,
          cx,
          cy
        });

        // If this group is currently selected, compute sub-ring leaf positions
        if (this._selectedGroup && this._selectedGroup.packageFqn === group.packageFqn) {
          const members = group.members || [];
          const leafCount = members.length;

          // Multi-ring concentric layout:
          // 1 ring for <= 16 nodes
          // 2 rings for 17..36 nodes
          // 3 rings for > 36 nodes
          const numRings = leafCount > 36 ? 3 : (leafCount > 16 ? 2 : 1);
          const ringRadii = [];
          if (numRings === 1) {
            ringRadii.push(leafRadius);
          } else if (numRings === 2) {
            ringRadii.push(outerRadius + (isDrawerOpen ? 28 : 38), outerRadius + (isDrawerOpen ? 54 : 70));
          } else {
            ringRadii.push(
              outerRadius + (isDrawerOpen ? 24 : 32),
              outerRadius + (isDrawerOpen ? 46 : 60),
              outerRadius + (isDrawerOpen ? 68 : 88)
            );
          }

          const nodesPerRing = Math.ceil(leafCount / numRings);
          const minStep = isDrawerOpen ? 0.055 : 0.065;
          const neededSpread = (nodesPerRing - 1) * minStep;
          const maxAllowedSpread = Math.min(Math.PI * 0.75, Math.max(sweepAngle * 1.15, neededSpread));
          const leafSpread = Math.max(0.12, maxAllowedSpread);
          const leafStart = midAngle - leafSpread / 2;
          const leafStep = nodesPerRing > 1 ? leafSpread / (nodesPerRing - 1) : 0;

          // Scale leaf node radius adaptively
          const nodeR = isDrawerOpen ?
            (leafCount > 36 ? 5.5 : (leafCount > 16 ? 7.5 : 9.5)) :
            (leafCount > 36 ? 7 : (leafCount > 16 ? 9.5 : 12));

          members.forEach((m, mIdx) => {
            const ringIdx = mIdx % numRings;
            const colIdx = Math.floor(mIdx / numRings);
            // Half-step angular stagger on alternating rings creates a dense hexagonal cluster
            const ringOffset = (ringIdx % 2 === 1) ? (leafStep * 0.5) : 0;
            const angle = nodesPerRing === 1 ? midAngle : (leafStart + colIdx * leafStep + ringOffset);
            const rDist = ringRadii[ringIdx];
            const lx = cx + Math.cos(angle) * rDist;
            const ly = cy + Math.sin(angle) * rDist;

            this._leafNodes.push({
              member: m,
              x: lx,
              y: ly,
              r: nodeR,
              angle,
              color,
              ringIdx,
              parentArc: this._arcLayouts[idx]
            });
          });
        }

        currentAngle = endAngle + gapAngle;
      });
    }

    _getGroupColor(group, idx) {
      if (window.CodeLensPalette && window.CodeLensPalette.getCommunityColor) {
        return window.CodeLensPalette.getCommunityColor(group.packageFqn || group.shortName, idx);
      }
      const palette = [
        '#ef4444', '#f97316', '#f59e0b', '#10b981', '#06b6d4',
        '#3b82f6', '#6366f1', '#8b5cf6', '#ec4899', '#14b8a6',
        '#84cc16', '#eab308', '#a855f7', '#0284c7', '#d97706'
      ];
      return palette[idx % palette.length];
    }

    _onResize() {
      if (!this._canvas) return;
      const rect = this._canvas.getBoundingClientRect();
      const w = Math.max(400, rect.width);
      const h = Math.max(300, rect.height);
      this._canvas.width = w * this._dpr;
      this._canvas.height = h * this._dpr;
      this._ctx?.scale(this._dpr, this._dpr);
      this._recomputeLayout();
      this.requestRender();
    }

    requestRender() {
      if (!this._rafId) {
        this._rafId = requestAnimationFrame(() => {
          this._rafId = null;
          this._draw();
        });
      }
    }

    _draw() {
      const ctx = this._ctx;
      if (!ctx || !this._canvas) return;

      const rect = this._canvas.getBoundingClientRect();
      const w = rect.width;
      const h = rect.height;

      ctx.save();
      ctx.clearRect(0, 0, w, h);

      // Apply Zoom & Pan relative to the center position
      const cx = this._centerPos.x;
      const cy = this._centerPos.y;
      ctx.translate(cx + this._panX, cy + this._panY);
      ctx.scale(this._zoom, this._zoom);
      ctx.translate(-cx, -cy);

      // 1. Draw Bundled Bézier curves from Center to Package Arcs
      this._drawBundledCurves(ctx);

      // 2. Draw Package Arcs
      this._drawArcs(ctx);

      // 3. Draw Leaf Nodes (when a group is expanded)
      this._drawLeafNodes(ctx);

      // 4. Draw Center Node
      this._drawCenterNode(ctx);

      ctx.restore();
    }

    _drawBundledCurves(ctx) {
      const cx = this._centerPos.x;
      const cy = this._centerPos.y;

      this._arcLayouts.forEach(arc => {
        const isHovered = this._hoveredItem && this._hoveredItem.data === arc;
        const isSelected = this._selectedGroup && this._selectedGroup.packageFqn === arc.group.packageFqn;

        const targetX = cx + Math.cos(arc.midAngle) * (arc.innerR);
        const targetY = cy + Math.sin(arc.midAngle) * (arc.innerR);

        // Control point curved towards midpoint
        const ctrlDist = arc.innerR * 0.45;
        const cpX = cx + Math.cos(arc.midAngle) * ctrlDist;
        const cpY = cy + Math.sin(arc.midAngle) * ctrlDist;

        ctx.beginPath();
        ctx.moveTo(cx, cy);
        ctx.quadraticCurveTo(cpX, cpY, targetX, targetY);

        const countRatio = Math.min(1.0, arc.group.count / 200.0);
        const lineWidth = Math.max(1.8, Math.min(12.0, 2.0 + countRatio * 10.0));

        ctx.lineWidth = isSelected ? lineWidth + 3 : (isHovered ? lineWidth + 1.5 : lineWidth);

        const alpha = isSelected ? 0.85 : (isHovered ? 0.75 : (this._selectedGroup ? 0.20 : 0.45));
        ctx.strokeStyle = this._hexToRgba(arc.color, alpha);
        ctx.stroke();

        // Subtle gradient particle glow at midpoint
        if (isSelected || isHovered) {
          ctx.beginPath();
          ctx.arc(cpX, cpY, lineWidth * 1.4, 0, Math.PI * 2);
          ctx.fillStyle = arc.color;
          ctx.shadowColor = arc.color;
          ctx.shadowBlur = 10;
          ctx.fill();
          ctx.shadowBlur = 0;
        }
      });
    }

    _drawArcs(ctx) {
      const isLight = document.body.classList.contains('theme-light');

      this._arcLayouts.forEach(arc => {
        const isHovered = this._hoveredItem && this._hoveredItem.data === arc;
        const isSelected = this._selectedGroup && this._selectedGroup.packageFqn === arc.group.packageFqn;

        ctx.save();

        const expandR = isSelected ? 8 : (isHovered ? 4 : 0);
        const innerR = arc.innerR + expandR;
        const outerR = arc.outerR + expandR;

        // Draw Arc Ribbon
        ctx.beginPath();
        ctx.arc(arc.cx, arc.cy, outerR, arc.startAngle, arc.endAngle);
        ctx.arc(arc.cx, arc.cy, innerR, arc.endAngle, arc.startAngle, true);
        ctx.closePath();

        const alpha = isSelected ? 0.95 : (isHovered ? 0.85 : (this._selectedGroup ? 0.35 : 0.70));
        ctx.fillStyle = this._hexToRgba(arc.color, alpha);
        ctx.fill();

        if (isSelected || isHovered) {
          ctx.lineWidth = isSelected ? 2.5 : 1.5;
          ctx.strokeStyle = isLight ? (isSelected ? '#0f172a' : '#334155') : '#ffffff';
          ctx.shadowColor = arc.color;
          ctx.shadowBlur = isSelected ? 16 : 8;
          ctx.stroke();
          ctx.shadowBlur = 0;
        }

        // Draw Arc Label
        const labelR = (innerR + outerR) / 2;
        const lx = arc.cx + Math.cos(arc.midAngle) * labelR;
        const ly = arc.cy + Math.sin(arc.midAngle) * labelR;

        ctx.save();
        ctx.translate(lx, ly);

        // Orient text radially so it's upright and readable
        let textAngle = arc.midAngle;
        if (textAngle > Math.PI / 2 || textAngle < -Math.PI / 2) {
          textAngle += Math.PI;
        }
        ctx.rotate(textAngle);

        ctx.font = 'bold 11px system-ui, -apple-system, sans-serif';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';

        const lum = this._getLuminance(arc.color);
        const useDarkText = isLight ? (lum > 0.35 || alpha < 0.8) : (lum > 0.7);
        ctx.fillStyle = useDarkText ? '#0f172a' : '#ffffff';
        ctx.shadowColor = useDarkText ? 'rgba(255, 255, 255, 0.95)' : 'rgba(0, 0, 0, 0.85)';
        ctx.shadowBlur = 3;

        const label = `${arc.group.shortName} (${arc.group.count})`;
        ctx.fillText(label, 0, 0);

        ctx.restore();
        ctx.restore();
      });
    }

    _drawLeafNodes(ctx) {
      if (!this._selectedGroup || this._leafNodes.length === 0) return;

      const isLight = document.body.classList.contains('theme-light');
      const isSwiss = document.body.classList.contains('theme-swiss');

      this._leafNodes.forEach(leaf => {
        const isHovered = this._hoveredItem && this._hoveredItem.data === leaf;

        // Connector line from arc to leaf (clamped to parent arc angle range)
        const arc = leaf.parentArc;
        const clampedAngle = Math.max(arc.startAngle + 0.03, Math.min(arc.endAngle - 0.03, leaf.angle));
        const arcEdgeX = arc.cx + Math.cos(clampedAngle) * (arc.outerR + 4);
        const arcEdgeY = arc.cy + Math.sin(clampedAngle) * (arc.outerR + 4);

        ctx.beginPath();
        ctx.moveTo(arcEdgeX, arcEdgeY);
        ctx.lineTo(leaf.x, leaf.y);
        ctx.strokeStyle = this._hexToRgba(leaf.color, isHovered ? 0.9 : 0.25);
        ctx.lineWidth = isHovered ? 2.0 : 1.0;
        ctx.stroke();

        // Leaf Node Circle
        ctx.beginPath();
        ctx.arc(leaf.x, leaf.y, isHovered ? leaf.r + 4 : leaf.r, 0, Math.PI * 2);
        ctx.fillStyle = isHovered ? '#ffffff' : this._hexToRgba(leaf.color, 0.9);
        ctx.fill();

        ctx.strokeStyle = isHovered ? '#ffffff' : leaf.color;
        ctx.lineWidth = isHovered ? 2.5 : 1.5;
        ctx.stroke();

        // Node glyph 'm' or 'c'
        const leafGlyph = (leaf.member.type === 'CLASS' || leaf.member.kind === 'CLASS') ? 'c' : 'm';
        ctx.font = `bold ${Math.max(8, leaf.r - 2)}px "JetBrains Mono", monospace`;
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillStyle = isHovered ? '#000000' : '#ffffff';
        ctx.fillText(leafGlyph, leaf.x, leaf.y);

        // De-clutter leaf labels: only draw text label if drawer is not open and total leaves <= 10
        const shouldDrawLabel = !this._selectedGroup && this._leafNodes.length <= 10;
        if (shouldDrawLabel) {
          const rect = this._canvas.getBoundingClientRect();
          const visibleW = this._selectedGroup ? rect.width - 360 : rect.width;

          let isLabelRight = Math.cos(leaf.angle) >= 0;
          if (isLabelRight && (leaf.x + 120 > visibleW)) {
            isLabelRight = false;
          }

          const labelX = leaf.x + (isLabelRight ? (leaf.r + 8) : -(leaf.r + 8));
          const labelY = leaf.y;

          ctx.save();
          ctx.font = '10px system-ui, -apple-system, sans-serif';
          ctx.textAlign = isLabelRight ? 'left' : 'right';
          ctx.textBaseline = 'middle';

          const label = leaf.member.label || leaf.member.id?.split('.').pop() || '';
          const truncated = label.length > 24 ? label.substring(0, 22) + '…' : label;

          if (isSwiss) {
            ctx.fillStyle = '#111827';
            ctx.shadowColor = 'rgba(255,255,255,0.9)';
            ctx.shadowBlur = 3;
          } else if (isLight) {
            ctx.fillStyle = '#1e293b';
            ctx.shadowColor = 'rgba(255,255,255,0.9)';
            ctx.shadowBlur = 3;
          } else {
            ctx.fillStyle = '#e2e8f0';
            ctx.shadowColor = 'rgba(0,0,0,0.85)';
            ctx.shadowBlur = 4;
          }

          ctx.fillText(truncated, labelX, labelY);
          ctx.restore();
        }
      });
    }

    _drawCenterNode(ctx) {
      const { x, y, r } = this._centerPos;
      const isHovered = this._hoveredItem && this._hoveredItem.type === 'center';
      const center = this._data?.centerNode || {};
      const isClass = center.type === 'CLASS';

      ctx.save();

      // Outer ambient glow
      ctx.beginPath();
      ctx.arc(x, y, r + 12, 0, Math.PI * 2);
      const glowGrad = ctx.createRadialGradient(x, y, r * 0.5, x, y, r + 16);
      if (isClass) {
        glowGrad.addColorStop(0, 'rgba(59, 130, 246, 0.45)');
        glowGrad.addColorStop(1, 'rgba(59, 130, 246, 0.0)');
      } else {
        glowGrad.addColorStop(0, 'rgba(239, 68, 68, 0.45)');
        glowGrad.addColorStop(1, 'rgba(239, 68, 68, 0.0)');
      }
      ctx.fillStyle = glowGrad;
      ctx.fill();

      // Center circle body
      ctx.beginPath();
      ctx.arc(x, y, isHovered ? r + 3 : r, 0, Math.PI * 2);
      const bodyGrad = ctx.createRadialGradient(x - r * 0.3, y - r * 0.3, r * 0.1, x, y, r);
      if (isClass) {
        bodyGrad.addColorStop(0, '#60a5fa');
        bodyGrad.addColorStop(1, '#1d4ed8');
      } else {
        bodyGrad.addColorStop(0, '#f87171');
        bodyGrad.addColorStop(1, '#991b1b');
      }
      ctx.fillStyle = bodyGrad;
      ctx.fill();

      // Crisp border ring
      ctx.lineWidth = isHovered ? 3.0 : 2.0;
      ctx.strokeStyle = '#ffffff';
      ctx.stroke();

      // Center Node Glyph & Label
      ctx.font = 'bold 16px "JetBrains Mono", monospace';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillStyle = '#ffffff';
      ctx.fillText(isClass ? 'c' : 'm', x, y - 8);

      // Short entity name
      ctx.font = 'bold 11px system-ui, -apple-system, sans-serif';
      const label = center.label || (center.id ? center.id.split('.').pop() : 'Entity');
      const shortLabel = label.length > 14 ? label.substring(0, 12) + '…' : label;
      ctx.fillText(shortLabel, x, y + 12);

      ctx.restore();
    }

    _renderDrawer() {
      const drawer = this._overlay?.querySelector('#hub-drawer');
      if (!drawer) return;

      if (!this._selectedGroup) {
        drawer.classList.remove('open');
        this._overlay?.classList.remove('drawer-open');
        return;
      }

      drawer.classList.add('open');
      this._overlay?.classList.add('drawer-open');

      const titleEl = drawer.querySelector('#hub-drawer-pkg-title');
      const countEl = drawer.querySelector('#hub-drawer-pkg-count');
      const listEl = drawer.querySelector('#hub-drawer-member-list');
      const matchBadge = drawer.querySelector('#hub-drawer-match-count');

      if (titleEl) titleEl.textContent = this._selectedGroup.shortName;
      if (countEl) countEl.textContent = `${this._selectedGroup.count} callers (${this._selectedGroup.packageFqn})`;

      if (listEl) {
        const query = this._drawerFilterQuery || this._filterQuery;
        const allMembers = this._selectedGroup.members || [];
        const members = allMembers.filter(m => {
          if (!query) return true;
          return (m.label && m.label.toLowerCase().includes(query)) ||
                 (m.className && m.className.toLowerCase().includes(query)) ||
                 (m.id && m.id.toLowerCase().includes(query));
        });

        if (matchBadge) {
          matchBadge.textContent = `${members.length} / ${allMembers.length}`;
        }

        if (members.length === 0) {
          listEl.innerHTML = '<div class="hub-empty-members">No callers match filter</div>';
          return;
        }

        // Group members by short class name
        const classGroups = new Map();
        members.forEach(m => {
          const cls = m.className ? m.className.split('.').pop() : 'Default';
          if (!classGroups.has(cls)) classGroups.set(cls, []);
          classGroups.get(cls).push(m);
        });

        let html = '';
        classGroups.forEach((mList, cls) => {
          html += `
            <div class="hub-class-group" data-class="${this._escapeHtml(cls)}">
              <div class="hub-class-header">
                <div class="hub-class-header-left">
                  <span class="hub-class-chevron">▼</span>
                  <span class="hub-class-title" title="${this._escapeHtml(cls)}">${this._escapeHtml(cls)}</span>
                </div>
                <span class="hub-class-badge">${mList.length}</span>
              </div>
              <div class="hub-class-members">
                ${mList.map(m => {
                  const methodDisplayName = (m.label && cls && m.label.startsWith(cls + '.')) ?
                    m.label.substring(cls.length + 1) :
                    (m.label || (m.id ? m.id.split('.').pop() : ''));
                  return `
                  <div class="hub-member-item" data-fqn="${this._escapeHtml(m.id)}">
                    <div class="hub-member-main">
                      <span class="hub-member-glyph">m</span>
                      <div class="hub-member-info">
                        <span class="hub-member-name" title="${this._escapeHtml(m.label || m.id)}">${this._escapeHtml(methodDisplayName)}</span>
                        <span class="hub-member-class" title="${this._escapeHtml(m.className || cls)}">${this._escapeHtml(cls)}</span>
                      </div>
                    </div>
                    <div class="hub-member-actions">
                      <button class="hub-member-hub-btn" title="Explore this method as central hub">Explore ↗</button>
                      <button class="hub-member-action-btn" title="Inspect method in CodeLens 2D Graph">Graph →</button>
                    </div>
                  </div>
                `;
                }).join('')}
              </div>
            </div>
          `;
        });

        listEl.innerHTML = html;

        // Collapsible header toggle
        listEl.querySelectorAll('.hub-class-header').forEach(header => {
          header.onclick = (e) => {
            e.stopPropagation();
            const group = header.closest('.hub-class-group');
            if (group) group.classList.toggle('collapsed');
          };
        });

        // Click and Bidirectional hover handlers
        listEl.querySelectorAll('.hub-member-item').forEach(el => {
          const fqn = el.dataset.fqn;

          // Row single click: select/highlight only, DO NOT close hub!
          el.onclick = (e) => {
            if (e.target.closest('button')) return; // handled by action buttons
            listEl.querySelectorAll('.hub-member-item').forEach(i => i.classList.remove('selected', 'highlight'));
            el.classList.add('selected', 'highlight');
            const leaf = this._leafNodes.find(l => l.member.id === fqn);
            if (leaf) {
              this._hoveredItem = { type: 'leaf', data: leaf };
              this.requestRender();
            }
          };

          // Row double click: explore this method as hub
          el.ondblclick = (e) => {
            if (e.target.closest('button')) return;
            this.load(fqn, 'callers');
          };

          // Explore button: drill down into hub
          const hubBtn = el.querySelector('.hub-member-hub-btn');
          if (hubBtn) {
            hubBtn.onclick = (e) => {
              e.stopPropagation();
              this.load(fqn, 'callers');
            };
          }

          // Graph button: navigate to 2D graph
          const graphBtn = el.querySelector('.hub-member-action-btn');
          if (graphBtn) {
            graphBtn.onclick = (e) => {
              e.stopPropagation();
              this.close();
              if (window.selectMethod) {
                window.selectMethod(fqn);
              } else if (window.App && window.App.selectMethod) {
                window.App.selectMethod(fqn);
              }
            };
          }

          el.onmouseenter = () => {
            const leaf = this._leafNodes.find(l => l.member.id === fqn);
            if (leaf) {
              this._hoveredItem = { type: 'leaf', data: leaf };
              this.requestRender();
            }
          };

          el.onmouseleave = () => {
            if (this._hoveredItem && this._hoveredItem.type === 'leaf' && this._hoveredItem.data.member.id === fqn) {
              this._hoveredItem = null;
              this.requestRender();
            }
          };
        });
      }
    }

    _onMouseDown(e) {
      if (e.button !== 0) return;
      const mouse = this._getCanvasPoint(e);
      const hit = this._hitTest(mouse.x, mouse.y);

      if (hit) {
        if (hit.type === 'arc') {
          // Toggle group selection
          if (this._selectedGroup && this._selectedGroup.packageFqn === hit.data.group.packageFqn) {
            this._selectedGroup = null;
            this._drawerFilterQuery = '';
            const df = this._overlay?.querySelector('#hub-drawer-filter');
            if (df) df.value = '';
          } else {
            this._selectedGroup = hit.data.group;
          }
          this._recomputeLayout();
          this._renderDrawer();
          this.requestRender();
        } else if (hit.type === 'leaf') {
          // Single click on leaf: highlight in drawer and canvas, DO NOT close hub!
          const fqn = hit.data.member.id;
          this._highlightDrawerMember(fqn);
          this._hoveredItem = hit;
          this.requestRender();
        }
      } else {
        this._isPanning = true;
        this._startPan = { x: e.clientX - this._panX, y: e.clientY - this._panY };
        this._canvas.style.cursor = 'grabbing';
      }
    }

    _onDblClick(e) {
      const mouse = this._getCanvasPoint(e);
      const hit = this._hitTest(mouse.x, mouse.y);
      if (hit) {
        if (hit.type === 'leaf') {
          // Double-clicking a leaf drills into it as a new hub
          this.load(hit.data.member.id, 'callers');
        } else if (hit.type === 'center') {
          // Double-clicking center closes hub and opens in 2D graph
          const center = this._data?.centerNode;
          if (center && center.id) {
            this.close();
            if (window.selectMethod) window.selectMethod(center.id);
          }
        }
      }
    }

    _highlightDrawerMember(fqn) {
      const listEl = this._overlay?.querySelector('#hub-drawer-member-list');
      if (!listEl) return;
      listEl.querySelectorAll('.hub-member-item').forEach(item => {
        if (item.dataset.fqn === fqn) {
          item.classList.add('selected', 'highlight');
          const grp = item.closest('.hub-class-group');
          if (grp) grp.classList.remove('collapsed');
          item.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
        } else {
          item.classList.remove('selected', 'highlight');
        }
      });
    }

    _onMouseMove(e) {
      if (this._isPanning) {
        this._panX = e.clientX - this._startPan.x;
        this._panY = e.clientY - this._startPan.y;
        this.requestRender();
        return;
      }

      if (!this._canvas) return;
      const mouse = this._getCanvasPoint(e);
      const hit = this._hitTest(mouse.x, mouse.y);

      if (hit !== this._hoveredItem) {
        this._hoveredItem = hit;
        this._canvas.style.cursor = hit ? 'pointer' : 'grab';
        this._updateTooltip(e, hit);
        this._updateDrawerHighlight(hit);
        this.requestRender();
      } else if (hit && this._tooltip && this._tooltip.style.display !== 'none') {
        this._positionTooltip(e);
      }
    }

    _updateDrawerHighlight(hit) {
      const listEl = this._overlay?.querySelector('#hub-drawer-member-list');
      if (!listEl) return;

      if (hit && hit.type === 'leaf') {
        const fqn = hit.data.member.id;
        listEl.querySelectorAll('.hub-member-item').forEach(item => {
          if (item.dataset.fqn === fqn) {
            item.classList.add('highlight');
            const grp = item.closest('.hub-class-group');
            if (grp) grp.classList.remove('collapsed');
            item.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
          } else {
            item.classList.remove('highlight');
          }
        });
      } else {
        listEl.querySelectorAll('.hub-member-item.highlight:not(.selected)').forEach(item => {
          item.classList.remove('highlight');
        });
      }
    }

    _onMouseUp() {
      if (this._isPanning) {
        this._isPanning = false;
        if (this._canvas) this._canvas.style.cursor = 'grab';
      }
    }

    _onWheel(e) {
      e.preventDefault();
      const zoomFactor = e.deltaY < 0 ? 1.12 : 0.88;
      this._zoom = Math.max(0.35, Math.min(4.0, this._zoom * zoomFactor));
      this.requestRender();
    }

    _getCanvasPoint(e) {
      const rect = this._canvas.getBoundingClientRect();
      const rawX = e.clientX - rect.left;
      const rawY = e.clientY - rect.top;

      // Invert zoom and pan relative to current center
      const cx = this._centerPos.x;
      const cy = this._centerPos.y;
      const centeredX = rawX - (cx + this._panX);
      const centeredY = rawY - (cy + this._panY);

      return {
        x: centeredX / this._zoom + cx,
        y: centeredY / this._zoom + cy
      };
    }

    _hitTest(x, y) {
      // 1. Center node hit test
      const { x: cx, y: cy, r: cr } = this._centerPos;
      const distFromCenter = Math.hypot(x - cx, y - cy);
      if (distFromCenter <= cr) {
        return { type: 'center', data: this._data?.centerNode };
      }

      // 2. Leaf nodes hit test
      for (const leaf of this._leafNodes) {
        if (Math.hypot(x - leaf.x, y - leaf.y) <= leaf.r + 4) {
          return { type: 'leaf', data: leaf };
        }
      }

      // 3. Package Arcs hit test (polar coordinates)
      const angle = Math.atan2(y - cy, x - cx);
      for (const arc of this._arcLayouts) {
        if (distFromCenter >= arc.innerR && distFromCenter <= arc.outerR + 8) {
          // Normalize angles between -PI and PI
          if (this._isAngleBetween(angle, arc.startAngle, arc.endAngle)) {
            return { type: 'arc', data: arc };
          }
        }
      }

      return null;
    }

    _isAngleBetween(target, a, b) {
      const twoPi = Math.PI * 2;
      let diff = (b - a) % twoPi;
      if (diff < 0) diff += twoPi;
      let targetDiff = (target - a) % twoPi;
      if (targetDiff < 0) targetDiff += twoPi;
      return targetDiff <= diff;
    }

    _updateTooltip(e, hit) {
      if (!this._tooltip) return;
      if (!hit) {
        this._tooltip.style.display = 'none';
        return;
      }

      let html = '';
      if (hit.type === 'center') {
        const center = this._data?.centerNode || {};
        html = `
          <div class="ht-title">Central Hub Method</div>
          <div class="ht-desc">${this._escapeHtml(center.id || '')}</div>
          <div class="ht-stats">
            <span>Callers: <strong>${(this._data?.totalCallers || 0).toLocaleString()}</strong></span>
            <span>Callees: <strong>${(this._data?.totalCallees || 0).toLocaleString()}</strong></span>
          </div>
        `;
      } else if (hit.type === 'arc') {
        const arc = hit.data;
        const total = this._data?.totalCallers || 1;
        const pct = ((arc.group.count / total) * 100).toFixed(1);
        html = `
          <div class="ht-title" style="color:${arc.color}">${this._escapeHtml(arc.group.shortName)}</div>
          <div class="ht-desc">${this._escapeHtml(arc.group.packageFqn)}</div>
          <div class="ht-stats">
            <span>Connections: <strong>${arc.group.count}</strong> (${pct}%)</span>
          </div>
          <div class="ht-hint">Click arc to view caller methods</div>
        `;
      } else if (hit.type === 'leaf') {
        const leaf = hit.data;
        html = `
          <div class="ht-title">${this._escapeHtml(leaf.member.label)}</div>
          <div class="ht-desc">${this._escapeHtml(leaf.member.className)}</div>
          <div class="ht-hint">Click to select · Double-click to explore hub</div>
        `;
      }

      this._tooltip.innerHTML = html;
      this._tooltip.style.display = 'block';
      this._positionTooltip(e);
    }

    _positionTooltip(e) {
      if (!this._tooltip || !this._overlay) return;
      const overlayRect = this._overlay.getBoundingClientRect();
      const isDrawerOpen = !!this._selectedGroup;
      const drawerW = isDrawerOpen ? 360 : 0;
      const visibleW = overlayRect.width - drawerW;

      let x = e.clientX - overlayRect.left + 16;
      let y = e.clientY - overlayRect.top + 16;

      // Flip tooltip to left of cursor if it would cross into the drawer or screen edge
      if (x + 280 > visibleW) {
        x = (e.clientX - overlayRect.left) - 290;
      }
      x = Math.max(12, Math.min(visibleW - 290, x));
      y = Math.max(12, Math.min(overlayRect.height - 130, y));

      this._tooltip.style.left = `${x}px`;
      this._tooltip.style.top = `${y}px`;
    }

    _hexToRgba(hex, alpha) {
      if (!hex || hex.length < 6) return `rgba(59, 130, 246, ${alpha})`;
      const clean = hex.replace('#', '');
      const r = parseInt(clean.substring(0, 2), 16) || 0;
      const g = parseInt(clean.substring(2, 4), 16) || 0;
      const b = parseInt(clean.substring(4, 6), 16) || 0;
      return `rgba(${r}, ${g}, ${b}, ${alpha})`;
    }

    _getLuminance(color) {
      if (!color || typeof color !== 'string') return 0.5;
      if (color.startsWith('#')) {
        let clean = color.slice(1);
        if (clean.length === 3) clean = clean.split('').map(c => c + c).join('');
        if (clean.length >= 6) {
          const r = parseInt(clean.substring(0, 2), 16) / 255;
          const g = parseInt(clean.substring(2, 4), 16) / 255;
          const b = parseInt(clean.substring(4, 6), 16) / 255;
          return 0.299 * r + 0.587 * g + 0.114 * b;
        }
      } else if (color.startsWith('rgb')) {
        const m = color.match(/\d+/g);
        if (m && m.length >= 3) {
          return 0.299 * (parseInt(m[0], 10) / 255) + 0.587 * (parseInt(m[1], 10) / 255) + 0.114 * (parseInt(m[2], 10) / 255);
        }
      }
      return 0.5;
    }

    _escapeHtml(str) {
      if (!str) return '';
      return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
    }
  }

  // Export globally
  window.HubExplorer = HubExplorer;
})();
