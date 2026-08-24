/**
 * treemap.js - Nested Squarified Treemap Renderer
 *
 * Renders a nested hierarchical Package > Class > Method treemap.
 * - Packages render as container frames with header bars.
 * - Classes render as complexity-colored tiles inside package containers.
 * - Methods render inside classes or when zoomed into a class.
 * - Zoom into any package or class by clicking; navigate with breadcrumb bar.
 */

class TreemapRenderer {
  constructor(container) {
    this._container = container;
    this._el = null;
    this._canvas = null;
    this._ctx = null;
    this._data = null;
    this._root = null;
    this._current = null;
    this._breadcrumb = [];
    this._rects = [];
    this._containers = [];
    this._hovered = null;
    this._tooltip = null;
    this._dpr = window.devicePixelRatio || 1;
    this._bound = {
      onMouseMove: this._onMouseMove.bind(this),
      onMouseLeave: this._onMouseLeave.bind(this),
      onClick: this._onClick.bind(this),
      onResize: this._onResize.bind(this),
    };
  }

  setData(payload) {
    this._data = payload;
    this._root = payload;
    this._current = payload;
    this._breadcrumb = [payload];
    this._mount();
    this._layout();
    this._draw();
  }

  destroy() {
    if (this._canvas) {
      this._canvas.removeEventListener('mousemove', this._bound.onMouseMove);
      this._canvas.removeEventListener('mouseleave', this._bound.onMouseLeave);
      this._canvas.removeEventListener('click', this._bound.onClick);
    }
    window.removeEventListener('resize', this._bound.onResize);
    if (this._el) { this._el.remove(); this._el = null; }
  }

  _mount() {
    if (this._el) this._el.remove();

    const wrap = document.createElement('div');
    wrap.className = 'treemap-container';

    // Breadcrumb
    const bc = document.createElement('div');
    bc.className = 'treemap-breadcrumb';
    bc.id = 'treemap-breadcrumb';
    wrap.appendChild(bc);

    // Canvas
    const canvas = document.createElement('canvas');
    canvas.className = 'treemap-canvas';
    wrap.appendChild(canvas);

    // Tooltip
    const tooltip = document.createElement('div');
    tooltip.className = 'treemap-tooltip';
    tooltip.style.display = 'none';
    wrap.appendChild(tooltip);

    this._container.appendChild(wrap);
    this._el = wrap;
    this._canvas = canvas;
    this._ctx = canvas.getContext('2d');
    this._tooltip = tooltip;

    canvas.addEventListener('mousemove', this._bound.onMouseMove);
    canvas.addEventListener('mouseleave', this._bound.onMouseLeave);
    canvas.addEventListener('click', this._bound.onClick);
    window.addEventListener('resize', this._bound.onResize);

    this._onResize();
  }

  _onResize() {
    if (!this._canvas || !this._el) return;
    const w = this._el.clientWidth;
    const h = this._el.clientHeight - 36; // breadcrumb height
    this._canvas.width = w * this._dpr;
    this._canvas.height = Math.max(h, 200) * this._dpr;
    this._canvas.style.width = w + 'px';
    this._canvas.style.height = Math.max(h, 200) + 'px';
    this._layout();
    this._draw();
  }

