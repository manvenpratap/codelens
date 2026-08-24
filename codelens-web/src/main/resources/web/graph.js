/**
 * graph.js - CodeLens Graphify-Style Knowledge Graph Engine
 *
 * Inspired by Graphify (Graphify-Labs):
 * - Community/Package Convex Hulls (Hyperedges) with translucent shaded regions & cluster tags
 * - ForceAtlas2-based physics simulation with strong anti-overlap & hard collision separation
 * - Dynamic node sizing based on degree with specular 3D highlights & high-contrast border rings
 * - Smooth curved directed bezier edges with directional arrowheads and cybernetic particle flow
 * - Connected-component focus mode (dims non-neighbors on hover/selection)
 * - Community filter legend with count badges and dim toggling
 * - Interactive Node Quick-Inspector card with clickable neighbor traversal links
 * - Canvas Radar Minimap overview navigator
 */

/* ─────────────────────────────────────────────────────────────────────────────
   Graphify Color Palette & Constants
   ───────────────────────────────────────────────────────────────────────────── */

let GRAPHIFY_COLORS = [
  '#3B82F6', // 0: Precision Cobalt
  '#10B981', // 1: Emerald Green
  '#F59E0B', // 2: Warm Amber
  '#0EA5E9', // 3: Sky Cyan
  '#EF4444', // 4: Crimson Red
  '#14B8A6', // 5: Mint Teal
  '#06B6D4', // 6: Ocean Cyan
  '#F97316', // 7: Blaze Orange
  '#84CC16', // 8: Lime Green
  '#64748B', // 9: Steel Slate
];

let GC = {
  bg:       '#0d1117',
  grid:     'rgba(255, 255, 255, 0.02)',
  roles: {
    root:        '#2563eb',
    caller:      '#38bdf8',
    callee:      '#34d399',
    propagator:  '#fbbf24',
    field:       '#fb923c',
    reader:      '#38bdf8',
    writer:      '#f87171',
    default:     '#3b82f6',
  },
  edgeKind: {
    CALLS:        '#38bdf8',
    READS_FIELD:  '#2dd4bf',
    WRITES_FIELD: '#f59e0b',
    EXTENDS:      '#94a3b8',
    IMPLEMENTS:   '#94a3b8',
    default:      '#64748b',
  },
};

let PHYSICS = {
  repulsion:      20000,  // strong anti-overlap charge repulsion
  springLen:      180,    // compact rest spring length
  springK:        0.015,  // spring tension
  clusterK:       0.0018, // community cohesion
  centerForce:    0.0004, // centering pull
  damping:        0.80,   // velocity damping
  maxTicks:       500,    // auto-cooling ticks
  nodeBaseRadius: 9,      // compact base radius
};

/* ─────────────────────────────────────────────────────────────────────────────
   Geometry & Convex Hull Helpers
   ───────────────────────────────────────────────────────────────────────────── */

function getConvexHull(points) {
  if (!points || points.length <= 2) return (points || []).slice();
  const pts = points.slice().sort((a, b) => (a.x === b.x ? a.y - b.y : a.x - b.x));
  const cross = (o, a, b) => (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x);

  const lower = [];
  for (const p of pts) {
    while (lower.length >= 2 && cross(lower[lower.length - 2], lower[lower.length - 1], p) <= 0) {
      lower.pop();
    }
    lower.push(p);
  }

  const upper = [];
  for (let i = pts.length - 1; i >= 0; i--) {
    const p = pts[i];
    while (upper.length >= 2 && cross(upper[upper.length - 2], upper[upper.length - 1], p) <= 0) {
      upper.pop();
    }
    upper.push(p);
  }

  upper.pop();
  lower.pop();
  return lower.concat(upper);
}

function expandHull(hullPoints, pad = 32) {
  if (!hullPoints || hullPoints.length < 2) return hullPoints;
  const cx = hullPoints.reduce((s, p) => s + p.x, 0) / hullPoints.length;
  const cy = hullPoints.reduce((s, p) => s + p.y, 0) / hullPoints.length;
  return hullPoints.map(p => {
    const dx = p.x - cx;
    const dy = p.y - cy;
    const dist = Math.sqrt(dx * dx + dy * dy) || 1;
    return {
      x: p.x + (dx / dist) * pad,
      y: p.y + (dy / dist) * pad,
    };
  });
}

function hexToRgba(hex, alpha = 1) {
  let c = String(hex || '#ffffff').replace('#', '');
  if (c.length === 3) c = c.split('').map(x => x + x).join('');
  const num = parseInt(c, 16);
  if (isNaN(num)) return `rgba(255, 255, 255, ${alpha})`;
  const r = (num >> 16) & 255;
  const g = (num >> 8) & 255;
  const b = num & 255;
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}

function lerpColor(c1, c2, factor) {
  const f = Math.max(0, Math.min(1, factor));
  const parse = hex => {
    let c = hex.replace('#', '');
    if (c.length === 3) c = c.split('').map(x => x + x).join('');
    const num = parseInt(c, 16);
    return [(num >> 16) & 255, (num >> 8) & 255, num & 255];
  };
  const [r1, g1, b1] = parse(c1);
  const [r2, g2, b2] = parse(c2);
  const r = Math.round(r1 + (r2 - r1) * f);
  const g = Math.round(g1 + (g2 - g1) * f);
  const b = Math.round(b1 + (b2 - b1) * f);
  return `rgb(${r}, ${g}, ${b})`;
}

/* ─────────────────────────────────────────────────────────────────────────────
   ForceGraph (Graphify Edition)
   ───────────────────────────────────────────────────────────────────────────── */

class ForceGraph {
  constructor(container, tooltip) {
    this._container = container;
    this._tooltip   = tooltip || null;

    // Main Canvas
    this._canvas = document.createElement('canvas');
    this._canvas.id = 'graph-canvas';
    this._canvas.style.cssText = 'width:100%;height:100%;display:block;cursor:grab;position:absolute;inset:0;';
    container.appendChild(this._canvas);
    this._ctx = this._canvas.getContext('2d');

    // Minimap Canvas
    this._minimapCanvas = document.getElementById('graph-minimap');
    this._minimapCtx = this._minimapCanvas ? this._minimapCanvas.getContext('2d') : null;

    // Simulation Data & State
    this._nodes       = [];
    this._edges       = [];
    this._communities = []; // Array of { cid, label, color, count, nodes: Set, hidden: boolean }
    this._communityMap = new Map(); // package/community name -> community object
    this._hiddenCommunities = new Set();

    this._ticks       = 0;
    this._rafId       = null;
    this._physicsEnabled = true;
    this._showHulls   = true;

    // Visual toggle flags (controlled by Settings)
    this._showParticles = true;
    this._showMinimap   = true;
    this._showLabels    = true;
    this._showGrid      = true;
    this._packagePrefixStrip = '';

    // Selection & Highlight
    this._hoveredNode   = null;
    this._selectedNode  = null;
    this._connectedMap  = new Map(); // nodeId -> Set of neighbor nodeIds

    // Heat overlay mode
    this._heatMode  = false;
    this._heatData  = {};
    this._heatMax   = 1;

    // Particle animations
    this._particles = [];
    this._lastParticleSpawn = 0;

    // Camera / Viewport Transform
    this._tx = 0;
    this._ty = 0;
    this._sc = 1;

    // Public click callback
    this.onNodeClick = null;

    this._resize();
    this._bindEvents();
    this._bindResize();
    this._initHudControls();
    this._bindNodeCardDrag();

    // Start render loop
    this._startLoop();
  }

  /** Extract package/module, declaring class, and member name from a Java FQN. */
  _extractPackageAndClass(fqn, nodeType) {
    if (!fqn) return { pkg: 'default', className: '', memberName: '' };

    const parenIdx = fqn.indexOf('(');
    const sigPart = parenIdx !== -1 ? fqn.substring(0, parenIdx) : fqn;
    const parts = sigPart.split('.');

    if (parts.length <= 1) {
      return { pkg: 'default', className: parts[0] || '', memberName: '' };
    }

    // Find class segment (first segment starting with uppercase letter)
    let classIdx = -1;
    for (let i = 0; i < parts.length; i++) {
      if (/^[A-Z]/.test(parts[i])) {
        classIdx = i;
        break;
      }
    }

    let pkg = 'default';
    let className = '';
    let memberName = '';

    if (classIdx !== -1) {
      pkg = parts.slice(0, classIdx).join('.') || 'default';
      className = parts[classIdx];
      memberName = parts.slice(classIdx + 1).join('.');
    } else {
      if (nodeType === 'METHOD' || parenIdx !== -1) {
        if (parts.length >= 3) {
          pkg = parts.slice(0, -2).join('.');
          className = parts[parts.length - 2];
          memberName = parts[parts.length - 1];
        } else if (parts.length === 2) {
          pkg = 'default';
          className = parts[0];
          memberName = parts[1];
        }
      } else {
        pkg = parts.slice(0, -1).join('.') || 'default';
        className = parts[parts.length - 1];
      }
    }

    return { pkg, className, memberName };
  }

  /** Format a package name by stripping configured prefix (e.g. "com.example.") into a clean Module name. */
  _formatPackageLabel(pkg) {
    if (!pkg || pkg === 'default') return 'Core';
    const prefixStr = this._packagePrefixStrip || '';
    let res = pkg;
    if (prefixStr) {
      const prefixes = prefixStr.split(',').map(s => s.trim()).filter(Boolean);
      for (const p of prefixes) {
        if (res.startsWith(p)) {
          const stripped = res.substring(p.length);
          res = stripped.startsWith('.') ? stripped.substring(1) : stripped;
          break;
        }
      }
    }
    if (!res) return 'Core';
    if (/^[a-z]+$/i.test(res)) {
      return res.charAt(0).toUpperCase() + res.slice(1);
    }
    return res;
  }

  /* ── Public Data & Control API ───────────────────────────────────────────── */

