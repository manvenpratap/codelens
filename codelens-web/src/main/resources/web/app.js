/**
 * app.js — CodeLens Frontend Application Controller
 *
 * Manages all UI state, API communication, and panel coordination.
 * Uses vanilla ES2020+ (no framework, no build step).
 *
 * Module sections:
 *   1. State management
 *   2. API client
 *   3. Scan workflow
 *   4. Left panel — explorer tree + search
 *   5. Centre panel — tabs and views
 *   6. Right panel — entity detail + notes
 *   7. Graph integration
 *   8. Keyboard shortcuts
 *   9. Bootstrapping
 */

/* ─────────────────────────────────────────────────────────────────────────────
   1. Application state — single source of truth
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
  // Monaco Editor state
  currentFilePath: null,
  editor: null,
  editorPromise: null,
};

/* ─────────────────────────────────────────────────────────────────────────────
   2. API client — thin fetch wrapper
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
  field:              (id)        => api.get(`/fields/${enc(id)}`),
  fieldImpact:        (id)        => api.get(`/fields/${enc(id)}/impact`),
  inconsistencies:    ()          => api.get('/inconsistencies'),
  search:             (q, n=30)   => api.get(`/search?q=${encodeURIComponent(q)}&limit=${n}`),
  scanStatus:         ()          => api.get('/scan/status'),
  startScan:          (sourcePath) => api.post('/scan', { sourcePath }),
  notes:              (fqn)       => api.get(`/notes/${enc(fqn)}`),
  saveNote:           (body)      => api.post('/notes', body),
  deleteNote:         (id)        => api.delete(`/notes/${id}`),
  gitSummary:         ()          => api.get('/git/summary'),
  gitMeta:            (fqn)       => api.get(`/git/meta/${enc(fqn)}`),
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

  setScanUI('scanning');
  try {
    await api.startScan(path);
    pollScanStatus();
  } catch (e) {
    setScanUI('idle');
    showError('Scan failed to start: ' + e.message);
  }
}

/** Poll /api/scan/status every 900 ms until COMPLETE or ERROR. */
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
  }, 900);
}

/** Update the progress bar and status text during an active scan. */
function updateScanProgress(s) {
  const pct = s.percentage || 0;
  qs('#scan-progress-bar').style.width = pct + '%';
  qs('.scan-status-text').textContent  = s.message || 'Scanning…';
  qs('.scan-pct').textContent          = pct + '%';
  qs('#scan-status-bar').classList.add('visible');
  
  // Footer update
  const fText = qs('#footer-status-text');
  const fInd = qs('.status-indicator');
  if (fText) fText.textContent = `Scanning: ${s.message || ''} (${pct}%)`;
  if (fInd) { fInd.className = 'status-indicator busy'; }
}

/** Called when scan finishes successfully. */
async function onScanComplete(s) {
  setScanUI('idle');
  qs('#scan-status-bar').classList.remove('visible');
  qs('#scan-progress-bar').style.width = '100%';
  setTimeout(() => qs('#scan-progress-bar').style.width = '0%', 600);

  // Refresh stats and tree
  await loadStats();
  await loadPackageTree();
  await loadInconsistencies();

  // Footer update
  const fText = qs('#footer-status-text');
  const fInd = qs('.status-indicator');
  if (fText) fText.textContent = 'Analyzer Idle · Scan complete';
  if (fInd) { fInd.className = 'status-indicator live'; }

  // Check git branch
  updateFooterGitBranch();

  showBanner(`✓ Scan complete — ${s.typesFound} types · ${s.methodsFound} methods · ${s.fieldsFound} fields`);
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
      branchEl.textContent = 'Branch: —';
    }
  } catch (_) {
    branchEl.textContent = 'Branch: —';
  }
}


/* ─────────────────────────────────────────────────────────────────────────────
   4. Left panel — package tree + search
   ───────────────────────────────────────────────────────────────────────────── */