  _layout() {
    if (!this._current || !this._canvas) return;
    const w = this._canvas.width / this._dpr;
    const h = this._canvas.height / this._dpr;
    this._rects = [];
    this._containers = [];

    const padding = 10;
    const availW = w - padding * 2;
    const availH = h - padding * 2;

    if (this._current === this._root) {
      // Top-level: Packages containing their classes
      const packages = this._root.children || [];
      const pkgRects = this._calcSquarify(packages, padding, padding, availW, availH);

      for (const pr of pkgRects) {
        const pkgNode = pr.node;
        const headerH = 26;
        this._containers.push({
          x: pr.x, y: pr.y, w: pr.w, h: pr.h,
          name: pkgNode.name, size: pkgNode.size, node: pkgNode,
          type: 'package'
        });

        // Inside package: lay out its classes
        const innerX = pr.x + 6;
        const innerY = pr.y + headerH + 2;
        const innerW = pr.w - 12;
        const innerH = pr.h - headerH - 8;

        if (innerW > 10 && innerH > 10 && pkgNode.children && pkgNode.children.length > 0) {
          const classRects = this._calcSquarify(pkgNode.children, innerX, innerY, innerW, innerH);
          for (const cr of classRects) {
            this._rects.push({
              x: cr.x, y: cr.y, w: cr.w, h: cr.h,
              node: cr.node, parentNode: pkgNode, depth: 1, type: 'class'
            });
          }
        }
      }

    } else if (this._current.children && this._current.children.length > 0) {
      const firstChild = this._current.children[0];
      const hasGrandchildren = firstChild.children && firstChild.children.length > 0;

      if (hasGrandchildren) {
        // Viewing a Package: container per Class with Methods inside
        const classRects = this._calcSquarify(this._current.children, padding, padding, availW, availH);
        for (const cr of classRects) {
          const classNode = cr.node;
          const headerH = 24;
          this._containers.push({
            x: cr.x, y: cr.y, w: cr.w, h: cr.h,
            name: classNode.name, size: classNode.size, node: classNode,
            type: 'class'
          });

          const innerX = cr.x + 4;
          const innerY = cr.y + headerH + 2;
          const innerW = cr.w - 8;
          const innerH = cr.h - headerH - 6;

          if (innerW > 10 && innerH > 10 && classNode.children && classNode.children.length > 0) {
            const methodRects = this._calcSquarify(classNode.children, innerX, innerY, innerW, innerH);
            for (const mr of methodRects) {
              this._rects.push({
                x: mr.x, y: mr.y, w: mr.w, h: mr.h,
                node: mr.node, parentNode: classNode, depth: 2, type: 'method'
              });
            }
          } else {
            this._rects.push({
              x: cr.x, y: cr.y, w: cr.w, h: cr.h,
              node: classNode, parentNode: this._current, depth: 1, type: 'class'
            });
          }
        }
      } else {
        // Viewing a Class (showing its Methods directly)
        const methodRects = this._calcSquarify(this._current.children, padding, padding, availW, availH);
        for (const mr of methodRects) {
          this._rects.push({
            x: mr.x, y: mr.y, w: mr.w, h: mr.h,
            node: mr.node, parentNode: this._current, depth: 2, type: 'method'
          });
        }
      }
    }

    this._renderBreadcrumb();
  }

  /** Calculate squarified rect positions for a list of nodes. */
  _calcSquarify(nodes, x, y, w, h) {
    const result = [];
    if (!nodes || nodes.length === 0 || w <= 0 || h <= 0) return result;

    const totalSize = nodes.reduce((s, n) => s + Math.max(n.size, 1), 0);
    if (totalSize <= 0) return result;

    const sorted = [...nodes].sort((a, b) => b.size - a.size);

    let cx = x, cy = y, cw = w, ch = h;
    let i = 0;

    while (i < sorted.length) {
      const isVertical = ch > cw;
      const side = isVertical ? ch : cw;
      const remaining = sorted.slice(i).reduce((s, n) => s + Math.max(n.size, 1), 0);

      let row = [];
      let rowSize = 0;
      let bestAspect = Infinity;

      for (let j = i; j < sorted.length; j++) {
        const nodeSize = Math.max(sorted[j].size, 1);
        const testRow = [...row, sorted[j]];
        const testSize = rowSize + nodeSize;

        const rowFraction = testSize / remaining;
        const rowSpan = side * rowFraction;

        let worstAspect = 0;
        for (const item of testRow) {
          const itemSize = Math.max(item.size, 1);
          const itemFraction = itemSize / testSize;
          const itemDim = (isVertical ? cw : ch) * itemFraction;
          const aspect = Math.max(rowSpan / Math.max(itemDim, 0.01), Math.max(itemDim, 0.01) / Math.max(rowSpan, 0.01));
          worstAspect = Math.max(worstAspect, aspect);
        }

        if (worstAspect <= bestAspect) {
          bestAspect = worstAspect;
          row = testRow;
          rowSize = testSize;
        } else {
          break;
        }
      }

      const rowFraction = rowSize / remaining;
      const rowSpan = side * rowFraction;
      let offset = 0;

      for (const item of row) {
        const itemSize = Math.max(item.size, 1);
        const itemFraction = itemSize / rowSize;

        let rx, ry, rw, rh;
        if (isVertical) {
          rw = cw * itemFraction;
          rh = rowSpan;
          rx = cx + offset;
          ry = cy;
          offset += rw;
        } else {
          rw = rowSpan;
          rh = ch * itemFraction;
          rx = cx;
          ry = cy + offset;
          offset += rh;
        }

        const pad = 2;
        result.push({
          x: rx + pad, y: ry + pad,
          w: Math.max(rw - pad * 2, 0), h: Math.max(rh - pad * 2, 0),
          node: item
        });
        i++;
      }

      if (isVertical) {
        cy += rowSpan;
        ch -= rowSpan;
      } else {
        cx += rowSpan;
        cw -= rowSpan;
      }
    }

    return result;
  }

