/**
 * graph.js — CodeLens Force Graph Renderer
 *
 * A fully self-contained, dependency-free force-directed graph built on the
 * HTML5 Canvas 2D API. No D3, no external libraries — works fully offline.
 *
 * Architecture:
 *   ForceGraph (class)
 *     ├── Physics simulation  (Verlet integration, spring + charge forces)
 *     ├── Renderer            (Canvas 2D: nodes, edges, glow, labels)
 *     ├── Interaction         (pan, zoom, drag, click, hover, tooltip)
 *     └── Public API          (setData, fitToScreen, clear, onNodeClick)
 *
 * Node colour coding:
 *   root       → violet  #8b5cf6
 *   caller     → cyan    #22d3ee
 *   callee     → emerald #10b981
 *   propagator → amber   #f59e0b
 *   field      → amber   #f59e0b
 *   reader     → cyan    #22d3ee
 *   writer     → red     #ef4444
 *
 * Edge colour coding (kind field):
 *   CALLS        → violet
 *   READS_FIELD  → cyan
 *   WRITES_FIELD → amber
 *   EXTENDS      → emerald
 *   IMPLEMENTS   → emerald
 */

/* ─────────────────────────────────────────────────────────────────────────────
   Colour constants (mirror CSS design tokens)
   ───────────────────────────────────────────────────────────────────────────── */
const GC = {
  bg:       '#06080f',
  grid:     'rgba(255,255,255,0.03)',
  roles: {
    root:        '#8b5cf6',
    caller:      '#22d3ee',
    callee:      '#10b981',
    propagator:  '#f59e0b',
    field:       '#f59e0b',
    reader:      '#22d3ee',
    writer:      '#ef4444',
    default:     '#484f58',
  },
  roleLight: {
    root:        '#a78bfa',
    caller:      '#67e8f9',
    callee:      '#34d399',
    propagator:  '#fcd34d',
    field:       '#fcd34d',
    reader:      '#67e8f9',
    writer:      '#fca5a5',
    default:     '#6b7280',
  },
  edgeKind: {
    CALLS:        'rgba(139,92,246,0.6)',
    READS_FIELD:  'rgba(34,211,238,0.6)',
    WRITES_FIELD: 'rgba(245,158,11,0.6)',
    EXTENDS:      'rgba(16,185,129,0.6)',
    IMPLEMENTS:   'rgba(16,185,129,0.6)',
    default:      'rgba(75,85,99,0.5)',
  },
};

/* ─────────────────────────────────────────────────────────────────────────────
   Physics constants — tune here to change graph "feel"
   ───────────────────────────────────────────────────────────────────────────── */
const PHYSICS = {
  repulsion:    3500,   // charge repulsion between every pair of nodes
  springLen:    130,    // rest length of a spring (pixels, pre-scale)
  springK:      0.04,   // spring stiffness
  centerForce:  0.008,  // weak pull toward canvas center
  damping:      0.82,   // velocity decay per tick
  maxTicks:     320,    // ticks before simulation cools (still renders)
  nodeRadius:   22,     // base node radius in world units
  labelPad:     10,     // gap between node edge and label
};

/* ─────────────────────────────────────────────────────────────────────────────
   ForceGraph class
   ───────────────────────────────────────────────────────────────────────────── */
class ForceGraph {
  /**
   * @param {HTMLElement} container  The element to append the canvas into.
   * @param {HTMLElement} [tooltip]  Optional existing tooltip element.
   */
  constructor(container, tooltip) {
    this._container = container;
    this._tooltip   = tooltip || null;

    // Canvas setup
    this._canvas = document.createElement('canvas');
    this._canvas.id = 'graph-canvas';
    this._canvas.style.cssText = 'width:100%;height:100%;display:block;cursor:grab';
    container.appendChild(this._canvas);
    this._ctx = this._canvas.getContext('2d');

    // Simulation state
    this._nodes     = [];    // { id, label, role, type, x, y, vx, vy, pinned }
    this._edges     = [];    // { source, target, kind }
    this._ticks     = 0;
    this._rafId     = null;
    this._hovered   = null;

    // Viewport transform
    this._tx = 0;  // translate x
    this._ty = 0;  // translate y
    this._sc = 1;  // scale

    // Callbacks
    this.onNodeClick = null;  // (node) => void

    this._resize();
    this._bindEvents();
    this._bindResize();

    // Draw empty state immediately
    this._drawBackground();
  }

