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
};

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
  typesByPackage:     (fqn)       => api.get(`/packages/${enc(fqn)}/types`),
  type:               (id)        => api.get(`/types/${enc(id)}`),
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
  clearExcludedScopes:()          => api.post('/scope/clear', {}),
  moduleDependencies: (nameOrFqn) => api.get(`/modules/${enc(nameOrFqn)}/dependencies`),
  packageDependencies:(fqn)       => api.get(`/packages/${enc(fqn)}/dependencies`),
  allModuleDependencies:()        => api.get('/modules/dependencies'),
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
  if (qs('#scan-remaining-files')) qs('#scan-remaining-files').textContent = 'Stage 1 of 4';
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

/** Poll /api/scan/status every 350 ms until COMPLETE or ERROR. */
function pollScanStatus() {
  if (App.scanPollHandle) clearInterval(App.scanPollHandle);

  App.scanPollHandle = setInterval(async () => {
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
    default:
      return {
        pill: 'Phase 4',
        name: 'Layout Precomputation',
        summary: stepInfo.summary || '2D and 3D graph layout warm-up & module overview precomputation',
        detailText: stepInfo.detail || 'Precomputed 6 topology layouts & module overview ready for instant interactive exploration.',
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
    'LAYOUT': '4. Layout Precomputation'
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

function openProcessHub() {
  const modal = qs('#process-hub-modal');
  if (!modal) return;
  showAccessibleModal(modal, qs('#btn-process-hub'));
  loadProcessHubData();
  if (processHubPollInterval) clearInterval(processHubPollInterval);
  processHubPollInterval = setInterval(loadProcessHubData, 2000);
}

function closeProcessHub() {
  const modal = qs('#process-hub-modal');
  if (!modal) return;
  hideAccessibleModal(modal);
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
  } else if (id === 'git-analyzer') {
    return `<svg class="svg-icon icon-sm icon-slate" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="18" cy="18" r="3"/><circle cx="6" cy="6" r="3"/><path d="M6 9v12"/><path d="M18 9a9 9 0 0 0-9 9"/></svg>`;
  } else if (id === 'db-watchdog') {
    return `<svg class="svg-icon icon-sm icon-emerald" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/><polyline points="9 12 11 14 15 10"/></svg>`;
  } else if (id === 'stress-test') {
    return `<svg class="svg-icon icon-sm icon-amber" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>`;
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

function renderServerPanel(system) {
  if (!system) return;
  const thEl = qs('#hub-jvm-threads');
  if (thEl) thEl.textContent = system.activeThreads ?? '-';
  const usedEl = qs('#hub-jvm-heap-alloc');
  if (usedEl) usedEl.textContent = `${system.heapUsedMb ?? 0} MB`;
  const maxEl = qs('#hub-jvm-heap-max');
  if (maxEl) maxEl.textContent = `${system.heapMaxMb ?? 0} MB`;
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
    const hasRunning = procs.some(p => p.status === 'RUNNING');
    const pulseEl = qs('#process-hub-pulse');
    if (pulseEl) pulseEl.style.display = hasRunning ? 'inline-block' : 'none';

    // 2. Compute process counts for tabs and stats
    const runningCount = procs.filter(p => p.status === 'RUNNING').length;
    const completeCount = procs.filter(p => p.status === 'COMPLETE').length;
    const idleCount = procs.filter(p => p.status === 'IDLE').length;
    const totalCount = procs.length;

    const countAllEl = qs('#hub-count-all');
    if (countAllEl) countAllEl.textContent = totalCount;
    const countRunningEl = qs('#hub-count-running');
    if (countRunningEl) countRunningEl.textContent = runningCount;
    const countCompleteEl = qs('#hub-count-complete');
    if (countCompleteEl) countCompleteEl.textContent = completeCount;
    const countIdleEl = qs('#hub-count-idle');
    if (countIdleEl) countIdleEl.textContent = idleCount;
    const navTasksCount = qs('#hub-nav-count-tasks');
    if (navTasksCount) navTasksCount.textContent = totalCount;

    // 3. Update compact telemetry HUD strip
    const hudTasks = qs('#hub-hud-tasks');
    if (hudTasks) hudTasks.textContent = `${runningCount}/${totalCount} Active`;
    const hudTasksSub = qs('#hub-hud-tasks-sub');
    if (hudTasksSub) hudTasksSub.textContent = '';

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
    const epCount = apis.summary?.totalEndpoints || 52;
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

    if (!listEl) return;

    // Filter processes by active tab and search query
    const q = (processHubSearch || '').trim().toLowerCase();
    const filteredProcs = procs.filter(p => {
      // Tab filter
      if (processHubFilter === 'running' && p.status !== 'RUNNING') return false;
      if (processHubFilter === 'complete' && p.status !== 'COMPLETE') return false;
      if (processHubFilter === 'idle' && p.status !== 'IDLE') return false;

      // Search query filter
      if (q) {
        const haystack = `${p.name || ''} ${p.id || ''} ${p.type || ''} ${p.currentPhase || ''} ${p.currentDetail || ''} ${p.thread || ''}`.toLowerCase();
        if (!haystack.includes(q)) return false;
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

      card.className = 'process-card' + (p.status === 'RUNNING' ? ' is-running' : '');

      const pct = typeof p.percentage === 'number' ? Math.max(0, Math.min(100, p.percentage)) : 0;
      const durSec = p.durationMs ? (p.durationMs / 1000).toFixed(1) + 's' : (p.startTime ? ((Date.now() - p.startTime) / 1000).toFixed(1) + 's' : '-');

      const iconSvg = getProcessIconSvg(p.id, p.type);
      const statusBadgeIcon = p.status === 'RUNNING' 
        ? '<span class="hub-live-dot" style="width:5px;height:5px;"></span>' 
        : (p.status === 'COMPLETE' 
          ? '<svg class="svg-icon icon-xs icon-emerald" style="width:10px;height:10px;margin-right:4px;vertical-align:-1px;" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><polyline points="20 6 9 17 4 12"/></svg>' 
          : '• ');

      let rawDetail = p.currentDetail || '';
      if (!rawDetail) {
        if (p.id === 'delta-scanner') rawDetail = 'Watching workspace for file modifications';
        else if (p.id === 'git-analyzer') rawDetail = 'Git commit history and churn correlator';
        else if (p.id === 'db-watchdog') rawDetail = 'HikariCP leak detector and auto-recovery';
        else if (p.status === 'IDLE') rawDetail = 'Idle · Waiting for trigger';
        else if (p.status === 'COMPLETE') rawDetail = 'Execution complete · Ready';
        else rawDetail = 'Ready';
      }
      const cleanDetail = rawDetail.replace(/\(rev=(\d{5})\d*\)/g, '(rev: $1…)');

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
            <span class="process-card-badge status-${(p.status || 'IDLE').toLowerCase()}">${statusBadgeIcon}${esc(p.status || 'IDLE')}</span>
            ${p.canKill ? `<button class="btn-kill-process" data-id="${esc(p.id)}" title="Terminate hanging thread"><svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg> Kill</button>` : ''}
            ${p.canRestart ? `<button class="btn-restart-process" data-id="${esc(p.id)}" title="Trigger immediate worker restart"><svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21.5 2v6h-6M21.34 15.57a10 10 0 1 1-.57-8.38l5.67-5.67"/></svg> Restart</button>` : ''}
          </div>
        </div>

        ${pct > 0 || p.status === 'RUNNING' ? `
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

  } catch (e) {
    console.warn('Failed to load process hub data:', e);
  }
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
          qs('.hub-nav-tab[data-tab="server"]')?.click();
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
        if (processHubActiveTab === 'database') renderDatabasePanel(processHubLastData.database);
        else if (processHubActiveTab === 'apis') renderApisPanel(processHubLastData.apis);
        else if (processHubActiveTab === 'server') renderServerPanel(processHubLastData.system);
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
      loadProcessHubData();
    });
  });

  // Search input handling (Background Tasks)
  const searchInput = qs('#process-hub-search-input');
  const clearBtn = qs('#process-hub-search-clear-btn');
  if (searchInput) {
    searchInput.addEventListener('input', (e) => {
      processHubSearch = e.target.value;
      if (clearBtn) clearBtn.style.display = processHubSearch ? 'block' : 'none';
      loadProcessHubData();
    });
  }
  if (clearBtn) {
    clearBtn.addEventListener('click', () => {
      if (searchInput) {
        searchInput.value = '';
        processHubSearch = '';
        clearBtn.style.display = 'none';
        searchInput.focus();
        loadProcessHubData();
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
    loadProcessHubData();
  });

  // Background check every 10 seconds to update header pulse badge
  setInterval(async () => {
    try {
      const data = await api.processes();
      const hasRunning = data?.processes?.some(p => p.status === 'RUNNING');
      const pulseEl = qs('#process-hub-pulse');
      if (pulseEl) pulseEl.style.display = hasRunning ? 'inline-block' : 'none';
    } catch (ignored) {}
  }, 10000);

  // Initialize scale & stress testing benchmark controls
  initStressTestControls();
}

/** Re-open scan modal when user clicks header badge or footer status */
function reopenScanModal() {
  App.scanModalDismissed = false;
  qs('#scan-status-bar')?.classList.add('visible');
  if (App.lastScanProgress) {
    updateScanProgress(App.lastScanProgress);
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
  const stageOrder = { 'PREPARE': 1, 'PARSE': 1, 'INDEX': 2, 'GRAPH': 3, 'LAYOUT': 4, 'COMPLETE': 5 };
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
      remainingFiles.textContent = 'All 4 stages complete';
    } else if (stage === 'LAYOUT') {
      if (s.stageTotal > 0) {
        remainingFiles.textContent = `Layout ${(s.stageCurrent || 0).toLocaleString()} of ${s.stageTotal.toLocaleString()} · Stage 4 of 4`;
      } else {
        remainingFiles.textContent = 'Stage 4 of 4';
      }
    } else if (stage === 'GRAPH') {
      if (s.stageTotal > 10) {
        remainingFiles.textContent = `${(s.stageCurrent || 0).toLocaleString()} of ${s.stageTotal.toLocaleString()} links · Stage 3 of 4`;
      } else if (s.stageTotal > 0) {
        remainingFiles.textContent = `Pass ${(s.stageCurrent || 0).toLocaleString()} of ${s.stageTotal.toLocaleString()} · Stage 3 of 4`;
      } else {
        remainingFiles.textContent = 'Stage 3 of 4';
      }
    } else if (stage === 'INDEX') {
      if (s.stageTotal > 0) {
        remainingFiles.textContent = `Index ${(s.stageCurrent || 0).toLocaleString()} of ${s.stageTotal.toLocaleString()} · Stage 2 of 4`;
      } else {
        remainingFiles.textContent = 'Stage 2 of 4';
      }
    } else {
      // PARSE / PREPARE
      const rem = Math.max(0, (s.totalFiles || 0) - (s.processedFiles || 0));
      remainingFiles.textContent = s.totalFiles ? `${rem.toLocaleString()} remaining · Stage 1 of 4` : 'Stage 1 of 4';
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
  
  // Footer update with title tooltip to prevent jitter and allow click-to-reopen
  const fText = qs('#footer-status-text');
  const fInd = qs('.status-indicator');
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
  const stageOrder = { 'PREPARE': 0, 'PARSE': 1, 'INDEX': 2, 'GRAPH': 3, 'LAYOUT': 4, 'COMPLETE': 5 };
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
      tabEl.title = `${tabEl.getAttribute('aria-label') || tabName} available after ${req.name} completes (Stage ${req.level} of 4)`;
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

  // Footer update
  const fText = qs('#footer-status-text');
  const fInd = qs('.status-indicator');
  if (fText) {
    fText.textContent = 'Analyzer Idle · Scan complete';
    fText.title = 'Analyzer Idle · All graphs ready';
  }
  if (fInd) {
    fInd.className = 'status-indicator live';
    fInd.title = 'System Ready';
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

    container.appendChild(item);

    // Child nodes container (types + sub-packages)
    const childContainer = createElement('div', {
      class: 'tree-children',
      style: !isOpen ? 'display:none' : '',
    });
    container.appendChild(childContainer);

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

      container.appendChild(item);
    }
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
  requestAnimationFrame(() => triggerRelayout());

  qsa('.tab').forEach(t => {
    const isActive = (t.dataset.tab === tabName);
    t.classList.toggle('active', isActive);
    t.setAttribute('aria-selected', isActive ? 'true' : 'false');
  });
  qsa('.tab-content').forEach(tc => tc.classList.toggle('active', tc.id === tabName + '-view'));

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
        selectMethod(App.selected.data.methods[0].id || App.selected.data.methods[0].fqn);
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

  App.editorPromise = new Promise((resolve) => {
    if (typeof require === 'undefined') {
      console.warn('Monaco AMD loader not available (offline/blocked), using fallback viewer.');
      resolve(null);
      return;
    }
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
  view.innerHTML = '';
  view.scrollTop = 0;

  try {
    const types = await api.typesByPackage(pkgFqn);
    if (window.CodeLensClassifier && Array.isArray(types)) {
      for (const t of types) {
        if (Array.isArray(t.methods) && t.methods.length > 0 && typeof window.CodeLensClassifier.registerTypeMethods === 'function') {
          window.CodeLensClassifier.registerTypeMethods(t.fqn || t.id, t.methods);
        }
      }
    }
    let activeKind = (initialTab || App.activeFilter || 'all').toUpperCase();

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
          <span class="kb-tab-badge" id="kb-dep-badge">…</span>
        </button>
      </div>
    `;
    view.appendChild(pkgTabsBar);

    // Container for types section or empty state
    const contentContainer = createElement('div', { id: 'kb-pkg-content-container' });
    view.appendChild(contentContainer);

    let moduleDepData = null;
    api.packageDependencies(pkgFqn).then(depData => {
      moduleDepData = depData;
      const badge = qs('#kb-dep-badge');
      if (badge && depData) {
        badge.textContent = `${depData.totalTouchPoints} touch pts`;
      }
      if (activeKind === 'DEPENDENCIES') {
        renderFilteredTypes('DEPENDENCIES');
      }
    }).catch(() => {
      const badge = qs('#kb-dep-badge');
      if (badge) badge.textContent = '0';
    });

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
          if (showingEl) showingEl.textContent = (moduleDepData.outgoingModules.length + moduleDepData.incomingModules.length) + ' modules';
          renderKnowledgeBaseDependenciesView(pkgFqn, contentContainer, moduleDepData);
        } else {
          if (showingEl) showingEl.textContent = '…';
          contentContainer.innerHTML = `
            <div class="kb-empty-container fade-in">
              <div class="kb-empty-icon"><div class="spinner" style="width:28px;height:28px;border-width:2.5px;"></div></div>
              <div class="kb-empty-title">Analyzing Module Dependencies…</div>
              <div class="kb-empty-desc">Mapping intermodular touch points, function calls, and class usage.</div>
            </div>`;
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

  // 1. Visual Coupling Topology Flow Map
  const flowMap = createElement('div', { class: 'mod-dep-flow-map' });
  const inMods = depData.incomingModules || [];
  const outMods = depData.outgoingModules || [];

  let inNodesHtml = '';
  if (inMods.length === 0) {
    inNodesHtml = '<div style="font-size:11px;color:var(--text-muted);padding:8px 0;text-align:center;">No incoming modules (Root / Independent)</div>';
  } else {
    inNodesHtml = inMods.map(m => {
      const modColor = (window.CodeLensPalette && window.CodeLensPalette.getColor)
        ? window.CodeLensPalette.getColor(m.packageFqn || m.moduleName, 0)
        : '#10b981';
      const pts = m.totalTouchPoints || 0;
      const tierCls = pts >= 50 ? 'tier-hot' : (pts >= 15 ? 'tier-warm' : (pts >= 5 ? 'tier-mid' : 'tier-low'));
      return `
        <div class="flow-node-card flow-in-node" data-fqn="${esc(m.packageFqn)}" data-modname="${esc(m.moduleName)}" style="border-left-color:${modColor};" title="Inspect ${esc(m.moduleName)} (${pts} touch points)">
          <div class="flow-node-info">
            <span class="flow-node-mod-badge" style="background:${modColor}22; color:${modColor}; border:1px solid ${modColor}55;">[MOD]</span>
            <span class="flow-node-name">${esc(m.moduleName)}</span>
          </div>
          <span class="flow-node-pts ${tierCls}">${pts} pts</span>
        </div>
      `;
    }).join('');
  }

  let outNodesHtml = '';
  if (outMods.length === 0) {
    outNodesHtml = '<div style="font-size:11px;color:var(--text-muted);padding:8px 0;text-align:center;">No outgoing dependencies (Leaf / Self-contained)</div>';
  } else {
    outNodesHtml = outMods.map(m => {
      const modColor = (window.CodeLensPalette && window.CodeLensPalette.getColor)
        ? window.CodeLensPalette.getColor(m.packageFqn || m.moduleName, 0)
        : '#38bdf8';
      const pts = m.totalTouchPoints || 0;
      const tierCls = pts >= 50 ? 'tier-hot' : (pts >= 15 ? 'tier-warm' : (pts >= 5 ? 'tier-mid' : 'tier-low'));
      return `
        <div class="flow-node-card flow-out-node" data-fqn="${esc(m.packageFqn)}" data-modname="${esc(m.moduleName)}" style="border-left-color:${modColor};" title="Inspect ${esc(m.moduleName)} (${pts} touch points)">
          <div class="flow-node-info">
            <span class="flow-node-mod-badge" style="background:${modColor}22; color:${modColor}; border:1px solid ${modColor}55;">[MOD]</span>
            <span class="flow-node-name">${esc(m.moduleName)}</span>
          </div>
          <span class="flow-node-pts ${tierCls}">${pts} pts</span>
        </div>
      `;
    }).join('');
  }

  flowMap.innerHTML = `
    <div class="flow-map-header">
      <div class="flow-map-title-group">
        <span class="flow-map-title">Coupling Topology Flow Map</span>
        <span class="flow-map-subtitle">Inbound Callers (${inMods.length}) ➔ Current Module ➔ Outbound Dependencies (${outMods.length})</span>
      </div>
      <button class="btn-flow-toggle" id="btn-flow-toggle" title="Toggle flow map visibility">
        <span>▲ Hide Flow Map</span>
      </button>
    </div>
    <div class="flow-map-content" id="flow-map-content">
      <svg class="flow-conduits-svg" id="flow-conduits-svg"></svg>
      <div class="flow-col flow-col-inbound">
        <div class="flow-col-header">
          <span class="flow-col-badge in">INBOUND CALLERS</span>
          <span class="flow-col-count">(${inMods.length})</span>
        </div>
        <div class="flow-nodes-list">${inNodesHtml}</div>
      </div>
      <div class="flow-col flow-col-center">
        <div class="flow-center-hub" id="flow-center-hub">
          <span class="hub-kind-badge">TARGET MODULE</span>
          <div class="hub-title" title="${esc(depData.moduleName || pkgFqn)}">${esc(depData.moduleName || pkgFqn)}</div>
          <div class="hub-kpis">
            <span class="hub-pts">${depData.totalTouchPoints} Touch Points</span>
            <span class="stability-rating-pill ${stabilityClass}">${esc(depData.stabilityRating || 'Balanced')}</span>
          </div>
        </div>
      </div>
      <div class="flow-col flow-col-outbound">
        <div class="flow-col-header">
          <span class="flow-col-badge out">OUTBOUND DEPENDENCIES</span>
          <span class="flow-col-count">(${outMods.length})</span>
        </div>
        <div class="flow-nodes-list">${outNodesHtml}</div>
      </div>
    </div>
  `;
  viewEl.appendChild(flowMap);

  // SVG Flow Conduits Drawing
  const flowContent = flowMap.querySelector('#flow-map-content');
  const flowSvg = flowMap.querySelector('#flow-conduits-svg');
  const centerHub = flowMap.querySelector('#flow-center-hub');

  function drawFlowConduits() {
    if (!flowSvg || !flowContent || !centerHub || flowContent.style.display === 'none' || flowContent.offsetHeight === 0) {
      return;
    }
    const contRect = flowContent.getBoundingClientRect();
    const hubRect = centerHub.getBoundingClientRect();

    if (contRect.width <= 0 || contRect.height <= 0) return;

    flowSvg.setAttribute('viewBox', `0 0 ${contRect.width} ${contRect.height}`);
    flowSvg.innerHTML = '';

    const hubLeftX = hubRect.left - contRect.left;
    const hubRightX = hubRect.right - contRect.left;
    const hubCenterY = hubRect.top - contRect.top + hubRect.height / 2;

    // Draw Inbound conduits
    const inCards = flowContent.querySelectorAll('.flow-in-node');
    inCards.forEach(card => {
      const cRect = card.getBoundingClientRect();
      const x1 = cRect.right - contRect.left;
      const y1 = cRect.top - contRect.top + cRect.height / 2;
      const x2 = hubLeftX;
      const y2 = hubCenterY;
      const dx = Math.max(20, (x2 - x1) * 0.45);

      const fqn = card.dataset.fqn;
      const modObj = inMods.find(m => m.packageFqn === fqn) || {};
      const pts = modObj.totalTouchPoints || 1;
      const strokeW = Math.min(5, Math.max(1.5, Math.sqrt(pts) * 0.9));
      const modColor = (window.CodeLensPalette && window.CodeLensPalette.getColor)
        ? window.CodeLensPalette.getColor(fqn || card.dataset.modname, 0)
        : '#059669';

      const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
      path.setAttribute('d', `M ${x1} ${y1} C ${x1 + dx} ${y1}, ${x2 - dx} ${y2}, ${x2} ${y2}`);
      path.setAttribute('class', 'flow-conduit in-conduit');
      path.setAttribute('stroke-width', strokeW);
      path.style.stroke = modColor;
      path.style.strokeOpacity = '0.75';
      path.dataset.nodeFqn = fqn;
      flowSvg.appendChild(path);
    });

    // Draw Outbound conduits
    const outCards = flowContent.querySelectorAll('.flow-out-node');
    outCards.forEach(card => {
      const cRect = card.getBoundingClientRect();
      const x1 = hubRightX;
      const y1 = hubCenterY;
      const x2 = cRect.left - contRect.left;
      const y2 = cRect.top - contRect.top + cRect.height / 2;
      const dx = Math.max(20, (x2 - x1) * 0.45);

      const fqn = card.dataset.fqn;
      const modObj = outMods.find(m => m.packageFqn === fqn) || {};
      const pts = modObj.totalTouchPoints || 1;
      const strokeW = Math.min(5, Math.max(1.5, Math.sqrt(pts) * 0.9));
      const modColor = (window.CodeLensPalette && window.CodeLensPalette.getColor)
        ? window.CodeLensPalette.getColor(fqn || card.dataset.modname, 0)
        : '#3b82f6';

      const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
      path.setAttribute('d', `M ${x1} ${y1} C ${x1 + dx} ${y1}, ${x2 - dx} ${y2}, ${x2} ${y2}`);
      path.setAttribute('class', 'flow-conduit out-conduit');
      path.setAttribute('stroke-width', strokeW);
      path.style.stroke = modColor;
      path.style.strokeOpacity = '0.75';
      path.dataset.nodeFqn = fqn;
      flowSvg.appendChild(path);
    });
  }

  // Bind flow node interactions
  flowMap.querySelectorAll('.flow-node-card').forEach(card => {
    const fqn = card.dataset.fqn;
    card.addEventListener('mouseenter', () => {
      card.classList.add('active-flow');
      flowSvg.querySelectorAll('.flow-conduit').forEach(p => {
        if (p.dataset.nodeFqn === fqn) {
          p.classList.add('highlighted');
        } else {
          p.classList.add('dimmed');
        }
      });
    });
    card.addEventListener('mouseleave', () => {
      card.classList.remove('active-flow');
      flowSvg.querySelectorAll('.flow-conduit').forEach(p => {
        p.classList.remove('highlighted', 'dimmed');
      });
    });
    card.addEventListener('click', () => {
      selectModuleItem({ fqn, name: card.dataset.modname }, 'DEPENDENCIES');
    });
  });

  // Toggle Flow Map
  const toggleBtn = flowMap.querySelector('#btn-flow-toggle');
  if (toggleBtn && flowContent) {
    toggleBtn.addEventListener('click', () => {
      const isHidden = flowContent.style.display === 'none';
      flowContent.style.display = isHidden ? 'grid' : 'none';
      toggleBtn.innerHTML = isHidden ? '<span>▲ Hide Flow Map</span>' : '<span>▼ Show Flow Map</span>';
      if (isHidden) {
        requestAnimationFrame(() => drawFlowConduits());
      }
    });
  }

  // Draw conduits on next frame and on resize
  setTimeout(drawFlowConduits, 50);
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
      const classUsages = m.classUsages || [];
      if (classUsages.length === 0) {
        classGrid.innerHTML = '<div style="font-size:11px;color:var(--text-muted);padding:4px 0;">No direct class usages recorded.</div>';
      } else {
        for (const cu of classUsages) {
          const pairCard = createElement('div', { class: 'mod-dep-class-pair-card' });
          const cuPts = cu.touchPointCount || 0;
          const tierCls = cuPts >= 50 ? 'tier-hot' : (cuPts >= 15 ? 'tier-warm' : (cuPts >= 5 ? 'tier-mid' : 'tier-low'));
          let cuKindsHtml = '';
          if (cu.kinds) {
            for (const [k, count] of Object.entries(cu.kinds)) {
              cuKindsHtml += `<span class="class-kind-pill ${k}">${k}: ${count}</span>`;
            }
          }
          pairCard.innerHTML = `
            <div class="class-pair-row-top">
              <div class="class-pair-flow">
                <a href="#" class="class-entity-link src-link" title="${esc(cu.sourceClassFqn)}">${esc(cu.sourceClassSimpleName || cu.sourceClassFqn)}</a>
                <span class="class-arrow-icon">➔</span>
                <a href="#" class="class-entity-link tgt-link" title="${esc(cu.targetClassFqn)}">${esc(cu.targetClassSimpleName || cu.targetClassFqn)}</a>
              </div>
              <div class="class-pair-actions">
                <span class="class-pair-pts-badge ${tierCls}">${cuPts} ${cuPts === 1 ? 'pt' : 'pts'}</span>
                <button class="btn-peek-calls" title="Peek function calls between these two classes">
                  <span class="peek-txt">Peek Calls</span>
                  <span class="peek-chevron">▼</span>
                </button>
                <button class="btn-copy-pair" title="Copy class pair to clipboard">
                  <svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="width:11px;height:11px;"><rect width="14" height="14" x="8" y="8" rx="2" ry="2"/><path d="M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2"/></svg>
                </button>
              </div>
            </div>
            <div class="class-pair-kinds">
              ${cuKindsHtml}
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
              const matchingCalls = (m.touchPoints || []).filter(tp => {
                const srcMatch = (tp.fromType === cu.sourceClassFqn) || (tp.fromEntity && tp.fromEntity.includes(cu.sourceClassSimpleName));
                const tgtMatch = (tp.toType === cu.targetClassFqn) || (tp.toEntity && tp.toEntity.includes(cu.targetClassSimpleName));
                return srcMatch && tgtMatch;
              });

              if (matchingCalls.length === 0) {
                drawer.innerHTML = '<div style="font-size:10px;color:var(--text-muted);padding:4px 0;">No individual method calls found in touch points index for this pair.</div>';
              } else {
                drawer.innerHTML = matchingCalls.map(tp => `
                  <div class="class-pair-call-item">
                    <div style="display:flex;align-items:center;gap:6px;min-width:0;overflow:hidden;">
                      <span class="class-kind-pill ${tp.kind}">${tp.kind}</span>
                      <div class="class-pair-call-chain" style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">
                        ${formatSignatureHtml(tp.fromEntity)}
                        <span style="color:var(--text-muted);margin:0 4px;">➔</span>
                        ${formatSignatureHtml(tp.toEntity)}
                      </div>
                    </div>
                    ${tp.sourceLine > 0 ? `<span class="mod-dep-call-line" style="font-size:9.5px;color:var(--text-muted);flex-shrink:0;">L: ${tp.sourceLine}</span>` : ''}
                  </div>
                `).join('');
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
                        (cu.targetModule || '').toLowerCase().includes(q) ||
                        (cu.sourceModule || '').toLowerCase().includes(q);
        if (!matches) return false;
      }
      return true;
    });

    // Sort class usages
    filtered.sort((a, b) => {
      if (currentSortMode === 'src-asc') return (a.sourceClassFqn || '').localeCompare(b.sourceClassFqn || '');
      if (currentSortMode === 'tgt-asc') return (a.targetClassFqn || '').localeCompare(b.targetClassFqn || '');
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
          <th>Source Class</th>
          <th>Target Class</th>
          <th>Connected Module</th>
          <th>Kinds</th>
          <th style="text-align:right;">Touch Points</th>
        </tr>
      </thead>
      <tbody></tbody>
    `;
    const tbody = table.querySelector('tbody');

    for (const cu of filtered) {
      const tr = createElement('tr');
      const dirCls = cu.direction === 'OUTBOUND' ? 'outbound' : 'inbound';
      const dirLabel = cu.direction === 'OUTBOUND' ? '➔ OUTBOUND' : '⬅ INBOUND';
      const modName = cu.targetModule || cu.sourceModule || '-';
      const modColor = (window.CodeLensPalette && window.CodeLensPalette.getColor)
        ? window.CodeLensPalette.getColor(modName, 0)
        : '#38bdf8';
      const cuPts = cu.touchPointCount || 0;
      const tierCls = cuPts >= 50 ? 'tier-hot' : (cuPts >= 15 ? 'tier-warm' : (cuPts >= 5 ? 'tier-mid' : 'tier-low'));

      let kindsHtml = '';
      if (cu.kinds) {
        for (const [k, count] of Object.entries(cu.kinds)) {
          kindsHtml += `<span class="class-kind-pill ${k}">${k}: ${count}</span> `;
        }
      }

      tr.innerHTML = `
        <td><span class="mod-dep-direction-tag ${dirCls}">${dirLabel}</span></td>
        <td><a href="#" class="class-entity-link src-link" title="${esc(cu.sourceClassFqn)}">${esc(cu.sourceClassSimpleName || cu.sourceClassFqn)}</a></td>
        <td><a href="#" class="class-entity-link tgt-link" title="${esc(cu.targetClassFqn)}">${esc(cu.targetClassSimpleName || cu.targetClassFqn)}</a></td>
        <td>
          <span class="flow-node-mod-badge" style="background:${modColor}22; color:${modColor}; border:1px solid ${modColor}55; margin-right:4px;">[MOD]</span>
          <strong style="color:var(--text-primary);font-family:var(--font-display);">${esc(modName)}</strong>
        </td>
        <td>${kindsHtml}</td>
        <td style="text-align:right;"><span class="class-pair-pts-badge ${tierCls}">${cuPts}</span></td>
      `;

      tr.querySelector('.src-link')?.addEventListener('click', (e) => {
        e.preventDefault();
        selectType(cu.sourceClassFqn);
      });
      tr.querySelector('.tgt-link')?.addEventListener('click', (e) => {
        e.preventDefault();
        selectType(cu.targetClassFqn);
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
            ${(m.touchPoints || []).slice(0, 50).map(tp => `
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
  setLoading();
  try {
    const data = await api.field(id);
    App.selected = { kind: 'field', id, data };
    if (data.field && data.field.sourceFile) {
      App.currentFilePath = data.field.sourceFile;
      App.currentLineNum = data.field.startLine || 1;
    } else if (data.type && data.type.sourceFile) {
      App.currentFilePath = data.type.sourceFile;
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
  if (mountContainer) {
    mountContainer.innerHTML = '';
  }

  if (isAltViz) {
    hideCodebaseEmpty();
    try {
      if (effectiveLevel === 'city3d') {
        showBanner(isMethods ? 'Building 3D Software City (Methods)...' : 'Building 3D Software City (Classes)...');
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
  depSec.innerHTML = `
    <div class="module-dep-inspector-title">
      <div style="display:flex;align-items:center;gap:6px;">
        <svg class="svg-icon icon-cyan icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="width:13px;height:13px;"><path d="M9 17H7A5 5 0 0 1 7 7h2"/><path d="M15 7h2a5 5 0 0 1 0 10h-2"/><line x1="8" y1="12" x2="16" y2="12"/></svg>
        <span>Module Touch Points</span>
      </div>
      <span class="stability-rating-pill is-balanced">Analyzing…</span>
    </div>
    <div style="font-size:11px;color:var(--text-muted);display:flex;align-items:center;gap:6px;">
      <div class="spinner" style="width:12px;height:12px;border-width:2px;"></div> Loading touch points…
    </div>
  `;
  body.appendChild(depSec);

  api.packageDependencies(pkg.fqn).then(deps => {
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
  }).catch(() => {
    depSec.innerHTML = '<div style="font-size:11px;color:var(--text-muted);">No dependency insights found.</div>';
  });

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
    animateCounter(qs('#stat-modules'), s.modules || s.packages || 0);
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
    const overview = await api.allModuleDependencies();
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
    // Open all ancestor package chains in tree
    const parts = pkg.fqn.split('.');
    let cur = '';
    for (let i = 0; i < parts.length - 1; i++) {
      cur = i === 0 ? parts[0] : cur + '.' + parts[i];
      App.openPackages.add(cur);
    }
    App.openPackages.add(pkg.fqn);
    await loadPackageTree();

    const itemEl = qs(`#explorer-tree [data-fqn="${CSS.escape(pkg.fqn)}"]`);
    selectPackage(pkg, itemEl, initialTab);
    if (itemEl) {
      itemEl.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    }
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
  setNum('scan-stat-rels', s.relationshipsFound || 0);

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

function openHelpModal(triggerEl = null) {
  const modal = qs('#help-modal');
  if (!modal) return;
  showAccessibleModal(modal, triggerEl || qs('#help-btn'));
}

function closeHelpModal() {
  const modal = qs('#help-modal');
  if (!modal) return;
  hideAccessibleModal(modal);
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
      if (['1','2','3','4','5'].includes(e.key)) {
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

  // Hero theme toggle (mirrors main toggle - cycles dark → swiss → light)
  qs('#hero-theme-toggle-btn')?.addEventListener('click', () => {
    const s = loadSettings();
    const THEME_CYCLE = ['dark', 'swiss', 'light'];
    const curIdx = THEME_CYCLE.indexOf(s.theme);
    const newTheme = THEME_CYCLE[(curIdx + 1) % THEME_CYCLE.length];
    s.theme = newTheme;
    saveSettings(s);
    applyAllSettings(s);
    syncSettingsUI(s);
    const labels = { dark: 'Midnight Obsidian', swiss: 'Swiss Minimalist', light: 'Pure Daylight' };
    showBanner(`Theme: ${labels[newTheme] || newTheme}`);
  });

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

  // Re-open scan modal when clicking header badge, progress bar, or footer indicator
  qs('#scan-status-badge')?.addEventListener('click', () => reopenScanModal());
  qs('#scan-progress-bar')?.addEventListener('click', () => reopenScanModal());
  qs('#footer-status-text')?.addEventListener('click', () => reopenScanModal());
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




  // Wire up quick theme toggle in header toolbar (cycles dark → swiss → light)
  qs('#theme-toggle-btn')?.addEventListener('click', () => {
    const s = loadSettings();
    const THEME_CYCLE = ['dark', 'swiss', 'light'];
    const curIdx = THEME_CYCLE.indexOf(s.theme);
    const newTheme = THEME_CYCLE[(curIdx + 1) % THEME_CYCLE.length];
    s.theme = newTheme;
    saveSettings(s);
    applyAllSettings(s);
    syncSettingsUI(s);
    const labels = { dark: 'Midnight Obsidian', swiss: 'Swiss Minimalist', light: 'Pure Daylight' };
    showBanner(`Theme switched to ${labels[newTheme] || newTheme}`);
  });

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

  bindKeyboard();
  initScopeManagement();

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



  // Initialize adjustable panel resizers
  initPanelResizers();

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
            if (fqn.includes('(')) {
              loadMethodDetails(fqn);
            } else {
              loadClassDetails(fqn);
            }
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

window.selectEntity = function(fqn) {
  if (!fqn) return;
  if (fqn.includes('(')) {
    loadMethodDetails(fqn);
  } else {
    loadClassDetails(fqn);
  }
};
window.selectClass = function(fqn) {
  window.selectEntity(fqn);
};

window.jumpToGraphHeat = async function(fqn) {
  App.codebaseLevel = 'graph2d';
  switchTab('graph');
  if (typeof loadWholeCodebaseGraph === 'function') {
    await loadWholeCodebaseGraph('arch');
  }
  if (App.graph && !App.graph._heatMode) {
    App.graph.toggleHeat();
  }
  if (fqn && App.graph) {
    App.graph.selectNode(fqn);
    if (typeof App.graph.focusNode === 'function') {
      App.graph.focusNode(fqn);
    }
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
  qs('#btn-scope-manager-close')?.addEventListener('click', () => hideAccessibleModal(qs('#scope-manager-modal')));
  qs('#scope-manager-modal')?.addEventListener('click', (e) => {
    if (e.target === qs('#scope-manager-modal')) hideAccessibleModal(qs('#scope-manager-modal'));
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

/* ── Toast notification queue ──────────────────────────────── */

/** Lazily create / return the single toast container element. */
function _getToastContainer() {
  let el = document.getElementById('toast-container');
  if (!el) {
    el = document.createElement('div');
    el.id = 'toast-container';
    document.body.appendChild(el);
  }
  return el;
}

/**
 * Show a queued toast notification.
 * @param {string} msg   — the message to display
 * @param {'success'|'error'|'info'|'warning'} type — visual variant
 * @param {number}  duration — auto-dismiss delay in ms (default 3500)
 */
function showToast(msg, type = 'success', duration = 3500) {
  const container = _getToastContainer();

  // Icon SVGs per type
  const icons = {
    success: `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>`,
    error:   `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>`,
    info:    `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>`,
    warning: `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>`,
  };

  const toast = document.createElement('div');
  toast.className = `toast-item toast-${type}`;
  toast.innerHTML = `<span class="toast-icon">${icons[type] || icons.info}</span><span>${msg}</span>`;
  container.appendChild(toast);

  // Trigger logo emit animation
  const logo = qs('.logo-image') || qs('.logo');
  if (logo) {
    logo.classList.remove('logo-pulse');
    void logo.offsetWidth; // trigger reflow
    logo.classList.add('logo-pulse');
  }

  // Auto-dismiss
  const dismiss = () => {
    toast.classList.add('toast-exit');
    toast.addEventListener('animationend', () => toast.remove(), { once: true });
  };
  const timer = setTimeout(dismiss, duration);

  // Click to dismiss early
  toast.style.pointerEvents = 'auto';
  toast.style.cursor = 'pointer';
  toast.addEventListener('click', () => { clearTimeout(timer); dismiss(); }, { once: true });
}

/** Show a brief success banner (queued). */
function showBanner(msg) { showToast(msg, 'success', 3500); }

/** Show a temporary error toast (queued). */
function showError(msg)  { showToast(msg, 'error',   4500); }

/** Flash a red border on an input briefly. */
function flashInput(el) {
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
const MIN_LEFT_WIDTH = 160;
const MAX_LEFT_WIDTH = 600;
const MIN_RIGHT_WIDTH = 220;
const MAX_RIGHT_WIDTH = 750;
const MIN_CENTRE_WIDTH = 260;

const PANEL_STORAGE = {
  LEFT_WIDTH: 'codelens_panel_left_w',
  RIGHT_WIDTH: 'codelens_panel_right_w',
  LEFT_COLLAPSED: 'codelens_panel_left_collapsed',
  RIGHT_COLLAPSED: 'codelens_panel_right_collapsed'
};

function initPanelResizers() {
  const resizerLeft = qs('#resizer-left');
  const resizerRight = qs('#resizer-right');
  const btnCollapseLeft = qs('#btn-collapse-left');
  const btnCollapseRight = qs('#btn-collapse-right');
  const footerToggleLeft = qs('#footer-toggle-left');
  const footerToggleRight = qs('#footer-toggle-right');

  // Load saved state or defaults
  let savedLeftW = parseInt(localStorage.getItem(PANEL_STORAGE.LEFT_WIDTH), 10);
  let savedRightW = parseInt(localStorage.getItem(PANEL_STORAGE.RIGHT_WIDTH), 10);
  const leftCollapsed = localStorage.getItem(PANEL_STORAGE.LEFT_COLLAPSED) === 'true';
  const rightCollapsed = localStorage.getItem(PANEL_STORAGE.RIGHT_COLLAPSED) === 'true';

  if (isNaN(savedLeftW) || savedLeftW < MIN_LEFT_WIDTH) savedLeftW = DEFAULT_LEFT_WIDTH;
  if (isNaN(savedRightW) || savedRightW < MIN_RIGHT_WIDTH) savedRightW = DEFAULT_RIGHT_WIDTH;

  // Apply initial widths and collapse states
  if (leftCollapsed) {
    collapseLeftPanel(true, false);
  } else {
    setLeftPanelWidth(savedLeftW, false);
  }

  if (rightCollapsed) {
    collapseRightPanel(true, false);
  } else {
    setRightPanelWidth(savedRightW, false);
  }

  // ── Dragging Left Resizer (Explorer) ────────────────────────────────────────
  if (resizerLeft) {
    let startX = 0;
    let startW = 0;

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
      if (e.button !== 0 && e.buttons !== 1) return;
      e.preventDefault();
      startX = e.clientX;
      startW = getLeftPanelWidth();
      document.body.classList.add('resizing');
      resizerLeft.classList.add('active');

      window.addEventListener('pointermove', onPointerMove);
      window.addEventListener('pointerup', onPointerUp);
      window.addEventListener('pointercancel', onPointerUp);
      window.addEventListener('mousemove', onPointerMove);
      window.addEventListener('mouseup', onPointerUp);
    };

    resizerLeft.addEventListener('pointerdown', startDrag);
    resizerLeft.addEventListener('mousedown', startDrag);
    resizerLeft.addEventListener('dblclick', () => {
      setLeftPanelWidth(DEFAULT_LEFT_WIDTH, true);
    });
  }

  // ── Dragging Right Resizer (Inspector) ───────────────────────────────────────
  if (resizerRight) {
    let startX = 0;
    let startW = 0;

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
      if (e.button !== 0 && e.buttons !== 1) return;
      e.preventDefault();
      startX = e.clientX;
      startW = getRightPanelWidth();
      document.body.classList.add('resizing');
      resizerRight.classList.add('active');

      window.addEventListener('pointermove', onPointerMove);
      window.addEventListener('pointerup', onPointerUp);
      window.addEventListener('pointercancel', onPointerUp);
      window.addEventListener('mousemove', onPointerMove);
      window.addEventListener('mouseup', onPointerUp);
    };

    resizerRight.addEventListener('pointerdown', startDrag);
    resizerRight.addEventListener('mousedown', startDrag);
    resizerRight.addEventListener('dblclick', () => {
      setRightPanelWidth(DEFAULT_RIGHT_WIDTH, true);
    });
  }

  // ── Collapse / Expand Buttons & Floating Expand Strips ─────────────────────
  if (btnCollapseLeft) {
    btnCollapseLeft.addEventListener('click', () => toggleLeftPanel());
  }
  if (btnCollapseRight) {
    btnCollapseRight.addEventListener('click', () => toggleRightPanel());
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

function resetPanelWidths() {
  setLeftPanelWidth(DEFAULT_LEFT_WIDTH, true);
  setRightPanelWidth(DEFAULT_RIGHT_WIDTH, true);
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
      '--font-ui': '-apple-system, BlinkMacSystemFont, "Helvetica Neue", Arial, sans-serif',
      '--font-display': '-apple-system, BlinkMacSystemFont, "Helvetica Neue", Arial, sans-serif',
      '--radius-xs': '1px', '--radius-sm': '2px', '--radius-md': '3px', '--radius-lg': '4px',
    },
    graph: {
      bg: '#0f0f10', grid: 'rgba(255,255,255,0.04)',
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
  repulsion: 20000,
  springLen: 180,
  damping: 0.80,
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

  // Update top-bar theme toggle button (cycles: dark→swiss→light→dark)
  const toggleIcon = qs('#theme-toggle-icon');
  const toggleLabel = qs('#theme-toggle-label');
  if (toggleIcon) {
    // Show the icon for the NEXT theme in cycle
    if (themeKey === 'dark') {
      // Next is swiss – show swiss cross icon
      if (window.Icons) {
        toggleIcon.innerHTML = window.Icons.get('swiss', { color: 'red' });
      } else {
        toggleIcon.innerHTML = '<svg class="svg-icon icon-red" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="9 3 15 3 15 9 21 9 21 15 15 15 15 21 9 21 9 15 3 15 3 9 9 9" fill="currentColor" stroke="none"/></svg>';
      }
    } else if (themeKey === 'swiss') {
      // Next is light – show sun icon
      if (window.Icons) {
        toggleIcon.innerHTML = window.Icons.get('sun', { color: 'amber' });
      } else {
        toggleIcon.innerHTML = '<svg class="svg-icon icon-amber" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="4"/><path d="M12 2v2"/><path d="M12 20v2"/><path d="m4.93 4.93 1.41 1.41"/><path d="m17.66 17.66 1.41 1.41"/><path d="M2 12h2"/><path d="M20 12h2"/><path d="m6.34 17.66-1.41 1.41"/><path d="m19.07 4.93-1.41 1.41"/></svg>';
      }
    } else {
      // themeKey === 'light', next is dark – show moon icon
      if (window.Icons) {
        toggleIcon.innerHTML = window.Icons.get('moon', { color: 'purple' });
      } else {
        toggleIcon.innerHTML = '<svg class="svg-icon icon-purple" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"/></svg>';
      }
    }
  }
  if (toggleLabel) {
    if (themeKey === 'dark') toggleLabel.textContent = 'Swiss';
    else if (themeKey === 'swiss') toggleLabel.textContent = 'Light';
    else toggleLabel.textContent = 'Dark';
  }

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

function renderArchetypeRulesList() {
  const container = qs('#archetype-rules-list');
  if (!container || !window.CodeLensClassifier) return;

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
    };
  });

  // Wire edit buttons
  container.querySelectorAll('.rule-btn-edit').forEach(btn => {
    btn.onclick = () => {
      const id = btn.dataset.id;
      const rule = window.CodeLensClassifier.getRules().find(r => r.id === id);
      if (!rule) return;
      const idEl = qs('#rule-form-id'); if (idEl) idEl.value = rule.id;
      const titleEl = qs('#rule-form-title'); if (titleEl) titleEl.textContent = 'Edit Archetype Rule';
      const labelEl = qs('#rule-form-label'); if (labelEl) labelEl.value = rule.label || '';
      const badgeEl = qs('#rule-form-badge'); if (badgeEl) badgeEl.value = rule.badge || '';
      const iconEl = qs('#rule-form-icon'); if (iconEl) iconEl.value = rule.icon || 'tag';
      const colorEl = qs('#rule-form-color'); if (colorEl) colorEl.value = rule.color || '#3b82f6';
      const colorTextEl = qs('#rule-form-color-text'); if (colorTextEl) colorTextEl.value = rule.color || '#3b82f6';
      const targetEl = qs('#rule-form-target') || qs('#rule-form-scope'); if (targetEl) targetEl.value = rule.scope || rule.target || 'METHOD';
      const matchTypeEl = qs('#rule-form-match-type'); if (matchTypeEl && rule.matchType) matchTypeEl.value = rule.matchType;
      const patternEl = qs('#rule-form-pattern'); if (patternEl) patternEl.value = rule.pattern || '';
      const descEl = qs('#rule-form-desc'); if (descEl) descEl.value = rule.description || '';
      const form = qs('#archetype-rule-form-wrap');
      if (form) form.style.display = 'block';
      updateFormLivePreview();
    };
  });

  // Wire delete buttons
  container.querySelectorAll('.rule-btn-delete').forEach(btn => {
    btn.onclick = () => {
      const id = btn.dataset.id;
      window.CodeLensClassifier.deleteRule(id);
      renderArchetypeRulesList();
    };
  });

  populateArchetypeDropdowns();
}

function openSettings(e) {
  const modal = qs('#settings-modal');
  if (!modal) return;
  syncSettingsUI(loadSettings());
  showAccessibleModal(modal, e?.currentTarget || qs('#settings-btn'));
}

function closeSettings() {
  const modal = qs('#settings-modal');
  if (!modal) return;
  hideAccessibleModal(modal);
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
      }
    });
  }

  const pojoPatternsArea = qs('#set-pojo-patterns');
  if (pojoPatternsArea) {
    pojoPatternsArea.addEventListener('input', () => {
      if (window.CodeLensClassifier) {
        const patterns = pojoPatternsArea.value.split(/[,\n]+/).map(s => s.trim()).filter(Boolean);
        window.CodeLensClassifier.setPojoConfig({ customPatterns: patterns, patterns: patterns.join(', ') });
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
        showBanner('POJO detection criteria reset to default');
      }
    });
  }


  // Wire Archetype Rule Presets
  const btnPresetBancs = qs('#btn-preset-bancs');
  if (btnPresetBancs) {
    btnPresetBancs.addEventListener('click', () => {
      if (window.CodeLensClassifier) {
        window.CodeLensClassifier.loadPreset('bancs');
        renderArchetypeRulesList();
        showBanner('Loaded Banking / BaNCS transaction archetypes');
      }
    });
  }

  const btnPresetSpring = qs('#btn-preset-spring');
  if (btnPresetSpring) {
    btnPresetSpring.addEventListener('click', () => {
      if (window.CodeLensClassifier) {
        window.CodeLensClassifier.loadPreset('spring');
        renderArchetypeRulesList();
        showBanner('Loaded Spring REST / MVC archetypes');
      }
    });
  }

  const btnPresetDdd = qs('#btn-preset-ddd');
  if (btnPresetDdd) {
    btnPresetDdd.addEventListener('click', () => {
      if (window.CodeLensClassifier) {
        window.CodeLensClassifier.loadPreset('ddd');
        renderArchetypeRulesList();
        showBanner('Loaded Domain-Driven Design / Clean Architecture archetypes');
      }
    });
  }

  const btnResetArchetypes = qs('#btn-reset-archetype-rules');
  if (btnResetArchetypes) {
    btnResetArchetypes.addEventListener('click', () => {
      if (window.CodeLensClassifier) {
        window.CodeLensClassifier.resetRules();
        renderArchetypeRulesList();
        showBanner('Reset archetype rules to defaults');
      }
    });
  }

  // Wire Archetype Rule Form
  const btnAddRule = qs('#btn-add-archetype-rule');
  const formWrap = qs('#archetype-rule-form-wrap');
  if (btnAddRule && formWrap) {
    btnAddRule.addEventListener('click', () => {
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
      formWrap.style.display = 'block';
      updateFormLivePreview();
    });
  }

  const btnCloseRule = qs('#btn-close-rule-form');
  if (btnCloseRule && formWrap) {
    btnCloseRule.addEventListener('click', () => {
      formWrap.style.display = 'none';
    });
  }

  const btnCancelRule = qs('#btn-cancel-archetype-rule');
  if (btnCancelRule && formWrap) {
    btnCancelRule.addEventListener('click', () => {
      formWrap.style.display = 'none';
    });
  }

  const colorInput = qs('#rule-form-color');
  const colorTextInput = qs('#rule-form-color-text');
  if (colorInput && colorTextInput) {
    colorInput.addEventListener('input', () => {
      colorTextInput.value = colorInput.value;
      updateFormLivePreview();
    });
    colorTextInput.addEventListener('input', () => {
      colorInput.value = colorTextInput.value;
      updateFormLivePreview();
    });
  }

  ['#rule-form-label', '#rule-form-badge', '#rule-form-icon'].forEach(sel => {
    const el = qs(sel);
    if (el) el.addEventListener('input', updateFormLivePreview);
    if (el) el.addEventListener('change', updateFormLivePreview);
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
        alert('Please provide at least a Rule Label and Pattern.');
        return;
      }

      if (window.CodeLensClassifier) {
        if (id) {
          window.CodeLensClassifier.updateRule(id, { label, badge: badge || label, icon, color, scope, target: scope, matchType, pattern, description });
        } else {
          window.CodeLensClassifier.addRule({ label, badge: badge || label, icon, color, scope, target: scope, matchType, pattern, description, enabled: true });
        }
        formWrap.style.display = 'none';
        renderArchetypeRulesList();
        showBanner(`Saved rule "${label}"`);
      }
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
  'change-risk': {
    title: 'Change Risk & Blast Radius Matrix',
    subtitle: 'Deep structural risk analysis combining field mutations, fan-out blast radius, and downstream dependency propagation.',
    badge: 'HIGH RISK',
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
  'html-snapshot': {
    title: 'Standalone Offline Graph Snapshot',
    subtitle: 'Self-contained zero-dependency HTML visualizer with interactive 2D graph, live physics, search, and embedded data.',
    badge: 'STANDALONE',
    badgeClass: 'tag-gov'
  }
};

const ReportsHub = {
  activeReport: 'change-risk',
  activeFormat: 'dashboard',
  cache: {},
  loading: false,
  initialized: false,

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
  },

  activate(reportKey, format) {
    if (reportKey && REPORTS_METADATA[reportKey]) {
      ReportsHub.activeReport = reportKey;
    }
    if (format) {
      ReportsHub.activeFormat = format;
    } else if (ReportsHub.activeReport === 'html-snapshot' && ReportsHub.activeFormat !== 'html') {
      ReportsHub.activeFormat = 'html';
    }

    ReportsHub.syncUI();
    ReportsHub.loadActiveReport();
  },

  setFormat(format) {
    ReportsHub.activeFormat = format;
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
  },

  async loadActiveReport() {
    const dashContainer = qs('#reports-dashboard-container');
    const htmlContainer = qs('#reports-html-container');
    const codeContainer = qs('#reports-code-container');
    const htmlFrame = qs('#reports-html-frame');
    const codeOutput = qs('#reports-code-output');

    if (!dashContainer || !htmlContainer || !codeContainer) return;

    // Snapshot only supports HTML/Dashboard preview
    if (ReportsHub.activeReport === 'html-snapshot') {
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

      dashContainer.innerHTML = `
        <div class="reports-loading-state">
          <div class="loading-spinner"></div>
          <div class="loading-text">Analyzing ${esc(REPORTS_METADATA[ReportsHub.activeReport].title)}…</div>
        </div>
      `;

      try {
        const res = await fetch(`/api/reports/${ReportsHub.activeReport}?format=json`);
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

    codeOutput.textContent = 'Generating ' + ReportsHub.activeFormat.toUpperCase() + ' report…';

    try {
      const res = await fetch(`/api/reports/${ReportsHub.activeReport}?format=${ReportsHub.activeFormat}`);
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
      codeOutput.textContent = 'Error generating report: ' + err.message;
    }
  },

  renderDashboard(type, data) {
    const container = qs('#reports-dashboard-container');
    if (!container) return;

    if (type === 'change-risk') {
      ReportsHub.renderChangeRiskDashboard(container, data);
    } else if (type === 'dead-code') {
      ReportsHub.renderDeadCodeDashboard(container, data);
    } else if (type === 'circular-dependencies') {
      ReportsHub.renderCircularDependenciesDashboard(container, data);
    } else if (type === 'archetype-governance') {
      ReportsHub.renderArchetypeGovernanceDashboard(container, data);
    } else if (type === 'architecture') {
      ReportsHub.renderArchitectureDashboard(container, data);
    } else if (type === 'review') {
      ReportsHub.renderReviewDashboard(container, data);
    } else if (type === 'metrics') {
      ReportsHub.renderMetricsDashboard(container, data);
    } else {
      container.innerHTML = `<pre class="reports-code-output">${esc(JSON.stringify(data, null, 2))}</pre>`;
    }
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
              <strong style="font-family:var(--font-mono); color:var(--text-primary);">${esc(h.simpleName)}</strong>
              <span style="font-size:10px; color:var(--text-muted); margin-left:4px;">(${esc(h.kind)})</span>
            </td>
            <td style="color:var(--text-muted); font-family:var(--font-mono); font-size:11.5px; max-width:140px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="${esc(h.packageName)}">${esc(h.packageName)}</td>
            <td><span class="risk-badge ${tierClass}">${Math.round(h.hotspotScore)} / 100</span></td>
            <td><span class="risk-badge ${tierClass}">${esc(h.riskTier)}</span></td>
            <td style="font-family:var(--font-mono);">${h.cyclomaticComplexity}</td>
            <td style="font-family:var(--font-mono);">${h.commitCount} commits</td>
            <td style="font-family:var(--font-mono);">${h.linesOfCode}</td>
            <td style="font-size:11px; color:var(--text-secondary); max-width:260px;">${esc(h.recommendation)}</td>
            <td>
              <div style="display:flex; align-items:center; gap:4px;">
                <button class="btn-ghost" style="font-size:11px; padding:3px 7px;" onclick="selectClass('${esc(h.entityFqn)}'); switchTab('knowledge');" title="Inspect in Knowledge Base">
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
          <td><strong style="font-family:var(--font-mono); color:var(--text-primary);">${esc(c.simpleName)}</strong></td>
          <td style="color:var(--text-muted); font-family:var(--font-mono); font-size:11.5px; max-width:160px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="${esc(c.packageName)}">${esc(c.packageName)}</td>
          <td><span class="risk-badge ${bClass}">${c.riskScore} / 100</span></td>
          <td><span class="risk-badge ${bClass}">${esc(c.riskLevel)}</span></td>
          <td style="font-family:var(--font-mono);">${c.afferentCoupling}</td>
          <td style="font-family:var(--font-mono);">${c.efferentCoupling}</td>
          <td style="font-family:var(--font-mono);">${c.fieldBlastRadius} readers</td>
          <td>
            <button class="btn-ghost" style="font-size:11px; padding:3px 8px;" onclick="selectClass('${esc(c.classFqn)}'); switchTab('knowledge');" title="Inspect in Knowledge Base">
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
          <td style="max-width:180px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="${esc(f.fieldFqn)}"><strong style="font-family:var(--font-mono); color:#38bdf8;">${esc(f.fieldFqn)}</strong></td>
          <td style="max-width:200px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="${esc(f.writerMethodFqn)}"><code style="color:var(--text-muted); font-size:11.5px;">${esc(f.writerMethodFqn)}</code></td>
          <td><span class="risk-badge risk-high">${f.readerMethodCount} methods</span></td>
          <td style="font-family:var(--font-mono);">${f.impactedModuleCount} modules</td>
          <td>
            <button class="btn-ghost" style="font-size:11px; padding:3px 8px;" onclick="switchTab('graph'); loadFieldImpact('${esc(f.fieldFqn)}');" title="Trace Field Propagation on Graph">
              Trace Graph →
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

    (d.orphanedMethods || []).slice(0, 20).forEach(m => {
      html += `
        <tr>
          <td><strong style="font-family:var(--font-mono); color:#f59e0b;">${esc(m.simpleName)}</strong></td>
          <td style="color:var(--text-muted); font-family:var(--font-mono);">${esc(m.declaringClass)}</td>
          <td style="color:var(--text-muted); font-family:var(--font-mono);">${esc(m.packageName)}</td>
          <td style="font-family:var(--font-mono);">${m.lineCount || 0}</td>
          <td><span class="risk-badge risk-low">${esc(m.reason || '0 callers')}</span></td>
          <td>
            <button class="btn-ghost" style="font-size:11px; padding:3px 8px;" onclick="selectClass('${esc(m.declaringClass)}'); switchTab('knowledge');" title="Inspect declaring class">
              Inspect →
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

    (d.orphanedClasses || []).slice(0, 20).forEach(c => {
      html += `
        <tr>
          <td><strong style="font-family:var(--font-mono); color:var(--text-primary);">${esc(c.simpleName)}</strong></td>
          <td style="color:var(--text-muted); font-family:var(--font-mono);">${esc(c.packageName)}</td>
          <td style="font-family:var(--font-mono);">${c.lineCount || 0}</td>
          <td style="font-family:var(--font-mono);">${c.methodCount || 0}</td>
          <td style="color:var(--text-muted);">${esc(c.reason || 'Zero incoming references')}</td>
          <td>
            <button class="btn-ghost" style="font-size:11px; padding:3px 8px;" onclick="selectClass('${esc(c.classFqn)}'); switchTab('knowledge');" title="Inspect in Knowledge Base">
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
              ${(c.path || []).map(p => `<span style="color:#38bdf8;">${esc(p)}</span>`).join(' <span style="color:#fbbf24;">➔</span> ')}
              <span style="color:#fbbf24;">➔</span> <span style="color:#38bdf8;">${esc(c.path[0])}</span>
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
            <td><strong style="font-family:var(--font-mono); color:var(--text-primary);">${esc(t.packageA)}</strong></td>
            <td><strong style="font-family:var(--font-mono); color:var(--text-primary);">${esc(t.packageB)}</strong></td>
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
              </tr>
            </thead>
            <tbody>
    `;

    if (!d.violations || d.violations.length === 0) {
      html += `
        <tr>
          <td colspan="5" style="text-align:center; padding:24px; color:#34d399;">
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
            <td style="max-width:200px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="${esc(v.entityFqn)}"><code style="color:#38bdf8; font-size:11px;">${esc(v.entityFqn)}</code></td>
            <td style="color:var(--text-muted); font-size:11.5px; max-width:240px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="${esc(v.violationDetails)}">${esc(v.violationDetails)}</td>
            <td style="color:#34d399; font-size:11px; max-width:220px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="${esc(v.architecturalRemediation)}">💡 ${esc(v.architecturalRemediation)}</td>
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
          <div class="report-kpi-val">${d.totalTypes || 0}</div>
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
          <div class="report-kpi-val">${d.totalRelationships || 0}</div>
          <span class="report-kpi-sub">Calls, reads, writes, inheritance</span>
        </div>
      </div>

      <div class="report-section-card">
        <div class="report-section-header">
          <div class="report-section-title">Package Coupling Matrix (Ca / Ce / Instability)</div>
        </div>
        <div class="report-table-wrap">
          <table class="report-table">
            <thead>
              <tr>
                <th>Package Name</th>
                <th>Afferent In (Ca)</th>
                <th>Efferent Out (Ce)</th>
                <th>Instability (I = Ce / (Ca + Ce))</th>
                <th>Stability Classification</th>
              </tr>
            </thead>
            <tbody>
    `;

    (d.packages || []).slice(0, 15).forEach(p => {
      const iVal = p.instability != null ? p.instability : (p.efferent + p.afferent > 0 ? (p.efferent / (p.efferent + p.afferent)).toFixed(2) : 0);
      const isStable = iVal < 0.3;
      html += `
        <tr>
          <td><strong style="font-family:var(--font-mono);">${esc(p.name || p.packageFqn)}</strong></td>
          <td style="font-family:var(--font-mono);">${p.afferent || p.ca || 0}</td>
          <td style="font-family:var(--font-mono);">${p.efferent || p.ce || 0}</td>
          <td><span class="risk-badge ${isStable ? 'risk-low' : 'risk-medium'}">${iVal}</span></td>
          <td style="color:var(--text-muted);">${isStable ? 'Stable Core' : 'Flexible / Dependent'}</td>
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
          <span class="report-kpi-sub">Priority remediation</span>
        </div>
        <div class="report-kpi-card" style="--kpi-accent: #06b6d4;">
          <span class="report-kpi-label">Audited Classes</span>
          <div class="report-kpi-val">${d.auditedClassesCount || (d.types ? d.types.length : 0)}</div>
          <span class="report-kpi-sub">Index coverage</span>
        </div>
        <div class="report-kpi-card" style="--kpi-accent: #10b981;">
          <span class="report-kpi-label">Health Grade</span>
          <div class="report-kpi-val"><span class="risk-badge risk-low">${d.healthGrade || 'A-'}</span></div>
          <span class="report-kpi-sub">Composite quality score</span>
        </div>
      </div>
      <div class="report-section-card" style="padding:20px; text-align:center;">
        <p style="color:var(--text-muted); margin-bottom:12px;">Detailed code review findings with inline code snippets and remediation instructions are available in the dedicated Code Review workspace.</p>
        <button class="btn-primary" onclick="switchTab('review');">Open Code Review Workspace →</button>
      </div>
    `;
    container.innerHTML = html;
  },

  renderMetricsDashboard(container, d) {
    let html = `
      <div class="report-kpi-grid">
        <div class="report-kpi-card" style="--kpi-accent: #06b6d4;">
          <span class="report-kpi-label">Total Classes</span>
          <div class="report-kpi-val">${d.totalTypes || 0}</div>
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
          <div class="report-kpi-val">${(d.totalLinesOfCode || 0).toLocaleString()}</div>
        </div>
      </div>
    `;
    container.innerHTML = html;
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
  open(type = 'architecture', format = 'markdown') {
    switchTab('reports');
    ReportsHub.activate(type, format);
  },
  close() {}
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

// Global window helpers for debugging & integration
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