  _complexityColor(complexity) {
    if (complexity <= 1) return '#2563eb';       // blue - simple
    if (complexity <= 3) return '#10b981';       // green - low
    if (complexity <= 6) return '#f59e0b';       // amber - moderate
    if (complexity <= 10) return '#f97316';      // orange - high
    return '#ef4444';                             // red - very high
  }

  _draw() {
    if (!this._ctx || !this._canvas) return;
    const ctx = this._ctx;
    const dpr = this._dpr;
    const w = this._canvas.width;
    const h = this._canvas.height;

    ctx.save();
    ctx.scale(dpr, dpr);
    ctx.clearRect(0, 0, w / dpr, h / dpr);

    // 1. Draw container frames (packages or classes)
    for (const c of this._containers) {
      if (c.w < 2 || c.h < 2) continue;

      // Container background
      ctx.fillStyle = 'rgba(15, 23, 42, 0.65)';
      ctx.beginPath();
      this._roundRect(ctx, c.x, c.y, c.w, c.h, 6);
      ctx.fill();

      // Container border
      ctx.strokeStyle = 'rgba(255, 255, 255, 0.12)';
      ctx.lineWidth = 1;
      ctx.stroke();

      // Container header bar
      ctx.fillStyle = 'rgba(30, 41, 59, 0.85)';
      ctx.beginPath();
      this._roundRectTop(ctx, c.x, c.y, c.w, 24, 6);
      ctx.fill();
      ctx.strokeStyle = 'rgba(255, 255, 255, 0.08)';
      ctx.stroke();

      // Header label
      ctx.fillStyle = '#94a3b8';
      ctx.font = '600 11px "Plus Jakarta Sans", system-ui, sans-serif';
      ctx.textBaseline = 'middle';
      ctx.textAlign = 'left';

      const icon = c.type === 'package' ? '📦 ' : '🏛️ ';
      const label = icon + (c.name || '');
      const maxW = c.w - 70;
      let displayLabel = label;
      if (ctx.measureText(label).width > maxW && maxW > 20) {
        while (displayLabel.length > 3 && ctx.measureText(displayLabel + '...').width > maxW) {
          displayLabel = displayLabel.slice(0, -1);
        }
        displayLabel += '...';
      }
      if (maxW > 10) {
        ctx.fillText(displayLabel, c.x + 8, c.y + 12);
      }

      // Total LOC badge
      if (c.w > 120) {
        ctx.fillStyle = 'rgba(148, 163, 184, 0.7)';
        ctx.font = '400 10px "JetBrains Mono", monospace';
        ctx.textAlign = 'right';
        ctx.fillText(`${c.size} loc`, c.x + c.w - 8, c.y + 12);
      }
    }

    // 2. Draw leaf item tiles (classes or methods)
    for (const rect of this._rects) {
      if (rect.w < 2 || rect.h < 2) continue;

      const node = rect.node;
      const color = this._complexityColor(node.complexity || 0);
      const isHovered = this._hovered === rect;

      // Fill
      ctx.fillStyle = color;
      ctx.globalAlpha = isHovered ? 1.0 : 0.85;
      ctx.beginPath();
      this._roundRect(ctx, rect.x, rect.y, rect.w, rect.h, 4);
      ctx.fill();

      // Border
      ctx.globalAlpha = 1;
      ctx.strokeStyle = isHovered ? '#ffffff' : 'rgba(0, 0, 0, 0.35)';
      ctx.lineWidth = isHovered ? 2 : 0.75;
      ctx.stroke();

      // Label (only if rect is big enough)
      if (rect.w > 36 && rect.h > 18) {
        ctx.fillStyle = '#ffffff';
        ctx.globalAlpha = 1;
        const fontSize = Math.max(10, Math.min(13, rect.w / 9));
        ctx.font = `600 ${fontSize}px "Plus Jakarta Sans", system-ui, sans-serif`;
        ctx.textBaseline = 'top';
        ctx.textAlign = 'left';

        const label = node.name || '';
        const maxWidth = rect.w - 10;
        let displayLabel = label;
        if (ctx.measureText(label).width > maxWidth) {
          while (displayLabel.length > 2 && ctx.measureText(displayLabel + '...').width > maxWidth) {
            displayLabel = displayLabel.slice(0, -1);
          }
          displayLabel += '...';
        }
        ctx.fillText(displayLabel, rect.x + 6, rect.y + 6);

        // Size and complexity line
        if (rect.h > 36 && rect.w > 50) {
          ctx.fillStyle = 'rgba(255, 255, 255, 0.75)';
          ctx.font = `400 ${Math.max(9, fontSize - 2)}px "JetBrains Mono", monospace`;
          ctx.fillText(`${node.size} loc • C:${node.complexity || 1}`, rect.x + 6, rect.y + 6 + fontSize + 3);
        }
      }
    }

    ctx.restore();
  }