  setData(nodes, edges) {
    const dpr = window.devicePixelRatio || 1;
    const cx = (this._canvas.width / dpr) / 2;
    const cy = (this._canvas.height / dpr) / 2;

    // 1. Build degree map & adjacency
    const inDegrees  = {};
    const outDegrees = {};
    this._connectedMap.clear();

    for (const n of nodes) {
      inDegrees[n.id]  = 0;
      outDegrees[n.id] = 0;
      this._connectedMap.set(n.id, new Set());
    }

    for (const e of edges) {
      if (outDegrees[e.source] !== undefined) outDegrees[e.source]++;
      if (inDegrees[e.target] !== undefined)  inDegrees[e.target]++;
      if (this._connectedMap.has(e.source)) this._connectedMap.get(e.source).add(e.target);
      if (this._connectedMap.has(e.target)) this._connectedMap.get(e.target).add(e.source);
    }

    // 2. Detect & assign Graphify communities (by Java package / module)
    this._communityMap.clear();
    const pkgCounts = {};
    for (const n of nodes) {
      const { pkg } = this._extractPackageAndClass(n.id, n.type || 'METHOD');
      const finalPkg = n.package || pkg || 'default';
      pkgCounts[finalPkg] = (pkgCounts[finalPkg] || 0) + 1;
    }

    const sortedPkgs = Object.keys(pkgCounts).sort((a, b) => pkgCounts[b] - pkgCounts[a]);
    this._communities = sortedPkgs.map((pkg, idx) => {
      const color = GRAPHIFY_COLORS[idx % GRAPHIFY_COLORS.length];
      const comm = {
        cid: idx,
        rawLabel: pkg,
        label: this._formatPackageLabel(pkg),
        color,
        count: pkgCounts[pkg],
        nodes: new Set(),
        hidden: false,
      };
      this._communityMap.set(pkg, comm);
      return comm;
    });

    // 3. Initialize nodes with positions spread by class constellations
    const uniqueClasses = Array.from(new Set(nodes.map(n => {
      const { className } = this._extractPackageAndClass(n.id, n.type || 'METHOD');
      return className || 'default';
    })));
    const classAngles = {};
    uniqueClasses.forEach((c, idx) => {
      classAngles[c] = (idx / Math.max(uniqueClasses.length, 1)) * Math.PI * 2;
    });

    const isLargeSet = nodes.length > 25;
    const spread = Math.max(cx, cy) * (isLargeSet ? 0.95 : 0.75) + Math.sqrt(nodes.length) * (isLargeSet ? 70 : 55);

    this._nodes = nodes.map((n, i) => {
      const { pkg, className, memberName } = this._extractPackageAndClass(n.id, n.type || 'METHOD');
      const finalPkg = n.package || pkg || 'default';
      const comm = this._communityMap.get(finalPkg) || this._communities[0];
      comm.nodes.add(n.id);

      const deg = (inDegrees[n.id] || 0) + (outDegrees[n.id] || 0);
      const cAngle = classAngles[className || 'default'] !== undefined
        ? classAngles[className || 'default']
        : ((i / Math.max(nodes.length, 1)) * Math.PI * 2);
      const cCenterDist = isLargeSet ? spread * 0.55 : spread * 0.35;
      const cX = cx + Math.cos(cAngle) * cCenterDist;
      const cY = cy + Math.sin(cAngle) * cCenterDist;

      const subAngle = Math.random() * Math.PI * 2;
      const subDist = (Math.random() * (isLargeSet ? 120 : 60));

      return {
        ...n,
        package: finalPkg,
        className: className,
        memberName: memberName,
        community: comm.cid,
        communityLabel: comm.label,
        communityColor: comm.color,
        inDegree: inDegrees[n.id] || 0,
        outDegree: outDegrees[n.id] || 0,
        degree: deg,
        radius: (isLargeSet && n.type !== 'CLASS' ? PHYSICS.nodeBaseRadius - 1 : PHYSICS.nodeBaseRadius) + Math.min(8, Math.sqrt(deg) * 1.8) + (n.role === 'root' ? 3 : 0) + (n.type === 'CLASS' ? 5 : 0),
        x: cX + Math.cos(subAngle) * subDist,
        y: cY + Math.sin(subAngle) * subDist,
        vx: 0,
        vy: 0,
        _fx: 0,
        _fy: 0,
        pinned: false,
      };
    });

    this._edges = edges.map(e => ({ ...e }));
    this._ticks = 0;
    this._particles = [];
    this._hoveredNode = null;
    this._selectedNode = null;
    this._physicsEnabled = true;

    // Update Graphify Legend & HUD
    this._renderCommunityLegend();
    this._populateSearchDropdown();

    // Auto-stabilize with anti-overlap and auto-fit to screen
    this._runInitialStabilization();
    this.fitToScreen();
    this._hideNodeCard();
  }

  setHeatData(heatMap) {
    this._heatData = heatMap || {};
    let max = 1;
    for (const v of Object.values(this._heatData)) {
      if (typeof v === 'number' && v > max) max = v;
      else if (v && typeof v.commitCount === 'number' && v.commitCount > max) max = v.commitCount;
    }
    this._heatMax = max;
  }

  toggleHeat() {
    this._heatMode = !this._heatMode;
    const btn = document.getElementById('btn-heat');
    if (btn) {
      btn.classList.toggle('active', this._heatMode);
      btn.setAttribute('aria-pressed', String(this._heatMode));
    }
    if (this._heatMode && Object.keys(this._heatData).length === 0) {
      if (typeof window.loadGitHeatData === 'function') {
        window.loadGitHeatData();
      }
    }
    return this._heatMode;
  }

  togglePhysics() {
    this._physicsEnabled = !this._physicsEnabled;
    const btn = document.getElementById('btn-toggle-physics');
    if (btn) btn.classList.toggle('active', this._physicsEnabled);
    return this._physicsEnabled;
  }

  toggleHulls() {
    this._showHulls = !this._showHulls;
    const btn = document.getElementById('btn-toggle-hulls');
    if (btn) btn.classList.toggle('active', this._showHulls);
    return this._showHulls;
  }

  clear() {
    this._nodes = [];
    this._edges = [];
    this._communities = [];
    this._particles = [];
    this._hoveredNode = null;
    this._selectedNode = null;
    this._hideNodeCard();
    this._hideTooltip();
    const legend = document.getElementById('graph-community-legend');
    if (legend) legend.style.display = 'none';
    const emptyState = document.getElementById('graph-empty');
    if (emptyState) emptyState.style.display = 'flex';
  }

  fitToScreen() {
    if (this._nodes.length === 0) return;
    const dpr = window.devicePixelRatio || 1;
    const W = this._canvas.width / dpr;
    const H = this._canvas.height / dpr;

    let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
    for (const n of this._nodes) {
      if (this._hiddenCommunities.has(n.community)) continue;
      minX = Math.min(minX, n.x - n.radius);
      maxX = Math.max(maxX, n.x + n.radius);
      minY = Math.min(minY, n.y - n.radius);
      maxY = Math.max(maxY, n.y + n.radius);
    }

    if (minX === Infinity) return;

    const graphW = Math.max(maxX - minX + 220, 100);
    const graphH = Math.max(maxY - minY + 220, 100);
    const scaleX = (W * 0.88) / graphW;
    const scaleY = (H * 0.88) / graphH;
    const newSc  = Math.min(1.25, Math.max(0.18, Math.min(scaleX, scaleY)));

    const midX = (minX + maxX) / 2;
    const midY = (minY + maxY) / 2;

    this._sc = newSc;
    this._tx = W / 2 - midX * newSc;
    this._ty = H / 2 - midY * newSc;
  }

  focusNode(nodeId, scale = 1.35) {
    const node = this._nodes.find(n => n.id === nodeId);
    if (!node) return;

    this._selectedNode = node;
    const dpr = window.devicePixelRatio || 1;
    const W = this._canvas.width / dpr;
    const H = this._canvas.height / dpr;

    this._sc = scale;
    this._tx = W / 2 - node.x * scale;
    this._ty = H / 2 - node.y * scale;

    this._showNodeCard(node);
  }

  zoomBy(factor) {
    const dpr = window.devicePixelRatio || 1;
    const cx = (this._canvas.width / dpr) / 2;
    const cy = (this._canvas.height / dpr) / 2;
    const oldSc = this._sc;
    const newSc = Math.max(0.1, Math.min(oldSc * factor, 4.5));
    this._tx = cx - (cx - this._tx) * (newSc / oldSc);
    this._ty = cy - (cy - this._ty) * (newSc / oldSc);
    this._sc = newSc;
  }

  /* ── Physics Simulation ─────────────────────────────────────────────────── */

  _runInitialStabilization() {
    for (let i = 0; i < 120; i++) {
      this._simulateTick();
    }
  }

