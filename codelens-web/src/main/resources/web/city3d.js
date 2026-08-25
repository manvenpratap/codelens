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
      this._raycaster = null;
      this._mouse = null;
      this._hoveredMesh = null;
      this._tooltip = null;
      this._resizeObserver = null;
      this._onSelectEntity = null;
    }

    onSelectEntity(callback) {
      this._onSelectEntity = callback;
    }

    setData(payload) {
      this._data = payload;
      this._initScene();
      this._buildCity();
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
      this._scene.background = new THREE.Color(0x000000);
      this._scene.fog = new THREE.FogExp2(0x000000, 0.0018);

      // Perspective Camera
      this._camera = new THREE.PerspectiveCamera(45, width / height, 1, 4000);
      this._camera.position.set(240, 280, 360);

      // WebGL Renderer
      this._renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
      this._renderer.setSize(width, height);
      this._renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
      this._renderer.shadowMap.enabled = true;
      this._renderer.shadowMap.type = THREE.PCFSoftShadowMap;
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
      const ambientLight = new THREE.AmbientLight(0xffffff, 0.65);
      this._scene.add(ambientLight);

      const dirLight = new THREE.DirectionalLight(0xffffff, 0.85);
      dirLight.position.set(200, 400, 200);
      dirLight.castShadow = true;
      dirLight.shadow.mapSize.width = 2048;
      dirLight.shadow.mapSize.height = 2048;
      this._scene.add(dirLight);

      const dirLight2 = new THREE.DirectionalLight(0x10b981, 0.45);
      dirLight2.position.set(-200, 300, -200);
      this._scene.add(dirLight2);

      // Grid Floor
      const gridHelper = new THREE.GridHelper(1200, 60, 0x10b981, 0x18202c);
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
        const line = new THREE.LineSegments(edges, new THREE.LineBasicMaterial({ color: 0x10b981, transparent: true, opacity: 0.35 }));
        line.position.copy(platform.position);
        this._scene.add(line);

        // Build Skyscrapers inside district
        pkgClasses.forEach((cls, cIndex) => {
          const cCol = cIndex % bCols;
          const cRow = Math.floor(cIndex / bCols);
          const bx = districtX - districtW / 2 + (cCol + 1) * (districtW / (bCols + 1));
          const bz = districtZ - districtH / 2 + (cRow + 1) * (districtH / (Math.ceil(pkgClasses.length / bCols) + 1));

          const loc = cls.lineCount || cls.size || 50;
          const height = Math.max(12, Math.min(240, Math.log2(loc + 1) * 22));
          const width = 14;
          const depth = 14;

          const buildingGeo = new THREE.BoxGeometry(width, height, depth);
          
          let colorHex = 0x34d399; // Emerald mint
          if (cls.kind === 'INTERFACE') colorHex = 0x10b981;
          else if (cls.kind === 'ENUM') colorHex = 0xf59e0b;
          else if (cls.kind === 'RECORD') colorHex = 0xa855f7;

          const buildingMat = new THREE.MeshStandardMaterial({
            color: colorHex,
            roughness: 0.25,
            metalness: 0.75,
            emissive: colorHex,
            emissiveIntensity: 0.12,
          });

          const mesh = new THREE.Mesh(buildingGeo, buildingMat);
          mesh.position.set(bx, 3 + height / 2, bz);
          mesh.castShadow = true;
          mesh.receiveShadow = true;
          mesh.userData = { entity: cls, origColor: colorHex, height: height };

          this._scene.add(mesh);
          this._buildings.push(mesh);

          // Skyscraper Edge Wireframe
          const bEdges = new THREE.EdgesGeometry(buildingGeo);
          const bLine = new THREE.LineSegments(bEdges, new THREE.LineBasicMaterial({ color: 0xffffff, transparent: true, opacity: 0.4 }));
          bLine.position.copy(mesh.position);
          this._scene.add(bLine);
        });
      });
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
        this._tooltip.innerHTML = `
          <div class="tt-inner" style="background:#0a0d12; border:1px solid #10b981; border-radius:6px; padding:8px 12px; box-shadow:0 8px 24px rgba(0,0,0,0.8);">
            <div style="font-size:12px; font-weight:700; color:#f8fafc; font-family:Sora,sans-serif;">${data.label || data.simpleName || data.id}</div>
            <div style="font-size:11px; color:#94a3b8; font-family:JetBrains Mono,monospace; margin-top:2px;">${data.package || data.id}</div>
            <div style="font-size:11px; color:#34d399; font-family:JetBrains Mono,monospace; margin-top:4px;">LOC: ${data.lineCount || data.size || 'N/A'} • Methods: ${data.methods ? data.methods.length : 'N/A'}</div>
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
      if (entity && this._onSelectEntity) {
        this._onSelectEntity(entity.id || entity.fqn);
      } else if (entity && window.selectEntity) {
        window.selectEntity(entity.id || entity.fqn);
      }
    }

    _animate() {
      this._animId = requestAnimationFrame(this._animate.bind(this));
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
      this._buildings = [];
    }
  }

  window.CodeCity3DRenderer = CodeCity3DRenderer;
})(window);
