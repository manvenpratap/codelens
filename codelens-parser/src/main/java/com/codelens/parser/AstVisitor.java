package com.codelens.parser;

import com.codelens.core.model.*;
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
        // field names declared on the current type — used to distinguish field
        // reads/writes from local variable accesses
        public Set<String> currentTypeFieldNames = new HashSet<>();

        public final List<CodePackage>      packages      = new ArrayList<>();
        public final List<CodeType>         types         = new ArrayList<>();
        public final List<CodeField>        fields        = new ArrayList<>();
        public final List<CodeMethod>       methods       = new ArrayList<>();
        public final List<CodeRelationship> relationships = new ArrayList<>();
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
        String prevTypeFqn       = ctx.currentTypeFqn;
        Set<String> prevFields   = ctx.currentTypeFieldNames;

        String simpleName = n.getNameAsString();
        String fqn = ctx.packageName.isEmpty() ? simpleName
                                               : ctx.packageName + "." + simpleName;
        ctx.currentTypeFqn       = fqn;
        ctx.currentTypeFieldNames = new HashSet<>();

        CodeType type = new CodeType();
        type.setId(fqn);
        type.setFqn(fqn);
        type.setSimpleName(simpleName);
        type.setPackageFqn(ctx.packageName);
        type.setKind(n.isInterface() ? "INTERFACE" : "CLASS");
        type.setModifiers(modifierString(n.getModifiers()));
        type.setSourceFile(ctx.sourceFile);

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
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Enum
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void visit(EnumDeclaration n, VisitContext ctx) {
        String prevTypeFqn     = ctx.currentTypeFqn;
        Set<String> prevFields = ctx.currentTypeFieldNames;

        String simpleName = n.getNameAsString();
        String fqn = ctx.packageName.isEmpty() ? simpleName
                                               : ctx.packageName + "." + simpleName;
        ctx.currentTypeFqn        = fqn;
        ctx.currentTypeFieldNames = new HashSet<>();

        CodeType type = new CodeType();
        type.setId(fqn);  type.setFqn(fqn);
        type.setSimpleName(simpleName);
        type.setPackageFqn(ctx.packageName);
        type.setKind("ENUM");
        type.setModifiers(modifierString(n.getModifiers()));
        type.setSourceFile(ctx.sourceFile);
        n.getRange().ifPresent(r -> {
            type.setStartLine(r.begin.line);
            type.setEndLine(r.end.line);
            type.setLineCount(r.end.line - r.begin.line + 1);
        });
        ctx.types.add(type);
        super.visit(n, ctx);

        ctx.currentTypeFqn        = prevTypeFqn;
        ctx.currentTypeFieldNames = prevFields;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Field declaration
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void visit(FieldDeclaration n, VisitContext ctx) {
        if (ctx.currentTypeFqn.isEmpty()) { super.visit(n, ctx); return; }

        for (VariableDeclarator var : n.getVariables()) {
            String name = var.getNameAsString();
            ctx.currentTypeFieldNames.add(name);   // register for read/write detection

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
        super.visit(n, ctx);   // recurse to pick up calls inside this method

        ctx.currentMethodFqn = prevMethod;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Constructor (treated like a method named "<init>")
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void visit(ConstructorDeclaration n, VisitContext ctx) {
        if (ctx.currentTypeFqn.isEmpty()) { super.visit(n, ctx); return; }
        String prevMethod = ctx.currentMethodFqn;

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
        ctx.currentMethodFqn = prevMethod;
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
            String scopeStr = n.getScope().get().toString();
            if ("this".equals(scopeStr) || "super".equals(scopeStr)) {
                // Same-class call — we can build the exact FQN target
                calleeTarget = ctx.currentTypeFqn + "." + calleeName;
            } else {
                // Unknown receiver type — mark with "~" prefix for post-scan resolution
                calleeTarget = "~" + scopeStr + "." + calleeName;
            }
        } else {
            // No scope → assumed same-class method call
            calleeTarget = ctx.currentTypeFqn + "." + calleeName;
        }

        CodeRelationship rel = new CodeRelationship();
        rel.setId(UUID.randomUUID().toString());
        rel.setFromEntityFqn(ctx.currentMethodFqn);
        rel.setToEntityFqn(calleeTarget);
        rel.setKind("CALLS");
        n.getRange().ifPresent(r -> rel.setSourceLine(r.begin.line));
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
            ctx.relationships.add(
                rel(ctx.currentMethodFqn,
                    ctx.currentTypeFqn + "." + fieldName,
                    "WRITES_FIELD", 0));
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
            ctx.relationships.add(
                rel(ctx.currentMethodFqn,
                    ctx.currentTypeFqn + "." + n.getNameAsString(),
                    "READS_FIELD", 0));
        }
        super.visit(n, ctx);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Build a CodeRelationship quickly. */
    private CodeRelationship rel(String from, String to, String kind, int line) {
        CodeRelationship r = new CodeRelationship();
        r.setId(UUID.randomUUID().toString());
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

    /** First 16 hex chars of SHA-256 of normalised body text. */
    private String hashBody(String body) {
        try {
            String normalised = body.replaceAll("\\s+", " ").trim();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(normalised.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return "0000000000000000";
        }
    }
}