  _simulateTick() {
    const nodes = this._nodes;
    const n = nodes.length;
    if (n === 0) return;

    const dpr = window.devicePixelRatio || 1;
    const cx = (this._canvas.width / dpr) / 2;
    const cy = (this._canvas.height / dpr) / 2;

    // Reset force accumulators
    for (let i = 0; i < n; i++) {
      nodes[i]._fx = 0;
      nodes[i]._fy = 0;
    }

    // 1. Repulsion force between all node pairs with strong anti-overlap
    for (let i = 0; i < n; i++) {
      for (let j = i + 1; j < n; j++) {
        const ni = nodes[i], nj = nodes[j];
        if (this._hiddenCommunities.has(ni.community) || this._hiddenCommunities.has(nj.community)) continue;

        const dx = nj.x - ni.x;
        const dy = nj.y - ni.y;
        const distSq = dx * dx + dy * dy || 0.01;
        const dist = Math.sqrt(distSq);

        const minClearance = ni.radius + nj.radius + 45;
        let rep = 0;
        if (dist < minClearance) {
          rep = (PHYSICS.repulsion * 4.0) / Math.max(dist, 10);
        } else {
          rep = (PHYSICS.repulsion * (1 + (ni.degree + nj.degree) * 0.12)) / distSq;
        }

        const fx = (dx / dist) * rep;
        const fy = (dy / dist) * rep;

        ni._fx -= fx; ni._fy -= fy;
        nj._fx += fx; nj._fy += fy;
      }
    }

    // 2. Gentle spring attraction along edges
    const nodeIndex = Object.fromEntries(nodes.map((nd, idx) => [nd.id, idx]));
    for (const e of this._edges) {
      const si = nodeIndex[e.source];
      const ti = nodeIndex[e.target];
      if (si === undefined || ti === undefined) continue;

      const src = nodes[si], tgt = nodes[ti];
      if (this._hiddenCommunities.has(src.community) || this._hiddenCommunities.has(tgt.community)) continue;

      const dx = tgt.x - src.x;
      const dy = tgt.y - src.y;
      const dist = Math.sqrt(dx * dx + dy * dy) || 0.1;
      const f = (dist - PHYSICS.springLen) * PHYSICS.springK;
      const fx = (dx / dist) * f;
      const fy = (dy / dist) * f;

      src._fx += fx; src._fy += fy;
      tgt._fx -= fx; tgt._fy -= fy;
    }

    // 3. Subtle community cohesion force + Class Constellation Cohesion
    for (let i = 0; i < n; i++) {
      for (let j = i + 1; j < n; j++) {
        const ni = nodes[i], nj = nodes[j];
        if (ni.community === nj.community) {
          const dx = nj.x - ni.x;
          const dy = nj.y - ni.y;
          const dist = Math.sqrt(dx * dx + dy * dy) || 0.1;

          // Intra-class constellation cohesion
          if (ni.className && ni.className === nj.className) {
            if (dist > PHYSICS.springLen * 0.7) {
              const f = (dist - PHYSICS.springLen * 0.7) * (PHYSICS.springK * 0.45);
              const fx = (dx / dist) * f;
              const fy = (dy / dist) * f;
              ni._fx += fx; ni._fy += fy;
              nj._fx -= fx; nj._fy -= fy;
            }
          } else if (dist > PHYSICS.springLen * 1.6) {
            const f = (dist - PHYSICS.springLen) * PHYSICS.clusterK;
            const fx = (dx / dist) * f;
            const fy = (dy / dist) * f;
            ni._fx += fx; ni._fy += fy;
            nj._fx -= fx; nj._fy -= fy;
          }
        }
      }
    }

    // 4. Integrate velocity and update position
    for (let i = 0; i < n; i++) {
      const nd = nodes[i];
      if (nd.pinned) { nd.vx = 0; nd.vy = 0; continue; }

      // Soft centering pull
      nd._fx += (cx - nd.x) * PHYSICS.centerForce;
      nd._fy += (cy - nd.y) * PHYSICS.centerForce;

      nd.vx = (nd.vx + nd._fx) * PHYSICS.damping;
      nd.vy = (nd.vy + nd._fy) * PHYSICS.damping;

      // Limit speed
      const speed = Math.sqrt(nd.vx * nd.vx + nd.vy * nd.vy);
      if (speed > 14) {
        nd.vx = (nd.vx / speed) * 14;
        nd.vy = (nd.vy / speed) * 14;
      }

      nd.x += nd.vx;
      nd.y += nd.vy;
    }

    // 5. Multi-pass Hard Collision Separation (mathematically guarantees NO OVERLAP)
    for (let iter = 0; iter < 4; iter++) {
      for (let i = 0; i < n; i++) {
        for (let j = i + 1; j < n; j++) {
          const ni = nodes[i], nj = nodes[j];
          if (this._hiddenCommunities.has(ni.community) || this._hiddenCommunities.has(nj.community)) continue;

          const dx = nj.x - ni.x;
          const dy = nj.y - ni.y;
          const dist = Math.sqrt(dx * dx + dy * dy) || 0.01;
          const requiredDist = ni.radius + nj.radius + 38;

          if (dist < requiredDist) {
            const overlap = (requiredDist - dist) * 0.5;
            const ux = dx / dist;
            const uy = dy / dist;

            if (!ni.pinned) {
              ni.x -= ux * overlap;
              ni.y -= uy * overlap;
            }
            if (!nj.pinned) {
              nj.x += ux * overlap;
              nj.y += uy * overlap;
            }
          }
        }
      }
    }

    this._ticks++;
  }

  /* ── Rendering Loop ─────────────────────────────────────────────────────── */

  _startLoop() {
    const loop = () => {
      if (this._physicsEnabled && this._ticks < PHYSICS.maxTicks) {
        this._simulateTick();
      }
      this._draw();
      if (this._showMinimap) this._drawMinimap();
      this._rafId = requestAnimationFrame(loop);
    };
    if (this._rafId) cancelAnimationFrame(this._rafId);
    this._rafId = requestAnimationFrame(loop);
  }

  _draw() {
    const ctx = this._ctx;
    const dpr = window.devicePixelRatio || 1;
    const W   = this._canvas.width / dpr;
    const H   = this._canvas.height / dpr;

    ctx.save();
    ctx.scale(dpr, dpr);
    ctx.clearRect(0, 0, W, H);

    // Cyber-industrial subtle dot-grid background
    this._drawGridBackground(ctx, W, H);

    // World transformation
    ctx.save();
    ctx.translate(this._tx, this._ty);
    ctx.scale(this._sc, this._sc);

    // 1. Hyperedges / Community Convex Hulls
    if (this._showHulls) {
      this._drawCommunityHulls(ctx);
    }

    // 2. Directed Edges & Flowing Energy Particles
    this._drawEdges(ctx);

    // 3. Nodes with High-Contrast Badges & Bloom Glow
    this._drawNodes(ctx);

    ctx.restore();
    ctx.restore();
  }

