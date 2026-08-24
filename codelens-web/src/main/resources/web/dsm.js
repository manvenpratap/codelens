/**
 * dsm.js - Advanced Dependency Structure Matrix (DSM) Renderer
 *
 * Features:
 * - 100% color-consistency with Sunburst, Treemap, Chord & Graphify via window.CodeLensPalette.
 * - Interactive multi-tier scoping (Modules / Packages / Classes).
 * - Multi-mode architectural reordering:
 *     * Alpha (A-Z)
 *     * Cluster (Package / Module clustering with diagonal boundary frames)
 *     * Layered (Topological ordering: in-degree vs out-degree feedforward architecture)
 *     * Cycles (Cycle isolation ranking)
 * - Cycle isolation filter toggle.
 * - Live instant entity search & matrix dimming.
 * - Glassmorphic caller -> callee tooltip with matching color swatches.
 * - Deep inspector selection callbacks on cell and header clicks.
 */

class DSMRenderer {
  constructor(container) {
    this._container = container;
    this._el = null;
    this._data = null;
    this._sortMode = 'cluster'; // 'cluster' | 'alpha' | 'layered' | 'cycles'
    this._filterCyclesOnly = false;
    this._searchQuery = '';
    this._selectedCell = null;
    this._onScopeChange = null;
    this._onSelectCell = null;
    this._onSelectEntity = null;
    this._tooltip = null;
  }

  onScopeChange(callback) {
    this._onScopeChange = callback;
  }

  onSelectCell(callback) {
    this._onSelectCell = callback;
  }

  onSelectEntity(callback) {
    this._onSelectEntity = callback;
  }

  setData(payload) {
    this._data = payload;
    this._render();
  }

  destroy() {
    if (this._tooltip) {
      this._tooltip.remove();
      this._tooltip = null;
    }
    if (this._el) {
      this._el.remove();
      this._el = null;
    }
  }

