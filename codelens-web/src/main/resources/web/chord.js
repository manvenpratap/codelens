/**
 * chord.js - Chord Diagram Renderer
 *
 * Renders inter-class call relationships as a circular chord diagram.
 * Arcs around the circle represent classes (sized by method count).
 * Chords between arcs represent call volume between classes.
 * Reuses /api/graph/architecture data.
 */

class ChordRenderer {
  constructor(container) {
    this._container = container;
    this._el = null;
    this._canvas = null;
    this._ctx = null;
    this._data = null;
    this._arcs = [];
    this._chords = [];
    this._hiddenPackages = new Set();
    this._hiddenEntities = new Set();
    this._archetypeFilter = 'ALL';
    this._hovered = -1;
    this._tooltip = null;
    this._dpr = window.devicePixelRatio || 1;
    this._bound = {
      onMouseMove: this._onMouseMove.bind(this),
      onMouseLeave: this._onMouseLeave.bind(this),
      onResize: this._onResize.bind(this),
    };
  }

  setArchetypeFilter(ruleId) {
    this._archetypeFilter = ruleId || 'ALL';
    this._buildChordData();
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
    this._buildChordData();
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
    this._buildChordData();
    this._draw();
  }

  _isNodeHidden(node) {
    if (!node) return false;
    const fqn = node.id || node.fqn || '';
    const name = node.label || (fqn.includes('.') ? fqn.split('.').pop() : fqn);
    let pkg = node.package;
    if (!pkg && fqn.includes('.')) {
      pkg = fqn.split('.').slice(0, -1).join('.');
    }
    pkg = pkg || 'default';

    if (this._hiddenPackages.has(pkg)) return true;
    if (this._hiddenEntities.has(name) || this._hiddenEntities.has(fqn)) return true;

    if (this._archetypeFilter && this._archetypeFilter !== 'ALL' && window.CodeLensClassifier) {
      const isMethod = node.type === 'METHOD' || (!node.type && fqn.includes('('));
      const ok = window.CodeLensClassifier.isMatchArchetype(
        name,
        fqn,
        pkg,
        isMethod,
        this._archetypeFilter
      );
      if (!ok) return true;
    }

    return false;
  }

  setData(payload) {
    this._data = payload;
    this._mount();
    this._buildChordData();
    this._draw();
  }

  destroy() {
    if (this._canvas) {
      this._canvas.removeEventListener('mousemove', this._bound.onMouseMove);
      this._canvas.removeEventListener('mouseleave', this._bound.onMouseLeave);
    }
    window.removeEventListener('resize', this._bound.onResize);
    if (this._el) { this._el.remove(); this._el = null; }
  }

  _mount() {
    if (this._el) this._el.remove();

    const wrap = document.createElement('div');
    wrap.className = 'chord-container';
    wrap.setAttribute('role', 'img');
    wrap.setAttribute('aria-label', 'Codebase Architecture Chord Diagram');
    wrap.setAttribute('tabindex', '0');

    const canvas = document.createElement('canvas');
    canvas.className = 'chord-canvas';
    wrap.appendChild(canvas);

    const tooltip = document.createElement('div');
    tooltip.className = 'chord-tooltip';
    tooltip.style.display = 'none';
    wrap.appendChild(tooltip);

    this._container.appendChild(wrap);
    this._el = wrap;
    this._canvas = canvas;
    this._ctx = canvas.getContext('2d');
    this._tooltip = tooltip;

    canvas.addEventListener('mousemove', this._bound.onMouseMove);
    canvas.addEventListener('mouseleave', this._bound.onMouseLeave);
    window.addEventListener('resize', this._bound.onResize);

    this._onResize();
  }

  _onResize() {
    if (!this._canvas || !this._el) return;
    const w = this._el.clientWidth;
    const h = this._el.clientHeight;
    this._canvas.width = w * this._dpr;
    this._canvas.height = h * this._dpr;
    this._canvas.style.width = w + 'px';
    this._canvas.style.height = h + 'px';
    if (this._data) {
      this._buildChordData();
      this._draw();
    }
  }

