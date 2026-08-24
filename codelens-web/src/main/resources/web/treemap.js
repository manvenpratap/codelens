/**
 * treemap.js - Zoomable Squarified Treemap Renderer
 *
 * Renders a hierarchical Package > Class > Method treemap on canvas.
 * Rectangle size = lines of code. Color = cyclomatic complexity.
 * Click to zoom into a subtree; breadcrumb trail for navigation.
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
    this._hovered = null;
    this._tooltip = null;
    this._dpr = window.devicePixelRatio || 1;
    this._animProgress = 1;
    this._animFrom = null;
    this._animFrame = null;
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
    if (this._animFrame) cancelAnimationFrame(this._animFrame);
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
    this._squarify(this._current.children || [], 0, 0, w, h, 0);
    this._renderBreadcrumb();
  }

  /** Squarified treemap algorithm. */
  _squarify(nodes, x, y, w, h, depth) {
    if (!nodes || nodes.length === 0 || w <= 0 || h <= 0) return;

    const totalSize = nodes.reduce((s, n) => s + Math.max(n.size, 1), 0);
    if (totalSize <= 0) return;

    // Sort descending by size
    const sorted = [...nodes].sort((a, b) => b.size - a.size);

    let cx = x, cy = y, cw = w, ch = h;

    let i = 0;
    while (i < sorted.length) {
      const isVertical = ch > cw;
      const side = isVertical ? ch : cw;
      const remaining = sorted.slice(i).reduce((s, n) => s + Math.max(n.size, 1), 0);

      // Find the best row
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

      // Lay out the row
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

        const padding = 2;
        const rect = {
          x: rx + padding, y: ry + padding,
          w: Math.max(rw - padding * 2, 0), h: Math.max(rh - padding * 2, 0),
          node: item, depth
        };
        this._rects.push(rect);

        i++;
      }

      // Shrink remaining area
      if (isVertical) {
        cy += rowSpan;
        ch -= rowSpan;
      } else {
        cx += rowSpan;
        cw -= rowSpan;
      }
    }
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

    for (const rect of this._rects) {
      if (rect.w < 1 || rect.h < 1) continue;

      const node = rect.node;
      const color = this._complexityColor(node.complexity || 0);
      const isHovered = this._hovered === rect;

      // Fill
      ctx.fillStyle = color;
      ctx.globalAlpha = isHovered ? 1.0 : 0.75;
      ctx.beginPath();
      this._roundRect(ctx, rect.x, rect.y, rect.w, rect.h, 3);
      ctx.fill();

      // Border
      ctx.globalAlpha = 1;
      ctx.strokeStyle = isHovered ? '#f8fafc' : 'rgba(0,0,0,0.3)';
      ctx.lineWidth = isHovered ? 2 : 0.5;
      ctx.stroke();

      // Label (only if rect is big enough)
      if (rect.w > 40 && rect.h > 16) {
        ctx.fillStyle = '#f8fafc';
        ctx.globalAlpha = 1;
        const fontSize = Math.max(9, Math.min(14, rect.w / 8));
        ctx.font = `500 ${fontSize}px "Plus Jakarta Sans", system-ui, sans-serif`;
        ctx.textBaseline = 'top';
        ctx.textAlign = 'left';

        const label = node.name || '';
        const maxWidth = rect.w - 8;
        let displayLabel = label;
        if (ctx.measureText(label).width > maxWidth) {
          while (displayLabel.length > 2 && ctx.measureText(displayLabel + '...').width > maxWidth) {
            displayLabel = displayLabel.slice(0, -1);
          }
          displayLabel += '...';
        }
        ctx.fillText(displayLabel, rect.x + 4, rect.y + 4);

        // Size indicator
        if (rect.h > 32 && rect.w > 50) {
          ctx.fillStyle = 'rgba(248,250,252,0.6)';
          ctx.font = `400 ${Math.max(8, fontSize - 2)}px "JetBrains Mono", monospace`;
          ctx.fillText(`${node.size} loc`, rect.x + 4, rect.y + 4 + fontSize + 2);
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

  _onMouseMove(e) {
    const rect = this._canvas.getBoundingClientRect();
    const mx = e.clientX - rect.left;
    const my = e.clientY - rect.top;

    let found = null;
    // Iterate in reverse to find topmost (last drawn)
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
        <div class="treemap-tip-row"><span>Size:</span> <span>${node.size} lines</span></div>
        <div class="treemap-tip-row"><span>Complexity:</span> <span>${node.complexity || '-'}</span></div>
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
      btn.textContent = node === this._root ? 'Root' : (node.name || '?');
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
    el.textContent = s;
    return el.innerHTML;
  }
}

window.TreemapRenderer = TreemapRenderer;