  _render() {
    if (this._el) this._el.remove();
    if (!this._data || !this._data.classes || this._data.classes.length === 0) return;

    const rawClasses = this._data.classes;
    let rawMatrix = this._data.matrix;
    const packages = this._data.packages || {};
    const n = rawClasses.length;

    // Fast sparse matrix support: reconstruct grid from sparse cells payload if matrix is sparse/omitted
    if ((!rawMatrix || rawMatrix.length === 0) && this._data.cells && Array.isArray(this._data.cells)) {
      rawMatrix = Array.from({ length: n }, () => new Array(n).fill(0));
      for (const cell of this._data.cells) {
        if (cell.r < n && cell.c < n) {
          rawMatrix[cell.r][cell.c] = cell.v;
        }
      }
    }

    // Detect cycles in original matrix: cells where both (i,j) and (j,i) > 0 and i !== j
    const cycles = new Set();
    const cyclicEntityIndices = new Set();
    for (let i = 0; i < n; i++) {
      for (let j = i + 1; j < n; j++) {
        if (rawMatrix[i][j] > 0 && rawMatrix[j][i] > 0) {
          cycles.add(`${rawClasses[i]}->${rawClasses[j]}`);
          cycles.add(`${rawClasses[j]}->${rawClasses[i]}`);
          cyclicEntityIndices.add(i);
          cyclicEntityIndices.add(j);
        }
      }
    }

    // Compute in-degrees and out-degrees for topological/layered ordering
    const inDegrees = new Array(n).fill(0);
    const outDegrees = new Array(n).fill(0);
    for (let i = 0; i < n; i++) {
      for (let j = 0; j < n; j++) {
        if (i !== j && rawMatrix[i][j] > 0) {
          outDegrees[i] += rawMatrix[i][j];
          inDegrees[j] += rawMatrix[i][j];
        }
      }
    }

    // Compute permutation indices based on sort mode
    const indices = Array.from({ length: n }, (_, i) => i);
    if (this._sortMode === 'alpha') {
      indices.sort((a, b) => {
        const nameA = this._shortName(rawClasses[a]);
        const nameB = this._shortName(rawClasses[b]);
        return nameA.localeCompare(nameB);
      });
    } else if (this._sortMode === 'cluster') {
      indices.sort((a, b) => {
        const pkgA = packages[rawClasses[a]] || '';
        const pkgB = packages[rawClasses[b]] || '';
        const pkgCmp = pkgA.localeCompare(pkgB);
        if (pkgCmp !== 0) return pkgCmp;
        return this._shortName(rawClasses[a]).localeCompare(this._shortName(rawClasses[b]));
      });
    } else if (this._sortMode === 'layered') {
      // High in-degree, low out-degree (foundational providers) top-left;
      // High out-degree, low in-degree (top-level orchestrators) bottom-right
      indices.sort((a, b) => {
        const rankA = inDegrees[a] - outDegrees[a];
        const rankB = inDegrees[b] - outDegrees[b];
        if (rankB !== rankA) return rankB - rankA;
        return this._shortName(rawClasses[a]).localeCompare(this._shortName(rawClasses[b]));
      });
    } else if (this._sortMode === 'cycles') {
      indices.sort((a, b) => {
        const aCyc = cyclicEntityIndices.has(a) ? 1 : 0;
        const bCyc = cyclicEntityIndices.has(b) ? 1 : 0;
        if (bCyc !== aCyc) return bCyc - aCyc;
        return this._shortName(rawClasses[a]).localeCompare(this._shortName(rawClasses[b]));
      });
    }

    // Ordered classes and re-indexed matrix
    const orderedClasses = indices.map(idx => rawClasses[idx]);
    const orderedMatrix = [];
    let maxVal = 1;
    for (let r = 0; r < n; r++) {
      orderedMatrix[r] = [];
      const origR = indices[r];
      for (let c = 0; c < n; c++) {
        const origC = indices[c];
        const val = rawMatrix[origR][origC];
        orderedMatrix[r][c] = val;
        if (val > maxVal) maxVal = val;
      }
    }

    // Main wrapper
    const wrap = document.createElement('div');
    wrap.className = 'dsm-container';
    wrap.setAttribute('role', 'region');
    wrap.setAttribute('aria-label', 'Dependency Structure Matrix');

    // Advanced Toolbar & Controls Bar
    const currentScope = this._data.scope || 'classes';
    const toolbar = document.createElement('div');
    toolbar.className = 'dsm-toolbar';

    const totalDeps = rawMatrix.flat().reduce((s, v) => s + (v > 0 ? 1 : 0), 0) - n;
    const cycleCount = cycles.size / 2;
    const scopeLabel = currentScope === 'modules' ? 'Modules' : (currentScope === 'packages' ? 'Packages' : 'Classes');

    toolbar.innerHTML = `
      <div class="dsm-toolbar-left">
        <!-- Tier Scope Switcher -->
        <div class="dsm-scope-pills" role="tablist" title="Change abstraction tier">
          <button class="dsm-scope-btn ${currentScope === 'modules' ? 'active' : ''}" data-scope="modules">Modules</button>
          <button class="dsm-scope-btn ${currentScope === 'packages' ? 'active' : ''}" data-scope="packages">Packages</button>
          <button class="dsm-scope-btn ${currentScope === 'classes' ? 'active' : ''}" data-scope="classes">Classes</button>
        </div>

        <!-- Architectural Ordering Selector -->
        <div class="dsm-sort-pills" title="Architectural Ordering & Clustering">
          <span class="dsm-sort-label">Order:</span>
          <button class="dsm-sort-btn ${this._sortMode === 'cluster' ? 'active' : ''}" data-sort="cluster">📦 Cluster</button>
          <button class="dsm-sort-btn ${this._sortMode === 'layered' ? 'active' : ''}" data-sort="layered">📐 Layered</button>
          <button class="dsm-sort-btn ${this._sortMode === 'cycles' ? 'active' : ''}" data-sort="cycles">🔄 Cycles</button>
          <button class="dsm-sort-btn ${this._sortMode === 'alpha' ? 'active' : ''}" data-sort="alpha">🔤 A-Z</button>
        </div>
      </div>

      <div class="dsm-toolbar-right">
        <!-- Filter Cycles Only Toggle -->
        <button class="dsm-filter-btn ${this._filterCyclesOnly ? 'active' : ''}" id="dsm-cycle-toggle" title="Filter to show only circular dependencies">
          ⚠️ ${this._filterCyclesOnly ? 'Showing Cycles Only' : 'Highlight Cycles'}
        </button>

        <!-- Live Search Box -->
        <div class="dsm-search-wrap">
          <input type="text" class="dsm-search-input" placeholder="Filter entities..." value="${this._escHtml(this._searchQuery)}" />
          ${this._searchQuery ? '<button class="dsm-search-clear">&times;</button>' : ''}
        </div>

        <!-- Stats Chips -->
        <div class="dsm-stats-chips">
          <span class="dsm-chip" title="Total entities in view"><strong class="dsm-chip-num">${n}</strong> ${scopeLabel}</span>
          <span class="dsm-chip" title="Total non-diagonal dependencies"><strong class="dsm-chip-num">${Math.max(0, totalDeps)}</strong> Deps</span>
          <span class="dsm-chip ${cycleCount > 0 ? 'dsm-chip-warn' : ''}" title="Circular dependency pairs"><strong class="dsm-chip-num">${cycleCount}</strong> Cycles</span>
        </div>
      </div>
    `;

    // Wire scope switchers
    toolbar.querySelectorAll('.dsm-scope-btn').forEach(btn => {
      btn.addEventListener('click', (e) => {
        const scope = e.currentTarget.dataset.scope;
        if (this._onScopeChange) this._onScopeChange(scope);
      });
    });

    // Wire sort buttons
    toolbar.querySelectorAll('.dsm-sort-btn').forEach(btn => {
      btn.addEventListener('click', (e) => {
        this._sortMode = e.currentTarget.dataset.sort;
        this._render();
      });
    });

    // Wire cycle toggle
    const cycleToggle = toolbar.querySelector('#dsm-cycle-toggle');
    if (cycleToggle) {
      cycleToggle.addEventListener('click', () => {
        this._filterCyclesOnly = !this._filterCyclesOnly;
        this._render();
      });
    }

    // Wire search input
    const searchInput = toolbar.querySelector('.dsm-search-input');
    if (searchInput) {
      searchInput.addEventListener('input', (e) => {
        this._searchQuery = e.target.value.toLowerCase().trim();
        this._applySearchFilter(table, orderedClasses);
      });
    }
    const clearBtn = toolbar.querySelector('.dsm-search-clear');
    if (clearBtn) {
      clearBtn.addEventListener('click', () => {
        this._searchQuery = '';
        this._render();
      });
    }

    wrap.appendChild(toolbar);

    // Scrollable matrix area
    const scroll = document.createElement('div');
    scroll.className = 'dsm-scroll';

    const table = document.createElement('table');
    table.className = 'dsm-table';

    // Header row with rotated column labels & entity color tags
    const thead = document.createElement('thead');
    const headerRow = document.createElement('tr');
    const cornerCell = document.createElement('th');
    cornerCell.className = 'dsm-corner-cell';
    cornerCell.innerHTML = `<span class="dsm-corner-label">Caller \\ Callee</span>`;
    headerRow.appendChild(cornerCell);

    for (let j = 0; j < n; j++) {
      const cls = orderedClasses[j];
      const colColor = (window.CodeLensPalette && window.CodeLensPalette.getColor)
        ? window.CodeLensPalette.getColor(cls, j)
        : '#3b82f6';

      const th = document.createElement('th');
      th.className = 'dsm-col-header';
      th.dataset.col = j;
      th.dataset.entity = cls;
      th.title = `[${j + 1}] ${cls}`;

      const textWrap = document.createElement('div');
      textWrap.className = 'dsm-col-text-wrap';
      textWrap.innerHTML = `
        <span class="dsm-idx" style="border-left: 2.5px solid ${colColor}">${j + 1}</span>
        <span class="dsm-color-dot" style="background:${colColor}; box-shadow: 0 0 6px ${colColor}66;"></span>
        <span class="dsm-col-label">${this._escHtml(this._shortName(cls))}</span>
      `;
      th.appendChild(textWrap);

      th.addEventListener('click', () => {
        if (this._onSelectEntity) this._onSelectEntity(cls);
      });

      headerRow.appendChild(th);
    }
    thead.appendChild(headerRow);
    table.appendChild(thead);

    // Body rows
    const tbody = document.createElement('tbody');
    let prevPkg = null;

    for (let i = 0; i < n; i++) {
      const rowCls = orderedClasses[i];
      const rowPkg = packages[rowCls] || '(default)';
      const rowColor = (window.CodeLensPalette && window.CodeLensPalette.getColor)
        ? window.CodeLensPalette.getColor(rowCls, i)
        : '#3b82f6';

      // Package cluster separator line in cluster mode
      if (this._sortMode === 'cluster' && rowPkg !== prevPkg && i > 0) {
        const sepRow = document.createElement('tr');
        sepRow.className = 'dsm-pkg-separator';
        const sepCell = document.createElement('td');
        sepCell.colSpan = n + 1;
        sepCell.innerHTML = `<span class="dsm-pkg-sep-tag">📦 ${this._escHtml(rowPkg)}</span>`;
        sepRow.appendChild(sepCell);
        tbody.appendChild(sepRow);
      }
      prevPkg = rowPkg;

      const tr = document.createElement('tr');
      tr.dataset.row = i;
      tr.dataset.entity = rowCls;

      // Row header with matching color tag
      const rowHeader = document.createElement('td');
      rowHeader.className = 'dsm-row-header';
      rowHeader.title = `[${i + 1}] ${rowCls} (In: ${inDegrees[indices[i]]}, Out: ${outDegrees[indices[i]]})`;
      rowHeader.innerHTML = `
        <span class="dsm-idx" style="border-left: 2.5px solid ${rowColor}">${i + 1}</span>
        <span class="dsm-color-dot" style="background:${rowColor}; box-shadow: 0 0 6px ${rowColor}66;"></span>
        <span class="dsm-row-label">${this._escHtml(this._shortName(rowCls))}</span>
        <span class="dsm-row-degree" title="Out / In degree">${outDegrees[indices[i]]}/${inDegrees[indices[i]]}</span>
      `;
      rowHeader.addEventListener('click', () => {
        if (this._onSelectEntity) this._onSelectEntity(rowCls);
      });
      tr.appendChild(rowHeader);

      // Data cells
      for (let j = 0; j < n; j++) {
        const colCls = orderedClasses[j];
        const td = document.createElement('td');
        td.className = 'dsm-cell';
        td.dataset.row = i;
        td.dataset.col = j;
        td.dataset.caller = rowCls;
        td.dataset.callee = colCls;

        const val = orderedMatrix[i][j];
        const isCycle = (i !== j) && (val > 0) && cycles.has(`${rowCls}->${colCls}`);

        if (i === j) {
          // Diagonal cell (self)
          td.classList.add('dsm-diagonal');
          td.style.borderLeft = `2px solid ${rowColor}55`;
          td.style.borderTop = `2px solid ${rowColor}55`;
        } else if (val > 0) {
          if (this._filterCyclesOnly && !isCycle) {
            td.classList.add('dsm-dimmed');
          }

          const intensity = Math.min(val / maxVal, 1);
          const alpha = 0.20 + intensity * 0.70;

          if (isCycle) {
            td.classList.add('dsm-cycle');
            td.style.backgroundColor = `rgba(239, 68, 68, ${alpha})`;
            td.style.boxShadow = `inset 0 0 0 1.5px rgba(239, 68, 68, 0.9), 0 0 8px rgba(239, 68, 68, 0.4)`;
          } else {
            // Tint cell background using Caller entity's consistent color
            td.style.backgroundColor = this._hexToRgba(rowColor, alpha);
          }

          td.textContent = val;
          td.dataset.val = val;

          // Interactive click selection
          td.addEventListener('click', () => {
            this._selectCell(td, rowCls, colCls, val, isCycle);
          });
        } else {
          if (this._filterCyclesOnly) {
            td.classList.add('dsm-dimmed');
          }
        }

        tr.appendChild(td);
      }

      tbody.appendChild(tr);
    }
    table.appendChild(tbody);

    // Crosshair & Tooltip
    table.addEventListener('mouseover', (e) => {
      const cell = e.target.closest('.dsm-cell');
      if (!cell) return;
      const r = parseInt(cell.dataset.row);
      const c = parseInt(cell.dataset.col);
      const caller = cell.dataset.caller;
      const callee = cell.dataset.callee;
      const val = parseInt(cell.dataset.val || '0');
      const isCycle = cell.classList.contains('dsm-cycle');

      this._setCrosshair(table, r, c, orderedClasses[r]);
      if (r !== c && val > 0) {
        this._showTooltip(e, caller, callee, val, isCycle);
      } else {
        this._hideTooltip();
      }
    });

    table.addEventListener('mousemove', (e) => {
      if (this._tooltip && this._tooltip.style.display !== 'none') {
        this._positionTooltip(e);
      }
    });

    table.addEventListener('mouseleave', () => {
      this._clearCrosshair(table);
      this._hideTooltip();
    });

    scroll.appendChild(table);
    wrap.appendChild(scroll);

    // Advanced Legend
    const legend = document.createElement('div');
    legend.className = 'dsm-legend';
    legend.innerHTML = `
      <div class="dsm-legend-left">
        <span class="dsm-legend-item"><span class="dsm-legend-swatch" style="background:var(--primary)"></span>Direct Call (Color = Caller Entity)</span>
        <span class="dsm-legend-item"><span class="dsm-legend-swatch" style="background:#ef4444; box-shadow:0 0 6px #ef444499;"></span>Circular Dependency (Cycle)</span>
        <span class="dsm-legend-item"><span class="dsm-legend-swatch dsm-legend-diag"></span>Self / Diagonal</span>
      </div>
      <div class="dsm-legend-right">
        <span class="dsm-hint-txt">💡 Click any cell or header to inspect deep call relationships</span>
      </div>
    `;
    wrap.appendChild(legend);

    this._container.appendChild(wrap);
    this._el = wrap;

    if (this._searchQuery) {
      this._applySearchFilter(table, orderedClasses);
    }
  }

