/**
 * galaxy3d.js  —  Three.js 3D Celestial Call Galaxy Renderer for CodeLens
 *
 * Enhancements (all three pillars):
 *  I.  SPIRAL ARM + SOLAR SYSTEM LAYOUT
 *        Packages arranged on 4 logarithmic spiral arms.
 *        Classes = central star nodes, methods = orbiting planetary satellites.
 *        Holographic galactic disc with concentric zone ring guides.
 *        Supermassive nucleus glow at the galactic core.
 *
 *  II. THREE.JS POSTPROCESSING PIPELINE  (r128 classic globals)
 *        EffectComposer  → RenderPass  → UnrealBloomPass
 *        → VignetteShader pass  → FXAAShader pass
 *        Falls back to plain renderer.render() if CDN addons are not loaded.
 *
 *  III. PROCEDURAL NEBULA + COMET-TRAIL PARTICLES
 *        Multi-layer parallax starfield (dim / mid / bright).
 *        Per-package additive-blended nebula dust sprite clouds.
 *        Comet-trail energy pulses: bright head + TRAIL_LENGTH fading tail dots.
 *        Constellation hover: hovered node's call edges & neighbours illuminate.
 */

(function (window) {
  'use strict';

  /* ═══════════════════════════════════════════════════════════
   *  Constants
   * ═══════════════════════════════════════════════════════════ */
  const TRAIL_LENGTH   = 6;          // comet tail dot count (including head)
  const STAR_COUNTS    = [1500, 600, 150];
  const NEBULA_PTS     = 55;         // additive dust points per package
  const BLOOM_STRENGTH = 0.85;
  const BLOOM_RADIUS   = 0.40;
  const BLOOM_THRESH   = 0.82;

  // 4 spiral arm base angles (radians)
  const ARM_OFFSETS = [0, Math.PI / 2, Math.PI, (3 * Math.PI) / 2];
  const SPIRAL_A    = 42;            // wider logarithmic spiral (was 22)
  const SPIRAL_B    = 0.20;          // spiral tightness

  /* ═══════════════════════════════════════════════════════════
   *  Galaxy3DRenderer
   * ═══════════════════════════════════════════════════════════ */
  class Galaxy3DRenderer {

    constructor(container) {
      this._container  = container;
      this._el         = null;
      this._scene      = null;
      this._camera     = null;
      this._renderer   = null;
      this._composer   = null;       // THREE.EffectComposer or null
      this._bloomPass  = null;
      this._fxaaPass   = null;
      this._controls   = null;
      this._clock      = null;
      this._animId     = null;
      this._paused     = false;

      this._data            = null;
      this._nodes           = [];
      this._nodeMeshes      = [];
      this._edgeLines       = [];
      this._edgeMap         = new Map();   // nodeId → Line[]
      this._planeMeshes     = [];
      this._nebulaGroups    = [];

      this._showPlanes      = true;
      this._showArcs        = true;
      this._autoRotate      = false;
      this._filterQuery     = '';
      this._hiddenPackages  = new Set();
      this._hiddenEntities  = new Set();
      this._hidePojo        = true;
      this._archetypeFilter = 'ALL';

      // Comet-trail particle system
      this._particles       = [];          // { curve, progress, speed }
      this._cometGroup      = null;        // THREE.Points  (head layer)
      this._cometPositions  = null;        // Float32Array for head geo
      this._cometTails      = [];          // [ { points:THREE.Points, positions:Float32Array, step } ]

      this._raycaster       = null;
      this._mouse           = null;
      this._hoveredMesh     = null;
      this._highlightEdges  = [];
      this._highlightNeighbors = [];
      this._tooltip         = null;
      this._toolbar         = null;
      this._resizeObserver  = null;
      this._onSelectEntity  = null;

      this._targetCamPos    = null;
      this._targetCtrlTgt   = null;

      // Lights (keep refs for setBrightness)
      this._ambientLight = null;
      this._hemiLight    = null;
      this._coreLight    = null;
      this._dirLight     = null;
    }

    /* ─────────────────── Public API ─────────────────────────── */

    setArchetypeFilter(ruleId) {
      this._archetypeFilter = ruleId || 'ALL';
      this._applyFilters();
    }

    toggleAutoRotate() {
      this._autoRotate = !this._autoRotate;
      if (this._controls) this._controls.autoRotate = this._autoRotate;
    }

    toggleArcs(v) {
      if (v !== undefined) this._showArcs = Boolean(v);
      else this._showArcs = !this._showArcs;
      this._applyFilters();
      return this._showArcs;
    }

    togglePojo() {
      this._hidePojo = !this._hidePojo;
      this._applyFilters();
      return this._hidePojo;
    }

    toggleHideGetters() { return this.togglePojo(); }

    setHidePojo(hide) {
      this._hidePojo = Boolean(hide);
      this._applyFilters();
    }

    toggleEntity(id, visible, fqn = null) {
      [id, fqn].filter(Boolean).forEach(x => {
        if (visible === undefined) {
          this._hiddenEntities.has(x) ? this._hiddenEntities.delete(x) : this._hiddenEntities.add(x);
        } else {
          visible ? this._hiddenEntities.delete(x) : this._hiddenEntities.add(x);
        }
      });
      this._applyFilters();
    }

    flyToNode(mesh) {
      if (!mesh || !this._camera || !this._controls) return;
      const t = mesh.position.clone();
      this._targetCtrlTgt = t.clone();
      this._targetCamPos  = new THREE.Vector3(t.x + 55, t.y + 35, t.z + 65);
    }

    togglePlanes(v) {
      if (v !== undefined) this._showPlanes = v; else this._showPlanes = !this._showPlanes;
      this._planeMeshes.forEach(p => { p.visible = this._showPlanes; });
    }

    setFilter(q) {
      this._filterQuery = (q || '').toLowerCase().trim();
      this._applyFilters();
    }

    togglePackage(pkg, visible) {
      if (visible === undefined) {
        this._hiddenPackages.has(pkg) ? this._hiddenPackages.delete(pkg) : this._hiddenPackages.add(pkg);
      } else {
        visible ? this._hiddenPackages.delete(pkg) : this._hiddenPackages.add(pkg);
      }
      this._applyFilters();
    }

    onSelectEntity(cb) { this._onSelectEntity = cb; }

    setData(payload) {
      this._data = payload;
      this._initScene();
    }

    setBrightness(v) {
      const f = Math.max(0.2, Math.min(2.5, v));
      if (this._renderer)    this._renderer.toneMappingExposure = 0.75 * f;  // was 1.2
      if (this._ambientLight) this._ambientLight.intensity = 0.25 * f;        // was 0.5
      if (this._hemiLight)    this._hemiLight.intensity    = 0.25 * f;        // was 0.6
      if (this._coreLight)    this._coreLight.intensity    = 1.0  * f;        // was 2.2
      if (this._dirLight)     this._dirLight.intensity     = 0.25 * f;        // was 0.5
    }

    pause() {
      this._paused = true;
      if (this._animId) { cancelAnimationFrame(this._animId); this._animId = null; }
    }

    resume() {
      if (!this._paused) return;
      this._paused = false;
      if (this._clock) this._clock.start();
      this._onResize();
      if (!this._animId) this._animId = requestAnimationFrame(this._animate.bind(this));
    }

    /* ─────────────────── Scene Initialisation ──────────────── */

    _initScene() {
      this.destroy();

      this._el = document.createElement('div');
      this._el.className = 'galaxy3d-container';
      this._el.style.cssText = 'width:100%;height:100%;position:relative;overflow:hidden;background:#000;';
      this._container.appendChild(this._el);

      this._tooltip = document.createElement('div');
      this._tooltip.className = 'galaxy3d-tooltip';
      this._tooltip.style.cssText = 'position:absolute;display:none;pointer-events:none;z-index:150;';
      this._el.appendChild(this._tooltip);

      const W = this._el.clientWidth  || 900;
      const H = this._el.clientHeight || 600;

      // Scene
      this._scene = new THREE.Scene();
      this._scene.fog = new THREE.FogExp2(0x02040a, 0.00075);

      // Camera
      this._camera = new THREE.PerspectiveCamera(45, W / H, 1, 4000);
      this._camera.position.set(0, 200, 550);

      // Renderer
      this._renderer = new THREE.WebGLRenderer({ antialias: true, powerPreference: 'high-performance' });
      this._renderer.setSize(W, H);
      this._renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
      if (THREE.sRGBEncoding) this._renderer.outputEncoding = THREE.sRGBEncoding;
      this._renderer.toneMapping = THREE.ACESFilmicToneMapping;
      this._renderer.toneMappingExposure = 0.75;   // was 1.2 — much dimmer default
      this._el.appendChild(this._renderer.domElement);
      this._clock = new THREE.Clock();

      // OrbitControls
      if (THREE.OrbitControls) {
        this._controls = new THREE.OrbitControls(this._camera, this._renderer.domElement);
        this._controls.enableDamping   = true;
        this._controls.dampingFactor   = 0.06;
        this._controls.minDistance     = 25;
        this._controls.maxDistance     = 1800;
        this._controls.autoRotateSpeed = 0.35;
      }

      // Lights
      this._ambientLight = new THREE.AmbientLight(0x0a0d1a, 0.25);  // was 0.5
      this._scene.add(this._ambientLight);

      this._hemiLight = new THREE.HemisphereLight(0x334466, 0x0a0d1a, 0.25);  // was 0.6
      this._scene.add(this._hemiLight);

      this._coreLight = new THREE.PointLight(0xfbbf24, 1.0, 700, 2.0);  // was 2.2 / 900
      this._coreLight.position.set(0, 0, 0);
      this._scene.add(this._coreLight);

      this._dirLight = new THREE.DirectionalLight(0x34d399, 0.25);  // was 0.5
      this._dirLight.position.set(250, 300, 200);
      this._scene.add(this._dirLight);

      // Environment
      this._buildStarfield();
      this._buildHolographicGrid();

      // Post-processing (graceful fallback if scripts not loaded)
      this._buildComposer(W, H);

      // Interaction
      this._raycaster = new THREE.Raycaster();
      this._mouse     = new THREE.Vector2();
      this._el.addEventListener('mousemove', this._onMouseMove.bind(this));
      this._el.addEventListener('click',     this._onClick.bind(this));

      this._resizeObserver = new ResizeObserver(() => this._onResize());
      this._resizeObserver.observe(this._el);

      // Data → meshes
      this._buildGalaxy();

      // Start loop
      this._animate();
    }

    /* ─────────────────── Starfield ─────────────────────────── */

    _buildStarfield() {
      const cfgs = [
        { n: STAR_COUNTS[0], size: 0.8,  op: 0.45, col: 0x7ecfff },
        { n: STAR_COUNTS[1], size: 1.5,  op: 0.60, col: 0xb3d9ff },
        { n: STAR_COUNTS[2], size: 2.8,  op: 0.90, col: 0xffffff },
      ];
      const spread = 2200;
      cfgs.forEach(({ n, size, op, col }) => {
        const geo = new THREE.BufferGeometry();
        const pos = new Float32Array(n * 3);
        for (let i = 0; i < n * 3; i += 3) {
          pos[i]     = (Math.random() - 0.5) * spread;
          pos[i + 1] = (Math.random() - 0.5) * spread * 0.55;
          pos[i + 2] = (Math.random() - 0.5) * spread;
        }
        geo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
        const mat = new THREE.PointsMaterial({ color: col, size, transparent: true, opacity: op, depthWrite: false, sizeAttenuation: true });
        this._scene.add(new THREE.Points(geo, mat));
      });
    }

    /* ─────────────────── Holographic Grid & Nucleus ────────── */

    _buildHolographicGrid() {
      // Galactic plane grid
      const grid = new THREE.GridHelper(950, 46, 0x0d9488, 0x0d3833);
      grid.position.y = -12;
      grid.material.transparent = true;
      grid.material.opacity     = 0.14;
      grid.material.depthWrite  = false;
      this._scene.add(grid);

      // Zone rings — inner core / mid domain / outer boundary
      [
        { r: 100,  col: 0xfbbf24, op: 0.30 },
        { r: 220,  col: 0x10b981, op: 0.22 },
        { r: 370,  col: 0x3b82f6, op: 0.18 },
      ].forEach(({ r, col, op }) => {
        const geo  = new THREE.RingGeometry(r - 0.8, r, 80);
        const mat  = new THREE.MeshBasicMaterial({ color: col, transparent: true, opacity: op, side: THREE.DoubleSide, depthWrite: false });
        const mesh = new THREE.Mesh(geo, mat);
        mesh.rotation.x = -Math.PI / 2;
        mesh.position.y = -11;
        this._scene.add(mesh);
      });

      // Supermassive nucleus sphere
      const nucMat = new THREE.MeshStandardMaterial({
        color: 0xfbbf24, emissive: 0xf59e0b, emissiveIntensity: 1.8,  // was 3.5
        roughness: 0.1, metalness: 0.1,
      });
      this._scene.add(new THREE.Mesh(new THREE.SphereGeometry(9, 24, 24), nucMat));

      // Nucleus corona halo
      const haloMat = new THREE.MeshBasicMaterial({ color: 0xfbbf24, transparent: true, opacity: 0.065, side: THREE.BackSide, depthWrite: false });
      this._scene.add(new THREE.Mesh(new THREE.SphereGeometry(18, 24, 24), haloMat));
    }

    /* ─────────────────── Post-Processing ───────────────────── */

    _buildComposer(W, H) {
      // r128 classic globals attach to THREE namespace: THREE.EffectComposer, THREE.UnrealBloomPass …
      if (!THREE.EffectComposer || !THREE.RenderPass || !THREE.UnrealBloomPass) {
        this._composer = null;
        return;
      }

      const composer = new THREE.EffectComposer(this._renderer);

      // 1. Scene render pass
      composer.addPass(new THREE.RenderPass(this._scene, this._camera));

      // 2. UnrealBloom — cosmic glow on emissive stars and arcs
      const bloom = new THREE.UnrealBloomPass(
        new THREE.Vector2(W, H),
        BLOOM_STRENGTH,
        BLOOM_RADIUS,
        BLOOM_THRESH
      );
      composer.addPass(bloom);
      this._bloomPass = bloom;

      // 3. Vignette — cinematic dark corners
      if (THREE.ShaderPass && THREE.VignetteShader) {
        const vig = new THREE.ShaderPass(THREE.VignetteShader);
        vig.uniforms['offset'].value   = 0.95;
        vig.uniforms['darkness'].value = 1.05;
        composer.addPass(vig);
      }

      // 4. FXAA — smooth thin bezier arc edges
      if (THREE.ShaderPass && THREE.FXAAShader) {
        const fxaa = new THREE.ShaderPass(THREE.FXAAShader);
        fxaa.material.uniforms['resolution'].value.set(1 / W, 1 / H);
        composer.addPass(fxaa);
        this._fxaaPass = fxaa;
      }

      this._composer = composer;
    }

    /* ─────────────────── Galaxy Data Layout ────────────────── */

    _buildGalaxy() {
      if (!this._data || !this._scene) return;

      const rawNodes = this._data.nodes || [];
      const rawEdges = this._data.edges || [];
      if (!rawNodes.length) return;

      this._nodeMeshes  = [];
      this._edgeLines   = [];
      this._edgeMap     = new Map();
      this._particles   = [];
      this._planeMeshes = [];
      this._nebulaGroups = [];

      const nodeMap = new Map();  // id → nodeObj { id, x,y,z, pkg, colorHex, colorStr, isMethod, raw }

      /* ── 1. Group nodes by package ───────────────────────── */
      const pkgMap = new Map();
      rawNodes.forEach(n => {
        const isType = (n.type === 'CLASS' || n.type === 'TYPE' || n.id.split('.').length <= 2);
        const pkg = n.package ||
          (n.id.includes('.')
            ? (isType ? n.id.split('.').slice(0, -1).join('.') : n.id.split('.').slice(0, -2).join('.'))
            : 'default');
        if (!pkgMap.has(pkg)) pkgMap.set(pkg, []);
        pkgMap.get(pkg).push(n);
      });

      // Sort: core/common packages first (sit near nucleus)
      const CORE_RE  = /core|common|kernel|default|base|util|shared|foundation/i;
      const sortedPkgs = [...pkgMap.keys()].sort((a, b) => {
        const aCore = CORE_RE.test(a) ? 0 : 1;
        const bCore = CORE_RE.test(b) ? 0 : 1;
        return aCore - bCore;
      });
      const totalPkgs = sortedPkgs.length;

      /* ── 2. Place packages on spiral arms ────────────────── */
      this._nodes = [];

      sortedPkgs.forEach((pkgName, pIdx) => {
        const pkgNodes = pkgMap.get(pkgName);
        const colorStr = (window.CodeLensPalette && window.CodeLensPalette.getColor)
          ? window.CodeLensPalette.getColor(pkgName, pIdx)
          : '#34d399';
        const colorHex = parseInt(colorStr.replace('#', ''), 16) || 0x34d399;

        /* Spiral arm position */
        let cx = 0, cy = 0, cz = 0;
        const isCore = CORE_RE.test(pkgName);
        if (!isCore || totalPkgs <= 1) {
          const armIdx  = pIdx % ARM_OFFSETS.length;
          const armStep = Math.floor(pIdx / ARM_OFFSETS.length) + 1;
          const theta   = ARM_OFFSETS[armIdx] + armStep * 1.2;  // was 0.85 — wider steps
          const r       = SPIRAL_A * Math.exp(SPIRAL_B * theta);
          cx = r * Math.cos(theta);
          cz = r * Math.sin(theta);
          cy = (armIdx % 2 === 0 ? 1 : -1) * armStep * 8;  // was 5 — more vertical spread
        }

        /* Separate classes and methods */
        const classNodes  = pkgNodes.filter(n => n.type !== 'METHOD' && n.kind !== 'METHOD' && !n.id.includes('('));
        const methodNodes = pkgNodes.filter(n => n.type === 'METHOD'  || n.kind === 'METHOD'  || n.id.includes('('));

        /* Place class "star" nodes in a fan ring around the package centre */
        // All classes in the module share the exact same package module color
        const ringR = Math.max(40, 22 * Math.sqrt(classNodes.length));
        classNodes.forEach((n, ci) => {
          const angle = (ci / Math.max(1, classNodes.length)) * Math.PI * 2;
          const r     = ringR + (ci % 3) * 12;
          const px    = cx + r * Math.cos(angle);
          const py    = cy + (ci % 2 === 0 ? 9 : -9);
          const pz    = cz + r * Math.sin(angle);

          const obj = { id: n.id, label: n.label || n.simpleName || n.id.split('.').pop(), raw: n,
                        x: px, y: py, z: pz, pkg: pkgName, colorStr: colorStr, colorHex: colorHex, isMethod: false };
          this._nodes.push(obj);
          nodeMap.set(n.id, obj);
        });

        /* Place method "planet" nodes: group by parent class and share the module/class color */
        // Group method nodes by parent class FQN
        const classGroups = new Map();
        methodNodes.forEach(n => {
          const parentClass = n.className || (n.id.includes('.')
            ? n.id.split('(')[0].split('.').slice(0, -1).join('.')
            : 'DefaultClass');
          if (!classGroups.has(parentClass)) classGroups.set(parentClass, []);
          classGroups.get(parentClass).push(n);
        });

        const classGroupKeys = Array.from(classGroups.keys());
        const totalClassGroups = classGroupKeys.length;

        classGroupKeys.forEach((clsKey, cgi) => {
          const grpMethods = classGroups.get(clsKey);
          const parent = nodeMap.get(clsKey);

          // Sub-center for this class
          let subCx = cx, subCy = cy, subCz = cz;
          if (parent) {
            subCx = parent.x;
            subCy = parent.y;
            subCz = parent.z;
          } else if (totalClassGroups > 1) {
            const grpAngle = (cgi / totalClassGroups) * Math.PI * 2;
            const grpDist = ringR + 25 + (cgi % 3) * 15;
            subCx = cx + grpDist * Math.cos(grpAngle);
            subCy = cy + (cgi % 2 === 0 ? 10 : -10);
            subCz = cz + grpDist * Math.sin(grpAngle);
          }

          grpMethods.forEach((n, mi) => {
            const orbitR = 18 + Math.floor(mi / 6) * 12;
            const orbitA = ((mi % 6) / 6) * Math.PI * 2 + Math.floor(mi / 6) * 0.7;
            const px = subCx + orbitR * Math.cos(orbitA);
            const py = subCy + 6 * Math.sin(orbitA * 0.5);
            const pz = subCz + orbitR * Math.sin(orbitA);

            // Methods take the exact color of their module/class
            const obj = { id: n.id, label: (n.label || n.simpleName || n.id.split('(')[0].split('.').pop()) + '()',
                          raw: n, x: px, y: py, z: pz, pkg: pkgName, parentClass: clsKey,
                          colorStr: colorStr, colorHex: colorHex, isMethod: true };
            this._nodes.push(obj);
            nodeMap.set(n.id, obj);
          });
        });

        /* Package orbital disc + rim torus */
        const pkgAll  = pkgNodes.map(n => nodeMap.get(n.id)).filter(Boolean);
        let diskR = 30;
        pkgAll.forEach(no => {
          const d = Math.sqrt((no.x - cx) ** 2 + (no.z - cz) ** 2);
          if (d + 14 > diskR) diskR = d + 14;
        });

        const diskGeo = new THREE.CylinderGeometry(diskR, diskR, 1.0, 40);
        const diskMat = new THREE.MeshStandardMaterial({
          color: colorHex, transparent: true, opacity: 0.09,
          roughness: 0.9, metalness: 0, side: THREE.DoubleSide, depthWrite: false,
        });
        const disk = new THREE.Mesh(diskGeo, diskMat);
        disk.position.set(cx, cy, cz);
        disk.userData = { pkg: pkgName };
        this._scene.add(disk);
        this._planeMeshes.push(disk);

        const rimGeo = new THREE.TorusGeometry(diskR, 0.9, 8, 64);
        const rimMat = new THREE.MeshBasicMaterial({ color: colorHex, transparent: true, opacity: 0.38, depthWrite: false });
        const rim    = new THREE.Mesh(rimGeo, rimMat);
        rim.rotation.x = Math.PI / 2;
        rim.position.set(cx, cy, cz);
        rim.userData = { pkg: pkgName };
        this._scene.add(rim);
        this._planeMeshes.push(rim);

        /* Nebula dust cloud (additive blending) */
        this._buildNebulaCloud(cx, cy, cz, diskR, colorHex, pkgName);
      });

      /* ── 3. Build node sphere meshes ─────────────────────── */
      this._nodes.forEach(n => {
        const isMeth = n.isMethod;
        const radius = isMeth ? 3.5 : 6.0;
        const hex    = n.colorHex;

        const mat = new THREE.MeshStandardMaterial({
          color: hex, emissive: hex,
          emissiveIntensity: isMeth ? 0.45 : 0.55,
          roughness: 0.15, metalness: 0.20,
        });
        const mesh = new THREE.Mesh(new THREE.SphereGeometry(radius, 20, 20), mat);
        mesh.position.set(n.x, n.y, n.z);

        let haloMesh = null;
        // Class stars get a corona halo
        if (!isMeth) {
          const hMat = new THREE.MeshBasicMaterial({ color: hex, transparent: true, opacity: 0.055, side: THREE.BackSide, depthWrite: false });
          haloMesh = new THREE.Mesh(new THREE.SphereGeometry(radius * 1.7, 16, 16), hMat);
          haloMesh.position.copy(mesh.position);
          this._scene.add(haloMesh);
        }

        mesh.userData = { node: n, colorStr: n.colorStr, pkg: n.pkg, isMethod: isMeth, haloMesh: haloMesh };
        this._scene.add(mesh);
        this._nodeMeshes.push(mesh);
      });

      /* ── 4. Build bezier edge arcs ───────────────────────── */
      rawEdges.forEach(e => {
        const src = nodeMap.get(e.source || e.caller);
        const tgt = nodeMap.get(e.target || e.callee);
        if (!src || !tgt) return;

        const p1   = new THREE.Vector3(src.x, src.y, src.z);
        const p2   = new THREE.Vector3(tgt.x, tgt.y, tgt.z);
        const dist = p1.distanceTo(p2);
        const mid  = new THREE.Vector3().addVectors(p1, p2).multiplyScalar(0.5);

        const cross   = src.pkg !== tgt.pkg;
        mid.y        += cross ? dist * 0.38 + 22 : dist * 0.18 + 8;
        mid.x        += (Math.random() - 0.5) * 10;
        mid.z        += (Math.random() - 0.5) * 10;

        const curve  = new THREE.QuadraticBezierCurve3(p1, mid, p2);
        const pts    = curve.getPoints(28);
        const geo    = new THREE.BufferGeometry().setFromPoints(pts);
        const arcCol = cross ? 0x6ee7b7 : src.colorHex;
        const arcOp  = cross ? 0.55 : 0.35;

        const lineMat = new THREE.LineBasicMaterial({ color: arcCol, transparent: true, opacity: arcOp });
        const line    = new THREE.Line(geo, lineMat);
        line.userData = { src: src.id, tgt: tgt.id, origColor: arcCol, origOpacity: arcOp };
        this._scene.add(line);
        this._edgeLines.push(line);

        [src.id, tgt.id].forEach(id => {
          if (!this._edgeMap.has(id)) this._edgeMap.set(id, []);
          this._edgeMap.get(id).push(line);
        });

        this._particles.push({ curve, line, progress: Math.random(), speed: 0.002 + Math.random() * 0.003 });
      });

      /* ── 5. Comet-trail particle system ──────────────────── */
      this._buildCometSystem();

      /* ── 6. HUD overlay ──────────────────────────────────── */
      this._buildOverlayControls();

      /* ── 7. Apply initial filters (e.g. POJO default) ───── */
      this._applyFilters();
    }

    /* ─────────────────── Nebula Cloud ──────────────────────── */

    _buildNebulaCloud(cx, cy, cz, diskR, colorHex, pkgName) {
      const N   = NEBULA_PTS;
      const geo = new THREE.BufferGeometry();
      const pos = new Float32Array(N * 3);
      const R   = diskR * 1.2;
      for (let i = 0; i < N * 3; i += 3) {
        const a = Math.random() * Math.PI * 2;
        const r = Math.random() * R;
        pos[i]     = cx + r * Math.cos(a);
        pos[i + 1] = cy + (Math.random() - 0.5) * 24;
        pos[i + 2] = cz + r * Math.sin(a);
      }
      geo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
      const mat = new THREE.PointsMaterial({
        color: colorHex, size: 15 + Math.random() * 10,
        transparent: true, opacity: 0.055,
        depthWrite: false, blending: THREE.AdditiveBlending, sizeAttenuation: true,
      });
      const cloud = new THREE.Points(geo, mat);
      cloud.userData = { pkg: pkgName };
      this._scene.add(cloud);
      this._nebulaGroups.push(cloud);
    }

    /* ─────────────────── Comet-Trail System ────────────────── */

    _buildCometSystem() {
      const N = this._particles.length;
      if (N === 0) return;

      // Head layer
      const headGeo = new THREE.BufferGeometry();
      const headPos = new Float32Array(N * 3);
      headGeo.setAttribute('position', new THREE.BufferAttribute(headPos, 3));
      const headMat = new THREE.PointsMaterial({
        color: 0x6ee7b7, size: 5.5, transparent: true, opacity: 0.95,
        depthWrite: false, blending: THREE.AdditiveBlending, sizeAttenuation: true,
      });
      this._cometGroup    = new THREE.Points(headGeo, headMat);
      this._cometPositions = headPos;
      this._scene.add(this._cometGroup);

      // Tail layers (progressively dimmer / smaller)
      this._cometTails = [];
      for (let t = 1; t < TRAIL_LENGTH; t++) {
        const tGeo = new THREE.BufferGeometry();
        const tPos = new Float32Array(N * 3);
        tGeo.setAttribute('position', new THREE.BufferAttribute(tPos, 3));
        const tMat = new THREE.PointsMaterial({
          color: 0x34d399,
          size: Math.max(1.0, 5.5 - t * 0.85),
          transparent: true,
          opacity: 0.70 * Math.pow(0.52, t),
          depthWrite: false,
          blending: THREE.AdditiveBlending,
          sizeAttenuation: true,
        });
        const tPts = new THREE.Points(tGeo, tMat);
        this._scene.add(tPts);
        this._cometTails.push({ points: tPts, positions: tPos, step: t });
      }
    }

    /* ─────────────────── HUD Overlay ───────────────────────── */

    _buildOverlayControls() {
      if (this._toolbar) this._toolbar.remove();
      this._toolbar = document.createElement('div');
      this._toolbar.className = 'galaxy3d-hud-overlay';
      Object.assign(this._toolbar.style, {
        position: 'absolute', bottom: '16px', left: '16px',
        display: 'flex', alignItems: 'center', gap: '8px', zIndex: '120',
      });

      const search = document.createElement('input');
      search.type        = 'text';
      search.placeholder = 'Filter nodes...';
      search.value       = this._filterQuery;
      Object.assign(search.style, {
        background: 'rgba(2,4,10,0.9)', border: '1px solid rgba(255,255,255,0.15)',
        color: '#f8fafc', borderRadius: '6px', padding: '4px 10px',
        fontSize: '11px', width: '140px', outline: 'none',
      });
      search.addEventListener('input', e => this.setFilter(e.target.value));
      this._toolbar.appendChild(search);

      const mkBtn = (label, icon, active) => {
        const b = document.createElement('button');
        b.innerHTML = `<span class="hud-btn-icon">${icon}</span> <span class="hud-btn-text">${label}</span>`;
        Object.assign(b.style, {
          background: '#02040a', border: `1px solid ${active ? '#10b981' : 'rgba(255,255,255,0.15)'}`,
          color: active ? '#f8fafc' : '#94a3b8', borderRadius: '6px',
          padding: '5px 10px', fontSize: '11px', cursor: 'pointer',
        });
        return b;
      };

      const orbitBtn = mkBtn('Orbit', '⟳', this._autoRotate);
      orbitBtn.addEventListener('click', () => {
        this.toggleAutoRotate();
        orbitBtn.style.borderColor = this._autoRotate ? '#10b981' : 'rgba(255,255,255,0.15)';
        orbitBtn.style.color       = this._autoRotate ? '#f8fafc'  : '#94a3b8';
      });
      this._toolbar.appendChild(orbitBtn);

      const planeBtn = mkBtn('Cluster Planes',
        `<svg class="svg-icon icon-cyan icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><circle cx="12" cy="12" r="6"/><circle cx="12" cy="12" r="2"/></svg>`,
        true);
      planeBtn.addEventListener('click', () => {
        this.togglePlanes();
        planeBtn.style.borderColor = this._showPlanes ? '#10b981' : 'rgba(255,255,255,0.15)';
        planeBtn.style.color       = this._showPlanes ? '#f8fafc'  : '#94a3b8';
      });
      this._toolbar.appendChild(planeBtn);

      this._el.appendChild(this._toolbar);
    }

    /* ─────────────────── Filters ───────────────────────────── */

    _applyFilters() {
      const visible = new Set();
      let hiddenPojoCount = 0;

      this._nodeMeshes.forEach(m => {
        const d    = m.userData;
        const raw  = d.node && d.node.raw ? d.node.raw : {};
        const id   = d.node ? (d.node.id || '') : '';
        const lbl  = (raw.label || raw.simpleName || raw.name || id).toLowerCase();
        const pkg  = (d.pkg || '').toLowerCase();
        const fqn  = raw.fqn || id;

        const okSearch = !this._filterQuery || lbl.includes(this._filterQuery) || pkg.includes(this._filterQuery);
        const okPkg    = !this._hiddenPackages.has(d.pkg);

        let hidden = this._hiddenEntities.has(id) || this._hiddenEntities.has(fqn);
        if (!hidden && fqn && fqn.includes('.')) {
          const simple = fqn.split('.').pop();
          if (simple && this._hiddenEntities.has(simple)) hidden = true;
          const cleanFqn    = fqn.replace(/\(.*\)/, '');
          const parts       = cleanFqn.split('.');
          const parentSim   = parts.length >= 2 ? parts[parts.length - 2] : '';
          const parentFqn2  = parts.length >= 2 ? parts.slice(0, -1).join('.') : '';
          if ((parentSim  && this._hiddenEntities.has(parentSim)) ||
              (parentFqn2 && this._hiddenEntities.has(parentFqn2))) hidden = true;
        }

        let okPojo = true;
        if (d.isMethod && window.CodeLensClassifier) {
          const isPojo = window.CodeLensClassifier.isPojo(raw, id, d.pkg);
          if (isPojo) {
            hiddenPojoCount++;
            if (this._hidePojo) okPojo = false;
          }
        }

        let okArchetype = true;
        if (this._archetypeFilter && this._archetypeFilter !== 'ALL' && window.CodeLensClassifier) {
          okArchetype = window.CodeLensClassifier.isMatchArchetype(
            raw,
            id,
            d.pkg,
            Boolean(d.isMethod),
            this._archetypeFilter
          );
        }

        const show = okSearch && okPkg && !hidden && okPojo && okArchetype;
        m.visible = show;
        if (d.haloMesh) d.haloMesh.visible = show;
        if (show) visible.add(id);
      });

      this._edgeLines.forEach(l => {
        if (!this._showArcs) {
          l.visible = false;
        } else if (l.userData.src && l.userData.tgt) {
          l.visible = visible.has(l.userData.src) && visible.has(l.userData.tgt);
        }
      });

      if (this._cometGroup) {
        this._cometGroup.visible = this._showArcs;
      }
      this._cometTails.forEach(t => {
        if (t.points) t.points.visible = this._showArcs;
      });

      this._planeMeshes.forEach(p => {
        const pkgOk = !p.userData || !this._hiddenPackages.has(p.userData.pkg);
        p.visible   = this._showPlanes && pkgOk;
      });

      this._nebulaGroups.forEach(ng => {
        ng.visible = !ng.userData || !this._hiddenPackages.has(ng.userData.pkg);
      });

      this._updatePojoButton(hiddenPojoCount);
      this._updateArcsButton();
    }

    _updateArcsButton() {
      const btn = document.getElementById('btn-codebase-filter-arcs');
      if (btn) btn.classList.toggle('active', this._showArcs);
    }

    _updatePojoButton(hiddenCount) {
      ['btn-filter-getters', 'btn-codebase-filter-getters'].forEach(id => {
        const btn = document.getElementById(id);
        const btnText = document.getElementById(`${id}-text`);
        if (btnText && btn) {
          if (this._hidePojo && hiddenCount > 0) {
            btnText.textContent = `Hide POJOs (${hiddenCount})`;
            btn.title = `${hiddenCount} POJO getters/setters hidden. Click to show all.`;
          } else {
            btnText.textContent = 'Hide POJOs';
            btn.title = this._hidePojo ? 'POJO getters & setters hidden' : 'Click to hide POJO getters & setters';
          }
          btn.classList.toggle('active', this._hidePojo);
        }
      });
    }

    /* ─────────────────── Mouse / Hover ─────────────────────── */

    _onMouseMove(ev) {
      if (!this._el || !this._camera) return;
      const rect      = this._el.getBoundingClientRect();
      this._mouse.x   =  ((ev.clientX - rect.left) / rect.width)  * 2 - 1;
      this._mouse.y   = -((ev.clientY - rect.top)  / rect.height) * 2 + 1;

      this._raycaster.setFromCamera(this._mouse, this._camera);
      const hits = this._raycaster.intersectObjects(this._nodeMeshes.filter(m => m.visible));

      if (hits.length > 0) {
        const hit = hits[0].object;
        if (this._hoveredMesh !== hit) {
          this._resetHover();
          this._hoveredMesh = hit;
          hit.material.emissiveIntensity = 3.0;
          hit.scale.set(1.45, 1.45, 1.45);
          this._el.style.cursor = 'pointer';
          this._lightConstellation(hit);
        }

        const data   = hit.userData.node.raw;
        const isMeth = hit.userData.isMethod;
        const col    = hit.userData.colorStr || '#34d399';
        const badge  = isMeth
          ? '<span style="color:#38bdf8;font-weight:700;">METHOD</span>'
          : `<span style="color:${col};font-weight:700;">${data.kind || 'CLASS'}</span>`;

        let archHtml = '';
        if (window.CodeLensClassifier) {
          const arch = isMeth
            ? window.CodeLensClassifier.classifyMethod(data, data.id || data.fqn, hit.userData.pkg)
            : window.CodeLensClassifier.classifyType(data, data.id || data.fqn, hit.userData.pkg);
          if (arch) archHtml = `<div style="margin-top:6px;"><span class="archetype-badge" style="background:${arch.color}22;border:1px solid ${arch.color};color:${arch.color};font-size:10px;padding:2px 6px;border-radius:4px;">${arch.icon} ${arch.label} (${arch.badge})</span></div>`;
        }

        this._tooltip.innerHTML = `
          <div style="background:#02040a;border:1px solid ${col};border-radius:8px;padding:10px 14px;box-shadow:0 8px 32px rgba(0,0,0,0.85),0 0 20px ${col}22;">
            <div style="font-size:12px;font-weight:700;color:#f8fafc;font-family:Sora,sans-serif;">${data.label || data.simpleName || data.id}</div>
            <div style="font-size:11px;color:#64748b;font-family:'JetBrains Mono',monospace;margin-top:2px;">${data.package || hit.userData.pkg || ''}</div>
            <div style="font-size:11px;font-family:'JetBrains Mono',monospace;margin-top:4px;">Kind: ${badge}</div>
            ${archHtml}
          </div>`;
        this._tooltip.style.left    = `${ev.clientX - rect.left + 16}px`;
        this._tooltip.style.top     = `${ev.clientY - rect.top  + 16}px`;
        this._tooltip.style.display = 'block';

      } else {
        this._resetHover();
        this._tooltip.style.display = 'none';
        this._el.style.cursor = 'default';
      }
    }

    _lightConstellation(hitMesh) {
      const id  = hitMesh.userData.node ? hitMesh.userData.node.id : null;
      if (!id) return;
      const neighbors = new Set();

      (this._edgeMap.get(id) || []).forEach(line => {
        line.material.color.setHex(0xf0fdf4);
        line.material.opacity = 0.92;
        this._highlightEdges.push(line);
        [line.userData.src, line.userData.tgt].forEach(nid => { if (nid !== id) neighbors.add(nid); });
      });

      this._nodeMeshes.forEach(m => {
        const nid = m.userData.node ? m.userData.node.id : null;
        if (nid && neighbors.has(nid)) {
          m.scale.set(1.20, 1.20, 1.20);
          m.material.emissiveIntensity = Math.min(3.5, m.material.emissiveIntensity * 1.8);
          this._highlightNeighbors.push(m);
        }
      });
    }

    _resetHover() {
      if (this._hoveredMesh) {
        const isMeth = this._hoveredMesh.userData.isMethod;
        this._hoveredMesh.material.emissiveIntensity = isMeth ? 0.45 : 0.55;  // matches default
        this._hoveredMesh.scale.set(1, 1, 1);
        this._hoveredMesh = null;
      }
      this._highlightEdges.forEach(l => {
        l.material.color.setHex(l.userData.origColor || 0x6ee7b7);
        l.material.opacity = l.userData.origOpacity || 0.35;
      });
      this._highlightEdges = [];
      this._highlightNeighbors.forEach(m => {
        m.scale.set(1, 1, 1);
        m.material.emissiveIntensity = m.userData.isMethod ? 0.45 : 0.55;
      });
      this._highlightNeighbors = [];
    }

    _onClick() {
      if (!this._hoveredMesh) return;
      const entity = this._hoveredMesh.userData.node.raw;
      this.flyToNode(this._hoveredMesh);
      if (entity && this._onSelectEntity) this._onSelectEntity(entity.id || entity.fqn);
      else if (entity && window.selectEntity) window.selectEntity(entity.id || entity.fqn);
    }

    /* ─────────────────── Resize ─────────────────────────────── */

    _onResize() {
      if (!this._el || !this._renderer || !this._camera) return;
      const W = this._el.clientWidth;
      const H = this._el.clientHeight;
      if (!W || !H) return;
      this._camera.aspect = W / H;
      this._camera.updateProjectionMatrix();
      this._renderer.setSize(W, H);
      if (this._composer)  this._composer.setSize(W, H);
      if (this._fxaaPass)  this._fxaaPass.material.uniforms['resolution'].value.set(1 / W, 1 / H);
      if (this._bloomPass) this._bloomPass.resolution && this._bloomPass.resolution.set(W, H);
    }

    /* ─────────────────── Animation Loop ────────────────────── */

    _animate() {
      if (this._paused) return;
      this._animId = requestAnimationFrame(this._animate.bind(this));

      const delta  = this._clock ? Math.min(this._clock.getDelta(), 0.1) : 0.016;
      const elapsed = this._clock ? this._clock.elapsedTime : 0;
      const lerp   = 1 - Math.exp(-6 * delta);

      /* Comet particles */
      if (this._cometGroup && this._particles.length > 0) {
        const hp = this._cometPositions;
        this._particles.forEach((p, i) => {
          if (p.line && !p.line.visible) {
            hp[i * 3 + 1] = 999999;
            this._cometTails.forEach(tail => {
              tail.positions[i * 3 + 1] = 999999;
            });
            return;
          }
          p.progress = (p.progress + p.speed) % 1;
          const pos  = p.curve.getPointAt(p.progress);
          hp[i * 3] = pos.x; hp[i * 3 + 1] = pos.y; hp[i * 3 + 2] = pos.z;

          this._cometTails.forEach(tail => {
            const tp = p.curve.getPointAt(Math.max(0, p.progress - tail.step * 0.015));
            tail.positions[i * 3] = tp.x; tail.positions[i * 3 + 1] = tp.y; tail.positions[i * 3 + 2] = tp.z;
          });
        });
        this._cometGroup.geometry.attributes.position.needsUpdate = true;
        this._cometTails.forEach(t => { t.points.geometry.attributes.position.needsUpdate = true; });
      }

      /* Galactic core light pulse */
      if (this._coreLight) this._coreLight.intensity = 1.0 + Math.sin(elapsed * 1.2) * 0.2;  // was 2.2 ± 0.4

      /* Nebula slow drift */
      this._nebulaGroups.forEach((ng, i) => { ng.rotation.y = elapsed * 0.012 * (i % 2 === 0 ? 1 : -1); });

      /* Camera fly-to */
      if (this._targetCamPos && this._camera) {
        this._camera.position.lerp(this._targetCamPos, lerp);
        if (this._camera.position.distanceTo(this._targetCamPos) < 1) this._targetCamPos = null;
      }
      if (this._targetCtrlTgt && this._controls) {
        this._controls.target.lerp(this._targetCtrlTgt, lerp);
        if (this._controls.target.distanceTo(this._targetCtrlTgt) < 1) this._targetCtrlTgt = null;
      }

      if (this._controls) this._controls.update();

      /* Render via composer (bloom) or plain */
      if (this._composer) {
        this._composer.render();
      } else if (this._renderer && this._scene && this._camera) {
        this._renderer.render(this._scene, this._camera);
      }
    }

    /* ─────────────────── Destroy / Cleanup ─────────────────── */

    destroy() {
      if (this._animId) { cancelAnimationFrame(this._animId); this._animId = null; }
      if (this._resizeObserver) { this._resizeObserver.disconnect(); this._resizeObserver = null; }
      if (this._scene) {
        this._scene.traverse(obj => {
          if (obj.geometry) obj.geometry.dispose();
          if (obj.material) {
            (Array.isArray(obj.material) ? obj.material : [obj.material]).forEach(m => {
              if (m.map) m.map.dispose(); m.dispose();
            });
          }
        });
      }
      if (this._composer) { try { this._composer.dispose(); } catch (_) {} this._composer = null; }
      if (this._controls) { this._controls.dispose(); this._controls = null; }
      if (this._renderer) { this._renderer.dispose(); this._renderer = null; }
      if (this._el)       { this._el.remove(); this._el = null; }
      this._scene = this._camera = this._clock = null;
      this._ambientLight = this._hemiLight = this._coreLight = this._dirLight = null;
      this._nodeMeshes = []; this._edgeLines = []; this._particles = [];
      this._cometTails = []; this._nebulaGroups = [];
      this._planeMeshes = []; this._edgeMap = new Map();
      this._highlightEdges = []; this._highlightNeighbors = [];
    }
  }

  window.Galaxy3DRenderer = Galaxy3DRenderer;
})(window);