  _buildChordData() {
    const rawNodes = this._data.nodes || [];
    const rawEdges = this._data.edges || [];
    if (rawNodes.length === 0) return;

    const nodes = rawNodes.filter(n => !this._isNodeHidden(n));
    if (nodes.length === 0) {
      this._arcs = [];
      this._chords = [];
      return;
    }

    const n = nodes.length;
    const nodeIndex = {};
    nodes.forEach((nd, i) => { nodeIndex[nd.id] = i; });

    // Build adjacency matrix
    const matrix = Array.from({ length: n }, () => new Array(n).fill(0));
    for (const edge of rawEdges) {
      const si = nodeIndex[edge.source];
      const ti = nodeIndex[edge.target];
      if (si !== undefined && ti !== undefined) {
        // Parse count from kind string like "CALLS (3)"
        let count = 1;
        const m = (edge.kind || '').match(/\((\d+)\)/);
        if (m) count = parseInt(m[1], 10);
        matrix[si][ti] += count;
      }
    }

    // Calculate arc sizes based on total connections
    const totals = nodes.map((_, i) => {
      let sum = 0;
      for (let j = 0; j < n; j++) sum += matrix[i][j] + matrix[j][i];
      return Math.max(sum, 1);
    });
    const grandTotal = totals.reduce((s, v) => s + v, 0);

    const w = this._canvas.width / this._dpr;
    const h = this._canvas.height / this._dpr;
    const cx = w / 2;
    const cy = h / 2;
    const radius = Math.min(cx, cy) - 80;
    const arcWidth = 18;
    const gap = 0.01; // gap between arcs in radians

    // Palette
    const colors = [
      '#3B82F6', '#10B981', '#F59E0B', '#0EA5E9', '#EF4444',
      '#14B8A6', '#06B6D4', '#F97316', '#84CC16', '#64748B',
      '#8B5CF6', '#EC4899', '#A855F7', '#22D3EE', '#FB923C',
    ];

    // Build arcs
    const totalAngle = 2 * Math.PI - n * gap;
    let angle = 0;
    this._arcs = nodes.map((nd, i) => {
      const fraction = totals[i] / grandTotal;
      const arcAngle = totalAngle * fraction;
      const arcColor = (window.CodeLensPalette && window.CodeLensPalette.getClassColor)
        ? window.CodeLensPalette.getClassColor(nd.id || nd.label, nd.type || 'METHOD', i)
        : ((window.CodeLensPalette && window.CodeLensPalette.getColor)
            ? window.CodeLensPalette.getColor(nd.id || nd.label, i)
            : colors[i % colors.length]);
      const arc = {
        index: i,
        startAngle: angle,
        endAngle: angle + arcAngle,
        node: nd,
        color: arcColor,
        total: totals[i],
      };
      angle += arcAngle + gap;
      return arc;
    });

    // Build chords
    this._chords = [];
    for (let i = 0; i < n; i++) {
      for (let j = i + 1; j < n; j++) {
        const val = matrix[i][j] + matrix[j][i];
        if (val > 0) {
          this._chords.push({
            source: i, target: j, value: val,
            sourceOut: matrix[i][j], targetOut: matrix[j][i],
          });
        }
      }
    }

    this._cx = cx;
    this._cy = cy;
    this._radius = radius;
    this._arcWidth = arcWidth;
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
    const r = this._radius;
    const aw = this._arcWidth;
    const hovered = this._hovered;

    // Draw D3-style double-sided ribbon polygons with linear gradients
    const minChordWeight = this._chords.length > 100 ? 2 : 1;
    for (const chord of this._chords) {
      if (chord.value < minChordWeight && hovered < 0) continue; // Chord weight culling / bundling

      const srcArc = this._arcs[chord.source];
      const tgtArc = this._arcs[chord.target];
      if (!srcArc || !tgtArc) continue;

      // Arc thresholding: skip drawing chords connecting tiny culled arcs (< 0.005 rad)
      if ((srcArc.endAngle - srcArc.startAngle < 0.005 || tgtArc.endAngle - tgtArc.startAngle < 0.005) && hovered < 0) {
        continue;
      }

      const sa0 = srcArc.startAngle, sa1 = srcArc.endAngle;
      const ta0 = tgtArc.startAngle, ta1 = tgtArc.endAngle;

      const sx0 = cx + Math.cos(sa0) * r, sy0 = cy + Math.sin(sa0) * r;
      const sx1 = cx + Math.cos(sa1) * r, sy1 = cy + Math.sin(sa1) * r;
      const tx0 = cx + Math.cos(ta0) * r, ty0 = cy + Math.sin(ta0) * r;
      const tx1 = cx + Math.cos(ta1) * r, ty1 = cy + Math.sin(ta1) * r;

      const isHot = hovered >= 0 && (chord.source === hovered || chord.target === hovered);
      const dimmed = hovered >= 0 && !isHot;

      // Draw D3 Ribbon Geometry
      ctx.beginPath();
      ctx.moveTo(sx0, sy0);
      ctx.arc(cx, cy, r, sa0, sa1);
      ctx.quadraticCurveTo(cx, cy, tx0, ty0);
      ctx.arc(cx, cy, r, ta0, ta1);
      ctx.quadraticCurveTo(cx, cy, sx0, sy0);
      ctx.closePath();

      const grad = ctx.createLinearGradient(sx0, sy0, tx0, ty0);
      grad.addColorStop(0, srcArc.color);
      grad.addColorStop(1, tgtArc.color);

      ctx.fillStyle = grad;
      ctx.globalAlpha = dimmed ? 0.04 : (isHot ? 0.65 : 0.28);
      ctx.fill();

      ctx.strokeStyle = srcArc.color;
      ctx.globalAlpha = dimmed ? 0.05 : (isHot ? 0.9 : 0.35);
      ctx.lineWidth = isHot ? 1.5 : 0.5;
      ctx.stroke();
    }

    // Compute connected/related arc indices for hover state
    const relatedArcs = new Set();
    if (hovered >= 0) {
      for (const chord of this._chords) {
        if (chord.source === hovered) relatedArcs.add(chord.target);
        if (chord.target === hovered) relatedArcs.add(chord.source);
      }
    }

    // Draw arcs
    for (const arc of this._arcs) {
      const isHovered = (hovered >= 0 && arc.index === hovered);
      const isRelated = (hovered >= 0 && relatedArcs.has(arc.index));
      const isDimmed = (hovered >= 0 && !isHovered && !isRelated);

      ctx.beginPath();
      ctx.arc(cx, cy, r + aw / 2, arc.startAngle, arc.endAngle);
      ctx.strokeStyle = arc.color;
      if (isHovered) {
        ctx.globalAlpha = 1.0;
        ctx.lineWidth = aw + 6;
      } else if (isRelated) {
        ctx.globalAlpha = 1.0;
        ctx.lineWidth = aw + 2;
      } else if (isDimmed) {
        ctx.globalAlpha = 0.12;
        ctx.lineWidth = aw;
      } else {
        ctx.globalAlpha = 1.0;
        ctx.lineWidth = aw;
      }
      ctx.lineCap = 'butt';
      ctx.stroke();

      // Outer accent ring for hovered and related arcs
      if (isHovered) {
        ctx.save();
        ctx.beginPath();
        ctx.arc(cx, cy, r + aw + 3, arc.startAngle, arc.endAngle);
        ctx.strokeStyle = '#ffffff';
        ctx.globalAlpha = 0.9;
        ctx.lineWidth = 2;
        ctx.stroke();
        ctx.restore();
      } else if (isRelated) {
        ctx.save();
        ctx.beginPath();
        ctx.arc(cx, cy, r + aw + 2, arc.startAngle, arc.endAngle);
        ctx.strokeStyle = arc.color;
        ctx.globalAlpha = 0.75;
        ctx.lineWidth = 1.5;
        ctx.stroke();
        ctx.restore();
      }

      // Label
      const midAngle = (arc.startAngle + arc.endAngle) / 2;
      const labelR = r + aw + 13;
      const lx = cx + Math.cos(midAngle) * labelR;
      const ly = cy + Math.sin(midAngle) * labelR;

      const arcSpan = arc.endAngle - arc.startAngle;
      if (isHovered || isRelated || (arcSpan > 0.08 && !isDimmed)) { // Always label hovered and related nodes
        ctx.save();
        ctx.translate(lx, ly);
        let rotation = midAngle;
        if (midAngle > Math.PI / 2 && midAngle < 3 * Math.PI / 2) {
          rotation += Math.PI;
          ctx.rotate(rotation);
          ctx.textAlign = 'right';
        } else {
          ctx.rotate(rotation);
          ctx.textAlign = 'left';
        }

        if (isHovered) {
          ctx.fillStyle = '#ffffff';
          ctx.font = '700 12px "Plus Jakarta Sans", system-ui, sans-serif';
          ctx.shadowColor = arc.color;
          ctx.shadowBlur = 8;
        } else if (isRelated) {
          ctx.fillStyle = arc.color;
          ctx.font = '600 11.5px "Plus Jakarta Sans", system-ui, sans-serif';
          ctx.shadowColor = 'rgba(0, 0, 0, 0.9)';
          ctx.shadowBlur = 5;
        } else if (isDimmed) {
          ctx.fillStyle = 'rgba(148, 163, 184, 0.18)';
          ctx.font = '400 10.5px "Plus Jakarta Sans", system-ui, sans-serif';
        } else {
          ctx.fillStyle = '#e2e8f0';
          ctx.font = '500 11px "Plus Jakarta Sans", system-ui, sans-serif';
        }

        ctx.globalAlpha = 1;
        ctx.textBaseline = 'middle';
        ctx.fillText(arc.node.label || arc.node.id, 0, 0);
        ctx.restore();
      }
    }

    ctx.restore();
  }

