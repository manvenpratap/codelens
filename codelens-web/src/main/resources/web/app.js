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

  get:    (path)         => api.request(path),
  post:   (path, body)   => api.request(path, { method: 'POST',   body: JSON.stringify(body) }),
  delete: (path)         => api.request(path, { method: 'DELETE' }),

  // ── Convenience wrappers ────────────────────────────────────────────────────
  stats:              ()          => api.get('/stats'),
  packages:           ()          => api.get('/packages'),
  typesByPackage:     (fqn)       => api.get(`/packages/${enc(fqn)}/types`),
  type:               (id)        => api.get(`/types/${enc(id)}`),
  method:             (id)        => api.get(`/methods/${enc(id)}`),
  callers:            (id, d=4)   => api.get(`/methods/${enc(id)}/callers?depth=${d}`),
  callees:            (id, d=4)   => api.get(`/methods/${enc(id)}/callees?depth=${d}`),
  callGraph:          (id, d=3)   => api.get(`/methods/${enc(id)}/graph?depth=${d}`),
  fullGraph:          async () => {
    const key = 'graph:full';
    if (GraphDataCache.has(key)) return GraphDataCache.get(key);
    const data = await api.get('/graph/all');
    GraphDataCache.set(key, data);
    return data;
  },
  architectureGraph:  async (scope, filter) => {
    const key = `graph:arch:${scope || ''}:${filter || ''}`;
    if (GraphDataCache.has(key)) return GraphDataCache.get(key);
    const data = await api.get(`/graph/architecture${scope || filter ? '?' + new URLSearchParams({ ...(scope ? { scope } : {}), ...(filter ? { filter } : {}) }) : ''}`);
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
  field:              (id)        => api.get(`/fields/${enc(id)}`),
  fieldImpact:        (id, d=1)   => api.get(`/fields/${enc(id)}/impact?depth=${d}`),
  review:             (body)      => api.post('/review', body),
  search:             (q, n=30)   => api.get(`/search?q=${encodeURIComponent(q)}&limit=${n}`),
  scanStatus:         ()          => api.get('/scan/status'),
  startScan:          (sourcePath, excludePatterns) => api.post('/scan', { sourcePath, excludePatterns }),
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
};

/** URL-encode an entity FQN for path segments. */
function enc(fqn) {
  return encodeURIComponent(fqn || '');
}

/* ─────────────────────────────────────────────────────────────────────────────
   3. Scan workflow
   ───────────────────────────────────────────────────────────────────────────── */

/** Start a scan with the path currently in the input box. */
async function startScan() {
  const path = qs('#scan-path-input').value.trim();
  if (!path) {
    flashInput(qs('#scan-path-input'));
    return;
  }

  const settings = loadSettings();
  const excludePatterns = settings.excludePatterns || 'target, build, .mvn, .git, .gradle, node_modules, bin, out';

  setScanUI('scanning');
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
        showError('Scan error: ' + (s.errorDetail || s.message));
      }
    } catch (e) {
      console.warn('Poll error:', e);
    }
  }, 350);
}

/** Update the progress bar and status text during an active scan. */
function updateScanProgress(s) {
  const pct = s.percentage || 0;
  qs('#scan-progress-bar').style.width = pct + '%';

  const phaseBadge = qs('.scan-phase-badge');
  if (phaseBadge) {
    phaseBadge.textContent = s.currentPhase || (pct < 100 ? 'Scanning' : 'Finishing');
  }

  const statusText = qs('.scan-status-text');
  if (statusText) {
    statusText.textContent = s.message || 'Scanning codebase…';
  }

  const detailText = qs('.scan-detail-text');
  if (detailText) {
    detailText.textContent = s.currentDetail || (s.totalFiles ? `${s.processedFiles || 0} of ${s.totalFiles} files` : 'Processing...');
  }

  qs('.scan-pct').textContent = pct + '%';
  qs('#scan-status-bar').classList.add('visible');
  
  // Footer update
  const fText = qs('#footer-status-text');
  const fInd = qs('.status-indicator');
  if (fText) {
    fText.textContent = `[${s.currentPhase || 'SCAN'}] ${s.message || ''} (${pct}%)`;
  }
  if (fInd) { fInd.className = 'status-indicator busy'; }
}

/** Called when scan finishes successfully. */
async function onScanComplete(s) {
  setScanUI('idle');
  qs('#scan-status-bar').classList.remove('visible');
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

  // Refresh stats and tree
  await loadStats();
  await loadPackageTree();

  // Footer update
  const fText = qs('#footer-status-text');
  const fInd = qs('.status-indicator');
  if (fText) fText.textContent = 'Analyzer Idle · Scan complete';
  if (fInd) { fInd.className = 'status-indicator live'; }

  // Check git branch
  updateFooterGitBranch();

  showBanner(`Scan complete - ${s.typesFound} types · ${s.methodsFound} methods · ${s.fieldsFound} fields`);
}

