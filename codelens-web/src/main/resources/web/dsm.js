/**
 * dsm.js - Dependency Structure Matrix Renderer
 *
 * Renders a class-level adjacency matrix as an interactive HTML table.
 * Rows and columns are Java classes; cells show call counts with color intensity.
 * Highlights dependency cycles and provides crosshair inspection on hover.
 */

class DSMRenderer {
  constructor(container) {
    this._container = container;
    this._el = null;
    this._data = null;
    this._hoverRow = -1;
    this._hoverCol = -1;
    this._sortMode = 'alpha'; // 'alpha' | 'package'
  }

  setData(payload) {
    this._data = payload;
    this._render();
  }

  destroy() {
    if (this._el) {
      this._el.remove();
      this._el = null;
    }
  }

  _render() {
    if (this._el) this._el.remove();

    const { classes, matrix, packages } = this._data;
    const n = classes.length;
    if (n === 0) return;

    // Find max value for color scaling
    let maxVal = 1;
    for (let i = 0; i < n; i++)
      for (let j = 0; j < n; j++)
        if (matrix[i][j] > maxVal) maxVal = matrix[i][j];

    // Detect cycles: cells where both (i,j) and (j,i) are non-zero and i !== j
    const cycles = new Set();
    for (let i = 0; i < n; i++)
      for (let j = i + 1; j < n; j++)
        if (matrix[i][j] > 0 && matrix[j][i] > 0) {
          cycles.add(`${i},${j}`);
          cycles.add(`${j},${i}`);
        }

    // Build package groupings for row separators
    const pkgList = [];
    let lastPkg = null;
    for (let i = 0; i < n; i++) {
      const pkg = packages[classes[i]] || '(default)';
      if (pkg !== lastPkg) {
        pkgList.push({ pkg, startIndex: i });
        lastPkg = pkg;
      }
    }

    // Wrapper
    const wrap = document.createElement('div');
    wrap.className = 'dsm-container';
    wrap.setAttribute('role', 'region');
    wrap.setAttribute('aria-label', 'Dependency Structure Matrix');

    // Stats bar
    const stats = document.createElement('div');
    stats.className = 'dsm-stats';
    const totalDeps = matrix.flat().reduce((s, v) => s + (v > 0 ? 1 : 0), 0) - n; // exclude diagonal
    const cycleCount = cycles.size / 2;
    stats.innerHTML = `
      <span class="dsm-stat"><span class="dsm-stat-num">${n}</span> Classes</span>
      <span class="dsm-stat"><span class="dsm-stat-num">${totalDeps < 0 ? 0 : totalDeps}</span> Dependencies</span>
      <span class="dsm-stat ${cycleCount > 0 ? 'dsm-stat-warn' : ''}"><span class="dsm-stat-num">${cycleCount}</span> Cycles</span>
    `;
    wrap.appendChild(stats);

    // Scrollable matrix area
    const scroll = document.createElement('div');
    scroll.className = 'dsm-scroll';

    const table = document.createElement('table');
    table.className = 'dsm-table';

    // Build short labels
    const shortLabels = classes.map(c => {
      const dot = c.lastIndexOf('.');
      return dot >= 0 ? c.substring(dot + 1) : c;
    });

    // Header row with rotated column labels
    const thead = document.createElement('thead');
    const headerRow = document.createElement('tr');
    headerRow.appendChild(document.createElement('th')); // corner cell
    for (let j = 0; j < n; j++) {
      const th = document.createElement('th');
      th.className = 'dsm-col-header';
      th.dataset.col = j;
      const span = document.createElement('span');
      span.className = 'dsm-col-label';
      span.textContent = shortLabels[j];
      span.title = classes[j];
      th.appendChild(span);
      headerRow.appendChild(th);
    }
    thead.appendChild(headerRow);
    table.appendChild(thead);

    // Body rows
    const tbody = document.createElement('tbody');
    let prevPkg = null;
    for (let i = 0; i < n; i++) {
      const pkg = packages[classes[i]] || '(default)';

      // Package separator row
      if (pkg !== prevPkg && i > 0) {
        const sepRow = document.createElement('tr');
        sepRow.className = 'dsm-pkg-separator';
        const sepCell = document.createElement('td');
        sepCell.colSpan = n + 1;
        sepRow.appendChild(sepCell);
        tbody.appendChild(sepRow);
      }
      prevPkg = pkg;

      const tr = document.createElement('tr');
      tr.dataset.row = i;

      // Row header
      const rowHeader = document.createElement('td');
      rowHeader.className = 'dsm-row-header';
      rowHeader.textContent = shortLabels[i];
      rowHeader.title = classes[i];
      tr.appendChild(rowHeader);

      // Data cells
      for (let j = 0; j < n; j++) {
        const td = document.createElement('td');
        td.className = 'dsm-cell';
        td.dataset.row = i;
        td.dataset.col = j;

        const val = matrix[i][j];

        if (i === j) {
          td.classList.add('dsm-diagonal');
        } else if (val > 0) {
          const intensity = Math.min(val / maxVal, 1);
          const alpha = 0.15 + intensity * 0.75;

          if (cycles.has(`${i},${j}`)) {
            td.classList.add('dsm-cycle');
            td.style.backgroundColor = `rgba(239, 68, 68, ${alpha})`;
          } else {
            td.style.backgroundColor = `rgba(59, 130, 246, ${alpha})`;
          }
          td.textContent = val;
          td.title = `${classes[i]} -> ${classes[j]}: ${val} call${val > 1 ? 's' : ''}`;
        }

        tr.appendChild(td);
      }

      tbody.appendChild(tr);
    }
    table.appendChild(tbody);

    // Crosshair hover
    table.addEventListener('mouseover', (e) => {
      const cell = e.target.closest('.dsm-cell');
      if (!cell) return;
      const r = parseInt(cell.dataset.row);
      const c = parseInt(cell.dataset.col);
      this._setCrosshair(table, r, c, n);
    });

    table.addEventListener('mouseleave', () => {
      this._clearCrosshair(table);
    });

    scroll.appendChild(table);
    wrap.appendChild(scroll);

    // Legend
    const legend = document.createElement('div');
    legend.className = 'dsm-legend';
    legend.innerHTML = `
      <span class="dsm-legend-item"><span class="dsm-legend-swatch" style="background:rgba(59,130,246,0.5)"></span>Dependency</span>
      <span class="dsm-legend-item"><span class="dsm-legend-swatch" style="background:rgba(239,68,68,0.5)"></span>Cycle</span>
      <span class="dsm-legend-item"><span class="dsm-legend-swatch dsm-legend-diag"></span>Self (diagonal)</span>
    `;
    wrap.appendChild(legend);

    this._container.appendChild(wrap);
    this._el = wrap;
  }

  _setCrosshair(table, row, col, n) {
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
}

window.DSMRenderer = DSMRenderer;
