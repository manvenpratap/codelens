/**
 * galaxy3d.js - Three.js 3D Force Galaxy ("Orbital Call Graph") Renderer for CodeLens
 *
 * Features:
 * - 3D Force Simulation ($X, Y, Z$ repulsion and spring tension).
 * - 3D glowing sphere nodes with IDE kind glyphs & LOD decluttering.
 * - 3D directed quadratic bezier call arcs with animated particle pulses.
 * - OrbitControls camera with raycasting hover highlights and click selection.
 */

(function(window) {
  'use strict';

  class Galaxy3DRenderer {
    constructor(container) {
      this._container = container;
      this._el = null;
      this._scene = null;
      this._camera = null;
      this._renderer = null;
      this._controls = null;
      this._animId = null;
      this._data = null;
      this._nodes = [];
      this._edges = [];
      this._nodeMeshes = [];
      this._edgeLines = [];
      this._planeMeshes = [];
      this._showPlanes = true;
      this._autoRotate = false;
      this._filterQuery = '';
      this._hiddenPackages = new Set();
      this._particleGroup = null;
      this._particles = [];
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

    toggleAutoRotate() {
      this._autoRotate = !this._autoRotate;
      if (this._controls) this._controls.autoRotate = this._autoRotate;
    }

    flyToNode(nodeMesh) {
      if (!nodeMesh || !this._camera || !this._controls) return;
      const targetPos = nodeMesh.position.clone();
      this._targetControlsTarget = targetPos;
      this._targetCameraPos = new THREE.Vector3(
        targetPos.x + 60,
        targetPos.y + 40,
        targetPos.z + 70
      );
    }

    togglePlanes(visible) {
      if (visible !== undefined) this._showPlanes = visible;
      else this._showPlanes = !this._showPlanes;
      this._planeMeshes.forEach(p => { p.visible = this._showPlanes; });
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

    _applyFilters() {
      const visibleNodeIds = new Set();
      this._nodeMeshes.forEach(m => {
        const d = m.userData;
        const name = (d.node.raw.label || d.node.raw.simpleName || d.node.id || '').toLowerCase();
        const pkg = (d.pkg || '').toLowerCase();
        const matchesSearch = !this._filterQuery || name.includes(this._filterQuery) || pkg.includes(this._filterQuery);
        const matchesPkg = !this._hiddenPackages.has(d.pkg);
        const visible = matchesSearch && matchesPkg;

        m.visible = visible;
        if (visible) visibleNodeIds.add(d.node.id);
      });

      this._edgeLines.forEach(l => {
        if (l.userData && l.userData.src && l.userData.tgt) {
          l.visible = visibleNodeIds.has(l.userData.src) && visibleNodeIds.has(l.userData.tgt);
        }
      });

      this._planeMeshes.forEach(p => {
        const pkgVisible = !p.userData || !this._hiddenPackages.has(p.userData.pkg);
        p.visible = this._showPlanes && pkgVisible;
      });
    }

    onSelectEntity(callback) {
      this._onSelectEntity = callback;
    }

    setData(payload) {
      this._data = payload;
      this._initScene();
      this._buildGalaxy();
    }

    _initScene() {
      this.destroy();

      this._el = document.createElement('div');
      this._el.className = 'galaxy3d-container';
      this._el.style.width = '100%';
      this._el.style.height = '100%';
      this._el.style.position = 'relative';
      this._el.style.overflow = 'hidden';
      this._el.style.background = '#000000';
      this._container.appendChild(this._el);

      // Tooltip
      this._tooltip = document.createElement('div');
      this._tooltip.className = 'galaxy3d-tooltip';
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

      // Camera
      this._camera = new THREE.PerspectiveCamera(45, width / height, 1, 3000);
      this._camera.position.set(0, 160, 420);

      // Renderer
      this._renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
      this._renderer.setSize(width, height);
      this._renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
      this._renderer.toneMapping = THREE.ACESFilmicToneMapping;
      this._renderer.toneMappingExposure = 1.4;
      this._el.appendChild(this._renderer.domElement);

      // Controls
      if (THREE.OrbitControls) {
        this._controls = new THREE.OrbitControls(this._camera, this._renderer.domElement);
        this._controls.enableDamping = true;
        this._controls.dampingFactor = 0.05;
        this._controls.minDistance = 30;
        this._controls.maxDistance = 1500;
      }

      // Lights - High Brightness Rig
      const ambientLight = new THREE.AmbientLight(0xffffff, 1.4);
      this._scene.add(ambientLight);

      const hemiLight = new THREE.HemisphereLight(0xffffff, 0x1e293b, 1.1);
      this._scene.add(hemiLight);

      const pointLight = new THREE.PointLight(0xffffff, 1.8, 1200);
      pointLight.position.set(0, 80, 0);
      this._scene.add(pointLight);

      const dirLight = new THREE.DirectionalLight(0x34d399, 1.0);
      dirLight.position.set(150, 200, 150);
      this._scene.add(dirLight);

      // Stars background particle field
      const starGeo = new THREE.BufferGeometry();
      const starCount = 600;
      const starPos = new Float32Array(starCount * 3);
      for (let i = 0; i < starCount * 3; i += 3) {
        starPos[i] = (Math.random() - 0.5) * 1400;
        starPos[i + 1] = (Math.random() - 0.5) * 1400;
        starPos[i + 2] = (Math.random() - 0.5) * 1400;
      }
      starGeo.setAttribute('position', new THREE.BufferAttribute(starPos, 3));
      const starMat = new THREE.PointsMaterial({ color: 0x475569, size: 1.5, transparent: true, opacity: 0.6 });
      const stars = new THREE.Points(starGeo, starMat);
      this._scene.add(stars);

      // Raycaster
      this._raycaster = new THREE.Raycaster();
      this._mouse = new THREE.Vector2();

      this._el.addEventListener('mousemove', this._onMouseMove.bind(this));
      this._el.addEventListener('click', this._onClick.bind(this));

      // Resize
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

    _buildGalaxy() {
      if (!this._data || !this._scene) return;

      const rawNodes = this._data.nodes || [];
      const rawEdges = this._data.edges || [];
      if (rawNodes.length === 0) return;

      this._nodeMeshes = [];
      this._edgeLines = [];
      this._particles = [];

      const nodeIndexMap = new Map();

      // Group nodes by package/module to form clean orbital star systems
      const pkgMap = new Map();
      rawNodes.forEach(n => {
        const pkg = n.package || (n.id.includes('.') ? n.id.split('.').slice(0, -1).join('.') : 'default');
        if (!pkgMap.has(pkg)) pkgMap.set(pkg, []);
        pkgMap.get(pkg).push(n);
      });

      const pkgs = Array.from(pkgMap.keys());
      const totalPkgs = pkgs.length;

      // Position each package cluster around the galactic core
      this._nodes = [];
      this._planeMeshes = [];

      pkgs.forEach((pkgName, pIdx) => {
        const pkgNodes = pkgMap.get(pkgName);
        const colorStr = (window.CodeLensPalette && window.CodeLensPalette.getColor)
          ? window.CodeLensPalette.getColor(pkgName, pIdx)
          : '#34d399';
        const colorHex = parseInt(colorStr.replace('#', ''), 16) || 0x34d399;

        // Galactic orbit angle for package center
        const pPhi = Math.acos(-1 + (2 * pIdx) / Math.max(1, totalPkgs));
        const pTheta = Math.sqrt(totalPkgs * Math.PI) * pPhi;
        const systemRadius = totalPkgs > 1 ? (140 + (pIdx % 3) * 45) : 0;

        const centerX = totalPkgs > 1 ? systemRadius * Math.cos(pTheta) * Math.sin(pPhi) : 0;
        const centerY = totalPkgs > 1 ? systemRadius * Math.sin(pTheta) * Math.sin(pPhi) * 0.5 : 0; // slightly flattened
        const centerZ = totalPkgs > 1 ? systemRadius * Math.cos(pPhi) : 0;

        // Define a clean local orbital plane for this package system
        const planeNormal = totalPkgs > 1 
          ? new THREE.Vector3(centerX, centerY, centerZ).normalize() 
          : new THREE.Vector3(0, 1, 0);

        // Find tangent vectors for circular plane
        const up = Math.abs(planeNormal.y) < 0.9 ? new THREE.Vector3(0, 1, 0) : new THREE.Vector3(1, 0, 0);
        const tangentU = new THREE.Vector3().crossVectors(planeNormal, up).normalize();
        const tangentV = new THREE.Vector3().crossVectors(planeNormal, tangentU).normalize();

        const count = pkgNodes.length;
        const localRadiusStep = Math.max(16, 80 / Math.max(1, Math.sqrt(count)));
        let maxClusterRadius = 25;

        pkgNodes.forEach((n, nIdx) => {
          // Arrange nodes in concentric rings on the package's local plane
          const ringIdx = Math.floor(Math.sqrt(nIdx));
          const itemsInRing = Math.max(1, ringIdx * 4);
          const posInRing = nIdx - ringIdx * ringIdx;
          const angle = (posInRing / itemsInRing) * Math.PI * 2 + (ringIdx * 0.5);
          const r = 18 + ringIdx * 24;
          if (r > maxClusterRadius) maxClusterRadius = r;

          const px = centerX + tangentU.x * r * Math.cos(angle) + tangentV.x * r * Math.sin(angle);
          const py = centerY + tangentU.y * r * Math.cos(angle) + tangentV.y * r * Math.sin(angle);
          const pz = centerZ + tangentU.z * r * Math.cos(angle) + tangentV.z * r * Math.sin(angle);

          const nodeObj = {
            id: n.id,
            label: n.label || n.id.split('.').pop(),
            raw: n,
            x: px, y: py, z: pz,
            pkg: pkgName,
            colorStr: colorStr,
            colorHex: colorHex
          };
          this._nodes.push(nodeObj);
          nodeIndexMap.set(n.id, nodeObj);
        });

        // 3D Orbital Shaded Disk / Plane for the package
        const diskRadius = maxClusterRadius + 16;
        const diskGeo = new THREE.CylinderGeometry(diskRadius, diskRadius, 1.5, 36);
        const diskMat = new THREE.MeshStandardMaterial({
          color: colorHex,
          transparent: true,
          opacity: 0.14,
          roughness: 0.8,
          metalness: 0.2,
          side: THREE.DoubleSide,
        });

        const planeMesh = new THREE.Mesh(diskGeo, diskMat);
        planeMesh.position.set(centerX, centerY, centerZ);
        // Align cylinder axis with plane normal
        const defaultNormal = new THREE.Vector3(0, 1, 0);
        planeMesh.quaternion.setFromUnitVectors(defaultNormal, planeNormal);
        planeMesh.userData = { pkg: pkgName };
        this._scene.add(planeMesh);
        this._planeMeshes.push(planeMesh);

        // Outer glowing orbital ring
        const ringGeo = new THREE.RingGeometry(diskRadius - 1.2, diskRadius, 48);
        const ringMat = new THREE.MeshBasicMaterial({
          color: colorHex,
          transparent: true,
          opacity: 0.55,
          side: THREE.DoubleSide
        });
        const ringMesh = new THREE.Mesh(ringGeo, ringMat);
        ringMesh.position.set(centerX, centerY, centerZ);
        ringMesh.quaternion.setFromUnitVectors(new THREE.Vector3(0, 0, 1), planeNormal);
        ringMesh.userData = { pkg: pkgName };
        this._scene.add(ringMesh);
        this._planeMeshes.push(ringMesh);
      });

      // Sphere Geometry for Nodes
      const sphereGeo = new THREE.SphereGeometry(6.5, 18, 18);

      this._nodes.forEach((n) => {
        const mat = new THREE.MeshStandardMaterial({
          color: n.colorHex,
          emissive: n.colorHex,
          emissiveIntensity: 0.65,
          roughness: 0.15,
          metalness: 0.45,
        });

        const mesh = new THREE.Mesh(sphereGeo, mat);
        mesh.position.set(n.x, n.y, n.z);
        mesh.userData = { node: n, origColor: n.colorHex, colorStr: n.colorStr, pkg: n.pkg };

        this._scene.add(mesh);
        this._nodeMeshes.push(mesh);
      });

      // Edge 3D Beziers
      rawEdges.forEach(e => {
        const src = nodeIndexMap.get(e.source || e.caller);
        const tgt = nodeIndexMap.get(e.target || e.callee);
        if (!src || !tgt) return;

        const p1 = new THREE.Vector3(src.x, src.y, src.z);
        const p2 = new THREE.Vector3(tgt.x, tgt.y, tgt.z);
        const dist = p1.distanceTo(p2);
        const mid = new THREE.Vector3().addVectors(p1, p2).multiplyScalar(0.5);
        mid.add(new THREE.Vector3((Math.random() - 0.5) * 15, Math.max(15, dist * 0.25), (Math.random() - 0.5) * 15));

        const curve = new THREE.QuadraticBezierCurve3(p1, mid, p2);
        const points = curve.getPoints(24);
        const geo = new THREE.BufferGeometry().setFromPoints(points);
        const lineMat = new THREE.LineBasicMaterial({ color: src.colorHex, transparent: true, opacity: 0.45 });
        const line = new THREE.Line(geo, lineMat);
        line.userData = { src: src.id, tgt: tgt.id };

        this._scene.add(line);
        this._edgeLines.push(line);

        // Add energy pulse particle along this curve
        this._particles.push({
          curve: curve,
          progress: Math.random(),
          speed: 0.003 + Math.random() * 0.004,
        });
      });

      // Flow Particles Mesh
      const particleGeo = new THREE.BufferGeometry();
      const pPositions = new Float32Array(this._particles.length * 3);
      particleGeo.setAttribute('position', new THREE.BufferAttribute(pPositions, 3));
      const pMat = new THREE.PointsMaterial({ color: 0x6ee7b7, size: 3.5, transparent: true, opacity: 0.9 });
      this._particleGroup = new THREE.Points(particleGeo, pMat);
      this._scene.add(this._particleGroup);

      // Create Floating Quick Controls Overlay inside 3D Galaxy
      this._buildOverlayControls();
    }

    _buildOverlayControls() {
      if (this._toolbar) this._toolbar.remove();

      this._toolbar = document.createElement('div');
      this._toolbar.className = 'galaxy3d-hud-overlay';
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
      searchBox.placeholder = 'Filter nodes...';
      searchBox.className = 'galaxy3d-search-box';
      searchBox.value = this._filterQuery;
      searchBox.style.background = 'rgba(10, 13, 18, 0.9)';
      searchBox.style.border = '1px solid rgba(255, 255, 255, 0.15)';
      searchBox.style.color = '#f8fafc';
      searchBox.style.borderRadius = '6px';
      searchBox.style.padding = '4px 10px';
      searchBox.style.fontSize = '11px';
      searchBox.style.width = '140px';
      searchBox.style.outline = 'none';
      searchBox.addEventListener('input', (e) => {
        this.setFilter(e.target.value);
      });
      this._toolbar.appendChild(searchBox);

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

      const toggleBtn = document.createElement('button');
      toggleBtn.className = 'hud-btn active';
      toggleBtn.innerHTML = '<span class="hud-btn-icon">◈</span> <span class="hud-btn-text">Cluster Planes</span>';
      toggleBtn.style.background = '#0a0d12';
      toggleBtn.style.border = '1px solid #10b981';
      toggleBtn.style.color = '#f8fafc';
      toggleBtn.style.borderRadius = '6px';
      toggleBtn.style.padding = '5px 10px';
      toggleBtn.style.fontSize = '11px';
      toggleBtn.style.cursor = 'pointer';

      toggleBtn.addEventListener('click', () => {
        this.togglePlanes();
        toggleBtn.classList.toggle('active', this._showPlanes);
        toggleBtn.style.borderColor = this._showPlanes ? '#10b981' : 'rgba(255,255,255,0.15)';
        toggleBtn.style.color = this._showPlanes ? '#f8fafc' : '#94a3b8';
      });

      this._toolbar.appendChild(toggleBtn);
      this._el.appendChild(this._toolbar);
    }

    _onMouseMove(event) {
      if (!this._el || !this._camera) return;
      const rect = this._el.getBoundingClientRect();
      this._mouse.x = ((event.clientX - rect.left) / rect.width) * 2 - 1;
      this._mouse.y = -((event.clientY - rect.top) / rect.height) * 2 + 1;

      this._raycaster.setFromCamera(this._mouse, this._camera);
      const intersects = this._raycaster.intersectObjects(this._nodeMeshes);

      if (intersects.length > 0) {
        const hit = intersects[0].object;
        if (this._hoveredMesh !== hit) {
          this._resetHover();
          this._hoveredMesh = hit;
          hit.material.emissiveIntensity = 0.8;
          hit.scale.set(1.4, 1.4, 1.4);
          this._el.style.cursor = 'pointer';
        }

        const data = hit.userData.node.raw;
        const col = hit.userData.colorStr || '#34d399';
        this._tooltip.innerHTML = `
          <div class="tt-inner" style="background:#0a0d12; border:1px solid ${col}; border-radius:6px; padding:8px 12px; box-shadow:0 8px 24px rgba(0,0,0,0.8);">
            <div style="font-size:12px; font-weight:700; color:#f8fafc; font-family:Sora,sans-serif;">${data.label || data.simpleName || data.id}</div>
            <div style="font-size:11px; color:#94a3b8; font-family:JetBrains Mono,monospace; margin-top:2px;">${data.package || data.id}</div>
            <div style="font-size:11px; color:${col}; font-family:JetBrains Mono,monospace; margin-top:4px;">Kind: ${data.kind || 'TYPE'}</div>
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
        this._hoveredMesh.material.emissiveIntensity = 0.25;
        this._hoveredMesh.scale.set(1.0, 1.0, 1.0);
        this._hoveredMesh = null;
      }
    }

    _onClick(event) {
      if (!this._hoveredMesh) return;
      const entity = this._hoveredMesh.userData.node.raw;
      this.flyToNode(this._hoveredMesh);

      if (entity && this._onSelectEntity) {
        this._onSelectEntity(entity.id || entity.fqn);
      } else if (entity && window.selectEntity) {
        window.selectEntity(entity.id || entity.fqn);
      }
    }

    _animate() {
      this._animId = requestAnimationFrame(this._animate.bind(this));

      // Animate particles along curves
      if (this._particleGroup && this._particles.length > 0) {
        const positions = this._particleGroup.geometry.attributes.position.array;
        this._particles.forEach((p, idx) => {
          p.progress = (p.progress + p.speed) % 1;
          const pos = p.curve.getPointAt(p.progress);
          positions[idx * 3] = pos.x;
          positions[idx * 3 + 1] = pos.y;
          positions[idx * 3 + 2] = pos.z;
        });
        this._particleGroup.geometry.attributes.position.needsUpdate = true;
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
      this._nodeMeshes = [];
      this._edgeLines = [];
      this._particles = [];
    }
  }

  window.Galaxy3DRenderer = Galaxy3DRenderer;
})(window);