  /* ── Public API ─────────────────────────────────────────────────────────── */

  /**
   * Load new graph data and start/restart the simulation.
   *
   * @param {Array<{id,label,role,type}>} nodes
   * @param {Array<{source,target,kind}>} edges
   */
  setData(nodes, edges) {
    const cx = this._canvas.width  / 2;
    const cy = this._canvas.height / 2;

    // Initialise node positions: spread in a circle so spring forces
    // are meaningful from tick 0 (avoids a big bang at the origin).
    this._nodes = nodes.map((n, i) => {
      const angle = (i / Math.max(nodes.length, 1)) * Math.PI * 2;
      const r     = Math.min(cx, cy) * 0.4;
      return {
        ...n,
        x:      cx + Math.cos(angle) * r + (Math.random() - 0.5) * 20,
        y:      cy + Math.sin(angle) * r + (Math.random() - 0.5) * 20,
        vx:     0,
        vy:     0,
        pinned: false,
      };
    });
    this._edges  = edges;
    this._ticks  = 0;
    this._hovered = null;
    this._resetView();
    this._startLoop();
  }

  /** Reset pan/zoom to fit all nodes in the viewport with padding. */
  fitToScreen() {
    if (this._nodes.length === 0) return;
    const pad = 60;
    const xs  = this._nodes.map(n => n.x);
    const ys  = this._nodes.map(n => n.y);
    const minX = Math.min(...xs), maxX = Math.max(...xs);
    const minY = Math.min(...ys), maxY = Math.max(...ys);
    const dw   = maxX - minX || 1;
    const dh   = maxY - minY || 1;
    const sc   = Math.min(
      (this._canvas.width  - pad * 2) / dw,
      (this._canvas.height - pad * 2) / dh,
      2.0,
    );
    this._sc = sc;
    this._tx = this._canvas.width  / 2 - ((minX + maxX) / 2) * sc;
    this._ty = this._canvas.height / 2 - ((minY + maxY) / 2) * sc;
  }

  /** Remove all nodes and edges; stop the loop. */
  clear() {
    this._nodes  = [];
    this._edges  = [];
    this._hovered = null;
    if (this._rafId) { cancelAnimationFrame(this._rafId); this._rafId = null; }
    this._resetView();
    this._ctx.clearRect(0, 0, this._canvas.width, this._canvas.height);
    this._drawBackground();
  }

  /* ── Simulation loop ─────────────────────────────────────────────────────── */

  _startLoop() {
    if (this._rafId) cancelAnimationFrame(this._rafId);
    const loop = () => {
      if (this._ticks < PHYSICS.maxTicks) this._tick();
      this._draw();
      this._rafId = requestAnimationFrame(loop);
    };
    this._rafId = requestAnimationFrame(loop);
  }

  /**
   * One physics tick — computes forces on every node and integrates velocity.
   * Uses O(n²) pairwise repulsion which is fine for typical Java project
   * call graphs (< 500 nodes visible at once).
   */
  _tick() {
    const nodes = this._nodes;
    const n     = nodes.length;
    const cx    = this._canvas.width  / 2;
    const cy    = this._canvas.height / 2;

    // Reset accumulated forces
    for (let i = 0; i < n; i++) { nodes[i]._fx = 0; nodes[i]._fy = 0; }

    // ── Pairwise charge repulsion ────────────────────────────────────────────
    for (let i = 0; i < n; i++) {
      for (let j = i + 1; j < n; j++) {
        const dx   = nodes[j].x - nodes[i].x;
        const dy   = nodes[j].y - nodes[i].y;
        const dist = Math.sqrt(dx * dx + dy * dy) || 0.1;
        const f    = PHYSICS.repulsion / (dist * dist);
        const fx   = (dx / dist) * f;
        const fy   = (dy / dist) * f;
        nodes[i]._fx -= fx;  nodes[i]._fy -= fy;
        nodes[j]._fx += fx;  nodes[j]._fy += fy;
      }
    }

    // ── Spring forces along edges ─────────────────────────────────────────────
    const nodeIndex = Object.fromEntries(nodes.map((n, i) => [n.id, i]));
    for (const e of this._edges) {
      const si = nodeIndex[e.source];
      const ti = nodeIndex[e.target];
      if (si === undefined || ti === undefined) continue;
      const src = nodes[si], tgt = nodes[ti];
      const dx   = tgt.x - src.x;
      const dy   = tgt.y - src.y;
      const dist = Math.sqrt(dx * dx + dy * dy) || 0.1;
      const f    = (dist - PHYSICS.springLen) * PHYSICS.springK;
      const fx   = (dx / dist) * f;
      const fy   = (dy / dist) * f;
      src._fx += fx;  src._fy += fy;
      tgt._fx -= fx;  tgt._fy -= fy;
    }

    // ── Integrate velocity and position ──────────────────────────────────────
    for (let i = 0; i < n; i++) {
      const nd = nodes[i];
      if (nd.pinned) { nd.vx = 0; nd.vy = 0; continue; }

      // Weak centering force
      nd._fx += (cx / this._sc - nd.x) * PHYSICS.centerForce * (n / 5 + 1);
      nd._fy += (cy / this._sc - nd.y) * PHYSICS.centerForce * (n / 5 + 1);

      nd.vx = (nd.vx + nd._fx) * PHYSICS.damping;
      nd.vy = (nd.vy + nd._fy) * PHYSICS.damping;
      nd.x += nd.vx;
      nd.y += nd.vy;
    }

    this._ticks++;
  }