  _selectCell(cell, caller, callee, val, isCycle) {
    if (this._selectedCell) {
      this._selectedCell.classList.remove('dsm-selected-cell');
    }
    cell.classList.add('dsm-selected-cell');
    this._selectedCell = cell;

    if (this._onSelectCell) {
      this._onSelectCell({ caller, callee, weight: val, isCycle });
    }
  }

  _setCrosshair(table, row, col, callerCls) {
    this._clearCrosshair(table);
    const cells = table.querySelectorAll('.dsm-cell');
    cells.forEach(cell => {
      const r = parseInt(cell.dataset.row);
      const c = parseInt(cell.dataset.col);
      if (r === row || c === col) {
        cell.classList.add('dsm-crosshair');
      }
    });

    // Highlight headers
    const colHeaders = table.querySelectorAll('.dsm-col-header');
    if (colHeaders[col]) colHeaders[col].classList.add('dsm-crosshair');
    const rowHeaders = table.querySelectorAll('.dsm-row-header');
    if (rowHeaders[row]) rowHeaders[row].classList.add('dsm-crosshair');
  }

  _clearCrosshair(table) {
    table.querySelectorAll('.dsm-crosshair').forEach(el => el.classList.remove('dsm-crosshair'));
  }

  _applySearchFilter(table, orderedClasses) {
    const q = this._searchQuery;
    const rows = table.querySelectorAll('tbody tr:not(.dsm-pkg-separator)');
    const cols = table.querySelectorAll('.dsm-col-header');

    if (!q) {
      rows.forEach(r => r.classList.remove('dsm-search-dimmed', 'dsm-search-highlight'));
      cols.forEach(c => c.classList.remove('dsm-search-dimmed', 'dsm-search-highlight'));
      return;
    }

    rows.forEach(r => {
      const name = (r.dataset.entity || '').toLowerCase();
      if (name.includes(q)) {
        r.classList.add('dsm-search-highlight');
        r.classList.remove('dsm-search-dimmed');
      } else {
        r.classList.add('dsm-search-dimmed');
        r.classList.remove('dsm-search-highlight');
      }
    });

    cols.forEach(c => {
      const name = (c.dataset.entity || '').toLowerCase();
      if (name.includes(q)) {
        c.classList.add('dsm-search-highlight');
        c.classList.remove('dsm-search-dimmed');
      } else {
        c.classList.add('dsm-search-dimmed');
        c.classList.remove('dsm-search-highlight');
      }
    });
  }