  _onMouseMove(e) {
    const rect = this._canvas.getBoundingClientRect();
    const mx = e.clientX - rect.left;
    const my = e.clientY - rect.top;

    const cx = this._cx;
    const cy = this._cy;
    const r = this._radius;
    const aw = this._arcWidth;

    // Check if mouse is on an arc
    const dx = mx - cx;
    const dy = my - cy;
    const dist = Math.sqrt(dx * dx + dy * dy);
    let angle = Math.atan2(dy, dx);
    if (angle < 0) angle += 2 * Math.PI;

    let found = -1;
    if (dist >= r - 5 && dist <= r + aw + 5) {
      for (const arc of this._arcs) {
        if (angle >= arc.startAngle && angle <= arc.endAngle) {
          found = arc.index;
          break;
        }
      }
    }

    // Also check if over a chord
    if (found < 0 && dist < r) {
      // Simple proximity - find nearest arc based on angle
      let bestDist = Infinity;
      for (const chord of this._chords) {
        const srcArc = this._arcs[chord.source];
        const tgtArc = this._arcs[chord.target];
        const srcMid = (srcArc.startAngle + srcArc.endAngle) / 2;
        const tgtMid = (tgtArc.startAngle + tgtArc.endAngle) / 2;

        // Check proximity to the chord's quadratic curve midpoint
        const sx = cx + Math.cos(srcMid) * r;
        const sy = cy + Math.sin(srcMid) * r;
        const tx = cx + Math.cos(tgtMid) * r;
        const ty = cy + Math.sin(tgtMid) * r;
        const cmx = (sx + tx + cx) / 3;
        const cmy = (sy + ty + cy) / 3;
        const d = Math.sqrt((mx - cmx) ** 2 + (my - cmy) ** 2);
        if (d < 25 && d < bestDist) {
          bestDist = d;
          found = chord.source; // highlight source arc
        }
      }
    }

    if (found !== this._hovered) {
      this._hovered = found;
      this._draw();
    }

    if (found >= 0) {
      const arc = this._arcs[found];
      const nd = arc.node;
      this._tooltip.style.display = 'block';
      this._tooltip.style.left = (e.clientX - this._container.getBoundingClientRect().left + 12) + 'px';
      this._tooltip.style.top = (e.clientY - this._container.getBoundingClientRect().top - 10) + 'px';

      // Count connections
      let outgoing = 0, incoming = 0;
      for (const chord of this._chords) {
        if (chord.source === found) { outgoing += chord.sourceOut; incoming += chord.targetOut; }
        if (chord.target === found) { outgoing += chord.targetOut; incoming += chord.sourceOut; }
      }

      this._tooltip.innerHTML = `
        <div class="chord-tip-name">${this._escHtml(nd.label || nd.id)}</div>
        <div class="chord-tip-row"><span>Outgoing:</span> <span>${outgoing}</span></div>
        <div class="chord-tip-row"><span>Incoming:</span> <span>${incoming}</span></div>
        <div class="chord-tip-row"><span>FQN:</span> <span class="chord-tip-mono">${this._escHtml(nd.id)}</span></div>
      `;
      this._canvas.style.cursor = 'pointer';
    } else {
      this._tooltip.style.display = 'none';
      this._canvas.style.cursor = 'default';
    }
  }

  _onMouseLeave() {
    this._hovered = -1;
    this._tooltip.style.display = 'none';
    this._draw();
  }

  _escHtml(s) {
    const el = document.createElement('span');
    el.textContent = s || '';
    return el.innerHTML;
  }
}

window.ChordRenderer = ChordRenderer;
