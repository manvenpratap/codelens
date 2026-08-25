/**
 * city3d.js - Three.js 3D Software City ("CodeCity") Renderer for CodeLens
 *
 * Visual Metaphor:
 * - Package districts as elevated polygonal/grid platforms on an OLED black grid plane.
 * - Classes & Records as 3D skyscraper monoliths where:
 *     * Footprint base = Method/Field complexity
 *     * Height (Y-axis) = Total lines of code (LOC)
 *     * Material/Shader = Emerald/Mint/Amber theme palette with specular highlights.
 * - OrbitControls for pan, zoom, tilt, and raycasting for hover tooltips & click inspection.
 */

(function(window) {
  'use strict';

  class CodeCity3DRenderer {
    constructor(container) {
      this._container = container;
      this._el = null;
      this._scene = null;
      this._camera = null;
      this._renderer = null;
      this._controls = null;
      this._animId = null;
      this._data = null;
      this._buildings = [];
      this._districts = [];
      this._arcLines = [];
      this._callBeams = [];
      this._filterQuery = '';
      this._hiddenPackages = new Set();
      this._showWireframe = false;
      this._showArcs = true;
      this._autoRotate = false;
      this._raycaster = null;
      this._mouse = null;
      this._hoveredMesh = null;
      this._tooltip = null;
      this._toolbar = null;
      this._resizeObserver = null;
      this._onSelectEntity = null;
      this._targetCameraPos = null;
      this._targetControlsTarget = null;
    }

    onSelectEntity(callback) {
      this._onSelectEntity = callback;
    }

    toggleAutoRotate() {
      this._autoRotate = !this._autoRotate;
      if (this._controls) this._controls.autoRotate = this._autoRotate;
    }

    toggleArcs() {
      this._showArcs = !this._showArcs;
      this._arcLines.forEach(a => { a.visible = this._showArcs; });
    }

    flyToBuilding(buildingMesh) {
      if (!buildingMesh || !this._camera || !this._controls) return;
      const targetPos = buildingMesh.position.clone();
      this._targetControlsTarget = targetPos;
      this._targetCameraPos = new THREE.Vector3(
        targetPos.x + 80,
        targetPos.y + 60,
        targetPos.z + 80
      );
    }

    setData(payload, treeData) {
      this._data = payload;
      this._treeData = treeData || null;
      this._treeClassMetrics = new Map();
      if (this._treeData) {
        this._indexTreeMetrics(this._treeData);
      }
      this._initScene();
      this._buildCity();
    }

    setFilter(query) {
      this._filterQuery = (query || '').toLowerCase().trim();
      this._applyFilters();
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
      this._applyFilters();
    }

    toggleWireframe() {
      this._showWireframe = !this._showWireframe;
      this._buildings.forEach(b => {
        if (b.material) b.material.wireframe = this._showWireframe;
      });
    }

    _applyFilters() {
      this._buildings.forEach(b => {
        const d = b.userData;
        const name = (d.entity.label || d.entity.simpleName || d.entity.id || '').toLowerCase();
        const pkg = (d.pkg || '').toLowerCase();
        const matchesSearch = !this._filterQuery || name.includes(this._filterQuery) || pkg.includes(this._filterQuery);
        const matchesPkg = !this._hiddenPackages.has(d.pkg);
        const visible = matchesSearch && matchesPkg;

        b.visible = visible;
        if (b.userData.edgeLine) b.userData.edgeLine.visible = visible;
      });

      this._districts.forEach(dist => {
        const visible = !this._hiddenPackages.has(dist.userData.pkg);
        dist.visible = visible;
        if (dist.userData.edgeLine) dist.userData.edgeLine.visible = visible;
      });
    }

    _indexTreeMetrics(node) {
      if (!node) return;
      if (node.kind === 'CLASS' || node.kind === 'INTERFACE' || node.kind === 'ENUM' || node.kind === 'RECORD' || (node.children && node.children.some(c => c.kind === 'METHOD'))) {
        const methods = (node.children || []).filter(c => c.kind === 'METHOD');
        this._treeClassMetrics.set(node.fqn || node.name, {
          lineCount: node.lineCount || node.size || 0,
          methodCount: methods.length,
          kind: node.kind || 'CLASS'
        });
      }
      if (node.children) {
        node.children.forEach(c => this._indexTreeMetrics(c));
      }
    }

    _initScene() {
      this.destroy();

      this._el = document.createElement('div');
      this._el.className = 'city3d-container';
      this._el.style.width = '100%';
      this._el.style.height = '100%';
      this._el.style.position = 'relative';
      this._el.style.overflow = 'hidden';
      this._el.style.background = '#000000';
      this._container.appendChild(this._el);

      // Tooltip
      this._tooltip = document.createElement('div');
      this._tooltip.className = 'city3d-tooltip';
      this._tooltip.style.position = 'absolute';
      this._tooltip.style.display = 'none';
      this._tooltip.style.pointerEvents = 'none';
      this._tooltip.style.zIndex = '150';
      this._el.appendChild(this._tooltip);

      const width = this._el.clientWidth || 800;
      const height = this._el.clientHeight || 600;

      // Three.js Scene
      this._scene = new THREE.Scene();
      this._scene.background = new THREE.Color(0x05080f);
      this._scene.fog = new THREE.FogExp2(0x05080f, 0.0009);

      // Perspective Camera
      this._camera = new THREE.PerspectiveCamera(45, width / height, 1, 4000);
      this._camera.position.set(240, 280, 360);

      // WebGL Renderer
      this._renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
      this._renderer.setSize(width, height);
      this._renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
      this._renderer.shadowMap.enabled = true;
      this._renderer.shadowMap.type = THREE.PCFSoftShadowMap;
      this._renderer.toneMapping = THREE.ACESFilmicToneMapping;
      this._renderer.toneMappingExposure = 1.35;
      this._el.appendChild(this._renderer.domElement);

      // OrbitControls
      if (THREE.OrbitControls) {
        this._controls = new THREE.OrbitControls(this._camera, this._renderer.domElement);
        this._controls.enableDamping = true;
        this._controls.dampingFactor = 0.05;
        this._controls.maxPolarAngle = Math.PI / 2 - 0.02; // Prevent camera under-floor
        this._controls.minDistance = 20;
        this._controls.maxDistance = 1800;
      }

      // Lights
      this._ambientLight = new THREE.AmbientLight(0xffffff, 0.95);
      this._scene.add(this._ambientLight);

      this._dirLight = new THREE.DirectionalLight(0xffffff, 1.1);
      this._dirLight.position.set(250, 450, 250);
      this._dirLight.castShadow = true;
      this._dirLight.shadow.mapSize.width = 2048;
      this._dirLight.shadow.mapSize.height = 2048;
      this._scene.add(this._dirLight);

      this._dirLight2 = new THREE.DirectionalLight(0x34d399, 0.65);
      this._dirLight2.position.set(-250, 350, -250);
      this._scene.add(this._dirLight2);

      this._topLight = new THREE.HemisphereLight(0xffffff, 0x1e293b, 0.7);
      this._scene.add(this._topLight);

      // Grid Floor
      const gridHelper = new THREE.GridHelper(1400, 70, 0x10b981, 0x334155);
      gridHelper.position.y = -0.1;
      this._scene.add(gridHelper);

      // Raycaster for interactions
      this._raycaster = new THREE.Raycaster();
      this._mouse = new THREE.Vector2();

      this._el.addEventListener('mousemove', this._onMouseMove.bind(this));
      this._el.addEventListener('click', this._onClick.bind(this));

      // Resize observer
      this._resizeObserver = new ResizeObserver(() => {
        if (!this._el || !this._renderer || !this._camera) return;
        const w = this._el.clientWidth;
        const h = this._el.clientHeight;
        if (w === 0 || h === 0) return;
        this._camera.aspect = w / h;
        this._camera.updateProjectionMatrix();
        this._renderer.setSize(w, h);
      });
      this._resizeObserver.observe(this._el);

      this._animate();
    }

    _buildCity() {
      if (!this._data || !this._scene) return;

      const classes = this._data.nodes || [];
      if (classes.length === 0) return;

      // Group classes by package
      const pkgMap = new Map();
      classes.forEach(c => {
        const pkg = c.package || c.id.split('.').slice(0, -1).join('.') || 'default';
        if (!pkgMap.has(pkg)) pkgMap.set(pkg, []);
        pkgMap.get(pkg).push(c);
      });

      const pkgs = Array.from(pkgMap.keys());
      const gridCols = Math.ceil(Math.sqrt(pkgs.length));
      const districtSpacing = 160;

      this._buildings = [];

      pkgs.forEach((pkgName, pIndex) => {
        const pCol = pIndex % gridCols;
        const pRow = Math.floor(pIndex / gridCols);
        const districtX = (pCol - gridCols / 2 + 0.5) * districtSpacing;
        const districtZ = (pRow - gridCols / 2 + 0.5) * districtSpacing;

        const pkgClasses = pkgMap.get(pkgName);
        const bCols = Math.ceil(Math.sqrt(pkgClasses.length));
        const bSpacing = 28;
        const districtW = Math.max(bCols * bSpacing + 20, 80);
        const districtH = Math.max(Math.ceil(pkgClasses.length / bCols) * bSpacing + 20, 80);

        const pkgColorStr = (window.CodeLensPalette && window.CodeLensPalette.getColor)
          ? window.CodeLensPalette.getColor(pkgName, pIndex)
          : '#10b981';
        const pkgColorHex = parseInt(pkgColorStr.replace('#', ''), 16) || 0x10b981;

        // District Base Platform
        const platformGeo = new THREE.BoxGeometry(districtW, 3, districtH);
        const platformMat = new THREE.MeshStandardMaterial({
          color: 0x0a0d14,
          roughness: 0.8,
          metalness: 0.2,
        });
        const platform = new THREE.Mesh(platformGeo, platformMat);
        platform.position.set(districtX, 1.5, districtZ);
        platform.receiveShadow = true;
        this._scene.add(platform);

        // District Platform Border Edge
        const edges = new THREE.EdgesGeometry(platformGeo);
        const distLine = new THREE.LineSegments(edges, new THREE.LineBasicMaterial({ color: pkgColorHex, transparent: true, opacity: 0.55 }));
        distLine.position.copy(platform.position);
        this._scene.add(distLine);

        platform.userData = { pkg: pkgName, edgeLine: distLine };
        this._districts.push(platform);

        // Build Skyscrapers inside district
        pkgClasses.forEach((cls, cIndex) => {
          const cCol = cIndex % bCols;
          const cRow = Math.floor(cIndex / bCols);
          const bx = districtX - districtW / 2 + (cCol + 1) * (districtW / (bCols + 1));
          const bz = districtZ - districtH / 2 + (cRow + 1) * (districtH / (Math.ceil(pkgClasses.length / bCols) + 1));

          const metrics = this._treeClassMetrics.get(cls.id || cls.label || cls.simpleName) || {};
          const loc = metrics.lineCount || cls.lineCount || cls.size || 60;
          const methodCount = metrics.methodCount !== undefined ? metrics.methodCount : (cls.methods ? cls.methods.length : 'N/A');

          const height = Math.max(14, Math.min(260, Math.log2(loc + 1) * 24));
          const width = 14;
          const depth = 14;

          const buildingGeo = new THREE.BoxGeometry(width, height, depth);
          
          const colorHex = pkgColorHex;

          const buildingMat = new THREE.MeshStandardMaterial({
            color: colorHex,
            roughness: 0.15,
            metalness: 0.55,
            emissive: colorHex,
            emissiveIntensity: 0.42,
            wireframe: this._showWireframe,
          });

          const mesh = new THREE.Mesh(buildingGeo, buildingMat);
          mesh.position.set(bx, 3 + height / 2, bz);
          mesh.castShadow = true;
          mesh.receiveShadow = true;

          // Skyscraper Edge Wireframe
          const bEdges = new THREE.EdgesGeometry(buildingGeo);
          const bLine = new THREE.LineSegments(bEdges, new THREE.LineBasicMaterial({ color: 0xffffff, transparent: true, opacity: 0.75 }));
          bLine.position.copy(mesh.position);
          this._scene.add(bLine);

          mesh.userData = {
            entity: cls,
            origColor: colorHex,
            height: height,
            colorStr: pkgColorStr,
            pkg: pkgName,
            loc: loc,
            methodCount: methodCount,
            edgeLine: bLine
          };

          this._scene.add(mesh);
          this._buildings.push(mesh);
        });
      });

      // Build Inter-Class Call Arcs across the Skyline
      this._buildSkylineCallArcs();
      this._buildOverlayControls(pkgs);
    }

    _buildSkylineCallArcs() {
      const edges = this._data.edges || [];
      if (edges.length === 0) return;

      const buildingMap = new Map();
      this._buildings.forEach(b => {
        const id = b.userData.entity.id || b.userData.entity.label || b.userData.entity.simpleName;
        if (id) buildingMap.set(id, b);
      });

      this._arcLines = [];
      this._callBeams = [];

      edges.forEach(e => {
        const srcMesh = buildingMap.get(e.source || e.caller);
        const tgtMesh = buildingMap.get(e.target || e.callee);
        if (!srcMesh || !tgtMesh || srcMesh === tgtMesh) return;

        const p1 = srcMesh.position.clone();
        p1.y += srcMesh.userData.height / 2; // Spire peak

        const p2 = tgtMesh.position.clone();
        p2.y += tgtMesh.userData.height / 2;

        const dist = p1.distanceTo(p2);
        const mid = new THREE.Vector3().addVectors(p1, p2).multiplyScalar(0.5);
        mid.y += Math.max(30, Math.min(180, dist * 0.45)); // Parabolic arch

        const curve = new THREE.QuadraticBezierCurve3(p1, mid, p2);
        const points = curve.getPoints(24);
        const geo = new THREE.BufferGeometry().setFromPoints(points);

        const arcMat = new THREE.LineBasicMaterial({
          color: srcMesh.userData.origColor || 0x10b981,
          transparent: true,
          opacity: 0.35,
        });

        const arcLine = new THREE.Line(geo, arcMat);
        this._scene.add(arcLine);
        this._arcLines.push(arcLine);

        this._callBeams.push({
          curve: curve,
          progress: Math.random(),
          speed: 0.003 + Math.random() * 0.004,
        });
      });

      // Laser energy particles mesh
      if (this._callBeams.length > 0) {
        const beamGeo = new THREE.BufferGeometry();
        const beamPositions = new Float32Array(this._callBeams.length * 3);
        beamGeo.setAttribute('position', new THREE.BufferAttribute(beamPositions, 3));
        const beamMat = new THREE.PointsMaterial({ color: 0x38bdf8, size: 3.5, transparent: true, opacity: 0.9 });
        this._beamGroup = new THREE.Points(beamGeo, beamMat);
        this._scene.add(this._beamGroup);
      }
    }

    _buildOverlayControls(pkgs = []) {
      if (this._toolbar) this._toolbar.remove();

      this._toolbar = document.createElement('div');
      this._toolbar.className = 'city3d-hud-overlay';
      this._toolbar.style.position = 'absolute';
      this._toolbar.style.top = '14px';
      this._toolbar.style.right = '14px';
      this._toolbar.style.display = 'flex';
      this._toolbar.style.alignItems = 'center';
      this._toolbar.style.gap = '8px';
      this._toolbar.style.zIndex = '120';

      // Search input filter
      const searchBox = document.createElement('input');
      searchBox.type = 'text';
      searchBox.placeholder = 'Filter buildings...';
      searchBox.className = 'city3d-search-box';
      searchBox.value = this._filterQuery;
      searchBox.style.background = 'rgba(10, 13, 18, 0.9)';
      searchBox.style.border = '1px solid rgba(255, 255, 255, 0.15)';
      searchBox.style.color = '#f8fafc';
      searchBox.style.borderRadius = '6px';
      searchBox.style.padding = '4px 10px';
      searchBox.style.fontSize = '11px';
      searchBox.style.width = '130px';
      searchBox.style.outline = 'none';
      searchBox.addEventListener('input', (e) => {
        this.setFilter(e.target.value);
      });
      this._toolbar.appendChild(searchBox);

      // Skyline Arcs toggle
      const arcsBtn = document.createElement('button');
      arcsBtn.className = 'hud-btn' + (this._showArcs ? ' active' : '');
      arcsBtn.innerHTML = '<span class="hud-btn-icon">⌒</span> <span class="hud-btn-text">Call Arcs</span>';
      arcsBtn.style.background = '#0a0d12';
      arcsBtn.style.border = '1px solid ' + (this._showArcs ? '#10b981' : 'rgba(255,255,255,0.15)');
      arcsBtn.style.color = this._showArcs ? '#f8fafc' : '#94a3b8';
      arcsBtn.style.borderRadius = '6px';
      arcsBtn.style.padding = '5px 10px';
      arcsBtn.style.fontSize = '11px';
      arcsBtn.style.cursor = 'pointer';
      arcsBtn.addEventListener('click', () => {
        this.toggleArcs();
        arcsBtn.classList.toggle('active', this._showArcs);
        arcsBtn.style.borderColor = this._showArcs ? '#10b981' : 'rgba(255,255,255,0.15)';
        arcsBtn.style.color = this._showArcs ? '#f8fafc' : '#94a3b8';
      });
      this._toolbar.appendChild(arcsBtn);

      // Auto Orbit toggle
      const orbitBtn = document.createElement('button');
      orbitBtn.className = 'hud-btn' + (this._autoRotate ? ' active' : '');
      orbitBtn.innerHTML = '<span class="hud-btn-icon">⟳</span> <span class="hud-btn-text">Orbit</span>';
      orbitBtn.style.background = '#0a0d12';
      orbitBtn.style.border = '1px solid ' + (this._autoRotate ? '#10b981' : 'rgba(255,255,255,0.15)');
      orbitBtn.style.color = this._autoRotate ? '#f8fafc' : '#94a3b8';
      orbitBtn.style.borderRadius = '6px';
      orbitBtn.style.padding = '5px 10px';
      orbitBtn.style.fontSize = '11px';
      orbitBtn.style.cursor = 'pointer';
      orbitBtn.addEventListener('click', () => {
        this.toggleAutoRotate();
        orbitBtn.classList.toggle('active', this._autoRotate);
        orbitBtn.style.borderColor = this._autoRotate ? '#10b981' : 'rgba(255,255,255,0.15)';
        orbitBtn.style.color = this._autoRotate ? '#f8fafc' : '#94a3b8';
      });
      this._toolbar.appendChild(orbitBtn);

      // Wireframe toggle
      const wireBtn = document.createElement('button');
      wireBtn.className = 'hud-btn' + (this._showWireframe ? ' active' : '');
      wireBtn.innerHTML = '<span class="hud-btn-icon">⬡</span> <span class="hud-btn-text">Wireframe</span>';
      wireBtn.style.background = '#0a0d12';
      wireBtn.style.border = '1px solid ' + (this._showWireframe ? '#10b981' : 'rgba(255,255,255,0.15)');
      wireBtn.style.color = this._showWireframe ? '#f8fafc' : '#94a3b8';
      wireBtn.style.borderRadius = '6px';
      wireBtn.style.padding = '5px 10px';
      wireBtn.style.fontSize = '11px';
      wireBtn.style.cursor = 'pointer';
      wireBtn.addEventListener('click', () => {
        this.toggleWireframe();
        wireBtn.classList.toggle('active', this._showWireframe);
        wireBtn.style.borderColor = this._showWireframe ? '#10b981' : 'rgba(255,255,255,0.15)';
        wireBtn.style.color = this._showWireframe ? '#f8fafc' : '#94a3b8';
      });
      this._toolbar.appendChild(wireBtn);

      this._el.appendChild(this._toolbar);
    }

    _onMouseMove(event) {
      if (!this._el || !this._camera) return;
      const rect = this._el.getBoundingClientRect();
      this._mouse.x = ((event.clientX - rect.left) / rect.width) * 2 - 1;
      this._mouse.y = -((event.clientY - rect.top) / rect.height) * 2 + 1;

      this._raycaster.setFromCamera(this._mouse, this._camera);
      const intersects = this._raycaster.intersectObjects(this._buildings);

      if (intersects.length > 0) {
        const hit = intersects[0].object;
        if (this._hoveredMesh !== hit) {
          this._resetHover();
          this._hoveredMesh = hit;
          hit.material.emissiveIntensity = 0.55;
          this._el.style.cursor = 'pointer';
        }

        const data = hit.userData.entity;
        const col = hit.userData.colorStr || '#34d399';
        const locVal = hit.userData.loc || 'N/A';
        const mVal = hit.userData.methodCount !== undefined ? hit.userData.methodCount : 'N/A';

        this._tooltip.innerHTML = `
          <div class="tt-inner" style="background:#0a0d12; border:1px solid ${col}; border-radius:6px; padding:8px 12px; box-shadow:0 8px 24px rgba(0,0,0,0.8);">
            <div style="font-size:12px; font-weight:700; color:#f8fafc; font-family:Sora,sans-serif;">${data.label || data.simpleName || data.id}</div>
            <div style="font-size:11px; color:#94a3b8; font-family:JetBrains Mono,monospace; margin-top:2px;">${data.package || hit.userData.pkg || data.id}</div>
            <div style="font-size:11px; color:${col}; font-family:JetBrains Mono,monospace; margin-top:4px;">LOC: ${locVal} • Methods: ${mVal}</div>
          </div>
        `;
        this._tooltip.style.left = `${event.clientX - rect.left + 14}px`;
        this._tooltip.style.top = `${event.clientY - rect.top + 14}px`;
        this._tooltip.style.display = 'block';
      } else {
        this._resetHover();
        this._tooltip.style.display = 'none';
        this._el.style.cursor = 'default';
      }
    }

    _resetHover() {
      if (this._hoveredMesh) {
        this._hoveredMesh.material.emissiveIntensity = 0.12;
        this._hoveredMesh = null;
      }
    }

    _onClick(event) {
      if (!this._hoveredMesh) return;
      const entity = this._hoveredMesh.userData.entity;
      this.flyToBuilding(this._hoveredMesh);

      if (entity && this._onSelectEntity) {
        this._onSelectEntity(entity.id || entity.fqn);
      } else if (entity && window.selectEntity) {
        window.selectEntity(entity.id || entity.fqn);
      }
    }

    _animate() {
      this._animId = requestAnimationFrame(this._animate.bind(this));

      // Animate laser energy call beams along arcs
      if (this._beamGroup && this._callBeams.length > 0) {
        const positions = this._beamGroup.geometry.attributes.position.array;
        this._callBeams.forEach((b, idx) => {
          b.progress = (b.progress + b.speed) % 1;
          const pos = b.curve.getPointAt(b.progress);
          positions[idx * 3] = pos.x;
          positions[idx * 3 + 1] = pos.y;
          positions[idx * 3 + 2] = pos.z;
        });
        this._beamGroup.geometry.attributes.position.needsUpdate = true;
      }

      // Smooth camera fly-to interpolation
      if (this._targetCameraPos && this._camera) {
        this._camera.position.lerp(this._targetCameraPos, 0.05);
        if (this._camera.position.distanceTo(this._targetCameraPos) < 1) {
          this._targetCameraPos = null;
        }
      }
      if (this._targetControlsTarget && this._controls) {
        this._controls.target.lerp(this._targetControlsTarget, 0.05);
        if (this._controls.target.distanceTo(this._targetControlsTarget) < 1) {
          this._targetControlsTarget = null;
        }
      }

      if (this._controls) this._controls.update();
      if (this._renderer && this._scene && this._camera) {
        this._renderer.render(this._scene, this._camera);
      }
    }

    setBrightness(val) {
      // val in range [0.2, 2.5], 1.0 is default
      const factor = Math.max(0.2, Math.min(2.5, val));
      if (this._renderer) {
        this._renderer.toneMappingExposure = 1.0 * factor;
      }
      if (this._ambientLight) this._ambientLight.intensity = 0.95 * factor;
      if (this._dirLight) this._dirLight.intensity = 1.1 * factor;
      if (this._dirLight2) this._dirLight2.intensity = 0.65 * factor;
      if (this._topLight) this._topLight.intensity = 0.7 * factor;
    }

    destroy() {
      if (this._animId) {
        cancelAnimationFrame(this._animId);
        this._animId = null;
      }
      if (this._resizeObserver) {
        this._resizeObserver.disconnect();
        this._resizeObserver = null;
      }
      if (this._controls) {
        this._controls.dispose();
        this._controls = null;
      }
      if (this._renderer) {
        this._renderer.dispose();
        this._renderer = null;
      }
      if (this._el) {
        this._el.remove();
        this._el = null;
      }
      this._scene = null;
      this._camera = null;
      this._ambientLight = null;
      this._dirLight = null;
      this._dirLight2 = null;
      this._topLight = null;
      this._buildings = [];
    }
  }

  window.CodeCity3DRenderer = CodeCity3DRenderer;
})(window);