  _showTooltip(e, caller, callee, val, isCycle) {
    if (!this._tooltip) {
      this._tooltip = document.createElement('div');
      this._tooltip.className = 'dsm-tooltip';
      document.body.appendChild(this._tooltip);
    }

    const callerColor = (window.CodeLensPalette && window.CodeLensPalette.getColor)
      ? window.CodeLensPalette.getColor(caller, 0)
      : '#3b82f6';
    const calleeColor = (window.CodeLensPalette && window.CodeLensPalette.getColor)
      ? window.CodeLensPalette.getColor(callee, 1)
      : '#10b981';

    this._tooltip.innerHTML = `
      <div class="dsm-tip-header ${isCycle ? 'dsm-tip-cycle' : ''}">
        ${isCycle ? '⚠️ CIRCULAR DEPENDENCY CYCLE' : '🔗 DIRECT DEPENDENCY'}
      </div>
      <div class="dsm-tip-body">
        <div class="dsm-tip-row">
          <span class="dsm-tip-label">Caller:</span>
          <span class="dsm-tip-val"><span class="dsm-color-dot" style="background:${callerColor}"></span>${this._escHtml(this._shortName(caller))}</span>
        </div>
        <div class="dsm-tip-arrow">⬇ calls (${val} call${val > 1 ? 's' : ''})</div>
        <div class="dsm-tip-row">
          <span class="dsm-tip-label">Callee:</span>
          <span class="dsm-tip-val"><span class="dsm-color-dot" style="background:${calleeColor}"></span>${this._escHtml(this._shortName(callee))}</span>
        </div>
      </div>
      <div class="dsm-tip-footer">Click cell to open relationship inspector</div>
    `;

    this._tooltip.style.display = 'block';
    this._positionTooltip(e);
  }

