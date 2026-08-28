/**
 * classifier.js - CodeLens Semantic Archetype & POJO Classification Engine
 *
 * Provides:
 * - Dynamic pattern matching for Methods and Classes with module token substitution ({MODULE}, {MOD}).
 * - Built-in banking / BaNCS transaction archetypes ({MODULE}ET, {MODULE}BT, {MODULE}PS, {MODULE}PB, {MODULE}PA, {MODULE}DG).
 * - Customizable POJO / Accessor method detection rules.
 * - LocalStorage persistence and Settings modal synchronisation.
 */

(function(window) {
  'use strict';

  const POJO_STORAGE_KEY = 'codelens_pojo_config';
  const RULES_STORAGE_KEY = 'codelens_archetype_rules';

  const DEFAULT_POJO_CONFIG = {
    enableStandardGettersSetters: true,
    patterns: 'get*, set*, is*, has*, toString, hashCode, equals, canEqual, getClass, compareTo, clone'
  };

  const BANCS_PRESET_RULES = [
    {
      id: 'rule-bancs-et',
      target: 'METHOD',
      scope: 'METHOD',
      matchType: 'PREFIX',
      pattern: '{MODULE}ET',
      label: 'Elementary Transaction',
      badge: 'FETCH',
      category: 'READ_ONLY',
      color: '#10b981',
      icon: 'download',
      description: 'Elementary transactions only meant for fetching data',
      enabled: true
    },
    {
      id: 'rule-bancs-bt',
      target: 'METHOD',
      scope: 'METHOD',
      matchType: 'PREFIX',
      pattern: '{MODULE}BT',
      label: 'Business Transaction',
      badge: 'MUTATE',
      category: 'MUTATION',
      color: '#f59e0b',
      icon: 'zap',
      description: 'Business transactions meant for create/update data',
      enabled: true
    },
    {
      id: 'rule-bancs-ps',
      target: 'METHOD',
      scope: 'METHOD',
      matchType: 'PREFIX',
      pattern: '{MODULE}PS',
      label: 'Batch Processor',
      badge: 'BATCH',
      category: 'BATCH',
      color: '#8b5cf6',
      icon: 'settings',
      description: 'Batch processor / background jobs',
      enabled: true
    },
    {
      id: 'rule-bancs-pb',
      target: 'METHOD',
      scope: 'METHOD',
      matchType: 'PREFIX',
      pattern: '{MODULE}PB',
      label: 'Process Before Batch',
      badge: 'PRE-BATCH',
      category: 'PRE_PROCESS',
      color: '#3b82f6',
      icon: 'skipBack',
      description: 'Process before batch execution',
      enabled: true
    },
    {
      id: 'rule-bancs-pa',
      target: 'METHOD',
      scope: 'METHOD',
      matchType: 'PREFIX',
      pattern: '{MODULE}PA',
      label: 'Process After Batch',
      badge: 'POST-BATCH',
      category: 'POST_PROCESS',
      color: '#ec4899',
      icon: 'skipForward',
      description: 'Process after batch execution',
      enabled: true
    },
    {
      id: 'rule-bancs-dg',
      target: 'CLASS',
      scope: 'CLASS',
      matchType: 'PREFIX',
      pattern: '{MODULE}DG',
      label: 'Data Grabber',
      badge: 'DATA-GRABBER',
      category: 'DATA_ACCESS',
      color: '#06b6d4',
      icon: 'box',
      description: 'Data grabber / data retrieval components',
      enabled: true
    }
  ];

  const SPRING_PRESET_RULES = [
    {
      id: 'rule-spring-ctrl',
      target: 'CLASS',
      scope: 'CLASS',
      matchType: 'SUFFIX',
      pattern: '*Controller',
      label: 'REST Controller',
      badge: 'CTRL',
      category: 'CONTROLLER',
      color: '#3b82f6',
      icon: 'globe',
      description: 'Spring REST / Web MVC Controller',
      enabled: true
    },
    {
      id: 'rule-spring-srv',
      target: 'CLASS',
      scope: 'CLASS',
      matchType: 'SUFFIX',
      pattern: '*Service',
      label: 'Business Service',
      badge: 'SRV',
      category: 'SERVICE',
      color: '#10b981',
      icon: 'settings',
      description: 'Spring Business Service Layer',
      enabled: true
    },
    {
      id: 'rule-spring-repo',
      target: 'CLASS',
      scope: 'CLASS',
      matchType: 'SUFFIX',
      pattern: '*Repository',
      label: 'Data Repository',
      badge: 'REPO',
      category: 'DATA_ACCESS',
      color: '#8b5cf6',
      icon: 'database',
      description: 'Spring Data JPA / Mongo Repository',
      enabled: true
    },
    {
      id: 'rule-spring-dto',
      target: 'CLASS',
      scope: 'CLASS',
      matchType: 'SUFFIX',
      pattern: '*DTO',
      label: 'Data Transfer Object',
      badge: 'DTO',
      category: 'DTO',
      color: '#06b6d4',
      icon: 'fileText',
      description: 'Data Transfer Object / Schema',
      enabled: true
    },
    {
      id: 'rule-spring-mapper',
      target: 'CLASS',
      scope: 'CLASS',
      matchType: 'SUFFIX',
      pattern: '*Mapper',
      label: 'Entity Mapper',
      badge: 'MAPPER',
      category: 'MAPPER',
      color: '#f59e0b',
      icon: 'refresh',
      description: 'MapStruct / Object transformation mapper',
      enabled: true
    }
  ];

  const DDD_PRESET_RULES = [
    {
      id: 'rule-ddd-aggregate',
      target: 'CLASS',
      scope: 'CLASS',
      matchType: 'SUFFIX',
      pattern: '*Aggregate',
      label: 'Aggregate Root',
      badge: 'AGGREGATE',
      category: 'DOMAIN',
      color: '#8b5cf6',
      icon: 'box',
      description: 'Domain-Driven Design Aggregate Root',
      enabled: true
    },
    {
      id: 'rule-ddd-entity',
      target: 'CLASS',
      scope: 'CLASS',
      matchType: 'SUFFIX',
      pattern: '*Entity',
      label: 'Domain Entity',
      badge: 'ENTITY',
      category: 'DOMAIN',
      color: '#3b82f6',
      icon: 'database',
      description: 'Domain Entity with business identity',
      enabled: true
    },
    {
      id: 'rule-ddd-vo',
      target: 'CLASS',
      scope: 'CLASS',
      matchType: 'SUFFIX',
      pattern: '*VO',
      label: 'Value Object',
      badge: 'VAL-OBJ',
      category: 'DOMAIN',
      color: '#06b6d4',
      icon: 'tag',
      description: 'Immutable Domain Value Object',
      enabled: true
    },
    {
      id: 'rule-ddd-usecase',
      target: 'CLASS',
      scope: 'CLASS',
      matchType: 'SUFFIX',
      pattern: '*UseCase',
      label: 'Use Case / Interactor',
      badge: 'USE-CASE',
      category: 'APPLICATION',
      color: '#10b981',
      icon: 'zap',
      description: 'Application Use Case / Command Handler',
      enabled: true
    },
    {
      id: 'rule-ddd-repo',
      target: 'CLASS',
      scope: 'CLASS',
      matchType: 'SUFFIX',
      pattern: '*Repository',
      label: 'Domain Repository',
      badge: 'REPO',
      category: 'INFRA',
      color: '#ec4899',
      icon: 'database',
      description: 'Domain Repository Contract / Gateway',
      enabled: true
    },
    {
      id: 'rule-ddd-event',
      target: 'CLASS',
      scope: 'CLASS',
      matchType: 'SUFFIX',
      pattern: '*Event',
      label: 'Domain Event',
      badge: 'EVENT',
      category: 'EVENT',
      color: '#f59e0b',
      icon: 'activity',
      description: 'Domain Event Notification',
      enabled: true
    }
  ];

  const DEFAULT_ARCHETYPE_RULES = BANCS_PRESET_RULES;

  class CodeLensClassifier {
    constructor() {
      this._pojoConfig = this._loadPojoConfig();
      this._rules = this._loadRules();
    }

    // ── Module Identification Helper ──────────────────────────────────────────

    /**
     * Extracts the module name from a package or FQN (e.g. "com.tcs.bancs.AM" -> "AM", "com.tcs.bancs.BS.AccountService" -> "BS").
     */
    extractModule(pkgOrFqn) {
      if (!pkgOrFqn) return '';
      const clean = pkgOrFqn.replace(/\(.*\)$/, '').trim();
      const parts = clean.split('.').filter(Boolean);
      if (parts.length === 0) return '';

      // Check standard enterprise prefixes (com.tcs.bancs.AM, org.example.banking.BS)
      if (parts.length >= 4 && ['com', 'org', 'io', 'net', 'dev', 'app'].includes(parts[0])) {
        // e.g. com.tcs.bancs.AM -> parts[3] is AM
        if (/^[A-Z0-9_]+$/.test(parts[3]) || parts.length >= 5) {
          return parts[3];
        }
        return parts[2];
      }
      if (parts.length >= 3 && ['com', 'org', 'io', 'net', 'dev', 'app'].includes(parts[0])) {
        return parts[2];
      }
      return parts[0];
    }

    _loadPojoConfig() {
      try {
        const raw = localStorage.getItem(POJO_STORAGE_KEY);
        if (raw) {
          const parsed = JSON.parse(raw);
          return { ...DEFAULT_POJO_CONFIG, ...parsed };
        }
      } catch (_) {}
      return { ...DEFAULT_POJO_CONFIG };
    }

    getPojoConfig() {
      const enableStd = this._pojoConfig.enableStandardGettersSetters !== false && this._pojoConfig.includeStandardAccessors !== false;
      const patternsList = Array.isArray(this._pojoConfig.customPatterns)
        ? this._pojoConfig.customPatterns
        : (typeof this._pojoConfig.patterns === 'string'
            ? this._pojoConfig.patterns.split(',').map(s => s.trim()).filter(Boolean)
            : ['get*', 'set*', 'is*', 'has*']);

      return {
        includeStandardAccessors: enableStd,
        enableStandardGettersSetters: enableStd,
        customPatterns: patternsList,
        patterns: patternsList.join(', ')
      };
    }

    setPojoConfig(cfg) {
      if (!cfg) return;
      if (cfg.includeStandardAccessors !== undefined) {
        this._pojoConfig.enableStandardGettersSetters = Boolean(cfg.includeStandardAccessors);
        this._pojoConfig.includeStandardAccessors = Boolean(cfg.includeStandardAccessors);
      }
      if (cfg.enableStandardGettersSetters !== undefined) {
        this._pojoConfig.enableStandardGettersSetters = Boolean(cfg.enableStandardGettersSetters);
        this._pojoConfig.includeStandardAccessors = Boolean(cfg.enableStandardGettersSetters);
      }
      if (cfg.customPatterns !== undefined) {
        const list = Array.isArray(cfg.customPatterns) ? cfg.customPatterns : [];
        this._pojoConfig.customPatterns = list;
        this._pojoConfig.patterns = list.join(', ');
      }
      if (cfg.patterns !== undefined) {
        this._pojoConfig.patterns = String(cfg.patterns);
        this._pojoConfig.customPatterns = this._pojoConfig.patterns.split(',').map(s => s.trim()).filter(Boolean);
      }
      this.savePojoConfig(this._pojoConfig);
    }

    savePojoConfig(cfg) {
      this._pojoConfig = { ...this._pojoConfig, ...cfg };
      localStorage.setItem(POJO_STORAGE_KEY, JSON.stringify(this._pojoConfig));
    }

    resetPojoConfig() {
      this._pojoConfig = { ...DEFAULT_POJO_CONFIG };
      this.savePojoConfig(this._pojoConfig);
      return this.getPojoConfig();
    }

    /**
     * Checks if a method is a POJO accessor / boilerplate method based on heuristics & user rules.
     */
    isPojo(methodNameOrNode, fqn, pkg) {
      let name = '';
      let fullFqn = fqn || '';
      let packageFqn = pkg || '';

      if (typeof methodNameOrNode === 'object' && methodNameOrNode !== null) {
        name = methodNameOrNode.simpleName || methodNameOrNode.label || (methodNameOrNode.id ? methodNameOrNode.id.split('.').pop() : '') || '';
        fullFqn = methodNameOrNode.fqn || methodNameOrNode.id || fullFqn;
        packageFqn = methodNameOrNode.package || methodNameOrNode.packageFqn || packageFqn;
      } else {
        name = String(methodNameOrNode || '');
      }

      name = name.replace(/\(.*\)$/, '').trim();
      if (!name) return false;

      // Never treat root graph node as POJO
      if (typeof methodNameOrNode === 'object' && methodNameOrNode.role === 'root') return false;

      // 1. Standard Object methods
      const standardObjectMethods = ['toString', 'hashCode', 'equals', 'canEqual', 'getClass', 'compareTo', 'clone', 'finalize', 'notify', 'notifyAll', 'wait'];
      if (standardObjectMethods.includes(name)) return true;

      // 2. Standard Java getter/setter heuristics
      if (this._pojoConfig.enableStandardGettersSetters) {
        if (name.length > 3 && name.startsWith('get') && /^[A-Z0-9_]/.test(name.charAt(3))) return true;
        if (name.length > 3 && name.startsWith('set') && /^[A-Z0-9_]/.test(name.charAt(3))) return true;
        if (name.length > 2 && name.startsWith('is') && /^[A-Z0-9_]/.test(name.charAt(2))) return true;
        if (name.length > 3 && name.startsWith('has') && /^[A-Z0-9_]/.test(name.charAt(3))) return true;
      }

      // 3. User configured POJO patterns
      const customPatterns = (this._pojoConfig.patterns || '')
        .split(',')
        .map(p => p.trim())
        .filter(Boolean);

      for (const pattern of customPatterns) {
        if (this._matchesPattern(name, pattern, fullFqn, packageFqn)) {
          return true;
        }
      }

      return false;
    }

    // ── Archetype Rules Engine ────────────────────────────────────────────────

    _loadRules() {
      try {
        const raw = localStorage.getItem(RULES_STORAGE_KEY);
        if (raw) {
          const parsed = JSON.parse(raw);
          if (Array.isArray(parsed) && parsed.length > 0) return parsed;
        }
      } catch (_) {}
      return JSON.parse(JSON.stringify(DEFAULT_ARCHETYPE_RULES));
    }

    getRules() {
      return [...this._rules];
    }

    saveRules(rules) {
      this._rules = Array.isArray(rules) ? rules : [];
      localStorage.setItem(RULES_STORAGE_KEY, JSON.stringify(this._rules));
    }

    resetRules() {
      this._rules = JSON.parse(JSON.stringify(DEFAULT_ARCHETYPE_RULES));
      this.saveRules(this._rules);
      return this.getRules();
    }

    loadPreset(presetName) {
      const name = String(presetName || '').toLowerCase().trim();
      let presetRules = [];
      if (name === 'bancs' || name === 'banking') {
        presetRules = JSON.parse(JSON.stringify(BANCS_PRESET_RULES));
      } else if (name === 'spring' || name === 'mvc') {
        presetRules = JSON.parse(JSON.stringify(SPRING_PRESET_RULES));
      } else if (name === 'ddd' || name === 'clean' || name === 'domain') {
        presetRules = JSON.parse(JSON.stringify(DDD_PRESET_RULES));
      } else {
        presetRules = JSON.parse(JSON.stringify(DEFAULT_ARCHETYPE_RULES));
      }
      this.saveRules(presetRules);
      return this.getRules();
    }

    addRule(rule) {
      const target = rule.scope || rule.target || 'METHOD';
      const newRule = {
        id: 'rule-' + Date.now() + '-' + Math.random().toString(36).substr(2, 4),
        enabled: true,
        category: 'CUSTOM',
        color: '#3b82f6',
        icon: 'tag',
        ...rule,
        target: target,
        scope: target
      };
      this._rules.push(newRule);
      this.saveRules(this._rules);
      return newRule;
    }

    updateRule(id, updatedFields) {
      const idx = this._rules.findIndex(r => r.id === id);
      if (idx >= 0) {
        const target = updatedFields.scope || updatedFields.target || this._rules[idx].scope || this._rules[idx].target || 'METHOD';
        this._rules[idx] = {
          ...this._rules[idx],
          ...updatedFields,
          target: target,
          scope: target
        };
        this.saveRules(this._rules);
        return this._rules[idx];
      }
      return null;
    }

    deleteRule(id) {
      this._rules = this._rules.filter(r => r.id !== id);
      this.saveRules(this._rules);
    }

    /**
     * Classifies a method by matching its name/FQN against active archetype rules.
     * @returns {Object|null} Archetype classification descriptor if matched, else null.
     */
    classifyMethod(methodNameOrNode, fqn, pkg) {
      return this._classifyEntity('METHOD', methodNameOrNode, fqn, pkg);
    }

    /**
     * Classifies a class/type by matching its name/FQN against active archetype rules.
     * @returns {Object|null} Archetype classification descriptor if matched, else null.
     */
    classifyType(typeNameOrNode, fqn, pkg) {
      return this._classifyEntity('CLASS', typeNameOrNode, fqn, pkg);
    }

    _classifyEntity(targetType, nameOrNode, fqn, pkg) {
      let name = '';
      let fullFqn = fqn || '';
      let packageFqn = pkg || '';

      if (typeof nameOrNode === 'object' && nameOrNode !== null) {
        name = nameOrNode.simpleName || nameOrNode.label || (nameOrNode.id ? nameOrNode.id.split('.').pop() : '') || '';
        fullFqn = nameOrNode.fqn || nameOrNode.id || fullFqn;
        packageFqn = nameOrNode.package || nameOrNode.packageFqn || packageFqn;
      } else {
        name = String(nameOrNode || '');
      }

      name = name.replace(/\(.*\)$/, '').trim();
      if (!name) return null;

      const activeRules = this._rules.filter(r => r.enabled && ((r.target || r.scope) === targetType || (r.target || r.scope) === 'ANY'));

      for (const rule of activeRules) {
        if (this._matchesRule(name, rule, fullFqn, packageFqn)) {
          return {
            ruleId: rule.id,
            label: rule.label,
            badge: rule.badge || rule.label.toUpperCase(),
            category: rule.category || 'CUSTOM',
            color: rule.color || '#10b981',
            icon: rule.icon || 'tag',
            description: rule.description || ''
          };
        }
      }

      return null;
    }

    _matchesRule(name, rule, fqn, pkg) {
      if (!rule || !rule.pattern) return false;
      const pattern = rule.pattern.trim();
      const matchType = rule.matchType || 'PREFIX';

      return this._matchesPattern(name, pattern, fqn, pkg, matchType);
    }

    _matchesPattern(name, pattern, fqn, pkg, matchType = 'AUTO') {
      if (!name || !pattern) return false;

      // Extract module name from package or fqn (e.g. com.tcs.bancs.AM -> "AM")
      const moduleName = this.extractModule(pkg || fqn || '');

      // Replace {MODULE} or {MOD} tokens in pattern
      let resolvedPattern = pattern;
      if (resolvedPattern.includes('{MODULE}') || resolvedPattern.includes('{MOD}')) {
        if (!moduleName) {
          // If no module is identified, match any 2-4 uppercase letter module prefix
          resolvedPattern = resolvedPattern.replace(/\{MODULE\}|\{MOD\}/g, '[A-Z]{2,4}');
          matchType = 'REGEX';
        } else {
          resolvedPattern = resolvedPattern.replace(/\{MODULE\}|\{MOD\}/g, moduleName);
        }
      }

      // Regex literal: /pattern/i
      if (resolvedPattern.startsWith('/') && resolvedPattern.endsWith('/')) {
        try {
          const re = new RegExp(resolvedPattern.slice(1, -1), 'i');
          return re.test(name) || re.test(fqn);
        } catch (_) { return false; }
      }

      if (matchType === 'REGEX') {
        try {
          const re = new RegExp(resolvedPattern, 'i');
          return re.test(name) || re.test(fqn);
        } catch (_) { return false; }
      }

      // Wildcard glob pattern (e.g. AMET*, *Service, get*Data)
      if (resolvedPattern.includes('*') || matchType === 'GLOB') {
        const regexStr = '^' + resolvedPattern.split('*').map(s => this._escapeRegex(s)).join('.*') + '$';
        try {
          return new RegExp(regexStr, 'i').test(name);
        } catch (_) { return false; }
      }

      if (matchType === 'PREFIX') {
        return name.toLowerCase().startsWith(resolvedPattern.toLowerCase());
      }
      if (matchType === 'SUFFIX') {
        return name.toLowerCase().endsWith(resolvedPattern.toLowerCase());
      }
      if (matchType === 'CONTAINS') {
        return name.toLowerCase().includes(resolvedPattern.toLowerCase());
      }
      if (matchType === 'EXACT') {
        return name.toLowerCase() === resolvedPattern.toLowerCase();
      }

      // Default AUTO behavior
      if (resolvedPattern.endsWith('*')) {
        return name.toLowerCase().startsWith(resolvedPattern.slice(0, -1).toLowerCase());
      }
      if (resolvedPattern.startsWith('*')) {
        return name.toLowerCase().endsWith(resolvedPattern.slice(1).toLowerCase());
      }
      return name.toLowerCase().startsWith(resolvedPattern.toLowerCase());
    }

    _escapeRegex(str) {
      return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    }
  }

  // 50 maximally distinguishable equidistant hues across the full 360° color wheel
  const PALETTE = [
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
  ];

  function getEntityColor(nameOrFqn, fallbackIndex = 0) {
    if (!nameOrFqn) return PALETTE[fallbackIndex % PALETTE.length];
    const clean = String(nameOrFqn).split('(')[0].trim();
    let hash = 0;
    for (let i = 0; i < clean.length; i++) {
      hash = ((hash << 5) - hash) + clean.charCodeAt(i);
      hash |= 0;
    }
    return PALETTE[Math.abs(hash) % PALETTE.length];
  }

  function extractClassFqn(fqn, nodeType) {
    if (!fqn) return '';
    const clean = String(fqn).split('(')[0].trim();
    const parts = clean.split('.');
    if (parts.length <= 1) return clean;

    const typeUpper = (nodeType || '').toUpperCase();
    if (typeUpper === 'CLASS' || typeUpper === 'TYPE') {
      return clean;
    }
    if (typeUpper === 'PACKAGE' || typeUpper === 'MODULE') {
      return clean;
    }
    if (typeUpper === 'METHOD' || typeUpper === 'FIELD' || fqn.includes('(')) {
      return parts.slice(0, -1).join('.');
    }
    if (/^[a-z_]/.test(parts[parts.length - 1])) {
      return parts.slice(0, -1).join('.');
    }
    return clean;
  }

  function getClassColor(nameOrFqn, nodeType = 'METHOD', fallbackIndex = 0) {
    const classFqn = extractClassFqn(nameOrFqn, nodeType);
    return getEntityColor(classFqn, fallbackIndex);
  }

  function tintColor(hex, index = 0) {
    if (!hex || !hex.startsWith('#')) return hex || '#3b82f6';
    const c = parseInt(hex.replace('#', ''), 16);
    const r = (c >> 16) & 255;
    const g = (c >> 8) & 255;
    const b = c & 255;

    const rNorm = r / 255, gNorm = g / 255, bNorm = b / 255;
    const max = Math.max(rNorm, gNorm, bNorm), min = Math.min(rNorm, gNorm, bNorm);
    let h = 0, s = 0, l = (max + min) / 2;

    if (max !== min) {
      const d = max - min;
      s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
      switch (max) {
        case rNorm: h = (gNorm - bNorm) / d + (gNorm < bNorm ? 6 : 0); break;
        case gNorm: h = (bNorm - rNorm) / d + 2; break;
        case bNorm: h = (rNorm - gNorm) / d + 4; break;
      }
      h /= 6;
    }

    const lightnessOffsets = [0.10, -0.08, 0.16, -0.14, 0.05, -0.10, 0.12];
    const lOffset = lightnessOffsets[index % lightnessOffsets.length];
    const newL = Math.max(0.28, Math.min(0.82, l + lOffset));

    return `hsl(${Math.round(h * 360)}, ${Math.round(Math.min(s * 1.1, 1) * 100)}%, ${Math.round(newL * 100)}%)`;
  }

  window.CodeLensPalette = {
    PALETTE,
    getColor: getEntityColor,
    getClassColor: getClassColor,
    extractClassFqn: extractClassFqn,
    tintColor: tintColor,
  };

  window.CodeLensClassifier = new CodeLensClassifier();
})(window);
