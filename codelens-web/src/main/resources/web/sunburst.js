/**
 * sunburst.js - Sunburst / Radial Hierarchy Renderer
 *
 * Renders Package > Class > Method as concentric ring segments.
 * Arc angle = proportion of total lines of code.
 * Color = cohesive hierarchy palette.
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
    this._hiddenPackages = new Set();
    this._hiddenEntities = new Set();
    this._archetypeFilter = 'ALL';
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

  setArchetypeFilter(ruleId) {
    this._archetypeFilter = ruleId || 'ALL';
    this._draw();
  }

  togglePackage(pkgName, visible) {
    if (visible === undefined) {
      if (this._hiddenPackages.has(pkgName)) this._hiddenPackages.delete(pkgName);
      else this._hiddenPackages.add(pkgName);
    } else if (visible) {
      this._hiddenPackages.delete(pkgName);
    } else {
      this._hiddenPackages.add(pkgName);
    }
    this._layout();
    this._draw();
  }

  toggleEntity(entityIdOrName, visible, fqn = null, pkg = null) {
    const ids = [entityIdOrName, fqn].filter(Boolean);
    ids.forEach(id => {
      if (visible === undefined) {
        if (this._hiddenEntities.has(id)) this._hiddenEntities.delete(id);
        else this._hiddenEntities.add(id);
      } else if (visible) {
        this._hiddenEntities.delete(id);
      } else {
        this._hiddenEntities.add(id);
      }
    });
    this._layout();
    this._draw();
  }

  _isItemHidden(node, parentPkg) {
    if (!node) return false;
    const name = node.name || node.label || '';
    const fqn = node.fqn || (parentPkg ? `${parentPkg}.${name}` : name);
    const pkg = node.package || parentPkg || (fqn.includes('.') ? fqn.split('.').slice(0, -1).join('.') : '');

    if (pkg && this._hiddenPackages.has(pkg)) return true;
    if (name && this._hiddenEntities.has(name)) return true;
    if (fqn && this._hiddenEntities.has(fqn)) return true;
    if (node.id && this._hiddenEntities.has(node.id)) return true;
    return false;
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
    wrap.setAttribute('role', 'img');
    wrap.setAttribute('aria-label', 'Codebase Hierarchy Sunburst Visualizer');
    wrap.setAttribute('tabindex', '0');

    // Header toolbar with breadcrumb & reset button
    const toolbar = document.createElement('div');
    toolbar.className = 'sunburst-toolbar';

    const bc = document.createElement('div');
    bc.className = 'sunburst-breadcrumb';
    bc.id = 'sunburst-breadcrumb';
    toolbar.appendChild(bc);

    const rightGroup = document.createElement('div');
    rightGroup.className = 'sunburst-toolbar-right';
    rightGroup.innerHTML = `
      <button class="sunburst-reset-btn" id="sunburst-root-btn" title="Reset view to top-level codebase">
        <svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/></svg>
        <span>Reset View</span>
      </button>
    `;
    toolbar.appendChild(rightGroup);
    wrap.appendChild(toolbar);

    // Canvas
    const canvas = document.createElement('canvas');
    canvas.className = 'sunburst-canvas';
    wrap.appendChild(canvas);

    // Tooltip
    const tooltip = document.createElement('div');
    tooltip.className = 'sunburst-tooltip';
    tooltip.style.display = 'none';
    wrap.appendChild(tooltip);

    // Center interactive label overlay
    const center = document.createElement('div');
    center.className = 'sunburst-center';
    center.id = 'sunburst-center-label';
    center.title = 'Click to zoom out';
    center.addEventListener('click', () => {
      if (this._breadcrumb.length > 1) {
        const parent = this._breadcrumb[this._breadcrumb.length - 2];
        this._triggerTransition(parent, this._breadcrumb.slice(0, -1));
      }
    });
    wrap.appendChild(center);

    const rootBtn = rightGroup.querySelector('#sunburst-root-btn');
    if (rootBtn) {
      rootBtn.addEventListener('click', () => {
        if (this._current !== this._root) {
          this._triggerTransition(this._root, [this._root]);
        }
      });
    }

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
    const h = this._el.clientHeight - 38; // 38px toolbar offset
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
    const maxRadius = Math.min(cx, cy) - 24;
    const innerRadius = maxRadius * 0.22;

    this._cx = cx;
    this._cy = cy;
    this._maxRadius = maxRadius;
    this._innerRadius = innerRadius;

    // Determine max depth from current node
    const maxDepth = Math.max(this._getMaxDepth(this._current, 0), 1);
    const ringWidth = (maxRadius - innerRadius) / maxDepth;
    this._ringWidth = ringWidth;

    // Build segments recursively
    this._buildSegments(this._current, 0, 2 * Math.PI, 0, innerRadius, ringWidth, null, 0);
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

  _buildSegments(node, startAngle, endAngle, depth, innerR, ringW, parentColor, childIndex) {
    if (!node.children || node.children.length === 0) return;

    const activeChildren = node.children.filter(c => !this._isItemHidden(c, node.fqn || node.name));
    if (activeChildren.length === 0) return;

    const totalSize = activeChildren.reduce((s, c) => s + Math.max(c.size, 1), 0);
    if (totalSize <= 0) return;

    const minThresholdAngle = 0.005; // Group hairline slivers into "Other" rollup
    const prominentChildren = [];
    const smallChildren = [];

    activeChildren.forEach((child) => {
      const fraction = Math.max(child.size, 1) / totalSize;
      const childAngle = (endAngle - startAngle) * fraction;
      if (childAngle >= minThresholdAngle || activeChildren.length <= 10) {
        prominentChildren.push(child);
      } else {
        smallChildren.push(child);
      }
    });

    const finalChildren = [...prominentChildren];
    if (smallChildren.length > 0) {
      const smallSizeSum = smallChildren.reduce((s, c) => s + Math.max(c.size, 1), 0);
      const otherNode = {
        name: `+${smallChildren.length} more items`,
        simpleName: `+${smallChildren.length} more`,
        size: smallSizeSum,
        complexity: 1,
        kind: 'OTHER',
        children: smallChildren
      };
      finalChildren.push(otherNode);
    }

    let angle = startAngle;
    finalChildren.forEach((child, idx) => {
      const fraction = Math.max(child.size, 1) / totalSize;
      const childAngle = (endAngle - startAngle) * fraction;
      const gap = 0.003;

      let segColor;
      if (child.kind === 'OTHER') {
        segColor = '#64748b'; // subtle slate
      } else if (depth === 0) {
        segColor = (window.CodeLensPalette && window.CodeLensPalette.getColor)
          ? window.CodeLensPalette.getColor(child.fqn || child.name, idx)
          : '#3b82f6';
      } else if (depth === 1) {
        segColor = (window.CodeLensPalette && window.CodeLensPalette.getClassColor)
          ? window.CodeLensPalette.getClassColor(child.fqn || child.name, 'CLASS', idx)
          : ((window.CodeLensPalette && window.CodeLensPalette.tintColor)
              ? window.CodeLensPalette.tintColor(parentColor || '#3b82f6', idx)
              : this._tintColor(parentColor || '#3b82f6', idx));
      } else {
        segColor = (window.CodeLensPalette && window.CodeLensPalette.tintColor)
          ? window.CodeLensPalette.tintColor(parentColor || '#3b82f6', idx)
          : this._tintColor(parentColor || '#3b82f6', idx);
      }

      if (childAngle > gap * 1.5) {
        this._segments.push({
          node: child,
          startAngle: angle + gap,
          endAngle: angle + childAngle - gap,
          innerRadius: innerR,
          outerRadius: innerR + ringW,
          depth,
          color: segColor,
          parentTotalSize: totalSize,
        });

        // Recurse for children
        if (child.kind !== 'OTHER' && child.children && child.children.length > 0) {
          this._buildSegments(child, angle + gap, angle + childAngle - gap, depth + 1, innerR + ringW, ringW, segColor, idx);
        }
      }

      angle += childAngle;
    });
  }

  _tintColor(hex, index) {
    if (!hex || !hex.startsWith('#')) return hex || '#3b82f6';
    const c = parseInt(hex.replace('#', ''), 16);
    const r = (c >> 16) & 255;
    const g = (c >> 8) & 255;
    const b = c & 255;

    const rNorm = r / 255, gNorm = g / 255, bNorm = b / 255;
    const max = Math.max(rNorm, gNorm, bNorm), min = Math.min(rNorm, gNorm, bNorm);
    let h = 0, s = 0, l = (max + min) / 2;

    if (max !== min) {
      const d = max - min;
      s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
      switch (max) {
        case rNorm: h = (gNorm - bNorm) / d + (gNorm < bNorm ? 6 : 0); break;
        case gNorm: h = (bNorm - rNorm) / d + 2; break;
        case bNorm: h = (rNorm - gNorm) / d + 4; break;
      }
      h /= 6;
    }

    const lightnessOffsets = [0.10, -0.08, 0.16, -0.14, 0.05, -0.10, 0.12];
    const lOffset = lightnessOffsets[index % lightnessOffsets.length];
    const newL = Math.max(0.28, Math.min(0.82, l + lOffset));

    return `hsl(${Math.round(h * 360)}, ${Math.round(Math.min(s * 1.1, 1) * 100)}%, ${Math.round(newL * 100)}%)`;
  }

  _triggerTransition(targetNode, newBreadcrumb) {
    this._current = targetNode;
    this._breadcrumb = newBreadcrumb;
    this._hovered = null;
    if (this._tooltip) this._tooltip.style.display = 'none';
    this._layout();

    let startTime = null;
    const duration = 240;
    const animate = (timestamp) => {
      if (!startTime) startTime = timestamp;
      const progress = Math.min((timestamp - startTime) / duration, 1);
      const ease = 1 - Math.pow(1 - progress, 3);
      this._draw(ease);
      if (progress < 1) {
        requestAnimationFrame(animate);
      }
    };
    requestAnimationFrame(animate);
  }

  _draw(transitionProgress = 1) {
    if (!this._ctx || !this._canvas) return;
    const ctx = this._ctx;
    const dpr = this._dpr;
    const w = this._canvas.width;
    const h = this._canvas.height;
    const alphaMult = Math.max(0.05, Math.min(1, transitionProgress));

    ctx.save();
    ctx.scale(dpr, dpr);
    ctx.clearRect(0, 0, w / dpr, h / dpr);

    const cx = this._cx;
    const cy = this._cy;
    const hovered = this._hovered;

    // ── Center circle disc background ─────────────────────────────────────────
    ctx.beginPath();
    ctx.arc(cx, cy, this._innerRadius, 0, 2 * Math.PI);
    ctx.fillStyle = 'rgba(15, 23, 42, 0.95)';
    ctx.globalAlpha = 1.0 * alphaMult;
    ctx.fill();
    ctx.strokeStyle = 'rgba(255, 255, 255, 0.14)';
    ctx.lineWidth = 1.5;
    ctx.stroke();

    // ── Segments rendering ───────────────────────────────────────────────────
    for (const seg of this._segments) {
      const node = seg.node;

      // Ancestor/descendant highlight logic
      let dimmed = false;
      let isHot = false;
      if (hovered) {
        isHot = seg === hovered;
        const hNode = hovered.node;
        const isAnc = this._isAncestor(node, hNode);
        const isDesc = this._isAncestor(hNode, node);
        if (!isHot && !isAnc && !isDesc) {
          dimmed = true;
        }
      }

      if (this._archetypeFilter && this._archetypeFilter !== 'ALL' && window.CodeLensClassifier) {
        const isMethod = node.type === 'method' || (!node.type && (node.complexity !== undefined || !node.children));
        const ok = window.CodeLensClassifier.isMatchArchetype(
          node,
          node.fqn || node.id || node.name,
          node.package || node.packageFqn,
          isMethod,
          this._archetypeFilter
        );
        if (!ok) {
          dimmed = true;
        }
      }

      ctx.beginPath();
      ctx.arc(cx, cy, seg.outerRadius, seg.startAngle, seg.endAngle);
      ctx.arc(cx, cy, seg.innerRadius, seg.endAngle, seg.startAngle, true);
      ctx.closePath();

      ctx.fillStyle = seg.color;
      ctx.globalAlpha = (dimmed ? 0.18 : (isHot ? 1.0 : 0.88)) * alphaMult;
      ctx.fill();

      // Stroke border
      if (isHot) {
        ctx.strokeStyle = '#38bdf8';
        ctx.lineWidth = 2.5;
        ctx.shadowColor = '#0284c7';
        ctx.shadowBlur = 10;
        ctx.stroke();
        ctx.shadowBlur = 0;
      } else {
        ctx.strokeStyle = 'rgba(10, 15, 26, 0.75)';
        ctx.lineWidth = 0.75;
        ctx.stroke();
      }

      // ── Smart Non-Overlapping Label Rendering Engine ──────────────────────────
      const angularSpan = seg.endAngle - seg.startAngle;
      const midR = (seg.innerRadius + seg.outerRadius) / 2;
      const ringH = seg.outerRadius - seg.innerRadius;
      const arcLength = angularSpan * midR;

      if (!dimmed && angularSpan > 0.03) {
        // Derive clean concise display name (never huge unparsed FQN)
        let label = node.simpleName || node.name || '';
        if (label === '<init>') {
          label = 'constructor';
        } else if (label.includes('.') && seg.depth === 0) {
          const parts = label.split('.');
          label = parts.slice(-2).join('.'); // show last two package parts
        } else if (label.includes('.')) {
          label = label.split('.').pop();
        }

        const midAngle = (seg.startAngle + seg.endAngle) / 2;
        const lx = cx + Math.cos(midAngle) * midR;
        const ly = cy + Math.sin(midAngle) * midR;

        // Decide text layout orientation: Tangential (along curve) vs Radial (along ray)
        const isTangential = (arcLength >= ringH * 0.9 && arcLength >= 42 && angularSpan >= 0.07);
        const isRadial = (!isTangential && ringH >= 38 && arcLength >= 14 && angularSpan >= 0.035);

        if (isTangential) {
          ctx.save();
          ctx.translate(lx, ly);

          let rotation = midAngle + Math.PI / 2;
          // Keep text right-side up (flip when in lower hemisphere)
          if (midAngle > 0 && midAngle < Math.PI) {
            rotation -= Math.PI;
          }
          ctx.rotate(rotation);

          const fontSize = Math.max(9, Math.min(12, ringH * 0.52));
          ctx.font = `600 ${fontSize}px "Plus Jakarta Sans", system-ui, sans-serif`;
          ctx.fillStyle = '#ffffff';
          ctx.shadowColor = 'rgba(0, 0, 0, 0.9)';
          ctx.shadowBlur = 4;
          ctx.textAlign = 'center';
          ctx.textBaseline = 'middle';
          ctx.globalAlpha = 0.96 * alphaMult;

          const maxW = arcLength * 0.82;
          let measured = ctx.measureText(label).width;
          if (measured > maxW) {
            while (label.length > 2 && ctx.measureText(label + '..').width > maxW) {
              label = label.slice(0, -1);
            }
            label += '..';
          }

          if (label.length > 2) {
            ctx.fillText(label, 0, 0);
          }
          ctx.restore();

        } else if (isRadial) {
          ctx.save();
          ctx.translate(lx, ly);

          let rotation = midAngle;
          // Keep text reading from center outward / left-to-right
          if (midAngle > Math.PI / 2 && midAngle < 3 * Math.PI / 2) {
            rotation += Math.PI;
          }
          ctx.rotate(rotation);

          const fontSize = Math.max(8.5, Math.min(11, arcLength * 0.55));
          ctx.font = `600 ${fontSize}px "Plus Jakarta Sans", system-ui, sans-serif`;
          ctx.fillStyle = '#ffffff';
          ctx.shadowColor = 'rgba(0, 0, 0, 0.9)';
          ctx.shadowBlur = 4;
          ctx.textAlign = 'center';
          ctx.textBaseline = 'middle';
          ctx.globalAlpha = 0.96 * alphaMult;

          const maxLen = ringH * 0.80; // strictly confined within ring thickness!
          let measured = ctx.measureText(label).width;
          if (measured > maxLen) {
            while (label.length > 2 && ctx.measureText(label + '..').width > maxLen) {
              label = label.slice(0, -1);
            }
            label += '..';
          }

          if (label.length > 2) {
            ctx.fillText(label, 0, 0);
          }
          ctx.restore();
        }
      }
    }

    ctx.restore();
  }

  _isAncestor(potentialAncestor, node) {
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
      const pct = ((node.size / Math.max(found.parentTotalSize || this._current.size || 1, 1)) * 100).toFixed(1);
      const kind = (node.kind || (found.depth === 0 ? 'PACKAGE' : found.depth === 1 ? 'CLASS' : 'METHOD')).toUpperCase();
      const hasChildren = node.children && node.children.length > 0;

      let kindClass = 'kind-package';
      if (kind === 'CLASS' || kind === 'RECORD' || kind === 'INTERFACE') kindClass = 'kind-class';
      else if (kind === 'METHOD' || kind === 'CONSTRUCTOR') kindClass = 'kind-method';
      else if (kind === 'FIELD') kindClass = 'kind-field';

      this._tooltip.style.display = 'block';
      this._tooltip.style.left = (e.clientX - this._container.getBoundingClientRect().left + 14) + 'px';
      this._tooltip.style.top = (e.clientY - this._container.getBoundingClientRect().top - 10) + 'px';

      this._tooltip.innerHTML = `
        <div class="sunburst-tip-header">
          <span class="sunburst-tip-badge ${kindClass}">${kind}</span>
          <span class="sunburst-tip-name">${this._escHtml(node.simpleName || node.name)}</span>
        </div>
        <div class="sunburst-tip-row">
          <span>Size:</span>
          <span><strong>${node.size}</strong> lines (${pct}%)</span>
        </div>
        ${node.complexity !== undefined && node.complexity > 0 ? `
        <div class="sunburst-tip-row">
          <span>Complexity:</span>
          <span><strong>CC ${node.complexity}</strong> (${node.complexity <= 4 ? 'Low' : node.complexity <= 10 ? 'Med' : 'High'})</span>
        </div>` : ''}
        ${node.children ? `
        <div class="sunburst-tip-row">
          <span>Contains:</span>
          <span><strong>${node.children.length}</strong> children</span>
        </div>` : ''}
        ${node.fqn ? `
        <div class="sunburst-tip-row" style="margin-top:2px">
          <span class="sunburst-tip-mono">${this._escHtml(node.fqn)}</span>
        </div>` : ''}
        <div class="sunburst-tip-hint">
          <svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
          <span>${hasChildren ? 'Click to zoom in hierarchy' : 'Click to inspect in sidebar'}</span>
        </div>
      `;
      this._canvas.style.cursor = 'pointer';
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
      this._triggerTransition(node, [...this._breadcrumb, node]);
    } else if (node.fqn || node.id) {
      // Trigger selection in right inspector panel
      if (node.kind === 'METHOD' || (!node.kind && this._hovered.depth >= 2)) {
        if (window.selectMethod) window.selectMethod(node.id || node.fqn);
      } else {
        if (window.selectType) window.selectType(node.id || node.fqn);
      }
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
        sep.textContent = '>';
        bc.appendChild(sep);
      }

      const btn = document.createElement('button');
      btn.className = 'sunburst-bc-btn';
      const label = node === this._root ? 'Codebase' : (node.simpleName || node.name || '?');
      btn.innerHTML = `<span>${this._escHtml(label)}</span>`;

      if (i === this._breadcrumb.length - 1) {
        btn.classList.add('active');
      } else {
        btn.addEventListener('click', () => {
          this._triggerTransition(node, this._breadcrumb.slice(0, i + 1));
        });
      }
      bc.appendChild(btn);
    });
  }

  _updateCenterLabel() {
    const label = this._el.querySelector('#sunburst-center-label');
    if (!label) return;
    const node = this._current;
    const name = node === this._root ? 'Codebase' : (node.simpleName || node.name || '?');
    const totalSize = node.size || 0;
    const childCount = node.children ? node.children.length : 0;
    const isZoomed = this._breadcrumb.length > 1;

    label.innerHTML = `
      <div class="sunburst-center-name">${this._escHtml(name)}</div>
      <div class="sunburst-center-size">${totalSize} lines · ${childCount} items</div>
      ${isZoomed ? '<div class="sunburst-center-hint">↺ Zoom Out</div>' : ''}
    `;

    // Position center label over canvas center
    const w = this._canvas.width / this._dpr;
    const h = this._canvas.height / this._dpr;
    label.style.left = (w / 2) + 'px';
    label.style.top = (38 + h / 2) + 'px';
  }

  _escHtml(s) {
    const el = document.createElement('span');
    el.textContent = s || '';
    return el.innerHTML;
  }
}

window.SunburstRenderer = SunburstRenderer;