/** Toggle scan button and spinner states. */
function setScanUI(state) {
  const btn = qs('#scan-btn');
  const fText = qs('#footer-status-text');
  const fInd = qs('.status-indicator');
  if (state === 'scanning') {
    btn.disabled      = true;
    btn.textContent   = 'Scanning…';
    if (fText) fText.textContent = 'Scanning codebase…';
    if (fInd) { fInd.className = 'status-indicator busy'; }
  } else {
    btn.disabled      = false;
    btn.textContent   = 'Scan';
    if (fText && state === 'idle') {
      fText.textContent = 'Analyzer Idle';
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
function selectPackage(pkg, itemEl) {
  setActiveTreeItem(itemEl);
  App.selected = { kind: 'package', id: pkg.fqn, data: pkg };

  // Show the knowledge-base tab with type list
  switchTab('knowledge');
  loadKnowledgeBase(pkg.fqn);

  // Show package info in right panel
  renderPackageDetail(pkg);
}

/* ── Search ─────────────────────────────────────────────────────────────────── */

/** Debounced search - triggers Lucene search after 280 ms of idle. */
let searchDebounce = null;
function onSearchInput(e) {
  const q = e.target.value.trim();

  clearTimeout(searchDebounce);
  if (!q) {
    showExplorer();
    return;
  }

  searchDebounce = setTimeout(() => runSearch(q), 280);
}

async function runSearch(q) {
  showSearchResults();
  const resultsEl = qs('#search-results');
  resultsEl.innerHTML = '<div class="list-empty">Searching…</div>';

  try {
    const hits = await api.search(q);
    resultsEl.innerHTML = '';

    if (hits.length === 0) {
      resultsEl.innerHTML = '<div class="list-empty">No results found.</div>';
      return;
    }

    for (const hit of hits) {
      const item = createElement('div', { class: 'search-result-item fade-in' });
      item.innerHTML = `
        <div>
          <span class="sr-kind ${hit.kind}">${hit.kind}</span>
          <span class="sr-label">${esc(hit.label)}</span>
        </div>
        <div class="sr-fqn">${esc(hit.fqn)}</div>`;

      item.addEventListener('click', () => {
        qs('#search-input').value = '';
        showExplorer();
        if      (hit.kind === 'TYPE')   selectType(hit.id);
        else if (hit.kind === 'METHOD') selectMethod(hit.id);
        else if (hit.kind === 'FIELD')  selectField(hit.id);
      });

      resultsEl.appendChild(item);
    }
  } catch (e) {
    resultsEl.innerHTML = `<div class="list-empty">Error: ${e.message}</div>`;
  }
}

function showExplorer()     { qs('#explorer-tree').style.display = ''; qs('#search-results').style.display = 'none'; }
function showSearchResults() { qs('#explorer-tree').style.display = 'none'; qs('#search-results').style.display = ''; }

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
  switchTab('codebase');
  if (level) {
    loadWholeCodebaseGraph(level, granularity);
  }
  requestAnimationFrame(() => triggerRelayout());
}

/** Close Macro Visualizer Studio and return to previous granular workspace tab. */
function closeMacroStudio() {
  document.body.classList.remove('macro-studio-mode');
  const targetTab = App.lastGranularTab || 'graph';
  switchTab(targetTab);
  requestAnimationFrame(() => triggerRelayout());
}

/** Switch the active tab in the centre panel. */
function switchTab(tabName) {
  const previousTab = App.activeTab;
  App.activeTab = tabName;

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
}

/* ─────────────────────────────────────────────────────────────────────────────
   Workspace Tabs - Drag & Drop Customization & Dynamic Shortcut Synchronization
   ───────────────────────────────────────────────────────────────────────────── */

function restoreTabOrder() {
  const tabBar = qs('.tab-bar');
  if (!tabBar) return;
  const spacer = tabBar.querySelector('.tab-bar-spacer');
  
  let savedOrder = null;
  try {
    const raw = localStorage.getItem('codelens_tab_order');
    if (raw) savedOrder = JSON.parse(raw);
  } catch (_) {}

  if (Array.isArray(savedOrder) && savedOrder.length > 0) {
    const tabMap = new Map();
    tabBar.querySelectorAll('.tab').forEach(t => {
      if (t.dataset.tab) tabMap.set(t.dataset.tab, t);
    });

    savedOrder.forEach(tabName => {
      const tabEl = tabMap.get(tabName);
      if (tabEl && spacer) {
        tabBar.insertBefore(tabEl, spacer);
      }
    });
  }
  updateTabTooltipsAndShortcuts();
}

function saveTabOrder() {
  const tabBar = qs('.tab-bar');
  if (!tabBar) return;
  const order = [...tabBar.querySelectorAll('.tab')].map(t => t.dataset.tab).filter(Boolean);
  try {
    localStorage.setItem('codelens_tab_order', JSON.stringify(order));
  } catch (_) {}
  updateTabTooltipsAndShortcuts();
}

function updateTabTooltipsAndShortcuts() {
  const tabBar = qs('.tab-bar');
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
  const tabBar = qs('.tab-bar');
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

      if (isLeft) {
        tabBar.insertBefore(draggedTab, tab);
      } else {
        tabBar.insertBefore(draggedTab, tab.nextSibling);
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

/** Load and render all types for a given package in the KB tab. */
async function loadKnowledgeBase(pkgFqn) {
  const view = qs('#knowledge-view');
  if (!view) return;
  view.innerHTML = '';

  try {
    const types = await api.typesByPackage(pkgFqn);
    const activeKind = (App.activeFilter || 'all').toUpperCase();
    const filteredTypes = types.filter(t => {
      if (activeKind === 'ALL') return true;
      return (t.kind || '').toUpperCase() === activeKind;
    });

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
        <div class="kb-meta-item"><span class="kb-meta-label">Total Types:</span> <span class="kb-meta-val">${types.length}</span></div>
        <div class="kb-meta-divider"></div>
        <div class="kb-meta-item"><span class="kb-meta-label">Showing:</span> <span class="kb-meta-val">${filteredTypes.length}</span></div>
        <div class="kb-meta-divider"></div>
        <div class="kb-meta-item"><span class="kb-meta-label">Filter:</span> <span class="kb-meta-val">${activeKind}</span></div>
      </div>
    `;
    hero.querySelector('#kb-pkg-graph')?.addEventListener('click', () => {
      switchTab('graph');
    });
    view.appendChild(hero);

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
      view.appendChild(empty);
      return;
    }

    const typesSection = createElement('div', { class: 'kb-section fade-in' });
    typesSection.innerHTML = `
      <div class="kb-section-title">
        <div class="kb-section-title-left">
          <svg class="svg-icon icon-emerald icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M3 9h18"/><path d="M9 21V9"/></svg>
          <span>Declared Types</span>
        </div>
        <span class="kb-section-badge">${filteredTypes.length}</span>
      </div>
      <div class="kb-list" id="kb-types-list"></div>
    `;
    const typesList = typesSection.querySelector('#kb-types-list');

    for (const t of filteredTypes) {
      const tKind = (t.kind || 'CLASS').toUpperCase();
      const tKindClass = `kind-${tKind.toLowerCase()}`;
      const row = createElement('div', { class: 'kb-row' });
      row.innerHTML = `
        <div class="kb-row-left">
          <div class="kb-row-icon icon-type" title="${esc(tKind)}">
            <svg class="svg-icon icon-emerald icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="12 2 2 7 12 12 22 7 12 2"/><polyline points="2 17 12 22 22 17"/><polyline points="2 12 12 17 22 12"/></svg>
          </div>
          <div class="kb-row-info">
            <div class="kb-row-name-wrap">
              <span class="kb-row-name">${esc(t.simpleName)}</span>
              <span class="kb-kind-badge ${tKindClass}">${esc(tKind)}</span>
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
      row.addEventListener('click', () => selectType(t.id));
      typesList.appendChild(row);
    }
    view.appendChild(typesSection);
  } catch (e) {
    const errorCard = createElement('div', { class: 'kb-empty-container fade-in' });
    errorCard.innerHTML = `
      <div class="kb-empty-title" style="color:var(--red)">Failed to load package</div>
      <div class="kb-empty-desc">${esc(e.message)}</div>
    `;
    view.appendChild(errorCard);
  }
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
    switchTab('knowledge');
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
    switchTab('graph');
    await loadCallGraph(id);
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
  const granCtrl = qs('#codebase-granularity-selector');
  const granDiv = qs('#codebase-granularity-divider');
  const supportsGranularity = ['city3d', 'galaxy3d', 'graph2d', 'dsm', 'chord'].includes(effectiveLevel);
  if (granCtrl) granCtrl.style.display = supportsGranularity ? 'flex' : 'none';
  if (granDiv) granDiv.style.display = supportsGranularity ? '' : 'none';

  // Toggle POJO filter button in Codebase HUD (visible for 2D Graph, 3D City, and 3D Galaxy when scope is methods)
  const pojoCtrl = qs('#codebase-pojo-controls');
  const pojoDiv = qs('#codebase-pojo-divider');
  const showPojoFilter = (['graph2d', 'city3d', 'galaxy3d'].includes(effectiveLevel) && isMethods);
  if (pojoCtrl) pojoCtrl.style.display = showPojoFilter ? 'block' : 'none';
  if (pojoDiv) pojoDiv.style.display = showPojoFilter ? '' : 'none';

  // Toggle Call Arcs filter button in Codebase HUD (visible for 3D modes: 3D City & 3D Galaxy)
  const is3D = (effectiveLevel === 'city3d' || effectiveLevel === 'galaxy3d');
  const arcsCtrl = qs('#codebase-arcs-controls');
  const arcsDiv = qs('#codebase-arcs-divider');
  if (arcsCtrl) arcsCtrl.style.display = is3D ? 'block' : 'none';
  if (arcsDiv) arcsDiv.style.display = is3D ? '' : 'none';

  // Toggle brightness slider visibility (only for 3D modes)
  const brightnessCtrl = qs('#codebase-brightness-controls');
  const brightnessDiv = qs('#codebase-brightness-divider');
  if (brightnessCtrl) brightnessCtrl.style.display = is3D ? 'flex' : 'none';
  if (brightnessDiv) brightnessDiv.style.display = is3D ? '' : 'none';

  // Toggle visibility of bottom canvas toolbar
  const canvasToolbar = qs('#codebase-canvas-toolbar');
  const hasBottomControls = (supportsGranularity || showPojoFilter || is3D);
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

  // Destroy any previous alternate renderer
  if (App.activeAltRenderer) {
    App.activeAltRenderer.destroy();
    App.activeAltRenderer = null;
  }

  const mountContainer = isAltViz ? qs('#codebase-canvas-wrap') : qs('#graph-view');

  if (isAltViz) {
    hideCodebaseEmpty();
    try {
      if (effectiveLevel === 'city3d') {
        showBanner(isMethods ? 'Building 3D Software City (Methods)...' : 'Building 3D Software City (Classes)...');
        const [graphData, treeData] = await Promise.all([
          isMethods ? api.fullGraph() : api.architectureGraph(),
          api.treemapData()
        ]);
        if (!graphData.nodes || graphData.nodes.length === 0) {
          showCodebaseEmpty('No graph data available for 3D City. Run a scan first.');
          return;
        }
        const renderer = new window.CodeCity3DRenderer(mountContainer);
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
        const data = isMethods ? await api.fullGraph() : await api.architectureGraph();
        if (!data.nodes || data.nodes.length === 0) {
          showCodebaseEmpty('No graph data available for 3D Galaxy. Run a scan first.');
          return;
        }
        const renderer = new window.Galaxy3DRenderer(mountContainer);
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
        const data = isMethods ? await api.fullGraph() : await api.architectureGraph();
        if (!data.nodes || data.nodes.length === 0) {
          showCodebaseEmpty('No graph data available for 2D Blooming Tree. Run a scan first.');
          return;
        }

        if (cbMinimap) cbMinimap.style.display = (!App.settings || App.settings.showMinimap !== false) ? '' : 'none';

        const tooltip = qs('#codebase-tooltip') || qs('#graph-tooltip');
        const fg = new window.ForceGraph(mountContainer, tooltip);
        if (App.settings) fg.applySettings(App.settings);
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
        renderer.onScopeChange(async (newScope) => {
          try {
            showBanner(`Loading DSM (${newScope})...`);
            App.codebaseGranularity = (newScope === 'methods' ? 'methods' : 'arch');
            qsa('#codebase-granularity-selector .level-pill').forEach(btn => btn.classList.toggle('active', btn.dataset.granularity === (newScope === 'methods' ? 'methods' : 'arch')));
            const scopedData = await api.dsmData(newScope);
            renderer.setData(scopedData);
            renderAltVizInspector('DSM', scopedData.classes.length, 0);
            renderCodebaseLegend(scopedData.classes.map(c => ({ id: c, package: c.split('.').slice(0, -1).join('.') || 'default' })));
            showBanner(`DSM loaded: ${scopedData.classes.length} ${newScope}`);
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
        const data = isMethods ? await api.fullGraph() : await api.architectureGraph();
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

      const view = isArch ? await api.architectureGraph() : await api.fullGraph();

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
      if (!pkg && n.id && n.id.includes('.')) {
        const parts = n.id.replace(/\(.*\)/, '').split('.');
        const isType = (n.type === 'CLASS' || n.type === 'TYPE' || parts.length <= 2);
        pkg = isType ? (parts.slice(0, -1).join('.') || 'default') : (parts.slice(0, -2).join('.') || 'default');
        if (!cls) cls = isType ? parts[parts.length - 1] : parts[parts.length - 2];
      }
      pkg = pkg || 'default';
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
    const color = (window.CodeLensPalette && window.CodeLensPalette.getColor)
      ? window.CodeLensPalette.getColor(pkg, idx)
      : '#3b82f6';
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
  if (!pkg || pkg === 'default' || pkg === '(default)') return 'Core';
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

/** 
 * Automatically format package FQN into clean module name without manual prefix configuration.
 * Fully supports uppercase and PascalCase package segments (e.g. com.tcs.bancs.ModuleName).
 */
function formatModuleFromPackage(pkg) {
  if (!pkg || pkg === 'default' || pkg === '(default)') return 'Core';
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

  if (!res || res === 'default') return 'Core';
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

  // Header
  renderEntityHeader(type.kind, type.simpleName, type.fqn);

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
    { label: 'View All Methods', action: () => { switchTab('knowledge'); renderKnowledgeBaseForType(data); } },
  ]));

  // Notes
  renderNotes(type.fqn, notes);
}

function renderKnowledgeBaseForType(data) {
  const { type, fields = [], methods = [], notes = [] } = data;
  const view = qs('#knowledge-view');
  if (!view) return;
  view.innerHTML = '';

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
    switchTab('graph');
    if (type.id) selectType(type.id);
  });
  hero.querySelector('#kb-btn-source')?.addEventListener('click', () => {
    if (type.sourceFile) openSourceFile(type.sourceFile, type.startLine || 1);
  });
  hero.querySelector('#kb-btn-review')?.addEventListener('click', () => {
    switchTab('review');
    updateReviewTargetInfo();
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

  // ── Members Grid (2-column on desktop) ──────────────────────────────────────
  if (fields.length > 0 || methods.length > 0) {
    const isSingleCol = (fields.length === 0 || methods.length === 0);
    const membersGrid = createElement('div', { class: `kb-members-grid ${isSingleCol ? 'single-col' : ''}` });

    // ── Fields / Record Components Section ────────────────────────────────────
    if (fields.length > 0) {
      const fieldsSection = createElement('div', { class: 'kb-section fade-in' });
      fieldsSection.innerHTML = `
        <div class="kb-section-title">
          <div class="kb-section-title-left">
            <svg class="svg-icon icon-cyan icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2"/><line x1="3" y1="9" x2="21" y2="9"/><line x1="9" y1="21" x2="9" y2="9"/></svg>
            <span>${isRecord ? 'Record Components' : 'Fields'}</span>
          </div>
          <span class="kb-section-badge">${fields.length}</span>
        </div>
        <div class="kb-list" id="kb-fields-list"></div>
      `;
      const fieldsList = fieldsSection.querySelector('#kb-fields-list');

      for (const f of fields) {
        const row = createElement('div', { class: 'kb-row' });
        row.innerHTML = `
          <div class="kb-row-left">
            <div class="kb-row-icon icon-field" title="Field">
              <svg class="svg-icon icon-cyan icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="4" y="4" width="16" height="16" rx="2"/><circle cx="9" cy="9" r="2"/></svg>
            </div>
            <div class="kb-row-info">
              <div class="kb-row-name-wrap">
                <span class="kb-row-name">${esc(f.simpleName)}</span>
                ${f.modifiers ? `<span class="kb-mod-pill">${esc(f.modifiers)}</span>` : ''}
              </div>
              <div class="kb-row-meta">
                <span>Type: <strong style="color:var(--cyan-bright)">${esc(f.fieldType || 'Object')}</strong></span>
                ${f.startLine ? `<span>· Line ${f.startLine}</span>` : ''}
              </div>
            </div>
          </div>
          <div class="kb-row-right">
            <span class="kb-type-pill" title="${esc(f.fieldType || '')}">${esc(f.fieldType || 'Object')}</span>
          </div>
        `;
        row.addEventListener('click', () => selectField(f.id || f.fqn));
        fieldsList.appendChild(row);
      }
      membersGrid.appendChild(fieldsSection);
    }

    // ── Methods & Constructors Section ────────────────────────────────────────
    if (methods.length > 0) {
      const methodsSection = createElement('div', { class: 'kb-section fade-in' });
      methodsSection.innerHTML = `
        <div class="kb-section-title">
          <div class="kb-section-title-left">
            <svg class="svg-icon icon-indigo icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>
            <span>${isRecord ? 'Methods & Accessors' : 'Methods & Constructors'}</span>
          </div>
          <span class="kb-section-badge">${methods.length}</span>
        </div>
        <div class="kb-list" id="kb-methods-list"></div>
      `;
      const methodsList = methodsSection.querySelector('#kb-methods-list');

      for (const m of methods) {
        const isConstructor = m.simpleName === '<init>' || m.simpleName === type.simpleName;
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

        const row = createElement('div', { class: 'kb-row' });
        row.innerHTML = `
          <div class="kb-row-left">
            <div class="kb-row-icon icon-method" title="${isConstructor ? 'Constructor' : 'Method'}">
              <svg class="svg-icon icon-indigo icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="m18 16 4-4-4-4"/><path d="m6 8-4 4 4 4"/><path d="m14.5 4-5 16"/></svg>
            </div>
            <div class="kb-row-info">
              <div class="kb-row-name-wrap">
                <span class="kb-row-name">${esc(displayName)}</span>
                ${isConstructor ? '<span class="kb-mod-pill" style="color:var(--amber);background:rgba(245,158,11,0.1)">constructor</span>' : ''}
                ${m.modifiers ? `<span class="kb-mod-pill">${esc(m.modifiers)}</span>` : ''}
              </div>
              <div class="kb-row-meta">
                <span>${paramsFormatted}</span>
                ${m.startLine ? `<span>· Line ${m.startLine}</span>` : ''}
              </div>
            </div>
          </div>
          <div class="kb-row-right">
            <span class="kb-cc-pill ${ccTier}" title="Cyclomatic Complexity: ${cc}">CC: ${cc} (${ccLabel})</span>
            <span class="kb-type-pill" title="Return type">${esc(m.returnType || (isConstructor ? 'void' : 'void'))}</span>
          </div>
        `;
        row.addEventListener('click', () => selectMethod(m.id || m.fqn));
        methodsList.appendChild(row);
      }
      membersGrid.appendChild(methodsSection);
    }

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

  // Call graph action buttons
  body.appendChild(actionRow([
    { label: '⬆ Callers', title: 'Trace all upstream methods that call this method (BFS)', action: () => { switchTab('graph'); loadCallersGraph(method.id); } },
    { label: '⬇ Callees', title: 'Trace all downstream methods invoked by this method (BFS)', action: () => { switchTab('graph'); loadCalleesGraph(method.id); } },
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

    // Animate counters to new values
    animateCounter(qs('#stat-types'),   s.types   || 0);
    animateCounter(qs('#stat-methods'), s.methods || 0);
    animateCounter(qs('#stat-fields'),  s.fields  || 0);

    if (s.types > 0) {
      updateHeaderProjectBar();
    }
  } catch (e) {
    console.warn('Stats load failed:', e);
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

/** Reveal the scan input bar to switch or open a different project */
function showHeaderScanBar() {
  const projectBar = qs('#header-project-bar');
  const scanBar = qs('#header-scan-bar');
  if (projectBar) projectBar.style.display = 'none';
  if (scanBar) scanBar.style.display = 'flex';
  const input = qs('#scan-path-input');
  if (input) {
    input.focus();
    input.select();
  }
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
    // Escape → close modal or exit studio mode or clear search
    if (e.key === 'Escape') {
      if (document.body.classList.contains('macro-studio-mode')) {
        closeMacroStudio();
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
        const tabs = [...qsa('.tab-bar .tab')];
        const idx = parseInt(e.key, 10) - 1;
        if (tabs[idx] && tabs[idx].dataset.tab) {
          switchTab(tabs[idx].dataset.tab);
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

  // Wire up scan button and Enter key
  qs('#scan-btn')?.addEventListener('click', startScan);

  // Wire up header project bar controls
  qs('#btn-rescan')?.addEventListener('click', startScan);
  qs('#btn-open-project')?.addEventListener('click', showHeaderScanBar);
  qs('#project-pill')?.addEventListener('click', showHeaderScanBar);
  qs('#scan-cancel-btn')?.addEventListener('click', () => {
    if (App.stats && App.stats.types > 0) {
      updateHeaderProjectBar();
    }
  });

  // Wire up quick theme toggle in header toolbar
  qs('#theme-toggle-btn')?.addEventListener('click', () => {
    const s = loadSettings();
    const isCurrentlyLight = s.theme === 'light' || document.body.classList.contains('theme-light');
    const newTheme = isCurrentlyLight ? 'dark' : 'light';
    s.theme = newTheme;
    saveSettings(s);
    applyAllSettings(s);
    syncSettingsUI(s);
    showBanner(`Theme switched to ${newTheme === 'light' ? 'Light (Pure Daylight)' : 'Dark (Midnight Obsidian)'}`);
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

  // Search input
  qs('#search-input').addEventListener('input', onSearchInput);

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

  // Eagerly initialize graph canvas instance
  ensureGraph();

  // Check if server already has data (e.g. re-open after prior scan)
  try {
    const status = await api.scanStatus();
    if (status.status === 'SCANNING') {
      setScanUI('scanning');
      if (status.sourcePath) qs('#scan-path-input').value = status.sourcePath;
      pollScanStatus();
    }
  } catch (_) { /* first run */ }

  await loadStats();
  await loadPackageTree();

  // Initialize adjustable panel resizers
  initPanelResizers();

  // Initialize settings & themes
  initSettings();

  // Initialize report export hub
  initExportHub();

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
      if (App.activeAltRenderer && typeof App.activeAltRenderer.toggleHideGetters === 'function') {
        App.activeAltRenderer.toggleHideGetters();
      } else if (App.graph && typeof App.graph.toggleHideGetters === 'function') {
        App.graph.toggleHideGetters();
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

  // Expose global handles for testing and automation
  window.App = App;
  window.selectMethod = selectMethod;
  window.loadWholeCodebaseGraph = loadWholeCodebaseGraph;
  window.api = api;
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
/** Load heat data (entityFqn -> commitCount) and register it with the graph. */
async function loadGitHeatData() {
  try {
    const summary = await api.gitSummary();
    if (!summary.hotEntities) return;
    const heatMap = {};
    for (const e of summary.hotEntities) {
      heatMap[e.entityFqn] = e.commitCount;
    }
    App.graph?.setHeatData(heatMap);
    return heatMap;
  } catch (_) { /* non-fatal */ }
}
window.loadGitHeatData = loadGitHeatData;

function esc(str) {
  return String(str || '').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}

document.addEventListener('DOMContentLoaded', init);

/* ─────────────────────────────────────────────────────────────────────────────
   UI helpers - shared rendering primitives
   ───────────────────────────────────────────────────────────────────────────── */

/** Build the entity header in the right panel. */
function renderEntityHeader(kind, name, fqn) {
  const header = qs('#entity-header');
  if (!header) return;

  let archBadge = '';
  if (window.CodeLensClassifier) {
    const isMethod = (kind === 'METHOD');
    const arch = isMethod
      ? window.CodeLensClassifier.classifyMethod(name, fqn)
      : window.CodeLensClassifier.classifyType(name, fqn);
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
    btn.textContent = a.label;
    if (a.title) btn.title = a.title;
    btn.addEventListener('click', a.action);
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

  // Toggle light mode class
  const isLight = themeKey === 'light';
  document.body.classList.toggle('theme-light', isLight);
  document.body.dataset.theme = themeKey;

  // Update top-bar theme toggle button
  const toggleIcon = qs('#theme-toggle-icon');
  const toggleLabel = qs('#theme-toggle-label');
  if (toggleIcon) {
    if (window.Icons) {
      toggleIcon.innerHTML = isLight ? window.Icons.get('moon', { color: 'purple' }) : window.Icons.get('sun', { color: 'amber' });
    } else {
      toggleIcon.innerHTML = isLight
        ? '<svg class="svg-icon icon-purple" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"/></svg>'
        : '<svg class="svg-icon icon-amber" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="4"/><path d="M12 2v2"/><path d="M12 20v2"/><path d="m4.93 4.93 1.41 1.41"/><path d="m17.66 17.66 1.41 1.41"/><path d="M2 12h2"/><path d="M20 12h2"/><path d="m6.34 17.66-1.41 1.41"/><path d="m19.07 4.93-1.41 1.41"/></svg>';
    }
  }
  if (toggleLabel) toggleLabel.textContent = isLight ? 'Dark' : 'Light';

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
      const patterns = Array.isArray(pojoCfg.customPatterns)
        ? pojoCfg.customPatterns
        : (typeof pojoCfg.patterns === 'string' ? pojoCfg.patterns.split(',').map(s => s.trim()).filter(Boolean) : []);
      pojoPatternsArea.value = patterns.join('\n');
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

function renderArchetypeRulesList() {
  const container = qs('#archetype-rules-list');
  if (!container || !window.CodeLensClassifier) return;

  const rules = window.CodeLensClassifier.getRules();
  const countBadge = qs('#archetype-count-badge');
  const activeCount = rules.filter(r => r.enabled).length;
  if (countBadge) {
    countBadge.textContent = `${activeCount} / ${rules.length} active`;
    countBadge.className = `archetype-count-badge ${activeCount > 0 ? 'active' : 'empty'}`;
  }

  if (rules.length === 0) {
    container.innerHTML = `
      <div class="archetype-empty-state">
        <svg class="svg-icon icon-slate icon-lg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2H2v10l9.29 9.29c.94.94 2.48.94 3.42 0l6.58-6.58c.94-.94.94-2.48 0-3.42L12 2Z"/><circle cx="7" cy="7" r=".5" fill="currentColor"/></svg>
        <p class="archetype-empty-title">No Archetype Rules Configured</p>
        <p class="archetype-empty-sub">Choose a preset above (Banking, Spring REST, Clean Arch) or click "+ Add Archetype" to create custom rules.</p>
      </div>`;
    return;
  }

  container.innerHTML = rules.map(r => {
    const color = r.color || '#10b981';
    const scope = (r.scope || r.target || 'METHOD').toUpperCase();
    const matchType = (r.matchType || 'PREFIX').toUpperCase();
    const iconSvg = window.Icons ? window.Icons.get(r.icon || 'tag', { size: 'xs' }) : '';

    return `
      <div class="archetype-card ${r.enabled ? 'is-active' : 'is-disabled'}" data-id="${r.id}" style="border-left-color:${color};">
        <div class="archetype-card-toggle">
          <label class="toggle-switch" title="${r.enabled ? 'Disable rule' : 'Enable rule'}">
            <input type="checkbox" class="rule-toggle" data-id="${r.id}" ${r.enabled ? 'checked' : ''}>
            <span class="toggle-track"></span>
          </label>
        </div>
        <div class="archetype-card-badge-wrap">
          <span class="archetype-badge-pill" style="background:${color}1f; color:${color}; border-color:${color}55;">
            ${iconSvg}
            <span class="archetype-badge-tag">${esc(r.badge || r.label)}</span>
          </span>
        </div>
        <div class="archetype-card-body">
          <div class="archetype-card-header-row">
            <span class="archetype-card-title">${esc(r.label)}</span>
            <span class="archetype-scope-chip scope-${scope.toLowerCase()}">${scope}</span>
            <span class="archetype-match-chip">${matchType}</span>
          </div>
          <div class="archetype-card-sub-row">
            <code class="archetype-pattern-code" title="Pattern: ${esc(r.pattern)}">${esc(r.pattern)}</code>
            ${r.description ? `<span class="archetype-card-desc" title="${esc(r.description)}">${esc(r.description)}</span>` : ''}
          </div>
        </div>
        <div class="archetype-card-actions">
          <button class="rule-action-btn rule-btn-edit" data-id="${r.id}" title="Edit Archetype">
            <svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M17 3a2.828 2.828 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5L17 3z"/></svg>
          </button>
          <button class="rule-action-btn rule-btn-delete" data-id="${r.id}" title="Delete Archetype">
            <svg class="svg-icon icon-xs icon-red" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
          </button>
        </div>
      </div>
    `;
  }).join('');

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
        const lines = pojoPatternsArea.value.split('\n').map(s => s.trim()).filter(Boolean);
        window.CodeLensClassifier.setPojoConfig({ customPatterns: lines });
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
          const patterns = Array.isArray(cfg.customPatterns)
            ? cfg.customPatterns
            : (typeof cfg.patterns === 'string' ? cfg.patterns.split(',').map(s => s.trim()).filter(Boolean) : []);
          pojoPatternsArea.value = patterns.join('\n');
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

// ── Export Reports Hub ───────────────────────────────────────────────────────
const ExportHub = {
  activeType: 'architecture',
  activeFormat: 'markdown',
  cachedContent: '',
  loading: false,

  open(defaultType = 'architecture', defaultFormat = 'markdown', triggerEl = null) {
    const modal = qs('#export-modal');
    if (!modal) return;

    ExportHub.activeType = defaultType;
    ExportHub.activeFormat = defaultFormat;
    ExportHub.syncUI();
    ExportHub.fetchPreview();

    showAccessibleModal(modal, triggerEl || qs('#export-btn') || qs('#export-review-report-btn'));
  },

  close() {
    const modal = qs('#export-modal');
    if (!modal) return;
    hideAccessibleModal(modal);
  },

  syncUI() {
    // Highlight active report card
    qsa('.export-type-card').forEach(c => {
      c.classList.toggle('active', c.dataset.report === ExportHub.activeType);
    });

    // Update format pills
    qsa('.export-format-btn').forEach(btn => {
      btn.classList.toggle('active', btn.dataset.format === ExportHub.activeFormat);
    });
  },

  async fetchPreview() {
    const codeEl = qs('#export-preview-code');
    const frameEl = qs('#export-preview-frame');
    const statusEl = qs('#export-preview-status');
    if (!codeEl || !frameEl) return;

    if (statusEl) statusEl.textContent = 'Generating report…';

    try {
      ExportHub.loading = true;
      const isSnapshot = (ExportHub.activeType === 'html-snapshot' || ExportHub.activeType === 'graph-snapshot');
      const url = isSnapshot
        ? '/api/reports/html-snapshot'
        : `/api/reports/${ExportHub.activeType}?format=${ExportHub.activeFormat}`;
      const res = await fetch(url);
      if (!res.ok) {
        throw new Error(`HTTP ${res.status}: ${res.statusText}`);
      }

      let text = await res.text();
      // Format JSON string if response is raw JSON
      if (ExportHub.activeFormat === 'json' && !isSnapshot) {
        try {
          const parsed = JSON.parse(text);
          text = JSON.stringify(parsed, null, 2);
        } catch (_) {}
      }

      ExportHub.cachedContent = text;

      if (ExportHub.activeFormat === 'html' || isSnapshot) {
        codeEl.style.display = 'none';
        frameEl.style.display = 'block';
        frameEl.srcdoc = text;
      } else {
        frameEl.style.display = 'none';
        codeEl.style.display = 'block';
        codeEl.textContent = text;
      }

      if (statusEl) statusEl.textContent = `Generated (${(text.length / 1024).toFixed(1)} KB)`;
    } catch (err) {
      ExportHub.cachedContent = '';
      if (codeEl) {
        frameEl.style.display = 'none';
        codeEl.style.display = 'block';
        codeEl.textContent = 'Error generating report: ' + err.message;
      }
      if (statusEl) statusEl.textContent = 'Error';
    } finally {
      ExportHub.loading = false;
    }
  },

  async download() {
    const isSnapshot = (ExportHub.activeType === 'html-snapshot' || ExportHub.activeType === 'graph-snapshot');
    const ext = isSnapshot ? 'html' : (ExportHub.activeFormat === 'markdown' ? 'md' : ExportHub.activeFormat);
    const filename = isSnapshot ? 'codelens-interactive-graph.html' : `codelens-${ExportHub.activeType}-report.${ext}`;

    let content = ExportHub.cachedContent;
    if (!content) {
      try {
        const url = isSnapshot
          ? '/api/reports/html-snapshot'
          : `/api/reports/${ExportHub.activeType}?format=${ExportHub.activeFormat}`;
        const res = await fetch(url);
        content = await res.text();
        ExportHub.cachedContent = content;
      } catch (e) {
        showError('Failed to fetch report content: ' + e.message);
        return;
      }
    }

    const mimeTypes = {
      html: 'text/html;charset=utf-8',
      markdown: 'text/markdown;charset=utf-8',
      json: 'application/json;charset=utf-8',
      csv: 'text/csv;charset=utf-8',
    };
    const mime = isSnapshot ? 'text/html;charset=utf-8' : (mimeTypes[ExportHub.activeFormat] || 'text/plain;charset=utf-8');

    try {
      const blob = new Blob([content], { type: mime });
      const blobUrl = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = blobUrl;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      setTimeout(() => {
        if (a.parentNode) document.body.removeChild(a);
        URL.revokeObjectURL(blobUrl);
      }, 300);
      showBanner(`Downloaded ${filename}`);
    } catch (err) {
      const url = isSnapshot ? '/api/reports/download?type=html-snapshot' : `/api/reports/download?type=${ExportHub.activeType}&format=${ExportHub.activeFormat}`;
      const a = document.createElement('a');
      a.href = url;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      setTimeout(() => {
        if (a.parentNode) document.body.removeChild(a);
      }, 300);
      showBanner(`Downloading ${filename}…`);
    }
  },

  copy() {
    if (!ExportHub.cachedContent) return;
    navigator.clipboard.writeText(ExportHub.cachedContent).then(() => {
      const copyBtn = qs('#btn-export-copy');
      if (copyBtn) {
        const orig = copyBtn.innerHTML;
        copyBtn.innerHTML = '<svg class="svg-icon icon-emerald icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg> Copied!';
        setTimeout(() => { copyBtn.innerHTML = orig; }, 2000);
      }
      showBanner('Report copied to clipboard!');
    }).catch(err => {
      showBanner('Failed to copy: ' + err.message);
    });
  },

  openTab() {
    const isSnapshot = (ExportHub.activeType === 'html-snapshot' || ExportHub.activeType === 'graph-snapshot');
    if ((ExportHub.activeFormat === 'html' || isSnapshot) && ExportHub.cachedContent) {
      const win = window.open('', '_blank');
      if (win) {
        win.document.open();
        win.document.write(ExportHub.cachedContent);
        win.document.close();
        return;
      }
    }
    const url = isSnapshot ? '/api/reports/html-snapshot' : `/api/reports/${ExportHub.activeType}?format=${ExportHub.activeFormat}`;
    window.open(url, '_blank');
  }
};

function initExportHub() {
  const exportBtn = qs('#export-btn');
  if (exportBtn) exportBtn.addEventListener('click', (e) => ExportHub.open('architecture', 'markdown', e.currentTarget));

  const exportReviewBtn = qs('#export-review-report-btn');
  if (exportReviewBtn) exportReviewBtn.addEventListener('click', (e) => ExportHub.open('review', 'markdown', e.currentTarget));

  const closeBtn = qs('#export-modal-close');
  if (closeBtn) closeBtn.addEventListener('click', () => ExportHub.close());

  const modal = qs('#export-modal');
  if (modal) {
    modal.addEventListener('click', e => { if (e.target === modal) ExportHub.close(); });
  }

  qsa('.export-type-card').forEach(card => {
    card.addEventListener('click', () => {
      ExportHub.activeType = card.dataset.report;
      if (ExportHub.activeType === 'html-snapshot') {
        ExportHub.activeFormat = 'html';
      }
      ExportHub.syncUI();
      ExportHub.fetchPreview();
    });
  });

  qsa('.export-format-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      ExportHub.activeFormat = btn.dataset.format;
      ExportHub.syncUI();
      ExportHub.fetchPreview();
    });
  });

  const downloadBtn = qs('#btn-export-download');
  if (downloadBtn) downloadBtn.addEventListener('click', () => ExportHub.download());

  const copyBtn = qs('#btn-export-copy');
  if (copyBtn) copyBtn.addEventListener('click', () => ExportHub.copy());

  const openBtn = qs('#btn-export-open');
  if (openBtn) openBtn.addEventListener('click', () => ExportHub.openTab());
}