  _drawGridBackground(ctx, W, H) {
    const themeKey = this._activeTheme || 'midnight';

    if (themeKey === 'cyberpunk') {
      // 🟣 CYBERPUNK: Synthwave grid, neon perspective crosshairs, CRT scanlines, purple nebula
      ctx.fillStyle = '#040008';
      ctx.fillRect(0, 0, W, H);

      // Deep magenta/cyan ambient nebula
      const grad = ctx.createRadialGradient(W / 2, H / 2, 40, W / 2, H / 2, Math.max(W, H) * 0.8);
      grad.addColorStop(0, 'rgba(224, 64, 251, 0.15)');
      grad.addColorStop(0.5, 'rgba(0, 229, 255, 0.05)');
      grad.addColorStop(1, 'rgba(4, 0, 8, 0.95)');
      ctx.fillStyle = grad;
      ctx.fillRect(0, 0, W, H);

      if (this._showGrid) {
        const step = 36;
        const offX = (this._tx % (step * this._sc) + step * this._sc) % (step * this._sc);
        const offY = (this._ty % (step * this._sc) + step * this._sc) % (step * this._sc);

        // Neon synth grid lines
        ctx.strokeStyle = 'rgba(224, 64, 251, 0.07)';
        ctx.lineWidth = 1;
        ctx.beginPath();
        for (let x = offX; x < W; x += step * this._sc) {
          ctx.moveTo(x, 0);
          ctx.lineTo(x, H);
        }
        for (let y = offY; y < H; y += step * this._sc) {
          ctx.moveTo(0, y);
          ctx.lineTo(W, y);
        }
        ctx.stroke();

        // Glowing cyan crosshairs at intersections
        ctx.strokeStyle = 'rgba(0, 229, 255, 0.35)';
        ctx.lineWidth = 1.2;
        const arm = 3;
        ctx.beginPath();
        for (let x = offX; x < W; x += step * this._sc) {
          for (let y = offY; y < H; y += step * this._sc) {
            ctx.moveTo(x - arm, y);
            ctx.lineTo(x + arm, y);
            ctx.moveTo(x, y - arm);
            ctx.lineTo(x, y + arm);
          }
        }
        ctx.stroke();
      }

      // Subtle CRT scanline overlay
      ctx.fillStyle = 'rgba(224, 64, 251, 0.015)';
      for (let y = 0; y < H; y += 4) {
        ctx.fillRect(0, y, W, 1);
      }

    } else if (themeKey === 'ember') {
      // 🔥 EMBER: Molten hearth glow, warm bronze background, golden diamond constellation ticks
      ctx.fillStyle = '#110d08';
      ctx.fillRect(0, 0, W, H);

      // Warm radial hearth glow
      const grad = ctx.createRadialGradient(W / 2, H / 2, 60, W / 2, H / 2, Math.max(W, H) * 0.75);
      grad.addColorStop(0, 'rgba(232, 127, 23, 0.18)');
      grad.addColorStop(0.45, 'rgba(180, 83, 9, 0.08)');
      grad.addColorStop(1, 'rgba(17, 13, 8, 0.95)');
      ctx.fillStyle = grad;
      ctx.fillRect(0, 0, W, H);

      if (this._showGrid) {
        const step = 36;
        const offX = (this._tx % (step * this._sc) + step * this._sc) % (step * this._sc);
        const offY = (this._ty % (step * this._sc) + step * this._sc) % (step * this._sc);

        // Diamond spark ticks (◆)
        ctx.fillStyle = 'rgba(245, 158, 11, 0.14)';
        let countX = 0;
        for (let x = offX; x < W; x += step * this._sc, countX++) {
          let countY = 0;
          for (let y = offY; y < H; y += step * this._sc, countY++) {
            const isMajor = (countX % 3 === 0 && countY % 3 === 0);
            ctx.save();
            ctx.translate(x, y);
            ctx.rotate(Math.PI / 4);
            const size = isMajor ? 3.5 : 2;
            ctx.fillStyle = isMajor ? 'rgba(251, 191, 36, 0.45)' : 'rgba(245, 158, 11, 0.14)';
            ctx.fillRect(-size / 2, -size / 2, size, size);
            ctx.restore();
          }
        }
      }

    } else if (themeKey === 'arctic') {
      // ❄️ ARCTIC: Light architectural drafting paper, crisp blue-gray orthogonal grid, blueprint ticks
      ctx.fillStyle = '#f1f5f9';
      ctx.fillRect(0, 0, W, H);

      // Subtle cool daylight vignette
      const grad = ctx.createRadialGradient(W / 2, H / 2, 80, W / 2, H / 2, Math.max(W, H) * 0.85);
      grad.addColorStop(0, 'rgba(248, 250, 252, 0.9)');
      grad.addColorStop(1, 'rgba(226, 232, 240, 0.7)');
      ctx.fillStyle = grad;
      ctx.fillRect(0, 0, W, H);

      if (this._showGrid) {
        const step = 28;
        const offX = (this._tx % (step * this._sc) + step * this._sc) % (step * this._sc);
        const offY = (this._ty % (step * this._sc) + step * this._sc) % (step * this._sc);

        // Fine drafting grid lines
        ctx.strokeStyle = 'rgba(30, 107, 184, 0.06)';
        ctx.lineWidth = 1;
        ctx.beginPath();
        for (let x = offX; x < W; x += step * this._sc) {
          ctx.moveTo(x, 0);
          ctx.lineTo(x, H);
        }
        for (let y = offY; y < H; y += step * this._sc) {
          ctx.moveTo(0, y);
          ctx.lineTo(W, y);
        }
        ctx.stroke();

        // Major blueprint grid intersections (every 4th step)
        ctx.strokeStyle = 'rgba(30, 107, 184, 0.22)';
        ctx.lineWidth = 1.2;
        const tick = 4;
        ctx.beginPath();
        let mX = 0;
        for (let x = offX; x < W; x += step * this._sc, mX++) {
          let mY = 0;
          for (let y = offY; y < H; y += step * this._sc, mY++) {
            if (mX % 4 === 0 && mY % 4 === 0) {
              ctx.moveTo(x - tick, y); ctx.lineTo(x + tick, y);
              ctx.moveTo(x, y - tick); ctx.lineTo(x, y + tick);
            }
          }
        }
        ctx.stroke();
      }

    } else if (themeKey === 'forest') {
      // 🌲 FOREST: Deep bioluminescent canopy, tactical sonar radar rings, firefly spore dots
      ctx.fillStyle = '#040a05';
      ctx.fillRect(0, 0, W, H);

      // Deep bioluminescent green bloom
      const grad = ctx.createRadialGradient(W / 2, H / 2, 50, W / 2, H / 2, Math.max(W, H) * 0.75);
      grad.addColorStop(0, 'rgba(34, 197, 94, 0.14)');
      grad.addColorStop(0.5, 'rgba(16, 185, 129, 0.05)');
      grad.addColorStop(1, 'rgba(4, 10, 5, 0.95)');
      ctx.fillStyle = grad;
      ctx.fillRect(0, 0, W, H);

      if (this._showGrid) {
        // Tactical sonar range rings centered at camera position
        const cx = this._tx;
        const cy = this._ty;
        const ringStep = 110 * this._sc;
        ctx.save();
        ctx.strokeStyle = 'rgba(34, 197, 94, 0.06)';
        ctx.lineWidth = 1;
        for (let r = ringStep; r < Math.max(W, H) * 2; r += ringStep) {
          ctx.beginPath();
          ctx.arc(cx, cy, r, 0, Math.PI * 2);
          ctx.stroke();
        }

        // 45-degree tactical cross axes
        ctx.strokeStyle = 'rgba(34, 197, 94, 0.04)';
        ctx.beginPath();
        ctx.moveTo(cx - 3000, cy); ctx.lineTo(cx + 3000, cy);
        ctx.moveTo(cx, cy - 3000); ctx.lineTo(cx, cy + 3000);
        ctx.stroke();
        ctx.restore();

        // Bio-spore matrix dots
        const step = 34;
        const offX = (this._tx % (step * this._sc) + step * this._sc) % (step * this._sc);
        const offY = (this._ty % (step * this._sc) + step * this._sc) % (step * this._sc);

        ctx.fillStyle = 'rgba(74, 222, 128, 0.16)';
        for (let x = offX; x < W; x += step * this._sc) {
          for (let y = offY; y < H; y += step * this._sc) {
            ctx.beginPath();
            ctx.arc(x, y, 1.2, 0, Math.PI * 2);
            ctx.fill();
          }
        }
      }

    } else {
      // 🌑 MIDNIGHT: Industrial neutral dark charcoal, blueprint dot matrix, precision telemetry crosshairs
      ctx.fillStyle = '#0d1117';
      ctx.fillRect(0, 0, W, H);

      // Neutral ambient gradient
      const grad = ctx.createRadialGradient(W / 2, H / 2, 50, W / 2, H / 2, Math.max(W, H) * 0.75);
      grad.addColorStop(0, 'rgba(22, 27, 34, 0.40)');
      grad.addColorStop(1, 'rgba(13, 17, 23, 0.95)');
      ctx.fillStyle = grad;
      ctx.fillRect(0, 0, W, H);

      if (this._showGrid) {
        const step = 32;
        const offX = (this._tx % (step * this._sc) + step * this._sc) % (step * this._sc);
        const offY = (this._ty % (step * this._sc) + step * this._sc) % (step * this._sc);

        ctx.fillStyle = 'rgba(255, 255, 255, 0.05)';
        for (let x = offX; x < W; x += step * this._sc) {
          for (let y = offY; y < H; y += step * this._sc) {
            ctx.beginPath();
            ctx.arc(x, y, 1.1, 0, Math.PI * 2);
            ctx.fill();
          }
        }

        // Precision crosshair markers every 4th step
        ctx.strokeStyle = 'rgba(59, 130, 246, 0.22)';
        ctx.lineWidth = 1;
        const ch = 3;
        ctx.beginPath();
        let kX = 0;
        for (let x = offX; x < W; x += step * this._sc, kX++) {
          let kY = 0;
          for (let y = offY; y < H; y += step * this._sc, kY++) {
            if (kX % 4 === 0 && kY % 4 === 0) {
              ctx.moveTo(x - ch, y); ctx.lineTo(x + ch, y);
              ctx.moveTo(x, y - ch); ctx.lineTo(x, y + ch);
            }
          }
        }
        ctx.stroke();
      }
    }
  }

  /* ── 1. Graphify Community Convex Hulls ───────────────────────────────────── */

  _drawCommunityHulls(ctx) {
    for (const comm of this._communities) {
      if (this._hiddenCommunities.has(comm.cid)) continue;

      const memberNodes = this._nodes.filter(n => n.community === comm.cid);
      if (memberNodes.length < 2) continue;

      const pts = memberNodes.map(n => ({ x: n.x, y: n.y }));
      const hull = getConvexHull(pts);
      const expanded = expandHull(hull, 36);
      if (expanded.length < 2) continue;

      ctx.save();
      ctx.beginPath();
      ctx.moveTo(expanded[0].x, expanded[0].y);
      for (let i = 1; i < expanded.length; i++) {
        ctx.lineTo(expanded[i].x, expanded[i].y);
      }
      ctx.closePath();

      // Shaded translucent fill
      ctx.fillStyle = hexToRgba(comm.color, 0.07);
      ctx.fill();

      // Glowing border line
      ctx.strokeStyle = hexToRgba(comm.color, 0.38);
      ctx.lineWidth = 1.5;
      ctx.lineJoin = 'round';
      ctx.stroke();

      // Community / Package Tag Label
      const cx = expanded.reduce((s, p) => s + p.x, 0) / expanded.length;
      const minY = Math.min(...expanded.map(p => p.y));

      ctx.font = '600 11px Space Grotesk, system-ui, sans-serif';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'bottom';

      // Label background pill
      const labelText = comm.label;
      const metrics = ctx.measureText(labelText);
      const pw = metrics.width + 16;
      const ph = 18;
      const px = cx - pw / 2;
      const py = minY - 8 - ph;

      ctx.fillStyle = 'rgba(13, 17, 23, 0.85)';
      ctx.strokeStyle = hexToRgba(comm.color, 0.4);
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.roundRect(px, py, pw, ph, 4);
      ctx.fill();
      ctx.stroke();

      ctx.fillStyle = comm.color;
      ctx.fillText(labelText, cx, minY - 11);

      ctx.restore();
    }
  }

  /* ── 2. Curved Directed Edges & Flow Particles ───────────────────────────── */

  _drawEdges(ctx) {
    const reducedMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    const now = Date.now();
    // Spawn energy particles periodically if motion is enabled
    if (this._showParticles && !reducedMotion && now - this._lastParticleSpawn > 320 && this._edges.length > 0 && this._particles.length < 40) {
      this._lastParticleSpawn = now;
      const edgeIdx = Math.floor(Math.random() * this._edges.length);
      this._particles.push({ edgeIdx, t: 0, speed: 0.007 + Math.random() * 0.006 });
    }

    // Advance particles
    if (this._showParticles && !reducedMotion) {
      this._particles = this._particles.filter(p => p.t <= 1);
      for (const p of this._particles) p.t += p.speed;
    } else if (!this._showParticles || reducedMotion) {
      this._particles = [];
    }

    const activeNode = this._hoveredNode || this._selectedNode;
    const connectedSet = activeNode ? this._connectedMap.get(activeNode.id) : null;

    const nodeIndex = Object.fromEntries(this._nodes.map((nd, idx) => [nd.id, idx]));

    for (let i = 0; i < this._edges.length; i++) {
      const e = this._edges[i];
      const si = nodeIndex[e.source];
      const ti = nodeIndex[e.target];
      if (si === undefined || ti === undefined) continue;

      const src = this._nodes[si], tgt = this._nodes[ti];
      if (this._hiddenCommunities.has(src.community) || this._hiddenCommunities.has(tgt.community)) continue;

      // Focus opacity logic
      let opacity = 0.65;
      if (activeNode) {
        const isIncident = (src.id === activeNode.id || tgt.id === activeNode.id);
        opacity = isIncident ? 1.0 : 0.12;
      }

      const edgeParticles = this._particles.filter(p => p.edgeIdx === i);
      this._drawSingleEdge(ctx, src, tgt, e, opacity, edgeParticles);
    }
  }