  /* ── Rendering ───────────────────────────────────────────────────────────── */

  _draw() {
    const ctx = this._ctx;
    const W   = this._canvas.width;
    const H   = this._canvas.height;

    ctx.clearRect(0, 0, W, H);
    this._drawBackground();

    ctx.save();
    ctx.translate(this._tx, this._ty);
    ctx.scale(this._sc, this._sc);

    this._drawEdges(ctx);
    this._drawNodes(ctx);

    ctx.restore();
  }

  /** Subtle dot-grid background — gives the "circuit board" aesthetic. */
  _drawBackground() {
    const ctx  = this._ctx;
    const W    = this._canvas.width;
    const H    = this._canvas.height;
    const step = 28;

    ctx.fillStyle = GC.bg;
    ctx.fillRect(0, 0, W, H);

    ctx.fillStyle = GC.grid;
    for (let x = 0; x < W; x += step) {
      for (let y = 0; y < H; y += step) {
        ctx.beginPath();
        ctx.arc(x, y, 1, 0, Math.PI * 2);
        ctx.fill();
      }
    }
  }

  _drawEdges(ctx) {
    for (const edge of this._edges) {
      const src = this._nodes.find(n => n.id === edge.source);
      const tgt = this._nodes.find(n => n.id === edge.target);
      if (!src || !tgt) continue;
      this._drawArrow(ctx, src.x, src.y, tgt.x, tgt.y, edge.kind);
    }
  }

  /**
   * Draws a directed arrow from (x1,y1) to (x2,y2), shortened by the node
   * radius so it terminates at the node's circumference, not its centre.
   */
  _drawArrow(ctx, x1, y1, x2, y2, kind) {
    const r    = PHYSICS.nodeRadius;
    const dx   = x2 - x1;
    const dy   = y2 - y1;
    const dist = Math.sqrt(dx * dx + dy * dy);
    if (dist < r * 2 + 2) return;   // nodes overlapping; skip arrow

    const ux = dx / dist;
    const uy = dy / dist;
    const sx = x1 + ux * r;
    const sy = y1 + uy * r;
    const ex = x2 - ux * (r + 4);  // +4 for arrowhead clearance
    const ey = y2 - uy * (r + 4);

    const colour = GC.edgeKind[kind] || GC.edgeKind.default;

    // ── Line (slightly curved for multi-edge aesthetics) ─────────────────────
    const mx  = (sx + ex) / 2 - uy * 20;  // quadratic control point
    const my  = (sy + ey) / 2 + ux * 20;

    ctx.beginPath();
    ctx.moveTo(sx, sy);
    ctx.quadraticCurveTo(mx, my, ex, ey);
    ctx.strokeStyle = colour;
    ctx.lineWidth   = 1.5;
    ctx.stroke();

    // ── Arrowhead ─────────────────────────────────────────────────────────────
    // Compute tangent at the end of the quadratic curve (t=1)
    const tx = 2 * (ex - mx);
    const ty = 2 * (ey - my);
    const ta = Math.atan2(ty, tx);
    const as = 9;   // arrowhead size

    ctx.beginPath();
    ctx.moveTo(ex, ey);
    ctx.lineTo(ex - as * Math.cos(ta - 0.42), ey - as * Math.sin(ta - 0.42));
    ctx.lineTo(ex - as * Math.cos(ta + 0.42), ey - as * Math.sin(ta + 0.42));
    ctx.closePath();
    ctx.fillStyle = colour;
    ctx.fill();
  }

