/**
 * sunburst.js - Sunburst / Radial Hierarchy Renderer
 *
 * Renders Package > Class > Method as concentric ring segments.
 * Arc angle = proportion of total lines of code.
 * Color = cyclomatic complexity.
 * Click a segment to zoom in (it becomes the center).
 * Reuses the /api/graph/treemap hierarchical data.
 */

class SunburstRenderer {
  constructor(container) {
    this._container = container;
    this._el = null;
    this._canvas = null;
    this._ctx = null;
    this._data = null;
    this._root = null;
    this._current = null;
    this._breadcrumb = [];
    this._segments = [];
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
    wrap.className = 'sunburst-container';

    // Breadcrumb
    const bc = document.createElement('div');
    bc.className = 'sunburst-breadcrumb';
    bc.id = 'sunburst-breadcrumb';
    wrap.appendChild(bc);

    // Canvas
    const canvas = document.createElement('canvas');
    canvas.className = 'sunburst-canvas';
    wrap.appendChild(canvas);

    // Tooltip
    const tooltip = document.createElement('div');
    tooltip.className = 'sunburst-tooltip';
    tooltip.style.display = 'none';
    wrap.appendChild(tooltip);

    // Center label
    const center = document.createElement('div');
    center.className = 'sunburst-center';
    center.id = 'sunburst-center-label';
    wrap.appendChild(center);

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
    const h = this._el.clientHeight - 36;
    this._canvas.width = w * this._dpr;
    this._canvas.height = Math.max(h, 200) * this._dpr;
    this._canvas.style.width = w + 'px';
    this._canvas.style.height = Math.max(h, 200) + 'px';
    this._layout();
    this._draw();
  }

  _layout() {
    if (!this._current || !this._canvas) return;
    this._segments = [];

    const w = this._canvas.width / this._dpr;
    const h = this._canvas.height / this._dpr;
    const cx = w / 2;
    const cy = h / 2;
    const maxRadius = Math.min(cx, cy) - 30;
    const innerRadius = maxRadius * 0.18;

    this._cx = cx;
    this._cy = cy;
    this._maxRadius = maxRadius;
    this._innerRadius = innerRadius;

    // Determine max depth from current node
    const maxDepth = this._getMaxDepth(this._current, 0);
    const ringWidth = (maxRadius - innerRadius) / Math.max(maxDepth, 1);
    this._ringWidth = ringWidth;

    // Build segments recursively
    this._buildSegments(this._current, 0, 2 * Math.PI, 0, innerRadius, ringWidth);
    this._renderBreadcrumb();
    this._updateCenterLabel();
  }

  _getMaxDepth(node, depth) {
    if (!node.children || node.children.length === 0) return depth;
    let max = depth;
    for (const child of node.children) {
      max = Math.max(max, this._getMaxDepth(child, depth + 1));
    }
    return max;
  }

  _buildSegments(node, startAngle, endAngle, depth, innerR, ringW) {
    if (!node.children || node.children.length === 0) return;

    const totalSize = node.children.reduce((s, c) => s + Math.max(c.size, 1), 0);
    if (totalSize <= 0) return;

    let angle = startAngle;
    for (const child of node.children) {
      const fraction = Math.max(child.size, 1) / totalSize;
      const childAngle = (endAngle - startAngle) * fraction;
      const gap = 0.005;

      if (childAngle > gap * 2) {
        this._segments.push({
          node: child,
          startAngle: angle + gap,
          endAngle: angle + childAngle - gap,
          innerRadius: innerR,
          outerRadius: innerR + ringW,
          depth,
        });

        // Recurse for children
        if (child.children && child.children.length > 0) {
          this._buildSegments(child, angle + gap, angle + childAngle - gap, depth + 1, innerR + ringW, ringW);
        }
      }

      angle += childAngle;
    }
  }

