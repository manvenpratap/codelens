/**
 * graph-worker.js - Dedicated Web Worker for Offloaded Force-Directed Graph Physics
 *
 * Keeps the main UI thread at a locked 60 FPS by executing O(N^2) repulsion,
 * spring relaxation, community bouquet forces, and spatial collision resolution
 * entirely off-thread.
 */

'use strict';

const PHYSICS_DEFAULTS = {
  repulsion: 1800,
  clusterK: 0.08,
  springK: 0.045,
  springLen: 120,
  centerForce: 0.003,
  damping: 0.88,
  maxTicks: 180,
  nodeBaseRadius: 6,
};

let nodes = [];
let edges = [];
let canvasWidth = 1920;
let canvasHeight = 1080;
let params = { ...PHYSICS_DEFAULTS };
let ticks = 0;
let isRunning = false;

// Spatial grid for broadphase pairwise collision in worker
class WorkerSpatialGrid {
  constructor(cellSize = 120) {
    this.cellSize = cellSize;
    this.invCellSize = 1 / cellSize;
    this.grid = new Map();
  }

  clear() {
    this.grid.clear();
  }

  build(nodeList) {
    this.clear();
    const inv = this.invCellSize;
    for (let i = 0; i < nodeList.length; i++) {
      const nd = nodeList[i];
      if (nd.hidden) continue;
      const gx = Math.floor(nd.x * inv);
      const gy = Math.floor(nd.y * inv);
      const key = `${gx}:${gy}`;
      let cell = this.grid.get(key);
      if (!cell) {
        cell = [];
        this.grid.set(key, cell);
      }
      cell.push(nd);
    }
  }

  forEachPair(callback) {
    for (const [key, cell] of this.grid.entries()) {
      const len = cell.length;
      for (let i = 0; i < len; i++) {
        for (let j = i + 1; j < len; j++) {
          callback(cell[i], cell[j]);
        }
      }
      const sep = key.indexOf(':');
      const gx = parseInt(key.substring(0, sep), 10);
      const gy = parseInt(key.substring(sep + 1), 10);

      const neighborKeys = [
        `${gx + 1}:${gy}`,
        `${gx - 1}:${gy + 1}`,
        `${gx}:${gy + 1}`,
        `${gx + 1}:${gy + 1}`
      ];
      for (let n = 0; n < neighborKeys.length; n++) {
        const neighbor = this.grid.get(neighborKeys[n]);
        if (!neighbor) continue;
        const nlen = neighbor.length;
        for (let i = 0; i < len; i++) {
          for (let j = 0; j < nlen; j++) {
            callback(cell[i], neighbor[j]);
          }
        }
      }
    }
  }
}

const spatialGrid = new WorkerSpatialGrid(120);