  _roundRect(ctx, x, y, w, h, r) {
    r = Math.min(r, w / 2, h / 2);
    ctx.moveTo(x + r, y);
    ctx.lineTo(x + w - r, y);
    ctx.quadraticCurveTo(x + w, y, x + w, y + r);
    ctx.lineTo(x + w, y + h - r);
    ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
    ctx.lineTo(x + r, y + h);
    ctx.quadraticCurveTo(x, y + h, x, y + h - r);
    ctx.lineTo(x, y + r);
    ctx.quadraticCurveTo(x, y, x + r, y);
    ctx.closePath();
  }

  _roundRectTop(ctx, x, y, w, h, r) {
    r = Math.min(r, w / 2, h);
    ctx.moveTo(x + r, y);
    ctx.lineTo(x + w - r, y);
    ctx.quadraticCurveTo(x + w, y, x + w, y + r);
    ctx.lineTo(x + w, y + h);
    ctx.lineTo(x, y + h);
    ctx.lineTo(x, y + r);
    ctx.quadraticCurveTo(x, y, x + r, y);
    ctx.closePath();
  }

  _onMouseMove(e) {
    const rect = this._canvas.getBoundingClientRect();
    const mx = e.clientX - rect.left;
    const my = e.clientY - rect.top;

    let found = null;
    // Check innermost rects first (reverse order)
    for (let i = this._rects.length - 1; i >= 0; i--) {
      const r = this._rects[i];
      if (mx >= r.x && mx <= r.x + r.w && my >= r.y && my <= r.y + r.h) {
        found = r;
        break;
      }
    }

    if (found !== this._hovered) {
      this._hovered = found;
      this._draw();
    }

    if (found) {
      const node = found.node;
      this._tooltip.style.display = 'block';
      this._tooltip.style.left = (e.clientX - this._container.getBoundingClientRect().left + 12) + 'px';
      this._tooltip.style.top = (e.clientY - this._container.getBoundingClientRect().top - 10) + 'px';
      const hasChildren = node.children && node.children.length > 0;
      this._tooltip.innerHTML = `
        <div class="treemap-tip-name">${this._escHtml(node.name)}</div>
        <div class="treemap-tip-row"><span>Type:</span> <span>${found.type ? found.type.toUpperCase() : 'NODE'}</span></div>
        <div class="treemap-tip-row"><span>Size:</span> <span>${node.size} lines</span></div>
        <div class="treemap-tip-row"><span>Complexity:</span> <span>${node.complexity || 1}</span></div>
        ${node.fqn ? `<div class="treemap-tip-row"><span>FQN:</span> <span class="treemap-tip-mono">${this._escHtml(node.fqn)}</span></div>` : ''}
        ${hasChildren ? '<div class="treemap-tip-hint">Click to zoom in</div>' : ''}
      `;
      this._canvas.style.cursor = hasChildren ? 'pointer' : 'default';
    } else {
      this._tooltip.style.display = 'none';
      this._canvas.style.cursor = 'default';
    }
  }

  _onMouseLeave() {
    this._hovered = null;
    this._tooltip.style.display = 'none';
    this._draw();
  }

  _onClick() {
    if (!this._hovered) return;
    const node = this._hovered.node;
    if (node.children && node.children.length > 0) {
      this._current = node;
      this._breadcrumb.push(node);
      this._hovered = null;
      this._tooltip.style.display = 'none';
      this._layout();
      this._draw();
    }
  }

  _renderBreadcrumb() {
    const bc = this._el.querySelector('#treemap-breadcrumb');
    if (!bc) return;
    bc.innerHTML = '';

    this._breadcrumb.forEach((node, i) => {
      if (i > 0) {
        const sep = document.createElement('span');
        sep.className = 'treemap-bc-sep';
        sep.textContent = '/';
        bc.appendChild(sep);
      }

      const btn = document.createElement('button');
      btn.className = 'treemap-bc-btn';
      btn.textContent = node === this._root ? 'Root (All Packages)' : (node.name || '?');
      if (i === this._breadcrumb.length - 1) {
        btn.classList.add('active');
      } else {
        btn.addEventListener('click', () => {
          this._current = node;
          this._breadcrumb = this._breadcrumb.slice(0, i + 1);
          this._hovered = null;
          this._tooltip.style.display = 'none';
          this._layout();
          this._draw();
        });
      }
      bc.appendChild(btn);
    });
  }

  _escHtml(s) {
    const el = document.createElement('span');
    el.textContent = s || '';
    return el.innerHTML;
  }
}

window.TreemapRenderer = TreemapRenderer;