  _drawSingleEdge(ctx, src, tgt, edge, opacity, particles) {
    const dx = tgt.x - src.x;
    const dy = tgt.y - src.y;
    const dist = Math.sqrt(dx * dx + dy * dy);
    if (dist < src.radius + tgt.radius + 4) return;

    const ux = dx / dist;
    const uy = dy / dist;

    // Trim endpoints to node boundaries
    const sx = src.x + ux * src.radius;
    const sy = src.y + uy * src.radius;
    const ex = tgt.x - ux * (tgt.radius + 5);
    const ey = tgt.y - uy * (tgt.radius + 5);

    // Quadratic bezier control point with smooth 0.18 roundness
    const mx = (sx + ex) / 2 - uy * 22;
    const my = (sy + ey) / 2 + ux * 22;

    const baseColor = GC.edgeKind[edge.kind] || GC.edgeKind.default;

    ctx.save();
    ctx.beginPath();
    ctx.moveTo(sx, sy);
    ctx.quadraticCurveTo(mx, my, ex, ey);
    ctx.strokeStyle = hexToRgba(baseColor, opacity);
    ctx.lineWidth = opacity > 0.8 ? 2.2 : 1.4;
    ctx.stroke();

    // Arrowhead
    const tx = 2 * (ex - mx);
    const ty = 2 * (ey - my);
    const ta = Math.atan2(ty, tx);
    const as = 8.5;

    ctx.beginPath();
    ctx.moveTo(ex, ey);
    ctx.lineTo(ex - as * Math.cos(ta - 0.45), ey - as * Math.sin(ta - 0.45));
    ctx.lineTo(ex - as * Math.cos(ta + 0.45), ey - as * Math.sin(ta + 0.45));
    ctx.closePath();
    ctx.fillStyle = hexToRgba(baseColor, opacity);
    ctx.fill();

    // Flowing Energy Particles
    if (particles && particles.length > 0 && opacity > 0.3) {
      for (const p of particles) {
        const mt = 1 - p.t;
        const px = mt * mt * sx + 2 * mt * p.t * mx + p.t * p.t * ex;
        const py = mt * mt * sy + 2 * mt * p.t * my + p.t * p.t * ey;

        ctx.beginPath();
        ctx.arc(px, py, 4, 0, Math.PI * 2);
        ctx.fillStyle = hexToRgba(baseColor, 0.45);
        ctx.fill();

        ctx.beginPath();
        ctx.arc(px, py, 1.8, 0, Math.PI * 2);
        ctx.fillStyle = '#ffffff';
        ctx.fill();
      }
    }

    ctx.restore();
  }

  /* ── 3. Graphify Nodes Rendering ─────────────────────────────────────────── */

  _drawNodes(ctx) {
    const activeNode = this._hoveredNode || this._selectedNode;
    const connectedSet = activeNode ? this._connectedMap.get(activeNode.id) : null;
    const isDenseGraph = this._nodes.length > 25;
    const isZoomedIn = this._sc >= 1.25;

    for (const node of this._nodes) {
      if (this._hiddenCommunities.has(node.community)) continue;

      let opacity = 1.0;
      if (activeNode) {
        const isSelf = (node.id === activeNode.id);
        const isNeighbor = connectedSet && connectedSet.has(node.id);
        opacity = (isSelf || isNeighbor) ? 1.0 : 0.18;
      }

      const isHovered = (node === this._hoveredNode);
      const isSelected = (node === this._selectedNode);
      const isConnected = connectedSet && connectedSet.has(node.id);

      // Semantic LOD: in dense graphs, only draw text label for active/neighbor/focal nodes unless zoomed in
      let shouldDrawLabel = this._showLabels;
      if (shouldDrawLabel && isDenseGraph && !isZoomedIn) {
        shouldDrawLabel = isHovered || isSelected || isConnected || node.role === 'root' || node.role === 'class' || (node.degree >= 5);
      }

      this._drawSingleNode(ctx, node, opacity, isHovered, isSelected, shouldDrawLabel);
    }
  }

  _drawSingleNode(ctx, node, opacity, isHovered, isSelected, shouldDrawLabel = true) {
    const r = node.radius;
    const x = node.x;
    const y = node.y;

    // Pick base and glow color
    let mainColor = node.communityColor;
    let heatRatio = 0;

    if (this._heatMode) {
      const count = (this._heatData[node.id] !== undefined)
        ? this._heatData[node.id]
        : ((node.label && this._heatData[node.label] !== undefined)
            ? this._heatData[node.label]
            : (node.id ? this._heatData[node.id.replace(/\(.*\)/, '')] : 0)) || 0;

      heatRatio = Math.min(count / (this._heatMax || 1), 1);

      if (count === 0) {
        mainColor = '#475569';
      } else if (heatRatio < 0.35) {
        mainColor = lerpColor('#38bdf8', '#f59e0b', heatRatio / 0.35);
      } else {
        mainColor = lerpColor('#f59e0b', '#ef4444', (heatRatio - 0.35) / 0.65);
      }
    } else if (node.role === 'root') {
      mainColor = GC.roles.root;
    }

    ctx.save();
    ctx.globalAlpha = opacity;

    // 1. Ambient Bloom Glow (Hovered / Selected / Root)
    if (isHovered || isSelected || node.role === 'root' || (this._heatMode && heatRatio > 0.15)) {
      const glowRadius = r + (isSelected ? 16 : 10) + heatRatio * 12;
      const glowGrad = ctx.createRadialGradient(x, y, r * 0.8, x, y, glowRadius);
      glowGrad.addColorStop(0, hexToRgba(mainColor, 0.45));
      glowGrad.addColorStop(1, 'transparent');

      ctx.beginPath();
      ctx.arc(x, y, glowRadius, 0, Math.PI * 2);
      ctx.fillStyle = glowGrad;
      ctx.fill();
    }

    // 2. Node Circle Fill with 3D Specular Highlight
    ctx.beginPath();
    ctx.arc(x, y, r, 0, Math.PI * 2);
    const fillGrad = ctx.createRadialGradient(x - r * 0.35, y - r * 0.35, r * 0.1, x, y, r);
    fillGrad.addColorStop(0, lerpColor(mainColor, '#ffffff', 0.35));
    fillGrad.addColorStop(0.7, mainColor);
    fillGrad.addColorStop(1, lerpColor(mainColor, '#000000', 0.45));
    ctx.fillStyle = fillGrad;
    ctx.fill();

    // 3. High-Contrast Border Ring
    ctx.beginPath();
    ctx.arc(x, y, r, 0, Math.PI * 2);
    ctx.strokeStyle = isSelected
      ? '#ffffff'
      : (isHovered ? '#f8fafc' : hexToRgba('#ffffff', 0.25));
    ctx.lineWidth = isSelected ? 2.2 : (isHovered ? 1.8 : 1.2);
    ctx.stroke();

    // 4. Type Glyph / Icon inside node (Standard IDE symbols)
    const typeIcons = {
      METHOD:    'm',
      FIELD:     'f',
      CLASS:     'C',
      INTERFACE: 'I',
      ENUM:      'E',
      RECORD:    'R',
    };
    const glyph = typeIcons[node.type] || 'm';

    ctx.font = `bold ${Math.max(8, Math.round(r * 0.75))}px JetBrains Mono, monospace`;
    ctx.fillStyle = '#ffffff';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(glyph, x, y);

    // 5. High-Legibility Colorful Label Pill Below Node (guarded by _showLabels & shouldDrawLabel)
    if (!this._showLabels || !shouldDrawLabel) { ctx.restore(); return; }
    const maxChars = 22;
    const fullLabel = node.label || node.id.split('.').pop() || '';
    const labelText = fullLabel.length > maxChars ? fullLabel.slice(0, maxChars - 1) + '…' : fullLabel;

    ctx.font = `${node.role === 'root' || isSelected ? 'bold' : '500'} 11px Space Grotesk, system-ui, sans-serif`;
    ctx.textAlign = 'left';
    ctx.textBaseline = 'middle';

    const lblY = y + r + 11;

    // Measure text width + add space for colorful dot indicator
    const textMetrics = ctx.measureText(labelText);
    const dotRadius = 3;
    const dotMargin = 6;
    const pw = textMetrics.width + dotRadius * 2 + dotMargin + 14;
    const ph = 18;
    const px = x - pw / 2;

    // Theme-specific label pill styling
    const themeKey = this._activeTheme || 'midnight';
    let pillBg, pillBorder, pillText;
    if (themeKey === 'arctic') {
      pillBg = 'rgba(255, 255, 255, 0.96)';
      pillBorder = isSelected ? '#1e6bb8' : (isHovered ? mainColor : 'rgba(30, 107, 184, 0.35)');
      pillText = isSelected ? '#1e6bb8' : '#1e293b';
    } else if (themeKey === 'cyberpunk') {
      pillBg = 'rgba(12, 6, 20, 0.95)';
      pillBorder = isSelected ? '#00e5ff' : (isHovered ? '#e040fb' : 'rgba(224, 64, 251, 0.55)');
      pillText = isSelected ? '#00e5ff' : '#f5f3ff';
    } else if (themeKey === 'ember') {
      pillBg = 'rgba(26, 21, 16, 0.95)';
      pillBorder = isSelected ? '#fbbf24' : (isHovered ? '#e87f17' : 'rgba(245, 158, 11, 0.45)');
      pillText = isSelected ? '#fbbf24' : '#fef3c7';
    } else if (themeKey === 'forest') {
      pillBg = 'rgba(14, 26, 16, 0.95)';
      pillBorder = isSelected ? '#4ade80' : (isHovered ? '#22c55e' : 'rgba(34, 197, 94, 0.45)');
      pillText = isSelected ? '#4ade80' : '#dcfce7';
    } else {
      pillBg = 'rgba(13, 17, 23, 0.94)';
      pillBorder = isSelected ? '#ffffff' : (isHovered ? mainColor : hexToRgba(mainColor, 0.6));
      pillText = isSelected ? '#ffffff' : (isHovered ? '#ffffff' : '#e2e8f0');
    }

    ctx.fillStyle = pillBg;
    ctx.strokeStyle = pillBorder;
    ctx.lineWidth = isSelected ? 1.8 : (isHovered ? 1.4 : 1.0);
    ctx.beginPath();
    ctx.roundRect(px, lblY - ph / 2, pw, ph, 5);
    ctx.fill();
    ctx.stroke();

    // Community color dot inside pill
    ctx.beginPath();
    ctx.arc(px + 8 + dotRadius, lblY, dotRadius, 0, Math.PI * 2);
    ctx.fillStyle = mainColor;
    ctx.fill();

    // Text label
    ctx.fillStyle = pillText;
    ctx.fillText(labelText, px + 8 + dotRadius * 2 + dotMargin, lblY);

    ctx.restore();
  }

