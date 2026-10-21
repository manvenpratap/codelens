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
  const BLOOM_STRENGTH = 1.15;       // rich celestial starlight bloom
  const BLOOM_RADIUS   = 0.50;
  const BLOOM_THRESH   = 0.48;       // allow emissive star cores and arcs to glow

  // 4 spiral arm base angles (radians)
  const ARM_OFFSETS = [0, Math.PI / 2, Math.PI, (3 * Math.PI) / 2];
  const SPIRAL_A    = 28;            // compact, cohesive spiral core
  const SPIRAL_B    = 0.16;          // balanced galactic disk curvature

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
      this._labelSprites    = [];          // billboard sprites for packages & top hubs
      this._showLabels      = true;
      this._pkgMeta         = new Map();   // pkgName → { cx, cy, cz, diskR, colorStr, colorHex }
      this._isTopDown       = false;

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
      this._orbitingMethods = [];          // Keplerian planetary satellite methods

      this._raycaster       = null;
      this._mouse           = null;
      this._hoveredMesh     = null;
      this._selectedMesh    = null;
      this._highlightEdges  = [];
      this._dimmedEdges     = [];
      this._highlightNeighbors = [];
      this._dimmedNodes     = [];
      this._tooltip         = null;
      this._toolbar         = null;
      this._telemetryCard   = null;
      this._isolatedPkg     = null;
      this._isolationBanner = null;
      this._heatData        = null;
      this._heatMode        = false;
      this._inDegreeMap     = new Map();
      this._outDegreeMap    = new Map();
      this._onKeyDown       = null;
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

    setHeatData(heatMap) {
      this._heatData = heatMap;
      if (this._heatMode) this._updateHeatColors();
    }

    toggleHeatMode() {
      this._heatMode = !this._heatMode;
      this._updateHeatColors();
      const btn = this._toolbar ? this._toolbar.querySelector('#hud-btn-heat') : null;
      if (btn) btn.classList.toggle('active', this._heatMode);
      return this._heatMode;
    }

    isolatePackage(pkgName) {
      if (!pkgName) return;
      this._isolatedPkg = pkgName;
      this._applyFilters();
      this.flyToPackage(pkgName);
      this._updateIsolationBanner();
    }

    exitIsolation() {
      this._isolatedPkg = null;
      this._applyFilters();
      this._updateIsolationBanner();
    }

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

    flyToNode(meshOrId) {
      if (!meshOrId || !this._camera || !this._controls) return;
      let mesh = meshOrId;
      if (typeof meshOrId === 'string') {
        mesh = this._nodeMeshes.find(m => m.userData.node && m.userData.node.id === meshOrId);
      }
      if (!mesh) return;
      const t = mesh.position.clone();
      this._targetCtrlTgt = t.clone();
      this._targetCamPos  = new THREE.Vector3(t.x + 35, t.y + 22, t.z + 45);
    }

    flyToPackage(pkgName) {
      if (!pkgName || !this._camera || !this._controls) return;
      let meta = this._pkgMeta.get(pkgName);
      if (!meta) {
        for (const [k, v] of this._pkgMeta.entries()) {
          if (k.toLowerCase() === pkgName.toLowerCase() || k.endsWith('.' + pkgName) || pkgName.endsWith('.' + k)) {
            meta = v;
            break;
          }
        }
      }
      if (!meta) return;
      const { cx, cy, cz, diskR } = meta;
      this._targetCtrlTgt = new THREE.Vector3(cx, cy, cz);
      this._targetCamPos = new THREE.Vector3(cx + diskR * 0.85, cy + diskR * 0.70 + 35, cz + diskR * 1.05);
    }

    toggleLabels(v) {
      if (v !== undefined) this._showLabels = Boolean(v);
      else this._showLabels = !this._showLabels;
      this._applyFilters();
      return this._showLabels;
    }

    toggleTopDown() {
      this._isTopDown = !this._isTopDown;
      if (this._isTopDown) {
        this._targetCamPos = new THREE.Vector3(0, 750, 0.01);
        this._targetCtrlTgt = new THREE.Vector3(0, 0, 0);
      } else {
        this._targetCamPos = new THREE.Vector3(0, 200, 550);
        this._targetCtrlTgt = new THREE.Vector3(0, 0, 0);
      }
      return this._isTopDown;
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
      if (this._renderer)    this._renderer.toneMappingExposure = 1.20 * f;
      if (this._ambientLight) this._ambientLight.intensity = 0.65 * f;
      if (this._hemiLight)    this._hemiLight.intensity    = 0.55 * f;
      if (this._coreLight)    this._coreLight.intensity    = 2.5  * f;
      if (this._dirLight)     this._dirLight.intensity     = 0.65 * f;
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

      this._telemetryCard = document.createElement('div');
      this._telemetryCard.className = 'galaxy3d-telemetry-card';
      Object.assign(this._telemetryCard.style, {
        position: 'absolute',
        bottom: '68px',
        right: '16px',
        width: '280px',
        background: 'rgba(5, 9, 20, 0.88)',
        backdropFilter: 'blur(20px)',
        WebkitBackdropFilter: 'blur(20px)',
        border: '1px solid rgba(56, 189, 248, 0.25)',
        borderRadius: '12px',
        padding: '12px 14px',
        boxShadow: '0 16px 40px rgba(0, 0, 0, 0.6), 0 0 24px rgba(56, 189, 248, 0.08)',
        display: 'none',
        zIndex: '140',
        pointerEvents: 'auto',
        fontFamily: 'Sora, sans-serif',
        color: '#f8fafc',
      });
      this._el.appendChild(this._telemetryCard);

      this._onKeyDown = (e) => {
        if (e.target && (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA')) return;
        if (e.key === 'Escape') {
          if (this._isolatedPkg) this.exitIsolation();
          else this.resetView();
        } else if (e.key === 'h' || e.key === 'H') {
          this.toggleHeatMode();
        } else if (e.key === 'i' || e.key === 'I') {
          if (this._hoveredMesh && this._hoveredMesh.userData.pkg) {
            this.isolatePackage(this._hoveredMesh.userData.pkg);
          } else if (this._isolatedPkg) {
            this.exitIsolation();
          }
        } else if (e.key === ' ') {
          e.preventDefault();
          this.toggleAutoRotate();
        }
      };
      window.addEventListener('keydown', this._onKeyDown);

      const W = this._el.clientWidth  || 900;
      const H = this._el.clientHeight || 600;

      // Scene
      this._scene = new THREE.Scene();
      this._scene.fog = new THREE.Fog(0x02040a, 450, 2600);

      // Camera
      this._camera = new THREE.PerspectiveCamera(45, W / H, 1, 4000);
      this._camera.position.set(0, 180, 480);

      // Renderer
      this._renderer = new THREE.WebGLRenderer({ antialias: true, powerPreference: 'high-performance' });
      this._renderer.setSize(W, H);
      this._renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
      if (THREE.sRGBEncoding) this._renderer.outputEncoding = THREE.sRGBEncoding;
      this._renderer.toneMapping = THREE.ACESFilmicToneMapping;
      this._renderer.toneMappingExposure = 1.20;
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
      this._ambientLight = new THREE.AmbientLight(0x0e172a, 0.65);
      this._scene.add(this._ambientLight);

      this._hemiLight = new THREE.HemisphereLight(0x38bdf8, 0x0a0d1a, 0.55);
      this._scene.add(this._hemiLight);

      this._coreLight = new THREE.PointLight(0xfbbf24, 2.5, 1200, 1.4);
      this._coreLight.position.set(0, 0, 0);
      this._scene.add(this._coreLight);

      this._dirLight = new THREE.DirectionalLight(0x34d399, 0.65);
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

    _getArchitecturalTier(rawNode, id, pkg) {
      if (window.CodeLensClassifier) {
        const arch = window.CodeLensClassifier.classifyType(rawNode, id, pkg);
        if (arch) {
          const b = (arch.badge || '').toUpperCase();
          const r = (arch.role || '').toUpperCase();
          if (b.includes('CTRL') || b.includes('API') || b.includes('REST') || b.includes('GATEWAY') || r.includes('CONTROLLER') || r.includes('ENDPOINT')) {
            return { tier: 1, name: 'Upper Orbital Cloud (Controller / API)', yOffset: 26 };
          }
          if (b.includes('SRV') || b.includes('LOGIC') || b.includes('CORE') || b.includes('DOMAIN') || b.includes('WORKFLOW') || r.includes('SERVICE') || r.includes('BUSINESS')) {
            return { tier: 2, name: 'Equatorial Disc (Service / Domain Logic)', yOffset: 0 };
          }
          if (b.includes('REPO') || b.includes('DAO') || b.includes('STORE') || b.includes('DATA') || b.includes('SQL') || r.includes('REPOSITORY') || r.includes('PERSISTENCE')) {
            return { tier: 3, name: 'Deep Gravity Well (Repository / Persistence)', yOffset: -28 };
          }
          if (b.includes('MODEL') || b.includes('ENTITY') || b.includes('DTO') || b.includes('SCHEMA')) {
            return { tier: 2.5, name: 'Mid-Low Orbit (Model / Entity)', yOffset: -12 };
          }
        }
      }
      const s = ((rawNode && (rawNode.label || rawNode.simpleName)) || id || '').toLowerCase();
      if (s.endsWith('controller') || s.endsWith('resource') || s.endsWith('endpoint') || s.includes('gateway') || s.includes('api')) {
        return { tier: 1, name: 'Upper Orbital Cloud (Controller / API)', yOffset: 26 };
      }
      if (s.endsWith('service') || s.endsWith('manager') || s.endsWith('handler') || s.endsWith('engine') || s.endsWith('processor')) {
        return { tier: 2, name: 'Equatorial Disc (Service / Domain Logic)', yOffset: 0 };
      }
      if (s.endsWith('repository') || s.endsWith('dao') || s.includes('store') || s.includes('mapper') || s.includes('database')) {
        return { tier: 3, name: 'Deep Gravity Well (Repository / Persistence)', yOffset: -28 };
      }
      if (s.endsWith('model') || s.endsWith('entity') || s.endsWith('dto') || s.endsWith('vo')) {
        return { tier: 2.5, name: 'Mid-Low Orbit (Model / Entity)', yOffset: -12 };
      }
      return { tier: 2, name: 'Equatorial Disc (Domain Logic)', yOffset: 0 };
    }

    _updateHeatColors() {
      const heat = this._heatData || {};
      const maxCommits = Math.max(1, ...Object.values(heat));

      this._nodeMeshes.forEach(m => {
        const d = m.userData;
        if (!d || !d.node) return;
        const id = d.node.id;
        const fqn = d.node.raw ? (d.node.raw.fqn || id) : id;
        const commits = (heat[id] || heat[fqn] || 0);

        if (!this._heatMode) {
          m.material.color.setHex(d.colorHex || 0x34d399);
          m.material.emissive.setHex(d.colorHex || 0x34d399);
          m.material.emissiveIntensity = d.isMethod ? 0.75 : 1.15;
          if (d.haloMesh) {
            d.haloMesh.material.color.setHex(d.colorHex || 0x34d399);
            d.haloMesh.scale.set(1, 1, 1);
          }
        } else {
          const ratio = Math.min(1.0, commits / Math.max(5, maxCommits * 0.7));
          let heatHex, heatEmissive;
          if (ratio > 0.65) {
            heatHex = 0x67e8f9;
            heatEmissive = 2.2;
            if (d.haloMesh) d.haloMesh.scale.set(1.4, 1.4, 1.4);
          } else if (ratio > 0.35) {
            heatHex = 0xfbbf24;
            heatEmissive = 1.6;
            if (d.haloMesh) d.haloMesh.scale.set(1.2, 1.2, 1.2);
          } else if (ratio > 0.08) {
            heatHex = 0xf43f5e;
            heatEmissive = 1.2;
            if (d.haloMesh) d.haloMesh.scale.set(1.0, 1.0, 1.0);
          } else {
            heatHex = 0x64748b;
            heatEmissive = 0.55;
            if (d.haloMesh) d.haloMesh.scale.set(0.85, 0.85, 0.85);
          }

          m.material.color.setHex(heatHex);
          m.material.emissive.setHex(heatHex);
          m.material.emissiveIntensity = heatEmissive;
          if (d.haloMesh) d.haloMesh.material.color.setHex(heatHex);
        }
      });
    }

    _updateIsolationBanner() {
      if (!this._isolationBanner) {
        this._isolationBanner = document.createElement('div');
        this._isolationBanner.className = 'galaxy3d-isolation-banner';
        Object.assign(this._isolationBanner.style, {
          position: 'absolute',
          top: '16px',
          left: '50%',
          transform: 'translateX(-50%)',
          zIndex: '150',
          display: 'none',
          alignItems: 'center',
          gap: '10px',
          background: 'rgba(5, 9, 20, 0.88)',
          backdropFilter: 'blur(16px)',
          WebkitBackdropFilter: 'blur(16px)',
          border: '1px solid rgba(56, 189, 248, 0.4)',
          borderRadius: '9999px',
          padding: '6px 16px',
          boxShadow: '0 8px 32px rgba(0, 0, 0, 0.7), 0 0 20px rgba(56, 189, 248, 0.15)',
          fontFamily: 'Sora, sans-serif',
          fontSize: '12px',
          color: '#f8fafc',
        });
        this._el.appendChild(this._isolationBanner);
      }

      if (this._isolatedPkg) {
        const dispName = (typeof formatPackageDisplayName === 'function')
          ? formatPackageDisplayName(this._isolatedPkg)
          : this._isolatedPkg;
        this._isolationBanner.innerHTML = `
          <span style="display:flex;align-items:center;gap:6px;">
            <svg class="svg-icon icon-xs icon-cyan" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="width:13px;height:13px;"><circle cx="12" cy="12" r="5"/><ellipse cx="12" cy="12" rx="10" ry="3.5" transform="rotate(-25 12 12)"/></svg>
            <span>Solo System: <strong style="color:#f8fafc;">${dispName}</strong></span>
          </span>
          <button id="galaxy3d-exit-iso-btn" class="hud-btn" style="height:22px;padding:2px 8px;font-size:10px;background:rgba(239,68,68,0.2);border-color:rgba(239,68,68,0.4);color:#fca5a5;cursor:pointer;display:inline-flex;align-items:center;gap:4px;">
            <svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="width:10px;height:10px;"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg> Exit Solo
          </button>
        `;
        const exitBtn = this._isolationBanner.querySelector('#galaxy3d-exit-iso-btn');
        if (exitBtn) exitBtn.onclick = () => this.exitIsolation();
        this._isolationBanner.style.display = 'flex';
      } else {
        this._isolationBanner.style.display = 'none';
      }
    }

    _updateTelemetryCard(mesh) {
      if (!this._telemetryCard) return;
      if (!mesh || !mesh.userData || !mesh.userData.node) {
        if (!this._selectedMesh) this._telemetryCard.style.display = 'none';
        return;
      }

      const d = mesh.userData;
      const raw = d.node.raw || {};
      const id = d.node.id || '';
      const fqn = raw.fqn || id;
      const title = raw.label || raw.simpleName || (id.includes('.') ? id.split('.').pop() : id);
      const pkg = d.pkg || '';
      const isMeth = d.isMethod;

      let archBadgeHtml = '';
      if (window.CodeLensClassifier) {
        const arch = isMeth
          ? window.CodeLensClassifier.classifyMethod(raw, id, pkg)
          : window.CodeLensClassifier.classifyType(raw, id, pkg);
        if (arch) {
          archBadgeHtml = `<span style="background:${arch.color}22;border:1px solid ${arch.color}66;color:${arch.color};font-size:10px;padding:2px 7px;border-radius:4px;font-weight:600;white-space:nowrap;">${arch.icon} ${arch.badge}</span>`;
        }
      }

      const inDeg = this._inDegreeMap.get(id) || 0;
      const outDeg = this._outDegreeMap.get(id) || 0;
      const totDeg = inDeg + outDeg;
      const instability = totDeg > 0 ? (outDeg / totDeg).toFixed(2) : '0.00';
      const numInstab = parseFloat(instability);
      let instabColor = '#34d399';
      let instabText = 'Stable Bedrock';
      if (numInstab > 0.65) {
        instabColor = '#f43f5e';
        instabText = 'Fragile / High Out';
      } else if (numInstab > 0.3) {
        instabColor = '#fbbf24';
        instabText = 'Balanced Logic';
      }

      const tierName = d.tierMeta ? d.tierMeta.name : (isMeth ? 'Planetary Satellite Orbit' : 'Equatorial Disc');

      const heat = this._heatData || {};
      const commits = heat[id] || heat[fqn] || 0;
      const commitsHtml = commits > 0
        ? `<span style="color:#f43f5e;font-weight:700;"><svg class="svg-icon icon-xs icon-rose" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="display:inline-block;vertical-align:-1px;margin-right:2px;"><path d="M8.5 14.5A2.5 2.5 0 0 0 11 12c0-1.38-.5-2-1-3-1.072-2.143-.224-4.054 2-6 .5 2.5 2 4.9 4 6.5 2 1.6 3 3.5 3 5.5a7 7 0 1 1-14 0c0-1.153.433-2.294 1-3a2.5 2.5 0 0 0 2.5 2.5z"/></svg>${commits} commits</span>`
        : `<span style="color:#64748b;"><svg class="svg-icon icon-xs icon-slate" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="display:inline-block;vertical-align:-1px;margin-right:2px;"><circle cx="12" cy="12" r="10"/><path d="m9 12 2 2 4-4"/></svg>Stable</span>`;

      this._telemetryCard.innerHTML = `
        <div style="display:flex;align-items:flex-start;justify-content:space-between;gap:8px;">
          <div>
            <div style="font-weight:700;font-size:13px;color:#f8fafc;line-height:1.2;">${title}</div>
            <div style="font-size:10px;color:#94a3b8;font-family:'JetBrains Mono',monospace;margin-top:2px;">${pkg}</div>
          </div>
          ${archBadgeHtml}
        </div>

        <div style="margin-top:8px;font-size:10px;padding:3px 7px;background:rgba(255,255,255,0.04);border-radius:6px;border:1px solid rgba(255,255,255,0.06);color:#cbd5e1;display:flex;align-items:center;gap:5px;">
          <svg class="svg-icon icon-xs icon-cyan" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="6"/><ellipse cx="12" cy="12" rx="10" ry="3.5" transform="rotate(-20 12 12)"/></svg>
          <span><span style="font-weight:600;">Stratum:</span> ${tierName}</span>
        </div>

        <div style="display:grid;grid-template-columns:1fr 1fr;gap:6px;margin-top:8px;padding-top:8px;border-top:1px solid rgba(255,255,255,0.08);font-size:11px;font-family:'JetBrains Mono',monospace;">
          <div><span style="color:#64748b;">Callers (In):</span> <strong style="color:#fbbf24;">${inDeg}</strong></div>
          <div><span style="color:#64748b;">Callees (Out):</span> <strong style="color:#38bdf8;">${outDeg}</strong></div>
          <div style="grid-column:span 2;"><span style="color:#64748b;">Instability (I):</span> <span style="color:${instabColor};font-weight:600;">${instabText} (${instability})</span></div>
          <div style="grid-column:span 2;"><span style="color:#64748b;">Velocity:</span> ${commitsHtml}</div>
        </div>

        <div style="display:flex;gap:5px;margin-top:10px;padding-top:8px;border-top:1px solid rgba(255,255,255,0.08);">
          <button class="hud-btn" id="telemetry-act-isolate" style="flex:1;justify-content:center;font-size:10px;height:22px;"><svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="6"/><ellipse cx="12" cy="12" rx="10" ry="3.5" transform="rotate(-20 12 12)"/></svg> Isolate</button>
          <button class="hud-btn" id="telemetry-act-inspect" style="flex:1;justify-content:center;font-size:10px;height:22px;"><svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/></svg> Inspect</button>
          <button class="hud-btn" id="telemetry-act-copy" style="flex:1;justify-content:center;font-size:10px;height:22px;"><svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect width="8" height="4" x="8" y="2" rx="1" ry="1"/><path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"/></svg> Copy</button>
        </div>
      `;

      const isoBtn = this._telemetryCard.querySelector('#telemetry-act-isolate');
      if (isoBtn) isoBtn.onclick = (e) => {
        e.stopPropagation();
        this.isolatePackage(pkg);
      };

      const inspBtn = this._telemetryCard.querySelector('#telemetry-act-inspect');
      if (inspBtn) inspBtn.onclick = (e) => {
        e.stopPropagation();
        if (this._onSelectEntity) this._onSelectEntity(id || fqn);
        else if (window.selectEntity) window.selectEntity(id || fqn);
      };

      const copyBtn = this._telemetryCard.querySelector('#telemetry-act-copy');
      if (copyBtn) copyBtn.onclick = (e) => {
        e.stopPropagation();
        navigator.clipboard.writeText(fqn || id).then(() => {
          copyBtn.innerHTML = '<svg class="svg-icon icon-xs icon-emerald" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg> Copied!';
          setTimeout(() => {
            if (copyBtn) copyBtn.innerHTML = '<svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect width="8" height="4" x="8" y="2" rx="1" ry="1"/><path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"/></svg> Copy';
          }, 1500);
        });
      };

      this._telemetryCard.style.display = 'block';
    }

    _createLabelSprite(text, colorStr, isPackage = true) {
      const canvas = document.createElement('canvas');
      const ctx = canvas.getContext('2d');

      const fontSize = isPackage ? 26 : 20;
      const font = `600 ${fontSize}px Sora, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif`;
      ctx.font = font;

      const metrics = ctx.measureText(text);
      const textWidth = Math.ceil(metrics.width);
      const textHeight = Math.ceil(fontSize * 1.3);

      const padX = isPackage ? 14 : 10;
      const padY = isPackage ? 7 : 5;
      const w = textWidth + padX * 2;
      const h = textHeight + padY * 2;

      // Retina 2x supersampling for crisp in-world text
      const scale = 2;
      canvas.width = w * scale;
      canvas.height = h * scale;
      ctx.scale(scale, scale);

      // Pill background
      const r = isPackage ? 8 : 6;
      ctx.fillStyle = isPackage ? 'rgba(5, 9, 20, 0.82)' : 'rgba(10, 15, 29, 0.78)';
      ctx.strokeStyle = colorStr || (isPackage ? '#38bdf8' : '#94a3b8');
      ctx.lineWidth = isPackage ? 1.8 : 1.2;

      ctx.beginPath();
      if (typeof ctx.roundRect === 'function') {
        ctx.roundRect(1, 1, w - 2, h - 2, r);
      } else {
        ctx.rect(1, 1, w - 2, h - 2);
      }
      ctx.fill();
      ctx.stroke();

      // Accent dot
      const dotR = isPackage ? 3.5 : 2.5;
      ctx.fillStyle = colorStr || '#38bdf8';
      ctx.beginPath();
      ctx.arc(padX + dotR, h / 2, dotR, 0, Math.PI * 2);
      ctx.fill();

      // Text
      ctx.font = font;
      ctx.textAlign = 'left';
      ctx.textBaseline = 'middle';
      ctx.fillStyle = '#f8fafc';
      ctx.fillText(text, padX + dotR * 2 + 6, h / 2 + 1);

      const texture = new THREE.CanvasTexture(canvas);
      texture.minFilter = THREE.LinearFilter;
      texture.generateMipmaps = false;

      const mat = new THREE.SpriteMaterial({
        map: texture,
        transparent: true,
        depthTest: false,
        depthWrite: false,
      });

      const sprite = new THREE.Sprite(mat);
      const worldHeight = isPackage ? 14.0 : 8.5;
      const aspect = w / h;
      sprite.scale.set(worldHeight * aspect, worldHeight, 1);
      return sprite;
    }

    _buildGalaxy() {
      if (!this._data || !this._scene) return;

      const rawNodes = this._data.nodes || [];
      const rawEdges = this._data.edges || [];
      if (!rawNodes.length) return;

      this._nodeMeshes   = [];
      this._edgeLines    = [];
      this._edgeMap      = new Map();
      this._particles    = [];
      this._planeMeshes  = [];
      this._nebulaGroups = [];
      this._labelSprites = [];
      this._pkgMeta      = new Map();

      // Pre-calculate node degree (in-degree + out-degree) for sizing & hierarchy
      const degreeMap = new Map();
      this._inDegreeMap  = new Map();
      this._outDegreeMap = new Map();
      rawEdges.forEach(e => {
        const s = e.source || e.caller;
        const t = e.target || e.callee;
        if (s) {
          degreeMap.set(s, (degreeMap.get(s) || 0) + 1);
          this._outDegreeMap.set(s, (this._outDegreeMap.get(s) || 0) + 1);
        }
        if (t) {
          degreeMap.set(t, (degreeMap.get(t) || 0) + 1);
          this._inDegreeMap.set(t, (this._inDegreeMap.get(t) || 0) + 1);
        }
      });

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
          const theta   = ARM_OFFSETS[armIdx] + armStep * 1.2;
          const r       = SPIRAL_A * Math.exp(SPIRAL_B * theta);
          cx = r * Math.cos(theta);
          cz = r * Math.sin(theta);
          cy = (armIdx % 2 === 0 ? 1 : -1) * armStep * 8;
        }

        /* Separate classes and methods */
        const classNodes  = pkgNodes.filter(n => n.type !== 'METHOD' && n.kind !== 'METHOD' && !n.id.includes('('));
        const methodNodes = pkgNodes.filter(n => n.type === 'METHOD'  || n.kind === 'METHOD'  || n.id.includes('('));

        /* Place class "star" nodes in a fan ring around the package centre */
        // Stratify vertically by architectural tier (Controllers high, Services mid, Repositories in gravity well)
        const ringR = Math.max(26, 14 * Math.sqrt(classNodes.length));
        classNodes.forEach((n, ci) => {
          const angle = (ci / Math.max(1, classNodes.length)) * Math.PI * 2;
          const r     = ringR + (ci % 3) * 8;
          const px    = cx + r * Math.cos(angle);
          const tierMeta = this._getArchitecturalTier(n, n.id, pkgName);
          const py    = cy + tierMeta.yOffset + (ci % 2 === 0 ? 3 : -3);
          const pz    = cz + r * Math.sin(angle);

          const obj = { id: n.id, label: n.label || n.simpleName || n.id.split('.').pop(), raw: n,
                        x: px, y: py, z: pz, pkg: pkgName, colorStr: colorStr, colorHex: colorHex, isMethod: false,
                        tierMeta: tierMeta };
          this._nodes.push(obj);
          nodeMap.set(n.id, obj);
        });

        /* Place method "planet" nodes: group by parent class and share the module/class color */
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
            const orbitR = 15 + Math.floor(mi / 6) * 10;
            const orbitA = ((mi % 6) / 6) * Math.PI * 2 + Math.floor(mi / 6) * 0.7;
            const px = subCx + orbitR * Math.cos(orbitA);
            const py = subCy + 4 * Math.sin(orbitA * 0.5);
            const pz = subCz + orbitR * Math.sin(orbitA);

            const obj = { id: n.id, label: (n.label || n.simpleName || n.id.split('(')[0].split('.').pop()) + '()',
                          raw: n, x: px, y: py, z: pz, pkg: pkgName, parentClass: clsKey,
                          colorStr: colorStr, colorHex: colorHex, isMethod: true,
                          orbitR: orbitR, orbitA: orbitA, orbitIndex: mi };
            this._nodes.push(obj);
            nodeMap.set(n.id, obj);
          });
        });

        /* Package ethereal boundary rings */
        const pkgAll  = pkgNodes.map(n => nodeMap.get(n.id)).filter(Boolean);
        let diskR = 30;
        pkgAll.forEach(no => {
          const d = Math.sqrt((no.x - cx) ** 2 + (no.z - cz) ** 2);
          if (d + 16 > diskR) diskR = d + 16;
        });

        // Store package metadata for camera navigation
        this._pkgMeta.set(pkgName, { cx, cy, cz, diskR, colorStr, colorHex });

        // Outer ethereal orbital boundary ring
        const ringGeo = new THREE.RingGeometry(diskR - 0.6, diskR, 80);
        const ringMat = new THREE.MeshBasicMaterial({
          color: colorHex,
          transparent: true,
          opacity: 0.32,
          side: THREE.DoubleSide,
          depthWrite: false,
          blending: THREE.AdditiveBlending,
        });
        const ring = new THREE.Mesh(ringGeo, ringMat);
        ring.rotation.x = Math.PI / 2;
        ring.position.set(cx, cy - 2, cz);
        ring.userData = { pkg: pkgName };
        this._scene.add(ring);
        this._planeMeshes.push(ring);

        // Inner concentric orbit guide ring
        const innerGeo = new THREE.RingGeometry(diskR * 0.52 - 0.4, diskR * 0.52, 64);
        const innerMat = new THREE.MeshBasicMaterial({
          color: colorHex,
          transparent: true,
          opacity: 0.16,
          side: THREE.DoubleSide,
          depthWrite: false,
        });
        const innerRing = new THREE.Mesh(innerGeo, innerMat);
        innerRing.rotation.x = Math.PI / 2;
        innerRing.position.set(cx, cy - 2, cz);
        innerRing.userData = { pkg: pkgName };
        this._scene.add(innerRing);
        this._planeMeshes.push(innerRing);

        // Faint translucent floor glow circle
        const glowGeo = new THREE.CircleGeometry(diskR, 64);
        const glowMat = new THREE.MeshBasicMaterial({
          color: colorHex,
          transparent: true,
          opacity: 0.055,
          side: THREE.DoubleSide,
          depthWrite: false,
        });
        const glowDisc = new THREE.Mesh(glowGeo, glowMat);
        glowDisc.rotation.x = Math.PI / 2;
        glowDisc.position.set(cx, cy - 2.1, cz);
        glowDisc.userData = { pkg: pkgName };
        this._scene.add(glowDisc);
        this._planeMeshes.push(glowDisc);

        // Nebula dust cloud (additive cosmic dust)
        this._buildNebulaCloud(cx, cy, cz, diskR, colorHex, pkgName);

        // Floating 3D in-world package billboard sprite
        const displayPkg = (typeof formatPackageDisplayName === 'function')
          ? formatPackageDisplayName(pkgName)
          : (pkgName.split('.').pop() || pkgName);
        const pkgSprite = this._createLabelSprite(displayPkg, colorStr, true);
        pkgSprite.position.set(cx, cy + 14, cz);
        pkgSprite.userData = { pkg: pkgName, isPackageLabel: true };
        this._scene.add(pkgSprite);
        this._labelSprites.push(pkgSprite);
      });

      /* ── 3. Build node sphere meshes ─────────────────────── */
      const nodeMeshMap = new Map();
      this._orbitingMethods = [];

      this._nodes.forEach(n => {
        const isMeth = n.isMethod;
        const deg    = degreeMap.get(n.id) || 0;
        let radius   = isMeth
          ? Math.max(1.1, Math.min(2.2, 1.1 + (deg > 3 ? 0.35 : 0)))
          : Math.max(3.2, Math.min(7.5, 3.2 + Math.sqrt(deg) * 0.65));
        let hex      = n.colorHex;
        let emissiveInt = isMeth ? 1.4 : 2.2;

        // Apply Git Churn Stellar Spectrometry if heat mode is active
        if (this._heatMode && !isMeth) {
          const heat = this._heatData || {};
          const maxCommits = Math.max(1, ...Object.values(heat));
          const commits = (heat[n.id] || (n.raw && heat[n.raw.fqn])) || 0;
          const ratio = Math.min(1.0, commits / Math.max(5, maxCommits * 0.7));
          if (ratio > 0.65) {
            hex = 0x67e8f9; emissiveInt = 2.8; radius *= 1.25;
          } else if (ratio > 0.35) {
            hex = 0xfbbf24; emissiveInt = 2.2; radius *= 1.15;
          } else if (ratio > 0.08) {
            hex = 0xf43f5e; emissiveInt = 1.7;
          } else {
            hex = 0x64748b; emissiveInt = 0.85;
          }
        }

        // Radiant celestial star: self-luminous, soft starlight core
        const mat = new THREE.MeshStandardMaterial({
          color: hex,
          emissive: hex,
          emissiveIntensity: emissiveInt,
          roughness: 0.35,
          metalness: 0.10,
        });
        const mesh = new THREE.Mesh(new THREE.SphereGeometry(radius, isMeth ? 14 : 24, isMeth ? 14 : 24), mat);
        mesh.position.set(n.x, n.y, n.z);

        let haloMesh = null;
        // Ethereal starlight corona aura
        const hMat = new THREE.MeshBasicMaterial({
          color: hex,
          transparent: true,
          opacity: isMeth ? 0.18 : 0.28,
          side: THREE.BackSide,
          depthWrite: false,
          blending: THREE.AdditiveBlending,
        });
        haloMesh = new THREE.Mesh(new THREE.SphereGeometry(radius * (isMeth ? 2.0 : 2.5), 16, 16), hMat);
        haloMesh.position.copy(mesh.position);
        this._scene.add(haloMesh);

        mesh.userData = {
          node: n,
          colorStr: n.colorStr,
          colorHex: n.colorHex,
          pkg: n.pkg,
          isMethod: isMeth,
          haloMesh: haloMesh,
          deg: deg,
          baseRadius: radius,
          tierMeta: n.tierMeta,
        };
        this._scene.add(mesh);
        this._nodeMeshes.push(mesh);
        nodeMeshMap.set(n.id, mesh);

        // Keplerian orbiting method satellite setup
        if (isMeth && n.parentClass) {
          const parentMesh = nodeMeshMap.get(n.parentClass);
          this._orbitingMethods.push({
            mesh: mesh,
            parentMesh: parentMesh,
            orbitR: n.orbitR || 18,
            baseOrbitA: n.orbitA || 0,
            speed: (0.16 + ((n.orbitIndex || 0) % 3) * 0.05) * ((n.orbitIndex || 0) % 2 === 0 ? 1 : -1),
            bobSpeed: 0.6 + ((n.orbitIndex || 0) % 3) * 0.2,
          });
        }
      });

      // Top hub superstar labels (top 8 classes by degree for immediate architectural orientation)
      const classStars = this._nodes.filter(n => !n.isMethod);
      classStars.sort((a, b) => (degreeMap.get(b.id) || 0) - (degreeMap.get(a.id) || 0));
      const topSuperstars = classStars.slice(0, 8).filter(n => (degreeMap.get(n.id) || 0) >= 2);

      topSuperstars.forEach(sn => {
        const simpleName = sn.label || sn.simpleName || (sn.id.includes('.') ? sn.id.split('.').pop() : sn.id);
        const starSprite = this._createLabelSprite(simpleName, sn.colorStr, false);
        const mesh = nodeMeshMap.get(sn.id);
        const r = mesh ? mesh.userData.baseRadius : 2.0;
        starSprite.position.set(sn.x, sn.y + r + 4.2, sn.z);
        starSprite.userData = { pkg: sn.pkg, nodeId: sn.id, isClassLabel: true };
        this._scene.add(starSprite);
        this._labelSprites.push(starSprite);
      });

      /* ── 4. Build bezier edge arcs (Constellation Mode) ──── */
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
        mid.x        += (Math.random() - 0.5) * 8;
        mid.z        += (Math.random() - 0.5) * 8;

        const curve  = new THREE.QuadraticBezierCurve3(p1, mid, p2);
        const pts    = curve.getPoints(24);
        const geo    = new THREE.BufferGeometry().setFromPoints(pts);

        // Default state: luminous, atmospheric cosmic threads
        const arcCol = cross ? 0x38bdf8 : src.colorHex;
        const arcOp  = cross ? 0.28 : 0.18;

        const lineMat = new THREE.LineBasicMaterial({
          color: arcCol,
          transparent: true,
          opacity: arcOp,
          depthWrite: false,
          blending: THREE.AdditiveBlending,
        });
        const line    = new THREE.Line(geo, lineMat);
        line.userData = { src: src.id, tgt: tgt.id, srcPkg: src.pkg, tgtPkg: tgt.pkg, origColor: arcCol, origOpacity: arcOp, cross: cross };
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

      /* ── 8. Automatically frame galaxy comfortably in viewport */
      setTimeout(() => { this.fitToScreen(); }, 20);
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
        color: colorHex, size: 24 + Math.random() * 14,
        transparent: true, opacity: 0.14,
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
        color: 0x6ee7b7, size: 3.2, transparent: true, opacity: 0.95,
        depthWrite: false, blending: THREE.AdditiveBlending, sizeAttenuation: true,
      });
      this._cometGroup     = new THREE.Points(headGeo, headMat);
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
          size: Math.max(0.8, 3.2 - t * 0.45),
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
        display: 'flex', alignItems: 'center', gap: '6px', zIndex: '120',
        background: 'rgba(5, 8, 16, 0.78)',
        backdropFilter: 'blur(16px)',
        WebkitBackdropFilter: 'blur(16px)',
        border: '1px solid rgba(255, 255, 255, 0.10)',
        borderRadius: '9999px',
        padding: '5px 10px',
        boxShadow: '0 8px 32px rgba(0, 0, 0, 0.55)',
      });

      const search = document.createElement('input');
      search.type        = 'text';
      search.placeholder = 'Filter nodes...';
      search.className   = 'city3d-search-box';
      search.value       = this._filterQuery;
      search.style.width = '115px';
      search.style.height = '24px';
      search.style.fontSize = '11px';
      search.style.borderRadius = '9999px';
      search.style.padding = '0 10px';
      search.addEventListener('input', e => this.setFilter(e.target.value));
      this._toolbar.appendChild(search);

      const orbitBtn = document.createElement('button');
      orbitBtn.className = 'hud-btn' + (this._autoRotate ? ' active' : '');
      orbitBtn.innerHTML = '<span class="hud-btn-icon"><svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12a9 9 0 1 1-9-9c2.52 0 4.85.83 6.72 2.24L21 8"/><path d="M21 3v5h-5"/></svg></span> <span class="hud-btn-text">Orbit</span>';
      orbitBtn.title = 'Toggle Galactic Orbit rotation';
      orbitBtn.addEventListener('click', () => {
        this.toggleAutoRotate();
        orbitBtn.classList.toggle('active', this._autoRotate);
      });
      this._toolbar.appendChild(orbitBtn);

      const viewBtn = document.createElement('button');
      viewBtn.className = 'hud-btn' + (this._isTopDown ? ' active' : '');
      viewBtn.innerHTML = '<span class="hud-btn-icon"><svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><circle cx="12" cy="12" r="8"/></svg></span> <span class="hud-btn-text">' + (this._isTopDown ? '3D View' : 'Top-Down') + '</span>';
      viewBtn.title = 'Switch between Top-Down map and 3D Celestial view';
      viewBtn.addEventListener('click', () => {
        const isTop = this.toggleTopDown();
        viewBtn.classList.toggle('active', isTop);
        viewBtn.querySelector('.hud-btn-text').textContent = isTop ? '3D View' : 'Top-Down';
      });
      this._toolbar.appendChild(viewBtn);

      const labelBtn = document.createElement('button');
      labelBtn.className = 'hud-btn' + (this._showLabels ? ' active' : '');
      labelBtn.innerHTML = '<span class="hud-btn-icon">Aa</span> <span class="hud-btn-text">Labels</span>';
      labelBtn.title = 'Toggle 3D Floating Package & Hub Labels';
      labelBtn.addEventListener('click', () => {
        const on = this.toggleLabels();
        labelBtn.classList.toggle('active', on);
      });
      this._toolbar.appendChild(labelBtn);

      const ringsBtn = document.createElement('button');
      ringsBtn.className = 'hud-btn' + (this._showPlanes ? ' active' : '');
      ringsBtn.innerHTML = '<span class="hud-btn-icon"><svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><ellipse cx="12" cy="12" rx="10" ry="5"/></svg></span> <span class="hud-btn-text">Rings</span>';
      ringsBtn.title = 'Toggle Ethereal Orbital Boundary Rings';
      ringsBtn.addEventListener('click', () => {
        this.togglePlanes();
        ringsBtn.classList.toggle('active', this._showPlanes);
      });
      this._toolbar.appendChild(ringsBtn);

      const arcsBtn = document.createElement('button');
      arcsBtn.className = 'hud-btn' + (this._showArcs ? ' active' : '');
      arcsBtn.innerHTML = '<span class="hud-btn-icon"><svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 19a14 14 0 0 1 14-14m0 0v6m0-6h-6"/></svg></span> <span class="hud-btn-text">Arcs</span>';
      arcsBtn.title = 'Toggle Call Arcs';
      arcsBtn.addEventListener('click', () => {
        const on = this.toggleArcs();
        arcsBtn.classList.toggle('active', on);
      });
      this._toolbar.appendChild(arcsBtn);

      const churnBtn = document.createElement('button');
      churnBtn.id = 'hud-btn-heat';
      churnBtn.className = 'hud-btn' + (this._heatMode ? ' active' : '');
      churnBtn.innerHTML = '<span class="hud-btn-icon"><svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M8.5 14.5A2.5 2.5 0 0 0 11 12c0-1.38-.5-2-1-3-1.072-2.143-.224-4.054 2-6 .5 2.5 2 4.9 4 6.5 2 1.6 3 3.5 3 5.5a7 7 0 1 1-14 0c0-1.153.433-2.294 1-3a2.5 2.5 0 0 0 2.5 2.5z"/></svg></span> <span class="hud-btn-text">Churn</span>';
      churnBtn.title = 'Toggle Git Churn Stellar Spectrometry (H)';
      churnBtn.addEventListener('click', () => {
        this.toggleHeatMode();
      });
      this._toolbar.appendChild(churnBtn);

      const resetBtn = document.createElement('button');
      resetBtn.className = 'hud-btn';
      resetBtn.innerHTML = '<span class="hud-btn-icon"><svg class="svg-icon icon-xs" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="7"/><path d="M12 2v3m0 14v3M2 12h3m14 0h3"/></svg></span> <span class="hud-btn-text">Reset</span>';
      resetBtn.title = 'Reset Camera to Galactic Overview';
      resetBtn.addEventListener('click', () => {
        this.resetView();
        if (this._isTopDown) {
          this._isTopDown = false;
          viewBtn.classList.remove('active');
          viewBtn.querySelector('.hud-btn-text').textContent = 'Top-Down';
        }
      });
      this._toolbar.appendChild(resetBtn);

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

        let okIsolated = true;
        if (this._isolatedPkg) {
          okIsolated = (d.pkg === this._isolatedPkg);
        }

        const show = okSearch && okPkg && !hidden && okPojo && okArchetype && okIsolated;
        m.visible = show;
        if (d.haloMesh) d.haloMesh.visible = show;
        if (show) visible.add(id);
      });

      this._edgeLines.forEach(l => {
        if (!this._showArcs) {
          l.visible = false;
        } else if (l.userData.src && l.userData.tgt) {
          const bothVisible = visible.has(l.userData.src) && visible.has(l.userData.tgt);
          if (this._isolatedPkg) {
            const srcIn = l.userData.srcPkg === this._isolatedPkg || visible.has(l.userData.src);
            const tgtIn = l.userData.tgtPkg === this._isolatedPkg || visible.has(l.userData.tgt);
            l.visible = bothVisible && srcIn && tgtIn;
            if (l.visible) l.material.opacity = 0.85;
          } else {
            l.visible = bothVisible;
          }
        }
      });

      if (this._cometGroup) {
        this._cometGroup.visible = this._showArcs && !this._isolatedPkg;
      }
      this._cometTails.forEach(t => {
        if (t.points) t.points.visible = this._showArcs && !this._isolatedPkg;
      });

      this._planeMeshes.forEach(p => {
        const pkgOk = !p.userData || !this._hiddenPackages.has(p.userData.pkg);
        const isoOk = !this._isolatedPkg || (p.userData && p.userData.pkg === this._isolatedPkg);
        p.visible   = this._showPlanes && pkgOk && isoOk;
      });

      this._nebulaGroups.forEach(ng => {
        const pkgOk = !ng.userData || !this._hiddenPackages.has(ng.userData.pkg);
        const isoOk = !this._isolatedPkg || (ng.userData && ng.userData.pkg === this._isolatedPkg);
        ng.visible = pkgOk && isoOk;
      });

      this._labelSprites.forEach(s => {
        const pkgOk = !this._hiddenPackages.has(s.userData.pkg);
        const entityOk = !s.userData.nodeId || visible.has(s.userData.nodeId);
        const isoOk = !this._isolatedPkg || (s.userData.pkg === this._isolatedPkg);
        s.visible = this._showLabels && pkgOk && entityOk && isoOk;
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

        const isLight = document.body.classList.contains('theme-light') || document.body.getAttribute('data-theme') === 'light';
        const ttBg = isLight ? 'rgba(255, 255, 255, 0.96)' : '#02040a';
        const ttText = isLight ? '#0f172a' : '#f8fafc';
        const ttSub = isLight ? '#64748b' : '#64748b';
        const ttBorder = isLight ? 'rgba(0, 0, 0, 0.10)' : col;
        const ttShadow = isLight ? '0 12px 32px rgba(0,0,0,0.12), inset 0 1px 0 rgba(255,255,255,0.95)' : `0 8px 32px rgba(0,0,0,0.85), 0 0 20px ${col}22`;

        this._tooltip.innerHTML = `
          <div style="background:${ttBg};border:1px solid ${ttBorder};border-radius:8px;padding:10px 14px;box-shadow:${ttShadow};">
            <div style="font-size:12px;font-weight:700;color:${ttText};font-family:Sora,sans-serif;">${data.label || data.simpleName || data.id}</div>
            <div style="font-size:11px;color:${ttSub};font-family:'JetBrains Mono',monospace;margin-top:2px;">${data.package || hit.userData.pkg || ''}</div>
            <div style="font-size:11px;font-family:'JetBrains Mono',monospace;margin-top:4px;">Kind: ${badge}</div>
            ${archHtml}
          </div>`;
        this._tooltip.style.left    = `${ev.clientX - rect.left + 16}px`;
        this._tooltip.style.top     = `${ev.clientY - rect.top  + 16}px`;
        this._tooltip.style.display = 'block';

        this._updateTelemetryCard(hit);

      } else {
        this._resetHover();
        this._tooltip.style.display = 'none';
        if (!this._selectedMesh) {
          this._updateTelemetryCard(null);
        }
        this._el.style.cursor = 'default';
      }
    }

    _lightConstellation(hitMesh) {
      const id  = hitMesh.userData.node ? hitMesh.userData.node.id : null;
      if (!id) return;
      const neighbors = new Set();
      const connectedLines = this._edgeMap.get(id) || [];
      const connectedSet = new Set(connectedLines);

      // Light up incident lines with direction colors and dim all other lines
      this._edgeLines.forEach(line => {
        if (!line.visible) return;
        if (connectedSet.has(line)) {
          const isCaller = (line.userData.tgt === id);
          if (isCaller) {
            line.material.color.setHex(0xfbbf24);  // Warm gold for incoming callers
            line.material.opacity = 0.95;
          } else {
            line.material.color.setHex(0x38bdf8);  // Electric cyber cyan for outgoing callees
            line.material.opacity = 0.95;
          }
          this._highlightEdges.push(line);
          [line.userData.src, line.userData.tgt].forEach(nid => { if (nid !== id) neighbors.add(nid); });
        } else {
          // Dim non-incident lines to faint whisper
          line.material.opacity = 0.012;
          this._dimmedEdges.push(line);
        }
      });

      // Brighten neighbors, dim unrelated nodes
      this._nodeMeshes.forEach(m => {
        if (!m.visible || m === hitMesh) return;
        const nid = m.userData.node ? m.userData.node.id : null;
        if (nid && neighbors.has(nid)) {
          m.scale.set(1.25, 1.25, 1.25);
          m.material.emissiveIntensity = 2.4;
          this._highlightNeighbors.push(m);
        } else {
          m.material.emissiveIntensity = 0.20;
          this._dimmedNodes.push(m);
        }
      });
    }

    _resetHover() {
      if (this._hoveredMesh) {
        const isMeth = this._hoveredMesh.userData.isMethod;
        this._hoveredMesh.material.emissiveIntensity = isMeth ? 0.75 : 1.15;
        this._hoveredMesh.scale.set(1, 1, 1);
        this._hoveredMesh = null;
      }
      this._highlightEdges.forEach(l => {
        l.material.color.setHex(l.userData.origColor);
        l.material.opacity = l.userData.origOpacity;
      });
      this._highlightEdges = [];
      this._dimmedEdges.forEach(l => {
        l.material.color.setHex(l.userData.origColor);
        l.material.opacity = l.userData.origOpacity;
      });
      this._dimmedEdges = [];

      this._highlightNeighbors.forEach(m => {
        m.scale.set(1, 1, 1);
        m.material.emissiveIntensity = m.userData.isMethod ? 0.75 : 1.15;
      });
      this._highlightNeighbors = [];

      this._dimmedNodes.forEach(m => {
        m.material.emissiveIntensity = m.userData.isMethod ? 0.75 : 1.15;
      });
      this._dimmedNodes = [];
    }

    _onClick(ev) {
      if (!this._el || !this._camera) return;
      const rect    = this._el.getBoundingClientRect();
      this._mouse.x =  ((ev.clientX - rect.left) / rect.width)  * 2 - 1;
      this._mouse.y = -((ev.clientY - rect.top)  / rect.height) * 2 + 1;

      this._raycaster.setFromCamera(this._mouse, this._camera);

      // Check node meshes first
      const nodeHits = this._raycaster.intersectObjects(this._nodeMeshes.filter(m => m.visible));
      if (nodeHits.length > 0) {
        const hit = nodeHits[0].object;
        this._selectedMesh = hit;
        this._updateTelemetryCard(hit);
        const entity = hit.userData.node ? hit.userData.node.raw : null;
        this.flyToNode(hit);
        if (entity && this._onSelectEntity) this._onSelectEntity(entity.id || entity.fqn);
        else if (entity && window.selectEntity) window.selectEntity(entity.id || entity.fqn);
        return;
      }

      // Check label sprites (clicking package label flies to package, clicking star label flies to star)
      const labelHits = this._raycaster.intersectObjects(this._labelSprites.filter(s => s.visible));
      if (labelHits.length > 0) {
        const hitLabel = labelHits[0].object;
        if (hitLabel.userData.isPackageLabel && hitLabel.userData.pkg) {
          this.flyToPackage(hitLabel.userData.pkg);
        } else if (hitLabel.userData.nodeId) {
          this.flyToNode(hitLabel.userData.nodeId);
        }
        return;
      }

      // Clicked in empty cosmic space: unpin selection & hide telemetry card
      this._selectedMesh = null;
      this._updateTelemetryCard(null);
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

      /* Harmonic Keplerian planetary orbits for method satellites */
      if (this._orbitingMethods && this._orbitingMethods.length > 0) {
        this._orbitingMethods.forEach(om => {
          if (!om.mesh.visible || !om.parentMesh) return;
          const a = om.baseOrbitA + elapsed * om.speed;
          const px = om.parentMesh.position.x + om.orbitR * Math.cos(a);
          const pz = om.parentMesh.position.z + om.orbitR * Math.sin(a);
          const py = om.parentMesh.position.y + 3.5 * Math.sin(elapsed * om.bobSpeed);
          om.mesh.position.set(px, py, pz);
        });
      }

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
      if (this._coreLight) this._coreLight.intensity = 1.0 + Math.sin(elapsed * 1.2) * 0.2;

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

    zoomBy(factor) {
      if (!this._camera || !this._controls) return;
      this._targetCamPos = null;
      this._targetCtrlTgt = null;
      const target = this._controls.target;
      const offset = this._camera.position.clone().sub(target);
      offset.divideScalar(factor);
      const dist = offset.length();
      const minDist = this._controls.minDistance || 50;
      const maxDist = this._controls.maxDistance || 2500;
      if (dist < minDist) offset.setLength(minDist);
      if (dist > maxDist) offset.setLength(maxDist);
      this._camera.position.copy(target).add(offset);
      this._controls.update();
    }

    fitToScreen() {
      if (!this._camera || !this._controls) return;
      if (this._nodeMeshes && this._nodeMeshes.length > 0 && typeof THREE !== 'undefined') {
        const box = new THREE.Box3();
        let count = 0;
        for (const m of this._nodeMeshes) {
          if (m.visible) {
            box.expandByPoint(m.position);
            count++;
          }
        }
        if (count > 0 && !box.isEmpty()) {
          const center = new THREE.Vector3();
          box.getCenter(center);
          const size = new THREE.Vector3();
          box.getSize(size);

          const aspect = (this._camera && this._camera.aspect) ? this._camera.aspect : (this._el ? this._el.clientWidth / this._el.clientHeight : 1.77);
          const vFov = (this._camera.fov || 45) * (Math.PI / 180);
          const hFov = 2 * Math.atan(Math.tan(vFov / 2) * aspect);

          // Horizontal distance required to frame width comfortably
          const distHorizontal = (Math.max(size.x, 80) / 2) / Math.tan(hFov / 2);

          // Vertical distance required to frame elevation-projected height
          const projectedHeight = Math.max(size.y * 0.91 + size.z * 0.42, size.y, 60);
          const distVertical = (projectedHeight / 2) / Math.tan(vFov / 2);

          // Snug, rich framing filling ~82% of the viewport (was over-padded by 1.4x on maxDim)
          let cameraDistance = Math.max(distHorizontal, distVertical) * 1.08;
          cameraDistance = Math.max(160, Math.min(cameraDistance, 880));

          const dir = new THREE.Vector3(0, 0.42, 0.98).normalize();
          this._targetCamPos = center.clone().add(dir.multiplyScalar(cameraDistance));
          this._targetCtrlTgt = center;
          if (this._controls) this._controls.update();
          return;
        }
      }
      this._targetCamPos = new THREE.Vector3(0, 180, 480);
      this._targetCtrlTgt = new THREE.Vector3(0, 0, 0);
      if (this._controls) this._controls.update();
    }

    resetView() {
      if (!this._camera || !this._controls) return;
      if (this._nodeMeshes && this._nodeMeshes.length > 0) {
        this.fitToScreen();
      } else {
        this._targetCamPos = new THREE.Vector3(0, 180, 480);
        this._targetCtrlTgt = new THREE.Vector3(0, 0, 0);
        if (this._controls) this._controls.update();
      }
    }

    /* ─────────────────── Destroy / Cleanup ─────────────────── */

    destroy() {
      if (this._animId) { cancelAnimationFrame(this._animId); this._animId = null; }
      if (this._resizeObserver) { this._resizeObserver.disconnect(); this._resizeObserver = null; }
      if (this._onKeyDown) { window.removeEventListener('keydown', this._onKeyDown); this._onKeyDown = null; }
      if (this._telemetryCard) { this._telemetryCard.remove(); this._telemetryCard = null; }
      if (this._isolationBanner) { this._isolationBanner.remove(); this._isolationBanner = null; }
      if (this._scene) {
        this._scene.traverse(obj => {
          if (obj.geometry) obj.geometry.dispose();
          if (obj.material) {
            (Array.isArray(obj.material) ? obj.material : [obj.material]).forEach(m => {
              if (!m) return;
              Object.keys(m).forEach(k => {
                if (m[k] && typeof m[k] === 'object' && m[k].isTexture) {
                  m[k].dispose();
                }
              });
              m.dispose();
            });
          }
        });
      }
      if (this._bloomPass) {
        try { if (typeof this._bloomPass.dispose === 'function') this._bloomPass.dispose(); } catch (_) {}
        this._bloomPass = null;
      }
      if (this._composer) { try { this._composer.dispose(); } catch (_) {} this._composer = null; }
      if (this._controls) { this._controls.dispose(); this._controls = null; }
      if (this._renderer) {
        if (this._renderer.renderLists) this._renderer.renderLists.dispose();
        this._renderer.dispose();
        if (typeof this._renderer.forceContextLoss === 'function') {
          this._renderer.forceContextLoss();
        }
        this._renderer = null;
      }
      if (this._el)       { this._el.remove(); this._el = null; }
      this._scene = this._camera = this._clock = null;
      this._ambientLight = this._hemiLight = this._coreLight = this._dirLight = null;
      this._nodeMeshes = []; this._edgeLines = []; this._particles = [];
      this._cometTails = []; this._nebulaGroups = [];
      this._planeMeshes = []; this._edgeMap = new Map();
      this._highlightEdges = []; this._dimmedEdges = [];
      this._highlightNeighbors = []; this._dimmedNodes = [];
      this._orbitingMethods = [];
      this._selectedMesh = null;
      this._isolatedPkg = null;
      if (this._inDegreeMap) this._inDegreeMap.clear();
      if (this._outDegreeMap) this._outDegreeMap.clear();
      if (this._labelSprites) {
        this._labelSprites.forEach(s => {
          if (s.material && s.material.map) s.material.map.dispose();
          if (s.material) s.material.dispose();
        });
        this._labelSprites = [];
      }
      if (this._pkgMeta) this._pkgMeta.clear();
    }
  }

  window.Galaxy3DRenderer = Galaxy3DRenderer;
})(window);