  _drawNodes(ctx) {
    for (const node of this._nodes) {
      this._drawNode(ctx, node, node === this._hovered);
    }
  }

  _drawNode(ctx, node, isHovered) {
    const r         = PHYSICS.nodeRadius;
    const x         = node.x;
    const y         = node.y;
    const baseColour = GC.roles[node.role]  || GC.roles.default;
    const lightColour = GC.roleLight[node.role] || GC.roleLight.default;
    const isRoot    = node.role === 'root';

    // ── Outer glow ring for root node or hovered ──────────────────────────────
    if (isRoot || isHovered) {
      ctx.beginPath();
      ctx.arc(x, y, r + 6, 0, Math.PI * 2);
      const glowGrad = ctx.createRadialGradient(x, y, r, x, y, r + 10);
      glowGrad.addColorStop(0,   baseColour + '55');
      glowGrad.addColorStop(1,   'transparent');
      ctx.fillStyle = glowGrad;
      ctx.fill();
    }

    // ── Node circle — radial gradient for depth illusion ──────────────────────
    ctx.beginPath();
    ctx.arc(x, y, r, 0, Math.PI * 2);
    const grad = ctx.createRadialGradient(x - r * 0.3, y - r * 0.3, r * 0.1,
                                           x,            y,            r);
    grad.addColorStop(0, lightColour);
    grad.addColorStop(1, baseColour);
    ctx.fillStyle = grad;
    ctx.fill();

    // ── Border ring ────────────────────────────────────────────────────────────
    ctx.beginPath();
    ctx.arc(x, y, r, 0, Math.PI * 2);
    ctx.strokeStyle = isHovered
      ? '#ffffff88'
      : (isRoot ? baseColour + 'aa' : 'rgba(255,255,255,0.15)');
    ctx.lineWidth   = isRoot ? 2 : 1;
    ctx.stroke();

    // ── Type icon inside node ─────────────────────────────────────────────────
    const icon = { METHOD: '◆', FIELD: '■', TYPE: '●', CLASS: '●' }[node.type] || '◆';
    ctx.fillStyle   = 'rgba(255,255,255,0.55)';
    ctx.font        = `10px monospace`;
    ctx.textAlign   = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(icon, x, y);

    // ── Label below node ───────────────────────────────────────────────────────
    const maxChars = 16;
    const lbl      = node.label.length > maxChars
      ? node.label.slice(0, maxChars - 1) + '…'
      : node.label;

    ctx.font        = isRoot
      ? `bold 11px ${getComputedStyle(document.documentElement).getPropertyValue('--font-ui') || 'system-ui'}`
      : `10px system-ui`;
    ctx.fillStyle   = isHovered ? '#e6edf3' : '#8b949e';
    ctx.textAlign   = 'center';
    ctx.textBaseline = 'top';
    ctx.fillText(lbl, x, y + r + PHYSICS.labelPad - 2);
  }

  /* ── Interaction ─────────────────────────────────────────────────────────── */