  /* ── 4. Radar Minimap Overview ───────────────────────────────────────────── */

  _drawMinimap() {
    if (!this._minimapCtx || !this._minimapCanvas) return;
    const mctx = this._minimapCtx;
    const MW = this._minimapCanvas.width;
    const MH = this._minimapCanvas.height;

    const themeKey = this._activeTheme || 'midnight';
    let mmBg, mmVpStroke, mmVpFill;
    if (themeKey === 'arctic') {
      mmBg = 'rgba(241, 245, 249, 0.96)';
      mmVpStroke = 'rgba(30, 107, 184, 0.9)';
      mmVpFill = 'rgba(30, 107, 184, 0.10)';
    } else if (themeKey === 'cyberpunk') {
      mmBg = 'rgba(4, 0, 8, 0.96)';
      mmVpStroke = 'rgba(0, 229, 255, 0.9)';
      mmVpFill = 'rgba(224, 64, 251, 0.12)';
    } else if (themeKey === 'ember') {
      mmBg = 'rgba(17, 13, 8, 0.96)';
      mmVpStroke = 'rgba(232, 127, 23, 0.9)';
      mmVpFill = 'rgba(245, 158, 11, 0.12)';
    } else if (themeKey === 'forest') {
      mmBg = 'rgba(4, 10, 5, 0.96)';
      mmVpStroke = 'rgba(74, 222, 128, 0.9)';
      mmVpFill = 'rgba(34, 197, 94, 0.12)';
    } else {
      mmBg = 'rgba(13, 17, 23, 0.95)';
      mmVpStroke = 'rgba(56, 189, 248, 0.85)';
      mmVpFill = 'rgba(56, 189, 248, 0.08)';
    }

    mctx.clearRect(0, 0, MW, MH);
    mctx.fillStyle = mmBg;
    mctx.fillRect(0, 0, MW, MH);

    if (this._nodes.length === 0) return;

    let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
    for (const n of this._nodes) {
      minX = Math.min(minX, n.x);
      maxX = Math.max(maxX, n.x);
      minY = Math.min(minY, n.y);
      maxY = Math.max(maxY, n.y);
    }

    const pad = 80;
    const gw = Math.max(maxX - minX + pad * 2, 100);
    const gh = Math.max(maxY - minY + pad * 2, 100);

    const mScale = Math.min((MW - 12) / gw, (MH - 12) / gh);
    const mOx = MW / 2 - ((minX + maxX) / 2) * mScale;
    const mOy = MH / 2 - ((minY + maxY) / 2) * mScale;

    // Draw minimap nodes
    for (const n of this._nodes) {
      if (this._hiddenCommunities.has(n.community)) continue;
      const mx = n.x * mScale + mOx;
      const my = n.y * mScale + mOy;

      mctx.beginPath();
      mctx.arc(mx, my, Math.max(2, n.radius * mScale), 0, Math.PI * 2);
      mctx.fillStyle = n.communityColor || '#38bdf8';
      mctx.fill();
    }

    // Draw camera viewport box
    const dpr = window.devicePixelRatio || 1;
    const W = this._canvas.width / dpr;
    const H = this._canvas.height / dpr;

    const viewX = (-this._tx / this._sc);
    const viewY = (-this._ty / this._sc);
    const viewW = W / this._sc;
    const viewH = H / this._sc;

    const rvx = viewX * mScale + mOx;
    const rvy = viewY * mScale + mOy;
    const rvw = viewW * mScale;
    const rvh = viewH * mScale;

    mctx.strokeStyle = mmVpStroke;
    mctx.lineWidth = 1.2;
    mctx.strokeRect(rvx, rvy, rvw, rvh);
    mctx.fillStyle = mmVpFill;
    mctx.fillRect(rvx, rvy, rvw, rvh);
  }

  /* ── 5. Graphify HUD & Controls ──────────────────────────────────────────── */

  _initHudControls() {
    // Zoom & Fit Buttons
    const btnFit = document.getElementById('btn-fit');
    if (btnFit) btnFit.onclick = () => this.fitToScreen();

    const btnZoomIn = document.getElementById('btn-zoom-in');
    if (btnZoomIn) btnZoomIn.onclick = () => this.zoomBy(1.25);

    const btnZoomOut = document.getElementById('btn-zoom-out');
    if (btnZoomOut) btnZoomOut.onclick = () => this.zoomBy(0.8);

    const btnReset = document.getElementById('btn-reset');
    if (btnReset) btnReset.onclick = () => this.clear();

    const btnHulls = document.getElementById('btn-toggle-hulls');
    if (btnHulls) btnHulls.onclick = () => this.toggleHulls();

    const btnPhysics = document.getElementById('btn-toggle-physics');
    if (btnPhysics) btnPhysics.onclick = () => this.togglePhysics();

    const btnHeat = document.getElementById('btn-heat');
    if (btnHeat) btnHeat.onclick = () => this.toggleHeat();

    // Node card close
    const btnNodeCardClose = document.getElementById('btn-node-card-close');
    if (btnNodeCardClose) btnNodeCardClose.onclick = () => this._hideNodeCard();

    // Legend close
    const btnLegendClose = document.getElementById('btn-legend-close');
    if (btnLegendClose) {
      btnLegendClose.onclick = () => {
        const legend = document.getElementById('graph-community-legend');
        if (legend) legend.style.display = 'none';
      };
    }
  }

  _renderCommunityLegend() {
    const legendWrap = document.getElementById('graph-community-legend');
    const legendList = document.getElementById('graph-legend-list');
    if (!legendWrap || !legendList) return;

    if (this._communities.length === 0) {
      legendWrap.style.display = 'none';
      return;
    }

    legendWrap.style.display = 'flex';
    legendList.innerHTML = this._communities.map(c => `
      <div class="legend-item ${this._hiddenCommunities.has(c.cid) ? 'dimmed' : ''}" data-cid="${c.cid}">
        <div class="legend-dot" style="background:${c.color}"></div>
        <span class="legend-label" title="${c.label}">${c.label}</span>
        <span class="legend-count">${c.count}</span>
      </div>
    `).join('');

    legendList.querySelectorAll('.legend-item').forEach(item => {
      item.onclick = () => {
        const cid = parseInt(item.dataset.cid, 10);
        if (this._hiddenCommunities.has(cid)) {
          this._hiddenCommunities.delete(cid);
          item.classList.remove('dimmed');
        } else {
          this._hiddenCommunities.add(cid);
          item.classList.add('dimmed');
        }
      };
    });
  }

  _populateSearchDropdown() {
    const searchInput = document.getElementById('graph-node-search');
    const searchResults = document.getElementById('graph-node-search-results');
    if (!searchInput || !searchResults) return;

    searchInput.value = '';
    searchResults.innerHTML = '';
    searchResults.style.display = 'none';

    searchInput.oninput = () => {
      const q = searchInput.value.toLowerCase().trim();
      searchResults.innerHTML = '';
      if (!q) { searchResults.style.display = 'none'; return; }

      const matches = this._nodes.filter(n =>
        (n.label && n.label.toLowerCase().includes(q)) ||
        (n.id && n.id.toLowerCase().includes(q))
      ).slice(0, 15);

      if (matches.length === 0) {
        searchResults.style.display = 'none';
        return;
      }

      searchResults.style.display = 'block';
      matches.forEach(n => {
        const row = document.createElement('div');
        row.className = 'graph-search-item';
        row.style.borderLeft = `3px solid ${n.communityColor}`;
        row.innerHTML = `
          <span class="gsi-label">${n.label}</span>
          <span class="gsi-pkg">${n.package}</span>
        `;
        row.onclick = () => {
          this.focusNode(n.id, 1.4);
          searchResults.style.display = 'none';
          searchInput.value = '';
        };
        searchResults.appendChild(row);
      });
    };

    document.addEventListener('click', e => {
      if (!searchResults.contains(e.target) && e.target !== searchInput) {
        searchResults.style.display = 'none';
      }
    });
  }