/** Fetch all packages and render the tree into #explorer-tree. */
async function loadPackageTree() {
  const tree = qs('#explorer-tree');
  tree.innerHTML = '<div class="list-empty">Loading…</div>';

  try {
    App.packages = await api.packages();

    // Build a tree structure from the flat list
    const root     = buildPackageTree(App.packages);
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
 * Convert flat package list to a tree.
 * e.g. ["com.example", "com.example.trading"] → nested children.
 */
function buildPackageTree(packages) {
  const map = {};
  const roots = [];

  // Sort so parents always come before children
  const sorted = [...packages].sort((a, b) => a.fqn.localeCompare(b.fqn));

  for (const pkg of sorted) {
    map[pkg.fqn] = { ...pkg, children: [] };
  }

  for (const pkg of sorted) {
    if (pkg.parentFqn && map[pkg.parentFqn]) {
      map[pkg.parentFqn].children.push(map[pkg.fqn]);
    } else {
      roots.push(map[pkg.fqn]);
    }
  }

  return roots;
}

/** Recursively render the package tree into a container element. */
function renderPackageTree(nodes, container, depth) {
  for (const node of nodes) {
    const hasChildren = node.children && node.children.length > 0;
    const isOpen      = App.openPackages.has(node.fqn);

    const item = createElement('div', {
      class:       `tree-item${App.selected.id === node.fqn ? ' active' : ''}`,
      'data-depth': depth,
      'data-fqn':   node.fqn,
    });

    // Toggle arrow
    const toggle = createElement('span', { class: `tree-toggle${hasChildren && isOpen ? ' open' : ''}` });
    toggle.textContent = hasChildren ? '▶' : '';
    item.appendChild(toggle);

    // Icon
    const icon = createElement('span', { class: 'tree-icon' });
    icon.textContent = '📦';
    item.appendChild(icon);

    // Label
    const label = createElement('span', { class: 'tree-label' });
    label.textContent = node.name || node.fqn;
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
      style: hasChildren && !isOpen ? 'display:none' : '',
    });
    container.appendChild(childContainer);

    // Click: toggle or load types
    item.addEventListener('click', async e => {
      e.stopPropagation();
      if (hasChildren) {
        const open = App.openPackages.has(node.fqn);
        if (open) {
          App.openPackages.delete(node.fqn);
          childContainer.style.display = 'none';
          toggle.classList.remove('open');
        } else {
          App.openPackages.add(node.fqn);
          childContainer.style.display = '';
          toggle.classList.add('open');
          // Lazy-load types if not already populated
          if (!childContainer.dataset.loaded) {
            await loadTypesInTree(node.fqn, childContainer, depth + 1);
            childContainer.dataset.loaded = '1';
          }
        }
      }
      selectPackage(node, item);
    });

    // Recursively render sub-packages
    if (hasChildren) {
      renderPackageTree(node.children, childContainer, depth + 1);
    }
  }
}