  _positionTooltip(e) {
    if (!this._tooltip) return;
    const x = e.clientX + 16;
    const y = e.clientY + 16;
    this._tooltip.style.left = `${Math.min(window.innerWidth - 300, x)}px`;
    this._tooltip.style.top = `${Math.min(window.innerHeight - 150, y)}px`;
  }

  _hideTooltip() {
    if (this._tooltip) {
      this._tooltip.style.display = 'none';
    }
  }

  _hexToRgba(hex, alpha) {
    if (!hex || !hex.startsWith('#')) return `rgba(59, 130, 246, ${alpha})`;
    const c = parseInt(hex.replace('#', ''), 16);
    const r = (c >> 16) & 255;
    const g = (c >> 8) & 255;
    const b = c & 255;
    return `rgba(${r}, ${g}, ${b}, ${alpha})`;
  }

  _shortName(fqn) {
    if (!fqn) return '';
    const paren = fqn.indexOf('(');
    const base = paren >= 0 ? fqn.substring(0, paren) : fqn;
    const dot = base.lastIndexOf('.');
    return dot >= 0 ? base.substring(dot + 1) : base;
  }

  _escHtml(s) {
    const el = document.createElement('span');
    el.textContent = s || '';
    return el.innerHTML;
  }
}

window.DSMRenderer = DSMRenderer;