function simulateTick() {
  const n = nodes.length;
  if (n === 0) return;

  const cx = canvasWidth / 2;
  const cy = canvasHeight / 2;
  const maxT = params.maxTicks || 180;
  const progress = Math.min(1, ticks / maxT);
  const alpha = Math.max(0.01, (1 - progress) * (1 - progress));
  const currentDamping = params.damping + (0.92 - params.damping) * progress;

  // Reset force accumulators
  for (let i = 0; i < n; i++) {
    nodes[i]._fx = 0;
    nodes[i]._fy = 0;
  }

  // 1. Dynamic community centroids
  const commCentroids = new Map();
  for (let i = 0; i < n; i++) {
    const nd = nodes[i];
    if (nd.hidden) continue;
    const cid = nd.community !== undefined ? nd.community : 0;
    let c = commCentroids.get(cid);
    if (!c) {
      c = { x: 0, y: 0, count: 0, coreNode: null, members: [] };
      commCentroids.set(cid, c);
    }
    c.x += nd.x;
    c.y += nd.y;
    c.count++;
    c.members.push(nd);
    if (nd.isBranchCore || !c.coreNode || (nd.hotScore || 0) > (c.coreNode.hotScore || 0)) {
      c.coreNode = nd;
    }
  }
  for (const [, c] of commCentroids.entries()) {
    if (c.count > 0) {
      c.x /= c.count;
      c.y /= c.count;
    }
    c.boundingRadius = Math.max(85, 76 + Math.sqrt(c.count) * 84);
  }

  // 2. Inter-cluster bouquet repulsion
  const cids = Array.from(commCentroids.keys());
  for (let i = 0; i < cids.length; i++) {
    const ca = commCentroids.get(cids[i]);
    if (!ca || !ca.members.length) continue;
    for (let j = i + 1; j < cids.length; j++) {
      const cb = commCentroids.get(cids[j]);
      if (!cb || !cb.members.length) continue;
      const dx = cb.x - ca.x;
      const dy = cb.y - ca.y;
      const distSq = dx * dx + dy * dy || 1;
      const dist = Math.sqrt(distSq);
      const targetSep = ca.boundingRadius + cb.boundingRadius + 180;
      if (dist < targetSep * 1.5) {
        const clusterRep = ((params.repulsion * 4.0) / Math.max(dist, 40)) * alpha;
        const fx = (dx / dist) * clusterRep;
        const fy = (dy / dist) * clusterRep;
        const repA = fx / Math.max(ca.count, 1);
        const repAY = fy / Math.max(ca.count, 1);
        for (let k = 0; k < ca.members.length; k++) {
          ca.members[k]._fx -= repA;
          ca.members[k]._fy -= repAY;
        }
        const repB = fx / Math.max(cb.count, 1);
        const repBY = fy / Math.max(cb.count, 1);
        for (let k = 0; k < cb.members.length; k++) {
          cb.members[k]._fx += repB;
          cb.members[k]._fy += repBY;
        }
      }
    }
  }

  // 3. Intra-community blooming cohesion
  for (let i = 0; i < n; i++) {
    const nd = nodes[i];
    if (nd.hidden) continue;
    const cid = nd.community !== undefined ? nd.community : 0;
    const c = commCentroids.get(cid);
    if (c && c.count > 1) {
      const targetX = (c.coreNode && c.coreNode !== nd) ? c.coreNode.x : c.x;
      const targetY = (c.coreNode && c.coreNode !== nd) ? c.coreNode.y : c.y;
      const dx = targetX - nd.x;
      const dy = targetY - nd.y;
      const dist = Math.sqrt(dx * dx + dy * dy) || 0.1;

      if (nd.isBranchCore) {
        const fc = dist * (params.clusterK * 1.5) * alpha;
        nd._fx += (dx / dist) * fc;
        nd._fy += (dy / dist) * fc;
      } else if (dist > c.boundingRadius * 0.92) {
        const fBloom = (dist - c.boundingRadius * 0.92) * (params.clusterK * 2.0) * alpha;
        nd._fx += (dx / dist) * fBloom;
        nd._fy += (dy / dist) * fBloom;
      }
    }
  }

  // 4. Pairwise repulsion
  if (n > 300) {
    for (const [, c] of commCentroids.entries()) {
      const cNodes = c.members;
      if (!cNodes || cNodes.length <= 1) continue;
      const cn = cNodes.length;
      for (let i = 0; i < cn; i++) {
        const ni = cNodes[i];
        for (let j = i + 1; j < cn; j++) {
          const nj = cNodes[j];
          const dx = nj.x - ni.x;
          const dy = nj.y - ni.y;
          const distSq = dx * dx + dy * dy || 0.01;
          const dist = Math.sqrt(distSq);
          const minClearance = ni.radius + nj.radius + 54;
          let rep = 0;
          if (dist < minClearance) {
            rep = ((params.repulsion * 2.2) / Math.max(dist, 8)) * alpha;
          } else {
            rep = ((params.repulsion * 0.4 * (1 + (ni.degree + nj.degree) * 0.05)) / distSq) * alpha;
          }
          const fx = (dx / dist) * rep;
          const fy = (dy / dist) * rep;
          ni._fx -= fx; ni._fy -= fy;
          nj._fx += fx; nj._fy += fy;
        }
      }
    }
  } else {
    for (let i = 0; i < n; i++) {
      for (let j = i + 1; j < n; j++) {
        const ni = nodes[i], nj = nodes[j];
        if (ni.hidden || nj.hidden) continue;

        const dx = nj.x - ni.x;
        const dy = nj.y - ni.y;
        const distSq = dx * dx + dy * dy || 0.01;
        const dist = Math.sqrt(distSq);

        const isSameComm = ni.community === nj.community;
        const minClearance = ni.radius + nj.radius + (isSameComm ? 54 : 90);
        let rep = 0;
        if (dist < minClearance) {
          rep = ((params.repulsion * (isSameComm ? 1.8 : 4.0)) / Math.max(dist, 10)) * alpha;
        } else {
          const mult = isSameComm ? 0.35 : 1.2;
          rep = ((params.repulsion * mult * (1 + (ni.degree + nj.degree) * 0.05)) / distSq) * alpha;
        }

        const fx = (dx / dist) * rep;
        const fy = (dy / dist) * rep;
        ni._fx -= fx; ni._fy -= fy;
        nj._fx += fx; nj._fy += fy;
      }
    }
  }

  // 5. Spring attraction along edges
  for (let i = 0; i < edges.length; i++) {
    const e = edges[i];
    const src = nodes[e.src];
    const tgt = nodes[e.tgt];
    if (!src || !tgt || src.hidden || tgt.hidden) continue;

    const dx = tgt.x - src.x;
    const dy = tgt.y - src.y;
    const dist = Math.sqrt(dx * dx + dy * dy) || 0.1;

    const isSameClass = src.className && src.className === tgt.className;
    const isSameComm = src.community === tgt.community;
    if (!isSameComm) {
      const cSrc = commCentroids.get(src.community);
      const cTgt = commCentroids.get(tgt.community);
      const minCommDist = (cSrc ? cSrc.boundingRadius : 120) + (cTgt ? cTgt.boundingRadius : 120) + 185;
      if (dist <= minCommDist) continue;
      const f = (dist - minCommDist) * (params.springK * 0.04) * alpha;
      const fx = (dx / dist) * f;
      const fy = (dy / dist) * f;
      src._fx += fx; src._fy += fy;
      tgt._fx -= fx; tgt._fy -= fy;
      continue;
    }

    const targetLen = isSameClass ? (params.springLen * 0.75) : (params.springLen * 1.05);
    const springTension = (isSameClass ? (params.springK * 0.9) : (params.springK * 0.6)) * alpha;
    const f = (dist - targetLen) * springTension;
    const fx = (dx / dist) * f;
    const fy = (dy / dist) * f;
    src._fx += fx; src._fy += fy;
    tgt._fx -= fx; tgt._fy -= fy;
  }

  // 6. Velocity integration
  for (let i = 0; i < n; i++) {
    const nd = nodes[i];
    if (nd.pinned) { nd.vx = 0; nd.vy = 0; continue; }

    nd._fx += (cx - nd.x) * params.centerForce * alpha;
    nd._fy += (cy - nd.y) * params.centerForce * alpha;

    nd.vx = (nd.vx + nd._fx) * currentDamping;
    nd.vy = (nd.vy + nd._fy) * currentDamping;

    const maxSpeed = 3.0 + 9.0 * alpha;
    const speed = Math.sqrt(nd.vx * nd.vx + nd.vy * nd.vy);
    if (speed > maxSpeed) {
      nd.vx = (nd.vx / speed) * maxSpeed;
      nd.vy = (nd.vy / speed) * maxSpeed;
    }

    nd.x += nd.vx;
    nd.y += nd.vy;
  }

  // 7. Angular momentum cancellation
  for (const [cid, c] of commCentroids.entries()) {
    if (c.count <= 2) continue;
    let sumNumerator = 0;
    let sumDenominator = 0;
    for (let i = 0; i < n; i++) {
      const nd = nodes[i];
      if (nd.community !== cid || nd.hidden || nd.pinned) continue;
      const rx = nd.x - c.x;
      const ry = nd.y - c.y;
      sumNumerator += (rx * nd.vy - ry * nd.vx);
      sumDenominator += (rx * rx + ry * ry);
    }
    if (sumDenominator > 10) {
      const omega = sumNumerator / sumDenominator;
      for (let i = 0; i < n; i++) {
        const nd = nodes[i];
        if (nd.community !== cid || nd.hidden || nd.pinned) continue;
        const rx = nd.x - c.x;
        const ry = nd.y - c.y;
        nd.vx -= (-omega * ry) * 0.70;
        nd.vy -= (omega * rx) * 0.70;
      }
    }
  }

  // 8A. Hard community hull separation
  if (cids.length > 1) {
    for (let pass = 0; pass < 3; pass++) {
      for (let i = 0; i < cids.length; i++) {
        const ca = commCentroids.get(cids[i]);
        if (!ca || !ca.members.length) continue;
        for (let j = i + 1; j < cids.length; j++) {
          const cb = commCentroids.get(cids[j]);
          if (!cb || !cb.members.length) continue;
          let dx = cb.x - ca.x;
          let dy = cb.y - ca.y;
          let dist = Math.sqrt(dx * dx + dy * dy);
          if (dist < 0.1) {
            dx = Math.cos(j * 2.39996);
            dy = Math.sin(j * 2.39996);
            dist = 1;
          }
          const reqDist = ca.boundingRadius + cb.boundingRadius + 165;
          if (dist < reqDist) {
            const overlap = (reqDist - dist) * 0.54;
            const ux = dx / dist;
            const uy = dy / dist;
            const shiftX = ux * overlap;
            const shiftY = uy * overlap;
            ca.x -= shiftX; ca.y -= shiftY;
            cb.x += shiftX; cb.y += shiftY;
            for (let k = 0; k < ca.members.length; k++) {
              if (!ca.members[k].pinned) {
                ca.members[k].x -= shiftX;
                ca.members[k].y -= shiftY;
              }
            }
            for (let k = 0; k < cb.members.length; k++) {
              if (!cb.members[k].pinned) {
                cb.members[k].x += shiftX;
                cb.members[k].y += shiftY;
              }
            }
          }
        }
      }
    }
  }

  // 8B. Spatial hash separation
  spatialGrid.build(nodes);
  spatialGrid.forEachPair((ni, nj) => {
    const dx = nj.x - ni.x;
    const dy = nj.y - ni.y;
    if (Math.abs(dx) > 155 || Math.abs(dy) > 95) return;

    const isSameComm = ni.community === nj.community;
    const minX = ni.radius + nj.radius + (isSameComm ? 98 : 132);
    const minY = ni.radius + nj.radius + (isSameComm ? 52 : 72);
    const nx = dx / minX;
    const ny = dy / minY;
    const normDistSq = nx * nx + ny * ny;

    if (normDistSq < 1.0) {
      const dist = Math.sqrt(dx * dx + dy * dy) || 0.01;
      const normDist = Math.sqrt(normDistSq);
      const push = (1.0 - normDist) * 0.55;
      const px = (dx / dist) * (minX * 0.65) * push;
      const py = (dy / dist) * (minY * 0.85) * push;

      if (!ni.pinned) {
        ni.x -= px;
        ni.y -= py;
      }
      if (!nj.pinned) {
        nj.x += px;
        nj.y += py;
      }
    }
  });

  ticks++;
}

