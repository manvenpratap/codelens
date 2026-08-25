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
  fullGraph:          ()          => api.get('/graph/all'),
  architectureGraph:  (scope, filter) => api.get(`/graph/architecture${scope || filter ? '?' + new URLSearchParams({ ...(scope ? { scope } : {}), ...(filter ? { filter } : {}) }) : ''}`),
  dsmData:            (scope, filter) => api.get(`/graph/dsm${scope || filter ? '?' + new URLSearchParams({ ...(scope ? { scope } : {}), ...(filter ? { filter } : {}) }) : ''}`),
  treemapData:        (scope, filter) => api.get(`/graph/treemap${scope || filter ? '?' + new URLSearchParams({ ...(scope ? { scope } : {}), ...(filter ? { filter } : {}) }) : ''}`),
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

    const item = createElement('div', {
      class: `tree-item${App.selected.id === node.fqn ? ' active' : ''}`,
      'data-depth': depth,
      'data-fqn': node.fqn,
    });

    // Toggle arrow
    const toggle = createElement('span', { class: `tree-toggle${canExpand && isOpen ? ' open' : ''}` });
    toggle.textContent = canExpand ? '▶' : '';
    item.appendChild(toggle);

    // Icon
    const icon = createElement('span', { class: 'tree-icon' });
    icon.textContent = '📦';
    item.appendChild(icon);

    // Label: show full FQN in flat mode, or leaf name in hierarchical mode
    const label = createElement('span', { class: 'tree-label' });
    label.textContent = node.name || node.fqn;
    label.title = node.fqn;
    item.appendChild(label);

    // Count badge
    if (node.typeCount > 0) {
      const count = createElement('span', { class: 'tree-count' });
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

    for (const t of typeEls) {
      const item = createElement('div', {
        class: `tree-item tree-type-item${App.selected.id === t.id ? ' active' : ''}`,
        'data-depth': depth,
        'data-id': t.id,
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

/** Switch the active tab in the centre panel. */
function switchTab(tabName) {
  App.activeTab = tabName;
  qsa('.tab').forEach(t => t.classList.toggle('active', t.dataset.tab === tabName));
  qsa('.tab-content').forEach(tc => tc.classList.toggle('active', tc.id === tabName + '-view'));
  if (tabName === 'review') {
    updateReviewTargetInfo();
  }
  if (tabName === 'source' && App.editor) {
    setTimeout(() => {
      App.editor.layout();
    }, 20);
  }
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
async function openSourceFile(filePath, lineNum = null) {
  if (!filePath) return;

  App.currentFilePath = filePath;
  updateReviewTargetInfo();
  
  const pathLabel = qs('#editor-file-path');
  if (pathLabel) {
    pathLabel.innerHTML = `Source: <strong>${esc(filePath.split('/').pop().split('\\').pop())}</strong> <span style="font-size:10px; color:var(--text-muted)">(${esc(filePath)})</span>`;
  }

  try {
    // Fetch file content first
    const data = await api.readFile(filePath);

    // Switch to source tab
    switchTab('source');

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
  view.innerHTML = `<div class="kb-section-header">Types in ${esc(pkgFqn)}</div>`;

  try {
    const types = await api.typesByPackage(pkgFqn);
    const activeKind = (App.activeFilter || 'all').toUpperCase();
    const filteredTypes = types.filter(t => {
      if (activeKind === 'ALL') return true;
      return (t.kind || '').toUpperCase() === activeKind;
    });

    if (filteredTypes.length === 0) {
      const msg = activeKind === 'ALL'
        ? 'No types in this package.'
        : `No ${activeKind.toLowerCase()}s in this package (active filter: ${activeKind}).`;
      view.innerHTML += `<div class="list-empty">${msg}</div>`;
      return;
    }

    for (const t of filteredTypes) {
      const item = createElement('div', { class: 'kb-item fade-in' });
      item.innerHTML = `
        <span class="kb-item-icon">${kindIcon(t.kind)}</span>
        <div class="kb-item-body">
          <div class="kb-item-name">${esc(t.simpleName)}</div>
          <div class="kb-item-meta">
            ${t.lineCount > 0 ? t.lineCount + ' lines' : ''}
            ${t.fieldCount  > 0 ? '· ' + t.fieldCount  + ' fields'  : ''}
            ${t.methodCount > 0 ? '· ' + t.methodCount + ' methods' : ''}
          </div>
        </div>
        <span class="kb-item-badge badge-${(t.kind || '').toLowerCase()}">${t.kind}</span>`;

      item.addEventListener('click', () => selectType(t.id));
      view.appendChild(item);
    }
  } catch (e) {
    view.innerHTML += `<div class="list-empty">Error: ${e.message}</div>`;
  }
}

/* ── Inconsistency view ────────────────────────────────────────────────────── */

/* ─────────────────────────────────────────────────────────────────────────────
   5b. Code Review - on-demand AST-based review engine
   ───────────────────────────────────────────────────────────────────────────── */

// Active review mode: 'selection' | 'file' | 'snippet'
let reviewMode = 'selection';

const SEVERITY_META = {
  CRITICAL: { icon: '🔴', label: 'Critical', cls: 'sev-critical' },
  WARNING:  { icon: '🟠', label: 'Warning',  cls: 'sev-warning'  },
  INFO:     { icon: '🔵', label: 'Info',      cls: 'sev-info'     }
};

const CATEGORY_META = {
  CORRECTNESS:       { icon: '🔴', label: 'Correctness & Logic Defects' },
  EXCEPTION_SAFETY:  { icon: '🟠', label: 'Exception & Resource Safety' },
  THREAD_SAFETY:     { icon: '🟡', label: 'Thread Safety & Concurrency' },
  CODE_SMELL:        { icon: '🔵', label: 'Code Smell & Maintainability' },
  API_CONTRACT:      { icon: '🟣', label: 'API Contract & Design' },
  IMPACT:            { icon: '⚪', label: 'Impact & Cross-Cutting' }
};

function updateReviewTargetInfo() {
  const snippetArea = qs('#review-snippet-area');
  const targetInfo  = qs('#review-target-info');
  if (!targetInfo) return;

  if (reviewMode === 'snippet') {
    if (snippetArea) snippetArea.style.display = 'block';
    targetInfo.innerHTML = 'Paste your Java code above, then click <strong>⚡ Run Review</strong>.';
  } else {
    if (snippetArea) snippetArea.style.display = 'none';
    if (reviewMode === 'selection') {
      if (App.selected) {
        const kindStr = App.selected.kind ? String(App.selected.kind).toUpperCase() : 'UNKNOWN';
        targetInfo.innerHTML = `Target: <strong>${esc(App.selected.id)}</strong> <span style="font-size:10px; color:var(--text-muted)">(${kindStr})</span>`;
      } else {
        targetInfo.innerHTML = 'Select a class or method in the Explorer, then click <strong>⚡ Run Review</strong>.';
      }
    } else {
      if (App.currentFilePath) {
        targetInfo.innerHTML = `Target file: <strong>${esc(App.currentFilePath)}</strong>`;
      } else {
        targetInfo.innerHTML = 'Open a file in the Source tab first, then click <strong>⚡ Run Review</strong>.';
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
      targetInfo.innerHTML = '✅ <strong>No issues found.</strong> Code looks good!';
    }
  } catch (e) {
    resultsDiv.innerHTML = `<div class="list-empty">Review failed: ${esc(e.message)}</div>`;
  } finally {
    runBtn.disabled = false;
    runBtn.innerHTML = '⚡ Run Review';
  }
}

function renderReviewFindings(findings, container) {
  container.innerHTML = '';
  if (findings.length === 0) {
    container.innerHTML = '<div class="review-empty"><span>✅</span><p>No issues detected. Clean code!</p></div>';
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
    const catMeta = CATEGORY_META[cat] || { icon: '❓', label: cat };

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
        <div class="finding-suggestion">💡 ${esc(f.suggestion)}</div>
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

/** Load and render the full codebase graph in either Architecture or Methods view. */
async function loadWholeCodebaseGraph(level = null) {
  switchTab('graph');
  App.activeGraphMode = 'fullCodebase';
  if (level) App.codebaseGraphLevel = level;
  else if (!App.codebaseGraphLevel) App.codebaseGraphLevel = 'arch';

  App.selected = null;

  // Highlight Whole Codebase button and clear depth pills
  qsa('.depth-pill').forEach(btn => btn.classList.remove('active'));
  const fullBtn = qs('#btn-full-codebase');
  if (fullBtn) fullBtn.classList.add('active');

  // Show Level Selector in HUD
  const levelSel = qs('#graph-level-selector');
  const levelDiv = qs('#graph-level-divider');
  if (levelSel) levelSel.style.display = 'flex';
  if (levelDiv) levelDiv.style.display = 'block';

  // Update pill active states
  qsa('.level-pill').forEach(btn => btn.classList.toggle('active', btn.dataset.level === App.codebaseGraphLevel));

  const isAltViz = ['dsm', 'treemap', 'chord', 'sunburst'].includes(App.codebaseGraphLevel);

  // Destroy any previous alternate renderer
  if (App.activeAltRenderer) {
    App.activeAltRenderer.destroy();
    App.activeAltRenderer = null;
  }

  if (isAltViz) {
    // Hide ForceGraph canvas but do not destroy it
    const graphCanvas = qs('#graph-canvas');
    if (graphCanvas) graphCanvas.style.display = 'none';
    // Hide graph-specific HUD elements
    const hudActions = qs('.graph-hud-actions');
    if (hudActions) hudActions.style.display = 'none';
    const cameraControls = qs('.graph-camera-controls');
    if (cameraControls) cameraControls.style.display = 'none';
    const depthPills = qs('.graph-depth-pills');
    if (depthPills) depthPills.style.display = 'none';
    const graphLegend = qs('#graph-legend');
    if (graphLegend) graphLegend.style.display = 'none';
    const communityLegend = qs('#graph-community-legend');
    if (communityLegend) communityLegend.style.display = 'none';
    const graphMinimap = qs('#graph-minimap-wrap');
    if (graphMinimap) graphMinimap.style.display = 'none';
    const nodeCard = qs('#graph-node-card');
    if (nodeCard) nodeCard.style.display = 'none';

    hideGraphEmpty();

    try {
      if (App.codebaseGraphLevel === 'dsm') {
        showBanner('Loading Dependency Structure Matrix...');
        const data = await api.dsmData();
        if (!data.classes || data.classes.length === 0) {
          showGraphEmpty('No class data available for DSM. Run a scan first.');
          return;
        }
        const renderer = new window.DSMRenderer(qs('#graph-view'));
        renderer.onScopeChange(async (newScope) => {
          try {
            showBanner(`Loading DSM (${newScope})...`);
            const scopedData = await api.dsmData(newScope);
            renderer.setData(scopedData);
            renderAltVizInspector('DSM', scopedData.classes.length, 0);
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
        renderAltVizInspector('DSM', data.classes.length, 0);
        showBanner('DSM loaded: ' + data.classes.length + ' ' + (data.scope || 'classes'));

      } else if (App.codebaseGraphLevel === 'treemap') {
        showBanner('Loading Treemap...');
        const data = await api.treemapData();
        if (!data.children || data.children.length === 0) {
          showGraphEmpty('No hierarchy data available for Treemap. Run a scan first.');
          return;
        }
        const renderer = new window.TreemapRenderer(qs('#graph-view'));
        renderer.setData(data);
        App.activeAltRenderer = renderer;
        renderAltVizInspector('Treemap', countTreemapNodes(data), data.size);
        showBanner('Treemap loaded: ' + countTreemapNodes(data) + ' nodes, ' + data.size + ' total lines');

      } else if (App.codebaseGraphLevel === 'chord') {
        showBanner('Loading Chord Diagram...');
        const data = await api.architectureGraph();
        if (!data.nodes || data.nodes.length === 0) {
          showGraphEmpty('No architecture data available for Chord diagram. Run a scan first.');
          return;
        }
        const renderer = new window.ChordRenderer(qs('#graph-view'));
        renderer.setData(data);
        App.activeAltRenderer = renderer;
        renderAltVizInspector('Chord', data.nodes.length, data.edges.length);
        showBanner('Chord diagram loaded: ' + data.nodes.length + ' classes, ' + data.edges.length + ' relationships');

      } else if (App.codebaseGraphLevel === 'sunburst') {
        showBanner('Loading Sunburst...');
        const data = await api.treemapData();
        if (!data.children || data.children.length === 0) {
          showGraphEmpty('No hierarchy data available for Sunburst. Run a scan first.');
          return;
        }
        const renderer = new window.SunburstRenderer(qs('#graph-view'));
        renderer.setData(data);
        App.activeAltRenderer = renderer;
        renderAltVizInspector('Sunburst', countTreemapNodes(data), data.size);
        showBanner('Sunburst loaded: ' + countTreemapNodes(data) + ' nodes');
      }
    } catch (e) {
      showGraphEmpty('Failed to load visualization: ' + e.message);
    }

  } else {
    // Force graph modes (arch / methods)
    // Restore ForceGraph canvas and HUD elements
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

/** Count total nodes in a treemap tree (recursive). */
function countTreemapNodes(node) {
  if (!node) return 0;
  let count = 1;
  if (node.children) {
    for (const child of node.children) count += countTreemapNodes(child);
  }
  return count;
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
  };

  body.appendChild(metaGrid(labels[vizName] || [['Nodes', String(nodeCount)]]));

  const hint = createElement('div', { class: 'inspector-hint-box' });
  const tips = {
    'DSM': 'Rows = source classes, columns = target classes. Order: Cluster / Layered / Cycles / A-Z. Hover for crosshair, click to inspect call relationship.',
    'Treemap': 'Rectangle size = lines of code. Colors = categorical palette consistent with Sunburst & Chord. Click to zoom in.',
    'Chord': 'Arc size = connection volume. Chords = inter-class calls. Hover an arc to isolate its connections.',
    'Sunburst': 'Ring segments = packages/classes/methods. Angle = proportion of code size. Click to zoom in.',
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
        ${info.isCycle ? '⚠️ CIRCULAR FEEDBACK' : '⬇ CALLS'} (${info.weight} call${info.weight > 1 ? 's' : ''})
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

  const pkgs = new Set((view.nodes || []).map(n => n.package || n.id.split('.').slice(0, -1).join('.')));

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
    if (gm && gm.commitCount !== undefined) {
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
  const { type, fields = [], methods = [] } = data;
  const view = qs('#knowledge-view');
  view.innerHTML = '';

  const isRecord = (type.kind || '').toUpperCase() === 'RECORD';

  const header = createElement('div', { class: 'kb-section-header' });
  header.innerHTML = `<span class="kb-type-pill badge-${(type.kind || '').toLowerCase()}">${esc(type.kind)}</span> ${esc(type.simpleName)} <span style="font-size:11px;font-weight:400;color:var(--text-muted);margin-left:8px;">(${esc(type.packageFqn || 'default package')})</span>`;
  view.appendChild(header);

  if (fields.length > 0) {
    const fieldsHeader = createElement('div', {
      class: 'kb-section-header',
      style: 'font-size:11px;padding:10px 16px 4px;font-weight:700;'
    });
    fieldsHeader.textContent = isRecord ? 'Record Components / Fields' : 'Fields';
    view.appendChild(fieldsHeader);

    for (const f of fields) {
      const item = createElement('div', { class: 'kb-item fade-in' });
      item.innerHTML = `
        <span class="kb-item-icon">■</span>
        <div class="kb-item-body">
          <div class="kb-item-name">${esc(f.simpleName)}</div>
          <div class="kb-item-meta">${esc(f.fieldType || '')} · ${esc(f.modifiers || '')} ${f.startLine ? '· line ' + f.startLine : ''}</div>
        </div>
        <span class="kb-item-badge badge-enum">${esc(f.fieldType || '')}</span>`;
      item.addEventListener('click', () => selectField(f.id || f.fqn));
      view.appendChild(item);
    }
  }

  if (methods.length > 0) {
    const methodsHeader = createElement('div', {
      class: 'kb-section-header',
      style: 'font-size:11px;padding:10px 16px 4px;font-weight:700;'
    });
    methodsHeader.textContent = isRecord ? 'Methods & Accessors' : 'Methods';
    view.appendChild(methodsHeader);

    for (const m of methods) {
      const item = createElement('div', { class: 'kb-item fade-in' });
      const paramStr = (m.parameters || []).map(p => p.type + ' ' + p.name).join(', ');
      const cc = m.cyclomaticComplexity || 1;
      const ccColour = cc <= 4 ? 'var(--emerald)' : cc <= 10 ? 'var(--amber)' : 'var(--red)';
      item.innerHTML = `
        <span class="kb-item-icon">◆</span>
        <div class="kb-item-body">
          <div class="kb-item-name">${esc(m.simpleName)}</div>
          <div class="kb-item-meta">${esc(m.returnType || 'void')} · CC:
            <span style="color:${ccColour};font-weight:700">${cc}</span>
            ${m.startLine ? '· line ' + m.startLine : ''}
          </div>
          <div class="kb-item-meta" style="color:var(--text-muted)">(${esc(paramStr)})</div>
        </div>
        <span class="kb-item-badge badge-iface">${esc(m.modifiers || '')}</span>`;
      item.addEventListener('click', () => selectMethod(m.id || m.fqn));
      view.appendChild(item);
    }
  }

  if (fields.length === 0 && methods.length === 0) {
    const emptyEl = createElement('div', { class: 'list-empty' });
    emptyEl.textContent = `No members declared in this ${esc((type.kind || 'type').toLowerCase())}.`;
    view.appendChild(emptyEl);
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
    if (gm && gm.commitCount !== undefined) {
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
    if (gm && gm.commitCount !== undefined) {
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
    { label: '⚡ Impact (Direct)', title: 'Show direct readers, writers, and immediate propagators of this field', action: () => { switchTab('graph'); loadFieldImpact(field.id); } },
    { label: '🔗 Propagation Chain', title: 'Trace multi-hop upstream triggers and calling entrypoints that modify this field', action: () => { switchTab('graph'); loadFieldPropagationChain(field.id); } },
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
      <button class="note-delete" title="Delete note">✕</button>`;

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

function bindKeyboard() {
  document.addEventListener('keydown', e => {
    const helpModal = qs('#help-modal');
    // Escape → close modal or clear search
    if (e.key === 'Escape') {
      const settingsModal = qs('#settings-modal');
      if (settingsModal && settingsModal.classList.contains('open')) {
        closeSettings();
        return;
      }
      if (helpModal && helpModal.classList.contains('open')) {
        helpModal.classList.remove('open');
        helpModal.setAttribute('aria-hidden', 'true');
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
      if (e.key === '1') switchTab('graph');
      if (e.key === '2') switchTab('knowledge');
      if (e.key === '3') switchTab('review');
      if (e.key === '4') switchTab('git');
      if (e.key === '5') switchTab('source');
      if (e.key === '[') toggleLeftPanel();
      if (e.key === ']') toggleRightPanel();
      if (e.key === '\\') resetPanelWidths();
      if (e.key === '?') {
        if (helpModal) {
          helpModal.classList.toggle('open');
          helpModal.setAttribute('aria-hidden', helpModal.classList.contains('open') ? 'false' : 'true');
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
    showBanner(`Theme switched to ${newTheme === 'light' ? '☀️ Light (Pure Daylight)' : '🌑 Dark (Midnight Obsidian)'}`);
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
        showBanner(`✓ Folder selected: "${rootDir}" (${e.target.files.length} files detected). Please confirm the full absolute path in the scan bar and click Scan.`);
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

  // Tab bar
  qsa('.tab').forEach(tab => {
    tab.addEventListener('click', () => {
      switchTab(tab.dataset.tab);
      if (tab.dataset.tab === 'git') loadGitSummary();
    });
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
        showBanner('✓ File saved successfully');
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
    helpBtn.addEventListener('click', () => {
      helpModal.classList.add('open');
      helpModal.setAttribute('aria-hidden', 'false');
    });
  }
  if (helpClose && helpModal) {
    helpClose.addEventListener('click', () => {
      helpModal.classList.remove('open');
      helpModal.setAttribute('aria-hidden', 'true');
    });
  }
  if (helpModal) {
    helpModal.addEventListener('click', (e) => {
      if (e.target === helpModal) {
        helpModal.classList.remove('open');
        helpModal.setAttribute('aria-hidden', 'true');
      }
    });
  }

  bindKeyboard();

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

  // Whole codebase graph button
  const fullCodebaseBtn = qs('#btn-full-codebase');
  if (fullCodebaseBtn) {
    fullCodebaseBtn.addEventListener('click', () => loadWholeCodebaseGraph(App.codebaseGraphLevel || 'arch'));
  }

  // Level selector buttons
  const archLevelBtn = qs('#btn-level-arch');
  if (archLevelBtn) {
    archLevelBtn.addEventListener('click', () => loadWholeCodebaseGraph('arch'));
  }
  const methodsLevelBtn = qs('#btn-level-methods');
  if (methodsLevelBtn) {
    methodsLevelBtn.addEventListener('click', () => loadWholeCodebaseGraph('methods'));
  }
  const dsmLevelBtn = qs('#btn-level-dsm');
  if (dsmLevelBtn) {
    dsmLevelBtn.addEventListener('click', () => loadWholeCodebaseGraph('dsm'));
  }
  const treemapLevelBtn = qs('#btn-level-treemap');
  if (treemapLevelBtn) {
    treemapLevelBtn.addEventListener('click', () => loadWholeCodebaseGraph('treemap'));
  }
  const chordLevelBtn = qs('#btn-level-chord');
  if (chordLevelBtn) {
    chordLevelBtn.addEventListener('click', () => loadWholeCodebaseGraph('chord'));
  }
  const sunburstLevelBtn = qs('#btn-level-sunburst');
  if (sunburstLevelBtn) {
    sunburstLevelBtn.addEventListener('click', () => loadWholeCodebaseGraph('sunburst'));
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

  // Pre-fill input if empty and scan path is available
  if (repoInput && (!repoInput.value || repoInput.value.trim() === '')) {
    const scanPath = qs('#scan-path-input')?.value?.trim();
    if (scanPath) {
      repoInput.value = scanPath;
      repoInput.dataset.synced = 'true';
    }
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
  if (!badge) return;
  if (info.idle) {
    badge.innerHTML = '<span class="git-status-dot idle"></span><span class="git-status-text">Not validated</span>';
  } else if (info.valid) {
    badge.innerHTML = `<span class="git-status-dot valid"></span><span class="git-status-text">✓ Valid (${esc(info.branch || 'HEAD')})</span>`;
  } else if (info.running) {
    badge.innerHTML = '<span class="git-status-dot running"></span><span class="git-status-text">Analyzing…</span>';
  } else {
    badge.innerHTML = `<span class="git-status-dot invalid"></span><span class="git-status-text" title="${esc(info.error || '')}">✗ ${esc(info.error || 'Invalid repository')}</span>`;
  }
}

async function validateGitRepoPath() {
  const repoInput = qs('#git-repo-input');
  const repoPath = repoInput ? repoInput.value.trim() : '';
  if (!repoPath) {
    updateGitValidationBadge({ error: 'Please enter a path' });
    return false;
  }

  const validateBtn = qs('#git-validate-btn');
  if (validateBtn) {
    validateBtn.disabled = true;
    validateBtn.textContent = 'Validating…';
  }

  try {
    const res = await api.validateGitRepo(repoPath);
    if (res.valid) {
      updateGitValidationBadge({ valid: true, branch: res.branch });
      if (repoInput) repoInput.value = res.repoPath;
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
      validateBtn.textContent = '✓ Validate Repo';
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
    analyzeBtn.innerHTML = '<span class="spinner-inline"></span> Starting…';
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
      analyzeBtn.innerHTML = '⚡ Analyze Git History';
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
          analyzeBtn.textContent = 'Analyze History';
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
          analyzeBtn.innerHTML = '⚡ Analyze Git History';
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
  if (!authorsList || !hotList) return;

  try {
    const summary = await api.gitSummary();
    // ── Top authors ───────────────────────────────────────────────────────────
    if (!summary.topAuthors || summary.topAuthors.length === 0) {
      authorsList.innerHTML = '<div class="list-empty">Connect a Git repository above to inspect author telemetry.</div>';
    } else {
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
      hotList.innerHTML = '<div class="list-empty">No churn data available yet.</div>';
    } else {
      const maxCount = Math.max(...summary.hotEntities.map(e => e.commitCount), 1);
      hotList.innerHTML = summary.hotEntities.map(e => {
        const pct   = Math.round((e.commitCount / maxCount) * 100);
        const label = (e.entityFqn || '').split('.').pop();
        return `<div class="git-hot-row">
          <div class="git-hot-label" title="${esc(e.entityFqn)}">${esc(label)}</div>
          <div class="git-hot-bar-wrap">
            <div class="git-hot-bar" style="width:${pct}%" aria-label="${e.commitCount} commits"></div>
          </div>
          <div class="git-hot-count">${e.commitCount}</div>
        </div>`;
      }).join('');
    }
  } catch (err) {
    authorsList.innerHTML = '<div class="list-empty">Git data not available yet.</div>';
    hotList.innerHTML = '';
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
  header.innerHTML = `
    <div class="entity-kind-badge ${kind}">${kind}</div>
    <div class="entity-name">${esc(name)}</div>
    <div class="entity-fqn" title="Click to copy fully qualified name" style="cursor:pointer; display:inline-flex; align-items:center; gap:6px;">
      <span>${esc(fqn)}</span>
      <span class="copy-hint-icon" style="opacity:0.6;font-size:11px;" title="Copy to clipboard">📋</span>
    </div>`;
  header.style.display = '';
  const fqnEl = header.querySelector('.entity-fqn');
  if (fqnEl) {
    fqnEl.onclick = () => {
      navigator.clipboard.writeText(fqn).then(() => {
        showBanner(`✓ Copied ${kind}: ${name}`);
      }).catch(() => {
        showBanner(`✓ Copied ${fqn}`);
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

/** Show a brief success banner at the bottom of the screen. */
function showBanner(msg) {
  const el = createElement('div', { class: 'banner-toast' });
  el.textContent = msg;
  document.body.appendChild(el);
  setTimeout(() => {
    el.classList.add('fade-out');
    setTimeout(() => el.remove(), 400);
  }, 3500);
}

/** Flash a red border on an input briefly. */
function flashInput(el) {
  el.style.borderColor = 'var(--red)';
  el.focus();
  setTimeout(() => { el.style.borderColor = ''; }, 1200);
}

/** Show a temporary error toast. */
function showError(msg) {
  const el = createElement('div', { class: 'error-toast' });
  el.textContent = msg;
  document.body.appendChild(el);
  setTimeout(() => {
    el.classList.add('fade-out');
    setTimeout(() => el.remove(), 400);
  }, 4000);
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
}

/* ─────────────────────────────────────────────────────────────────────────────
   10. Themes & Settings System
   ───────────────────────────────────────────────────────────────────────────── */

const THEMES = {
  dark: {
    label: 'Dark', icon: '🌑', tagline: 'OLED Obsidian. True black canvas & deep graphite contrast.',
    css: {
      '--bg-base': '#000000', '--bg-panel': '#0a0d12', '--bg-surface': '#12161f',
      '--bg-elevated': '#181e28', '--bg-modal': '#0a0d12', '--bg-glass': 'rgba(10,13,18,0.88)',
      '--border': 'rgba(255,255,255,0.08)', '--border-hover': 'rgba(255,255,255,0.16)',
      '--border-light': 'rgba(255,255,255,0.12)', '--border-focus': '#3b82f6',
      '--primary': '#60a5fa', '--primary-bg': '#181e28', '--primary-hover': '#21262d', '--primary-active': '#12161f',
      '--primary-subtle': 'rgba(255,255,255,0.05)', '--primary-glow': 'rgba(255,255,255,0.08)',
      '--primary-border': 'rgba(96,165,250,0.40)',
      '--cyan-bright': '#60a5fa', '--emerald': '#10b981', '--amber': '#f59e0b', '--red': '#ef4444',
      '--text-primary': '#f8fafc', '--text-secondary': '#cbd5e1', '--text-muted': '#94a3b8',
    },
    graph: {
      bg: '#000000', grid: 'rgba(255,255,255,0.03)',
      roles: { root:'#2563eb',caller:'#60a5fa',callee:'#10b981',propagator:'#f59e0b',field:'#fb923c',reader:'#60a5fa',writer:'#ef4444',default:'#2563eb' },
      edgeKind: { CALLS:'#60a5fa',READS_FIELD:'#10b981',WRITES_FIELD:'#f59e0b',EXTENDS:'#94a3b8',IMPLEMENTS:'#94a3b8',default:'#64748b' },
      nodeColors: ['#2563eb','#10b981','#f59e0b','#818cf8','#ef4444','#14b8a6','#3b82f6','#f97316','#84cc16','#94a3b8'],
      lightMode: false,
    },
    preview: ['#000000','#0a0d12','#2563eb','#10b981','#f59e0b'],
  },
  light: {
    label: 'Light', icon: '☀️', tagline: 'Pure Daylight. Crisp contrast, ultra-readable typography.',
    css: {
      '--bg-base': '#f1f5f9', '--bg-panel': '#ffffff', '--bg-surface': '#f8fafc',
      '--bg-elevated': '#e2e8f0', '--bg-modal': '#ffffff', '--bg-glass': 'rgba(241,245,249,0.95)',
      '--border': '#cbd5e1', '--border-hover': '#94a3b8',
      '--border-light': '#e2e8f0', '--border-focus': '#2563eb',
      '--primary': '#2563eb', '--primary-bg': '#e2e8f0', '--primary-hover': '#cbd5e1', '--primary-active': '#94a3b8',
      '--primary-subtle': 'rgba(0,0,0,0.04)', '--primary-glow': 'rgba(0,0,0,0.08)',
      '--primary-border': 'rgba(37,99,235,0.45)',
      '--cyan-bright': '#2563eb', '--emerald': '#059669', '--amber': '#d97706', '--red': '#dc2626',
      '--text-primary': '#0f172a', '--text-secondary': '#334155', '--text-muted': '#475569',
    },
    graph: {
      bg: '#f1f5f9', grid: 'rgba(0,0,0,0.06)',
      roles: { root:'#2563eb',caller:'#1d4ed8',callee:'#059669',propagator:'#d97706',field:'#c2410c',reader:'#2563eb',writer:'#dc2626',default:'#2563eb' },
      edgeKind: { CALLS:'#2563eb',READS_FIELD:'#059669',WRITES_FIELD:'#d97706',EXTENDS:'#475569',IMPLEMENTS:'#475569',default:'#64748b' },
      nodeColors: ['#2563eb','#059669','#7c3aed','#0d9488','#e11d48','#15803d','#9333ea','#1e293b','#475569','#2563eb'],
      lightMode: true,
    },
    preview: ['#f1f5f9','#ffffff','#2563eb','#059669','#d97706'],
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
  if (toggleIcon) toggleIcon.textContent = isLight ? '🌙' : '☀️';
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
}

function openSettings() {
  const modal = qs('#settings-modal');
  if (!modal) return;
  syncSettingsUI(loadSettings());
  modal.classList.add('open');
  modal.setAttribute('aria-hidden', 'false');
}

function closeSettings() {
  const modal = qs('#settings-modal');
  if (!modal) return;
  modal.classList.remove('open');
  modal.setAttribute('aria-hidden', 'true');
}

function resetSettings() {
  const defaults = { ...SETTINGS_DEFAULTS };
  saveSettings(defaults);
  applyAllSettings(defaults);
  syncSettingsUI(defaults);
  showBanner('Settings restored to defaults');
}

function initSettings() {
  // Apply stored settings on load
  const settings = loadSettings();
  applyAllSettings(settings);

  // Wire ⚙ button
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
}

// ── Export Reports Hub ───────────────────────────────────────────────────────
const ExportHub = {
  activeType: 'architecture',
  activeFormat: 'markdown',
  cachedContent: '',
  loading: false,

  open(defaultType = 'architecture', defaultFormat = 'markdown') {
    const modal = qs('#export-modal');
    if (!modal) return;

    ExportHub.activeType = defaultType;
    ExportHub.activeFormat = defaultFormat;
    ExportHub.syncUI();
    ExportHub.fetchPreview();

    modal.classList.add('open');
    modal.setAttribute('aria-hidden', 'false');
  },

  close() {
    const modal = qs('#export-modal');
    if (!modal) return;
    modal.classList.remove('open');
    modal.setAttribute('aria-hidden', 'true');
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
      const res = await fetch(`/api/reports/${ExportHub.activeType}?format=${ExportHub.activeFormat}`);
      const text = await res.text();
      ExportHub.cachedContent = text;

      if (ExportHub.activeFormat === 'html') {
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
    const ext = ExportHub.activeFormat === 'markdown' ? 'md' : ExportHub.activeFormat;
    const filename = `codelens-${ExportHub.activeType}-report.${ext}`;

    let content = ExportHub.cachedContent;
    if (!content) {
      try {
        const res = await fetch(`/api/reports/${ExportHub.activeType}?format=${ExportHub.activeFormat}`);
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
    const mime = mimeTypes[ExportHub.activeFormat] || 'text/plain;charset=utf-8';

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
      showBanner(`✓ Downloaded ${filename}`);
    } catch (err) {
      const url = `/api/reports/download?type=${ExportHub.activeType}&format=${ExportHub.activeFormat}`;
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
        const orig = copyBtn.textContent;
        copyBtn.textContent = '✔ Copied!';
        setTimeout(() => { copyBtn.textContent = orig; }, 2000);
      }
      showBanner('Report copied to clipboard!');
    }).catch(err => {
      showBanner('Failed to copy: ' + err.message);
    });
  },

  openTab() {
    if (ExportHub.activeFormat === 'html' && ExportHub.cachedContent) {
      const win = window.open('', '_blank');
      if (win) {
        win.document.open();
        win.document.write(ExportHub.cachedContent);
        win.document.close();
        return;
      }
    }
    const url = `/api/reports/${ExportHub.activeType}?format=${ExportHub.activeFormat}`;
    window.open(url, '_blank');
  }
};

function initExportHub() {
  const exportBtn = qs('#export-btn');
  if (exportBtn) exportBtn.addEventListener('click', () => ExportHub.open('architecture', 'markdown'));

  const exportReviewBtn = qs('#export-review-report-btn');
  if (exportReviewBtn) exportReviewBtn.addEventListener('click', () => ExportHub.open('review', 'markdown'));

  const closeBtn = qs('#export-modal-close');
  if (closeBtn) closeBtn.addEventListener('click', () => ExportHub.close());

  const modal = qs('#export-modal');
  if (modal) {
    modal.addEventListener('click', e => { if (e.target === modal) ExportHub.close(); });
  }

  qsa('.export-type-card').forEach(card => {
    card.addEventListener('click', () => {
      ExportHub.activeType = card.dataset.report;
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

