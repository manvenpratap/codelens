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

        // Deduplication set for relationships within the active method/constructor scope
        public Set<String> currentMethodRels = new HashSet<>();

        // Extended semantic graph context (APIs, Database Tables, Events)
        public String currentTypeBaseEndpointPath = "";
        public String currentTypeTableName = "";
        public final Set<String> registeredSyntheticTypes = new HashSet<>();

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
        String prevBaseEndpoint            = ctx.currentTypeBaseEndpointPath;
        String prevTableName               = ctx.currentTypeTableName;

        String simpleName = n.getNameAsString();
        String fqn = (prevTypeFqn != null && !prevTypeFqn.isEmpty())
            ? prevTypeFqn + "." + simpleName
            : (ctx.packageName.isEmpty() ? simpleName : ctx.packageName + "." + simpleName);
        ctx.currentTypeFqn       = fqn;
        ctx.currentTypeFieldNames = new HashSet<>();
        ctx.currentTypeFieldTypes = new HashMap<>();

        // Extract class-level annotations for REST base paths and Database Tables
        String baseEndpoint = "";
        String tableName = "";
        for (AnnotationExpr a : n.getAnnotations()) {
            String aName = a.getNameAsString();
            if (aName.endsWith("RequestMapping") || aName.endsWith("Path")) {
                String p = extractAnnotationString(a, "value");
                if (p.isEmpty()) p = extractAnnotationString(a, "path");
                if (!p.isEmpty()) baseEndpoint = p;
            } else if (aName.endsWith("Table") || aName.endsWith("TableName")) {
                String t = extractAnnotationString(a, "name");
                if (t.isEmpty()) t = extractAnnotationString(a, "value");
                if (!t.isEmpty()) tableName = t;
            } else if (aName.endsWith("Entity") || aName.endsWith("Document")) {
                if (tableName.isEmpty()) {
                    tableName = guessTableFromClassName(simpleName);
                }
            }
        }

        // Heuristic: for Repository / DAO classes without explicit table annotation, infer table from class name
        if (tableName.isEmpty() && (simpleName.endsWith("Repository") || simpleName.endsWith("Dao"))) {
            String entityName = simpleName.replace("Repository", "").replace("Dao", "");
            if (!entityName.isEmpty()) {
                tableName = guessTableFromClassName(entityName);
            }
        }

        ctx.currentTypeBaseEndpointPath = baseEndpoint;
        ctx.currentTypeTableName = tableName;

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
                addRelationship(ctx, fqn, et.getNameAsString(), "EXTENDS", 0);
            });

        List<String> ifaces = n.getImplementedTypes().stream()
            .map(it -> it.getNameAsString())
            .collect(Collectors.toList());
        type.setInterfaces(ifaces);
        ifaces.forEach(iface ->
            addRelationship(ctx, fqn, iface, "IMPLEMENTS", 0));

        // If class is an entity or maps to a database table, register table and MAPS_TO_TABLE relationship
        if (!tableName.isEmpty() && (n.getAnnotations().stream().anyMatch(a -> a.getNameAsString().endsWith("Entity") || a.getNameAsString().endsWith("Table")) || simpleName.endsWith("Entity"))) {
            int line = n.getRange().map(r -> r.begin.line).orElse(0);
            registerTableNode(ctx, tableName, fqn, "MAPS_TO_TABLE", line);
        }

        ctx.types.add(type);
        super.visit(n, ctx);   // recurse into children (fields, methods, inner classes)

        ctx.currentTypeFqn               = prevTypeFqn;
        ctx.currentTypeFieldNames         = prevFields;
        ctx.currentTypeFieldTypes         = prevFieldTypes;
        ctx.currentTypeBaseEndpointPath   = prevBaseEndpoint;
        ctx.currentTypeTableName          = prevTableName;
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
                addRelationship(ctx, getterFqn, fieldFqn, "READS_FIELD", line);
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
                addRelationship(ctx, initFqn, fieldFqn, "WRITES_FIELD", line);
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
                addRelationship(ctx, fqn, iface, "IMPLEMENTS", 0));
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
        Set<String> prevMethodRels = ctx.currentMethodRels;
        ctx.currentMethodRels = new HashSet<>();

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
        ctx.currentMethodRels = prevMethodRels;
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
        Set<String> prevMethodRels = ctx.currentMethodRels;
        ctx.currentScopeVarTypes = new HashMap<>(prevScopeVars != null ? prevScopeVars : Collections.emptyMap());
        ctx.currentMethodRels = new HashSet<>();

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

        int methodLine = n.getRange().map(r -> r.begin.line).orElse(0);

        // A. Inspect method annotations for Endpoints, Queries, and Event Listeners
        for (AnnotationExpr a : n.getAnnotations()) {
            String aName = a.getNameAsString();
            String httpVerb = null;
            String subPath = "";

            if (aName.endsWith("GetMapping") || aName.equals("GET")) {
                httpVerb = "GET";
                subPath = extractAnnotationString(a, "value");
                if (subPath.isEmpty()) subPath = extractAnnotationString(a, "path");
            } else if (aName.endsWith("PostMapping") || aName.equals("POST")) {
                httpVerb = "POST";
                subPath = extractAnnotationString(a, "value");
                if (subPath.isEmpty()) subPath = extractAnnotationString(a, "path");
            } else if (aName.endsWith("PutMapping") || aName.equals("PUT")) {
                httpVerb = "PUT";
                subPath = extractAnnotationString(a, "value");
                if (subPath.isEmpty()) subPath = extractAnnotationString(a, "path");
            } else if (aName.endsWith("DeleteMapping") || aName.equals("DELETE")) {
                httpVerb = "DELETE";
                subPath = extractAnnotationString(a, "value");
                if (subPath.isEmpty()) subPath = extractAnnotationString(a, "path");
            } else if (aName.endsWith("PatchMapping") || aName.equals("PATCH")) {
                httpVerb = "PATCH";
                subPath = extractAnnotationString(a, "value");
                if (subPath.isEmpty()) subPath = extractAnnotationString(a, "path");
            } else if (aName.endsWith("RequestMapping") || aName.endsWith("Path")) {
                subPath = extractAnnotationString(a, "value");
                if (subPath.isEmpty()) subPath = extractAnnotationString(a, "path");
                String methodAttr = extractAnnotationString(a, "method");
                httpVerb = methodAttr.isEmpty() ? "GET" : methodAttr.replace("RequestMethod.", "").toUpperCase();
            }

            if (httpVerb != null) {
                String fullPath = cleanPath(ctx.currentTypeBaseEndpointPath, subPath);
                registerEndpointNode(ctx, httpVerb, fullPath, fqn, methodLine);
            }

            // B. Database Queries via Annotation (@Query, @Select, @Insert, etc.)
            if (aName.endsWith("Query") || aName.endsWith("Select") || aName.endsWith("Insert") || aName.endsWith("Update") || aName.endsWith("Delete")) {
                String sql = extractAnnotationString(a, "value");
                if (!sql.isEmpty()) {
                    List<String> tables = extractSqlTables(sql);
                    boolean isWrite = aName.endsWith("Insert") || aName.endsWith("Update") || aName.endsWith("Delete") || sql.matches("(?i).*\\b(INSERT|UPDATE|DELETE)\\b.*");
                    for (String tbl : tables) {
                        registerTableNode(ctx, tbl, fqn, isWrite ? CodeRelationship.KIND_WRITES_TABLE : CodeRelationship.KIND_READS_TABLE, methodLine);
                        registerTableNode(ctx, tbl, fqn, CodeRelationship.KIND_ACCESSES_TABLE, methodLine);
                    }
                }
            }

            // C. Event Listeners (@EventListener, @KafkaListener, @RabbitListener)
            if (aName.endsWith("EventListener") || aName.endsWith("TransactionalEventListener")) {
                String eventName = extractAnnotationString(a, "classes");
                if (eventName.isEmpty() && !n.getParameters().isEmpty()) {
                    eventName = normalizeTypeName(n.getParameter(0).getType().asString());
                    int dot = eventName.lastIndexOf('.');
                    if (dot >= 0) eventName = eventName.substring(dot + 1);
                }
                if (!eventName.isEmpty()) {
                    registerEventNode(ctx, eventName, fqn, CodeRelationship.KIND_LISTENS_EVENT, methodLine);
                }
            } else if (aName.endsWith("KafkaListener")) {
                String topic = extractAnnotationString(a, "topics");
                if (topic.isEmpty()) topic = extractAnnotationString(a, "value");
                if (!topic.isEmpty()) {
                    registerEventNode(ctx, topic, fqn, CodeRelationship.KIND_LISTENS_EVENT, methodLine);
                }
            } else if (aName.endsWith("RabbitListener")) {
                String queue = extractAnnotationString(a, "queues");
                if (queue.isEmpty()) queue = extractAnnotationString(a, "value");
                if (!queue.isEmpty()) {
                    registerEventNode(ctx, queue, fqn, CodeRelationship.KIND_LISTENS_EVENT, methodLine);
                }
            }
        }

        // D. Link Repository / DAO data access methods to the table
        if (ctx.currentTypeTableName != null && !ctx.currentTypeTableName.isEmpty()) {
            String mName = n.getNameAsString().toLowerCase();
            if (mName.startsWith("save") || mName.startsWith("find") || mName.startsWith("delete") ||
                mName.startsWith("update") || mName.startsWith("insert") || mName.startsWith("get") ||
                mName.startsWith("count") || mName.startsWith("exists")) {
                boolean isWrite = mName.startsWith("save") || mName.startsWith("delete") || mName.startsWith("update") || mName.startsWith("insert");
                registerTableNode(ctx, ctx.currentTypeTableName, fqn, isWrite ? CodeRelationship.KIND_WRITES_TABLE : CodeRelationship.KIND_READS_TABLE, methodLine);
                registerTableNode(ctx, ctx.currentTypeTableName, fqn, CodeRelationship.KIND_ACCESSES_TABLE, methodLine);
            }
        }

        super.visit(n, ctx);   // recurse to pick up variables & calls inside this method

        ctx.currentMethodRels = prevMethodRels;
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
        Set<String> prevMethodRels = ctx.currentMethodRels;
        ctx.currentScopeVarTypes = new HashMap<>(prevScopeVars != null ? prevScopeVars : Collections.emptyMap());
        ctx.currentMethodRels = new HashSet<>();

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

        ctx.currentMethodRels = prevMethodRels;
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
            if ("this".equals(scopeStr) || "super".equals(scopeStr) || scopeStr.endsWith(".this")) {
                // Same-class call or outer class 'this' (e.g. this.Get(), super.Get(), Account.this.Get())
                if (scopeStr.endsWith(".this")) {
                    String qualifier = scopeStr.substring(0, scopeStr.length() - 5).trim();
                    int lastDot = ctx.currentTypeFqn.lastIndexOf('.');
                    String currentSimple = (lastDot >= 0) ? ctx.currentTypeFqn.substring(lastDot + 1) : ctx.currentTypeFqn;
                    if (qualifier.equals(currentSimple)) {
                        calleeTarget = ctx.currentTypeFqn + "." + calleeName;
                    } else if (ctx.currentTypeFqn.contains("." + qualifier)) {
                        int idx = ctx.currentTypeFqn.indexOf("." + qualifier);
                        calleeTarget = ctx.currentTypeFqn.substring(0, idx + qualifier.length() + 1) + "." + calleeName;
                    } else {
                        String enclosingFqn = ctx.imports.get(qualifier);
                        if (enclosingFqn == null && !ctx.packageName.isEmpty()) {
                            enclosingFqn = ctx.packageName + "." + qualifier;
                        }
                        calleeTarget = (enclosingFqn != null ? enclosingFqn : qualifier) + "." + calleeName;
                    }
                } else {
                    calleeTarget = ctx.currentTypeFqn + "." + calleeName;
                }
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
                    // 4. Check if scopeStr follows naming convention matching an imported class (e.g. p_tradeRecord, accountMasterVO)
                    if (targetType == null || targetType.isEmpty()) {
                        targetType = inferTypeFromScopeName(scopeStr, ctx);
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

        int line = n.getRange().map(r -> r.begin.line).orElse(0);
        addRelationship(ctx, ctx.currentMethodFqn, calleeTarget, "CALLS", line);

        // Event publishing calls (publishEvent, send, convertAndSend, emit)
        if (calleeName.equals("publishEvent") || calleeName.equals("send") || calleeName.equals("convertAndSend") || calleeName.equals("emit")) {
            if (!n.getArguments().isEmpty()) {
                Expression arg0 = n.getArgument(0);
                String eventName = null;
                if (arg0.isObjectCreationExpr()) {
                    eventName = normalizeTypeName(arg0.asObjectCreationExpr().getType().asString());
                } else if (arg0.isStringLiteralExpr()) {
                    eventName = arg0.asStringLiteralExpr().getValue();
                } else if (arg0.isNameExpr()) {
                    String varName = arg0.asNameExpr().getNameAsString();
                    eventName = ctx.currentScopeVarTypes.get(varName);
                    if (eventName == null) eventName = ctx.currentTypeFieldTypes.get(varName);
                    if (eventName == null) eventName = varName;
                }
                if (eventName != null && !eventName.isEmpty()) {
                    int dot = eventName.lastIndexOf('.');
                    if (dot >= 0) eventName = eventName.substring(dot + 1);
                    registerEventNode(ctx, eventName, ctx.currentMethodFqn, CodeRelationship.KIND_PUBLISHES_EVENT, line);
                }
            }
        }

        super.visit(n, ctx);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // String literal expression → SQL Table & Query relationship
    // Detects raw SQL DML queries (SELECT, INSERT, UPDATE, DELETE) inside methods
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void visit(StringLiteralExpr n, VisitContext ctx) {
        if (ctx.currentMethodFqn != null && !ctx.currentMethodFqn.isEmpty()) {
            String lit = n.getValue();
            if (lit != null && lit.length() > 8 && lit.matches("(?i).*\\b(SELECT|INSERT\\s+INTO|UPDATE|DELETE\\s+FROM)\\b.*")) {
                List<String> tables = extractSqlTables(lit);
                int line = n.getRange().map(r -> r.begin.line).orElse(0);
                boolean isWrite = lit.matches("(?i).*\\b(INSERT|UPDATE|DELETE)\\b.*");
                for (String tbl : tables) {
                    registerTableNode(ctx, tbl, ctx.currentMethodFqn, isWrite ? CodeRelationship.KIND_WRITES_TABLE : CodeRelationship.KIND_READS_TABLE, line);
                    registerTableNode(ctx, tbl, ctx.currentMethodFqn, CodeRelationship.KIND_ACCESSES_TABLE, line);
                }
            }
        }
        super.visit(n, ctx);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Assignment expression → WRITES_FIELD relationship
    // Detects: this.field = …  or  fieldName = … (where fieldName is a known field)
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void visit(AssignExpr n, VisitContext ctx) {
        if (ctx.currentMethodFqn.isEmpty() || isBoilerplateFieldAccessMethod(ctx.currentMethodFqn)) {
            super.visit(n, ctx);
            return;
        }

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
            addRelationship(ctx, ctx.currentMethodFqn,
                ctx.currentTypeFqn + "." + fieldName,
                "WRITES_FIELD", line);
            if (n.getOperator() != AssignExpr.Operator.ASSIGN) {
                addRelationship(ctx, ctx.currentMethodFqn,
                    ctx.currentTypeFqn + "." + fieldName,
                    "READS_FIELD", line);
            }
            // Crucial: Only visit the RHS (value) of the assignment.
            // If we called super.visit(n, ctx), the target NameExpr would also be visited
            // by visit(NameExpr), which would erroneously emit a duplicate READS_FIELD relationship for this write.
            n.getValue().accept(this, ctx);
            return;
        }
        super.visit(n, ctx);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Name expression & Field access → READS_FIELD (heuristic: name matches a known field)
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void visit(NameExpr n, VisitContext ctx) {
        if (!ctx.currentMethodFqn.isEmpty()
            && !isBoilerplateFieldAccessMethod(ctx.currentMethodFqn)
            && ctx.currentTypeFieldNames.contains(n.getNameAsString())) {
            int line = n.getRange().map(r -> r.begin.line).orElse(0);
            addRelationship(ctx, ctx.currentMethodFqn,
                ctx.currentTypeFqn + "." + n.getNameAsString(),
                "READS_FIELD", line);
        }
        super.visit(n, ctx);
    }

    @Override
    public void visit(FieldAccessExpr n, VisitContext ctx) {
        if (!ctx.currentMethodFqn.isEmpty()
            && !isBoilerplateFieldAccessMethod(ctx.currentMethodFqn)) {
            String scope = n.getScope().toString();
            String fieldName = n.getNameAsString();
            if ("this".equals(scope) && ctx.currentTypeFieldNames.contains(fieldName)) {
                int line = n.getRange().map(r -> r.begin.line).orElse(0);
                addRelationship(ctx, ctx.currentMethodFqn,
                    ctx.currentTypeFqn + "." + fieldName,
                    "READS_FIELD", line);
            }
        }
        super.visit(n, ctx);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static final Set<String> IGNORED_SCOPE_NAMES = Set.of(
        "log", "logger", "LOG", "LOGGER",
        "System.out", "System.err", "out", "err",
        "Math", "Objects", "Arrays", "Collections", "Optional", "Thread",
        "String", "StringBuilder", "StringBuffer",
        "Integer", "Long", "Double", "Float", "Boolean", "Byte", "Short", "Character"
    );

    /**
     * Identifies generated or trivial boilerplate methods whose field accesses
     * (e.g. copying 200 fields in deepcopy, resetting 200 fields, or dumping in toString)
     * create tens of millions of useless READS_FIELD/WRITES_FIELD rows that choke database ingestion.
     */
    public static boolean isBoilerplateFieldAccessMethod(String methodFqn) {
        if (methodFqn == null || methodFqn.isEmpty()) return false;
        int parenIdx = methodFqn.indexOf('(');
        String sub = (parenIdx > 0) ? methodFqn.substring(0, parenIdx) : methodFqn;
        int dotIdx = sub.lastIndexOf('.');
        String name = (dotIdx >= 0) ? sub.substring(dotIdx + 1) : sub;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("deepcopy") || lower.equals("clone") || lower.equals("reset")
            || lower.equals("clear") || lower.equals("tostring") || lower.equals("hashcode")
            || lower.equals("equals") || lower.equals("canequal");
    }

    /**
     * Infers receiver type from variable naming conventions matching imported types
     * (e.g. p_tradeRecord -> TradeRecord, accountMasterVO -> AccountMasterVO).
     */
    private static String inferTypeFromScopeName(String scopeStr, VisitContext ctx) {
        if (scopeStr == null || scopeStr.isEmpty() || ctx.imports == null || ctx.imports.isEmpty()) {
            return null;
        }
        String clean = scopeStr;
        if (clean.startsWith("p_") || clean.startsWith("m_") || clean.startsWith("v_")
            || clean.startsWith("r_") || clean.startsWith("s_")) {
            clean = clean.substring(2);
        } else if (clean.startsWith("in_") || clean.startsWith("out_")) {
            clean = clean.substring(3);
        }
        String normalized = clean.replace("_", "").toLowerCase(Locale.ROOT);
        if (normalized.length() < 3) return null;

        for (String simpleImport : ctx.imports.keySet()) {
            if (simpleImport.equalsIgnoreCase(clean)
                || simpleImport.replace("_", "").toLowerCase(Locale.ROOT).equals(normalized)) {
                return simpleImport;
            }
        }
        return null;
    }

    /**
     * Determines whether a call target should be ignored.
     * Heuristically filters out unresolvable JDK/logging targets or chained receiver calls
     * that would otherwise bloat the relationship table with millions of rows that the
     * call graph analyzer discards anyway.
     */
    public static boolean isIgnoredCalleeTarget(String target) {
        if (target == null || target.isEmpty()) return true;
        if (!target.startsWith("~")) return false; // intra-class / resolved calls are never ignored
        String raw = target.substring(1);
        // Chained calls with method invocation or lambda in receiver: e.g. builder.foo().bar, v -> ...
        if (raw.contains("(") || raw.contains(")") || raw.contains("->") || raw.contains("::")) {
            return true;
        }
        // Known JDK and logging package prefixes
        if (raw.startsWith("java.") || raw.startsWith("javax.") || raw.startsWith("jakarta.")
            || raw.startsWith("sun.") || raw.startsWith("jdk.")
            || raw.startsWith("org.slf4j.") || raw.startsWith("org.apache.log4j.")
            || raw.startsWith("org.apache.commons.logging.") || raw.startsWith("org.apache.logging.log4j.")
            || raw.startsWith("System.out.") || raw.startsWith("System.err.")) {
            return true;
        }
        int lastDot = raw.lastIndexOf('.');
        if (lastDot > 0) {
            String scope = raw.substring(0, lastDot);
            if (IGNORED_SCOPE_NAMES.contains(scope)) {
                return true;
            }
            String methodName = raw.substring(lastDot + 1);
            String lowerMethod = methodName.toLowerCase(Locale.ROOT);
            if (lowerMethod.equals("tostring") || lowerMethod.equals("hashcode")
                || lowerMethod.equals("equals") || lowerMethod.equals("canequal")
                || lowerMethod.equals("getclass") || lowerMethod.equals("clone")
                || lowerMethod.equals("deepcopy")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Records a relationship with method-level deduplication and noise filtering.
     */
    public void addRelationship(VisitContext ctx, String from, String to, String kind, int line) {
        if (from == null || to == null || kind == null) return;
        if ("CALLS".equals(kind) && isIgnoredCalleeTarget(to)) {
            return;
        }
        if (ctx.currentMethodRels != null) {
            String dedupKey = from + "|" + to + "|" + kind;
            if (!ctx.currentMethodRels.add(dedupKey)) {
                return; // already recorded in this method scope
            }
        }
        ctx.relationships.add(rel(from, to, kind, line));
    }

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
            return java.util.HexFormat.of().formatHex(hash, 0, 8);
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

    // ─────────────────────────────────────────────────────────────────────────
    // Semantic Graph Extraction Helpers (Endpoints, Tables, Events)
    // ─────────────────────────────────────────────────────────────────────────

    private static String extractAnnotationString(AnnotationExpr a, String memberName) {
        if (a == null) return "";
        try {
            if (a.isSingleMemberAnnotationExpr()) {
                return expressionToString(a.asSingleMemberAnnotationExpr().getMemberValue());
            } else if (a.isNormalAnnotationExpr()) {
                for (MemberValuePair pair : a.asNormalAnnotationExpr().getPairs()) {
                    if (memberName == null || pair.getNameAsString().equalsIgnoreCase(memberName) || "value".equalsIgnoreCase(pair.getNameAsString())) {
                        return expressionToString(pair.getValue());
                    }
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static String expressionToString(Expression val) {
        if (val == null) return "";
        if (val.isStringLiteralExpr()) {
            return val.asStringLiteralExpr().getValue();
        }
        if (val.isArrayInitializerExpr()) {
            var elements = val.asArrayInitializerExpr().getValues();
            if (!elements.isEmpty()) {
                return expressionToString(elements.get(0));
            }
        }
        String s = val.toString().trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String cleanPath(String base, String sub) {
        if (base == null) base = "";
        if (sub == null) sub = "";
        base = base.trim();
        sub = sub.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (!sub.isEmpty() && !sub.startsWith("/")) sub = "/" + sub;
        String res = base + sub;
        if (res.isEmpty()) res = "/";
        return res;
    }

    private static String guessTableFromClassName(String simpleName) {
        if (simpleName == null || simpleName.isEmpty()) return "";
        String s = simpleName;
        if (s.endsWith("Entity")) s = s.substring(0, s.length() - 6);
        if (s.endsWith("Model"))  s = s.substring(0, s.length() - 5);
        if (s.endsWith("DO"))     s = s.substring(0, s.length() - 2);
        if (s.endsWith("PO"))     s = s.substring(0, s.length() - 2);
        if (s.isEmpty()) return "";
        return s.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase();
    }

    private static final java.util.regex.Pattern SQL_TABLE_PATTERN =
        java.util.regex.Pattern.compile("(?i)\\b(?:FROM|JOIN|INTO|UPDATE)\\s+[`\"\\[]?([a-zA-Z0-9_]+)[`\"\\]]?");

    private static List<String> extractSqlTables(String sql) {
        if (sql == null || sql.isBlank()) return Collections.emptyList();
        List<String> list = new ArrayList<>();
        var m = SQL_TABLE_PATTERN.matcher(sql);
        while (m.find()) {
            String tbl = m.group(1).trim().toUpperCase();
            if (tbl.equalsIgnoreCase("SET") || tbl.equalsIgnoreCase("VALUES") ||
                tbl.equalsIgnoreCase("WHERE") || tbl.equalsIgnoreCase("SELECT") ||
                tbl.equalsIgnoreCase("DUAL") || tbl.equalsIgnoreCase("ORDER") || tbl.length() < 2) {
                continue;
            }
            if (!list.contains(tbl)) list.add(tbl);
        }
        return list;
    }

    private void registerEndpointNode(VisitContext ctx, String verb, String path, String methodFqn, int line) {
        String endpointFqn = "endpoint:" + verb + " " + path;
        if (ctx.registeredSyntheticTypes.add(endpointFqn)) {
            CodeType ep = new CodeType();
            ep.setId(endpointFqn);
            ep.setFqn(endpointFqn);
            ep.setSimpleName(verb + " " + path);
            ep.setPackageFqn(ctx.packageName.isEmpty() ? "api" : ctx.packageName);
            ep.setKind(CodeType.KIND_ENDPOINT);
            ep.setModifiers("HTTP " + verb);
            ep.setSourceFile(ctx.sourceFile);
            ep.setStartLine(line);
            ep.setEndLine(line);
            ctx.types.add(ep);
        }
        addRelationship(ctx, endpointFqn, methodFqn, CodeRelationship.KIND_HANDLED_BY, line);
        addRelationship(ctx, methodFqn, endpointFqn, CodeRelationship.KIND_EXPOSES_ENDPOINT, line);
    }

    private void registerTableNode(VisitContext ctx, String tableName, String fromFqn, String relKind, int line) {
        if (tableName == null || tableName.isBlank()) return;
        String cleanTable = tableName.trim().toUpperCase();
        if (cleanTable.length() < 2 || cleanTable.equalsIgnoreCase("DUAL")) return;
        String tableFqn = "table:" + cleanTable;
        if (ctx.registeredSyntheticTypes.add(tableFqn)) {
            CodeType tbl = new CodeType();
            tbl.setId(tableFqn);
            tbl.setFqn(tableFqn);
            tbl.setSimpleName(cleanTable);
            tbl.setPackageFqn("database");
            tbl.setKind(CodeType.KIND_TABLE);
            tbl.setModifiers("DATABASE TABLE");
            tbl.setSourceFile(ctx.sourceFile);
            tbl.setStartLine(line);
            tbl.setEndLine(line);
            ctx.types.add(tbl);
        }
        if (fromFqn != null && !fromFqn.isBlank()) {
            addRelationship(ctx, fromFqn, tableFqn, relKind != null ? relKind : CodeRelationship.KIND_ACCESSES_TABLE, line);
        }
    }

    private void registerEventNode(VisitContext ctx, String eventName, String fromFqn, String relKind, int line) {
        if (eventName == null || eventName.isBlank()) return;
        String cleanEvent = eventName.trim();
        if (cleanEvent.length() < 2) return;
        String eventFqn = "event:" + cleanEvent;
        if (ctx.registeredSyntheticTypes.add(eventFqn)) {
            CodeType ev = new CodeType();
            ev.setId(eventFqn);
            ev.setFqn(eventFqn);
            ev.setSimpleName(cleanEvent);
            ev.setPackageFqn("events");
            ev.setKind(CodeType.KIND_EVENT);
            ev.setModifiers("DOMAIN EVENT");
            ev.setSourceFile(ctx.sourceFile);
            ev.setStartLine(line);
            ev.setEndLine(line);
            ctx.types.add(ev);
        }
        if (fromFqn != null && !fromFqn.isBlank()) {
            addRelationship(ctx, fromFqn, eventFqn, relKind != null ? relKind : CodeRelationship.KIND_PUBLISHES_EVENT, line);
        }
    }
}
