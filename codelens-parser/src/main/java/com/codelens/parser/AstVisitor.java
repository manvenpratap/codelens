package com.codelens.parser;

import com.codelens.core.model.*;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * JavaParser VoidVisitorAdapter that extracts all indexable entities from one
 * CompilationUnit and appends them into a shared {@link VisitContext}.
 *
 * Design choices:
 *  - No SymbolSolver (avoids classpath requirements); method calls resolved by
 *    name heuristic — unresolved targets are prefixed with "~" for later
 *    post-scan matching by {@link com.codelens.analysis.CallGraphAnalyzer}.
 *  - Cyclomatic complexity: decision-point count + 1 (McCabe's definition).
 *  - bodyHash: first 16 hex chars of SHA-256 of normalised body text.
 */
public class AstVisitor extends VoidVisitorAdapter<AstVisitor.VisitContext> {

    // ── Shared mutable state passed through the visitor ───────────────────────
    public static class VisitContext {
        public String sourceFile   = "";
        public String packageName  = "";
        public String currentTypeFqn   = "";
        public String currentMethodFqn = "";

        // Map simple class name -> fully qualified name from explicit import statements
        public final Map<String, String> imports = new HashMap<>();

        // field names declared on the current type — used to distinguish field
        // reads/writes from local variable accesses
        public Set<String> currentTypeFieldNames = new HashSet<>();
        // field names -> declared type name
        public Map<String, String> currentTypeFieldTypes = new HashMap<>();
        // active local variables and parameters in current method/constructor scope -> declared type name
        public Map<String, String> currentScopeVarTypes = new HashMap<>();

        public final List<CodePackage>      packages      = new ArrayList<>();
        public final List<CodeType>         types         = new ArrayList<>();
        public final List<CodeField>        fields        = new ArrayList<>();
        public final List<CodeMethod>       methods       = new ArrayList<>();
        public final List<CodeRelationship> relationships = new ArrayList<>();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Imports
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void visit(ImportDeclaration n, VisitContext ctx) {
        String fqn = n.getNameAsString();
        if (!n.isAsterisk()) {
            int dot = fqn.lastIndexOf('.');
            String simpleName = (dot >= 0) ? fqn.substring(dot + 1) : fqn;
            ctx.imports.put(simpleName, fqn);
        }
        super.visit(n, ctx);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Package
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void visit(PackageDeclaration n, VisitContext ctx) {
        ctx.packageName = n.getNameAsString();
        // Register package entity (deduplicated at DB insert time)
        ctx.packages.add(new CodePackage(ctx.packageName));
        super.visit(n, ctx);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Class / Interface
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void visit(ClassOrInterfaceDeclaration n, VisitContext ctx) {
        String prevTypeFqn                 = ctx.currentTypeFqn;
        Set<String> prevFields             = ctx.currentTypeFieldNames;
        Map<String, String> prevFieldTypes = ctx.currentTypeFieldTypes;

        String simpleName = n.getNameAsString();
        String fqn = (prevTypeFqn != null && !prevTypeFqn.isEmpty())
            ? prevTypeFqn + "." + simpleName
            : (ctx.packageName.isEmpty() ? simpleName : ctx.packageName + "." + simpleName);
        ctx.currentTypeFqn       = fqn;
        ctx.currentTypeFieldNames = new HashSet<>();
        ctx.currentTypeFieldTypes = new HashMap<>();

        CodeType type = new CodeType();
        type.setId(fqn);
        type.setFqn(fqn);
        type.setSimpleName(simpleName);
        type.setPackageFqn(ctx.packageName);
        type.setKind(n.isInterface() ? "INTERFACE" : "CLASS");
        type.setModifiers(modifierString(n.getModifiers()));
        type.setSourceFile(ctx.sourceFile);
        type.setFieldCount(n.getFields().size());
        type.setMethodCount(n.getMethods().size() + n.getConstructors().size());

        n.getRange().ifPresent(r -> {
            type.setStartLine(r.begin.line);
            type.setEndLine(r.end.line);
            type.setLineCount(r.end.line - r.begin.line + 1);
        });

        // Inheritance
        n.getExtendedTypes().stream().findFirst()
            .ifPresent(et -> {
                type.setSuperClass(et.getNameAsString());
                CodeRelationship rel = rel(fqn, et.getNameAsString(), "EXTENDS", 0);
                ctx.relationships.add(rel);
            });

        List<String> ifaces = n.getImplementedTypes().stream()
            .map(it -> it.getNameAsString())
            .collect(Collectors.toList());
        type.setInterfaces(ifaces);
        ifaces.forEach(iface ->
            ctx.relationships.add(rel(fqn, iface, "IMPLEMENTS", 0)));

        ctx.types.add(type);
        super.visit(n, ctx);   // recurse into children (fields, methods, inner classes)

        ctx.currentTypeFqn       = prevTypeFqn;
        ctx.currentTypeFieldNames = prevFields;
        ctx.currentTypeFieldTypes = prevFieldTypes;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Enum
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void visit(EnumDeclaration n, VisitContext ctx) {
        String prevTypeFqn                 = ctx.currentTypeFqn;
        Set<String> prevFields             = ctx.currentTypeFieldNames;
        Map<String, String> prevFieldTypes = ctx.currentTypeFieldTypes;

        String simpleName = n.getNameAsString();
        String fqn = (prevTypeFqn != null && !prevTypeFqn.isEmpty())
            ? prevTypeFqn + "." + simpleName
            : (ctx.packageName.isEmpty() ? simpleName : ctx.packageName + "." + simpleName);
        ctx.currentTypeFqn        = fqn;
        ctx.currentTypeFieldNames = new HashSet<>();
        ctx.currentTypeFieldTypes = new HashMap<>();

        CodeType type = new CodeType();
        type.setId(fqn);  type.setFqn(fqn);
        type.setSimpleName(simpleName);
        type.setPackageFqn(ctx.packageName);
        type.setKind("ENUM");
        type.setModifiers(modifierString(n.getModifiers()));
        type.setSourceFile(ctx.sourceFile);
        type.setFieldCount(n.getFields().size());
        type.setMethodCount(n.getMethods().size() + n.getConstructors().size());
        n.getRange().ifPresent(r -> {
            type.setStartLine(r.begin.line);
            type.setEndLine(r.end.line);
            type.setLineCount(r.end.line - r.begin.line + 1);
        });
        ctx.types.add(type);
        super.visit(n, ctx);

        ctx.currentTypeFqn        = prevTypeFqn;
        ctx.currentTypeFieldNames = prevFields;
        ctx.currentTypeFieldTypes = prevFieldTypes;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Record
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void visit(RecordDeclaration n, VisitContext ctx) {
        String prevTypeFqn                 = ctx.currentTypeFqn;
        Set<String> prevFields             = ctx.currentTypeFieldNames;
        Map<String, String> prevFieldTypes = ctx.currentTypeFieldTypes;

        String simpleName = n.getNameAsString();
        String fqn = (prevTypeFqn != null && !prevTypeFqn.isEmpty())
            ? prevTypeFqn + "." + simpleName
            : (ctx.packageName.isEmpty() ? simpleName : ctx.packageName + "." + simpleName);
        ctx.currentTypeFqn        = fqn;
        ctx.currentTypeFieldNames = new HashSet<>();
        ctx.currentTypeFieldTypes = new HashMap<>();

        // Register record components (parameters) as fields and accessor methods
        for (Parameter p : n.getParameters()) {
            String paramName = p.getNameAsString();
            String paramType = p.getType().asString();
            String normType = normalizeTypeName(paramType);
            ctx.currentTypeFieldNames.add(paramName);
            ctx.currentTypeFieldTypes.put(paramName, normType);

            // Record Component Field
            CodeField field = new CodeField();
            String fieldFqn = fqn + "." + paramName;
            field.setId(fieldFqn);
            field.setFqn(fieldFqn);
            field.setSimpleName(paramName);
            field.setDeclaringTypeFqn(fqn);
            field.setFieldType(paramType);
            field.setModifiers("private final");
            p.getRange().ifPresent(r -> field.setStartLine(r.begin.line));
            ctx.fields.add(field);

            // Implicit Accessor Method if not explicitly declared in record body
            boolean hasExplicitAccessor = n.getMethods().stream()
                .anyMatch(m -> m.getNameAsString().equals(paramName) && m.getParameters().isEmpty());
            if (!hasExplicitAccessor) {
                CodeMethod getter = new CodeMethod();
                String getterFqn = fqn + "." + paramName + "()";
                getter.setId(getterFqn);
                getter.setFqn(getterFqn);
                getter.setSimpleName(paramName);
                getter.setDeclaringTypeFqn(fqn);
                getter.setReturnType(paramType);
                getter.setModifiers("public");
                getter.setCyclomaticComplexity(1);
                p.getRange().ifPresent(r -> {
                    getter.setStartLine(r.begin.line);
                    getter.setEndLine(r.end.line);
                });
                ctx.methods.add(getter);

                // Register READS_FIELD relationship from accessor to component field
                int line = p.getRange().map(r -> r.begin.line).orElse(0);
                ctx.relationships.add(rel(getterFqn, fieldFqn, "READS_FIELD", line));
            }
        }

        // Implicit Canonical Constructor if no explicit constructor is declared
        boolean hasExplicitConstructor = n.getConstructors().stream()
            .anyMatch(c -> c.getParameters().size() == n.getParameters().size())
            || n.getCompactConstructors().size() > 0;
        if (!hasExplicitConstructor && !n.getParameters().isEmpty()) {
            String paramSig = n.getParameters().stream()
                .map(p -> p.getType().asString())
                .collect(Collectors.joining(","));
            String initFqn = fqn + ".<init>(" + paramSig + ")";
            CodeMethod initMethod = new CodeMethod();
            initMethod.setId(initFqn);
            initMethod.setFqn(initFqn);
            initMethod.setSimpleName("<init>");
            initMethod.setDeclaringTypeFqn(fqn);
            initMethod.setReturnType("void");
            initMethod.setModifiers("public");
            initMethod.setParameters(
                n.getParameters().stream()
                    .map(p -> new MethodParam(p.getType().asString(), p.getNameAsString()))
                    .collect(Collectors.toList()));
            n.getRange().ifPresent(r -> {
                initMethod.setStartLine(r.begin.line);
                initMethod.setEndLine(r.begin.line);
            });
            initMethod.setCyclomaticComplexity(1);
            ctx.methods.add(initMethod);

            // Register WRITES_FIELD relationship for each component
            int line = n.getRange().map(r -> r.begin.line).orElse(0);
            for (Parameter p : n.getParameters()) {
                String fieldFqn = fqn + "." + p.getNameAsString();
                ctx.relationships.add(rel(initFqn, fieldFqn, "WRITES_FIELD", line));
            }
        }

        CodeType type = new CodeType();
        type.setId(fqn);  type.setFqn(fqn);
        type.setSimpleName(simpleName);
        type.setPackageFqn(ctx.packageName);
        type.setKind("RECORD");
        type.setModifiers(modifierString(n.getModifiers()));
        type.setSourceFile(ctx.sourceFile);
        type.setSuperClass("java.lang.Record");

        if (n.getImplementedTypes() != null) {
            List<String> ifaces = n.getImplementedTypes().stream()
                .map(it -> it.getNameAsString())
                .collect(Collectors.toList());
            type.setInterfaces(ifaces);
            ifaces.forEach(iface ->
                ctx.relationships.add(rel(fqn, iface, "IMPLEMENTS", 0)));
        }

        type.setFieldCount(n.getFields().size() + n.getParameters().size());
        type.setMethodCount(n.getMethods().size() + n.getConstructors().size() + n.getCompactConstructors().size() + (hasExplicitConstructor ? 0 : 1) + n.getParameters().size());
        n.getRange().ifPresent(r -> {
            type.setStartLine(r.begin.line);
            type.setEndLine(r.end.line);
            type.setLineCount(r.end.line - r.begin.line + 1);
        });
        ctx.types.add(type);
        super.visit(n, ctx);

        ctx.currentTypeFqn        = prevTypeFqn;
        ctx.currentTypeFieldNames = prevFields;
        ctx.currentTypeFieldTypes = prevFieldTypes;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Compact Constructor (Java Records)
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void visit(CompactConstructorDeclaration n, VisitContext ctx) {
        if (ctx.currentTypeFqn.isEmpty()) { super.visit(n, ctx); return; }
        String prevMethod = ctx.currentMethodFqn;

        String fqn = ctx.currentTypeFqn + ".<init>()";
        ctx.currentMethodFqn = fqn;

        CodeMethod method = new CodeMethod();
        method.setId(fqn);
        method.setFqn(fqn);
        method.setSimpleName("<init>");
        method.setDeclaringTypeFqn(ctx.currentTypeFqn);
        method.setReturnType("void");
        method.setModifiers(modifierString(n.getModifiers()));
        n.getRange().ifPresent(r -> {
            method.setStartLine(r.begin.line);
            method.setEndLine(r.end.line);
        });
        method.setCyclomaticComplexity(computeComplexity(n));
        method.setBodyHash(hashBody(n.getBody().toString()));

        ctx.methods.add(method);
        super.visit(n, ctx);
        ctx.currentMethodFqn = prevMethod;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Field declaration
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void visit(FieldDeclaration n, VisitContext ctx) {
        if (ctx.currentTypeFqn.isEmpty()) { super.visit(n, ctx); return; }

        String declaredType = normalizeTypeName(n.getElementType().asString());
        for (VariableDeclarator var : n.getVariables()) {
            String name = var.getNameAsString();
            ctx.currentTypeFieldNames.add(name);   // register for read/write detection
            ctx.currentTypeFieldTypes.put(name, declaredType);

            CodeField field = new CodeField();
            String fqn = ctx.currentTypeFqn + "." + name;
            field.setId(fqn);
            field.setFqn(fqn);
            field.setSimpleName(name);
            field.setDeclaringTypeFqn(ctx.currentTypeFqn);
            field.setFieldType(n.getElementType().asString());
            field.setModifiers(modifierString(n.getModifiers()));
            var.getInitializer().ifPresent(init -> {
                String s = init.toString();
                field.setInitializer(s.length() > 200 ? s.substring(0, 200) : s);
            });
            n.getRange().ifPresent(r -> field.setStartLine(r.begin.line));
            ctx.fields.add(field);
        }
        super.visit(n, ctx);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Method declaration
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void visit(MethodDeclaration n, VisitContext ctx) {
        if (ctx.currentTypeFqn.isEmpty()) { super.visit(n, ctx); return; }
        String prevMethod = ctx.currentMethodFqn;
        Map<String, String> prevScopeVars = ctx.currentScopeVarTypes;
        ctx.currentScopeVarTypes = new HashMap<>(prevScopeVars != null ? prevScopeVars : Collections.emptyMap());

        for (Parameter p : n.getParameters()) {
            ctx.currentScopeVarTypes.put(p.getNameAsString(), normalizeTypeName(p.getType().asString()));
        }

        String paramSig = n.getParameters().stream()
            .map(p -> p.getType().asString())
            .collect(Collectors.joining(","));
        String fqn = ctx.currentTypeFqn + "." + n.getNameAsString() + "(" + paramSig + ")";
        ctx.currentMethodFqn = fqn;

        CodeMethod method = new CodeMethod();
        method.setId(fqn);
        method.setFqn(fqn);
        method.setSimpleName(n.getNameAsString());
        method.setDeclaringTypeFqn(ctx.currentTypeFqn);
        method.setReturnType(n.getType().asString());
        method.setModifiers(modifierString(n.getModifiers()));
        method.setParameters(
            n.getParameters().stream()
                .map(p -> new MethodParam(p.getType().asString(), p.getNameAsString()))
                .collect(Collectors.toList()));
        n.getRange().ifPresent(r -> {
            method.setStartLine(r.begin.line);
            method.setEndLine(r.end.line);
        });
        method.setCyclomaticComplexity(computeComplexity(n));
        n.getBody().ifPresent(body -> method.setBodyHash(hashBody(body.toString())));

        ctx.methods.add(method);
        super.visit(n, ctx);   // recurse to pick up variables & calls inside this method

        ctx.currentScopeVarTypes = prevScopeVars;
        ctx.currentMethodFqn = prevMethod;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Constructor (treated like a method named "<init>")
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void visit(ConstructorDeclaration n, VisitContext ctx) {
        if (ctx.currentTypeFqn.isEmpty()) { super.visit(n, ctx); return; }
        String prevMethod = ctx.currentMethodFqn;
        Map<String, String> prevScopeVars = ctx.currentScopeVarTypes;
        ctx.currentScopeVarTypes = new HashMap<>(prevScopeVars != null ? prevScopeVars : Collections.emptyMap());

        for (Parameter p : n.getParameters()) {
            ctx.currentScopeVarTypes.put(p.getNameAsString(), normalizeTypeName(p.getType().asString()));
        }

        String paramSig = n.getParameters().stream()
            .map(p -> p.getType().asString())
            .collect(Collectors.joining(","));
        String fqn = ctx.currentTypeFqn + ".<init>(" + paramSig + ")";
        ctx.currentMethodFqn = fqn;

        CodeMethod method = new CodeMethod();
        method.setId(fqn);  method.setFqn(fqn);
        method.setSimpleName("<init>");
        method.setDeclaringTypeFqn(ctx.currentTypeFqn);
        method.setReturnType("void");
        method.setModifiers(modifierString(n.getModifiers()));
        method.setParameters(
            n.getParameters().stream()
                .map(p -> new MethodParam(p.getType().asString(), p.getNameAsString()))
                .collect(Collectors.toList()));
        n.getRange().ifPresent(r -> {
            method.setStartLine(r.begin.line);
            method.setEndLine(r.end.line);
        });
        method.setCyclomaticComplexity(computeComplexity(n));
        method.setBodyHash(hashBody(n.getBody().toString()));

        ctx.methods.add(method);
        super.visit(n, ctx);

        ctx.currentScopeVarTypes = prevScopeVars;
        ctx.currentMethodFqn = prevMethod;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Local Variable Declaration (tracks variable types inside method bodies)
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void visit(VariableDeclarator n, VisitContext ctx) {
        if (ctx.currentMethodFqn != null && !ctx.currentMethodFqn.isEmpty() && ctx.currentScopeVarTypes != null) {
            String name = n.getNameAsString();
            String normType = normalizeTypeName(n.getType().asString());
            if (!normType.isEmpty() && !"var".equalsIgnoreCase(normType)) {
                ctx.currentScopeVarTypes.put(name, normType);
            }
        }
        super.visit(n, ctx);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Method call expression → CALLS relationship
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void visit(MethodCallExpr n, VisitContext ctx) {
        if (ctx.currentMethodFqn.isEmpty()) { super.visit(n, ctx); return; }

        String calleeName = n.getNameAsString();
        String calleeTarget;

        if (n.getScope().isPresent()) {
            String scopeStr = n.getScope().get().toString().trim();
            if ("this".equals(scopeStr) || "super".equals(scopeStr)) {
                // Same-class call — we can build the exact FQN target
                calleeTarget = ctx.currentTypeFqn + "." + calleeName;
            } else {
                String targetType = null;
                if (scopeStr.startsWith("this.")) {
                    String fieldName = scopeStr.substring(5).trim();
                    targetType = ctx.currentTypeFieldTypes.get(fieldName);
                } else if (!scopeStr.contains("(") && !scopeStr.contains(" ")) {
                    // 1. Check active scope local variables or parameters
                    targetType = ctx.currentScopeVarTypes != null ? ctx.currentScopeVarTypes.get(scopeStr) : null;
                    // 2. Check class field
                    if (targetType == null || targetType.isEmpty()) {
                        targetType = ctx.currentTypeFieldTypes.get(scopeStr);
                    }
                    // 3. Check if scopeStr itself looks like a class name (e.g. Account or AccountService)
                    if ((targetType == null || targetType.isEmpty()) && scopeStr.matches("^[A-Z][a-zA-Z0-9_]*$")) {
                        targetType = scopeStr;
                    }
                }

                if (targetType != null && !targetType.isEmpty()) {
                    // Resolve simple type to FQN if in imports or current package
                    String resolvedFqn = ctx.imports.get(targetType);
                    if (resolvedFqn == null) {
                        if (targetType.contains(".")) {
                            resolvedFqn = targetType;
                        } else if (!ctx.packageName.isEmpty()) {
                            // Candidate in the same package
                            resolvedFqn = ctx.packageName + "." + targetType;
                        } else {
                            resolvedFqn = targetType;
                        }
                    }
                    calleeTarget = "~" + resolvedFqn + "." + calleeName;
                } else {
                    // Unknown receiver type — mark with "~" prefix for post-scan resolution
                    calleeTarget = "~" + scopeStr + "." + calleeName;
                }
            }
        } else {
            // No scope → assumed same-class method call
            calleeTarget = ctx.currentTypeFqn + "." + calleeName;
        }

        CodeRelationship rel = new CodeRelationship();
        int line = n.getRange().map(r -> r.begin.line).orElse(0);
        rel.setId(deterministicRelId(ctx.currentMethodFqn, calleeTarget, "CALLS", line));
        rel.setFromEntityFqn(ctx.currentMethodFqn);
        rel.setToEntityFqn(calleeTarget);
        rel.setKind("CALLS");
        rel.setSourceLine(line);
        ctx.relationships.add(rel);

        super.visit(n, ctx);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Assignment expression → WRITES_FIELD relationship
    // Detects: this.field = …  or  fieldName = … (where fieldName is a known field)
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void visit(AssignExpr n, VisitContext ctx) {
        if (ctx.currentMethodFqn.isEmpty()) { super.visit(n, ctx); return; }

        String fieldName = null;
        Expression target = n.getTarget();

        if (target.isFieldAccessExpr()) {
            FieldAccessExpr fa = target.asFieldAccessExpr();
            // e.g. this.fee = …
            if ("this".equals(fa.getScope().toString())) {
                fieldName = fa.getNameAsString();
            }
        } else if (target.isNameExpr()) {
            String nm = target.asNameExpr().getNameAsString();
            if (ctx.currentTypeFieldNames.contains(nm)) {
                fieldName = nm;
            }
        }

        if (fieldName != null) {
            int line = n.getRange().map(r -> r.begin.line).orElse(0);
            ctx.relationships.add(
                rel(ctx.currentMethodFqn,
                    ctx.currentTypeFqn + "." + fieldName,
                    "WRITES_FIELD", line));
        }
        super.visit(n, ctx);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Name expression → READS_FIELD (heuristic: name matches a known field)
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void visit(NameExpr n, VisitContext ctx) {
        if (!ctx.currentMethodFqn.isEmpty()
            && ctx.currentTypeFieldNames.contains(n.getNameAsString())) {
            int line = n.getRange().map(r -> r.begin.line).orElse(0);
            ctx.relationships.add(
                rel(ctx.currentMethodFqn,
                    ctx.currentTypeFqn + "." + n.getNameAsString(),
                    "READS_FIELD", line));
        }
        super.visit(n, ctx);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Generates a fast, deterministic, collision-resistant 128-bit hex ID based on relationship
     * source, target, kind, and line without the allocation and lock overhead of MessageDigest / UUID.
     * Guarantees 100% idempotency across parallel workers and rescans.
     */
    public static String deterministicRelId(String from, String to, String kind, int line) {
        long h1 = 0xcbf29ce484222325L;
        long h2 = 0x100000001b3L;
        if (from != null) {
            for (int i = 0; i < from.length(); i++) {
                char c = from.charAt(i);
                h1 = (h1 ^ c) * 0x100000001b3L;
                h2 = (h2 ^ (c * 31L)) * 0xcbf29ce484222325L;
            }
        }
        h1 = (h1 ^ '|') * 0x100000001b3L;
        if (kind != null) {
            for (int i = 0; i < kind.length(); i++) {
                char c = kind.charAt(i);
                h1 = (h1 ^ c) * 0x100000001b3L;
                h2 = (h2 ^ (c * 31L)) * 0xcbf29ce484222325L;
            }
        }
        h1 = (h1 ^ '|') * 0x100000001b3L;
        if (to != null) {
            for (int i = 0; i < to.length(); i++) {
                char c = to.charAt(i);
                h1 = (h1 ^ c) * 0x100000001b3L;
                h2 = (h2 ^ (c * 31L)) * 0xcbf29ce484222325L;
            }
        }
        h1 = (h1 ^ (line & 0xFFFFFFFFL)) * 0x100000001b3L;
        h2 = (h2 ^ ((long) line << 16)) * 0xcbf29ce484222325L;
        return Long.toHexString(h1) + Long.toHexString(h2);
    }

    /** Build a CodeRelationship quickly. */
    private CodeRelationship rel(String from, String to, String kind, int line) {
        CodeRelationship r = new CodeRelationship();
        r.setId(deterministicRelId(from, to, kind, line));
        r.setFromEntityFqn(from);
        r.setToEntityFqn(to);
        r.setKind(kind);
        r.setSourceLine(line);
        return r;
    }

    /** Convert a modifier set to a space-separated string. */
    private String modifierString(Iterable<?> mods) {
        StringBuilder sb = new StringBuilder();
        for (Object m : mods) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(m.toString().trim().toLowerCase()
                       .replace("modifier.", "")
                       .replace("()", ""));
        }
        return sb.toString();
    }

    /**
     * McCabe cyclomatic complexity: count decision-point nodes in the AST.
     * Uses a nested visitor so this only counts nodes inside this method.
     */
    private int computeComplexity(com.github.javaparser.ast.Node n) {
        final int[] count = {0};
        n.walk(node -> {
            if (node instanceof IfStmt
             || node instanceof ForStmt
             || node instanceof ForEachStmt
             || node instanceof WhileStmt
             || node instanceof DoStmt
             || node instanceof CatchClause
             || node instanceof ConditionalExpr
             || node instanceof SwitchEntry) {
                count[0]++;
            } else if (node instanceof BinaryExpr) {
                BinaryExpr.Operator op = ((BinaryExpr) node).getOperator();
                if (op == BinaryExpr.Operator.AND || op == BinaryExpr.Operator.OR) {
                    count[0]++;
                }
            }
        });
        return count[0] + 1; // baseline of 1
    }

    private static final ThreadLocal<MessageDigest> TL_DIGEST = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    });

    private static final char[] HEX_ARRAY = "0123456789abcdef".toCharArray();

    /** First 16 hex chars of SHA-256 of normalised body text without regex or String.format churn. */
    private String hashBody(String body) {
        if (body == null || body.isEmpty()) return "0000000000000000";
        try {
            MessageDigest md = TL_DIGEST.get();
            md.reset();

            // Direct byte streaming with in-flight whitespace collapse (no regex replaceAll)
            boolean inWhitespace = false;
            for (int i = 0; i < body.length(); i++) {
                char c = body.charAt(i);
                if (Character.isWhitespace(c)) {
                    if (!inWhitespace) {
                        md.update((byte) ' ');
                        inWhitespace = true;
                    }
                } else {
                    inWhitespace = false;
                    md.update((byte) c);
                }
            }

            byte[] hash = md.digest();
            char[] hexChars = new char[16];
            for (int j = 0; j < 8; j++) {
                int v = hash[j] & 0xFF;
                hexChars[j * 2]     = HEX_ARRAY[v >>> 4];
                hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
            }
            return new String(hexChars);
        } catch (Exception e) {
            return "0000000000000000";
        }
    }

    private static String normalizeTypeName(String typeStr) {
        if (typeStr == null) return "";
        String s = typeStr.trim();
        while (s.endsWith("[]")) {
            s = s.substring(0, s.length() - 2).trim();
        }
        if (s.contains("<") && s.endsWith(">")) {
            int open = s.indexOf('<');
            int close = s.lastIndexOf('>');
            if (open > 0 && close > open) {
                String outer = s.substring(0, open).trim();
                String inner = s.substring(open + 1, close).trim();
                if (outer.endsWith("Optional") || outer.endsWith("CompletableFuture") || outer.endsWith("Supplier") || outer.endsWith("AtomicReference")) {
                    s = inner;
                }
            }
        }
        return s;
    }
}