  _complexityColor(complexity) {
    if (complexity <= 1) return '#2563eb';
    if (complexity <= 3) return '#10b981';
    if (complexity <= 6) return '#f59e0b';
    if (complexity <= 10) return '#f97316';
    return '#ef4444';
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

    const cx = this._cx;
    const cy = this._cy;
    const hovered = this._hovered;

    // Draw center circle
    ctx.beginPath();
    ctx.arc(cx, cy, this._innerRadius, 0, 2 * Math.PI);
    ctx.fillStyle = 'rgba(22, 27, 34, 0.9)';
    ctx.fill();
    ctx.strokeStyle = 'rgba(255,255,255,0.1)';
    ctx.lineWidth = 1;
    ctx.stroke();

    // Draw segments
    for (const seg of this._segments) {
      const node = seg.node;
      const color = this._complexityColor(node.complexity || 0);
      const isHovered = hovered === seg;
      const isAncestor = hovered && this._isAncestor(seg.node, hovered.node);
      const dimmed = hovered && !isHovered && !isAncestor && !this._isAncestor(hovered.node, seg.node);

      ctx.beginPath();
      ctx.arc(cx, cy, seg.outerRadius, seg.startAngle, seg.endAngle);
      ctx.arc(cx, cy, seg.innerRadius, seg.endAngle, seg.startAngle, true);
      ctx.closePath();

      ctx.fillStyle = color;
      ctx.globalAlpha = dimmed ? 0.15 : (isHovered ? 1.0 : 0.7);
      ctx.fill();

      ctx.strokeStyle = isHovered ? '#f8fafc' : 'rgba(13,17,23,0.6)';
      ctx.lineWidth = isHovered ? 2 : 0.5;
      ctx.globalAlpha = 1;
      ctx.stroke();

      // Label (only for segments big enough)
      const arcAngle = seg.endAngle - seg.startAngle;
      const arcLength = arcAngle * (seg.innerRadius + seg.outerRadius) / 2;
      const ringH = seg.outerRadius - seg.innerRadius;

      if (arcLength > 35 && ringH > 12 && !dimmed) {
        const midAngle = (seg.startAngle + seg.endAngle) / 2;
        const midR = (seg.innerRadius + seg.outerRadius) / 2;
        const lx = cx + Math.cos(midAngle) * midR;
        const ly = cy + Math.sin(midAngle) * midR;

        ctx.save();
        ctx.translate(lx, ly);
        let rotation = midAngle;
        if (midAngle > Math.PI / 2 && midAngle < 3 * Math.PI / 2) {
          rotation += Math.PI;
        }
        ctx.rotate(rotation);

        const fontSize = Math.max(8, Math.min(11, ringH * 0.6));
        ctx.font = `500 ${fontSize}px "Plus Jakarta Sans", system-ui, sans-serif`;
        ctx.fillStyle = '#f8fafc';
        ctx.globalAlpha = dimmed ? 0.2 : 0.9;
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';

        let label = node.name || '';
        const maxWidth = arcLength * 0.8;
        if (ctx.measureText(label).width > maxWidth) {
          while (label.length > 2 && ctx.measureText(label + '..').width > maxWidth) {
            label = label.slice(0, -1);
          }
          label += '..';
        }
        ctx.fillText(label, 0, 0);
        ctx.restore();
      }
    }

    ctx.restore();
  }

  _isAncestor(potentialAncestor, node) {
    // Simple check via FQN prefix
    if (!potentialAncestor || !node) return false;
    if (potentialAncestor === node) return true;
    if (potentialAncestor.children) {
      for (const child of potentialAncestor.children) {
        if (this._isAncestor(child, node)) return true;
      }
    }
    return false;
  }

  _onMouseMove(e) {
    const rect = this._canvas.getBoundingClientRect();
    const mx = e.clientX - rect.left;
    const my = e.clientY - rect.top;

    const cx = this._cx;
    const cy = this._cy;
    const dx = mx - cx;
    const dy = my - cy;
    const dist = Math.sqrt(dx * dx + dy * dy);
    let angle = Math.atan2(dy, dx);
    if (angle < 0) angle += 2 * Math.PI;

    let found = null;
    // Iterate in reverse to find innermost matching segment (most specific)
    for (let i = this._segments.length - 1; i >= 0; i--) {
      const seg = this._segments[i];
      if (dist >= seg.innerRadius && dist <= seg.outerRadius &&
          angle >= seg.startAngle && angle <= seg.endAngle) {
        found = seg;
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
        <div class="sunburst-tip-name">${this._escHtml(node.name)}</div>
        <div class="sunburst-tip-row"><span>Size:</span> <span>${node.size} lines</span></div>
        <div class="sunburst-tip-row"><span>Complexity:</span> <span>${node.complexity || '-'}</span></div>
        ${node.fqn ? `<div class="sunburst-tip-row"><span>FQN:</span> <span class="sunburst-tip-mono">${this._escHtml(node.fqn)}</span></div>` : ''}
        ${hasChildren ? '<div class="sunburst-tip-hint">Click to zoom in</div>' : ''}
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
    const bc = this._el.querySelector('#sunburst-breadcrumb');
    if (!bc) return;
    bc.innerHTML = '';

    this._breadcrumb.forEach((node, i) => {
      if (i > 0) {
        const sep = document.createElement('span');
        sep.className = 'sunburst-bc-sep';
        sep.textContent = '/';
        bc.appendChild(sep);
      }

      const btn = document.createElement('button');
      btn.className = 'sunburst-bc-btn';
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

  _updateCenterLabel() {
    const label = this._el.querySelector('#sunburst-center-label');
    if (!label) return;
    const node = this._current;
    const name = node === this._root ? 'Codebase' : (node.name || '?');
    const totalSize = node.size || 0;
    label.innerHTML = `<div class="sunburst-center-name">${this._escHtml(name)}</div><div class="sunburst-center-size">${totalSize} lines</div>`;

    // Position center label over the center of the canvas
    const w = this._canvas.width / this._dpr;
    const h = this._canvas.height / this._dpr;
    label.style.left = (w / 2) + 'px';
    label.style.top = (36 + h / 2) + 'px'; // 36 = breadcrumb height offset
  }

  _escHtml(s) {
    const el = document.createElement('span');
    el.textContent = s || '';
    return el.innerHTML;
  }
}

window.SunburstRenderer = SunburstRenderer;