function sendTick() {
  const n = nodes.length;
  const positions = new Float32Array(n * 2);
  for (let i = 0; i < n; i++) {
    positions[i * 2] = nodes[i].x;
    positions[i * 2 + 1] = nodes[i].y;
  }
  self.postMessage({
    type: 'tick',
    positions: positions,
    tick: ticks
  }, [positions.buffer]);
}

function runLoop() {
  if (!isRunning) return;
  const maxT = params.maxTicks || 180;
  if (ticks >= maxT) {
    isRunning = false;
    sendTick();
    self.postMessage({ type: 'completed', tick: ticks });
    return;
  }

  simulateTick();
  sendTick();

  // Schedule next tick
  setTimeout(runLoop, 0);
}

self.onmessage = function(e) {
  const msg = e.data;
  if (!msg) return;

  switch (msg.type) {
    case 'init': {
      nodes = msg.nodes || [];
      edges = msg.edges || [];
      canvasWidth = msg.width || 1920;
      canvasHeight = msg.height || 1080;
      params = Object.assign({}, PHYSICS_DEFAULTS, msg.params || {});
      ticks = msg.ticks || 0;
      if (msg.warmupTicks > 0) {
        for (let i = 0; i < msg.warmupTicks; i++) {
          simulateTick();
        }
      }
      sendTick();
      if (msg.autoStart) {
        isRunning = true;
        runLoop();
      }
      break;
    }

    case 'start': {
      isRunning = true;
      runLoop();
      break;
    }

    case 'stop': {
      isRunning = false;
      break;
    }

    case 'restart': {
      const maxT = params.maxTicks || 180;
      const extraTicks = msg.extraTicks || 90;
      ticks = Math.max(0, maxT - extraTicks);
      isRunning = true;
      runLoop();
      break;
    }

    case 'updateNode': {
      const idx = msg.index;
      if (nodes[idx]) {
        nodes[idx].x = msg.x;
        nodes[idx].y = msg.y;
        if (msg.pinned !== undefined) nodes[idx].pinned = msg.pinned;
      }
      break;
    }

    case 'resize': {
      canvasWidth = msg.width || canvasWidth;
      canvasHeight = msg.height || canvasHeight;
      break;
    }
  }
};
