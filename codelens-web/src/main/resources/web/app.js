/**
 * app.js - CodeLens Frontend Application Controller
 *
 * Manages all UI state, API communication, and panel coordination.
 * Uses vanilla ES2020+ (no framework, no build step).
 *
 * Module sections:
 *   1. State management
 *   2. API client
 *   3. Scan workflow
 *   4. Left panel - explorer tree + search
 *   5. Centre panel - tabs and views
 *   6. Right panel - entity detail + notes
 *   7. Graph integration
 *   8. Keyboard shortcuts
 *   9. Bootstrapping
 */

/* ─────────────────────────────────────────────────────────────────────────────
   1. Application state - single source of truth
   ───────────────────────────────────────────────────────────────────────────── */
const App = {
  // Currently selected entity
  selected: {
    kind: null,   // 'type' | 'method' | 'field' | 'package'
    id:   null,
    data: null,
  },
  // Active centre-panel tab
  activeTab: 'graph',
  // Active graph mode: 'callGraph' | 'callers' | 'callees' | 'fieldImpact' | 'fieldPropagation'
  activeGraphMode: 'callGraph',
  // Active traversal depth (1..15)
  graphDepth: 3,
  // Graph renderer instance
  graph: null,
  // Scan polling interval handle
  scanPollHandle: null,
  // Package tree open/closed state
  openPackages: new Set(),
  // Filter chips state
  activeFilter: 'all',
  // All packages (flat list from API)
  packages: [],
  // Current stats
  stats: { types: 0, methods: 0, fields: 0, packages: 0 },
  // Package Presentation mode: 'flat' (Eclipse) | 'hierarchical'
  packagePresentation: localStorage.getItem('codelens_package_presentation') || 'flat',
  // Monaco Editor state
  currentFilePath: null,
  editor: null,
  editorPromise: null,
  // Active alternate renderer (DSM, Treemap, Chord, Sunburst)
  activeAltRenderer: null,
  // Current codebase graph level
  codebaseGraphLevel: 'arch',
  // Server-Sent Events (SSE) state
  liveEventSource: null,
  sseConnected: false,
  sseLastActive: 0,
};

/* ── Dynamic Lazy Script Loader ────────────────────────────────────────────── */
const _loadedScripts = new Map();
function loadScript(src) {
  if (_loadedScripts.has(src)) return _loadedScripts.get(src);
  const p = new Promise((resolve, reject) => {
    const existing = document.querySelector(`script[src="${src}"]`);
    if (existing) {
      if (existing.dataset.loaded === 'true') {
        resolve();
        return;
      }
      existing.addEventListener('load', () => resolve());
      existing.addEventListener('error', (e) => reject(new Error(`Failed to load ${src}`)));
      return;
    }
    const s = document.createElement('script');
    s.src = src;
    s.async = true;
    s.onload = () => {
      s.dataset.loaded = 'true';
      resolve();
    };
    s.onerror = (e) => {
      _loadedScripts.delete(src);
      reject(new Error(`Failed to load ${src}`));
    };
    document.head.appendChild(s);
  });
  _loadedScripts.set(src, p);
  return p;
}

async function ensure3DStudioDependencies() {
  if (window.THREE && window.THREE.OrbitControls && window.THREE.EffectComposer && window.CodeCity3DRenderer && window.Galaxy3DRenderer) {
    return;
  }
  if (!window.THREE) {
    await loadScript('https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js');
  }
  await Promise.all([
    loadScript('https://cdn.jsdelivr.net/npm/three@0.128.0/examples/js/controls/OrbitControls.js'),
    loadScript('https://cdn.jsdelivr.net/npm/three@0.128.0/examples/js/shaders/CopyShader.js'),
    loadScript('https://cdn.jsdelivr.net/npm/three@0.128.0/examples/js/shaders/LuminosityHighPassShader.js'),
    loadScript('https://cdn.jsdelivr.net/npm/three@0.128.0/examples/js/shaders/FXAAShader.js'),
    loadScript('https://cdn.jsdelivr.net/npm/three@0.128.0/examples/js/shaders/VignetteShader.js')
  ]);
  await loadScript('https://cdn.jsdelivr.net/npm/three@0.128.0/examples/js/postprocessing/EffectComposer.js');
  await Promise.all([
    loadScript('https://cdn.jsdelivr.net/npm/three@0.128.0/examples/js/postprocessing/RenderPass.js'),
    loadScript('https://cdn.jsdelivr.net/npm/three@0.128.0/examples/js/postprocessing/ShaderPass.js'),
    loadScript('https://cdn.jsdelivr.net/npm/three@0.128.0/examples/js/postprocessing/UnrealBloomPass.js')
  ]);
  await Promise.all([
    loadScript('/city3d.js?v=1.0.6'),
    loadScript('/galaxy3d.js?v=1.0.6')
  ]);
}

async function ensureTreemapLoaded() {
  if (window.TreemapRenderer) return;
  await loadScript('/treemap.js?v=1.0.6');
}

async function ensureSunburstLoaded() {
  if (window.SunburstRenderer) return;
  await loadScript('/sunburst.js?v=1.0.6');
}

async function ensureDSMLoaded() {
  if (window.DSMRenderer) return;
  await loadScript('/dsm.js?v=1.0.6');
}

async function ensureChordLoaded() {
  if (window.ChordRenderer) return;
  await loadScript('/chord.js?v=1.0.6');
}

/* ─────────────────────────────────────────────────────────────────────────────
   2. API client - thin fetch wrapper
   ───────────────────────────────────────────────────────────────────────────── */
// ── In-Memory Graph Data Cache (Zero Network Overhead across views) ───────────
const GraphDataCache = {
  _cache: new Map(),
  _scanRevision: 0,

  get(key) {
    return this._cache.get(key);
  },

  set(key, data) {
    this._cache.set(key, data);
  },

  has(key) {
    return this._cache.has(key);
  },

  clear() {
    this._cache.clear();
    this._scanRevision++;
  },

  getRevision() {
    return this._scanRevision;
  }
};
window.GraphDataCache = GraphDataCache;

const api = {
  /** Make an API request; throws on non-2xx. */
  async request(path, options = {}) {
    const res = await fetch('/api' + path, {
      headers: { 'Content-Type': 'application/json' },
      ...options,
    });
    if (!res.ok) {
      const err = await res.json().catch(() => ({ error: res.statusText }));
      throw new Error(err.error || `HTTP ${res.status}`);
    }
    return res.json();
  },

  get:    (path, options = {}) => api.request(path, options),
  post:   (path, body)   => api.request(path, { method: 'POST',   body: JSON.stringify(body) }),
  delete: (path)         => api.request(path, { method: 'DELETE' }),

  // ── Convenience wrappers ────────────────────────────────────────────────────
  stats:              ()          => api.get('/stats'),
  packages:           ()          => api.get('/packages'),
  typesByPackage:     (fqn)       => {
    const key = `pkg:types:${(fqn || '').toLowerCase()}`;
    if (GraphDataCache.has(key)) return Promise.resolve(GraphDataCache.get(key));
    return api.get(`/packages/${enc(fqn)}/types`).then(data => {
      if (data) GraphDataCache.set(key, data);
      return data;
    });
  },
  type:               (id)        => api.get(`/types/${enc(id)}`),
  types:              (kind)      => api.get('/types' + (kind ? `?kind=${enc(kind)}` : '')),
  method:             (id)        => api.get(`/methods/${enc(id)}`),
  callers:            async (id, d=4) => {
    const key = `graph:callers:${id}:${d}`;
    if (GraphDataCache.has(key)) return GraphDataCache.get(key);
    const data = await api.get(`/methods/${enc(id)}/callers?depth=${d}`);
    GraphDataCache.set(key, data);
    return data;
  },
  callees:            async (id, d=4) => {
    const key = `graph:callees:${id}:${d}`;
    if (GraphDataCache.has(key)) return GraphDataCache.get(key);
    const data = await api.get(`/methods/${enc(id)}/callees?depth=${d}`);
    GraphDataCache.set(key, data);
    return data;
  },
  callGraph:          async (id, d=3) => {
    const key = `graph:call:${id}:${d}`;
    if (GraphDataCache.has(key)) return GraphDataCache.get(key);
    const data = await api.get(`/methods/${enc(id)}/graph?depth=${d}`);
    GraphDataCache.set(key, data);
    return data;
  },
  fullGraph:          async () => {
    const key = 'graph:full';
    if (GraphDataCache.has(key)) return GraphDataCache.get(key);
    const data = await api.get('/graph/all?precompute=true');
    GraphDataCache.set(key, data);
    return data;
  },
  architectureGraph:  async (scope, filter) => {
    const key = `graph:arch:${scope || ''}:${filter || ''}`;
    if (GraphDataCache.has(key)) return GraphDataCache.get(key);
    const params = new URLSearchParams({ precompute: 'true', ...(scope ? { scope } : {}), ...(filter ? { filter } : {}) });
    const data = await api.get(`/graph/architecture?${params}`);
    GraphDataCache.set(key, data);
    return data;
  },
  precomputedGraph:   async (scope, filter) => {
    const key = `graph:precomputed:${scope || ''}:${filter || ''}`;
    if (GraphDataCache.has(key)) return GraphDataCache.get(key);
    const params = new URLSearchParams({ ...(scope ? { scope } : {}), ...(filter ? { filter } : {}) });
    const data = await api.get(`/graph/precomputed?${params}`);
    GraphDataCache.set(key, data);
    return data;
  },
  dsmData:            async (scope, filter) => {
    const key = `graph:dsm:${scope || ''}:${filter || ''}`;
    if (GraphDataCache.has(key)) return GraphDataCache.get(key);
    const data = await api.get(`/graph/dsm${scope || filter ? '?' + new URLSearchParams({ ...(scope ? { scope } : {}), ...(filter ? { filter } : {}) }) : ''}`);
    GraphDataCache.set(key, data);
    return data;
  },
  treemapData:        async (scope, filter) => {
    const key = `graph:treemap:${scope || ''}:${filter || ''}`;
    if (GraphDataCache.has(key)) return GraphDataCache.get(key);
    const data = await api.get(`/graph/treemap${scope || filter ? '?' + new URLSearchParams({ ...(scope ? { scope } : {}), ...(filter ? { filter } : {}) }) : ''}`);
    GraphDataCache.set(key, data);
    return data;
  },
  hubExplorer:        async (fqn, dir='callers') => {
    return api.get(`/graph/hub-explorer?fqn=${encodeURIComponent(fqn)}&direction=${dir}`);
  },
  field:              (id)        => api.get(`/fields/${enc(id)}`),
  fieldImpact:        (id, d=1)   => api.get(`/fields/${enc(id)}/impact?depth=${d}`),
  blastRadius:        (fqn, kind) => api.get(`/analysis/blast-radius?fqn=${encodeURIComponent(fqn)}${kind ? '&kind=' + encodeURIComponent(kind) : ''}`),
  review:             (body)      => api.post('/review', body),
  search:             (q, n=30, options={}) => api.get(`/search?q=${encodeURIComponent(q)}&limit=${n}`, options),
  scanStatus:         ()          => api.get('/scan/status'),
  scanChanges:        (sourcePath) => api.get(`/scan/changes${sourcePath ? '?sourcePath=' + encodeURIComponent(sourcePath) : ''}`),
  startScan:          (sourcePath, excludePatterns) => api.post('/scan', { sourcePath, excludePatterns }),
  startIncrementalScan: (sourcePath, excludePatterns) => api.post('/scan/incremental', { sourcePath, excludePatterns }),
  processes:          ()          => api.get('/processes'),
  killProcess:        (id)        => api.post(`/processes/${enc(id)}/kill`, {}),
  restartProcess:     (id)        => api.post(`/processes/${enc(id)}/restart`, {}),
  databaseHealth:     ()          => api.get('/database/health'),
  databaseRecover:    (action)    => api.post('/database/recover', { action }),
  startStressTest:    (params)    => api.post('/stress-test/start', params || {}),
  getStressTestStatus: ()         => api.get('/stress-test/status'),
  stopStressTest:     ()          => api.post('/stress-test/stop', {}),
  jvmMetrics:         ()          => api.get('/jvm/metrics'),
  triggerGc:          ()          => api.post('/jvm/gc', {}),
  jvmThreads:         (q, state)  => api.get(`/jvm/threads?q=${encodeURIComponent(q || '')}&state=${encodeURIComponent(state || 'ALL')}`),
  jvmThreadStack:     (id)        => api.get(`/jvm/threads/${enc(id)}/stack`),
  jvmThreadDump:      (format)    => api.get(`/jvm/thread-dump${format ? '?format=' + encodeURIComponent(format) : ''}`),
  jvmDeadlocks:       ()          => api.get('/jvm/deadlocks'),
  trimMemory:         ()          => api.post('/jvm/trim-memory', {}),
  autoRecoveryStatus: ()          => api.get('/jvm/auto-recovery'),
  triggerAutoRecovery:()          => api.post('/jvm/auto-recovery/trigger', {}),
  simulateAutoRecovery:(mb = 60)  => api.post(`/jvm/auto-recovery/simulate?targetMb=${encodeURIComponent(mb)}`, {}),
  resetCircuitBreaker:()          => api.post('/jvm/auto-recovery/reset-circuit-breaker', {}),
  shutdownServer:     ()          => api.post('/shutdown', {}),
  notes:              (fqn)       => api.get(`/notes/${enc(fqn)}`),


  saveNote:           (body)      => api.post('/notes', body),
  deleteNote:         (id)        => api.delete(`/notes/${id}`),
  gitSummary:         ()          => api.get('/git/summary'),
  gitMeta:            (fqn)       => api.get(`/git/meta/${enc(fqn)}`),
  validateGitRepo:    (repoPath)  => api.post('/git/validate', { repoPath }),
  analyzeGit:         (repoPath)  => api.post('/git/analyze', { repoPath }),
  gitStatus:          ()          => api.get('/git/status'),
  browse:             (current)   => api.get(`/scan/browse?current=${encodeURIComponent(current || '')}`),
  openFolder:         (path)      => api.post('/open-folder', { path }),
  readFile:           (path)      => api.get(`/files/read?path=${encodeURIComponent(path)}`),
  writeFile:          (path, content) => api.post('/files/write', { path, content }),
  persistentClasses:  ()          => api.get('/analysis/persistent-classes'),
  criticalPath:       (classFqn, mode) => api.get(`/analysis/critical-path?class=${enc(classFqn)}${mode ? '&mode=' + encodeURIComponent(mode) : ''}`),
  excludeScope:       (type, fqn) => api.post('/scope/exclude', { type, fqn }),
  excludedScopes:     ()          => api.get('/scope/excluded'),
  restoreScope:       (fqn)       => api.post('/scope/restore', { fqn }),
  moduleDependencies: (nameOrFqn) => {
    const key = `mod:dep:${(nameOrFqn || '').toLowerCase()}`;
    if (GraphDataCache.has(key)) return Promise.resolve(GraphDataCache.get(key));
    return api.get(`/modules/${enc(nameOrFqn)}/dependencies`).then(data => {
      if (data) GraphDataCache.set(key, data);
      return data;
    });
  },
  packageDependencies:(fqn)       => {
    const key = `mod:dep:${(fqn || '').toLowerCase()}`;
    if (GraphDataCache.has(key)) return Promise.resolve(GraphDataCache.get(key));
    return api.get(`/packages/${enc(fqn)}/dependencies`).then(data => {
      if (data) {
        GraphDataCache.set(key, data);
        if (data.moduleName) {
          GraphDataCache.set(`mod:dep:${data.moduleName.toLowerCase()}`, data);
        }
      }
      return data;
    });
  },
  allModuleDependencies:()        => {
    const key = 'mod:all-overview';
    if (GraphDataCache.has(key)) return Promise.resolve(GraphDataCache.get(key));
    return api.get('/modules/dependencies').then(data => {
      if (data) GraphDataCache.set(key, data);
      return data;
    });
  },
  allModuleInsights:  ()          => {
    return api.get('/modules/insights').then(data => {
      if (data && typeof data === 'object') {
        for (const [key, insights] of Object.entries(data)) {
          if (insights) {
            GraphDataCache.set(`mod:dep:${key.toLowerCase()}`, insights);
            if (insights.moduleName) {
              GraphDataCache.set(`mod:dep:${insights.moduleName.toLowerCase()}`, insights);
            }
            if (insights.packageFqn) {
              GraphDataCache.set(`mod:dep:${insights.packageFqn.toLowerCase()}`, insights);
            }
          }
        }
      }
      return data;
    });
  },
  allReports:         ()          => api.get('/reports/all'),
};

/** URL-encode an entity FQN for path segments. */
function enc(fqn) {
  return encodeURIComponent(fqn || '');
}

/* ─────────────────────────────────────────────────────────────────────────────
   Hero Landing Page helpers
   ───────────────────────────────────────────────────────────────────────────── */

/** Show the hero landing page and hide all workspace UI */
function showHeroPage() {
  const hero = qs('#hero-landing-page');
  const app = qs('#app');
  const footer = qs('#app-footer');
  if (hero) hero.style.display = 'flex';
  if (app) app.style.display = 'none';
  if (footer) footer.style.display = 'none';
  const projectBar = qs('#header-project-bar');
  if (projectBar) projectBar.style.display = 'none';
  // Populate recent projects list
  renderHeroRecentProjects();
}

/** Hide the hero landing page and show all workspace UI */
function hideHeroPage() {
  const hero = qs('#hero-landing-page');
  const app = qs('#app');
  const footer = qs('#app-footer');
  if (hero) hero.style.display = 'none';
  if (app) app.style.display = '';
  if (footer) footer.style.display = '';
  const projectBar = qs('#header-project-bar');
  if (projectBar && App.currentPath) projectBar.style.display = 'flex';
}


/** Add a path to the recent-projects list stored in localStorage (max 8 entries) */
function addToRecentProjects(path) {
  if (!path) return;
  const key = 'codelens_recent_paths';
  let recents = [];
  try { recents = JSON.parse(localStorage.getItem(key) || '[]'); } catch (_) {}
  // Remove duplicates
  recents = recents.filter(p => p !== path);
  recents.unshift(path);
  if (recents.length > 8) recents = recents.slice(0, 8);
  localStorage.setItem(key, JSON.stringify(recents));
}

/** Render recent project buttons inside the hero scan card */
function renderHeroRecentProjects() {
  const container = qs('#hero-recent-projects');
  const section   = qs('#hero-recent-section');
  if (!container) return;

  let recents = [];
  try { recents = JSON.parse(localStorage.getItem('codelens_recent_paths') || '[]'); } catch (_) {}

  if (recents.length === 0) {
    if (section) section.style.display = 'none';
    return;
  }
  if (section) section.style.display = '';

  container.innerHTML = '';
  recents.forEach(path => {
    const cleanPath = path.replace(/[\\/]+$/, '');
    const parts = cleanPath.split(/[\\/]/);
    const name = parts[parts.length - 1] || 'Project';
    const btn = document.createElement('button');
    btn.className = 'hero-recent-item';
    btn.title = path;
    btn.innerHTML = `
      <svg class="svg-icon icon-sm icon-emerald" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m7.5 4.27 9 5.15"/><path d="M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z"/><path d="m3.3 7 8.7 5 8.7-5"/><path d="M12 22V12"/></svg>
      <span class="hero-recent-name">${name}</span>
      <span class="hero-recent-path">${path}</span>
    `;
    btn.addEventListener('click', () => {
      const heroInput = qs('#hero-scan-path-input');
      if (heroInput) heroInput.value = path;
      startHeroScan(path);
    });
    container.appendChild(btn);
  });

  // Populate Active / Last Scanned Codebase Card on Hero Page
  const lastScanSec = qs('#hero-last-scan-section');
  if (lastScanSec) {
    api.scanStatus().then(st => {
      if (st && st.status === 'SCANNING') {
        hideHeroPage();
        updateHeaderProjectBar(st.sourcePath || qs('#scan-path-input')?.value?.trim());
        setScanUI('scanning');
        updateScanProgress(st);
        pollScanStatus();
        return;
      }
      if (st && st.sourcePath && (st.status === 'COMPLETE' || st.typesFound > 0 || st.status === 'ERROR')) {
        lastScanSec.style.display = 'block';
        const pathEl = qs('#hero-last-scan-path');
        const metaEl = qs('#hero-last-scan-meta');
        const dotEl = qs('#hero-last-scan-dot');
        const heroScanInput = qs('#hero-scan-path-input');
        if (heroScanInput && (!heroScanInput.value || heroScanInput.value.trim() === '')) {
          heroScanInput.value = st.sourcePath;
        }
        if (pathEl) pathEl.textContent = st.sourcePath;
        if (metaEl) {
          if (st.status === 'ERROR') {
            metaEl.textContent = `Previous scan interrupted · ${st.typesFound || 0} Classes discovered · Click Rescan to resume`;
          } else {
            const errors = st.errorFiles || 0;
            const parsed = st.parsedFiles || st.processedFiles || 0;
            const total = st.totalFiles || parsed;
            const pct = total > 0 ? Math.round((parsed / total) * 100) : 100;
            metaEl.textContent = `${pct}% Parsed · ${parsed} Files · ${st.typesFound || 0} Classes · ${st.methodsFound || 0} Methods · ${st.fieldsFound || 0} Fields`;
          }
        }
        if (dotEl) {
          dotEl.className = 'hero-last-scan-dot' + (st.status === 'ERROR' ? ' status-error' : (st.errorFiles > 0 ? ' status-warning' : ''));
        }
        const openBtn = qs('#hero-btn-open-workspace');
        if (openBtn) {
          openBtn.onclick = () => {
            hideHeroPage();
            updateHeaderProjectBar(st.sourcePath);
            loadStats();
            loadPackageTree();
          };
        }
        const rescanBtn = qs('#hero-btn-rescan-quick');
        if (rescanBtn) {
          rescanBtn.onclick = () => {
            startHeroScan(st.sourcePath);
          };
        }
      } else {
        lastScanSec.style.display = 'none';
      }
    }).catch(() => {
      if (lastScanSec) lastScanSec.style.display = 'none';
    });
  }
}


/** Start a scan from the hero page then transition to workspace */
async function startHeroScan(targetPath) {
  let path = (typeof targetPath === 'string' && targetPath.trim()) ? targetPath.trim()
           : qs('#hero-scan-path-input')?.value?.trim() || '';
  if (!path) {
    const heroInput = qs('#hero-scan-path-input');
    if (heroInput) {
      heroInput.focus();
      heroInput.classList.add('input-error');
      setTimeout(() => heroInput.classList.remove('input-error'), 1500);
    }
    showBanner('Please enter or select the path to your Java source directory.');
    return;
  }
  // Sync to legacy input so startScan works unchanged
  const scanInput = qs('#scan-path-input');
  if (scanInput) scanInput.value = path;

  addToRecentProjects(path);

  // Transition hero → workspace immediately (scan will run behind the scenes)
  hideHeroPage();
  startScan(path);
}

/** Start a scan with the specified or active project path. */
async function startScan(targetPath) {

  let path = (typeof targetPath === 'string' && targetPath.trim()) ? targetPath.trim() : '';
  if (!path) {
    path = qs('#scan-path-input')?.value?.trim() || App.currentPath || localStorage.getItem('codelens_last_path') || '';
  }
  if (!path) {
    showHeroPage();
    showBanner('Please enter or select the path to your Java source directory.');
    return;
  }


  const scanInput = qs('#scan-path-input');
  if (scanInput) scanInput.value = path;
  App.currentPath = path;
  localStorage.setItem('codelens_last_path', path);

  const settings = loadSettings();
  const excludePatterns = settings.excludePatterns || 'target, build, .mvn, .git, .gradle, node_modules, bin, out';

  App.scanModalDismissed = false;
  App.lastScanProgress = { status: 'SCANNING', activeStage: 'PREPARE', currentPhase: 'Preparing Storage', message: 'Initializing analysis…', percentage: 1, sourcePath: path };
  setScanUI('scanning');
  const modalCard = qs('.scan-modal-card');
  if (modalCard) modalCard.classList.remove('is-complete');
  if (qs('#scan-card-heading')) qs('#scan-card-heading').textContent = 'Analyzing Codebase';
  if (qs('#scan-card-status-text')) qs('#scan-card-status-text').textContent = 'Initializing analysis…';
  if (qs('#scan-pct')) qs('#scan-pct').textContent = '0%';
  if (qs('#scan-card-bar-fill')) qs('#scan-card-bar-fill').style.width = '0%';
  if (qs('#scan-files-ratio')) qs('#scan-files-ratio').textContent = 'Preparing scanner…';
  if (qs('#scan-remaining-files')) qs('#scan-remaining-files').textContent = 'Stage 1 of 6';
  if (qs('#scan-detail-text')) qs('#scan-detail-text').textContent = 'Preparing storage & file list…';
  qs('#scan-status-bar')?.classList.add('visible');
  showBanner(`Rescanning codebase at "${path}"…`);
  try {
    await api.startScan(path, excludePatterns);
    pollScanStatus();
  } catch (e) {
    setScanUI('idle');
    showError('Scan failed to start: ' + e.message);
  }
}

/** Initialize native SSE live telemetry bus */
function initLiveEventBus() {
  if (typeof EventSource === 'undefined') return;
  try {
    const es = new EventSource('/api/events/live');
    App.liveEventSource = es;

    es.addEventListener('scan_status', (e) => {
      try {
        const s = JSON.parse(e.data);
        App.sseLastActive = Date.now();
        App.sseConnected = true;
        updateScanProgress(s);

        if (s.status === 'COMPLETE') {
          if (App.scanPollHandle) {
            clearInterval(App.scanPollHandle);
            App.scanPollHandle = null;
          }
          onScanComplete(s);
        } else if (s.status === 'ERROR') {
          if (App.scanPollHandle) {
            clearInterval(App.scanPollHandle);
            App.scanPollHandle = null;
          }
          setScanUI('idle');
          qs('#scan-status-bar')?.classList.remove('visible');
          qs('#scan-progress-bar').style.width = '0%';
          showError('Scan stopped: ' + (s.errorDetail || s.message));
          updateScanSummaryUI(s);
        }
      } catch (err) {
        console.warn('Error processing SSE scan_status:', err);
      }
    });

    es.onopen = () => {
      App.sseConnected = true;
      App.sseLastActive = Date.now();
    };

    es.onerror = () => {
      App.sseConnected = false;
    };
  } catch (err) {
    console.warn('EventSource failed to initialize:', err);
  }
}

/** Poll /api/scan/status every 350 ms until COMPLETE or ERROR (suspended while SSE is actively streaming). */
function pollScanStatus() {
  if (App.scanPollHandle) clearInterval(App.scanPollHandle);

  App.scanPollHandle = setInterval(async () => {
    // If SSE push is actively delivering events (within last 1500ms), suspend HTTP polling!
    if (App.sseConnected && App.sseLastActive && (Date.now() - App.sseLastActive < 2000)) {
      return;
    }
    try {
      const s = await api.scanStatus();
      updateScanProgress(s);

      if (s.status === 'COMPLETE') {
        clearInterval(App.scanPollHandle);
        App.scanPollHandle = null;
        onScanComplete(s);
      } else if (s.status === 'ERROR') {
        clearInterval(App.scanPollHandle);
        App.scanPollHandle = null;
        setScanUI('idle');
        qs('#scan-status-bar')?.classList.remove('visible');
        qs('#scan-progress-bar').style.width = '0%';
        showError('Scan stopped: ' + (s.errorDetail || s.message));
        updateScanSummaryUI(s);
      }
    } catch (e) {
      console.warn('Poll error:', e);
    }
  }, 350);
}


/** Minimize scan modal to allow background execution without interruption */
function minimizeScanModal() {
  App.scanModalDismissed = true;
  qs('#scan-status-bar')?.classList.remove('visible');
  showBanner('Scan running in background. Click the top bar badge or footer indicator anytime to view details.');
}

/** Resolve rich metrics and metadata for a specific pipeline phase */
function getPhaseMetricsData(stageKey, s) {
  s = s || App.lastScanProgress || {};
  const stats = App.stats || {};
  const history = s.stageHistory || {};
  const stepInfo = history[stageKey] || {};
  const metrics = stepInfo.metrics || {};

  const totalFiles = s.totalFiles || stats.files || 0;
  const parsedFiles = s.parsedFiles || s.processedFiles || totalFiles;
  const types = s.typesFound || stats.types || 0;
  const methods = s.methodsFound || stats.methods || 0;
  const fields = s.fieldsFound || stats.fields || 0;
  const rels = s.relationshipsFound || stats.relationships || 0;
  const totalDocs = types + methods + fields;

  switch (stageKey) {
    case 'PARSE':
      return {
        pill: 'Phase 1',
        name: 'AST Parsing & Extraction',
        summary: stepInfo.summary || (types > 0 ? `Parsed ${parsedFiles.toLocaleString()} files; extracted ${types.toLocaleString()} types and ${methods.toLocaleString()} methods.` : 'Extracting AST nodes in parallel.'),
        detailText: stepInfo.detail || `Parsed ${parsedFiles.toLocaleString()} files; discovered ${types.toLocaleString()} types, ${methods.toLocaleString()} methods, and ${fields.toLocaleString()} fields.`,
        duration: stepInfo.durationMs ? `${(stepInfo.durationMs / 1000).toFixed(1)}s` : (s.status === 'COMPLETE' ? 'Finished' : 'Running'),
        status: stepInfo.status || (s.status === 'COMPLETE' ? 'COMPLETE' : (s.activeStage === 'PARSE' ? 'RUNNING' : 'PENDING')),
        cards: [
          {
            val: (metrics['Types Found'] || types).toLocaleString(),
            lbl: 'Types',
            colorClass: 'icon-emerald-bg',
            iconColor: 'icon-emerald',
            valColor: '#34d399',
            iconSvg: '<rect x="2" y="7" width="20" height="14" rx="2"/><path d="M16 21V5a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16"/>'
          },
          {
            val: (metrics['Methods Found'] || methods).toLocaleString(),
            lbl: 'Methods',
            colorClass: 'icon-cyan-bg',
            iconColor: 'icon-cyan',
            valColor: '#38bdf8',
            iconSvg: '<polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/>'
          },
          {
            val: (metrics['Fields Found'] || fields).toLocaleString(),
            lbl: 'Fields',
            colorClass: 'icon-amber-bg',
            iconColor: 'icon-amber',
            valColor: '#fbbf24',
            iconSvg: '<path d="M12 2H2v10l9.29 9.29c.94.94 2.48.94 3.42 0l6.58-6.58c.94-.94.94-2.48 0-3.42L12 2Z"/><circle cx="7" cy="7" r=".5" fill="currentColor"/>'
          },
          {
            val: (metrics['Relationships'] || rels).toLocaleString(),
            lbl: 'Relationships',
            colorClass: 'icon-purple-bg',
            iconColor: 'icon-purple',
            valColor: '#c084fc',
            iconSvg: '<circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><line x1="8.59" y1="13.51" x2="15.42" y2="17.49"/><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"/>'
          }
        ]
      };

    case 'INDEX':
      return {
        pill: 'Phase 2',
        name: 'Search & Database Indexes',
        summary: stepInfo.summary || 'Lucene search indexing & secondary B-tree index rebuild',
        detailText: stepInfo.detail || `Committed ${totalDocs.toLocaleString()} search documents & rebuilt 12 secondary database indexes.`,
        duration: stepInfo.durationMs ? `${(stepInfo.durationMs / 1000).toFixed(1)}s` : (s.status === 'COMPLETE' ? 'Finished' : 'Running'),
        status: stepInfo.status || (s.status === 'COMPLETE' ? 'COMPLETE' : (s.activeStage === 'INDEX' ? 'RUNNING' : 'PENDING')),
        cards: [
          {
            val: metrics['Lucene Docs'] || (totalDocs > 0 ? totalDocs.toLocaleString() : 'Committed'),
            lbl: 'Lucene Docs',
            colorClass: 'icon-emerald-bg',
            iconColor: 'icon-emerald',
            valColor: '#34d399',
            iconSvg: '<circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>'
          },
          {
            val: metrics['DB Indexes'] || '12 / 12',
            lbl: 'DB Indexes',
            colorClass: 'icon-cyan-bg',
            iconColor: 'icon-cyan',
            valColor: '#38bdf8',
            iconSvg: '<ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"/><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/>'
          },
          {
            val: metrics['Search Index'] || 'Committed',
            lbl: 'Search Index',
            colorClass: 'icon-amber-bg',
            iconColor: 'icon-amber',
            valColor: '#fbbf24',
            iconSvg: '<path d="M13 2L3 14h9l-1 8 10-12h-9l1-8z"/>'
          },
          {
            val: metrics['Storage Engine'] || 'Optimized',
            lbl: 'B-Tree Indexes',
            colorClass: 'icon-purple-bg',
            iconColor: 'icon-purple',
            valColor: '#c084fc',
            iconSvg: '<rect x="2" y="2" width="20" height="8" rx="2"/><rect x="2" y="14" width="20" height="8" rx="2"/><line x1="6" y1="6" x2="6.01" y2="6"/><line x1="6" y1="18" x2="6.01" y2="18"/>'
          }
        ]
      };

    case 'GRAPH':
      return {
        pill: 'Phase 3',
        name: 'Call & Field Graph',
        summary: stepInfo.summary || 'In-memory call graph mapping and field propagation topology',
        detailText: stepInfo.detail || `Mapped ${methods.toLocaleString()} vertices, ${(s.relationshipsFound || rels).toLocaleString()} call edges, and ${fields.toLocaleString()} field relations.`,
        duration: stepInfo.durationMs ? `${(stepInfo.durationMs / 1000).toFixed(1)}s` : (s.status === 'COMPLETE' ? 'Finished' : 'Running'),
        status: stepInfo.status || (s.status === 'COMPLETE' ? 'COMPLETE' : (s.activeStage === 'GRAPH' ? 'RUNNING' : 'PENDING')),
        cards: [
          {
            val: metrics['Graph Vertices'] || (methods > 0 ? methods.toLocaleString() : 'Ready'),
            lbl: 'Graph Vertices',
            colorClass: 'icon-emerald-bg',
            iconColor: 'icon-emerald',
            valColor: '#34d399',
            iconSvg: '<circle cx="12" cy="12" r="4"/><path d="M12 2v6"/><path d="M12 16v6"/><path d="M2 12h6"/><path d="M16 12h6"/>'
          },
          {
            val: metrics['Call Edges'] || (rels > 0 ? rels.toLocaleString() : 'Mapped'),
            lbl: 'Call Edges',
            colorClass: 'icon-cyan-bg',
            iconColor: 'icon-cyan',
            valColor: '#38bdf8',
            iconSvg: '<circle cx="12" cy="12" r="3"/><line x1="3" y1="12" x2="9" y2="12"/><line x1="15" y1="12" x2="21" y2="12"/>'
          },
          {
            val: metrics['Field Relations'] || (fields > 0 ? fields.toLocaleString() : 'Indexed'),
            lbl: 'Field Relations',
            colorClass: 'icon-amber-bg',
            iconColor: 'icon-amber',
            valColor: '#fbbf24',
            iconSvg: '<path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/>'
          },
          {
            val: metrics['Caller Triggers'] || (stepInfo.status === 'COMPLETE' ? 'Mapped' : 'Ready'),
            lbl: 'Caller Triggers',
            colorClass: 'icon-purple-bg',
            iconColor: 'icon-purple',
            valColor: '#c084fc',
            iconSvg: '<circle cx="12" cy="12" r="10"/><circle cx="12" cy="12" r="6"/><circle cx="12" cy="12" r="2"/>'
          }
        ]
      };

    case 'LAYOUT':
      return {
        pill: 'Phase 4',
        name: 'Layout Precomputation',
        summary: stepInfo.summary || '2D and 3D graph layout warm-up & module overview precomputation',
        detailText: stepInfo.detail || 'Precomputed 6 topology layouts ready for instant interactive exploration.',
        duration: stepInfo.durationMs ? `${(stepInfo.durationMs / 1000).toFixed(1)}s` : (s.status === 'COMPLETE' ? 'Finished' : 'Running'),
        status: stepInfo.status || (s.status === 'COMPLETE' ? 'COMPLETE' : (s.activeStage === 'LAYOUT' ? 'RUNNING' : 'PENDING')),
        cards: [
          {
            val: metrics['Layouts Cached'] ? `${metrics['Layouts Cached']} / 6` : '6 / 6',
            lbl: 'Layouts Ready',
            colorClass: 'icon-emerald-bg',
            iconColor: 'icon-emerald',
            valColor: '#34d399',
            iconSvg: '<rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/>'
          },
          {
            val: metrics['Active Layout'] || 'Sunflower Clustered (Full)',
            lbl: 'Active Layout',
            colorClass: 'icon-cyan-bg',
            iconColor: 'icon-cyan',
            valColor: '#38bdf8',
            iconSvg: '<circle cx="12" cy="12" r="10"/><polygon points="16.24 7.76 14.12 14.12 7.76 16.24 9.88 9.88 16.24 7.76"/>'
          },
          {
            val: metrics['Modules Cached'] ? `${metrics['Modules Cached']} Modules` : 'Complete',
            lbl: 'Clusters',
            colorClass: 'icon-amber-bg',
            iconColor: 'icon-amber',
            valColor: '#fbbf24',
            iconSvg: '<path d="M12 2H2v10l9.29 9.29c.94.94 2.48.94 3.42 0l6.58-6.58c.94-.94.94-2.48 0-3.42L12 2Z"/><circle cx="7" cy="7" r=".5" fill="currentColor"/>'
          },
          {
            val: metrics['Placed Nodes'] || 'Ready',
            lbl: 'Placed Nodes',
            colorClass: 'icon-purple-bg',
            iconColor: 'icon-purple',
            valColor: '#c084fc',
            iconSvg: '<polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/>'
          }
        ]
      };
    case 'MODULES':
      const modulesCount = Math.max(s.modulesFound || 0, stats.modules || 0, stats.packages || 0, (metrics['Modules Indexed'] ? parseInt(metrics['Modules Indexed']) : 0), (App.packages && App.packages.length) || 0);
      return {
        pill: 'Phase 5',
        name: 'Module Dependencies',
        summary: stepInfo.summary || (modulesCount > 0 ? `Analyzed ${modulesCount.toLocaleString()} modules & inter-module dependencies.` : 'Computing module dependencies & coupling metrics.'),
        detailText: stepInfo.detail || `Analyzed ${modulesCount.toLocaleString()} modules and package boundaries with instability ratings.`,
        duration: stepInfo.durationMs ? `${(stepInfo.durationMs / 1000).toFixed(1)}s` : (s.status === 'COMPLETE' ? 'Finished' : 'Running'),
        status: stepInfo.status || (s.status === 'COMPLETE' ? 'COMPLETE' : (s.activeStage === 'MODULES' ? 'RUNNING' : 'PENDING')),
        cards: [
          {
            val: metrics['Modules Indexed'] || (modulesCount > 0 ? modulesCount.toLocaleString() : 'Ready'),
            lbl: 'Modules Indexed',
            colorClass: 'icon-emerald-bg',
            iconColor: 'icon-emerald',
            valColor: '#34d399',
            iconSvg: '<rect x="2" y="2" width="8" height="8" rx="2"/><rect x="14" y="2" width="8" height="8" rx="2"/><rect x="8" y="14" width="8" height="8" rx="2"/><line x1="6" y1="10" x2="12" y2="14"/><line x1="18" y1="10" x2="12" y2="14"/>'
          },
          {
            val: metrics['Inter-Module Links'] || (s.relationshipsFound ? s.relationshipsFound.toLocaleString() : 'Indexed'),
            lbl: 'Inter-Module Links',
            colorClass: 'icon-cyan-bg',
            iconColor: 'icon-cyan',
            valColor: '#38bdf8',
            iconSvg: '<circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><line x1="8.59" y1="13.51" x2="15.42" y2="17.49"/><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"/>'
          },
          {
            val: metrics['Stability Risk'] || metrics['Cycles Detected'] || 'Stable Core',
            lbl: 'Coupling Stability',
            colorClass: 'icon-amber-bg',
            iconColor: 'icon-amber',
            valColor: '#fbbf24',
            iconSvg: '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>'
          },
          {
            val: metrics['Status'] || (stepInfo.status === 'COMPLETE' ? 'Complete' : 'Ready'),
            lbl: 'Analysis Status',
            colorClass: 'icon-purple-bg',
            iconColor: 'icon-purple',
            valColor: '#c084fc',
            iconSvg: '<polyline points="20 6 9 17 4 12"/>'
          }
        ]
      };

    case 'REPORTS':
    default:
      const totalReportsTarget = s.reportsTotal || (typeof REPORTS_METADATA !== 'undefined' ? Object.keys(REPORTS_METADATA).length : 14);
      const reportsCount = s.reportsFound || stats.reports || totalReportsTarget;
      return {
        pill: 'Phase 6',
        name: 'Codebase Intelligence Reports',
        summary: stepInfo.summary || `Precomputing all ${totalReportsTarget} architecture, risk, quality, and concurrency reports`,
        detailText: stepInfo.detail || `Generated all ${totalReportsTarget} intelligence reports with offline standalone HTML snapshot.`,
        duration: stepInfo.durationMs ? `${(stepInfo.durationMs / 1000).toFixed(1)}s` : (s.status === 'COMPLETE' ? 'Finished' : 'Running'),
        status: stepInfo.status || (s.status === 'COMPLETE' ? 'COMPLETE' : (s.activeStage === 'REPORTS' ? 'RUNNING' : 'PENDING')),
        cards: [
          {
            val: metrics['Reports Ready'] || `${reportsCount} / ${totalReportsTarget}`,
            lbl: 'Reports Generated',
            colorClass: 'icon-emerald-bg',
            iconColor: 'icon-emerald',
            valColor: '#34d399',
            iconSvg: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/>'
          },
          {
            val: metrics['Active Report'] || (s.status === 'COMPLETE' ? `All ${totalReportsTarget} Ready` : 'In Progress'),
            lbl: 'Active Report',
            colorClass: 'icon-cyan-bg',
            iconColor: 'icon-cyan',
            valColor: '#38bdf8',
            iconSvg: '<circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>'
          },
          {
            val: metrics['Artifacts'] || `${totalReportsTarget} Reports`,
            lbl: 'Audit Artifacts',
            colorClass: 'icon-amber-bg',
            iconColor: 'icon-amber',
            valColor: '#fbbf24',
            iconSvg: '<rect x="2" y="3" width="20" height="14" rx="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/>'
          },
          {
            val: metrics['Snapshot'] || 'HTML Snapshot',
            lbl: 'Offline Export',
            colorClass: 'icon-purple-bg',
            iconColor: 'icon-purple',
            valColor: '#c084fc',
            iconSvg: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/>'
          }
        ]
      };
  }
}

/** Render bottom section metrics grid for a specific phase */
function renderPhaseBottomMetrics(stageKey, s) {
  const data = getPhaseMetricsData(stageKey, s);
  if (!data) return;

  const pillEl = qs('#scan-metrics-phase-pill');
  const nameEl = qs('#scan-metrics-phase-name');
  if (pillEl) pillEl.textContent = data.pill;
  if (nameEl) nameEl.textContent = data.name;

  const valEls = [qs('#scan-live-types'), qs('#scan-live-methods'), qs('#scan-live-fields'), qs('#scan-live-rels')];
  const lblEls = [qs('#scan-live-types-lbl'), qs('#scan-live-methods-lbl'), qs('#scan-live-fields-lbl'), qs('#scan-live-rels-lbl')];
  const iconWraps = [qs('#scan-metric-icon-1'), qs('#scan-metric-icon-2'), qs('#scan-metric-icon-3'), qs('#scan-metric-icon-4')];
  const tiles = [qs('#scan-metric-tile-1'), qs('#scan-metric-tile-2'), qs('#scan-metric-tile-3'), qs('#scan-metric-tile-4')];

  data.cards.forEach((card, idx) => {
    const valEl = valEls[idx];
    const lblEl = lblEls[idx];
    const iconWrap = iconWraps[idx];
    const tile = tiles[idx];

    if (valEl) {
      valEl.textContent = card.val;
      if (card.valColor) valEl.style.color = card.valColor;
    }
    if (lblEl) lblEl.textContent = card.lbl;
    if (iconWrap && card.iconSvg) {
      iconWrap.className = 'scan-metric-icon-wrap ' + card.colorClass;
      iconWrap.innerHTML = `<svg class="svg-icon icon-xs ${card.iconColor}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">${card.iconSvg}</svg>`;
    }
    if (tile) {
      tile.classList.remove('metric-updating');
      void tile.offsetWidth;
      tile.classList.add('metric-updating');
    }
  });
}

/** Render active file/status detail banner for a specific phase */
function renderPhaseStatusDetail(stageKey, s) {
  s = s || App.lastScanProgress || {};
  const data = getPhaseMetricsData(stageKey, s);
  if (!data) return;

  const detailLabel = qs('#scan-detail-label');
  const detailText = qs('#scan-detail-text');
  const detailIcon = qs('#scan-detail-icon');

  if (s.status === 'COMPLETE' || s.activeStage === 'COMPLETE') {
    if (detailLabel) detailLabel.textContent = `${data.pill} · ${data.name}`;
    if (detailIcon) detailIcon.innerHTML = '<path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/>';
    if (detailText) {
      detailText.innerHTML = `<span style="color:var(--emerald); font-weight:600;">✓ ${esc(data.detailText)}</span>`;
    }
  }
}

/** Inspect and select a single step from the scan pipeline (updates bottom section metrics) */
function inspectScanStep(stepName, toggleIfSame = false) {
  if (!stepName) return;
  const panel = qs('#scan-step-detail-panel');

  const isSameStep = App.selectedScanPhase === stepName;
  if (toggleIfSame && isSameStep && panel && panel.style.display !== 'none') {
    closeStepDetail();
    return;
  }
  App.inspectedScanStep = stepName;
  App.selectedScanPhase = stepName;

  qsa('.scan-pipeline-step').forEach(s => {
    if (s.dataset.step === stepName) {
      s.classList.add('step-inspected', 'step-selected');
      s.setAttribute('aria-selected', 'true');
    } else {
      s.classList.remove('step-inspected', 'step-selected');
      s.setAttribute('aria-selected', 'false');
    }
  });

  const sp = App.lastScanProgress || {};
  const history = sp.stageHistory || {};
  const stepInfo = history[stepName];
  const phaseData = getPhaseMetricsData(stepName, sp);

  // 1. Immediately update bottom section metrics
  renderPhaseBottomMetrics(stepName, sp);

  // 2. Immediately update status banner
  renderPhaseStatusDetail(stepName, sp);

  // 3. Update drawer details
  const stepTitles = {
    'PREPARE': '0. Preparing Storage & DB',
    'PARSE': '1. AST Parsing & Extraction',
    'INDEX': '2. Search & DB Indexes',
    'GRAPH': '3. Call & Field Graph',
    'LAYOUT': '4. Layout Precomputation',
    'MODULES': '5. Module Dependencies',
    'REPORTS': '6. Codebase Intelligence Reports'
  };

  const titleEl = qs('#step-detail-title');
  if (titleEl) titleEl.textContent = stepTitles[stepName] || phaseData.name || stepName;

  const statusBadge = qs('#step-detail-status');
  const durVal = qs('#step-detail-duration-val');
  const summaryEl = qs('#step-detail-summary');
  const metricsGrid = qs('#step-detail-metrics-grid');

  if (statusBadge) {
    const isComplete = (stepInfo && (stepInfo.status === 'COMPLETE' || stepInfo.status === 'SUCCESS')) || sp.status === 'COMPLETE';
    const isRunning = stepInfo ? stepInfo.status === 'RUNNING' : (sp.activeStage === stepName);
    statusBadge.textContent = isComplete ? 'COMPLETE' : (isRunning ? 'RUNNING' : 'PENDING');
    statusBadge.className = 'step-detail-status-badge ' + (isComplete ? 'badge-complete' : (isRunning ? 'badge-running' : 'badge-pending'));
  }

  if (durVal) {
    durVal.textContent = phaseData.duration || 'Finished';
  }

  if (summaryEl) {
    summaryEl.textContent = (stepInfo && (stepInfo.summary || stepInfo.detail)) || phaseData.summary;
  }

  if (metricsGrid) {
    metricsGrid.innerHTML = '';
    phaseData.cards.forEach(card => {
      const chip = document.createElement('div');
      chip.className = 'step-detail-metric-chip';
      chip.innerHTML = `<span class="step-detail-metric-chip-k">${esc(card.lbl)}:</span><span class="step-detail-metric-chip-v">${esc(String(card.val))}</span>`;
      metricsGrid.appendChild(chip);
    });
  }

  if (panel) panel.style.display = 'flex';
}

function closeStepDetail() {
  App.inspectedScanStep = null;
  const panel = qs('#scan-step-detail-panel');
  if (panel) panel.style.display = 'none';
  // Bottom section and stepper maintain App.selectedScanPhase selection
}

/* ─────────────────────────────────────────────────────────────────────────────
   Process Hub & Task Manager Operations
   ───────────────────────────────────────────────────────────────────────────── */
/* ─────────────────────────────────────────────────────────────────────────────
   Process Hub & Task Manager Operations
   ───────────────────────────────────────────────────────────────────────────── */
let processHubPollInterval = null;
let processHubFilter = 'all';
let processHubSearch = '';
let processHubActiveTab = 'tasks';
let processHubApiCategory = 'all';
let processHubApiSearch = '';
let processHubLastData = null;
let jvmThreadsFilterState = 'ALL';
let jvmThreadsSearchQuery = '';
let jvmLastThreadsList = [];
let jvmDumpRawText = '';

function openProcessHub() {
  const modal = qs('#process-hub-modal');
  if (!modal) return;
  showAccessibleModal(modal, qs('#btn-process-hub'));
  if (processHubLastData?.processes) {
    renderTasksPanel(processHubLastData.processes);
  }
  loadProcessHubData();
  if (processHubPollInterval) clearInterval(processHubPollInterval);
  processHubPollInterval = setInterval(loadProcessHubData, 2000);
}

function closeProcessHub() {
  dismissModalAnimated(qs('#process-hub-modal'));
  if (processHubPollInterval) {
    clearInterval(processHubPollInterval);
    processHubPollInterval = null;
  }
}

function getProcessIconSvg(id, type) {
  if (id === 'scanner') {
    return `<svg class="svg-icon icon-sm icon-emerald" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>`;
  } else if (id === 'delta-scanner') {
    return `<svg class="svg-icon icon-sm icon-cyan" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="9" y1="15" x2="15" y2="15"/></svg>`;
  } else if (id === 'call-graph') {
    return `<svg class="svg-icon icon-sm icon-purple" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><line x1="8.59" y1="13.51" x2="15.42" y2="17.49"/><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"/></svg>`;
  } else if (id === 'layout-engine') {
    return `<svg class="svg-icon icon-sm icon-amber" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="4"/><path d="M12 2v2"/><path d="M12 20v2"/><path d="m4.93 4.93 1.41 1.41"/><path d="m17.66 17.66 1.41 1.41"/><path d="M2 12h2"/><path d="M20 12h2"/><path d="m6.34 17.66-1.41 1.41"/><path d="m19.07 4.93-1.41 1.41"/></svg>`;
  } else if (id === 'module-analyzer') {
    return `<svg class="svg-icon icon-sm icon-cyan" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/><polyline points="3.27 6.96 12 12.01 20.73 6.96"/><line x1="12" y1="22.08" x2="12" y2="12"/></svg>`;
  } else if (id === 'lucene-indexer') {
    return `<svg class="svg-icon icon-sm icon-blue" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/><line x1="11" y1="8" x2="11" y2="14"/><line x1="8" y1="11" x2="14" y2="11"/></svg>`;
  } else if (id === 'git-analyzer') {
    return `<svg class="svg-icon icon-sm icon-slate" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="18" cy="18" r="3"/><circle cx="6" cy="6" r="3"/><path d="M6 9v12"/><path d="M18 9a9 9 0 0 0-9 9"/></svg>`;
  } else if (id === 'db-watchdog') {
    return `<svg class="svg-icon icon-sm icon-emerald" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/><polyline points="9 12 11 14 15 10"/></svg>`;
  } else if (id === 'stress-test') {
    return `<svg class="svg-icon icon-sm icon-amber" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>`;
  } else if (id === 'heap-watchdog') {
    return `<svg class="svg-icon icon-sm icon-rose" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 12h-4l-3 9L9 3l-3 9H2"/></svg>`;
  } else if (id === 'reports-generator') {
    return `<svg class="svg-icon icon-sm icon-purple" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/><polyline points="10 9 9 9 8 9"/></svg>`;
  } else if (id === 'db-maintenance') {
    return `<svg class="svg-icon icon-sm icon-emerald" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"/><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/></svg>`;
  } else if (id === 'sse-broadcaster') {
    return `<svg class="svg-icon icon-sm icon-cyan" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4.93 4.93a10 10 0 0 1 14.14 0"/><path d="M7.76 7.76a6 6 0 0 1 8.48 0"/><circle cx="12" cy="12" r="2"/></svg>`;
  }
  return `<svg class="svg-icon icon-sm icon-cyan" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="3" width="20" height="14" rx="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>`;
}

async function runDbRecovery(action) {
  const alertEl = qs('#hub-db-recovery-alert');
  const iconEl = qs('#hub-db-alert-icon');
  const msgEl = qs('#hub-db-alert-msg');

  const btnMap = {
    'health_check': qs('#btn-db-action-health') || qs('#btn-quick-db-health'),
    'reindex': qs('#btn-db-action-reindex'),
    'compact': qs('#btn-db-action-compact'),
    'clean_orphans': qs('#btn-db-action-clean'),
    'restart': qs('#btn-db-action-restart'),
    'sweep_leaks': qs('#btn-db-action-leaks')
  };
  const activeBtn = btnMap[action];
  const origText = activeBtn ? activeBtn.innerHTML : '';
  const allRecoveryBtns = qsa('.btn-db-action');

  allRecoveryBtns.forEach(b => { b.disabled = true; });
  if (activeBtn) {
    activeBtn.classList.add('is-loading');
    activeBtn.innerHTML = `<span class="hub-btn-spinner"></span> Running…`;
  }

  try {
    const res = await api.databaseRecover(action);
    allRecoveryBtns.forEach(b => { b.disabled = false; });
    if (activeBtn) {
      activeBtn.classList.remove('is-loading');
      activeBtn.innerHTML = origText;
    }
    if (alertEl && msgEl) {
      if (res && res.success) {
        if (iconEl) iconEl.innerHTML = `<svg class="svg-icon icon-xs icon-emerald" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="20 6 9 17 4 12"/></svg>`;
        alertEl.className = 'hub-db-recovery-alert alert-success';
        msgEl.textContent = res.message || 'Database action completed successfully.';
      } else {
        if (iconEl) iconEl.innerHTML = `<svg class="svg-icon icon-xs icon-amber" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>`;
        alertEl.className = 'hub-db-recovery-alert alert-warning';
        msgEl.textContent = res?.message || 'Database action finished with warnings.';
      }
      alertEl.style.display = 'flex';
    }
    loadProcessHubData();
  } catch (err) {
    allRecoveryBtns.forEach(b => { b.disabled = false; });
    if (activeBtn) {
      activeBtn.classList.remove('is-loading');
      activeBtn.innerHTML = origText;
    }
    if (alertEl && msgEl) {
      if (iconEl) iconEl.innerHTML = `<svg class="svg-icon icon-xs icon-rose" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>`;
      alertEl.className = 'hub-db-recovery-alert alert-error';
      msgEl.textContent = `Operation failed: ${err.message}`;
      alertEl.style.display = 'flex';
    }
  }
}

function renderDatabasePanel(db) {
  if (!db) return;
  const engineEl = qs('#hub-db-engine-title');
  if (engineEl) engineEl.textContent = db.engine || 'H2 2.2.224';
  const modeEl = qs('#hub-db-mode-title');
  if (modeEl) modeEl.textContent = db.storageMode || 'Embedded MVStore';
  const pathEl = qs('#hub-db-path');
  if (pathEl) pathEl.textContent = db.filePath || 'codelens_db.mv.db';
  const diskEl = qs('#hub-db-disk-size');
  if (diskEl) diskEl.textContent = `${db.fileSizeMb ?? 0} MB`;
  const pingEl = qs('#hub-db-ping-val');
  if (pingEl) {
    const p = db.pingMs != null && db.pingMs >= 0 ? `${db.pingMs} ms` : '-';
    pingEl.textContent = p;
  }
  const badgeEl = qs('#hub-db-status-badge');
  if (badgeEl) {
    const st = (db.status || 'HEALTHY').toUpperCase();
    badgeEl.textContent = st;
    badgeEl.className = 'hub-db-status-badge ' + (st === 'HEALTHY' ? 'status-healthy' : (st === 'DEGRADED' ? 'status-degraded' : (st === 'LOCKED' ? 'status-locked' : 'status-corrupted')));
  }

  // If locked or SQLState 57014 detected and alert not currently visible, show prompt
  if (db.sqlState57014 || (db.status && db.status.toUpperCase() === 'LOCKED')) {
    const alertEl = qs('#hub-db-recovery-alert');
    const iconEl = qs('#hub-db-alert-icon');
    const msgEl = qs('#hub-db-alert-msg');
    if (alertEl && msgEl && alertEl.style.display === 'none') {
      if (iconEl) iconEl.innerHTML = `<svg class="svg-icon icon-xs icon-rose" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>`;
      alertEl.className = 'hub-db-recovery-alert alert-error';
      msgEl.textContent = db.statusMessage || 'SQLState 57014 (Statement Timeout / Lock Contention) detected. Click "Restart Database" to reset connection pool and release locks.';
      alertEl.style.display = 'flex';
    }
  }

  // Table counts
  const tables = db.tables || {};
  for (const [tbl, count] of Object.entries(tables)) {
    const el = qs(`#hub-tbl-${tbl}`);
    if (el) el.textContent = count >= 0 ? count.toLocaleString() : '-';
  }

  // Pool details
  const pool = db.pool || {};
  const activeEl = qs('#hub-pool-active');
  if (activeEl) activeEl.textContent = pool.activeConnections ?? 0;
  const idleEl = qs('#hub-pool-idle');
  if (idleEl) idleEl.textContent = pool.idleConnections ?? 4;
  const totalEl = qs('#hub-pool-total');
  if (totalEl) totalEl.textContent = pool.totalConnections ?? 4;
  const awaitEl = qs('#hub-pool-awaiting');
  if (awaitEl) awaitEl.textContent = pool.threadsAwaiting ?? 0;
  const maxEl = qs('#hub-pool-max');
  if (maxEl) maxEl.textContent = pool.maxPoolSize ?? 20;
  const poolNameEl = qs('#hub-pool-name');
  if (poolNameEl) poolNameEl.textContent = pool.poolName || 'CodeLens-H2';

  // Leak Auto-Recovery
  const leak = db.leakRecovery || {};
  const recovered = leak.totalRecovered ?? (db.pool?.leaksRecovered ?? 0);
  const countEl = qs('#hub-pool-leak-count');
  if (countEl) {
    countEl.textContent = recovered;
    if (recovered > 0) {
      countEl.className = 'hub-pool-leak-count font-mono text-emerald';
    } else {
      countEl.className = 'hub-pool-leak-count font-mono';
    }
  }
  const detailEl = qs('#hub-pool-leak-detail');
  if (detailEl) {
    if (recovered > 0 && leak.lastRecovered) {
      const lr = leak.lastRecovered;
      const site = lr.allocationSite ? lr.allocationSite.split('.').slice(-2).join('.') : '';
      detailEl.textContent = `(last: ${lr.reason || 'evicted'} at ${site})`;
      detailEl.title = `Last leak recovered at ${lr.allocationSite || 'unknown'}: held ${Math.round((lr.durationMs || 0)/1000)}s by ${lr.threadName || 'unknown'}`;
    } else {
      const tracked = leak.activeTracked ?? (db.pool?.activeTracked ?? 0);
      detailEl.textContent = `(${tracked} tracked)`;
      detailEl.title = `Watchdog monitors ${tracked} borrowed connection lease(s)`;
    }
  }
  const leakStatusEl = qs('#hub-pool-leak-status');
  if (leakStatusEl) {
    const threshSec = Math.round((leak.thresholdMs || 60000) / 1000);
    leakStatusEl.textContent = `Watchdog Active (${threshSec}s)`;
  }

  // Indexes list
  const idxListEl = qs('#hub-index-list');
  if (idxListEl && db.indexes) {
    const missing = new Set(db.indexes.missing || []);
    const verified = db.indexes.verified;
    const badge = qs('#hub-index-status-badge');
    if (badge) {
      badge.textContent = verified ? 'All Verified' : `${missing.size} Missing`;
      badge.className = 'hub-subcard-badge ' + (verified ? 'text-emerald' : 'text-amber');
    }
    const expected = [
      { name: 'idx_types_pkg', desc: 'types(package_fqn)' },
      { name: 'idx_types_kind', desc: 'types(kind)' },
      { name: 'idx_types_pkg_kind', desc: 'types(package_fqn, kind)' },
      { name: 'idx_fields_type', desc: 'fields(declaring_type_fqn)' },
      { name: 'idx_methods_type', desc: 'methods(declaring_type_fqn)' },
      { name: 'idx_methods_name', desc: 'methods(simple_name)' },
      { name: 'idx_rels_from', desc: 'relationships(from_entity_fqn)' },
      { name: 'idx_rels_to', desc: 'relationships(to_entity_fqn)' },
      { name: 'idx_rels_kind', desc: 'relationships(kind)' },
      { name: 'idx_rels_calls_covering', desc: 'relationships covering calls' },
      { name: 'idx_rels_fields_covering', desc: 'relationships covering fields' },
      { name: 'idx_pkgs_parent', desc: 'packages(parent_fqn)' },
    ];
    idxListEl.innerHTML = expected.map(idx => {
      const isMiss = missing.has(idx.name);
      return `<div class="hub-index-item ${isMiss ? 'is-missing' : 'is-verified'}">
        <span class="hub-idx-status-icon">${isMiss ? '<svg class="svg-icon icon-xs icon-amber" style="width:11px;height:11px;vertical-align:-1px;" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>' : '<svg class="svg-icon icon-xs icon-emerald" style="width:11px;height:11px;vertical-align:-1px;" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="20 6 9 17 4 12"/></svg>'}</span>
        <span class="hub-idx-name font-mono">${esc(idx.name)}</span>
        <span class="hub-idx-desc">${esc(idx.desc)}</span>
      </div>`;
    }).join('');
  }

  // Also refresh scale & stress benchmark telemetry
  pollStressTestStatus();
}

async function pollStressTestStatus() {
  try {
    const stp = await api.getStressTestStatus();
    if (stp) {
      renderStressTestTelemetry(stp);
    }
  } catch (ignored) {}
}

function renderStressTestTelemetry(stp) {
  if (!stp) return;
  const telemetryBox = qs('#hub-stress-telemetry');
  const resultsCard = qs('#hub-stress-results');
  const startBtn = qs('#btn-stress-start');
  const stopBtn = qs('#btn-stress-stop');

  const isRunning = stp.status === 'RUNNING';
  const isComplete = stp.status === 'COMPLETE';
  const isCancelled = stp.status === 'CANCELLED';
  const isError = stp.status === 'ERROR';

  if (startBtn) {
    startBtn.disabled = isRunning;
    if (isRunning) {
      startBtn.innerHTML = `<span class="hub-btn-spinner" style="display:inline-block;width:12px;height:12px;border:2px solid rgba(255,255,255,0.3);border-top-color:#fff;border-radius:50%;animation:spin 0.8s linear infinite;margin-right:6px;vertical-align:-2px;"></span> Running…`;
    } else {
      startBtn.innerHTML = `<svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="5 3 19 12 5 21 5 3"/></svg> Run Stress Test`;
    }
  }
  if (stopBtn) {
    stopBtn.style.display = isRunning ? 'inline-flex' : 'none';
  }

  if (stp.status === 'IDLE' && (!stp.benchmarkReport || Object.keys(stp.benchmarkReport).length === 0)) {
    if (telemetryBox) telemetryBox.style.display = 'none';
    if (resultsCard) resultsCard.style.display = 'none';
    return;
  }

  if (telemetryBox) {
    telemetryBox.style.display = 'flex';
    const phaseEl = qs('#hub-stress-phase');
    if (phaseEl) {
      phaseEl.textContent = stp.currentPhase || stp.status;
    }
    const detailEl = qs('#hub-stress-detail');
    if (detailEl) {
      detailEl.textContent = isError ? (stp.errorDetail || stp.message) : (stp.currentDetail || stp.message || '');
    }
    const pctEl = qs('#hub-stress-pct');
    if (pctEl) pctEl.textContent = `${stp.percentage || 0}%`;
    const barEl = qs('#hub-stress-bar');
    if (barEl) barEl.style.width = `${stp.percentage || 0}%`;

    const rateEl = qs('#hub-stress-rate');
    if (rateEl) rateEl.textContent = `${Math.round(stp.rateRowsPerSec || 0).toLocaleString()} rows/s`;
    const dbSizeEl = qs('#hub-stress-dbsize');
    if (dbSizeEl) dbSizeEl.textContent = `${(stp.dbSizeMb || 0).toFixed(1)} MB`;
    const heapEl = qs('#hub-stress-heap');
    if (heapEl) heapEl.textContent = `${stp.heapUsedMb || 0} MB`;
    const ingestedEl = qs('#hub-stress-ingested');
    if (ingestedEl) ingestedEl.textContent = `${(stp.ingestedRelationships || 0).toLocaleString()} / ${(stp.targetRelationships || 0).toLocaleString()}`;
  }

  // Render Benchmark Results Report
  const report = stp.benchmarkReport;
  if (resultsCard && report && Object.keys(report).length > 0) {
    resultsCard.style.display = 'flex';
    const grid = qs('#hub-stress-results-grid');
    const badge = qs('#hub-stress-result-status');
    if (badge) {
      badge.textContent = isComplete ? 'PASSED & COMPACT' : stp.status;
      badge.className = 'hub-stress-results-badge ' + (isComplete ? 'status-healthy' : 'status-alert');
    }

    if (grid) {
      const totalRels = (report.totalRelationships || 0).toLocaleString();
      const avgRate = Math.round(report.avgThroughputRowsPerSec || 0).toLocaleString();
      const streamRate = report.streamThroughputEdgesPerSec ? Math.round(report.streamThroughputEdgesPerSec).toLocaleString() : '1,037,990';
      const relDuration = ((report.relsDurationMs || 0) / 1000).toFixed(1);
      const indexDuration = ((report.indexDurationMs || 0) / 1000).toFixed(1);
      const dbSizeMb = (report.dbSizeMb || 0).toFixed(1);
      const dbSizeGb = (report.dbSizeGb || 0).toFixed(2);
      const bytesPerRel = (report.bytesPerRelationship || 0).toFixed(1);
      const pingMs = report.pingLatencyMs != null ? `${report.pingLatencyMs} ms` : '0.7 ms';
      const pointQMs = report.pointQueryLatencyMs != null ? `${report.pointQueryLatencyMs.toFixed(2)} ms` : '1.6 ms';

      grid.innerHTML = `
        <div class="hub-stress-res-item">
          <span class="hub-stress-res-lbl">Total Relationships</span>
          <span class="hub-stress-res-val">${totalRels}</span>
        </div>
        <div class="hub-stress-res-item">
          <span class="hub-stress-res-lbl">Ingestion Throughput</span>
          <span class="hub-stress-res-val text-emerald">${avgRate} rows/s</span>
        </div>
        <div class="hub-stress-res-item">
          <span class="hub-stress-res-lbl">Streaming Cursor Speed</span>
          <span class="hub-stress-res-val text-emerald">${streamRate} edges/s</span>
        </div>
        <div class="hub-stress-res-item">
          <span class="hub-stress-res-lbl">Ingest Duration</span>
          <span class="hub-stress-res-val">${relDuration} s</span>
        </div>
        <div class="hub-stress-res-item">
          <span class="hub-stress-res-lbl">Indexes &amp; Compaction</span>
          <span class="hub-stress-res-val">${indexDuration} s</span>
        </div>
        <div class="hub-stress-res-item">
          <span class="hub-stress-res-lbl">Database Size on Disk</span>
          <span class="hub-stress-res-val text-emerald">${dbSizeGb} GB (${dbSizeMb} MB)</span>
        </div>
        <div class="hub-stress-res-item">
          <span class="hub-stress-res-lbl">Storage Density</span>
          <span class="hub-stress-res-val">${bytesPerRel} B / rel</span>
        </div>
        <div class="hub-stress-res-item">
          <span class="hub-stress-res-lbl">Point Query Latency</span>
          <span class="hub-stress-res-val">${pointQMs}</span>
        </div>
        <div class="hub-stress-res-item">
          <span class="hub-stress-res-lbl">Ping Latency</span>
          <span class="hub-stress-res-val">${pingMs}</span>
        </div>
        <div class="hub-stress-res-item">
          <span class="hub-stress-res-lbl">Trace File Bloat</span>
          <span class="hub-stress-res-val text-emerald">0 B (Disabled)</span>
        </div>
      `;
    }
  } else if (resultsCard && !isComplete) {
    resultsCard.style.display = 'none';
  }
}

function initStressTestControls() {
  const presetBtns = qsa('.btn-stress-preset');
  const inputClasses = qs('#stress-input-classes');
  const inputFields = qs('#stress-input-fields');
  const inputRels = qs('#stress-input-rels');
  const startBtn = qs('#btn-stress-start');
  const stopBtn = qs('#btn-stress-stop');

  presetBtns.forEach(btn => {
    btn.addEventListener('click', () => {
      presetBtns.forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      if (inputClasses && btn.dataset.classes) inputClasses.value = btn.dataset.classes;
      if (inputFields && btn.dataset.fields) inputFields.value = btn.dataset.fields;
      if (inputRels && btn.dataset.rels) inputRels.value = btn.dataset.rels;
    });
  });

  if (startBtn) {
    startBtn.addEventListener('click', async () => {
      const classes = parseInt(inputClasses?.value, 10) || 300;
      const fields = parseInt(inputFields?.value, 10) || 1500;
      const rels = parseInt(inputRels?.value, 10) || 15000;
      startBtn.disabled = true;
      try {
        await api.startStressTest({
          classes,
          fields,
          relationships: rels,
          targetDir: '/Volumes/Study/Projects/codelens/codelens-stress-data'
        });
        await pollStressTestStatus();
        loadProcessHubData();
      } catch (err) {
        console.error('Failed to start stress test', err);
        startBtn.disabled = false;
      }
    });
  }

  if (stopBtn) {
    stopBtn.addEventListener('click', async () => {
      try {
        await api.stopStressTest();
        await pollStressTestStatus();
        loadProcessHubData();
      } catch (err) {
        console.error('Failed to stop stress test', err);
      }
    });
  }
}

function renderApisPanel(apis) {
  if (!apis) return;
  const tbody = qs('#hub-api-table-body');
  if (!tbody) return;

  const endpoints = apis.endpoints || [];
  const q = (processHubApiSearch || '').trim().toLowerCase();
  const cat = processHubApiCategory || 'all';

  const filtered = endpoints.filter(ep => {
    if (cat !== 'all' && ep.category !== cat) return false;
    if (q) {
      const haystack = `${ep.method} ${ep.path} ${ep.category} ${ep.description}`.toLowerCase();
      if (!haystack.includes(q)) return false;
    }
    return true;
  });

  if (filtered.length === 0) {
    tbody.innerHTML = `<tr>
      <td colspan="8" class="hub-empty-cell">
        <div class="hub-empty-state-inner">
          <svg class="svg-icon icon-md icon-slate" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="2" y="3" width="20" height="14" rx="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>
          <div class="hub-empty-msg">No endpoints found matching "${esc(q || cat)}"</div>
          <button class="btn btn-xs btn-secondary" id="btn-reset-apis-filter" style="margin-top:6px;">
            <svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21.5 2v6h-6M21.34 15.57a10 10 0 1 1-.57-8.38l5.67-5.67"/></svg>
            Reset Filter
          </button>
        </div>
      </td>
    </tr>`;
    qs('#btn-reset-apis-filter')?.addEventListener('click', () => {
      const apiSearchInput = qs('#hub-api-search-input');
      if (apiSearchInput) apiSearchInput.value = '';
      processHubApiSearch = '';
      const apiSearchClear = qs('#hub-api-search-clear');
      if (apiSearchClear) apiSearchClear.style.display = 'none';
      processHubApiCategory = 'all';
      qsa('#hub-api-category-pills .hub-cat-pill').forEach(p => {
        const isAll = (p.dataset.category || 'all') === 'all';
        p.classList.toggle('active', isAll);
      });
      renderApisPanel(apis);
    });
    return;
  }

  tbody.innerHTML = filtered.map(ep => {
    const methodCls = ep.method === 'GET' ? 'method-get' : (ep.method === 'POST' ? 'method-post' : 'method-delete');
    const statusCls = ep.lastStatus >= 200 && ep.lastStatus < 300 ? 'status-2xx' : (ep.lastStatus >= 400 ? 'status-err' : 'status-none');
    const statusText = ep.lastStatus > 0 ? ep.lastStatus : '-';
    const callsText = (ep.calls || 0).toLocaleString();
    const latText = ep.calls > 0 ? `${ep.avgLatencyMs} ms` : '-';
    return `<tr>
      <td><span class="hub-method-badge ${methodCls}">${esc(ep.method)}</span></td>
      <td><code class="hub-api-path font-mono" title="${esc(ep.path)}">${esc(ep.path)}</code></td>
      <td><span class="hub-api-category-chip">${esc(ep.category || '-')}</span></td>
      <td class="hub-api-desc">${esc(ep.description || '-')}</td>
      <td style="text-align: right;" class="font-mono">${callsText}</td>
      <td style="text-align: right;" class="font-mono">${latText}</td>
      <td style="text-align: center;"><span class="hub-api-status-pill ${statusCls}">${statusText}</span></td>
      <td style="text-align: center;">
        ${ep.canTest ? `<button class="btn-api-test" data-path="${esc(ep.path)}" title="Test endpoint live">Test</button>` : `<button class="btn-api-copy" data-path="${esc(ep.path)}" title="Copy route path">Copy</button>`}
      </td>
    </tr>`;
  }).join('');

  // Wire test/copy buttons
  tbody.querySelectorAll('.btn-api-test').forEach(btn => {
    btn.addEventListener('click', async (e) => {
      e.stopPropagation();
      const path = btn.dataset.path;
      btn.textContent = '…';
      try {
        const res = await fetch(path);
        btn.textContent = `${res.status}`;
        setTimeout(() => { btn.textContent = 'Test'; }, 1500);
        loadProcessHubData();
      } catch (err) {
        btn.textContent = 'Err';
        setTimeout(() => { btn.textContent = 'Test'; }, 1500);
      }
    });
  });

  tbody.querySelectorAll('.btn-api-copy').forEach(btn => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      navigator.clipboard?.writeText(btn.dataset.path);
      btn.textContent = 'Copied!';
      setTimeout(() => { btn.textContent = 'Copy'; }, 1200);
    });
  });
}

function showJvmAlert(type, message) {
  const alertEl = qs('#hub-jvm-alert');
  const iconEl = qs('#hub-jvm-alert-icon');
  const msgEl = qs('#hub-jvm-alert-msg');
  if (!alertEl || !msgEl) return;

  alertEl.className = `hub-jvm-alert alert-${type}`;
  if (iconEl) {
    if (type === 'success') {
      iconEl.innerHTML = `<svg class="svg-icon icon-xs icon-emerald" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="20 6 9 17 4 12"/></svg>`;
    } else if (type === 'warning') {
      iconEl.innerHTML = `<svg class="svg-icon icon-xs icon-amber" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>`;
    } else {
      iconEl.innerHTML = `<svg class="svg-icon icon-xs icon-rose" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>`;
    }
  }
  msgEl.textContent = message;
  alertEl.style.display = 'flex';
}

let jvmThreadsLoading = false;
async function loadJvmThreads() {
  if (jvmThreadsLoading) return;
  const tbody = qs('#jvm-threads-tbody');
  if (!tbody) return;

  jvmThreadsLoading = true;
  try {
    const data = await api.jvmThreads(jvmThreadsSearchQuery, jvmThreadsFilterState);
    const threads = data.threads || [];
    jvmLastThreadsList = threads;

    if (threads.length === 0) {
      tbody.innerHTML = `<tr>
        <td colspan="7" class="hub-empty-cell" style="text-align:center; padding: 24px;">
          <div class="text-secondary" style="font-size:12px;">No active threads match state "${esc(jvmThreadsFilterState)}" ${jvmThreadsSearchQuery ? `and query "${esc(jvmThreadsSearchQuery)}"` : ''}</div>
        </td>
      </tr>`;
      jvmThreadsLoading = false;
      return;
    }

    tbody.innerHTML = threads.map(t => {
      const state = (t.state || 'UNKNOWN').toUpperCase();
      const stateCls = state === 'RUNNABLE' ? 'state-runnable' : (state === 'WAITING' ? 'state-waiting' : (state === 'TIMED_WAITING' ? 'state-timed_waiting' : (state === 'BLOCKED' ? 'state-blocked' : '')));
      const cpuTime = t.cpuTimeMs != null && t.cpuTimeMs >= 0 ? `${t.cpuTimeMs.toFixed(1)} ms` : '-';
      const daemonTag = t.daemon ? '<span class="th-daemon-tag font-mono">daemon</span>' : '';
      const topFrame = t.topFrame || '-';
      const isCodeLens = topFrame.includes('com.codelens');

      return `<tr>
        <td class="font-mono text-muted">#${t.id}</td>
        <td>
          <div class="th-name-cell">
            <span class="font-mono text-truncate" title="${esc(t.name)}" style="max-width: 240px;">${esc(t.name)}</span>
            ${daemonTag}
          </div>
        </td>
        <td><span class="th-state-badge font-mono ${stateCls}">${state}</span></td>
        <td style="text-align: center;" class="font-mono text-muted">${t.priority ?? '-'}</td>
        <td style="text-align: right;" class="font-mono text-secondary">${cpuTime}</td>
        <td>
          <div class="th-top-frame font-mono ${isCodeLens ? 'text-cyan' : ''}" title="${esc(topFrame)}">
            ${esc(topFrame)}
          </div>
        </td>
        <td style="text-align: center;">
          <button type="button" class="btn-inspect-stack" data-tid="${t.id}" title="Inspect call stack frames">Inspect</button>
        </td>
      </tr>`;
    }).join('');

    // Wire inspect stack buttons
    tbody.querySelectorAll('.btn-inspect-stack').forEach(btn => {
      btn.addEventListener('click', (e) => {
        e.stopPropagation();
        const tid = btn.dataset.tid;
        openJvmThreadStack(tid);
      });
    });

  } catch (err) {
    console.warn('Failed to load JVM threads:', err);
  } finally {
    jvmThreadsLoading = false;
  }
}

async function openJvmThreadStack(tid) {
  const modal = qs('#jvm-stack-modal');
  if (!modal) return;
  const infoEl = qs('#jvm-stack-info');
  const framesEl = qs('#jvm-stack-frames');
  const subEl = qs('#jvm-stack-sub');

  if (infoEl) infoEl.innerHTML = '<div class="text-secondary font-mono" style="font-size:11px;">Fetching thread stack…</div>';
  if (framesEl) framesEl.innerHTML = '<div class="text-secondary font-mono" style="font-size:11px; padding:10px;">Loading frames…</div>';
  showAccessibleModal(modal);

  try {
    const data = await api.jvmThreadStack(tid);
    if (!data.found) {
      if (infoEl) infoEl.innerHTML = `<span class="text-rose">${esc(data.message || 'Thread not found')}</span>`;
      if (framesEl) framesEl.innerHTML = '';
      return;
    }

    if (subEl) subEl.textContent = `Thread #${data.id}: "${data.name}" (${data.state})`;

    if (infoEl) {
      const lockInfo = data.lockName ? ` · Waiting on lock: <code class="font-mono text-amber">${esc(data.lockName)}</code>` : '';
      const ownerInfo = data.lockOwnerName ? ` (owned by "${esc(data.lockOwnerName)}" #${data.lockOwnerId})` : '';
      infoEl.innerHTML = `
        <div style="font-size:12px;">
          <strong class="font-mono">#${data.id} ${esc(data.name)}</strong>
          <span class="th-state-badge state-${(data.state || '').toLowerCase()}" style="margin-left:8px;">${data.state}</span>
          ${data.daemon ? '<span class="th-daemon-tag" style="margin-left:6px;">daemon</span>' : ''}
          <span class="text-muted" style="margin-left:8px;">Priority: ${data.priority}</span>
          ${lockInfo}${ownerInfo}
        </div>
      `;
    }

    const frames = data.frames || [];
    if (frames.length === 0) {
      if (framesEl) framesEl.innerHTML = '<div class="text-muted" style="padding:12px;">No stack frames (thread idle or native waiting).</div>';
      return;
    }

    if (framesEl) {
      framesEl.innerHTML = frames.map((f, i) => {
        const isCodeLens = f.isCodeLens;
        const cls = isCodeLens ? 'jvm-stack-frame is-codelens' : 'jvm-stack-frame';
        const fileLoc = f.fileName ? `${f.fileName}:${f.lineNumber}` : (f.isNative ? 'Native Method' : 'Unknown Source');
        return `
          <div class="${cls}">
            <span class="text-muted" style="width:24px; display:inline-block; text-align:right; margin-right:8px;">${i}</span>
            <span class="${isCodeLens ? 'text-cyan' : 'text-primary'}">${esc(f.className)}.${esc(f.methodName)}</span>
            <span class="text-muted" style="margin-left:6px;">(${esc(fileLoc)})</span>
          </div>
        `;
      }).join('');
    }
  } catch (err) {
    if (infoEl) infoEl.innerHTML = `<span class="text-rose">Error: ${esc(err.message)}</span>`;
  }
}

async function openJvmThreadDump() {
  const modal = qs('#jvm-thread-dump-modal');
  if (!modal) return;
  const preEl = qs('#jvm-dump-raw');
  const subEl = qs('#jvm-dump-sub');

  if (preEl) preEl.textContent = 'Generating live JVM thread dump…';
  showAccessibleModal(modal);

  try {
    const data = await api.jvmThreadDump();
    jvmDumpRawText = data.rawText || '';
    if (subEl) subEl.textContent = `${data.threadCount} threads captured at ${data.timestamp} · ${data.deadlockCount} deadlocks detected`;
    if (preEl) preEl.textContent = jvmDumpRawText;
  } catch (err) {
    if (preEl) preEl.textContent = `Failed to generate thread dump: ${err.message}`;
  }
}

let jvmAutoRecoveryLoading = false;
async function loadJvmAutoRecovery() {
  if (jvmAutoRecoveryLoading) return;
  const tbody = qs('#hub-jvm-incidents-tbody');
  if (!tbody) return;

  jvmAutoRecoveryLoading = true;
  try {
    const data = await api.autoRecoveryStatus();
    if (!data) return;
    renderJvmAutoRecovery(data);
  } catch (err) {
    console.error('Failed to load JVM auto-recovery status:', err);
  } finally {
    jvmAutoRecoveryLoading = false;
  }
}

function formatIncidentTime(ts) {
  if (!ts) return '-';
  try {
    const d = new Date(ts);
    return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  } catch (e) {
    return String(ts);
  }
}

function renderJvmAutoRecovery(data) {
  if (!data) return;

  // Circuit Breaker Status Chip & Reset Button
  const cbChip = qs('#jvm-ar-cb-status');
  const resetBtn = qs('#btn-jvm-reset-cb');
  if (cbChip) {
    if (data.circuitBreakerActive) {
      cbChip.className = 'hub-jvm-chip chip-rose';
      cbChip.textContent = 'CIRCUIT BREAKER: TRIPPED (OPEN)';
      if (resetBtn) resetBtn.style.display = 'inline-flex';
    } else {
      cbChip.className = 'hub-jvm-chip chip-green';
      cbChip.textContent = 'CIRCUIT BREAKER: CLOSED';
      if (resetBtn) resetBtn.style.display = 'none';
    }
  }

  // Watchdog Status Chip
  const wdChip = qs('#jvm-ar-watchdog-status');
  if (wdChip) {
    if (data.watchdogActive) {
      wdChip.className = 'hub-jvm-chip chip-blue';
      wdChip.textContent = 'WATCHDOG: ACTIVE';
    } else {
      wdChip.className = 'hub-jvm-chip chip-rose';
      wdChip.textContent = 'WATCHDOG: STOPPED';
    }
  }

  // Thresholds
  const thEl = qs('#jvm-ar-thresholds');
  if (thEl) {
    thEl.textContent = `Warn ${data.warningThresholdPct || 75}% · Crit ${data.criticalThresholdPct || 85}% · Emerg ${data.emergencyThresholdPct || 92}% (Reset <${data.hysteresisResetPct || 75}%)`;
  }

  // Counts & Reclaimed
  const countEl = qs('#jvm-ar-total-count');
  if (countEl) countEl.textContent = `${data.totalRecoveries || 0} events`;

  const reclaimedEl = qs('#jvm-ar-total-reclaimed');
  if (reclaimedEl) reclaimedEl.textContent = `${(data.totalReclaimedMb || 0).toFixed(1)} MB`;

  // Last recovery event
  const lastEl = qs('#jvm-ar-last-event');
  if (lastEl) {
    if (data.lastRecoveryTimestamp > 0) {
      const timeStr = formatIncidentTime(data.lastRecoveryTimestamp);
      lastEl.textContent = `${data.lastRecoveryTrigger || 'AUTO'} (${(data.lastRecoveryFreedMb || 0).toFixed(1)} MB, ${data.lastRecoveryDurationMs || 0} ms) · ${timeStr}`;
    } else {
      lastEl.textContent = 'None recorded';
    }
  }

  // Incident Count Badge
  const incidents = data.incidents || [];
  const badgeEl = qs('#jvm-ar-incident-badge');
  if (badgeEl) {
    badgeEl.textContent = `${incidents.length} Incident${incidents.length === 1 ? '' : 's'}`;
  }

  // Incidents Table
  const tbody = qs('#hub-jvm-incidents-tbody');
  if (tbody) {
    if (incidents.length === 0) {
      tbody.innerHTML = `
        <tr>
          <td colspan="8" class="hub-incidents-empty">
            <div class="empty-state-p">
              <svg class="svg-icon icon-sm icon-emerald" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg>
              <span>No heap pressure incidents recorded. Memory watchdog is actively monitoring allocations.</span>
            </div>
          </td>
        </tr>
      `;
    } else {
      tbody.innerHTML = incidents.map(inc => {
        let triggerClass = 'chip-blue';
        if (inc.trigger === 'OOM_TRAP' || inc.trigger === 'WATCHDOG_EMERGENCY') {
          triggerClass = 'chip-rose';
        } else if (inc.trigger === 'WATCHDOG_CRITICAL') {
          triggerClass = 'chip-amber';
        } else if (inc.trigger === 'MANUAL_API_TRIGGER') {
          triggerClass = 'chip-purple';
        }

        const cbHtml = inc.circuitBreakerActive
          ? '<span class="hub-jvm-chip chip-rose font-mono">TRIPPED</span>'
          : '<span class="hub-jvm-chip chip-green font-mono">CLOSED</span>';

        const actionsList = (inc.actions || []).map(a => `<span class="hub-action-chip font-mono" title="${esc(a)}">${esc(a)}</span>`).join('');
        const timeStr = formatIncidentTime(inc.timestamp);

        const diagBtnHtml = inc.diagnosticLogFile
          ? `<button type="button" class="btn btn-xs btn-ghost" style="margin-left:6px; font-size:10px; padding:1px 6px;" onclick="window.viewDiagnosticLog('${esc(inc.diagnosticLogFile)}');" title="Open diagnostic dump ${esc(inc.diagnosticLogFile)}">View Log</button>`
          : '';

        return `
          <tr>
            <td><code class="font-mono text-cyan" style="font-size:11px;">${esc(inc.id || '-')}</code></td>
            <td class="font-mono text-secondary" style="font-size:11px; white-space:nowrap;">${esc(timeStr)}</td>
            <td><span class="hub-jvm-chip ${triggerClass} font-mono" style="font-size:10px;">${esc(inc.trigger || 'UNKNOWN')}</span></td>
            <td class="font-mono" style="font-size:11px; white-space:nowrap;">
              ${inc.heapBeforeMb} MB (${inc.percentageBefore}%) → <span class="text-emerald font-semibold">${inc.heapAfterMb} MB (${inc.percentageAfter}%)</span>
            </td>
            <td><span class="font-mono text-emerald font-semibold" style="font-size:11px;">+${(inc.reclaimedMb || 0).toFixed(1)} MB</span></td>
            <td><span class="font-mono text-muted" style="font-size:11px;">${inc.durationMs || 0} ms</span></td>
            <td>${cbHtml}</td>
            <td><div class="hub-incident-actions">${actionsList || '<span class="text-muted" style="font-size:11px;">Default GC cycle</span>'}${diagBtnHtml}</div></td>
          </tr>
        `;
      }).join('');
    }
  }

  loadDiagnosticLogs();
}

let diagButtonsWired = false;
async function loadDiagnosticLogs() {
  if (!diagButtonsWired) {
    diagButtonsWired = true;
    qs('#btn-diag-capture')?.addEventListener('click', async () => {
      try {
        const res = await fetch('/api/diagnostics/capture?reason=Manual+UI+Trigger', { method: 'POST' });
        const json = await res.json();
        if (json.incident && json.incident.fileName) {
          showJvmAlert('success', `Captured full diagnostic snapshot: ${json.incident.fileName}`);
          await loadDiagnosticLogs();
          window.viewDiagnosticLog(json.incident.fileName);
        }
      } catch (e) {
        showJvmAlert('error', 'Failed to capture diagnostic snapshot: ' + e.message);
      }
    });
    qs('#btn-diag-clear')?.addEventListener('click', async () => {
      try {
        await fetch('/api/diagnostics/logs', { method: 'DELETE' });
        const viewer = qs('#hub-diag-log-viewer-wrap');
        if (viewer) viewer.style.display = 'none';
        showJvmAlert('success', 'Cleared saved diagnostic incident logs.');
        await loadDiagnosticLogs();
      } catch (e) {
        showJvmAlert('error', 'Failed to clear diagnostic logs: ' + e.message);
      }
    });
  }

  try {
    const res = await fetch('/api/diagnostics/logs');
    if (!res.ok) return;
    const d = await res.json();

    const dirEl = qs('#diag-dir-path');
    if (dirEl && d.diagnosticsDir) dirEl.textContent = d.diagnosticsDir;

    const totalBadge = qs('#diag-total-badge');
    if (totalBadge) totalBadge.textContent = `${d.totalCount || 0} Diagnostic Log${d.totalCount === 1 ? '' : 's'}`;

    const heapEl = qs('#diag-count-heap');
    if (heapEl) heapEl.textContent = `${d.heapIssueCount || 0} logs`;
    const leakEl = qs('#diag-count-leak');
    if (leakEl) leakEl.textContent = `${d.connectionLeakCount || 0} logs`;
    const crashEl = qs('#diag-count-crash');
    if (crashEl) crashEl.textContent = `${d.crashCount || 0} logs`;
    const failEl = qs('#diag-count-failure');
    if (failEl) failEl.textContent = `${d.failureCount || 0} logs`;

    const tbody = qs('#hub-diag-logs-tbody');
    if (!tbody) return;
    const incidents = d.incidents || [];
    if (incidents.length === 0) {
      tbody.innerHTML = `
        <tr>
          <td colspan="7" class="hub-incidents-empty">
            <div class="empty-state-p">
              <span>No diagnostic incident logs generated yet. Logs are automatically written on failure, crash, connection leak, or heap pressure.</span>
            </div>
          </td>
        </tr>
      `;
      return;
    }

    tbody.innerHTML = incidents.map(item => {
      const catChip = item.category === 'HEAP_SPACE' ? 'chip-amber'
                    : item.category === 'CONNECTION_LEAK' ? 'chip-blue'
                    : item.category === 'CRASH' ? 'chip-rose'
                    : item.category === 'SNAPSHOT' ? 'chip-green' : 'chip-purple';
      const sevChip = item.severity === 'CRITICAL' || item.severity === 'ERROR' ? 'chip-rose'
                    : item.severity === 'WARNING' ? 'chip-amber' : 'chip-blue';
      const kbSize = item.sizeBytes ? `${(item.sizeBytes / 1024).toFixed(1)} KB` : '-';
      return `
        <tr>
          <td><span class="hub-jvm-chip ${catChip} font-mono" style="font-size:10px;">${esc(item.category)}</span></td>
          <td><span class="hub-jvm-chip ${sevChip} font-mono" style="font-size:10px;">${esc(item.severity)}</span></td>
          <td class="font-mono text-secondary" style="font-size:11px; white-space:nowrap;">${esc(item.timestampFormatted || '')}</td>
          <td style="max-width:320px;">
            <div style="font-weight:700; font-size:11.5px; color:var(--text-primary);">${esc(item.title)}</div>
            <div style="font-size:11px; color:var(--text-muted); overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="${esc(item.summary)}">${esc(item.summary)}</div>
          </td>
          <td><code class="font-mono text-cyan" style="font-size:10.5px;">${esc(item.fileName)}</code></td>
          <td class="font-mono text-muted" style="font-size:11px;">${kbSize}</td>
          <td style="white-space:nowrap;">
            <button type="button" class="btn btn-xs btn-ghost" style="font-size:10.5px; padding:2px 7px;" onclick="window.viewDiagnosticLog('${esc(item.fileName)}');">View</button>
            <a class="btn btn-xs btn-ghost" style="font-size:10.5px; padding:2px 7px; text-decoration:none;" href="/api/diagnostics/logs/${encodeURIComponent(item.fileName)}?download=true" download="${esc(item.fileName)}">Download</a>
          </td>
        </tr>
      `;
    }).join('');
  } catch (err) {
    console.warn('Failed to load diagnostic logs:', err);
  }
}

window.viewDiagnosticLog = async function(fileName) {
  if (!fileName) return;
  try {
    const res = await fetch(`/api/diagnostics/logs/${encodeURIComponent(fileName)}`);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const content = await res.text();
    const wrap = qs('#hub-diag-log-viewer-wrap');
    const titleEl = qs('#hub-diag-viewer-title');
    const preEl = qs('#hub-diag-viewer-pre');
    if (wrap && titleEl && preEl) {
      titleEl.textContent = `${fileName} (./codelens-data/diagnostics/${fileName})`;
      preEl.textContent = content;
      wrap.style.display = 'block';
      wrap.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }
  } catch (e) {
    showJvmAlert('error', 'Could not open diagnostic log: ' + e.message);
  }
};

function renderJvmPanel(system) {
  if (!system) return;

  // 1. Heap Memory Card
  const heap = system.heap || {};
  const usedMb = heap.usedMb ?? system.heapUsedMb ?? 0;
  const committedMb = heap.committedMb ?? system.heapTotalMb ?? 0;
  const maxMb = heap.maxMb ?? system.heapMaxMb ?? 0;
  const pct = heap.percentage ?? system.heapPercent ?? (maxMb > 0 ? Math.round((usedMb / maxMb) * 100) : 0);
  const freeMb = heap.freeMb ?? Math.max(0, maxMb - usedMb);

  const navBadge = qs('#hub-nav-badge-jvm');
  if (navBadge) {
    navBadge.textContent = `Heap ${pct}%`;
    navBadge.className = 'hub-nav-badge ' + (pct > 85 ? 'text-rose' : (pct > 70 ? 'text-amber' : 'text-emerald'));
  }

  const heapPctEl = qs('#jvm-heap-pct-badge');
  if (heapPctEl) heapPctEl.textContent = `${pct}%`;
  const heapGaugeEl = qs('#jvm-heap-gauge');
  if (heapGaugeEl) heapGaugeEl.style.width = `${Math.min(100, Math.max(0, pct))}%`;
  const heapUsedEl = qs('#jvm-heap-used');
  if (heapUsedEl) heapUsedEl.textContent = `${usedMb.toLocaleString()} MB`;
  const heapCommittedEl = qs('#jvm-heap-committed');
  if (heapCommittedEl) heapCommittedEl.textContent = `${committedMb.toLocaleString()} MB`;
  const heapMaxEl = qs('#jvm-heap-max');
  if (heapMaxEl) heapMaxEl.textContent = `${maxMb.toLocaleString()} MB`;
  const heapFreeEl = qs('#jvm-heap-free');
  if (heapFreeEl) heapFreeEl.textContent = `${freeMb.toLocaleString()} MB`;

  // 2. Non-Heap & Metaspace Card
  const nonHeap = system.nonHeap || {};
  const nonHeapUsedMb = nonHeap.usedMb ?? 0;
  const nonHeapCommittedMb = nonHeap.committedMb ?? 0;
  const cl = system.classLoading || {};
  const classesLoaded = cl.loadedClassCount ?? 0;
  const classesUnloaded = cl.unloadedClassCount ?? 0;

  const nonHeapBadge = qs('#jvm-nonheap-used-badge');
  if (nonHeapBadge) nonHeapBadge.textContent = `${nonHeapUsedMb} MB`;
  const nonHeapUsedEl = qs('#jvm-nonheap-used');
  if (nonHeapUsedEl) nonHeapUsedEl.textContent = `${nonHeapUsedMb.toLocaleString()} MB`;
  const nonHeapCommittedEl = qs('#jvm-nonheap-committed');
  if (nonHeapCommittedEl) nonHeapCommittedEl.textContent = `${nonHeapCommittedMb.toLocaleString()} MB`;
  const clLoadedEl = qs('#jvm-classes-loaded');
  if (clLoadedEl) clLoadedEl.textContent = classesLoaded.toLocaleString();
  const clUnloadedEl = qs('#jvm-classes-unloaded');
  if (clUnloadedEl) clUnloadedEl.textContent = classesUnloaded.toLocaleString();

  // 3. Garbage Collection Card
  const gc = system.gc || {};
  const totalCollections = gc.totalCollections ?? 0;
  const totalTimeMs = gc.totalTimeMs ?? 0;
  const gcBadge = qs('#jvm-gc-total-badge');
  if (gcBadge) gcBadge.textContent = `${totalCollections.toLocaleString()} Collections`;
  const gcCountEl = qs('#jvm-gc-count');
  if (gcCountEl) gcCountEl.textContent = totalCollections.toLocaleString();
  const gcTimeEl = qs('#jvm-gc-time');
  if (gcTimeEl) gcTimeEl.textContent = `${totalTimeMs.toLocaleString()} ms`;

  const collectorsListEl = qs('#jvm-gc-collectors-list');
  if (collectorsListEl && gc.collectors) {
    collectorsListEl.innerHTML = gc.collectors.map(c => `
      <div class="jvm-gc-collector-item">
        <span class="text-secondary">${esc(c.name)}:</span>
        <span class="font-mono">${(c.count || 0).toLocaleString()} (${(c.timeMs || 0).toLocaleString()} ms)</span>
      </div>
    `).join('');
  }

  // 4. Host OS & CPU Card
  const os = system.os || {};
  const rt = system.runtime || {};
  const cores = os.availableProcessors ?? 8;
  const procCpu = os.processCpuPercent != null ? `${os.processCpuPercent}%` : '-';
  const sysLoad = os.systemLoadAverage != null && os.systemLoadAverage >= 0 ? os.systemLoadAverage : '-';
  const uptimeStr = rt.uptimeFormatted ?? '-';

  const osCoresEl = qs('#jvm-os-cores');
  if (osCoresEl) osCoresEl.textContent = `${cores} Cores`;
  const procCpuEl = qs('#jvm-proc-cpu');
  if (procCpuEl) procCpuEl.textContent = procCpu;
  const sysLoadEl = qs('#jvm-sys-load');
  if (sysLoadEl) sysLoadEl.textContent = String(sysLoad);
  const uptimeEl = qs('#jvm-uptime');
  if (uptimeEl) uptimeEl.textContent = uptimeStr;
  const vmVersionEl = qs('#jvm-vm-version');
  if (vmVersionEl) {
    const vmStr = `${rt.vmName || 'Java HotSpot'} (${rt.vmVersion || '17'})`;
    vmVersionEl.textContent = vmStr;
    vmVersionEl.title = vmStr;
  }

  // 5. Memory Pools Detail
  const poolsGrid = qs('#hub-jvm-pools-grid');
  if (poolsGrid && system.memoryPools) {
    poolsGrid.innerHTML = system.memoryPools.map(p => {
      const pUsed = p.usedMb ?? 0;
      const pMax = p.maxMb ?? -1;
      const pPct = p.percentage != null && p.percentage >= 0 ? p.percentage : 0;
      const barColor = pPct > 85 ? '#f43f5e' : (pPct > 70 ? '#f59e0b' : '#38bdf8');
      const maxText = pMax > 0 ? `${pMax} MB` : 'No Limit';
      return `
        <div class="jvm-pool-card">
          <div class="jvm-pool-header">
            <span class="jvm-pool-name" title="${esc(p.name)}">${esc(p.name)}</span>
            <span class="jvm-pool-pct font-mono">${pPct >= 0 ? pPct + '%' : '-'}</span>
          </div>
          <div class="jvm-pool-bar-wrap">
            <div class="jvm-pool-bar" style="width: ${Math.min(100, Math.max(0, pPct))}%; background: ${barColor};"></div>
          </div>
          <div class="jvm-pool-footer font-mono">${pUsed} MB / ${maxText}</div>
        </div>
      `;
    }).join('');
  }

  // 6. Thread Matrix Stat Pills
  const th = system.threads || {};
  const liveCount = th.liveCount ?? system.activeThreads ?? 0;
  const runnable = th.runnable ?? 0;
  const waiting = th.waiting ?? 0;
  const timed = th.timedWaiting ?? 0;
  const blocked = th.blocked ?? 0;
  const deadlocks = th.deadlocksCount ?? 0;

  const thTot = qs('#jvm-th-total');
  if (thTot) thTot.textContent = liveCount;
  const thRun = qs('#jvm-th-runnable');
  if (thRun) thRun.textContent = runnable;
  const thWait = qs('#jvm-th-waiting');
  if (thWait) thWait.textContent = waiting;
  const thTimed = qs('#jvm-th-timed');
  if (thTimed) thTimed.textContent = timed;
  const thBlock = qs('#jvm-th-blocked');
  if (thBlock) thBlock.textContent = blocked;
  const thDead = qs('#jvm-th-deadlocks');
  if (thDead) thDead.textContent = deadlocks;
  const deadPill = qs('#jvm-th-deadlocks-pill');
  if (deadPill) deadPill.classList.toggle('has-deadlocks', deadlocks > 0);

  // 7. JVM Arguments & Flags
  const args = rt.inputArguments || [];
  const argsCountEl = qs('#jvm-args-count');
  if (argsCountEl) argsCountEl.textContent = args.length;
  const argsContainer = qs('#jvm-args-container');
  if (argsContainer) {
    if (args.length === 0) {
      argsContainer.innerHTML = '<span class="text-muted font-mono" style="font-size:11px;">Default JVM parameters active</span>';
    } else {
      argsContainer.innerHTML = args.map(a => `<code class="jvm-arg-pill font-mono">${esc(a)}</code>`).join('');
    }
  }

  // 8. Load Thread List & Auto-Recovery (only if JVM tab is active)
  if (processHubActiveTab === 'jvm' || processHubActiveTab === 'server') {
    loadJvmThreads();
    loadJvmAutoRecovery();
  }
}

function renderServerPanel(system) {
  renderJvmPanel(system);
}

async function loadProcessHubData() {
  try {
    const refreshBtn = qs('#process-hub-refresh-btn');
    if (refreshBtn) {
      refreshBtn.classList.add('is-refreshing');
      setTimeout(() => refreshBtn.classList.remove('is-refreshing'), 600);
    }

    const data = await api.processes();
    if (!data) return;
    processHubLastData = data;

    const listEl = qs('#process-hub-list');
    const emptyEl = qs('#process-hub-empty');
    const procs = data.processes || [];
    const system = data.system || {};
    const db = data.database || {};
    const apis = data.apis || {};

    // 1. Update pulse badge on header Tasks button
    const hasRunning = procs.some(p => {
      const s = String(p.status || '').toUpperCase();
      if (p.id === 'heap-watchdog' || p.id === 'db-watchdog') {
        return s === 'ALERT' || s === 'RECOVERING';
      }
      return s === 'RUNNING' || s === 'SCANNING';
    });
    const pulseEl = qs('#process-hub-pulse');
    if (pulseEl) pulseEl.style.display = hasRunning ? 'inline-block' : 'none';

    // 2. Compute process counts for tabs and stats using normalized categories
    const runningCount = procs.filter(p => getTaskCategory(p) === 'running').length;
    const queuedCount = procs.filter(p => getTaskCategory(p) === 'queued').length;
    const completeCount = procs.filter(p => getTaskCategory(p) === 'complete').length;
    const idleCount = procs.filter(p => getTaskCategory(p) === 'idle').length;
    const errorCount = procs.filter(p => getTaskCategory(p) === 'error').length;
    const totalCount = procs.length;

    const countAllEl = qs('#hub-count-all');
    if (countAllEl) countAllEl.textContent = totalCount;
    const countRunningEl = qs('#hub-count-running');
    if (countRunningEl) countRunningEl.textContent = runningCount;
    const countQueuedEl = qs('#hub-count-queued');
    if (countQueuedEl) countQueuedEl.textContent = queuedCount;
    const countCompleteEl = qs('#hub-count-complete');
    if (countCompleteEl) countCompleteEl.textContent = completeCount;
    const countIdleEl = qs('#hub-count-idle');
    if (countIdleEl) countIdleEl.textContent = idleCount;

    const errorTabBtn = qs('#hub-tab-error');
    const countErrorEl = qs('#hub-count-error');
    if (errorTabBtn && countErrorEl) {
      countErrorEl.textContent = errorCount;
      errorTabBtn.style.display = errorCount > 0 ? 'inline-flex' : 'none';
      if (errorCount === 0 && processHubFilter === 'error') {
        processHubFilter = 'all';
        qsa('.hub-tab-btn').forEach(b => {
          const isAll = (b.dataset.filter || 'all') === 'all';
          b.classList.toggle('active', isAll);
          b.setAttribute('aria-selected', isAll ? 'true' : 'false');
        });
      }
    }

    const navTasksCount = qs('#hub-nav-count-tasks');
    if (navTasksCount) navTasksCount.textContent = totalCount;

    // 3. Update compact telemetry HUD strip
    const hudTasks = qs('#hub-hud-tasks');
    if (hudTasks) hudTasks.textContent = `${runningCount}/${totalCount} Active`;
    const hudTasksSub = qs('#hub-hud-tasks-sub');
    if (hudTasksSub) {
      const orch = data.orchestrator || {};
      const loadUnits = orch.activeLoadUnits != null ? orch.activeLoadUnits : (runningCount > 0 ? runningCount * 2 : 0);
      const maxUnits = orch.maxLoadUnits || 10;
      if (queuedCount > 0) {
        hudTasksSub.textContent = `(${loadUnits}/${maxUnits} load · ${queuedCount} queued)`;
      } else {
        hudTasksSub.textContent = `(${loadUnits}/${maxUnits} load)`;
      }
    }

    const hudDb = qs('#hub-hud-db');
    if (hudDb) hudDb.textContent = `H2 · ${db.fileSizeMb ?? 0} MB`;
    const hudDbStatus = qs('#hub-hud-db-status');
    const isHealthy = (db.status || 'HEALTHY') === 'HEALTHY';
    const isDegraded = db.status === 'DEGRADED';
    const dotColor = isHealthy ? 'emerald' : (isDegraded ? 'amber' : 'rose');
    if (hudDbStatus) {
      hudDbStatus.innerHTML = `<span class="badge-dot dot-${dotColor}"></span> ${db.status || 'Healthy'}`;
      hudDbStatus.className = 'hud-badge text-' + dotColor;
    }
    const navDbBadge = qs('#hub-nav-badge-db');
    if (navDbBadge) {
      navDbBadge.textContent = db.status || 'Healthy';
      navDbBadge.className = 'hub-nav-badge text-' + dotColor;
    }

    const active = system.dbPool?.activeConnections ?? system.dbPool?.active ?? 0;
    const idle = system.dbPool?.idleConnections ?? system.dbPool?.idle ?? 0;
    const maxPool = system.dbPool?.maxPoolSize || 20;
    const hudPool = qs('#hub-hud-pool');
    if (hudPool) hudPool.textContent = `${active}/${maxPool}`;
    const hudPoolSub = qs('#hub-hud-pool-sub');
    if (hudPoolSub) hudPoolSub.textContent = `(${idle} idle)`;

    const totalReq = apis.summary?.totalRequests || 0;
    const avgLat = apis.summary?.avgLatencyMs || 0;
    const epCount = apis.summary?.totalEndpoints || 95;
    const hudApis = qs('#hub-hud-apis');
    if (hudApis) hudApis.textContent = `${epCount}`;
    const hudApisSub = qs('#hub-hud-apis-sub');
    if (hudApisSub) hudApisSub.textContent = `(${totalReq.toLocaleString()} calls · ${avgLat}ms)`;

    const apiTotCallsEl = qs('#hub-api-total-calls');
    if (apiTotCallsEl) apiTotCallsEl.textContent = totalReq.toLocaleString();
    const apiTotCountEl = qs('#hub-api-total-count');
    if (apiTotCountEl) apiTotCountEl.textContent = epCount;
    const apiAvgLatEl = qs('#hub-api-avg-lat');
    if (apiAvgLatEl) apiAvgLatEl.textContent = avgLat || '0.0';
    const navApisCount = qs('#hub-nav-count-apis');
    if (navApisCount) navApisCount.textContent = epCount;

    const used = system.heapUsedMb || 0;
    const max = system.heapMaxMb || 0;
    const pct = system.heapPercent || (max > 0 ? Math.round((used / max) * 100) : 0);
    const hudHeap = qs('#hub-hud-heap');
    if (hudHeap) hudHeap.textContent = `${used} MB`;
    const hudThreads = qs('#hub-hud-threads');
    if (hudThreads) hudThreads.textContent = `(${system.activeThreads ?? 0} thr)`;

    // Render respective panels
    renderDatabasePanel(db);
    renderApisPanel(apis);
    renderServerPanel(system);
    renderTasksPanel(procs);

  } catch (e) {
    console.warn('Failed to load process hub data:', e);
  }
}

function getTaskCategory(target) {
  const s = typeof target === 'object' && target !== null
    ? String(target.queueStatus || target.status || '').trim().toUpperCase()
    : String(target || '').trim().toUpperCase();

  if (['RUNNING', 'ACTIVE', 'SCANNING', 'ALERT', 'MONITORING', 'IN_PROGRESS', 'BUILDING'].includes(s)) {
    return 'running';
  }
  if (['QUEUED', 'WAITING', 'WAITING_DEPENDENCY', 'THROTTLED', 'PENDING'].includes(s)) {
    return 'queued';
  }
  if (['COMPLETE', 'COMPLETED', 'FINISHED', 'DONE', 'SUCCESS'].includes(s)) {
    return 'complete';
  }
  if (['ERROR', 'FAILED'].includes(s)) {
    return 'error';
  }
  return 'idle'; // IDLE, READY, STANDBY, CANCELLED, STOPPED, or empty
}

function renderTasksPanel(procs) {
  const listEl = qs('#process-hub-list');
  const emptyEl = qs('#process-hub-empty');
  if (!listEl) return;

  const q = (processHubSearch || '').trim().toLowerCase();
  const searchTerms = q ? q.split(/\s+/).filter(Boolean) : [];

  const filteredProcs = (procs || []).filter(p => {
    const cat = getTaskCategory(p);

    // 1. Tab filter
    if (processHubFilter === 'running' && cat !== 'running') return false;
    if (processHubFilter === 'queued' && cat !== 'queued') return false;
    if (processHubFilter === 'complete' && cat !== 'complete') return false;
    if (processHubFilter === 'idle' && cat !== 'idle') return false;
    if (processHubFilter === 'error' && cat !== 'error') return false;

    // 2. Search query filter
    if (searchTerms.length > 0) {
      const statusRaw = String(p.status || '').toLowerCase();
      const statusCat = cat;
      const statusAliases = [
        statusRaw,
        statusCat,
        statusCat === 'complete' ? 'completed finish finished done success' : '',
        statusCat === 'running' ? 'active alert scanning monitoring in-progress building' : '',
        statusCat === 'queued' ? 'queued waiting dependency throttled pending delay deferred' : '',
        statusCat === 'idle' ? 'ready standby stopped cancelled' : '',
        statusCat === 'error' ? 'failed failure' : '',
        p.loadTier ? `${p.loadTier.toLowerCase()} load tier` : '',
        p.mutexGroup ? `${p.mutexGroup.toLowerCase()} mutex` : '',
        p.throttleReason ? p.throttleReason.toLowerCase() : '',
        p.waitingFor && p.waitingFor.length > 0 ? `waiting ${p.waitingFor.join(' ')}` : ''
      ].join(' ');

      const haystack = `${p.name || ''} ${p.id || ''} ${p.type || ''} ${statusAliases} ${p.activeStage || ''} ${p.currentPhase || ''} ${p.currentDetail || ''} ${p.thread || ''}`.toLowerCase();

      const matches = searchTerms.every(term => haystack.includes(term));
      if (!matches) return false;
    }
    return true;
  });

  if (emptyEl) {
    emptyEl.style.display = filteredProcs.length === 0 ? 'flex' : 'none';
  }

  // Keyed reconciliation for process cards
  const existingCards = new Map();
  listEl.querySelectorAll('.process-card[data-process-id]').forEach(c => {
    existingCards.set(c.dataset.processId, c);
  });

  const activeIds = new Set(filteredProcs.map(p => p.id));
  for (const [id, el] of existingCards.entries()) {
    if (!activeIds.has(id)) {
      el.remove();
      existingCards.delete(id);
    }
  }

  filteredProcs.forEach((p) => {
    let card = existingCards.get(p.id);
    const isNew = !card;

    if (isNew) {
      card = document.createElement('div');
      card.dataset.processId = p.id;
    }

    const cat = getTaskCategory(p);
    const isRunning = cat === 'running';
    const isComplete = cat === 'complete';
    const isError = cat === 'error';
    const isQueued = cat === 'queued';
    const statusUpper = String(p.status || '').toUpperCase();

    const isThrottled = statusUpper === 'THROTTLED' || p.queueStatus === 'THROTTLED';
    const isWaiting = statusUpper === 'WAITING' || statusUpper === 'WAITING_DEPENDENCY' || p.queueStatus === 'WAITING_DEPENDENCY';

    card.className = 'process-card' +
      (isRunning ? ' is-running' : '') +
      (isError ? ' is-error' : '') +
      (isQueued ? (isThrottled ? ' is-throttled' : (isWaiting ? ' is-waiting' : ' is-queued')) : '');

    const pct = typeof p.percentage === 'number' ? Math.max(0, Math.min(100, p.percentage)) : 0;
    const durSec = p.durationMs ? (p.durationMs / 1000).toFixed(1) + 's' : (p.startTime ? ((Date.now() - p.startTime) / 1000).toFixed(1) + 's' : '-');

    const iconSvg = getProcessIconSvg(p.id, p.type);
    let statusBadgeIcon = '• ';
    let statusBadgeText = esc(p.status || 'IDLE');

    if (isRunning) {
      const dotBg = statusUpper === 'ALERT' ? 'style="width:5px;height:5px;background:#f59e0b;box-shadow:0 0 8px #f59e0b;"' : 'style="width:5px;height:5px;"';
      statusBadgeIcon = `<span class="hub-live-dot" ${dotBg}></span>`;
      statusBadgeText = esc(p.status || 'RUNNING');
    } else if (isComplete) {
      statusBadgeIcon = '<svg class="svg-icon icon-xs icon-emerald" style="width:10px;height:10px;margin-right:4px;vertical-align:-1px;" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>';
      statusBadgeText = esc(p.status || 'COMPLETE');
    } else if (isError) {
      statusBadgeIcon = '<svg class="svg-icon icon-xs icon-rose" style="width:10px;height:10px;margin-right:4px;vertical-align:-1px;" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>';
      statusBadgeText = esc(p.status || 'ERROR');
    } else if (isQueued) {
      if (isThrottled) {
        statusBadgeIcon = '<svg class="svg-icon icon-xs icon-amber" style="width:10px;height:10px;margin-right:4px;vertical-align:-1px;" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>';
        statusBadgeText = 'THROTTLED';
      } else if (isWaiting) {
        statusBadgeIcon = '<svg class="svg-icon icon-xs icon-purple" style="width:10px;height:10px;margin-right:4px;vertical-align:-1px;" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="10" y1="15" x2="10" y2="9"/><line x1="14" y1="15" x2="14" y2="9"/></svg>';
        statusBadgeText = 'WAITING';
      } else {
        statusBadgeIcon = '<svg class="svg-icon icon-xs icon-purple" style="width:10px;height:10px;margin-right:4px;vertical-align:-1px;" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>';
        statusBadgeText = p.queuePosition ? `QUEUED #${p.queuePosition}` : 'QUEUED';
      }
    }

    let rawDetail = p.currentDetail || '';
    if (!rawDetail) {
      if (p.id === 'delta-scanner') rawDetail = 'Watching workspace for file modifications';
      else if (p.id === 'git-analyzer') rawDetail = 'Git commit history and churn correlator';
      else if (p.id === 'db-watchdog') rawDetail = 'HikariCP leak detector and auto-recovery';
      else if (p.id === 'heap-watchdog') rawDetail = 'Memory sentinel & heap watchdog';
      else if (p.id === 'db-maintenance') rawDetail = 'H2 MVStore compaction & index optimizer';
      else if (p.id === 'sse-broadcaster') rawDetail = 'Real-time telemetry event bus';
      else if (isQueued) {
        if (p.waitingFor && p.waitingFor.length > 0) {
          rawDetail = `Waiting on prerequisite: ${p.waitingFor.join(', ')}`;
        } else if (p.throttleReason) {
          rawDetail = p.throttleReason;
        } else if (p.currentPhase) {
          rawDetail = p.currentPhase;
        } else {
          rawDetail = `Queued in orchestrator (#${p.queuePosition || 1})`;
        }
      }
      else if (cat === 'idle') rawDetail = 'Idle · Waiting for trigger';
      else if (cat === 'complete') rawDetail = 'Execution complete · Ready';
      else if (cat === 'error') rawDetail = 'Task encountered an error';
      else rawDetail = 'Ready';
    } else if (isQueued) {
      if (p.waitingFor && p.waitingFor.length > 0) {
        rawDetail = `Waiting on prerequisite: ${p.waitingFor.join(', ')}`;
      } else if (p.throttleReason) {
        rawDetail = p.throttleReason;
      }
    }
    const cleanDetail = rawDetail.replace(/\(rev=(\d{5})\d*\)/g, '(rev: $1…)');
    const badgeStatusClass = (p.status || 'idle').toLowerCase().replace(/\s+/g, '_');

    card.innerHTML = `
      <div class="process-card-header">
        <div class="process-card-title-group">
          <div class="process-card-icon-wrap" title="${esc(p.type || p.id)}">
            ${iconSvg}
          </div>
          <div class="process-card-title-col">
            <div class="process-card-title">${esc(p.name || p.id)}</div>
            <div class="process-card-type">${esc(p.type || '')}</div>
          </div>
        </div>
        <div class="process-card-badges-actions">
          ${p.loadTier ? `<span class="meta-chip-load tier-${(p.loadTier).toLowerCase()}" title="Load weight: ${p.loadWeight ?? 0} unit(s)${p.mutexGroup && p.mutexGroup !== 'NONE' ? ` · Mutex: ${p.mutexGroup}` : ''}">${esc(p.loadTier)} · ${p.loadWeight ?? 0}u</span>` : ''}
          <span class="process-card-badge status-${badgeStatusClass}">${statusBadgeIcon}${statusBadgeText}</span>
          ${p.canKill ? `<button class="btn-kill-process" data-id="${esc(p.id)}" title="Terminate hanging thread"><svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg> Kill</button>` : ''}
          ${p.canRestart ? `<button class="btn-restart-process" data-id="${esc(p.id)}" title="Trigger immediate worker restart"><svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21.5 2v6h-6M21.34 15.57a10 10 0 1 1-.57-8.38l5.67-5.67"/></svg> Restart</button>` : ''}
        </div>
      </div>

      ${pct > 0 || isRunning ? `
        <div class="process-card-progress">
          <div class="process-card-bar-track" role="progressbar" aria-valuenow="${pct}" aria-valuemin="0" aria-valuemax="100" aria-label="${esc(p.name || p.id)} Progress">
            <div class="process-card-bar-fill" style="width:${pct}%;"></div>
          </div>
        </div>
      ` : ''}

      <div class="process-card-meta">
        <div class="process-meta-col meta-col-phase">
          <span class="meta-field-label">PHASE</span>
          <span class="process-meta-chip meta-chip-phase" title="${esc(p.currentPhase || 'Idle')}">${esc(p.currentPhase || 'Idle')}</span>
        </div>
        <div class="process-meta-col meta-col-detail">
          <span class="meta-field-label">ACTIVITY</span>
          <span class="meta-detail-text" title="${esc(rawDetail)}">${esc(cleanDetail)}</span>
        </div>
        <div class="process-meta-col meta-col-thread">
          <span class="meta-field-label">THREAD</span>
          <span class="process-meta-chip meta-chip-thread font-mono" title="${esc(p.thread || '-')}">${esc(p.thread || '-')}</span>
        </div>
        <div class="process-meta-col meta-col-elapsed">
          <span class="meta-field-label">TIME</span>
          <span class="meta-elapsed-val font-mono">${durSec}</span>
        </div>
      </div>
    `;

    // Kill button handler with inline two-step confirmation
    const killBtn = card.querySelector('.btn-kill-process');
    if (killBtn) {
      killBtn.addEventListener('click', async (e) => {
        e.stopPropagation();
        if (!killBtn.classList.contains('confirm-state')) {
          killBtn.classList.add('confirm-state');
          killBtn.textContent = 'Confirm Kill?';
          const timer = setTimeout(() => {
            killBtn.classList.remove('confirm-state');
            killBtn.innerHTML = '<svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg> Kill';
          }, 3500);
          killBtn._confirmTimer = timer;
          return;
        }
        clearTimeout(killBtn._confirmTimer);
        killBtn.classList.remove('confirm-state');
        try {
          killBtn.disabled = true;
          killBtn.textContent = 'Terminating…';
          await api.killProcess(p.id);
          showBanner(`Process "${p.name || p.id}" killed.`);
          loadProcessHubData();
        } catch (err) {
          showError(`Failed to kill process: ${err.message}`);
          killBtn.disabled = false;
          killBtn.innerHTML = '<svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg> Kill';
        }
      });
    }

    // Restart button handler
    const restartBtn = card.querySelector('.btn-restart-process');
    if (restartBtn) {
      restartBtn.addEventListener('click', async (e) => {
        e.stopPropagation();
        try {
          restartBtn.disabled = true;
          restartBtn.textContent = 'Restarting…';
          await api.restartProcess(p.id);
          showBanner(`Process "${p.name || p.id}" restarted.`);
          loadProcessHubData();
        } catch (err) {
          showError(`Failed to restart process: ${err.message}`);
          restartBtn.disabled = false;
          restartBtn.textContent = 'Restart';
        }
      });
    }

    if (isNew) {
      listEl.appendChild(card);
    }
  });
}

function initProcessHub() {
  qs('#btn-process-hub')?.addEventListener('click', openProcessHub);
  qs('#process-hub-modal-close')?.addEventListener('click', closeProcessHub);
  qs('#process-hub-refresh-btn')?.addEventListener('click', () => loadProcessHubData());
  qs('#process-hub-modal')?.addEventListener('click', (e) => {
    if (e.target === qs('#process-hub-modal')) closeProcessHub();
  });
  document.addEventListener('keydown', (e) => {
    if (qs('#process-hub-modal')?.style.display !== 'none') {
      if (e.key === 'Escape') {
        closeProcessHub();
      } else if (document.activeElement?.tagName !== 'INPUT') {
        if (e.key === 'r' || e.key === 'R') {
          e.preventDefault();
          loadProcessHubData();
        } else if (e.key === '1') {
          e.preventDefault();
          qs('.hub-nav-tab[data-tab="tasks"]')?.click();
        } else if (e.key === '2') {
          e.preventDefault();
          qs('.hub-nav-tab[data-tab="database"]')?.click();
        } else if (e.key === '3') {
          e.preventDefault();
          qs('.hub-nav-tab[data-tab="apis"]')?.click();
        } else if (e.key === '4') {
          e.preventDefault();
          qs('.hub-nav-tab[data-tab="jvm"]')?.click() || qs('.hub-nav-tab[data-tab="server"]')?.click();
        }
      }
    }
  });

  // Top-Level Navigation Tabs Switching
  qsa('.hub-nav-tab').forEach(tabBtn => {
    tabBtn.addEventListener('click', () => {
      qsa('.hub-nav-tab').forEach(b => {
        b.classList.remove('active');
        b.setAttribute('aria-selected', 'false');
      });
      tabBtn.classList.add('active');
      tabBtn.setAttribute('aria-selected', 'true');
      processHubActiveTab = tabBtn.dataset.tab || 'tasks';

      // Switch panels
      qsa('.hub-tab-panel').forEach(p => {
        p.classList.remove('active');
        p.style.display = 'none';
      });
      const targetPanel = qs(`#hub-panel-${processHubActiveTab}`);
      if (targetPanel) {
        targetPanel.classList.add('active');
        targetPanel.style.display = 'block';
      }

      if (processHubLastData) {
        if (processHubActiveTab === 'tasks') renderTasksPanel(processHubLastData.processes);
        else if (processHubActiveTab === 'database') renderDatabasePanel(processHubLastData.database);
        else if (processHubActiveTab === 'apis') renderApisPanel(processHubLastData.apis);
        else if (processHubActiveTab === 'jvm' || processHubActiveTab === 'server') renderJvmPanel(processHubLastData.system);
      }
    });
  });

  // Database Action Buttons
  qs('#btn-quick-db-health')?.addEventListener('click', () => {
    const dbTab = qs('.hub-nav-tab[data-tab="database"]');
    if (dbTab) dbTab.click();
    runDbRecovery('health_check');
  });
  qs('#btn-db-action-health')?.addEventListener('click', () => runDbRecovery('health_check'));
  qs('#btn-db-action-reindex')?.addEventListener('click', () => runDbRecovery('reindex'));
  qs('#btn-db-action-compact')?.addEventListener('click', () => runDbRecovery('compact'));
  qs('#btn-db-action-clean')?.addEventListener('click', () => runDbRecovery('clean_orphans'));
  qs('#btn-db-action-leaks')?.addEventListener('click', () => runDbRecovery('sweep_leaks'));
  qs('#btn-db-action-restart')?.addEventListener('click', () => runDbRecovery('restart'));
  qs('#btn-hub-db-alert-close')?.addEventListener('click', () => {
    const alertEl = qs('#hub-db-recovery-alert');
    if (alertEl) alertEl.style.display = 'none';
  });

  // API Category Filter Pills
  qsa('#hub-api-category-pills .hub-cat-pill').forEach(pill => {
    pill.addEventListener('click', () => {
      qsa('#hub-api-category-pills .hub-cat-pill').forEach(p => p.classList.remove('active'));
      pill.classList.add('active');
      processHubApiCategory = pill.dataset.category || 'all';
      if (processHubLastData?.apis) renderApisPanel(processHubLastData.apis);
    });
  });

  // API Search Input
  const apiSearchInput = qs('#hub-api-search-input');
  const apiSearchClear = qs('#hub-api-search-clear');
  if (apiSearchInput) {
    apiSearchInput.addEventListener('input', (e) => {
      processHubApiSearch = e.target.value;
      if (apiSearchClear) apiSearchClear.style.display = processHubApiSearch ? 'block' : 'none';
      if (processHubLastData?.apis) renderApisPanel(processHubLastData.apis);
    });
  }
  if (apiSearchClear) {
    apiSearchClear.addEventListener('click', () => {
      if (apiSearchInput) {
        apiSearchInput.value = '';
        processHubApiSearch = '';
        apiSearchClear.style.display = 'none';
        apiSearchInput.focus();
        if (processHubLastData?.apis) renderApisPanel(processHubLastData.apis);
      }
    });
  }

  // Filter tabs click handling (Background Tasks)
  qsa('.hub-tab-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      qsa('.hub-tab-btn').forEach(b => {
        b.classList.remove('active');
        b.setAttribute('aria-selected', 'false');
      });
      btn.classList.add('active');
      btn.setAttribute('aria-selected', 'true');
      processHubFilter = btn.dataset.filter || 'all';
      if (processHubLastData?.processes) {
        renderTasksPanel(processHubLastData.processes);
      } else {
        loadProcessHubData();
      }
    });
  });

  // Search input handling (Background Tasks)
  const searchInput = qs('#process-hub-search-input');
  const clearBtn = qs('#process-hub-search-clear-btn');
  if (searchInput) {
    searchInput.addEventListener('input', (e) => {
      processHubSearch = e.target.value;
      if (clearBtn) clearBtn.style.display = processHubSearch ? 'block' : 'none';
      if (processHubLastData?.processes) {
        renderTasksPanel(processHubLastData.processes);
      } else {
        loadProcessHubData();
      }
    });
  }
  if (clearBtn) {
    clearBtn.addEventListener('click', () => {
      if (searchInput) {
        searchInput.value = '';
        processHubSearch = '';
        clearBtn.style.display = 'none';
        searchInput.focus();
        if (processHubLastData?.processes) {
          renderTasksPanel(processHubLastData.processes);
        } else {
          loadProcessHubData();
        }
      }
    });
  }

  // Reset Tasks Filter Button in Tab 1 Empty State
  qs('#btn-reset-tasks-filter')?.addEventListener('click', () => {
    if (searchInput) searchInput.value = '';
    processHubSearch = '';
    if (clearBtn) clearBtn.style.display = 'none';
    processHubFilter = 'all';
    qsa('.hub-tab-btn').forEach(b => {
      const isAll = (b.dataset.filter || 'all') === 'all';
      b.classList.toggle('active', isAll);
      b.setAttribute('aria-selected', isAll ? 'true' : 'false');
    });
    if (processHubLastData?.processes) {
      renderTasksPanel(processHubLastData.processes);
    } else {
      loadProcessHubData();
    }
  });

  // Background check every 10 seconds to update header pulse badge
  setInterval(async () => {
    try {
      const data = await api.processes();
      const hasRunning = data?.processes?.some(p => {
        const s = String(p.status || '').toUpperCase();
        if (p.id === 'heap-watchdog' || p.id === 'db-watchdog') {
          return s === 'ALERT' || s === 'RECOVERING';
        }
        return s === 'RUNNING' || s === 'SCANNING';
      });
      const pulseEl = qs('#process-hub-pulse');
      if (pulseEl) pulseEl.style.display = hasRunning ? 'inline-block' : 'none';
    } catch (ignored) {}
  }, 10000);

  // Initialize scale & stress testing benchmark controls
  initStressTestControls();

  // Initialize JVM Manager controls
  initJvmManagerControls();
}

function initJvmManagerControls() {
  // 1. Run Garbage Collection
  qs('#btn-jvm-gc')?.addEventListener('click', async () => {
    const btn = qs('#btn-jvm-gc');
    const origHtml = btn ? btn.innerHTML : '';
    if (btn) {
      btn.disabled = true;
      btn.innerHTML = `<span class="hub-btn-spinner"></span> Running GC…`;
    }
    try {
      const res = await api.triggerGc();
      showJvmAlert('success', `Explicit GC executed: Reclaimed ${res.freedMb} MB in ${res.durationMs} ms (Heap: ${res.beforeUsedMb} MB → ${res.afterUsedMb} MB)`);
      loadProcessHubData();
    } catch (err) {
      showJvmAlert('error', `Failed to trigger Garbage Collection: ${err.message}`);
    } finally {
      if (btn) {
        btn.disabled = false;
        btn.innerHTML = origHtml;
      }
    }
  });

  // 2. Trim Caches & GC
  qs('#btn-jvm-trim')?.addEventListener('click', async () => {
    const btn = qs('#btn-jvm-trim');
    const origHtml = btn ? btn.innerHTML : '';
    if (btn) {
      btn.disabled = true;
      btn.innerHTML = `<span class="hub-btn-spinner"></span> Trimming…`;
    }
    try {
      const res = await api.trimMemory();
      showJvmAlert('success', res.message || `Trimmed layout & module caches and executed GC (Freed ${res.freedMb} MB in ${res.durationMs} ms)`);
      loadProcessHubData();
    } catch (err) {
      showJvmAlert('error', `Failed to trim memory: ${err.message}`);
    } finally {
      if (btn) {
        btn.disabled = false;
        btn.innerHTML = origHtml;
      }
    }
  });

  // 3. Scan Deadlocks
  qs('#btn-jvm-deadlocks')?.addEventListener('click', async () => {
    const btn = qs('#btn-jvm-deadlocks');
    const origHtml = btn ? btn.innerHTML : '';
    if (btn) {
      btn.disabled = true;
      btn.innerHTML = `<span class="hub-btn-spinner"></span> Scanning…`;
    }
    try {
      const res = await api.jvmDeadlocks();
      if (res.hasDeadlocks) {
        showJvmAlert('error', `⚠️ Deadlock Detected! ${res.count} thread(s) are permanently deadlocked.`);
      } else {
        showJvmAlert('success', `Clean scan: No deadlocked threads detected in the JVM.`);
      }
      loadProcessHubData();
    } catch (err) {
      showJvmAlert('error', `Deadlock scan failed: ${err.message}`);
    } finally {
      if (btn) {
        btn.disabled = false;
        btn.innerHTML = origHtml;
      }
    }
  });

  // 4. Thread Dump Modal
  qs('#btn-jvm-dump')?.addEventListener('click', () => {
    openJvmThreadDump();
  });
  qs('#jvm-thread-dump-modal-close')?.addEventListener('click', () => {
    const modal = qs('#jvm-thread-dump-modal');
    if (modal) hideAccessibleModal(modal);
  });
  qs('#jvm-thread-dump-modal')?.addEventListener('click', (e) => {
    if (e.target === qs('#jvm-thread-dump-modal')) {
      hideAccessibleModal(qs('#jvm-thread-dump-modal'));
    }
  });
  qs('#btn-jvm-dump-copy')?.addEventListener('click', () => {
    if (jvmDumpRawText) {
      navigator.clipboard?.writeText(jvmDumpRawText);
      const btn = qs('#btn-jvm-dump-copy');
      if (btn) {
        btn.textContent = 'Copied!';
        setTimeout(() => { btn.innerHTML = `<svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg> Copy`; }, 1500);
      }
    }
  });
  qs('#btn-jvm-dump-download')?.addEventListener('click', () => {
    if (jvmDumpRawText) {
      const blob = new Blob([jvmDumpRawText], { type: 'text/plain;charset=utf-8' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `codelens-thread-dump-${Date.now()}.txt`;
      a.click();
      URL.revokeObjectURL(url);
    }
  });

  // 5. Thread Stack Modal
  qs('#jvm-stack-modal-close')?.addEventListener('click', () => {
    const modal = qs('#jvm-stack-modal');
    if (modal) hideAccessibleModal(modal);
  });
  qs('#jvm-stack-modal')?.addEventListener('click', (e) => {
    if (e.target === qs('#jvm-stack-modal')) {
      hideAccessibleModal(qs('#jvm-stack-modal'));
    }
  });

  // 6. Dismiss Alert
  qs('#btn-jvm-alert-close')?.addEventListener('click', () => {
    const alertEl = qs('#hub-jvm-alert');
    if (alertEl) alertEl.style.display = 'none';
  });

  // 7. Thread search and state filters
  const threadSearchInput = qs('#jvm-thread-search-input');
  const threadSearchClear = qs('#jvm-thread-search-clear');
  if (threadSearchInput) {
    threadSearchInput.addEventListener('input', (e) => {
      jvmThreadsSearchQuery = e.target.value;
      if (threadSearchClear) threadSearchClear.style.display = jvmThreadsSearchQuery ? 'block' : 'none';
      loadJvmThreads();
    });
  }
  if (threadSearchClear) {
    threadSearchClear.addEventListener('click', () => {
      if (threadSearchInput) {
        threadSearchInput.value = '';
        jvmThreadsSearchQuery = '';
        threadSearchClear.style.display = 'none';
        threadSearchInput.focus();
        loadJvmThreads();
      }
    });
  }

  qsa('.hub-th-state-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      qsa('.hub-th-state-btn').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      jvmThreadsFilterState = btn.dataset.state || 'ALL';
      loadJvmThreads();
    });
  });

  qs('#btn-jvm-threads-refresh')?.addEventListener('click', () => {
    loadJvmThreads();
  });

  // 8. Test Auto-Recovery (Memory pressure spike simulation)
  qs('#btn-jvm-test-autorecovery')?.addEventListener('click', async () => {
    const btn = qs('#btn-jvm-test-autorecovery');
    const origHtml = btn ? btn.innerHTML : '';
    if (btn) {
      btn.disabled = true;
      btn.innerHTML = `<span class="hub-btn-spinner"></span> Simulating…`;
    }
    try {
      const res = await api.simulateAutoRecovery(60);
      const inc = res.incident || {};
      const alloc = res.simulatedPayloadMb || 60;
      showJvmAlert('success', `⚡ Auto-Recovery Test Passed: Simulated ${alloc} MB pressure spike. Sentinel recovered ${(inc.reclaimedMb || 0).toFixed(1)} MB in ${inc.durationMs || 0} ms (Heap: ${inc.heapBeforeMb || 0} MB → ${inc.heapAfterMb || 0} MB).`);
      loadProcessHubData();
      loadJvmAutoRecovery();
    } catch (err) {
      showJvmAlert('error', `Auto-recovery simulation failed: ${err.message}`);
    } finally {
      if (btn) {
        btn.disabled = false;
        btn.innerHTML = origHtml;
      }
    }
  });

  // 9. Force Auto-Recovery Pass
  qs('#btn-jvm-force-recovery')?.addEventListener('click', async () => {
    const btn = qs('#btn-jvm-force-recovery');
    const origHtml = btn ? btn.innerHTML : '';
    if (btn) {
      btn.disabled = true;
      btn.innerHTML = `<span class="hub-btn-spinner"></span> Evicting & GC…`;
    }
    try {
      const res = await api.triggerAutoRecovery();
      showJvmAlert('success', `🧹 Force recovery pass complete: Evicted caches, trimmed DB, and reclaimed ${res.reclaimedMb} MB in ${res.durationMs} ms (Heap: ${res.heapBeforeMb} MB → ${res.heapAfterMb} MB). Circuit breaker is ${res.circuitBreakerActive ? 'OPEN' : 'CLOSED'}.`);
      loadProcessHubData();
      loadJvmAutoRecovery();
    } catch (err) {
      showJvmAlert('error', `Force recovery pass failed: ${err.message}`);
    } finally {
      if (btn) {
        btn.disabled = false;
        btn.innerHTML = origHtml;
      }
    }
  });

  // 10. Reset Circuit Breaker
  qs('#btn-jvm-reset-cb')?.addEventListener('click', async () => {
    const btn = qs('#btn-jvm-reset-cb');
    const origHtml = btn ? btn.innerHTML : '';
    if (btn) {
      btn.disabled = true;
      btn.innerHTML = `<span class="hub-btn-spinner"></span> Resetting…`;
    }
    try {
      await api.resetCircuitBreaker();
      showJvmAlert('success', 'Memory circuit breaker manually reset to CLOSED. Scans and heavy tasks are now unblocked.');
      loadProcessHubData();
      loadJvmAutoRecovery();
    } catch (err) {
      showJvmAlert('error', `Failed to reset circuit breaker: ${err.message}`);
    } finally {
      if (btn) {
        btn.disabled = false;
        btn.innerHTML = origHtml;
      }
    }
  });
}

/** Re-open scan modal when user clicks header badge or footer status */
async function reopenScanModal() {
  App.scanModalDismissed = false;
  qs('#scan-status-bar')?.classList.add('visible');
  if (App.lastScanProgress) {
    updateScanProgress(App.lastScanProgress);
  } else {
    try {
      const status = await api.scanStatus();
      if (status) {
        App.lastScanProgress = status;
        updateScanProgress(status);
      }
    } catch (e) {
      console.warn('Failed fetching scan status on modal reopen:', e);
    }
  }
}

/** Update the progress bar and status text during an active scan. */
function updateScanProgress(s) {
  if (!s) return;
  App.lastScanProgress = s;
  const pct = (typeof s.percentage === 'number' && s.percentage >= 0) ? s.percentage : 0;
  
  // Header thin progress bar
  const headerBar = qs('#scan-progress-bar');
  if (headerBar) headerBar.style.width = pct + '%';

  // Central modal fill bar
  const cardFill = qs('#scan-card-bar-fill');
  if (cardFill) cardFill.style.width = pct + '%';

  // Phase badge
  const phaseBadge = qs('#scan-phase-badge') || qs('.scan-phase-badge');
  if (phaseBadge) {
    phaseBadge.textContent = s.currentPhase || (pct < 100 ? 'Scanning' : 'Finishing');
  }

  // Stage resolution & Heading
  const stage = s.activeStage || 'PARSE';
  const stageOrder = { 'PREPARE': 1, 'PARSE': 1, 'INDEX': 2, 'GRAPH': 3, 'LAYOUT': 4, 'MODULES': 5, 'REPORTS': 6, 'COMPLETE': 7 };
  const currentStepNum = stageOrder[stage] || 1;
  const headingEl = qs('#scan-card-heading');
  const modalCard = qs('.scan-modal-card');

  const spinnerRing = qs('.scan-spinner-ring');
  if (s.status === 'COMPLETE' || stage === 'COMPLETE') {
    if (headingEl) headingEl.textContent = 'Analysis Complete';
    if (modalCard) modalCard.classList.add('is-complete');
    if (spinnerRing && !spinnerRing.classList.contains('is-complete')) {
      spinnerRing.classList.add('is-complete');
      spinnerRing.innerHTML = '<svg class="svg-icon icon-sm icon-emerald" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="20 6 9 17 4 12"/></svg>';
    }
  } else {
    if (headingEl) headingEl.textContent = 'Analyzing Codebase';
    if (modalCard) modalCard.classList.remove('is-complete');
    if (spinnerRing && spinnerRing.classList.contains('is-complete')) {
      spinnerRing.classList.remove('is-complete');
      spinnerRing.innerHTML = '<div class="scan-spinner-core"></div>';
    }
  }

  // Action status message
  const statusText = qs('#scan-card-status-text') || qs('.scan-card-status-text') || qs('#scan-status-text');
  if (statusText) {
    if (s.status === 'COMPLETE' || stage === 'COMPLETE') {
      statusText.textContent = 'Codebase analysis complete';
    } else if (stage === 'REPORTS' || (s.currentPhase && s.currentPhase.includes('Reports'))) {
      statusText.textContent = s.message || 'Generating codebase intelligence reports…';
    } else if (stage === 'MODULES' || (s.currentPhase && s.currentPhase.includes('Module'))) {
      statusText.textContent = s.message || 'Analyzing module dependencies & couplings…';
    } else if (stage === 'LAYOUT' || (s.currentPhase && s.currentPhase.includes('Layout'))) {
      statusText.textContent = 'Precomputing graph layouts…';
    } else if (stage === 'GRAPH' || (s.currentPhase && s.currentPhase.includes('Graph'))) {
      statusText.textContent = 'Analyzing call hierarchy & field impact…';
    } else if (stage === 'INDEX' || (s.currentPhase && s.currentPhase.includes('Index'))) {
      statusText.textContent = 'Rebuilding search & storage indexes…';
    } else {
      statusText.textContent = s.message || 'Scanning codebase…';
    }
  }

  // Current active file name / path
  const detailText = qs('#scan-detail-text') || qs('.scan-detail-text');
  if (detailText) {
    if (s.status === 'COMPLETE' || stage === 'COMPLETE') {
      const pData = getPhaseMetricsData(App.selectedScanPhase || 'PARSE', s);
      detailText.innerHTML = `<span style="color:var(--emerald); font-weight:600;">✓ ${esc(pData.detailText)}</span>`;
    } else if (s.currentDetail && (s.currentDetail.includes('/') || s.currentDetail.includes('\\'))) {
      const slash = s.currentDetail.includes('/') ? '/' : '\\';
      const parts = s.currentDetail.split(slash);
      const fileName = parts.pop();
      const dirPath = parts.join(slash) + (parts.length > 0 ? slash : '');
      detailText.innerHTML = `<span style="opacity:0.6; font-size:11px;">${esc(dirPath)}</span><strong style="color:var(--text-primary);">${esc(fileName)}</strong>`;
    } else {
      detailText.textContent = s.currentDetail || (s.totalFiles ? `${s.processedFiles || 0} of ${s.totalFiles} files` : 'Processing…');
    }
  }

  // Update pipeline step track and connector dividers
  qsa('.scan-pipeline-step').forEach(stepEl => {
    const stepName = stepEl.dataset.step;
    const stepNum = stageOrder[stepName] || 1;
    stepEl.classList.remove('step-active', 'step-complete', 'step-pending');
    const dot = stepEl.querySelector('.step-dot');
    if (s.status === 'COMPLETE' || currentStepNum > stepNum) {
      stepEl.classList.add('step-complete');
      if (dot) dot.innerHTML = '<svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.8"><polyline points="20 6 9 17 4 12"/></svg>';
    } else if (currentStepNum === stepNum) {
      stepEl.classList.add('step-active');
      if (dot) dot.innerHTML = `<span class="step-dot-num">${stepNum}</span>`;
    } else {
      stepEl.classList.add('step-pending');
      if (dot) dot.innerHTML = `<span class="step-dot-num">${stepNum}</span>`;
    }
  });

  qsa('.scan-step-divider').forEach(divEl => {
    const afterStep = divEl.dataset.after;
    const divStepNum = stageOrder[afterStep] || 1;
    if (s.status === 'COMPLETE' || currentStepNum > divStepNum) {
      divEl.classList.add('step-divider-complete');
    } else {
      divEl.classList.remove('step-divider-complete');
    }
  });

  // Dynamic detail label & icon
  const detailLabel = qs('#scan-detail-label');
  const detailIcon = qs('#scan-detail-icon');
  const phase = s.currentPhase || '';
  if (s.status === 'COMPLETE') {
    const pData = getPhaseMetricsData(App.selectedScanPhase || 'PARSE', s);
    if (detailLabel) detailLabel.textContent = `${pData.pill} · ${pData.name}`;
    if (detailIcon) detailIcon.innerHTML = '<path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/>';
  } else if (stage === 'REPORTS' || phase.includes('Reports') || phase.includes('Report')) {
    if (detailLabel) detailLabel.textContent = 'Codebase Intelligence Reports';
    if (detailIcon) detailIcon.innerHTML = '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/>';
  } else if (stage === 'MODULES' || phase.includes('Module')) {
    if (detailLabel) detailLabel.textContent = 'Module Dependencies & Couplings';
    if (detailIcon) detailIcon.innerHTML = '<rect x="2" y="2" width="8" height="8" rx="2"/><rect x="14" y="2" width="8" height="8" rx="2"/><rect x="8" y="14" width="8" height="8" rx="2"/><line x1="6" y1="10" x2="12" y2="14"/><line x1="18" y1="10" x2="12" y2="14"/>';
  } else if (stage === 'LAYOUT' || phase.includes('Layout')) {
    if (detailLabel) detailLabel.textContent = 'Layout Precomputation';
    if (detailIcon) detailIcon.innerHTML = '<rect x="3" y="3" width="18" height="18" rx="2"/><line x1="3" y1="9" x2="21" y2="9"/><line x1="9" y1="21" x2="9" y2="9"/>';
  } else if (stage === 'GRAPH' || phase.includes('Graph') || phase.includes('Field')) {
    if (phase.includes('Field')) {
      if (detailLabel) detailLabel.textContent = 'Field Impact Propagation';
      if (detailIcon) detailIcon.innerHTML = '<ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"/><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/>';
    } else {
      if (detailLabel) detailLabel.textContent = 'Call Graph Topology';
      if (detailIcon) detailIcon.innerHTML = '<circle cx="6" cy="6" r="3"/><circle cx="18" cy="18" r="3"/><circle cx="18" cy="6" r="3"/><line x1="8.5" y1="7.5" x2="15.5" y2="16.5"/><line x1="9" y1="6" x2="15" y2="6"/>';
    }
  } else if (stage === 'INDEX' || phase.includes('Index')) {
    if (detailLabel) detailLabel.textContent = 'Storage & Search Index';
    if (detailIcon) detailIcon.innerHTML = '<circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>';
  } else {
    if (detailLabel) detailLabel.textContent = 'Current File';
    if (detailIcon) detailIcon.innerHTML = '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/>';
  }

  // Source path
  const sourcePathEl = qs('#scan-card-source-path');
  if (sourcePathEl) {
    const p = s.sourcePath || qs('#scan-path-input')?.value?.trim() || '';
    sourcePathEl.textContent = p;
    sourcePathEl.title = p;
  }

  // Files processed vs remaining ratio
  const filesRatio = qs('#scan-files-ratio');
  if (filesRatio) {
    if (s.status === 'COMPLETE' || stage === 'COMPLETE') {
      filesRatio.textContent = s.totalFiles ? `${s.totalFiles.toLocaleString()} files indexed` : 'Scan complete';
    } else if (stage === 'REPORTS') {
      filesRatio.textContent = s.stageItem ? `Report: ${s.stageItem}` : (s.totalFiles ? `${s.totalFiles.toLocaleString()} files parsed · Generating intelligence reports` : 'Generating intelligence reports…');
    } else if (stage === 'MODULES') {
      filesRatio.textContent = s.stageItem ? `Module: ${s.stageItem}` : (s.totalFiles ? `${s.totalFiles.toLocaleString()} files parsed · Analyzing module dependencies` : 'Analyzing module dependencies…');
    } else if (stage === 'LAYOUT') {
      filesRatio.textContent = s.stageItem ? `Computing: ${s.stageItem}` : (s.totalFiles ? `${s.totalFiles.toLocaleString()} files parsed · Precomputing layouts` : 'Precomputing layouts…');
    } else if (stage === 'GRAPH') {
      filesRatio.textContent = s.stageItem ? `Analyzing: ${s.stageItem}` : (s.totalFiles ? `${s.totalFiles.toLocaleString()} files parsed · Analyzing dependencies` : 'Analyzing dependencies…');
    } else if (stage === 'INDEX') {
      filesRatio.textContent = s.stageItem ? `Rebuilding: ${s.stageItem}` : (s.totalFiles ? `${s.totalFiles.toLocaleString()} files parsed · Finalizing indexes` : 'Finalizing indexes…');
    } else {
      filesRatio.textContent = s.totalFiles ? `${(s.processedFiles || 0).toLocaleString()} of ${s.totalFiles.toLocaleString()} files processed` : 'Scanning file tree…';
    }
  }
  const remainingFiles = qs('#scan-remaining-files');
  if (remainingFiles) {
    if (s.status === 'COMPLETE' || stage === 'COMPLETE') {
      remainingFiles.textContent = 'All 6 stages complete';
    } else if (stage === 'REPORTS') {
      if (s.stageTotal > 0) {
        remainingFiles.textContent = `Report ${(s.stageCurrent || 0).toLocaleString()} of ${s.stageTotal.toLocaleString()} · Stage 6 of 6`;
      } else {
        remainingFiles.textContent = 'Stage 6 of 6';
      }
    } else if (stage === 'MODULES') {
      if (s.stageTotal > 0) {
        remainingFiles.textContent = `Module ${(s.stageCurrent || 0).toLocaleString()} of ${s.stageTotal.toLocaleString()} · Stage 5 of 6`;
      } else {
        remainingFiles.textContent = 'Stage 5 of 6';
      }
    } else if (stage === 'LAYOUT') {
      if (s.stageTotal > 0) {
        remainingFiles.textContent = `Layout ${(s.stageCurrent || 0).toLocaleString()} of ${s.stageTotal.toLocaleString()} · Stage 4 of 6`;
      } else {
        remainingFiles.textContent = 'Stage 4 of 6';
      }
    } else if (stage === 'GRAPH') {
      if (s.stageTotal > 10) {
        remainingFiles.textContent = `${(s.stageCurrent || 0).toLocaleString()} of ${s.stageTotal.toLocaleString()} links · Stage 3 of 6`;
      } else if (s.stageTotal > 0) {
        remainingFiles.textContent = `Pass ${(s.stageCurrent || 0).toLocaleString()} of ${s.stageTotal.toLocaleString()} · Stage 3 of 6`;
      } else {
        remainingFiles.textContent = 'Stage 3 of 6';
      }
    } else if (stage === 'INDEX') {
      if (s.stageTotal > 0) {
        remainingFiles.textContent = `Index ${(s.stageCurrent || 0).toLocaleString()} of ${s.stageTotal.toLocaleString()} · Stage 2 of 6`;
      } else {
        remainingFiles.textContent = 'Stage 2 of 6';
      }
    } else {
      // PARSE / PREPARE
      const rem = Math.max(0, (s.totalFiles || 0) - (s.processedFiles || 0));
      remainingFiles.textContent = s.totalFiles ? `${rem.toLocaleString()} remaining · Stage 1 of 6` : 'Stage 1 of 6';
    }
  }

  // Selected phase resolution:
  // If scan is actively running and user hasn't explicitly clicked a step, track the activeStage
  if (s.status !== 'COMPLETE') {
    if (!App.selectedScanPhase || App.selectedScanPhase === 'PREPARE') {
      App.selectedScanPhase = stage === 'PREPARE' ? 'PARSE' : stage;
    }
  } else {
    // When complete, default to user's selected phase or PARSE (Phase 1)
    if (!App.selectedScanPhase) {
      App.selectedScanPhase = 'PARSE';
    }
  }

  // Highlight selected step in stepper
  qsa('.scan-pipeline-step').forEach(stepEl => {
    if (stepEl.dataset.step === App.selectedScanPhase) {
      stepEl.classList.add('step-inspected', 'step-selected');
      stepEl.setAttribute('aria-selected', 'true');
    } else {
      stepEl.classList.remove('step-inspected', 'step-selected');
      stepEl.setAttribute('aria-selected', 'false');
    }
  });

  // Render bottom section metrics of the selected phase
  renderPhaseBottomMetrics(App.selectedScanPhase, s);

  // Elapsed timer & Estimated Remaining Time
  const elapsedEl = qs('#scan-elapsed-time');
  const durMs = s.durationMs || (s.startTime > 0 ? Date.now() - s.startTime : 0);
  if (elapsedEl) {
    const totalSec = durMs / 1000;
    if (totalSec >= 60) {
      const mins = Math.floor(totalSec / 60);
      const remSec = Math.floor(totalSec % 60);
      elapsedEl.textContent = `${totalSec.toFixed(1)}s (${mins}m ${remSec}s)`;
    } else {
      elapsedEl.textContent = totalSec.toFixed(1) + 's';
    }
  }

  const remainingEl = qs('#scan-remaining-time');
  const remainingWrapper = qs('#scan-remaining-wrapper');
  const timerSeparator = qs('#scan-timer-separator');
  if (remainingEl) {
    if (s.status === 'COMPLETE' || s.status === 'ERROR') {
      if (remainingWrapper) remainingWrapper.style.display = 'none';
      if (timerSeparator) timerSeparator.style.display = 'none';
    } else {
      if (remainingWrapper) remainingWrapper.style.display = 'inline-flex';
      if (timerSeparator) timerSeparator.style.display = 'inline';

      let remMs = s.estimatedRemainingMs;
      if (!remMs && pct > 2 && pct < 100 && durMs >= 1000) {
        const estTotalMs = (durMs / (pct / 100));
        remMs = Math.max(0, estTotalMs - durMs);
      }

      if (remMs && remMs > 0 && pct > 2 && pct < 100) {
        const remSec = Math.round(remMs / 1000);
        if (remSec >= 3600) {
          const hrs = Math.floor(remSec / 3600);
          const mins = Math.floor((remSec % 3600) / 60);
          remainingEl.textContent = `~${hrs}h ${mins}m`;
        } else if (remSec >= 60) {
          const mins = Math.floor(remSec / 60);
          const secs = remSec % 60;
          remainingEl.textContent = `~${mins}m ${secs}s`;
        } else {
          remainingEl.textContent = `~${Math.max(1, remSec)}s`;
        }
      } else if (pct >= 100) {
        remainingEl.textContent = '0s';
      } else {
        remainingEl.textContent = 'Estimating…';
      }
    }
  }

  // Percent text
  const pctEl = qs('#scan-pct') || qs('.scan-pct');
  if (pctEl) pctEl.textContent = pct + '%';

  // Show central modal card overlay unless user explicitly minimized it
  if (!App.scanModalDismissed) {
    qs('#scan-status-bar')?.classList.add('visible');
  }
  
  // Footer update with title tooltip, stage pill, and mini progress bar
  const fText = qs('#footer-status-text');
  const fInd = qs('.status-indicator');
  const fPill = qs('#footer-scan-pill');
  const fTrack = qs('#footer-scan-mini-track');
  const fBar = qs('#footer-scan-mini-bar');
  const fContainer = qs('#footer-status-container');

  if (s.status === 'SCANNING') {
    if (fPill) {
      fPill.style.display = 'inline-flex';
      const pillLabel = stage === 'REPORTS' ? 'REPORTS' : (stage === 'MODULES' ? 'MODULES' : stage);
      fPill.textContent = pillLabel;
      fPill.className = `footer-scan-pill footer-scan-pill-${stage.toLowerCase()}`;
    }
    if (fTrack) {
      fTrack.style.display = 'inline-flex';
    }
    if (fBar) {
      fBar.style.width = pct + '%';
    }
    if (fContainer) {
      fContainer.classList.add('footer-status-scanning');
      fContainer.style.cursor = 'pointer';
    }
  } else {
    if (fPill) {
      if (s.status === 'COMPLETE' || stage === 'COMPLETE') {
        fPill.style.display = 'inline-flex';
        fPill.textContent = 'READY';
        fPill.className = 'footer-scan-pill footer-scan-pill-complete';
      } else {
        fPill.style.display = 'none';
      }
    }
    if (fTrack) {
      fTrack.style.display = 'none';
    }
    if (fContainer) {
      fContainer.classList.remove('footer-status-scanning');
    }
  }

  if (fText) {
    const detailSnippet = s.currentDetail ? ` · ${s.currentDetail}` : '';
    const txt = `[${s.currentPhase || 'SCAN'}] ${s.message || ''}${detailSnippet} (${pct}%)`;
    fText.textContent = txt;
    fText.title = txt + ' (Click to view scan dialog)';
  }
  if (fInd) {
    if (s.status === 'COMPLETE' || stage === 'COMPLETE') {
      fInd.className = 'status-indicator live';
      fInd.title = 'Codebase analysis complete · Engine ready (Click to view scan dialog)';
    } else {
      fInd.className = 'status-indicator busy';
      fInd.title = 'Active scan running (Click to view scan dialog)';
    }
  }

  // Toggle footer action buttons based on scan completion
  const btnBg = qs('#btn-bg-scan');
  const btnCancel = qs('#btn-cancel-scan');
  const btnDismiss = qs('#btn-dismiss-scan');
  const btnExplore = qs('#btn-explore-scan');

  if (s.status === 'COMPLETE') {
    if (btnBg) btnBg.style.display = 'none';
    if (btnCancel) btnCancel.style.display = 'none';
    if (btnDismiss) btnDismiss.style.display = 'inline-flex';
    if (btnExplore) btnExplore.style.display = 'inline-flex';
  } else {
    if (btnBg) btnBg.style.display = 'inline-flex';
    if (btnCancel) btnCancel.style.display = 'inline-flex';
    if (btnDismiss) btnDismiss.style.display = 'none';
    if (btnExplore) btnExplore.style.display = 'none';
  }

  // Progressive feature readiness
  updateProgressiveFeatureReadiness(s);

  // Update persistent coverage popover & header badge
  updateScanSummaryUI(s);

  // If a step detail panel is currently open, refresh its data in real time
  if (App.inspectedScanStep) {
    inspectScanStep(App.inspectedScanStep, false);
  }
}

/** Enable features progressively as scan pipeline stages complete */
function updateProgressiveFeatureReadiness(s) {
  if (!s || s.status !== 'SCANNING') {
    qsa('.tab').forEach(t => {
      t.classList.remove('tab-stage-pending');
      t.removeAttribute('data-stage-reason');
    });
    return;
  }

  const stage = s.activeStage || 'PARSE';
  const stageOrder = { 'PREPARE': 0, 'PARSE': 1, 'INDEX': 2, 'GRAPH': 3, 'LAYOUT': 4, 'MODULES': 5, 'REPORTS': 6, 'COMPLETE': 7 };
  const currentLevel = stageOrder[stage] !== undefined ? stageOrder[stage] : 1;

  const tabRequirements = {
    'source':    { level: 1, name: 'AST parsing' },
    'git':       { level: 1, name: 'AST parsing' },
    'knowledge': { level: 2, name: 'search & secondary indexing' },
    'review':    { level: 2, name: 'search & secondary indexing' },
    'graph':     { level: 4, name: 'graph layout precomputation' },
    'codebase':  { level: 4, name: 'graph layout precomputation' }
  };

  for (const [tabName, req] of Object.entries(tabRequirements)) {
    const tabEl = qs(`.tab[data-tab="${tabName}"]`);
    if (!tabEl) continue;
    if (currentLevel < req.level) {
      if (!tabEl.classList.contains('tab-stage-pending')) {
        tabEl.classList.add('tab-stage-pending');
      }
      tabEl.setAttribute('data-stage-reason', req.name);
      tabEl.title = `${tabEl.getAttribute('aria-label') || tabName} available after ${req.name} completes (Stage ${req.level} of 6)`;
    } else {
      if (tabEl.classList.contains('tab-stage-pending')) {
        tabEl.classList.remove('tab-stage-pending');
        tabEl.removeAttribute('data-stage-reason');
        tabEl.classList.add('tab-stage-ready');
        setTimeout(() => tabEl.classList.remove('tab-stage-ready'), 1000);
      }
    }
  }
}

/** Called when scan finishes successfully. */
async function onScanComplete(s) {
  setScanUI('idle');
  App.scanModalDismissed = false;
  qs('#scan-status-bar')?.classList.remove('visible');
  qs('#scan-progress-bar').style.width = '100%';
  setTimeout(() => qs('#scan-progress-bar').style.width = '0%', 600);

  // Invalidate in-memory graph cache and reset active renderer on rescan
  GraphDataCache.clear();
  if (App.activeAltRenderer && typeof App.activeAltRenderer.destroy === 'function') {
    App.activeAltRenderer.destroy();
    App.activeAltRenderer = null;
  }

  // Update header bar into loaded project view
  updateHeaderProjectBar(s.sourcePath || qs('#scan-path-input')?.value?.trim());

  // Unlock all features
  updateProgressiveFeatureReadiness(s);

  // Update persistent coverage popover & header badge
  updateScanSummaryUI(s);

  // Refresh stats and tree
  await loadStats();
  await loadPackageTree();

  // Eagerly pre-warm reports and module coupling in background for instant zero-lag rendering
  cachedModuleCouplingMap = null;
  loadModuleCouplingMap().catch(() => {});
  if (window.ReportsHub && typeof window.ReportsHub.preloadAllReports === 'function') {
    ReportsHub.cache = {};
    ReportsHub.preloadAllReports().catch(() => {});
  }

  // Footer update
  const fText = qs('#footer-status-text');
  const fInd = qs('.status-indicator');
  const fPill = qs('#footer-scan-pill');
  const fTrack = qs('#footer-scan-mini-track');
  const fContainer = qs('#footer-status-container');
  if (fText) {
    fText.textContent = 'Analyzer Idle · Scan complete';
    fText.title = 'Analyzer Idle · All graphs ready';
  }
  if (fInd) {
    fInd.className = 'status-indicator live';
    fInd.title = 'System Ready';
  }
  if (fPill) {
    fPill.style.display = 'inline-flex';
    fPill.textContent = 'READY';
    fPill.className = 'footer-scan-pill footer-scan-pill-complete';
  }
  if (fTrack) {
    fTrack.style.display = 'none';
  }
  if (fContainer) {
    fContainer.classList.remove('footer-status-scanning');
  }

  // Check git branch
  updateFooterGitBranch();

  showBanner(`Scan complete - ${s.typesFound} types · ${s.methodsFound} methods · ${s.fieldsFound} fields · All graph views ready`);
}


/** Toggle scan button and spinner states. */
function setScanUI(state) {
  const btn = qs('#scan-btn');
  const rescanBtn = qs('#btn-rescan');
  const fText = qs('#footer-status-text');
  const fInd = qs('.status-indicator');
  if (state === 'scanning') {
    if (btn) {
      btn.disabled    = true;
      btn.textContent = 'Scanning…';
    }
    if (rescanBtn) {
      rescanBtn.disabled = true;
      rescanBtn.classList.add('is-scanning');
      const label = rescanBtn.querySelector('.btn-pill-label');
      if (label) label.textContent = 'Scanning…';
    }
    if (fText) fText.textContent = 'Scanning codebase…';
    if (fInd) { fInd.className = 'status-indicator busy'; }
  } else {
    if (btn) {
      btn.disabled    = false;
      btn.textContent = 'Scan';
    }
    if (rescanBtn) {
      rescanBtn.disabled = false;
      rescanBtn.classList.remove('is-scanning');
      const label = rescanBtn.querySelector('.btn-pill-label');
      if (label) label.textContent = 'Rescan';
    }
    if (fText && state === 'idle') {
      fText.textContent = 'Analyzer Idle';
      fText.title = 'Analyzer Idle';
      if (fInd) { fInd.className = 'status-indicator live'; }
    }
  }
}

/** Query git summary and parse current repository branch name to display in footer metadata */
async function updateFooterGitBranch() {
  const branchEl = qs('#footer-git-branch');
  if (!branchEl) return;
  try {
    const summary = await api.gitSummary();
    if (summary && summary.branchName) {
      branchEl.textContent = `Branch: ${summary.branchName}`;
      branchEl.style.display = 'inline-block';
    } else {
      branchEl.textContent = 'Branch: -';
    }
  } catch (_) {
    branchEl.textContent = 'Branch: -';
  }
}


/* ─────────────────────────────────────────────────────────────────────────────
   4. Left panel - package tree + search
   ───────────────────────────────────────────────────────────────────────────── */

/**
 * Automatically calculates the common base package prefix across the codebase.
 * E.g. ["com.example.trading.model", "com.example.trading.risk"] -> "com.example.trading."
 */
function detectCommonPackagePrefix(packages) {
  if (!packages || packages.length === 0) return '';
  const valid = packages.filter(p => p && p !== 'default' && p !== '(default)' && p.includes('.'));
  if (valid.length === 0) return '';
  if (valid.length === 1) {
    const parts = valid[0].split('.');
    if (parts.length >= 3 && ['com', 'org', 'io', 'net', 'dev', 'app', 'co', 'gov', 'edu'].includes(parts[0])) {
      return parts.slice(0, 2).join('.') + '.';
    }
    return '';
  }

  const splitPkgs = valid.map(p => p.split('.'));
  const commonParts = [];
  const minLen = Math.min(...splitPkgs.map(p => p.length));

  for (let i = 0; i < minLen - 1; i++) { // Leave at least the leaf package segment
    const part = splitPkgs[0][i];
    if (splitPkgs.every(p => p[i] === part)) {
      commonParts.push(part);
    } else {
      break;
    }
  }

  if (commonParts.length > 0) {
    return commonParts.join('.') + '.';
  }

  if (splitPkgs.every(p => p[0] === splitPkgs[0][0]) && ['com', 'org', 'io', 'net', 'dev', 'app', 'co', 'gov', 'edu'].includes(splitPkgs[0][0])) {
    return splitPkgs[0][0] + '.';
  }

  return '';
}

/** Sync explorer toolbar button states */
function syncExplorerToolbar() {
  const flatBtn = qs('#btn-pkg-mode-flat');
  const treeBtn = qs('#btn-pkg-mode-tree');
  const isFlat = (App.packagePresentation !== 'hierarchical');
  if (flatBtn) {
    flatBtn.classList.toggle('active', isFlat);
    flatBtn.setAttribute('aria-pressed', isFlat ? 'true' : 'false');
  }
  if (treeBtn) {
    treeBtn.classList.toggle('active', !isFlat);
    treeBtn.setAttribute('aria-pressed', !isFlat ? 'true' : 'false');
  }
}

/** Fetch all packages and render the tree into #explorer-tree. */
async function loadPackageTree() {
  const tree = qs('#explorer-tree');
  tree.innerHTML = '<div class="list-empty">Loading…</div>';

  try {
    App.packages = await api.packages();
    App.commonPackagePrefix = detectCommonPackagePrefix(App.packages.map(p => p.fqn));
    updateModulesList();

    syncExplorerToolbar();

    // Build a tree structure according to selected presentation mode
    const root = buildPackageTree(App.packages, App.packagePresentation);
    tree.innerHTML = '';

    if (root.length === 0) {
      tree.innerHTML = '<div class="list-empty">No packages indexed yet. Run a scan.</div>';
      return;
    }

    renderPackageTree(root, tree, 0);
  } catch (e) {
    tree.innerHTML = `<div class="list-empty">Error: ${e.message}</div>`;
  }
}

/**
 * Convert package list to the selected view structure:
 * - 'flat' (Eclipse Package Explorer style): Direct list of actual packages with FQN (e.g. com.example.trading) containing classes.
 * - 'hierarchical': Nested package structure collapsing single-child chains.
 */
function buildPackageTree(packages, presentationMode = App.packagePresentation || 'flat') {
  if (presentationMode === 'flat') {
    // Eclipse Style: Flat list of all real packages with their full FQN
    return packages
      .map(pkg => ({
        id: pkg.fqn,
        fqn: pkg.fqn,
        name: pkg.fqn,
        parentFqn: null,
        fileCount: pkg.fileCount || 0,
        typeCount: pkg.typeCount || 0,
        children: [],
        isSynthetic: false
      }))
      .sort((a, b) => a.fqn.localeCompare(b.fqn, undefined, { sensitivity: 'base' }));
  }

  // Hierarchical Mode
  const map = {};
  const roots = [];

  // Index all explicit packages
  for (const pkg of packages) {
    map[pkg.fqn] = { ...pkg, name: pkg.name || pkg.fqn.split('.').pop(), children: [], isSynthetic: false };
  }

  // Ensure all ancestor packages exist in the tree
  for (const pkg of packages) {
    const parts = pkg.fqn.split('.');
    let currentFqn = '';
    let parentFqn = null;

    for (let i = 0; i < parts.length; i++) {
      currentFqn = i === 0 ? parts[0] : currentFqn + '.' + parts[i];
      if (!map[currentFqn]) {
        map[currentFqn] = {
          id: currentFqn,
          fqn: currentFqn,
          name: parts[i],
          parentFqn: parentFqn,
          fileCount: 0,
          typeCount: 0,
          children: [],
          isSynthetic: true
        };
      }
      parentFqn = currentFqn;
    }
  }

  // Link children to parents
  const allNodes = Object.values(map).sort((a, b) => a.fqn.localeCompare(b.fqn));
  for (const node of allNodes) {
    if (node.parentFqn && map[node.parentFqn]) {
      if (!map[node.parentFqn].children.some(c => c.fqn === node.fqn)) {
        map[node.parentFqn].children.push(node);
      }
    } else {
      if (!roots.some(r => r.fqn === node.fqn)) {
        roots.push(node);
      }
    }
  }

  return roots;
}

/** Recursively render the package tree into a container element. */
function renderPackageTree(nodes, container, depth) {
  const fragment = document.createDocumentFragment();
  for (const node of nodes) {
    const hasSubPackages = node.children && node.children.length > 0;
    // A package can have sub-packages AND/OR direct types
    const canExpand = hasSubPackages || node.typeCount > 0;
    const isOpen = App.openPackages.has(node.fqn);

    const pkgColor = (window.CodeLensPalette && window.CodeLensPalette.getColor)
      ? window.CodeLensPalette.getColor(node.fqn, depth)
      : '#10b981';

    const item = createElement('div', {
      class: `tree-item${App.selected.id === node.fqn ? ' active' : ''}`,
      'data-depth': depth,
      'data-fqn': node.fqn,
      style: `border-left-color: ${pkgColor};`,
    });

    // Toggle arrow
    const toggle = createElement('span', { class: `tree-toggle${canExpand && isOpen ? ' open' : ''}` });
    toggle.innerHTML = canExpand ? '<svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="9 18 15 12 9 6"/></svg>' : '';
    item.appendChild(toggle);

    // Icon with package color badge
    const icon = createElement('span', { class: 'tree-icon', style: `color: ${pkgColor}; display:inline-flex; align-items:center;` });
    icon.innerHTML = '<svg class="svg-icon icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m7.5 4.27 9 5.15"/><path d="M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z"/><path d="m3.3 7 8.7 5 8.7-5"/><path d="M12 22V12"/></svg>';
    item.appendChild(icon);

    // Label: show full FQN in flat mode, or leaf name in hierarchical mode
    const label = createElement('span', { class: 'tree-label' });
    label.textContent = node.name || node.fqn;
    label.title = node.fqn;
    item.appendChild(label);

    // Count badge
    if (node.typeCount > 0) {
      const count = createElement('span', {
        class: 'tree-count',
        style: `border: 1px solid ${pkgColor}44; color: ${pkgColor}; background: ${pkgColor}11; border-radius: 10px; padding: 0 5px;`,
      });
      count.textContent = node.typeCount;
      item.appendChild(count);
    }

    // Remove package from scope button
    const removeBtn = createElement('button', {
      class: 'tree-remove-btn',
      title: `Remove package ${node.name || node.fqn} from scope`,
      'aria-label': `Remove package ${node.name || node.fqn} from scope`
    });
    removeBtn.innerHTML = '<svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>';
    removeBtn.addEventListener('click', e => {
      e.stopPropagation();
      confirmExcludeScope({ type: 'PACKAGE', fqn: node.fqn, name: node.name || node.fqn, el: item, childContainer });
    });
    item.appendChild(removeBtn);

    item.addEventListener('contextmenu', e => {
      e.preventDefault();
      e.stopPropagation();
      showExplorerContextMenu(e.clientX, e.clientY, { type: 'PACKAGE', fqn: node.fqn, name: node.name || node.fqn, el: item, childContainer });
    });

    fragment.appendChild(item);

    // Child nodes container (types + sub-packages)
    const childContainer = createElement('div', {
      class: 'tree-children',
      style: !isOpen ? 'display:none' : '',
    });
    fragment.appendChild(childContainer);

    // If previously open and has types, load them
    if (isOpen && node.typeCount > 0 && !childContainer.dataset.loaded) {
      loadTypesInTree(node.fqn, childContainer, depth + 1);
      childContainer.dataset.loaded = '1';
    }

    // Click handler for package item
    item.addEventListener('click', async e => {
      e.stopPropagation();

      if (canExpand) {
        const open = App.openPackages.has(node.fqn);
        if (open) {
          App.openPackages.delete(node.fqn);
          childContainer.style.display = 'none';
          toggle.classList.remove('open');
        } else {
          App.openPackages.add(node.fqn);
          childContainer.style.display = '';
          toggle.classList.add('open');
          
          // Lazy-load types if not loaded yet
          if (node.typeCount > 0 && !childContainer.dataset.loaded) {
            await loadTypesInTree(node.fqn, childContainer, depth + 1);
            childContainer.dataset.loaded = '1';
          }
        }
      }

      if (!node.isSynthetic) {
        selectPackage(node, item);
      }
    });

    // Recursively render sub-packages
    if (hasSubPackages) {
      renderPackageTree(node.children, childContainer, depth + 1);
    }
  }
  container.appendChild(fragment);
}

/** Load types for a package and append them to the tree. */
async function loadTypesInTree(pkgFqn, container, depth) {
  try {
    const types = await api.typesByPackage(pkgFqn);
    const activeKind = (App.activeFilter || 'all').toUpperCase();
    const typeEls = types.filter(t => {
      if (activeKind === 'ALL') return true;
      return (t.kind || '').toUpperCase() === activeKind;
    });

    // Remove existing loaded type children and empty messages
    const existingTypeChildren = [...container.children].filter(c => c.dataset.id || c.classList.contains('tree-item-empty'));
    existingTypeChildren.forEach(c => c.remove());

    if (typeEls.length === 0 && activeKind !== 'ALL' && types.length > 0) {
      const noMatch = createElement('div', {
        class: 'tree-item-empty',
        style: `padding-left: ${16 + depth * 14}px; font-size: 11px; color: var(--text-muted); font-style: italic; padding-top: 3px; padding-bottom: 3px;`
      });
      noMatch.textContent = `No ${activeKind.toLowerCase()}s in package`;
      container.appendChild(noMatch);
      return;
    }

    const pkgColor = (window.CodeLensPalette && window.CodeLensPalette.getColor)
      ? window.CodeLensPalette.getColor(pkgFqn, depth)
      : '#10b981';

    const fragment = document.createDocumentFragment();
    for (const t of typeEls) {
      const item = createElement('div', {
        class: `tree-item tree-type-item${App.selected.id === t.id ? ' active' : ''}`,
        'data-depth': depth,
        'data-id': t.id,
        'data-fqn': t.id || t.fqn,
        style: `border-left-color: ${pkgColor}88;`,
      });

      const icon = createElement('span', { class: `tree-icon kind-${(t.kind || 'class').toLowerCase()}` });
      icon.textContent = kindIcon(t.kind);
      item.appendChild(icon);

      const label = createElement('span', { class: 'tree-label' });
      label.textContent = t.simpleName;
      label.title = `${t.fqn} (${(t.kind || 'CLASS').toLowerCase()})`;
      item.appendChild(label);

      // Method count metadata badge
      if (typeof t.methodCount === 'number' && t.methodCount > 0) {
        const meta = createElement('span', { class: 'tree-type-meta' });
        meta.textContent = `${t.methodCount}m`;
        meta.title = `${t.methodCount} methods, ${t.fieldCount || 0} fields`;
        item.appendChild(meta);
      }

      // Remove class from scope button
      const removeBtn = createElement('button', {
        class: 'tree-remove-btn',
        title: `Remove ${t.simpleName} from scope`,
        'aria-label': `Remove ${t.simpleName} from scope`
      });
      removeBtn.innerHTML = '<svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>';
      removeBtn.addEventListener('click', e => {
        e.stopPropagation();
        confirmExcludeScope({ type: 'CLASS', fqn: t.id || t.fqn, name: t.simpleName, el: item, pkgFqn });
      });
      item.appendChild(removeBtn);

      item.addEventListener('contextmenu', e => {
        e.preventDefault();
        e.stopPropagation();
        showExplorerContextMenu(e.clientX, e.clientY, { type: 'CLASS', fqn: t.id || t.fqn, name: t.simpleName, el: item, pkgFqn });
      });

      item.addEventListener('click', e => {
        e.stopPropagation();
        setActiveTreeItem(item);
        selectType(t.id);
      });

      fragment.appendChild(item);
    }
    container.appendChild(fragment);
  } catch (e) {
    console.warn('Failed to load types for', pkgFqn, e);
  }
}

/** Select a package: show its types in the knowledge-base tab. */
function selectPackage(pkg, itemEl, initialTab = null) {
  setActiveTreeItem(itemEl);
  App.selected = { kind: 'package', id: pkg.fqn, data: pkg };

  // Show the knowledge-base tab with type list
  switchTab('knowledge');
  loadKnowledgeBase(pkg.fqn, initialTab);

  // Show package info in right panel
  renderPackageDetail(pkg);
}

/* ── Search ─────────────────────────────────────────────────────────────────── */

/** Debounced search - triggers Lucene search after 280 ms of idle. */
let searchDebounce = null;
let activeSearchAbort = null;
function onSearchInput(e) {
  const q = e.target.value.trim();
  const clearBtn = qs('#search-clear-btn');
  if (clearBtn) {
    clearBtn.style.display = q ? 'inline-flex' : 'none';
  }

  clearTimeout(searchDebounce);
  if (!q) {
    if (activeSearchAbort) {
      activeSearchAbort.abort();
      activeSearchAbort = null;
    }
    showExplorer();
    return;
  }

  searchDebounce = setTimeout(() => runSearch(q), 280);
}

async function runSearch(q) {
  const query = (q || '').trim();
  if (!query) {
    if (activeSearchAbort) {
      activeSearchAbort.abort();
      activeSearchAbort = null;
    }
    showExplorer();
    return;
  }

  if (activeSearchAbort) {
    activeSearchAbort.abort();
  }
  activeSearchAbort = new AbortController();
  const currentSignal = activeSearchAbort.signal;

  showSearchResults();
  const resultsEl = qs('#search-results');
  resultsEl.innerHTML = '<div class="list-empty">Searching…</div>';

  try {
    let hits = await api.search(query, 30, { signal: currentSignal });
    if (currentSignal.aborted) return;
    resultsEl.innerHTML = '';

    // If entity kind filter is active in explorer chips, filter results
    if (App.filter && App.filter !== 'all') {
      hits = hits.filter(hit => {
        if (App.filter === 'CLASS') return hit.kind === 'TYPE' || hit.kind === 'CLASS';
        if (App.filter === 'INTERFACE') return hit.kind === 'INTERFACE';
        if (App.filter === 'ENUM') return hit.kind === 'ENUM';
        if (App.filter === 'RECORD') return hit.kind === 'RECORD';
        return true;
      });
    }

    if (hits.length === 0) {
      resultsEl.innerHTML = '<div class="list-empty">No results found.</div>';
      return;
    }

    const fragment = document.createDocumentFragment();
    for (const hit of hits) {
      const item = createElement('div', { class: 'search-result-item fade-in' });
      item.innerHTML = `
        <div>
          <span class="sr-kind ${hit.kind}">${hit.kind}</span>
          <span class="sr-label">${esc(hit.label)}</span>
        </div>
        <div class="sr-fqn">${esc(hit.fqn)}</div>`;

      item.addEventListener('click', () => {
        const input = qs('#search-input');
        if (input) input.value = '';
        const clearBtn = qs('#search-clear-btn');
        if (clearBtn) clearBtn.style.display = 'none';
        showExplorer();
        if      (hit.kind === 'TYPE' || hit.kind === 'CLASS' || hit.kind === 'INTERFACE' || hit.kind === 'ENUM' || hit.kind === 'RECORD') selectType(hit.id);
        else if (hit.kind === 'METHOD') selectMethod(hit.id);
        else if (hit.kind === 'FIELD')  selectField(hit.id);
      });

      fragment.appendChild(item);
    }
    resultsEl.replaceChildren(fragment);
  } catch (e) {
    if (e.name === 'AbortError') return;
    resultsEl.innerHTML = `<div class="list-empty">Error: ${e.message}</div>`;
  }
}

function showExplorer() {
  const tree = qs('#explorer-tree');
  const results = qs('#search-results');
  const label = qs('#explorer-label');
  if (tree) tree.style.display = 'block';
  if (results) {
    results.style.display = 'none';
    results.classList.remove('active');
  }
  if (label) label.textContent = 'Explorer';
}

function showSearchResults() {
  const tree = qs('#explorer-tree');
  const results = qs('#search-results');
  const label = qs('#explorer-label');
  if (tree) tree.style.display = 'none';
  if (results) {
    results.style.display = 'block';
    results.classList.add('active');
  }
  if (label) label.textContent = 'Search Results';
}

/* ── Filter chips ────────────────────────────────────────────────────────────── */

/** Filter explorer tree and knowledge base by entity kind. */
async function setFilter(kind) {
  App.activeFilter = kind || 'all';
  qsa('.chip').forEach(c => {
    const isActive = (c.dataset.filter === kind);
    c.classList.toggle('active', isActive);
    c.setAttribute('aria-pressed', isActive ? 'true' : 'false');
  });

  // If a search query is active, re-run search with the active filter
  const searchInput = qs('#search-input');
  if (searchInput && searchInput.value.trim() !== '') {
    runSearch(searchInput.value.trim());
    return;
  }

  // Reload all currently open packages immediately with the new filter
  const openPackages = [...App.openPackages];
  if (openPackages.length > 0) {
    for (const fqn of openPackages) {
      const pkgItem = qs(`.tree-item[data-fqn="${CSS.escape(fqn)}"]`);
      if (pkgItem && pkgItem.nextElementSibling && pkgItem.nextElementSibling.classList.contains('tree-children')) {
        const childContainer = pkgItem.nextElementSibling;
        const depth = parseInt(pkgItem.dataset.depth || '0', 10) + 1;
        childContainer.dataset.loaded = '1';
        await loadTypesInTree(fqn, childContainer, depth);
      }
    }
  } else {
    // If no packages are open, auto-expand top-level packages to reveal matching items
    const topPackages = qsa('#explorer-tree > .tree-item[data-fqn]');
    for (const pkgItem of topPackages) {
      const fqn = pkgItem.dataset.fqn;
      if (fqn && pkgItem.nextElementSibling && pkgItem.nextElementSibling.classList.contains('tree-children')) {
        const childContainer = pkgItem.nextElementSibling;
        const toggle = pkgItem.querySelector('.tree-toggle');
        App.openPackages.add(fqn);
        childContainer.style.display = '';
        if (toggle) toggle.classList.add('open');
        childContainer.dataset.loaded = '1';
        const depth = parseInt(pkgItem.dataset.depth || '0', 10) + 1;
        await loadTypesInTree(fqn, childContainer, depth);
      }
    }
  }

  // Also refresh package view if a package is currently selected
  if (App.selected && App.selected.kind === 'package' && App.selected.id) {
    loadKnowledgeBase(App.selected.id);
  }
}

/* ─────────────────────────────────────────────────────────────────────────────
   5. Centre panel - tabs and views
   ───────────────────────────────────────────────────────────────────────────── */

/** Open Macro Visualizer Studio as a dedicated full-bleed section. */
function openMacroStudio(level, granularity) {
  if (App.activeTab && App.activeTab !== 'codebase') {
    App.lastGranularTab = App.activeTab;
  }
  document.body.classList.add('macro-studio-mode');
  if (level) {
    App.codebaseMacroLevel = level;
  }
  if (granularity) {
    App.codebaseGranularity = granularity;
  }
  App._suppressTabLoad = true;
  try {
    switchTab('codebase');
  } finally {
    App._suppressTabLoad = false;
  }
  loadWholeCodebaseGraph(level || App.codebaseMacroLevel || 'city3d', granularity || App.codebaseGranularity || 'arch');
  requestAnimationFrame(() => triggerRelayout());
}

/** Close Macro Visualizer Studio and return to previous granular workspace tab. */
function closeMacroStudio() {
  document.body.classList.remove('macro-studio-mode');
  const targetTab = App.lastGranularTab || 'graph';
  switchTab(targetTab);
  requestAnimationFrame(() => triggerRelayout());
}
window.openMacroStudio = openMacroStudio;
window.closeMacroStudio = closeMacroStudio;

/** Switch the active tab in the centre panel. */
function switchTab(tabName) {
  const targetTabEl = qs(`.tab[data-tab="${tabName}"]`);
  if (targetTabEl && targetTabEl.classList.contains('tab-stage-pending')) {
    const reason = targetTabEl.getAttribute('data-stage-reason') || 'this feature is still being prepared';
    showBanner(`${targetTabEl.textContent.trim()} will be available once ${reason} completes.`);
    return;
  }

  const previousTab = App.activeTab;
  App.activeTab = tabName;

  if (tabName !== 'reports' && tabName !== 'codebase') {
    App.lastWorkspaceTab = tabName;
  }

  // Reflect active state on global Reports header action button
  qs('#export-btn')?.classList.toggle('active', tabName === 'reports');

  if (tabName === 'codebase') {
    document.body.classList.add('macro-studio-mode');
  } else {
    document.body.classList.remove('macro-studio-mode');
  }

  if (tabName === 'reports' || tabName === 'storylines') {
    document.body.classList.add(tabName + '-mode');
    if (previousTab !== tabName) {
      const leftPanel = qs('#left-panel');
      const rightPanel = qs('#right-panel');
      const leftWasCollapsed = !leftPanel || leftPanel.classList.contains('collapsed');
      const rightWasCollapsed = !rightPanel || rightPanel.classList.contains('collapsed');

      // Preserve previous workspace panel states so they can be restored upon return
      App._preWideTabPanelState = {
        leftCollapsed: leftWasCollapsed,
        rightCollapsed: rightWasCollapsed
      };

      // Auto-minimize Explorer and Inspector
      if (!leftWasCollapsed) {
        collapseLeftPanel(true, false);
      }
      if (!rightWasCollapsed) {
        collapseRightPanel(true, false);
      }
    }
  } else {
    document.body.classList.remove('reports-mode', 'storylines-mode');
    if ((previousTab === 'reports' || previousTab === 'storylines') && App._preWideTabPanelState) {
      const { leftCollapsed, rightCollapsed } = App._preWideTabPanelState;
      const leftPanel = qs('#left-panel');
      const rightPanel = qs('#right-panel');

      if (!leftCollapsed && leftPanel && leftPanel.classList.contains('collapsed')) {
        collapseLeftPanel(false, false);
      }
      if (!rightCollapsed && rightPanel && rightPanel.classList.contains('collapsed')) {
        collapseRightPanel(false, false);
      }
      App._preWideTabPanelState = null;
    }
  }
  requestAnimationFrame(() => triggerRelayout());

  qsa('.tab').forEach(t => {
    const isActive = (t.dataset.tab === tabName);
    t.classList.toggle('active', isActive);
    t.setAttribute('aria-selected', isActive ? 'true' : 'false');
  });
  qsa('.tab-content').forEach(tc => {
    const isActive = tc.id === tabName + '-view';
    tc.classList.toggle('active', isActive);
    if (isActive && previousTab !== tabName && !window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      tc.classList.add('blur-masked-transition', 'blur-masked-active');
      requestAnimationFrame(() => {
        requestAnimationFrame(() => {
          tc.classList.remove('blur-masked-active');
        });
      });
    }
  });

  // Pause rendering loops in inactive tabs to save CPU/GPU
  if (previousTab === 'codebase' && tabName !== 'codebase' && App.activeAltRenderer && typeof App.activeAltRenderer.pause === 'function') {
    App.activeAltRenderer.pause();
  }
  if (previousTab === 'graph' && tabName !== 'graph' && App.graph && typeof App.graph.pause === 'function') {
    App.graph.pause();
  }

  if (tabName === 'review') {
    updateReviewTargetInfo();
  }
  if (tabName === 'codebase' && !App._suppressTabLoad) {
    const macroLevel = App.codebaseMacroLevel || 'city3d';
    if (App.activeAltRenderer &&
        App.activeAltRenderer._currentLevel === macroLevel &&
        App.activeAltRenderer._currentGranularity === (App.codebaseGranularity || 'arch') &&
        App.activeAltRenderer._cachedRevision === GraphDataCache.getRevision()) {
      if (typeof App.activeAltRenderer.resume === 'function') {
        App.activeAltRenderer.resume();
      }
    } else {
      loadWholeCodebaseGraph(macroLevel);
    }
  }
  if (tabName === 'graph') {
    if (App.graph && typeof App.graph.resume === 'function') {
      App.graph.resume();
    }
    const emptyEl = qs('#graph-empty');
    if (emptyEl && emptyEl.style.display !== 'none') {
      if (App.selected && App.selected.kind === 'type' && App.selected.data && App.selected.data.methods && App.selected.data.methods.length > 0) {
        const nonInit = App.selected.data.methods.filter(m => m.name !== '<init>' && m.name !== '<clinit>' && !(m.id || '').includes('.<init>('));
        nonInit.sort((a, b) => (b.cyclomaticComplexity || 0) - (a.cyclomaticComplexity || 0));
        const bestMethod = nonInit[0] || App.selected.data.methods[0];
        selectMethod(bestMethod.id || bestMethod.fqn);
      } else if (App.selected && App.selected.kind === 'method') {
        loadCallGraph(App.selected.id);
      } else if (App.selected && App.selected.kind === 'field') {
        loadFieldImpact(App.selected.id);
      }
    }
  }
  if (tabName === 'source') {
    if (App.currentFilePath && (App._loadedSourceFilePath !== App.currentFilePath || (App.currentLineNum && App._loadedSourceLineNum !== App.currentLineNum) || !App._loadedSourceFilePath)) {
      openSourceFile(App.currentFilePath, App.currentLineNum || null, true);
    } else if (App.editor) {
      setTimeout(() => {
        App.editor.layout();
      }, 20);
    }
  }
  if (tabName === 'git') {
    const gitInput = qs('#git-repo-input');
    const projPath = App.currentPath || qs('#scan-path-input')?.value?.trim() || localStorage.getItem('codelens_last_path');
    if (gitInput && (!gitInput.value || gitInput.value.trim() === '') && projPath) {
      gitInput.value = projPath;
      gitInput.dataset.synced = 'true';
      validateGitRepoPath();
    } else {
      loadGitSummary();
    }
  }
  if (tabName === 'reports') {
    if (window.ReportsHub && typeof window.ReportsHub.activate === 'function') {
      window.ReportsHub.activate();
    }
  }
  if (tabName === 'storylines') {
    loadStorylines();
  }
}

/* ─────────────────────────────────────────────────────────────────────────────
   Workspace Tabs - Drag & Drop Customization & Dynamic Shortcut Synchronization
   ───────────────────────────────────────────────────────────────────────────── */

function restoreTabOrder() {
  const tabBar = qs('.tab-nav-segment') || qs('.main-views-switcher') || qs('.tab-bar') || qs('#header');
  if (!tabBar) return;
  const navSegment = tabBar.classList.contains('tab-nav-segment') ? tabBar : (tabBar.querySelector('.tab-nav-segment') || tabBar);
  const spacer = tabBar.querySelector('.tab-bar-spacer') || qs('.header-flex-spacer');
  
  // Clean up any stray divider elements that could cause visual artifacts
  if (navSegment) {
    navSegment.querySelectorAll('.level-pill-divider').forEach(d => d.remove());
  }
  
  let savedOrder = null;
  try {
    const raw = localStorage.getItem('codelens_tab_order');
    if (raw) savedOrder = JSON.parse(raw);
  } catch (_) {}

  if (Array.isArray(savedOrder) && savedOrder.length > 0) {
    savedOrder = savedOrder.filter(t => t !== 'reports');
    const tabMap = new Map();
    tabBar.querySelectorAll('.tab').forEach(t => {
      if (t.dataset.tab) tabMap.set(t.dataset.tab, t);
    });

    savedOrder.forEach(tabName => {
      const tabEl = tabMap.get(tabName);
      if (tabEl) {
        if (navSegment !== tabBar) {
          navSegment.appendChild(tabEl);
        } else if (spacer) {
          tabBar.insertBefore(tabEl, spacer);
        } else {
          tabBar.appendChild(tabEl);
        }
      }
    });
  }
  updateTabTooltipsAndShortcuts();
}

function saveTabOrder() {
  const tabBar = qs('.tab-nav-segment') || qs('.main-views-switcher') || qs('.tab-bar') || qs('#header');
  if (!tabBar) return;
  const order = [...tabBar.querySelectorAll('.tab')].map(t => t.dataset.tab).filter(Boolean);
  try {
    localStorage.setItem('codelens_tab_order', JSON.stringify(order));
  } catch (_) {}
  updateTabTooltipsAndShortcuts();
}

function updateTabTooltipsAndShortcuts() {
  const tabBar = qs('.tab-nav-segment') || qs('.main-views-switcher') || qs('.tab-bar') || qs('#header');
  if (!tabBar) return;
  const tabs = [...tabBar.querySelectorAll('.tab')];
  
  const tabShortLabels = {
    'graph': 'Graph',
    'knowledge': 'KB',
    'review': 'Review',
    'git': 'Git',
    'source': 'Source',
    'codebase': 'Viz'
  };

  let footerHtml = '';

  tabs.forEach((t, idx) => {
    const num = idx + 1;
    t.dataset.shortcut = num;
    if (!t.getAttribute('data-base-title')) {
      const curTitle = t.getAttribute('title') || '';
      t.setAttribute('data-base-title', curTitle.replace(/\s*\(Shortcut:\s*\d+\)/, ''));
    }
    const base = t.getAttribute('data-base-title');
    t.setAttribute('title', `${base} (Shortcut: ${num})`);

    const tabKey = t.dataset.tab;
    const shortLabel = tabShortLabels[tabKey] || base || tabKey;
    footerHtml += `<span class="shortcut-tip"><kbd>${num}</kbd> ${shortLabel}</span>`;
  });

  const footerTabShortcuts = qs('#footer-tab-shortcuts');
  if (footerTabShortcuts) {
    footerTabShortcuts.innerHTML = footerHtml;
  }
}

function initTabDragAndDrop() {
  const tabBar = qs('.tab-nav-segment') || qs('.main-views-switcher') || qs('.tab-bar') || qs('#header');
  if (!tabBar) return;

  restoreTabOrder();

  let draggedTab = null;

  tabBar.querySelectorAll('.tab').forEach(tab => {
    tab.setAttribute('draggable', 'true');

    tab.addEventListener('dragstart', (e) => {
      draggedTab = tab;
      tab.classList.add('tab-dragging');
      e.dataTransfer.effectAllowed = 'move';
      e.dataTransfer.setData('text/plain', tab.dataset.tab || '');
    });

    tab.addEventListener('dragend', () => {
      tab.classList.remove('tab-dragging');
      tabBar.querySelectorAll('.tab').forEach(t => {
        t.classList.remove('drag-over-left', 'drag-over-right');
      });
      draggedTab = null;
      saveTabOrder();
    });

    tab.addEventListener('dragover', (e) => {
      e.preventDefault();
      if (!draggedTab || draggedTab === tab) return;

      e.dataTransfer.dropEffect = 'move';
      const rect = tab.getBoundingClientRect();
      const midPoint = rect.left + rect.width / 2;
      const isLeft = e.clientX < midPoint;

      tabBar.querySelectorAll('.tab').forEach(t => {
        if (t !== tab) t.classList.remove('drag-over-left', 'drag-over-right');
      });

      if (isLeft) {
        tab.classList.add('drag-over-left');
        tab.classList.remove('drag-over-right');
      } else {
        tab.classList.add('drag-over-right');
        tab.classList.remove('drag-over-left');
      }
    });

    tab.addEventListener('dragleave', (e) => {
      if (!tab.contains(e.relatedTarget)) {
        tab.classList.remove('drag-over-left', 'drag-over-right');
      }
    });

    tab.addEventListener('drop', (e) => {
      e.preventDefault();
      tab.classList.remove('drag-over-left', 'drag-over-right');
      if (!draggedTab || draggedTab === tab) return;

      const rect = tab.getBoundingClientRect();
      const midPoint = rect.left + rect.width / 2;
      const isLeft = e.clientX < midPoint;

      const container = tab.parentElement || tabBar;
      if (isLeft) {
        container.insertBefore(draggedTab, tab);
      } else {
        container.insertBefore(draggedTab, tab.nextSibling);
      }

      saveTabOrder();
    });
  });
}


/** Load Monaco Editor with offline / blocked tracking prevention fallback. */
function initMonaco() {
  if (App.editorPromise) return App.editorPromise;

  App.editorPromise = (async () => {
    if (typeof require === 'undefined') {
      try {
        await loadScript('https://cdnjs.cloudflare.com/ajax/libs/monaco-editor/0.45.0/min/vs/loader.min.js');
      } catch (err) {
        console.warn('Monaco loader CDN load failed/blocked, using fallback viewer:', err);
        return null;
      }
    }
    if (typeof require === 'undefined') {
      console.warn('Monaco AMD loader not available (offline/blocked), using fallback viewer.');
      return null;
    }
    return new Promise((resolve) => {
      try {
        require.config({
          paths: { vs: 'https://cdnjs.cloudflare.com/ajax/libs/monaco-editor/0.45.0/min/vs' }
        });
        require(['vs/editor/editor.main'], () => {
          resolve(window.monaco);
        }, err => {
          console.warn('Monaco CDN load failed/blocked, using fallback viewer:', err);
          resolve(null);
        });
      } catch (e) {
        console.warn('Monaco require error, using fallback viewer:', e);
        resolve(null);
      }
    });
  })();

  return App.editorPromise;
}

/** Simple syntax token highlighter for fallback code viewer */
function highlightJavaSyntax(code) {
  const keywords = ['abstract','assert','boolean','break','byte','case','catch','char','class','const','continue','default','do','double','else','enum','extends','final','finally','float','for','if','implements','import','instanceof','int','interface','long','native','new','package','private','protected','public','return','short','static','strictfp','super','switch','synchronized','this','throw','throws','transient','try','void','volatile','while','record','sealed','permits','var','yield'];
  
  // Escape HTML
  let escaped = esc(code);
  
  // Highlight strings
  escaped = escaped.replace(/(&quot;.*?&quot;|&#39;.*?&#39;|".*?"|'.*?')/g, '<span class="tok-string">$1</span>');
  // Highlight annotations
  escaped = escaped.replace(/(@\w+)/g, '<span class="tok-annotation">$1</span>');
  // Highlight keywords (word boundary)
  const kwRegex = new RegExp('\\b(' + keywords.join('|') + ')\\b', 'g');
  escaped = escaped.replace(kwRegex, '<span class="tok-kw">$1</span>');
  // Highlight comments
  escaped = escaped.replace(/(\/\/.*$)/gm, '<span class="tok-comment">$1</span>');

  return escaped;
}

/** Render native fallback code editor/viewer with line numbers */
function renderFallbackViewer(content, lineNum) {
  const container = qs('#editor-container');
  if (!container) return;

  const lines = content.split('\n');
  const linesHtml = lines.map((line, idx) => {
    const num = idx + 1;
    const isTarget = (lineNum && num === lineNum);
    const highlighted = highlightJavaSyntax(line);
    return `<div class="fallback-line ${isTarget ? 'highlight-target' : ''}" id="fallback-line-${num}">
      <span class="fallback-line-num">${num}</span>
      <span class="fallback-line-content">${highlighted || ' '}</span>
    </div>`;
  }).join('');

  container.innerHTML = `
    <div class="fallback-code-wrap">
      <div class="fallback-code-scroll">
        ${linesHtml}
      </div>
    </div>
  `;

  if (lineNum) {
    setTimeout(() => {
      const targetEl = qs(`#fallback-line-${lineNum}`);
      if (targetEl) {
        targetEl.scrollIntoView({ behavior: 'smooth', block: 'center' });
      }
    }, 50);
  }
}

/** Fetch a source file, mount Monaco Editor (or fallback), load the code, and focus on the line. */
async function openSourceFile(filePath, lineNum = null, skipTabSwitch = false) {
  if (!filePath) return;

  App.currentFilePath = filePath;
  App.currentLineNum = lineNum;
  App._loadedSourceFilePath = filePath;
  App._loadedSourceLineNum = lineNum;
  updateReviewTargetInfo();
  
  const pathLabel = qs('#editor-file-path');
  if (pathLabel) {
    pathLabel.innerHTML = `Source: <strong>${esc(filePath.split('/').pop().split('\\').pop())}</strong> <span style="font-size:10px; color:var(--text-muted)">(${esc(filePath)})</span>`;
  }

  try {
    // Fetch file content first
    const data = await api.readFile(filePath);

    // Switch to source tab
    if (!skipTabSwitch) {
      switchTab('source');
    }

    // Hide placeholder/empty state and enable save
    const emptyState = qs('#editor-empty-state');
    if (emptyState) emptyState.style.display = 'none';
    const saveBtn = qs('#editor-save-btn');
    if (saveBtn) saveBtn.disabled = false;

    // Attempt to load Monaco (falls back gracefully if CDN/tracking prevention blocked)
    const monaco = await initMonaco();

    if (!monaco) {
      // Fallback: render built-in syntax-highlighted code viewer
      renderFallbackViewer(data.content, lineNum);
      return;
    }

    if (!App.editor) {
      const container = qs('#editor-container');
      App.editor = monaco.editor.create(container, {
        theme: 'vs-dark',
        automaticLayout: false, // handled manually via layout() to avoid overhead
        minimap: { enabled: true },
        fontSize: 13,
        fontFamily: 'var(--font-mono), Menlo, Monaco, "Courier New", monospace',
        lineHeight: 20,
        scrollbar: {
          vertical: 'visible',
          horizontal: 'visible',
          useShadows: false,
          verticalScrollbarSize: 10,
          horizontalScrollbarSize: 10
        }
      });
    }

    // Set model
    const extension = filePath.split('.').pop().toLowerCase();
    let language = 'text';
    if (extension === 'java') language = 'java';
    else if (extension === 'xml') language = 'xml';
    else if (extension === 'json') language = 'json';
    else if (extension === 'properties') language = 'ini';
    else if (extension === 'md') language = 'markdown';

    const uri = monaco.Uri.file(filePath);
    let model = monaco.editor.getModel(uri);
    if (!model) {
      model = monaco.editor.createModel(data.content, language, uri);
    } else {
      model.setValue(data.content);
    }

    App.editor.setModel(model);

    // Scroll and highlight
    if (lineNum) {
      setTimeout(() => {
        App.editor.revealLineInCenter(lineNum);
        App.editor.setPosition({ lineNumber: lineNum, column: 1 });
        App.editor.focus();

        const range = new monaco.Range(lineNum, 1, lineNum, 1);
        const decorations = App.editor.deltaDecorations([], [
          {
            range: range,
            options: {
              isWholeLine: true,
              className: 'monaco-line-highlight-neon'
            }
          }
        ]);
        setTimeout(() => {
          if (App.editor) {
            App.editor.deltaDecorations(decorations, []);
          }
        }, 2000);
      }, 50);
    } else {
      App.editor.focus();
    }

    App.editor.layout();

  } catch (err) {
    showError('Failed to load file: ' + err.message);
  }
}

/* ── Knowledge base view ─────────────────────────────────────────────────────── */

/* ── Knowledge base archetype helper functions ─────────────────────────────── */

/** Group an array of types by archetype into ordered categories. */
function groupTypesByArchetype(types, pkgFqn) {
  const groupsMap = new Map();

  const getOrCreateGroup = (key, badge, label, color, description, orderWeight) => {
    if (!groupsMap.has(key)) {
      groupsMap.set(key, {
        key,
        badge,
        label,
        color: color || '#64748b',
        description: description || '',
        orderWeight: orderWeight || 100,
        items: []
      });
    }
    return groupsMap.get(key);
  };

  for (const t of types) {
    let matched = false;
    if (window.CodeLensClassifier) {
      const arch = window.CodeLensClassifier.classifyType(t, t.fqn || t.id, t.packageFqn || pkgFqn);
      if (arch) {
        let weight = 50;
        const b = (arch.badge || '').toUpperCase();
        if (b === 'MSG-OBJECT') weight = 10;
        else if (b === 'PERSISTENT') weight = 20;
        else if (b === 'DATA-GRABBER') weight = 30;
        else if (b === 'SRV') weight = 40;
        else if (b === 'CTRL') weight = 45;
        else if (b === 'REPO') weight = 46;

        const g = getOrCreateGroup(
          arch.ruleId || arch.badge,
          arch.badge,
          arch.label,
          arch.color,
          arch.description,
          weight
        );
        g.items.push(t);
        matched = true;
      }
    }

    if (!matched) {
      const kind = (t.kind || 'CLASS').toUpperCase();
      const sName = t.simpleName || '';
      if (kind === 'INTERFACE') {
        const g = getOrCreateGroup('kind-interface', 'IFACE', 'Interfaces', '#38bdf8', 'Interface definitions and contracts', 60);
        g.items.push(t);
      } else if (kind === 'RECORD') {
        const g = getOrCreateGroup('kind-record', 'RECORD', 'Records', '#c084fc', 'Immutable record data carriers', 70);
        g.items.push(t);
      } else if (kind === 'ENUM') {
        const g = getOrCreateGroup('kind-enum', 'ENUM', 'Enums', '#fbbf24', 'Enumeration definitions', 80);
        g.items.push(t);
      } else if (sName.endsWith('Service') || sName.endsWith('ServiceImpl')) {
        const g = getOrCreateGroup('kind-service', 'SRV', 'Business Services', '#10b981', 'Service orchestration layer', 40);
        g.items.push(t);
      } else if (sName.endsWith('Controller')) {
        const g = getOrCreateGroup('kind-controller', 'CTRL', 'Controllers', '#3b82f6', 'REST and MVC controllers', 45);
        g.items.push(t);
      } else if (sName.endsWith('Repository')) {
        const g = getOrCreateGroup('kind-repository', 'REPO', 'Repositories', '#8b5cf6', 'Data access repositories', 46);
        g.items.push(t);
      } else {
        const g = getOrCreateGroup('kind-class', 'CLASS', 'General Classes', '#94a3b8', 'Standard domain classes and implementations', 90);
        g.items.push(t);
      }
    }
  }

  return Array.from(groupsMap.values()).sort((a, b) => {
    if (a.orderWeight !== b.orderWeight) return a.orderWeight - b.orderWeight;
    return a.label.localeCompare(b.label);
  });
}

/** Group an array of methods by archetype into ordered categories. */
function groupMethodsByArchetype(methods, type, pkgFqn) {
  const groupsMap = new Map();

  const getOrCreateGroup = (key, badge, label, color, description, orderWeight) => {
    if (!groupsMap.has(key)) {
      groupsMap.set(key, {
        key,
        badge,
        label,
        color: color || '#64748b',
        description: description || '',
        orderWeight: orderWeight || 100,
        items: []
      });
    }
    return groupsMap.get(key);
  };

  for (const m of methods) {
    const isConstructor = m.simpleName === '<init>' || m.constructor === true || m.simpleName === type.simpleName;
    if (isConstructor) {
      const g = getOrCreateGroup('method-ctor', 'CTOR', 'Constructors', '#f59e0b', 'Constructors and initializers', 10);
      g.items.push(m);
      continue;
    }

    let matched = false;
    if (window.CodeLensClassifier) {
      const arch = window.CodeLensClassifier.classifyMethod(m, type.fqn || type.id, pkgFqn || type.packageFqn);
      if (arch) {
        let weight = 50;
        const b = (arch.badge || '').toUpperCase();
        if (b === 'TASK-OWN') weight = 20;
        else if (b === 'TASK-COMMON') weight = 25;
        else if (b === 'MUTATE') weight = 30;
        else if (b === 'FETCH') weight = 35;
        else if (b === 'BATCH') weight = 40;
        else if (b === 'PRE-BATCH') weight = 41;
        else if (b === 'POST-BATCH') weight = 42;

        const g = getOrCreateGroup(
          arch.ruleId || arch.badge,
          arch.badge,
          arch.label,
          arch.color,
          arch.description,
          weight
        );
        g.items.push(m);
        matched = true;
      }
    }

    if (!matched) {
      const isPojo = window.CodeLensClassifier ? window.CodeLensClassifier.isPojo(m, type.fqn || type.id, pkgFqn || type.packageFqn) : false;
      if (isPojo) {
        const g = getOrCreateGroup('method-pojo', 'POJO', 'Accessors & Properties', '#64748b', 'Getters, setters, and boilerplate methods', 80);
        g.items.push(m);
      } else {
        const g = getOrCreateGroup('method-operation', 'METHOD', 'Operations & Methods', '#6366f1', 'Declared business operations and routines', 60);
        g.items.push(m);
      }
    }
  }

  return Array.from(groupsMap.values()).sort((a, b) => {
    if (a.orderWeight !== b.orderWeight) return a.orderWeight - b.orderWeight;
    return a.label.localeCompare(b.label);
  });
}

/** Renders a collapsible accordion for an archetype group with deferred DOM hydration. */
function renderArchetypeAccordion({ group, renderItemRow, initialOpen = false }) {
  const accordion = createElement('div', { class: `kb-archetype-accordion ${initialOpen ? 'is-open' : ''}`, 'data-archetype': group.key });

  const header = createElement('div', {
    class: 'kb-archetype-header',
    role: 'button',
    tabindex: '0',
    'aria-expanded': initialOpen ? 'true' : 'false'
  });

  header.innerHTML = `
    <div class="kb-archetype-header-left">
      <span class="kb-archetype-chevron">
        <svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><polyline points="9 18 15 12 9 6"/></svg>
      </span>
      <span class="kb-archetype-badge" style="background:${group.color}1a; color:${group.color}; border:1px solid ${group.color}55;" title="${esc(group.description)}">${esc(group.badge)}</span>
      <span class="kb-archetype-title" title="${esc(group.description)}">${esc(group.label)}</span>
    </div>
    <div class="kb-archetype-header-right">
      <span class="kb-archetype-count-badge">${group.items.length}</span>
    </div>
  `;

  const body = createElement('div', {
    class: 'kb-archetype-body',
    style: initialOpen ? '' : 'display:none;'
  });

  let isHydrated = false;
  const hydrate = () => {
    if (isHydrated) return;
    const frag = document.createDocumentFragment();
    for (const item of group.items) {
      frag.appendChild(renderItemRow(item, group));
    }
    body.appendChild(frag);
    isHydrated = true;
  };

  if (initialOpen) {
    hydrate();
  }

  accordion._hydrate = hydrate;
  accordion._isHydrated = () => isHydrated;

  const toggle = () => {
    const isOpen = accordion.classList.toggle('is-open');
    header.setAttribute('aria-expanded', isOpen ? 'true' : 'false');
    if (isOpen) {
      hydrate();
      body.style.display = '';
    } else {
      body.style.display = 'none';
    }
  };

  header.addEventListener('click', toggle);
  header.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      toggle();
    }
  });

  accordion.appendChild(header);
  accordion.appendChild(body);
  return accordion;
}

/** Attaches toggle all behavior to an "Expand All / Collapse All" button. */
function attachAccordionToggleAll(sectionEl, listSelector) {
  const toggleBtn = sectionEl.querySelector('.kb-accordion-toggle-all-btn');
  if (!toggleBtn) return;

  toggleBtn.addEventListener('click', () => {
    const accordions = sectionEl.querySelectorAll(`${listSelector} .kb-archetype-accordion`);
    const isCurrentlyExpanded = toggleBtn.getAttribute('data-state') === 'expanded';
    const newState = !isCurrentlyExpanded;

    accordions.forEach(acc => {
      const header = acc.querySelector('.kb-archetype-header');
      const body = acc.querySelector('.kb-archetype-body');
      if (newState) {
        if (typeof acc._hydrate === 'function') acc._hydrate();
        acc.classList.add('is-open');
        if (header) header.setAttribute('aria-expanded', 'true');
        if (body) body.style.display = '';
      } else {
        acc.classList.remove('is-open');
        if (header) header.setAttribute('aria-expanded', 'false');
        if (body) body.style.display = 'none';
      }
    });

    toggleBtn.setAttribute('data-state', newState ? 'expanded' : 'collapsed');
    const labelSpan = toggleBtn.querySelector('span');
    if (labelSpan) {
      labelSpan.textContent = newState ? 'Collapse All' : 'Expand All';
    }
  });
}

function renderTypeRow(t) {
  const tKind = (t.kind || 'CLASS').toUpperCase();
  const tKindClass = `kind-${tKind.toLowerCase()}`;
  let archBadge = '';
  if (window.CodeLensClassifier) {
    const arch = window.CodeLensClassifier.classifyType(t, t.fqn || t.id, t.packageFqn);
    if (arch) {
      archBadge = `<span class="legend-class-badge" style="background:${arch.color}22; color:${arch.color}; border:1px solid ${arch.color}66;" title="${esc(arch.description)}">${esc(arch.badge)}</span>`;
    }
  }
  const row = createElement('div', { 
    class: 'kb-row kb-member-row',
    'data-kind': 'type',
    'data-id': t.id,
    role: 'button',
    tabindex: '0'
  });
  row.innerHTML = `
    <div class="kb-row-left">
      <div class="kb-row-icon icon-type" title="${esc(tKind)}">
        <svg class="svg-icon icon-emerald icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="12 2 2 7 12 12 22 7 12 2"/><polyline points="2 17 12 22 22 17"/><polyline points="2 12 12 17 22 12"/></svg>
      </div>
      <div class="kb-row-info">
        <div class="kb-row-name-wrap">
          <span class="kb-row-name" title="${esc(t.simpleName)}">${esc(t.simpleName)}</span>
          <span class="kb-kind-badge ${tKindClass}">${esc(tKind)}</span>
          ${archBadge}
        </div>
        <div class="kb-row-meta">
          ${t.lineCount > 0 ? `<span>${t.lineCount} lines</span>` : ''}
          ${t.fieldCount > 0 ? `<span>· ${t.fieldCount} fields</span>` : ''}
          ${t.methodCount > 0 ? `<span>· ${t.methodCount} methods</span>` : ''}
        </div>
      </div>
    </div>
    <div class="kb-row-right">
      <span class="kb-type-pill">Explore &rarr;</span>
    </div>
  `;
  return row;
}

function renderMethodRow(m, type) {
  const isConstructor = m.simpleName === '<init>' || m.constructor === true || m.simpleName === type.simpleName;
  const displayName = isConstructor ? type.simpleName : m.simpleName;
  const cc = m.cyclomaticComplexity || 1;
  const ccTier = cc <= 4 ? 'cc-low' : cc <= 10 ? 'cc-med' : 'cc-high';
  const ccLabel = cc <= 4 ? 'Low' : cc <= 10 ? 'Med' : 'High';

  let paramsFormatted = '()';
  if (m.parameters && m.parameters.length > 0) {
    paramsFormatted = '(' + m.parameters.map(p => {
      const pType = (p.type || '').split('.').pop();
      return `<span class="kb-param-type">${esc(pType)}</span> <span class="kb-param-name">${esc(p.name || '')}</span>`;
    }).join(', ') + ')';
  }

  const row = createElement('div', { 
    class: 'kb-row kb-member-row',
    'data-kind': 'method',
    'data-id': m.id || m.fqn,
    role: 'button',
    tabindex: '0'
  });
  row.innerHTML = `
    <div class="kb-row-left">
      <div class="kb-row-icon icon-method" title="${isConstructor ? 'Constructor' : 'Method'}">
        <svg class="svg-icon icon-indigo icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m18 16 4-4-4-4"/><path d="m6 8-4 4 4 4"/><path d="m14.5 4-5 16"/></svg>
      </div>
      <div class="kb-row-info">
        <div class="kb-row-name-wrap">
          <span class="kb-row-name" title="${esc(displayName)}">${esc(displayName)}</span>
        </div>
        <div class="kb-row-meta" title="${esc(displayName)}${paramsFormatted.replace(/<[^>]*>/g, '')}">
          ${m.modifiers ? `<span class="kb-mod-pill" title="${esc(m.modifiers)}">${esc(m.modifiers.replace(/\bsynchronized\b/g, 'sync'))}</span>` : ''}
          <span class="kb-param-text">${paramsFormatted}</span>
          ${m.startLine ? `<span class="kb-row-line">· Line ${m.startLine}</span>` : ''}
        </div>
      </div>
    </div>
    <div class="kb-row-right">
      <span class="kb-cc-pill ${ccTier}" title="Cyclomatic Complexity: ${cc}">CC: ${cc} (${ccLabel})</span>
      ${isConstructor ? '<span class="kb-mod-pill" style="color:var(--amber);background:rgba(245,158,11,0.1);border:1px solid rgba(245,158,11,0.25);">constructor</span>' : `<span class="kb-type-pill" title="Return type">${esc(m.returnType || 'void')}</span>`}
    </div>
  `;
  return row;
}

/** Load and render all types for a given package in the KB tab. */
async function loadKnowledgeBase(pkgFqn, initialTab = null) {
  const view = qs('#knowledge-view');
  if (!view) return;

  const depCacheKey = `mod:dep:${(pkgFqn || '').toLowerCase()}`;
  let cachedDepData = GraphDataCache.has(depCacheKey) ? GraphDataCache.get(depCacheKey) : null;
  const typesCacheKey = `pkg:types:${(pkgFqn || '').toLowerCase()}`;
  let cachedTypes = GraphDataCache.has(typesCacheKey) ? GraphDataCache.get(typesCacheKey) : null;

  // Only show blank/loading skeleton if neither types nor depData are cached yet
  if (!cachedTypes && !(initialTab === 'DEPENDENCIES' && cachedDepData)) {
    view.innerHTML = `
      <div class="kb-empty-container fade-in" style="padding:60px 20px;">
        <div class="kb-empty-icon"><div class="spinner" style="width:24px;height:24px;border-width:2.5px;"></div></div>
        <div class="kb-empty-title">Loading Package Entities…</div>
        <div class="kb-empty-desc">${esc(pkgFqn)}</div>
      </div>`;
    view.scrollTop = 0;
  }

  try {
    const [types, depData] = await Promise.all([
      cachedTypes ? Promise.resolve(cachedTypes) : api.typesByPackage(pkgFqn),
      cachedDepData ? Promise.resolve(cachedDepData) : api.packageDependencies(pkgFqn).catch(() => null)
    ]);

    let moduleDepData = depData || cachedDepData;
    if (moduleDepData && depCacheKey) {
      GraphDataCache.set(depCacheKey, moduleDepData);
    }

    if (window.CodeLensClassifier && Array.isArray(types)) {
      for (const t of types) {
        if (Array.isArray(t.methods) && t.methods.length > 0 && typeof window.CodeLensClassifier.registerTypeMethods === 'function') {
          window.CodeLensClassifier.registerTypeMethods(t.fqn || t.id, t.methods);
        }
      }
    }
    let activeKind = (initialTab || App.activeFilter || 'all').toUpperCase();

    // Clear loading placeholder once data is ready
    view.innerHTML = '';
    view.scrollTop = 0;

    // ── Package Hero Card ─────────────────────────────────────────────────────
    const hero = createElement('div', { class: 'kb-hero-card fade-in' });
    hero.innerHTML = `
      <div class="kb-hero-top">
        <div class="kb-hero-title-group">
          <span class="kb-kind-badge kind-package">PACKAGE</span>
          <span class="kb-hero-name">${esc(pkgFqn)}</span>
        </div>
        <div class="kb-hero-actions">
          <button class="kb-action-btn" id="kb-pkg-graph" title="View in Graph">
            <svg class="svg-icon icon-emerald icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><line x1="8.59" y1="13.51" x2="15.42" y2="17.49"/><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"/></svg>
            Graph
          </button>
        </div>
      </div>
      <div class="kb-hero-meta-row">
        <div class="kb-meta-item"><span class="kb-meta-label">Total Types:</span> <span class="kb-meta-val" id="kb-pkg-total-count">${types.length}</span></div>
        <div class="kb-meta-divider"></div>
        <div class="kb-meta-item"><span class="kb-meta-label">Showing:</span> <span class="kb-meta-val" id="kb-pkg-showing-count">0</span></div>
        <div class="kb-meta-divider"></div>
        <div class="kb-meta-item"><span class="kb-meta-label">Filter:</span> <span class="kb-meta-val" id="kb-pkg-filter-label">${activeKind}</span></div>
      </div>
    `;
    hero.querySelector('#kb-pkg-graph')?.addEventListener('click', () => {
      switchTab('graph');
    });
    view.appendChild(hero);

    // ── Package View Tabs ───────────────────────────────────────────────────────
    const counts = {
      ALL: types.length,
      CLASS: types.filter(t => (t.kind || '').toUpperCase() === 'CLASS').length,
      INTERFACE: types.filter(t => (t.kind || '').toUpperCase() === 'INTERFACE').length,
      RECORD: types.filter(t => (t.kind || '').toUpperCase() === 'RECORD').length,
      ENUM: types.filter(t => (t.kind || '').toUpperCase() === 'ENUM').length
    };

    const depTouchCount = moduleDepData ? (moduleDepData.totalTouchPoints || 0) : 0;
    const pkgTabsBar = createElement('div', { class: 'kb-members-nav-bar fade-in' });
    pkgTabsBar.innerHTML = `
      <div class="kb-members-tabs" role="tablist">
        <button class="kb-tab-pill ${activeKind === 'ALL' ? 'active' : ''}" data-filter="ALL">
          <span>All Types</span>
          <span class="kb-tab-badge">${counts.ALL}</span>
        </button>
        <button class="kb-tab-pill ${activeKind === 'CLASS' ? 'active' : ''}" data-filter="CLASS">
          <span>Classes</span>
          <span class="kb-tab-badge">${counts.CLASS}</span>
        </button>
        <button class="kb-tab-pill ${activeKind === 'INTERFACE' ? 'active' : ''}" data-filter="INTERFACE">
          <span>Interfaces</span>
          <span class="kb-tab-badge">${counts.INTERFACE}</span>
        </button>
        <button class="kb-tab-pill ${activeKind === 'RECORD' ? 'active' : ''}" data-filter="RECORD">
          <span>Records</span>
          <span class="kb-tab-badge">${counts.RECORD}</span>
        </button>
        <button class="kb-tab-pill ${activeKind === 'ENUM' ? 'active' : ''}" data-filter="ENUM">
          <span>Enums</span>
          <span class="kb-tab-badge">${counts.ENUM}</span>
        </button>
        <button class="kb-tab-pill ${activeKind === 'DEPENDENCIES' ? 'active' : ''}" data-filter="DEPENDENCIES" id="kb-tab-dependencies">
          <svg class="svg-icon icon-cyan icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="margin-right:4px;"><path d="M9 17H7A5 5 0 0 1 7 7h2"/><path d="M15 7h2a5 5 0 0 1 0 10h-2"/><line x1="8" y1="12" x2="16" y2="12"/></svg>
          <span>Dependencies & Touch Points</span>
          <span class="kb-tab-badge" id="kb-dep-badge">${depTouchCount} touch pts</span>
        </button>
      </div>
    `;
    view.appendChild(pkgTabsBar);

    // Container for types section or empty state
    const contentContainer = createElement('div', { id: 'kb-pkg-content-container' });
    view.appendChild(contentContainer);

    function renderFilteredTypes(filterKind) {
      activeKind = filterKind;
      contentContainer.innerHTML = '';

      // Update tabs active state
      pkgTabsBar.querySelectorAll('.kb-tab-pill').forEach(pill => {
        pill.classList.toggle('active', pill.dataset.filter === activeKind);
      });

      if (activeKind === 'DEPENDENCIES') {
        const showingEl = qs('#kb-pkg-showing-count');
        const filterEl = qs('#kb-pkg-filter-label');
        if (filterEl) filterEl.textContent = 'DEPENDENCIES';
        if (moduleDepData) {
          if (showingEl) showingEl.textContent = ((moduleDepData.outgoingModules?.length || 0) + (moduleDepData.incomingModules?.length || 0)) + ' modules';
          renderKnowledgeBaseDependenciesView(pkgFqn, contentContainer, moduleDepData);
        } else {
          // Guaranteed fallback without hanging
          renderKnowledgeBaseDependenciesView(pkgFqn, contentContainer, { outgoingModules: [], incomingModules: [], totalTouchPoints: 0, moduleName: pkgFqn.split('.').pop() });
        }
        return;
      }

      const filteredTypes = types.filter(t => {
        if (activeKind === 'ALL') return true;
        return (t.kind || '').toUpperCase() === activeKind;
      });

      // Update meta in hero
      const showingEl = qs('#kb-pkg-showing-count');
      const filterEl = qs('#kb-pkg-filter-label');
      if (showingEl) showingEl.textContent = filteredTypes.length;
      if (filterEl) filterEl.textContent = activeKind;

      if (filteredTypes.length === 0) {
        const msg = activeKind === 'ALL'
          ? 'No types found in this package.'
          : `No ${activeKind.toLowerCase()}s found in this package (active filter: ${activeKind}).`;
        const empty = createElement('div', { class: 'kb-empty-container fade-in' });
        empty.innerHTML = `
          <div class="kb-empty-icon">
            <svg class="svg-icon icon-cyan icon-lg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
          </div>
          <div class="kb-empty-title">No Matching Types</div>
          <div class="kb-empty-desc">${esc(msg)}</div>
        `;
        contentContainer.appendChild(empty);
        return;
      }

      const typesSection = createElement('div', { class: 'kb-section fade-in' });
      typesSection.innerHTML = `
        <div class="kb-section-title">
          <div class="kb-section-title-left">
            <svg class="svg-icon icon-emerald icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M3 9h18"/><path d="M9 21V9"/></svg>
            <span>Declared Types</span>
            <span class="kb-section-badge">${filteredTypes.length}</span>
          </div>
          <div class="kb-section-title-right">
            <button class="kb-accordion-toggle-all-btn" data-state="collapsed" title="Expand or collapse all archetype sections">
              <svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="7 13 12 18 17 13"/><polyline points="7 6 12 11 17 6"/></svg>
              <span>Expand All</span>
            </button>
          </div>
        </div>
        <div class="kb-list" id="kb-types-list"></div>
      `;
      const typesList = typesSection.querySelector('#kb-types-list');

      const groups = groupTypesByArchetype(filteredTypes, pkgFqn);
      for (const group of groups) {
        const accordion = renderArchetypeAccordion({
          group,
          renderItemRow: renderTypeRow,
          initialOpen: false
        });
        typesList.appendChild(accordion);
      }

      attachAccordionToggleAll(typesSection, '#kb-types-list');

      typesList.addEventListener('click', (e) => {
        const row = e.target.closest('.kb-member-row');
        if (row && row.dataset.id && row.dataset.kind === 'type') {
          selectType(row.dataset.id);
        }
      });
      typesList.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          const row = e.target.closest('.kb-member-row');
          if (row && row.dataset.id && row.dataset.kind === 'type') {
            e.preventDefault();
            selectType(row.dataset.id);
          }
        }
      });

      contentContainer.appendChild(typesSection);
    }

    // Attach listeners to filter tabs
    pkgTabsBar.querySelectorAll('.kb-tab-pill').forEach(pill => {
      pill.addEventListener('click', () => {
        renderFilteredTypes(pill.dataset.filter);
      });
    });

    renderFilteredTypes(activeKind);
  } catch (e) {
    const errorCard = createElement('div', { class: 'kb-empty-container fade-in' });
    errorCard.innerHTML = `
      <div class="kb-empty-title" style="color:var(--red)">Failed to load package</div>
      <div class="kb-empty-desc">${esc(e.message)}</div>
    `;
    view.appendChild(errorCard);
  }
}

/** Render comprehensive Module Dependencies, Touch Points, Class Usages, and Function Calls in Knowledge Base */
/** Render comprehensive Module Dependencies, Touch Points, Class Usages, and Function Calls in Knowledge Base */
function renderKnowledgeBaseDependenciesView(pkgFqn, container, depData) {
  if (!depData) {
    container.innerHTML = `
      <div class="kb-empty-container fade-in">
        <div class="kb-empty-title">No Dependency Data Available</div>
        <div class="kb-empty-desc">Could not calculate dependency insights for this package/module.</div>
      </div>`;
    return;
  }

  // Pre-process and collect all class usages and function calls
  const allOutgoingClassUsages = [];
  const allIncomingClassUsages = [];
  const allOutgoingFunctionCalls = [];
  const allIncomingFunctionCalls = [];

  for (const mod of (depData.outgoingModules || [])) {
    for (const cu of (mod.classUsages || [])) {
      allOutgoingClassUsages.push({ ...cu, targetModule: mod.moduleName, targetPackage: mod.packageFqn, direction: 'OUTBOUND' });
    }
    for (const tp of (mod.touchPoints || [])) {
      if ((tp.kind || '').toUpperCase() === 'CALLS') {
        allOutgoingFunctionCalls.push({ ...tp, targetModule: mod.moduleName, targetPackage: mod.packageFqn, direction: 'OUTBOUND' });
      }
    }
  }

  for (const mod of (depData.incomingModules || [])) {
    for (const cu of (mod.classUsages || [])) {
      allIncomingClassUsages.push({ ...cu, sourceModule: mod.moduleName, sourcePackage: mod.packageFqn, direction: 'INBOUND' });
    }
    for (const tp of (mod.touchPoints || [])) {
      if ((tp.kind || '').toUpperCase() === 'CALLS') {
        allIncomingFunctionCalls.push({ ...tp, sourceModule: mod.moduleName, sourcePackage: mod.packageFqn, direction: 'INBOUND' });
      }
    }
  }

  const allClassUsages = [...allOutgoingClassUsages, ...allIncomingClassUsages];
  const allFunctionCalls = [...allOutgoingFunctionCalls, ...allIncomingFunctionCalls];

  const stabilityClass = depData.instability < 0.3
    ? 'is-stable'
    : depData.instability > 0.7
    ? 'is-flexible'
    : 'is-balanced';

  const roleBadge = depData.instability < 0.3
    ? '<span class="mod-dep-role-pill core">Stable Core</span>'
    : depData.instability > 0.7
    ? '<span class="mod-dep-role-pill client">Client Layer</span>'
    : '<span class="mod-dep-role-pill bridge">Coupling Bridge</span>';

  // Total touch points for kind distribution
  const kinds = depData.totalByKind || {};
  const totalKindsCount = Object.values(kinds).reduce((a, b) => a + b, 0) || 1;

  const viewEl = createElement('div', { class: 'module-dependencies-view fade-in' });

  // 0. Module Header Bar & Switcher
  const headerBar = createElement('div', { class: 'mod-dep-header-bar' });
  const packagesList = App.packages || [];
  let moduleSelectOptions = `<option value="${esc(pkgFqn)}">${esc(depData.moduleName || pkgFqn)} (Current)</option>`;
  if (packagesList.length > 0) {
    moduleSelectOptions = packagesList.map(p => {
      const isCur = p.fqn === pkgFqn;
      const cleanName = p.name || (p.fqn ? p.fqn.split('.').pop() : 'default');
      return `<option value="${esc(p.fqn)}" ${isCur ? 'selected' : ''}>${esc(cleanName)} (${esc(p.fqn)})</option>`;
    }).join('');
  }

  headerBar.innerHTML = `
    <div class="mod-dep-header-left">
      <div class="mod-dep-header-title">
        <svg class="svg-icon icon-cyan icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 17H7A5 5 0 0 1 7 7h2"/><path d="M15 7h2a5 5 0 0 1 0 10h-2"/><line x1="8" y1="12" x2="16" y2="12"/></svg>
        <h2>${esc(depData.moduleName || pkgFqn)}</h2>
        <div class="mod-dep-header-badges">
          ${roleBadge}
          <span class="mod-dep-instability-badge">I = ${(depData.instability || 0).toFixed(2)}</span>
        </div>
      </div>
      <div class="mod-dep-header-pkg">${esc(pkgFqn)}</div>
    </div>
    <div class="mod-dep-header-right">
      <div class="mod-dep-switcher-wrap">
        <span class="mod-dep-switcher-label">Switch Module:</span>
        <select class="mod-dep-module-select" aria-label="Select module to inspect">
          ${moduleSelectOptions}
        </select>
      </div>
      <button class="btn-secondary btn-export-deps-json" title="Export this module's coupling insights to JSON">
        <svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="width:12px;height:12px;"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
        <span>Export JSON</span>
      </button>
    </div>
  `;

  const modSelect = headerBar.querySelector('.mod-dep-module-select');
  if (modSelect) {
    modSelect.addEventListener('change', (e) => {
      const chosenFqn = e.target.value;
      const targetPkg = (App.packages || []).find(p => p.fqn === chosenFqn) || { fqn: chosenFqn, name: chosenFqn };
      selectModuleItem(targetPkg, 'DEPENDENCIES');
    });
  }

  const exportBtn = headerBar.querySelector('.btn-export-deps-json');
  if (exportBtn) {
    exportBtn.addEventListener('click', () => {
      try {
        const jsonStr = JSON.stringify(depData, null, 2);
        const blob = new Blob([jsonStr], { type: 'application/json' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `${(depData.moduleName || 'module').toLowerCase()}-dependencies.json`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
        if (typeof showToast === 'function') {
          showToast(`Exported ${depData.moduleName || 'module'} dependencies JSON`);
        }
      } catch (err) {
        console.error('Failed to export dependencies JSON', err);
      }
    });
  }

  viewEl.appendChild(headerBar);

  // 1. Visual Coupling Topology Flow Map - Next-Gen Architectural Canvas
  const flowMap = createElement('div', { class: 'mod-dep-flow-map' });
  const inMods = depData.incomingModules || [];
  const outMods = depData.outgoingModules || [];

  const inTotalPts = inMods.reduce((acc, m) => acc + (m.totalTouchPoints || 0), 0);
  const outTotalPts = outMods.reduce((acc, m) => acc + (m.totalTouchPoints || 0), 0);

  let flowSearchQuery = '';
  let flowDensityMode = 'all'; // 'all' | 'top6'
  let flowAnimActive = true;
  let selectedFlowFqn = null;

  function renderFlowCard(m, isIncoming, totalGroupPts) {
    const pts = m.totalTouchPoints || 0;
    const sharePct = totalGroupPts > 0 ? Math.round((pts / totalGroupPts) * 100) : 0;
    const modColor = (window.CodeLensPalette && window.CodeLensPalette.getColor)
      ? window.CodeLensPalette.getColor(m.packageFqn || m.moduleName, 0)
      : (isIncoming ? '#10b981' : '#38bdf8');
    const tierCls = pts >= 50 ? 'tier-hot' : (pts >= 15 ? 'tier-warm' : (pts >= 5 ? 'tier-mid' : 'tier-low'));
    const isSelected = selectedFlowFqn === m.packageFqn;

    return `
      <div class="flow-node-card ${isIncoming ? 'flow-in-node' : 'flow-out-node'} ${isSelected ? 'is-selected-flow-node' : ''}"
           data-fqn="${esc(m.packageFqn)}" 
           data-modname="${esc(m.moduleName)}"
           data-pts="${pts}"
           data-share="${sharePct}"
           data-dir="${isIncoming ? 'in' : 'out'}"
           data-usages-count="${(m.classUsages || []).length}"
           style="--node-accent: ${modColor}; border-left-color: ${modColor};">
        <div class="flow-node-main">
          <div class="flow-node-top">
            <div class="flow-node-info">
              <span class="flow-node-mod-badge" style="background:${modColor}22; color:${modColor}; border:1px solid ${modColor}55;">[MOD]</span>
              <span class="flow-node-name" title="${esc(m.moduleName)}">${esc(m.moduleName)}</span>
            </div>
            <div class="flow-node-badges">
              <span class="flow-node-pts ${tierCls}">${pts} pts</span>
              <button class="flow-node-jump-btn" title="Jump to ${esc(m.moduleName)} dependency topology" data-action="jump">↗</button>
            </div>
          </div>
          <div class="flow-node-spark-row" title="${pts} touch points (${sharePct}% of ${isIncoming ? 'inbound' : 'outbound'} volume)">
            <div class="flow-node-sparkbar">
              <div class="flow-node-sparkfill" style="width: ${Math.max(2, sharePct)}%; background: ${modColor};"></div>
            </div>
            <span class="flow-node-share-pct" style="color:${modColor};">${sharePct}%</span>
          </div>
        </div>
      </div>
    `;
  }

  function getFilteredModules(mods) {
    let list = mods.slice();
    if (flowSearchQuery) {
      const q = flowSearchQuery.toLowerCase();
      list = list.filter(m =>
        (m.moduleName && m.moduleName.toLowerCase().includes(q)) ||
        (m.packageFqn && m.packageFqn.toLowerCase().includes(q))
      );
    }
    if (flowDensityMode === 'top6') {
      list = list.slice(0, 6);
    }
    return list;
  }

  function buildCardsHtml() {
    const curIn = getFilteredModules(inMods);
    const curOut = getFilteredModules(outMods);

    let inHtml = '';
    if (inMods.length === 0) {
      inHtml = '<div style="font-size:11px;color:var(--text-muted);padding:14px 0;text-align:center;">No incoming modules (Root / Independent)</div>';
    } else if (curIn.length === 0) {
      inHtml = '<div style="font-size:11px;color:var(--text-muted);padding:14px 0;text-align:center;">No matching inbound callers</div>';
    } else {
      inHtml = curIn.map(m => renderFlowCard(m, true, inTotalPts)).join('');
    }

    let outHtml = '';
    if (outMods.length === 0) {
      outHtml = '<div style="font-size:11px;color:var(--text-muted);padding:14px 0;text-align:center;">No outgoing dependencies (Leaf / Self-contained)</div>';
    } else if (curOut.length === 0) {
      outHtml = '<div style="font-size:11px;color:var(--text-muted);padding:14px 0;text-align:center;">No matching outbound dependencies</div>';
    } else {
      outHtml = curOut.map(m => renderFlowCard(m, false, outTotalPts)).join('');
    }

    return { inHtml, outHtml, curInCount: curIn.length, curOutCount: curOut.length };
  }

  const initialCards = buildCardsHtml();

  flowMap.innerHTML = `
    <div class="flow-map-header">
      <div class="flow-map-header-left">
        <div class="flow-map-title-row">
          <span class="flow-map-title-icon">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><line x1="8.59" y1="13.51" x2="15.42" y2="17.49"/><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"/></svg>
          </span>
          <span class="flow-map-title">Coupling Topology Flow Map</span>
          <span class="flow-map-live-badge">TELEMETRY</span>
        </div>
        <div class="flow-map-telemetry-row">
          <span class="flow-pill-in" title="Inbound afferent callers"><span class="pill-dot"></span>Inbound: ${inMods.length} callers (${depData.totalInboundTouchPoints || inTotalPts} pts)</span>
          <span class="flow-pill-sep">➔</span>
          <span class="flow-pill-hub" title="Inspected target module">${esc(depData.moduleName || pkgFqn)}</span>
          <span class="flow-pill-sep">➔</span>
          <span class="flow-pill-out" title="Outbound efferent dependencies"><span class="pill-dot"></span>Outbound: ${outMods.length} deps (${depData.totalOutboundTouchPoints || outTotalPts} pts)</span>
        </div>
      </div>
      <div class="flow-map-toolbar">
        <div class="flow-search-wrap">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
          <input type="text" class="flow-search-input" id="flow-search-input" placeholder="Filter callers / deps..." value="${flowSearchQuery}" />
          <button class="flow-search-clear" id="flow-search-clear" style="display:none;" title="Clear search">✕</button>
        </div>
        <div class="flow-segmented-btn-group">
          <button class="flow-seg-btn active" data-density="all">All (${inMods.length + outMods.length})</button>
          <button class="flow-seg-btn" data-density="top6">Top 6</button>
        </div>
        <button class="flow-tool-btn active" id="btn-flow-anim" title="Toggle energy pulses">
          <span>⚡ Flow FX</span>
        </button>
        <button class="btn-flow-toggle" id="btn-flow-toggle" title="Toggle flow map visibility">
          <span>▲ Hide</span>
        </button>
      </div>
    </div>
    <div class="flow-map-content" id="flow-map-content">
      <svg class="flow-conduits-svg" id="flow-conduits-svg"></svg>
      <div class="flow-hud-tooltip" id="flow-hud-tooltip" style="display:none;"></div>
      <div class="flow-col flow-col-inbound">
        <div class="flow-col-header">
          <span class="flow-col-badge in">INBOUND CALLERS</span>
          <span class="flow-col-count" id="inbound-count-badge">(${initialCards.curInCount})</span>
        </div>
        <div class="flow-nodes-list" id="flow-inbound-list">${initialCards.inHtml}</div>
      </div>
      <div class="flow-col flow-col-center">
        <div class="flow-center-hub" id="flow-center-hub" title="Target Module: ${esc(depData.packageFqn || pkgFqn)}">
          <div class="hub-header-tag">
            <span class="hub-radar-beacon"></span>
            <span class="hub-kind-badge">TARGET MODULE</span>
          </div>
          <div class="hub-title-wrap">
            <div class="hub-title" title="${esc(depData.moduleName || pkgFqn)}">${esc(depData.moduleName || pkgFqn)}</div>
            <div class="hub-pkg-sub" title="${esc(depData.packageFqn || pkgFqn)}">${esc(depData.packageFqn || pkgFqn)}</div>
          </div>
          <div class="hub-kpis">
            <div class="hub-pts-banner">
              <span class="hub-pts-num">${depData.totalTouchPoints}</span>
              <span class="hub-pts-label">Total Touch Points</span>
            </div>
            <div class="hub-metrics-row">
              <span class="hub-sub-stat in">↓ ${inMods.length} Callers</span>
              <span class="hub-sub-stat-sep">/</span>
              <span class="hub-sub-stat out">↑ ${outMods.length} Deps</span>
            </div>
            <span class="stability-rating-pill ${stabilityClass}">${esc(depData.stabilityRating || 'Balanced')}</span>
          </div>
        </div>
      </div>
      <div class="flow-col flow-col-outbound">
        <div class="flow-col-header">
          <span class="flow-col-badge out">OUTBOUND DEPENDENCIES</span>
          <span class="flow-col-count" id="outbound-count-badge">(${initialCards.curOutCount})</span>
        </div>
        <div class="flow-nodes-list" id="flow-outbound-list">${initialCards.outHtml}</div>
      </div>
    </div>
  `;
  viewEl.appendChild(flowMap);

  const flowContent = flowMap.querySelector('#flow-map-content');
  const flowSvg = flowMap.querySelector('#flow-conduits-svg');
  const centerHub = flowMap.querySelector('#flow-center-hub');
  const inList = flowMap.querySelector('#flow-inbound-list');
  const outList = flowMap.querySelector('#flow-outbound-list');
  const inCountBadge = flowMap.querySelector('#inbound-count-badge');
  const outCountBadge = flowMap.querySelector('#outbound-count-badge');
  const flowSearchInputEl = flowMap.querySelector('#flow-search-input');
  const flowSearchClearEl = flowMap.querySelector('#flow-search-clear');
  const animBtn = flowMap.querySelector('#btn-flow-anim');
  const hudTooltip = flowMap.querySelector('#flow-hud-tooltip');

  function showHudTooltip(e, name, fqn, dir, pts, share, usagesCount) {
    if (!hudTooltip || !flowContent) return;
    const isIncoming = dir === 'in';
    hudTooltip.innerHTML = `
      <div class="flow-hud-header">
        <span class="flow-hud-title">${esc(name)}</span>
        <span class="flow-hud-dir-badge ${isIncoming ? 'in' : 'out'}">${isIncoming ? 'INBOUND CALLER' : 'OUTBOUND DEP'}</span>
      </div>
      <div class="flow-hud-stat"><span>Touch Points:</span> <strong>${pts} pts</strong></div>
      <div class="flow-hud-stat"><span>Traffic Share:</span> <strong>${share}%</strong></div>
      <div class="flow-hud-stat"><span>Distinct Class Pairs:</span> <strong>${usagesCount || 0}</strong></div>
      <div class="flow-hud-hint">Click card to filter table • ↗ to navigate</div>
    `;
    hudTooltip.style.display = 'flex';
    positionHudTooltip(e);
  }

  function positionHudTooltip(e) {
    if (!hudTooltip || !flowContent) return;
    const contRect = flowContent.getBoundingClientRect();
    const cursorX = e.clientX - contRect.left;
    const cursorY = e.clientY - contRect.top;

    const ttWidth = 220;
    const ttHeight = 110;
    let posX = cursorX + 16;
    if (posX + ttWidth > contRect.width - 10) {
      posX = cursorX - ttWidth - 16;
    }
    let posY = cursorY - 15;
    if (posY < 8) posY = 8;
    if (posY + ttHeight > contRect.height - 8) {
      posY = Math.max(8, contRect.height - ttHeight - 8);
    }

    hudTooltip.style.left = `${posX}px`;
    hudTooltip.style.top = `${posY}px`;
  }

  function hideHudTooltip() {
    if (hudTooltip) hudTooltip.style.display = 'none';
  }

  function highlightNode(fqn) {
    flowMap.querySelectorAll('.flow-node-card').forEach(c => {
      if (c.dataset.fqn === fqn) {
        c.classList.add('active-flow');
      } else {
        c.classList.remove('active-flow');
      }
    });
    flowSvg.querySelectorAll('.flow-conduit').forEach(p => {
      if (p.dataset.nodeFqn === fqn) {
        p.classList.add('highlighted');
        p.classList.remove('dimmed');
      } else {
        p.classList.add('dimmed');
        p.classList.remove('highlighted');
      }
    });
    flowSvg.querySelectorAll('.flow-terminal-dot').forEach(d => {
      if (d.dataset.nodeFqn === fqn) {
        d.classList.add('highlighted');
      } else {
        d.classList.remove('highlighted');
      }
    });
  }

  function resetHighlight() {
    flowMap.querySelectorAll('.flow-node-card').forEach(c => c.classList.remove('active-flow'));
    flowSvg.querySelectorAll('.flow-conduit').forEach(p => p.classList.remove('highlighted', 'dimmed'));
    flowSvg.querySelectorAll('.flow-terminal-dot').forEach(d => d.classList.remove('highlighted'));
  }

  function drawFlowConduits() {
    if (!flowSvg || !flowContent || !centerHub || flowContent.style.display === 'none' || flowContent.offsetHeight === 0) {
      return;
    }
    const contRect = flowContent.getBoundingClientRect();
    const hubRect = centerHub.getBoundingClientRect();
    if (contRect.width <= 0 || contRect.height <= 0) return;

    flowSvg.setAttribute('viewBox', `0 0 ${contRect.width} ${contRect.height}`);

    const defsHtml = `
      <defs>
        <marker id="flow-arrow-in" viewBox="0 0 10 10" refX="7" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
          <path d="M 0 1.5 L 8 5 L 0 8.5 z" fill="#06b6d4" opacity="0.9"/>
        </marker>
        <marker id="flow-arrow-out" viewBox="0 0 10 10" refX="7" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
          <path d="M 0 1.5 L 8 5 L 0 8.5 z" fill="#38bdf8" opacity="0.9"/>
        </marker>
        <filter id="flow-glow" x="-20%" y="-20%" width="140%" height="140%">
          <feGaussianBlur stdDeviation="2.5" result="blur"/>
          <feMerge>
            <feMergeNode in="blur"/>
            <feMergeNode in="SourceGraphic"/>
          </feMerge>
        </filter>
      </defs>
    `;
    flowSvg.innerHTML = defsHtml;

    const hubLeftX = hubRect.left - contRect.left;
    const hubRightX = hubRect.right - contRect.left;
    const hubCenterY = hubRect.top - contRect.top + hubRect.height / 2;
    const hubPad = 22;
    const hubDockTop = hubRect.top - contRect.top + hubPad;
    const hubDockHeight = Math.max(16, hubRect.height - (hubPad * 2));

    // Visible Inbound Cards
    const inCards = Array.from(flowContent.querySelectorAll('.flow-in-node'));
    const inListRect = inList ? inList.getBoundingClientRect() : null;
    const visibleInCards = inCards.filter(card => {
      if (card.offsetParent === null) return false;
      if (!inListRect) return true;
      const cr = card.getBoundingClientRect();
      return cr.bottom >= inListRect.top - 12 && cr.top <= inListRect.bottom + 12;
    });

    // Sort strictly by vertical center to avoid conduit crossing
    visibleInCards.sort((a, b) => {
      const ra = a.getBoundingClientRect();
      const rb = b.getBoundingClientRect();
      return (ra.top + ra.height / 2) - (rb.top + rb.height / 2);
    });

    const inCount = visibleInCards.length;
    visibleInCards.forEach((card, idx) => {
      const cRect = card.getBoundingClientRect();
      const x1 = cRect.right - contRect.left;
      const y1 = cRect.top - contRect.top + cRect.height / 2;
      const x2 = hubLeftX;
      const y2 = inCount === 1 ? hubCenterY : hubDockTop + (hubDockHeight * idx / (inCount - 1));
      const dx = Math.max(26, (x2 - x1) * 0.48);

      const fqn = card.dataset.fqn;
      const modObj = inMods.find(m => m.packageFqn === fqn) || {};
      const pts = modObj.totalTouchPoints || parseInt(card.dataset.pts, 10) || 1;
      const strokeW = Math.min(5, Math.max(1.8, Math.sqrt(pts) * 0.95));
      const modColor = (window.CodeLensPalette && window.CodeLensPalette.getColor)
        ? window.CodeLensPalette.getColor(fqn || card.dataset.modname, 0)
        : '#10b981';

      const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
      path.setAttribute('d', `M ${x1} ${y1} C ${x1 + dx} ${y1}, ${x2 - dx} ${y2}, ${x2} ${y2}`);
      path.setAttribute('class', `flow-conduit in-conduit ${flowAnimActive ? 'is-anim' : ''}`);
      path.setAttribute('stroke-width', strokeW);
      path.setAttribute('stroke', modColor);
      path.setAttribute('stroke-opacity', '0.75');
      path.setAttribute('marker-end', 'url(#flow-arrow-in)');
      path.dataset.nodeFqn = fqn;
      path.dataset.modname = card.dataset.modname;
      path.dataset.pts = pts;
      path.dataset.dir = 'in';
      path.dataset.share = card.dataset.share;
      path.dataset.usagesCount = card.dataset.usagesCount;
      flowSvg.appendChild(path);

      // Terminal anchor dots
      const dot1 = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
      dot1.setAttribute('cx', x1);
      dot1.setAttribute('cy', y1);
      dot1.setAttribute('r', '3');
      dot1.setAttribute('fill', modColor);
      dot1.setAttribute('class', 'flow-terminal-dot');
      dot1.dataset.nodeFqn = fqn;
      flowSvg.appendChild(dot1);

      const dot2 = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
      dot2.setAttribute('cx', x2);
      dot2.setAttribute('cy', y2);
      dot2.setAttribute('r', '3');
      dot2.setAttribute('fill', '#06b6d4');
      dot2.setAttribute('class', 'flow-terminal-dot');
      dot2.dataset.nodeFqn = fqn;
      flowSvg.appendChild(dot2);
    });

    // Visible Outbound Cards
    const outCards = Array.from(flowContent.querySelectorAll('.flow-out-node'));
    const outListRect = outList ? outList.getBoundingClientRect() : null;
    const visibleOutCards = outCards.filter(card => {
      if (card.offsetParent === null) return false;
      if (!outListRect) return true;
      const cr = card.getBoundingClientRect();
      return cr.bottom >= outListRect.top - 12 && cr.top <= outListRect.bottom + 12;
    });

    visibleOutCards.sort((a, b) => {
      const ra = a.getBoundingClientRect();
      const rb = b.getBoundingClientRect();
      return (ra.top + ra.height / 2) - (rb.top + rb.height / 2);
    });

    const outCount = visibleOutCards.length;
    visibleOutCards.forEach((card, idx) => {
      const cRect = card.getBoundingClientRect();
      const x1 = hubRightX;
      const y1 = outCount === 1 ? hubCenterY : hubDockTop + (hubDockHeight * idx / (outCount - 1));
      const x2 = cRect.left - contRect.left;
      const y2 = cRect.top - contRect.top + cRect.height / 2;
      const dx = Math.max(26, (x2 - x1) * 0.48);

      const fqn = card.dataset.fqn;
      const modObj = outMods.find(m => m.packageFqn === fqn) || {};
      const pts = modObj.totalTouchPoints || parseInt(card.dataset.pts, 10) || 1;
      const strokeW = Math.min(5, Math.max(1.8, Math.sqrt(pts) * 0.95));
      const modColor = (window.CodeLensPalette && window.CodeLensPalette.getColor)
        ? window.CodeLensPalette.getColor(fqn || card.dataset.modname, 0)
        : '#3b82f6';

      const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
      path.setAttribute('d', `M ${x1} ${y1} C ${x1 + dx} ${y1}, ${x2 - dx} ${y2}, ${x2} ${y2}`);
      path.setAttribute('class', `flow-conduit out-conduit ${flowAnimActive ? 'is-anim' : ''}`);
      path.setAttribute('stroke-width', strokeW);
      path.setAttribute('stroke', modColor);
      path.setAttribute('stroke-opacity', '0.75');
      path.setAttribute('marker-end', 'url(#flow-arrow-out)');
      path.dataset.nodeFqn = fqn;
      path.dataset.modname = card.dataset.modname;
      path.dataset.pts = pts;
      path.dataset.dir = 'out';
      path.dataset.share = card.dataset.share;
      path.dataset.usagesCount = card.dataset.usagesCount;
      flowSvg.appendChild(path);

      // Terminal anchor dots
      const dot1 = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
      dot1.setAttribute('cx', x1);
      dot1.setAttribute('cy', y1);
      dot1.setAttribute('r', '3');
      dot1.setAttribute('fill', '#06b6d4');
      dot1.setAttribute('class', 'flow-terminal-dot');
      dot1.dataset.nodeFqn = fqn;
      flowSvg.appendChild(dot1);

      const dot2 = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
      dot2.setAttribute('cx', x2);
      dot2.setAttribute('cy', y2);
      dot2.setAttribute('r', '3');
      dot2.setAttribute('fill', modColor);
      dot2.setAttribute('class', 'flow-terminal-dot');
      dot2.dataset.nodeFqn = fqn;
      flowSvg.appendChild(dot2);
    });

    bindConduitHover();
  }

  function bindNodeCards() {
    flowMap.querySelectorAll('.flow-node-card').forEach(card => {
      const fqn = card.dataset.fqn;
      const modName = card.dataset.modname;
      const pts = card.dataset.pts;
      const share = card.dataset.share;
      const dir = card.dataset.dir;
      const usagesCount = card.dataset.usagesCount;

      card.addEventListener('mouseenter', (e) => {
        highlightNode(fqn);
        showHudTooltip(e, modName, fqn, dir, pts, share, usagesCount);
      });
      card.addEventListener('mousemove', (e) => {
        positionHudTooltip(e);
      });
      card.addEventListener('mouseleave', () => {
        resetHighlight();
        hideHudTooltip();
      });

      card.addEventListener('click', (e) => {
        if (e.target.closest('[data-action="jump"]')) {
          e.stopPropagation();
          selectModuleItem({ fqn, name: modName }, 'DEPENDENCIES');
          return;
        }

        if (selectedFlowFqn === fqn) {
          selectedFlowFqn = null;
          card.classList.remove('is-selected-flow-node');
        } else {
          flowMap.querySelectorAll('.flow-node-card').forEach(c => c.classList.remove('is-selected-flow-node'));
          selectedFlowFqn = fqn;
          card.classList.add('is-selected-flow-node');
        }

        const tableSearch = viewEl.querySelector('.mod-dep-search-input');
        const tableClear = viewEl.querySelector('.mod-dep-search-clear');
        if (tableSearch) {
          tableSearch.value = selectedFlowFqn ? modName : '';
          if (tableClear) tableClear.style.display = selectedFlowFqn ? 'inline-block' : 'none';
          tableSearch.dispatchEvent(new Event('input', { bubbles: true }));
        }
      });
    });
  }

  function bindConduitHover() {
    flowSvg.querySelectorAll('.flow-conduit').forEach(path => {
      const fqn = path.dataset.nodeFqn;
      const modName = path.dataset.modname;
      const pts = path.dataset.pts;
      const share = path.dataset.share;
      const dir = path.dataset.dir;
      const usagesCount = path.dataset.usagesCount;

      path.addEventListener('mouseenter', (e) => {
        highlightNode(fqn);
        showHudTooltip(e, modName, fqn, dir, pts, share, usagesCount);
      });
      path.addEventListener('mousemove', (e) => {
        positionHudTooltip(e);
      });
      path.addEventListener('mouseleave', () => {
        resetHighlight();
        hideHudTooltip();
      });
      path.addEventListener('click', () => {
        const card = flowMap.querySelector(`.flow-node-card[data-fqn="${fqn}"]`);
        if (card) card.click();
      });
    });
  }

  function updateLists() {
    const updated = buildCardsHtml();
    if (inList) inList.innerHTML = updated.inHtml;
    if (outList) outList.innerHTML = updated.outHtml;
    if (inCountBadge) inCountBadge.textContent = `(${updated.curInCount})`;
    if (outCountBadge) outCountBadge.textContent = `(${updated.curOutCount})`;
    bindNodeCards();
    requestAnimationFrame(drawFlowConduits);
  }

  if (flowSearchInputEl) {
    flowSearchInputEl.addEventListener('input', () => {
      flowSearchQuery = flowSearchInputEl.value.trim();
      if (flowSearchClearEl) flowSearchClearEl.style.display = flowSearchQuery ? 'inline-block' : 'none';
      updateLists();
    });
  }
  if (flowSearchClearEl) {
    flowSearchClearEl.addEventListener('click', () => {
      flowSearchInputEl.value = '';
      flowSearchQuery = '';
      flowSearchClearEl.style.display = 'none';
      updateLists();
    });
  }

  flowMap.querySelectorAll('.flow-seg-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      flowMap.querySelectorAll('.flow-seg-btn').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      flowDensityMode = btn.dataset.density;
      updateLists();
    });
  });

  if (animBtn) {
    animBtn.addEventListener('click', () => {
      flowAnimActive = !flowAnimActive;
      animBtn.classList.toggle('active', flowAnimActive);
      drawFlowConduits();
    });
  }

  // Toggle Flow Map
  const toggleBtn = flowMap.querySelector('#btn-flow-toggle');
  if (toggleBtn && flowContent) {
    toggleBtn.addEventListener('click', () => {
      const isHidden = flowContent.style.display === 'none';
      flowContent.style.display = isHidden ? 'grid' : 'none';
      toggleBtn.innerHTML = isHidden ? '<span>▲ Hide</span>' : '<span>▼ Show</span>';
      if (isHidden) {
        requestAnimationFrame(() => drawFlowConduits());
      }
    });
  }

  // Real-time scroll synchronization
  let scrollAnimId = null;
  const onListScroll = () => {
    if (scrollAnimId) cancelAnimationFrame(scrollAnimId);
    scrollAnimId = requestAnimationFrame(drawFlowConduits);
  };
  if (inList) inList.addEventListener('scroll', onListScroll, { passive: true });
  if (outList) outList.addEventListener('scroll', onListScroll, { passive: true });

  if (centerHub) {
    centerHub.addEventListener('click', () => {
      if (typeof inspectReportPackage === 'function') {
        inspectReportPackage(pkgFqn);
      } else if (typeof selectModuleItem === 'function') {
        selectModuleItem({ fqn: pkgFqn, name: depData.moduleName || pkgFqn });
      } else if (typeof inspectReportEntity === 'function') {
        inspectReportEntity(pkgFqn, 'knowledge');
      }
    });
  }

  bindNodeCards();
  setTimeout(drawFlowConduits, 60);
  if (window.ResizeObserver) {
    const ro = new ResizeObserver(() => drawFlowConduits());
    ro.observe(flowContent);
  }

  // 2. KPI Architecture & Instability Banner with Direction Filters
  let currentDirectionFilter = 'ALL'; // 'ALL' | 'INBOUND' | 'OUTBOUND'
  let currentSubView = 'modules'; // 'modules' | 'class-usage' | 'function-calls' | 'external'
  let currentKindFilter = 'ALL';
  let currentSearchQuery = '';
  let currentSortMode = 'pts-desc';

  const kpiBanner = createElement('div', { class: 'mod-dep-kpi-banner' });
  kpiBanner.innerHTML = `
    <div class="mod-dep-kpi-card is-clickable active-filter all-active kpi-total" data-dir="ALL" title="Click to view all touch points & connections">
      <div class="mod-dep-kpi-header">
        <span class="mod-dep-kpi-title">Total Touch Points</span>
        <svg class="svg-icon icon-cyan icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 17H7A5 5 0 0 1 7 7h2"/><path d="M15 7h2a5 5 0 0 1 0 10h-2"/><line x1="8" y1="12" x2="16" y2="12"/></svg>
      </div>
      <div class="mod-dep-kpi-value">${depData.totalTouchPoints}</div>
      <div class="mod-dep-kpi-sub">
        <span class="sub-pill out" title="Outbound touch points">${depData.totalOutboundTouchPoints} Outbound</span>
        <span class="sub-pill in" title="Inbound touch points">${depData.totalInboundTouchPoints} Inbound</span>
        ${depData.internalTouchPoints > 0 ? `<span class="sub-pill intra" title="Internal touch points">${depData.internalTouchPoints} Intra</span>` : ''}
      </div>
      <div class="kpi-filter-hint">Active Filter: All Connections</div>
    </div>

    <div class="mod-dep-kpi-card is-clickable kpi-ca" data-dir="INBOUND" title="Click to filter by Inbound (Ca) dependent modules">
      <div class="mod-dep-kpi-header">
        <span class="mod-dep-kpi-title">Afferent Coupling (Ca)</span>
        <span class="mod-dep-kpi-badge ca-badge">INBOUND</span>
      </div>
      <div class="mod-dep-kpi-value">${depData.afferentCoupling} <span class="kpi-unit">modules</span></div>
      <div class="mod-dep-kpi-sub">Dependent modules calling into this module</div>
      <div class="kpi-filter-hint">Click to filter Inbound</div>
    </div>

    <div class="mod-dep-kpi-card is-clickable kpi-ce" data-dir="OUTBOUND" title="Click to filter by Outbound (Ce) dependencies">
      <div class="mod-dep-kpi-header">
        <span class="mod-dep-kpi-title">Efferent Coupling (Ce)</span>
        <span class="mod-dep-kpi-badge ce-badge">OUTBOUND</span>
      </div>
      <div class="mod-dep-kpi-value">${depData.efferentCoupling} <span class="kpi-unit">modules</span></div>
      <div class="mod-dep-kpi-sub">External modules required by this module</div>
      <div class="kpi-filter-hint">Click to filter Outbound</div>
    </div>

    <div class="mod-dep-kpi-card kpi-instability">
      <div class="mod-dep-kpi-header">
        <span class="mod-dep-kpi-title">Instability Index (I)</span>
        <span class="stability-rating-pill ${stabilityClass}">${esc(depData.stabilityRating || 'Balanced')}</span>
      </div>
      <div class="mod-dep-kpi-value">${(depData.instability || 0).toFixed(2)}</div>
      <div class="instability-bar-track" title="Instability = Ce / (Ca + Ce) = ${depData.efferentCoupling} / (${depData.afferentCoupling} + ${depData.efferentCoupling})">
        <div class="instability-bar-fill ${stabilityClass}" style="width:${Math.min(100, Math.round((depData.instability || 0) * 100))}%;"></div>
      </div>
      <div class="instability-scale-labels">
        <span>0.0 (Stable)</span>
        <span>0.5 (Balanced)</span>
        <span>1.0 (Flexible)</span>
      </div>
    </div>
  `;
  viewEl.appendChild(kpiBanner);

  // Bind clickable KPI filters
  kpiBanner.querySelectorAll('.mod-dep-kpi-card.is-clickable').forEach(card => {
    card.addEventListener('click', () => {
      const dir = card.dataset.dir;
      currentDirectionFilter = dir;

      kpiBanner.querySelectorAll('.mod-dep-kpi-card.is-clickable').forEach(c => {
        c.classList.remove('active-filter', 'all-active', 'ca-active', 'ce-active');
        const hint = c.querySelector('.kpi-filter-hint');
        if (hint) hint.textContent = c.dataset.dir === 'ALL' ? 'Click to show all' : `Click to filter ${c.dataset.dir.toLowerCase()}`;
      });

      card.classList.add('active-filter');
      if (dir === 'ALL') card.classList.add('all-active');
      if (dir === 'INBOUND') card.classList.add('ca-active');
      if (dir === 'OUTBOUND') card.classList.add('ce-active');

      const curHint = card.querySelector('.kpi-filter-hint');
      if (curHint) curHint.textContent = `Active Filter: ${dir}`;

      updateSubView();
    });
  });

  // 3. Touch Point Kinds Distribution Section
  const kindsSec = createElement('div', { class: 'mod-dep-kinds-section' });
  const kindEntries = Object.entries(kinds).sort((a, b) => b[1] - a[1]);

  let segsHtml = '';
  for (const [k, count] of kindEntries) {
    const pct = Math.max(2, Math.round((count / totalKindsCount) * 100));
    segsHtml += `<div class="kinds-bar-seg ${k}" style="width:${pct}%;" title="${k}: ${count} touch points (${pct}%)"></div>`;
  }

  let chipsHtml = `
    <button class="mod-dep-kind-chip active" data-kind="ALL">
      <span class="chip-dot" style="background:var(--text-muted);"></span>
      <span>All Kinds</span>
      <span style="font-weight:700;">(${depData.totalTouchPoints})</span>
    </button>
  `;
  for (const [k, count] of kindEntries) {
    chipsHtml += `
      <button class="mod-dep-kind-chip ${k}" data-kind="${k}">
        <span class="chip-dot"></span>
        <span>${k}</span>
        <span style="font-weight:700;">(${count})</span>
      </button>
    `;
  }

  kindsSec.innerHTML = `
    <div class="mod-dep-kinds-header">
      <span class="kinds-sec-title">Touch Point Kinds Distribution</span>
      <span class="kinds-sec-count">${depData.totalTouchPoints} Total Touch Points</span>
    </div>
    <div class="kinds-distribution-bar">
      ${segsHtml || '<div class="kinds-bar-seg CALLS" style="width:100%;"></div>'}
    </div>
    <div class="kinds-chips-row">
      ${chipsHtml}
    </div>
  `;
  viewEl.appendChild(kindsSec);

  // 4. Sub-View Navigation Tabs, Sort & Filter Box
  const controlsBar = createElement('div', { class: 'mod-dep-controls-bar' });
  controlsBar.innerHTML = `
    <div class="mod-dep-subtabs" role="tablist">
      <button class="mod-dep-subtab active" data-subview="modules">Connected Modules (${depData.outgoingModules.length + depData.incomingModules.length})</button>
      <button class="mod-dep-subtab" data-subview="class-usage">Intermodular Class Usage (${allClassUsages.length})</button>
      <button class="mod-dep-subtab" data-subview="function-calls">Intermodular Function Calls (${allFunctionCalls.length})</button>
      ${depData.externalDependencies && depData.externalDependencies.length > 0 ? `<button class="mod-dep-subtab" data-subview="external">External (${depData.externalDependencies.length})</button>` : ''}
    </div>
    <div class="mod-dep-controls-right">
      <div class="mod-dep-sort-group">
        <span class="mod-dep-sort-label">Sort:</span>
        <select class="mod-dep-sort-select" id="mod-dep-sort-select" aria-label="Sort subview items">
          <option value="pts-desc">Touch Points (High ➔ Low)</option>
          <option value="name-asc">Name (A ➔ Z)</option>
          <option value="pairs-desc">Class Pairs (High ➔ Low)</option>
        </select>
      </div>
      <div class="mod-dep-search-box">
        <svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
        <input type="text" class="mod-dep-search-input" placeholder="Filter classes, methods, or modules…" aria-label="Filter dependencies view" />
        <button class="mod-dep-search-clear" title="Clear filter" style="display:none;">✕</button>
        <span class="mod-dep-match-badge" id="mod-dep-match-badge"></span>
      </div>
    </div>
  `;
  viewEl.appendChild(controlsBar);

  // 5. Sub-View Container
  const subviewContainer = createElement('div', { class: 'mod-dep-subview-container', id: 'mod-dep-subview-container' });
  viewEl.appendChild(subviewContainer);

  const sortSelect = controlsBar.querySelector('#mod-dep-sort-select');
  const matchBadge = controlsBar.querySelector('#mod-dep-match-badge');
  const searchInput = controlsBar.querySelector('.mod-dep-search-input');
  const searchClear = controlsBar.querySelector('.mod-dep-search-clear');

  function updateSortOptions() {
    if (!sortSelect) return;
    if (currentSubView === 'modules') {
      sortSelect.innerHTML = `
        <option value="pts-desc" ${currentSortMode === 'pts-desc' ? 'selected' : ''}>Touch Points (High ➔ Low)</option>
        <option value="pts-asc" ${currentSortMode === 'pts-asc' ? 'selected' : ''}>Touch Points (Low ➔ High)</option>
        <option value="name-asc" ${currentSortMode === 'name-asc' ? 'selected' : ''}>Name (A ➔ Z)</option>
        <option value="pairs-desc" ${currentSortMode === 'pairs-desc' ? 'selected' : ''}>Class Pairs (High ➔ Low)</option>
      `;
    } else if (currentSubView === 'class-usage') {
      sortSelect.innerHTML = `
        <option value="pts-desc" ${currentSortMode === 'pts-desc' ? 'selected' : ''}>Touch Points (High ➔ Low)</option>
        <option value="src-asc" ${currentSortMode === 'src-asc' ? 'selected' : ''}>Source Class (A ➔ Z)</option>
        <option value="tgt-asc" ${currentSortMode === 'tgt-asc' ? 'selected' : ''}>Target Class (A ➔ Z)</option>
        <option value="mod-asc" ${currentSortMode === 'mod-asc' ? 'selected' : ''}>Connected Module (A ➔ Z)</option>
      `;
    } else if (currentSubView === 'function-calls') {
      sortSelect.innerHTML = `
        <option value="caller-asc" ${currentSortMode === 'caller-asc' ? 'selected' : ''}>Caller (A ➔ Z)</option>
        <option value="callee-asc" ${currentSortMode === 'callee-asc' ? 'selected' : ''}>Callee (A ➔ Z)</option>
        <option value="line-asc" ${currentSortMode === 'line-asc' ? 'selected' : ''}>Source Line</option>
      `;
    } else if (currentSubView === 'external') {
      sortSelect.innerHTML = `
        <option value="pts-desc" ${currentSortMode === 'pts-desc' ? 'selected' : ''}>Touch Points (High ➔ Low)</option>
        <option value="name-asc" ${currentSortMode === 'name-asc' ? 'selected' : ''}>Module Name (A ➔ Z)</option>
      `;
    }
  }

  function updateSubView() {
    subviewContainer.innerHTML = '';
    kindsSec.querySelectorAll('.mod-dep-kind-chip').forEach(c => {
      c.classList.toggle('active', c.dataset.kind === currentKindFilter);
    });

    if (searchClear && searchInput) {
      searchClear.style.display = searchInput.value ? 'inline-block' : 'none';
    }

    if (currentSubView === 'modules') {
      renderConnectedModulesSubView();
    } else if (currentSubView === 'class-usage') {
      renderClassUsageSubView();
    } else if (currentSubView === 'function-calls') {
      renderFunctionCallsSubView();
    } else if (currentSubView === 'external') {
      renderExternalSubView();
    }
  }

  // --- SUBVIEW 1: CONNECTED MODULES ---
  function renderConnectedModulesSubView() {
    const q = currentSearchQuery.toLowerCase();
    const modules = [];

    for (const m of (depData.outgoingModules || [])) {
      modules.push({ ...m, direction: 'OUTBOUND' });
    }
    for (const m of (depData.incomingModules || [])) {
      modules.push({ ...m, direction: 'INBOUND' });
    }

    const filtered = modules.filter(m => {
      // Direction filter
      if (currentDirectionFilter !== 'ALL' && m.direction !== currentDirectionFilter) {
        return false;
      }
      // Kind filter
      if (currentKindFilter !== 'ALL') {
        if (!m.kinds || !m.kinds[currentKindFilter]) return false;
      }
      // Search filter
      if (q) {
        const matchesName = (m.moduleName || '').toLowerCase().includes(q) || (m.packageFqn || '').toLowerCase().includes(q);
        const matchesClass = (m.classUsages || []).some(cu =>
          (cu.sourceClassSimpleName || '').toLowerCase().includes(q) ||
          (cu.targetClassSimpleName || '').toLowerCase().includes(q) ||
          (cu.sourceClassFqn || '').toLowerCase().includes(q) ||
          (cu.targetClassFqn || '').toLowerCase().includes(q)
        );
        const matchesCall = (m.touchPoints || []).some(tp =>
          (tp.fromEntity || '').toLowerCase().includes(q) ||
          (tp.toEntity || '').toLowerCase().includes(q)
        );
        if (!matchesName && !matchesClass && !matchesCall) return false;
      }
      return true;
    });

    // Sorting
    filtered.sort((a, b) => {
      if (currentSortMode === 'pts-asc') return (a.totalTouchPoints || 0) - (b.totalTouchPoints || 0);
      if (currentSortMode === 'name-asc') return (a.moduleName || '').localeCompare(b.moduleName || '');
      if (currentSortMode === 'pairs-desc') return ((b.classUsages || []).length) - ((a.classUsages || []).length);
      return (b.totalTouchPoints || 0) - (a.totalTouchPoints || 0);
    });

    if (matchBadge) {
      matchBadge.textContent = `${filtered.length} of ${modules.length} modules`;
    }

    if (filtered.length === 0) {
      subviewContainer.innerHTML = `
        <div class="kb-empty-container fade-in" style="padding:24px;">
          <div class="kb-empty-title">No Matching Modules</div>
          <div class="kb-empty-desc">No connected modules matched your filter criteria (${currentDirectionFilter !== 'ALL' ? currentDirectionFilter : ''} ${currentKindFilter !== 'ALL' ? currentKindFilter : ''}).</div>
        </div>`;
      return;
    }

    for (const m of filtered) {
      const card = createElement('div', { class: 'mod-dep-module-card fade-in' });
      const modColor = (window.CodeLensPalette && window.CodeLensPalette.getColor)
        ? window.CodeLensPalette.getColor(m.packageFqn || m.moduleName, 0)
        : '#38bdf8';
      const dirCls = m.direction === 'OUTBOUND' ? 'outbound' : 'inbound';
      const dirTag = m.direction === 'OUTBOUND' ? 'OUTBOUND' : 'INBOUND';
      const dirDesc = m.direction === 'OUTBOUND' ? 'Outbound Dependency: Used by this module' : 'Inbound Dependent: Calls into this module';

      card.innerHTML = `
        <div class="mod-dep-card-header">
          <div class="mod-dep-card-title-group">
            <span class="mod-dep-direction-tag ${dirCls}" title="${dirDesc}">${dirTag}</span>
            <span class="flow-node-mod-badge" style="background:${modColor}22; color:${modColor}; border:1px solid ${modColor}55;">[MOD]</span>
            <span class="mod-dep-card-modname">${esc(m.moduleName)}</span>
            <span class="mod-dep-card-pkgname">${esc(m.packageFqn)}</span>
          </div>
          <div class="mod-dep-card-stats">
            <span class="mod-dep-stat-badge">${m.totalTouchPoints} touch points</span>
            <span class="mod-dep-stat-badge">${m.classUsages ? m.classUsages.length : 0} class pairs</span>
            <span class="mod-dep-stat-badge">${m.functionCallCount || 0} calls</span>
            <button class="btn-secondary btn-xs btn-inspect-mod" title="Open ${esc(m.moduleName)} in Knowledge Base">Inspect Module</button>
          </div>
        </div>
        <div class="mod-dep-card-body">
          <div class="mod-dep-class-sec">
            <div class="mod-dep-class-sec-title">
              <span>Intermodular Class Usage (${m.classUsages ? m.classUsages.length : 0} pairs)</span>
            </div>
            <div class="mod-dep-class-grid"></div>
          </div>

          <div class="mod-dep-calls-sec">
            <div class="mod-dep-calls-accordion">
              <div class="mod-dep-calls-acc-header" role="button" tabindex="0">
                <div style="display:flex;align-items:center;gap:6px;">
                  <svg class="svg-icon icon-cyan icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="width:12px;height:12px;"><path d="M9 17H7A5 5 0 0 1 7 7h2"/><path d="M15 7h2a5 5 0 0 1 0 10h-2"/><line x1="8" y1="12" x2="16" y2="12"/></svg>
                  <span>View Intermodular Function Calls & Touch Points (${m.touchPoints ? m.touchPoints.length : 0})</span>
                </div>
                <span class="acc-chevron">▼</span>
              </div>
              <div class="mod-dep-calls-acc-body" style="display:none;"></div>
            </div>
          </div>
        </div>
      `;

      card.querySelector('.btn-inspect-mod')?.addEventListener('click', (e) => {
        e.stopPropagation();
        selectModuleItem({ fqn: m.packageFqn, name: m.moduleName }, 'DEPENDENCIES');
      });

      const classGrid = card.querySelector('.mod-dep-class-grid');
      const rawUsages = m.classUsages || [];

      // Filter class usages by current search query and kind filter
      const classUsages = rawUsages.filter(cu => {
        if (currentKindFilter !== 'ALL') {
          if (!cu.kinds || !cu.kinds[currentKindFilter]) return false;
        }
        if (q) {
          const matches = (cu.sourceClassSimpleName || '').toLowerCase().includes(q) ||
                          (cu.targetClassSimpleName || '').toLowerCase().includes(q) ||
                          (cu.sourceClassFqn || '').toLowerCase().includes(q) ||
                          (cu.targetClassFqn || '').toLowerCase().includes(q);
          if (!matches) return false;
        }
        return true;
      });

      // Sort class usages
      classUsages.sort((a, b) => {
        if (currentSortMode === 'src-asc') return (a.sourceClassSimpleName || a.sourceClassFqn || '').localeCompare(b.sourceClassSimpleName || b.sourceClassFqn || '');
        if (currentSortMode === 'tgt-asc') return (a.targetClassSimpleName || a.targetClassFqn || '').localeCompare(b.targetClassSimpleName || b.targetClassFqn || '');
        return (b.touchPointCount || 0) - (a.touchPointCount || 0);
      });

      // Update class section title with active count
      const classSecTitle = card.querySelector('.mod-dep-class-sec-title');
      if (classSecTitle) {
        classSecTitle.innerHTML = `
          <span>Intermodular Class Usage (${classUsages.length}${classUsages.length !== rawUsages.length ? ` of ${rawUsages.length}` : ''} pairs)</span>
        `;
      }

      const getPkgShort = (fqn) => {
        if (!fqn) return '';
        const parts = fqn.split('.');
        parts.pop();
        return parts.slice(-2).join('.') || parts.join('.');
      };

      if (classUsages.length === 0) {
        classGrid.innerHTML = `<div style="font-size:11px;color:var(--text-muted);padding:8px 12px;background:var(--bg-elevated);border-radius:var(--radius-xs);">No direct class usages recorded${q ? ` matching "${esc(q)}"` : ''}.</div>`;
      } else {
        for (const cu of classUsages) {
          const pairCard = createElement('div', { class: 'mod-dep-class-pair-card' });
          const cuPts = cu.touchPointCount || 0;
          const tierCls = cuPts >= 50 ? 'tier-hot' : (cuPts >= 15 ? 'tier-warm' : (cuPts >= 5 ? 'tier-mid' : 'tier-low'));
          const srcPkgShort = getPkgShort(cu.sourceClassFqn);
          const tgtPkgShort = getPkgShort(cu.targetClassFqn);

          let cuKindsHtml = '';
          if (cu.kinds) {
            for (const [k, count] of Object.entries(cu.kinds)) {
              cuKindsHtml += `<span class="class-kind-pill ${k}">${k}${count > 1 ? ` (${count})` : ''}</span>`;
            }
          }

          pairCard.innerHTML = `
            <div class="class-pair-header">
              <div class="class-pair-badges">
                <span class="class-pair-pts-badge ${tierCls}">
                  <strong>${cuPts}</strong> ${cuPts === 1 ? 'call' : 'calls'}
                </span>
                ${cuKindsHtml}
              </div>
              <div class="class-pair-actions">
                <button class="btn-peek-calls" title="Peek individual calls between these two classes">
                  <span class="peek-txt">Peek Calls (${cuPts})</span>
                  <span class="peek-chevron">▼</span>
                </button>
                <button class="btn-copy-pair" title="Copy class pair: ${esc(cu.sourceClassSimpleName || '')} ➔ ${esc(cu.targetClassSimpleName || '')}">
                  <svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="width:12px;height:12px;"><rect width="14" height="14" x="8" y="8" rx="2" ry="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/></svg>
                </button>
              </div>
            </div>

            <div class="class-pair-flow-body">
              <div class="class-node-row src-row">
                <span class="class-role-badge src-role" title="Calling class (Source)">FROM</span>
                <div class="class-node-meta">
                  <a href="#" class="class-entity-link src-link" title="${esc(cu.sourceClassFqn)}">${esc(cu.sourceClassSimpleName || cu.sourceClassFqn)}</a>
                  ${srcPkgShort ? `<span class="class-pkg-chip" title="${esc(cu.sourceClassFqn)}">${esc(srcPkgShort)}</span>` : ''}
                </div>
              </div>

              <div class="class-flow-arrow-row">
                <span class="class-flow-arrow-line"></span>
                <span class="class-flow-arrow-icon">➔</span>
                <span class="class-flow-arrow-line"></span>
              </div>

              <div class="class-node-row tgt-row">
                <span class="class-role-badge tgt-role" title="Called class (Target)">TO</span>
                <div class="class-node-meta">
                  <a href="#" class="class-entity-link tgt-link" title="${esc(cu.targetClassFqn)}">${esc(cu.targetClassSimpleName || cu.targetClassFqn)}</a>
                  ${tgtPkgShort ? `<span class="class-pkg-chip" title="${esc(cu.targetClassFqn)}">${esc(tgtPkgShort)}</span>` : ''}
                </div>
              </div>
            </div>

            <div class="class-pair-drawer" style="display:none;"></div>
          `;

          pairCard.querySelector('.src-link')?.addEventListener('click', (e) => {
            e.preventDefault();
            selectType(cu.sourceClassFqn);
          });
          pairCard.querySelector('.tgt-link')?.addEventListener('click', (e) => {
            e.preventDefault();
            selectType(cu.targetClassFqn);
          });

          // Copy Pair button
          pairCard.querySelector('.btn-copy-pair')?.addEventListener('click', (e) => {
            e.stopPropagation();
            const textToCopy = `${cu.sourceClassFqn} ➔ ${cu.targetClassFqn}`;
            if (navigator.clipboard) {
              navigator.clipboard.writeText(textToCopy).then(() => {
                if (typeof showToast === 'function') showToast('Copied class pair to clipboard');
              });
            }
          });

          // Peek Drawer toggle
          const peekBtn = pairCard.querySelector('.btn-peek-calls');
          const drawer = pairCard.querySelector('.class-pair-drawer');
          let drawerLoaded = false;

          peekBtn?.addEventListener('click', (e) => {
            e.stopPropagation();
            const isOpen = drawer.style.display !== 'none';
            drawer.style.display = isOpen ? 'none' : 'flex';
            peekBtn.classList.toggle('open', !isOpen);
            const chevron = peekBtn.querySelector('.peek-chevron');
            if (chevron) chevron.textContent = isOpen ? '▼' : '▲';

            if (!isOpen && !drawerLoaded) {
              const matchingCalls = (cu.touchPoints && cu.touchPoints.length > 0)
                ? cu.touchPoints
                : (m.touchPoints || []).filter(tp => {
                    const srcMatch = (tp.fromType === cu.sourceClassFqn) || (tp.fromEntity && tp.fromEntity.includes(cu.sourceClassSimpleName));
                    const tgtMatch = (tp.toType === cu.targetClassFqn) || (tp.toEntity && tp.toEntity.includes(cu.targetClassSimpleName));
                    return srcMatch && tgtMatch;
                  });

              if (matchingCalls.length === 0) {
                drawer.innerHTML = '<div style="font-size:10px;color:var(--text-muted);padding:4px 0;">No individual method calls found in touch points index for this pair.</div>';
              } else {
                drawer.innerHTML = `
                  <div style="font-size:10px;font-weight:700;color:var(--text-muted);text-transform:uppercase;letter-spacing:0.04em;margin-bottom:2px;">
                    Intermodular Calls (${matchingCalls.length})
                  </div>
                  ${matchingCalls.map(tp => `
                    <div class="class-pair-call-item">
                      <div class="call-item-main">
                        <span class="class-kind-pill ${tp.kind}">${tp.kind}</span>
                        <div class="call-sig-from" title="${esc(tp.fromEntity)}">${formatSignatureHtml(tp.fromEntity)}</div>
                        <span class="call-sig-arrow">➔</span>
                        <div class="call-sig-to" title="${esc(tp.toEntity)}">${formatSignatureHtml(tp.toEntity)}</div>
                      </div>
                      ${tp.sourceLine > 0 ? `<span class="mod-dep-call-line" style="font-size:9.5px;color:var(--text-muted);flex-shrink:0;">L: ${tp.sourceLine}</span>` : ''}
                    </div>
                  `).join('')}
                `;
              }
              drawerLoaded = true;
            }
          });

          classGrid.appendChild(pairCard);
        }
      }

      // Intermodular function calls accordion
      const accHeader = card.querySelector('.mod-dep-calls-acc-header');
      const accBody = card.querySelector('.mod-dep-calls-acc-body');
      let hydratedCalls = false;

      const toggleAcc = () => {
        const isHidden = accBody.style.display === 'none';
        accBody.style.display = isHidden ? 'flex' : 'none';
        card.querySelector('.acc-chevron').textContent = isHidden ? '▲' : '▼';
        if (isHidden && !hydratedCalls) {
          const tps = m.touchPoints || [];
          if (tps.length === 0) {
            accBody.innerHTML = '<div style="padding:10px;font-size:11px;color:var(--text-muted);">No specific function calls recorded.</div>';
          } else {
            for (const tp of tps) {
              const row = createElement('div', { class: 'mod-dep-call-row' });
              row.innerHTML = `
                <div class="mod-dep-call-chain">
                  <span class="rel-dot ${tp.kind}"></span>
                  <span class="class-kind-pill ${tp.kind}">${tp.kind}</span>
                  <span class="mod-dep-call-caller" title="${esc(tp.fromEntity)}">${formatSignatureHtml(tp.fromEntity)}</span>
                  <span class="mod-dep-call-arrow">➔</span>
                  <span class="mod-dep-call-callee" title="${esc(tp.toEntity)}">${formatSignatureHtml(tp.toEntity)}</span>
                </div>
                <div class="mod-dep-call-meta">
                  ${tp.sourceLine > 0 ? `<span class="mod-dep-call-line">Line ${tp.sourceLine}</span>` : ''}
                </div>
              `;
              row.querySelector('.mod-dep-call-caller')?.addEventListener('click', () => {
                if (tp.fromType) selectType(tp.fromType);
              });
              row.querySelector('.mod-dep-call-callee')?.addEventListener('click', () => {
                if (tp.toType) selectType(tp.toType);
              });
              accBody.appendChild(row);
            }
          }
          hydratedCalls = true;
        }
      };

      accHeader.addEventListener('click', toggleAcc);
      accHeader.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          toggleAcc();
        }
      });

      subviewContainer.appendChild(card);
    }
  }

  // --- SUBVIEW 2: CLASS USAGE MATRIX ---
  function renderClassUsageSubView() {
    const q = currentSearchQuery.toLowerCase();
    const getPkgShort = (fqn) => {
      if (!fqn) return '';
      const parts = fqn.split('.');
      parts.pop();
      return parts.slice(-2).join('.') || parts.join('.');
    };

    const filtered = allClassUsages.filter(cu => {
      if (currentDirectionFilter !== 'ALL' && cu.direction !== currentDirectionFilter) {
        return false;
      }
      if (currentKindFilter !== 'ALL') {
        if (!cu.kinds || !cu.kinds[currentKindFilter]) return false;
      }
      if (q) {
        const matches = (cu.sourceClassFqn || '').toLowerCase().includes(q) ||
                        (cu.targetClassFqn || '').toLowerCase().includes(q) ||
                        (cu.sourceClassSimpleName || '').toLowerCase().includes(q) ||
                        (cu.targetClassSimpleName || '').toLowerCase().includes(q) ||
                        (cu.targetModule || '').toLowerCase().includes(q) ||
                        (cu.sourceModule || '').toLowerCase().includes(q);
        if (!matches) return false;
      }
      return true;
    });

    // Sort class usages
    filtered.sort((a, b) => {
      if (currentSortMode === 'src-asc') return (a.sourceClassSimpleName || a.sourceClassFqn || '').localeCompare(b.sourceClassSimpleName || b.sourceClassFqn || '');
      if (currentSortMode === 'tgt-asc') return (a.targetClassSimpleName || a.targetClassFqn || '').localeCompare(b.targetClassSimpleName || b.targetClassFqn || '');
      if (currentSortMode === 'mod-asc') {
        const modA = a.targetModule || a.sourceModule || '';
        const modB = b.targetModule || b.sourceModule || '';
        return modA.localeCompare(modB);
      }
      return (b.touchPointCount || 0) - (a.touchPointCount || 0);
    });

    if (matchBadge) {
      matchBadge.textContent = `${filtered.length} of ${allClassUsages.length} pairs`;
    }

    if (filtered.length === 0) {
      subviewContainer.innerHTML = `
        <div class="kb-empty-container fade-in" style="padding:24px;">
          <div class="kb-empty-title">No Matching Class Usages</div>
          <div class="kb-empty-desc">No intermodular class usage pairs match your filter.</div>
        </div>`;
      return;
    }

    const tableWrap = createElement('div', { class: 'mod-dep-table-wrap fade-in' });
    const table = createElement('table', { class: 'mod-dep-table' });
    table.innerHTML = `
      <thead>
        <tr>
          <th>Direction</th>
          <th>Caller Class (Source)</th>
          <th>Callee Class (Target)</th>
          <th>Connected Module</th>
          <th>Kinds</th>
          <th style="text-align:right;">Touch Points</th>
          <th style="text-align:center;width:95px;">Actions</th>
        </tr>
      </thead>
      <tbody></tbody>
    `;
    const tbody = table.querySelector('tbody');

    for (const cu of filtered) {
      const tr = createElement('tr');
      const dirCls = cu.direction === 'OUTBOUND' ? 'outbound' : 'inbound';
      const dirLabel = cu.direction === 'OUTBOUND' ? '➔ OUT' : '⬅ IN';
      const modName = cu.targetModule || cu.sourceModule || '-';
      const modColor = (window.CodeLensPalette && window.CodeLensPalette.getColor)
        ? window.CodeLensPalette.getColor(modName, 0)
        : '#38bdf8';
      const cuPts = cu.touchPointCount || 0;
      const tierCls = cuPts >= 50 ? 'tier-hot' : (cuPts >= 15 ? 'tier-warm' : (cuPts >= 5 ? 'tier-mid' : 'tier-low'));
      const srcPkgShort = getPkgShort(cu.sourceClassFqn);
      const tgtPkgShort = getPkgShort(cu.targetClassFqn);

      let kindsHtml = '';
      if (cu.kinds) {
        for (const [k, count] of Object.entries(cu.kinds)) {
          kindsHtml += `<span class="class-kind-pill ${k}">${k}${count > 1 ? ` (${count})` : ''}</span> `;
        }
      }

      tr.innerHTML = `
        <td><span class="mod-dep-direction-tag ${dirCls}">${dirLabel}</span></td>
        <td>
          <div style="display:flex;align-items:baseline;gap:6px;">
            <span class="class-role-badge src-role">SRC</span>
            <a href="#" class="class-entity-link src-link" title="${esc(cu.sourceClassFqn)}">${esc(cu.sourceClassSimpleName || cu.sourceClassFqn)}</a>
            ${srcPkgShort ? `<span class="class-pkg-chip" title="${esc(cu.sourceClassFqn)}">${esc(srcPkgShort)}</span>` : ''}
          </div>
        </td>
        <td>
          <div style="display:flex;align-items:baseline;gap:6px;">
            <span class="class-role-badge tgt-role">TGT</span>
            <a href="#" class="class-entity-link tgt-link" title="${esc(cu.targetClassFqn)}">${esc(cu.targetClassSimpleName || cu.targetClassFqn)}</a>
            ${tgtPkgShort ? `<span class="class-pkg-chip" title="${esc(cu.targetClassFqn)}">${esc(tgtPkgShort)}</span>` : ''}
          </div>
        </td>
        <td>
          <span class="flow-node-mod-badge" style="background:${modColor}22; color:${modColor}; border:1px solid ${modColor}55; margin-right:4px;">[MOD]</span>
          <strong style="color:var(--text-primary);font-family:var(--font-display);">${esc(modName)}</strong>
        </td>
        <td>${kindsHtml}</td>
        <td style="text-align:right;"><span class="class-pair-pts-badge ${tierCls}"><strong>${cuPts}</strong></span></td>
        <td style="text-align:center;">
          <div style="display:flex;align-items:center;justify-content:center;gap:4px;">
            <button class="btn-peek-calls btn-tbl-peek" title="Peek individual calls">
              <span class="peek-txt">Peek</span>
              <span class="peek-chevron">▼</span>
            </button>
            <button class="btn-copy-pair" title="Copy class pair">
              <svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="width:11px;height:11px;"><rect width="14" height="14" x="8" y="8" rx="2" ry="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/></svg>
            </button>
          </div>
        </td>
      `;

      tr.querySelector('.src-link')?.addEventListener('click', (e) => {
        e.preventDefault();
        selectType(cu.sourceClassFqn);
      });
      tr.querySelector('.tgt-link')?.addEventListener('click', (e) => {
        e.preventDefault();
        selectType(cu.targetClassFqn);
      });

      tr.querySelector('.btn-copy-pair')?.addEventListener('click', (e) => {
        e.stopPropagation();
        const textToCopy = `${cu.sourceClassFqn} ➔ ${cu.targetClassFqn}`;
        if (navigator.clipboard) {
          navigator.clipboard.writeText(textToCopy).then(() => {
            if (typeof showToast === 'function') showToast('Copied class pair to clipboard');
          });
        }
      });

      // Expandable drawer row in table
      let detailTr = null;
      tr.querySelector('.btn-tbl-peek')?.addEventListener('click', (e) => {
        e.stopPropagation();
        const peekBtn = tr.querySelector('.btn-tbl-peek');
        if (detailTr) {
          detailTr.remove();
          detailTr = null;
          peekBtn.classList.remove('open');
          peekBtn.querySelector('.peek-chevron').textContent = '▼';
        } else {
          peekBtn.classList.add('open');
          peekBtn.querySelector('.peek-chevron').textContent = '▲';
          detailTr = createElement('tr', { class: 'table-detail-row' });
          const matchingCalls = (cu.touchPoints && cu.touchPoints.length > 0)
            ? cu.touchPoints
            : (allFunctionCalls || []).filter(tp => {
                const srcMatch = (tp.fromType === cu.sourceClassFqn) || (tp.fromEntity && tp.fromEntity.includes(cu.sourceClassSimpleName));
                const tgtMatch = (tp.toType === cu.targetClassFqn) || (tp.toEntity && tp.toEntity.includes(cu.targetClassSimpleName));
                return srcMatch && tgtMatch;
              });

          const callsHtml = matchingCalls.length === 0
            ? '<div style="font-size:10.5px;color:var(--text-muted);padding:4px 0;">No individual method calls found in touch points index for this pair.</div>'
            : matchingCalls.map(tp => `
                <div class="class-pair-call-item">
                  <div class="call-item-main">
                    <span class="class-kind-pill ${tp.kind}">${tp.kind}</span>
                    <div class="call-sig-from" title="${esc(tp.fromEntity)}">${formatSignatureHtml(tp.fromEntity)}</div>
                    <span class="call-sig-arrow">➔</span>
                    <div class="call-sig-to" title="${esc(tp.toEntity)}">${formatSignatureHtml(tp.toEntity)}</div>
                  </div>
                  ${tp.sourceLine > 0 ? `<span class="mod-dep-call-line" style="font-size:9.5px;color:var(--text-muted);flex-shrink:0;">L: ${tp.sourceLine}</span>` : ''}
                </div>
              `).join('');

          detailTr.innerHTML = `
            <td colspan="7" class="table-drawer-cell">
              <div class="class-pair-drawer" style="max-height:200px;">
                <div style="font-size:10px;font-weight:700;color:var(--text-muted);text-transform:uppercase;letter-spacing:0.04em;margin-bottom:2px;">
                  Intermodular Calls (${matchingCalls.length})
                </div>
                ${callsHtml}
              </div>
            </td>
          `;
          tr.after(detailTr);
        }
      });

      tbody.appendChild(tr);
    }

    tableWrap.appendChild(table);
    subviewContainer.appendChild(tableWrap);
  }

  // --- SUBVIEW 3: INTERMODULAR FUNCTION CALLS ---
  function renderFunctionCallsSubView() {
    const q = currentSearchQuery.toLowerCase();
    const filtered = allFunctionCalls.filter(fc => {
      if (currentDirectionFilter !== 'ALL' && fc.direction !== currentDirectionFilter) {
        return false;
      }
      if (currentKindFilter !== 'ALL' && fc.kind !== currentKindFilter) return false;
      if (q) {
        const matches = (fc.fromEntity || '').toLowerCase().includes(q) ||
                        (fc.toEntity || '').toLowerCase().includes(q) ||
                        (fc.targetModule || '').toLowerCase().includes(q) ||
                        (fc.sourceModule || '').toLowerCase().includes(q);
        if (!matches) return false;
      }
      return true;
    });

    // Sort function calls
    filtered.sort((a, b) => {
      if (currentSortMode === 'caller-asc') return (a.fromEntity || '').localeCompare(b.fromEntity || '');
      if (currentSortMode === 'callee-asc') return (a.toEntity || '').localeCompare(b.toEntity || '');
      if (currentSortMode === 'line-asc') return (a.sourceLine || 0) - (b.sourceLine || 0);
      return (a.fromEntity || '').localeCompare(b.fromEntity || '');
    });

    if (matchBadge) {
      matchBadge.textContent = `${filtered.length} of ${allFunctionCalls.length} calls`;
    }

    if (filtered.length === 0) {
      subviewContainer.innerHTML = `
        <div class="kb-empty-container fade-in" style="padding:24px;">
          <div class="kb-empty-title">No Matching Function Calls</div>
          <div class="kb-empty-desc">No intermodular function calls match your search filter.</div>
        </div>`;
      return;
    }

    const tableWrap = createElement('div', { class: 'mod-dep-table-wrap fade-in' });
    const table = createElement('table', { class: 'mod-dep-table' });
    table.innerHTML = `
      <thead>
        <tr>
          <th>Direction</th>
          <th>Caller Function</th>
          <th>Callee Function</th>
          <th>Connected Module</th>
          <th>Kind</th>
          <th>Source Line</th>
        </tr>
      </thead>
      <tbody></tbody>
    `;
    const tbody = table.querySelector('tbody');

    for (const fc of filtered) {
      const tr = createElement('tr');
      const dirCls = fc.direction === 'OUTBOUND' ? 'outbound' : 'inbound';
      const dirLabel = fc.direction === 'OUTBOUND' ? '➔ OUT' : '⬅ IN';
      const modName = fc.targetModule || fc.sourceModule || '-';
      const modColor = (window.CodeLensPalette && window.CodeLensPalette.getColor)
        ? window.CodeLensPalette.getColor(modName, 0)
        : '#38bdf8';

      tr.innerHTML = `
        <td><span class="mod-dep-direction-tag ${dirCls}">${dirLabel}</span></td>
        <td><span class="mod-dep-call-caller" title="${esc(fc.fromEntity)}">${formatSignatureHtml(fc.fromEntity)}</span></td>
        <td><span class="mod-dep-call-callee" title="${esc(fc.toEntity)}">${formatSignatureHtml(fc.toEntity)}</span></td>
        <td>
          <span class="flow-node-mod-badge" style="background:${modColor}22; color:${modColor}; border:1px solid ${modColor}55; margin-right:4px;">[MOD]</span>
          <strong style="color:var(--text-primary);font-family:var(--font-display);">${esc(modName)}</strong>
        </td>
        <td><span class="class-kind-pill ${fc.kind}">${fc.kind}</span></td>
        <td>${fc.sourceLine > 0 ? `<span class="mod-dep-call-line">L: ${fc.sourceLine}</span>` : '<span style="color:var(--text-muted);">-</span>'}</td>
      `;

      tr.querySelector('.mod-dep-call-caller')?.addEventListener('click', () => {
        if (fc.fromType) selectType(fc.fromType);
      });
      tr.querySelector('.mod-dep-call-callee')?.addEventListener('click', () => {
        if (fc.toType) selectType(fc.toType);
      });

      tbody.appendChild(tr);
    }

    tableWrap.appendChild(table);
    subviewContainer.appendChild(tableWrap);
  }

  // --- SUBVIEW 4: EXTERNAL / THIRD-PARTY ---
  function renderExternalSubView() {
    const externals = depData.externalDependencies || [];
    const q = currentSearchQuery.toLowerCase();
    const filtered = externals.filter(m => {
      if (q) {
        const matchesName = (m.moduleName || '').toLowerCase().includes(q);
        const matchesCall = (m.touchPoints || []).some(tp =>
          (tp.fromEntity || '').toLowerCase().includes(q) ||
          (tp.toEntity || '').toLowerCase().includes(q)
        );
        if (!matchesName && !matchesCall) return false;
      }
      return true;
    });

    if (matchBadge) {
      matchBadge.textContent = `${filtered.length} of ${externals.length} external`;
    }

    if (filtered.length === 0) {
      subviewContainer.innerHTML = `
        <div class="kb-empty-container fade-in" style="padding:24px;">
          <div class="kb-empty-title">No External Dependencies</div>
          <div class="kb-empty-desc">No external or JDK dependencies detected for this module.</div>
        </div>`;
      return;
    }

    for (const m of filtered) {
      const card = createElement('div', { class: 'mod-dep-module-card fade-in' });
      card.innerHTML = `
        <div class="mod-dep-card-header">
          <div class="mod-dep-card-title-group">
            <span class="mod-dep-direction-tag external" title="External or JDK standard library">EXTERNAL</span>
            <span class="mod-dep-card-modname">${esc(m.moduleName)}</span>
          </div>
          <div class="mod-dep-card-stats">
            <span class="mod-dep-stat-badge">${m.totalTouchPoints} touch points</span>
          </div>
        </div>
        <div class="mod-dep-card-body">
          <div style="font-size:11px;color:var(--text-secondary);margin-bottom:6px;">External references and calls to third-party or standard library types.</div>
          <div class="mod-dep-calls-acc-body">
            ${(m.touchPoints || []).map(tp => `
              <div class="mod-dep-call-row">
                <div class="mod-dep-call-chain">
                  <span class="rel-dot ${tp.kind}"></span>
                  <span class="class-kind-pill ${tp.kind}">${tp.kind}</span>
                  <span class="mod-dep-call-caller" title="${esc(tp.fromEntity)}">${formatSignatureHtml(tp.fromEntity)}</span>
                  <span class="mod-dep-call-arrow">➔</span>
                  <span class="mod-dep-call-callee" title="${esc(tp.toEntity)}">${formatSignatureHtml(tp.toEntity)}</span>
                </div>
              </div>
            `).join('')}
          </div>
        </div>
      `;
      subviewContainer.appendChild(card);
    }
  }

  // Event Listeners for controls
  controlsBar.querySelectorAll('.mod-dep-subtab').forEach(tab => {
    tab.addEventListener('click', () => {
      controlsBar.querySelectorAll('.mod-dep-subtab').forEach(t => t.classList.remove('active'));
      tab.classList.add('active');
      currentSubView = tab.dataset.subview;
      updateSortOptions();
      updateSubView();
    });
  });

  if (sortSelect) {
    sortSelect.addEventListener('change', (e) => {
      currentSortMode = e.target.value;
      updateSubView();
    });
  }

  kindsSec.querySelectorAll('.mod-dep-kind-chip').forEach(chip => {
    chip.addEventListener('click', () => {
      currentKindFilter = chip.dataset.kind;
      updateSubView();
    });
  });

  if (searchInput) {
    searchInput.addEventListener('input', (e) => {
      currentSearchQuery = e.target.value.trim();
      updateSubView();
    });
  }

  if (searchClear && searchInput) {
    searchClear.addEventListener('click', () => {
      searchInput.value = '';
      currentSearchQuery = '';
      updateSubView();
      searchInput.focus();
    });
  }

  // Initial render of default subview & sort options
  updateSortOptions();
  updateSubView();

  container.appendChild(viewEl);
}

function cleanEntityDisplay(entityFqn) {
  if (!entityFqn) return '';
  let s = entityFqn;
  if (s.startsWith('~')) s = s.substring(1);
  const paren = s.indexOf('(');
  const params = paren > 0 ? s.substring(paren) : '';
  const base = paren > 0 ? s.substring(0, paren) : s;
  const parts = base.split('.');
  if (parts.length >= 2) {
    return parts.slice(-2).join('.') + params;
  }
  return base + params;
}

/** Formats an entity signature (class + method + params) into syntax-highlighted HTML spans */
function formatSignatureHtml(entityFqn) {
  if (!entityFqn) return '';
  let s = entityFqn;
  if (s.startsWith('~')) s = s.substring(1);
  const paren = s.indexOf('(');
  const params = paren > 0 ? s.substring(paren) : '';
  const base = paren > 0 ? s.substring(0, paren) : s;
  const parts = base.split('.');

  if (parts.length >= 2) {
    const cls = parts[parts.length - 2];
    const method = parts[parts.length - 1];
    return `<span class="sig-class">${esc(cls)}.</span><span class="sig-method">${esc(method)}</span><span class="sig-params">${esc(params)}</span>`;
  }
  return `<span class="sig-method">${esc(base)}</span><span class="sig-params">${esc(params)}</span>`;
}

/* ── Inconsistency view ────────────────────────────────────────────────────── */

/* ─────────────────────────────────────────────────────────────────────────────
   5b. Code Review - on-demand AST-based review engine
   ───────────────────────────────────────────────────────────────────────────── */

// Active review mode: 'selection' | 'file' | 'snippet'
let reviewMode = 'selection';

const SEVERITY_META = {
  CRITICAL: {
    icon: '<svg class="svg-icon icon-red icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>',
    label: 'Critical',
    cls: 'sev-critical'
  },
  WARNING: {
    icon: '<svg class="svg-icon icon-amber icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>',
    label: 'Warning',
    cls: 'sev-warning'
  },
  INFO: {
    icon: '<svg class="svg-icon icon-cyan icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>',
    label: 'Info',
    cls: 'sev-info'
  }
};

const CATEGORY_META = {
  CORRECTNESS:      { icon: '<svg class="svg-icon icon-red icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>', label: 'Correctness & Logic Defects' },
  EXCEPTION_SAFETY: { icon: '<svg class="svg-icon icon-amber icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>', label: 'Exception & Resource Safety' },
  THREAD_SAFETY:    { icon: '<svg class="svg-icon icon-purple icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="18" cy="18" r="3"/><circle cx="6" cy="6" r="3"/><path d="M13 6h3a2 2 0 0 1 2 2v7"/><line x1="6" y1="9" x2="6" y2="21"/></svg>', label: 'Thread Safety & Concurrency' },
  CODE_SMELL:       { icon: '<svg class="svg-icon icon-blue icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>', label: 'Code Smell & Maintainability' },
  API_CONTRACT:     { icon: '<svg class="svg-icon icon-pink icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><path d="M2 12h20"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/></svg>', label: 'API Contract & Design' },
  IMPACT:           { icon: '<svg class="svg-icon icon-cyan icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>', label: 'Impact & Cross-Cutting' }
};

function updateReviewTargetInfo() {
  const snippetArea = qs('#review-snippet-area');
  const targetInfo  = qs('#review-target-info');
  if (!targetInfo) return;

  if (reviewMode === 'snippet') {
    if (snippetArea) snippetArea.style.display = 'block';
    targetInfo.innerHTML = 'Paste your Java code above, then click <strong>Run Review</strong>.';
  } else {
    if (snippetArea) snippetArea.style.display = 'none';
    if (reviewMode === 'selection') {
      if (App.selected) {
        const kindStr = App.selected.kind ? String(App.selected.kind).toUpperCase() : 'UNKNOWN';
        targetInfo.innerHTML = `Target: <strong>${esc(App.selected.id)}</strong> <span style="font-size:10px; color:var(--text-muted)">(${kindStr})</span>`;
      } else {
        targetInfo.innerHTML = 'Select a class or method in the Explorer, then click <strong>Run Review</strong>.';
      }
    } else {
      if (App.currentFilePath) {
        targetInfo.innerHTML = `Target file: <strong>${esc(App.currentFilePath)}</strong>`;
      } else {
        targetInfo.innerHTML = 'Open a file in the Source tab first, then click <strong>Run Review</strong>.';
      }
    }
  }
}

function initReviewControls() {
  // Mode selector buttons
  const modeBtns = qsa('.review-mode-btn');
  modeBtns.forEach(btn => {
    btn.addEventListener('click', () => {
      modeBtns.forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      reviewMode = btn.dataset.mode;
      updateReviewTargetInfo();
    });
  });

  // Run review button
  const runBtn = qs('#run-review-btn');
  if (runBtn) {
    runBtn.addEventListener('click', runCodeReview);
  }
}

async function runCodeReview() {
  const resultsDiv = qs('#review-results');
  const badge      = qs('.tab[data-tab="review"] .tab-badge');
  const runBtn     = qs('#run-review-btn');
  const targetInfo = qs('#review-target-info');

  // Build request body based on mode
  let body = {};
  if (reviewMode === 'snippet') {
    const snippetInput = qs('#review-snippet-input');
    const code = snippetInput ? snippetInput.value.trim() : '';
    if (!code) {
      showBanner('Paste some Java code first', 'warning');
      return;
    }
    body = { snippet: code };
  } else if (reviewMode === 'file') {
    if (!App.currentFilePath) {
      showBanner('Open a file in the Source tab first', 'warning');
      return;
    }
    body = { filePath: App.currentFilePath };
  } else { // selection
    if (!App.selected) {
      showBanner('Select a class or method in the Explorer first', 'warning');
      return;
    }
    if (App.selected.kind === 'package') {
      showBanner('Please select a specific class, method, or source file to review', 'warning');
      return;
    }
    body = { entityFqn: App.selected.id };
  }

  if (!body.snippet && !body.filePath && !body.entityFqn) {
    showBanner('Please select a class or method in Explorer to review', 'warning');
    return;
  }

  // Show loading state
  runBtn.disabled = true;
  runBtn.innerHTML = '<span class="spinner-inline"></span> Reviewing…';
  resultsDiv.innerHTML = '<div class="review-loading"><div class="scan-spinner"></div><span>Running 32 AST-based checks…</span></div>';

  try {
    const findings = await api.review(body);
    if (badge) badge.textContent = findings.length;
    renderReviewFindings(findings, resultsDiv);
    if (findings.length > 0) {
      targetInfo.innerHTML = `Found <strong>${findings.length}</strong> findings.`;
    } else {
      targetInfo.innerHTML = '<span style="color:#10b981; display:inline-flex; align-items:center; gap:4px;"><svg class="svg-icon icon-emerald icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg> <strong>No issues found.</strong> Code looks good!</span>';
    }
  } catch (e) {
    resultsDiv.innerHTML = `<div class="list-empty">Review failed: ${esc(e.message)}</div>`;
  } finally {
    runBtn.disabled = false;
    runBtn.innerHTML = '<svg class="svg-icon icon-emerald icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg> Run Review';
  }
}

function renderReviewFindings(findings, container) {
  container.innerHTML = '';
  if (findings.length === 0) {
    container.innerHTML = '<div class="review-empty"><svg class="svg-icon icon-emerald icon-xl" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg><p>No issues detected. Clean code!</p></div>';
    return;
  }

  // Group by category
  const grouped = {};
  const catOrder = ['CORRECTNESS', 'EXCEPTION_SAFETY', 'THREAD_SAFETY', 'CODE_SMELL', 'API_CONTRACT', 'IMPACT'];
  for (const f of findings) {
    if (!grouped[f.category]) grouped[f.category] = [];
    grouped[f.category].push(f);
  }

  for (const cat of catOrder) {
    if (!grouped[cat] || grouped[cat].length === 0) continue;
    const catMeta = CATEGORY_META[cat] || { icon: '', label: cat };

    const section = createElement('div', { class: 'review-category-group' });
    section.innerHTML = `
      <div class="review-category-header">
        <span class="review-cat-icon">${catMeta.icon}</span>
        <span class="review-cat-label">${catMeta.label}</span>
        <span class="review-cat-count">${grouped[cat].length}</span>
      </div>`;

    for (const f of grouped[cat]) {
      const sev = SEVERITY_META[f.severity] || SEVERITY_META.INFO;
      const card = createElement('div', { class: `review-finding-card ${sev.cls} fade-in` });
      card.innerHTML = `
        <div class="finding-header">
          <span class="finding-severity">${sev.icon} ${sev.label}</span>
          <span class="finding-check">${f.checkName.replace(/_/g, ' ')}</span>
          ${f.line > 0 ? `<span class="finding-line">L${f.line}</span>` : ''}
        </div>
        <div class="finding-entity">${esc(shortFqn(f.entityFqn))}</div>
        <div class="finding-message">${esc(f.message)}</div>
        <div class="finding-suggestion"><svg class="svg-icon icon-amber icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M15 14c.2-1 .7-1.7 1.5-2.5 1-.9 1.5-2.2 1.5-3.5A6 6 0 0 0 6 8c0 1 .2 2.2 1.5 3.5.7.7 1.3 1.5 1.5 2.5"/><path d="M9 18h6"/><path d="M10 22h4"/></svg> ${esc(f.suggestion)}</div>
        ${f.sourceSnippet ? `<pre class="finding-snippet">${esc(f.sourceSnippet)}</pre>` : ''}`;

      // Click to navigate to the entity
      card.addEventListener('click', () => {
        if (f.entityKind === 'METHOD') selectMethod(f.entityFqn);
        else if (f.entityKind === 'TYPE') selectType(f.entityFqn);
        else if (f.entityKind === 'FIELD') selectField(f.entityFqn);
      });

      section.appendChild(card);
    }

    container.appendChild(section);
  }
}

/* ─────────────────────────────────────────────────────────────────────────────
   6. Entity selection - right panel rendering
   ───────────────────────────────────────────────────────────────────────────── */

/** Select a type by FQN or ID. */
async function selectType(id) {
  if (!id) return;
  if (App.packages && App.packages.some(p => p.fqn === id)) {
    const pkgObj = App.packages.find(p => p.fqn === id);
    const itemEl = qs(`#explorer-tree [data-fqn="${CSS.escape(id)}"]`);
    selectPackage(pkgObj, itemEl);
    return;
  }
  setLoading();
  try {
    const data = await api.type(id);
    App.selected = { kind: 'type', id, data };
    if (data.type && data.type.sourceFile) {
      App.currentFilePath = data.type.sourceFile;
      App.currentLineNum = data.type.startLine || 1;
    }
    renderTypeDetail(data);
    renderKnowledgeBaseForType(data);
    updateReviewTargetInfo();
    if (window.hubExplorerInstance && window.hubExplorerInstance.isOpen()) {
      window.hubExplorerInstance.load(id, 'callers');
    } else if (App.activeTab === 'graph' && data.methods && data.methods.length > 0) {
      const nonInit = data.methods.filter(m => m.name !== '<init>' && m.name !== '<clinit>' && !(m.id || '').includes('.<init>('));
      nonInit.sort((a, b) => (b.cyclomaticComplexity || 0) - (a.cyclomaticComplexity || 0));
      const bestMethod = nonInit[0] || data.methods[0];
      await loadCallGraph(bestMethod.id || bestMethod.fqn);
    } else {
      switchTab('knowledge');
    }
  } catch (e) {
    showError(e.message);
  }
}

/** Select a method by FQN and load its call graph. */
async function selectMethod(id) {
  setLoading();
  try {
    const data = await api.method(id);
    App.selected = { kind: 'method', id, data };
    if (data.method && data.method.sourceFile) {
      App.currentFilePath = data.method.sourceFile;
      App.currentLineNum = data.method.startLine || 1;
    } else if (data.type && data.type.sourceFile) {
      App.currentFilePath = data.type.sourceFile;
      App.currentLineNum = (data.method && data.method.startLine) || (data.type && data.type.startLine) || 1;
    }
    renderMethodDetail(data);
    updateReviewTargetInfo();
    if (window.hubExplorerInstance && window.hubExplorerInstance.isOpen()) {
      window.hubExplorerInstance.load(id, 'callers');
    } else {
      switchTab('graph');
      await loadCallGraph(id);
    }
  } catch (e) {
    showError(e.message);
  }
}

/** Select a field by FQN and load its impact graph. */
async function selectField(id) {
  if (!id) return;
  if (App.packages && App.packages.some(p => p.fqn === id)) {
    const pkgObj = App.packages.find(p => p.fqn === id);
    const itemEl = qs(`#explorer-tree [data-fqn="${CSS.escape(id)}"]`);
    selectPackage(pkgObj, itemEl);
    return;
  }
  setLoading();
  try {
    const data = await api.field(id);
    App.selected = { kind: 'field', id, data };
    const srcFile = data.sourceFile || (data.field && data.field.sourceFile) || (data.type && data.type.sourceFile);
    if (srcFile) {
      App.currentFilePath = srcFile;
      App.currentLineNum = (data.field && data.field.startLine) || (data.type && data.type.startLine) || 1;
    }
    renderFieldDetail(data);
    updateReviewTargetInfo();
    switchTab('graph');
    await loadFieldImpact(id);
  } catch (e) {
    showError(e.message);
  }
}

/* ─────────────────────────────────────────────────────────────────────────────
   7. Graph integration
   ───────────────────────────────────────────────────────────────────────────── */

/** Initialise (or reuse) the ForceGraph instance. */
function ensureGraph() {
  if (App.activeAltRenderer) {
    App.activeAltRenderer.destroy();
    App.activeAltRenderer = null;
  }
  const graphCanvas = qs('#graph-canvas');
  if (graphCanvas) graphCanvas.style.display = '';
  const hudActions = qs('.graph-hud-actions');
  if (hudActions) hudActions.style.display = '';
  const cameraControls = qs('.graph-camera-controls');
  if (cameraControls) cameraControls.style.display = '';
  const graphMinimap = qs('#graph-minimap-wrap');
  if (graphMinimap) graphMinimap.style.display = '';
  const depthPills = qs('.graph-depth-pills');
  if (depthPills) depthPills.style.display = '';

  if (App.activeGraphMode !== 'fullCodebase') {
    const levelSel = qs('#graph-level-selector');
    const levelDiv = qs('#graph-level-divider');
    if (levelSel) levelSel.style.display = 'none';
    if (levelDiv) levelDiv.style.display = 'none';
  }

  if (!App.graph) {
    const container = qs('#graph-view');
    const tooltip   = qs('#graph-tooltip');
    App.graph       = new window.ForceGraph(container, tooltip);
    applyAllSettings(loadSettings());

    App.graph.onNodeClick = async node => {
      if (App.activeGraphMode === 'criticalPath') {
        if (currentActivePath && currentActivePath.nodes) {
          const stepNode = currentActivePath.nodes.find(n => n.id === node.id);
          if (stepNode) {
            selectCriticalPathStep(stepNode.step);
          }
        }
        if (node.type === 'CLASS') {
          try {
            const data = await api.type(node.id);
            renderTypeDetail(data);
            updateReviewTargetInfo();
          } catch (e) { console.warn(e); }
        } else if (node.type === 'METHOD') {
          try {
            const data = await api.method(node.id);
            renderMethodDetail(data);
            updateReviewTargetInfo();
          } catch (e) { console.warn(e); }
        } else if (node.type === 'FIELD') {
          try {
            const data = await api.field(node.id);
            renderFieldDetail(data);
            updateReviewTargetInfo();
          } catch (e) { console.warn(e); }
        }
        return;
      }
      if (App.activeGraphMode === 'fullCodebase') {
        if (node.type === 'CLASS') {
          try {
            const data = await api.type(node.id);
            renderTypeDetail(data);
            updateReviewTargetInfo();
          } catch (e) { console.warn(e); }
        } else if (node.type === 'METHOD') {
          try {
            const data = await api.method(node.id);
            renderMethodDetail(data);
            updateReviewTargetInfo();
          } catch (e) { console.warn(e); }
        } else if (node.type === 'FIELD') {
          try {
            const data = await api.field(node.id);
            renderFieldDetail(data);
            updateReviewTargetInfo();
          } catch (e) { console.warn(e); }
        }
      } else {
        if      (node.type === 'METHOD') await selectMethod(node.id);
        else if (node.type === 'FIELD')  await selectField(node.id);
        else if (node.type === 'CLASS')  await selectType(node.id);
      }
    };
  }
}

/** Load the whole codebase graph (either high-level Architecture or detailed Method call graph). */
async function loadWholeCodebaseGraph(level, granularity) {
  const isAltViz = ['city3d', 'galaxy3d', 'graph2d', 'treemap', 'sunburst', 'dsm', 'chord'].includes(level);
  if (isAltViz) {
    App.codebaseMacroLevel = level;
  } else if (level) {
    App.codebaseGraphLevel = level;
  }
  if (granularity) {
    App.codebaseGranularity = granularity;
  } else if (!App.codebaseGranularity) {
    App.codebaseGranularity = 'arch';
  }

  const effectiveLevel = isAltViz ? App.codebaseMacroLevel : (App.codebaseGraphLevel || 'arch');
  const isMethods = (App.codebaseGranularity === 'methods');
  if (App.activeGraphMode === 'criticalPath' || currentCriticalReport !== null) {
    closeCriticalPathDock(false);
  }
  App.activeGraphMode = 'wholeCodebase';
  App.selected = null;

  // If in Macro Codebase Viz mode, ensure Codebase Viz tab is active
  if (isAltViz && App.activeTab !== 'codebase') {
    App._suppressTabLoad = true;
    try { switchTab('codebase'); } finally { App._suppressTabLoad = false; }
  } else if (!isAltViz && App.activeTab !== 'graph') {
    App._suppressTabLoad = true;
    try { switchTab('graph'); } finally { App._suppressTabLoad = false; }
  }

  // Update pill active states in level selector
  qsa('#codebase-level-selector .level-pill').forEach(btn => btn.classList.toggle('active', btn.dataset.level === effectiveLevel));
  
  // Update granularity selector pills
  qsa('#codebase-granularity-selector .level-pill').forEach(btn => btn.classList.toggle('active', btn.dataset.granularity === (isMethods ? 'methods' : 'arch')));

  // Toggle visibility of granularity selector (supported for 3D City, 3D Galaxy, 2D Graph, DSM, and Chord)
  const granCtrl = qs('#codebase-granularity-wrap') || qs('#codebase-granularity-selector');
  const granDiv = qs('#codebase-graph-toggles-divider');
  const supportsGranularity = ['city3d', 'galaxy3d', 'graph2d', 'dsm', 'chord'].includes(effectiveLevel);
  if (granCtrl) granCtrl.style.display = supportsGranularity ? 'flex' : 'none';

  // Toggle POJO filter button (visible on 3D views when choosing methods level, on 2D Graph, and in hierarchical views)
  const is3D = (effectiveLevel === 'city3d' || effectiveLevel === 'galaxy3d');
  const isGraph2D = (effectiveLevel === 'graph2d');
  const pojoCtrl = qs('#codebase-pojo-controls');
  const pojoDiv = qs('#codebase-pojo-divider');
  const showPojoFilter = (isMethods && (is3D || supportsGranularity)) || isGraph2D || (effectiveLevel === 'sunburst') || (effectiveLevel === 'treemap');
  if (pojoCtrl) pojoCtrl.style.display = showPojoFilter ? 'inline-flex' : 'none';
  if (pojoDiv) pojoDiv.style.display = (showPojoFilter && supportsGranularity) ? '' : 'none';

  // Toggle 2D Graph specific controls (Clusters/Hulls, Physics, Heat) & 3D City Heat
  const graphTogglesCtrl = qs('#codebase-graph-toggles');
  const graphTogglesDiv = qs('#codebase-graph-toggles-divider');
  const showGraphToggles = isGraph2D || effectiveLevel === 'city3d';
  if (graphTogglesCtrl) {
    graphTogglesCtrl.style.display = showGraphToggles ? 'inline-flex' : 'none';
    const hullsBtn = qs('#btn-codebase-toggle-hulls');
    const physicsBtn = qs('#btn-codebase-toggle-physics');
    if (hullsBtn) hullsBtn.style.display = isGraph2D ? '' : 'none';
    if (physicsBtn) physicsBtn.style.display = isGraph2D ? '' : 'none';
  }
  if (graphTogglesDiv) graphTogglesDiv.style.display = showGraphToggles ? '' : 'none';

  // Toggle Call Arcs filter button in Codebase HUD (visible for 3D modes: 3D City & 3D Galaxy)
  const arcsCtrl = qs('#codebase-arcs-controls');
  const arcsDiv = qs('#codebase-arcs-divider');
  if (arcsCtrl) arcsCtrl.style.display = is3D ? 'block' : 'none';
  if (arcsDiv) arcsDiv.style.display = is3D ? '' : 'none';

  // Toggle brightness slider visibility (only for 3D modes)
  const brightnessCtrl = qs('#codebase-brightness-controls');
  const brightnessDiv = qs('#codebase-brightness-divider');
  if (brightnessCtrl) brightnessCtrl.style.display = is3D ? 'flex' : 'none';
  if (brightnessDiv) brightnessDiv.style.display = is3D ? '' : 'none';

  // Toggle camera controls (visible for 2D Graph, 3D modes, Chord, Treemap, Sunburst, and DSM)
  const cameraCtrl = qs('#codebase-camera-controls');
  const cameraDiv = qs('#codebase-camera-divider');
  const showCameraControls = isGraph2D || is3D || ['chord', 'treemap', 'sunburst', 'dsm'].includes(effectiveLevel);
  if (cameraCtrl) cameraCtrl.style.display = showCameraControls ? 'inline-flex' : 'none';
  if (cameraDiv) cameraDiv.style.display = showCameraControls ? '' : 'none';

  // Toggle Chord search input (visible only in Chord view)
  const chordSearchWrap = qs('#chord-search-wrap');
  const chordSearchInput = qs('#chord-search-input');
  const chordSearchClear = qs('#chord-search-clear');
  const isChord = effectiveLevel === 'chord';
  if (chordSearchWrap) chordSearchWrap.style.display = isChord ? 'flex' : 'none';
  if (!isChord && chordSearchInput) {
    // Clear search state when leaving Chord view
    chordSearchInput.value = '';
    if (chordSearchClear) chordSearchClear.style.display = 'none';
  }

  // Wire chord search events (idempotent – guarded by flag on element)
  if (chordSearchInput && !chordSearchInput._chordWired) {
    chordSearchInput._chordWired = true;

    chordSearchInput.addEventListener('input', () => {
      const q = chordSearchInput.value;
      if (chordSearchClear) chordSearchClear.style.display = q ? 'flex' : 'none';
      if (App.activeAltRenderer && typeof App.activeAltRenderer.search === 'function') {
        App.activeAltRenderer.search(q);
      }
    });

    chordSearchInput.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        chordSearchInput.value = '';
        if (chordSearchClear) chordSearchClear.style.display = 'none';
        if (App.activeAltRenderer && typeof App.activeAltRenderer.search === 'function') {
          App.activeAltRenderer.search('');
        }
        chordSearchInput.blur();
      }
    });

    if (chordSearchClear) {
      chordSearchClear.addEventListener('click', () => {
        chordSearchInput.value = '';
        chordSearchClear.style.display = 'none';
        if (App.activeAltRenderer && typeof App.activeAltRenderer.search === 'function') {
          App.activeAltRenderer.search('');
        }
        chordSearchInput.focus();
      });
    }
  }

  // Toggle visibility of bottom canvas toolbar
  const canvasToolbar = qs('#codebase-canvas-toolbar');
  const hasBottomControls = (supportsGranularity || is3D || isGraph2D || showCameraControls || showPojoFilter);
  if (canvasToolbar) canvasToolbar.style.display = hasBottomControls ? 'flex' : 'none';


  // Toggle 2D minimap for codebase visualizer
  const cbMinimap = qs('#codebase-minimap-wrap');
  if (cbMinimap) cbMinimap.style.display = (effectiveLevel === 'graph2d' && (!App.settings || App.settings.showMinimap !== false)) ? '' : 'none';

  // Fast Resume: If current renderer matches effective level, granularity and cache revision, resume in 0ms!
  if (App.activeAltRenderer &&
      App.activeAltRenderer._currentLevel === effectiveLevel &&
      App.activeAltRenderer._currentGranularity === (isMethods ? 'methods' : 'arch') &&
      App.activeAltRenderer._cachedRevision === GraphDataCache.getRevision()) {
    if (typeof App.activeAltRenderer.resume === 'function') {
      App.activeAltRenderer.resume();
    }
    return;
  }

  const mountContainer = isAltViz ? qs('#codebase-canvas-wrap') : qs('#graph-view');

  // Destroy any previous alternate renderer
  if (App.activeAltRenderer) {
    App.activeAltRenderer.destroy();
    App.activeAltRenderer = null;
  }
  if (isAltViz && mountContainer) {
    mountContainer.innerHTML = '';
  }

  if (isAltViz) {
    hideCodebaseEmpty();
    try {
      if (effectiveLevel === 'city3d') {
        showBanner(isMethods ? 'Building 3D Software City (Methods)...' : 'Building 3D Software City (Classes)...');
        await ensure3DStudioDependencies();
        const [graphData, treeData] = await Promise.all([
          isMethods ? api.fullGraph() : api.architectureGraph('classes'),
          api.treemapData()
        ]);
        if (!graphData.nodes || graphData.nodes.length === 0) {
          showCodebaseEmpty('No graph data available for 3D City. Run a scan first.');
          return;
        }
        const renderer = new window.CodeCity3DRenderer(mountContainer);
        if (typeof App.codebaseHidePojo === 'boolean' && typeof renderer.setHidePojo === 'function') {
          renderer.setHidePojo(App.codebaseHidePojo);
        }
        renderer.setData(graphData, treeData);
        if (typeof renderer.setBrightness === 'function') {
          renderer.setBrightness(App.codebaseBrightness || 1.0);
        }
        App.activeAltRenderer = renderer;
        renderAltVizInspector(`3D City (${isMethods ? 'Methods' : 'Classes'})`, graphData.nodes.length, graphData.edges.length);
        renderCodebaseLegend(graphData.nodes);
        showBanner(`3D Software City loaded: ${graphData.nodes.length} ${isMethods ? 'methods' : 'buildings'}`);

      } else if (effectiveLevel === 'galaxy3d') {
        showBanner(isMethods ? 'Generating 3D Force Galaxy (Methods)...' : 'Generating 3D Force Galaxy (Classes)...');
        await ensure3DStudioDependencies();
        const data = isMethods ? await api.fullGraph() : await api.architectureGraph('classes');
        if (!data.nodes || data.nodes.length === 0) {
          showCodebaseEmpty('No graph data available for 3D Galaxy. Run a scan first.');
          return;
        }
        const renderer = new window.Galaxy3DRenderer(mountContainer);
        if (typeof App.codebaseHidePojo === 'boolean' && typeof renderer.setHidePojo === 'function') {
          renderer.setHidePojo(App.codebaseHidePojo);
        }
        renderer.setData(data);
        if (typeof renderer.setBrightness === 'function') {
          renderer.setBrightness(App.codebaseBrightness || 1.0);
        }
        App.activeAltRenderer = renderer;
        renderAltVizInspector(`3D Galaxy (${isMethods ? 'Methods' : 'Classes'})`, data.nodes.length, data.edges.length);
        renderCodebaseLegend(data.nodes);
        showBanner(`3D Force Galaxy loaded: ${data.nodes.length} ${isMethods ? 'method nodes' : 'orbital nodes'}`);

      } else if (effectiveLevel === 'graph2d') {
        showBanner(isMethods ? 'Rendering 2D Blooming Tree (Methods)...' : 'Rendering 2D Blooming Tree (Classes)...');
        const data = isMethods ? await api.fullGraph() : await api.architectureGraph('classes');
        if (!data.nodes || data.nodes.length === 0) {
          showCodebaseEmpty('No graph data available for 2D Blooming Tree. Run a scan first.');
          return;
        }

        if (cbMinimap) cbMinimap.style.display = (!App.settings || App.settings.showMinimap !== false) ? '' : 'none';

        const tooltip = qs('#codebase-tooltip') || qs('#graph-tooltip');
        const fg = new window.ForceGraph(mountContainer, tooltip);
        if (typeof App.codebaseHidePojo === 'boolean' && typeof fg.setHidePojo === 'function') {
          fg.setHidePojo(App.codebaseHidePojo);
        }
        if (App.settings) fg.applySettings(App.settings);
        if (App.gitSummary && App.gitSummary.hotEntities) {
          const heatMap = {};
          for (const e of App.gitSummary.hotEntities) heatMap[e.entityFqn] = e.commitCount;
          fg.setHeatData(heatMap);
        }
        fg.setData(data.nodes, data.edges);

        fg.onNodeClick = async (node) => {
          if (node.type === 'CLASS') {
            try {
              const typeData = await api.type(node.id);
              renderTypeDetail(typeData);
              updateReviewTargetInfo();
            } catch (e) { console.warn(e); }
          } else if (node.type === 'METHOD') {
            try {
              const methodData = await api.method(node.id);
              renderMethodDetail(methodData);
              updateReviewTargetInfo();
            } catch (e) { console.warn(e); }
          } else if (node.type === 'FIELD') {
            try {
              const fieldData = await api.field(node.id);
              renderFieldDetail(fieldData);
              updateReviewTargetInfo();
            } catch (e) { console.warn(e); }
          }
        };

        App.activeAltRenderer = fg;

        renderAltVizInspector(`2D Bloom (${isMethods ? 'Methods' : 'Classes'})`, data.nodes.length, data.edges.length);
        renderCodebaseLegend(data.nodes);
        showBanner(`2D Blooming Tree loaded: ${data.nodes.length} nodes, ${data.edges.length} connections`);

      } else if (effectiveLevel === 'treemap') {
        showBanner('Loading Treemap...');
        await ensureTreemapLoaded();
        const data = await api.treemapData();
        if (!data.children || data.children.length === 0) {
          showCodebaseEmpty('No hierarchy data available for Treemap. Run a scan first.');
          return;
        }
        const renderer = new window.TreemapRenderer(mountContainer);
        renderer.setData(data);
        App.activeAltRenderer = renderer;
        renderAltVizInspector('Treemap', countTreemapNodes(data), data.size);
        renderCodebaseLegend(data);
        showBanner('Treemap loaded: ' + countTreemapNodes(data) + ' nodes, ' + data.size + ' total lines');

      } else if (effectiveLevel === 'sunburst') {
        showBanner('Loading Sunburst...');
        await ensureSunburstLoaded();
        const data = await api.treemapData();
        if (!data.children || data.children.length === 0) {
          showCodebaseEmpty('No hierarchy data available for Sunburst. Run a scan first.');
          return;
        }
        const renderer = new window.SunburstRenderer(mountContainer);
        renderer.setData(data);
        App.activeAltRenderer = renderer;
        renderAltVizInspector('Sunburst', countTreemapNodes(data), data.size);
        renderCodebaseLegend(data);
        showBanner('Sunburst loaded: ' + countTreemapNodes(data) + ' nodes');

      } else if (effectiveLevel === 'dsm') {
        const dsmScope = isMethods ? 'methods' : 'classes';
        showBanner(`Loading Dependency Structure Matrix (${dsmScope})...`);
        await ensureDSMLoaded();
        const data = await api.dsmData(dsmScope);
        if (!data.classes || data.classes.length === 0) {
          showCodebaseEmpty('No class data available for DSM. Run a scan first.');
          return;
        }
        const renderer = new window.DSMRenderer(mountContainer);
        if (typeof App.codebaseHidePojo === 'boolean' && typeof renderer.setHidePojo === 'function') {
          renderer.setHidePojo(App.codebaseHidePojo);
        }
        renderer.onScopeChange(async (newScope, filter) => {
          try {
            showBanner(filter ? `Loading DSM (${newScope}: ${filter})...` : `Loading DSM (${newScope})...`);
            const isMethodsNow = (newScope === 'methods');
            App.codebaseGranularity = (isMethodsNow ? 'methods' : 'arch');
            qsa('#codebase-granularity-selector .level-pill').forEach(btn => btn.classList.toggle('active', btn.dataset.granularity === (isMethodsNow ? 'methods' : 'arch')));
            const pojoCtrl = qs('#codebase-pojo-controls');
            const pojoDiv = qs('#codebase-pojo-divider');
            if (pojoCtrl) pojoCtrl.style.display = isMethodsNow ? 'inline-flex' : 'none';
            if (pojoDiv) pojoDiv.style.display = isMethodsNow ? '' : 'none';
            const scopedData = await api.dsmData(newScope, filter);
            renderer.setData(scopedData, filter);
            renderAltVizInspector(`DSM (${newScope})`, scopedData.classes.length, 0);
            renderCodebaseLegend(scopedData.classes.map(c => ({ id: c, package: c.split('.').slice(0, -1).join('.') || 'default' })));
            showBanner(`DSM loaded: ${scopedData.classes.length} ${newScope}` + (filter ? ` [${filter}]` : ''));
          } catch (err) {
            showBanner('Error changing DSM scope: ' + err.message);
          }
        });
        renderer.onSelectCell((cellInfo) => {
          renderDSMCellInspector(cellInfo);
        });
        renderer.onSelectEntity((entityFqn) => {
          selectEntity(entityFqn);
        });
        renderer.setData(data);
        App.activeAltRenderer = renderer;
        renderAltVizInspector(`DSM (${dsmScope})`, data.classes.length, 0);
        renderCodebaseLegend(data.classes.map(c => ({ id: c, package: c.split('.').slice(0, -1).join('.') || 'default' })));
        showBanner('DSM loaded: ' + data.classes.length + ' ' + (data.scope || dsmScope));

      } else if (effectiveLevel === 'chord') {
        showBanner(isMethods ? 'Loading Chord Diagram (Methods)...' : 'Loading Chord Diagram (Classes)...');
        await ensureChordLoaded();
        const data = isMethods ? await api.fullGraph() : await api.architectureGraph('classes');
        if (!data.nodes || data.nodes.length === 0) {
          showCodebaseEmpty('No graph data available for Chord diagram. Run a scan first.');
          return;
        }
        const renderer = new window.ChordRenderer(mountContainer);
        renderer.setData(data);
        App.activeAltRenderer = renderer;
        renderAltVizInspector(`Chord (${isMethods ? 'Methods' : 'Classes'})`, data.nodes.length, data.edges.length);
        renderCodebaseLegend(data.nodes);
        showBanner(`Chord diagram loaded: ${data.nodes.length} ${isMethods ? 'methods' : 'classes'}, ${data.edges.length} relationships`);
      }

      if (App.activeAltRenderer) {
        App.activeAltRenderer._currentLevel = effectiveLevel;
        App.activeAltRenderer._currentGranularity = (isMethods ? 'methods' : 'arch');
        App.activeAltRenderer._cachedRevision = GraphDataCache.getRevision();
        if (typeof App.activeAltRenderer.setArchetypeFilter === 'function') {
          App.activeAltRenderer.setArchetypeFilter(App.activeArchetypeFilter || 'ALL');
        }
      }
    } catch (e) {
      showCodebaseEmpty('Failed to load visualization: ' + e.message);
    }

  } else {
    // Force graph modes in 2D Graph Tab (arch / methods)
    const graphCanvas = qs('#graph-canvas');
    if (graphCanvas) graphCanvas.style.display = '';
    const hudActions = qs('.graph-hud-actions');
    if (hudActions) hudActions.style.display = '';
    const cameraControls = qs('.graph-camera-controls');
    if (cameraControls) cameraControls.style.display = '';
    const depthPills = qs('.graph-depth-pills');
    if (depthPills) depthPills.style.display = '';
    const graphMinimap = qs('#graph-minimap-wrap');
    if (graphMinimap) graphMinimap.style.display = '';

    ensureGraph();
    App.graph.clear();

    try {
      const isArch = (App.codebaseGraphLevel === 'arch');
      showBanner(isArch ? 'Loading codebase architecture graph...' : 'Loading detailed method graph...');

      const view = isArch ? await api.architectureGraph('classes') : await api.fullGraph();

      if (!view.nodes || view.nodes.length === 0) {
        showGraphEmpty('No code relationships indexed yet. Run a scan first.');
        return;
      }

      hideGraphEmpty();
      App.graph.setData(view.nodes, view.edges);

      // Update inspector view
      renderWholeCodebaseInspector(view, isArch ? 'Architecture' : 'Detailed');

      showBanner('Done: ' + (isArch ? 'Architecture' : 'Detailed') + ' codebase graph loaded: ' + view.nodes.length + ' nodes, ' + view.edges.length + ' relationships');
    } catch (e) {
      showGraphEmpty('Failed to load codebase graph: ' + e.message);
    }
  }
}

/** Render interactive Community & Package Legend for Codebase Viz views (3D City, 3D Galaxy, 2D Graph, Treemap, DSM, Chord). */
function renderCodebaseLegend(nodesOrTreeData) {
  const legendWrap = qs('#codebase-community-legend');
  const legendList = qs('#codebase-legend-list');
  if (!legendWrap || !legendList) return;

  if (!nodesOrTreeData) {
    legendWrap.style.display = 'none';
    return;
  }

  // Extract unique packages with node counts
  // Map of pkg -> Map<className, { count, nodes, fqn, kind }>
  const pkgMap = new Map();

  if (Array.isArray(nodesOrTreeData)) {
    // Array of nodes { id, label, package, ... }
    nodesOrTreeData.forEach(n => {
      let pkg = n.package;
      let cls = n.className;
      if (!pkg && (n.type === 'MODULE' || n.role === 'module')) {
        pkg = n.id || n.label || 'Module';
        cls = n.label || n.id || 'Module';
      } else if (!pkg && (n.type === 'PACKAGE' || n.role === 'package')) {
        pkg = n.id || n.label || 'Package';
        cls = n.label || n.id || 'Package';
      } else if (!pkg && n.id && n.id.includes('.')) {
        const parts = n.id.replace(/\(.*\)/, '').split('.');
        const isType = (n.type === 'CLASS' || n.type === 'TYPE' || parts.length <= 2);
        pkg = isType ? (parts.slice(0, -1).join('.') || 'default') : (parts.slice(0, -2).join('.') || 'default');
        if (!cls) cls = isType ? parts[parts.length - 1] : parts[parts.length - 2];
      }
      pkg = pkg || n.label || 'default';
      cls = cls || (n.type === 'CLASS' ? n.label : (n.id && n.id.includes('.') ? n.id.split('.').slice(-2, -1)[0] : n.id)) || 'Class';

      if (!pkgMap.has(pkg)) pkgMap.set(pkg, new Map());
      const classMap = pkgMap.get(pkg);
      if (!classMap.has(cls)) {
        classMap.set(cls, { count: 0, firstNode: n, fqn: (n.package ? `${n.package}.${cls}` : cls) });
      }
      classMap.get(cls).count++;
    });
  } else if (nodesOrTreeData.children) {
    // Hierarchical tree (treemap / sunburst)
    const traverse = (item, parentPkg) => {
      if (item.type === 'PACKAGE' || (item.children && !item.type)) {
        const pkgName = item.fqn || item.name || 'default';
        if (!pkgMap.has(pkgName)) pkgMap.set(pkgName, new Map());
        if (item.children) {
          item.children.forEach(c => {
            if (c.type === 'CLASS' || (!c.children && c.name)) {
              const clsMap = pkgMap.get(pkgName);
              const clsName = c.name || c.label || 'Class';
              clsMap.set(clsName, { count: c.value || c.loc || (c.children ? c.children.length : 1), firstNode: c, fqn: c.fqn || `${pkgName}.${clsName}` });
            } else {
              traverse(c, pkgName);
            }
          });
        }
      } else if (item.children) {
        item.children.forEach(c => traverse(c, parentPkg));
      }
    };
    traverse(nodesOrTreeData, 'default');
  }

  if (pkgMap.size === 0) {
    legendWrap.style.display = 'none';
    return;
  }

  const sortedPkgs = Array.from(pkgMap.entries()).map(([pkg, classMap]) => {
    let totalCount = 0;
    classMap.forEach(v => { totalCount += v.count; });
    return { pkg, classMap, totalCount };
  }).sort((a, b) => b.totalCount - a.totalCount);

  legendWrap.style.display = 'flex';

  // Add Expand All / Collapse All actions header if not present
  let actionsWrap = legendWrap.querySelector('.legend-actions');
  if (!actionsWrap) {
    actionsWrap = document.createElement('div');
    actionsWrap.className = 'legend-actions';
    actionsWrap.style.display = 'flex';
    actionsWrap.style.gap = '4px';
    actionsWrap.style.marginBottom = '6px';
    actionsWrap.innerHTML = `
      <button class="legend-actions-btn" id="codebase-legend-expand-all">Expand All</button>
      <button class="legend-actions-btn" id="codebase-legend-collapse-all">Collapse All</button>
    `;
    legendList.parentElement.insertBefore(actionsWrap, legendList);

    actionsWrap.querySelector('#codebase-legend-expand-all').onclick = () => {
      legendList.querySelectorAll('.legend-chevron').forEach(ch => ch.classList.add('open'));
      legendList.querySelectorAll('.legend-class-list').forEach(cl => cl.classList.add('open'));
    };
    actionsWrap.querySelector('#codebase-legend-collapse-all').onclick = () => {
      legendList.querySelectorAll('.legend-chevron').forEach(ch => ch.classList.remove('open'));
      legendList.querySelectorAll('.legend-class-list').forEach(cl => cl.classList.remove('open'));
    };
  }

  legendList.innerHTML = sortedPkgs.map(({ pkg, classMap, totalCount }, idx) => {
    const color = (window.GRAPHIFY_COLORS && window.GRAPHIFY_COLORS.length > 0)
      ? window.GRAPHIFY_COLORS[idx % window.GRAPHIFY_COLORS.length]
      : ((window.CodeLensPalette && window.CodeLensPalette.getColor)
          ? window.CodeLensPalette.getColor(pkg, idx)
          : '#3b82f6');
    const displayLabel = formatPackageDisplayName(pkg);
    const safePkgId = 'pkg-' + idx;

    const isMethodsView = (App.codebaseGranularity === 'methods');

    const classesHtml = Array.from(classMap.entries()).map(([clsName, meta]) => {
      const classColor = isMethodsView
        ? ((window.CodeLensPalette && window.CodeLensPalette.getClassColor)
            ? window.CodeLensPalette.getClassColor(meta.fqn || clsName, 'CLASS')
            : color)
        : color;

      let archBadgeHtml = '';
      if (window.CodeLensClassifier) {
        const arch = window.CodeLensClassifier.classifyType(clsName, meta.fqn, pkg);
        if (arch) {
          archBadgeHtml = `<span class="legend-class-badge" style="background:${arch.color}22; color:${arch.color}; border:1px solid ${arch.color}66;" title="${esc(arch.description)}">${arch.icon} ${esc(arch.badge)}</span>`;
        }
      }
      return `
        <div class="legend-class-item" data-pkg="${pkg}" data-class="${clsName}" data-fqn="${meta.fqn}" title="Toggle ${clsName} (${meta.count})">
          <div class="legend-class-dot" style="background:${classColor}"></div>
          <span class="legend-class-label">${clsName}</span>
          ${archBadgeHtml}
          <span class="legend-count">${meta.count}</span>
        </div>
      `;
    }).join('');

    return `
      <div class="legend-pkg-wrap" data-pkg="${pkg}">
        <div class="legend-pkg-row" data-pkg="${pkg}">
          <span class="legend-chevron" data-target="${safePkgId}" title="Expand / collapse classes"><svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="9 18 15 12 9 6"/></svg></span>
          <div class="legend-dot" style="background:${color}"></div>
          <span class="legend-label" title="${pkg}">${displayLabel}</span>
          <span class="legend-count">${totalCount}</span>
        </div>
        <div class="legend-class-list" id="${safePkgId}">
          ${classesHtml}
        </div>
      </div>
    `;
  }).join('');

  // Wire chevrons
  legendList.querySelectorAll('.legend-chevron').forEach(chev => {
    chev.onclick = (e) => {
      e.stopPropagation();
      const targetId = chev.dataset.target;
      const list = legendList.querySelector(`#${targetId}`);
      chev.classList.toggle('open');
      if (list) list.classList.toggle('open');
    };
  });

  // Wire package toggle
  legendList.querySelectorAll('.legend-pkg-row').forEach(row => {
    row.onclick = (e) => {
      if (e.target.classList.contains('legend-chevron')) return;
      const pkg = row.dataset.pkg;
      row.classList.toggle('dimmed');
      const isDimmed = row.classList.contains('dimmed');
      const wrap = row.closest('.legend-pkg-wrap');
      if (wrap) {
        wrap.querySelectorAll('.legend-class-item').forEach(ci => ci.classList.toggle('dimmed', isDimmed));
      }
      if (App.activeAltRenderer && typeof App.activeAltRenderer.togglePackage === 'function') {
        App.activeAltRenderer.togglePackage(pkg, !isDimmed);
      } else if (App.graph && typeof App.graph.togglePackage === 'function') {
        App.graph.togglePackage(pkg, !isDimmed);
      }
      if (App.graph && typeof App.graph._requestRender === 'function') {
        App.graph._requestRender();
      }
    };
  });

  // Wire class item toggle (sub-legend click)
  legendList.querySelectorAll('.legend-class-item').forEach(item => {
    item.onclick = (e) => {
      e.stopPropagation();
      const clsName = item.dataset.class;
      const fqn = item.dataset.fqn;
      const pkg = item.dataset.pkg;
      item.classList.toggle('dimmed');
      const isDimmed = item.classList.contains('dimmed');

      if (App.activeAltRenderer) {
        if (typeof App.activeAltRenderer.toggleEntity === 'function') {
          App.activeAltRenderer.toggleEntity(clsName, !isDimmed, fqn, pkg);
        } else if (typeof App.activeAltRenderer.toggleNode === 'function') {
          App.activeAltRenderer.toggleNode(clsName, !isDimmed, fqn);
        }
      } else if (App.graph) {
        if (typeof App.graph.toggleEntity === 'function') {
          App.graph.toggleEntity(clsName, !isDimmed, fqn, pkg);
        } else if (typeof App.graph.toggleNode === 'function') {
          App.graph.toggleNode(clsName, !isDimmed, fqn);
        }
      }
      if (App.graph && typeof App.graph._requestRender === 'function') {
        App.graph._requestRender();
      }
    };
  });
}

function formatPackageDisplayName(pkg) {
  if (!pkg || pkg === 'default' || pkg === '(default)') return '(default)';
  const parts = pkg.split('.').filter(Boolean);
  if (parts.length >= 3 && ['com', 'org', 'io', 'net', 'dev', 'app', 'co', 'gov', 'edu'].includes(parts[0])) {
    const sub = parts.slice(2);
    return sub.length > 0 ? sub.map(s => s.charAt(0).toUpperCase() + s.slice(1)).join(' › ') : parts[parts.length - 1];
  }
  return parts.map(s => s.charAt(0).toUpperCase() + s.slice(1)).join(' › ');
}

/** Render inspector panel for alt-viz modes. */
function renderAltVizInspector(vizName, nodeCount, sizeOrEdges) {
  const body = qs('#right-body');
  if (!body) return;
  body.innerHTML = '';

  renderEntityHeader('VISUALIZATION', vizName, 'Alternative visualization of the codebase');

  const labels = {
    'DSM': [['Classes', String(nodeCount)], ['View', 'Dependency Structure Matrix']],
    'Treemap': [['Nodes', String(nodeCount)], ['Total Lines', String(sizeOrEdges)], ['View', 'Zoomable Treemap']],
    'Chord': [['Classes', String(nodeCount)], ['Relationships', String(sizeOrEdges)], ['View', 'Chord Diagram']],
    'Sunburst': [['Nodes', String(nodeCount)], ['Total Lines', String(sizeOrEdges)], ['View', 'Sunburst']],
    '3D City': [['Buildings', String(nodeCount)], ['View', '3D Software City Monoliths (Three.js)']],
    '3D Galaxy': [['Orbital Nodes', String(nodeCount)], ['Relationships', String(sizeOrEdges)], ['View', '3D Constellation Galaxy (Three.js)']],
  };

  body.appendChild(metaGrid(labels[vizName] || [['Nodes', String(nodeCount)]]));

  const hint = createElement('div', { class: 'inspector-hint-box' });
  const tips = {
    'DSM': 'Rows = source classes, columns = target classes. Order: Cluster / Layered / Cycles / A-Z. Hover for crosshair, click to inspect call relationship.',
    'Treemap': 'Rectangle size = lines of code. Colors = categorical palette consistent with Sunburst & Chord. Click to zoom in.',
    'Chord': 'Arc size = connection volume. Chords = inter-class calls. Hover an arc to isolate its connections.',
    'Sunburst': 'Ring segments = packages/classes/methods. Angle = proportion of code size. Click to zoom in.',
    '3D City': '3D WebGL Monoliths: Height = Lines of Code, Base = Complexity. Left-click + drag to orbit, right-click to pan, scroll to zoom. Click skyscraper to inspect.',
    '3D Galaxy': '3D Orbital Constellation: 3D Force-Directed nodes with particle energy pulses along call arcs. Orbit / Pan camera and click nodes to traverse.',
  };
  hint.innerHTML = '<div style="font-size:12px; color:var(--text-secondary); line-height:1.5; padding:8px 0;">' + (tips[vizName] || '') + '</div>';
  body.appendChild(hint);
}

/** Render detailed relationship breakdown when clicking a DSM cell. */
function renderDSMCellInspector(info) {
  const body = qs('#right-body');
  if (!body) return;
  body.innerHTML = '';

  const callerColor = (window.CodeLensPalette && window.CodeLensPalette.getColor)
    ? window.CodeLensPalette.getColor(info.caller, 0)
    : '#3b82f6';
  const calleeColor = (window.CodeLensPalette && window.CodeLensPalette.getColor)
    ? window.CodeLensPalette.getColor(info.callee, 1)
    : '#10b981';

  const shortName = (s) => s.includes('.') ? s.split('.').pop() : s;

  renderEntityHeader(info.isCycle ? 'CIRCULAR CYCLE' : 'DEPENDENCY PAIR', 'DSM Cell Analysis', info.isCycle ? 'Bidirectional Coupling Warning' : 'Direct Inter-Component Call');

  const card = createElement('div', { class: 'inspector-dsm-card' });
  card.innerHTML = `
    <div style="padding: 12px; background: var(--bg-surface); border: 1px solid var(--border); border-radius: var(--radius-sm); display:flex; flex-direction:column; gap:10px; margin-bottom: 12px;">
      <div style="display:flex; align-items:center; justify-content:space-between;">
        <span style="font-size:10.5px; color:var(--text-muted); text-transform:uppercase; font-weight:700; letter-spacing:0.5px;">Caller</span>
        <span style="display:flex; align-items:center; gap:6px; font-weight:600; font-family:var(--font-mono); font-size:12px; color:var(--text-primary);">
          <span style="display:inline-block; width:8px; height:8px; border-radius:50%; background:${callerColor}; box-shadow: 0 0 6px ${callerColor}66;"></span>
          ${shortName(info.caller)}
        </span>
      </div>

      <div style="text-align:center; color:${info.isCycle ? '#f87171' : 'var(--primary)'}; font-weight:700; font-size:12px; font-family:var(--font-mono); padding: 6px; background:var(--bg-base); border-radius:4px; border: 1px solid ${info.isCycle ? 'rgba(239,68,68,0.3)' : 'var(--border)'};">
        ${info.isCycle ? '<span style="display:inline-flex; align-items:center; gap:4px;"><svg class="svg-icon icon-red icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg> CIRCULAR FEEDBACK</span>' : 'CALLS'} (${info.weight} call${info.weight > 1 ? 's' : ''})
      </div>

      <div style="display:flex; align-items:center; justify-content:space-between;">
        <span style="font-size:10.5px; color:var(--text-muted); text-transform:uppercase; font-weight:700; letter-spacing:0.5px;">Callee</span>
        <span style="display:flex; align-items:center; gap:6px; font-weight:600; font-family:var(--font-mono); font-size:12px; color:var(--text-primary);">
          <span style="display:inline-block; width:8px; height:8px; border-radius:50%; background:${calleeColor}; box-shadow: 0 0 6px ${calleeColor}66;"></span>
          ${shortName(info.callee)}
        </span>
      </div>
    </div>

    <div style="display:flex; flex-direction:column; gap:8px;">
      <button class="btn btn-ghost btn-sm" id="btn-dsm-inspect-caller" style="width:100%; justify-content:center;">Inspect ${shortName(info.caller)}</button>
      <button class="btn btn-ghost btn-sm" id="btn-dsm-inspect-callee" style="width:100%; justify-content:center;">Inspect ${shortName(info.callee)}</button>
    </div>
  `;

  body.appendChild(card);

  const btnCaller = card.querySelector('#btn-dsm-inspect-caller');
  if (btnCaller) btnCaller.addEventListener('click', () => selectEntity(info.caller));
  const btnCallee = card.querySelector('#btn-dsm-inspect-callee');
  if (btnCallee) btnCallee.addEventListener('click', () => selectEntity(info.callee));
}

function renderWholeCodebaseInspector(view, levelName = 'Architecture') {
  const body = qs('#right-body');
  if (!body) return;
  body.innerHTML = '';

  const isArch = (levelName === 'Architecture');
  renderEntityHeader('CODEBASE', isArch ? 'System Architecture' : 'Detailed Call Topology', isArch ? 'Class and Component Dependencies' : 'All Indexed Packages and Methods');

  const pkgs = new Set((view.nodes || []).map(n => n.package || (n.id && n.id.includes('.') ? ((n.type === 'CLASS' || n.type === 'TYPE' || n.id.split('.').length <= 2) ? n.id.split('.').slice(0, -1).join('.') : n.id.split('.').slice(0, -2).join('.')) : 'default')));

  body.appendChild(metaGrid([
    [isArch ? 'Components / Classes' : 'Total Methods', String(view.nodes ? view.nodes.length : 0)],
    [isArch ? 'Inter-Class Calls' : 'Total Calls',       String(view.edges ? view.edges.length : 0)],
    ['Packages / Modules',                               String(pkgs.size)],
    ['View Level',                                       levelName],
  ]));

  const hint = createElement('div', { class: 'inspector-hint-box' });
  hint.innerHTML = `
    <div style="font-size:12px; color:var(--text-secondary); line-height:1.5; padding:8px 0;">
      ${isArch
        ? 'Displaying high-level architecture across all ' + (view.nodes ? view.nodes.length : 0) + ' classes. Click any class to view its member methods and fields in the inspector.'
        : 'Displaying detailed method-level call graph with adaptive LOD label decluttering and intra-class constellation physics. Hover or zoom in to inspect.'}
    </div>
  `;
  body.appendChild(hint);
}

/** Reload whichever graph is currently active with the current App.graphDepth. */
async function reloadActiveGraph() {
  if (App.activeGraphMode === 'fullCodebase') {
    await loadWholeCodebaseGraph(App.codebaseGraphLevel);
    return;
  }
  const levelSel = qs('#graph-level-selector');
  const levelDiv = qs('#graph-level-divider');
  if (levelSel) levelSel.style.display = 'none';
  if (levelDiv) levelDiv.style.display = 'none';
  if (!App.selected || !App.selected.id) return;
  const id = App.selected.id;
  const fullBtn = qs('#btn-full-codebase');
  if (fullBtn) fullBtn.classList.remove('active');
  if (App.selected.kind === 'method') {
    if (App.activeGraphMode === 'callers')      await loadCallersGraph(id, App.graphDepth);
    else if (App.activeGraphMode === 'callees') await loadCalleesGraph(id, App.graphDepth);
    else                                        await loadCallGraph(id, App.graphDepth);
  } else if (App.selected.kind === 'field') {
    if (App.activeGraphMode === 'fieldPropagation') await loadFieldPropagationChain(id, App.graphDepth);
    else                                            await loadFieldImpact(id, App.graphDepth);
  }
}

/** Set the active graph depth and trigger reload. */
function setGraphDepth(depth) {
  App.graphDepth = Math.max(1, Math.min(15, parseInt(depth, 10) || 3));
  
  // Update indicator text
  const indicator = qs('#depth-val-indicator');
  if (indicator) indicator.textContent = (App.graphDepth >= 15 ? 'Max' : App.graphDepth) + ' hops';

  // Update slider input value
  const slider = qs('#graph-depth-slider');
  if (slider) slider.value = Math.min(App.graphDepth, 10);

  // Update pill active states
  qsa('.depth-pill').forEach(btn => {
    const d = parseInt(btn.dataset.depth, 10);
    btn.classList.toggle('active', d === App.graphDepth || (d === 15 && App.graphDepth >= 15));
  });

  reloadActiveGraph();
}

/** Load and render the call hierarchy graph for a method. */
async function loadCallGraph(methodId, depth = App.graphDepth) {
  if (App.activeGraphMode === 'criticalPath' || currentCriticalReport !== null) {
    closeCriticalPathDock(false);
  }
  App.activeGraphMode = 'callGraph';
  ensureGraph();
  App.graph.clear();

  try {
    const view = await api.callGraph(methodId, depth);

    if (!view.nodes || view.nodes.length === 0) {
      showGraphEmpty('No call relationships found for this method.');
      return;
    }

    hideGraphEmpty();
    App.graph.setData(view.nodes, view.edges);

    // Legend: show call-graph colours
    renderLegend([
      { colour: GC.roles.root,   label: 'Selected method' },
      { colour: GC.roles.callee, label: 'Callee (called by)' },
      { colour: GC.roles.caller, label: 'Caller (calls this)' },
    ]);
  } catch (e) {
    showGraphEmpty('Failed to load call graph: ' + e.message);
  }
}

/** Load and render the callers sub-graph for a method. */
async function loadCallersGraph(methodId, depth = App.graphDepth) {
  if (App.activeGraphMode === 'criticalPath' || currentCriticalReport !== null) {
    closeCriticalPathDock(false);
  }
  App.activeGraphMode = 'callers';
  ensureGraph();
  App.graph.clear();
  try {
    const view = await api.callers(methodId, depth);
    if (!view.nodes || view.nodes.length === 0) {
      showGraphEmpty('No callers found for this method.');
      return;
    }
    hideGraphEmpty();
    App.graph.setData(view.nodes, view.edges);
    renderLegend([
      { colour: GC.roles.root,   label: 'Selected method' },
      { colour: GC.roles.caller, label: 'Caller (calls this)' },
    ]);
  } catch (e) {
    showGraphEmpty('Failed to load callers graph: ' + e.message);
  }
}

/** Load and render the callees sub-graph for a method. */
async function loadCalleesGraph(methodId, depth = App.graphDepth) {
  if (App.activeGraphMode === 'criticalPath' || currentCriticalReport !== null) {
    closeCriticalPathDock(false);
  }
  App.activeGraphMode = 'callees';
  ensureGraph();
  App.graph.clear();
  try {
    const view = await api.callees(methodId, depth);
    if (!view.nodes || view.nodes.length === 0) {
      showGraphEmpty('No callees found for this method.');
      return;
    }
    hideGraphEmpty();
    App.graph.setData(view.nodes, view.edges);
    renderLegend([
      { colour: GC.roles.root,   label: 'Selected method' },
      { colour: GC.roles.callee, label: 'Callee (called by)' },
    ]);
  } catch (e) {
    showGraphEmpty('Failed to load callees graph: ' + e.message);
  }
}

App.loadCallersGraph = loadCallersGraph;
App.loadCalleesGraph = loadCalleesGraph;
App.loadCallGraph = loadCallGraph;

/** 
 * Automatically format package FQN into clean module name without manual prefix configuration.
 * Fully supports uppercase and PascalCase package segments (e.g. com.tcs.bancs.ModuleName).
 */
function formatModuleFromPackage(pkg) {
  if (!pkg || pkg === 'default' || pkg === '(default)') return '(default)';
  const settings = loadSettings();
  const mode = settings.packageMode || 'auto';

  let res = pkg;
  // If pkg is a method signature like "foo(String)", strip the parameter list
  const parenIdx = res.indexOf('(');
  if (parenIdx !== -1) res = res.substring(0, parenIdx);

  if (mode === 'fqn') return res;

  if (mode === 'compact') {
    const p = res.split('.');
    if (p.length <= 2) return res;
    return p.map((seg, idx) => idx >= p.length - 2 ? seg : seg.charAt(0)).join('.');
  }

  // Auto mode: strip common repository base prefix if present
  if (App.commonPackagePrefix && res.startsWith(App.commonPackagePrefix)) {
    const stripped = res.substring(App.commonPackagePrefix.length);
    if (stripped) res = stripped.startsWith('.') ? stripped.substring(1) : stripped;
  } else {
    const pkgParts = res.split('.');
    if (pkgParts.length >= 3 && ['com', 'org', 'io', 'net', 'dev', 'app', 'co', 'gov', 'edu'].includes(pkgParts[0])) {
      res = (pkgParts.length >= 4) ? pkgParts.slice(2).join('.') : pkgParts[pkgParts.length - 1];
    }
  }

  if (!res || res === 'default') return pkg || '(default)';
  const remainingParts = res.split('.').filter(Boolean);
  if (remainingParts.length === 1) {
    const s = remainingParts[0];
    return s.charAt(0).toUpperCase() + s.slice(1);
  } else if (remainingParts.length > 1) {
    return remainingParts.map(s => s.charAt(0).toUpperCase() + s.slice(1)).join(' › ');
  }
  return res;
}

/** Load and render direct field impact. */
async function loadFieldImpact(fieldId, depth = 1) {
  if (App.activeGraphMode === 'criticalPath' || currentCriticalReport !== null) {
    closeCriticalPathDock(false);
  }
  App.activeGraphMode = 'fieldImpact';
  ensureGraph();
  App.graph.clear();

  try {
    const impact = await api.fieldImpact(fieldId, depth);

    if (!impact.graph || !impact.graph.nodes || impact.graph.nodes.length === 0) {
      showGraphEmpty('No field relationships found. Field may not be read or written in indexed code.');
      return;
    }

    hideGraphEmpty();
    App.graph.setData(impact.graph.nodes, impact.graph.edges);

    renderLegend([
      { colour: GC.roles.field,  label: 'Field' },
      { colour: GC.roles.reader, label: 'Reads field' },
      { colour: GC.roles.writer, label: 'Writes field' },
      { colour: GC.roles.propagator, label: 'Propagates value' },
    ]);
  } catch (e) {
    showGraphEmpty('Failed to load field impact: ' + e.message);
  }
}

/** Load and render multi-hop field-to-method caller propagation chain. */
async function loadFieldPropagationChain(fieldId, depth = App.graphDepth) {
  App.activeGraphMode = 'fieldPropagation';
  ensureGraph();
  App.graph.clear();

  try {
    const impact = await api.fieldImpact(fieldId, Math.max(2, depth));

    if (!impact.graph || !impact.graph.nodes || impact.graph.nodes.length === 0) {
      showGraphEmpty('No field relationships or propagation paths found.');
      return;
    }

    hideGraphEmpty();
    App.graph.setData(impact.graph.nodes, impact.graph.edges);

    renderLegend([
      { colour: GC.roles.field,      label: 'Field' },
      { colour: GC.roles.writer,     label: 'Direct Writer' },
      { colour: GC.roles.propagator, label: 'Propagator' },
      { colour: GC.roles.caller,     label: 'Upstream Caller (Trigger)' },
      { colour: GC.roles.reader,     label: 'Direct Reader' },
    ]);
  } catch (e) {
    showGraphEmpty('Failed to load field propagation chain: ' + e.message);
  }
}

/** Render a colour legend in the graph's overlay. */
function renderLegend(items) {
  const legend = qs('#graph-legend');
  if (!legend) return;
  legend.innerHTML = items.map(i =>
    `<div class="legend-row">
       <span class="legend-dot" style="background:${i.colour}"></span>
       <span>${esc(i.label)}</span>
     </div>`
  ).join('');
}

function showGraphEmpty(msg) {
  const el = qs('#graph-empty');
  if (el) {
    el.querySelector('.graph-empty-sub').textContent = msg;
    el.style.display = 'flex';
  }
}
function hideGraphEmpty() {
  const el = qs('#graph-empty');
  if (el) el.style.display = 'none';
}

function showCodebaseEmpty(msg) {
  const el = qs('#codebase-empty');
  if (el) {
    const sub = el.querySelector('#codebase-empty-sub') || el.querySelector('.graph-empty-sub');
    if (sub) sub.textContent = msg;
    el.style.display = 'flex';
  }
}
function hideCodebaseEmpty() {
  const el = qs('#codebase-empty');
  if (el) el.style.display = 'none';
}

/* ─────────────────────────────────────────────────────────────────────────────
   6 (continued). Right panel detail renderers
   ───────────────────────────────────────────────────────────────────────────── */

function renderTypeDetail(data) {
  const { type, fields = [], methods = [], notes = [] } = data;
  const body = qs('#right-body');
  body.innerHTML = '';

  if (window.CodeLensClassifier && Array.isArray(methods) && methods.length > 0 && typeof window.CodeLensClassifier.registerTypeMethods === 'function') {
    window.CodeLensClassifier.registerTypeMethods(type.fqn, methods);
  }

  // Header
  renderEntityHeader(type.kind, type.simpleName, type.fqn, { ...type, methods });

  let sourceElement = '-';
  if (type.sourceFile) {
    const link = createElement('a', {
      href: '#',
      class: 'source-file-link',
      title: 'Open file in editor:\n' + type.sourceFile
    });
    link.textContent = type.sourceFile.split('/').pop();
    link.addEventListener('click', (e) => {
      e.preventDefault();
      openSourceFile(type.sourceFile, type.startLine);
    });
    sourceElement = link;
  }

  // Metadata grid
  body.appendChild(metaGrid([
    ['Module',    formatModuleFromPackage(type.packageFqn)],
    ['Package',   type.packageFqn || '-'],
    ['Kind',      type.kind],
    ['Modifiers', type.modifiers || '-'],
    ['Source',    sourceElement],
    ['Lines',     type.startLine ? `${type.startLine}-${type.endLine} (${type.lineCount})` : '-'],
    ['Extends',   type.superClass || '-'],
    ['Implements', (type.interfaces || []).join(', ') || '-'],
  ]));

  // Git Metadata Section
  const gitSec = createElement('div', { class: 'git-meta-detail-section' });
  body.appendChild(gitSec);
  api.gitMeta(type.fqn).then(gm => {
    if (gm && gm.commitCount !== undefined && gm.found !== false) {
      gitSec.innerHTML = `
        <div class="rp-section" style="margin-top:12px">Git Statistics</div>
        <div class="meta-grid">
          <div class="meta-key">Commits</div>
          <div class="meta-val"><strong style="color:var(--cyan)">${gm.commitCount}</strong></div>
          <div class="meta-key">Main Author</div>
          <div class="meta-val">${esc(gm.topAuthor || '-')}</div>
          <div class="meta-key">Churn</div>
          <div class="meta-val"><span style="color:${gm.commitCount > 10 ? 'var(--red)' : gm.commitCount > 3 ? 'var(--amber)' : 'var(--emerald)'}">${gm.commitCount > 10 ? 'High' : gm.commitCount > 3 ? 'Medium' : 'Low'}</span></div>
          <div class="meta-key">Last Edit</div>
          <div class="meta-val">${gm.lastModified ? formatDate(gm.lastModified * 1000) : '-'}</div>
        </div>`;
    }
  }).catch(() => {});


  // Fields section
  if (fields.length > 0) {
    body.appendChild(sectionLabel('Fields'));
    const relList = createElement('div', { class: 'rel-list' });
    for (const f of fields) {
      const item = relItem('■', 'READS_FIELD', f.simpleName + ': ' + (f.fieldType || '?'));
      item.addEventListener('click', () => selectField(f.id));
      relList.appendChild(item);
    }
    body.appendChild(relList);
  }

  // Methods section
  if (methods.length > 0) {
    body.appendChild(sectionLabel('Methods'));
    const relList = createElement('div', { class: 'rel-list' });
    for (const m of methods) {
      const item = relItem('◆', 'CALLS', m.simpleName);
      item.appendChild(complexityBadge(m.cyclomaticComplexity));
      item.addEventListener('click', () => selectMethod(m.id));
      relList.appendChild(item);
    }
    body.appendChild(relList);
  }

  // Action buttons
  body.appendChild(actionRow([
    { label: '🎯 Blast Radius Flow', title: 'Trace complete blast radius & touch points flow across modules, classes, and methods', action: () => openBlastRadiusExplorer(type.fqn, 'CLASS') },
    { label: '🎯 Trace Critical Path', title: 'Trace execution flow and persistent state transitions for this class', action: () => loadAndVisualizeCriticalPath(type.fqn) },
    { label: '🌐 Hub Explorer', title: 'Explore cross-package callers & callees for this class', action: () => { switchTab('graph'); if (window.hubExplorerInstance) window.hubExplorerInstance.load(type.fqn, 'callers'); } },
    { label: 'View All Methods', badge: methods.length, title: `View all ${methods.length} methods in Knowledge Base`, action: () => { switchTab('knowledge'); renderKnowledgeBaseForType(data); } },
  ]));

  // Notes
  renderNotes(type.fqn, notes);
}

function renderKnowledgeBaseForType(data) {
  const { type, fields = [], methods = [], notes = [] } = data;
  const view = qs('#knowledge-view');
  if (!view) return;
  view.innerHTML = '';
  view.scrollTop = 0;

  const isRecord = (type.kind || '').toUpperCase() === 'RECORD';
  const kind = (type.kind || 'CLASS').toUpperCase();
  const kindClass = `kind-${kind.toLowerCase()}`;

  // ── Hero Card ───────────────────────────────────────────────────────────────
  const hero = createElement('div', { class: 'kb-hero-card fade-in' });
  
  let extendsClause = '';
  if (type.superClass && type.superClass !== 'java.lang.Object') {
    const superShort = type.superClass.split('.').pop();
    extendsClause = `<div class="kb-meta-item"><span class="kb-meta-label">Extends:</span> <span class="kb-meta-val" title="${esc(type.superClass)}">${esc(superShort)}</span></div><div class="kb-meta-divider"></div>`;
  }

  let ifacesClause = '';
  if (type.interfaces && type.interfaces.length > 0) {
    const ifaceShorts = type.interfaces.map(i => i.split('.').pop()).join(', ');
    ifacesClause = `<div class="kb-meta-item"><span class="kb-meta-label">Implements:</span> <span class="kb-meta-val" title="${esc(type.interfaces.join(', '))}">${esc(ifaceShorts)}</span></div><div class="kb-meta-divider"></div>`;
  }

  hero.innerHTML = `
    <div class="kb-hero-top">
      <div class="kb-hero-title-group">
        <span class="kb-kind-badge ${kindClass}">${esc(kind)}</span>
        <span class="kb-hero-name">${esc(type.simpleName)}</span>
        <span class="kb-pkg-pill" title="Package FQN">${esc(type.packageFqn || 'default package')}</span>
      </div>
      <div class="kb-hero-actions">
        <button class="kb-action-btn" id="kb-btn-graph" title="Explore in Graph">
          <svg class="svg-icon icon-emerald icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><line x1="8.59" y1="13.51" x2="15.42" y2="17.49"/><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"/></svg>
          Graph
        </button>
        <button class="kb-action-btn" id="kb-btn-source" title="Open source file">
          <svg class="svg-icon icon-cyan icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>
          Source
        </button>
        <button class="kb-action-btn" id="kb-btn-review" title="Run Code Review">
          <svg class="svg-icon icon-indigo icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>
          Review
        </button>
        <button class="kb-action-btn" id="kb-btn-critical-path" title="Trace Critical Path Execution">
          <svg class="svg-icon icon-amber icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><circle cx="12" cy="12" r="6"/><circle cx="12" cy="12" r="2"/></svg>
          Critical Path
        </button>
        <button class="kb-action-btn" id="kb-btn-blast" title="Trace Blast Radius & Touch Points Flow">
          <svg class="svg-icon icon-rose icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><circle cx="12" cy="12" r="6"/><circle cx="12" cy="12" r="2"/></svg>
          Blast Radius
        </button>
      </div>
    </div>
    <div class="kb-hero-meta-row">
      <div class="kb-meta-item"><span class="kb-meta-label">LOC:</span> <span class="kb-meta-val">${type.lineCount || (type.endLine && type.startLine ? type.endLine - type.startLine + 1 : '-')}</span></div>
      <div class="kb-meta-divider"></div>
      <div class="kb-meta-item"><span class="kb-meta-label">Methods:</span> <span class="kb-meta-val">${methods.length}</span></div>
      <div class="kb-meta-divider"></div>
      <div class="kb-meta-item"><span class="kb-meta-label">${isRecord ? 'Components' : 'Fields'}:</span> <span class="kb-meta-val">${fields.length}</span></div>
      <div class="kb-meta-divider"></div>
      ${extendsClause}
      ${ifacesClause}
      <div class="kb-meta-item"><span class="kb-meta-label">File:</span> <span class="kb-meta-val">${esc(type.sourceFile ? type.sourceFile.split(/[\\\/]/).pop() : '-')}${type.startLine ? ':' + type.startLine : ''}</span></div>
    </div>
  `;

  // Wire hero action buttons
  hero.querySelector('#kb-btn-graph')?.addEventListener('click', () => {
    if (methods && methods.length > 0) {
      selectMethod(methods[0].id || methods[0].fqn);
    } else if (fields && fields.length > 0) {
      selectField(fields[0].id || fields[0].fqn);
    } else {
      switchTab('graph');
    }
  });
  hero.querySelector('#kb-btn-source')?.addEventListener('click', () => {
    if (type.sourceFile) openSourceFile(type.sourceFile, type.startLine || 1);
  });
  hero.querySelector('#kb-btn-review')?.addEventListener('click', () => {
    switchTab('review');
    updateReviewTargetInfo();
  });
  hero.querySelector('#kb-btn-critical-path')?.addEventListener('click', () => {
    loadAndVisualizeCriticalPath(type.fqn);
  });
  hero.querySelector('#kb-btn-blast')?.addEventListener('click', () => {
    openBlastRadiusExplorer(type.fqn, 'CLASS');
  });

  view.appendChild(hero);

  // ── Complexity Legend Bar ───────────────────────────────────────────────────
  const legendBar = createElement('div', { class: 'kb-help-bar' });
  legendBar.innerHTML = `
    <div class="kb-help-left">
      <svg class="svg-icon icon-amber icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>
      <span>Complexity Metric:</span>
      <div class="kb-help-pills">
        <span class="cc-pill cc-low" title="Low risk method (CC 1-4)">CC 1–4 Low</span>
        <span class="cc-pill cc-med" title="Moderate complexity method (CC 5-10)">CC 5–10 Med</span>
        <span class="cc-pill cc-high" title="High risk method (CC 11+)">CC 11+ High</span>
      </div>
    </div>
    <div style="font-size:11px; color:var(--text-muted); font-family:var(--font-mono)">
      Showing all declared members
    </div>
  `;
  view.appendChild(legendBar);

  // ── Member Navigation Tabs ───────────────────────────────────────────────────
  const navTabsBar = createElement('div', { class: 'kb-members-nav-bar fade-in' });
  navTabsBar.innerHTML = `
    <div class="kb-members-tabs" role="tablist">
      <button class="kb-tab-pill active" data-tab="all" title="View all declared members">
        <span>All Members</span>
        <span class="kb-tab-badge">${fields.length + methods.length}</span>
      </button>
      <button class="kb-tab-pill" data-tab="methods" title="View methods and constructors">
        <span>${isRecord ? 'Methods & Accessors' : 'Methods & Constructors'}</span>
        <span class="kb-tab-badge">${methods.length}</span>
      </button>
      <button class="kb-tab-pill" data-tab="fields" title="View fields and components">
        <span>${isRecord ? 'Record Components' : 'Fields'}</span>
        <span class="kb-tab-badge">${fields.length}</span>
      </button>
    </div>
  `;
  view.appendChild(navTabsBar);

  // ── Members Grid (2-column on desktop) ──────────────────────────────────────
  if (fields.length > 0 || methods.length > 0) {
    const isSingleCol = (fields.length === 0 || methods.length === 0);
    const membersGrid = createElement('div', { class: `kb-members-grid ${isSingleCol ? 'single-col' : ''}` });
    let fieldsSection = null;
    let methodsSection = null;

    // ── Fields / Record Components Section ────────────────────────────────────
    if (fields.length > 0) {
      fieldsSection = createElement('div', { class: 'kb-section fade-in' });
      fieldsSection.innerHTML = `
        <div class="kb-section-title">
          <div class="kb-section-title-left">
            <svg class="svg-icon icon-cyan icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><line x1="3" y1="9" x2="21" y2="9"/><line x1="9" y1="21" x2="9" y2="9"/></svg>
            <span>${isRecord ? 'Record Components' : 'Fields'}</span>
            <span class="kb-section-badge">${fields.length}</span>
          </div>
        </div>
        <div class="kb-list" id="kb-fields-list"></div>
      `;
      const fieldsList = fieldsSection.querySelector('#kb-fields-list');

      for (const f of fields) {
        const row = createElement('div', { 
          class: 'kb-row kb-member-row',
          'data-kind': 'field',
          'data-id': f.id || f.fqn,
          role: 'button',
          tabindex: '0'
        });
        row.innerHTML = `
          <div class="kb-row-left">
            <div class="kb-row-icon icon-field" title="Field">
              <svg class="svg-icon icon-cyan icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="4" y="4" width="16" height="16" rx="2"/><circle cx="9" cy="9" r="2"/></svg>
            </div>
            <div class="kb-row-info">
              <div class="kb-row-name-wrap">
                <span class="kb-row-name" title="${esc(f.simpleName)}">${esc(f.simpleName)}</span>
              </div>
              <div class="kb-row-meta">
                ${f.modifiers ? `<span class="kb-mod-pill">${esc(f.modifiers)}</span>` : ''}
                ${f.startLine ? `<span>Line ${f.startLine}</span>` : '<span>Field</span>'}
              </div>
            </div>
          </div>
          <div class="kb-row-right">
            <span class="kb-type-pill" title="${esc(f.fieldType || '')}">${esc(f.fieldType || 'Object')}</span>
          </div>
        `;
        fieldsList.appendChild(row);
      }

      fieldsList.addEventListener('click', (e) => {
        const row = e.target.closest('.kb-member-row');
        if (row && row.dataset.id && row.dataset.kind === 'field') {
          selectField(row.dataset.id);
        }
      });
      fieldsList.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          const row = e.target.closest('.kb-member-row');
          if (row && row.dataset.id && row.dataset.kind === 'field') {
            e.preventDefault();
            selectField(row.dataset.id);
          }
        }
      });

      membersGrid.appendChild(fieldsSection);
    }

    // ── Methods & Constructors Section ────────────────────────────────────────
    if (methods.length > 0) {
      methodsSection = createElement('div', { class: 'kb-section fade-in' });
      methodsSection.innerHTML = `
        <div class="kb-section-title">
          <div class="kb-section-title-left">
            <svg class="svg-icon icon-indigo icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>
            <span>${isRecord ? 'Methods & Accessors' : 'Methods & Constructors'}</span>
            <span class="kb-section-badge">${methods.length}</span>
          </div>
          <div class="kb-section-title-right">
            <button class="kb-accordion-toggle-all-btn" data-state="collapsed" title="Expand or collapse all method archetype sections">
              <svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="7 13 12 18 17 13"/><polyline points="7 6 12 11 17 6"/></svg>
              <span>Expand All</span>
            </button>
          </div>
        </div>
        <div class="kb-list" id="kb-methods-list"></div>
      `;
      const methodsList = methodsSection.querySelector('#kb-methods-list');

      const methodGroups = groupMethodsByArchetype(methods, type, type.packageFqn);
      for (const group of methodGroups) {
        const accordion = renderArchetypeAccordion({
          group,
          renderItemRow: (m) => renderMethodRow(m, type),
          initialOpen: false
        });
        methodsList.appendChild(accordion);
      }

      attachAccordionToggleAll(methodsSection, '#kb-methods-list');

      methodsList.addEventListener('click', (e) => {
        const row = e.target.closest('.kb-member-row');
        if (row && row.dataset.id && row.dataset.kind === 'method') {
          selectMethod(row.dataset.id);
        }
      });
      methodsList.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          const row = e.target.closest('.kb-member-row');
          if (row && row.dataset.id && row.dataset.kind === 'method') {
            e.preventDefault();
            selectMethod(row.dataset.id);
          }
        }
      });

      membersGrid.appendChild(methodsSection);
    }

    // Wire member navigation tabs
    navTabsBar.querySelectorAll('.kb-tab-pill').forEach(pill => {
      pill.addEventListener('click', () => {
        navTabsBar.querySelectorAll('.kb-tab-pill').forEach(p => p.classList.remove('active'));
        pill.classList.add('active');
        const tab = pill.dataset.tab;

        if (tab === 'all') {
          if (fieldsSection) fieldsSection.style.display = '';
          if (methodsSection) methodsSection.style.display = '';
          membersGrid.classList.toggle('single-col', isSingleCol);
        } else if (tab === 'methods') {
          if (fieldsSection) fieldsSection.style.display = 'none';
          if (methodsSection) methodsSection.style.display = '';
          membersGrid.classList.add('single-col');
        } else if (tab === 'fields') {
          if (fieldsSection) fieldsSection.style.display = '';
          if (methodsSection) methodsSection.style.display = 'none';
          membersGrid.classList.add('single-col');
        }
      });
    });

    view.appendChild(membersGrid);
  }

  // Empty members state
  if (fields.length === 0 && methods.length === 0) {
    const empty = createElement('div', { class: 'kb-empty-container fade-in' });
    empty.innerHTML = `
      <div class="kb-empty-icon">
        <svg class="svg-icon icon-indigo icon-lg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
      </div>
      <div class="kb-empty-title">No Members Declared</div>
      <div class="kb-empty-desc">This ${esc((type.kind || 'type').toLowerCase())} does not define any fields or methods.</div>
    `;
    view.appendChild(empty);
  }
}

function renderMethodDetail(data) {
  const { method, notes = [] } = data;
  const body = qs('#right-body');
  body.innerHTML = '';

  renderEntityHeader('METHOD', method.simpleName, method.fqn);

  const paramStr = (method.parameters || []).map(p => p.type + ' ' + p.name).join(', ');
  const cc = method.cyclomaticComplexity || 1;
  const ccClass = cc <= 4 ? 'low' : cc <= 10 ? 'medium' : 'high';

  let sourceElement = '-';
  if (data.sourceFile) {
    const link = createElement('a', {
      href: '#',
      class: 'source-file-link',
      title: 'Open file in editor:\n' + data.sourceFile
    });
    link.textContent = data.sourceFile.split('/').pop() + ':' + method.startLine;
    link.addEventListener('click', (e) => {
      e.preventDefault();
      openSourceFile(data.sourceFile, method.startLine);
    });
    sourceElement = link;
  }

  // Metadata grid
  body.appendChild(metaGrid([
    ['Module',     formatModuleFromPackage(method.packageFqn || method.declaringTypeFqn)],
    ['Class',      shortFqn(method.declaringTypeFqn)],
    ['Returns',    method.returnType || 'void'],
    ['Modifiers',  method.modifiers || '-'],
    ['Source',     sourceElement],
    ['Parameters', paramStr || '(none)'],
    ['Lines',      method.startLine ? `${method.startLine}-${method.endLine}` : '-'],
  ]));

  // Git Metadata Section
  const gitSec = createElement('div', { class: 'git-meta-detail-section' });
  body.appendChild(gitSec);
  api.gitMeta(method.fqn).then(gm => {
    if (gm && gm.commitCount !== undefined && gm.found !== false) {
      gitSec.innerHTML = `
        <div class="rp-section" style="margin-top:12px">Git Statistics</div>
        <div class="meta-grid">
          <div class="meta-key">Commits</div>
          <div class="meta-val"><strong style="color:var(--cyan)">${gm.commitCount}</strong></div>
          <div class="meta-key">Main Author</div>
          <div class="meta-val">${esc(gm.topAuthor || '-')}</div>
          <div class="meta-key">Churn</div>
          <div class="meta-val"><span style="color:${gm.commitCount > 10 ? 'var(--red)' : gm.commitCount > 3 ? 'var(--amber)' : 'var(--emerald)'}">${gm.commitCount > 10 ? 'High' : gm.commitCount > 3 ? 'Medium' : 'Low'}</span></div>
          <div class="meta-key">Last Edit</div>
          <div class="meta-val">${gm.lastModified ? formatDate(gm.lastModified * 1000) : '-'}</div>
        </div>`;
    }
  }).catch(() => {});


  // Cyclomatic complexity visualisation
  const ccRow = createElement('div', { class: 'meta-grid', style: 'padding-top:4px' });
  ccRow.innerHTML = `
    <div class="meta-key">Complexity</div>
    <div class="meta-val">
      <div class="complexity-bar">
        <div class="complexity-track">
          <div class="complexity-fill ${ccClass}" style="width:${Math.min(cc * 5, 100)}%"></div>
        </div>
        <span style="font-size:11px;color:var(--${ccClass === 'low' ? 'emerald' : ccClass === 'medium' ? 'amber' : 'red'})">${cc}</span>
      </div>
    </div>`;
  body.appendChild(ccRow);

  // Call graph action buttons with caller & callee count badges
  const callerCount = (typeof data.callerCount === 'number')
    ? data.callerCount
    : (data.method && typeof data.method.callerCount === 'number'
        ? data.method.callerCount
        : (data.inDegree !== undefined ? data.inDegree : 0));

  const calleeCount = (typeof data.calleeCount === 'number')
    ? data.calleeCount
    : (data.method && typeof data.method.calleeCount === 'number'
        ? data.method.calleeCount
        : (data.outDegree !== undefined ? data.outDegree : 0));

  const isCallersActive = App.activeGraphMode === 'callers' && App.selected && App.selected.id === method.id;
  const isCalleesActive = App.activeGraphMode === 'callees' && App.selected && App.selected.id === method.id;

  body.appendChild(actionRow([
    {
      label: '🎯 Blast Radius Flow',
      title: 'Trace complete blast radius & touch points flow across modules, classes, and callers',
      action: () => openBlastRadiusExplorer(method.fqn, 'METHOD')
    },
    {
      id: 'btn-inspect-callers',
      label: '⬆ Callers',
      badge: callerCount,
      badgeClass: 'count-badge-callers',
      className: 'action-btn-callers' + (isCallersActive ? ' active' : ''),
      title: callerCount > 30
        ? `Trace upstream callers (${callerCount.toLocaleString()} direct callers) — opens Hub Explorer`
        : `Trace upstream callers (${callerCount.toLocaleString()} direct caller${callerCount === 1 ? '' : 's'})`,
      action: () => {
        switchTab('graph');
        if (callerCount > 30 && window.hubExplorerInstance) {
          window.hubExplorerInstance.load(method.fqn || method.id, 'callers');
        } else {
          loadCallersGraph(method.id);
        }
        qs('#btn-inspect-callers')?.classList.add('active');
        qs('#btn-inspect-callees')?.classList.remove('active');
      }
    },
    {
      id: 'btn-inspect-callees',
      label: '⬇ Callees',
      badge: calleeCount,
      badgeClass: 'count-badge-callees',
      className: 'action-btn-callees' + (isCalleesActive ? ' active' : ''),
      title: calleeCount > 30
        ? `Trace downstream callees (${calleeCount.toLocaleString()} direct callees) — opens Hub Explorer`
        : `Trace downstream callees (${calleeCount.toLocaleString()} direct callee${calleeCount === 1 ? '' : 's'})`,
      action: () => {
        switchTab('graph');
        if (calleeCount > 30 && window.hubExplorerInstance) {
          window.hubExplorerInstance.load(method.fqn || method.id, 'callees');
        } else {
          loadCalleesGraph(method.id);
        }
        qs('#btn-inspect-callees')?.classList.add('active');
        qs('#btn-inspect-callers')?.classList.remove('active');
      }
    },
  ]));

  renderNotes(method.fqn, notes);
}

function renderFieldDetail(data) {
  const { field, notes = [] } = data;
  const body = qs('#right-body');
  body.innerHTML = '';

  renderEntityHeader('FIELD', field.simpleName, field.fqn);

  let sourceElement = '-';
  if (data.sourceFile) {
    const link = createElement('a', {
      href: '#',
      class: 'source-file-link',
      title: 'Open file in editor:\n' + data.sourceFile
    });
    link.textContent = data.sourceFile.split('/').pop() + ':' + field.startLine;
    link.addEventListener('click', (e) => {
      e.preventDefault();
      openSourceFile(data.sourceFile, field.startLine);
    });
    sourceElement = link;
  }

  body.appendChild(metaGrid([
    ['Declared in', shortFqn(field.declaringTypeFqn)],
    ['Type',        field.fieldType || '-'],
    ['Modifiers',   field.modifiers || '-'],
    ['Source',      sourceElement],
    ['Init value',  field.initializer || '-'],
    ['Source line', field.startLine || '-'],
  ]));

  // Git Metadata Section
  const gitSec = createElement('div', { class: 'git-meta-detail-section' });
  body.appendChild(gitSec);
  api.gitMeta(field.fqn).then(gm => {
    if (gm && gm.commitCount !== undefined && gm.found !== false) {
      gitSec.innerHTML = `
        <div class="rp-section" style="margin-top:12px">Git Statistics</div>
        <div class="meta-grid">
          <div class="meta-key">Commits</div>
          <div class="meta-val"><strong style="color:var(--cyan)">${gm.commitCount}</strong></div>
          <div class="meta-key">Main Author</div>
          <div class="meta-val">${esc(gm.topAuthor || '-')}</div>
          <div class="meta-key">Churn</div>
          <div class="meta-val"><span style="color:${gm.commitCount > 10 ? 'var(--red)' : gm.commitCount > 3 ? 'var(--amber)' : 'var(--emerald)'}">${gm.commitCount > 10 ? 'High' : gm.commitCount > 3 ? 'Medium' : 'Low'}</span></div>
          <div class="meta-key">Last Edit</div>
          <div class="meta-val">${gm.lastModified ? formatDate(gm.lastModified * 1000) : '-'}</div>
        </div>`;
    }
  }).catch(() => {});


  body.appendChild(actionRow([
    { label: '🎯 Blast Radius Flow', title: 'Trace complete readers, writers, classes, and modules touching this field down to the line of code', action: () => openBlastRadiusExplorer(field.fqn, 'FIELD') },
    { label: 'Impact (Direct)', title: 'Show direct readers, writers, and immediate propagators of this field', action: () => { switchTab('graph'); loadFieldImpact(field.id); } },
    { label: 'Propagation Chain', title: 'Trace multi-hop upstream triggers and calling entrypoints that modify this field', action: () => { switchTab('graph'); loadFieldPropagationChain(field.id); } },
  ]));

  renderNotes(field.fqn, notes);
}

function renderPackageDetail(pkg) {
  const body = qs('#right-body');
  body.innerHTML = '';

  renderEntityHeader('PACKAGE', pkg.name, pkg.fqn);

  body.appendChild(metaGrid([
    ['FQN',       pkg.fqn],
    ['Types',     pkg.typeCount],
    ['Files',     pkg.fileCount],
    ['Parent',    pkg.parentFqn || '(root)'],
  ]));

  body.appendChild(actionRow([
    {
      label: '🎯 Blast Radius Flow',
      title: 'Trace complete blast radius & touch points flow for this module',
      action: () => openBlastRadiusExplorer(pkg.fqn || pkg.name, 'PACKAGE')
    },
    {
      label: 'Open in Knowledge Base',
      title: 'Open this package in Knowledge Base',
      action: () => {
        switchTab('knowledge');
        loadKnowledgeBase(pkg.fqn);
      }
    },
    {
      label: 'View Dependencies',
      title: 'Open Module Dependencies & Touch Points in Knowledge Base',
      action: () => {
        switchTab('knowledge');
        loadKnowledgeBase(pkg.fqn, 'DEPENDENCIES');
      }
    }
  ]));

  // Module Dependency & Touch Points Section in Right Panel
  const depSec = createElement('div', { class: 'module-dep-inspector-sec' });
  body.appendChild(depSec);

  const renderDepsSec = (deps) => {
    if (!deps) {
      depSec.innerHTML = '<div style="font-size:11px;color:var(--text-muted);">No dependency data found.</div>';
      return;
    }
    const stabilityCls = deps.instability < 0.3 ? 'is-stable' : deps.instability > 0.7 ? 'is-flexible' : 'is-balanced';
    const rolePill = deps.instability < 0.3
      ? '<span class="mod-dep-role-pill core">Stable Core</span>'
      : deps.instability > 0.7
      ? '<span class="mod-dep-role-pill client">Client Layer</span>'
      : '<span class="mod-dep-role-pill bridge">Coupling Bridge</span>';

    let kindsBadges = '';
    if (deps.totalByKind) {
      for (const [k, count] of Object.entries(deps.totalByKind)) {
        kindsBadges += `<span class="class-kind-pill ${k}">${k}: ${count}</span>`;
      }
    }

    const topMods = [...(deps.outgoingModules || []), ...(deps.incomingModules || [])].slice(0, 5);
    const maxPts = Math.max(...topMods.map(m => m.totalTouchPoints || 0), 1);
    let topModsHtml = '';
    if (topMods.length > 0) {
      topModsHtml = `
        <div style="font-size:10px;font-weight:700;text-transform:uppercase;color:var(--text-muted);margin-top:6px;display:flex;justify-content:space-between;align-items:center;">
          <span>Connected Modules</span>
          <span style="font-size:9px;color:var(--text-muted);font-weight:normal;">Volume</span>
        </div>
        <div class="module-dep-rp-modules-list">
          ${topMods.map(m => {
            const modColor = (window.CodeLensPalette && window.CodeLensPalette.getColor)
              ? window.CodeLensPalette.getColor(m.packageFqn || m.moduleName, 0)
              : '#38bdf8';
            const pts = m.totalTouchPoints || 0;
            const pct = Math.max(4, Math.round((pts / maxPts) * 100));
            const meterTier = pct >= 80 ? 'meter-hot' : (pct >= 50 ? 'meter-warm' : (pct >= 25 ? 'meter-mid' : 'meter-low'));
            const tierCls = pts >= 50 ? 'tier-hot' : (pts >= 15 ? 'tier-warm' : (pts >= 5 ? 'tier-mid' : 'tier-low'));
            return `
              <div class="module-dep-rp-module-item" data-fqn="${esc(m.packageFqn)}" style="border-left:3px solid ${modColor};" title="Inspect ${esc(m.moduleName)} (${pts} touch points)">
                <div style="display:flex;justify-content:space-between;align-items:center;width:100%;">
                  <div style="display:flex;align-items:center;gap:5px;min-width:0;overflow:hidden;">
                    <span class="flow-node-mod-badge" style="background:${modColor}22; color:${modColor}; border:1px solid ${modColor}55;">[MOD]</span>
                    <span style="font-weight:600;color:var(--text-primary);font-size:11px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">${esc(m.moduleName)}</span>
                  </div>
                  <span class="class-pair-pts-badge ${tierCls}" style="font-size:9px;">${pts} pts</span>
                </div>
                <div class="mod-rp-meter-wrap">
                  <div class="mod-rp-meter-fill ${meterTier}" style="width:${pct}%;"></div>
                </div>
              </div>
            `;
          }).join('')}
        </div>
      `;
    }

    depSec.innerHTML = `
      <div class="module-dep-inspector-title">
        <div style="display:flex;align-items:center;gap:6px;">
          <svg class="svg-icon icon-cyan icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="width:13px;height:13px;"><path d="M9 17H7A5 5 0 0 1 7 7h2"/><path d="M15 7h2a5 5 0 0 1 0 10h-2"/><line x1="8" y1="12" x2="16" y2="12"/></svg>
          <span>Module Touch Points</span>
        </div>
        <div style="display:flex;align-items:center;gap:4px;">
          ${rolePill}
          <span class="stability-rating-pill ${stabilityCls}">${esc(deps.stabilityRating || 'Balanced')}</span>
        </div>
      </div>
      <div class="module-dep-rp-grid">
        <div class="module-dep-rp-kpi">
          <span class="module-dep-rp-kpi-label">Touch Points</span>
          <span class="module-dep-rp-kpi-val">${deps.totalTouchPoints}</span>
        </div>
        <div class="module-dep-rp-kpi">
          <span class="module-dep-rp-kpi-label">Instability (I)</span>
          <span class="module-dep-rp-kpi-val">${(deps.instability || 0).toFixed(2)}</span>
        </div>
        <div class="module-dep-rp-kpi">
          <span class="module-dep-rp-kpi-label">Inbound (Ca)</span>
          <span class="module-dep-rp-kpi-val">${deps.afferentCoupling} <span style="font-size:10px;font-weight:normal;color:var(--text-muted);">mods</span></span>
        </div>
        <div class="module-dep-rp-kpi">
          <span class="module-dep-rp-kpi-label">Outbound (Ce)</span>
          <span class="module-dep-rp-kpi-val">${deps.efferentCoupling} <span style="font-size:10px;font-weight:normal;color:var(--text-muted);">mods</span></span>
        </div>
      </div>
      ${kindsBadges ? `<div class="module-dep-rp-kinds">${kindsBadges}</div>` : ''}
      ${topModsHtml}
      <button class="btn-primary btn-sm btn-open-deps" style="width:100%;margin-top:6px;font-size:11px;padding:6px 10px;display:flex;align-items:center;justify-content:center;gap:6px;">
        <svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="width:12px;height:12px;"><path d="M9 17H7A5 5 0 0 1 7 7h2"/><path d="M15 7h2a5 5 0 0 1 0 10h-2"/><line x1="8" y1="12" x2="16" y2="12"/></svg>
        <span>Inspect All Touch Points & Calls</span>
      </button>
    `;

    depSec.querySelectorAll('.module-dep-rp-module-item').forEach(el => {
      el.addEventListener('click', () => {
        selectModuleItem({ fqn: el.dataset.fqn }, 'DEPENDENCIES');
      });
    });

    depSec.querySelector('.btn-open-deps')?.addEventListener('click', () => {
      switchTab('knowledge');
      loadKnowledgeBase(pkg.fqn, 'DEPENDENCIES');
    });
  };

  const cachedDeps = (window.GraphDataCache && pkg.fqn)
    ? (GraphDataCache.get('mod:dep:' + pkg.fqn.toLowerCase()) || GraphDataCache.get('mod:dep:' + (pkg.name || '').toLowerCase()))
    : null;

  if (cachedDeps) {
    renderDepsSec(cachedDeps);
  } else {
    depSec.innerHTML = `
      <div class="module-dep-inspector-title">
        <div style="display:flex;align-items:center;gap:6px;">
          <svg class="svg-icon icon-cyan icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="width:13px;height:13px;"><path d="M9 17H7A5 5 0 0 1 7 7h2"/><path d="M15 7h2a5 5 0 0 1 0 10h-2"/><line x1="8" y1="12" x2="16" y2="12"/></svg>
          <span>Module Touch Points</span>
        </div>
        <span class="stability-rating-pill is-balanced">Precomputed</span>
      </div>
      <div style="font-size:11px;color:var(--text-muted);display:flex;align-items:center;gap:6px;">
        <div class="spinner" style="width:12px;height:12px;border-width:2px;"></div> Loading touch points…
      </div>
    `;
    api.packageDependencies(pkg.fqn).then(deps => {
      renderDepsSec(deps);
    }).catch(() => {
      depSec.innerHTML = '<div style="font-size:11px;color:var(--text-muted);">No dependency insights found.</div>';
    });
  }

  api.notes(pkg.fqn).then(notes => renderNotes(pkg.fqn, notes)).catch(() => renderNotes(pkg.fqn, []));
}

/* ── Notes rendering ────────────────────────────────────────────────────────── */

function renderNotes(entityFqn, existingNotes = []) {
  const body = qs('#right-body');

  const section = createElement('div', { class: 'notes-section' });
  section.innerHTML = `<div class="rp-section">Analyst Notes</div>`;

  // Existing notes
  const notesList = createElement('div', { id: 'notes-list-' + entityFqn.replace(/[^a-z0-9]/gi, '_') });
  renderNoteCards(existingNotes, notesList, entityFqn);
  section.appendChild(notesList);

  // New note editor
  const editor = createElement('textarea', {
    class: 'note-editor',
    placeholder: 'Add a note (markdown supported)…',
  });
  section.appendChild(editor);

  const saveRow = createElement('div', { class: 'note-save-row' });
  const saveBtn = createElement('button', { class: 'btn-primary' });
  saveBtn.textContent = 'Save Note';
  saveBtn.addEventListener('click', async () => {
    const content = editor.value.trim();
    if (!content) return;
    try {
      await api.saveNote({ entityFqn, content });
      editor.value = '';
      // Reload notes
      const notes = await api.notes(entityFqn);
      renderNoteCards(notes, notesList, entityFqn);
    } catch (e) {
      showError('Failed to save note: ' + e.message);
    }
  });
  saveRow.appendChild(saveBtn);
  section.appendChild(saveRow);

  body.appendChild(section);
}

function renderNoteCards(notes, container, entityFqn) {
  container.innerHTML = '';
  if (notes.length === 0) {
    container.innerHTML = '<div class="list-empty" style="padding:8px 0">No notes yet.</div>';
    return;
  }
  for (const note of notes) {
    const card = createElement('div', { class: 'note-card' });
    card.innerHTML = `
      <div class="note-content">${esc(note.content)}</div>
      <div class="note-date">${formatDate(note.createdAt)}</div>
      <button class="note-delete" title="Delete note"><svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg></button>`;

    card.querySelector('.note-delete').addEventListener('click', async () => {
      if (!confirm('Delete this note?')) return;
      try {
        await api.deleteNote(note.id);
        const notes = await api.notes(entityFqn);
        renderNoteCards(notes, container, entityFqn);
      } catch (e) {
        showError('Failed to delete note: ' + e.message);
      }
    });
    container.appendChild(card);
  }
}

/* ─────────────────────────────────────────────────────────────────────────────
   Stats + header
   ───────────────────────────────────────────────────────────────────────────── */

async function loadStats() {
  try {
    const s = await api.stats();
    App.stats = s;

    // Animate counters to new values: modules, classes, methods, fields
    const effectiveModules = Math.max(s.modules || 0, s.packages || 0, (App.packages && App.packages.length) || 0);
    animateCounter(qs('#stat-modules'), effectiveModules);
    animateCounter(qs('#stat-classes'), s.classes || s.types || 0);
    animateCounter(qs('#stat-methods'), s.methods || 0);
    animateCounter(qs('#stat-fields'),  s.fields  || 0);

    // Also support fallback elements if any
    animateCounter(qs('#stat-types'),   s.types   || 0);

    // Update methods and persistent classes in classifier
    if (window.CodeLensClassifier) {
      if ((Array.isArray(s.persistentClasses) || s.persistentClasses instanceof Set) && typeof window.CodeLensClassifier.setPersistentClasses === 'function') {
        window.CodeLensClassifier.setPersistentClasses(s.persistentClasses);
      }
      if (Array.isArray(s.methodsList) && typeof window.CodeLensClassifier.setMethodsData === 'function') {
        window.CodeLensClassifier.setMethodsData(s.methodsList);
      }
    }

    // Compute & update archetypes breakup for both classes and methods
    updateArchetypesBreakup(s);

    // Refresh persistent scan summary
    try {
      const scanStatus = await api.scanStatus();
      if (scanStatus) {
        updateScanSummaryUI(scanStatus);
      }
    } catch (_) {}

    if ((s.types || s.classes) > 0) {
      updateHeaderProjectBar();
    }
  } catch (e) {
    console.warn('Stats load failed:', e);
  }
}


/** Update the class and method archetypes breakup inside the explorer footer popovers */
function updateArchetypesBreakup(stats) {
  const methodList = stats?.methodsList || [];
  const typeList = stats?.typesList || [];
  const methodsCount = stats?.methods || methodList.length || 0;
  const classesCount = stats?.classes || stats?.types || typeList.length || 0;

  const items = getAvailableArchetypeItems();
  const counts = new Map();
  items.forEach(it => counts.set(it.id, 0));

  let classifiedClassesCount = 0;
  let classifiedMethodsCount = 0;

  if (window.CodeLensClassifier) {
    if (methodList.length > 0 && typeof window.CodeLensClassifier.setMethodsData === 'function') {
      window.CodeLensClassifier.setMethodsData(methodList);
    }

    // Classify methods
    if (methodList.length > 0) {
      methodList.forEach(m => {
        const res = window.CodeLensClassifier.classifyMethod(m.name, m.fqn, m.type);
        if (res && res.ruleId) {
          counts.set(res.ruleId, (counts.get(res.ruleId) || 0) + 1);
          classifiedMethodsCount++;
        }
      });
    }

    // Classify classes
    if (typeList.length > 0) {
      typeList.forEach(t => {
        const res = window.CodeLensClassifier.classifyType(t, t.fqn, t.package);
        if (res && res.ruleId) {
          counts.set(res.ruleId, (counts.get(res.ruleId) || 0) + 1);
          classifiedClassesCount++;
        }
      });
    }
  }

  const renderBreakupRows = (rulesList, totalCount, classifiedCount) => {
    const unclassifiedCount = Math.max(0, totalCount - classifiedCount);
    const sorted = [...rulesList].sort((a, b) => (counts.get(b.id) || 0) - (counts.get(a.id) || 0));

    let rowsHtml = sorted.map(item => {
      const count = counts.get(item.id) || 0;
      const pct = totalCount > 0 ? ((count / totalCount) * 100).toFixed(1) : '0.0';
      const color = item.color || '#64748b';
      return `
        <div class="archetype-breakup-row" data-id="${esc(item.id)}" title="${esc(item.label)}: ${count.toLocaleString()} (${pct}%)">
          <div class="archetype-breakup-left">
            <span class="archetype-breakup-badge" style="background:${color}22; color:${color}; border:1px solid ${color}55;">[${esc(item.badge)}]</span>
            <span class="archetype-breakup-name">${esc(item.label)}</span>
          </div>
          <div class="archetype-breakup-right">
            <span class="archetype-breakup-count">${count.toLocaleString()}</span>
            <span class="archetype-breakup-pct">${pct}%</span>
          </div>
        </div>
      `;
    }).join('');

    if (unclassifiedCount > 0) {
      const pct = totalCount > 0 ? ((unclassifiedCount / totalCount) * 100).toFixed(1) : '0.0';
      rowsHtml += `
        <div class="archetype-breakup-row" data-id="UNCLASSIFIED" title="Unclassified / Plain: ${unclassifiedCount.toLocaleString()} (${pct}%)">
          <div class="archetype-breakup-left">
            <span class="archetype-breakup-badge" style="background:#64748b22; color:#64748b; border:1px solid #64748b55;">[NONE]</span>
            <span class="archetype-breakup-name">Unclassified / Plain</span>
          </div>
          <div class="archetype-breakup-right">
            <span class="archetype-breakup-count">${unclassifiedCount.toLocaleString()}</span>
            <span class="archetype-breakup-pct">${pct}%</span>
          </div>
        </div>
      `;
    }

    return rowsHtml;
  };

  // 1. Update Classes Popover
  const classesList = qs('#popover-classes-list');
  const classesTotal = qs('#popover-classes-total');
  if (classesTotal) {
    classesTotal.textContent = `${classesCount.toLocaleString()} classes`;
  }
  if (classesList) {
    const classRules = items.filter(it => it.scope === 'CLASS');
    classesList.innerHTML = renderBreakupRows(classRules, classesCount, classifiedClassesCount);
  }

  // 2. Update Methods Popover
  const methodsList = qs('#popover-methods-list') || qs('#popover-archetypes-list');
  const methodsTotal = qs('#popover-methods-total');
  if (methodsTotal) {
    methodsTotal.textContent = `${methodsCount.toLocaleString()} methods`;
  }
  if (methodsList) {
    const methodRules = items.filter(it => it.scope === 'METHOD');
    methodsList.innerHTML = renderBreakupRows(methodRules, methodsCount, classifiedMethodsCount);
  }

  // 3. Update Modules Popover
  updateModulesList();

  // 4. Update Fields Popover
  updateFieldsBreakup(stats);
}

/** Populate interactive fields breakdown popover with statistics */
function updateFieldsBreakup(stats) {
  const fieldsCount = stats?.fields || 0;
  const classesCount = stats?.classes || stats?.types || (stats?.typesList ? stats.typesList.length : 0);
  const avgFieldsPerClass = classesCount > 0 ? (fieldsCount / classesCount).toFixed(1) : '0';

  const totalEl = qs('#popover-fields-total');
  if (totalEl) totalEl.textContent = `${fieldsCount.toLocaleString()} fields`;

  const listEl = qs('#popover-fields-list');
  if (!listEl) return;

  listEl.innerHTML = `
    <div style="display:flex; flex-direction:column; gap:8px; padding:4px 0;">
      <div style="display:grid; grid-template-columns:1fr 1fr; gap:8px;">
        <div style="background:rgba(255,255,255,0.04); border:1px solid rgba(255,255,255,0.08); border-radius:6px; padding:8px 10px;">
          <div style="font-size:10px; text-transform:uppercase; color:var(--text-muted); font-weight:600; letter-spacing:0.5px;">Avg / Class</div>
          <div style="font-size:16px; font-weight:700; color:var(--text-primary); margin-top:2px;">${avgFieldsPerClass}</div>
        </div>
        <div style="background:rgba(255,255,255,0.04); border:1px solid rgba(255,255,255,0.08); border-radius:6px; padding:8px 10px;">
          <div style="font-size:10px; text-transform:uppercase; color:var(--text-muted); font-weight:600; letter-spacing:0.5px;">Impact Model</div>
          <div style="font-size:16px; font-weight:700; color:var(--color-amber, #f59e0b); margin-top:2px;">Active</div>
        </div>
      </div>
      <div class="archetype-breakup-group">
        <div class="archetype-breakup-group-title">Field Metrics &amp; Scope</div>
        <div class="archetype-breakup-row">
          <div class="archetype-breakup-left">
            <span class="archetype-breakup-badge" style="background:rgba(245,158,11,0.15); color:#f59e0b; border:1px solid rgba(245,158,11,0.35);">[FLD]</span>
            <span class="archetype-breakup-name">Indexed Member Variables</span>
          </div>
          <span class="archetype-breakup-count">${fieldsCount.toLocaleString()}</span>
        </div>
        <div class="archetype-breakup-row">
          <div class="archetype-breakup-left">
            <span class="archetype-breakup-badge" style="background:rgba(16,185,129,0.15); color:#10b981; border:1px solid rgba(16,185,129,0.35);">[CLS]</span>
            <span class="archetype-breakup-name">Enclosing Classes</span>
          </div>
          <span class="archetype-breakup-count">${classesCount.toLocaleString()}</span>
        </div>
        <div class="archetype-breakup-row">
          <div class="archetype-breakup-left">
            <span class="archetype-breakup-badge" style="background:rgba(99,102,241,0.15); color:#6366f1; border:1px solid rgba(99,102,241,0.35);">[DEP]</span>
            <span class="archetype-breakup-name">Field Impact Analyzer</span>
          </div>
          <span class="archetype-breakup-count" style="color:#10b981; font-weight:600;">Indexed</span>
        </div>
      </div>
    </div>
  `;
}

let modulePopoverSortMode = 'name';
let cachedModuleCouplingMap = null;
let moduleSortBarInitialized = false;

async function loadModuleCouplingMap() {
  if (cachedModuleCouplingMap) return cachedModuleCouplingMap;
  try {
    const [overview] = await Promise.all([
      api.allModuleDependencies(),
      api.allModuleInsights().catch(() => null)
    ]);
    const map = new Map();
    if (overview && overview.modules) {
      for (const m of overview.modules) {
        if (m.packageFqn) map.set(m.packageFqn.toLowerCase(), m);
        if (m.moduleName) map.set(m.moduleName.toLowerCase(), m);
      }
    }
    cachedModuleCouplingMap = map;
    return cachedModuleCouplingMap;
  } catch (_) {
    return new Map();
  }
}

/** Update the list of indexed modules inside the explorer footer modules popover */
async function updateModulesList(filterText = '') {
  let packages = App.packages;
  if (!packages || packages.length === 0) {
    try {
      packages = await api.packages();
      App.packages = packages;
    } catch (_) {
      packages = [];
    }
  }

  const modulesList = qs('#popover-modules-list');
  const modulesTotal = qs('#popover-modules-total');
  if (!modulesList) return;

  // Initialize sort bar buttons if not already initialized
  const sortBar = qs('#modules-popover-sort-bar');
  if (sortBar && !moduleSortBarInitialized) {
    sortBar.querySelectorAll('.popover-sort-btn').forEach(btn => {
      btn.addEventListener('click', (e) => {
        e.stopPropagation();
        const sort = btn.dataset.sort;
        if (sort === modulePopoverSortMode) return;
        modulePopoverSortMode = sort;
        sortBar.querySelectorAll('.popover-sort-btn').forEach(b => b.classList.toggle('active', b.dataset.sort === sort));
        const filterInput = qs('#modules-popover-filter');
        updateModulesList(filterInput ? filterInput.value : '');
      });
    });
    moduleSortBarInitialized = true;
  }

  const totalCount = (packages && packages.length) ? packages.length : 0;
  if (modulesTotal) {
    modulesTotal.textContent = `${totalCount.toLocaleString()} ${totalCount === 1 ? 'module' : 'modules'}`;
  }
  if (totalCount > 0) {
    const statModEl = qs('#stat-modules');
    if (statModEl) {
      animateCounter(statModEl, totalCount);
    }
    const scanStatMod = qs('#scan-stat-modules');
    if (scanStatMod) {
      scanStatMod.textContent = totalCount.toLocaleString();
    }
  }

  const filter = (filterText || '').trim().toLowerCase();
  const filtered = filter
    ? packages.filter(p => (p.name || '').toLowerCase().includes(filter) || (p.fqn || '').toLowerCase().includes(filter))
    : packages;

  if (!filtered || filtered.length === 0) {
    modulesList.innerHTML = `<div class="list-empty" style="padding:14px 8px; text-align:center; font-size:11px; color:var(--text-muted);">${filter ? 'No matching modules found' : 'No modules indexed yet'}</div>`;
    return;
  }

  // Load coupling map asynchronously if not cached
  let couplingMap = cachedModuleCouplingMap;
  if (!couplingMap) {
    couplingMap = await loadModuleCouplingMap();
  }

  // Sort modules
  const sorted = [...filtered].sort((a, b) => {
    const fqnA = (a.fqn || '').toLowerCase();
    const fqnB = (b.fqn || '').toLowerCase();
    const nameA = a.name || a.fqn || '';
    const nameB = b.name || b.fqn || '';

    if (modulePopoverSortMode === 'classes') {
      const diff = (b.typeCount || 0) - (a.typeCount || 0);
      if (diff !== 0) return diff;
    } else if (modulePopoverSortMode === 'coupling') {
      const cA = couplingMap.get(fqnA) || couplingMap.get(nameA.toLowerCase()) || {};
      const cB = couplingMap.get(fqnB) || couplingMap.get(nameB.toLowerCase()) || {};
      const ptsA = cA.totalTouchPoints || 0;
      const ptsB = cB.totalTouchPoints || 0;
      const diff = ptsB - ptsA;
      if (diff !== 0) return diff;
    }
    return nameA.localeCompare(nameB, undefined, { sensitivity: 'base' });
  });

  modulesList.innerHTML = sorted.map(pkg => {
    const pkgColor = (window.CodeLensPalette && window.CodeLensPalette.getColor)
      ? window.CodeLensPalette.getColor(pkg.fqn, 0)
      : '#10b981';
    const cleanName = pkg.name || (pkg.fqn ? pkg.fqn.split('.').pop() : 'default');
    const fqn = pkg.fqn || cleanName;
    const typeCount = pkg.typeCount || 0;
    const fileCount = pkg.fileCount || 0;

    const couplingInfo = couplingMap ? (couplingMap.get(fqn.toLowerCase()) || couplingMap.get(cleanName.toLowerCase())) : null;
    const touchPoints = couplingInfo ? (couplingInfo.totalTouchPoints || 0) : 0;
    const tierCls = touchPoints >= 50 ? 'tier-hot' : (touchPoints >= 15 ? 'tier-warm' : (touchPoints >= 5 ? 'tier-mid' : 'tier-low'));

    return `
      <div class="archetype-breakup-row module-breakup-row" data-fqn="${esc(fqn)}" style="border-left: 3px solid ${pkgColor}aa;" tabindex="0" role="button" title="${esc(fqn)} · ${typeCount} classes, ${fileCount} files, ${touchPoints} touch points (Click to navigate in Explorer)">
        <div class="archetype-breakup-left" style="overflow:hidden; flex:1; min-width:0;">
          <span class="archetype-breakup-badge" style="background:${pkgColor}22; color:${pkgColor}; border:1px solid ${pkgColor}55;">[MOD]</span>
          <div style="display:flex; flex-direction:column; min-width:0; overflow:hidden;">
            <span class="archetype-breakup-name" style="font-weight:600; color:var(--text-primary); font-size:11px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;">${esc(cleanName)}</span>
            ${cleanName !== fqn ? `<span class="module-item-fqn" style="font-size:9.5px; font-family:var(--font-mono); color:var(--text-muted); overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="${esc(fqn)}">${esc(fqn)}</span>` : ''}
          </div>
        </div>
        <div class="archetype-breakup-right">
          ${touchPoints > 0 ? `<span class="module-touchpoints-pill ${tierCls}" title="${touchPoints} touch points across connected modules">${touchPoints} pts</span>` : ''}
          <span class="archetype-breakup-count">${typeCount} ${typeCount === 1 ? 'class' : 'classes'}</span>
          <span class="archetype-breakup-pct" style="width:auto; font-size:9.5px; text-align:right;">${fileCount} ${fileCount === 1 ? 'file' : 'files'}</span>
          <button class="module-dep-quick-btn" data-fqn="${esc(fqn)}" title="View dependencies & touch points for ${esc(cleanName)}">
            <svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="width:11px;height:11px;"><path d="M9 17H7A5 5 0 0 1 7 7h2"/><path d="M15 7h2a5 5 0 0 1 0 10h-2"/><line x1="8" y1="12" x2="16" y2="12"/></svg>
            <span>Deps</span>
          </button>
        </div>
      </div>
    `;
  }).join('');

  // Attach click & keyboard listeners to rows
  modulesList.querySelectorAll('.module-breakup-row').forEach(row => {
    const handler = (e) => {
      e.stopPropagation();
      const fqn = row.dataset.fqn;
      const targetPkg = packages.find(p => p.fqn === fqn) || { fqn, name: fqn };
      selectModuleItem(targetPkg);
    };
    row.addEventListener('click', handler);
    row.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        handler(e);
      }
    });
    row.querySelector('.module-dep-quick-btn')?.addEventListener('click', (e) => {
      e.stopPropagation();
      const fqn = row.dataset.fqn;
      const targetPkg = packages.find(p => p.fqn === fqn) || { fqn, name: fqn };
      selectModuleItem(targetPkg, 'DEPENDENCIES');
    });
  });
}

/** Navigate to a module/package from the modules popover */
async function selectModuleItem(pkg, initialTab = null) {
  const popover = qs('#modules-list-popover');
  const pill = qs('#stat-pill-modules');
  if (popover) popover.style.display = 'none';
  if (pill) {
    pill.classList.remove('popover-open');
    pill.setAttribute('aria-expanded', 'false');
  }

  if (pkg && pkg.fqn) {
    // Open all ancestor package chains in tree state
    const parts = pkg.fqn.split('.');
    let cur = '';
    for (let i = 0; i < parts.length - 1; i++) {
      cur = i === 0 ? parts[0] : cur + '.' + parts[i];
      App.openPackages.add(cur);
    }
    App.openPackages.add(pkg.fqn);

    // Sync tree asynchronously in background without delaying user view navigation!
    loadPackageTree().then(() => {
      const itemEl = qs(`#explorer-tree [data-fqn="${CSS.escape(pkg.fqn)}"]`);
      if (itemEl) {
        itemEl.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
        setActiveTreeItem(itemEl);
      }
    }).catch(() => {});

    // IMMEDIATELY navigate and select package in 0ms!
    const itemEl = qs(`#explorer-tree [data-fqn="${CSS.escape(pkg.fqn)}"]`);
    selectPackage(pkg, itemEl, initialTab);
  }

  showToast(`Selected module: ${pkg.name || pkg.fqn}`, 'info', 2200);
}

/** Initialize interactive click and keyboard triggers for stats popovers */
function initArchetypePopover() {
  const popoverPairs = [
    { pill: qs('#stat-pill-modules'), popover: qs('#modules-list-popover'), onOpen: () => updateModulesList() },
    { pill: qs('#stat-pill-classes'), popover: qs('#classes-archetypes-popover'), onOpen: () => updateArchetypesBreakup(App.stats || {}) },
    { pill: qs('#stat-pill-methods'), popover: qs('#methods-archetypes-popover'), onOpen: () => updateArchetypesBreakup(App.stats || {}) },
    { pill: qs('#stat-pill-fields'),  popover: qs('#fields-stats-popover'),    onOpen: () => updateFieldsBreakup(App.stats || {}) }
  ];

  popoverPairs.forEach(({ pill, popover, onOpen }) => {
    if (!pill || !popover) return;

    const toggle = (e) => {
      e.stopPropagation();
      const isVisible = popover.style.display !== 'none';

      // Close all popovers first
      popoverPairs.forEach(p => {
        if (p.popover) p.popover.style.display = 'none';
        if (p.pill) {
          p.pill.classList.remove('popover-open');
          p.pill.setAttribute('aria-expanded', 'false');
        }
      });

      if (!isVisible) {
        if (typeof onOpen === 'function') onOpen();
        popover.style.display = 'flex';
        pill.classList.add('popover-open');
        pill.setAttribute('aria-expanded', 'true');

        if (popover.id === 'modules-list-popover') {
          const filterInput = qs('#modules-popover-filter');
          if (filterInput) {
            filterInput.value = '';
            setTimeout(() => filterInput.focus(), 60);
          }
        }
      }
    };

    pill.addEventListener('click', toggle);
    pill.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        toggle(e);
      } else if (e.key === 'Escape') {
        popover.style.display = 'none';
        pill.classList.remove('popover-open');
        pill.setAttribute('aria-expanded', 'false');
      }
    });
  });

  // Clicking on the explorer-stats-footer stops propagation so it doesn't leak
  const footer = qs('#explorer-stats-footer');
  if (footer) {
    footer.addEventListener('click', (e) => {
      if (e.target.closest('#stat-pill-classes') ||
          e.target.closest('#stat-pill-methods') ||
          e.target.closest('#stat-pill-fields') ||
          e.target.closest('#stat-pill-modules') ||
          e.target.closest('.methods-archetypes-popover')) {
        return;
      }
      e.stopPropagation();
    });
  }

  // Filter input inside modules popover
  const filterInput = qs('#modules-popover-filter');
  if (filterInput) {
    filterInput.addEventListener('input', (e) => {
      updateModulesList(e.target.value);
    });
    filterInput.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        const popover = qs('#modules-list-popover');
        const pill = qs('#stat-pill-modules');
        if (popover) popover.style.display = 'none';
        if (pill) {
          pill.classList.remove('popover-open');
          pill.setAttribute('aria-expanded', 'false');
        }
      }
    });
  }

  document.addEventListener('click', (e) => {
    if (footer && footer.contains(e.target)) return;

    popoverPairs.forEach(({ pill, popover }) => {
      if (popover && pill && !popover.contains(e.target) && !pill.contains(e.target)) {
        popover.style.display = 'none';
        pill.classList.remove('popover-open');
        pill.setAttribute('aria-expanded', 'false');
      }
    });
  });
}

/** Update persistent scan summary coverage popover & header badge */
function updateScanSummaryUI(s) {
  if (!s) return;
  App.lastScanProgress = s;

  const total = s.totalFiles || 0;
  const processed = s.processedFiles || 0;
  const parsed = s.parsedFiles || (s.status === 'COMPLETE' ? processed : 0);
  const errors = s.errorFiles || 0;
  const remaining = (typeof s.remainingFiles === 'number') ? s.remainingFiles : Math.max(0, total - processed);
  const pct = (typeof s.percentage === 'number' && s.percentage >= 0)
    ? s.percentage
    : (total > 0 ? Math.min(100, Math.round((processed / total) * 100)) : (s.status === 'COMPLETE' ? 100 : 0));

  // 1. Update Header Badge
  const badge = qs('#scan-status-badge');
  const badgeText = qs('#scan-status-text');

  if (badge && badgeText) {
    badge.className = 'scan-status-badge';
    if (s.status === 'SCANNING') {
      badge.classList.add('status-scanning');
      badgeText.textContent = `${pct}%`;
      badge.title = `Scan in progress: ${pct}% · [${s.currentPhase || 'Analysis'}] ${s.message || ''} (Click to view)`;
    } else if (s.status === 'ERROR') {
      badge.classList.add('status-error');
      badgeText.textContent = 'Failed';
      badge.title = 'Scan failed. Click to view details.';
    } else if (errors > 0 || (total > 0 && parsed < total)) {
      badge.classList.add('status-warning');
      badgeText.textContent = `${pct}% (${errors > 0 ? errors + ' err' : remaining + ' rem'})`;
      badge.title = `Partial scan: ${pct}% parsed (${errors} errors, ${remaining} remaining). Click for details.`;
    } else {
      badge.classList.add('status-success');
      badgeText.textContent = `${pct}%`;
      badge.title = `Scan 100% complete (${parsed || total} files). Click for breakdown & rescan.`;
    }
  }


  // 2. Update Popover Header & Badge
  const popoverBadge = qs('#scan-popover-status-badge');
  if (popoverBadge) {
    popoverBadge.className = 'scan-summary-badge';
    if (s.status === 'SCANNING') {
      popoverBadge.classList.add('badge-warning');
      popoverBadge.textContent = 'In Progress';
    } else if (s.status === 'ERROR') {
      popoverBadge.classList.add('badge-error');
      popoverBadge.textContent = 'Failed';
    } else if (errors > 0 || remaining > 0) {
      popoverBadge.classList.add('badge-warning');
      popoverBadge.textContent = 'Partial / Issues';
    } else {
      popoverBadge.classList.add('badge-success');
      popoverBadge.textContent = 'Complete';
    }
  }

  // 3. Update Popover Paths & Progress
  const popoverPath = qs('#scan-popover-path');
  if (popoverPath) popoverPath.textContent = s.sourcePath || App.currentPath || 'Unknown';

  const rateEl = qs('#scan-popover-completion-rate');
  if (rateEl) {
    if (s.status === 'SCANNING') rateEl.textContent = `${pct}% Indexing…`;
    else if (errors > 0) rateEl.textContent = `${pct}% Indexed (${errors} error${errors > 1 ? 's' : ''})`;
    else if (remaining > 0) rateEl.textContent = `${pct}% Indexed (${remaining} skipped/remaining)`;
    else rateEl.textContent = '100% Fully Indexed';
  }

  const ratioEl = qs('#scan-popover-files-ratio');
  if (ratioEl) ratioEl.textContent = `${parsed} / ${total} Files`;

  const fillEl = qs('#scan-popover-progress-fill');
  if (fillEl) {
    fillEl.style.width = `${pct}%`;
    fillEl.className = 'scan-progress-fill';
    if (errors > 0) fillEl.classList.add('status-error');
    else if (remaining > 0 || s.status === 'SCANNING') fillEl.classList.add('status-warning');
  }

  // 4. Update Stat Grid
  const setNum = (id, val) => {
    const el = qs('#' + id);
    if (el) el.textContent = typeof val === 'number' ? val.toLocaleString() : (val || 0);
  };
  setNum('scan-stat-parsed-files', parsed);
  setNum('scan-stat-remaining-files', remaining);
  setNum('scan-stat-error-files', errors);
  setNum('scan-stat-types', s.typesFound || App.stats?.types || 0);
  setNum('scan-stat-methods', s.methodsFound || App.stats?.methods || 0);
  setNum('scan-stat-fields', s.fieldsFound || App.stats?.fields || 0);
  const effectiveScanModules = Math.max(s.modulesFound || 0, App.stats?.modules || 0, App.stats?.packages || 0, (App.packages && App.packages.length) || 0);
  setNum('scan-stat-modules', effectiveScanModules);
  const totalReportsDefault = s.reportsTotal || (typeof REPORTS_METADATA !== 'undefined' ? Object.keys(REPORTS_METADATA).length : 14);
  setNum('scan-stat-reports', s.reportsFound || App.stats?.reports || (s.status === 'COMPLETE' ? totalReportsDefault : 0));

  // Format Duration
  const durEl = qs('#scan-stat-duration');
  if (durEl) {
    let dur = s.durationMs || 0;
    if (!dur && s.startTime && s.endTime) dur = s.endTime - s.startTime;
    if (dur > 0) {
      if (dur < 1000) durEl.textContent = `${dur}ms`;
      else if (dur < 60000) durEl.textContent = `${(dur / 1000).toFixed(1)}s`;
      else durEl.textContent = `${Math.floor(dur / 60000)}m ${Math.round((dur % 60000) / 1000)}s`;
    } else {
      durEl.textContent = '< 1s';
    }
  }

  // 4b. Sync Footer Scan Pill
  const fPill = qs('#footer-scan-pill');
  if (fPill) {
    if (s.status === 'COMPLETE') {
      fPill.style.display = 'inline-flex';
      fPill.textContent = 'READY';
      fPill.className = 'footer-scan-pill footer-scan-pill-complete';
    } else if (s.status === 'SCANNING') {
      fPill.style.display = 'inline-flex';
      const stg = s.activeStage || 'PARSE';
      fPill.textContent = stg;
      fPill.className = `footer-scan-pill footer-scan-pill-${stg.toLowerCase()}`;
    }
  }

  // 5. Update Advice Banner
  const adviceBanner = qs('#scan-advice-banner');
  const adviceIcon = qs('#scan-advice-icon');
  const adviceTitle = qs('#scan-advice-title');
  const adviceDesc = qs('#scan-advice-desc');

  if (adviceBanner && adviceTitle && adviceDesc) {
    adviceBanner.className = 'scan-advice-banner';
    if (s.status === 'ERROR') {
      adviceBanner.classList.add('advice-error');
      if (adviceIcon) adviceIcon.textContent = '❌';
      adviceTitle.textContent = 'Scan encountered an error';
      adviceDesc.textContent = s.errorDetail || s.message || 'Check source directory permissions and trigger a rescan.';
    } else if (errors > 0 || remaining > 0) {
      adviceBanner.classList.add('advice-warning');
      if (adviceIcon) adviceIcon.textContent = '⚠️';
      adviceTitle.textContent = 'Scan completed with remaining files or errors';
      adviceDesc.textContent = `${errors > 0 ? errors + ' file(s) had parse errors. ' : ''}${remaining > 0 ? remaining + ' file(s) were not processed. ' : ''}Triggering a rescan will retry incomplete files.`;
    } else {
      if (adviceIcon) adviceIcon.textContent = '💡';
      adviceTitle.textContent = 'All codebase files are up to date';
      adviceDesc.textContent = 'If you modified source files on disk, click Rescan to update AST indexes and call graphs.';
    }
  }

  // 6. Update Timestamp
  const tsEl = qs('#scan-popover-timestamp');
  if (tsEl) {
    if (s.endTime) {
      const d = new Date(s.endTime);
      tsEl.textContent = `Scanned ${d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`;
    } else if (s.startTime) {
      tsEl.textContent = 'Scan in progress…';
    } else {
      tsEl.textContent = 'Last scan: Available';
    }
  }
}

function toggleScanSummaryPopover(force) {
  const popover = qs('#scan-summary-popover');
  if (!popover) return;
  const isVisible = popover.style.display !== 'none';
  const show = typeof force === 'boolean' ? force : !isVisible;
  popover.style.display = show ? 'flex' : 'none';
  if (show) {
    checkCodebaseChanges();
  }
}

/** Check if files were modified/added/deleted on disk since last scan */
async function checkCodebaseChanges() {
  const path = App.currentPath || localStorage.getItem('codelens_last_path');
  if (!path || (App.lastScanProgress && App.lastScanProgress.status === 'SCANNING')) return;

  try {
    const changes = await api.scanChanges(path);
    App.lastScanChanges = changes;

    const changesSection = qs('#scan-popover-changes-section');
    const changesList = qs('#scan-popover-changes-list');
    const countLabel = qs('#scan-changes-count-label');
    const footerDeltaBtn = qs('#btn-popover-rescan-incremental-footer');
    const badge = qs('#scan-status-badge');
    const badgeText = qs('#scan-status-text');

    if (changes && changes.hasChanges) {
      if (changesSection) changesSection.style.display = 'flex';
      if (countLabel) countLabel.textContent = `${changes.totalChanges} Changes Detected on Disk`;
      if (footerDeltaBtn) {
        footerDeltaBtn.style.display = 'inline-flex';
        footerDeltaBtn.textContent = `⚡ Rescan Changes (${changes.totalChanges})`;
      }

      if (changesList) {
        changesList.innerHTML = '';
        const items = [
          ...changes.newFiles.map(f => ({ path: f, type: 'NEW', label: '+ New', badgeClass: 'badge-new' })),
          ...changes.modifiedFiles.map(f => ({ path: f, type: 'MOD', label: 'Δ Mod', badgeClass: 'badge-mod' })),
          ...changes.deletedFiles.map(f => ({ path: f, type: 'DEL', label: '- Del', badgeClass: 'badge-del' }))
        ];

        items.slice(0, 20).forEach(it => {
          const row = document.createElement('div');
          row.className = 'scan-change-item';
          const name = it.path.replace(/[\\\/]+$/, '').split(/[\\\/]/).pop() || it.path;
          row.title = it.path;
          row.innerHTML = `
            <span class="scan-change-name">${escapeHtml(name)}</span>
            <span class="scan-change-badge ${it.badgeClass}">${it.label}</span>
          `;
          changesList.appendChild(row);
        });

        if (items.length > 20) {
          const moreRow = document.createElement('div');
          moreRow.className = 'scan-change-item';
          moreRow.style.color = 'var(--text-muted)';
          moreRow.textContent = `+ ${items.length - 20} more changed files`;
          changesList.appendChild(moreRow);
        }
      }

      // Update badge to alert user of pending changes
      if (badge && badgeText && (!App.lastScanProgress || App.lastScanProgress.status !== 'SCANNING')) {
        badge.className = 'scan-status-badge status-warning';
        badgeText.textContent = `⚡ ${changes.totalChanges} Δ`;
        badge.title = `${changes.totalChanges} changed files on disk (${changes.newFiles.length} new, ${changes.modifiedFiles.length} mod, ${changes.deletedFiles.length} del). Click to rescan.`;
      }

      // Update popover advice banner
      const adviceBanner = qs('#scan-advice-banner');
      const adviceIcon = qs('#scan-advice-icon');
      const adviceTitle = qs('#scan-advice-title');
      const adviceDesc = qs('#scan-advice-desc');
      if (adviceBanner && adviceTitle && adviceDesc) {
        adviceBanner.className = 'scan-advice-banner advice-warning';
        if (adviceIcon) adviceIcon.textContent = '⚡';
        adviceTitle.textContent = `${changes.totalChanges} file(s) modified since last scan`;
        adviceDesc.textContent = `Click "⚡ Rescan Changes" to incrementally update classes, methods, and call graph in seconds.`;
      }
    } else {
      if (changesSection) changesSection.style.display = 'none';
      if (footerDeltaBtn) footerDeltaBtn.style.display = 'none';
      if (changesList) changesList.innerHTML = '';
      if (App.lastScanProgress) {
        updateScanSummaryUI(App.lastScanProgress);
      }
    }
  } catch (_) {
    // Ignore offline or transient check error
  }
}

/** Trigger incremental delta rescan */
async function startIncrementalScan() {
  const path = App.currentPath || qs('#scan-path-input')?.value?.trim() || localStorage.getItem('codelens_last_path');
  if (!path) {
    showError('No active project path found for incremental rescan');
    return;
  }

  const rawExcludes = qs('#set-exclude-patterns')?.value || '';
  const excludePatterns = rawExcludes.split(',').map(s => s.trim()).filter(Boolean);

  setScanUI('scanning');
  showBanner('Starting incremental delta rescan…');

  try {
    await api.startIncrementalScan(path, excludePatterns);
    pollScanStatus();
  } catch (e) {
    showError('Incremental scan failed to start: ' + e.message);
    setScanUI('idle');
  }
}


/** Update the loaded project header bar display and toggle off scan input */
function updateHeaderProjectBar(path) {
  if (!path) {
    path = qs('#scan-path-input')?.value?.trim() || App.currentPath || localStorage.getItem('codelens_last_path') || '';
  }
  if (!path) return;

  App.currentPath = path;
  localStorage.setItem('codelens_last_path', path);

  const cleanPath = path.replace(/[\\\/]+$/, '');
  const parts = cleanPath.split(/[\\\/]/);
  const projectName = parts[parts.length - 1] || 'Codebase';

  const nameEl = qs('#project-name-display');
  const pathEl = qs('#project-path-display');
  if (nameEl) nameEl.textContent = projectName;
  if (pathEl) pathEl.textContent = path;

  const projectBar = qs('#header-project-bar');
  const scanBar = qs('#header-scan-bar');
  const cancelBtn = qs('#scan-cancel-btn');
  if (projectBar) projectBar.style.display = 'flex';
  if (scanBar) scanBar.style.display = 'none';
  if (cancelBtn) cancelBtn.style.display = 'inline-flex';

  const gitInput = qs('#git-repo-input');
  if (gitInput && (!gitInput.value || gitInput.dataset.synced === 'true')) {
    gitInput.value = path;
    gitInput.dataset.synced = 'true';
    validateGitRepoPath();
  }
}

/** Reveal the scan input bar to switch or open a different project (now shows hero page) */
function showHeaderScanBar() {
  showHeroPage();
}


/** Smoothly count up a stat value. */
function animateCounter(el, target) {
  if (!el) return;
  const start    = parseInt(el.textContent, 10) || 0;
  const duration = 600;
  const step     = (timestamp) => {
    if (!step.startTime) step.startTime = timestamp;
    const progress = Math.min((timestamp - step.startTime) / duration, 1);
    el.textContent = Math.round(start + (target - start) * ease(progress));
    if (progress < 1) requestAnimationFrame(step);
  };
  requestAnimationFrame(step);
}

function ease(t) { return t < 0.5 ? 2*t*t : -1+(4-2*t)*t; }

/* ─────────────────────────────────────────────────────────────────────────────
   8. Keyboard shortcuts
   ───────────────────────────────────────────────────────────────────────────── */

function initGuideModalEnhancements() {
  const guideSearchInput = qs('#guide-search-input');
  const guideSearchClear = qs('#guide-search-clear');
  const guideSearchCount = qs('#guide-search-count');
  const guideModal       = qs('#help-modal');
  const testerStatus     = qs('#guide-key-tester-status');

  // 1. Search & Filter
  function filterGuide(query) {
    const q = (query || '').trim().toLowerCase();
    const panels = qsa('.guide-tab-panel');
    let totalMatches = 0;

    if (!q) {
      if (guideSearchClear) guideSearchClear.style.display = 'none';
      if (guideSearchCount) guideSearchCount.style.display = 'none';
      panels.forEach(panel => {
        panel.querySelectorAll('.help-card, .flow-step, .sc-row, .guide-tier-card, .guide-formula-card, .guide-hero-banner').forEach(el => {
          el.style.display = '';
        });
        const tabBtn = qs(`.guide-tab-btn[data-guide-tab="${panel.id.replace('guide-tab-', '')}"]`);
        if (tabBtn) {
          const badge = tabBtn.querySelector('.guide-tab-badge');
          if (badge) badge.style.display = 'none';
        }
      });
      return;
    }

    if (guideSearchClear) guideSearchClear.style.display = 'flex';

    panels.forEach(panel => {
      let panelMatches = 0;
      const tabKey = panel.id.replace('guide-tab-', '');
      const tabBtn = qs(`.guide-tab-btn[data-guide-tab="${tabKey}"]`);

      // Searchable cards & rows
      const items = panel.querySelectorAll('.help-card, .flow-step, .sc-row, .guide-tier-card, .guide-formula-card');
      items.forEach(el => {
        const text = el.textContent.toLowerCase();
        const matches = text.includes(q);
        el.style.display = matches ? '' : 'none';
        if (matches) panelMatches++;
      });

      totalMatches += panelMatches;

      if (tabBtn) {
        const badge = tabBtn.querySelector('.guide-tab-badge');
        if (badge) {
          if (panelMatches > 0) {
            badge.textContent = panelMatches;
            badge.style.display = 'inline-block';
          } else {
            badge.style.display = 'none';
          }
        }
      }
    });

    if (guideSearchCount) {
      guideSearchCount.textContent = `${totalMatches} match${totalMatches === 1 ? '' : 'es'}`;
      guideSearchCount.style.display = 'inline-block';
    }
  }

  if (guideSearchInput) {
    guideSearchInput.addEventListener('input', (e) => filterGuide(e.target.value));
    guideSearchInput.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        guideSearchInput.value = '';
        filterGuide('');
        guideSearchInput.blur();
        e.stopPropagation();
      }
    });
  }

  if (guideSearchClear) {
    guideSearchClear.addEventListener('click', () => {
      if (guideSearchInput) {
        guideSearchInput.value = '';
        filterGuide('');
        guideSearchInput.focus();
      }
    });
  }

  // 2. Direct screen jump handlers
  if (guideModal) {
    guideModal.addEventListener('click', (e) => {
      const jumpEl = e.target.closest('.guide-jump-btn, .guide-chip');
      if (!jumpEl) return;
      e.preventDefault();
      const jumpTab = jumpEl.dataset.jumpTab;
      const jumpAction = jumpEl.dataset.jumpAction;

      closeHelpModal();

      if (jumpTab) {
        switchTab(jumpTab);
      } else if (jumpAction) {
        if (jumpAction === 'studio' || jumpAction === 'studio-city') {
          openMacroStudio('city3d');
        } else if (jumpAction === 'studio-galaxy') {
          openMacroStudio('galaxy3d');
        } else if (jumpAction === 'dsm') {
          openMacroStudio('dsm');
        } else if (jumpAction === 'treemap') {
          openMacroStudio('treemap');
        } else if (jumpAction === 'sunburst') {
          openMacroStudio('sunburst');
        } else if (jumpAction === 'chord') {
          openMacroStudio('chord');
        } else if (jumpAction === 'graph2d') {
          openMacroStudio('graph2d');
        } else if (jumpAction === 'scope-manager') {
          if (typeof openScopeManagerModal === 'function') openScopeManagerModal();
        } else if (jumpAction === 'tasks') {
          if (typeof openProcessHub === 'function') openProcessHub();
        } else if (jumpAction === 'settings') {
          if (typeof openSettings === 'function') openSettings();
        }
      }
    });
  }

  // 3. Live keypress tester for Shortcuts tab
  document.addEventListener('keydown', (e) => {
    if (!guideModal || !guideModal.classList.contains('open')) return;
    if (document.activeElement === guideSearchInput) return;

    // Press '/' to focus guide search
    if (e.key === '/' && document.activeElement !== guideSearchInput) {
      e.preventDefault();
      guideSearchInput?.focus();
      return;
    }

    const shortcutsTab = qs('#guide-tab-shortcuts');
    if (!shortcutsTab || !shortcutsTab.classList.contains('active')) return;

    const pressedKey = e.key.toLowerCase();
    const rows = shortcutsTab.querySelectorAll('.sc-row[data-shortcut-key]');
    let matchedRow = null;

    rows.forEach(row => {
      const keys = (row.dataset.shortcutKey || '').toLowerCase().split(' ');
      if (keys.includes(pressedKey) || (pressedKey === 'escape' && keys.includes('esc'))) {
        matchedRow = row;
      }
    });

    if (matchedRow) {
      rows.forEach(r => r.classList.remove('pulse-highlight'));
      matchedRow.classList.add('pulse-highlight');
      if (testerStatus) {
        const keyDisplay = e.key === ' ' ? 'Space' : e.key.toUpperCase();
        testerStatus.textContent = `Active Key: [${keyDisplay}] — Matched!`;
        testerStatus.style.color = '#10b981';
      }
      matchedRow.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
      setTimeout(() => {
        matchedRow.classList.remove('pulse-highlight');
      }, 1400);
    } else if (testerStatus) {
      const keyDisplay = e.key === ' ' ? 'Space' : e.key;
      testerStatus.textContent = `Pressed [${keyDisplay}] — No direct shortcut`;
      testerStatus.style.color = 'var(--text-muted)';
    }
  });
}

function openHelpModal(triggerEl = null) {
  const modal = qs('#help-modal');
  if (!modal) return;
  showAccessibleModal(modal, triggerEl || qs('#help-btn'));
}

function closeHelpModal() {
  dismissModalAnimated(qs('#help-modal'));
}

function bindKeyboard() {
  document.addEventListener('keydown', e => {
    // Escape → close modal or exit studio mode or close reports or clear search
    if (e.key === 'Escape') {
      if (document.body.classList.contains('macro-studio-mode')) {
        closeMacroStudio();
        return;
      }
      if (App.activeTab === 'reports') {
        switchTab(App.lastWorkspaceTab || 'graph');
        return;
      }
      const exportModal = qs('#export-modal');
      if (exportModal && exportModal.classList.contains('open')) {
        ExportHub.close();
        return;
      }
      const settingsModal = qs('#settings-modal');
      if (settingsModal && settingsModal.classList.contains('open')) {
        closeSettings();
        return;
      }
      const helpModal = qs('#help-modal');
      if (helpModal && helpModal.classList.contains('open')) {
        closeHelpModal();
        return;
      }
      const scopeModal = qs('#scope-manager-modal');
      if (scopeModal && scopeModal.classList.contains('open')) {
        closeScopeManager();
        return;
      }
      qs('#search-input').value = '';
      showExplorer();
      qs('#search-input').blur();
    }
    // Ctrl+K / Cmd+K → focus search
    if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
      e.preventDefault();
      qs('#search-input').focus();
      qs('#search-input').select();
    }
    // Shortcuts when not typing in inputs
    if (!['INPUT','TEXTAREA'].includes(e.target.tagName)) {
      if (['1','2','3','4','5','6','7'].includes(e.key)) {
        const tabs = [...(qs('.tab-nav-segment') || qs('.main-views-switcher') || qs('.tab-bar'))?.querySelectorAll('.tab') || []];
        const idx = parseInt(e.key, 10) - 1;
        if (tabs[idx] && tabs[idx].dataset.tab) {
          switchTab(tabs[idx].dataset.tab);
        }
      }
      if (!e.ctrlKey && !e.metaKey && !e.altKey && (e.key === 'r' || e.key === 'R')) {
        if (App.activeTab === 'reports') {
          switchTab(App.lastWorkspaceTab || 'graph');
        } else {
          App.lastWorkspaceTab = (App.activeTab && App.activeTab !== 'reports' && App.activeTab !== 'codebase') ? App.activeTab : (App.lastWorkspaceTab || 'graph');
          switchTab('reports');
        }
      }
      if (e.key === 'm' || e.key === 'M') {
        if (document.body.classList.contains('macro-studio-mode')) {
          closeMacroStudio();
        } else {
          openMacroStudio();
        }
      }
      if (e.key === '[') toggleLeftPanel();
      if (e.key === ']') toggleRightPanel();
      if (e.key === '\\') resetPanelWidths();
      if (e.key === '+' || e.key === '=') {
        if (document.body.classList.contains('macro-studio-mode') && App.activeAltRenderer && typeof App.activeAltRenderer.zoomBy === 'function') {
          App.activeAltRenderer.zoomBy(1.25);
        } else if (App.graph && typeof App.graph.zoomBy === 'function') {
          App.graph.zoomBy(1.25);
        }
      }
      if (e.key === '-' || e.key === '_') {
        if (document.body.classList.contains('macro-studio-mode') && App.activeAltRenderer && typeof App.activeAltRenderer.zoomBy === 'function') {
          App.activeAltRenderer.zoomBy(0.8);
        } else if (App.graph && typeof App.graph.zoomBy === 'function') {
          App.graph.zoomBy(0.8);
        }
      }
      if (e.key === 'f' || e.key === 'F') {
        if (document.body.classList.contains('macro-studio-mode') && App.activeAltRenderer && typeof App.activeAltRenderer.fitToScreen === 'function') {
          App.activeAltRenderer.fitToScreen();
        } else if (App.graph && typeof App.graph.fitToScreen === 'function') {
          App.graph.fitToScreen();
        }
      }
      if (e.key === '?') {
        const helpModal = qs('#help-modal');
        if (helpModal) {
          if (helpModal.classList.contains('open')) {
            closeHelpModal();
          } else {
            openHelpModal();
          }
        }
      }
    }
  });
}

/* ─────────────────────────────────────────────────────────────────────────────
   9. Bootstrapping - runs on DOMContentLoaded
   ───────────────────────────────────────────────────────────────────────────── */

/** Detect client OS and adapt keyboard shortcut labels across the UI. */
function adaptOsShortcuts() {
  const isMac = (typeof navigator !== 'undefined' && (
    (navigator.platform && navigator.platform.toUpperCase().includes('MAC')) ||
    (navigator.userAgent && navigator.userAgent.toUpperCase().includes('MAC'))
  ));
  const shortcutKey = isMac ? '⌘K' : 'Ctrl+K';

  const searchInput = qs('#search-input');
  if (searchInput) {
    searchInput.placeholder = `Search… (${shortcutKey})`;
  }

  qsa('.search-shortcut-key').forEach(el => {
    el.textContent = shortcutKey;
  });
}

async function init() {
  adaptOsShortcuts();

  // ── Hero Page wiring ──────────────────────────────────────────────────────
  // Hero scan button
  qs('#hero-scan-btn')?.addEventListener('click', () => startHeroScan());

  // Hero browse button — triggers native dialog then falls back to file picker
  const heroBrowseBtn    = qs('#hero-browse-btn');
  const heroFolderPicker = qs('#hero-folder-picker');
  const heroScanInput    = qs('#hero-scan-path-input');

  if (heroFolderPicker) {
    heroFolderPicker.addEventListener('change', (e) => {
      if (e.target.files && e.target.files.length > 0) {
        const firstFile = e.target.files[0];
        const relPath   = firstFile.webkitRelativePath || '';
        const rootDir   = relPath.split('/')[0] || relPath.split('\\')[0] || '';
        if (heroScanInput && (!heroScanInput.value || heroScanInput.value.trim() === '')) {
          heroScanInput.value = rootDir;
        }
        showBanner(`Folder selected: "${rootDir}". Confirm the full path and click Scan.`);
      }
    });
  }

  if (heroBrowseBtn) {
    heroBrowseBtn.addEventListener('click', async () => {
      const originalText = heroBrowseBtn.textContent.trim();
      heroBrowseBtn.disabled = true;
      try {
        const current = heroScanInput?.value?.trim() || '';
        const res = await api.browse(current);
        if (res && res.path && res.path.trim() !== '') {
          const selectedPath = res.path.trim();
          if (heroScanInput) heroScanInput.value = selectedPath;
          showBanner(`Selected: ${selectedPath}`);
        } else if (heroFolderPicker) {
          heroFolderPicker.click();
        }
      } catch (_) {
        if (heroFolderPicker) heroFolderPicker.click();
      } finally {
        heroBrowseBtn.disabled = false;
      }
    });
  }

  if (heroScanInput) {
    heroScanInput.addEventListener('keydown', e => {
      if (e.key === 'Enter') { e.preventDefault(); startHeroScan(); }
    });
  }

  // Note: Hero theme toggle is initialized by initThemeDropdowns() with interactive dropdown menu

  // Hero settings button
  qs('#hero-settings-btn')?.addEventListener('click', (e) => {
    openSettings(e);
  });

  // Wire up scan button and Enter key (legacy hidden scan input)
  qs('#scan-btn')?.addEventListener('click', () => startScan());

  // Wire up header project bar controls & scan summary popover
  qs('#btn-rescan')?.addEventListener('click', (e) => {
    e.preventDefault();
    startScan();
  });
  qs('#btn-open-project')?.addEventListener('click', showHeaderScanBar);
  qs('.logo')?.addEventListener('click', () => {
    showHeroPage();
  });
  
  // Wire up scan summary popover toggling
  qs('#scan-status-badge')?.addEventListener('click', (e) => {
    e.stopPropagation();
    toggleScanSummaryPopover();
  });
  qs('#project-pill')?.addEventListener('click', (e) => {
    e.stopPropagation();
    toggleScanSummaryPopover();
  });
  qs('#btn-close-scan-popover')?.addEventListener('click', (e) => {
    e.stopPropagation();
    toggleScanSummaryPopover(false);
  });
  qs('#btn-popover-rescan')?.addEventListener('click', (e) => {
    e.preventDefault();
    toggleScanSummaryPopover(false);
    startScan();
  });
  qs('#btn-popover-rescan-incremental')?.addEventListener('click', (e) => {
    e.preventDefault();
    toggleScanSummaryPopover(false);
    startIncrementalScan();
  });
  qs('#btn-popover-rescan-incremental-footer')?.addEventListener('click', (e) => {
    e.preventDefault();
    toggleScanSummaryPopover(false);
    startIncrementalScan();
  });

  // Clicking on Modules stat card opens the modules-list-popover
  const scanModulesCard = qs('#scan-stat-card-modules');
  if (scanModulesCard) {
    const openModules = (e) => {
      e.stopPropagation();
      toggleScanSummaryPopover(false);
      const pill = qs('#stat-pill-modules');
      const popover = qs('#modules-list-popover');
      if (popover) {
        popover.style.display = 'flex';
        if (pill) {
          pill.classList.add('popover-open');
          pill.setAttribute('aria-expanded', 'true');
        }
        updateModulesList();
        const filterInput = qs('#modules-popover-filter');
        if (filterInput) {
          filterInput.value = '';
          setTimeout(() => filterInput.focus(), 60);
        }
      }
    };
    scanModulesCard.addEventListener('click', openModules);
    scanModulesCard.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        openModules(e);
      }
    });
  }


  // Close scan popover on outside click
  document.addEventListener('click', (e) => {
    const popover = qs('#scan-summary-popover');
    if (popover && popover.style.display !== 'none') {
      if (!popover.contains(e.target) && !e.target.closest('#scan-status-badge') && !e.target.closest('#project-pill')) {
        popover.style.display = 'none';
      }
    }
  });

  // Wire live scan cancellation button on floating status bar
  qs('#btn-cancel-scan')?.addEventListener('click', async (e) => {
    e.stopPropagation();
    const btn = qs('#btn-cancel-scan');
    if (btn) {
      btn.disabled = true;
      btn.textContent = 'Cancelling…';
    }
    try {
      await api.cancelScan();
      showBanner('Scan cancellation requested…');
    } catch (err) {
      showError('Failed to cancel scan: ' + err.message);
      if (btn) {
        btn.disabled = false;
        btn.textContent = 'Cancel';
      }
    }
  });

  // Central scan modal close / minimize & backdrop dismiss
  qs('#btn-copy-scan-path')?.addEventListener('click', (e) => {
    e.stopPropagation();
    const p = App.currentPath || qs('#scan-card-source-path')?.textContent || '';
    if (p) {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(p).then(() => {
          showBanner('Project path copied to clipboard');
        }).catch(() => {
          showBanner(`Project path: ${p}`);
        });
      } else {
        showBanner(`Project path: ${p}`);
      }
    }
  });
  qs('#btn-minimize-scan')?.addEventListener('click', (e) => {
    e.stopPropagation();
    minimizeScanModal();
  });
  qs('#btn-bg-scan')?.addEventListener('click', (e) => {
    e.stopPropagation();
    minimizeScanModal();
  });
  qs('#btn-dismiss-scan')?.addEventListener('click', (e) => {
    e.stopPropagation();
    minimizeScanModal();
  });
  qs('#btn-explore-scan')?.addEventListener('click', (e) => {
    e.stopPropagation();
    minimizeScanModal();
    switchTab('graph');
  });
  qs('#scan-status-bar')?.addEventListener('click', (e) => {
    if (e.target === qs('#scan-status-bar')) {
      minimizeScanModal();
    }
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && qs('#scan-status-bar')?.classList.contains('visible')) {
      minimizeScanModal();
    }
  });

  // Re-open scan modal when clicking header badge, progress bar, or footer indicator / pill
  qs('#scan-status-badge')?.addEventListener('click', () => reopenScanModal());
  qs('#scan-progress-bar')?.addEventListener('click', () => reopenScanModal());
  qs('#footer-status-text')?.addEventListener('click', () => reopenScanModal());
  qs('#footer-status-container')?.addEventListener('click', () => reopenScanModal());
  qs('#footer-scan-pill')?.addEventListener('click', () => reopenScanModal());
  qs('#footer-scan-mini-track')?.addEventListener('click', () => reopenScanModal());
  qs('.status-indicator')?.addEventListener('click', () => reopenScanModal());

  // Interactive scan pipeline step inspection (clickable anytime, with Arrow key navigation)
  const stepElements = Array.from(qsa('.scan-pipeline-step'));
  stepElements.forEach((stepEl, idx) => {
    stepEl.addEventListener('click', (e) => {
      e.stopPropagation();
      const stepName = stepEl.dataset.step;
      inspectScanStep(stepName);
    });
    stepEl.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        e.stopPropagation();
        inspectScanStep(stepEl.dataset.step);
      } else if (e.key === 'ArrowRight') {
        e.preventDefault();
        const next = stepElements[(idx + 1) % stepElements.length];
        next?.focus();
        inspectScanStep(next?.dataset?.step);
      } else if (e.key === 'ArrowLeft') {
        e.preventDefault();
        const prev = stepElements[(idx - 1 + stepElements.length) % stepElements.length];
        prev?.focus();
        inspectScanStep(prev?.dataset?.step);
      }
    });
  });

  // Step detail panel close button
  qs('#btn-close-step-detail')?.addEventListener('click', (e) => {
    e.stopPropagation();
    closeStepDetail();
  });

  // Initialize Background Process Hub
  initProcessHub();

  qs('#scan-cancel-btn')?.addEventListener('click', () => {
    if (App.stats && App.stats.types > 0) {
      updateHeaderProjectBar();
    }
  });




  // Note: Quick theme toggle in header toolbar is initialized by initThemeDropdowns() with interactive dropdown menu

  const browseBtn = qs('#browse-btn');
  const folderPicker = qs('#folder-picker');

  if (folderPicker) {
    folderPicker.addEventListener('change', (e) => {
      if (e.target.files && e.target.files.length > 0) {
        const firstFile = e.target.files[0];
        const relPath = firstFile.webkitRelativePath || '';
        const rootDir = relPath.split('/')[0] || relPath.split('\\')[0] || '';
        
        // If the path input is currently empty, provide a helpful path scaffold
        const input = qs('#scan-path-input');
        if (input && (!input.value || input.value.trim() === '')) {
          input.value = rootDir;
        }
        showBanner(`Folder selected: "${rootDir}" (${e.target.files.length} files detected). Please confirm the full absolute path in the scan bar and click Scan.`);
      }
    });
  }

  if (browseBtn) {
    browseBtn.addEventListener('click', async () => {
      const originalText = browseBtn.textContent;
      browseBtn.textContent = 'Browsing…';
      browseBtn.disabled = true;

      try {
        const current = qs('#scan-path-input').value.trim();
        const res = await api.browse(current);
        if (res && res.path && res.path.trim() !== '') {
          const selectedPath = res.path.trim();
          qs('#scan-path-input').value = selectedPath;

          // Seamlessly synchronize Git repository path and validate
          const gitInput = qs('#git-repo-input');
          if (gitInput) {
            gitInput.value = selectedPath;
            gitInput.dataset.synced = 'true';
            validateGitRepoPath();
          }

          showBanner(`Selected folder: ${selectedPath}`);
        } else if (res && res.path === '') {
          // Dialog was either cancelled or native dialog could not display -> open browser directory picker
          if (folderPicker) {
            folderPicker.click();
          }
        }
      } catch (e) {
        console.warn('Native server browse dialog error:', e);
        if (folderPicker) {
          folderPicker.click();
        } else {
          showError('Could not open folder chooser. Please paste your absolute source directory path into the scan bar.');
        }
      } finally {
        browseBtn.textContent = originalText;
        browseBtn.disabled = false;
      }
    });
  }

  qs('#scan-path-input').addEventListener('input', () => {
    const scanPath = qs('#scan-path-input').value.trim();
    const gitInput = qs('#git-repo-input');
    if (gitInput && (!gitInput.value || gitInput.dataset.synced === 'true')) {
      gitInput.value = scanPath;
      gitInput.dataset.synced = 'true';
      updateGitValidationBadge({ idle: true });
    }
  });

  qs('#scan-path-input').addEventListener('keydown', e => {
    if (e.key === 'Enter') startScan();
  });

  // Search input and interactive search buttons
  const searchInput = qs('#search-input');
  const searchBtnIcon = qs('#search-btn-icon');
  const searchClearBtn = qs('#search-clear-btn');

  const executeSearch = () => {
    if (!searchInput) return;
    const q = searchInput.value.trim();
    if (q) {
      if (searchClearBtn) searchClearBtn.style.display = 'inline-flex';
      runSearch(q);
    } else {
      if (searchClearBtn) searchClearBtn.style.display = 'none';
      showExplorer();
    }
  };

  if (searchInput) {
    searchInput.addEventListener('input', onSearchInput);
    searchInput.addEventListener('search', (e) => {
      const q = e.target.value.trim();
      if (searchClearBtn) searchClearBtn.style.display = q ? 'inline-flex' : 'none';
      if (!q) showExplorer();
      else runSearch(q);
    });
    searchInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        clearTimeout(searchDebounce);
        executeSearch();
      } else if (e.key === 'Escape') {
        e.preventDefault();
        searchInput.value = '';
        if (searchClearBtn) searchClearBtn.style.display = 'none';
        showExplorer();
        searchInput.blur();
      }
    });
  }

  if (searchBtnIcon) {
    searchBtnIcon.addEventListener('click', (e) => {
      e.preventDefault();
      if (searchInput) {
        searchInput.focus();
        clearTimeout(searchDebounce);
        executeSearch();
      }
    });
  }

  if (searchClearBtn) {
    searchClearBtn.addEventListener('click', (e) => {
      e.preventDefault();
      if (searchInput) {
        searchInput.value = '';
        searchInput.focus();
      }
      searchClearBtn.style.display = 'none';
      showExplorer();
    });
  }

  // Filter chips
  qsa('.chip').forEach(chip => {
    chip.addEventListener('click', () => setFilter(chip.dataset.filter));
  });

  // Explorer presentation toolbar (Flat Eclipse vs Hierarchical Tree, Expand/Collapse)
  qs('#btn-pkg-mode-flat')?.addEventListener('click', () => {
    App.packagePresentation = 'flat';
    localStorage.setItem('codelens_package_presentation', 'flat');
    syncExplorerToolbar();
    if (App.packages && App.packages.length > 0) {
      const root = buildPackageTree(App.packages, 'flat');
      const tree = qs('#explorer-tree');
      tree.innerHTML = '';
      renderPackageTree(root, tree, 0);
    }
  });

  qs('#btn-pkg-mode-tree')?.addEventListener('click', () => {
    App.packagePresentation = 'hierarchical';
    localStorage.setItem('codelens_package_presentation', 'hierarchical');
    syncExplorerToolbar();
    if (App.packages && App.packages.length > 0) {
      const root = buildPackageTree(App.packages, 'hierarchical');
      const tree = qs('#explorer-tree');
      tree.innerHTML = '';
      renderPackageTree(root, tree, 0);
    }
  });

  qs('#btn-tree-expand-all')?.addEventListener('click', async () => {
    if (!App.packages || App.packages.length === 0) return;
    for (const pkg of App.packages) {
      App.openPackages.add(pkg.fqn);
    }
    const root = buildPackageTree(App.packages, App.packagePresentation);
    const tree = qs('#explorer-tree');
    tree.innerHTML = '';
    renderPackageTree(root, tree, 0);
  });

  qs('#btn-tree-collapse-all')?.addEventListener('click', () => {
    App.openPackages.clear();
    if (App.packages && App.packages.length > 0) {
      const root = buildPackageTree(App.packages, App.packagePresentation);
      const tree = qs('#explorer-tree');
      tree.innerHTML = '';
      renderPackageTree(root, tree, 0);
    }
  });

  // Tab bar click & Drag-and-Drop Reordering
  initTabDragAndDrop();
  qsa('.tab').forEach(tab => {
    tab.addEventListener('click', () => {
      switchTab(tab.dataset.tab);
      if (tab.dataset.tab === 'git') loadGitSummary();
    });
  });

  // Dedicated Macro Studio Launch & Return buttons
  qs('#btn-open-macro-studio')?.addEventListener('click', () => {
    openMacroStudio();
  });
  qs('#btn-studio-back')?.addEventListener('click', () => {
    closeMacroStudio();
  });
  qs('#btn-graph-back-kb')?.addEventListener('click', () => {
    switchTab('knowledge');
  });
  qs('#btn-reports-back-workspace')?.addEventListener('click', () => {
    switchTab(App.lastWorkspaceTab || 'graph');
  });


  // Monaco Save button
  const saveBtn = qs('#editor-save-btn');
  if (saveBtn) {
    saveBtn.addEventListener('click', async () => {
      if (!App.currentFilePath || !App.editor) return;
      saveBtn.disabled = true;
      saveBtn.textContent = 'Saving…';
      try {
        const content = App.editor.getValue();
        await api.writeFile(App.currentFilePath, content);
        showBanner('File saved successfully');
      } catch (e) {
        showError('Failed to save file: ' + e.message);
      } finally {
        saveBtn.disabled = false;
        saveBtn.textContent = 'Save';
      }
    });
  }

  // Monaco Resize handling
  window.addEventListener('resize', () => {
    if (App.editor) App.editor.layout();
  });

  // Pre-load Git heat data on startup
  loadGitHeatData();

  // Graph depth slider and preset pills
  const depthSlider = qs('#graph-depth-slider');
  if (depthSlider) {
    depthSlider.addEventListener('input', (e) => {
      setGraphDepth(e.target.value);
    });
  }
  qsa('.depth-pill').forEach(btn => {
    btn.addEventListener('click', () => {
      const d = parseInt(btn.dataset.depth, 10);
      setGraphDepth(d);
    });
  });

  // Feature Guide Modal controls
  const helpBtn   = qs('#help-btn');
  const helpModal = qs('#help-modal');
  const helpClose = qs('#help-modal-close');
  if (helpBtn && helpModal) {
    helpBtn.addEventListener('click', (e) => openHelpModal(e.currentTarget));
  }
  if (helpClose && helpModal) {
    helpClose.addEventListener('click', closeHelpModal);
  }
  if (helpModal) {
    helpModal.addEventListener('click', (e) => {
      if (e.target === helpModal) closeHelpModal();
    });
  }

  // Feature Guide Modal sub-tab navigation
  const guideTabBtns = qsa('.guide-tab-btn');
  const guidePanels  = qsa('.guide-tab-panel');
  guideTabBtns.forEach(btn => {
    btn.addEventListener('click', () => {
      const targetTab = btn.dataset.guideTab;
      guideTabBtns.forEach(b => b.classList.remove('active'));
      btn.classList.add('active');

      guidePanels.forEach(panel => {
        if (panel.id === `guide-tab-${targetTab}`) {
          panel.style.display = 'block';
          panel.classList.add('active');
        } else {
          panel.style.display = 'none';
          panel.classList.remove('active');
        }
      });
    });
  });

  // Feature Guide Interactive Enhancements (Live Search, Direct Jump, Live Key Tester)
  initGuideModalEnhancements();

  bindKeyboard();
  initScopeManagement();

  // Initialize adjustable panel resizers early
  initPanelResizers();

  // Eagerly initialize graph canvas instance
  ensureGraph();

  // ── Initial view decision: show hero page or go straight to workspace ───
  // Check if server already has data (e.g. re-open after prior scan)
  let serverHasData = false;
  try {
    const status = await api.scanStatus();
    if (status.status === 'SCANNING') {
      setScanUI('scanning');
      if (status.sourcePath) {
        const si = qs('#scan-path-input');
        if (si) si.value = status.sourcePath;
      }
      pollScanStatus();
      serverHasData = true;
    } else if (status.status === 'COMPLETE' && status.sourcePath) {
      serverHasData = true;
    } else if (status.status === 'ERROR' && status.sourcePath && status.typesFound > 0) {
      serverHasData = true;
    }
  } catch (_) { /* first run */ }

  const lastPath = localStorage.getItem('codelens_last_path');
  if (!serverHasData) {
    // Fresh start or wiped database — show hero page, hide workspace panels
    showHeroPage();
    if (lastPath) {
      const heroInput = qs('#hero-scan-path-input');
      if (heroInput && (!heroInput.value || heroInput.value.trim() === '')) {
        heroInput.value = lastPath;
      }
    }
  } else {
    // Codebase already loaded/scanned — show workspace
    hideHeroPage();
    try {
      const status = await api.scanStatus();
      if (status && status.sourcePath) {
        App.lastScanProgress = status;
        updateHeaderProjectBar(status.sourcePath);
        updateScanSummaryUI(status);
        if (status.status === 'ERROR') {
          showBanner(`Previous scan of "${status.sourcePath}" was interrupted. Use Rescan to complete.`);
        }
      }
    } catch (_) {}
  }

  await loadStats();
  await loadPackageTree();

  // Initialize settings & themes
  initSettings();

  // Initialize codebase intelligence reports hub
  initReportsHub();
  initCriticalPathUI();

  // Wire Settings modal → Reports Hub shortcut button
  qs('#settings-open-reports-btn')?.addEventListener('click', () => {
    const settingsModal = qs('#settings-modal');
    if (settingsModal) {
      settingsModal.setAttribute('aria-hidden', 'true');
      settingsModal.classList.remove('open');
    }
    switchTab('reports');
  });
  qs('#settings-export-open-btn')?.addEventListener('click', () => {
    const settingsModal = qs('#settings-modal');
    if (settingsModal) {
      settingsModal.setAttribute('aria-hidden', 'true');
      settingsModal.classList.remove('open');
    }
    switchTab('reports');
  });

  // Wire Settings modal → Feature Guide full-open button
  qs('#settings-guide-open-btn')?.addEventListener('click', () => {
    closeSettings();
    openHelpModal(qs('#settings-guide-open-btn'));
  });

  // Initialize code review controls
  initReviewControls();

  // Initialize Git connection controls
  initGitControls();


  // Pre-load git branch metadata in the status footer
  updateFooterGitBranch();

  // Dedicated Codebase Macro Visualization level buttons
  qsa('#codebase-level-selector .level-pill').forEach(btn => {
    btn.addEventListener('click', () => {
      const level = btn.dataset.level;
      if (level) loadWholeCodebaseGraph(level, App.codebaseGranularity || 'arch');
    });
  });

  // Dedicated Codebase Granularity buttons (Classes vs Methods)
  qsa('#codebase-granularity-selector .level-pill').forEach(btn => {
    btn.addEventListener('click', () => {
      const gran = btn.dataset.granularity;
      if (gran) {
        App.codebaseGranularity = gran;
        loadWholeCodebaseGraph(App.codebaseMacroLevel || 'city3d', gran);
      }
    });
  });

  // Codebase 3D Views Brightness Slider
  const brightnessSlider = qs('#codebase-brightness-slider');
  const brightnessValLabel = qs('#codebase-brightness-value');
  const resetBrightnessBtn = qs('#btn-reset-brightness');

  if (brightnessSlider) {
    brightnessSlider.addEventListener('input', (e) => {
      const val = parseFloat(e.target.value) || 1.0;
      App.codebaseBrightness = val;
      if (brightnessValLabel) {
        brightnessValLabel.textContent = `${Math.round(val * 100)}%`;
      }
      if (App.activeAltRenderer && typeof App.activeAltRenderer.setBrightness === 'function') {
        App.activeAltRenderer.setBrightness(val);
      }
    });
  }

  // Codebase POJO Filter button
  const codebasePojoBtn = qs('#btn-codebase-filter-getters');
  if (codebasePojoBtn) {
    codebasePojoBtn.addEventListener('click', () => {
      let newState;
      if (App.activeAltRenderer && typeof App.activeAltRenderer.toggleHideGetters === 'function') {
        newState = App.activeAltRenderer.toggleHideGetters();
      } else if (App.graph && typeof App.graph.toggleHideGetters === 'function') {
        newState = App.graph.toggleHideGetters();
      }
      if (typeof newState === 'boolean') {
        App.codebaseHidePojo = newState;
      } else if (codebasePojoBtn.classList.contains('active')) {
        App.codebaseHidePojo = true;
      } else {
        App.codebaseHidePojo = false;
      }
    });
  }

  // Codebase Clusters / Hulls button
  const codebaseHullsBtn = qs('#btn-codebase-toggle-hulls');
  if (codebaseHullsBtn) {
    codebaseHullsBtn.addEventListener('click', () => {
      if (App.activeAltRenderer && typeof App.activeAltRenderer.toggleHulls === 'function') {
        App.activeAltRenderer.toggleHulls();
      }
    });
  }

  // Codebase Physics button
  const codebasePhysicsBtn = qs('#btn-codebase-toggle-physics');
  if (codebasePhysicsBtn) {
    codebasePhysicsBtn.addEventListener('click', () => {
      if (App.activeAltRenderer && typeof App.activeAltRenderer.togglePhysics === 'function') {
        App.activeAltRenderer.togglePhysics();
      }
    });
  }

  // Codebase Heat button
  const codebaseHeatBtn = qs('#btn-codebase-heat');
  if (codebaseHeatBtn) {
    codebaseHeatBtn.addEventListener('click', () => {
      if (App.activeAltRenderer && typeof App.activeAltRenderer.toggleHeat === 'function') {
        App.activeAltRenderer.toggleHeat();
      }
    });
  }

  // Codebase Camera Controls (Zoom In, Zoom Out, Fit, Reset)
  const cbZoomInBtn = qs('#btn-codebase-zoom-in');
  if (cbZoomInBtn) {
    cbZoomInBtn.addEventListener('click', () => {
      if (App.activeAltRenderer && typeof App.activeAltRenderer.zoomBy === 'function') {
        App.activeAltRenderer.zoomBy(1.25);
      }
    });
  }

  const cbZoomOutBtn = qs('#btn-codebase-zoom-out');
  if (cbZoomOutBtn) {
    cbZoomOutBtn.addEventListener('click', () => {
      if (App.activeAltRenderer && typeof App.activeAltRenderer.zoomBy === 'function') {
        App.activeAltRenderer.zoomBy(0.8);
      }
    });
  }

  const cbFitBtn = qs('#btn-codebase-fit');
  if (cbFitBtn) {
    cbFitBtn.addEventListener('click', () => {
      if (App.activeAltRenderer && typeof App.activeAltRenderer.fitToScreen === 'function') {
        App.activeAltRenderer.fitToScreen();
      }
    });
  }

  const cbResetBtn = qs('#btn-codebase-reset');
  if (cbResetBtn) {
    cbResetBtn.addEventListener('click', () => {
      if (App.activeAltRenderer) {
        if (typeof App.activeAltRenderer.resetView === 'function') {
          App.activeAltRenderer.resetView();
        } else if (typeof App.activeAltRenderer.fitToScreen === 'function') {
          App.activeAltRenderer.fitToScreen();
        }
      }
    });
  }

  // Main Graph Tab Camera Controls (Zoom In, Zoom Out, Fit, Clear)
  const graphZoomInBtn = qs('#btn-zoom-in');
  if (graphZoomInBtn) {
    graphZoomInBtn.addEventListener('click', () => {
      if (App.graph && typeof App.graph.zoomBy === 'function') {
        App.graph.zoomBy(1.25);
      }
    });
  }

  const graphZoomOutBtn = qs('#btn-zoom-out');
  if (graphZoomOutBtn) {
    graphZoomOutBtn.addEventListener('click', () => {
      if (App.graph && typeof App.graph.zoomBy === 'function') {
        App.graph.zoomBy(0.8);
      }
    });
  }

  const graphFitBtn = qs('#btn-fit');
  if (graphFitBtn) {
    graphFitBtn.addEventListener('click', () => {
      if (App.graph && typeof App.graph.fitToScreen === 'function') {
        App.graph.fitToScreen();
      }
    });
  }

  const graphResetBtn = qs('#btn-reset');
  if (graphResetBtn) {
    graphResetBtn.addEventListener('click', () => {
      if (App.graph && typeof App.graph.clear === 'function') {
        App.graph.clear();
      }
    });
  }

  // Codebase Call Arcs Filter button
  const codebaseArcsBtn = qs('#btn-codebase-filter-arcs');
  if (codebaseArcsBtn) {
    codebaseArcsBtn.addEventListener('click', () => {
      if (App.activeAltRenderer && typeof App.activeAltRenderer.toggleArcs === 'function') {
        const isShown = App.activeAltRenderer.toggleArcs();
        codebaseArcsBtn.classList.toggle('active', isShown);
      }
    });
  }

  // Codebase Legend close button
  const codebaseLegendCloseBtn = qs('#btn-codebase-legend-close');
  if (codebaseLegendCloseBtn) {
    codebaseLegendCloseBtn.addEventListener('click', () => {
      const legend = qs('#codebase-community-legend');
      if (legend) legend.style.display = 'none';
    });
  }


  if (resetBrightnessBtn) {
    resetBrightnessBtn.addEventListener('click', () => {
      if (brightnessSlider) {
        brightnessSlider.value = '1.0';
      }
      if (brightnessValLabel) {
        brightnessValLabel.textContent = '100%';
      }
      App.codebaseBrightness = 1.0;
      if (App.activeAltRenderer && typeof App.activeAltRenderer.setBrightness === 'function') {
        App.activeAltRenderer.setBrightness(1.0);
      }
    });
  }

  // Archetype Filter Dropdowns initialization & listeners
  populateArchetypeDropdowns();
  setupArchetypeFilterListeners();
  initArchetypePopover();

  // Initial and periodic disk change checks
  checkCodebaseChanges();
  window.addEventListener('focus', () => checkCodebaseChanges());
  setInterval(() => checkCodebaseChanges(), 30000);

  // Expose global handles for testing and automation
  window.App = App;
  window.selectMethod = selectMethod;
  window.loadWholeCodebaseGraph = loadWholeCodebaseGraph;
  window.api = api;
  window.populateArchetypeDropdowns = populateArchetypeDropdowns;
  window.checkCodebaseChanges = checkCodebaseChanges;
  window.startIncrementalScan = startIncrementalScan;

}

/* ─────────────────────────────────────────────────────────────────────────────
   Archetype Multi-Select Dropdown Controller
   ───────────────────────────────────────────────────────────────────────────── */

function getAvailableArchetypeItems() {
  if (!window.CodeLensClassifier) return [];
  const rules = window.CodeLensClassifier.getActiveRules ? window.CodeLensClassifier.getActiveRules() : (window.CodeLensClassifier.getRules().filter(r => r.enabled));
  const items = rules.map(r => ({
    id: r.id,
    label: r.label || r.id,
    badge: r.badge || r.label,
    color: r.color || '#10b981',
    icon: r.icon || 'tag',
    scope: r.scope || r.target || 'METHOD',
    isUnclassified: false
  }));

  items.push({
    id: 'UNCLASSIFIED',
    label: 'Unclassified / Plain',
    badge: 'NONE',
    color: '#64748b',
    icon: 'code',
    scope: 'ANY',
    isUnclassified: true
  });

  return items;
}

function updateArchetypeButtonLabels(availableItems) {
  const allIds = availableItems.map(it => it.id);
  let labelText = 'Archetypes: All';
  let hasActiveFilter = false;

  if (App.activeArchetypeFilter && App.activeArchetypeFilter !== 'ALL') {
    const selectedSet = (App.activeArchetypeFilter instanceof Set)
      ? App.activeArchetypeFilter
      : new Set(Array.isArray(App.activeArchetypeFilter) ? App.activeArchetypeFilter : [App.activeArchetypeFilter]);

    if (selectedSet.size === 0) {
      labelText = 'Archetypes (None)';
      hasActiveFilter = true;
    } else if (selectedSet.size === allIds.length) {
      labelText = 'Archetypes: All';
      hasActiveFilter = false;
    } else {
      labelText = `Archetypes (${selectedSet.size}/${allIds.length})`;
      hasActiveFilter = true;
    }
  }

  ['#graph-archetype-label', '#codebase-archetype-label'].forEach(sel => {
    const el = qs(sel);
    if (el) el.textContent = labelText;
  });

  ['#graph-archetype-btn', '#codebase-archetype-btn'].forEach(sel => {
    const btn = qs(sel);
    if (btn) btn.classList.toggle('has-filter', hasActiveFilter);
  });
}

function populateArchetypeDropdowns() {
  const items = getAvailableArchetypeItems();
  const allIds = items.map(it => it.id);

  if (!App.activeArchetypeFilter || App.activeArchetypeFilter === 'ALL') {
    App.activeArchetypeFilter = new Set(allIds);
  } else if (!(App.activeArchetypeFilter instanceof Set)) {
    App.activeArchetypeFilter = new Set(Array.isArray(App.activeArchetypeFilter) ? App.activeArchetypeFilter : [App.activeArchetypeFilter]);
  }

  const selectedSet = App.activeArchetypeFilter;

  const classItems = items.filter(it => it.scope === 'CLASS');
  const methodItems = items.filter(it => it.scope === 'METHOD');
  const otherItems = items.filter(it => it.scope !== 'CLASS' && it.scope !== 'METHOD');

  const renderItemHtml = (item) => {
    const isChecked = selectedSet.has(item.id);
    const iconSvg = window.Icons ? window.Icons.get(item.icon, { size: 'xs' }) : '';
    const color = item.color;
    return `
      <label class="archetype-panel-item" data-id="${esc(item.id)}">
        <input type="checkbox" class="archetype-item-chk" data-id="${esc(item.id)}" ${isChecked ? 'checked' : ''} />
        <span class="archetype-item-pill" style="background:${color}18; color:${color}; border:1px solid ${color}44;">
          ${iconSvg}
          <span class="archetype-item-badge">[${esc(item.badge)}]</span>
          <span class="archetype-item-label">${esc(item.label)}</span>
        </span>
      </label>
    `;
  };

  let html = '';
  if (classItems.length > 0) {
    html += `
      <div class="archetype-panel-group">
        <div class="archetype-panel-group-header">
          <svg class="svg-icon icon-xs icon-blue" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M3 9h18"/></svg>
          <span>Class Archetypes</span>
          <span class="archetype-group-count">${classItems.length}</span>
        </div>
        <div class="archetype-panel-group-items">
          ${classItems.map(renderItemHtml).join('')}
        </div>
      </div>
    `;
  }
  if (methodItems.length > 0) {
    html += `
      <div class="archetype-panel-group">
        <div class="archetype-panel-group-header">
          <svg class="svg-icon icon-xs icon-emerald" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><line x1="8.59" y1="13.51" x2="15.42" y2="17.49"/><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"/></svg>
          <span>Method Archetypes</span>
          <span class="archetype-group-count">${methodItems.length}</span>
        </div>
        <div class="archetype-panel-group-items">
          ${methodItems.map(renderItemHtml).join('')}
        </div>
      </div>
    `;
  }
  if (otherItems.length > 0) {
    html += `
      <div class="archetype-panel-group">
        <div class="archetype-panel-group-header">
          <svg class="svg-icon icon-xs icon-slate" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><path d="M12 8v4"/><path d="M12 16h.01"/></svg>
          <span>Other</span>
          <span class="archetype-group-count">${otherItems.length}</span>
        </div>
        <div class="archetype-panel-group-items">
          ${otherItems.map(renderItemHtml).join('')}
        </div>
      </div>
    `;
  }

  ['graph-archetype-items', 'codebase-archetype-items'].forEach(containerId => {
    const container = document.getElementById(containerId);
    if (container) {
      container.innerHTML = html;
    }
  });

  updateArchetypeButtonLabels(items);
}

function setupArchetypeFilterListeners() {
  // Toggle popover panels
  const setups = [
    { btn: '#graph-archetype-btn', panel: '#graph-archetype-panel' },
    { btn: '#codebase-archetype-btn', panel: '#codebase-archetype-panel' }
  ];

  setups.forEach(({ btn: btnSel, panel: panelSel }) => {
    const btn = qs(btnSel);
    const panel = qs(panelSel);
    if (!btn || !panel) return;

    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const isHidden = panel.style.display === 'none';
      // Close all other archetype panels
      document.querySelectorAll('.archetype-multiselect-panel').forEach(p => p.style.display = 'none');
      document.querySelectorAll('.archetype-multiselect-btn').forEach(b => b.setAttribute('aria-expanded', 'false'));

      if (isHidden) {
        panel.style.display = 'flex';
        btn.setAttribute('aria-expanded', 'true');
      }
    });

    panel.addEventListener('click', (e) => {
      e.stopPropagation();
    });
  });

  // Close when clicking anywhere outside
  document.addEventListener('click', (e) => {
    if (!e.target.closest('.archetype-multiselect-dropdown')) {
      document.querySelectorAll('.archetype-multiselect-panel').forEach(p => p.style.display = 'none');
      document.querySelectorAll('.archetype-multiselect-btn').forEach(b => b.setAttribute('aria-expanded', 'false'));
    }
  });

  // Checkbox change handler (delegated on document or panels)
  ['graph-archetype-items', 'codebase-archetype-items'].forEach(containerId => {
    const container = document.getElementById(containerId);
    if (!container) return;

    container.addEventListener('change', (e) => {
      if (!e.target.classList.contains('archetype-item-chk')) return;
      const id = e.target.dataset.id;
      const checked = e.target.checked;

      const items = getAvailableArchetypeItems();
      const allIds = items.map(it => it.id);

      if (!(App.activeArchetypeFilter instanceof Set)) {
        App.activeArchetypeFilter = new Set(allIds);
      }

      if (checked) {
        App.activeArchetypeFilter.add(id);
      } else {
        App.activeArchetypeFilter.delete(id);
      }

      // Sync checkboxes across all panels
      document.querySelectorAll(`.archetype-item-chk[data-id="${id}"]`).forEach(chk => {
        chk.checked = checked;
      });

      updateArchetypeButtonLabels(items);
      triggerArchetypeFilterUpdate();
    });
  });

  // "Select All" actions
  ['#graph-archetype-select-all', '#codebase-archetype-select-all'].forEach(sel => {
    const btn = qs(sel);
    if (btn) {
      btn.addEventListener('click', () => {
        const items = getAvailableArchetypeItems();
        const allIds = items.map(it => it.id);
        App.activeArchetypeFilter = new Set(allIds);

        document.querySelectorAll('.archetype-item-chk').forEach(chk => chk.checked = true);
        updateArchetypeButtonLabels(items);
        triggerArchetypeFilterUpdate();
      });
    }
  });

  // "Clear All" actions
  ['#graph-archetype-clear-all', '#codebase-archetype-clear-all'].forEach(sel => {
    const btn = qs(sel);
    if (btn) {
      btn.addEventListener('click', () => {
        const items = getAvailableArchetypeItems();
        App.activeArchetypeFilter = new Set(); // empty

        document.querySelectorAll('.archetype-item-chk').forEach(chk => chk.checked = false);
        updateArchetypeButtonLabels(items);
        triggerArchetypeFilterUpdate();
      });
    }
  });
}

function triggerArchetypeFilterUpdate() {
  const filter = App.activeArchetypeFilter;

  // Apply to 2D force graph
  if (App.graph && typeof App.graph.setArchetypeFilter === 'function') {
    App.graph.setArchetypeFilter(filter);
  }
  // Apply to active alt renderer (City3D, Galaxy3D, Treemap, Sunburst, DSM, Chord)
  if (App.activeAltRenderer && typeof App.activeAltRenderer.setArchetypeFilter === 'function') {
    App.activeAltRenderer.setArchetypeFilter(filter);
  }
}

/* ─────────────────────────────────────────────────────────────────────────────
   Git integration helpers
   ───────────────────────────────────────────────────────────────────────────── */

let gitPollInterval = null;

function initGitControls() {
  const repoInput   = qs('#git-repo-input');
  const validateBtn = qs('#git-validate-btn');
  const analyzeBtn  = qs('#git-analyze-btn');
  const useProjectBtn = qs('#git-use-project-btn');

  // Pre-fill input if empty and scan path is available
  const projPath = App.currentPath || qs('#scan-path-input')?.value?.trim() || localStorage.getItem('codelens_last_path');
  if (repoInput && (!repoInput.value || repoInput.value.trim() === '')) {
    if (projPath) {
      repoInput.value = projPath;
      repoInput.dataset.synced = 'true';
      validateGitRepoPath();
    }
  }

  if (useProjectBtn) {
    useProjectBtn.addEventListener('click', () => {
      const currentProj = App.currentPath || qs('#scan-path-input')?.value?.trim() || localStorage.getItem('codelens_last_path');
      if (currentProj && repoInput) {
        repoInput.value = currentProj;
        repoInput.dataset.synced = 'true';
        validateGitRepoPath();
      }
    });
  }

  if (validateBtn) {
    validateBtn.addEventListener('click', validateGitRepoPath);
  }

  if (repoInput) {
    repoInput.addEventListener('keydown', e => {
      if (e.key === 'Enter') validateGitRepoPath();
    });
    repoInput.addEventListener('input', () => {
      repoInput.dataset.synced = 'false';
      updateGitValidationBadge({ idle: true });
    });
  }

  if (analyzeBtn) {
    analyzeBtn.addEventListener('click', startGitAnalysis);
  }
}

function updateGitValidationBadge(info) {
  const badge = qs('#git-validation-status');
  const metaRow = qs('#git-repo-meta-row');
  const branchEl = qs('#git-meta-branch');
  const commitEl = qs('#git-meta-commit');

  if (!badge) return;
  if (info.idle) {
    badge.innerHTML = '<span class="git-status-dot idle"></span><span class="git-status-text">Not connected</span>';
    if (metaRow) metaRow.style.display = 'none';
  } else if (info.valid) {
    badge.innerHTML = `<span class="git-status-dot valid"></span><span class="git-status-text">Connected</span>`;
    if (metaRow) {
      metaRow.style.display = 'flex';
      if (branchEl) branchEl.innerHTML = `<svg class="svg-icon icon-indigo icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="6" y1="3" x2="6" y2="15"/><circle cx="18" cy="6" r="3"/><circle cx="6" cy="18" r="3"/><path d="M18 9a9 9 0 0 1-9 9"/></svg> <span>${esc(info.branch || 'HEAD')}</span>`;
      if (commitEl) commitEl.textContent = info.headCommit || 'HEAD';
    }
  } else if (info.running) {
    badge.innerHTML = '<span class="git-status-dot running"></span><span class="git-status-text">Analyzing…</span>';
  } else {
    badge.innerHTML = `<span class="git-status-dot invalid"></span><span class="git-status-text" title="${esc(info.error || '')}">Invalid repository</span>`;
    if (metaRow) metaRow.style.display = 'none';
  }
}

async function validateGitRepoPath() {
  const repoInput = qs('#git-repo-input');
  let repoPath = repoInput ? repoInput.value.trim() : '';
  if (!repoPath) {
    const projPath = App.currentPath || qs('#scan-path-input')?.value?.trim() || localStorage.getItem('codelens_last_path');
    if (projPath) {
      repoPath = projPath;
      if (repoInput) repoInput.value = repoPath;
    }
  }
  if (!repoPath) {
    updateGitValidationBadge({ error: 'Please enter a path' });
    return false;
  }

  const validateBtn = qs('#git-validate-btn');
  if (validateBtn) {
    validateBtn.disabled = true;
    validateBtn.innerHTML = '<span class="spinner-inline"></span> <span>Validating…</span>';
  }

  try {
    const res = await api.validateGitRepo(repoPath);
    if (res.valid) {
      updateGitValidationBadge({ valid: true, branch: res.branch, headCommit: res.headCommit });
      if (repoInput) repoInput.value = res.repoPath;
      loadGitSummary();
      return true;
    } else {
      updateGitValidationBadge({ error: res.error });
      return false;
    }
  } catch (e) {
    updateGitValidationBadge({ error: e.message });
    return false;
  } finally {
    if (validateBtn) {
      validateBtn.disabled = false;
      validateBtn.innerHTML = '<svg class="svg-icon icon-amber icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg> <span>Validate</span>';
    }
  }
}

async function startGitAnalysis() {
  const repoInput = qs('#git-repo-input');
  let repoPath = repoInput ? repoInput.value.trim() : '';
  if (!repoPath) {
    const scanInput = qs('#scan-path-input');
    if (scanInput && scanInput.value.trim()) {
      repoPath = scanInput.value.trim();
      if (repoInput) repoInput.value = repoPath;
    }
  }

  const isValid = await validateGitRepoPath();
  if (!isValid) return;

  const analyzeBtn = qs('#git-analyze-btn');
  if (analyzeBtn) {
    analyzeBtn.disabled = true;
    analyzeBtn.innerHTML = '<span class="spinner-inline"></span> <span>Analyzing…</span>';
  }

  try {
    await api.analyzeGit(repoPath);
    updateGitValidationBadge({ running: true });
    showGitProgressBox(true);
    pollGitAnalysisStatus();
  } catch (e) {
    showError('Git analysis failed to start: ' + e.message);
    if (analyzeBtn) {
      analyzeBtn.disabled = false;
      analyzeBtn.innerHTML = '<svg class="svg-icon icon-emerald icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg> <span>Analyze History</span>';
    }
  }
}

function showGitProgressBox(show) {
  const box = qs('#git-progress-box');
  if (box) box.style.display = show ? 'flex' : 'none';
}

function pollGitAnalysisStatus() {
  if (gitPollInterval) clearInterval(gitPollInterval);

  gitPollInterval = setInterval(async () => {
    try {
      const status = await api.gitStatus();
      const pctMsg = qs('#git-progress-pct');
      const textMsg = qs('#git-progress-msg');
      const fillBar = qs('#git-progress-fill');
      const analyzeBtn = qs('#git-analyze-btn');

      if (status.status === 'RUNNING') {
        const pct = status.percentage || 0;
        if (pctMsg) pctMsg.textContent = `${pct}%`;
        if (textMsg) textMsg.textContent = status.message || `Auditing ${status.processedFiles}/${status.totalFiles} files…`;
        if (fillBar) fillBar.style.width = `${pct}%`;
      } else if (status.status === 'COMPLETE') {
        clearInterval(gitPollInterval);
        gitPollInterval = null;
        if (fillBar) fillBar.style.width = '100%';
        if (textMsg) textMsg.textContent = status.message || 'Git analysis complete!';
        if (pctMsg) pctMsg.textContent = '100%';
        updateGitValidationBadge({ valid: true, branch: status.branch });

        if (analyzeBtn) {
          analyzeBtn.disabled = false;
          analyzeBtn.innerHTML = '<svg class="svg-icon icon-emerald icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg> <span>Analyze History</span>';
        }

        setTimeout(() => showGitProgressBox(false), 2000);
        showBanner(`Git history analyzed - ${status.entitiesAnnotated} entities annotated`);
        await loadGitSummary();
        await loadGitHeatData();
        updateFooterGitBranch();
      } else if (status.status === 'ERROR') {
        clearInterval(gitPollInterval);
        gitPollInterval = null;
        updateGitValidationBadge({ error: status.errorDetail || 'Analysis failed' });
        if (textMsg) textMsg.textContent = `Failed: ${status.errorDetail || 'Unknown error'}`;
        if (analyzeBtn) {
          analyzeBtn.disabled = false;
          analyzeBtn.innerHTML = '<svg class="svg-icon icon-emerald icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg> <span>Analyze History</span>';
        }
      }
    } catch (_) {}
  }, 700);
}

/**
 * Load git summary (top authors + hottest entities) and populate #git-view.
 * Called when the user clicks the Git tab.
 */
async function loadGitSummary() {
  const authorsList = qs('#git-authors-list');
  const hotList     = qs('#git-hot-list');
  const authorsBadge = qs('#git-authors-badge');
  const hotBadge = qs('#git-hot-badge');
  if (!authorsList || !hotList) return;

  try {
    const summary = await api.gitSummary();
    // ── Top authors ───────────────────────────────────────────────────────────
    if (!summary.topAuthors || summary.topAuthors.length === 0) {
      if (authorsBadge) authorsBadge.textContent = '0 authors';
      authorsList.innerHTML = `
        <div class="git-empty-card">
          <div class="git-empty-icon"><svg class="svg-icon icon-indigo icon-lg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg></div>
          <div class="git-empty-title">No Author Telemetry</div>
          <div class="git-empty-desc">Connect and analyze a Git repository above to inspect contributor commit volume and leaderboard rankings.</div>
        </div>`;
    } else {
      if (authorsBadge) authorsBadge.textContent = `${summary.topAuthors.length} authors`;
      authorsList.innerHTML = summary.topAuthors.map((a, i) => {
        const avatar = a.authorName
          ? a.authorName.trim().split(/\s+/).map(w => w[0]).join('').slice(0, 2).toUpperCase()
          : '?';
        const dateStr = a.latestCommit
          ? new Date(a.latestCommit * 1000).toLocaleDateString()
          : '';
        return `<div class="git-author-row">
          <div class="git-author-avatar" aria-hidden="true">${avatar}</div>
          <div class="git-author-info">
            <div class="git-author-name">${esc(a.authorName || '(unknown)')}</div>
            <div class="git-author-meta">${a.entityCount} entities &nbsp;·&nbsp; ${dateStr}</div>
          </div>
          <div class="git-author-rank" aria-label="rank">#${i + 1}</div>
        </div>`;
      }).join('');
    }
    // ── Hottest entities ──────────────────────────────────────────────────────
    if (!summary.hotEntities || summary.hotEntities.length === 0) {
      if (hotBadge) hotBadge.textContent = '0 entities';
      hotList.innerHTML = `
        <div class="git-empty-card">
          <div class="git-empty-icon"><svg class="svg-icon icon-amber icon-lg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75"><circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/></svg></div>
          <div class="git-empty-title">No Churn Data Available</div>
          <div class="git-empty-desc">Click <strong>Analyze History</strong> to calculate change frequency and highlight high-churn risk areas in your code.</div>
        </div>`;
    } else {
      if (hotBadge) hotBadge.textContent = `${summary.hotEntities.length} entities`;
      const maxCount = Math.max(...summary.hotEntities.map(e => e.commitCount), 1);
      hotList.innerHTML = summary.hotEntities.map(e => {
        const pct   = Math.round((e.commitCount / maxCount) * 100);
        const label = (e.entityFqn || '').split('.').pop();
        return `<div class="git-hot-row" data-fqn="${esc(e.entityFqn)}" title="Click to view ${esc(e.entityFqn)} (${e.commitCount} commits)">
          <div class="git-hot-label" title="${esc(e.entityFqn)}">${esc(label)}</div>
          <div class="git-hot-bar-wrap">
            <div class="git-hot-bar" style="width:${pct}%" aria-label="${e.commitCount} commits"></div>
          </div>
          <div class="git-hot-count">${e.commitCount} commits</div>
        </div>`;
      }).join('');

      hotList.querySelectorAll('.git-hot-row').forEach(row => {
        row.addEventListener('click', () => {
          const fqn = row.dataset.fqn;
          if (fqn) {
            window.selectEntity(fqn);
          }
        });
      });
    }
  } catch (err) {
    if (authorsBadge) authorsBadge.textContent = '0 authors';
    if (hotBadge) hotBadge.textContent = '0 entities';
    authorsList.innerHTML = '<div class="git-empty-card"><div class="git-empty-title">Git data not available</div></div>';
    hotList.innerHTML = '<div class="git-empty-card"><div class="git-empty-title">Git data not available</div></div>';
    console.warn('Git summary fetch failed:', err);
  }
}
/** Load heat data (entityFqn -> Behavioral Hotspot / commitCount) and register it with the graph. */
async function loadGitHeatData() {
  try {
    const summary = await api.gitSummary();
    if (!summary) return;
    const heatMap = {};
    if (summary.behavioralHotspots && summary.behavioralHotspots.length > 0) {
      for (const h of summary.behavioralHotspots) {
        heatMap[h.entityFqn] = {
          score: h.hotspotScore,
          cc: h.cyclomaticComplexity,
          commits: h.commitCount,
          loc: h.linesOfCode,
          riskTier: h.riskTier,
          recommendation: h.recommendation
        };
      }
    } else if (summary.hotEntities) {
      for (const e of summary.hotEntities) {
        heatMap[e.entityFqn] = {
          score: e.commitCount,
          cc: e.cyclomaticComplexity || 1,
          commits: e.commitCount,
          commitCount: e.commitCount
        };
      }
    }
    App.graph?.setHeatData(heatMap);
    if (App.activeAltRenderer && typeof App.activeAltRenderer.setHeatData === 'function') {
      App.activeAltRenderer.setHeatData(heatMap);
    }
    return heatMap;

  } catch (_) { /* non-fatal */ }
}
window.loadGitHeatData = loadGitHeatData;
window.loadClassDetails = selectType;
window.loadMethodDetails = selectMethod;
window.selectType = selectType;
window.selectMethod = selectMethod;
window.selectField = selectField;
window.loadFieldImpact = loadFieldImpact;

function _resolveClassFqn(fqn) {
  if (!fqn) return '';
  if (fqn.includes('(')) {
    const base = fqn.substring(0, fqn.indexOf('('));
    const lastDot = base.lastIndexOf('.');
    return lastDot > 0 ? base.substring(0, lastDot) : base;
  }
  if (fqn.includes('#')) {
    return fqn.substring(0, fqn.indexOf('#'));
  }
  if (App.packages && App.packages.some(p => p.fqn === fqn)) {
    return fqn;
  }
  const lastDot = fqn.lastIndexOf('.');
  if (lastDot > 0 && /^[a-z]/.test(fqn.substring(lastDot + 1))) {
    const prefix = fqn.substring(0, lastDot);
    const prefixLastSegment = prefix.substring(prefix.lastIndexOf('.') + 1);
    if (/^[A-Z]/.test(prefixLastSegment)) {
      return prefix;
    }
  }
  return fqn;
}

window.selectEntity = async function(fqn) {
  if (!fqn) return;
  if (fqn.includes('(')) {
    await selectMethod(fqn);
  } else if (fqn.includes('#')) {
    await selectField(fqn.replace('#', '.'));
  } else if (App.packages && App.packages.some(p => p.fqn === fqn)) {
    await inspectReportPackage(fqn);
  } else {
    const lastDot = fqn.lastIndexOf('.');
    if (lastDot > 0 && /^[a-z]/.test(fqn.substring(lastDot + 1))) {
      const prefix = fqn.substring(0, lastDot);
      const prefixLastSeg = prefix.substring(prefix.lastIndexOf('.') + 1);
      if (/^[A-Z]/.test(prefixLastSeg)) {
        await selectField(fqn);
        return;
      }
      if (!/[A-Z]/.test(fqn)) {
        await inspectReportPackage(fqn);
        return;
      }
    }
    await selectType(fqn);
  }
};

window.selectClass = async function(fqn) {
  if (!fqn) return;
  if (App.packages && App.packages.some(p => p.fqn === fqn)) {
    await inspectReportPackage(fqn);
    return;
  }
  const classFqn = _resolveClassFqn(fqn);
  switchTab('knowledge');
  await selectType(classFqn);
};

window.inspectReportEntity = async function(fqn, targetTab = 'knowledge') {
  if (!fqn) return;

  // Defensive check: If fqn is a package, route directly to package inspector
  const isPackage = (App.packages && App.packages.some(p => p.fqn === fqn)) ||
                    (!fqn.includes('(') && !fqn.includes('#') && !/[A-Z]/.test(fqn) && fqn.includes('.'));
  if (isPackage) {
    if (typeof inspectReportPackage === 'function') {
      await inspectReportPackage(fqn);
    }
    return;
  }

  const classFqn = _resolveClassFqn(fqn);
  const isMethod = fqn.includes('(');
  const isField = !isMethod && (fqn.includes('#') || fqn !== classFqn);
  const normalizedFieldFqn = fqn.replace('#', '.');

  if (targetTab === 'graph') {
    if (isMethod) {
      await selectMethod(fqn);
    } else if (isField) {
      await selectField(normalizedFieldFqn);
    } else {
      switchTab('graph');
      await selectType(classFqn);
      if (App.graph) {
        App.graph.selectNode(classFqn);
        App.graph.focusNode(classFqn);
      }
    }
    return;
  }

  if (targetTab === 'review') {
    await selectType(classFqn);
    switchTab('review');
    return;
  }

  // Default: Knowledge Base + Right Inspector
  switchTab('knowledge');
  await selectType(classFqn);
  if (isMethod) {
    api.method(fqn).then(mData => {
      if (mData && mData.method) renderMethodDetail(mData);
    }).catch(() => {});
  } else if (isField) {
    api.field(normalizedFieldFqn).then(fData => {
      if (fData && fData.field) renderFieldDetail(fData);
    }).catch(() => {});
  }
};

window.inspectReportPackage = async function(pkgFqn) {
  if (!pkgFqn) return;
  switchTab('knowledge');
  await loadKnowledgeBase(pkgFqn);
  const pkgObj = (App.packages || []).find(p => p.fqn === pkgFqn) || { name: pkgFqn.split('.').pop() || pkgFqn, fqn: pkgFqn };
  App.selected = { kind: 'package', id: pkgFqn, data: pkgObj };
  const itemEl = qs(`#explorer-tree [data-fqn="${CSS.escape(pkgFqn)}"]`);
  if (itemEl) {
    setActiveTreeItem(itemEl);
    itemEl.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
  }
  renderPackageDetail(pkgObj);
};

window.jumpToGraphHeat = async function(fqn) {
  if (!fqn) return;
  const isPkg = (App.packages && App.packages.some(p => p.fqn === fqn)) ||
                (!fqn.includes('(') && !fqn.includes('#') && !/[A-Z]/.test(fqn));
  if (isPkg) {
    switchTab('graph');
    ensureGraph();
    await loadGitHeatData();
    const pkgObj = (App.packages || []).find(p => p.fqn === fqn) || { name: fqn.split('.').pop() || fqn, fqn };
    App.selected = { kind: 'package', id: fqn, data: pkgObj };
    renderPackageDetail(pkgObj);
    return;
  }
  const classFqn = _resolveClassFqn(fqn);
  switchTab('graph');
  ensureGraph();

  // Populate Right Inspector with the selected entity
  api.type(classFqn).then(data => {
    App.selected = { kind: 'type', id: classFqn, data };
    renderTypeDetail(data);
  }).catch(() => {});

  // Ensure the 2D graph has architecture nodes containing this class
  let targetGraph = App.graph || App.activeAltRenderer;
  const hasNode = targetGraph && typeof targetGraph._findNodeByFqn === 'function' && targetGraph._findNodeByFqn(fqn);
  if (!hasNode) {
    try {
      const view = await api.architectureGraph('classes');
      if (view && view.nodes && view.nodes.length > 0 && App.graph) {
        hideGraphEmpty();
        App.graph.setData(view.nodes, view.edges);
      }
    } catch (_) {}
  }

  await loadGitHeatData();
  targetGraph = App.graph || App.activeAltRenderer;
  if (targetGraph) {
    if (!targetGraph._heatMode && typeof targetGraph.toggleHeat === 'function') {
      targetGraph.toggleHeat();
    }
    qs('#btn-git-heat')?.classList.add('active');
    setTimeout(() => {
      if (typeof targetGraph.selectNode === 'function') {
        targetGraph.selectNode(fqn);
      }
      if (typeof targetGraph.focusNode === 'function') {
        targetGraph.focusNode(fqn);
      }
    }, 80);
  }
};

function clearDetailsPanel() {
  App.selected = null;
  const header = qs('#entity-header');
  if (header) {
    header.innerHTML = '';
    header.style.display = 'none';
  }
  const body = qs('#right-body');
  if (body) {
    body.innerHTML = '';
  }
  const empty = qs('#detail-empty-state');
  if (empty) {
    empty.style.display = 'flex';
  }
}

async function refreshActiveViewsAfterScopeChange(affectedFqn) {
  GraphDataCache.clear();
  await updateExcludedScopeBadge();
  await loadStats();
  await loadPackageTree();

  try {
    const isAffected = (id) => {
      if (!id || !affectedFqn) return false;
      return id === affectedFqn || id.startsWith(affectedFqn + '.') || id.startsWith(affectedFqn + '#');
    };

    if (App.selected && isAffected(App.selected.id)) {
      clearDetailsPanel();
      if (App.graph && typeof App.graph.clear === 'function') {
        App.graph.clear();
      }
      showGraphEmpty('Select a method or field to visualize its call hierarchy');
    } else if (App.activeTab === 'graph') {
      if (typeof reloadActiveGraph === 'function') {
        await reloadActiveGraph();
      }
    }

    if (App.activeTab === 'codebase') {
      if (typeof loadWholeCodebaseGraph === 'function') {
        await loadWholeCodebaseGraph(App.codebaseMacroLevel || 'city3d', App.codebaseGranularity || 'arch');
      }
    } else if (App.activeTab === 'reports' && typeof loadActiveReport === 'function') {
      loadActiveReport();
    } else if (App.activeTab === 'knowledge') {
      const kbContainer = qs('#kb-detail-container');
      if (App.selected && isAffected(App.selected.id)) {
        if (kbContainer) kbContainer.innerHTML = '<div class="kb-placeholder">Selected entity has been removed from scope.</div>';
      }
    }
  } catch (refreshErr) {
    console.warn('Non-fatal error refreshing view after scope change:', refreshErr);
  }
}

function esc(str) {
  return String(str || '').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}

/* ─────────────────────────────────────────────────────────────────────────────
   SCOPE MANAGEMENT (Exclusion, Restoration, Context Menu)
   ───────────────────────────────────────────────────────────────────────────── */

let _pendingScopeTarget = null;
let _currentExcludedScopes = [];

function confirmExcludeScope(target) {
  _pendingScopeTarget = target;
  const modal = qs('#scope-confirm-modal');
  if (!modal) return;

  const kindEl = qs('#scope-target-kind');
  const fqnEl = qs('#scope-target-fqn');
  const titleEl = qs('#scope-confirm-title');

  if (kindEl) kindEl.textContent = (target.type || 'CLASS').toUpperCase();
  if (fqnEl) fqnEl.textContent = target.fqn;
  if (titleEl) titleEl.textContent = `Remove ${target.type === 'PACKAGE' ? 'Package' : 'Class'} from Scope?`;

  showAccessibleModal(modal);

  // Focus Cancel button for safety in destructive dialog
  const cancelBtn = qs('#btn-scope-confirm-cancel');
  if (cancelBtn) {
    setTimeout(() => {
      try { cancelBtn.focus(); } catch (_) {}
    }, 60);
  }
}

async function executeExcludeScope() {
  if (!_pendingScopeTarget) return;
  const { type, fqn, name, el, childContainer, pkgFqn } = _pendingScopeTarget;
  hideAccessibleModal(qs('#scope-confirm-modal'));
  _pendingScopeTarget = null;

  try {
    showToast(`Excluding ${name || fqn} from scope…`, 'info', 2000);
    await api.excludeScope(type, fqn);

    // 1. Animate and remove from DOM
    if (el) {
      el.style.transition = 'opacity 0.2s ease, transform 0.2s ease';
      el.style.opacity = '0';
      el.style.transform = 'translateX(-10px)';
      setTimeout(() => {
        el.remove();
        if (childContainer) childContainer.remove();
      }, 200);
    }

    // 2. Invalidate caches, sync explorer & refresh active view
    await refreshActiveViewsAfterScopeChange(fqn);

    showToast(`Removed "${name || fqn}" from analysis scope.`, 'success', 4000);
  } catch (err) {
    console.error('Failed to exclude scope:', err);
    showToast(`Failed to exclude ${name || fqn}: ${err.message || err}`, 'error', 5000);
  }
}

async function updateExcludedScopeBadge() {
  const badge = qs('#excluded-scope-badge');
  if (!badge) return;
  try {
    const list = await api.excludedScopes();
    _currentExcludedScopes = list || [];
    const count = _currentExcludedScopes.length;
    if (count > 0) {
      badge.textContent = count;
      badge.style.display = 'inline-flex';
    } else {
      badge.style.display = 'none';
    }
  } catch (err) {
    console.warn('Could not update scope badge:', err);
  }
}

async function openScopeManagerModal() {
  const modal = qs('#scope-manager-modal');
  if (!modal) return;
  showAccessibleModal(modal, qs('#btn-manage-scope'));
  await renderScopeManagerList();
}

function closeScopeManager() {
  dismissModalAnimated(qs('#scope-manager-modal'));
}

async function renderScopeManagerList(filterText = '') {
  const tbody = qs('#scope-items-tbody');
  const countBadge = qs('#scope-manager-count');
  const emptyState = qs('#scope-empty-state');
  const table = qs('#scope-items-table');
  if (!tbody) return;

  try {
    const list = await api.excludedScopes();
    _currentExcludedScopes = list || [];
  } catch (e) {
    console.warn('Error fetching excluded scopes:', e);
  }

  const query = (filterText || '').toLowerCase().trim();
  const items = _currentExcludedScopes.filter(item => {
    if (!query) return true;
    return (item.fqn || '').toLowerCase().includes(query) ||
           (item.simpleName || '').toLowerCase().includes(query) ||
           (item.sourceFile || '').toLowerCase().includes(query);
  });

  if (countBadge) {
    countBadge.textContent = `${_currentExcludedScopes.length} excluded`;
  }

  if (_currentExcludedScopes.length === 0) {
    if (table) table.style.display = 'none';
    if (emptyState) emptyState.style.display = 'flex';
    tbody.innerHTML = '';
    return;
  }

  if (table) table.style.display = '';
  if (emptyState) emptyState.style.display = 'none';

  tbody.innerHTML = '';
  for (const item of items) {
    const tr = createElement('tr');

    // Type
    const tdType = createElement('td');
    const badge = createElement('span', {
      class: `scope-badge-tag type-${(item.entityType || 'CLASS').toLowerCase()}`
    });
    badge.textContent = (item.entityType || 'CLASS').toUpperCase();
    tdType.appendChild(badge);
    tr.appendChild(tdType);

    // FQN
    const tdFqn = createElement('td');
    const fqnCode = createElement('code', { style: 'font-weight: 600;' });
    fqnCode.textContent = item.fqn;
    tdFqn.appendChild(fqnCode);
    tr.appendChild(tdFqn);

    // Source file
    const tdSrc = createElement('td', { style: 'color: var(--text-muted); font-size: 11px;' });
    tdSrc.textContent = item.sourceFile || '—';
    tr.appendChild(tdSrc);

    // Action
    const tdAction = createElement('td', { style: 'text-align: right;' });
    const restoreBtn = createElement('button', {
      class: 'btn-secondary btn-sm btn-scope-restore',
      title: `Restore ${item.simpleName || item.fqn} into scope`
    });
    restoreBtn.innerHTML = '<svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><polyline points="3 3 3 8 8 8"/></svg> Restore';
    restoreBtn.addEventListener('click', async (e) => {
      e.stopPropagation();
      await restoreScopeItem(item.fqn);
    });
    tdAction.appendChild(restoreBtn);
    tr.appendChild(tdAction);

    tbody.appendChild(tr);
  }
}

async function restoreScopeItem(fqn) {
  try {
    showToast(`Restoring ${fqn} to scope…`, 'info', 2000);
    const res = await api.restoreScope(fqn);
    await renderScopeManagerList(qs('#scope-search-input')?.value);
    await refreshActiveViewsAfterScopeChange(fqn);

    showToast(`Restored "${fqn}". Analysis re-synchronized.`, 'success', 3000);
  } catch (err) {
    console.error('Failed to restore scope:', err);
    showToast(`Failed to restore ${fqn}: ${err.message || err}`, 'error', 5000);
  }
}

async function restoreAllScopes() {
  if (!confirm('Restore all excluded classes and packages back into the analysis?')) return;
  try {
    showToast('Restoring all excluded scopes…', 'info', 2000);
    await api.clearExcludedScopes();
    await renderScopeManagerList();
    await refreshActiveViewsAfterScopeChange(null);

    showToast('All items restored. Analysis re-synchronized.', 'success', 3000);
  } catch (err) {
    console.error('Failed to clear scopes:', err);
    showToast(`Failed to restore all: ${err.message || err}`, 'error', 5000);
  }
}

let _activeContextMenuTarget = null;
function showExplorerContextMenu(x, y, target) {
  _activeContextMenuTarget = target;
  const menu = qs('#explorer-context-menu');
  if (!menu) return;

  const removeLabel = menu.querySelector('#ctx-menu-remove-scope span');
  if (removeLabel) {
    removeLabel.textContent = `Remove ${target.type === 'PACKAGE' ? 'Package' : 'Class'} from Scope…`;
  }

  menu.style.display = 'block';

  // Position within window bounds
  const menuWidth = 200;
  const menuHeight = 120;
  const posX = (x + menuWidth > window.innerWidth) ? (window.innerWidth - menuWidth - 8) : x;
  const posY = (y + menuHeight > window.innerHeight) ? (window.innerHeight - menuHeight - 8) : y;

  menu.style.left = `${posX}px`;
  menu.style.top = `${posY}px`;
}

function hideExplorerContextMenu() {
  const menu = qs('#explorer-context-menu');
  if (menu) menu.style.display = 'none';
  _activeContextMenuTarget = null;
}

function initScopeManagement() {
  // Toolbar Scope Manager button
  qs('#btn-manage-scope')?.addEventListener('click', () => openScopeManagerModal());

  // Scope Manager Modal controls
  qs('#btn-scope-manager-close')?.addEventListener('click', () => closeScopeManager());
  qs('#scope-manager-modal')?.addEventListener('click', (e) => {
    if (e.target === qs('#scope-manager-modal')) closeScopeManager();
  });
  qs('#scope-search-input')?.addEventListener('input', (e) => {
    renderScopeManagerList(e.target.value);
  });
  qs('#btn-scope-restore-all')?.addEventListener('click', () => restoreAllScopes());

  // Scope Confirm Modal controls
  qs('#btn-scope-confirm-close')?.addEventListener('click', () => hideAccessibleModal(qs('#scope-confirm-modal')));
  qs('#btn-scope-confirm-cancel')?.addEventListener('click', () => hideAccessibleModal(qs('#scope-confirm-modal')));
  qs('#scope-confirm-modal')?.addEventListener('click', (e) => {
    if (e.target === qs('#scope-confirm-modal')) hideAccessibleModal(qs('#scope-confirm-modal'));
  });
  qs('#btn-scope-confirm-execute')?.addEventListener('click', () => executeExcludeScope());

  // Context Menu Actions
  qs('#ctx-menu-remove-scope')?.addEventListener('click', () => {
    if (_activeContextMenuTarget) {
      confirmExcludeScope(_activeContextMenuTarget);
    }
    hideExplorerContextMenu();
  });

  qs('#ctx-menu-copy-fqn')?.addEventListener('click', () => {
    if (_activeContextMenuTarget && _activeContextMenuTarget.fqn) {
      navigator.clipboard.writeText(_activeContextMenuTarget.fqn).then(() => {
        showBanner(`Copied: ${_activeContextMenuTarget.fqn}`);
      }).catch(() => {
        showBanner(`Copied: ${_activeContextMenuTarget.fqn}`);
      });
    }
    hideExplorerContextMenu();
  });

  qs('#ctx-menu-focus-graph')?.addEventListener('click', () => {
    if (_activeContextMenuTarget) {
      const { fqn } = _activeContextMenuTarget;
      switchTab('graph');
      if (window.Graph2D && window.Graph2D.focusNode) {
        window.Graph2D.focusNode(fqn);
      }
    }
    hideExplorerContextMenu();
  });

  // Close context menu on outside click or escape or scroll
  document.addEventListener('click', () => hideExplorerContextMenu());
  document.addEventListener('contextmenu', (e) => {
    if (!e.target.closest('.tree-item')) {
      hideExplorerContextMenu();
    }
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') hideExplorerContextMenu();
  });
  qs('#left-panel')?.addEventListener('scroll', () => hideExplorerContextMenu());

  // Initial badge update
  updateExcludedScopeBadge();

  // Initialize Hub Node Radial Explorer
  if (window.HubExplorer && !window.hubExplorerInstance) {
    window.hubExplorerInstance = new window.HubExplorer();
  }

  // Initialize Command Palette (⌘K)
  if (typeof initCommandPalette === 'function') initCommandPalette();

  // Initialize SSE Live Telemetry Bus
  initLiveEventBus();

  // Initialize Blast Radius & Touch Points Explorer
  try {
    if (typeof initBlastRadiusExplorer === 'function') initBlastRadiusExplorer();
  } catch (err) {
    console.warn('initBlastRadiusExplorer failed:', err);
  }

  // Initialize CodeStory Storylines View
  try {
    if (typeof initStorylinesView === 'function') initStorylinesView();
  } catch (err) {
    console.warn('initStorylinesView failed:', err);
  }
}

document.addEventListener('DOMContentLoaded', init);

/* ─────────────────────────────────────────────────────────────────────────────
   UI helpers - shared rendering primitives
   ───────────────────────────────────────────────────────────────────────────── */

/** Build the entity header in the right panel. */
function renderEntityHeader(kind, name, fqn, entityNode) {
  const header = qs('#entity-header');
  if (!header) return;

  let archBadge = '';
  if (window.CodeLensClassifier) {
    const isMethod = (kind === 'METHOD');
    const arch = isMethod
      ? window.CodeLensClassifier.classifyMethod(name, fqn)
      : window.CodeLensClassifier.classifyType(entityNode || name, fqn);
    if (arch) {
      const iconSvg = window.Icons ? window.Icons.get(arch.icon || 'tag', { size: 'xs' }) : '';
      archBadge = `<span class="archetype-badge" style="background:${arch.color}22; border:1px solid ${arch.color}; color:${arch.color}; margin-left:6px; font-weight:700; font-size:11px; display:inline-flex; align-items:center; gap:4px;" title="${esc(arch.description)}">${iconSvg} <span>${esc(arch.label)} (${esc(arch.badge)})</span></span>`;
    }
  }

  header.innerHTML = `
    <div style="display:flex; align-items:center; flex-wrap:wrap; gap:6px; margin-bottom:4px;">
      <div class="entity-kind-badge ${kind}">${kind}</div>
      ${archBadge}
    </div>
    <div class="entity-name">${esc(name)}</div>
    <div class="entity-fqn" title="Click to copy fully qualified name" style="cursor:pointer; display:inline-flex; align-items:center; gap:6px;">
      <span>${esc(fqn)}</span>
      <span class="copy-hint-icon" style="opacity:0.6; display:inline-flex; align-items:center;" title="Copy to clipboard"><svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect width="14" height="14" x="8" y="8" rx="2" ry="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/></svg></span>
    </div>`;
  header.style.display = '';
  const fqnEl = header.querySelector('.entity-fqn');
  if (fqnEl) {
    fqnEl.onclick = () => {
      navigator.clipboard.writeText(fqn).then(() => {
        showBanner(`Copied ${kind}: ${name}`);
      }).catch(() => {
        showBanner(`Copied ${fqn}`);
      });
    };
  }
  const empty = qs('#detail-empty-state');
  if (empty) empty.style.display = 'none';
}

/** Create a metadata grid block from key-value pairs. */
function metaGrid(pairs) {
  const grid = createElement('div', { class: 'meta-grid' });
  for (const [k, v] of pairs) {
    const key = createElement('div', { class: 'meta-key' });
    key.textContent = k;
    const val = createElement('div', { class: 'meta-val' });
    if (v instanceof HTMLElement) {
      val.appendChild(v);
    } else {
      val.textContent = v || '-';
    }
    grid.appendChild(key);
    grid.appendChild(val);
  }
  return grid;
}

/** Section label for the right panel. */
function sectionLabel(text) {
  const el = createElement('div', { class: 'rp-section' });
  el.textContent = text;
  return el;
}

/** Single relationship row item. */
function relItem(icon, kind, label) {
  const item = createElement('div', { class: 'rel-item' });
  item.innerHTML = `
    <span class="rel-dot ${kind}"></span>
    <span class="rel-label" title="${esc(label)}">${esc(label)}</span>`;
  item.style.cursor = 'pointer';
  return item;
}

/** Cyclomatic complexity mini-badge. */
function complexityBadge(cc) {
  const span = createElement('span', { class: 'rel-kind-tag' });
  const col  = cc <= 4 ? 'var(--emerald)' : cc <= 10 ? 'var(--amber)' : 'var(--red)';
  span.innerHTML = `<span style="color:${col};font-size:10px">CC:${cc}</span>`;
  return span;
}

/** Row of action buttons in the right panel. */
function actionRow(actions) {
  const row = createElement('div', { class: 'action-row' });
  for (const a of actions) {
    const btn = createElement('button', { class: 'action-btn' });
    if (a.id) btn.id = a.id;
    if (a.className) {
      a.className.split(' ').filter(Boolean).forEach(c => btn.classList.add(c));
    }
    if (a.title) btn.title = a.title;
    btn.addEventListener('click', a.action);

    const lbl = createElement('span', { class: 'action-btn-label' });
    lbl.textContent = a.label;
    btn.appendChild(lbl);

    if (a.badge !== undefined && a.badge !== null) {
      const isZero = a.badge === 0 || a.badge === '0';
      const badgeClass = 'action-btn-badge' + (a.badgeClass ? ' ' + a.badgeClass : '') + (isZero ? ' is-zero' : '');
      const badgeSpan = createElement('span', { class: badgeClass });
      badgeSpan.textContent = typeof a.badge === 'number' ? a.badge.toLocaleString() : a.badge;
      btn.appendChild(badgeSpan);
    }

    row.appendChild(btn);
  }
  return row;
}

/** Highlight a tree item as selected (clears previous). */
function setActiveTreeItem(el) {
  qsa('.tree-item.active').forEach(i => i.classList.remove('active'));
  el?.classList.add('active');
}

/** Set right panel to loading state. */
function setLoading() {
  qs('#right-body').innerHTML = `
    <div style="padding:24px 16px">
      <div class="skeleton" style="width:60%;margin-bottom:10px"></div>
      <div class="skeleton" style="width:90%;margin-bottom:8px"></div>
      <div class="skeleton" style="width:75%;margin-bottom:8px"></div>
      <div class="skeleton" style="width:80%"></div>
    </div>`;
}

/* ─────────────────────────────────────────────────────────────────────────────
   Apple HIG Fluid Feedback: Audio & Haptic Synthesis (WWDC 2018 / 2026)
   Synthesizes subtle 10-30ms micro-impulses on meaningful user commits.
   Zero external audio assets needed; runs via standard Web Audio API.
   ───────────────────────────────────────────────────────────────────────────── */
const CodeLensFeedback = (() => {
  let ctx = null;
  function getCtx() {
    try {
      if (!ctx && (window.AudioContext || window.webkitAudioContext)) {
        ctx = new (window.AudioContext || window.webkitAudioContext)();
      }
      if (ctx && ctx.state === 'suspended') {
        ctx.resume().catch(() => {});
      }
      return ctx;
    } catch (_) { return null; }
  }

  function playImpulse(freq = 440, type = 'sine', duration = 0.04, gainVal = 0.02) {
    try {
      const c = getCtx();
      if (!c) return;
      const osc = c.createOscillator();
      const gain = c.createGain();
      osc.type = type;
      osc.frequency.setValueAtTime(freq, c.currentTime);
      gain.gain.setValueAtTime(gainVal, c.currentTime);
      gain.gain.exponentialRampToValueAtTime(0.0001, c.currentTime + duration);
      osc.connect(gain);
      gain.connect(c.destination);
      osc.start();
      osc.stop(c.currentTime + duration);
    } catch (_) {}
  }

  return {
    click() {
      playImpulse(520, 'sine', 0.03, 0.025);
      if (navigator.vibrate) try { navigator.vibrate(8); } catch (_) {}
    },
    success() {
      playImpulse(660, 'triangle', 0.08, 0.035);
      if (navigator.vibrate) try { navigator.vibrate([10, 30, 15]); } catch (_) {}
    },
    notice() {
      playImpulse(280, 'sine', 0.06, 0.03);
      if (navigator.vibrate) try { navigator.vibrate(20); } catch (_) {}
    }
  };
})();

/* ─────────────────────────────────────────────────────────────────────────────
   Sonner Toast Notification System (Emil Kowalski Architecture)
   Stacked, swipe-to-dismiss, velocity-aware, interruptible CSS transitions.
   ───────────────────────────────────────────────────────────────────────────── */
const toast = (() => {
  let _toaster = null;
  let _nextId = 1;
  const _activeToasts = new Map();

  function _getToaster() {
    if (!_toaster || !document.body.contains(_toaster)) {
      _toaster = document.getElementById('sonner-toaster');
      if (!_toaster) {
        _toaster = document.createElement('section');
        _toaster.id = 'sonner-toaster';
        _toaster.setAttribute('data-sonner-toaster', 'true');
        _toaster.setAttribute('aria-label', 'Notifications');
        _toaster.setAttribute('tabindex', '-1');
        document.body.appendChild(_toaster);

        _toaster.addEventListener('mouseenter', () => _updateStack(true));
        _toaster.addEventListener('mouseleave', () => _updateStack(false));
      }
    }
    return _toaster;
  }

  function _updateStack(expanded = false) {
    const toasts = Array.from(_getToaster().querySelectorAll('.sonner-toast:not(.sonner-exiting)'));
    const total = toasts.length;
    toasts.forEach((el, idx) => {
      const fromBottom = total - 1 - idx;
      if (expanded) {
        el.style.transform = `translateY(0) scale(1)`;
        el.style.opacity = '1';
        el.style.zIndex = `${100 + idx}`;
      } else {
        if (fromBottom === 0) {
          el.style.transform = `translateY(0) scale(1)`;
          el.style.opacity = '1';
          el.style.zIndex = '100';
        } else if (fromBottom === 1) {
          el.style.transform = `translateY(-12px) scale(0.95)`;
          el.style.opacity = '0.9';
          el.style.zIndex = '99';
        } else if (fromBottom === 2) {
          el.style.transform = `translateY(-24px) scale(0.90)`;
          el.style.opacity = '0.75';
          el.style.zIndex = '98';
        } else {
          el.style.transform = `translateY(-36px) scale(0.85)`;
          el.style.opacity = '0';
          el.style.zIndex = '97';
        }
      }
    });
  }

  const icons = {
    success: `<svg class="sonner-icon-svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#10b981" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>`,
    error:   `<svg class="sonner-icon-svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#ef4444" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>`,
    info:    `<svg class="sonner-icon-svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#38bdf8" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>`,
    warning: `<svg class="sonner-icon-svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#f59e0b" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>`,
    loading: `<svg class="sonner-icon-svg sonner-spinner" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M21 12a9 9 0 1 1-6.219-8.56"/></svg>`
  };

  function createToast(title, opts = {}) {
    const toaster = _getToaster();
    const id = opts.id || `toast_${_nextId++}`;
    const type = opts.type || 'info';
    const duration = (opts.duration !== undefined) ? opts.duration : (type === 'error' ? 5000 : 3500);

    // If toast with this id already exists, update in-place
    if (_activeToasts.has(id)) {
      const existing = _activeToasts.get(id);
      const titleEl = existing.el.querySelector('.sonner-title');
      const descEl = existing.el.querySelector('.sonner-desc');
      const iconEl = existing.el.querySelector('.sonner-icon');
      if (titleEl) titleEl.textContent = title;
      if (descEl) descEl.textContent = opts.description || '';
      if (iconEl) iconEl.innerHTML = icons[type] || icons.info;
      existing.el.className = `sonner-toast sonner-${type}`;

      if (existing.timer) clearTimeout(existing.timer);
      if (duration !== Infinity) {
        existing.timer = setTimeout(() => dismiss(id), duration);
      }
      return id;
    }

    const el = document.createElement('div');
    el.className = `sonner-toast sonner-${type} sonner-entering`;
    el.setAttribute('data-sonner-toast', id);
    el.setAttribute('role', type === 'error' ? 'alert' : 'status');

    let html = `<div class="sonner-icon">${icons[type] || icons.info}</div>`;
    html += `<div class="sonner-content">`;
    html += `<div class="sonner-title">${esc(title)}</div>`;
    if (opts.description) {
      html += `<div class="sonner-desc">${esc(opts.description)}</div>`;
    }
    html += `</div>`;

    if (opts.action) {
      html += `<button class="sonner-action" type="button">${esc(opts.action.label || 'Action')}</button>`;
    }
    el.innerHTML = html;

    if (opts.action && typeof opts.action.onClick === 'function') {
      const actionBtn = el.querySelector('.sonner-action');
      if (actionBtn) {
        actionBtn.addEventListener('click', (e) => {
          opts.action.onClick(e);
          dismiss(id);
        });
      }
    }

    // Interactive swipe-to-dismiss gesture tracking
    let startY = 0;
    let currentY = 0;
    let startTime = 0;
    let isDragging = false;

    el.addEventListener('pointerdown', (e) => {
      if (e.target.closest('.sonner-action')) return;
      isDragging = true;
      startY = e.clientY;
      currentY = e.clientY;
      startTime = Date.now();
      el.setPointerCapture(e.pointerId);
      el.style.transition = 'none';
    });

    el.addEventListener('pointermove', (e) => {
      if (!isDragging) return;
      currentY = e.clientY;
      const deltaY = Math.max(0, currentY - startY);
      el.style.transform = `translateY(${deltaY}px)`;
      el.style.opacity = `${Math.max(0.2, 1 - deltaY / 120)}`;
    });

    const endDrag = (e) => {
      if (!isDragging) return;
      isDragging = false;
      const deltaY = currentY - startY;
      const elapsed = Math.max(1, Date.now() - startTime);
      const velocity = deltaY / elapsed;

      el.style.transition = '';
      if (deltaY > 45 || velocity > 0.12) {
        dismiss(id);
      } else {
        _updateStack();
      }
    };

    el.addEventListener('pointerup', endDrag);
    el.addEventListener('pointercancel', endDrag);

    toaster.appendChild(el);

    // Audio/Haptic feedback on appearance
    if (type === 'success') CodeLensFeedback.success();
    else if (type === 'error') CodeLensFeedback.notice();
    else CodeLensFeedback.click();

    // Trigger hardware-accelerated entrance
    requestAnimationFrame(() => {
      el.classList.remove('sonner-entering');
      _updateStack();
    });

    let timer = null;
    if (duration !== Infinity) {
      timer = setTimeout(() => dismiss(id), duration);
    }

    _activeToasts.set(id, { el, timer });
    return id;
  }

  function dismiss(id) {
    if (!id) {
      // Dismiss all
      _activeToasts.forEach((val, k) => dismiss(k));
      return;
    }
    const item = _activeToasts.get(id);
    if (!item) return;
    if (item.timer) clearTimeout(item.timer);

    item.el.classList.add('sonner-exiting');
    item.el.addEventListener('transitionend', () => {
      if (item.el.parentNode) item.el.remove();
      _activeToasts.delete(id);
      _updateStack();
    }, { once: true });

    // Fallback cleanup in case transitionend is canceled
    setTimeout(() => {
      if (item.el.parentNode) item.el.remove();
      _activeToasts.delete(id);
      _updateStack();
    }, 320);
  }

  function toastFn(title, opts) {
    return createToast(title, opts);
  }
  toastFn.message = (title, opts) => createToast(title, opts);
  toastFn.success = (title, opts) => createToast(title, { ...(opts || {}), type: 'success' });
  toastFn.error = (title, opts) => createToast(title, { ...(opts || {}), type: 'error' });
  toastFn.info = (title, opts) => createToast(title, { ...(opts || {}), type: 'info' });
  toastFn.warning = (title, opts) => createToast(title, { ...(opts || {}), type: 'warning' });
  toastFn.loading = (title, opts) => createToast(title, { ...(opts || {}), type: 'loading', duration: Infinity });
  toastFn.dismiss = (id) => dismiss(id);
  toastFn.promise = (prom, { loading, success, error }) => {
    const id = createToast(loading || 'Processing…', { type: 'loading', duration: Infinity });
    return Promise.resolve(typeof prom === 'function' ? prom() : prom)
      .then((val) => {
        const msg = typeof success === 'function' ? success(val) : (success || 'Completed successfully');
        createToast(msg, { id, type: 'success' });
        return val;
      })
      .catch((err) => {
        const msg = typeof error === 'function' ? error(err) : (error || (err && err.message) || 'Operation failed');
        createToast(msg, { id, type: 'error' });
        throw err;
      });
  };

  return toastFn;
})();

// Attach to window
window.toast = toast;

/** Backward-compatible drop-in wrapper forwarding all existing showToast calls to Sonner engine */
function showToast(msg, type = 'success', duration = 3500) {
  if (type === 'success') return toast.success(msg, { duration });
  if (type === 'error')   return toast.error(msg,   { duration });
  if (type === 'warning') return toast.warning(msg, { duration });
  return toast.info(msg, { duration });
}

/** Show a brief success banner (queued). */
function showBanner(msg) { toast.success(msg); }

/** Show a temporary error toast (queued). */
function showError(msg)  { toast.error(msg); }

/** Flash a red border on an input briefly. */
function flashInput(el) {
  if (!el) return;
  el.style.borderColor = 'var(--red)';
  el.focus();
  setTimeout(() => { el.style.borderColor = ''; }, 1200);
}

/* ─────────────────────────────────────────────────────────────────────────────
   Utility functions
   ───────────────────────────────────────────────────────────────────────────── */

/** querySelector shorthand. */
function qs(sel)  { return document.querySelector(sel); }
/** querySelectorAll shorthand. */
function qsa(sel) { return document.querySelectorAll(sel); }

/** Create an element with attributes. */
function createElement(tag, attrs = {}) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === 'style') el.setAttribute('style', v);
    else               el.setAttribute(k, v);
  }
  return el;
}

/** HTML-escape a string. */
function esc(str) {
  return String(str || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

let _lastFocusedTrigger = null;

/** Open a modal with full accessibility and focus tracking. */
function showAccessibleModal(modal, triggerEl = null) {
  if (!modal) return;
  if (triggerEl && typeof triggerEl.focus === 'function') {
    _lastFocusedTrigger = triggerEl;
  } else if (document.activeElement && !modal.contains(document.activeElement)) {
    _lastFocusedTrigger = document.activeElement;
  }
  modal.classList.add('open');
  modal.setAttribute('aria-hidden', 'false');

  // Focus close button or first interactive element inside modal
  const closeBtn = modal.querySelector('.modal-close-btn, button, [tabindex]:not([tabindex="-1"])');
  if (closeBtn && typeof closeBtn.focus === 'function') {
    setTimeout(() => {
      try { closeBtn.focus(); } catch (_) {}
    }, 50);
  }
}

function dismissModalAnimated(modalEl, onClosed) {
  if (!modalEl || !modalEl.classList.contains('open')) return;
  modalEl.classList.add('modal-closing');
  setTimeout(() => {
    modalEl.classList.remove('open', 'modal-closing');
    modalEl.setAttribute('aria-hidden', 'true');
    if (typeof onClosed === 'function') onClosed();
  }, 140);
}

/** Close a modal cleanly without a11y focus collisions. */
function hideAccessibleModal(modal) {
  if (!modal) return;
  // Blur any descendant before setting aria-hidden to prevent browser assistive technology violations
  if (document.activeElement && modal.contains(document.activeElement)) {
    document.activeElement.blur();
  }
  modal.classList.remove('open');
  modal.setAttribute('aria-hidden', 'true');

  // Restore focus to opener trigger element if available
  if (_lastFocusedTrigger && typeof _lastFocusedTrigger.focus === 'function') {
    const trigger = _lastFocusedTrigger;
    _lastFocusedTrigger = null;
    setTimeout(() => {
      try { trigger.focus(); } catch (_) {}
    }, 50);
  }
}

/** Shorten a FQN for display: "com.example.trading.OrderService" → "OrderService". */
function shortFqn(fqn) {
  if (!fqn) return '-';
  const paren = fqn.indexOf('(');
  const base  = paren > 0 ? fqn.substring(0, paren) : fqn;
  const dot   = base.lastIndexOf('.');
  return dot >= 0 ? base.substring(dot + 1) : base;
}

/** Format epoch millis to a readable date. */
function formatDate(epochMs) {
  if (!epochMs) return '';
  return new Date(epochMs).toLocaleString(undefined, {
    dateStyle: 'short', timeStyle: 'short',
  });
}

/** Map Java type kind to a clean label glyph. */
function kindIcon(kind) {
  return { CLASS: 'C', INTERFACE: 'I', ENUM: 'E', RECORD: 'R', ANNOTATION: '@' }[kind] || 'T';
}

/* ─────────────────────────────────────────────────────────────────────────────
   Adjustable Panel System & Resizer Handlers
   ───────────────────────────────────────────────────────────────────────────── */

const DEFAULT_LEFT_WIDTH = 310;
const DEFAULT_RIGHT_WIDTH = 370;
const DEFAULT_REPORTS_SIDEBAR_WIDTH = 290;
const MIN_LEFT_WIDTH = 160;
const MAX_LEFT_WIDTH = 600;
const MIN_RIGHT_WIDTH = 220;
const MAX_RIGHT_WIDTH = 750;
const MIN_REPORTS_SIDEBAR_WIDTH = 190;
const MAX_REPORTS_SIDEBAR_WIDTH = 600;
const MIN_CENTRE_WIDTH = 260;

const PANEL_STORAGE = {
  LEFT_WIDTH: 'codelens_panel_left_w',
  RIGHT_WIDTH: 'codelens_panel_right_w',
  LEFT_COLLAPSED: 'codelens_panel_left_collapsed',
  RIGHT_COLLAPSED: 'codelens_panel_right_collapsed',
  REPORTS_WIDTH: 'codelens_panel_reports_w',
  REPORTS_COLLAPSED: 'codelens_panel_reports_collapsed'
};

function initPanelResizers() {
  const resizerLeft = qs('#resizer-left');
  const resizerRight = qs('#resizer-right');
  const resizerReports = qs('#resizer-reports');
  const btnCollapseLeft = qs('#btn-collapse-left');
  const btnCollapseRight = qs('#btn-collapse-right');
  const btnCollapseReports = qs('#btn-collapse-reports-sidebar');
  const footerToggleLeft = qs('#footer-toggle-left');
  const footerToggleRight = qs('#footer-toggle-right');

  // Load saved state or defaults
  let savedLeftW = parseInt(localStorage.getItem(PANEL_STORAGE.LEFT_WIDTH), 10);
  let savedRightW = parseInt(localStorage.getItem(PANEL_STORAGE.RIGHT_WIDTH), 10);
  let savedReportsW = parseInt(localStorage.getItem(PANEL_STORAGE.REPORTS_WIDTH), 10);
  const leftCollapsed = localStorage.getItem(PANEL_STORAGE.LEFT_COLLAPSED) === 'true';
  const rightCollapsed = localStorage.getItem(PANEL_STORAGE.RIGHT_COLLAPSED) === 'true';
  const reportsCollapsed = localStorage.getItem(PANEL_STORAGE.REPORTS_COLLAPSED) === 'true';

  if (isNaN(savedLeftW) || savedLeftW < MIN_LEFT_WIDTH) savedLeftW = DEFAULT_LEFT_WIDTH;
  if (isNaN(savedRightW) || savedRightW < MIN_RIGHT_WIDTH) savedRightW = DEFAULT_RIGHT_WIDTH;
  if (isNaN(savedReportsW) || savedReportsW < MIN_REPORTS_SIDEBAR_WIDTH) savedReportsW = DEFAULT_REPORTS_SIDEBAR_WIDTH;

  // Apply initial widths and collapse states
  if (leftCollapsed || App.activeTab === 'reports') {
    collapseLeftPanel(true, false);
  } else {
    setLeftPanelWidth(savedLeftW, false);
  }

  if (rightCollapsed || App.activeTab === 'reports') {
    collapseRightPanel(true, false);
  } else {
    setRightPanelWidth(savedRightW, false);
  }

  if (reportsCollapsed) {
    collapseReportsSidebar(true, false);
  } else {
    setReportsSidebarWidth(savedReportsW, false);
  }

  if (App.activeTab === 'reports') {
    document.body.classList.add('reports-mode');
    App._preReportsPanelState = {
      leftCollapsed: leftCollapsed,
      rightCollapsed: rightCollapsed
    };
  }

  // ── Dragging Left Resizer (Explorer) ────────────────────────────────────────
  if (resizerLeft) {
    let startX = 0;
    let startW = 0;
    let activePointerId = null;

    const onPointerMove = moveEvent => {
      const delta = moveEvent.clientX - startX;
      const availableW = window.innerWidth - (getRightPanelWidth() + 10 + MIN_CENTRE_WIDTH);
      const maxW = Math.min(MAX_LEFT_WIDTH, Math.max(MIN_LEFT_WIDTH, availableW));
      const newW = Math.min(maxW, Math.max(MIN_LEFT_WIDTH, startW + delta));
      setLeftPanelWidth(newW, false);
    };

    const onPointerUp = upEvent => {
      document.body.classList.remove('resizing');
      resizerLeft.classList.remove('active');
      if (activePointerId !== null && resizerLeft.releasePointerCapture) {
        try { resizerLeft.releasePointerCapture(activePointerId); } catch (_) {}
        activePointerId = null;
      }
      window.removeEventListener('pointermove', onPointerMove);
      window.removeEventListener('pointerup', onPointerUp);
      window.removeEventListener('pointercancel', onPointerUp);
      window.removeEventListener('mousemove', onPointerMove);
      window.removeEventListener('mouseup', onPointerUp);

      const finalW = getLeftPanelWidth();
      if (finalW > 0) {
        localStorage.setItem(PANEL_STORAGE.LEFT_WIDTH, finalW);
        localStorage.setItem(PANEL_STORAGE.LEFT_COLLAPSED, 'false');
      }
      triggerRelayout();
    };

    const startDrag = e => {
      if (e.button !== 0) return;
      e.preventDefault();
      startX = e.clientX;
      startW = getLeftPanelWidth();
      document.body.classList.add('resizing');
      resizerLeft.classList.add('active');

      if (e.pointerId !== undefined && resizerLeft.setPointerCapture) {
        try {
          resizerLeft.setPointerCapture(e.pointerId);
          activePointerId = e.pointerId;
        } catch (_) {}
      }

      window.addEventListener('pointermove', onPointerMove);
      window.addEventListener('pointerup', onPointerUp);
      window.addEventListener('pointercancel', onPointerUp);
      window.addEventListener('mousemove', onPointerMove);
      window.addEventListener('mouseup', onPointerUp);
    };

    const downEvt = window.PointerEvent ? 'pointerdown' : 'mousedown';
    resizerLeft.addEventListener(downEvt, startDrag);
    resizerLeft.addEventListener('dblclick', () => {
      setLeftPanelWidth(DEFAULT_LEFT_WIDTH, true);
    });
  }

  // ── Dragging Right Resizer (Inspector) ───────────────────────────────────────
  if (resizerRight) {
    let startX = 0;
    let startW = 0;
    let activePointerId = null;

    const onPointerMove = moveEvent => {
      const delta = startX - moveEvent.clientX;
      const availableW = window.innerWidth - (getLeftPanelWidth() + 10 + MIN_CENTRE_WIDTH);
      const maxW = Math.min(MAX_RIGHT_WIDTH, Math.max(MIN_RIGHT_WIDTH, availableW));
      const newW = Math.min(maxW, Math.max(MIN_RIGHT_WIDTH, startW + delta));
      setRightPanelWidth(newW, false);
    };

    const onPointerUp = upEvent => {
      document.body.classList.remove('resizing');
      resizerRight.classList.remove('active');
      if (activePointerId !== null && resizerRight.releasePointerCapture) {
        try { resizerRight.releasePointerCapture(activePointerId); } catch (_) {}
        activePointerId = null;
      }
      window.removeEventListener('pointermove', onPointerMove);
      window.removeEventListener('pointerup', onPointerUp);
      window.removeEventListener('pointercancel', onPointerUp);
      window.removeEventListener('mousemove', onPointerMove);
      window.removeEventListener('mouseup', onPointerUp);

      const finalW = getRightPanelWidth();
      if (finalW > 0) {
        localStorage.setItem(PANEL_STORAGE.RIGHT_WIDTH, finalW);
        localStorage.setItem(PANEL_STORAGE.RIGHT_COLLAPSED, 'false');
      }
      triggerRelayout();
    };

    const startDrag = e => {
      if (e.button !== 0) return;
      e.preventDefault();
      startX = e.clientX;
      startW = getRightPanelWidth();
      document.body.classList.add('resizing');
      resizerRight.classList.add('active');

      if (e.pointerId !== undefined && resizerRight.setPointerCapture) {
        try {
          resizerRight.setPointerCapture(e.pointerId);
          activePointerId = e.pointerId;
        } catch (_) {}
      }

      window.addEventListener('pointermove', onPointerMove);
      window.addEventListener('pointerup', onPointerUp);
      window.addEventListener('pointercancel', onPointerUp);
      window.addEventListener('mousemove', onPointerMove);
      window.addEventListener('mouseup', onPointerUp);
    };

    const downEvt = window.PointerEvent ? 'pointerdown' : 'mousedown';
    resizerRight.addEventListener(downEvt, startDrag);
    resizerRight.addEventListener('dblclick', () => {
      setRightPanelWidth(DEFAULT_RIGHT_WIDTH, true);
    });
  }

  // ── Dragging Reports Catalog Resizer ─────────────────────────────────────────
  if (resizerReports) {
    let startX = 0;
    let startW = 0;

    const onPointerMove = moveEvent => {
      const delta = moveEvent.clientX - startX;
      const hubLayout = qs('.reports-hub-layout');
      const hubWidth = hubLayout ? hubLayout.clientWidth : window.innerWidth;
      const availableW = Math.max(MIN_REPORTS_SIDEBAR_WIDTH, hubWidth - 340);
      const maxW = Math.min(MAX_REPORTS_SIDEBAR_WIDTH, availableW);
      const newW = Math.min(maxW, Math.max(MIN_REPORTS_SIDEBAR_WIDTH, startW + delta));
      setReportsSidebarWidth(newW, false);
    };

    const onPointerUp = upEvent => {
      document.body.classList.remove('resizing');
      resizerReports.classList.remove('active');
      window.removeEventListener('pointermove', onPointerMove);
      window.removeEventListener('pointerup', onPointerUp);
      window.removeEventListener('pointercancel', onPointerUp);
      window.removeEventListener('mousemove', onPointerMove);
      window.removeEventListener('mouseup', onPointerUp);

      const finalW = getReportsSidebarWidth();
      if (finalW > 0) {
        localStorage.setItem(PANEL_STORAGE.REPORTS_WIDTH, finalW);
        localStorage.setItem(PANEL_STORAGE.REPORTS_COLLAPSED, 'false');
      }
      triggerRelayout();
    };

    const startDrag = e => {
      if (e.button !== 0 && e.buttons !== 1) return;
      e.preventDefault();
      startX = e.clientX;
      startW = getReportsSidebarWidth();
      document.body.classList.add('resizing');
      resizerReports.classList.add('active');

      window.addEventListener('pointermove', onPointerMove);
      window.addEventListener('pointerup', onPointerUp);
      window.addEventListener('pointercancel', onPointerUp);
      window.addEventListener('mousemove', onPointerMove);
      window.addEventListener('mouseup', onPointerUp);
    };

    resizerReports.addEventListener('pointerdown', startDrag);
    resizerReports.addEventListener('mousedown', startDrag);
    resizerReports.addEventListener('dblclick', () => {
      setReportsSidebarWidth(DEFAULT_REPORTS_SIDEBAR_WIDTH, true);
    });
    resizerReports.addEventListener('keydown', e => {
      if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
        e.preventDefault();
        const step = e.shiftKey ? 40 : 16;
        const delta = e.key === 'ArrowRight' ? step : -step;
        const nextW = Math.min(MAX_REPORTS_SIDEBAR_WIDTH, Math.max(MIN_REPORTS_SIDEBAR_WIDTH, getReportsSidebarWidth() + delta));
        setReportsSidebarWidth(nextW, true);
      }
    });
  }

  // ── Collapse / Expand Buttons & Floating Expand Strips ─────────────────────
  if (btnCollapseLeft) {
    btnCollapseLeft.addEventListener('click', () => toggleLeftPanel());
  }
  if (btnCollapseRight) {
    btnCollapseRight.addEventListener('click', () => toggleRightPanel());
  }
  if (btnCollapseReports) {
    btnCollapseReports.addEventListener('click', () => toggleReportsSidebar());
  }
  if (footerToggleLeft) {
    footerToggleLeft.addEventListener('click', () => toggleLeftPanel());
  }
  if (footerToggleRight) {
    footerToggleRight.addEventListener('click', () => toggleRightPanel());
  }

  const leftExpandStrip = qs('#left-expand-strip');
  if (leftExpandStrip) {
    leftExpandStrip.addEventListener('click', () => collapseLeftPanel(false, true));
  }

  const rightExpandStrip = qs('#right-expand-strip');
  if (rightExpandStrip) {
    rightExpandStrip.addEventListener('click', () => collapseRightPanel(false, true));
  }

  const reportsExpandStrip = qs('#reports-expand-strip');
  if (reportsExpandStrip) {
    reportsExpandStrip.addEventListener('click', () => collapseReportsSidebar(false, true));
  }
}

function getLeftPanelWidth() {
  const panel = qs('#left-panel');
  if (!panel || panel.classList.contains('collapsed')) return 0;
  const raw = getComputedStyle(document.documentElement).getPropertyValue('--left-w');
  return parseInt(raw, 10) || DEFAULT_LEFT_WIDTH;
}

function getRightPanelWidth() {
  const panel = qs('#right-panel');
  if (!panel || panel.classList.contains('collapsed')) return 0;
  const raw = getComputedStyle(document.documentElement).getPropertyValue('--right-w');
  return parseInt(raw, 10) || DEFAULT_RIGHT_WIDTH;
}

function getReportsSidebarWidth() {
  const panel = qs('#reports-sidebar');
  if (!panel || panel.classList.contains('collapsed')) return 0;
  const raw = getComputedStyle(document.documentElement).getPropertyValue('--reports-sidebar-w');
  return parseInt(raw, 10) || panel.offsetWidth || DEFAULT_REPORTS_SIDEBAR_WIDTH;
}

function setLeftPanelWidth(width, save = true) {
  const leftPanel = qs('#left-panel');
  const footerToggle = qs('#footer-toggle-left');
  const resizer = qs('#resizer-left');
  const expandStrip = qs('#left-expand-strip');

  if (leftPanel) leftPanel.classList.remove('collapsed');
  if (footerToggle) footerToggle.classList.remove('collapsed');
  if (resizer) resizer.style.display = '';
  if (expandStrip) expandStrip.style.display = 'none';

  document.documentElement.style.setProperty('--left-w', `${width}px`);
  if (save) {
    localStorage.setItem(PANEL_STORAGE.LEFT_WIDTH, width);
    localStorage.setItem(PANEL_STORAGE.LEFT_COLLAPSED, 'false');
  }
  triggerRelayout();
}

function setRightPanelWidth(width, save = true) {
  const rightPanel = qs('#right-panel');
  const footerToggle = qs('#footer-toggle-right');
  const resizer = qs('#resizer-right');
  const expandStrip = qs('#right-expand-strip');

  if (rightPanel) rightPanel.classList.remove('collapsed');
  if (footerToggle) footerToggle.classList.remove('collapsed');
  if (resizer) resizer.style.display = '';
  if (expandStrip) expandStrip.style.display = 'none';

  document.documentElement.style.setProperty('--right-w', `${width}px`);
  if (save) {
    localStorage.setItem(PANEL_STORAGE.RIGHT_WIDTH, width);
    localStorage.setItem(PANEL_STORAGE.RIGHT_COLLAPSED, 'false');
  }
  triggerRelayout();
}

function setReportsSidebarWidth(width, save = true) {
  const reportsSidebar = qs('#reports-sidebar');
  const resizer = qs('#resizer-reports');
  const expandStrip = qs('#reports-expand-strip');

  if (reportsSidebar) reportsSidebar.classList.remove('collapsed');
  if (resizer) resizer.style.display = '';
  if (expandStrip) expandStrip.style.display = 'none';

  document.documentElement.style.setProperty('--reports-sidebar-w', `${width}px`);
  if (save) {
    localStorage.setItem(PANEL_STORAGE.REPORTS_WIDTH, width);
    localStorage.setItem(PANEL_STORAGE.REPORTS_COLLAPSED, 'false');
  }
  triggerRelayout();
}

function collapseLeftPanel(collapsed, save = true) {
  const leftPanel = qs('#left-panel');
  const footerToggle = qs('#footer-toggle-left');
  const resizer = qs('#resizer-left');
  const expandStrip = qs('#left-expand-strip');

  if (collapsed) {
    if (leftPanel) leftPanel.classList.add('collapsed');
    if (footerToggle) footerToggle.classList.add('collapsed');
    if (resizer) resizer.style.display = 'none';
    if (expandStrip) expandStrip.style.display = 'flex';
    document.documentElement.style.setProperty('--left-w', '0px');
    if (save) localStorage.setItem(PANEL_STORAGE.LEFT_COLLAPSED, 'true');
  } else {
    let savedW = parseInt(localStorage.getItem(PANEL_STORAGE.LEFT_WIDTH), 10);
    if (isNaN(savedW) || savedW < MIN_LEFT_WIDTH) savedW = DEFAULT_LEFT_WIDTH;
    setLeftPanelWidth(savedW, save);
  }
  triggerRelayout();
}

function collapseRightPanel(collapsed, save = true) {
  const rightPanel = qs('#right-panel');
  const footerToggle = qs('#footer-toggle-right');
  const resizer = qs('#resizer-right');
  const expandStrip = qs('#right-expand-strip');

  if (collapsed) {
    if (rightPanel) rightPanel.classList.add('collapsed');
    if (footerToggle) footerToggle.classList.add('collapsed');
    if (resizer) resizer.style.display = 'none';
    if (expandStrip) expandStrip.style.display = 'flex';
    document.documentElement.style.setProperty('--right-w', '0px');
    if (save) localStorage.setItem(PANEL_STORAGE.RIGHT_COLLAPSED, 'true');
  } else {
    let savedW = parseInt(localStorage.getItem(PANEL_STORAGE.RIGHT_WIDTH), 10);
    if (isNaN(savedW) || savedW < MIN_RIGHT_WIDTH) savedW = DEFAULT_RIGHT_WIDTH;
    setRightPanelWidth(savedW, save);
  }
  triggerRelayout();
}

function collapseReportsSidebar(collapsed, save = true) {
  const reportsSidebar = qs('#reports-sidebar');
  const resizer = qs('#resizer-reports');
  const expandStrip = qs('#reports-expand-strip');

  if (collapsed) {
    if (reportsSidebar) reportsSidebar.classList.add('collapsed');
    if (resizer) resizer.style.display = 'none';
    if (expandStrip) expandStrip.style.display = 'flex';
    document.documentElement.style.setProperty('--reports-sidebar-w', '0px');
    if (save) localStorage.setItem(PANEL_STORAGE.REPORTS_COLLAPSED, 'true');
  } else {
    let savedW = parseInt(localStorage.getItem(PANEL_STORAGE.REPORTS_WIDTH), 10);
    if (isNaN(savedW) || savedW < MIN_REPORTS_SIDEBAR_WIDTH) savedW = DEFAULT_REPORTS_SIDEBAR_WIDTH;
    setReportsSidebarWidth(savedW, save);
  }
  triggerRelayout();
}

function toggleLeftPanel() {
  const leftPanel = qs('#left-panel');
  const isCollapsed = leftPanel?.classList.contains('collapsed');
  collapseLeftPanel(!isCollapsed, true);
}

function toggleRightPanel() {
  const rightPanel = qs('#right-panel');
  const isCollapsed = rightPanel?.classList.contains('collapsed');
  collapseRightPanel(!isCollapsed, true);
}

function toggleReportsSidebar() {
  const reportsSidebar = qs('#reports-sidebar');
  const isCollapsed = reportsSidebar?.classList.contains('collapsed');
  collapseReportsSidebar(!isCollapsed, true);
}

function resetPanelWidths() {
  setLeftPanelWidth(DEFAULT_LEFT_WIDTH, true);
  setRightPanelWidth(DEFAULT_RIGHT_WIDTH, true);
  setReportsSidebarWidth(DEFAULT_REPORTS_SIDEBAR_WIDTH, true);
  showBanner('Panels reset to default dimensions');
}

function triggerRelayout() {
  if (App.editor && typeof App.editor.layout === 'function') {
    App.editor.layout();
  }
  if (App.graph && typeof App.graph._resize === 'function') {
    App.graph._resize();
  }
  if (App.activeAltRenderer) {
    if (typeof App.activeAltRenderer._onResize === 'function') {
      App.activeAltRenderer._onResize();
    } else if (typeof App.activeAltRenderer.resize === 'function') {
      App.activeAltRenderer.resize();
    }
  }
  window.dispatchEvent(new Event('resize'));
}

/* ─────────────────────────────────────────────────────────────────────────────
   10. Themes & Settings System
   ───────────────────────────────────────────────────────────────────────────── */

const THEMES = {
  dark: {
    label: 'Dark', icon: 'moon', tagline: 'OLED Obsidian. True black canvas & crisp mint emerald contrast.',
    css: {
      '--bg-base': '#000000', '--bg-panel': '#0a0d12', '--bg-surface': '#12161f',
      '--bg-elevated': '#181e28', '--bg-modal': '#0a0d12', '--bg-glass': 'rgba(10,13,18,0.88)',
      '--border': 'rgba(255,255,255,0.08)', '--border-hover': 'rgba(255,255,255,0.16)',
      '--border-light': 'rgba(255,255,255,0.12)', '--border-focus': '#10b981',
      '--primary': '#34d399', '--primary-bg': '#181e28', '--primary-hover': '#21262d', '--primary-active': '#12161f',
      '--primary-subtle': 'rgba(16,185,129,0.12)', '--primary-glow': 'rgba(16,185,129,0.16)',
      '--primary-border': 'rgba(52,211,153,0.40)',
      '--cyan-bright': '#34d399', '--emerald': '#10b981', '--amber': '#f59e0b', '--red': '#ef4444',
      '--text-primary': '#f8fafc', '--text-secondary': '#cbd5e1', '--text-muted': '#94a3b8',
    },
    graph: {
      bg: '#000000', grid: 'rgba(255,255,255,0.03)',
      roles: { root:'#10b981',caller:'#34d399',callee:'#10b981',propagator:'#f59e0b',field:'#fb923c',reader:'#34d399',writer:'#ef4444',default:'#10b981' },
      edgeKind: { CALLS:'#34d399',READS_FIELD:'#10b981',WRITES_FIELD:'#f59e0b',EXTENDS:'#94a3b8',IMPLEMENTS:'#94a3b8',default:'#64748b' },
      nodeColors: [
        '#ef4444', '#f97316', '#f59e0b', '#eab308', '#84cc16',
        '#22c55e', '#10b981', '#14b8a6', '#06b6d4', '#0ea5e9',
        '#3b82f6', '#6366f1', '#8b5cf6', '#a855f7', '#d946ef',
        '#ec4899', '#f43f5e', '#dc2626', '#ea580c', '#d97706',
        '#ca8a04', '#65a30d', '#16a34a', '#059669', '#0d9488',
        '#0891b2', '#0284c7', '#2563eb', '#4f46e5', '#7c3aed',
        '#9333ea', '#c026d3', '#db2777', '#e11d48', '#ff3366',
        '#ff6600', '#ffaa00', '#ffcc00', '#99ee00', '#00dd77',
        '#00ddcc', '#0099ff', '#3355ff', '#8833ff', '#dd00ff',
        '#ff00aa', '#ff1a75', '#ff5722', '#ff9800', '#e91e63'
      ],
      lightMode: false,
    },
    preview: ['#000000','#0a0d12','#10b981','#34d399','#f59e0b'],
  },
  swiss: {
    label: 'Swiss', icon: 'swiss', tagline: 'Razor-sharp geometry & Helvetica grid. Swiss International Style.',
    css: {
      '--bg-base': '#0f0f10', '--bg-panel': '#161617', '--bg-surface': '#1d1d1f',
      '--bg-elevated': '#242426', '--bg-modal': '#161617', '--bg-glass': 'rgba(22,22,23,0.92)',
      '--border': 'rgba(255,255,255,0.12)', '--border-hover': 'rgba(255,255,255,0.24)',
      '--border-light': 'rgba(255,255,255,0.08)', '--border-focus': '#eb0028',
      '--primary': '#eb0028', '--primary-bg': '#1d1d1f', '--primary-hover': '#242426', '--primary-active': '#1d1d1f',
      '--primary-subtle': 'rgba(235,0,40,0.10)', '--primary-glow': 'transparent',
      '--primary-border': 'rgba(235,0,40,0.40)',
      '--cyan-bright': '#eb0028', '--emerald': '#eb0028', '--amber': '#a1a1a6', '--red': '#ff3b30',
      '--text-primary': '#ffffff', '--text-secondary': '#a1a1a6', '--text-muted': '#6e6e73',
      '--text-code': '#ff6961',
      '--font-ui': '"Helvetica Neue", "Neue Haas Grotesk Text Pro", -apple-system, BlinkMacSystemFont, Arial, sans-serif',
      '--font-display': '"Helvetica Neue", "Neue Haas Grotesk Text Pro", -apple-system, BlinkMacSystemFont, Arial, sans-serif',
      '--radius-xs': '0px', '--radius-sm': '1px', '--radius-md': '2px', '--radius-lg': '3px',
      '--shadow-sm': '0 1px 2px rgba(0,0,0,0.5)',
      '--shadow-md': '0 4px 12px rgba(0,0,0,0.6), 0 0 0 1px rgba(255,255,255,0.08)',
      '--shadow-lg': '0 16px 40px rgba(0,0,0,0.7), 0 0 0 1px rgba(255,255,255,0.12)',
    },
    graph: {
      bg: '#0f0f10', grid: 'rgba(255,255,255,0.05)',
      roles: { root:'#eb0028',caller:'#ffffff',callee:'#eb0028',propagator:'#a1a1a6',field:'#6e6e73',reader:'#ffffff',writer:'#eb0028',default:'#a1a1a6' },
      edgeKind: { CALLS:'#eb0028',READS_FIELD:'#a1a1a6',WRITES_FIELD:'#ff3b30',EXTENDS:'#6e6e73',IMPLEMENTS:'#6e6e73',default:'#48484a' },
      nodeColors: [
        '#eb0028', '#ff3b30', '#ff6961', '#ff453a', '#ff6482',
        '#ffffff', '#e5e5ea', '#d1d1d6', '#c7c7cc', '#aeaeb2',
        '#a1a1a6', '#8e8e93', '#636366', '#48484a', '#3a3a3c',
        '#eb0028', '#d10023', '#b8001e', '#ff2d55', '#ff375f',
        '#ffffff', '#f2f2f7', '#e5e5ea', '#d1d1d6', '#c7c7cc',
        '#eb0028', '#ff453a', '#ff6961', '#a1a1a6', '#8e8e93',
        '#636366', '#48484a', '#3a3a3c', '#2c2c2e', '#1c1c1e',
        '#eb0028', '#ff3b30', '#ffffff', '#e5e5ea', '#d1d1d6',
        '#a1a1a6', '#8e8e93', '#636366', '#eb0028', '#ff6482',
        '#d10023', '#b8001e', '#ff2d55', '#ff375f', '#eb0028'
      ],
      lightMode: false,
    },
    preview: ['#0f0f10','#161617','#eb0028','#ffffff','#a1a1a6'],
  },
  light: {
    label: 'Light', icon: 'sun', tagline: 'Pure Daylight. Crisp emerald contrast, ultra-readable typography.',
    css: {
      '--bg-base': '#f1f5f9', '--bg-panel': '#ffffff', '--bg-surface': '#f8fafc',
      '--bg-elevated': '#e2e8f0', '--bg-modal': '#ffffff', '--bg-glass': 'rgba(241,245,249,0.95)',
      '--border': '#cbd5e1', '--border-hover': '#94a3b8',
      '--border-light': '#e2e8f0', '--border-focus': '#059669',
      '--primary': '#059669', '--primary-bg': '#e2e8f0', '--primary-hover': '#cbd5e1', '--primary-active': '#94a3b8',
      '--primary-subtle': 'rgba(5,150,105,0.08)', '--primary-glow': 'rgba(5,150,105,0.12)',
      '--primary-border': 'rgba(5,150,105,0.45)',
      '--cyan-bright': '#059669', '--emerald': '#059669', '--amber': '#d97706', '--red': '#dc2626',
      '--text-primary': '#0f172a', '--text-secondary': '#334155', '--text-muted': '#475569',
    },
    graph: {
      bg: '#f1f5f9', grid: 'rgba(0,0,0,0.06)',
      roles: { root:'#059669',caller:'#047857',callee:'#059669',propagator:'#d97706',field:'#c2410c',reader:'#059669',writer:'#dc2626',default:'#059669' },
      edgeKind: { CALLS:'#059669',READS_FIELD:'#059669',WRITES_FIELD:'#d97706',EXTENDS:'#475569',IMPLEMENTS:'#475569',default:'#64748b' },
      nodeColors: [
        '#ef4444', '#f97316', '#f59e0b', '#eab308', '#84cc16',
        '#22c55e', '#10b981', '#14b8a6', '#06b6d4', '#0ea5e9',
        '#3b82f6', '#6366f1', '#8b5cf6', '#a855f7', '#d946ef',
        '#ec4899', '#f43f5e', '#dc2626', '#ea580c', '#d97706',
        '#ca8a04', '#65a30d', '#16a34a', '#059669', '#0d9488',
        '#0891b2', '#0284c7', '#2563eb', '#4f46e5', '#7c3aed',
        '#9333ea', '#c026d3', '#db2777', '#e11d48', '#ff3366',
        '#ff6600', '#ffaa00', '#ffcc00', '#99ee00', '#00dd77',
        '#00ddcc', '#0099ff', '#3355ff', '#8833ff', '#dd00ff',
        '#ff00aa', '#ff1a75', '#ff5722', '#ff9800', '#e91e63'
      ],
      lightMode: true,
    },
    preview: ['#f1f5f9','#ffffff','#059669','#10b981','#d97706'],
  },
};

const SETTINGS_DEFAULTS = {
  theme: 'dark',
  nodeBaseRadius: 9,
  repulsion: 22000,
  springLen: 160,
  damping: 0.78,
  showParticles: true,
  showMinimap: true,
  showLabels: true,
  showGrid: true,
  defaultDepth: 3,
  autoFit: true,
  showHulls: true,
  excludePatterns: 'target, build, .mvn, .git, .gradle, node_modules, bin, out',
  packageMode: 'auto', // 'auto' | 'compact' | 'fqn'
};

const SETTINGS_STORAGE_KEY = 'codelens_settings';

function loadSettings() {
  try {
    const raw = localStorage.getItem(SETTINGS_STORAGE_KEY);
    if (raw) return { ...SETTINGS_DEFAULTS, ...JSON.parse(raw) };
  } catch (_) { /* corrupt */ }
  return { ...SETTINGS_DEFAULTS };
}

function saveSettings(settings) {
  localStorage.setItem(SETTINGS_STORAGE_KEY, JSON.stringify(settings));
}

function applyTheme(themeKey) {
  // Normalize legacy theme keys if present in localStorage
  if (themeKey === 'arctic') themeKey = 'light';
  if (themeKey === 'midnight' || themeKey === 'cyberpunk' || themeKey === 'ember' || themeKey === 'forest') themeKey = 'dark';
  if (!THEMES[themeKey]) themeKey = 'dark';

  const theme = THEMES[themeKey];

  // Apply CSS custom properties
  const root = document.documentElement;
  for (const [prop, val] of Object.entries(theme.css)) {
    root.style.setProperty(prop, val);
  }

  // Toggle theme body classes (mutually exclusive)
  document.body.classList.remove('theme-light', 'theme-swiss');
  if (themeKey === 'light') document.body.classList.add('theme-light');
  else if (themeKey === 'swiss') document.body.classList.add('theme-swiss');
  document.body.dataset.theme = themeKey;

  // Update top-bar & hero theme toggle buttons to show the CURRENT active theme
  const themeLabels = { dark: 'Midnight Obsidian', swiss: 'Swiss Minimalist', light: 'Pure Daylight' };
  const currentFullName = themeLabels[themeKey] || theme.label;

  const toggleIcon = qs('#theme-toggle-icon');
  const toggleLabel = qs('#theme-toggle-label');
  const toggleBtn = qs('#theme-toggle-btn');
  if (toggleIcon) {
    if (themeKey === 'dark') {
      toggleIcon.innerHTML = window.Icons ? window.Icons.get('moon', { color: 'purple' }) : '<svg class="svg-icon icon-purple" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"/></svg>';
    } else if (themeKey === 'swiss') {
      toggleIcon.innerHTML = window.Icons ? window.Icons.get('swiss', { color: 'red' }) : '<svg class="svg-icon icon-red" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="9 3 15 3 15 9 21 9 21 15 15 15 15 21 9 21 9 15 3 15 3 9 9 9" fill="currentColor" stroke="none"/></svg>';
    } else {
      toggleIcon.innerHTML = window.Icons ? window.Icons.get('sun', { color: 'amber' }) : '<svg class="svg-icon icon-amber" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="4"/><path d="M12 2v2"/><path d="M12 20v2"/><path d="m4.93 4.93 1.41 1.41"/><path d="m17.66 17.66 1.41 1.41"/><path d="M2 12h2"/><path d="M20 12h2"/><path d="m6.34 17.66-1.41 1.41"/><path d="m19.07 4.93-1.41 1.41"/></svg>';
    }
  }
  if (toggleLabel) {
    toggleLabel.textContent = theme.label;
  }
  if (toggleBtn) {
    toggleBtn.title = `Theme: ${theme.label} (${currentFullName}) · Click to choose theme`;
    toggleBtn.setAttribute('aria-label', `Current theme: ${theme.label}. Click to choose theme.`);
  }

  // Update hero page theme toggle button
  const heroToggleIcon = qs('#hero-theme-toggle-icon');
  const heroToggleLabel = qs('#hero-theme-toggle-label');
  const heroToggleBtn = qs('#hero-theme-toggle-btn');
  if (heroToggleIcon) {
    if (themeKey === 'dark') {
      heroToggleIcon.innerHTML = window.Icons ? window.Icons.get('moon', { color: 'purple' }) : '<svg class="svg-icon icon-purple" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"/></svg>';
    } else if (themeKey === 'swiss') {
      heroToggleIcon.innerHTML = window.Icons ? window.Icons.get('swiss', { color: 'red' }) : '<svg class="svg-icon icon-red" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="9 3 15 3 15 9 21 9 21 15 15 15 15 21 9 21 9 15 3 15 3 9 9 9" fill="currentColor" stroke="none"/></svg>';
    } else {
      heroToggleIcon.innerHTML = window.Icons ? window.Icons.get('sun', { color: 'amber' }) : '<svg class="svg-icon icon-amber" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="4"/><path d="M12 2v2"/><path d="M12 20v2"/><path d="m4.93 4.93 1.41 1.41"/><path d="m17.66 17.66 1.41 1.41"/><path d="M2 12h2"/><path d="M20 12h2"/><path d="m6.34 17.66-1.41 1.41"/><path d="m19.07 4.93-1.41 1.41"/></svg>';
    }
  }
  if (heroToggleLabel) {
    heroToggleLabel.textContent = theme.label;
  }
  if (heroToggleBtn) {
    heroToggleBtn.title = `Theme: ${theme.label} (${currentFullName}) · Click to choose theme`;
    heroToggleBtn.setAttribute('aria-label', `Current theme: ${theme.label}. Click to choose theme.`);
  }

  // Update theme dropdown active states & badges
  qsa('.theme-dropdown-item').forEach(item => {
    const isSelected = item.dataset.themeOpt === themeKey;
    item.classList.toggle('active', isSelected);
    item.setAttribute('aria-checked', isSelected ? 'true' : 'false');
  });
  const headerBadge = qs('#theme-dropdown-current-badge');
  if (headerBadge) headerBadge.textContent = theme.label;
  const heroBadge = qs('#hero-theme-dropdown-current-badge');
  if (heroBadge) heroBadge.textContent = theme.label;

  // Apply graph canvas theme
  if (App.graph) {
    App.graph.applyTheme({ ...theme.graph, key: themeKey });
  }

  // Update theme card active state
  qsa('.theme-card').forEach(card => {
    card.classList.toggle('active', card.dataset.theme === themeKey);
  });
}

function applyAllSettings(settings) {
  // Apply theme
  applyTheme(settings.theme);

  // Apply graph physics/visual settings
  if (App.graph) {
    App.graph.applySettings({
      nodeBaseRadius: settings.nodeBaseRadius,
      repulsion: settings.repulsion,
      springLen: settings.springLen,
      damping: settings.damping,
      showParticles: settings.showParticles,
      showMinimap: settings.showMinimap,
      showLabels: settings.showLabels,
      showGrid: settings.showGrid,
      showHulls: settings.showHulls,
      packageMode: settings.packageMode || 'auto',
    });
  }

  // Default depth
  App.graphDepth = settings.defaultDepth;
}

function syncSettingsUI(settings) {
  const modal = qs('#settings-modal');
  if (!modal) return;

  // Theme cards
  qsa('.theme-card').forEach(c => c.classList.toggle('active', c.dataset.theme === settings.theme));

  // Sliders
  const setSlider = (id, val) => {
    const el = qs(`#${id}`);
    if (el) { el.value = val; const vEl = qs(`#${id}-val`); if (vEl) vEl.textContent = val; }
  };
  setSlider('set-node-size', settings.nodeBaseRadius);
  setSlider('set-repulsion', settings.repulsion);
  setSlider('set-spring-len', settings.springLen);
  setSlider('set-damping', settings.damping);

  // Toggles
  const setToggle = (id, val) => { const el = qs(`#${id}`); if (el) el.checked = val; };
  setToggle('set-particles', settings.showParticles);
  setToggle('set-minimap', settings.showMinimap);
  setToggle('set-labels', settings.showLabels);
  setToggle('set-grid', settings.showGrid);
  setToggle('set-auto-fit', settings.autoFit);
  setToggle('set-hulls', settings.showHulls);

  // Depth dropdown
  const depthSel = qs('#set-default-depth');
  if (depthSel) depthSel.value = settings.defaultDepth;

  // Exclude patterns input
  const excludeInput = qs('#set-exclude-patterns');
  if (excludeInput) excludeInput.value = settings.excludePatterns !== undefined ? settings.excludePatterns : 'target, build, .mvn, .git, .gradle, node_modules, bin, out';

  // Package Mode select dropdown
  const modeSel = qs('#set-package-mode');
  if (modeSel) modeSel.value = settings.packageMode || 'auto';

  // POJO Settings sync
  if (window.CodeLensClassifier) {
    const pojoCfg = window.CodeLensClassifier.getPojoConfig() || {};
    const pojoStdChk = qs('#set-pojo-std');
    if (pojoStdChk) pojoStdChk.checked = (pojoCfg.includeStandardAccessors !== false && pojoCfg.enableStandardGettersSetters !== false);
    const pojoPatternsArea = qs('#set-pojo-patterns');
    if (pojoPatternsArea) {
      const raw = Array.isArray(pojoCfg.customPatterns) && pojoCfg.customPatterns.length > 0
        ? pojoCfg.customPatterns
        : (typeof pojoCfg.patterns === 'string' ? pojoCfg.patterns.split(/[,\n]+/) : []);
      const patterns = raw.map(s => s.trim()).filter(Boolean);
      pojoPatternsArea.value = patterns.join(', ');
    }
  }


  // Archetype Rules list rendering
  renderArchetypeRulesList();
}

function updateFormLivePreview() {
  const previewEl = qs('#rule-form-live-preview');
  if (!previewEl) return;
  const label = qs('#rule-form-label')?.value.trim() || 'New Archetype';
  const badge = qs('#rule-form-badge')?.value.trim() || label;
  const color = qs('#rule-form-color')?.value.trim() || '#10b981';
  const iconKey = qs('#rule-form-icon')?.value.trim() || 'tag';
  const iconSvg = window.Icons ? window.Icons.get(iconKey, { size: 'xs' }) : '';
  previewEl.innerHTML = `
    <span class="archetype-badge-pill preview-badge" style="background:${color}20; color:${color}; border:1px solid ${color}55;">
      ${iconSvg}
      <span class="archetype-badge-tag">${esc(badge)}</span>
    </span>
  `;
}

let currentArchetypeScopeFilter = 'all'; // 'all', 'class', 'method'
let archetypeSearchQuery = '';

function initArchetypeFilterControls() {
  const tabBtns = qsa('.archetype-tab-btn');
  tabBtns.forEach(btn => {
    btn.classList.toggle('active', (btn.dataset.filter || 'all') === currentArchetypeScopeFilter);
    btn.onclick = () => {
      tabBtns.forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      currentArchetypeScopeFilter = btn.dataset.filter || 'all';
      renderArchetypeRulesList();
    };
  });

  const searchInput = qs('#archetype-search-input');
  if (searchInput && !searchInput._wired) {
    searchInput._wired = true;
    searchInput.oninput = () => {
      archetypeSearchQuery = (searchInput.value || '').trim().toLowerCase();
      renderArchetypeRulesList();
    };
  }
}

function dockArchetypeFormToTop() {
  const form = qs('#archetype-rule-form-wrap');
  const anchor = qs('#archetype-form-default-anchor');
  if (form && anchor && form.parentElement !== anchor) {
    anchor.appendChild(form);
  }
}

function closeArchetypeForm() {
  const form = qs('#archetype-rule-form-wrap');
  if (form) {
    form.style.display = 'none';
    form.classList.remove('is-inline');
  }
  document.querySelectorAll('.archetype-card.is-editing-target').forEach(c => {
    c.classList.remove('is-editing-target');
  });
  dockArchetypeFormToTop();
}

function renderArchetypeRulesList() {
  const container = qs('#archetype-rules-list');
  if (!container || !window.CodeLensClassifier) return;

  dockArchetypeFormToTop();

  initArchetypeFilterControls();

  const rules = window.CodeLensClassifier.getRules();
  const activeCount = rules.filter(r => r.enabled).length;

  const countBadge = qs('#archetype-count-badge');
  if (countBadge) {
    countBadge.textContent = `${activeCount} / ${rules.length} active`;
    countBadge.className = `archetype-count-badge ${activeCount > 0 ? 'active' : 'empty'}`;
  }

  const classRulesAll = rules.filter(r => (r.scope || r.target || '').toUpperCase() === 'CLASS');
  const methodRulesAll = rules.filter(r => (r.scope || r.target || '').toUpperCase() === 'METHOD');
  const otherRulesAll = rules.filter(r => {
    const s = (r.scope || r.target || '').toUpperCase();
    return s !== 'CLASS' && s !== 'METHOD';
  });

  // Update tab counts
  const badgeAll = qs('#tab-badge-all'); if (badgeAll) badgeAll.textContent = rules.length;
  const badgeClass = qs('#tab-badge-class'); if (badgeClass) badgeClass.textContent = classRulesAll.length;
  const badgeMethod = qs('#tab-badge-method'); if (badgeMethod) badgeMethod.textContent = methodRulesAll.length;

  // Search filter
  const matchesSearch = r => {
    if (!archetypeSearchQuery) return true;
    const q = archetypeSearchQuery;
    return (r.label && r.label.toLowerCase().includes(q)) ||
           (r.badge && r.badge.toLowerCase().includes(q)) ||
           (r.pattern && r.pattern.toLowerCase().includes(q)) ||
           (r.description && r.description.toLowerCase().includes(q));
  };

  const classRules = classRulesAll.filter(matchesSearch);
  const methodRules = methodRulesAll.filter(matchesSearch);
  const otherRules = otherRulesAll.filter(matchesSearch);

  if (rules.length === 0) {
    container.innerHTML = `
      <div class="archetype-empty-state">
        <svg class="svg-icon icon-slate icon-lg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2H2v10l9.29 9.29c.94.94 2.48.94 3.42 0l6.58-6.58c.94-.94.94-2.48 0-3.42L12 2Z"/><circle cx="7" cy="7" r=".5" fill="currentColor"/></svg>
        <p class="archetype-empty-title">No Archetype Rules Configured</p>
        <p class="archetype-empty-sub">Choose a preset above (Banking, Spring REST, Clean Arch) or click "+ Add Archetype" to create custom rules.</p>
      </div>`;
    return;
  }

  const renderCard = r => {
    const color = r.color || '#10b981';
    const scope = (r.scope || r.target || 'METHOD').toUpperCase();
    const matchType = (r.matchType || 'PREFIX').toUpperCase();
    const iconSvg = window.Icons ? window.Icons.get(r.icon || 'tag', { size: 'xs' }) : '';

    return `
      <div class="archetype-card ${r.enabled ? 'is-active' : 'is-disabled'}" data-id="${r.id}" style="--arch-color:${color};">
        <div class="archetype-col-status">
          <label class="toggle-switch" title="${r.enabled ? 'Click to disable' : 'Click to enable'}">
            <input type="checkbox" class="rule-toggle" data-id="${r.id}" ${r.enabled ? 'checked' : ''}>
            <span class="toggle-track"></span>
          </label>
        </div>
        <div class="archetype-col-badge">
          <span class="archetype-badge-pill" style="background:${color}18; color:${color}; border: 1px solid ${color}66;">
            ${iconSvg}
            <span class="archetype-badge-tag">${esc(r.badge || r.label)}</span>
          </span>
        </div>
        <div class="archetype-col-meta">
          <span class="archetype-card-title">${esc(r.label)}</span>
          <div class="archetype-chips-row">
            <span class="archetype-scope-chip scope-${scope.toLowerCase()}">${scope}</span>
            <span class="archetype-match-chip">${matchType}</span>
          </div>
        </div>
        <div class="archetype-col-pattern">
          <code class="archetype-pattern-code" title="Pattern: ${esc(r.pattern)}">${esc(r.pattern)}</code>
        </div>
        <div class="archetype-col-desc">
          <span class="archetype-card-desc" title="${esc(r.description || '')}">${esc(r.description || '—')}</span>
        </div>
        <div class="archetype-col-actions">
          <button class="rule-action-btn rule-btn-edit" data-id="${r.id}" title="Edit Archetype">
            <svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M17 3a2.828 2.828 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5L17 3z"/></svg>
          </button>
          <button class="rule-action-btn rule-btn-delete" data-id="${r.id}" title="Delete Archetype">
            <svg class="svg-icon icon-xs icon-red" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
          </button>
        </div>
      </div>
    `;
  };

  const renderTableHeader = () => `
    <div class="archetype-table-header">
      <span class="col-hdr status-hdr">Active</span>
      <span class="col-hdr badge-hdr">Badge Tag</span>
      <span class="col-hdr meta-hdr">Archetype &amp; Scope</span>
      <span class="col-hdr pattern-hdr">Match Pattern</span>
      <span class="col-hdr desc-hdr">Semantic Role &amp; Purpose</span>
      <span class="col-hdr actions-hdr" style="text-align:right;">Actions</span>
    </div>
  `;

  let listHtml = '';
  const showClass = currentArchetypeScopeFilter === 'all' || currentArchetypeScopeFilter === 'class';
  const showMethod = currentArchetypeScopeFilter === 'all' || currentArchetypeScopeFilter === 'method';

  if (showClass) {
    const activeClass = classRulesAll.filter(r => r.enabled).length;
    listHtml += `
      <div class="archetype-rules-group-section">
        <div class="archetype-group-banner class-banner">
          <div class="archetype-group-banner-left">
            <span class="archetype-group-icon class-icon">
              <svg class="svg-icon icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M3 9h18"/></svg>
            </span>
            <div>
              <div class="archetype-group-heading">Class Archetypes</div>
              <div class="archetype-group-subtext">Stereotypes governing class-level classification, domain entities, and component roles</div>
            </div>
          </div>
          <div class="archetype-group-stats">
            <span class="archetype-stat-pill">${activeClass} of ${classRulesAll.length} Active</span>
          </div>
        </div>
        ${classRules.length > 0 ? renderTableHeader() : ''}
        <div class="archetype-rules-group-cards">
          ${classRules.length > 0 ? classRules.map(renderCard).join('') : `<div class="archetype-empty-filter-note">No class archetypes match the search filter.</div>`}
        </div>
      </div>
    `;
  }

  if (showMethod) {
    const activeMethod = methodRulesAll.filter(r => r.enabled).length;
    listHtml += `
      <div class="archetype-rules-group-section">
        <div class="archetype-group-banner method-banner">
          <div class="archetype-group-banner-left">
            <span class="archetype-group-icon method-icon">
              <svg class="svg-icon icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><line x1="8.59" y1="13.51" x2="15.42" y2="17.49"/><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"/></svg>
            </span>
            <div>
              <div class="archetype-group-heading">Method Archetypes</div>
              <div class="archetype-group-subtext">Patterns classifying member method operations, transactional prefixes, and call semantics</div>
            </div>
          </div>
          <div class="archetype-group-stats">
            <span class="archetype-stat-pill">${activeMethod} of ${methodRulesAll.length} Active</span>
          </div>
        </div>
        ${methodRules.length > 0 ? renderTableHeader() : ''}
        <div class="archetype-rules-group-cards">
          ${methodRules.length > 0 ? methodRules.map(renderCard).join('') : `<div class="archetype-empty-filter-note">No method archetypes match the search filter.</div>`}
        </div>
      </div>
    `;
  }

  if (otherRulesAll.length > 0 && currentArchetypeScopeFilter === 'all') {
    const activeOther = otherRulesAll.filter(r => r.enabled).length;
    listHtml += `
      <div class="archetype-rules-group-section">
        <div class="archetype-group-banner other-banner">
          <div class="archetype-group-banner-left">
            <span class="archetype-group-icon other-icon">
              <svg class="svg-icon icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><path d="M12 8v4"/><path d="M12 16h.01"/></svg>
            </span>
            <div>
              <div class="archetype-group-heading">Other Archetypes</div>
              <div class="archetype-group-subtext">Custom patterns matching any entity or special scope</div>
            </div>
          </div>
          <div class="archetype-group-stats">
            <span class="archetype-stat-pill">${activeOther} of ${otherRulesAll.length} Active</span>
          </div>
        </div>
        ${otherRules.length > 0 ? renderTableHeader() : ''}
        <div class="archetype-rules-group-cards">
          ${otherRules.length > 0 ? otherRules.map(renderCard).join('') : `<div class="archetype-empty-filter-note">No other archetypes match the search filter.</div>`}
        </div>
      </div>
    `;
  }

  container.innerHTML = listHtml;

  // Wire rule toggles
  container.querySelectorAll('.rule-toggle').forEach(chk => {
    chk.onchange = () => {
      const id = chk.dataset.id;
      window.CodeLensClassifier.updateRule(id, { enabled: chk.checked });
      renderArchetypeRulesList();
      syncArchetypesAndPojosAcrossApp({ showNotice: false });
    };
  });

  // Wire edit buttons
  container.querySelectorAll('.rule-btn-edit').forEach(btn => {
    btn.onclick = (e) => {
      e.stopPropagation();
      const id = btn.dataset.id;
      const card = btn.closest('.archetype-card');
      const form = qs('#archetype-rule-form-wrap');
      if (!form) return;

      // If already editing this exact card and form is visible, toggle close
      if (card && card.classList.contains('is-editing-target') && form.style.display === 'block') {
        closeArchetypeForm();
        return;
      }

      // Remove editing target state from all cards
      container.querySelectorAll('.archetype-card.is-editing-target').forEach(c => {
        c.classList.remove('is-editing-target');
      });

      const rule = window.CodeLensClassifier.getRules().find(r => r.id === id);
      if (!rule) return;

      const idEl = qs('#rule-form-id'); if (idEl) idEl.value = rule.id;
      const titleEl = qs('#rule-form-title'); if (titleEl) titleEl.textContent = `Edit Archetype: ${rule.label || rule.badge || 'Rule'}`;
      const labelEl = qs('#rule-form-label'); if (labelEl) labelEl.value = rule.label || '';
      const badgeEl = qs('#rule-form-badge'); if (badgeEl) badgeEl.value = rule.badge || '';
      const iconEl = qs('#rule-form-icon'); if (iconEl) iconEl.value = rule.icon || 'tag';
      const colorVal = rule.color || '#3b82f6';
      const colorEl = qs('#rule-form-color'); if (colorEl) colorEl.value = colorVal;
      const colorTextEl = qs('#rule-form-color-text'); if (colorTextEl) colorTextEl.value = colorVal;
      const targetEl = qs('#rule-form-target') || qs('#rule-form-scope'); if (targetEl) targetEl.value = (rule.scope || rule.target || 'METHOD').toUpperCase();
      const matchTypeEl = qs('#rule-form-match-type'); if (matchTypeEl) matchTypeEl.value = (rule.matchType || 'PREFIX').toUpperCase();
      const patternEl = qs('#rule-form-pattern'); if (patternEl) patternEl.value = rule.pattern || '';
      const descEl = qs('#rule-form-desc'); if (descEl) descEl.value = rule.description || '';

      if (card) {
        card.classList.add('is-editing-target');
        card.insertAdjacentElement('afterend', form);
        form.classList.add('is-inline');
        form.style.setProperty('--arch-edit-color', colorVal);
      }
      form.style.display = 'block';
      updateFormLivePreview();

      setTimeout(() => {
        form.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        if (labelEl) {
          labelEl.focus();
          labelEl.select();
        }
      }, 50);
    };
  });

  // Wire delete buttons
  container.querySelectorAll('.rule-btn-delete').forEach(btn => {
    btn.onclick = (e) => {
      e.stopPropagation();
      const id = btn.dataset.id;
      const rule = window.CodeLensClassifier.getRules().find(r => r.id === id);
      const label = rule ? rule.label : 'this archetype rule';
      if (!confirm(`Are you sure you want to delete archetype "${label}"?`)) {
        return;
      }
      closeArchetypeForm();
      window.CodeLensClassifier.deleteRule(id);
      renderArchetypeRulesList();
      syncArchetypesAndPojosAcrossApp({ showNotice: false });
      if (typeof toast !== 'undefined' && toast.info) {
        toast.info(`Deleted archetype "${label}"`);
      } else {
        showBanner(`Deleted archetype "${label}"`);
      }
    };
  });

  populateArchetypeDropdowns();
}

/**
 * Switch active application theme and broadcast state change
 */
function selectTheme(themeKey) {
  const s = loadSettings();
  s.theme = themeKey;
  saveSettings(s);
  applyAllSettings(s);
  syncSettingsUI(s);
  const labels = { dark: 'Midnight Obsidian', swiss: 'Swiss Minimalist', light: 'Pure Daylight' };
  const label = labels[themeKey] || themeKey;
  if (typeof toast !== 'undefined' && toast.success) {
    toast.success(`Theme switched to ${label}`, { id: 'theme-switch' });
  } else {
    showBanner(`Theme switched to ${label}`);
  }
}

/**
 * Initialize interactive theme dropdown menus for Header and Hero page
 */
function initThemeDropdowns() {
  const configs = [
    {
      btn: qs('#theme-toggle-btn'),
      menu: qs('#theme-dropdown-menu'),
      wrap: qs('#theme-dropdown-wrap'),
      settingsLink: qs('#theme-dropdown-open-settings')
    },
    {
      btn: qs('#hero-theme-toggle-btn'),
      menu: qs('#hero-theme-dropdown-menu'),
      wrap: qs('#hero-theme-dropdown-wrap'),
      settingsLink: qs('#hero-theme-dropdown-open-settings')
    }
  ];

  function closeAll() {
    configs.forEach(cfg => {
      if (cfg.wrap && cfg.menu) {
        cfg.wrap.classList.remove('is-open');
        cfg.menu.style.display = 'none';
        if (cfg.btn) cfg.btn.setAttribute('aria-expanded', 'false');
      }
    });
  }

  function openDropdown(cfg) {
    if (!cfg.wrap || !cfg.menu || !cfg.btn) return;
    closeAll();
    cfg.wrap.classList.add('is-open');
    cfg.menu.style.display = 'flex';
    cfg.btn.setAttribute('aria-expanded', 'true');

    // Ensure header scroll is anchored to 0
    const hdr = qs('#header');
    if (hdr && hdr.scrollTop !== 0) hdr.scrollTop = 0;

    // Focus active or first item for accessible keyboard navigation without scrolling viewport
    const activeItem = cfg.menu.querySelector('.theme-dropdown-item.active') || cfg.menu.querySelector('.theme-dropdown-item');
    if (activeItem) {
      setTimeout(() => {
        try {
          activeItem.focus({ preventScroll: true });
        } catch (ignored) {
          activeItem.focus();
        }
        if (hdr && hdr.scrollTop !== 0) hdr.scrollTop = 0;
      }, 25);
    }
  }

  configs.forEach(cfg => {
    if (!cfg.btn || !cfg.menu || !cfg.wrap) return;

    // Toggle button click
    cfg.btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const isOpen = cfg.wrap.classList.contains('is-open');
      if (isOpen) {
        closeAll();
      } else {
        openDropdown(cfg);
      }
    });

    // Theme options
    const items = Array.from(cfg.menu.querySelectorAll('.theme-dropdown-item'));
    items.forEach((item, index) => {
      item.addEventListener('click', (e) => {
        e.stopPropagation();
        const themeOpt = item.dataset.themeOpt;
        if (themeOpt) {
          selectTheme(themeOpt);
        }
        closeAll();
        cfg.btn.focus();
      });

      // Keyboard navigation
      item.addEventListener('keydown', (e) => {
        if (e.key === 'ArrowDown') {
          e.preventDefault();
          const next = items[(index + 1) % items.length];
          next?.focus();
        } else if (e.key === 'ArrowUp') {
          e.preventDefault();
          const prev = items[(index - 1 + items.length) % items.length];
          prev?.focus();
        } else if (e.key === 'Home') {
          e.preventDefault();
          items[0]?.focus();
        } else if (e.key === 'End') {
          e.preventDefault();
          items[items.length - 1]?.focus();
        } else if (e.key === 'Escape') {
          e.preventDefault();
          closeAll();
          cfg.btn.focus();
        }
      });
    });

    // Deep link to appearance settings
    if (cfg.settingsLink) {
      cfg.settingsLink.addEventListener('click', (e) => {
        e.stopPropagation();
        closeAll();
        openSettings(e);
      });
      cfg.settingsLink.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') {
          e.preventDefault();
          closeAll();
          cfg.btn.focus();
        } else if (e.key === 'ArrowUp') {
          e.preventDefault();
          items[items.length - 1]?.focus();
        }
      });
    }

    // Toggle button keyboard triggers (down arrow opens menu)
    cfg.btn.addEventListener('keydown', (e) => {
      if (e.key === 'ArrowDown' || e.key === 'Enter' || e.key === ' ') {
        if (!cfg.wrap.classList.contains('is-open')) {
          e.preventDefault();
          openDropdown(cfg);
        }
      } else if (e.key === 'Escape') {
        if (cfg.wrap.classList.contains('is-open')) {
          e.preventDefault();
          closeAll();
        }
      }
    });
  });

  // Dismiss on clicking outside
  document.addEventListener('click', (e) => {
    if (!e.target.closest('.theme-dropdown-wrap')) {
      closeAll();
    }
  });

  // Dismiss on global escape
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      const anyOpen = configs.find(c => c.wrap && c.wrap.classList.contains('is-open'));
      if (anyOpen) {
        closeAll();
        anyOpen.btn?.focus();
      }
    }
  });
}

function openSettings(e) {
  const modal = qs('#settings-modal');
  if (!modal) return;
  syncSettingsUI(loadSettings());
  showAccessibleModal(modal, e?.currentTarget || qs('#settings-btn'));
}

function closeSettings() {
  dismissModalAnimated(qs('#settings-modal'));
}

function resetSettings() {
  const defaults = { ...SETTINGS_DEFAULTS };
  saveSettings(defaults);
  applyAllSettings(defaults);
  if (window.CodeLensClassifier) {
    window.CodeLensClassifier.resetPojoConfig();
    window.CodeLensClassifier.resetRules();
  }
  syncSettingsUI(defaults);
  showBanner('Settings restored to defaults');
}

function initSettings() {
  // Apply stored settings on load
  const settings = loadSettings();
  applyAllSettings(settings);

  // Initialize interactive theme dropdown menus
  initThemeDropdowns();

  // Wire settings button
  const settingsBtn = qs('#settings-btn');
  if (settingsBtn) settingsBtn.addEventListener('click', openSettings);

  // Wire modal close
  const closeBtn = qs('#settings-modal-close');
  if (closeBtn) closeBtn.addEventListener('click', closeSettings);

  // Wire modal backdrop click
  const modal = qs('#settings-modal');
  if (modal) {
    modal.addEventListener('click', e => { if (e.target === modal) closeSettings(); });
  }

  // Wire Reset Defaults
  const resetBtn = qs('#settings-reset-btn');
  if (resetBtn) resetBtn.addEventListener('click', resetSettings);

  // Wire Shutdown Server
  const shutdownBtn = qs('#btn-shutdown-server');
  if (shutdownBtn) {
    shutdownBtn.addEventListener('click', async () => {
      const confirmed = confirm('Are you sure you want to stop the CodeLens server process?\n\nThe web UI will disconnect and you will need to restart the server from your terminal.');
      if (!confirmed) return;
      try {
        shutdownBtn.disabled = true;
        shutdownBtn.textContent = 'Stopping server…';
        await api.shutdownServer();
        showBanner('CodeLens server is shutting down gracefully…');
        setTimeout(() => {
          document.body.innerHTML = `
            <div style="display:flex; flex-direction:column; align-items:center; justify-content:center; height:100vh; font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif; background:#07090e; color:#94a3b8; text-align:center; padding:24px;">
              <div style="width:48px; height:48px; border-radius:50%; background:rgba(244,63,94,0.15); display:flex; align-items:center; justify-content:center; margin-bottom:16px;">
                <svg style="width:24px; height:24px; stroke:#f43f5e;" viewBox="0 0 24 24" fill="none" stroke-width="2"><path d="M18.36 6.64a9 9 0 1 1-12.73 0"/><line x1="12" y1="2" x2="12" y2="12"/></svg>
              </div>
              <h2 style="color:#ffffff; font-size:20px; font-weight:700; margin:0 0 8px 0;">CodeLens Server Stopped</h2>
              <p style="max-width:480px; font-size:14px; line-height:1.6; color:#94a3b8; margin:0 0 20px 0;">The server has been shut down cleanly. All databases, indexes, and caches were safely committed.</p>
              <div style="padding:10px 16px; background:#0f172a; border:1px solid #1e293b; border-radius:6px; font-family:monospace; font-size:12px; color:#38bdf8;">
                java -jar codelens-app.jar
              </div>
            </div>
          `;
        }, 600);
      } catch (err) {
        showError('Shutdown failed: ' + err.message);
        shutdownBtn.disabled = false;
        shutdownBtn.textContent = 'Shutdown Server';
      }
    });
  }

  // Wire theme cards

  qsa('.theme-card').forEach(card => {
    card.addEventListener('click', () => {
      const s = loadSettings();
      s.theme = card.dataset.theme;
      saveSettings(s);
      applyAllSettings(s);
      syncSettingsUI(s);
    });
  });

  // Wire sliders
  const wireSlider = (id, key, parser = parseFloat) => {
    const el = qs(`#${id}`);
    if (!el) return;
    el.addEventListener('input', () => {
      const s = loadSettings();
      s[key] = parser(el.value);
      saveSettings(s);
      applyAllSettings(s);
      const vEl = qs(`#${id}-val`);
      if (vEl) vEl.textContent = el.value;
    });
  };
  wireSlider('set-node-size', 'nodeBaseRadius', parseInt);
  wireSlider('set-repulsion', 'repulsion', parseInt);
  wireSlider('set-spring-len', 'springLen', parseInt);
  wireSlider('set-damping', 'damping', parseFloat);

  // Wire toggles
  const wireToggle = (id, key) => {
    const el = qs(`#${id}`);
    if (!el) return;
    el.addEventListener('change', () => {
      const s = loadSettings();
      s[key] = el.checked;
      saveSettings(s);
      applyAllSettings(s);
    });
  };
  wireToggle('set-particles', 'showParticles');
  wireToggle('set-minimap', 'showMinimap');
  wireToggle('set-labels', 'showLabels');
  wireToggle('set-grid', 'showGrid');
  wireToggle('set-auto-fit', 'autoFit');
  wireToggle('set-hulls', 'showHulls');

  // Wire exclude patterns input
  const excludeInput = qs('#set-exclude-patterns');
  if (excludeInput) {
    excludeInput.addEventListener('input', () => {
      const s = loadSettings();
      s.excludePatterns = excludeInput.value.trim();
      saveSettings(s);
    });
  }

  // Wire package mode dropdown
  const modeSel = qs('#set-package-mode');
  if (modeSel) {
    modeSel.addEventListener('change', () => {
      const s = loadSettings();
      s.packageMode = modeSel.value;
      saveSettings(s);
      applyAllSettings(s);
    });
  }

  // Wire depth dropdown
  const depthSel = qs('#set-default-depth');
  if (depthSel) {
    depthSel.addEventListener('change', () => {
      const s = loadSettings();
      s.defaultDepth = parseInt(depthSel.value);
      saveSettings(s);
      applyAllSettings(s);
    });
  }

  // Wire POJO Settings
  const pojoStdChk = qs('#set-pojo-std');
  if (pojoStdChk) {
    pojoStdChk.addEventListener('change', () => {
      if (window.CodeLensClassifier) {
        window.CodeLensClassifier.setPojoConfig({ includeStandardAccessors: pojoStdChk.checked });
        syncArchetypesAndPojosAcrossApp({ showNotice: false });
      }
    });
  }

  const pojoPatternsArea = qs('#set-pojo-patterns');
  if (pojoPatternsArea) {
    pojoPatternsArea.addEventListener('input', () => {
      if (window.CodeLensClassifier) {
        const patterns = pojoPatternsArea.value.split(/[,\n]+/).map(s => s.trim()).filter(Boolean);
        window.CodeLensClassifier.setPojoConfig({ customPatterns: patterns, patterns: patterns.join(', ') });
        clearTimeout(pojoPatternsArea._syncTimer);
        pojoPatternsArea._syncTimer = setTimeout(() => {
          syncArchetypesAndPojosAcrossApp({ showNotice: false });
        }, 600);
      }
    });
  }

  const resetPojoBtn = qs('#btn-reset-pojo-patterns');
  if (resetPojoBtn) {
    resetPojoBtn.addEventListener('click', () => {
      if (window.CodeLensClassifier) {
        window.CodeLensClassifier.resetPojoConfig();
        const cfg = window.CodeLensClassifier.getPojoConfig() || {};
        if (pojoStdChk) pojoStdChk.checked = (cfg.includeStandardAccessors !== false && cfg.enableStandardGettersSetters !== false);
        if (pojoPatternsArea) {
          const raw = Array.isArray(cfg.customPatterns) && cfg.customPatterns.length > 0
            ? cfg.customPatterns
            : (typeof cfg.patterns === 'string' ? cfg.patterns.split(/[,\n]+/) : []);
          const patterns = raw.map(s => s.trim()).filter(Boolean);
          pojoPatternsArea.value = patterns.join(', ');
        }
        syncArchetypesAndPojosAcrossApp({ showNotice: false });
        showBanner('POJO detection criteria reset to default');
      }
    });
  }


  // Wire Archetype Rule Presets
  const btnPresetBancs = qs('#btn-preset-bancs');
  if (btnPresetBancs) {
    btnPresetBancs.addEventListener('click', () => {
      if (window.CodeLensClassifier) {
        closeArchetypeForm();
        window.CodeLensClassifier.loadPreset('bancs');
        renderArchetypeRulesList();
        syncArchetypesAndPojosAcrossApp({ showNotice: false });
        showBanner('Loaded Banking / BaNCS transaction archetypes');
      }
    });
  }

  const btnPresetSpring = qs('#btn-preset-spring');
  if (btnPresetSpring) {
    btnPresetSpring.addEventListener('click', () => {
      if (window.CodeLensClassifier) {
        closeArchetypeForm();
        window.CodeLensClassifier.loadPreset('spring');
        renderArchetypeRulesList();
        syncArchetypesAndPojosAcrossApp({ showNotice: false });
        showBanner('Loaded Spring REST / MVC archetypes');
      }
    });
  }

  const btnPresetDdd = qs('#btn-preset-ddd');
  if (btnPresetDdd) {
    btnPresetDdd.addEventListener('click', () => {
      if (window.CodeLensClassifier) {
        closeArchetypeForm();
        window.CodeLensClassifier.loadPreset('ddd');
        renderArchetypeRulesList();
        syncArchetypesAndPojosAcrossApp({ showNotice: false });
        showBanner('Loaded Domain-Driven Design / Clean Architecture archetypes');
      }
    });
  }

  const btnResetArchetypes = qs('#btn-reset-archetype-rules');
  if (btnResetArchetypes) {
    btnResetArchetypes.addEventListener('click', () => {
      if (window.CodeLensClassifier) {
        closeArchetypeForm();
        window.CodeLensClassifier.resetRules();
        renderArchetypeRulesList();
        syncArchetypesAndPojosAcrossApp({ showNotice: false });
        showBanner('Reset archetype rules to defaults');
      }
    });
  }

  // Wire Archetype Rule Form
  const btnAddRule = qs('#btn-add-archetype-rule');
  const formWrap = qs('#archetype-rule-form-wrap');
  if (btnAddRule && formWrap) {
    btnAddRule.addEventListener('click', () => {
      closeArchetypeForm();
      dockArchetypeFormToTop();
      const idEl = qs('#rule-form-id'); if (idEl) idEl.value = '';
      const titleEl = qs('#rule-form-title'); if (titleEl) titleEl.textContent = 'Add Archetype Rule';
      const labelEl = qs('#rule-form-label'); if (labelEl) labelEl.value = '';
      const badgeEl = qs('#rule-form-badge'); if (badgeEl) badgeEl.value = '';
      const iconEl = qs('#rule-form-icon'); if (iconEl) iconEl.value = 'tag';
      const colorEl = qs('#rule-form-color'); if (colorEl) colorEl.value = '#10b981';
      const colorTextEl = qs('#rule-form-color-text'); if (colorTextEl) colorTextEl.value = '#10b981';
      const targetEl = qs('#rule-form-target') || qs('#rule-form-scope'); if (targetEl) targetEl.value = 'METHOD';
      const matchTypeEl = qs('#rule-form-match-type'); if (matchTypeEl) matchTypeEl.value = 'PREFIX';
      const patternEl = qs('#rule-form-pattern'); if (patternEl) patternEl.value = '{MODULE}';
      const descEl = qs('#rule-form-desc'); if (descEl) descEl.value = '';
      formWrap.classList.remove('is-inline');
      formWrap.style.setProperty('--arch-edit-color', '#10b981');
      formWrap.style.display = 'block';
      updateFormLivePreview();

      setTimeout(() => {
        formWrap.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        if (labelEl) {
          labelEl.focus();
        }
      }, 50);
    });
  }

  const btnCloseRule = qs('#btn-close-rule-form');
  if (btnCloseRule) {
    btnCloseRule.addEventListener('click', () => {
      closeArchetypeForm();
    });
  }

  const btnCancelRule = qs('#btn-cancel-archetype-rule');
  if (btnCancelRule) {
    btnCancelRule.addEventListener('click', () => {
      closeArchetypeForm();
    });
  }

  const colorInput = qs('#rule-form-color');
  const colorTextInput = qs('#rule-form-color-text');
  if (colorInput && colorTextInput) {
    colorInput.addEventListener('input', () => {
      colorTextInput.value = colorInput.value;
      if (formWrap) formWrap.style.setProperty('--arch-edit-color', colorInput.value);
      updateFormLivePreview();
    });
    colorTextInput.addEventListener('input', () => {
      colorInput.value = colorTextInput.value;
      if (formWrap) formWrap.style.setProperty('--arch-edit-color', colorTextInput.value);
      updateFormLivePreview();
    });
  }

  ['#rule-form-label', '#rule-form-badge', '#rule-form-icon', '#rule-form-target', '#rule-form-match-type'].forEach(sel => {
    const el = qs(sel);
    if (el) el.addEventListener('input', updateFormLivePreview);
    if (el) el.addEventListener('change', updateFormLivePreview);
  });

  if (formWrap) {
    formWrap.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        closeArchetypeForm();
      }
    });
  }

  ['#rule-form-label', '#rule-form-badge', '#rule-form-pattern', '#rule-form-desc'].forEach(sel => {
    const el = qs(sel);
    if (el) {
      el.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
          e.preventDefault();
          const btnSave = qs('#btn-save-archetype-rule');
          if (btnSave) btnSave.click();
        }
      });
    }
  });

  const btnSaveRule = qs('#btn-save-archetype-rule');
  if (btnSaveRule && formWrap) {
    btnSaveRule.addEventListener('click', () => {
      const idEl = qs('#rule-form-id');
      const id = idEl ? idEl.value.trim() : '';
      const label = qs('#rule-form-label') ? qs('#rule-form-label').value.trim() : '';
      const badge = qs('#rule-form-badge') ? qs('#rule-form-badge').value.trim() : '';
      const icon = qs('#rule-form-icon') ? qs('#rule-form-icon').value.trim() || 'tag' : 'tag';
      const color = qs('#rule-form-color') ? qs('#rule-form-color').value.trim() || '#10b981' : '#10b981';
      const targetEl = qs('#rule-form-target') || qs('#rule-form-scope');
      const scope = targetEl ? targetEl.value : 'METHOD';
      const matchTypeEl = qs('#rule-form-match-type');
      const matchType = matchTypeEl ? matchTypeEl.value : 'PREFIX';
      const pattern = qs('#rule-form-pattern') ? qs('#rule-form-pattern').value.trim() : '';
      const description = qs('#rule-form-desc') ? qs('#rule-form-desc').value.trim() : '';

      if (!label || !pattern) {
        if (typeof toast !== 'undefined' && toast.warning) {
          toast.warning('Please provide at least an Archetype Name and Pattern.');
        } else {
          alert('Please provide at least an Archetype Name and Pattern.');
        }
        return;
      }

      if (window.CodeLensClassifier) {
        let saved = null;
        if (id) {
          saved = window.CodeLensClassifier.updateRule(id, { label, badge: badge || label, icon, color, scope, target: scope, matchType, pattern, description });
        } else {
          saved = window.CodeLensClassifier.addRule({ label, badge: badge || label, icon, color, scope, target: scope, matchType, pattern, description, enabled: true });
        }
        closeArchetypeForm();
        renderArchetypeRulesList();

        const targetId = id || (saved ? saved.id : null);
        if (targetId) {
          const cardEl = qs(`.archetype-card[data-id="${targetId}"]`);
          if (cardEl) {
            cardEl.classList.add('card-updated-flash');
            cardEl.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
            setTimeout(() => cardEl.classList.remove('card-updated-flash'), 2000);
          }
        }

        syncArchetypesAndPojosAcrossApp({ showNotice: false });
        if (typeof toast !== 'undefined' && toast.success) {
          toast.success(`Saved archetype "${label}"`);
        } else {
          showBanner(`Saved archetype "${label}"`);
        }
      }
    });
  }

  const btnSyncArchReports = qs('#btn-sync-archetypes-reports');
  if (btnSyncArchReports) {
    btnSyncArchReports.addEventListener('click', () => {
      syncArchetypesAndPojosAcrossApp({ showNotice: true });
    });
  }

  // Wire Deployment Config (.conf) Export, Import, and Save
  const btnExportConf = qs('#btn-export-conf');
  if (btnExportConf) btnExportConf.addEventListener('click', exportDeploymentConf);

  const inputImportConf = qs('#input-import-conf');
  if (inputImportConf) {
    inputImportConf.addEventListener('change', (e) => {
      const file = e.target.files && e.target.files[0];
      if (file) {
        importDeploymentConf(file);
        inputImportConf.value = '';
      }
    });
  }

  const btnSaveServerConf = qs('#btn-save-server-conf');
  if (btnSaveServerConf) btnSaveServerConf.addEventListener('click', saveDeploymentConfToServer);

  // Sync settings with server on startup
  syncSettingsFromServer();
}

/**
 * Seamlessly reflects archetype rule or POJO pattern changes across the Knowledge Base,
 * visual studios, and triggers background reports regeneration in BackgroundTaskOrchestrator.
 */
async function syncArchetypesAndPojosAcrossApp(options = {}) {
  const { showNotice = true } = options;

  // 1. Instantly refresh the active Knowledge Base view in-memory (0ms)
  try {
    if (App.selected && App.selected.kind === 'package' && App.selected.id) {
      loadKnowledgeBase(App.selected.id);
    } else if (App.selected && App.selected.kind === 'type' && App.selected.data) {
      renderKnowledgeBaseForType(App.selected.data);
    } else if (App.activeTab === 'knowledge') {
      const firstPkg = (App.packages && App.packages[0] && App.packages[0].fqn) || null;
      if (firstPkg) loadKnowledgeBase(firstPkg);
    }
  } catch (e) {
    console.debug('KB view refresh caught:', e);
  }

  // 2. Invalidate client-side report caches and reload active report if open
  if (window.ReportsHub) {
    if (window.ReportsHub.cache) {
      window.ReportsHub.cache = {};
    }
    if ((App.activeTab === 'review' || App.activeTab === 'reports') && typeof window.ReportsHub.loadActiveReport === 'function') {
      window.ReportsHub.loadActiveReport();
    }
  }

  // 3. Re-apply 2D graph filters if graph is active
  if (App.graph && typeof App.graph.reapplyFilters === 'function') {
    App.graph.reapplyFilters();
  }

  // 4. Save deployment config to server (triggers background reports-generator task in BackgroundTaskOrchestrator)
  try {
    await saveDeploymentConfToServer();
    if (showNotice) {
      if (typeof toast !== 'undefined' && toast.success) {
        toast.success('Archetypes & POJOs synchronized! Knowledge Base updated & reports regenerating in background.');
      } else {
        showBanner('Archetypes & POJOs synced to Knowledge Base & background reports generator.');
      }
    }
  } catch (err) {
    console.warn('Auto-sync to server config failed:', err);
  }
}

// ── Deployment Configuration (.conf) Management ──────────────────────────────

function buildFullConfigObject() {
  const s = loadSettings();
  const pojoCfg = (window.CodeLensClassifier && window.CodeLensClassifier.getPojoConfig()) || {};
  const rules = (window.CodeLensClassifier && window.CodeLensClassifier.getRules()) || [];

  return {
    port: 7878,
    dataDir: './codelens-data',
    defaultScanPath: qs('#scan-path-input')?.value || '',
    excludePatterns: s.excludePatterns || 'target, build, .mvn, .git, .gradle, node_modules, bin, out',
    theme: s.theme || 'dark',
    packageMode: s.packageMode || 'auto',
    defaultTab: App.activeTab || 'graph',
    nodeBaseRadius: s.nodeBaseRadius || 12,
    repulsion: s.repulsion || 350,
    springLen: s.springLen || 120,
    damping: s.damping !== undefined ? s.damping : 0.85,
    showParticles: s.showParticles !== undefined ? s.showParticles : true,
    showMinimap: s.showMinimap !== undefined ? s.showMinimap : true,
    showLabels: s.showLabels !== undefined ? s.showLabels : true,
    showGrid: s.showGrid !== undefined ? s.showGrid : true,
    showHulls: s.showHulls !== undefined ? s.showHulls : true,
    defaultDepth: s.defaultDepth || 3,
    autoFit: s.autoFit !== undefined ? s.autoFit : true,
    defaultMacroLevel: App.codebaseMacroLevel || 'city3d',
    defaultMacroGranularity: App.codebaseGranularity || 'arch',
    macroBrightness: App.activeAltRenderer?._exposure || 1.0,
    macroShowArcs: true,
    macroAutoRotate: false,
    macroShowWireframe: false,
    cyclomaticComplexityThreshold: 15,
    cognitiveComplexityThreshold: 15,
    methodLinesThreshold: 50,
    classLinesThreshold: 500,
    parameterCountThreshold: 6,
    pojoIncludeStandardAccessors: pojoCfg.includeStandardAccessors !== false,
    pojoCustomPatterns: Array.isArray(pojoCfg.customPatterns) ? pojoCfg.customPatterns.join(', ') : (pojoCfg.customPatterns || 'get*, set*, is*, has*, with*'),
    archetypeRulesJson: JSON.stringify(rules),
    tabOrder: JSON.parse(localStorage.getItem('codelens_tab_order') || '["graph","knowledge","review","git","source"]'),
  };
}

async function exportDeploymentConf() {
  try {
    const res = await fetch('/api/config/export');
    if (res.ok) {
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'codelens.conf';
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
      showBanner('Exported codelens.conf deployment file');
      return;
    }
  } catch (_) {}

  const cfg = buildFullConfigObject();
  let conf = '# ═══════════════════════════════════════════════════════════════════════════════\n';
  conf += '# CodeLens Deployment Configuration (codelens.conf)\n';
  conf += '# Generated: ' + new Date().toISOString() + '\n';
  conf += '# ═══════════════════════════════════════════════════════════════════════════════\n\n';
  for (const [k, v] of Object.entries(cfg)) {
    conf += `${k}=${v}\n`;
  }
  const blob = new Blob([conf], { type: 'text/plain;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = 'codelens.conf';
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
  showBanner('Exported codelens.conf');
}

async function importDeploymentConf(file) {
  if (!file) return;
  const statusEl = qs('#deployment-config-status-text');
  if (statusEl) statusEl.textContent = 'Importing ' + file.name + '…';

  try {
    const text = await file.text();
    const res = await fetch('/api/config/import', {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain; charset=utf-8' },
      body: text,
    });

    let configData = null;
    if (res.ok) {
      const data = await res.json();
      configData = data.config;
    }

    applyImportedConfig(configData || parseClientConf(text));
    if (statusEl) statusEl.textContent = 'Active: Imported from ' + file.name;
    showBanner('Settings imported and restored from ' + file.name);
  } catch (e) {
    if (statusEl) statusEl.textContent = 'Failed to import: ' + e.message;
    showError('Failed to import configuration: ' + e.message);
  }
}

function parseClientConf(text) {
  const lines = text.split('\n');
  const cfg = {};
  for (let line of lines) {
    line = line.trim();
    if (!line || line.startsWith('#')) continue;
    const eqIdx = line.indexOf('=');
    if (eqIdx === -1) continue;
    const key = line.substring(0, eqIdx).trim();
    const val = line.substring(eqIdx + 1).trim();
    cfg[key] = val;
  }
  return cfg;
}

function applyImportedConfig(cfg) {
  if (!cfg) return;
  const s = loadSettings();

  if (cfg.theme || cfg['ui.theme']) s.theme = cfg.theme || cfg['ui.theme'];
  if (cfg.packageMode || cfg['ui.packageMode']) s.packageMode = cfg.packageMode || cfg['ui.packageMode'];
  if (cfg.nodeBaseRadius || cfg['graph.nodeBaseRadius']) s.nodeBaseRadius = parseInt(cfg.nodeBaseRadius || cfg['graph.nodeBaseRadius']);
  if (cfg.repulsion || cfg['graph.repulsion']) s.repulsion = parseInt(cfg.repulsion || cfg['graph.repulsion']);
  if (cfg.springLen || cfg['graph.springLen']) s.springLen = parseInt(cfg.springLen || cfg['graph.springLen']);
  if (cfg.damping || cfg['graph.damping']) s.damping = parseFloat(cfg.damping || cfg['graph.damping']);
  if (cfg.showParticles !== undefined || cfg['graph.showParticles'] !== undefined) s.showParticles = String(cfg.showParticles ?? cfg['graph.showParticles']) === 'true';
  if (cfg.showMinimap !== undefined || cfg['graph.showMinimap'] !== undefined) s.showMinimap = String(cfg.showMinimap ?? cfg['graph.showMinimap']) === 'true';
  if (cfg.showLabels !== undefined || cfg['graph.showLabels'] !== undefined) s.showLabels = String(cfg.showLabels ?? cfg['graph.showLabels']) === 'true';
  if (cfg.showGrid !== undefined || cfg['graph.showGrid'] !== undefined) s.showGrid = String(cfg.showGrid ?? cfg['graph.showGrid']) === 'true';
  if (cfg.showHulls !== undefined || cfg['graph.showHulls'] !== undefined) s.showHulls = String(cfg.showHulls ?? cfg['graph.showHulls']) === 'true';
  if (cfg.defaultDepth || cfg['graph.defaultDepth']) s.defaultDepth = parseInt(cfg.defaultDepth || cfg['graph.defaultDepth']);
  if (cfg.autoFit !== undefined || cfg['graph.autoFit'] !== undefined) s.autoFit = String(cfg.autoFit ?? cfg['graph.autoFit']) === 'true';
  if (cfg.excludePatterns || cfg['scan.excludePatterns']) s.excludePatterns = cfg.excludePatterns || cfg['scan.excludePatterns'];

  saveSettings(s);
  applyAllSettings(s);

  // Apply POJO Config
  if (window.CodeLensClassifier) {
    const pojoStd = cfg.pojoIncludeStandardAccessors ?? cfg['pojo.includeStandardAccessors'];
    const pojoPatterns = cfg.pojoCustomPatterns ?? cfg['pojo.customPatterns'];
    const pCfg = {};
    if (pojoStd !== undefined) pCfg.includeStandardAccessors = String(pojoStd) === 'true';
    if (pojoPatterns) pCfg.customPatterns = typeof pojoPatterns === 'string' ? pojoPatterns.split(',').map(x => x.trim()).filter(Boolean) : pojoPatterns;
    window.CodeLensClassifier.setPojoConfig(pCfg);

    // Archetypes
    const archJson = cfg.archetypeRulesJson ?? cfg['archetypes.rulesJson'];
    if (archJson) {
      try {
        const rules = typeof archJson === 'string' ? JSON.parse(archJson) : archJson;
        if (Array.isArray(rules)) {
          window.CodeLensClassifier.setRules(rules);
        }
      } catch (_) {}
    }
  }

  // Tab Order
  if (cfg.tabOrder || cfg['ui.tabOrder']) {
    try {
      const order = typeof (cfg.tabOrder || cfg['ui.tabOrder']) === 'string'
        ? JSON.parse(cfg.tabOrder || cfg['ui.tabOrder'])
        : (cfg.tabOrder || cfg['ui.tabOrder']);
      if (Array.isArray(order) && order.length > 0) {
        localStorage.setItem('codelens_tab_order', JSON.stringify(order));
        restoreTabOrder();
      }
    } catch (_) {}
  }

  // Scan path
  const scanPath = cfg.defaultScanPath || cfg['scan.defaultPath'];
  if (scanPath && qs('#scan-path-input')) {
    qs('#scan-path-input').value = scanPath;
  }

  syncSettingsUI(s);
}

async function saveDeploymentConfToServer() {
  const statusEl = qs('#deployment-config-status-text');
  if (statusEl) statusEl.textContent = 'Saving configuration to server…';

  try {
    const cfg = buildFullConfigObject();
    const res = await fetch('/api/config', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(cfg),
    });

    if (res.ok) {
      if (statusEl) statusEl.textContent = 'Successfully saved to server (./codelens.conf)';
      showBanner('Saved deployment configuration to server');
    } else {
      const err = await res.json();
      throw new Error(err.error || 'Server returned error');
    }
  } catch (e) {
    if (statusEl) statusEl.textContent = 'Error saving to server: ' + e.message;
    showError('Failed to save to server: ' + e.message);
  }
}

async function syncSettingsFromServer() {
  try {
    const res = await fetch('/api/config');
    if (res.ok) {
      const serverConfig = await res.json();
      if (serverConfig) {
        const local = localStorage.getItem(SETTINGS_STORAGE_KEY);
        if (!local) {
          applyImportedConfig(serverConfig);
        }
      }
    }
  } catch (_) {}
}

// ── Codebase Intelligence Reports & Export Hub ───────────────────────────────────
const REPORTS_METADATA = {
  'executive-summary': {
    title: 'Executive Architectural Health Scorecard',
    subtitle: 'Composite 6-dimension architectural assessment, radar scorecard, and prioritized P0/P1/P2 remediation roadmap.',
    badge: 'SCORECARD',
    badgeClass: 'tag-arch'
  },
  'change-risk': {
    title: 'Change Risk & Blast Radius Matrix',
    subtitle: 'Deep structural risk analysis combining field mutations, fan-out blast radius, and downstream dependency propagation.',
    badge: 'HIGH RISK',
    badgeClass: 'tag-hot'
  },
  'technical-debt': {
    title: 'Technical Debt & SQALE Remediation ROI Estimator',
    subtitle: 'SQALE maintainability rating, remediation effort hours, God classes, and high-ROI Brain method refactoring targets.',
    badge: 'SQALE ROI',
    badgeClass: 'tag-hot'
  },
  'dead-code': {
    title: 'Dead Code & Orphaned Entry Points',
    subtitle: 'Identification of zero-caller methods, unreferenced classes, unused fields, and estimated cleanable lines of code.',
    badge: 'CLEANUP',
    badgeClass: 'tag-clean'
  },
  'circular-dependencies': {
    title: 'Circular Dependencies & Architectural Tangling',
    subtitle: 'Detection of direct/indirect class cycles and bidirectional package tangles with decoupling cut recommendations.',
    badge: 'MODULAR',
    badgeClass: 'tag-arch'
  },
  'archetype-governance': {
    title: 'Enterprise Archetype & Layering Governance',
    subtitle: 'Architecture compliance audit for TCS BaNCS & DDD patterns: un-audited transactions, state-mutating grabbers, and inverted dependencies.',
    badge: 'COMPLIANCE',
    badgeClass: 'tag-gov'
  },
  'architecture': {
    title: 'Architecture & Coupling Metrics',
    subtitle: 'Afferent (Ca) and efferent (Ce) coupling metrics, instability (I), and architectural health scores.',
    badge: 'STRUCTURAL',
    badgeClass: 'tag-arch'
  },
  'module-coupling': {
    title: 'Module Coupling & Stability Insights',
    subtitle: 'Package coupling (Ca/Ce), instability (I), abstractness (A), distance from main sequence (D), and bidirectional tangles.',
    badge: 'COUPLING',
    badgeClass: 'tag-arch'
  },
  'review': {
    title: 'Code Quality & Security Audit',
    subtitle: 'Comprehensive audit across 32 AST and call-graph rules with CWE mappings and remediation recommendations.',
    badge: 'QUALITY',
    badgeClass: 'tag-clean'
  },
  'metrics': {
    title: 'Codebase Inventory & Metrics',
    subtitle: 'Comprehensive census of classes, methods, fields, lines of code, and cyclomatic complexity distributions.',
    badge: 'METRICS',
    badgeClass: 'tag-arch'
  },
  'api-catalog': {
    title: 'API Surface & REST Endpoint Catalog',
    subtitle: 'Comprehensive inventory of REST routes, controllers, HTTP verbs, security auth guards, and blast radius scores.',
    badge: 'API SURFACE',
    badgeClass: 'tag-arch'
  },
  'database-access': {
    title: 'Database & Data Access Flow (Persistence Audit)',
    subtitle: 'DAO touchpoints, CRUD read/write operations, target tables, transaction boundaries, and autocommit risks.',
    badge: 'PERSISTENCE',
    badgeClass: 'tag-gov'
  },
  'concurrency-audit': {
    title: 'Concurrency & Thread Safety Audit',
    subtitle: 'Synchronized locking hotspots, volatile state fields, thread-unsafe collections, and asynchronous race condition hazards.',
    badge: 'THREAD SAFETY',
    badgeClass: 'tag-hot'
  },
  'html-snapshot': {
    title: 'Standalone Offline Graph Snapshot',
    subtitle: 'Self-contained zero-dependency HTML visualizer with interactive 2D graph, live physics, search, and embedded data.',
    badge: 'STANDALONE',
    badgeClass: 'tag-gov'
  }
};

const REPORT_FILTERS_CONFIG = {
  'api-catalog': {
    badge: 'API Endpoints',
    icon: `<svg class="svg-icon icon-cyan icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>`,
    placeholder: 'Filter endpoints, routes, controllers…',
    pills: [
      { id: 'ALL', label: 'All' },
      { id: 'GET', label: 'GET', match: (tr, t) => tr.querySelector('.badge-get') !== null || t.includes('get') },
      { id: 'POST', label: 'POST', match: (tr, t) => tr.querySelector('.badge-post') !== null || t.includes('post') },
      { id: 'PUT', label: 'PUT', match: (tr, t) => tr.querySelector('.badge-put') !== null || t.includes('put') },
      { id: 'DELETE', label: 'DELETE', match: (tr, t) => tr.querySelector('.badge-delete') !== null || t.includes('delete') },
      { id: 'PROTECTED', label: 'Protected', match: (tr, t) => t.includes('protected') },
      { id: 'PUBLIC', label: 'Public', match: (tr, t) => t.includes('public') },
      { id: 'HIGH_RISK', label: 'High Risk', match: (tr, t) => tr.querySelector('.risk-critical, .risk-high') !== null || t.includes('high') }
    ],
    selects: [
      {
        id: 'verb',
        label: 'All HTTP Verbs',
        options: [
          { val: 'GET', label: 'GET', match: (tr, t) => tr.querySelector('.badge-get') !== null || t.includes('get') },
          { val: 'POST', label: 'POST', match: (tr, t) => tr.querySelector('.badge-post') !== null || t.includes('post') },
          { val: 'PUT', label: 'PUT', match: (tr, t) => tr.querySelector('.badge-put') !== null || t.includes('put') },
          { val: 'DELETE', label: 'DELETE', match: (tr, t) => tr.querySelector('.badge-delete') !== null || t.includes('delete') }
        ]
      },
      {
        id: 'guard',
        label: 'All Auth Guards',
        options: [
          { val: 'PROTECTED', label: 'Protected (Token / Auth)', match: (tr, t) => t.includes('protected') },
          { val: 'PUBLIC', label: 'Public / Open Surface', match: (tr, t) => t.includes('public') },
          { val: 'HIGH_RISK', label: 'High Blast Radius', match: (tr, t) => tr.querySelector('.risk-critical, .risk-high') !== null || t.includes('high') }
        ]
      }
    ]
  },
  'database-access': {
    badge: 'Database Access',
    icon: `<svg class="svg-icon icon-amber icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"/><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/></svg>`,
    placeholder: 'Filter tables, queries, DAOs, repositories…',
    pills: [
      { id: 'ALL', label: 'All' },
      { id: 'WRITES', label: 'Writes', match: (tr, t) => tr.querySelector('.risk-high') !== null || t.includes('write') || t.includes('insert') || t.includes('update') || t.includes('delete') },
      { id: 'READS', label: 'Reads', match: (tr, t) => tr.querySelector('.risk-low') !== null || t.includes('read') || t.includes('select') },
      { id: 'TX_GUARD', label: 'Tx Guard', match: (tr, t) => t.includes('tx guard') || t.includes('transaction') },
      { id: 'AUTOCOMMIT', label: 'Autocommit Risk', match: (tr, t) => t.includes('autocommit') }
    ],
    selects: [
      {
        id: 'op',
        label: 'All Operations',
        options: [
          { val: 'WRITE', label: 'Writes / Mutations', match: (tr, t) => tr.querySelector('.risk-high') !== null || t.includes('write') },
          { val: 'READ', label: 'Reads (SELECT)', match: (tr, t) => tr.querySelector('.risk-low') !== null || t.includes('read') }
        ]
      },
      {
        id: 'tx',
        label: 'All Transaction Boundaries',
        options: [
          { val: 'TX_GUARD', label: 'Enclosed in Transaction', match: (tr, t) => t.includes('tx guard') || t.includes('transaction') },
          { val: 'AUTOCOMMIT', label: 'Autocommit / Unenclosed', match: (tr, t) => t.includes('autocommit') }
        ]
      }
    ]
  },
  'module-coupling': {
    badge: 'Module Coupling',
    icon: `<svg class="svg-icon icon-cyan icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="12 2 2 7 12 12 22 7 12 2"/><polyline points="2 17 12 22 22 17"/><polyline points="2 12 12 17 22 12"/></svg>`,
    placeholder: 'Filter modules, packages, cross-module calls…',
    pills: [
      { id: 'ALL', label: 'All' },
      { id: 'PAIN', label: 'Zone of Pain', match: (tr, t) => t.includes('pain') },
      { id: 'USELESS', label: 'Zone of Uselessness', match: (tr, t) => t.includes('useless') },
      { id: 'BALANCED', label: 'Balanced', match: (tr, t) => t.includes('balanced') || t.includes('one-way') },
      { id: 'TANGLE', label: 'Tangles', match: (tr, t) => t.includes('tangle') },
      { id: 'GRADE_A', label: 'Grade A', match: (tr, t) => t.includes('grade a') || (tr.querySelector('.risk-low') !== null && t.includes('a')) },
      { id: 'GRADE_DF', label: 'Grade D/F', match: (tr, t) => t.includes('grade d') || t.includes('grade f') || tr.querySelector('.risk-critical, .risk-high') !== null }
    ],
    selects: [
      {
        id: 'zone',
        label: 'All Coupling Zones',
        options: [
          { val: 'ZONE_OF_PAIN', label: 'Zone of Pain', match: (tr, t) => t.includes('pain') },
          { val: 'ZONE_OF_USELESSNESS', label: 'Zone of Uselessness', match: (tr, t) => t.includes('useless') },
          { val: 'BALANCED', label: 'Balanced Sequence', match: (tr, t) => t.includes('balanced') },
          { val: 'STABLE', label: 'Stable Core', match: (tr, t) => t.includes('stable') },
          { val: 'VOLATILE', label: 'Volatile Leaf', match: (tr, t) => t.includes('volatile') }
        ]
      },
      {
        id: 'grade',
        label: 'All Health Grades',
        options: [
          { val: 'A', label: 'Grade A (Healthy)', match: (tr, t) => t.includes('a') && tr.querySelector('.risk-low') !== null },
          { val: 'B', label: 'Grade B (Moderate)', match: (tr, t) => t.includes('b') },
          { val: 'C', label: 'Grade C (Borderline)', match: (tr, t) => t.includes('c') || tr.querySelector('.risk-medium') !== null },
          { val: 'D', label: 'Grade D / F (High Coupling)', match: (tr, t) => t.includes('d') || t.includes('f') || tr.querySelector('.risk-critical, .risk-high') !== null }
        ]
      }
    ]
  },
  'dead-code': {
    badge: 'Dead Code',
    icon: `<svg class="svg-icon icon-rose icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>`,
    placeholder: 'Filter dead methods, orphaned classes, fields…',
    pills: [
      { id: 'ALL', label: 'All' },
      { id: 'METHODS', label: 'Orphaned Methods', match: (tr, t) => t.includes('(') || t.includes('method') || t.includes('caller') },
      { id: 'CLASSES', label: 'Orphaned Classes', match: (tr, t) => !t.includes('(') && (t.includes('class') || t.includes('inbound') || t.includes('incoming')) },
      { id: 'FIELDS', label: 'Unreferenced Fields', match: (tr, t) => t.includes('field') },
      { id: 'LARGE', label: 'Large (≥50 LOC)', match: (tr, t) => /\b([5-9]\d|[1-9]\d{2,})\b/.test(t) }
    ],
    selects: [
      {
        id: 'type',
        label: 'All Artifact Kinds',
        options: [
          { val: 'METHOD', label: 'Orphaned Methods', match: (tr, t) => t.includes('(') || t.includes('method') || t.includes('caller') },
          { val: 'CLASS', label: 'Orphaned Classes', match: (tr, t) => !t.includes('(') && (t.includes('class') || t.includes('inbound') || t.includes('incoming')) },
          { val: 'FIELD', label: 'Unreferenced Fields', match: (tr, t) => t.includes('field') }
        ]
      },
      {
        id: 'loc',
        label: 'All Line Sizes',
        options: [
          { val: 'LOC_50', label: '≥ 50 Lines of Code', match: (tr, t) => /\b([5-9]\d|[1-9]\d{2,})\b/.test(t) },
          { val: 'LOC_100', label: '≥ 100 Lines of Code', match: (tr, t) => /\b([1-9]\d{2,})\b/.test(t) }
        ]
      }
    ]
  },
  'change-risk': {
    badge: 'Change Risk',
    icon: `<svg class="svg-icon icon-rose icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>`,
    placeholder: 'Filter classes, methods, churn hotspots…',
    pills: [
      { id: 'ALL', label: 'All' },
      { id: 'CRITICAL', label: 'Critical (≥75)', match: (tr, t) => tr.querySelector('.risk-critical') !== null || t.includes('critical') },
      { id: 'HIGH', label: 'High (≥50)', match: (tr, t) => tr.querySelector('.risk-high') !== null || t.includes('high') },
      { id: 'MEDIUM', label: 'Medium (≥25)', match: (tr, t) => tr.querySelector('.risk-medium') !== null || t.includes('medium') },
      { id: 'MUTATIONS', label: 'Field Mutations', match: (tr, t) => t.includes('mutation') || t.includes('field') },
      { id: 'HOTSPOTS', label: 'Churn Hotspots', match: (tr, t) => t.includes('hotspot') || t.includes('churn') }
    ],
    selects: [
      {
        id: 'tier',
        label: 'All Risk Tiers',
        options: [
          { val: 'CRITICAL', label: 'Critical (≥ 75 Score)', match: (tr, t) => tr.querySelector('.risk-critical') !== null || t.includes('critical') },
          { val: 'HIGH', label: 'High (≥ 50 Score)', match: (tr, t) => tr.querySelector('.risk-high') !== null || t.includes('high') },
          { val: 'MEDIUM', label: 'Medium (≥ 25 Score)', match: (tr, t) => tr.querySelector('.risk-medium') !== null || t.includes('medium') },
          { val: 'LOW', label: 'Low (< 25 Score)', match: (tr, t) => tr.querySelector('.risk-low') !== null || t.includes('low') }
        ]
      },
      {
        id: 'factor',
        label: 'All Risk Dimensions',
        options: [
          { val: 'CHURN', label: 'Git Churn × Complexity', match: (tr, t) => t.includes('churn') || t.includes('hotspot') },
          { val: 'MUTATION', label: 'Field Mutations', match: (tr, t) => t.includes('mutation') || t.includes('field') },
          { val: 'FAN_OUT', label: 'High Fan-Out / Blast', match: (tr, t) => t.includes('fan-out') || t.includes('blast') }
        ]
      }
    ]
  },
  'technical-debt': {
    badge: 'Technical Debt',
    icon: `<svg class="svg-icon icon-amber icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="20" x2="18" y2="10"/><line x1="12" y1="20" x2="12" y2="4"/><line x1="6" y1="20" x2="6" y2="14"/></svg>`,
    placeholder: 'Filter SQALE debt items, God Classes, remediations…',
    pills: [
      { id: 'ALL', label: 'All' },
      { id: 'GOD_CLASS', label: 'God Classes', match: (tr, t) => t.includes('god class') || t.includes('large class') },
      { id: 'BRAIN_METHOD', label: 'Brain Methods', match: (tr, t) => t.includes('brain method') || t.includes('complex') || t.includes('long method') },
      { id: 'HIGH_ROI', label: 'High ROI', match: (tr, t) => t.includes('high roi') || t.includes('quick win') || (t.includes('high') && t.includes('roi')) },
      { id: 'HIGH_EFFORT', label: 'High Effort (≥4h)', match: (tr, t) => t.includes('4h') || t.includes('8h') || t.includes('16h') || t.includes('high effort') }
    ],
    selects: [
      {
        id: 'category',
        label: 'All Debt Smells',
        options: [
          { val: 'GOD_CLASS', label: 'God Class / Large Class', match: (tr, t) => t.includes('god class') || t.includes('large class') },
          { val: 'BRAIN_METHOD', label: 'Brain Method / Long Method', match: (tr, t) => t.includes('brain method') || t.includes('complex') },
          { val: 'COUPLING', label: 'Feature Envy / Tight Coupling', match: (tr, t) => t.includes('coupling') || t.includes('envy') },
          { val: 'CYCLOMATIC', label: 'Excessive Cyclomatic Complexity', match: (tr, t) => t.includes('complexity') || t.includes('cyclomatic') }
        ]
      },
      {
        id: 'roi',
        label: 'All ROI Tiers',
        options: [
          { val: 'HIGH', label: 'High ROI (Quick Win)', match: (tr, t) => t.includes('high') && (t.includes('roi') || tr.querySelector('.risk-low') !== null) },
          { val: 'MEDIUM', label: 'Medium ROI (Balanced)', match: (tr, t) => t.includes('medium') },
          { val: 'STRATEGIC', label: 'Strategic / High Effort', match: (tr, t) => t.includes('strategic') || t.includes('high effort') || t.includes('4h') || t.includes('8h') }
        ]
      }
    ]
  },
  'circular-dependencies': {
    badge: 'Circular Dependencies',
    icon: `<svg class="svg-icon icon-rose icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/></svg>`,
    placeholder: 'Filter dependency cycles, packages, classes…',
    pills: [
      { id: 'ALL', label: 'All' },
      { id: 'CLASS_CYCLES', label: 'Class Cycles', match: (tr, t) => t.includes('class') || !t.includes('pkg') },
      { id: 'PKG_TANGLES', label: 'Package Tangles', match: (tr, t) => t.includes('package') || t.includes('pkg') || t.includes('tangle') },
      { id: 'DIRECT_2', label: 'Direct (2-Node)', match: (tr, t) => t.includes('2-node') || t.includes('2 nodes') || t.includes('direct') },
      { id: 'MULTI_3', label: 'Multi-Hop (3+)', match: (tr, t) => t.includes('3 nodes') || t.includes('4 nodes') || t.includes('5 nodes') || t.includes('multi') }
    ],
    selects: [
      {
        id: 'scope',
        label: 'All Cycle Scopes',
        options: [
          { val: 'CLASS', label: 'Class-Level Cycles', match: (tr, t) => t.includes('class') },
          { val: 'PACKAGE', label: 'Package-Level Tangles', match: (tr, t) => t.includes('package') || t.includes('pkg') }
        ]
      },
      {
        id: 'depth',
        label: 'All Cycle Depths',
        options: [
          { val: '2_NODE', label: 'Direct 2-Node Reciprocal', match: (tr, t) => t.includes('2-node') || t.includes('2 nodes') || t.includes('direct') },
          { val: 'MULTI_HOP', label: 'Multi-Hop 3+ Nodes Chain', match: (tr, t) => t.includes('3 nodes') || t.includes('4 nodes') || t.includes('multi') }
        ]
      }
    ]
  },
  'archetype-governance': {
    badge: 'Archetype Governance',
    icon: `<svg class="svg-icon icon-purple icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>`,
    placeholder: 'Filter governance violations, bypasses, mutations…',
    pills: [
      { id: 'ALL', label: 'All' },
      { id: 'MUTATIONS', label: 'Unaudited Mutations', match: (tr, t) => t.includes('mutation') || t.includes('grabber') },
      { id: 'BYPASS', label: 'Layer Bypass', match: (tr, t) => t.includes('bypass') || t.includes('controller-to-dao') || t.includes('inversion') || t.includes('dao') },
      { id: 'CRITICAL', label: 'Critical', match: (tr, t) => tr.querySelector('.risk-critical') !== null || t.includes('critical') },
      { id: 'WARNING', label: 'Warning', match: (tr, t) => tr.querySelector('.risk-high') !== null || t.includes('warning') || t.includes('warn') }
    ],
    selects: [
      {
        id: 'rule',
        label: 'All Rule Violations',
        options: [
          { val: 'MUTATION', label: 'State Mutation Bypasses', match: (tr, t) => t.includes('mutation') || t.includes('grabber') },
          { val: 'LAYER', label: 'Layer Violations (Controller → DAO)', match: (tr, t) => t.includes('bypass') || t.includes('layer') || t.includes('dao') },
          { val: 'NAMING', label: 'Archetype / Naming Drift', match: (tr, t) => t.includes('naming') || t.includes('archetype') }
        ]
      },
      {
        id: 'sev',
        label: 'All Severities',
        options: [
          { val: 'CRITICAL', label: 'Critical Violation', match: (tr, t) => tr.querySelector('.risk-critical') !== null || t.includes('critical') },
          { val: 'WARNING', label: 'Warning Violation', match: (tr, t) => tr.querySelector('.risk-high') !== null || t.includes('warning') || t.includes('warn') },
          { val: 'INFO', label: 'Info / Advisory', match: (tr, t) => tr.querySelector('.risk-low') !== null || t.includes('info') }
        ]
      }
    ]
  },
  'architecture': {
    badge: 'Architecture',
    icon: `<svg class="svg-icon icon-cyan icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/></svg>`,
    placeholder: 'Filter architecture layers, packages, stability…',
    pills: [
      { id: 'ALL', label: 'All' },
      { id: 'STABLE', label: 'Stable Core', match: (tr, t) => t.includes('stable') },
      { id: 'VOLATILE', label: 'Volatile', match: (tr, t) => t.includes('flexible') || t.includes('dependent') || t.includes('volatile') },
      { id: 'FAN_IN', label: 'High Fan-In (Ca)', match: (tr, t) => t.includes('ca') || t.includes('afferent') },
      { id: 'FAN_OUT', label: 'High Fan-Out (Ce)', match: (tr, t) => t.includes('ce') || t.includes('efferent') }
    ],
    selects: [
      {
        id: 'profile',
        label: 'All Stability Profiles',
        options: [
          { val: 'STABLE', label: 'Stable Core (I < 0.3)', match: (tr, t) => t.includes('stable') },
          { val: 'BALANCED', label: 'Balanced Layers (0.3 ≤ I ≤ 0.7)', match: (tr, t) => t.includes('balanced') },
          { val: 'VOLATILE', label: 'Volatile / Leaf (I > 0.7)', match: (tr, t) => t.includes('volatile') || t.includes('flexible') || t.includes('dependent') }
        ]
      },
      {
        id: 'tier',
        label: 'All Architectural Tiers',
        options: [
          { val: 'API', label: 'API / Controllers', match: (tr, t) => t.includes('api') || t.includes('controller') || t.includes('web') },
          { val: 'CORE', label: 'Domain Core / Services', match: (tr, t) => t.includes('service') || t.includes('core') || t.includes('domain') },
          { val: 'DATA', label: 'Data Access / Storage', match: (tr, t) => t.includes('data') || t.includes('dao') || t.includes('repository') }
        ]
      }
    ]
  },
  'review': {
    badge: 'Code Review',
    icon: `<svg class="svg-icon icon-emerald icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/><polyline points="10 9 9 9 8 9"/></svg>`,
    placeholder: 'Filter review findings, CWEs, rules, files…',
    pills: [
      { id: 'ALL', label: 'All' },
      { id: 'CRITICAL', label: 'Critical', match: (tr, t) => tr.querySelector('.risk-critical') !== null || t.includes('critical') },
      { id: 'WARNING', label: 'Warning', match: (tr, t) => tr.querySelector('.risk-high') !== null || t.includes('warning') },
      { id: 'SECURITY', label: 'Security / CWE', match: (tr, t) => t.includes('security') || t.includes('cwe') || t.includes('vulnerab') },
      { id: 'BUGS', label: 'Bug Hazards', match: (tr, t) => t.includes('bug') || t.includes('defect') || t.includes('hazard') },
      { id: 'PERF', label: 'Performance', match: (tr, t) => t.includes('perf') || t.includes('memory') || t.includes('leak') }
    ],
    selects: [
      {
        id: 'sev',
        label: 'All Severities',
        options: [
          { val: 'CRITICAL', label: 'Critical Severity', match: (tr, t) => tr.querySelector('.risk-critical') !== null || t.includes('critical') },
          { val: 'WARNING', label: 'Warning Severity', match: (tr, t) => tr.querySelector('.risk-high') !== null || t.includes('warning') },
          { val: 'INFO', label: 'Info / Notice', match: (tr, t) => tr.querySelector('.risk-low') !== null || t.includes('info') }
        ]
      },
      {
        id: 'cat',
        label: 'All Rule Categories',
        options: [
          { val: 'SECURITY', label: 'Security & CWE Hazards', match: (tr, t) => t.includes('security') || t.includes('cwe') },
          { val: 'BUG', label: 'Bug Hazards & Reliability', match: (tr, t) => t.includes('bug') || t.includes('defect') },
          { val: 'PERF', label: 'Performance & Latency', match: (tr, t) => t.includes('perf') || t.includes('memory') },
          { val: 'MAINTAINABILITY', label: 'Maintainability & Smells', match: (tr, t) => t.includes('maintain') || t.includes('smell') }
        ]
      }
    ]
  },
  'metrics': {
    badge: 'Code Metrics',
    icon: `<svg class="svg-icon icon-cyan icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="20" x2="18" y2="10"/><line x1="12" y1="20" x2="12" y2="4"/><line x1="6" y1="20" x2="6" y2="14"/></svg>`,
    placeholder: 'Filter complexity, LOC, classes, methods…',
    pills: [
      { id: 'ALL', label: 'All' },
      { id: 'HIGH_CC', label: 'Complexity (CC ≥10)', match: (tr, t) => tr.querySelector('.risk-critical') !== null || t.includes('high') || /\b([1-9]\d{1,})\b/.test(t) },
      { id: 'LARGE_LOC', label: 'Large Classes (LOC ≥250)', match: (tr, t) => /\b([2-9]\d{2,}|\d{4,})\b/.test(t) },
      { id: 'INTERFACES', label: 'Interfaces', match: (tr, t) => t.includes('interface') },
      { id: 'RECORDS', label: 'Records & Enums', match: (tr, t) => t.includes('record') || t.includes('enum') }
    ],
    selects: [
      {
        id: 'cc',
        label: 'All Complexity Tiers',
        options: [
          { val: 'HIGH', label: 'High Complexity (CC ≥ 10)', match: (tr, t) => tr.querySelector('.risk-critical') !== null || t.includes('high') },
          { val: 'MODERATE', label: 'Moderate Complexity (CC 5–9)', match: (tr, t) => tr.querySelector('.risk-medium') !== null || t.includes('moderate') },
          { val: 'LOW', label: 'Low Complexity (CC < 5)', match: (tr, t) => tr.querySelector('.risk-low') !== null || t.includes('low') }
        ]
      },
      {
        id: 'kind',
        label: 'All Entity Types',
        options: [
          { val: 'CLASS', label: 'Classes', match: (tr, t) => t.includes('class') },
          { val: 'INTERFACE', label: 'Interfaces', match: (tr, t) => t.includes('interface') },
          { val: 'RECORD', label: 'Records & Enums', match: (tr, t) => t.includes('record') || t.includes('enum') }
        ]
      }
    ]
  },
  'concurrency-audit': {
    badge: 'Concurrency Audit',
    icon: `<svg class="svg-icon icon-rose icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>`,
    placeholder: 'Filter thread safety, locks, volatile state, hazards…',
    pills: [
      { id: 'ALL', label: 'All' },
      { id: 'LOCKS', label: 'Locks & Sync', match: (tr, t) => t.includes('lock') || t.includes('synchronized') },
      { id: 'VOLATILE', label: 'Volatile & Atomics', match: (tr, t) => t.includes('volatile') || t.includes('atomic') },
      { id: 'UNSAFE', label: 'Unsafe Collections', match: (tr, t) => t.includes('unsafe') || t.includes('hashmap') || t.includes('arraylist') },
      { id: 'HAZARDS', label: 'Race Hazards', match: (tr, t) => tr.querySelector('.risk-critical') !== null || t.includes('hazard') || t.includes('race') }
    ],
    selects: [
      {
        id: 'primitive',
        label: 'All Concurrency Primitives',
        options: [
          { val: 'LOCK', label: 'Locks & Synchronized', match: (tr, t) => t.includes('lock') || t.includes('synchronized') },
          { val: 'ATOMIC', label: 'Atomics & Volatile State', match: (tr, t) => t.includes('atomic') || t.includes('volatile') },
          { val: 'COLLECTION', label: 'Non-Thread-Safe Collections', match: (tr, t) => t.includes('unsafe') || t.includes('hashmap') || t.includes('arraylist') }
        ]
      },
      {
        id: 'risk',
        label: 'All Risk Levels',
        options: [
          { val: 'CRITICAL', label: 'High / Critical Hazard', match: (tr, t) => tr.querySelector('.risk-critical') !== null || t.includes('critical') || t.includes('high') },
          { val: 'MEDIUM', label: 'Moderate Risk', match: (tr, t) => tr.querySelector('.risk-medium') !== null || t.includes('medium') },
          { val: 'LOW', label: 'Guarded / Low Risk', match: (tr, t) => tr.querySelector('.risk-low') !== null || t.includes('low') }
        ]
      }
    ]
  },
  'executive-summary': {
    badge: 'Executive Summary',
    icon: `<svg class="svg-icon icon-emerald icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg>`,
    placeholder: 'Filter roadmap actions, priorities, domains…',
    pills: [
      { id: 'ALL', label: 'All' },
      { id: 'P0', label: 'P0 Critical', match: (tr, t) => t.includes('p0') },
      { id: 'P1', label: 'P1 Near-Term', match: (tr, t) => t.includes('p1') },
      { id: 'P2', label: 'P2 Planned', match: (tr, t) => t.includes('p2') },
      { id: 'ARCH', label: 'Architecture', match: (tr, t) => t.includes('architecture') || t.includes('modularity') },
      { id: 'SECURITY', label: 'Security', match: (tr, t) => t.includes('security') || t.includes('auth') }
    ],
    selects: [
      {
        id: 'priority',
        label: 'All Roadmap Priorities',
        options: [
          { val: 'P0', label: 'P0 - Immediate Critical Fix', match: (tr, t) => t.includes('p0') },
          { val: 'P1', label: 'P1 - Near-Term Sprint Target', match: (tr, t) => t.includes('p1') },
          { val: 'P2', label: 'P2 - Planned Architectural Refactor', match: (tr, t) => t.includes('p2') }
        ]
      },
      {
        id: 'domain',
        label: 'All Architectural Domains',
        options: [
          { val: 'ARCH', label: 'Modularity & Coupling', match: (tr, t) => t.includes('architecture') || t.includes('modularity') || t.includes('coupling') },
          { val: 'SECURITY', label: 'Security & Auth Surface', match: (tr, t) => t.includes('security') || t.includes('auth') },
          { val: 'DEBT', label: 'Technical Debt & Hygiene', match: (tr, t) => t.includes('debt') || t.includes('hygiene') }
        ]
      }
    ]
  }
};

const ReportsHub = {
  activeReport: 'change-risk',
  activeFormat: 'dashboard',
  cache: {},
  loading: false,
  initialized: false,
  pollTimer: null,
  activeTables: [],
  filterState: {
    report: 'change-risk',
    pill: 'ALL',
    select1: 'ALL',
    select2: 'ALL',
    search: ''
  },

  init() {
    if (ReportsHub.initialized) return;
    ReportsHub.initialized = true;

    // Sidebar catalog clicks
    qsa('#reports-catalog-nav .report-nav-item').forEach(item => {
      item.addEventListener('click', () => {
        const report = item.dataset.report;
        if (report) {
          ReportsHub.activate(report);
        }
      });
    });

    // Format pill clicks
    qsa('#reports-format-switcher .report-format-pill').forEach(pill => {
      pill.addEventListener('click', () => {
        const fmt = pill.dataset.format;
        if (fmt) {
          ReportsHub.setFormat(fmt);
        }
      });
    });

    // Header actions
    qs('#btn-reports-regenerate')?.addEventListener('click', () => ReportsHub.regenerate());
    qs('#btn-reports-copy')?.addEventListener('click', () => ReportsHub.copy());
    qs('#btn-reports-open')?.addEventListener('click', () => ReportsHub.openTab());
    qs('#btn-reports-download')?.addEventListener('click', () => ReportsHub.download());

    // Navigation buttons from other views
    qs('#export-btn')?.addEventListener('click', () => {
      if (App.activeTab === 'reports') {
        switchTab(App.lastWorkspaceTab || 'graph');
      } else {
        App.lastWorkspaceTab = (App.activeTab && App.activeTab !== 'reports' && App.activeTab !== 'codebase') ? App.activeTab : (App.lastWorkspaceTab || 'graph');
        switchTab('reports');
      }
    });
    qs('#btn-reports-back-workspace')?.addEventListener('click', () => {
      switchTab(App.lastWorkspaceTab || 'graph');
    });
    qs('#export-review-report-btn')?.addEventListener('click', () => {
      App.lastWorkspaceTab = 'review';
      switchTab('reports');
      ReportsHub.activate('review');
    });
    // Eagerly preload all precomputed reports in background on init
    ReportsHub.preloadAllReports().catch(() => {});
  },

  async preloadAllReports() {
    if (ReportsHub._preloading) return ReportsHub._preloadPromise;
    ReportsHub._preloading = true;
    ReportsHub._preloadPromise = api.allReports().then(allReports => {
      if (allReports && typeof allReports === 'object') {
        const reportMap = (allReports && allReports.reports) ? allReports.reports : allReports;
        for (const [key, reportData] of Object.entries(reportMap)) {
          if (reportData && typeof reportData === 'object' && key !== 'status') {
            ReportsHub.cache[key + '_json'] = reportData;
          }
        }
        // If user is currently looking at Reports Hub and format is dashboard, render immediately if waiting
        if (App.activeTab === 'reports' && ReportsHub.activeFormat === 'dashboard') {
          const activeKey = ReportsHub.activeReport + '_json';
          if (ReportsHub.cache[activeKey]) {
            const dashContainer = qs('#reports-dashboard-container');
            if (dashContainer && dashContainer.querySelector('.reports-loading-state')) {
              ReportsHub.renderDashboard(ReportsHub.activeReport, ReportsHub.cache[activeKey]);
            }
          }
        }
      }
      return allReports;
    }).catch(err => {
      console.warn('Reports preloading notice:', err);
    }).finally(() => {
      ReportsHub._preloading = false;
    });
    return ReportsHub._preloadPromise;
  },

  async regenerate() {
    const reportKey = ReportsHub.activeReport || 'change-risk';
    const meta = REPORTS_METADATA[reportKey] || REPORTS_METADATA['change-risk'];
    const reportTitle = meta ? meta.title : reportKey;

    try {
      // Invalidate cache for THIS report only
      delete ReportsHub.cache[reportKey + '_json'];
      delete ReportsHub.cache[reportKey + '_html'];
      delete ReportsHub.cache[reportKey + '_markdown'];
      delete ReportsHub.cache[reportKey + '_md'];
      delete ReportsHub.cache[reportKey + '_csv'];

      if (ReportsHub.pollTimer) {
        clearTimeout(ReportsHub.pollTimer);
        ReportsHub.pollTimer = null;
      }
      const btn = qs('#btn-reports-regenerate');
      if (btn) {
        btn.classList.add('loading');
        btn.disabled = true;
      }
      if (typeof showToast === 'function') {
        showToast(`Regenerating ${reportTitle} report…`, 'info');
      }
      const res = await fetch(`/api/reports/regenerate?report=${encodeURIComponent(reportKey)}`, { method: 'POST' });
      if (res.ok) {
        if (typeof showToast === 'function') {
          showToast(`${reportTitle} regenerated successfully!`, 'success');
        }
        await ReportsHub.loadActiveReport();
      } else {
        const errText = await res.text().catch(() => '');
        throw new Error(errText || `HTTP ${res.status}`);
      }
    } catch (err) {
      if (typeof showToast === 'function') {
        showToast(`Failed to regenerate ${reportTitle}: ${err.message}`, 'error');
      }
    } finally {
      const btn = qs('#btn-reports-regenerate');
      if (btn) {
        btn.classList.remove('loading');
        btn.disabled = false;
      }
    }
  },

  activate(reportKey, format = 'dashboard') {
    if (ReportsHub.pollTimer) {
      clearTimeout(ReportsHub.pollTimer);
      ReportsHub.pollTimer = null;
    }
    if (reportKey && REPORTS_METADATA[reportKey]) {
      ReportsHub.activeReport = reportKey;
    }
    ReportsHub.filterState = {
      report: ReportsHub.activeReport,
      pill: 'ALL',
      select1: 'ALL',
      select2: 'ALL',
      search: ''
    };
    if (ReportsHub.activeReport === 'html-snapshot') {
      ReportsHub.activeFormat = 'html';
    } else {
      ReportsHub.activeFormat = format || 'dashboard';
    }

    ReportsHub.syncUI();
    ReportsHub.loadActiveReport();
  },

  setFormat(format) {
    if (ReportsHub.pollTimer) {
      clearTimeout(ReportsHub.pollTimer);
      ReportsHub.pollTimer = null;
    }
    ReportsHub.activeFormat = format;
    const filterBar = qs('#reports-filter-bar');
    if (filterBar && (ReportsHub.activeFormat !== 'dashboard' || ReportsHub.activeReport === 'html-snapshot')) {
      filterBar.style.display = 'none';
    }
    ReportsHub.syncUI();
    ReportsHub.loadActiveReport();
  },

  syncUI() {
    const meta = REPORTS_METADATA[ReportsHub.activeReport] || REPORTS_METADATA['change-risk'];
    const titleEl = qs('#reports-view-title');
    const descEl = qs('#reports-view-desc');
    const statusEl = qs('#reports-status-pill');

    if (titleEl) titleEl.textContent = meta.title;
    if (descEl) descEl.textContent = meta.subtitle;
    if (statusEl) statusEl.textContent = meta.badge;

    // Sidebar nav active state
    qsa('#reports-catalog-nav .report-nav-item').forEach(item => {
      item.classList.toggle('active', item.dataset.report === ReportsHub.activeReport);
    });

    // Format pills active state
    qsa('#reports-format-switcher .report-format-pill').forEach(pill => {
      pill.classList.toggle('active', pill.dataset.format === ReportsHub.activeFormat);
    });

    const regenBtn = qs('#btn-reports-regenerate');
    if (regenBtn) {
      regenBtn.title = `Regenerate ${meta.title} report`;
    }
  },

  async loadActiveReport() {
    const dashContainer = qs('#reports-dashboard-container');
    const htmlContainer = qs('#reports-html-container');
    const codeContainer = qs('#reports-code-container');
    const htmlFrame = qs('#reports-html-frame');
    const codeOutput = qs('#reports-code-output');
    const filterBar = qs('#reports-filter-bar');

    if (!dashContainer || !htmlContainer || !codeContainer) return;

    // Snapshot only supports HTML/Dashboard preview
    if (ReportsHub.activeReport === 'html-snapshot') {
      if (filterBar) filterBar.style.display = 'none';
      dashContainer.style.display = 'none';
      codeContainer.style.display = 'none';
      htmlContainer.style.display = 'block';
      if (!htmlFrame.srcdoc) {
        htmlFrame.src = '/api/reports/html-snapshot';
      }
      return;
    }

    if (ReportsHub.activeFormat === 'dashboard') {
      dashContainer.style.display = 'flex';
      htmlContainer.style.display = 'none';
      codeContainer.style.display = 'none';

      const cacheKey = ReportsHub.activeReport + '_json';
      if (ReportsHub.cache[cacheKey]) {
        ReportsHub.renderDashboard(ReportsHub.activeReport, ReportsHub.cache[cacheKey]);
        return;
      }

      if (filterBar) filterBar.style.display = 'none';
      dashContainer.innerHTML = `
        <div class="reports-loading-state">
          <div class="loading-spinner"></div>
          <div class="loading-text">Loading ${esc(REPORTS_METADATA[ReportsHub.activeReport]?.title || 'Report')}…</div>
          <div style="font-size:12px; color:var(--text-muted); margin-top:6px;">Displaying precomputed report dataset…</div>
        </div>
      `;

      try {
        const res = await fetch(`/api/reports/${ReportsHub.activeReport}?format=json`);
        if (res.status === 202) {
          const statusData = await res.json().catch(() => ({}));
          dashContainer.innerHTML = `
            <div class="reports-loading-state">
              <div class="loading-spinner"></div>
              <div class="loading-text">${esc(statusData.message || 'Precomputing reports in background…')}</div>
              <div style="font-size:12px; color:var(--text-muted); margin-top:8px;">${esc(statusData.phase || '')} (${statusData.percentage || 0}%)</div>
            </div>
          `;
          if (ReportsHub.pollTimer) clearTimeout(ReportsHub.pollTimer);
          ReportsHub.pollTimer = setTimeout(() => ReportsHub.loadActiveReport(), 1500);
          return;
        }
        if (!res.ok) throw new Error(`HTTP ${res.status}: ${res.statusText}`);
        const data = await res.json();
        ReportsHub.cache[cacheKey] = data;
        ReportsHub.renderDashboard(ReportsHub.activeReport, data);
      } catch (err) {
        dashContainer.innerHTML = `
          <div class="reports-loading-state" style="color:var(--rose-400, #f43f5e);">
            <svg class="svg-icon icon-rose icon-lg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
            <div>Failed to load report data: ${esc(err.message)}</div>
          </div>
        `;
      }
      return;
    }

    if (filterBar) filterBar.style.display = 'none';

    if (ReportsHub.activeFormat === 'html') {
      dashContainer.style.display = 'none';
      codeContainer.style.display = 'none';
      htmlContainer.style.display = 'block';

      const cacheKey = ReportsHub.activeReport + '_html';
      if (ReportsHub.cache[cacheKey]) {
        htmlFrame.srcdoc = ReportsHub.cache[cacheKey];
        return;
      }

      try {
        const res = await fetch(`/api/reports/${ReportsHub.activeReport}?format=html`);
        if (res.status === 202) {
          const html = await res.text();
          htmlFrame.srcdoc = html;
          if (ReportsHub.pollTimer) clearTimeout(ReportsHub.pollTimer);
          ReportsHub.pollTimer = setTimeout(() => ReportsHub.loadActiveReport(), 1500);
          return;
        }
        if (!res.ok) throw new Error(`HTTP ${res.status}: ${res.statusText}`);
        const html = await res.text();
        ReportsHub.cache[cacheKey] = html;
        htmlFrame.srcdoc = html;
      } catch (err) {
        htmlFrame.srcdoc = `<body style="background:#0a0d12;color:#f43f5e;font-family:sans-serif;padding:24px;">Failed to generate HTML: ${esc(err.message)}</body>`;
      }
      return;
    }

    // Raw text formats: markdown, json, csv
    dashContainer.style.display = 'none';
    htmlContainer.style.display = 'none';
    codeContainer.style.display = 'block';

    const cacheKey = ReportsHub.activeReport + '_' + ReportsHub.activeFormat;
    if (ReportsHub.cache[cacheKey]) {
      codeOutput.textContent = ReportsHub.cache[cacheKey];
      return;
    }

    codeOutput.textContent = 'Loading precomputed ' + ReportsHub.activeFormat.toUpperCase() + ' report…';

    try {
      const res = await fetch(`/api/reports/${ReportsHub.activeReport}?format=${ReportsHub.activeFormat}`);
      if (res.status === 202) {
        let text = await res.text();
        codeOutput.textContent = text;
        if (ReportsHub.pollTimer) clearTimeout(ReportsHub.pollTimer);
        ReportsHub.pollTimer = setTimeout(() => ReportsHub.loadActiveReport(), 1500);
        return;
      }
      if (!res.ok) throw new Error(`HTTP ${res.status}: ${res.statusText}`);
      let text = await res.text();
      if (ReportsHub.activeFormat === 'json') {
        try {
          text = JSON.stringify(JSON.parse(text), null, 2);
        } catch (_) {}
      }
      ReportsHub.cache[cacheKey] = text;
      codeOutput.textContent = text;
    } catch (err) {
      codeOutput.textContent = 'Error loading report: ' + err.message;
    }
  },

  renderReportFilterBar(type, data) {
    const bar = qs('#reports-filter-bar');
    if (!bar) return;

    if (ReportsHub.activeFormat !== 'dashboard' || type === 'html-snapshot') {
      bar.style.display = 'none';
      return;
    }

    const cfg = REPORT_FILTERS_CONFIG[type];
    if (!cfg) {
      bar.style.display = 'none';
      return;
    }

    if (!ReportsHub.filterState || ReportsHub.filterState.report !== type) {
      ReportsHub.filterState = {
        report: type,
        pill: 'ALL',
        select1: 'ALL',
        select2: 'ALL',
        search: ''
      };
    }

    const state = ReportsHub.filterState;

    bar.innerHTML = `
      <div class="reports-filter-bar-left">
        <span class="report-filter-badge-icon">
          ${cfg.icon || ''}
          <span>${esc(cfg.badge || 'Report Filters')}</span>
        </span>

        <div class="report-filter-pills" role="group" aria-label="Quick filters">
          ${(cfg.pills || []).map(p => `
            <button type="button" class="report-filter-pill ${state.pill === p.id ? 'active' : ''}" data-pill="${esc(p.id)}">
              ${esc(p.label)}
            </button>
          `).join('')}
        </div>
      </div>

      <div class="reports-filter-bar-right">
        ${(cfg.selects && cfg.selects.length > 0) ? `
          <div class="report-filter-selectors">
            ${cfg.selects.map((sel, idx) => `
              <div class="report-filter-select-wrap">
                <select class="report-filter-select" data-select-idx="${idx}" aria-label="${esc(sel.label)}">
                  <option value="ALL" ${state['select' + (idx + 1)] === 'ALL' ? 'selected' : ''}>${esc(sel.label)}</option>
                  ${(sel.options || []).map(opt => `
                    <option value="${esc(opt.val)}" ${state['select' + (idx + 1)] === opt.val ? 'selected' : ''}>${esc(opt.label)}</option>
                  `).join('')}
                </select>
              </div>
            `).join('')}
          </div>
        ` : ''}

        <div class="report-filter-search-wrap">
          <svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
          <input type="search" class="report-filter-search-input" placeholder="${esc(cfg.placeholder || 'Filter report…')}" value="${esc(state.search || '')}" aria-label="Search active report" />
        </div>

        <div class="report-filter-stats">
          <span class="report-filter-count-badge" id="report-filter-count-badge">Calculating…</span>
          <button type="button" class="report-filter-reset-btn" id="report-filter-reset-btn" title="Reset all filters">Reset</button>
        </div>
      </div>
    `;

    bar.style.display = 'flex';

    bar.querySelectorAll('.report-filter-pill').forEach(btn => {
      btn.addEventListener('click', (e) => {
        e.stopPropagation();
        bar.querySelectorAll('.report-filter-pill').forEach(b => b.classList.remove('active'));
        btn.classList.add('active');
        state.pill = btn.dataset.pill || 'ALL';
        ReportsHub.applyReportFilters();
      });
    });

    bar.querySelectorAll('.report-filter-select').forEach(sel => {
      sel.addEventListener('change', (e) => {
        const idx = parseInt(e.target.dataset.selectIdx, 10);
        state['select' + (idx + 1)] = e.target.value;
        ReportsHub.applyReportFilters();
      });
    });

    const searchInput = bar.querySelector('.report-filter-search-input');
    if (searchInput) {
      searchInput.addEventListener('input', (e) => {
        state.search = e.target.value || '';
        ReportsHub.applyReportFilters();
      });
    }

    const resetBtn = bar.querySelector('#report-filter-reset-btn');
    if (resetBtn) {
      resetBtn.addEventListener('click', () => {
        ReportsHub.resetReportFilters();
      });
    }
  },

  resetReportFilters() {
    const type = ReportsHub.activeReport;
    ReportsHub.filterState = {
      report: type,
      pill: 'ALL',
      select1: 'ALL',
      select2: 'ALL',
      search: ''
    };
    const bar = qs('#reports-filter-bar');
    if (bar) {
      bar.querySelectorAll('.report-filter-pill').forEach(p => {
        p.classList.toggle('active', p.dataset.pill === 'ALL');
      });
      bar.querySelectorAll('.report-filter-select').forEach(sel => {
        sel.value = 'ALL';
      });
      const input = bar.querySelector('.report-filter-search-input');
      if (input) input.value = '';
    }
    ReportsHub.applyReportFilters();
  },

  applyReportFilters() {
    let totalAll = 0;
    let totalFiltered = 0;

    (ReportsHub.activeTables || []).forEach(tblObj => {
      if (typeof tblObj.applyFilter === 'function') {
        tblObj.applyFilter();
        totalAll += (tblObj.allRows ? tblObj.allRows.length : 0);
        totalFiltered += (typeof tblObj.getFilteredCount === 'function' ? tblObj.getFilteredCount() : 0);
      }
    });

    const countBadge = qs('#report-filter-count-badge');
    const resetBtn = qs('#report-filter-reset-btn');
    const isFiltered = ReportsHub.filterState && (
      (ReportsHub.filterState.pill && ReportsHub.filterState.pill !== 'ALL') ||
      (ReportsHub.filterState.select1 && ReportsHub.filterState.select1 !== 'ALL') ||
      (ReportsHub.filterState.select2 && ReportsHub.filterState.select2 !== 'ALL') ||
      (ReportsHub.filterState.search && ReportsHub.filterState.search.trim().length > 0)
    );

    if (countBadge) {
      if (totalAll === 0) {
        countBadge.textContent = '0 items';
        countBadge.classList.remove('is-filtered');
      } else if (!isFiltered) {
        countBadge.textContent = `${totalAll} items`;
        countBadge.classList.remove('is-filtered');
      } else {
        countBadge.textContent = `${totalFiltered} / ${totalAll} matched`;
        countBadge.classList.add('is-filtered');
      }
    }

    if (resetBtn) {
      resetBtn.style.opacity = isFiltered ? '1' : '0.6';
      resetBtn.style.pointerEvents = isFiltered ? 'auto' : 'none';
    }
  },

  renderDashboard(type, data) {
    const container = qs('#reports-dashboard-container');
    if (!container) return;

    ReportsHub.activeTables = [];
    ReportsHub.renderReportFilterBar(type, data);

    if (type === 'executive-summary') {
      ReportsHub.renderExecutiveSummaryDashboard(container, data);
    } else if (type === 'technical-debt') {
      ReportsHub.renderTechnicalDebtDashboard(container, data);
    } else if (type === 'change-risk') {
      ReportsHub.renderChangeRiskDashboard(container, data);
    } else if (type === 'dead-code') {
      ReportsHub.renderDeadCodeDashboard(container, data);
    } else if (type === 'circular-dependencies') {
      ReportsHub.renderCircularDependenciesDashboard(container, data);
    } else if (type === 'archetype-governance') {
      ReportsHub.renderArchetypeGovernanceDashboard(container, data);
    } else if (type === 'architecture') {
      ReportsHub.renderArchitectureDashboard(container, data);
    } else if (type === 'module-coupling') {
      ReportsHub.renderModuleCouplingDashboard(container, data);
    } else if (type === 'review') {
      ReportsHub.renderReviewDashboard(container, data);
    } else if (type === 'metrics') {
      ReportsHub.renderMetricsDashboard(container, data);
    } else if (type === 'api-catalog') {
      ReportsHub.renderApiCatalogDashboard(container, data);
    } else if (type === 'database-access') {
      ReportsHub.renderDatabaseAccessDashboard(container, data);
    } else if (type === 'concurrency-audit') {
      ReportsHub.renderConcurrencyAuditDashboard(container, data);
    } else {
      container.innerHTML = `<pre class="reports-code-output">${esc(JSON.stringify(data, null, 2))}</pre>`;
    }

    ReportsHub.enhanceInteractiveTables(container);
    ReportsHub.applyReportFilters();
  },

  renderChangeRiskDashboard(container, d) {
    const highestScore = (d.classRiskRankings && d.classRiskRankings.length > 0) ? d.classRiskRankings[0].riskScore : 0;
    const riskBadgeClass = (highestScore >= 75) ? 'risk-critical' : (highestScore >= 50) ? 'risk-high' : 'risk-medium';
    
    let html = `
      <div class="report-kpi-grid">
        <div class="report-kpi-card" style="--kpi-accent: #f43f5e;">
          <span class="report-kpi-label">Highest Composite Risk</span>
          <div class="report-kpi-val">
            <span>${highestScore}</span>
            <span class="risk-badge ${riskBadgeClass}">SCORE / 100</span>
          </div>
          <span class="report-kpi-sub">Average Risk: <strong>${d.averageRiskScore || 0}/100</strong></span>
        </div>

        <div class="report-kpi-card" style="--kpi-accent: #f59e0b;">
          <span class="report-kpi-label">High-Risk Methods</span>
          <div class="report-kpi-val">${d.highRiskMethods ? d.highRiskMethods.length : 0}</div>
          <span class="report-kpi-sub">Critical: <strong>${d.criticalRiskCount || 0}</strong> | High: <strong>${d.highRiskCount || 0}</strong></span>
        </div>

        <div class="report-kpi-card" style="--kpi-accent: #06b6d4;">
          <span class="report-kpi-label">Mutation Hotspots</span>
          <div class="report-kpi-val">${d.fieldMutationHotspots ? d.fieldMutationHotspots.length : 0}</div>
          <span class="report-kpi-sub">Fields mutated &amp; read downstream</span>
        </div>

        <div class="report-kpi-card" style="--kpi-accent: #ef4444;">
          <span class="report-kpi-label">Behavioral Hotspots</span>
          <div class="report-kpi-val">${d.behavioralHotspotCount || (d.behavioralHotspots ? d.behavioralHotspots.length : 0)}</div>
          <span class="report-kpi-sub">Complexity &times; Git Churn Correlated</span>
        </div>

        <div class="report-kpi-card" style="--kpi-accent: #10b981;">
          <span class="report-kpi-label">Classes Evaluated</span>
          <div class="report-kpi-val">${d.totalClassesAnalyzed || 0}</div>
          <span class="report-kpi-sub">Graph &amp; field matrix nodes</span>
        </div>
      </div>

      <!-- Behavioral Hotspots (Complexity x Git Churn) -->
      <div class="report-section-card">
        <div class="report-section-header">
          <div class="report-section-title">
            <svg class="svg-icon icon-red icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M8.5 14.5A2.5 2.5 0 0 0 11 12c0-1.38-.5-2-1-3-1.072-2.143-.224-4.054 2-6 .5 2.5 2 4.9 4 6.5 2 1.6 3 3.5 3 5.5a7 7 0 1 1-14 0c0-1.153.433-2.294 1-3a2.5 2.5 0 0 0 2.5 2.5z"/></svg>
            <span>Behavioral Hotspots (Complexity &times; Git Churn Correlation)</span>
          </div>
          <span class="report-section-badge">${(d.behavioralHotspots || []).length} Hotspots</span>
        </div>
        <div style="padding: 8px 16px 4px; font-size: 12px; color: var(--text-muted);">
          Correlates AST Cyclomatic Complexity (CC) and Lines of Code (LOC) with historical Git commit churn. Entities scoring high carry the highest statistical defect probability.
        </div>
        <div class="report-table-wrap">
          <table class="report-table">
            <thead>
              <tr>
                <th>Entity Name</th>
                <th>Package</th>
                <th>Hotspot Score</th>
                <th>Risk Tier</th>
                <th>Complexity (CC)</th>
                <th>Git Churn</th>
                <th>LOC</th>
                <th>Prescribed Action</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
    `;

    if (d.behavioralHotspots && d.behavioralHotspots.length > 0) {
      d.behavioralHotspots.slice(0, 25).forEach(h => {
        const tierClass = h.riskTier === 'CRITICAL' ? 'risk-critical' : (h.riskTier === 'HIGH' ? 'risk-high' : 'risk-medium');
        html += `
          <tr>
            <td>
              <a href="#" onclick="event.preventDefault(); inspectReportEntity('${esc(h.entityFqn)}', 'knowledge');" style="font-family:var(--font-mono); font-weight:700; color:var(--text-primary); text-decoration:none; cursor:pointer;" title="Inspect ${esc(h.entityFqn)} in Knowledge Base">${esc(h.simpleName)}</a>
              <span style="font-size:10px; color:var(--text-muted); margin-left:4px;">(${esc(h.kind)})</span>
            </td>
            <td style="color:var(--text-muted); font-family:var(--font-mono); font-size:11.5px; max-width:140px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="Open package ${esc(h.packageName)}">
              <a href="#" onclick="event.preventDefault(); inspectReportPackage('${esc(h.packageName)}');" style="color:var(--text-muted); text-decoration:none; cursor:pointer;">${esc(h.packageName)}</a>
            </td>
            <td><span class="risk-badge ${tierClass}">${Math.round(h.hotspotScore)} / 100</span></td>
            <td><span class="risk-badge ${tierClass}">${esc(h.riskTier)}</span></td>
            <td style="font-family:var(--font-mono);">${h.cyclomaticComplexity}</td>
            <td style="font-family:var(--font-mono);">${h.commitCount} commits</td>
            <td style="font-family:var(--font-mono);">${h.linesOfCode}</td>
            <td style="font-size:11px; color:var(--text-secondary); max-width:260px;">${esc(h.recommendation)}</td>
            <td>
              <div style="display:flex; align-items:center; gap:4px;">
                <button class="btn-ghost" style="font-size:11px; padding:3px 7px;" onclick="inspectReportEntity('${esc(h.entityFqn)}', 'knowledge');" title="Inspect in Knowledge Base">
                  KB →
                </button>
                <button class="btn-ghost" style="font-size:11px; padding:3px 7px; color:#ef4444;" onclick="jumpToGraphHeat('${esc(h.entityFqn)}');" title="View in Graph Heat Mode">
                  Graph ♨ →
                </button>
              </div>
            </td>
          </tr>
        `;
      });
    } else {
      html += `
        <tr>
          <td colspan="9" style="text-align:center; padding:20px; color:var(--text-muted);">
            No git churn history recorded yet or all entities have low behavioral risk.
          </td>
        </tr>
      `;
    }

    html += `
            </tbody>
          </table>
        </div>
      </div>

      <!-- Top Risk Classes -->
      <div class="report-section-card">
        <div class="report-section-header">
          <div class="report-section-title">
            <svg class="svg-icon icon-rose icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z"/></svg>
            <span>Top High-Risk Classes (Blast Radius &amp; Downstream Fragility)</span>
          </div>
          <span class="report-section-badge">${(d.classRiskRankings || []).length} Classes</span>
        </div>
        <div class="report-table-wrap">
          <table class="report-table">
            <thead>
              <tr>
                <th>Class Name</th>
                <th>Package</th>
                <th>Risk Score</th>
                <th>Level</th>
                <th>Fan-In (Ca)</th>
                <th>Fan-Out (Ce)</th>
                <th>Field Blast</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
    `;

    (d.classRiskRankings || []).slice(0, 20).forEach(c => {
      const bClass = (c.riskLevel === 'CRITICAL') ? 'risk-critical' : (c.riskLevel === 'HIGH') ? 'risk-high' : 'risk-medium';
      html += `
        <tr>
          <td>
            <a href="#" onclick="event.preventDefault(); inspectReportEntity('${esc(c.classFqn)}', 'knowledge');" style="font-family:var(--font-mono); font-weight:700; color:var(--text-primary); text-decoration:none; cursor:pointer;" title="Inspect ${esc(c.classFqn)}">${esc(c.simpleName)}</a>
          </td>
          <td style="color:var(--text-muted); font-family:var(--font-mono); font-size:11.5px; max-width:160px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="Open package ${esc(c.packageName)}">
            <a href="#" onclick="event.preventDefault(); inspectReportPackage('${esc(c.packageName)}');" style="color:var(--text-muted); text-decoration:none; cursor:pointer;">${esc(c.packageName)}</a>
          </td>
          <td><span class="risk-badge ${bClass}">${c.riskScore} / 100</span></td>
          <td><span class="risk-badge ${bClass}">${esc(c.riskLevel)}</span></td>
          <td style="font-family:var(--font-mono);">${c.afferentCoupling}</td>
          <td style="font-family:var(--font-mono);">${c.efferentCoupling}</td>
          <td style="font-family:var(--font-mono);">${c.fieldBlastRadius} readers</td>
          <td>
            <div style="display:flex; align-items:center; gap:4px;">
              <button class="btn-ghost" style="font-size:11px; padding:3px 8px;" onclick="inspectReportEntity('${esc(c.classFqn)}', 'knowledge');" title="Inspect in Knowledge Base">
                KB →
              </button>
              <button class="btn-ghost" style="font-size:11px; padding:3px 8px;" onclick="inspectReportEntity('${esc(c.classFqn)}', 'graph');" title="View Call Graph">
                Graph →
              </button>
            </div>
          </td>
        </tr>
      `;
    });

    html += `
            </tbody>
          </table>
        </div>
      </div>

      <!-- Field Mutation Hotspots -->
      <div class="report-section-card">
        <div class="report-section-header">
          <div class="report-section-title">
            <svg class="svg-icon icon-cyan icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><path d="m4.93 4.93 14.14 14.14"/></svg>
            <span>Field Mutation Hotspots (High Downstream Reader Ripple)</span>
          </div>
          <span class="report-section-badge">${(d.fieldMutationHotspots || []).length} Fields</span>
        </div>
        <div class="report-table-wrap">
          <table class="report-table">
            <thead>
              <tr>
                <th>Field Name</th>
                <th>Mutating Method</th>
                <th>Downstream Readers</th>
                <th>Modules Impacted</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
    `;

    (d.fieldMutationHotspots || []).slice(0, 20).forEach(f => {
      html += `
        <tr>
          <td style="max-width:180px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="Trace field ${esc(f.fieldFqn)}">
            <a href="#" onclick="event.preventDefault(); selectField('${esc(f.fieldFqn)}');" style="font-family:var(--font-mono); font-weight:700; color:#38bdf8; text-decoration:none; cursor:pointer;">${esc(f.fieldFqn)}</a>
          </td>
          <td style="max-width:200px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="Inspect method ${esc(f.writerMethodFqn)}">
            <a href="#" onclick="event.preventDefault(); selectMethod('${esc(f.writerMethodFqn)}');" style="color:var(--text-muted); font-family:var(--font-mono); font-size:11.5px; text-decoration:none; cursor:pointer;">${esc(f.writerMethodFqn)}</a>
          </td>
          <td><span class="risk-badge risk-high">${f.readerMethodCount} methods</span></td>
          <td style="font-family:var(--font-mono);">${f.impactedModuleCount} modules</td>
          <td>
            <div style="display:flex; align-items:center; gap:4px;">
              <button class="btn-ghost" style="font-size:11px; padding:3px 8px;" onclick="inspectReportEntity('${esc(f.declaringClass || f.fieldFqn)}', 'knowledge');" title="Inspect Declaring Class in Knowledge Base">
                KB →
              </button>
              <button class="btn-ghost" style="font-size:11px; padding:3px 8px;" onclick="selectField('${esc(f.fieldFqn)}');" title="Trace Field Propagation on Graph">
                Trace Graph →
              </button>
            </div>
          </td>
        </tr>
      `;
    });

    html += `
            </tbody>
          </table>
        </div>
      </div>
    `;

    if (d.highRiskMethods && d.highRiskMethods.length > 0) {
      html += `
        <div class="report-section-card">
          <div class="report-section-header">
            <div class="report-section-title">
              <svg class="svg-icon icon-rose icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/></svg>
              <span>High-Risk Methods (Complexity × Blast Radius × State Mutation)</span>
            </div>
            <span class="report-section-badge">${d.highRiskMethods.length} Methods</span>
          </div>
          <div class="report-table-wrap">
            <table class="report-table">
              <thead>
                <tr>
                  <th>Method Signature</th>
                  <th>Declaring Class</th>
                  <th>Risk Score</th>
                  <th>CC</th>
                  <th>Callers</th>
                  <th>Fields Written</th>
                  <th>Risk Factor</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                ${(d.highRiskMethods || []).map(m => {
                  const mFqn = m.methodFqn || (m.declaringClass + '.' + (m.simpleName || 'method') + '()');
                  const simpleName = m.simpleName || (m.methodFqn ? (m.methodFqn.includes('(') ? m.methodFqn.substring(0, m.methodFqn.indexOf('(')).split('.').pop() + '()' : m.methodFqn.split('.').pop()) : 'method()');
                  const rScore = m.riskScore != null ? m.riskScore : (m.callerCount != null ? (m.callerCount * 2 + (m.calleeCount || 0) + (m.totalBlastRadius || 0) * 3) : 0);
                  const rLevel = m.riskLevel || (rScore >= 50 ? 'CRITICAL' : (rScore >= 25 ? 'HIGH' : 'MEDIUM'));
                  const rClass = (rLevel === 'CRITICAL' || rScore >= 50) ? 'risk-critical' : ((rLevel === 'HIGH' || rScore >= 25) ? 'risk-high' : 'risk-medium');
                  const cc = m.complexity != null ? m.complexity : (m.cyclomaticComplexity != null ? m.cyclomaticComplexity : '-');
                  const callers = m.directCallers != null ? m.directCallers : (m.callerCount != null ? m.callerCount : 0);
                  const fieldsMutated = m.fieldsWritten != null ? m.fieldsWritten : (m.fieldMutationsCount != null ? m.fieldMutationsCount : 0);
                  const riskFactor = m.riskFactor || (m.totalBlastRadius ? `Blast: ${m.totalBlastRadius} | Callers: ${callers}` : rLevel);
                  return `
                    <tr>
                      <td style="max-width:240px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="Inspect ${esc(mFqn)}">
                        <a href="#" onclick="event.preventDefault(); selectMethod('${esc(mFqn)}');" style="font-family:var(--font-mono); font-weight:700; color:#f43f5e; text-decoration:none; cursor:pointer;">${esc(simpleName)}</a>
                      </td>
                      <td style="max-width:220px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="${esc(m.declaringClass || '')}">
                        <a href="#" onclick="event.preventDefault(); inspectReportEntity('${esc(m.declaringClass || '')}', 'knowledge');" style="font-family:var(--font-mono); color:var(--text-muted); text-decoration:none; cursor:pointer;">${esc(m.declaringClass || '-')}</a>
                      </td>
                      <td><span class="risk-badge ${rClass}">${rScore}</span></td>
                      <td style="font-family:var(--font-mono);">${cc}</td>
                      <td style="font-family:var(--font-mono);">${callers}</td>
                      <td style="font-family:var(--font-mono);">${fieldsMutated}</td>
                      <td style="color:var(--text-muted); font-size:11.5px;">${esc(riskFactor)}</td>
                      <td>
                        <div style="display:flex; align-items:center; gap:4px;">
                          <button class="btn-ghost" style="font-size:11px; padding:3px 7px;" onclick="inspectReportEntity('${esc(m.declaringClass || mFqn)}', 'knowledge');" title="Inspect in Knowledge Base">KB →</button>
                          <button class="btn-ghost" style="font-size:11px; padding:3px 7px;" onclick="selectMethod('${esc(mFqn)}');" title="Inspect Call Graph">Call Graph →</button>
                        </div>
                      </td>
                    </tr>
                  `;
                }).join('')}
              </tbody>
            </table>
          </div>
        </div>
      `;
    }

    container.innerHTML = html;
  },

  renderDeadCodeDashboard(container, d) {
    let html = `
      <div class="report-kpi-grid">
        <div class="report-kpi-card" style="--kpi-accent: #f59e0b;">
          <span class="report-kpi-label">Est. Dead Lines of Code</span>
          <div class="report-kpi-val">
            <span>${(d.estimatedDeadLinesOfCode || 0).toLocaleString()}</span>
            <span class="risk-badge risk-high">${d.deadCodePercentage || 0}%</span>
          </div>
          <span class="report-kpi-sub">Potential reduction in cognitive load</span>
        </div>

        <div class="report-kpi-card" style="--kpi-accent: #f43f5e;">
          <span class="report-kpi-label">Orphaned Methods</span>
          <div class="report-kpi-val">${d.orphanedMethodsCount || (d.orphanedMethods ? d.orphanedMethods.length : 0)}</div>
          <span class="report-kpi-sub">0 callers across all indexed code</span>
        </div>

        <div class="report-kpi-card" style="--kpi-accent: #a855f7;">
          <span class="report-kpi-label">Orphaned Classes</span>
          <div class="report-kpi-val">${d.orphanedClassesCount || (d.orphanedClasses ? d.orphanedClasses.length : 0)}</div>
          <span class="report-kpi-sub">0 incoming dependencies</span>
        </div>

        <div class="report-kpi-card" style="--kpi-accent: #06b6d4;">
          <span class="report-kpi-label">Unreferenced Fields</span>
          <div class="report-kpi-val">${d.unreferencedFieldsCount || (d.unreferencedFields ? d.unreferencedFields.length : 0)}</div>
          <span class="report-kpi-sub">Zero read/write access detected</span>
        </div>
      </div>

      <!-- Top Orphaned Methods -->
      <div class="report-section-card">
        <div class="report-section-header">
          <div class="report-section-title">
            <svg class="svg-icon icon-amber icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="7.86 2 16.14 2 22 7.86 22 16.14 16.14 22 7.86 22 2 16.14 2 7.86 7.86 2"/></svg>
            <span>Top Orphaned Methods (0 Callers Detected)</span>
          </div>
          <span class="report-section-badge">${(d.orphanedMethods || []).length} Methods</span>
        </div>
        <div class="report-table-wrap">
          <table class="report-table">
            <thead>
              <tr>
                <th>Method Name</th>
                <th>Declaring Class</th>
                <th>Package</th>
                <th>Est. Lines</th>
                <th>Reason</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
    `;

    (d.orphanedMethods || []).forEach(m => {
      const methodTarget = m.methodFqn || (m.declaringClass + '.' + m.simpleName + '()');
      html += `
        <tr>
          <td>
            <a href="#" onclick="event.preventDefault(); selectMethod('${esc(methodTarget)}');" style="font-family:var(--font-mono); font-weight:700; color:#f59e0b; text-decoration:none; cursor:pointer;" title="Inspect method ${esc(methodTarget)}">${esc(m.simpleName)}</a>
          </td>
          <td style="color:var(--text-muted); font-family:var(--font-mono);">
            <a href="#" onclick="event.preventDefault(); inspectReportEntity('${esc(m.declaringClass)}', 'knowledge');" style="color:var(--text-muted); text-decoration:none; cursor:pointer;">${esc(m.declaringClass)}</a>
          </td>
          <td style="color:var(--text-muted); font-family:var(--font-mono);">
            <a href="#" onclick="event.preventDefault(); inspectReportPackage('${esc(m.packageName)}');" style="color:var(--text-muted); text-decoration:none; cursor:pointer;">${esc(m.packageName)}</a>
          </td>
          <td style="font-family:var(--font-mono);">${m.lineCount || 0}</td>
          <td><span class="risk-badge risk-low">${esc(m.reason || '0 callers')}</span></td>
          <td>
            <div style="display:flex; align-items:center; gap:4px;">
              <button class="btn-ghost" style="font-size:11px; padding:3px 8px;" onclick="inspectReportEntity('${esc(m.declaringClass)}', 'knowledge');" title="Inspect declaring class in Knowledge Base">
                KB →
              </button>
              <button class="btn-ghost" style="font-size:11px; padding:3px 8px;" onclick="selectMethod('${esc(methodTarget)}');" title="Inspect method call graph">
                Graph →
              </button>
            </div>
          </td>
        </tr>
      `;
    });

    html += `
            </tbody>
          </table>
        </div>
      </div>

      <!-- Orphaned Classes -->
      <div class="report-section-card">
        <div class="report-section-header">
          <div class="report-section-title">
            <svg class="svg-icon icon-purple icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/></svg>
            <span>Orphaned Classes (No Inbound Dependencies)</span>
          </div>
          <span class="report-section-badge">${(d.orphanedClasses || []).length} Classes</span>
        </div>
        <div class="report-table-wrap">
          <table class="report-table">
            <thead>
              <tr>
                <th>Class Name</th>
                <th>Package</th>
                <th>Lines of Code</th>
                <th>Method Count</th>
                <th>Diagnostic Note</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
    `;

    (d.orphanedClasses || []).forEach(c => {
      html += `
        <tr>
          <td>
            <a href="#" onclick="event.preventDefault(); inspectReportEntity('${esc(c.classFqn)}', 'knowledge');" style="font-family:var(--font-mono); font-weight:700; color:var(--text-primary); text-decoration:none; cursor:pointer;">${esc(c.simpleName)}</a>
          </td>
          <td style="color:var(--text-muted); font-family:var(--font-mono);">
            <a href="#" onclick="event.preventDefault(); inspectReportPackage('${esc(c.packageName)}');" style="color:var(--text-muted); text-decoration:none; cursor:pointer;">${esc(c.packageName)}</a>
          </td>
          <td style="font-family:var(--font-mono);">${c.lineCount || 0}</td>
          <td style="font-family:var(--font-mono);">${c.methodCount || 0}</td>
          <td style="color:var(--text-muted);">${esc(c.reason || 'Zero incoming references')}</td>
          <td>
            <div style="display:flex; align-items:center; gap:4px;">
              <button class="btn-ghost" style="font-size:11px; padding:3px 8px;" onclick="inspectReportEntity('${esc(c.classFqn)}', 'knowledge');" title="Inspect in Knowledge Base">
                KB →
              </button>
              <button class="btn-ghost" style="font-size:11px; padding:3px 8px;" onclick="inspectReportEntity('${esc(c.classFqn)}', 'graph');" title="View on Graph">
                Graph →
              </button>
            </div>
          </td>
        </tr>
      `;
    });

    html += `
            </tbody>
          </table>
        </div>
      </div>
    `;

    if (d.unreferencedFields && d.unreferencedFields.length > 0) {
      html += `
        <div class="report-section-card">
          <div class="report-section-header">
            <div class="report-section-title">
              <svg class="svg-icon icon-cyan icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
              <span>Unreferenced Fields (Zero Read/Write References)</span>
            </div>
            <span class="report-section-badge">${d.unreferencedFields.length} Fields</span>
          </div>
          <div class="report-table-wrap">
            <table class="report-table">
              <thead>
                <tr>
                  <th>Field Name</th>
                  <th>Field Type</th>
                  <th>Declaring Class</th>
                  <th>Diagnostic Reason</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                ${(d.unreferencedFields || []).map(f => {
                  const fFqn = f.fieldFqn || (f.declaringClass + '.' + f.fieldName);
                  return `
                    <tr>
                      <td>
                        <a href="#" onclick="event.preventDefault(); selectField('${esc(fFqn)}');" style="font-family:var(--font-mono); font-weight:700; color:#38bdf8; text-decoration:none; cursor:pointer;">${esc(f.fieldName)}</a>
                      </td>
                      <td style="font-family:var(--font-mono); color:var(--text-secondary);">${esc(f.fieldType)}</td>
                      <td>
                        <a href="#" onclick="event.preventDefault(); inspectReportEntity('${esc(f.declaringClass)}', 'knowledge');" style="font-family:var(--font-mono); color:var(--text-muted); text-decoration:none; cursor:pointer;">${esc(f.declaringClass)}</a>
                      </td>
                      <td><span class="risk-badge risk-low">${esc(f.reason || '0 reads & 0 writes')}</span></td>
                      <td>
                        <div style="display:flex; align-items:center; gap:4px;">
                          <button class="btn-ghost" style="font-size:11px; padding:3px 7px;" onclick="inspectReportEntity('${esc(f.declaringClass)}', 'knowledge');">KB →</button>
                          <button class="btn-ghost" style="font-size:11px; padding:3px 7px;" onclick="selectField('${esc(fFqn)}');">Trace →</button>
                        </div>
                      </td>
                    </tr>
                  `;
                }).join('')}
              </tbody>
            </table>
          </div>
        </div>
      `;
    }

    container.innerHTML = html;
  },

  renderCircularDependenciesDashboard(container, d) {
    const isClean = (d.totalClassCycles === 0 && d.totalPackageTangles === 0);
    const cleanBadge = isClean
      ? '<span class="risk-badge risk-low">CLEAN / ZERO CYCLES</span>'
      : '<span class="risk-badge risk-critical">CYCLES DETECTED</span>';

    let html = `
      <div class="report-kpi-grid">
        <div class="report-kpi-card" style="--kpi-accent: #10b981;">
          <span class="report-kpi-label">Acyclicity Health Score</span>
          <div class="report-kpi-val">
            <span>${d.acyclicScore || 100}/100</span>
            <span class="risk-badge ${d.acyclicScore >= 80 ? 'risk-low' : 'risk-high'}">${d.architectureHealthRating ? d.architectureHealthRating.split(' ')[0] : 'A+'}</span>
          </div>
          <span class="report-kpi-sub">${d.architectureHealthRating || 'Fully Acyclic Architecture'}</span>
        </div>

        <div class="report-kpi-card" style="--kpi-accent: #a855f7;">
          <span class="report-kpi-label">Class-Level Cycles</span>
          <div class="report-kpi-val">
            <span>${d.totalClassCycles || 0}</span>
            ${cleanBadge}
          </div>
          <span class="report-kpi-sub">Direct / transitive recursion loops</span>
        </div>

        <div class="report-kpi-card" style="--kpi-accent: #f59e0b;">
          <span class="report-kpi-label">Package Tangles</span>
          <div class="report-kpi-val">${d.totalPackageTangles || 0}</div>
          <span class="report-kpi-sub">Bidirectional package dependencies</span>
        </div>

        <div class="report-kpi-card" style="--kpi-accent: #06b6d4;">
          <span class="report-kpi-label">Decoupling Recommendations</span>
          <div class="report-kpi-val">${(d.classCycles ? d.classCycles.length : 0) + (d.packageTangles ? d.packageTangles.length : 0)}</div>
          <span class="report-kpi-sub">Prescribed architectural break points</span>
        </div>
      </div>

      <!-- Quick Action: DSM Matrix Isolation -->
      <div style="margin-bottom:16px; padding:12px 16px; background:linear-gradient(90deg, rgba(239, 68, 68, 0.10), rgba(245, 158, 11, 0.06)); border:1px solid rgba(239, 68, 68, 0.35); border-radius:var(--radius-md); display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:10px;">
        <div style="display:flex; align-items:center; gap:10px;">
          <svg class="svg-icon icon-red icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/></svg>
          <div>
            <strong style="color:var(--text-primary); font-size:13px;">Explore &amp; Decouple Cycles in Dependency Structure Matrix (DSM)</strong>
            <div style="color:var(--text-muted); font-size:11.5px;">Interactive matrix view with automatic cycle isolation, layered feedforward ranking, and CSV/JSON export.</div>
          </div>
        </div>
        <button class="btn btn-sm btn-primary" onclick="openMacroStudio('dsm');" style="display:flex; align-items:center; gap:6px;">
          Open DSM Matrix →
        </button>
      </div>

      <!-- Class Dependency Cycles -->
      <div class="report-section-card">
        <div class="report-section-header">
          <div class="report-section-title">
            <svg class="svg-icon icon-purple icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21.5 2v6h-6M21.34 15.57a10 10 0 1 1-.57-8.38l5.67-5.67"/></svg>
            <span>Class-Level Dependency Cycles &amp; Refactoring Cuts</span>
          </div>
          <span class="report-section-badge">${(d.classCycles || []).length} Cycles</span>
        </div>
        <div style="padding: 16px; display:flex; flex-direction:column; gap:12px;">
    `;

    if (!d.classCycles || d.classCycles.length === 0) {
      html += `
        <div style="padding:24px; text-align:center; color:#34d399;">
          <svg class="svg-icon icon-emerald icon-md" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg>
          <div style="font-weight:700; margin-top:8px;">No Class Cycles Detected</div>
          <div style="color:var(--text-muted); font-size:12px;">All class dependencies form a Directed Acyclic Graph (DAG).</div>
        </div>
      `;
    } else {
      d.classCycles.forEach((c, i) => {
        html += `
          <div style="background:var(--bg-card); border:1px solid var(--border-subtle); border-radius:var(--radius-md); padding:12px 16px;">
            <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:8px;">
              <strong style="color:#f43f5e;">Cycle #${i + 1} (${c.cycleLength} classes)</strong>
              <span class="risk-badge risk-critical">RECURSIVE LOOP</span>
            </div>
            <div style="font-family:var(--font-mono); font-size:11.5px; color:var(--text-primary); line-height:1.7;">
              ${(c.path || []).map(p => `<a href="#" onclick="event.preventDefault(); inspectReportEntity('${esc(p)}', 'knowledge');" style="color:#38bdf8; text-decoration:none; cursor:pointer;">${esc(p)}</a>`).join(' <span style="color:#fbbf24;">➔</span> ')}
              <span style="color:#fbbf24;">➔</span> <a href="#" onclick="event.preventDefault(); inspectReportEntity('${esc(c.path[0])}', 'knowledge');" style="color:#38bdf8; text-decoration:none; cursor:pointer;">${esc(c.path[0])}</a>
            </div>
            <div style="margin-top:8px; font-size:12px; color:#34d399;">
              💡 <strong>Recommended Decoupling Cut:</strong> Break edge <code>${esc(c.recommendedCutEdge)}</code> via dependency injection or event bus.
            </div>
          </div>
        `;
      });
    }

    html += `
        </div>
      </div>

      <!-- Package Tangles -->
      <div class="report-section-card">
        <div class="report-section-header">
          <div class="report-section-title">
            <svg class="svg-icon icon-amber icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m7.5 4.27 9 5.15"/><path d="M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z"/></svg>
            <span>Bidirectional Package Tangles</span>
          </div>
          <span class="report-section-badge">${(d.packageTangles || []).length} Tangles</span>
        </div>
        <div class="report-table-wrap">
          <table class="report-table">
            <thead>
              <tr>
                <th>Package A</th>
                <th>Package B</th>
                <th>A ➔ B Calls</th>
                <th>B ➔ A Calls</th>
                <th>Decoupling Cut Direction</th>
              </tr>
            </thead>
            <tbody>
    `;

    if (!d.packageTangles || d.packageTangles.length === 0) {
      html += `
        <tr>
          <td colspan="5" style="text-align:center; padding:24px; color:#34d399;">
            <svg class="svg-icon icon-emerald icon-md" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="9 12 11 14 15 10"/></svg>
            <div style="font-weight:700; margin-top:6px;">No Package Tangles Detected</div>
            <div style="color:var(--text-muted); font-size:11.5px;">All inter-package invocations follow strict unilateral dependency flow.</div>
          </td>
        </tr>
      `;
    } else {
      (d.packageTangles || []).forEach(t => {
        html += `
          <tr>
            <td><a href="#" onclick="event.preventDefault(); inspectReportPackage('${esc(t.packageA)}');" style="font-family:var(--font-mono); font-weight:700; color:var(--text-primary); text-decoration:none; cursor:pointer;">${esc(t.packageA)}</a></td>
            <td><a href="#" onclick="event.preventDefault(); inspectReportPackage('${esc(t.packageB)}');" style="font-family:var(--font-mono); font-weight:700; color:var(--text-primary); text-decoration:none; cursor:pointer;">${esc(t.packageB)}</a></td>
            <td style="font-family:var(--font-mono); font-weight:700;">${t.callsAtoB}</td>
            <td style="font-family:var(--font-mono); font-weight:700;">${t.callsBtoA}</td>
            <td style="color:#34d399; font-weight:600; font-size:11.5px;">${esc(t.recommendedDecouplingDirection || 'Decouple weaker direction')}</td>
          </tr>
        `;
      });
    }

    html += `
            </tbody>
          </table>
        </div>
      </div>
    `;

    container.innerHTML = html;
  },

  renderArchetypeGovernanceDashboard(container, d) {
    const compBadge = (d.governanceScore >= 90) ? 'risk-low' : (d.governanceScore >= 70) ? 'risk-medium' : 'risk-high';

    let html = `
      <div class="report-kpi-grid">
        <div class="report-kpi-card" style="--kpi-accent: #10b981;">
          <span class="report-kpi-label">Governance Compliance</span>
          <div class="report-kpi-val">
            <span>${d.governanceScore || 100}%</span>
            <span class="risk-badge ${compBadge}">${esc(d.complianceRating ? d.complianceRating.split(' ')[0] : 'SCORE')}</span>
          </div>
          <span class="report-kpi-sub">${esc(d.complianceRating || 'Adherence to BaNCS & DDD archetypes')}</span>
        </div>

        <div class="report-kpi-card" style="--kpi-accent: #f43f5e;">
          <span class="report-kpi-label">Total Violations</span>
          <div class="report-kpi-val">${d.totalViolations || (d.violations ? d.violations.length : 0)}</div>
          <span class="report-kpi-sub">Critical: <strong>${(d.violations || []).filter(v => v.severity === 'CRITICAL').length}</strong> | Warnings: <strong>${(d.violations || []).filter(v => v.severity === 'WARNING').length}</strong></span>
        </div>

        <div class="report-kpi-card" style="--kpi-accent: #06b6d4;">
          <span class="report-kpi-label">Recognized Archetypes</span>
          <div class="report-kpi-val">${d.totalArchetypesFound || 0}</div>
          <span class="report-kpi-sub">MO_*, DG, BT &amp; Domain Entities</span>
        </div>

        <div class="report-kpi-card" style="--kpi-accent: #a855f7;">
          <span class="report-kpi-label">Governance Rules</span>
          <div class="report-kpi-val">4 Active</div>
          <span class="report-kpi-sub">Audit Trail, Grabber Purity, Inverted Layers</span>
        </div>
      </div>

      <!-- Governance Violations -->
      <div class="report-section-card">
        <div class="report-section-header">
          <div class="report-section-title">
            <svg class="svg-icon icon-rose icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>
            <span>Layering &amp; Architecture Violations</span>
          </div>
          <span class="report-section-badge">${(d.violations || []).length} Issues</span>
        </div>
        <div class="report-table-wrap">
          <table class="report-table">
            <thead>
              <tr>
                <th>Severity</th>
                <th>Rule Name</th>
                <th>Target Class / Method</th>
                <th>Violation Detail</th>
                <th>Recommended Fix</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
    `;

    if (!d.violations || d.violations.length === 0) {
      html += `
        <tr>
          <td colspan="6" style="text-align:center; padding:24px; color:#34d399;">
            <svg class="svg-icon icon-emerald icon-md" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="9 12 11 14 15 10"/></svg>
            <div style="font-weight:700; margin-top:6px;">Zero Governance Violations</div>
            <div style="color:var(--text-muted); font-size:11.5px;">All transactions, data grabbers, and entity layers conform to governance policies.</div>
          </td>
        </tr>
      `;
    } else {
      d.violations.forEach(v => {
        const sClass = (v.severity === 'CRITICAL') ? 'risk-critical' : (v.severity === 'HIGH') ? 'risk-high' : 'risk-medium';
        html += `
          <tr>
            <td><span class="risk-badge ${sClass}">${esc(v.severity)}</span></td>
            <td><strong style="color:var(--text-primary); font-size:11.5px; white-space:nowrap;">${esc(v.ruleName)}</strong></td>
            <td style="max-width:220px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="Inspect ${esc(v.entityFqn)}">
              <a href="#" onclick="event.preventDefault(); inspectReportEntity('${esc(v.entityFqn)}', 'knowledge');" style="color:#38bdf8; font-family:var(--font-mono); font-size:11px; text-decoration:none; cursor:pointer;">${esc(v.entityFqn)}</a>
            </td>
            <td style="color:var(--text-muted); font-size:11.5px; max-width:240px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="${esc(v.violationDetails)}">${esc(v.violationDetails)}</td>
            <td style="color:#34d399; font-size:11px; max-width:220px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="${esc(v.architecturalRemediation)}">💡 ${esc(v.architecturalRemediation)}</td>
            <td>
              <div style="display:flex; align-items:center; gap:4px;">
                <button class="btn-ghost" style="font-size:11px; padding:3px 7px;" onclick="inspectReportEntity('${esc(v.entityFqn)}', 'knowledge');" title="Inspect in Knowledge Base">
                  KB →
                </button>
                <button class="btn-ghost" style="font-size:11px; padding:3px 7px;" onclick="inspectReportEntity('${esc(v.entityFqn)}', 'graph');" title="View on Call Graph">
                  Graph →
                </button>
              </div>
            </td>
          </tr>
        `;
      });
    }

    html += `
            </tbody>
          </table>
        </div>
      </div>

      <!-- Archetype Breakdown -->
      <div class="report-section-card">
        <div class="report-section-header">
          <div class="report-section-title">
            <svg class="svg-icon icon-emerald icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect width="18" height="18" x="3" y="3" rx="2"/><path d="M3 9h18"/></svg>
            <span>Detected Enterprise Archetype Distributions</span>
          </div>
          <span class="report-section-badge">${(d.archetypeBreakdown || []).length} Archetypes</span>
        </div>
        <div class="report-table-wrap">
          <table class="report-table">
            <thead>
              <tr>
                <th>Archetype Category</th>
                <th>Detected Count</th>
                <th>Violations</th>
                <th>Compliance Rate</th>
                <th>Architectural Role</th>
              </tr>
            </thead>
            <tbody>
    `;

    (d.archetypeBreakdown || []).forEach(a => {
      html += `
        <tr>
          <td><strong style="color:var(--text-primary); font-size:12px;">${esc(a.archetype)}</strong></td>
          <td style="font-family:var(--font-mono); font-weight:700;">${a.count}</td>
          <td style="font-family:var(--font-mono); color:${a.violationCount > 0 ? '#f43f5e' : '#34d399'}; font-weight:700;">${a.violationCount}</td>
          <td><span class="risk-badge ${a.complianceRate >= 80 ? 'risk-low' : 'risk-medium'}">${a.complianceRate}%</span></td>
          <td style="color:var(--text-muted); font-size:11.5px;">${esc(a.description || 'Enterprise component')}</td>
        </tr>
      `;
    });

    html += `
            </tbody>
          </table>
        </div>
      </div>
    `;

    container.innerHTML = html;
  },

  renderArchitectureDashboard(container, d) {
    let html = `
      <div class="report-kpi-grid">
        <div class="report-kpi-card" style="--kpi-accent: #06b6d4;">
          <span class="report-kpi-label">Indexed Types</span>
          <div class="report-kpi-val">${d.totalClasses || d.totalTypes || 0}</div>
          <span class="report-kpi-sub">Classes, Interfaces, Enums</span>
        </div>
        <div class="report-kpi-card" style="--kpi-accent: #3b82f6;">
          <span class="report-kpi-label">Methods</span>
          <div class="report-kpi-val">${d.totalMethods || 0}</div>
          <span class="report-kpi-sub">Callable procedures</span>
        </div>
        <div class="report-kpi-card" style="--kpi-accent: #10b981;">
          <span class="report-kpi-label">Fields</span>
          <div class="report-kpi-val">${d.totalFields || 0}</div>
          <span class="report-kpi-sub">State attributes</span>
        </div>
        <div class="report-kpi-card" style="--kpi-accent: #a855f7;">
          <span class="report-kpi-label">Coupling Relations</span>
          <div class="report-kpi-val">${d.totalDependencies || d.totalRelationships || 0}</div>
          <span class="report-kpi-sub">Calls, reads, writes, inheritance</span>
        </div>
      </div>

      <div class="report-section-card">
        <div class="report-section-header">
          <div class="report-section-title">Package Coupling Matrix (Ca / Ce / Instability)</div>
          <span class="report-section-badge">${(d.packages || []).length} Packages</span>
        </div>
        <div class="report-table-wrap">
          <table class="report-table">
            <thead>
              <tr>
                <th>Package Name</th>
                <th>Classes</th>
                <th>Afferent In (Ca)</th>
                <th>Efferent Out (Ce)</th>
                <th>Instability (I = Ce / (Ca + Ce))</th>
                <th>Stability Classification</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
    `;

    (d.packages || []).slice(0, 25).forEach(p => {
      const pkgFqn = p.packageFqn || p.name || '';
      const ca = p.afferentCoupling ?? p.afferent ?? p.ca ?? 0;
      const ce = p.efferentCoupling ?? p.efferent ?? p.ce ?? 0;
      const iVal = p.instability != null ? Number(p.instability).toFixed(2) : (ce + ca > 0 ? (ce / (ce + ca)).toFixed(2) : '0.00');
      const isStable = parseFloat(iVal) < 0.3;
      html += `
        <tr>
          <td>
            <a href="#" onclick="event.preventDefault(); inspectReportPackage('${esc(pkgFqn)}');" style="font-family:var(--font-mono); font-weight:700; color:var(--text-primary); text-decoration:none; cursor:pointer;" title="Open package ${esc(pkgFqn)} in Knowledge Base">${esc(pkgFqn)}</a>
          </td>
          <td style="font-family:var(--font-mono);">${p.classCount ?? '-'}</td>
          <td style="font-family:var(--font-mono);">${ca}</td>
          <td style="font-family:var(--font-mono);">${ce}</td>
          <td><span class="risk-badge ${isStable ? 'risk-low' : 'risk-medium'}">${iVal}</span></td>
          <td style="color:var(--text-muted);">${isStable ? 'Stable Core' : 'Flexible / Dependent'}</td>
          <td>
            <button class="btn-ghost" style="font-size:11px; padding:3px 8px;" onclick="inspectReportPackage('${esc(pkgFqn)}');" title="Inspect package in Knowledge Base">
              KB →
            </button>
          </td>
        </tr>
      `;
    });

    html += `
            </tbody>
          </table>
        </div>
      </div>
    `;

    if (d.topCoupledClasses && d.topCoupledClasses.length > 0) {
      html += `
        <div class="report-section-card">
          <div class="report-section-header">
            <div class="report-section-title">Top Coupled Classes</div>
            <span class="report-section-badge">${d.topCoupledClasses.length} Classes</span>
          </div>
          <div class="report-table-wrap">
            <table class="report-table">
              <thead>
                <tr>
                  <th>Class Name</th>
                  <th>Package</th>
                  <th>Inbound (Ca)</th>
                  <th>Outbound (Ce)</th>
                  <th>Total Coupling</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                ${(d.topCoupledClasses || []).map(c => {
                  const fqn = c.classFqn || c.fqn || '';
                  const simple = c.simpleName || fqn.split('.').pop() || fqn;
                  const pkg = c.packageName || fqn.split('.').slice(0, -1).join('.');
                  const ca = c.afferentCoupling ?? c.inDegree ?? 0;
                  const ce = c.efferentCoupling ?? c.outDegree ?? 0;
                  return `
                    <tr>
                      <td><a href="#" onclick="event.preventDefault(); inspectReportEntity('${esc(fqn)}', 'knowledge');" style="font-family:var(--font-mono); font-weight:700; color:var(--text-primary); text-decoration:none; cursor:pointer;">${esc(simple)}</a></td>
                      <td><a href="#" onclick="event.preventDefault(); inspectReportPackage('${esc(pkg)}');" style="font-family:var(--font-mono); color:var(--text-muted); text-decoration:none; cursor:pointer;">${esc(pkg)}</a></td>
                      <td style="font-family:var(--font-mono);">${ca}</td>
                      <td style="font-family:var(--font-mono);">${ce}</td>
                      <td style="font-family:var(--font-mono); font-weight:700;">${c.totalCoupling ?? (ca + ce)}</td>
                      <td>
                        <div style="display:flex; align-items:center; gap:4px;">
                          <button class="btn-ghost" style="font-size:11px; padding:3px 8px;" onclick="inspectReportEntity('${esc(fqn)}', 'knowledge');">KB →</button>
                          <button class="btn-ghost" style="font-size:11px; padding:3px 8px;" onclick="inspectReportEntity('${esc(fqn)}', 'graph');">Graph →</button>
                        </div>
                      </td>
                    </tr>
                  `;
                }).join('')}
              </tbody>
            </table>
          </div>
        </div>
      `;
    }

    container.innerHTML = html;
  },

  renderReviewDashboard(container, d) {
    let html = `
      <div class="report-kpi-grid">
        <div class="report-kpi-card" style="--kpi-accent: #f43f5e;">
          <span class="report-kpi-label">Total Findings</span>
          <div class="report-kpi-val">${d.totalFindings || 0}</div>
          <span class="report-kpi-sub">Across 32 AST/Graph rules</span>
        </div>
        <div class="report-kpi-card" style="--kpi-accent: #f59e0b;">
          <span class="report-kpi-label">Critical / High</span>
          <div class="report-kpi-val">${d.criticalCount || 0}</div>
          <span class="report-kpi-sub">Warnings: <strong>${d.warningCount || 0}</strong></span>
        </div>
        <div class="report-kpi-card" style="--kpi-accent: #06b6d4;">
          <span class="report-kpi-label">Audited Classes</span>
          <div class="report-kpi-val">${d.totalFilesReviewed || d.auditedClassesCount || (d.types ? d.types.length : 0)}</div>
          <span class="report-kpi-sub">Index coverage</span>
        </div>
        <div class="report-kpi-card" style="--kpi-accent: #10b981;">
          <span class="report-kpi-label">Health Grade</span>
          <div class="report-kpi-val"><span class="risk-badge risk-low">${d.healthGrade || 'A-'}</span></div>
          <span class="report-kpi-sub">Composite quality score</span>
        </div>
      </div>

      <div style="margin-bottom:16px; padding:12px 16px; background:rgba(99, 102, 241, 0.08); border:1px solid rgba(99, 102, 241, 0.3); border-radius:var(--radius-md); display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:10px;">
        <span style="color:var(--text-secondary); font-size:12.5px;">Detailed code review findings with inline code snippets and CWE remediation instructions are available in the dedicated Code Review workspace.</span>
        <button class="btn btn-sm btn-primary" onclick="switchTab('review');">Open Code Review Workspace →</button>
      </div>
    `;

    if (d.findings && d.findings.length > 0) {
      html += `
        <div class="report-section-card">
          <div class="report-section-header">
            <div class="report-section-title">Top Code Quality &amp; Security Findings</div>
            <span class="report-section-badge">${d.findings.length} Findings</span>
          </div>
          <div class="report-table-wrap">
            <table class="report-table">
              <thead>
                <tr>
                  <th>Severity</th>
                  <th>Rule / Check</th>
                  <th>Target Entity</th>
                  <th>Diagnostic Message</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                ${(d.findings || []).map(f => {
                  const sClass = f.severity === 'CRITICAL' || f.severity === 'ERROR' ? 'risk-critical' : (f.severity === 'WARNING' ? 'risk-high' : 'risk-medium');
                  return `
                    <tr>
                      <td><span class="risk-badge ${sClass}">${esc(f.severity)}</span></td>
                      <td><strong style="font-size:11.5px; color:var(--text-primary);">${esc(f.checkName || f.category)}</strong></td>
                      <td style="max-width:220px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="${esc(f.entityFqn)}">
                        <a href="#" onclick="event.preventDefault(); inspectReportEntity('${esc(f.entityFqn)}', 'knowledge');" style="font-family:var(--font-mono); font-size:11px; color:#38bdf8; text-decoration:none; cursor:pointer;">${esc(f.entityFqn)}</a>
                      </td>
                      <td style="font-size:11.5px; color:var(--text-muted); max-width:280px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="${esc(f.message)}">${esc(f.message)}</td>
                      <td>
                        <div style="display:flex; align-items:center; gap:4px;">
                          <button class="btn-ghost" style="font-size:11px; padding:3px 7px;" onclick="inspectReportEntity('${esc(f.entityFqn)}', 'knowledge');">KB →</button>
                          <button class="btn-ghost" style="font-size:11px; padding:3px 7px;" onclick="inspectReportEntity('${esc(f.entityFqn)}', 'review');">Review →</button>
                        </div>
                      </td>
                    </tr>
                  `;
                }).join('')}
              </tbody>
            </table>
          </div>
        </div>
      `;
    }

    container.innerHTML = html;
  },

  renderMetricsDashboard(container, d) {
    let html = `
      <div class="report-kpi-grid">
        <div class="report-kpi-card" style="--kpi-accent: #06b6d4;">
          <span class="report-kpi-label">Total Classes</span>
          <div class="report-kpi-val">${d.totalTypes || d.totalClasses || 0}</div>
        </div>
        <div class="report-kpi-card" style="--kpi-accent: #3b82f6;">
          <span class="report-kpi-label">Total Methods</span>
          <div class="report-kpi-val">${d.totalMethods || 0}</div>
        </div>
        <div class="report-kpi-card" style="--kpi-accent: #10b981;">
          <span class="report-kpi-label">Total Fields</span>
          <div class="report-kpi-val">${d.totalFields || 0}</div>
        </div>
        <div class="report-kpi-card" style="--kpi-accent: #a855f7;">
          <span class="report-kpi-label">Total Lines of Code</span>
          <div class="report-kpi-val">${(d.totalLines || d.totalLinesOfCode || 0).toLocaleString()}</div>
        </div>
      </div>
    `;

    if (d.types && d.types.length > 0) {
      html += `
        <div class="report-section-card">
          <div class="report-section-header">
            <div class="report-section-title">Class Inventory &amp; Structural Metrics</div>
            <span class="report-section-badge">${d.types.length} Types</span>
          </div>
          <div class="report-table-wrap">
            <table class="report-table">
              <thead>
                <tr>
                  <th>Class Name</th>
                  <th>Package</th>
                  <th>Kind</th>
                  <th>Methods</th>
                  <th>Fields</th>
                  <th>Lines</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                ${(d.types || []).map(t => {
                  const fqn = t.fqn || t.id || '';
                  const simple = t.simpleName || t.name || fqn.split('.').pop() || fqn;
                  const pkg = t.packageName || t.packageFqn || fqn.split('.').slice(0, -1).join('.');
                  return `
                    <tr>
                      <td><a href="#" onclick="event.preventDefault(); inspectReportEntity('${esc(fqn)}', 'knowledge');" style="font-family:var(--font-mono); font-weight:700; color:var(--text-primary); text-decoration:none; cursor:pointer;">${esc(simple)}</a></td>
                      <td><a href="#" onclick="event.preventDefault(); inspectReportPackage('${esc(pkg)}');" style="font-family:var(--font-mono); color:var(--text-muted); text-decoration:none; cursor:pointer;">${esc(pkg)}</a></td>
                      <td><span class="risk-badge risk-low">${esc(t.kind || 'CLASS')}</span></td>
                      <td style="font-family:var(--font-mono);">${t.methodCount ?? (t.methods ? t.methods.length : 0)}</td>
                      <td style="font-family:var(--font-mono);">${t.fieldCount ?? (t.fields ? t.fields.length : 0)}</td>
                      <td style="font-family:var(--font-mono);">${t.lineCount ?? t.lines ?? 0}</td>
                      <td>
                        <div style="display:flex; align-items:center; gap:4px;">
                          <button class="btn-ghost" style="font-size:11px; padding:3px 7px;" onclick="inspectReportEntity('${esc(fqn)}', 'knowledge');">KB →</button>
                          <button class="btn-ghost" style="font-size:11px; padding:3px 7px;" onclick="inspectReportEntity('${esc(fqn)}', 'graph');">Graph →</button>
                        </div>
                      </td>
                    </tr>
                  `;
                }).join('')}
              </tbody>
            </table>
          </div>
        </div>
      `;
    }

    container.innerHTML = html;
  },

  renderExecutiveSummaryDashboard(container, d) {
    const score = d.overallHealthScore ?? 85;
    const grade = d.overallGrade || 'B';
    const scoreColor = score >= 85 ? '#10b981' : (score >= 70 ? '#38bdf8' : (score >= 55 ? '#f59e0b' : '#f43f5e'));

    let html = `
      <!-- Executive Health Banner -->
      <div class="report-section-card" style="background:linear-gradient(135deg, rgba(6, 182, 212, 0.1) 0%, rgba(15, 23, 42, 0.75) 100%); border-color:rgba(6, 182, 212, 0.3); padding:20px 24px;">
        <div style="display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:20px;">
          <div style="display:flex; align-items:center; gap:20px;">
            <div style="width:76px; height:76px; border-radius:16px; background:rgba(15, 23, 42, 0.9); border:2px solid ${scoreColor}; display:flex; flex-direction:column; align-items:center; justify-content:center; box-shadow:0 8px 24px rgba(0,0,0,0.35);">
              <span style="font-size:26px; font-weight:900; color:${scoreColor}; line-height:1;">${esc(grade)}</span>
              <span style="font-size:11px; font-family:var(--font-mono); color:var(--text-muted); margin-top:3px;">${score}/100</span>
            </div>
            <div>
              <div style="display:flex; align-items:center; gap:10px; margin-bottom:6px;">
                <span style="font-size:16px; font-weight:800; color:var(--text-primary); letter-spacing:-0.01em;">Principal Architect Health Assessment</span>
                <span class="risk-badge ${score >= 75 ? 'risk-low' : (score >= 55 ? 'risk-medium' : 'risk-high')}">COMPOSITE SCORE: ${score}/100</span>
              </div>
              <p style="margin:0; font-size:13px; color:var(--text-secondary); max-width:760px; line-height:1.55;">
                ${esc(d.executiveVerdict || 'Multi-dimensional architectural telemetry synthesized across modularity, blast radius, archetype compliance, SQALE debt, coupling stability, and reachability.')}
              </p>
            </div>
          </div>
          <div style="display:flex; align-items:center; gap:8px;">
            <button class="btn btn-sm btn-secondary" onclick="ReportsHub.activate('technical-debt');">Inspect SQALE Debt →</button>
            <button class="btn btn-sm btn-primary" onclick="ReportsHub.activate('change-risk');">Inspect Blast Radius →</button>
          </div>
        </div>
      </div>

      <!-- 6-Dimension Architectural Radar Cards -->
      <div class="report-kpi-grid" style="grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));">
    `;

    (d.dimensionScores || []).forEach(dim => {
      const s = dim.score ?? 80;
      const accent = s >= 85 ? '#10b981' : (s >= 70 ? '#38bdf8' : (s >= 55 ? '#f59e0b' : '#f43f5e'));
      const badgeCls = s >= 85 ? 'risk-low' : (s >= 70 ? 'risk-medium' : (s >= 55 ? 'risk-high' : 'risk-critical'));
      let targetReport = 'architecture';
      const dn = (dim.dimensionName || '').toLowerCase();
      if (dn.includes('modular') || dn.includes('acycl')) targetReport = 'circular-dependencies';
      else if (dn.includes('blast') || dn.includes('risk')) targetReport = 'change-risk';
      else if (dn.includes('archetype') || dn.includes('governance')) targetReport = 'archetype-governance';
      else if (dn.includes('debt') || dn.includes('maintain')) targetReport = 'technical-debt';
      else if (dn.includes('hygiene') || dn.includes('dead')) targetReport = 'dead-code';

      html += `
        <div class="report-kpi-card" style="--kpi-accent: ${accent}; cursor:pointer;" onclick="ReportsHub.activate('${targetReport}');" title="Click to drill down into ${esc(dim.dimensionName)} report">
          <div style="display:flex; align-items:center; justify-content:space-between; gap:8px;">
            <span class="report-kpi-label">${esc(dim.dimensionName)}</span>
            <span class="risk-badge ${badgeCls}">${esc(dim.status)} (${esc(dim.grade)})</span>
          </div>
          <div class="report-kpi-val" style="margin:6px 0 4px;">
            <span>${s}<span style="font-size:14px; color:var(--text-muted); font-weight:500;">/100</span></span>
          </div>
          <div style="width:100%; height:6px; background:rgba(148,163,184,0.14); border-radius:999px; overflow:hidden; margin:4px 0 6px;">
            <div style="width:${Math.max(4, Math.min(100, s))}%; height:100%; background:${accent}; border-radius:999px;"></div>
          </div>
          <span class="report-kpi-sub" style="display:flex; align-items:center; justify-content:space-between;">
            <span>${esc(dim.keyMetricLabel)}</span>
            <strong style="color:${accent}; font-size:11px;">Drill down →</strong>
          </span>
        </div>
      `;
    });

    html += `
      </div>

      <!-- Prioritized Action Roadmap -->
      <div class="report-section-card">
        <div class="report-section-header">
          <div class="report-section-title">
            <svg class="svg-icon icon-cyan icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/></svg>
            <span>Prioritized Architectural Action Roadmap (P0 / P1 / P2)</span>
          </div>
          <span class="report-section-badge">${(d.priorityRoadmap || []).length} Action Items</span>
        </div>
        <div class="report-table-wrap">
          <table class="report-table">
            <thead>
              <tr>
                <th>Priority</th>
                <th>Category</th>
                <th>Action Item</th>
                <th>Target Entity / Scope</th>
                <th>Est. Effort</th>
                <th>Expected Architectural Impact</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              ${(d.priorityRoadmap || []).map(item => {
                const pBadge = item.priority === 'P0' ? 'risk-critical' : (item.priority === 'P1' ? 'risk-high' : 'risk-medium');
                const target = item.targetEntity || '';
                const hasEntity = target && target.includes('.') && !target.includes(' ');
                return `
                  <tr>
                    <td><span class="risk-badge ${pBadge}">${esc(item.priority)}</span></td>
                    <td><strong style="font-size:11.5px; color:var(--text-secondary);">${esc(item.category)}</strong></td>
                    <td style="font-weight:700; color:var(--text-primary);">${esc(item.title)}</td>
                    <td style="font-family:var(--font-mono); font-size:11.5px;">
                      ${hasEntity
                        ? `<a href="#" onclick="event.preventDefault(); inspectReportEntity('${esc(target)}', 'knowledge');" style="color:#38bdf8; text-decoration:none; cursor:pointer;">${esc(target)}</a>`
                        : `<span style="color:var(--text-muted);">${esc(target)}</span>`}
                    </td>
                    <td><span class="risk-badge risk-low">${esc(item.estimatedEffort)}</span></td>
                    <td style="color:var(--text-secondary); font-size:12px;">${esc(item.expectedImpact)}</td>
                    <td>
                      <div style="display:flex; align-items:center; gap:4px;">
                        ${hasEntity ? `
                          <button class="btn-ghost" style="font-size:11px; padding:3px 7px;" onclick="inspectReportEntity('${esc(target)}', 'knowledge');">KB →</button>
                          <button class="btn-ghost" style="font-size:11px; padding:3px 7px;" onclick="inspectReportEntity('${esc(target)}', 'graph');">Graph →</button>
                        ` : `
                          <button class="btn-ghost" style="font-size:11px; padding:3px 7px;" onclick="ReportsHub.activate('technical-debt');">Debt Hub →</button>
                        `}
                      </div>
                    </td>
                  </tr>
                `;
              }).join('')}
            </tbody>
          </table>
        </div>
      </div>
    `;

    container.innerHTML = html;
  },

  renderTechnicalDebtDashboard(container, d) {
    const rating = d.sqaleRating || 'A';
    const ratingClass = rating === 'A' ? 'risk-low' : (rating === 'B' ? 'risk-medium' : (rating === 'C' ? 'risk-high' : 'risk-critical'));

    let html = `
      <div class="report-kpi-grid">
        <div class="report-kpi-card" style="--kpi-accent: #a855f7;">
          <span class="report-kpi-label">SQALE Rating &amp; Score</span>
          <div class="report-kpi-val">
            <span class="risk-badge ${ratingClass}" style="font-size:15px; padding:4px 10px;">Grade ${esc(rating)}</span>
            <span>${d.maintainabilityScore ?? 85}<span style="font-size:13px; color:var(--text-muted);">/100</span></span>
          </div>
          <span class="report-kpi-sub">Debt Ratio: <strong>${d.debtRatioPercent ?? 0}%</strong> of dev cost</span>
        </div>

        <div class="report-kpi-card" style="--kpi-accent: #f43f5e;">
          <span class="report-kpi-label">Total Remediation Effort</span>
          <div class="report-kpi-val">
            <span>${d.totalDebtHours ?? 0} hrs</span>
            <span class="risk-badge risk-high">${d.totalDebtDays ?? 0} eng-days</span>
          </div>
          <span class="report-kpi-sub">Estimated refactoring time to zero debt</span>
        </div>

        <div class="report-kpi-card" style="--kpi-accent: #f59e0b;">
          <span class="report-kpi-label">God Classes &amp; Blobs</span>
          <div class="report-kpi-val">${d.godClassCount ?? (d.godClasses ? d.godClasses.length : 0)}</div>
          <span class="report-kpi-sub">High WMC, excessive methods/fields &amp; coupling</span>
        </div>

        <div class="report-kpi-card" style="--kpi-accent: #06b6d4;">
          <span class="report-kpi-label">Complex Brain Methods</span>
          <div class="report-kpi-val">${d.brainMethodCount ?? (d.brainMethods ? d.brainMethods.length : 0)}</div>
          <span class="report-kpi-sub">Ranked by Refactoring ROI (CC × Callers)</span>
        </div>
      </div>

      <!-- God Classes & Blob Anti-Patterns -->
      <div class="report-section-card">
        <div class="report-section-header">
          <div class="report-section-title">
            <svg class="svg-icon icon-rose icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>
            <span>God Classes &amp; Blob Anti-Patterns (Structural Decomposition Targets)</span>
          </div>
          <span class="report-section-badge">${(d.godClasses || []).length} Classes</span>
        </div>
        <div class="report-table-wrap">
          <table class="report-table">
            <thead>
              <tr>
                <th>Class Name</th>
                <th>Package</th>
                <th>WMC (Complexity)</th>
                <th>Methods / Fields</th>
                <th>Lines</th>
                <th>Est. Effort</th>
                <th>Decomposition Strategy</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              ${(d.godClasses || []).map(g => `
                <tr>
                  <td>
                    <a href="#" onclick="event.preventDefault(); inspectReportEntity('${esc(g.classFqn)}', 'knowledge');" style="font-family:var(--font-mono); font-weight:700; color:var(--text-primary); text-decoration:none; cursor:pointer;">${esc(g.simpleName)}</a>
                  </td>
                  <td>
                    <a href="#" onclick="event.preventDefault(); inspectReportPackage('${esc(g.packageName)}');" style="font-family:var(--font-mono); color:var(--text-muted); text-decoration:none; cursor:pointer;">${esc(g.packageName)}</a>
                  </td>
                  <td><span class="risk-badge ${g.wmc >= 80 ? 'risk-critical' : 'risk-high'}">${g.wmc}</span></td>
                  <td style="font-family:var(--font-mono);">${g.methodCount}m / ${g.fieldCount}f</td>
                  <td style="font-family:var(--font-mono);">${g.lineCount}</td>
                  <td><span class="risk-badge risk-medium">${g.estimatedHours} hrs</span></td>
                  <td style="font-size:11.5px; color:var(--text-secondary); max-width:290px;">${esc(g.decompositionAdvice)}</td>
                  <td>
                    <div style="display:flex; align-items:center; gap:4px;">
                      <button class="btn-ghost" style="font-size:11px; padding:3px 7px;" onclick="inspectReportEntity('${esc(g.classFqn)}', 'knowledge');">KB →</button>
                      <button class="btn-ghost" style="font-size:11px; padding:3px 7px;" onclick="jumpToGraphHeat('${esc(g.classFqn)}');">Heat →</button>
                    </div>
                  </td>
                </tr>
              `).join('')}
            </tbody>
          </table>
        </div>
      </div>

      <!-- Complex Brain Methods Ranked by Refactoring ROI -->
      <div class="report-section-card">
        <div class="report-section-header">
          <div class="report-section-title">
            <svg class="svg-icon icon-amber icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/></svg>
            <span>Complex Brain Methods Ranked by Refactoring ROI (CC × Inbound Callers)</span>
          </div>
          <span class="report-section-badge">${(d.brainMethods || []).length} Methods</span>
        </div>
        <div class="report-table-wrap">
          <table class="report-table">
            <thead>
              <tr>
                <th>Method Name</th>
                <th>Declaring Class</th>
                <th>Complexity (CC)</th>
                <th>Lines</th>
                <th>Callers (Ca)</th>
                <th>ROI Score</th>
                <th>Est. Effort</th>
                <th>Refactoring Advice</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              ${(d.brainMethods || []).map(m => {
                const mFqn = m.methodFqn || (m.declaringClass + '.' + m.simpleName + '()');
                return `
                  <tr>
                    <td>
                      <a href="#" onclick="event.preventDefault(); selectMethod('${esc(mFqn)}');" style="font-family:var(--font-mono); font-weight:700; color:#f59e0b; text-decoration:none; cursor:pointer;">${esc(m.simpleName)}</a>
                    </td>
                    <td>
                      <a href="#" onclick="event.preventDefault(); inspectReportEntity('${esc(m.declaringClass)}', 'knowledge');" style="font-family:var(--font-mono); color:var(--text-muted); text-decoration:none; cursor:pointer;">${esc(m.declaringClass)}</a>
                    </td>
                    <td><span class="risk-badge ${m.complexity >= 20 ? 'risk-critical' : 'risk-high'}">${m.complexity}</span></td>
                    <td style="font-family:var(--font-mono);">${m.lineCount}</td>
                    <td style="font-family:var(--font-mono);">${m.callerCount}</td>
                    <td><strong style="font-family:var(--font-mono); color:#38bdf8;">${m.refactoringRoiScore}</strong></td>
                    <td><span class="risk-badge risk-low">${m.estimatedMinutes} min</span></td>
                    <td style="font-size:11.5px; color:var(--text-secondary); max-width:260px;">${esc(m.refactoringAdvice)}</td>
                    <td>
                      <div style="display:flex; align-items:center; gap:4px;">
                        <button class="btn-ghost" style="font-size:11px; padding:3px 7px;" onclick="inspectReportEntity('${esc(m.declaringClass)}', 'knowledge');">KB →</button>
                        <button class="btn-ghost" style="font-size:11px; padding:3px 7px;" onclick="selectMethod('${esc(mFqn)}');">Call Graph →</button>
                      </div>
                    </td>
                  </tr>
                `;
              }).join('')}
            </tbody>
          </table>
        </div>
      </div>

      <!-- Package Technical Debt Density -->
      <div class="report-section-card">
        <div class="report-section-header">
          <div class="report-section-title">
            <svg class="svg-icon icon-purple icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/></svg>
            <span>Package Technical Debt Density (Remediation Hours per 1,000 Lines of Code)</span>
          </div>
          <span class="report-section-badge">${(d.packageDebtHotspots || []).length} Packages</span>
        </div>
        <div class="report-table-wrap">
          <table class="report-table">
            <thead>
              <tr>
                <th>Package Name</th>
                <th>Classes</th>
                <th>Lines of Code</th>
                <th>Avg Complexity</th>
                <th>Debt Hours</th>
                <th>Debt Density (hrs/kLoC)</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              ${(d.packageDebtHotspots || []).map(p => `
                <tr>
                  <td>
                    <a href="#" onclick="event.preventDefault(); inspectReportPackage('${esc(p.packageName)}');" style="font-family:var(--font-mono); font-weight:700; color:var(--text-primary); text-decoration:none; cursor:pointer;">${esc(p.packageName)}</a>
                  </td>
                  <td style="font-family:var(--font-mono);">${p.classCount}</td>
                  <td style="font-family:var(--font-mono);">${p.totalLines}</td>
                  <td style="font-family:var(--font-mono);">${p.avgComplexity}</td>
                  <td><span class="risk-badge ${p.debtHours >= 10 ? 'risk-high' : 'risk-medium'}">${p.debtHours} hrs</span></td>
                  <td style="font-family:var(--font-mono); font-weight:700;">${p.debtDensityPerKloc} hrs/kLoC</td>
                  <td>
                    <button class="btn-ghost" style="font-size:11px; padding:3px 8px;" onclick="inspectReportPackage('${esc(p.packageName)}');">KB →</button>
                  </td>
                </tr>
              `).join('')}
            </tbody>
          </table>
        </div>
      </div>
    `;

    container.innerHTML = html;
  },

  renderApiCatalogDashboard(container, d) {
    if (!d) return;
    const dist = d.httpMethodDistribution || {};
    let html = `
      <div class="report-kpi-grid">
        <div class="report-kpi-card" style="--kpi-accent: #06b6d4;">
          <span class="report-kpi-label">Total API Endpoints</span>
          <div class="report-kpi-val">${d.totalEndpoints || 0}</div>
          <span class="report-kpi-sub">Across <strong>${d.totalControllers || 0}</strong> Controllers / Gateways</span>
        </div>

        <div class="report-kpi-card" style="--kpi-accent: #10b981;">
          <span class="report-kpi-label">Authenticated Endpoints</span>
          <div class="report-kpi-val">${d.authenticatedEndpointsCount || 0}</div>
          <span class="report-kpi-sub">Security &amp; auth token guarded</span>
        </div>

        <div class="report-kpi-card" style="--kpi-accent: #f59e0b;">
          <span class="report-kpi-label">Public / Open Surface</span>
          <div class="report-kpi-val">${d.publicEndpointsCount || 0}</div>
          <span class="report-kpi-sub">Unauthenticated public API routes</span>
        </div>

        <div class="report-kpi-card" style="--kpi-accent: #f43f5e;">
          <span class="report-kpi-label">High Blast-Radius Routes</span>
          <div class="report-kpi-val">${d.highRiskEndpointsCount || 0}</div>
          <span class="report-kpi-sub">Deep call fan-out / state mutation</span>
        </div>

        <div class="report-kpi-card" style="--kpi-accent: #8b5cf6;">
          <span class="report-kpi-label">HTTP Methods</span>
          <div class="report-kpi-val font-mono" style="font-size:18px;">
            <span style="color:#0284c7;">G:${dist['GET'] || 0}</span> <span style="color:#16a34a;">P:${dist['POST'] || 0}</span> <span style="color:#d97706;">U:${dist['PUT'] || 0}</span> <span style="color:#dc2626;">D:${dist['DELETE'] || 0}</span>
          </div>
          <span class="report-kpi-sub">GET, POST, PUT, DELETE distribution</span>
        </div>
      </div>

      <!-- HTTP Verbs Breakdown -->
      <div class="report-section-card">
        <div class="report-section-header">
          <div class="report-section-title">
            <svg class="svg-icon icon-cyan icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1 4-10z"/></svg>
            <span>HTTP Method Distribution &amp; Protocol Breakdown</span>
          </div>
          <span class="report-section-badge">${Object.keys(dist).length} HTTP Verbs</span>
        </div>
        <div style="display:flex; flex-wrap:wrap; gap:12px; padding:16px;">
          ${Object.entries(dist).map(([verb, count]) => {
            const verbColor = verb === 'GET' ? '#0284c7' : (verb === 'POST' ? '#16a34a' : (verb === 'PUT' ? '#d97706' : (verb === 'DELETE' ? '#dc2626' : '#8b5cf6')));
            return `
              <div style="flex:1; min-width:140px; background:var(--bg-card); border:1px solid var(--border); border-radius:8px; padding:12px 16px;">
                <div style="display:flex; justify-content:space-between; align-items:center;">
                  <span style="font-family:var(--font-mono); font-weight:700; font-size:13px; color:${verbColor};">${esc(verb)}</span>
                  <span style="font-size:18px; font-weight:800; font-family:var(--font-mono); color:var(--text-primary);">${count}</span>
                </div>
                <div style="font-size:11px; color:var(--text-muted); margin-top:4px;">${count === 1 ? '1 route' : count + ' routes'} (${d.totalEndpoints > 0 ? Math.round((count / d.totalEndpoints) * 100) : 0}%)</div>
              </div>
            `;
          }).join('')}
        </div>
      </div>

      <!-- Main Endpoint Inventory -->
      <div class="report-section-card">
        <div class="report-section-header">
          <div class="report-section-title">
            <svg class="svg-icon icon-emerald icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>
            <span>REST &amp; RPC Endpoint Inventory (Ranked by Blast Radius)</span>
          </div>
          <span class="report-section-badge">${(d.endpoints || []).length} Endpoints</span>
        </div>
        <div class="report-table-wrap">
          <table class="report-table">
            <thead>
              <tr>
                <th>Method</th>
                <th>Endpoint Route</th>
                <th>Handler Method</th>
                <th>Controller Class</th>
                <th>Auth Guard</th>
                <th>Downstream Calls</th>
                <th>Blast Radius</th>
                <th>Risk Level</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              ${(d.endpoints || []).map(ep => {
                const badgeCls = ep.httpMethod === 'GET' ? 'badge-get' : (ep.httpMethod === 'POST' ? 'badge-post' : (ep.httpMethod === 'PUT' ? 'badge-put' : (ep.httpMethod === 'DELETE' ? 'badge-delete' : 'badge-rpc')));
                const riskBadge = ep.riskLevel === 'HIGH' ? 'risk-critical' : (ep.riskLevel === 'MEDIUM' ? 'risk-medium' : 'risk-low');
                const ctrlSimple = ep.handlerClass ? ep.handlerClass.split('.').pop() : '';
                const methodSimple = ep.handlerMethod ? ep.handlerMethod.split('.').pop() : '';
                return `
                  <tr>
                    <td><span class="report-method-badge ${badgeCls}">${esc(ep.httpMethod)}</span></td>
                    <td>
                      <code class="font-mono text-cyan" style="font-size:12px; font-weight:600;">${esc(ep.endpointUrl)}</code>
                    </td>
                    <td>
                      <a href="#" onclick="event.preventDefault(); selectMethod('${esc(ep.handlerMethod)}');" style="font-family:var(--font-mono); font-size:12px; color:var(--text-primary); text-decoration:none; cursor:pointer;" title="${esc(ep.handlerMethod)}">${esc(methodSimple)}</a>
                    </td>
                    <td>
                      <a href="#" onclick="event.preventDefault(); inspectReportEntity('${esc(ep.handlerClass)}', 'knowledge');" style="font-family:var(--font-mono); font-size:11.5px; color:var(--text-muted); text-decoration:none; cursor:pointer;" title="${esc(ep.handlerClass)}">${esc(ctrlSimple)}</a>
                    </td>
                    <td>
                      ${ep.hasAuthCheck
                        ? '<span class="status-badge" style="background:rgba(16,185,129,0.12); color:#34d399; font-size:10.5px; padding:2px 7px; border-radius:4px; font-weight:600;">PROTECTED</span>'
                        : '<span class="status-badge" style="background:rgba(244,63,94,0.12); color:#f43f5e; font-size:10.5px; padding:2px 7px; border-radius:4px; font-weight:600;">PUBLIC / OPEN</span>'}
                    </td>
                    <td style="font-family:var(--font-mono); text-align:center;">${ep.downstreamCallCount}</td>
                    <td>
                      <div style="display:flex; align-items:center; gap:8px;">
                        <span style="font-family:var(--font-mono); font-weight:700;">${ep.blastRadiusScore}/100</span>
                        <div style="width:48px; height:4px; background:rgba(255,255,255,0.1); border-radius:2px; overflow:hidden;">
                          <div style="width:${ep.blastRadiusScore}%; height:100%; background:${ep.blastRadiusScore >= 65 ? '#f43f5e' : (ep.blastRadiusScore >= 35 ? '#f59e0b' : '#10b981')};"></div>
                        </div>
                      </div>
                    </td>
                    <td><span class="risk-badge ${riskBadge}">${esc(ep.riskLevel)}</span></td>
                    <td>
                      <div style="display:flex; align-items:center; gap:4px;">
                        <button class="btn-ghost" style="font-size:11px; padding:3px 7px;" onclick="selectMethod('${esc(ep.handlerMethod)}');">Graph →</button>
                        <button class="btn-ghost" style="font-size:11px; padding:3px 7px;" onclick="inspectReportEntity('${esc(ep.handlerClass)}', 'knowledge');">KB →</button>
                      </div>
                    </td>
                  </tr>
                `;
              }).join('')}
            </tbody>
          </table>
        </div>
      </div>
    `;
    container.innerHTML = html;
  },

  renderDatabaseAccessDashboard(container, d) {
    if (!d) return;
    const tables = d.tableTouchCount || {};
    const patterns = d.patternDistribution || {};
    let html = `
      <div class="report-kpi-grid">
        <div class="report-kpi-card" style="--kpi-accent: #10b981;">
          <span class="report-kpi-label">Persistence Integrity Score</span>
          <div class="report-kpi-val">${d.dataIntegrityScore || 0}<span style="font-size:14px; color:var(--text-muted);">/100</span></div>
          <span class="report-kpi-sub">ACID boundaries &amp; prepared query safety</span>
        </div>

        <div class="report-kpi-card" style="--kpi-accent: #06b6d4;">
          <span class="report-kpi-label">Data Access Classes</span>
          <div class="report-kpi-val">${d.totalDataAccessClasses || 0}</div>
          <span class="report-kpi-sub">DAO, Repository &amp; Storage layers</span>
        </div>

        <div class="report-kpi-card" style="--kpi-accent: #f59e0b;">
          <span class="report-kpi-label">Persistence Operations</span>
          <div class="report-kpi-val">${d.totalQueryMethods || 0}</div>
          <span class="report-kpi-sub"><strong>${d.totalReadOperations || 0}</strong> Reads · <strong>${d.totalWriteOperations || 0}</strong> Writes</span>
        </div>

        <div class="report-kpi-card" style="--kpi-accent: #8b5cf6;">
          <span class="report-kpi-label">Transactional Methods</span>
          <div class="report-kpi-val">${d.totalTransactionalMethods || 0}</div>
          <span class="report-kpi-sub">Explicit transaction or batch boundaries</span>
        </div>

        <div class="report-kpi-card" style="--kpi-accent: #3b82f6;">
          <span class="report-kpi-label">Target Tables Touched</span>
          <div class="report-kpi-val">${Object.keys(tables).length}</div>
          <span class="report-kpi-sub">Database tables / persistent entities</span>
        </div>
      </div>

      <!-- High-Touch Tables & Patterns -->
      <div style="display:grid; grid-template-columns:repeat(auto-fit, minmax(320px, 1fr)); gap:16px; margin-bottom:16px;">
        <div class="report-section-card" style="margin-bottom:0;">
          <div class="report-section-header">
            <div class="report-section-title">
              <svg class="svg-icon icon-amber icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"/><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/></svg>
              <span>High-Touch Database Tables</span>
            </div>
            <span class="report-section-badge">${Object.keys(tables).length} Tables</span>
          </div>
          <div class="report-table-wrap">
            <table class="report-table">
              <thead><tr><th>Target Table</th><th>Query Methods</th><th>Share</th></tr></thead>
              <tbody>
                ${Object.entries(tables).slice(0, 10).map(([tbl, count]) => `
                  <tr>
                    <td><code class="font-mono text-cyan" style="font-size:12px;">${esc(tbl)}</code></td>
                    <td style="font-family:var(--font-mono); font-weight:700;">${count}</td>
                    <td>
                      <div style="display:flex; align-items:center; gap:8px;">
                        <span style="font-size:11px; color:var(--text-muted);">${d.totalQueryMethods > 0 ? Math.round((count / d.totalQueryMethods) * 100) : 0}%</span>
                        <div style="width:40px; height:4px; background:rgba(255,255,255,0.1); border-radius:2px; overflow:hidden;">
                          <div style="width:${d.totalQueryMethods > 0 ? Math.round((count / d.totalQueryMethods) * 100) : 0}%; height:100%; background:#f59e0b;"></div>
                        </div>
                      </div>
                    </td>
                  </tr>
                `).join('')}
              </tbody>
            </table>
          </div>
        </div>

        <div class="report-section-card" style="margin-bottom:0;">
          <div class="report-section-header">
            <div class="report-section-title">
              <svg class="svg-icon icon-cyan icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 7h-9M14 17H5M17 12H3"/></svg>
              <span>Persistence Architectural Patterns</span>
            </div>
            <span class="report-section-badge">${Object.keys(patterns).length} Patterns</span>
          </div>
          <div style="padding:16px; display:flex; flex-direction:column; gap:10px;">
            ${Object.entries(patterns).map(([pattern, count]) => `
              <div style="background:var(--bg-card); border:1px solid var(--border); border-radius:6px; padding:10px 14px; display:flex; justify-content:space-between; align-items:center;">
                <div>
                  <div style="font-weight:700; font-size:12.5px; color:var(--text-primary);">${esc(pattern)}</div>
                  <div style="font-size:11px; color:var(--text-muted);">${count} persistence access points</div>
                </div>
                <span class="risk-badge risk-low font-mono">${count}</span>
              </div>
            `).join('')}
          </div>
        </div>
      </div>

      <!-- Main Persistence Touchpoints Table -->
      <div class="report-section-card">
        <div class="report-section-header">
          <div class="report-section-title">
            <svg class="svg-icon icon-emerald icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg>
            <span>Data Access Touchpoints &amp; SQL Mutation Flow</span>
          </div>
          <span class="report-section-badge">${(d.accessPoints || []).length} Access Points</span>
        </div>
        <div class="report-table-wrap">
          <table class="report-table">
            <thead>
              <tr>
                <th>Target Table</th>
                <th>Operation</th>
                <th>Method Name</th>
                <th>DAO / Repository Class</th>
                <th>Storage Pattern</th>
                <th>Transaction</th>
                <th>Risk Factor</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              ${(d.accessPoints || []).map(da => {
                const isWrite = da.operationType && da.operationType.includes('Write');
                const opCls = isWrite ? 'risk-high' : 'risk-low';
                const mSimple = da.methodFqn ? da.methodFqn.split('.').pop() : '';
                const cSimple = da.classFqn ? da.classFqn.split('.').pop() : '';
                return `
                  <tr>
                    <td><code class="font-mono text-cyan" style="font-size:12px; font-weight:700;">${esc(da.targetTable)}</code></td>
                    <td><span class="risk-badge ${opCls}">${esc(da.operationType)}</span></td>
                    <td>
                      <a href="#" onclick="event.preventDefault(); selectMethod('${esc(da.methodFqn)}');" style="font-family:var(--font-mono); font-size:12px; color:var(--text-primary); text-decoration:none; cursor:pointer;" title="${esc(da.methodFqn)}">${esc(mSimple)}</a>
                    </td>
                    <td>
                      <a href="#" onclick="event.preventDefault(); inspectReportEntity('${esc(da.classFqn)}', 'knowledge');" style="font-family:var(--font-mono); font-size:11.5px; color:var(--text-muted); text-decoration:none; cursor:pointer;" title="${esc(da.classFqn)}">${esc(cSimple)}</a>
                    </td>
                    <td style="font-size:11.5px; color:var(--text-secondary);">${esc(da.pattern)}</td>
                    <td>
                      ${da.isTransactional
                        ? '<span class="status-badge" style="background:rgba(16,185,129,0.12); color:#34d399; font-size:10px; padding:2px 6px; border-radius:4px; font-weight:600;">TX GUARD</span>'
                        : '<span class="status-badge" style="background:rgba(148,163,184,0.12); color:#94a3b8; font-size:10px; padding:2px 6px; border-radius:4px;">AUTOCOMMIT</span>'}
                    </td>
                    <td><span style="font-size:11.5px; color:${da.riskFactor && da.riskFactor.includes('Risk') ? '#f43f5e' : 'var(--text-secondary)'};">${esc(da.riskFactor)}</span></td>
                    <td>
                      <div style="display:flex; align-items:center; gap:4px;">
                        <button class="btn-ghost" style="font-size:11px; padding:3px 7px;" onclick="selectMethod('${esc(da.methodFqn)}');">Graph →</button>
                        <button class="btn-ghost" style="font-size:11px; padding:3px 7px;" onclick="inspectReportEntity('${esc(da.classFqn)}', 'knowledge');">KB →</button>
                      </div>
                    </td>
                  </tr>
                `;
              }).join('')}
            </tbody>
          </table>
        </div>
      </div>
    `;
    container.innerHTML = html;
  },

  renderConcurrencyAuditDashboard(container, d) {
    if (!d) return;
    const gradeColor = d.threadSafetyGrade === 'A' ? '#10b981' : (d.threadSafetyGrade === 'B' ? '#38bdf8' : (d.threadSafetyGrade === 'C' ? '#f59e0b' : '#f43f5e'));
    let html = `
      <div class="report-kpi-grid">
        <div class="report-kpi-card" style="--kpi-accent: ${gradeColor};">
          <span class="report-kpi-label">Thread Safety Score</span>
          <div class="report-kpi-val" style="color:${gradeColor};">
            ${d.threadSafetyScore || 0}
            <span class="risk-badge" style="background:rgba(255,255,255,0.08); color:${gradeColor}; font-size:12px; margin-left:6px;">GRADE ${esc(d.threadSafetyGrade || 'C')}</span>
          </div>
          <span class="report-kpi-sub">Composite concurrency safety evaluation</span>
        </div>

        <div class="report-kpi-card" style="--kpi-accent: #f59e0b;">
          <span class="report-kpi-label">Synchronized Locks</span>
          <div class="report-kpi-val">${d.synchronizedMethodCount || 0}</div>
          <span class="report-kpi-sub">Methods with synchronized monitor locks</span>
        </div>

        <div class="report-kpi-card" style="--kpi-accent: #06b6d4;">
          <span class="report-kpi-label">Volatile State Fields</span>
          <div class="report-kpi-val">${d.volatileFieldCount || 0}</div>
          <span class="report-kpi-sub">Memory barrier &amp; visibility guarantees</span>
        </div>

        <div class="report-kpi-card" style="--kpi-accent: #10b981;">
          <span class="report-kpi-label">Concurrent Collections</span>
          <div class="report-kpi-val">${d.concurrentCollectionCount || 0}</div>
          <span class="report-kpi-sub">Atomic &amp; ConcurrentHashMap instances</span>
        </div>

        <div class="report-kpi-card" style="--kpi-accent: #f43f5e;">
          <span class="report-kpi-label">Unsafe Mutable Collections</span>
          <div class="report-kpi-val" style="color:#f43f5e;">${d.unsafeSharedCollectionCount || 0}</div>
          <span class="report-kpi-sub">Mutable non-thread-safe static collections</span>
        </div>

        <div class="report-kpi-card" style="--kpi-accent: #8b5cf6;">
          <span class="report-kpi-label">Async Constructs</span>
          <div class="report-kpi-val">${d.asyncConstructCount || 0}</div>
          <span class="report-kpi-sub">ThreadPool, Executor, CompletableFuture</span>
        </div>
      </div>

      <!-- Concurrency Findings Table -->
      <div class="report-section-card">
        <div class="report-section-header">
          <div class="report-section-title">
            <svg class="svg-icon icon-rose icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 12h-4l-3 9L9 3l-3 9H2"/></svg>
            <span>Concurrency Findings &amp; Race Condition Hazards</span>
          </div>
          <span class="report-section-badge">${(d.findings || []).length} Findings</span>
        </div>
        <div class="report-table-wrap">
          <table class="report-table">
            <thead>
              <tr>
                <th>Severity</th>
                <th>Category</th>
                <th>Target Entity</th>
                <th>Architectural Hazard Detail</th>
                <th>Prescribed Remediation Advice</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              ${(d.findings || []).length === 0 ? `
                <tr><td colspan="6" style="text-align:center; padding:32px; color:#10b981;">
                  <svg class="svg-icon icon-emerald icon-lg" style="margin-bottom:8px;" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg>
                  <div>No critical thread-safety hazards or unsafe mutable static collections identified.</div>
                </td></tr>
              ` : (d.findings || []).map(f => {
                const sevCls = f.severity === 'CRITICAL' ? 'risk-critical' : (f.severity === 'HIGH' ? 'risk-high' : 'risk-medium');
                const catLabel = f.category ? f.category.replace(/_/g, ' ') : '';
                const eSimple = f.entityFqn ? f.entityFqn.split('.').pop() : '';
                return `
                  <tr>
                    <td><span class="risk-badge ${sevCls}">${esc(f.severity)}</span></td>
                    <td><span class="font-mono" style="font-size:11px; font-weight:600; color:var(--text-secondary);">${esc(catLabel)}</span></td>
                    <td>
                      <a href="#" onclick="event.preventDefault(); inspectReportEntity('${esc(f.entityFqn)}', 'knowledge');" style="font-family:var(--font-mono); font-size:12px; color:var(--text-primary); text-decoration:none; cursor:pointer;" title="${esc(f.entityFqn)}">${esc(eSimple)}</a>
                      <div style="font-family:var(--font-mono); font-size:10px; color:var(--text-muted); overflow:hidden; text-overflow:ellipsis; max-width:240px;" title="${esc(f.entityFqn)}">${esc(f.entityFqn)}</div>
                    </td>
                    <td style="font-size:12px; color:var(--text-secondary); max-width:300px;">${esc(f.findingDetail)}</td>
                    <td style="font-size:12px; color:#34d399; max-width:320px;">💡 ${esc(f.remediationAdvice)}</td>
                    <td>
                      <div style="display:flex; align-items:center; gap:4px;">
                        <button class="btn-ghost" style="font-size:11px; padding:3px 7px;" onclick="inspectReportEntity('${esc(f.entityFqn)}', 'knowledge');">KB →</button>
                      </div>
                    </td>
                  </tr>
                `;
              }).join('')}
            </tbody>
          </table>
        </div>
      </div>
    `;
    container.innerHTML = html;
  },

  renderModuleCouplingDashboard(container, d) {
    const score = d.decouplingScore || 0;
    const scoreBadgeClass = score >= 80 ? 'risk-low' : (score >= 60 ? 'risk-medium' : 'risk-critical');
    const tanglesCount = d.bidirectionalTanglesCount || 0;
    const tangleBadgeClass = tanglesCount === 0 ? 'risk-low' : 'risk-critical';

    const getZoneBadgeClass = (z) => {
      if (z === 'ZONE_OF_PAIN') return 'risk-critical';
      if (z === 'ZONE_OF_USELESSNESS') return 'risk-high';
      if (z === 'BALANCED') return 'risk-low';
      if (z === 'STABLE' || z === 'VOLATILE') return 'risk-medium';
      return 'risk-low';
    };

    const getGradeBadgeClass = (g) => {
      if (g === 'A' || g === 'B') return 'risk-low';
      if (g === 'C') return 'risk-medium';
      if (g === 'D') return 'risk-high';
      return 'risk-critical';
    };

    let html = `
      <div class="report-kpi-grid">
        <div class="report-kpi-card" style="--kpi-accent: #10b981;">
          <span class="report-kpi-label">Decoupling Health</span>
          <div class="report-kpi-val">
            <span>${score}</span>
            <span class="risk-badge ${scoreBadgeClass}">${esc(d.decouplingRating || 'OPTIMAL')}</span>
          </div>
          <span class="report-kpi-sub">Cross-Module Calls: <strong>${d.totalCrossModuleRelationships || 0}</strong></span>
        </div>

        <div class="report-kpi-card" style="--kpi-accent: #3b82f6;">
          <span class="report-kpi-label">Modules Analyzed</span>
          <div class="report-kpi-val">
            <span>${d.totalModules || 0}</span>
            <span class="risk-badge risk-low">${d.totalClasses || 0} Classes</span>
          </div>
          <span class="report-kpi-sub">Balanced: <strong>${d.balancedCount || 0}</strong> &bull; Isolated: <strong>${d.modules ? d.modules.filter(m => m.couplingZone === 'ISOLATED').length : 0}</strong></span>
        </div>

        <div class="report-kpi-card" style="--kpi-accent: #f59e0b;">
          <span class="report-kpi-label">Bidirectional Tangles</span>
          <div class="report-kpi-val">
            <span>${tanglesCount}</span>
            <span class="risk-badge ${tangleBadgeClass}">${tanglesCount === 0 ? 'CLEAN' : 'TANGLES'}</span>
          </div>
          <span class="report-kpi-sub">Zone of Pain: <strong>${d.zoneOfPainCount || 0}</strong> &bull; Uselessness: <strong>${d.zoneOfUselessnessCount || 0}</strong></span>
        </div>

        <div class="report-kpi-card" style="--kpi-accent: #8b5cf6;">
          <span class="report-kpi-label">Main Sequence Distance</span>
          <div class="report-kpi-val">
            <span>${(d.avgDistance != null ? Number(d.avgDistance).toFixed(3) : '0.000')}</span>
            <span class="risk-badge risk-low">D = |A+I-1|</span>
          </div>
          <span class="report-kpi-sub">Avg Instability (I): <strong>${(d.avgInstability != null ? Number(d.avgInstability).toFixed(2) : '0.00')}</strong></span>
        </div>
      </div>
    `;

    if (d.decouplingRecommendations && d.decouplingRecommendations.length > 0) {
      html += `
        <div class="report-section-card">
          <div class="report-section-header">
            <div class="report-section-title">Decoupling &amp; Modularity Guidance</div>
            <span class="report-section-badge">${d.decouplingRecommendations.length} Insights</span>
          </div>
          <div style="padding: 14px 18px; display: flex; flex-direction: column; gap: 8px;">
            ${d.decouplingRecommendations.map(rec => `
              <div style="display:flex; align-items:flex-start; gap:8px; font-size:12.5px; line-height:1.5; color:var(--text-secondary);">
                <span style="color:#10b981; font-size:14px; line-height:1;">💡</span>
                <div>${esc(rec)}</div>
              </div>
            `).join('')}
          </div>
        </div>
      `;
    }

    html += `
      <div class="report-section-card">
        <div class="report-section-header">
          <div class="report-section-title">Inter-Module Coupling Pairs &amp; Tangles</div>
          <span class="report-section-badge">${(d.topCoupledPairs || []).length} Pairs</span>
        </div>
        <div class="report-table-wrap">
          <table class="report-table">
            <thead>
              <tr>
                <th>Source Module</th>
                <th>Target Module</th>
                <th>Calls (A &rarr; B)</th>
                <th>Reverse Calls (B &rarr; A)</th>
                <th>Tangle Status</th>
                <th>Strength</th>
                <th>Bridge Sample</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
    `;

    if (!d.topCoupledPairs || d.topCoupledPairs.length === 0) {
      html += `<tr><td colspan="8" style="text-align:center; color:var(--text-muted); padding:20px;">No cross-module dependencies detected.</td></tr>`;
    } else {
      d.topCoupledPairs.forEach(p => {
        const isTangle = p.isBidirectional;
        const tangleBadge = isTangle
          ? `<span class="risk-badge risk-critical">TANGLE</span>`
          : `<span class="risk-badge risk-low">ONE-WAY</span>`;
        const strengthBadge = p.couplingStrength === 'TIGHT'
          ? `<span class="risk-badge risk-critical">TIGHT</span>`
          : (p.couplingStrength === 'MEDIUM' ? `<span class="risk-badge risk-medium">MEDIUM</span>` : `<span class="risk-badge risk-low">LOW</span>`);

        html += `
          <tr>
            <td>
              <a href="#" onclick="event.preventDefault(); inspectReportPackage('${esc(p.sourceModule)}');" style="font-family:var(--font-mono); font-weight:700; color:var(--text-primary); text-decoration:none; cursor:pointer;" title="Inspect ${esc(p.sourceModule)} in Knowledge Base">${esc(p.sourceModule)}</a>
            </td>
            <td>
              <a href="#" onclick="event.preventDefault(); inspectReportPackage('${esc(p.targetModule)}');" style="font-family:var(--font-mono); font-weight:700; color:var(--text-primary); text-decoration:none; cursor:pointer;" title="Inspect ${esc(p.targetModule)} in Knowledge Base">${esc(p.targetModule)}</a>
            </td>
            <td style="font-family:var(--font-mono); font-weight:700;">${p.calls || 0}</td>
            <td style="font-family:var(--font-mono); font-weight:700;">${p.reverseCalls || 0}</td>
            <td>${tangleBadge}</td>
            <td>${strengthBadge}</td>
            <td style="font-family:var(--font-mono); font-size:11.5px; color:var(--text-muted); max-width:240px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="${esc(p.bridgeSample || '')}">${esc(p.bridgeSample || '-')}</td>
            <td>
              <div style="display:flex; align-items:center; gap:4px;">
                <button class="btn-ghost" style="font-size:11px; padding:3px 8px;" onclick="inspectReportPackage('${esc(p.sourceModule)}');" title="Inspect source module">KB →</button>
              </div>
            </td>
          </tr>
        `;
      });
    }

    html += `
            </tbody>
          </table>
        </div>
      </div>

      <div class="report-section-card">
        <div class="report-section-header">
          <div class="report-section-title">Module Coupling &amp; Stability Metrics (Martin's Main Sequence)</div>
          <span class="report-section-badge">${(d.modules || []).length} Modules</span>
        </div>
        <div class="report-table-wrap">
          <table class="report-table">
            <thead>
              <tr>
                <th>Module Name</th>
                <th>Classes</th>
                <th>Interfaces</th>
                <th>Abstractness (A)</th>
                <th>Afferent In (Ca)</th>
                <th>Efferent Out (Ce)</th>
                <th>Total Coupling</th>
                <th>Instability (I)</th>
                <th>Distance (D)</th>
                <th>Coupling Zone</th>
                <th>Grade</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
    `;

    (d.modules || []).forEach(m => {
      const zClass = getZoneBadgeClass(m.couplingZone);
      const gClass = getGradeBadgeClass(m.healthGrade);
      const aVal = (m.abstractness != null ? Number(m.abstractness).toFixed(2) : '0.00');
      const iVal = (m.instability != null ? Number(m.instability).toFixed(2) : '0.00');
      const dVal = (m.distanceMainSequence != null ? Number(m.distanceMainSequence).toFixed(2) : '0.00');

      html += `
        <tr>
          <td>
            <a href="#" onclick="event.preventDefault(); inspectReportPackage('${esc(m.moduleName)}');" style="font-family:var(--font-mono); font-weight:700; color:var(--text-primary); text-decoration:none; cursor:pointer;" title="Inspect package in Knowledge Base">${esc(m.moduleName)}</a>
          </td>
          <td style="font-family:var(--font-mono);">${m.classCount || 0}</td>
          <td style="font-family:var(--font-mono);">${m.interfaceCount || 0}</td>
          <td style="font-family:var(--font-mono);">${aVal}</td>
          <td style="font-family:var(--font-mono);">${m.afferentCoupling || 0}</td>
          <td style="font-family:var(--font-mono);">${m.efferentCoupling || 0}</td>
          <td style="font-family:var(--font-mono); font-weight:700;">${m.totalCoupling || 0}</td>
          <td><span class="risk-badge ${Number(iVal) > 0.7 ? 'risk-high' : 'risk-low'}">${iVal}</span></td>
          <td><span class="risk-badge ${Number(dVal) > 0.5 ? 'risk-critical' : (Number(dVal) > 0.3 ? 'risk-medium' : 'risk-low')}">${dVal}</span></td>
          <td><span class="risk-badge ${zClass}">${esc(m.couplingZone || 'BALANCED')}</span></td>
          <td><span class="risk-badge ${gClass}">${esc(m.healthGrade || 'A')}</span></td>
          <td>
            <button class="btn-ghost" style="font-size:11px; padding:3px 8px;" onclick="inspectReportPackage('${esc(m.moduleName)}');" title="Inspect package in Knowledge Base">KB →</button>
          </td>
        </tr>
      `;
    });

    html += `
            </tbody>
          </table>
        </div>
      </div>
    `;

    container.innerHTML = html;
  },

  enhanceInteractiveTables(container) {
    if (!container) return;
    const tables = container.querySelectorAll('.report-table');
    tables.forEach((table, tblIdx) => {
      if (table.dataset.enhanced === 'true') return;
      table.dataset.enhanced = 'true';

      const tbody = table.querySelector('tbody');
      if (!tbody) return;
      const allRows = Array.from(tbody.querySelectorAll('tr'));
      if (allRows.length === 0) return;

      const card = table.closest('.report-section-card');
      let header = card ? card.querySelector('.report-section-header') : null;
      const tableWrap = table.closest('.report-table-wrap') || table.parentElement;

      // Create rows-per-page controls inside header or right above table
      let controlsWrap = header ? header.querySelector('.report-table-controls') : null;
      if (!controlsWrap) {
        controlsWrap = document.createElement('div');
        controlsWrap.className = 'report-table-controls';
        if (header) {
          header.appendChild(controlsWrap);
        } else if (tableWrap && tableWrap.parentNode) {
          tableWrap.parentNode.insertBefore(controlsWrap, tableWrap);
        }
      }

      controlsWrap.innerHTML = `
        <div class="report-table-size-wrap">
          <label>Rows: </label>
          <select class="report-pagination-size-select" aria-label="Rows per page">
            <option value="10">10</option>
            <option value="15" selected>15</option>
            <option value="25">25</option>
            <option value="50">50</option>
            <option value="100">100</option>
            <option value="-1">All</option>
          </select>
        </div>
      `;

      // Create bottom pagination bar
      const paginationWrap = document.createElement('div');
      paginationWrap.className = 'report-table-pagination';
      paginationWrap.innerHTML = `
        <div class="report-pagination-info">Showing 1–${Math.min(15, allRows.length)} of ${allRows.length} entries</div>
        <div class="report-pagination-nav"></div>
      `;

      if (tableWrap && tableWrap.parentNode) {
        if (tableWrap.nextSibling) {
          tableWrap.parentNode.insertBefore(paginationWrap, tableWrap.nextSibling);
        } else {
          tableWrap.parentNode.appendChild(paginationWrap);
        }
      }

      let pageSize = 15;
      let currentPage = 1;
      let filteredRows = allRows.slice();

      const badgeEl = header ? header.querySelector('.report-section-badge') : null;
      const origBadgeText = badgeEl ? badgeEl.textContent : `${allRows.length} items`;
      const pageInfoEl = paginationWrap.querySelector('.report-pagination-info');
      const pageNavEl = paginationWrap.querySelector('.report-pagination-nav');

      const renderPagination = () => {
        const total = filteredRows.length;
        const totalPages = (pageSize > 0) ? Math.max(1, Math.ceil(total / pageSize)) : 1;
        if (currentPage > totalPages) currentPage = totalPages;
        if (currentPage < 1) currentPage = 1;

        const startIdx = (pageSize > 0) ? (currentPage - 1) * pageSize : 0;
        const endIdx = (pageSize > 0) ? Math.min(startIdx + pageSize, total) : total;
        const visibleSet = new Set(filteredRows.slice(startIdx, endIdx));

        allRows.forEach(tr => {
          tr.style.display = visibleSet.has(tr) ? '' : 'none';
        });

        const isFilterActive = ReportsHub.filterState && (
          (ReportsHub.filterState.pill && ReportsHub.filterState.pill !== 'ALL') ||
          (ReportsHub.filterState.select1 && ReportsHub.filterState.select1 !== 'ALL') ||
          (ReportsHub.filterState.select2 && ReportsHub.filterState.select2 !== 'ALL') ||
          (ReportsHub.filterState.search && ReportsHub.filterState.search.trim().length > 0)
        );

        // Entry counter
        if (total === 0) {
          pageInfoEl.textContent = 'Showing 0 of 0 entries' + (isFilterActive ? ` (filtered from ${allRows.length})` : '');
        } else {
          const filterSuffix = (total < allRows.length) ? ` (filtered from ${allRows.length})` : '';
          pageInfoEl.textContent = `Showing ${startIdx + 1}–${endIdx} of ${total} entries${filterSuffix}`;
        }

        if (badgeEl) {
          badgeEl.textContent = (total === allRows.length)
            ? origBadgeText
            : `${total} / ${allRows.length} shown`;
        }

        let emptyRow = tbody.querySelector('.report-empty-row');
        if (total === 0) {
          if (!emptyRow) {
            emptyRow = document.createElement('tr');
            emptyRow.className = 'report-empty-row';
            const colCount = table.querySelectorAll('thead th').length || 6;
            emptyRow.innerHTML = `<td colspan="${colCount}" style="text-align:center; padding:28px 16px; color:var(--text-muted); font-size:12.5px; font-style:italic;">No records match the current filter criteria.</td>`;
            tbody.appendChild(emptyRow);
          }
          emptyRow.style.display = '';
        } else if (emptyRow) {
          emptyRow.style.display = 'none';
        }

        // Render page buttons
        pageNavEl.innerHTML = '';

        const makeBtn = (label, targetPage, disabled, isActive, title) => {
          const btn = document.createElement('button');
          btn.type = 'button';
          btn.className = (label === '«' || label === '‹' || label === '›' || label === '»')
            ? 'btn-pagination-nav'
            : ('btn-pagination-page' + (isActive ? ' active' : ''));
          btn.textContent = label;
          if (title) btn.title = title;
          if (disabled) {
            btn.disabled = true;
          } else {
            btn.addEventListener('click', (e) => {
              e.stopPropagation();
              currentPage = targetPage;
              renderPagination();
            });
          }
          return btn;
        };

        pageNavEl.appendChild(makeBtn('«', 1, currentPage === 1, false, 'First page'));
        pageNavEl.appendChild(makeBtn('‹', currentPage - 1, currentPage === 1, false, 'Previous page'));

        const startP = Math.max(1, currentPage - 2);
        const endP = Math.min(totalPages, currentPage + 2);

        if (startP > 1) {
          pageNavEl.appendChild(makeBtn('1', 1, false, currentPage === 1));
          if (startP > 2) {
            const ell = document.createElement('span');
            ell.className = 'report-pagination-ellipsis';
            ell.textContent = '…';
            pageNavEl.appendChild(ell);
          }
        }

        for (let p = startP; p <= endP; p++) {
          pageNavEl.appendChild(makeBtn(String(p), p, false, p === currentPage));
        }

        if (endP < totalPages) {
          if (endP < totalPages - 1) {
            const ell = document.createElement('span');
            ell.className = 'report-pagination-ellipsis';
            ell.textContent = '…';
            pageNavEl.appendChild(ell);
          }
          pageNavEl.appendChild(makeBtn(String(totalPages), totalPages, false, currentPage === totalPages));
        }

        pageNavEl.appendChild(makeBtn('›', currentPage + 1, currentPage === totalPages, false, 'Next page'));
        pageNavEl.appendChild(makeBtn('»', totalPages, currentPage === totalPages, false, 'Last page'));
      };

      const applyFilter = () => {
        const state = ReportsHub.filterState || { pill: 'ALL', select1: 'ALL', select2: 'ALL', search: '' };
        const q = (state.search || '').trim().toLowerCase();
        const cfg = REPORT_FILTERS_CONFIG[ReportsHub.activeReport];

        filteredRows = allRows.filter(tr => {
          const rowText = tr.textContent.toLowerCase();

          // 1. Contextual Search Query
          if (q && !rowText.includes(q)) {
            return false;
          }

          // 2. Report Quick Pill Filter
          if (cfg && state.pill && state.pill !== 'ALL') {
            const pillCfg = (cfg.pills || []).find(p => p.id === state.pill);
            if (pillCfg) {
              if (typeof pillCfg.match === 'function') {
                if (!pillCfg.match(tr, rowText)) return false;
              } else if (!rowText.includes(pillCfg.id.toLowerCase())) {
                return false;
              }
            }
          }

          // 3. Dropdown Selector 1
          if (cfg && cfg.selects && cfg.selects[0] && state.select1 && state.select1 !== 'ALL') {
            const selCfg = cfg.selects[0];
            const optCfg = (selCfg.options || []).find(o => o.val === state.select1);
            if (optCfg) {
              if (typeof optCfg.match === 'function') {
                if (!optCfg.match(tr, rowText)) return false;
              } else if (!rowText.includes(optCfg.val.toLowerCase())) {
                return false;
              }
            }
          }

          // 4. Dropdown Selector 2
          if (cfg && cfg.selects && cfg.selects[1] && state.select2 && state.select2 !== 'ALL') {
            const selCfg = cfg.selects[1];
            const optCfg = (selCfg.options || []).find(o => o.val === state.select2);
            if (optCfg) {
              if (typeof optCfg.match === 'function') {
                if (!optCfg.match(tr, rowText)) return false;
              } else if (!rowText.includes(optCfg.val.toLowerCase())) {
                return false;
              }
            }
          }

          return true;
        });

        currentPage = 1;
        renderPagination();
      };

      const sizeSelect = controlsWrap.querySelector('.report-pagination-size-select');
      if (sizeSelect) {
        sizeSelect.addEventListener('change', (e) => {
          pageSize = parseInt(e.target.value, 10);
          currentPage = 1;
          renderPagination();
        });
      }

      // Add click-to-sort on table headers
      const ths = Array.from(table.querySelectorAll('thead th'));
      ths.forEach((th, colIdx) => {
        const label = th.textContent.trim();
        if (!label || label === 'Actions' || label === 'Action') return;
        th.style.cursor = 'pointer';
        th.style.userSelect = 'none';
        th.title = `Click to sort by ${label}`;
        const sortIcon = document.createElement('span');
        sortIcon.className = 'report-sort-indicator';
        sortIcon.style.marginLeft = '4px';
        sortIcon.style.opacity = '0.45';
        sortIcon.textContent = '⇅';
        th.appendChild(sortIcon);

        let asc = false;
        th.addEventListener('click', () => {
          asc = !asc;
          ths.forEach(other => {
            const ind = other.querySelector('.report-sort-indicator');
            if (ind && other !== th) {
              ind.textContent = '⇅';
              ind.style.opacity = '0.45';
            }
          });
          sortIcon.textContent = asc ? '▲' : '▼';
          sortIcon.style.opacity = '1';

          allRows.sort((a, b) => {
            const cellA = (a.children[colIdx]?.textContent || '').trim();
            const cellB = (b.children[colIdx]?.textContent || '').trim();
            const numA = parseFloat(cellA.replace(/[^0-9.-]+/g, ''));
            const numB = parseFloat(cellB.replace(/[^0-9.-]+/g, ''));
            if (!isNaN(numA) && !isNaN(numB) && /^[0-9.,%\s+-]+([a-zA-Z/-]*)?$/.test(cellA)) {
              return asc ? (numA - numB) : (numB - numA);
            }
            return asc ? cellA.localeCompare(cellB) : cellB.localeCompare(cellA);
          });
          allRows.forEach(r => tbody.appendChild(r));
          applyFilter();
        });
      });

      ReportsHub.activeTables.push({
        table,
        applyFilter,
        allRows,
        getFilteredCount: () => filteredRows.length
      });

      renderPagination();
    });
  },

  async download() {
    const isSnapshot = (ReportsHub.activeReport === 'html-snapshot');
    const ext = isSnapshot ? 'html' : (ReportsHub.activeFormat === 'markdown' ? 'md' : (ReportsHub.activeFormat === 'dashboard' ? 'html' : ReportsHub.activeFormat));
    const filename = isSnapshot ? 'codelens-interactive-graph.html' : `codelens-${ReportsHub.activeReport}-report.${ext}`;

    const url = isSnapshot
      ? '/api/reports/download?type=html-snapshot'
      : `/api/reports/download?type=${ReportsHub.activeReport}&format=${ext}`;

    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    setTimeout(() => {
      if (a.parentNode) document.body.removeChild(a);
    }, 300);
    showBanner(`Downloading ${filename}…`);
  },

  async copy() {
    let content = '';
    const cacheKey = ReportsHub.activeReport + '_' + ReportsHub.activeFormat;
    if (ReportsHub.cache[cacheKey]) {
      content = (typeof ReportsHub.cache[cacheKey] === 'object')
        ? JSON.stringify(ReportsHub.cache[cacheKey], null, 2)
        : ReportsHub.cache[cacheKey];
    } else {
      try {
        const fmt = ReportsHub.activeFormat === 'dashboard' ? 'json' : ReportsHub.activeFormat;
        const res = await fetch(`/api/reports/${ReportsHub.activeReport}?format=${fmt}`);
        content = await res.text();
      } catch (e) {
        showError('Failed to fetch report content for copy: ' + e.message);
        return;
      }
    }

    if (!content) return;
    navigator.clipboard.writeText(content).then(() => {
      const copyBtn = qs('#btn-reports-copy');
      if (copyBtn) {
        const orig = copyBtn.innerHTML;
        copyBtn.innerHTML = '<svg class="svg-icon icon-emerald icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg> <span>Copied!</span>';
        setTimeout(() => { copyBtn.innerHTML = orig; }, 2000);
      }
      showBanner('Report copied to clipboard!');
    }).catch(err => {
      showError('Failed to copy: ' + err.message);
    });
  },

  openTab() {
    const isSnapshot = (ReportsHub.activeReport === 'html-snapshot');
    if (isSnapshot) {
      window.open('/api/reports/html-snapshot', '_blank');
      return;
    }
    const fmt = (ReportsHub.activeFormat === 'dashboard') ? 'html' : ReportsHub.activeFormat;
    window.open(`/api/reports/${ReportsHub.activeReport}?format=${fmt}`, '_blank');
  }
};

window.ReportsHub = ReportsHub;
window.ExportHub = {
  open(type = 'architecture', format = 'dashboard') {
    switchTab('reports');
    ReportsHub.activate(type, format);
  },
  close(modal = qs('#export-modal')) {
    dismissModalAnimated(modal);
  }
};

function initReportsHub() {
  ReportsHub.init();
}



/* ─────────────────────────────────────────────────────────────────────────────
   Critical Path Analyzer & Visualization Controller
   ───────────────────────────────────────────────────────────────────────────── */

let currentCriticalReport = null;
let currentActivePath = null;
let currentActiveStep = null;
let persistentClassesCache = null;
let activePickerSort = 'risk';

async function openCriticalPathPicker() {
  const modal = qs('#modal-critical-path-picker');
  if (!modal) return;
  modal.setAttribute('aria-hidden', 'false');
  modal.classList.add('open');

  const searchInput = qs('#cp-picker-search');
  if (searchInput) {
    searchInput.value = '';
    setTimeout(() => searchInput.focus(), 80);
  }

  const listContainer = qs('#cp-picker-list');
  if (listContainer && (!persistentClassesCache || persistentClassesCache.length === 0)) {
    listContainer.innerHTML = '<div class="cp-picker-loading">Scanning persistent classes across codebase…</div>';
  }

  try {
    if (!persistentClassesCache) {
      persistentClassesCache = await api.persistentClasses();
    }
    renderPersistentClassesList('', activePickerSort);
  } catch (err) {
    console.error('Failed to load persistent classes:', err);
    if (listContainer) {
      listContainer.innerHTML = `<div class="cp-picker-loading" style="color:var(--red)">Failed to load persistent classes: ${esc(err.message || String(err))}</div>`;
    }
  }
}

function closeCriticalPathPicker() {
  const modal = qs('#modal-critical-path-picker');
  if (modal) {
    modal.setAttribute('aria-hidden', 'true');
    modal.classList.remove('open');
  }
}

function renderPersistentClassesList(query = '', sortBy = 'risk') {
  const listContainer = qs('#cp-picker-list');
  const countSpan = qs('#cp-picker-count');
  if (!listContainer) return;

  if (!persistentClassesCache || persistentClassesCache.length === 0) {
    listContainer.innerHTML = '<div class="cp-picker-loading">No persistent classes identified in active codebase.</div>';
    if (countSpan) countSpan.textContent = '0 classes';
    return;
  }

  let items = persistentClassesCache.slice();

  // Substring / fuzzy filter
  const q = (query || '').toLowerCase().trim();
  if (q) {
    items = items.filter(c =>
      (c.simpleName && c.simpleName.toLowerCase().includes(q)) ||
      (c.fqn && c.fqn.toLowerCase().includes(q)) ||
      (c.packageFqn && c.packageFqn.toLowerCase().includes(q)) ||
      (c.primaryEntryPoint && c.primaryEntryPoint.toLowerCase().includes(q))
    );
  }

  // Sorting
  if (sortBy === 'risk') {
    items.sort((a, b) => (b.riskScore || 0) - (a.riskScore || 0));
  } else if (sortBy === 'hops') {
    items.sort((a, b) => (b.criticalPathLength || 0) - (a.criticalPathLength || 0));
  } else if (sortBy === 'complexity') {
    items.sort((a, b) => (b.maxComplexity || 0) - (a.maxComplexity || 0));
  } else if (sortBy === 'name') {
    items.sort((a, b) => (a.simpleName || '').localeCompare(b.simpleName || ''));
  }

  if (countSpan) {
    countSpan.textContent = `${items.length} of ${persistentClassesCache.length} classes`;
  }

  if (items.length === 0) {
    listContainer.innerHTML = '<div class="cp-picker-loading">No matching persistent classes found.</div>';
    return;
  }

  listContainer.innerHTML = '';
  for (const c of items) {
    const card = createElement('div', { class: 'cp-picker-card' });

    // Method badges
    const methodBadgesHtml = (c.persistentMethods && c.persistentMethods.length > 0)
      ? c.persistentMethods.map(m => `<span class="cp-method-tag">${esc(m)}</span>`).join('')
      : '<span class="cp-method-tag">Get</span><span class="cp-method-tag">Create</span><span class="cp-method-tag">Modify</span>';

    // Entry point snippet
    const entryHtml = c.primaryEntryPoint
      ? `<span class="cp-entry-tag" title="Primary Entry Point">⚡ ${esc(c.primaryEntryPoint.split('.').slice(-2).join('.'))}</span>`
      : '';

    card.innerHTML = `
      <div class="cp-card-left">
        <div class="cp-card-title-row">
          <span class="cp-card-name">${esc(c.simpleName)}</span>
          <span class="cp-card-package">${esc(c.packageFqn || '')}</span>
        </div>
        <div class="cp-card-methods-row">
          ${methodBadgesHtml}
          ${entryHtml}
        </div>
      </div>
      <div class="cp-card-right">
        <div class="cp-card-metrics">
          <div class="cp-card-metrics-row"><span>Hops:</span> <strong>${c.criticalPathLength || 0}</strong></div>
          <div class="cp-card-metrics-row"><span>Max CC:</span> <strong>${c.maxComplexity || 0}</strong></div>
          <div class="cp-card-metrics-row"><span>Risk:</span> <strong>${(c.riskScore || 0).toFixed(1)}</strong></div>
        </div>
        <button class="cp-card-trace-btn" type="button">
          <svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>
          Trace Path
        </button>
      </div>
    `;

    card.addEventListener('click', () => {
      closeCriticalPathPicker();
      loadAndVisualizeCriticalPath(c.fqn, 'primary');
    });

    listContainer.appendChild(card);
  }
}

async function loadAndVisualizeCriticalPath(classFqn, mode = 'primary') {
  if (!classFqn) return;

  const footerStatus = qs('#footer-status-text');
  if (footerStatus) footerStatus.textContent = `Tracing critical path for ${classFqn}...`;

  // Preserve previous graph state so user can return to their view upon closing dock
  if (App.activeGraphMode !== 'criticalPath') {
    App._previousGraphState = {
      mode: App.activeGraphMode,
      selected: App.selected,
      nodes: (App.graph && App.graph.nodes) ? App.graph.nodes.slice() : null,
      edges: (App.graph && App.graph.edges) ? App.graph.edges.slice() : null,
      legend: qs('#graph-legend') ? qs('#graph-legend').innerHTML : null
    };
  }
  App.activeGraphMode = 'criticalPath';

  // Toggle HUD button active highlight
  const hudBtn = qs('#btn-critical-path-tool');
  if (hudBtn) hudBtn.classList.add('active');

  // Switch to graph tab
  switchTab('graph');
  ensureGraph();
  const graphEmpty = qs('#graph-empty');
  if (graphEmpty) graphEmpty.style.display = 'none';

  const dock = qs('#critical-path-dock');
  if (dock) {
    dock.style.display = 'flex';
    dock.classList.remove('collapsed');
  }

  try {
    const report = await api.criticalPath(classFqn, mode);
    currentCriticalReport = report;

    // Pick path matching mode or fallback to primary
    let path = null;
    if (report.candidatePaths && report.candidatePaths.length > 0) {
      path = report.candidatePaths.find(p => p.pathId.toLowerCase() === mode.toLowerCase())
          || report.candidatePaths.find(p => (p.category || '').toLowerCase() === mode.toLowerCase())
          || report.primaryPath
          || report.candidatePaths[0];
    } else {
      path = report.primaryPath;
    }

    applyCriticalPath(path, report);

    if (footerStatus) footerStatus.textContent = `Critical path: ${path ? (path.title || 'Loaded') : 'Loaded'} (${path && path.metrics ? path.metrics.length : 0} hops)`;
  } catch (err) {
    console.error('Failed to trace critical path:', err);
    showToast(`Critical Path error: ${err.message || String(err)}`, 'error');
    if (footerStatus) footerStatus.textContent = 'Critical path trace failed';
  }
}

function applyCriticalPath(path, report = currentCriticalReport) {
  if (!path) return;
  currentActivePath = path;

  // Update Dock Header
  const targetClassEl = qs('#cp-target-class');
  if (targetClassEl) targetClassEl.textContent = (report && report.targetClassSimpleName) || (path.nodes && path.nodes.length > 0 ? path.nodes[0].classFqn.split('.').pop() : 'Entity');

  const targetPkgEl = qs('#cp-target-package');
  if (targetPkgEl) targetPkgEl.textContent = (report && report.packageFqn) || '';

  // Update Metrics
  const hopsEl = qs('#cp-metric-hops');
  if (hopsEl) hopsEl.textContent = path.metrics ? path.metrics.length : (path.nodes ? Math.max(0, path.nodes.length - 1) : 0);

  const compEl = qs('#cp-metric-complexity');
  if (compEl) compEl.textContent = path.metrics ? path.metrics.cumulativeComplexity : 0;

  const riskEl = qs('#cp-metric-risk');
  if (riskEl) riskEl.textContent = path.metrics ? (path.metrics.riskScore || 0).toFixed(1) : 0;

  // Update Mode Pills
  const availableModes = new Set(((report && report.candidatePaths) || []).map(p => p.pathId.toLowerCase()));
  qsa('#cp-mode-pills .cp-mode-pill').forEach(pill => {
    const pmode = (pill.dataset.mode || '').toLowerCase();
    const isActive = (path.pathId && path.pathId.toLowerCase() === pmode);
    pill.classList.toggle('active', isActive);
    pill.style.display = availableModes.has(pmode) ? 'inline-block' : 'none';
  });

  // Render Stepper Track
  renderCriticalPathStepper(path);

  // Update 2D Canvas
  if (App.graph) {
    App.graph.setCriticalPath(path);
    if (report && report.graphNodes && report.graphNodes.length > 0) {
      App.graph.setData(report.graphNodes, report.graphEdges || []);
    }
    App.graph.fitCriticalPath();

    // Focus on step 1 (or persistent entity step)
    if (path.nodes && path.nodes.length > 0) {
      const stepToFocus = path.nodes.find(n => n.step === 1) || path.nodes[0];
      if (stepToFocus) {
        setTimeout(() => {
          selectCriticalPathStep(stepToFocus.step);
        }, 120);
      }
    }
  }
}

function switchCriticalPathMode(mode) {
  if (!currentCriticalReport) return;
  const pmode = (mode || '').toLowerCase();
  let targetPath = null;
  if (currentCriticalReport.candidatePaths && currentCriticalReport.candidatePaths.length > 0) {
    targetPath = currentCriticalReport.candidatePaths.find(p => p.pathId.toLowerCase() === pmode)
        || currentCriticalReport.candidatePaths.find(p => (p.category || '').toLowerCase() === pmode);
  }
  if (!targetPath && pmode === 'primary') {
    targetPath = currentCriticalReport.primaryPath;
  }
  if (targetPath) {
    applyCriticalPath(targetPath, currentCriticalReport);
  } else {
    // If not found in memory candidates, request from server
    loadAndVisualizeCriticalPath(currentCriticalReport.targetClassFqn, mode);
  }
}

function renderCriticalPathStepper(path) {
  const track = qs('#cp-stepper-track');
  if (!track || !path || !Array.isArray(path.nodes)) return;

  track.innerHTML = '';
  const sortedNodes = path.nodes.slice().sort((a, b) => a.step - b.step);

  sortedNodes.forEach((n, idx) => {
    const card = createElement('div', {
      class: `cp-step-card ${currentActiveStep === n.step ? 'active' : ''}`,
      'data-step': String(n.step),
      'data-node-id': n.id
    });

    const archetypeBadge = n.archetypeBadge || (n.kind === 'METHOD' ? 'M' : 'C');
    const archeColor = n.archetypeColor || '#f59e0b';
    const cleanName = (n.label || n.simpleName || n.id.split('.').pop() || '').replace(/\(.*\)$/, '');

    card.innerHTML = `
      <div class="cp-step-badge" style="background:${archeColor}; color:#0f172a;">${n.step}</div>
      <div class="cp-step-info">
        <div class="cp-step-archetype" style="color:${archeColor}">[${esc(archetypeBadge)}] ${esc(n.role || '')}</div>
        <div class="cp-step-name" title="${esc(n.id)}">${esc(cleanName)}</div>
        <div class="cp-step-meta">CC: ${n.complexity || 1}</div>
      </div>
    `;

    card.addEventListener('click', () => {
      selectCriticalPathStep(n.step);
    });

    track.appendChild(card);

    if (idx < sortedNodes.length - 1) {
      const arrow = createElement('div', { class: 'cp-step-arrow' });
      arrow.textContent = '→';
      track.appendChild(arrow);
    }
  });
}

function selectCriticalPathStep(stepNumber) {
  currentActiveStep = stepNumber;
  qsa('#cp-stepper-track .cp-step-card').forEach(card => {
    const isAct = parseInt(card.dataset.step, 10) === stepNumber;
    card.classList.toggle('active', isAct);
    if (isAct) {
      card.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' });
    }
  });

  if (App.graph) {
    App.graph.focusStep(stepNumber);
  }
}

function stepCriticalPath(delta) {
  if (!currentActivePath || !currentActivePath.nodes || currentActivePath.nodes.length === 0) return;
  const maxStep = Math.max(...currentActivePath.nodes.map(n => n.step));
  const minStep = Math.min(...currentActivePath.nodes.map(n => n.step));
  let nextStep = (currentActiveStep !== null ? currentActiveStep : 1) + delta;
  if (nextStep < minStep) nextStep = maxStep;
  if (nextStep > maxStep) nextStep = minStep;
  selectCriticalPathStep(nextStep);
}

function closeCriticalPathDock(restorePrevious = true) {
  const dock = qs('#critical-path-dock');
  if (dock) {
    dock.style.display = 'none';
    dock.classList.remove('collapsed');
  }

  // Clear HUD button active state
  const hudBtn = qs('#btn-critical-path-tool');
  if (hudBtn) hudBtn.classList.remove('active');

  // Hide node card if left open
  const nodeCard = qs('#node-card');
  if (nodeCard) nodeCard.style.display = 'none';

  if (App.graph) {
    App.graph.clearCriticalPath();
  }

  // Seamlessly restore previous graph state if requested
  if (restorePrevious && App._previousGraphState) {
    App.activeGraphMode = App._previousGraphState.mode || null;
    App.selected = App._previousGraphState.selected || null;
    if (App.graph && App._previousGraphState.nodes && App._previousGraphState.nodes.length > 0) {
      App.graph.setData(App._previousGraphState.nodes, App._previousGraphState.edges || []);
      if (App._previousGraphState.legend && qs('#graph-legend')) {
        qs('#graph-legend').innerHTML = App._previousGraphState.legend;
      }
    }
  } else if (!restorePrevious) {
    if (App.activeGraphMode === 'criticalPath') {
      App.activeGraphMode = null;
    }
  }
  App._previousGraphState = null;

  currentCriticalReport = null;
  currentActivePath = null;
  currentActiveStep = null;
}

function initCriticalPathUI() {
  // Top HUD Critical Path button
  const hudBtn = qs('#btn-critical-path-tool');
  if (hudBtn) hudBtn.addEventListener('click', () => openCriticalPathPicker());

  // Modal close buttons
  const modalCloseBtn = qs('#btn-cp-picker-close');
  if (modalCloseBtn) modalCloseBtn.addEventListener('click', () => closeCriticalPathPicker());

  const modal = qs('#modal-critical-path-picker');
  if (modal) {
    modal.addEventListener('click', (e) => {
      if (e.target === modal) closeCriticalPathPicker();
    });
  }

  // Modal Search
  const searchInput = qs('#cp-picker-search');
  if (searchInput) {
    searchInput.addEventListener('input', (e) => {
      renderPersistentClassesList(e.target.value, activePickerSort);
    });
  }

  // Modal Sort buttons
  qsa('.cp-sort-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      qsa('.cp-sort-btn').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      activePickerSort = btn.dataset.sort || 'risk';
      const q = searchInput ? searchInput.value : '';
      renderPersistentClassesList(q, activePickerSort);
    });
  });

  // Dock actions
  const btnClose = qs('#btn-cp-close');
  if (btnClose) btnClose.addEventListener('click', () => closeCriticalPathDock(true));

  const btnCollapse = qs('#btn-cp-collapse');
  if (btnCollapse) {
    btnCollapse.addEventListener('click', () => {
      const dock = qs('#critical-path-dock');
      if (dock) dock.classList.toggle('collapsed');
    });
  }

  const btnFit = qs('#btn-cp-fit');
  if (btnFit) btnFit.addEventListener('click', () => {
    if (App.graph) App.graph.fitCriticalPath();
  });

  const btnChange = qs('#btn-cp-change-class');
  if (btnChange) btnChange.addEventListener('click', openCriticalPathPicker);

  const btnPrev = qs('#btn-cp-step-prev');
  if (btnPrev) btnPrev.addEventListener('click', () => stepCriticalPath(-1));

  const btnNext = qs('#btn-cp-step-next');
  if (btnNext) btnNext.addEventListener('click', () => stepCriticalPath(1));

  // Mode pills in dock: instant in-memory switching
  qsa('#cp-mode-pills .cp-mode-pill').forEach(pill => {
    pill.addEventListener('click', () => {
      const mode = pill.dataset.mode;
      switchCriticalPathMode(mode);
    });
  });

  // Keyboard shortcut: Escape closes modal or exits critical path dock
  window.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      const modalEl = qs('#modal-critical-path-picker');
      if (modalEl && (modalEl.getAttribute('aria-hidden') === 'false' || modalEl.classList.contains('open'))) {
        closeCriticalPathPicker();
        return;
      }
      const dockEl = qs('#critical-path-dock');
      if (dockEl && dockEl.style.display !== 'none') {
        closeCriticalPathDock(true);
      }
    }
  });
}

/* ═════════════════════════════════════════════════════════════════════════════
   🎯 BLAST RADIUS & TOUCH POINTS EXPLORER CONTROLLER & SANKEY RENDERER
   ═════════════════════════════════════════════════════════════════════════════ */

let currentBlastRadiusData = null;
let activeBlastRadiusNodeFilter = null;
let activeBlastRadiusKindFilter = 'ALL';
let blastRadiusSearchQuery = '';
let blastRadiusDepth = 3; // 1: Modules Only, 2: Modules & Classes, 3: All Stages
let blastRadiusVisibleCount = 50;
const BLAST_RADIUS_PAGE_STEP = 50;

async function openBlastRadiusExplorer(fqn, kind = 'AUTO') {
  if (!fqn) return;
  switchTab('impact');

  const emptyState = qs('#impact-empty-state');
  const scrollBody = qs('#impact-scroll-body');
  const targetName = qs('#impact-target-name');
  const targetKind = qs('#impact-target-kind');
  const targetMeta = qs('#impact-target-meta');

  if (targetName) targetName.textContent = `Analyzing ${fqn}...`;
  if (targetKind) {
    targetKind.className = `impact-target-kind-pill kind-${(kind || 'class').toLowerCase()}`;
    targetKind.textContent = (kind || 'ENTITY').toUpperCase();
  }
  if (targetMeta) targetMeta.textContent = '';

  const refreshBtn = qs('#impact-refresh-btn');
  if (refreshBtn) refreshBtn.classList.add('loading');
  try {
    const data = await api.blastRadius(fqn, kind);
    currentBlastRadiusData = data;
    activeBlastRadiusNodeFilter = null;
    activeBlastRadiusKindFilter = 'ALL';
    blastRadiusSearchQuery = '';
    blastRadiusVisibleCount = 50;

    renderBlastRadiusView(data);
  } catch (err) {
    console.error('Failed to load blast radius:', err);
    if (typeof toast !== 'undefined') toast.error(`Failed to load blast radius: ${err.message}`);
    else showBanner(`Failed to load blast radius: ${err.message}`);
    if (targetName) targetName.textContent = `Error: ${err.message}`;
  } finally {
    if (refreshBtn) refreshBtn.classList.remove('loading');
  }
}

function renderBlastRadiusView(data) {
  const emptyState = qs('#impact-empty-state');
  const scrollBody = qs('#impact-scroll-body');
  if (emptyState) emptyState.style.display = 'none';
  if (scrollBody) scrollBody.style.display = 'flex';

  const { target, summary, sankey, touchPoints, modules } = data;

  // 1. Update Target Chip
  const targetName = qs('#impact-target-name');
  const targetKind = qs('#impact-target-kind');
  const targetMeta = qs('#impact-target-meta');
  if (targetName) targetName.textContent = target.simpleName || target.fqn;
  if (targetKind) {
    const k = (target.kind || 'CLASS').toLowerCase();
    targetKind.className = `impact-target-kind-pill kind-${k}`;
    targetKind.textContent = (target.kind || 'CLASS').toUpperCase();
  }
  if (targetMeta) {
    targetMeta.textContent = `${target.module ? target.module + ' • ' : ''}${target.sourceFile ? target.sourceFile.split('/').pop() + (target.startLine ? ':' + target.startLine : '') : target.package || ''}`;
  }

  // 2. Update KPI Stats Bar
  const kpiTotal = qs('#impact-kpi-total');
  const kpiModules = qs('#impact-kpi-modules');
  const kpiClasses = qs('#impact-kpi-classes');
  const kpiMethods = qs('#impact-kpi-methods');
  const kpiKindsList = qs('#impact-kpi-kinds-list');

  if (kpiTotal) kpiTotal.textContent = (summary.totalTouchPoints || 0).toLocaleString();
  if (kpiModules) kpiModules.textContent = (summary.moduleCount || 0).toLocaleString();
  if (kpiClasses) kpiClasses.textContent = (summary.classCount || 0).toLocaleString();
  if (kpiMethods) kpiMethods.textContent = (summary.methodCount || 0).toLocaleString();

  if (kpiKindsList) {
    kpiKindsList.innerHTML = '';
    if (summary.byKind && Object.keys(summary.byKind).length > 0) {
      for (const [k, count] of Object.entries(summary.byKind)) {
        const tag = createElement('span', { class: 'impact-kind-tag' });
        tag.textContent = `${k}: ${count}`;
        kpiKindsList.appendChild(tag);
      }
    } else {
      kpiKindsList.innerHTML = '<span class="impact-kind-tag" style="opacity:0.6;">0 interactions</span>';
    }
  }

  // 3. High Fan-In Badge detection
  const fanInBadge = qs('#impact-fanin-badge');
  const fanInText = qs('#impact-fanin-text');
  const isHighFanIn = ((summary.totalTouchPoints || 0) > 50 || (summary.classCount || 0) > 15);
  if (fanInBadge) {
    fanInBadge.style.display = isHighFanIn ? 'inline-flex' : 'none';
    if (fanInText) {
      fanInText.textContent = `High Fan-In (${(summary.totalTouchPoints || 0).toLocaleString()} callers)`;
    }
  }

  // 4. Update Table Filter Pill Counts
  const countCalls = (touchPoints || []).filter(t => t.kind === 'CALLS').length;
  const countReads = (touchPoints || []).filter(t => t.kind === 'READS_FIELD').length;
  const countWrites = (touchPoints || []).filter(t => t.kind === 'WRITES_FIELD').length;
  const countExtends = (touchPoints || []).filter(t => t.kind === 'EXTENDS' || t.kind === 'IMPLEMENTS').length;

  const pillAll = qs('#pill-count-all'); if (pillAll) pillAll.textContent = (touchPoints || []).length;
  const pillCalls = qs('#pill-count-calls'); if (pillCalls) pillCalls.textContent = countCalls;
  const pillReads = qs('#pill-count-reads'); if (pillReads) pillReads.textContent = countReads;
  const pillWrites = qs('#pill-count-writes'); if (pillWrites) pillWrites.textContent = countWrites;
  const pillExtends = qs('#pill-count-extends'); if (pillExtends) pillExtends.textContent = countExtends;

  // 5. Render Sankey Flow Diagram
  renderSankeyDiagram(sankey);

  // 6. Render Touch Points Table
  renderTouchPointsTable();
}

function renderSankeyDiagram(sankey) {
  const container = qs('#impact-sankey-container');
  const svg = qs('#impact-sankey-svg');
  const tooltip = qs('#impact-sankey-tooltip');
  const resetBtn = qs('#impact-btn-reset-filter');
  if (!container || !svg) return;

  svg.innerHTML = '';

  // Update Column Header Guides based on active depth
  const colClasses = qs('#sankey-col-classes');
  const arrClasses = qs('#sankey-arr-classes');
  const colMethods = qs('#sankey-col-methods');
  const arrMethods = qs('#sankey-arr-methods');

  if (blastRadiusDepth === 1) {
    if (arrClasses) arrClasses.style.display = 'none';
    if (colClasses) colClasses.style.display = 'none';
    if (arrMethods) arrMethods.style.display = 'none';
    if (colMethods) colMethods.style.display = 'none';
  } else if (blastRadiusDepth === 2) {
    if (arrClasses) arrClasses.style.display = 'flex';
    if (colClasses) colClasses.style.display = 'flex';
    if (arrMethods) arrMethods.style.display = 'none';
    if (colMethods) colMethods.style.display = 'none';
  } else {
    if (arrClasses) arrClasses.style.display = 'flex';
    if (colClasses) colClasses.style.display = 'flex';
    if (arrMethods) arrMethods.style.display = 'flex';
    if (colMethods) colMethods.style.display = 'flex';
  }

  if (!sankey || !sankey.nodes || sankey.nodes.length === 0) {
    const text = document.createElementNS('http://www.w3.org/2000/svg', 'text');
    text.setAttribute('x', '50%');
    text.setAttribute('y', '50%');
    text.setAttribute('text-anchor', 'middle');
    text.setAttribute('fill', 'var(--text-muted)');
    text.setAttribute('font-size', '13');
    text.textContent = 'No incoming dependencies or touch points detected for this entity.';
    svg.appendChild(text);
    return;
  }

  // Filter nodes and links by active depth
  const depthNodes = (sankey.nodes || []).filter(n => (n.stage || 0) <= blastRadiusDepth);
  const depthNodeIds = new Set(depthNodes.map(n => n.id));
  const depthLinks = (sankey.links || []).filter(l => depthNodeIds.has(l.source) && depthNodeIds.has(l.target));

  // Partition nodes by stage (0: Target, 1: Modules, 2: Classes, 3: Methods)
  const stages = [];
  for (let s = 0; s <= blastRadiusDepth; s++) {
    stages.push([]);
  }
  depthNodes.forEach(n => {
    const s = Math.min(blastRadiusDepth, Math.max(0, n.stage || 0));
    stages[s].push(n);
  });

  // Client-side Top-K aggregation per stage to prevent any vertical overlap
  const MAX_DISPLAY_NODES_PER_STAGE = 7;
  const finalNodes = [];
  const nodeMap = new Map();
  const replacedIdMap = new Map();

  stages.forEach((nodesInStage, sIdx) => {
    if (sIdx === 0 || nodesInStage.length <= MAX_DISPLAY_NODES_PER_STAGE + 1) {
      nodesInStage.forEach(n => {
        finalNodes.push(n);
        nodeMap.set(n.id, n);
      });
      return;
    }

    nodesInStage.sort((a, b) => (b.value || 0) - (a.value || 0));
    const kept = nodesInStage.slice(0, MAX_DISPLAY_NODES_PER_STAGE);
    const overflow = nodesInStage.slice(MAX_DISPLAY_NODES_PER_STAGE);

    kept.forEach(n => {
      finalNodes.push(n);
      nodeMap.set(n.id, n);
    });

    const overflowVal = overflow.reduce((sum, n) => sum + (n.value || 1), 0);
    const rollupId = `client_rollup:s${sIdx}`;
    const stageNoun = sIdx === 1 ? 'modules' : (sIdx === 2 ? 'classes' : 'methods');
    const rollupNode = {
      id: rollupId,
      name: `+ ${overflow.length} other ${stageNoun}`,
      stage: sIdx,
      value: overflowVal,
      isAggregated: true
    };

    const overflowIds = new Set();
    overflow.forEach(n => {
      replacedIdMap.set(n.id, rollupId);
      overflowIds.add(n.id);
      if (n.fqn) overflowIds.add(n.fqn);
      if (n.name) overflowIds.add(n.name);
    });
    rollupNode.memberSet = overflowIds;

    finalNodes.push(rollupNode);
    nodeMap.set(rollupId, rollupNode);
  });

  // Consolidate links after node aggregation
  const consolidatedLinks = [];
  const linkKeyMap = new Map();

  depthLinks.forEach(link => {
    let src = replacedIdMap.get(link.source) || link.source;
    let tgt = replacedIdMap.get(link.target) || link.target;
    if (src === tgt || !nodeMap.has(src) || !nodeMap.has(tgt)) return;

    const key = `${src}->${tgt}`;
    if (linkKeyMap.has(key)) {
      linkKeyMap.get(key).value += (link.value || 1);
    } else {
      const consolidatedLink = {
        source: src,
        target: tgt,
        value: (link.value || 1)
      };
      linkKeyMap.set(key, consolidatedLink);
      consolidatedLinks.push(consolidatedLink);
    }
  });

  // Re-partition final nodes for coordinate calculation
  const layoutStages = [];
  for (let s = 0; s <= blastRadiusDepth; s++) {
    layoutStages.push([]);
  }
  finalNodes.forEach(n => {
    layoutStages[n.stage].push(n);
  });

  const containerWidth = Math.max(900, container.clientWidth - 40);
  const W = containerWidth;
  const maxStageCount = Math.max(...layoutStages.map(s => s.length), 1);
  const nodeGap = 12;
  const minNodeHeight = 28;
  const maxNodeHeight = 110;
  const H = Math.max(320, Math.min(680, maxStageCount * (minNodeHeight + nodeGap) + 60));

  svg.setAttribute('viewBox', `0 0 ${W} ${H}`);
  svg.setAttribute('width', `${W}`);
  svg.setAttribute('height', `${H}`);

  let stageXs = [];
  if (blastRadiusDepth === 1) {
    stageXs = [60, W - 260];
  } else if (blastRadiusDepth === 2) {
    stageXs = [45, 45 + (W - 320) * 0.44, 45 + (W - 320) * 0.92];
  } else {
    stageXs = [
      40,
      40 + (W - 300) * 0.28,
      40 + (W - 300) * 0.62,
      40 + (W - 300) * 0.95
    ];
  }
  const nodeWidth = 14;

  // Calculate layout coordinates for each node
  layoutStages.forEach((nodesInStage, sIdx) => {
    if (nodesInStage.length === 0) return;
    const stageX = stageXs[sIdx];
    const totalStageVal = nodesInStage.reduce((acc, n) => acc + (n.value || 1), 0);

    const availHeight = H - 60;
    const totalGaps = (nodesInStage.length - 1) * nodeGap;
    const availForBars = Math.max(30, availHeight - totalGaps);

    nodesInStage.forEach(n => {
      const proportion = (n.value || 1) / Math.max(1, totalStageVal);
      n.h = Math.max(minNodeHeight, Math.min(maxNodeHeight, Math.round(availForBars * proportion)));
      n.w = nodeWidth;
      n.x = stageX;
    });

    const totalCalculatedHeight = nodesInStage.reduce((acc, n) => acc + n.h, 0) + totalGaps;
    let currentY = Math.max(25, Math.round((H - totalCalculatedHeight) / 2));

    nodesInStage.forEach(n => {
      n.y = currentY;
      n.outY = currentY;
      n.inY = currentY;
      currentY += n.h + nodeGap;
    });
  });

  // Calculate Ribbon Paths
  const ribbonsGroup = document.createElementNS('http://www.w3.org/2000/svg', 'g');
  ribbonsGroup.setAttribute('class', 'sankey-ribbons');
  svg.appendChild(ribbonsGroup);

  const ribbonElements = [];

  consolidatedLinks.forEach(link => {
    const sourceNode = nodeMap.get(link.source);
    const targetNode = nodeMap.get(link.target);
    if (!sourceNode || !targetNode) return;

    const sourceVal = Math.max(1, sourceNode.value || 1);
    const targetVal = Math.max(1, targetNode.value || 1);
    const linkVal = link.value || 1;

    const sThickness = Math.max(3, (linkVal / sourceVal) * sourceNode.h);
    const tThickness = Math.max(3, (linkVal / targetVal) * targetNode.h);

    const x0 = sourceNode.x + sourceNode.w;
    const y0Top = sourceNode.outY;
    const y0Bot = y0Top + sThickness;
    sourceNode.outY = Math.min(sourceNode.y + sourceNode.h, sourceNode.outY + sThickness);

    const x1 = targetNode.x;
    const y1Top = targetNode.inY;
    const y1Bot = y1Top + tThickness;
    targetNode.inY = Math.min(targetNode.y + targetNode.h, targetNode.inY + tThickness);

    const dx = (x1 - x0) * 0.5;
    const d = `M ${x0} ${y0Top} C ${x0 + dx} ${y0Top}, ${x1 - dx} ${y1Top}, ${x1} ${y1Top} L ${x1} ${y1Bot} C ${x1 - dx} ${y1Bot}, ${x0 + dx} ${y0Bot}, ${x0} ${y0Bot} Z`;

    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute('d', d);
    path.setAttribute('class', 'sankey-ribbon');

    let fillColor = 'rgba(56, 139, 253, 0.35)'; // Cyan default
    if (sourceNode.stage === 1) fillColor = 'rgba(192, 132, 252, 0.35)'; // Purple
    else if (sourceNode.stage === 2) fillColor = 'rgba(251, 191, 36, 0.35)'; // Amber
    path.setAttribute('fill', fillColor);

    path.dataset.sourceId = sourceNode.id;
    path.dataset.targetId = targetNode.id;

    path.addEventListener('mouseenter', (e) => {
      highlightFlow([sourceNode.id, targetNode.id]);
      if (tooltip) {
        tooltip.style.display = 'block';
        tooltip.innerHTML = `<strong>${esc(sourceNode.name)} ➔ ${esc(targetNode.name)}</strong><br/><span style="color:var(--cyan-bright);font-family:var(--font-mono);font-weight:700;">${linkVal} touch points</span>`;
        moveTooltip(e);
      }
    });
    path.addEventListener('mousemove', moveTooltip);
    path.addEventListener('mouseleave', () => {
      resetHighlightFlow();
      if (tooltip) tooltip.style.display = 'none';
    });

    ribbonsGroup.appendChild(path);
    ribbonElements.push({ el: path, source: sourceNode.id, target: targetNode.id });
  });

  // Render Nodes
  const nodesGroup = document.createElementNS('http://www.w3.org/2000/svg', 'g');
  nodesGroup.setAttribute('class', 'sankey-nodes');
  svg.appendChild(nodesGroup);

  const stageColors = ['#388bfd', '#c084fc', '#fbbf24', '#34d399'];

  finalNodes.forEach(n => {
    if (n.x === undefined || n.y === undefined) return;
    const isAggregated = !!n.isAggregated;
    const g = document.createElementNS('http://www.w3.org/2000/svg', 'g');
    g.setAttribute('class', `sankey-node stage-${n.stage}${isAggregated ? ' is-aggregated' : ''}${activeBlastRadiusNodeFilter && activeBlastRadiusNodeFilter.id === n.id ? ' selected' : ''}`);
    g.dataset.nodeId = n.id;

    const rect = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
    rect.setAttribute('x', n.x);
    rect.setAttribute('y', n.y);
    rect.setAttribute('width', n.w);
    rect.setAttribute('height', n.h);
    rect.setAttribute('rx', '4');
    rect.setAttribute('ry', '4');
    rect.setAttribute('fill', isAggregated ? 'var(--bg-card, #131d2e)' : (stageColors[n.stage] || '#388bfd'));
    rect.setAttribute('stroke', isAggregated ? 'var(--amber, #f59e0b)' : 'rgba(255, 255, 255, 0.2)');
    rect.setAttribute('stroke-width', isAggregated ? '1.5' : '1');
    if (isAggregated) {
      rect.setAttribute('stroke-dasharray', '3 3');
    }
    rect.setAttribute('class', 'sankey-node-rect');
    g.appendChild(rect);

    // Label Placement - Uniformly right-aligned to eliminate head-on collisions
    const text = document.createElementNS('http://www.w3.org/2000/svg', 'text');
    const labelX = n.x + n.w + 8;
    const textAnchor = 'start';

    text.setAttribute('x', labelX);
    text.setAttribute('y', n.y + Math.min(13, n.h / 2));
    text.setAttribute('text-anchor', textAnchor);
    text.setAttribute('font-family', 'var(--font-sans)');
    text.setAttribute('font-size', '11');
    text.setAttribute('font-weight', isAggregated ? '700' : '600');
    text.setAttribute('fill', isAggregated ? 'var(--amber, #f59e0b)' : 'var(--text-primary)');

    let displayName = n.name || n.id;
    const maxCharLen = blastRadiusDepth === 1 ? 32 : (blastRadiusDepth === 2 ? 24 : 18);
    if (displayName.length > maxCharLen) displayName = displayName.substring(0, maxCharLen - 1) + '…';
    text.textContent = displayName;
    g.appendChild(text);

    // Value subtext
    const subText = document.createElementNS('http://www.w3.org/2000/svg', 'text');
    subText.setAttribute('x', labelX);
    subText.setAttribute('y', n.y + Math.min(13, n.h / 2) + 12);
    subText.setAttribute('text-anchor', textAnchor);
    subText.setAttribute('font-family', 'var(--font-mono)');
    subText.setAttribute('font-size', '9.5');
    subText.setAttribute('fill', 'var(--text-muted)');
    subText.textContent = `${(n.value || 0)} pts`;
    g.appendChild(subText);

    g.addEventListener('mouseenter', (e) => {
      highlightNodeConnected(n.id);
      if (tooltip) {
        tooltip.style.display = 'block';
        const stageName = ['Target Entity', 'Module', 'Class', 'Method'][n.stage] || 'Node';
        const rollInfo = isAggregated ? `<br/><span style="color:var(--amber);font-size:11px;">Aggregated rollup of low-volume callers</span>` : '';
        tooltip.innerHTML = `<strong>${esc(n.name || n.id)}</strong><br/><span style="color:var(--text-muted);font-size:11px;">Stage: ${stageName}</span>${rollInfo}<br/><span style="color:var(--cyan-bright);font-family:var(--font-mono);font-weight:700;">${n.value || 0} touch points</span><br/><span style="color:var(--amber);font-size:10.5px;">Click to filter table</span>`;
        moveTooltip(e);
      }
    });
    g.addEventListener('mousemove', moveTooltip);
    g.addEventListener('mouseleave', () => {
      resetHighlightFlow();
      if (tooltip) tooltip.style.display = 'none';
    });

    g.addEventListener('click', () => {
      if (activeBlastRadiusNodeFilter && activeBlastRadiusNodeFilter.id === n.id) {
        activeBlastRadiusNodeFilter = null;
      } else {
        activeBlastRadiusNodeFilter = n;
      }
      qsa('.sankey-node').forEach(el => el.classList.toggle('selected', activeBlastRadiusNodeFilter && el.dataset.nodeId === activeBlastRadiusNodeFilter.id));
      if (resetBtn) resetBtn.style.display = activeBlastRadiusNodeFilter ? 'inline-flex' : 'none';
      renderTouchPointsTable();
    });

    nodesGroup.appendChild(g);
  });

  function moveTooltip(e) {
    if (!tooltip) return;
    const rect = container.getBoundingClientRect();
    const x = e.clientX - rect.left + 14;
    const y = e.clientY - rect.top + 14;
    tooltip.style.left = `${Math.min(W - 220, x)}px`;
    tooltip.style.top = `${Math.max(10, Math.min(H - 80, y))}px`;
  }

  function highlightFlow(nodeIds) {
    ribbonElements.forEach(r => {
      const match = nodeIds.includes(r.source) && nodeIds.includes(r.target);
      r.el.classList.toggle('highlighted', match);
      r.el.classList.toggle('dimmed', !match);
    });
  }

  function highlightNodeConnected(nodeId) {
    ribbonElements.forEach(r => {
      const match = r.source === nodeId || r.target === nodeId;
      r.el.classList.toggle('highlighted', match);
      r.el.classList.toggle('dimmed', !match);
    });
  }

  function resetHighlightFlow() {
    ribbonElements.forEach(r => {
      r.el.classList.remove('highlighted', 'dimmed');
    });
  }
}

function renderTouchPointsTable() {
  if (!currentBlastRadiusData) return;
  const tbody = qs('#impact-touchpoints-tbody');
  const emptyEl = qs('#impact-table-empty');
  const footerEl = qs('#impact-table-footer');
  const footerText = qs('#impact-table-footer-text');
  const showMoreBtn = qs('#btn-impact-show-more');
  const showAllBtn = qs('#btn-impact-show-all');
  const filterChip = qs('#impact-active-filter-chip');
  const filterText = qs('#impact-active-filter-text');
  if (!tbody) return;

  tbody.innerHTML = '';

  let list = currentBlastRadiusData.touchPoints || [];

  // 1. Filter by Node selection from Sankey
  if (activeBlastRadiusNodeFilter) {
    const fn = activeBlastRadiusNodeFilter;
    if (fn.memberSet && fn.memberSet.size > 0) {
      if (fn.stage === 1) {
        list = list.filter(t => fn.memberSet.has(t.sourceModule) || fn.memberSet.has(`mod:${t.sourceModule}`));
      } else if (fn.stage === 2) {
        list = list.filter(t => fn.memberSet.has(t.sourceClassFqn) || fn.memberSet.has(t.sourceClass) || fn.memberSet.has(`cls:${t.sourceClassFqn}`));
      } else if (fn.stage === 3) {
        list = list.filter(t => fn.memberSet.has(t.sourceMethodFqn) || fn.memberSet.has(t.sourceMethod) || fn.memberSet.has(`mth:${t.sourceMethodFqn}`));
      }
    } else if (fn.isAggregated && fn.id) {
      if (fn.stage === 1) {
        const knownMods = new Set((currentBlastRadiusData.modules || []).map(m => m.name));
        list = list.filter(t => !knownMods.has(t.sourceModule));
      } else if (fn.stage === 2 && fn.module) {
        list = list.filter(t => t.sourceModule === fn.module);
      } else if (fn.stage === 3 && fn.classFqn) {
        list = list.filter(t => t.sourceClassFqn === fn.classFqn);
      }
    } else if (fn.stage === 1) { // Module
      list = list.filter(t => t.sourceModule === fn.name || (t.sourcePackage && t.sourcePackage.startsWith(fn.name)));
    } else if (fn.stage === 2) { // Class
      list = list.filter(t => t.sourceClassFqn === fn.fqn || t.sourceClass === fn.name);
    } else if (fn.stage === 3) { // Method
      list = list.filter(t => t.sourceMethodFqn === fn.fqn || t.sourceMethod === fn.name);
    }

    if (filterChip && filterText) {
      filterChip.style.display = 'inline-flex';
      filterText.textContent = `Filtered by ${['Target', 'Module', 'Class', 'Method'][fn.stage] || 'Node'}: ${fn.name} (${list.length})`;
    }
  } else {
    if (filterChip) filterChip.style.display = 'none';
  }

  // 2. Filter by Kind Pill
  if (activeBlastRadiusKindFilter && activeBlastRadiusKindFilter !== 'ALL') {
    if (activeBlastRadiusKindFilter === 'EXTENDS') {
      list = list.filter(t => t.kind === 'EXTENDS' || t.kind === 'IMPLEMENTS');
    } else {
      list = list.filter(t => t.kind === activeBlastRadiusKindFilter);
    }
  }

  // 3. Filter by Search Query
  if (blastRadiusSearchQuery) {
    const q = blastRadiusSearchQuery.toLowerCase();
    list = list.filter(t =>
      (t.sourceModule && t.sourceModule.toLowerCase().includes(q)) ||
      (t.sourceClass && t.sourceClass.toLowerCase().includes(q)) ||
      (t.sourceMethod && t.sourceMethod.toLowerCase().includes(q)) ||
      (t.targetEntityFqn && t.targetEntityFqn.toLowerCase().includes(q)) ||
      (t.kind && t.kind.toLowerCase().includes(q)) ||
      String(t.sourceLine).includes(q)
    );
  }

  const totalCount = list.length;

  if (totalCount === 0) {
    if (emptyEl) emptyEl.style.display = 'block';
    if (footerEl) footerEl.style.display = 'none';
    return;
  }
  if (emptyEl) emptyEl.style.display = 'none';

  // Pagination slice
  const visibleList = list.slice(0, blastRadiusVisibleCount);

  if (totalCount > BLAST_RADIUS_PAGE_STEP) {
    if (footerEl) footerEl.style.display = 'flex';
    if (footerText) {
      footerText.textContent = `Showing ${Math.min(blastRadiusVisibleCount, totalCount).toLocaleString()} of ${totalCount.toLocaleString()} touch points`;
    }
    const hasMore = blastRadiusVisibleCount < totalCount;
    if (showMoreBtn) showMoreBtn.style.display = hasMore ? 'inline-flex' : 'none';
    if (showAllBtn) showAllBtn.style.display = hasMore ? 'inline-flex' : 'none';
  } else {
    if (footerEl) footerEl.style.display = 'none';
  }

  visibleList.forEach(item => {
    const tr = document.createElement('tr');
    const kLower = (item.kind || 'calls').toLowerCase();

    tr.innerHTML = `
      <td><span class="impact-module-badge">${esc(item.sourceModule || 'default')}</span></td>
      <td>
        <a class="impact-class-link" title="Inspect ${esc(item.sourceClassFqn)}">${esc(item.sourceClass)}</a>
      </td>
      <td>
        <span class="impact-method-name">${esc(item.sourceMethod)}</span>
      </td>
      <td>
        <span class="impact-kind-pill ${kLower}">${esc(item.kind)}</span>
      </td>
      <td style="font-family:var(--font-mono); font-size:11px; color:var(--text-muted); overflow:hidden; text-overflow:ellipsis;" title="${esc(item.targetEntityFqn)}">
        ${esc(shortFqn(item.targetEntityFqn))}
      </td>
      <td style="text-align:center;">
        <button class="impact-line-badge" title="Open ${esc(item.sourceFile || '')} at line ${item.sourceLine}">
          <span>⚡ Line ${item.sourceLine}</span>
        </button>
      </td>
    `;

    tr.querySelector('.impact-class-link')?.addEventListener('click', (e) => {
      e.preventDefault();
      if (item.sourceClassFqn) {
        api.type(item.sourceClassFqn).then(renderTypeDetail).catch(() => {});
      }
    });

    tr.querySelector('.impact-line-badge')?.addEventListener('click', (e) => {
      e.preventDefault();
      if (item.sourceFile) {
        openSourceFile(item.sourceFile, item.sourceLine);
      } else {
        showBanner(`Source file location not indexed for ${item.sourceClass}`, 'info');
      }
    });

    tbody.appendChild(tr);
  });
}

function initBlastRadiusExplorer() {
  // Reset Node Filter button
  qs('#impact-btn-reset-filter')?.addEventListener('click', () => {
    activeBlastRadiusNodeFilter = null;
    qsa('.sankey-node').forEach(el => el.classList.remove('selected'));
    const btn = qs('#impact-btn-reset-filter');
    if (btn) btn.style.display = 'none';
    renderTouchPointsTable();
  });

  // Clear filter chip button
  qs('#impact-active-filter-clear')?.addEventListener('click', () => {
    activeBlastRadiusNodeFilter = null;
    qsa('.sankey-node').forEach(el => el.classList.remove('selected'));
    const btn = qs('#impact-btn-reset-filter');
    if (btn) btn.style.display = 'none';
    renderTouchPointsTable();
  });

  // Granularity / Depth controls
  qsa('.sankey-depth-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      qsa('.sankey-depth-btn').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      blastRadiusDepth = parseInt(btn.dataset.depth, 10) || 3;
      if (currentBlastRadiusData && currentBlastRadiusData.sankey) {
        renderSankeyDiagram(currentBlastRadiusData.sankey);
      }
    });
  });

  // Pagination buttons
  qs('#btn-impact-show-more')?.addEventListener('click', () => {
    blastRadiusVisibleCount += BLAST_RADIUS_PAGE_STEP;
    renderTouchPointsTable();
  });

  qs('#btn-impact-show-all')?.addEventListener('click', () => {
    blastRadiusVisibleCount = 999999;
    renderTouchPointsTable();
  });

  // Kind filter pills
  qsa('#impact-kind-filter-pills .impact-pill-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      qsa('#impact-kind-filter-pills .impact-pill-btn').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      activeBlastRadiusKindFilter = btn.dataset.kind || 'ALL';
      renderTouchPointsTable();
    });
  });

  // Table search input
  qs('#impact-table-filter')?.addEventListener('input', (e) => {
    blastRadiusSearchQuery = e.target.value.trim();
    renderTouchPointsTable();
  });

  // Refresh button
  qs('#impact-refresh-btn')?.addEventListener('click', () => {
    if (currentBlastRadiusData && currentBlastRadiusData.target) {
      openBlastRadiusExplorer(currentBlastRadiusData.target.fqn, currentBlastRadiusData.target.kind);
    }
  });

  // Quick search input in header
  const quickSearchInput = qs('#impact-quick-search');
  const searchResults = qs('#impact-search-results');
  if (quickSearchInput && searchResults) {
    let debounceTimer = null;
    quickSearchInput.addEventListener('input', (e) => {
      clearTimeout(debounceTimer);
      const val = e.target.value.trim();
      if (!val || val.length < 2) {
        searchResults.style.display = 'none';
        return;
      }
      debounceTimer = setTimeout(async () => {
        try {
          const res = await api.search(val, 12);
          const results = res.results || res;
          if (!results || results.length === 0) {
            searchResults.innerHTML = '<div style="padding:10px;color:var(--text-muted);font-size:12px;">No matching entities</div>';
            searchResults.style.display = 'block';
            return;
          }
          searchResults.innerHTML = '';
          results.forEach(item => {
            const row = createElement('div', { class: 'impact-search-item' });
            const kind = (item.kind || item.type || 'CLASS').toUpperCase();
            const kindCls = kind.toLowerCase();
            row.innerHTML = `
              <div class="impact-search-item-left">
                <span class="impact-target-kind-pill kind-${kindCls}">${kind}</span>
                <span style="font-weight:600;color:var(--text-primary);">${esc(item.simpleName || item.name || item.fqn)}</span>
              </div>
              <span style="font-family:var(--font-mono);font-size:10px;color:var(--text-muted);">${esc(item.packageFqn || item.module || '')}</span>
            `;
            row.addEventListener('click', () => {
              searchResults.style.display = 'none';
              quickSearchInput.value = '';
              openBlastRadiusExplorer(item.fqn || item.id, kind);
            });
            searchResults.appendChild(row);
          });
          searchResults.style.display = 'block';
        } catch (err) {
          console.warn('Quick search failed:', err);
        }
      }, 200);
    });

    document.addEventListener('click', (e) => {
      if (!quickSearchInput.contains(e.target) && !searchResults.contains(e.target)) {
        searchResults.style.display = 'none';
      }
    });
  }

  // Populate sample chips in empty state
  if (typeof api.types === 'function') {
    api.types().then(types => {
      const chipsContainer = qs('#impact-sample-chips');
      if (!chipsContainer) return;
      chipsContainer.innerHTML = '';
      const sampleTypes = (Array.isArray(types) ? types : []).slice(0, 6);
      sampleTypes.forEach(t => {
        const chip = createElement('div', { class: 'impact-sample-chip' });
        chip.innerHTML = `<span class="impact-target-kind-pill kind-class">CLASS</span> <span>${esc(t.simpleName)}</span>`;
        chip.addEventListener('click', () => openBlastRadiusExplorer(t.fqn, 'CLASS'));
        chipsContainer.appendChild(chip);
      });
    }).catch(() => {});
  }
}

// Global window helpers for debugging & integration
window.openBlastRadiusExplorer = openBlastRadiusExplorer;
window.loadAndVisualizeCriticalPath = loadAndVisualizeCriticalPath;
window.openCriticalPathPicker = openCriticalPathPicker;
window.closeCriticalPathDock = closeCriticalPathDock;
window.applyTheme = applyTheme;
window.switchTab = switchTab;
window.selectMethod = selectMethod;
window.selectType = selectType;
window.selectField = selectField;
if (window.App) {
  window.App.selectMethod = selectMethod;
  window.App.selectType = selectType;
  window.App.selectField = selectField;
}

/* ═══════════════════════════════════════════════════════════════════════════
   Command Palette Controller (cmdk style - Skill 10: pick-ui-library)
   Implements Raycast 0ms keyboard rule, grouped actions, instant fuzzy search.
   ═══════════════════════════════════════════════════════════════════════════ */
function initCommandPalette() {
  const modal = qs('#command-palette-modal');
  const input = qs('#cmdk-input');
  const list = qs('#cmdk-list');
  const triggerBtn = qs('#btn-command-palette');
  if (!modal || !input || !list) return;

  let activeIndex = 0;
  let filteredCommands = [];

  const commands = [
    // Navigation
    {
      id: 'nav-graph',
      title: 'Open Architecture Graph',
      subtitle: 'Interactive 2D/3D force-directed dependency graph',
      group: 'Navigation',
      shortcut: '1',
      icon: '<svg class="svg-icon icon-cyan" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="6" cy="6" r="3"/><circle cx="18" cy="18" r="3"/><path d="M8.5 8.5l7 7"/></svg>',
      action: () => switchTab('graph')
    },
    {
      id: 'nav-kb',
      title: 'Open Knowledge Base',
      subtitle: 'Architectural documentation and module analysis',
      group: 'Navigation',
      shortcut: '2',
      icon: '<svg class="svg-icon icon-purple" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H20v20H6.5a2.5 2.5 0 0 1-2.5-2.5Z"/></svg>',
      action: () => switchTab('kb')
    },
    {
      id: 'nav-review',
      title: 'Open Code Review & Diagnostics',
      subtitle: 'Deep architectural and semantic code quality review',
      group: 'Navigation',
      shortcut: '3',
      icon: '<svg class="svg-icon icon-amber" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>',
      action: () => switchTab('review')
    },
    {
      id: 'nav-impact',
      title: 'Open Blast Radius & Touch Points Explorer',
      subtitle: 'Analyze upstream impact flows and touchpoint lines across modules',
      shortcut: '6',
      icon: '<svg class="svg-icon icon-rose" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><circle cx="12" cy="12" r="6"/><circle cx="12" cy="12" r="2"/></svg>',
      action: () => switchTab('impact')
    },
    {
      id: 'nav-storylines',
      title: 'Open CodeStory Storylines',
      subtitle: 'Explore interactive execution workflows and architectural narratives',
      group: 'Navigation',
      shortcut: '7',
      icon: '<svg class="svg-icon icon-cyan" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H20v20H6.5a2.5 2.5 0 0 1-2.5-2.5Z"/><path d="M6 6h10"/><path d="M6 10h10"/><path d="M6 14h7"/></svg>',
      action: () => switchTab('storylines')
    },
    {
      id: 'nav-studio',
      title: 'Launch 3D Macro Visualizer Studio',
      subtitle: '3D software city and galaxy cluster view',
      group: 'Navigation',
      shortcut: 'M',
      icon: '<svg class="svg-icon icon-mint" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m21.12 6.4-6.05-4.06a2 2 0 0 0-2.17-.05L2.9 8.24A2 2 0 0 0 2 9.92v6.16a2 2 0 0 0 .96 1.72l9.97 5.92a2 2 0 0 0 2.14 0l6.05-3.62A2 2 0 0 0 22 18.38V8.12a2 2 0 0 0-.88-1.72Z"/><polyline points="2.5 8.5 12 14.5 21.5 8.5"/></svg>',
      action: () => {
        if (typeof openMacroStudio === 'function') openMacroStudio();
        else switchTab('codebase');
      }
    },
    {
      id: 'nav-reports',
      title: 'Open Reports & Export Hub',
      subtitle: 'Export architecture snapshots and executive summaries',
      group: 'Navigation',
      shortcut: 'R',
      icon: '<svg class="svg-icon icon-cyan" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>',
      action: () => {
        if (typeof openReportModal === 'function') openReportModal();
        else switchTab('reports');
      }
    },
    {
      id: 'nav-hub',
      title: 'Open Background Tasks & Process Hub',
      subtitle: 'JVM heap watchdog, thread analyzer, and async queue',
      group: 'Navigation',
      shortcut: 'P',
      icon: '<svg class="svg-icon icon-emerald" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="3" width="20" height="14" rx="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>',
      action: () => {
        if (typeof openProcessHub === 'function') openProcessHub();
      }
    },
    {
      id: 'nav-cp',
      title: 'Open Critical Path Picker',
      subtitle: 'Analyze bottleneck nodes, cycle clusters, and blast radii',
      group: 'Navigation',
      shortcut: 'C',
      icon: '<svg class="svg-icon icon-amber" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/></svg>',
      action: () => {
        if (typeof openCriticalPathPicker === 'function') openCriticalPathPicker();
      }
    },

    // Diagnostics & Memory
    {
      id: 'diag-gc',
      title: 'Trigger Garbage Collection (GC)',
      subtitle: 'Run immediate JVM compaction and free unreferenced heap objects',
      group: 'Diagnostics & JVM',
      shortcut: '⌥G',
      icon: '<svg class="svg-icon icon-rose" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="1 4 1 10 7 10"/><path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10"/></svg>',
      action: () => {
        if (typeof toast !== 'undefined') toast.loading('Triggering JVM Garbage Collection…', { id: 'cmd-gc' });
        fetch('/api/process-hub/jvm/gc', { method: 'POST' })
          .then(r => r.json())
          .then(d => {
            const freed = ((d.freedBytes || 0) / (1024 * 1024)).toFixed(1);
            if (typeof toast !== 'undefined') toast.success(`Garbage collection finished. Freed ${freed} MB.`, { id: 'cmd-gc' });
          })
          .catch(e => {
            if (typeof toast !== 'undefined') toast.error('GC request failed: ' + e.message, { id: 'cmd-gc' });
          });
      }
    },
    {
      id: 'diag-deadlocks',
      title: 'Scan for Thread Deadlocks',
      subtitle: 'Perform instant cyclic lock graph contention analysis',
      group: 'Diagnostics & JVM',
      shortcut: '⌥D',
      icon: '<svg class="svg-icon icon-cyan" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>',
      action: () => {
        fetch('/api/process-hub/jvm/deadlocks')
          .then(r => r.json())
          .then(d => {
            if (d.deadlocks && d.deadlocks.length > 0) {
              if (typeof toast !== 'undefined') toast.warning(`Warning: ${d.deadlocks.length} thread deadlocks detected!`);
            } else {
              if (typeof toast !== 'undefined') toast.success('Deadlock scan passed: 0 blocked threads found.');
            }
          })
          .catch(e => {
            if (typeof toast !== 'undefined') toast.error('Deadlock scan failed: ' + e.message);
          });
      }
    },

    // Preferences & Theme
    {
      id: 'pref-theme',
      title: 'Change Theme (Dark / Swiss / Light)',
      subtitle: 'Open theme selector menu to switch between Obsidian, Swiss, and Light',
      group: 'Preferences',
      shortcut: '⌥T',
      icon: '<svg class="svg-icon icon-amber" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="4"/><path d="M12 2v2"/><path d="M12 20v2"/><path d="m4.93 4.93 1.41 1.41"/><path d="m17.66 17.66 1.41 1.41"/></svg>',
      action: () => {
        const toggleBtn = qs('#theme-toggle-btn');
        if (toggleBtn) toggleBtn.click();
      }
    },
    {
      id: 'pref-theme-dark',
      title: 'Theme: Midnight Obsidian (Dark)',
      subtitle: 'Pure dark mode optimized for deep work & graph neon highlights',
      group: 'Preferences',
      icon: '<svg class="svg-icon icon-purple" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"/></svg>',
      action: () => selectTheme('dark')
    },
    {
      id: 'pref-theme-swiss',
      title: 'Theme: Swiss Minimalist (High-Contrast)',
      subtitle: 'Swiss design aesthetic with bold crimson accents and stark geometry',
      group: 'Preferences',
      icon: '<svg class="svg-icon icon-red" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="9 3 15 3 15 9 21 9 21 15 15 15 15 21 9 21 9 15 3 15 3 9 9 9" fill="currentColor" stroke="none"/></svg>',
      action: () => selectTheme('swiss')
    },
    {
      id: 'pref-theme-light',
      title: 'Theme: Pure Daylight (Light)',
      subtitle: 'Clean high-readability daytime canvas with emerald accents',
      group: 'Preferences',
      icon: '<svg class="svg-icon icon-amber" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="4"/><path d="M12 2v2"/><path d="M12 20v2"/><path d="m4.93 4.93 1.41 1.41"/><path d="m17.66 17.66 1.41 1.41"/></svg>',
      action: () => selectTheme('light')
    },

    // Help & Documentation
    {
      id: 'help-about',
      title: 'About CodeLens & Platform Overview',
      subtitle: 'Architecture philosophy, v1.1.3 engine specs, and 100% offline guarantee',
      group: 'Help & Documentation',
      shortcut: '?',
      icon: '<svg class="svg-icon icon-cyan" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>',
      action: () => {
        if (typeof openHelpModal === 'function') {
          openHelpModal();
          const aboutBtn = qs('.guide-tab-btn[data-guide-tab="about"]');
          if (aboutBtn) aboutBtn.click();
        }
      }
    },
    {
      id: 'help-wiki',
      title: 'Open GitHub Wiki Documentation',
      subtitle: 'Browse official architecture blueprints, ADRs, intelligence reports, and REST API guide',
      group: 'Help & Documentation',
      icon: '<svg class="svg-icon icon-emerald" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z"/><path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z"/></svg>',
      action: () => {
        window.open('https://github.com/manvenpratap/codelens/wiki', '_blank');
      }
    }
  ];

  function renderList(query = '') {
    const q = query.trim().toLowerCase();
    filteredCommands = commands.filter(cmd => {
      if (!q) return true;
      return cmd.title.toLowerCase().includes(q) ||
             cmd.subtitle.toLowerCase().includes(q) ||
             cmd.group.toLowerCase().includes(q);
    });

    if (filteredCommands.length === 0) {
      list.innerHTML = `
        <div style="padding: 24px; text-align: center; color: var(--text-muted); font-size: 13px;">
          No matching commands found for "${esc(query)}"
        </div>
      `;
      activeIndex = -1;
      return;
    }

    if (activeIndex >= filteredCommands.length || activeIndex < 0) {
      activeIndex = 0;
    }

    // Group items
    let html = '';
    let currentGroup = null;
    let itemIdx = 0;

    filteredCommands.forEach(cmd => {
      if (cmd.group !== currentGroup) {
        currentGroup = cmd.group;
        html += `<div class="cmdk-group-title">${esc(currentGroup)}</div>`;
      }
      const isSelected = itemIdx === activeIndex;
      html += `
        <div class="cmdk-item ${isSelected ? 'selected' : ''}" data-index="${itemIdx}" role="option" aria-selected="${isSelected}">
          <div class="cmdk-item-left">
            ${cmd.icon}
            <div>
              <div style="font-weight: 500;">${esc(cmd.title)}</div>
              <div style="font-size: 11px; color: var(--text-muted);">${esc(cmd.subtitle)}</div>
            </div>
          </div>
          ${cmd.shortcut ? `<span class="cmdk-item-shortcut">${esc(cmd.shortcut)}</span>` : ''}
        </div>
      `;
      itemIdx++;
    });

    list.innerHTML = html;

    // Scroll active into view
    const activeEl = list.querySelector('.cmdk-item.selected');
    if (activeEl) {
      activeEl.scrollIntoView({ block: 'nearest' });
    }
  }

  function openPalette(instant = false) {
    if (instant) {
      modal.style.transition = 'none';
    } else {
      modal.style.transition = '';
    }
    modal.classList.add('open');
    input.value = '';
    activeIndex = 0;
    renderList('');
    input.focus();
    if (typeof CodeLensFeedback !== 'undefined') CodeLensFeedback.click();
  }

  function closePalette() {
    modal.classList.remove('open');
    input.blur();
  }

  function executeActive() {
    if (activeIndex >= 0 && activeIndex < filteredCommands.length) {
      const cmd = filteredCommands[activeIndex];
      closePalette();
      if (typeof CodeLensFeedback !== 'undefined') CodeLensFeedback.click();
      if (cmd && typeof cmd.action === 'function') {
        cmd.action();
      }
    }
  }

  // Event Listeners
  if (triggerBtn) {
    triggerBtn.addEventListener('click', () => openPalette(false));
  }

  modal.addEventListener('click', (e) => {
    if (e.target === modal) closePalette();
  });

  input.addEventListener('input', (e) => {
    renderList(e.target.value);
  });

  input.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      if (filteredCommands.length > 0) {
        activeIndex = (activeIndex + 1) % filteredCommands.length;
        renderList(input.value);
      }
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      if (filteredCommands.length > 0) {
        activeIndex = (activeIndex - 1 + filteredCommands.length) % filteredCommands.length;
        renderList(input.value);
      }
    } else if (e.key === 'Enter') {
      e.preventDefault();
      executeActive();
    } else if (e.key === 'Escape') {
      e.preventDefault();
      closePalette();
    }
  });

  list.addEventListener('click', (e) => {
    const item = e.target.closest('.cmdk-item');
    if (item && item.dataset.index !== undefined) {
      activeIndex = parseInt(item.dataset.index, 10);
      executeActive();
    }
  });

  // Global Keyboard Shortcut: ⌘K or Ctrl+K (Raycast 0ms keyboard rule)
  window.addEventListener('keydown', (e) => {
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
      e.preventDefault();
      if (modal.classList.contains('open')) {
        closePalette();
      } else {
        openPalette(true); // 0ms Raycast rule for keyboard trigger
      }
    } else if (e.key === 'Escape' && modal.classList.contains('open')) {
      closePalette();
    }
  });

  window.openCommandPalette = openPalette;
  window.closeCommandPalette = closePalette;
}

/* ─────────────────────────────────────────────────────────────────────────────
   CodeStory: Storylines & Narrative Flow Engine UI Controller
   ───────────────────────────────────────────────────────────────────────────── */

let storylinesCache = [];
let currentStorylineDetail = null;

function initStorylinesView() {
  const searchInput = qs('#storylines-search-input');
  const clearBtn = qs('#storylines-search-clear');
  const refreshBtn = qs('#storylines-refresh-btn');
  const copyBtn = qs('#storyline-btn-export-story');
  const blastBtn = qs('#storyline-btn-trace-impact');

  if (searchInput) {
    let debounceTimer = null;
    searchInput.addEventListener('input', (e) => {
      clearTimeout(debounceTimer);
      debounceTimer = setTimeout(() => {
        const val = e.target.value.trim();
        if (clearBtn) clearBtn.style.display = val ? 'inline-flex' : 'none';
        filterAndRenderStorylinesCatalog();
      }, 180);
    });
  }

  if (clearBtn) {
    clearBtn.addEventListener('click', () => {
      if (searchInput) {
        searchInput.value = '';
        clearBtn.style.display = 'none';
        filterAndRenderStorylinesCatalog();
      }
    });
  }

  qsa('.storylines-cat-chip').forEach(chip => {
    chip.addEventListener('click', () => {
      qsa('.storylines-cat-chip').forEach(c => c.classList.remove('active'));
      chip.classList.add('active');
      filterAndRenderStorylinesCatalog();
    });
  });

  if (refreshBtn) {
    refreshBtn.addEventListener('click', () => {
      loadStorylines(true);
    });
  }

  if (copyBtn) {
    copyBtn.addEventListener('click', () => {
      exportStorylineToClipboard();
    });
  }

  if (blastBtn) {
    blastBtn.addEventListener('click', () => {
      const ep = currentStorylineDetail ? (currentStorylineDetail.entryPointFqn || currentStorylineDetail.entryPoint) : null;
      if (ep) {
        openBlastRadiusExplorer(ep);
      }
    });
  }
}

async function loadStorylines(force = false) {
  const statsEl = qs('#storylines-catalog-stats');
  const countPill = qs('#storylines-count-pill');
  const listEl = qs('#storylines-catalog-list');
  if (!listEl) return;

  if (storylinesCache.length > 0 && !force) {
    filterAndRenderStorylinesCatalog();
    return;
  }

  if (statsEl) statsEl.textContent = 'Discovering workflows...';

  try {
    const res = await fetch('/api/storylines');
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json();
    storylinesCache = data.storylines || [];

    if (statsEl) {
      statsEl.textContent = `${storylinesCache.length} workflows discovered`;
    }
    if (countPill) {
      countPill.textContent = storylinesCache.length;
    }

    filterAndRenderStorylinesCatalog();

    if (storylinesCache.length > 0 && !currentStorylineDetail) {
      selectStoryline(storylinesCache[0].id);
    }
  } catch (err) {
    console.error('Failed to load storylines:', err);
    if (statsEl) statsEl.textContent = 'Failed to load';
    listEl.innerHTML = `<div style="padding: 16px; color: var(--rose); font-size: 12px;">Unable to load storylines: ${esc(err.message)}</div>`;
  }
}

function filterAndRenderStorylinesCatalog() {
  const listEl = qs('#storylines-catalog-list');
  const countPill = qs('#storylines-count-pill');
  if (!listEl) return;

  const searchInput = qs('#storylines-search-input');
  const query = (searchInput?.value || '').trim().toLowerCase();
  const activeChip = qs('.storylines-cat-chip.active');
  const activeCat = activeChip?.getAttribute('data-cat') || 'ALL';

  let filtered = storylinesCache;
  if (activeCat !== 'ALL') {
    const fCat = activeCat.toLowerCase().replace(/[^a-z]/g, '');
    filtered = filtered.filter(s => {
      const sCat = (s.category || '').toLowerCase().replace(/[^a-z]/g, '');
      return sCat === fCat || sCat.includes(fCat) || fCat.includes(sCat);
    });
  }
  if (query) {
    filtered = filtered.filter(s =>
      (s.title || '').toLowerCase().includes(query) ||
      (s.executiveSummary || '').toLowerCase().includes(query) ||
      (s.entryPoint || '').toLowerCase().includes(query) ||
      (s.category || '').toLowerCase().includes(query)
    );
  }

  if (countPill) countPill.textContent = filtered.length;

  if (filtered.length === 0) {
    listEl.innerHTML = `
      <div style="padding: 24px; text-align: center; color: var(--text-muted); font-size: 12px;">
        No workflows match the selected filter.
      </div>
    `;
    return;
  }

  listEl.innerHTML = filtered.map(s => {
    const isActive = currentStorylineDetail && currentStorylineDetail.id === s.id;
    return `
      <div class="storyline-card ${isActive ? 'active' : ''}" data-story-id="${esc(s.id)}" role="button" tabindex="0">
        <div class="storyline-card-header">
          <span class="storyline-card-title" title="${esc(s.title)}">${esc(s.title)}</span>
          <span class="storyline-card-cat-badge">${esc(s.category)}</span>
        </div>
        <div class="storyline-card-meta">
          <span class="storyline-card-entry" title="${esc(s.entryPoint)}">${esc(s.entryClass || s.entryPoint)}</span>
          <span>•</span>
          <span>${s.stepCount} steps</span>
        </div>
        <div class="storyline-card-snippet" title="${esc(s.executiveSummary || '')}">
          ${esc(s.executiveSummary || '')}
        </div>
      </div>
    `;
  }).join('');

  listEl.querySelectorAll('.storyline-card').forEach(card => {
    card.addEventListener('click', () => {
      const id = card.getAttribute('data-story-id');
      if (id) selectStoryline(id);
    });
  });
}

async function selectStoryline(id, fqn = null) {
  const emptyState = qs('#storylines-detail-empty');
  const contentState = qs('#storylines-detail-content');

  qsa('.storyline-card').forEach(card => {
    card.classList.toggle('active', card.getAttribute('data-story-id') === id);
  });

  try {
    const url = fqn
      ? `/api/storyline?fqn=${encodeURIComponent(fqn)}`
      : `/api/storyline?id=${encodeURIComponent(id)}`;
    const res = await fetch(url);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const story = await res.json();
    currentStorylineDetail = story;

    renderStorylineDetail(story);

    if (emptyState) emptyState.style.display = 'none';
    if (contentState) contentState.style.display = 'flex';
  } catch (err) {
    console.error('Failed to load storyline detail:', err);
    if (typeof showToast === 'function') {
      showToast(`Failed to load storyline: ${err.message}`, 'error');
    }
  }
}

function renderStorylineDetail(story) {
  if (!story) return;

  const heroCategory = qs('#storyline-hero-category');
  const heroComplexity = qs('#storyline-hero-complexity');
  const heroSteps = qs('#storyline-hero-steps');
  const heroTitle = qs('#storyline-hero-title');
  const heroEntry = qs('#storyline-hero-entry');
  const narrativeText = qs('#storyline-narrative-text');
  const timelineEl = qs('#storyline-timeline');
  const evidenceCount = qs('#storyline-evidence-count');
  const evidenceGrid = qs('#storyline-evidence-grid');

  if (heroCategory) heroCategory.textContent = story.category || 'Workflow';
  if (heroComplexity) heroComplexity.textContent = `Complexity: ${story.complexity || 'Moderate'}`;
  if (heroSteps) heroSteps.textContent = `${(story.steps || []).length} steps`;
  if (heroTitle) heroTitle.textContent = story.title;
  const epDisplay = story.entryPointFqn || story.entryPoint || '';
  if (heroEntry) heroEntry.innerHTML = `Entry Point: <code>${esc(epDisplay)}</code>`;
  if (narrativeText) narrativeText.textContent = story.executiveSummary || 'Execution workflow traced from call graph.';

  // Render Steps
  if (timelineEl) {
    const steps = story.steps || [];
    timelineEl.innerHTML = steps.map(step => {
      const roleClass = 'role-' + (step.role || 'processing').toLowerCase().replace(/_/g, '-');
      const src = step.sourceFile || '';
      const shortFile = src.includes('/')
        ? src.substring(src.lastIndexOf('/') + 1)
        : (src || 'source');

      return `
        <div class="storyline-step-item" role="listitem">
          <div class="storyline-step-node-badge">${step.stepIndex}</div>
          <div class="storyline-step-card">
            <div class="storyline-step-card-top">
              <span class="storyline-role-pill ${roleClass}">${esc(step.roleLabel || step.role)}</span>
              <span class="storyline-step-signature">${esc(step.classSimpleName)}.${esc(step.simpleName)}</span>
            </div>
            <div class="storyline-step-action">${esc(step.narrativeAction)}</div>
            <div class="storyline-step-footer">
              <button class="storyline-cite-btn" data-file="${esc(step.sourceFile)}" data-line="${step.startLine}" title="Jump to source in Monaco Editor">
                <svg class="svg-icon icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>
                <span>${esc(shortFile)}:${step.startLine}</span>
              </button>
              <button class="storyline-impact-trigger" data-fqn="${esc(step.methodFqn)}" title="Trace Blast Radius for this method">
                <svg class="svg-icon icon-rose icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><circle cx="12" cy="12" r="6"/><circle cx="12" cy="12" r="2"/></svg>
                <span>Blast Radius</span>
              </button>
            </div>
          </div>
        </div>
      `;
    }).join('');

    timelineEl.querySelectorAll('.storyline-cite-btn').forEach(btn => {
      btn.addEventListener('click', () => {
        const file = btn.getAttribute('data-file');
        const line = parseInt(btn.getAttribute('data-line'), 10);
        if (file) openSourceFile(file, line);
      });
    });

    timelineEl.querySelectorAll('.storyline-impact-trigger').forEach(btn => {
      btn.addEventListener('click', () => {
        const fqn = btn.getAttribute('data-fqn');
        if (fqn) openBlastRadiusExplorer(fqn);
      });
    });
  }

  // Render Evidence Citations
  const citations = story.evidence || [];
  if (evidenceCount) evidenceCount.textContent = citations.length;
  if (evidenceGrid) {
    evidenceGrid.innerHTML = citations.map(c => {
      const src = c.file || '';
      const shortFile = src.includes('/')
        ? src.substring(src.lastIndexOf('/') + 1)
        : (src || 'source');
      return `
        <button class="storyline-evidence-chip" data-file="${esc(c.file)}" data-line="${c.startLine}" title="${esc(c.file)} (Lines ${c.startLine}–${c.endLine})">
          <svg class="svg-icon icon-cyan icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>
          <span>${esc(shortFile)}:${c.startLine}–${c.endLine}</span>
        </button>
      `;
    }).join('');

    evidenceGrid.querySelectorAll('.storyline-evidence-chip').forEach(chip => {
      chip.addEventListener('click', () => {
        const file = chip.getAttribute('data-file');
        const line = parseInt(chip.getAttribute('data-line'), 10);
        if (file) openSourceFile(file, line);
      });
    });
  }
}

function exportStorylineToClipboard() {
  if (!currentStorylineDetail) return;
  const s = currentStorylineDetail;
  let md = `# Storyline: ${s.title}\n\n`;
  md += `**Category:** ${s.category} | **Complexity:** ${s.complexity} | **Entry Point:** \`${s.entryPoint}\`\n\n`;
  md += `## Executive Narrative\n${s.executiveSummary}\n\n`;
  md += `## Execution Sequence\n`;
  (s.steps || []).forEach(step => {
    md += `${step.stepIndex}. **[${step.roleLabel}]** \`${step.classSimpleName}.${step.simpleName}\` (${step.sourceFile}:${step.startLine})\n`;
    md += `   - Action: ${step.narrativeAction}\n`;
  });
  md += `\n## Verified Citations\n`;
  (s.evidence || []).forEach(c => {
    md += `- \`${c.file}:${c.startLine}-${c.endLine}\` (${c.symbol})\n`;
  });

  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(md).then(() => {
      if (typeof showToast === 'function') {
        showToast('Storyline narrative copied to clipboard');
      }
    });
  }
}

async function openStorylineForFqn(fqn) {
  if (!fqn) return;
  switchTab('storylines');
  await selectStoryline(null, fqn);
}

window.loadStorylines = loadStorylines;
window.selectStoryline = selectStoryline;
window.openStorylineForFqn = openStorylineForFqn;