/** Load types for a package and append them to the tree. */
async function loadTypesInTree(pkgFqn, container, depth) {
  try {
    const types = await api.typesByPackage(pkgFqn);
    const typeEls = types.filter(t => {
      if (App.activeFilter === 'all') return true;
      return t.kind === App.activeFilter;
    });

    for (const t of typeEls) {
      const item = createElement('div', {
        class:        `tree-item${App.selected.id === t.id ? ' active' : ''}`,
        'data-depth': depth,
        'data-id':    t.id,
      });

      const icon  = createElement('span', { class: 'tree-icon' });
      icon.textContent = kindIcon(t.kind);
      item.appendChild(icon);

      const label = createElement('span', { class: 'tree-label' });
      label.textContent = t.simpleName;
      item.appendChild(label);

      item.addEventListener('click', e => {
        e.stopPropagation();
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

/** Debounced search — triggers Lucene search after 280 ms of idle. */
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

function setFilter(kind) {
  App.activeFilter = kind;
  qsa('.chip').forEach(c => c.classList.toggle('active', c.dataset.filter === kind));
  // Reload open package sub-trees with new filter
  qsa('.tree-children[data-loaded]').forEach(el => {
    el.removeAttribute('data-loaded');
    const childNodes = [...el.children].filter(c => c.dataset.id);
    childNodes.forEach(c => c.remove());
    // Will be lazily reloaded on next open
  });
}

/* ─────────────────────────────────────────────────────────────────────────────
   5. Centre panel — tabs and views
   ───────────────────────────────────────────────────────────────────────────── */

/** Switch the active tab in the centre panel. */
function switchTab(tabName) {
  App.activeTab = tabName;
  qsa('.tab').forEach(t => t.classList.toggle('active', t.dataset.tab === tabName));
  qsa('.tab-content').forEach(tc => tc.classList.toggle('active', tc.id === tabName + '-view'));
  if (tabName === 'source' && App.editor) {
    setTimeout(() => {
      App.editor.layout();
    }, 20);
  }
}

/** Load Monaco Editor from the CDN AMD loader. */
function initMonaco() {
  if (App.editorPromise) return App.editorPromise;

  App.editorPromise = new Promise((resolve, reject) => {
    if (typeof require === 'undefined') {
      reject(new Error('Monaco AMD loader require() not found in window context.'));
      return;
    }
    try {
      require.config({
        paths: { vs: 'https://cdnjs.cloudflare.com/ajax/libs/monaco-editor/0.45.0/min/vs' }
      });
      require(['vs/editor/editor.main'], () => {
        resolve(window.monaco);
      }, err => {
        reject(err);
      });
    } catch (e) {
      reject(e);
    }
  });

  return App.editorPromise;
}

/** Fetch a source file, mount Monaco Editor, load the code, and focus on the line. */
async function openSourceFile(filePath, lineNum = null) {
  if (!filePath) return;

  App.currentFilePath = filePath;
  
  const pathLabel = qs('#editor-file-path');
  if (pathLabel) {
    pathLabel.innerHTML = `Source: <strong>${esc(filePath.split('/').pop())}</strong> <span style="font-size:10px; color:var(--text-muted)">(${esc(filePath)})</span>`;
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

    // Load Monaco
    const monaco = await initMonaco();

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

    if (types.length === 0) {
      view.innerHTML += '<div class="list-empty">No types in this package.</div>';
      return;
    }

    for (const t of types) {
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
        <span class="kb-item-badge badge-${t.kind.toLowerCase()}">${t.kind}</span>`;

      item.addEventListener('click', () => selectType(t.id));
      view.appendChild(item);
    }
  } catch (e) {
    view.innerHTML += `<div class="list-empty">Error: ${e.message}</div>`;
  }
}

/* ── Inconsistency view ────────────────────────────────────────────────────── */

async function loadInconsistencies() {
  const view   = qs('#inconsistency-view');
  const badge  = qs('.tab[data-tab="inconsistency"] .tab-badge');

  try {
    const items = await api.inconsistencies();
    if (badge) badge.textContent = items.length;
    view.innerHTML = '';

    if (items.length === 0) {
      view.innerHTML = '<div class="list-empty">No inconsistencies detected.</div>';
      return;
    }

    for (const item of items) {
      const el = createElement('div', { class: 'incon-item fade-in' });
      el.innerHTML = `
        <div class="incon-header">
          <span class="incon-kind ${item.kind}">${item.kind.replace(/_/g,' ')}</span>
          <span class="incon-score">${(item.similarityScore * 100).toFixed(0)}% similar</span>
        </div>
        <div class="incon-entities">
          ${esc(shortFqn(item.entity1Fqn))}
          <span style="color:var(--text-muted)"> ↔ </span>
          ${esc(shortFqn(item.entity2Fqn))}
        </div>
        <div class="incon-reason">${esc(item.reason || '')}</div>`;

      el.addEventListener('click', () => {
        // Navigate to the first entity for context
        if (item.entity1Kind === 'METHOD') selectMethod(item.entity1Fqn);
        else if (item.entity1Kind === 'FIELD') selectField(item.entity1Fqn);
      });

      view.appendChild(el);
    }
  } catch (e) {
    view.innerHTML = `<div class="list-empty">Error: ${e.message}</div>`;
  }
}

/* ─────────────────────────────────────────────────────────────────────────────
   6. Entity selection — right panel rendering
   ───────────────────────────────────────────────────────────────────────────── */

/** Select a type by FQN or ID. */
async function selectType(id) {
  setLoading();
  try {
    const data = await api.type(id);
    App.selected = { kind: 'type', id, data };
    renderTypeDetail(data);
    renderKnowledgeBaseForType(data);
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
  if (!App.graph) {
    const container = qs('#graph-view');
    const tooltip   = qs('#graph-tooltip');
    App.graph       = new window.ForceGraph(container, tooltip);

    App.graph.onNodeClick = async node => {
      if      (node.type === 'METHOD') await selectMethod(node.id);
      else if (node.type === 'FIELD')  await selectField(node.id);
    };
  }
}

/** Load and render the call hierarchy graph for a method. */
async function loadCallGraph(methodId) {
  ensureGraph();
  App.graph.clear();

  try {
    const view = await api.callGraph(methodId);

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
async function loadCallersGraph(methodId) {
  ensureGraph();
  App.graph.clear();
  try {
    const view = await api.callers(methodId);
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
async function loadCalleesGraph(methodId) {
  ensureGraph();
  App.graph.clear();
  try {
    const view = await api.callees(methodId);
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

/** Load and render the field impact graph. */
async function loadFieldImpact(fieldId) {
  ensureGraph();
  App.graph.clear();

  try {
    const impact = await api.fieldImpact(fieldId);

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

  let sourceElement = '—';
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
    ['Package',   type.packageFqn || '—'],
    ['Kind',      type.kind],
    ['Modifiers', type.modifiers || '—'],
    ['Source',    sourceElement],
    ['Lines',     type.startLine ? `${type.startLine}–${type.endLine} (${type.lineCount})` : '—'],
    ['Extends',   type.superClass || '—'],
    ['Implements', (type.interfaces || []).join(', ') || '—'],
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
          <div class="meta-val">${esc(gm.topAuthor || '—')}</div>
          <div class="meta-key">Churn</div>
          <div class="meta-val"><span style="color:${gm.commitCount > 10 ? 'var(--red)' : gm.commitCount > 3 ? 'var(--amber)' : 'var(--emerald)'}">${gm.commitCount > 10 ? 'High' : gm.commitCount > 3 ? 'Medium' : 'Low'}</span></div>
          <div class="meta-key">Last Edit</div>
          <div class="meta-val">${gm.lastModified ? formatDate(gm.lastModified * 1000) : '—'}</div>
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
  view.innerHTML = `<div class="kb-section-header">
    ${kindIcon(type.kind)} ${esc(type.simpleName)} — ${type.kind}
  </div>`;

  if (fields.length > 0) {
    view.innerHTML += `<div class="kb-section-header" style="font-size:10px;padding-left:16px">Fields</div>`;
    for (const f of fields) {
      const item = createElement('div', { class: 'kb-item fade-in' });
      item.innerHTML = `
        <span class="kb-item-icon">■</span>
        <div class="kb-item-body">
          <div class="kb-item-name">${esc(f.simpleName)}</div>
          <div class="kb-item-meta">${esc(f.fieldType || '')} · ${esc(f.modifiers || '')} · line ${f.startLine}</div>
        </div>
        <span class="kb-item-badge badge-enum">${esc(f.fieldType || '')}</span>`;
      item.addEventListener('click', () => selectField(f.id));
      view.appendChild(item);
    }
  }

  if (methods.length > 0) {
    view.innerHTML += `<div class="kb-section-header" style="font-size:10px;padding-left:16px">Methods</div>`;
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
            · line ${m.startLine}
          </div>
          <div class="kb-item-meta" style="color:var(--text-muted)">(${esc(paramStr)})</div>
        </div>
        <span class="kb-item-badge badge-iface">${esc(m.modifiers || '')}</span>`;
      item.addEventListener('click', () => selectMethod(m.id));
      view.appendChild(item);
    }
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

  let sourceElement = '—';
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
    ['Class',      shortFqn(method.declaringTypeFqn)],
    ['Returns',    method.returnType || 'void'],
    ['Modifiers',  method.modifiers || '—'],
    ['Source',     sourceElement],
    ['Parameters', paramStr || '(none)'],
    ['Lines',      method.startLine ? `${method.startLine}–${method.endLine}` : '—'],
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
          <div class="meta-val">${esc(gm.topAuthor || '—')}</div>
          <div class="meta-key">Churn</div>
          <div class="meta-val"><span style="color:${gm.commitCount > 10 ? 'var(--red)' : gm.commitCount > 3 ? 'var(--amber)' : 'var(--emerald)'}">${gm.commitCount > 10 ? 'High' : gm.commitCount > 3 ? 'Medium' : 'Low'}</span></div>
          <div class="meta-key">Last Edit</div>
          <div class="meta-val">${gm.lastModified ? formatDate(gm.lastModified * 1000) : '—'}</div>
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
    { label: '⬆ Callers', action: () => { switchTab('graph'); loadCallersGraph(method.id); } },
    { label: '⬇ Callees', action: () => { switchTab('graph'); loadCalleesGraph(method.id); } },
  ]));

  renderNotes(method.fqn, notes);
}

function renderFieldDetail(data) {
  const { field, notes = [] } = data;
  const body = qs('#right-body');
  body.innerHTML = '';

  renderEntityHeader('FIELD', field.simpleName, field.fqn);

  let sourceElement = '—';
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
    ['Type',        field.fieldType || '—'],
    ['Modifiers',   field.modifiers || '—'],
    ['Source',      sourceElement],
    ['Init value',  field.initializer || '—'],
    ['Source line', field.startLine || '—'],
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
          <div class="meta-val">${esc(gm.topAuthor || '—')}</div>
          <div class="meta-key">Churn</div>
          <div class="meta-val"><span style="color:${gm.commitCount > 10 ? 'var(--red)' : gm.commitCount > 3 ? 'var(--amber)' : 'var(--emerald)'}">${gm.commitCount > 10 ? 'High' : gm.commitCount > 3 ? 'Medium' : 'Low'}</span></div>
          <div class="meta-key">Last Edit</div>
          <div class="meta-val">${gm.lastModified ? formatDate(gm.lastModified * 1000) : '—'}</div>
        </div>`;
    }
  }).catch(() => {});


  body.appendChild(actionRow([
    { label: '⚡ Impact', action: () => { switchTab('graph'); loadFieldImpact(field.id); } },
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
  } catch (e) {
    console.warn('Stats load failed:', e);
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
    // Ctrl+K / Cmd+K → focus search
    if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
      e.preventDefault();
      qs('#search-input').focus();
      qs('#search-input').select();
    }
    // Escape → clear search
    if (e.key === 'Escape') {
      qs('#search-input').value = '';
      showExplorer();
      qs('#search-input').blur();
    }
    // Tab switching: 1/2/3 when not in input
    if (!['INPUT','TEXTAREA'].includes(e.target.tagName)) {
      if (e.key === '1') switchTab('graph');
      if (e.key === '2') switchTab('knowledge');
      if (e.key === '3') switchTab('inconsistency');
    }
  });
}

/* ─────────────────────────────────────────────────────────────────────────────
   9. Bootstrapping — runs on DOMContentLoaded
   ───────────────────────────────────────────────────────────────────────────── */

async function init() {
  // Wire up scan button and Enter key
  qs('#scan-btn').addEventListener('click', startScan);
  const browseBtn = qs('#browse-btn');
  if (browseBtn) {
    browseBtn.addEventListener('click', async () => {
      try {
        const current = qs('#scan-path-input').value.trim();
        const res = await api.browse(current);
        if (res && res.path) {
          qs('#scan-path-input').value = res.path;
        }
      } catch (e) {
        showError('Failed to open file chooser: ' + e.message);
      }
    });
  }
  qs('#scan-path-input').addEventListener('keydown', e => {
    if (e.key === 'Enter') startScan();
  });

  // Search input
  qs('#search-input').addEventListener('input', onSearchInput);

  // Filter chips
  qsa('.chip').forEach(chip => {
    chip.addEventListener('click', () => setFilter(chip.dataset.filter));
  });

  // Tab bar
  qsa('.tab').forEach(tab => {
    tab.addEventListener('click', () => {
      switchTab(tab.dataset.tab);
      if (tab.dataset.tab === 'inconsistency') loadInconsistencies();
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

  // Graph control buttons
  const fitBtn   = qs('#btn-fit');
  const resetBtn = qs('#btn-reset');
  const heatBtn  = qs('#btn-heat');
  if (fitBtn)   fitBtn.addEventListener('click', () => App.graph?.fitToScreen());
  if (resetBtn) resetBtn.addEventListener('click', () => App.graph?.clear());
  if (heatBtn) {
    heatBtn.addEventListener('click', () => {
      const isOn = App.graph?.toggleHeat();
      heatBtn.setAttribute('aria-pressed', isOn ? 'true' : 'false');
      heatBtn.classList.toggle('active', !!isOn);
    });
    // Pre-load heat data
    loadGitHeatData();
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

  // Pre-load inconsistencies badge count
  try {
    const issues = await api.inconsistencies();
    const badge  = qs('.tab[data-tab="inconsistency"] .tab-badge');
    if (badge) badge.textContent = issues.length;
  } catch (_) {}

  // Pre-load git branch metadata in the status footer
  updateFooterGitBranch();
}

/* ─────────────────────────────────────────────────────────────────────────────
   Git integration helpers
   ───────────────────────────────────────────────────────────────────────────── */

/**
 * Load git summary (top authors + hottest entities) and populate #git-view.
 * Called when the user clicks the Git tab.
 */
async function loadGitSummary() {
  const authorsList = qs('#git-authors-list');
  const hotList     = qs('#git-hot-list');
  if (!authorsList || !hotList) return;

  authorsList.innerHTML = '<div class="list-empty">Loading…</div>';
  hotList.innerHTML     = '<div class="list-empty">Loading…</div>';

  try {
    const summary = await api.gitSummary();
    // ── Top authors ───────────────────────────────────────────────────────────
    if (!summary.topAuthors || summary.topAuthors.length === 0) {
      authorsList.innerHTML = '<div class="list-empty">No git data found. Scan a git repository first.</div>';
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
      hotList.innerHTML = '<div class="list-empty">No churn data available.</div>';
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
/** Load heat data (entityFqn → commitCount) and register it with the graph. */
async function loadGitHeatData() {
  try {
    const summary = await api.gitSummary();
    if (!summary.hotEntities) return;
    const heatMap = {};
    for (const e of summary.hotEntities) {
      heatMap[e.entityFqn] = e.commitCount;
    }
    App.graph?.setHeatData(heatMap);
  } catch (_) { /* non-fatal */ }
}

function esc(str) {
  return String(str || '').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}

document.addEventListener('DOMContentLoaded', init);

/* ─────────────────────────────────────────────────────────────────────────────
   UI helpers — shared rendering primitives
   ───────────────────────────────────────────────────────────────────────────── */

/** Build the entity header in the right panel. */
function renderEntityHeader(kind, name, fqn) {
  const header = qs('#entity-header');
  if (!header) return;
  header.innerHTML = `
    <div class="entity-kind-badge ${kind}">${kind}</div>
    <div class="entity-name">${esc(name)}</div>
    <div class="entity-fqn">${esc(fqn)}</div>`;
  header.style.display = '';
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
      val.textContent = v || '—';
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
  el.textContent = '⚠ ' + msg;
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
  if (!fqn) return '—';
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

/** Map Java type kind to an emoji icon. */
function kindIcon(kind) {
  return { CLASS: '🔷', INTERFACE: '🔹', ENUM: '🔸', ANNOTATION: '🔖' }[kind] || '📄';
}