  _bindEvents() {
    const cv = this._canvas;

    let dragging  = null;   // node being dragged
    let panning   = false;  // canvas being panned
    let lastMouse = null;
    let clickStart = null;  // for click-vs-drag detection

    // ── Mouse down ─────────────────────────────────────────────────────────────
    cv.addEventListener('mousedown', e => {
      const wp = this._screenToWorld(e.offsetX, e.offsetY);
      const hit = this._hitTest(wp.x, wp.y);
      clickStart = { x: e.offsetX, y: e.offsetY };

      if (hit) {
        dragging       = hit;
        hit.pinned     = true;
        cv.style.cursor = 'grabbing';
      } else {
        panning        = true;
        lastMouse      = { x: e.offsetX, y: e.offsetY };
        cv.style.cursor = 'grabbing';
      }
    });

    // ── Mouse move ─────────────────────────────────────────────────────────────
    cv.addEventListener('mousemove', e => {
      const wp = this._screenToWorld(e.offsetX, e.offsetY);

      if (dragging) {
        dragging.x  = wp.x;
        dragging.y  = wp.y;
        dragging.vx = 0;
        dragging.vy = 0;

      } else if (panning && lastMouse) {
        this._tx    += e.offsetX - lastMouse.x;
        this._ty    += e.offsetY - lastMouse.y;
        lastMouse    = { x: e.offsetX, y: e.offsetY };

      } else {
        // Hover hit test
        const hit = this._hitTest(wp.x, wp.y);
        this._hovered = hit;
        cv.style.cursor = hit ? 'pointer' : 'grab';
        this._showTooltip(hit, e.clientX, e.clientY);
      }
    });

    // ── Mouse up ────────────────────────────────────────────────────────────────
    cv.addEventListener('mouseup', e => {
      if (dragging) {
        const dx = e.offsetX - (clickStart?.x || 0);
        const dy = e.offsetY - (clickStart?.y || 0);
        // If barely moved, treat as a click
        if (Math.abs(dx) < 4 && Math.abs(dy) < 4) {
          if (this.onNodeClick) this.onNodeClick(dragging);
        }
        dragging.pinned = false;
        dragging = null;
      }
      panning   = false;
      lastMouse = null;
      cv.style.cursor = 'grab';
    });

    cv.addEventListener('mouseleave', () => {
      this._hovered = null;
      this._hideTooltip();
      if (dragging) { dragging.pinned = false; dragging = null; }
      panning = false;
    });

    // ── Scroll wheel zoom ──────────────────────────────────────────────────────
    cv.addEventListener('wheel', e => {
      e.preventDefault();
      const delta  = e.deltaY > 0 ? 0.88 : 1.12;
      const ox     = e.offsetX;
      const oy     = e.offsetY;
      // Zoom around the cursor position
      this._tx     = ox - (ox - this._tx) * delta;
      this._ty     = oy - (oy - this._ty) * delta;
      this._sc    *= delta;
      this._sc     = Math.max(0.1, Math.min(this._sc, 5));
    }, { passive: false });
  }

  _bindResize() {
    const ro = new ResizeObserver(() => this._resize());
    ro.observe(this._container);
  }

  _resize() {
    const dpr = window.devicePixelRatio || 1;
    const rect = this._container.getBoundingClientRect();
    this._canvas.width  = rect.width  * dpr;
    this._canvas.height = rect.height * dpr;
    this._ctx.scale(dpr, dpr);
    this._canvas.style.width  = rect.width  + 'px';
    this._canvas.style.height = rect.height + 'px';
  }

  /* ── Coordinate utilities ─────────────────────────────────────────────────── */

  _screenToWorld(sx, sy) {
    return {
      x: (sx - this._tx) / this._sc,
      y: (sy - this._ty) / this._sc,
    };
  }

  _hitTest(wx, wy) {
    const r2 = PHYSICS.nodeRadius * PHYSICS.nodeRadius;
    return this._nodes.find(n => {
      const dx = n.x - wx;
      const dy = n.y - wy;
      return dx * dx + dy * dy < r2;
    }) || null;
  }

  _resetView() {
    this._tx = 0;
    this._ty = 0;
    this._sc = 1;
  }

  /* ── Tooltip ─────────────────────────────────────────────────────────────── */

  _showTooltip(node, clientX, clientY) {
    if (!this._tooltip || !node) { this._hideTooltip(); return; }

    this._tooltip.innerHTML = `
      <div class="tooltip-title">${this._esc(node.label)}</div>
      <div class="tooltip-sub">${this._esc(node.id)}</div>
      <div class="tooltip-role">
        <span style="color:${GC.roles[node.role] || GC.roles.default}">${node.role}</span>
        &nbsp;·&nbsp; ${node.type}
      </div>`;

    // Position near cursor, flipping if near right/bottom edge
    const gap = 14;
    let left   = clientX + gap;
    let top    = clientY + gap;
    this._tooltip.style.display = 'block';
    const tw = this._tooltip.offsetWidth;
    const th = this._tooltip.offsetHeight;
    if (left + tw > window.innerWidth  - 12) left = clientX - tw - gap;
    if (top  + th > window.innerHeight - 12) top  = clientY - th - gap;
    this._tooltip.style.left = left + 'px';
    this._tooltip.style.top  = top  + 'px';
    this._tooltip.classList.add('visible');
  }

  _hideTooltip() {
    if (this._tooltip) {
      this._tooltip.classList.remove('visible');
      this._tooltip.style.display = 'none';
    }
  }

  _esc(str) {
    return String(str || '')
      .replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
  }
}

/* Export to global scope for app.js */
window.ForceGraph = ForceGraph;
window.GC = GC;