  _showNodeCard(node) {
    const card = document.getElementById('graph-node-card');
    if (!card) return;

    card.style.display = 'flex';
    const accentBar = document.getElementById('node-card-accent-bar');
    const iconEl = document.getElementById('node-card-icon');
    const nameEl = document.getElementById('node-card-name');
    const commEl = document.getElementById('node-card-community');
    const typeEl = document.getElementById('node-card-type');
    const degEl  = document.getElementById('node-card-degree');
    const neighborsList = document.getElementById('node-card-neighbors');
    const countEl = document.getElementById('node-card-neighbor-count');

    const commColor = node.communityColor || '#38bdf8';

    // Type color map
    const typeColors = {
      METHOD: '#38bdf8',    // Cyan
      FIELD: '#f59e0b',     // Amber
      CLASS: '#3b82f6',     // Cobalt
      INTERFACE: '#10b981', // Emerald
      ENUM: '#fb7185',      // Rose
      RECORD: '#0d9488',    // Teal
    };
    const typeGlyphs = {
      METHOD:    'm',
      FIELD:     'f',
      CLASS:     'C',
      INTERFACE: 'I',
      ENUM:      'E',
      RECORD:    'R',
    };
    const nodeType = node.type || 'METHOD';
    const tColor = typeColors[nodeType] || commColor;
    const tGlyph = typeGlyphs[nodeType] || '◈';

    // Dynamic colorful card shell
    card.style.borderColor = hexToRgba(commColor, 0.45);
    card.style.boxShadow = `0 16px 44px rgba(0, 0, 0, 0.7), 0 0 24px ${hexToRgba(commColor, 0.2)}, inset 0 1px 0 rgba(255, 255, 255, 0.10)`;

    if (accentBar) {
      accentBar.style.background = `linear-gradient(90deg, ${commColor}, ${tColor}, transparent)`;
    }

    if (iconEl) {
      iconEl.textContent = tGlyph;
      iconEl.style.background = hexToRgba(commColor, 0.16);
      iconEl.style.borderColor = hexToRgba(commColor, 0.45);
      iconEl.style.color = commColor;
      iconEl.style.boxShadow = `0 0 10px ${hexToRgba(commColor, 0.25)}`;
    }

    if (nameEl) {
      nameEl.textContent = node.label || node.id;
    }

    if (commEl) {
      commEl.textContent = this._formatPackageLabel(node.package || node.communityLabel);
      commEl.style.color = commColor;
      commEl.style.background = hexToRgba(commColor, 0.22);
      commEl.style.borderColor = hexToRgba(commColor, 0.55);
      commEl.style.fontWeight = '700';
    }

    const classEl = document.getElementById('node-card-class');
    const classRow = document.getElementById('node-card-class-row');
    if (classEl && classRow) {
      if (node.className) {
        classEl.textContent = node.className;
        classEl.style.color = tColor;
        classEl.style.background = hexToRgba(tColor, 0.22);
        classEl.style.borderColor = hexToRgba(tColor, 0.55);
        classEl.style.fontWeight = '700';
        classRow.style.display = 'flex';
      } else {
        classRow.style.display = 'none';
      }
    }

    if (typeEl) {
      typeEl.textContent = nodeType;
      typeEl.style.color = tColor;
      typeEl.style.background = hexToRgba(tColor, 0.22);
      typeEl.style.borderColor = hexToRgba(tColor, 0.55);
      typeEl.style.fontWeight = '700';
    }

    if (degEl) {
      degEl.innerHTML = `<span style="color:#0284c7; font-weight:700">${node.inDegree} in</span> · <span style="color:#dc2626; font-weight:700">${node.outDegree} out</span> (<span style="color:var(--text-secondary); font-weight:600">${node.degree} total</span>)`;
    }

    // Neighbors list
    const neighborIds = Array.from(this._connectedMap.get(node.id) || []);
    if (countEl) countEl.textContent = neighborIds.length;

    if (neighborsList) {
      if (neighborIds.length === 0) {
        neighborsList.innerHTML = '<span class="nc-empty">No direct connections</span>';
      } else {
        neighborsList.innerHTML = neighborIds.map(nid => {
          const nb = this._nodes.find(n => n.id === nid);
          const nColor = nb ? nb.communityColor : '#64748b';
          const nLabel = nb ? nb.label : nid.split('.').pop();
          const nType = nb ? nb.type : 'METHOD';
          const nGlyph = typeGlyphs[nType] || '•';
          const nTColor = typeColors[nType] || nColor;

          return `
            <button class="neighbor-link" style="border-left-color:${nColor}; border-color:${hexToRgba(nColor, 0.45)}" data-nid="${nid}">
              <span class="neighbor-link-label">${nLabel}</span>
              <span class="neighbor-link-kind" style="background:${hexToRgba(nTColor, 0.22)}; color:${nTColor}; border:1px solid ${hexToRgba(nTColor, 0.55)}">${nGlyph}</span>
            </button>
          `;
        }).join('');

        neighborsList.querySelectorAll('.neighbor-link').forEach(btn => {
          btn.onclick = () => this.focusNode(btn.dataset.nid, 1.4);
        });
      }
    }

    // Position node card safely within visible container bounds
    this._positionNodeCard(node);
  }

  _positionNodeCard(node) {
    const card = document.getElementById('graph-node-card');
    if (!card || card.style.display === 'none') return;

    const container = this._container;
    const cWidth = container.clientWidth || 800;
    const cHeight = container.clientHeight || 600;

    const pad = 14;
    const topPad = 64; // HUD clearance
    const bottomPad = 20;

    if (node) {
      const sx = node.x * this._sc + this._tx;
      const sy = node.y * this._sc + this._ty;

      const cardW = card.offsetWidth || 260;
      const cardH = card.offsetHeight || 280;

      // Position adjacent to the node if room permits
      let x = sx + (node.radius * this._sc) + 16;
      if (x + cardW + pad > cWidth) {
        x = sx - (node.radius * this._sc) - cardW - 16;
      }
      if (x < pad) {
        x = pad;
      }
      if (x + cardW > cWidth - pad) {
        x = Math.max(pad, cWidth - cardW - pad);
      }

      let y = sy - 24;
      if (y + cardH + bottomPad > cHeight) {
        y = cHeight - cardH - bottomPad;
      }
      if (y < topPad) {
        y = topPad;
      }

      card.style.left = `${Math.round(x)}px`;
      card.style.top  = `${Math.round(y)}px`;
    } else {
      this._clampNodeCardToViewport();
    }
  }

  _clampNodeCardToViewport() {
    const card = document.getElementById('graph-node-card');
    if (!card || card.style.display === 'none') return;

    const container = this._container;
    const cWidth = container.clientWidth || 800;
    const cHeight = container.clientHeight || 600;

    const pad = 14;
    const topPad = 64;
    const bottomPad = 20;

    const cardW = card.offsetWidth || 260;
    const cardH = card.offsetHeight || 280;

    let currLeft = parseInt(card.style.left, 10);
    if (isNaN(currLeft)) currLeft = pad;

    let currTop = parseInt(card.style.top, 10);
    if (isNaN(currTop)) currTop = topPad;

    if (currLeft + cardW > cWidth - pad) {
      currLeft = Math.max(pad, cWidth - cardW - pad);
    }
    if (currLeft < pad) {
      currLeft = pad;
    }

    if (currTop + cardH > cHeight - bottomPad) {
      currTop = Math.max(topPad, cHeight - cardH - bottomPad);
    }
    if (currTop < topPad) {
      currTop = topPad;
    }

    card.style.left = `${Math.round(currLeft)}px`;
    card.style.top  = `${Math.round(currTop)}px`;
  }

  _bindNodeCardDrag() {
    const card = document.getElementById('graph-node-card');
    if (!card) return;
    const header = card.querySelector('.node-card-header');
    if (!header) return;

    let isDragging = false;
    let startX = 0, startY = 0;
    let initialLeft = 0, initialTop = 0;

    header.addEventListener('mousedown', (e) => {
      if (e.target.closest('.node-card-close')) return;
      isDragging = true;
      startX = e.clientX;
      startY = e.clientY;
      initialLeft = parseInt(card.style.left, 10) || 14;
      initialTop = parseInt(card.style.top, 10) || 70;
      document.body.style.userSelect = 'none';
    });

    window.addEventListener('mousemove', (e) => {
      if (!isDragging) return;
      const dx = e.clientX - startX;
      const dy = e.clientY - startY;

      const container = this._container;
      const cWidth = container.clientWidth || 800;
      const cHeight = container.clientHeight || 600;
      const pad = 14;
      const topPad = 64;
      const bottomPad = 20;

      const cardW = card.offsetWidth || 260;
      const cardH = card.offsetHeight || 280;

      let newLeft = initialLeft + dx;
      let newTop = initialTop + dy;

      newLeft = Math.max(pad, Math.min(newLeft, cWidth - cardW - pad));
      newTop = Math.max(topPad, Math.min(newTop, cHeight - cardH - bottomPad));

      card.style.left = `${Math.round(newLeft)}px`;
      card.style.top  = `${Math.round(newTop)}px`;
    });

    window.addEventListener('mouseup', () => {
      if (isDragging) {
        isDragging = false;
        document.body.style.userSelect = '';
      }
    });
  }

  _hideNodeCard() {
    const card = document.getElementById('graph-node-card');
    if (card) card.style.display = 'none';
  }

  /* ── Interaction Events ─────────────────────────────────────────────────── */

  _bindEvents() {
    const cv = this._canvas;
    let draggingNode = null;
    let isPanning = false;
    let lastPoint = null;
    let clickStart = null;

    cv.addEventListener('mousedown', e => {
      if (e.button !== 0) return;
      const hit = this._hitTest(e.offsetX, e.offsetY);
      clickStart = { x: e.offsetX, y: e.offsetY };

      if (hit) {
        draggingNode = hit;
        hit.pinned = true;
        cv.style.cursor = 'grabbing';
      } else {
        isPanning = true;
        lastPoint = { x: e.offsetX, y: e.offsetY };
        cv.style.cursor = 'grabbing';
      }
    });

    window.addEventListener('mousemove', e => {
      const rect = cv.getBoundingClientRect();
      const ox = e.clientX - rect.left;
      const oy = e.clientY - rect.top;

      if (draggingNode) {
        const wp = this._screenToWorld(ox, oy);
        draggingNode.x = wp.x;
        draggingNode.y = wp.y;
        draggingNode.vx = 0;
        draggingNode.vy = 0;
      } else if (isPanning && lastPoint) {
        this._tx += ox - lastPoint.x;
        this._ty += oy - lastPoint.y;
        lastPoint = { x: ox, y: oy };
      } else if (ox >= 0 && ox <= rect.width && oy >= 0 && oy <= rect.height) {
        const hit = this._hitTest(ox, oy);
        this._hoveredNode = hit;
        cv.style.cursor = hit ? 'pointer' : 'grab';
        if (hit) this._showTooltip(hit, e.clientX, e.clientY);
        else this._hideTooltip();
      }
    });

    window.addEventListener('mouseup', e => {
      if (draggingNode) {
        const rect = cv.getBoundingClientRect();
        const ox = e.clientX - rect.left;
        const oy = e.clientY - rect.top;
        const dx = ox - (clickStart?.x || 0);
        const dy = oy - (clickStart?.y || 0);

        if (Math.abs(dx) < 7 && Math.abs(dy) < 7) {
          this._selectedNode = draggingNode;
          this._showNodeCard(draggingNode);
          if (this.onNodeClick) this.onNodeClick(draggingNode);
        }
        draggingNode.pinned = false;
        draggingNode = null;
      }
      isPanning = false;
      lastPoint = null;
      cv.style.cursor = 'grab';
    });

    cv.addEventListener('mouseleave', () => {
      this._hoveredNode = null;
      this._hideTooltip();
    });

    cv.addEventListener('wheel', e => {
      e.preventDefault();
      const delta = e.deltaY > 0 ? 0.88 : 1.14;
      const ox = e.offsetX;
      const oy = e.offsetY;
      const oldSc = this._sc;
      const newSc = Math.max(0.1, Math.min(oldSc * delta, 4.5));

      this._tx = ox - (ox - this._tx) * (newSc / oldSc);
      this._ty = oy - (oy - this._ty) * (newSc / oldSc);
      this._sc = newSc;
    }, { passive: false });
  }

  _bindResize() {
    const ro = new ResizeObserver(() => this._resize());
    ro.observe(this._container);
  }

  _resize() {
    const dpr = window.devicePixelRatio || 1;
    const w = this._container.clientWidth || 800;
    const h = this._container.clientHeight || 600;

    this._canvas.width  = Math.round(w * dpr);
    this._canvas.height = Math.round(h * dpr);

    // Keep floating node card clamped within visible viewport
    this._clampNodeCardToViewport();
  }

  _hitTest(screenX, screenY) {
    const wp = this._screenToWorld(screenX, screenY);
    for (let i = this._nodes.length - 1; i >= 0; i--) {
      const n = this._nodes[i];
      if (this._hiddenCommunities.has(n.community)) continue;
      const dx = wp.x - n.x;
      const dy = wp.y - n.y;
      if (dx * dx + dy * dy <= (n.radius + 6) * (n.radius + 6)) {
        return n;
      }
    }
    return null;
  }

  _screenToWorld(sx, sy) {
    return {
      x: (sx - this._tx) / this._sc,
      y: (sy - this._ty) / this._sc,
    };
  }

  _showTooltip(node, clientX, clientY) {
    if (!this._tooltip) return;

    const commColor = node.communityColor || '#3b82f6';
    const typeColors = {
      METHOD:    '#38bdf8', // Cyan
      FIELD:     '#f59e0b', // Amber
      CLASS:     '#3b82f6', // Cobalt
      INTERFACE: '#10b981', // Emerald
      ENUM:      '#fb7185', // Rose
      RECORD:    '#0d9488', // Teal
    };
    const typeGlyphs = {
      METHOD:    'm',
      FIELD:     'f',
      CLASS:     'C',
      INTERFACE: 'I',
      ENUM:      'E',
      RECORD:    'R',
    };
    const nodeType = node.type || 'METHOD';
    const tColor = typeColors[nodeType] || commColor;
    const tGlyph = typeGlyphs[nodeType] || 'm';

    const heatVal = this._heatData[node.id] || 0;
    const heatSnippet = (this._heatMode || heatVal > 0)
      ? `<span class="tt-tag-pill" style="background:rgba(245, 158, 11, 0.18); color:#f59e0b; border:1px solid rgba(245, 158, 11, 0.45);">♨ Churn: ${heatVal}</span>`
      : '';

    const roleName = node.role ? String(node.role).toUpperCase() : 'NODE';
    const roleColors = {
      ROOT:       '#3b82f6',
      CALLER:     '#0284c7',
      CALLEE:     '#059669',
      PROPAGATOR: '#d97706',
      WRITER:     '#dc2626',
      READER:     '#0891b2',
    };
    const rColor = roleColors[roleName] || '#64748b';

    this._tooltip.style.borderColor = hexToRgba(commColor, 0.6);
    this._tooltip.style.boxShadow = `0 16px 40px rgba(0, 0, 0, 0.75), 0 0 24px ${hexToRgba(commColor, 0.25)}, inset 0 1px 0 rgba(255, 255, 255, 0.15)`;

    this._tooltip.innerHTML = `
      <div class="tt-accent-strip" style="background:linear-gradient(90deg, ${commColor}, ${tColor})"></div>
      <div class="tt-inner">
        <div class="tt-header-row">
          <span class="tt-badge-icon" style="background:${hexToRgba(tColor, 0.22)}; color:${tColor}; border:1px solid ${hexToRgba(tColor, 0.55)}">${tGlyph}</span>
          <span class="tt-name-text" title="${node.label || node.id}">${node.label || node.id.split('.').pop()}</span>
        </div>
        <div class="tt-tags-row">
          <span class="tt-tag-pill" style="background:${hexToRgba(commColor, 0.22)}; color:${commColor}; border:1px solid ${hexToRgba(commColor, 0.55)}; font-weight:700" title="Module: ${this._formatPackageLabel(node.package || 'default')}">📦 ${this._formatPackageLabel(node.package || 'default')}</span>
          ${node.className ? `<span class="tt-tag-pill" style="background:${hexToRgba(tColor, 0.22)}; color:${tColor}; border:1px solid ${hexToRgba(tColor, 0.55)}; font-weight:700" title="Class: ${node.className}">🏷️ ${node.className}</span>` : ''}
          <span class="tt-tag-pill" style="background:${hexToRgba(tColor, 0.2)}; color:${tColor}; border:1px solid ${hexToRgba(tColor, 0.5)}">${nodeType}</span>
          <span class="tt-tag-pill" style="background:${hexToRgba(rColor, 0.2)}; color:${rColor}; border:1px solid ${hexToRgba(rColor, 0.5)}">${roleName}</span>
          ${heatSnippet}
        </div>
        <div class="tt-stats-row">
          <span>Connections: <strong style="color:var(--text-primary); font-weight:700">${node.degree || 0}</strong></span>
          <span style="display:flex; gap:8px;">
            <span style="color:#0284c7; font-weight:700">↓ ${node.inDegree || 0} in</span>
            <span style="color:#059669; font-weight:700">↑ ${node.outDegree || 0} out</span>
          </span>
        </div>
      </div>
    `;
    this._tooltip.style.display = 'block';

    const tt = this._tooltip;
    const rect = tt.getBoundingClientRect();
    const pad = 12;
    const winW = window.innerWidth;
    const winH = window.innerHeight;

    let x = clientX + 16;
    let y = clientY - 12;

    if (x + rect.width + pad > winW) {
      x = clientX - rect.width - 16;
    }
    if (x < pad) x = pad;

    if (y + rect.height + pad > winH) {
      y = winH - rect.height - pad;
    }
    if (y < pad) y = pad;

    tt.style.left = `${Math.round(x)}px`;
    tt.style.top  = `${Math.round(y)}px`;
  }

  _hideTooltip() {
    if (this._tooltip) this._tooltip.style.display = 'none';
  }
  /* ── Public API: Apply Theme & Settings from Settings Modal ──────────── */

  applyTheme(graphTheme) {
    if (!graphTheme) return;
    this._activeTheme = graphTheme.key || 'midnight';
    if (graphTheme.bg)        GC.bg   = graphTheme.bg;
    if (graphTheme.grid)      GC.grid = graphTheme.grid;
    if (graphTheme.roles)     Object.assign(GC.roles, graphTheme.roles);
    if (graphTheme.edgeKind)  Object.assign(GC.edgeKind, graphTheme.edgeKind);
    if (graphTheme.nodeColors && graphTheme.nodeColors.length) {
      GRAPHIFY_COLORS = graphTheme.nodeColors.slice();
    }
    // Update minimap wrap visibility for Arctic (light bg)
    const mmWrap = document.getElementById('graph-minimap-wrap');
    if (mmWrap) mmWrap.style.display = this._showMinimap ? '' : 'none';
  }

  applySettings(s) {
    if (!s) return;
    if (s.nodeBaseRadius !== undefined) PHYSICS.nodeBaseRadius = s.nodeBaseRadius;
    if (s.repulsion !== undefined)      PHYSICS.repulsion      = s.repulsion;
    if (s.springLen !== undefined)       PHYSICS.springLen      = s.springLen;
    if (s.springK !== undefined)         PHYSICS.springK        = s.springK;
    if (s.damping !== undefined)         PHYSICS.damping        = s.damping;
    if (s.showParticles !== undefined)   this._showParticles    = s.showParticles;
    if (s.showMinimap !== undefined) {
      this._showMinimap = s.showMinimap;
      const mmWrap = document.getElementById('graph-minimap-wrap');
      if (mmWrap) mmWrap.style.display = s.showMinimap ? '' : 'none';
    }
    if (s.showLabels !== undefined)      this._showLabels       = s.showLabels;
    if (s.showGrid !== undefined)        this._showGrid         = s.showGrid;
    if (s.showHulls !== undefined)       this._showHulls        = s.showHulls;
    if (s.packagePrefixStrip !== undefined) {
      const changed = this._packagePrefixStrip !== s.packagePrefixStrip;
      this._packagePrefixStrip = s.packagePrefixStrip;
      if (changed && this._communities && this._communities.length > 0) {
        for (const comm of this._communities) {
          comm.label = this._formatPackageLabel(comm.rawLabel || comm.label);
        }
        for (const node of this._nodes) {
          const comm = this._communityMap.get(node.package) || this._communities[node.community || 0];
          if (comm) {
            node.communityLabel = comm.label;
          }
        }
        this._renderCommunityLegend();
      }
    }
  }
}

window.ForceGraph = ForceGraph;
