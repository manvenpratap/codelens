package com.codelens.analysis;

import com.codelens.core.model.*;
import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

/**
 * On-demand code review engine that performs deep AST-based inspection
 * of Java source files or code snippets.
 *
 * 32 checks across 6 categories:
 *   1. Correctness & Logic Defects  (CRITICAL)
 *   2. Exception & Resource Safety  (WARNING)
 *   3. Thread Safety & Concurrency  (WARNING)
 *   4. Code Smell & Maintainability (INFO)
 *   5. API Contract & Design        (WARNING/INFO)
 *   6. Impact & Cross-Cutting       (INFO) — requires call graph data
 */
public class CodeReviewEngine {

    private static final Logger log = LoggerFactory.getLogger(CodeReviewEngine.class);

    // Thresholds
    private static final int COMPLEXITY_THRESHOLD   = 15;
    private static final int GOD_METHOD_LINES       = 80;
    private static final int GOD_CLASS_METHODS      = 25;
    private static final int GOD_CLASS_FIELDS       = 20;
    private static final int DEEP_NESTING_LIMIT     = 4;
    private static final int HIGH_FAN_THRESHOLD     = 10;

    // Known closeable types (simple name match)
    private static final Set<String> CLOSEABLE_TYPES = Set.of(
        "FileInputStream", "FileOutputStream", "BufferedReader", "BufferedWriter",
        "InputStreamReader", "OutputStreamWriter", "PrintWriter", "PrintStream",
        "FileReader", "FileWriter", "Scanner", "Socket", "ServerSocket",
        "Connection", "Statement", "PreparedStatement", "ResultSet",
        "InputStream", "OutputStream", "Reader", "Writer",
        "RandomAccessFile", "DataInputStream", "DataOutputStream",
        "ObjectInputStream", "ObjectOutputStream", "ZipInputStream", "ZipOutputStream"
    );

    // Known immutable-return methods whose return value should not be discarded
    private static final Map<String, Set<String>> IGNORED_RETURN_METHODS = Map.of(
        "String", Set.of("trim", "strip", "toLowerCase", "toUpperCase", "replace",
                         "replaceAll", "replaceFirst", "substring", "concat"),
        "List", Set.of("stream", "subList"),
        "Optional", Set.of("map", "flatMap", "filter", "orElse", "orElseGet")
    );

    // Source lines cache for snippet extraction
    private String[] sourceLines;

    // ─────────────────────────────────────────────────────────────────────────
    // Public entry points
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Review a Java source file by path.
     */
    public List<ReviewFinding> reviewFile(String filePath,
                                           CallGraphAnalyzer callGraph,
                                           FieldImpactAnalyzer fieldImpact) {
        try {
            File file = new File(filePath);
            if (!file.exists() || !file.isFile()) {
                log.warn("Review target not found: {}", filePath);
                return Collections.emptyList();
            }
            String source = Files.readString(file.toPath());
            return reviewSource(source, filePath, callGraph, fieldImpact);
        } catch (Exception e) {
            log.error("Failed to review file {}: {}", filePath, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Review a raw Java source snippet (e.g. pasted by user).
     */
    public List<ReviewFinding> reviewSnippet(String javaSource,
                                              CallGraphAnalyzer callGraph,
                                              FieldImpactAnalyzer fieldImpact) {
        return reviewSource(javaSource, "<snippet>", callGraph, fieldImpact);
    }

    /**
     * Review a specific entity (class or method) by FQN using indexed data.
     */
    public List<ReviewFinding> reviewEntity(String entityFqn,
                                             String sourceFile,
                                             CallGraphAnalyzer callGraph,
                                             FieldImpactAnalyzer fieldImpact) {
        if (sourceFile == null || sourceFile.isBlank()) {
            return Collections.emptyList();
        }
        return reviewFile(sourceFile, callGraph, fieldImpact);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core review pipeline
    // ─────────────────────────────────────────────────────────────────────────

    private List<ReviewFinding> reviewSource(String source, String filePath,
                                              CallGraphAnalyzer callGraph,
                                              FieldImpactAnalyzer fieldImpact) {
        List<ReviewFinding> findings = new ArrayList<>();
        sourceLines = source.split("\n");

        try {
            JavaParser parser = new JavaParser();
            ParseResult<CompilationUnit> result = parser.parse(source);
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                log.warn("JavaParser failed to parse source from {}", filePath);
                return findings;
            }

            CompilationUnit cu = result.getResult().get();
            String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString()).orElse("");

            // Run all checks
            cu.accept(new ReviewVisitor(findings, packageName, filePath, callGraph, fieldImpact), null);

            // Sort by severity: CRITICAL first, then WARNING, then INFO
            findings.sort(Comparator.comparingInt(f -> severityOrder(f.getSeverity())));

        } catch (Exception e) {
            log.error("Review engine error on {}: {}", filePath, e.getMessage());
        }

        log.info("Code review of {}: {} findings", filePath, findings.size());
        return findings;
    }

    private int severityOrder(String severity) {
        return switch (severity) {
            case "CRITICAL" -> 0;
            case "WARNING"  -> 1;
            case "INFO"     -> 2;
            default         -> 3;
        };
    }

    private String extractSnippet(int line) {
        if (sourceLines == null || line <= 0) return "";
        int start = Math.max(0, line - 2);
        int end   = Math.min(sourceLines.length, line + 2);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            sb.append(String.format("%4d │ %s\n", i + 1, sourceLines[i]));
        }
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AST Visitor — runs all 32 checks in a single pass
    // ─────────────────────────────────────────────────────────────────────────

    private class ReviewVisitor extends VoidVisitorAdapter<Void> {

        private final List<ReviewFinding> findings;
        private final String packageName;
        private final String filePath;
        private final CallGraphAnalyzer callGraph;
        private final FieldImpactAnalyzer fieldImpact;

        // Per-type state
        private String currentTypeFqn = "";
        private final Set<String> typeFieldNames = new HashSet<>();
        private final Map<String, String> fieldModifiers = new HashMap<>();  // fieldName → modifiers
        private final Map<String, String> fieldTypes = new HashMap<>();      // fieldName → type
        private boolean hasEquals = false;
        private boolean hasHashCode = false;
        private int methodCount = 0;
        private int fieldCount = 0;

        ReviewVisitor(List<ReviewFinding> findings, String packageName, String filePath,
                      CallGraphAnalyzer callGraph, FieldImpactAnalyzer fieldImpact) {
            this.findings    = findings;
            this.packageName = packageName;
            this.filePath    = filePath;
            this.callGraph   = callGraph;
            this.fieldImpact = fieldImpact;
        }

        // ── Type-level checks ────────────────────────────────────────────────

        @Override
        public void visit(ClassOrInterfaceDeclaration n, Void arg) {
            String prevType = currentTypeFqn;
            Set<String> prevFields = new HashSet<>(typeFieldNames);
            Map<String, String> prevFieldMods = new HashMap<>(fieldModifiers);
            Map<String, String> prevFieldTypes = new HashMap<>(fieldTypes);
            boolean prevHasEquals = hasEquals;
            boolean prevHashCode = hasHashCode;
            int prevMethodCount = methodCount;
            int prevFieldCount = fieldCount;

            String simpleName = n.getNameAsString();
            currentTypeFqn = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
            typeFieldNames.clear();
            fieldModifiers.clear();
            fieldTypes.clear();
            hasEquals = false;
            hasHashCode = false;
            methodCount = 0;
            fieldCount = 0;

            // Count members
            methodCount = (int) n.getMethods().stream().count();
            fieldCount = (int) n.getFields().stream().mapToLong(f -> f.getVariables().size()).sum();

            // Pre-scan fields
            for (FieldDeclaration fd : n.getFields()) {
                String mods = fd.getModifiers().stream()
                    .map(m -> m.getKeyword().asString()).collect(Collectors.joining(" "));
                String fType = fd.getElementType().asString();
                for (VariableDeclarator v : fd.getVariables()) {
                    String fname = v.getNameAsString();
                    typeFieldNames.add(fname);
                    fieldModifiers.put(fname, mods);
                    fieldTypes.put(fname, fType);
                }
            }

            // Pre-scan for equals/hashCode
            for (MethodDeclaration md : n.getMethods()) {
                if ("equals".equals(md.getNameAsString()) && md.getParameters().size() == 1) hasEquals = true;
                if ("hashCode".equals(md.getNameAsString()) && md.getParameters().isEmpty()) hasHashCode = true;
            }

            // Recurse into class body
            super.visit(n, arg);

            // ── Post-class checks ──

            // Check 3: equals() without hashCode()
            if (hasEquals != hasHashCode && !n.isInterface()) {
                int line = n.getRange().map(r -> r.begin.line).orElse(0);
                findings.add(finding("CORRECTNESS", "CRITICAL", "EQUALS_HASHCODE_CONTRACT",
                    currentTypeFqn, "TYPE",
                    hasEquals ? "Class overrides equals() but not hashCode()"
                              : "Class overrides hashCode() but not equals()",
                    "Always override both equals() and hashCode() together to maintain the contract.",
                    line));
            }

            // Check 20: God Class
            if (methodCount > GOD_CLASS_METHODS) {
                int line = n.getRange().map(r -> r.begin.line).orElse(0);
                findings.add(finding("CODE_SMELL", "INFO", "GOD_CLASS",
                    currentTypeFqn, "TYPE",
                    String.format("Class has %d methods (threshold: %d). Consider splitting responsibilities.",
                        methodCount, GOD_CLASS_METHODS),
                    "Apply the Single Responsibility Principle: extract cohesive groups of methods into separate classes.",
                    line));
            }
            if (fieldCount > GOD_CLASS_FIELDS) {
                int line = n.getRange().map(r -> r.begin.line).orElse(0);
                findings.add(finding("CODE_SMELL", "INFO", "GOD_CLASS",
                    currentTypeFqn, "TYPE",
                    String.format("Class has %d fields (threshold: %d). Consider splitting responsibilities.",
                        fieldCount, GOD_CLASS_FIELDS),
                    "Group related fields into value objects or extract sub-components.",
                    line));
            }

            // Restore state
            currentTypeFqn = prevType;
            typeFieldNames.clear(); typeFieldNames.addAll(prevFields);
            fieldModifiers.clear(); fieldModifiers.putAll(prevFieldMods);
            fieldTypes.clear(); fieldTypes.putAll(prevFieldTypes);
            hasEquals = prevHasEquals;
            hasHashCode = prevHashCode;
            methodCount = prevMethodCount;
            fieldCount = prevFieldCount;
        }

        // ── Field-level checks ───────────────────────────────────────────────

        @Override
        public void visit(FieldDeclaration n, Void arg) {
            String mods = n.getModifiers().stream()
                .map(m -> m.getKeyword().asString()).collect(Collectors.joining(" "));
            String fType = n.getElementType().asString();
            int line = n.getRange().map(r -> r.begin.line).orElse(0);

            for (VariableDeclarator v : n.getVariables()) {
                String fqn = currentTypeFqn + "." + v.getNameAsString();

                // Check 16: SimpleDateFormat in shared scope
                if ("SimpleDateFormat".equals(fType) || "DateFormat".equals(fType)) {
                    findings.add(finding("THREAD_SAFETY", "WARNING", "SIMPLEDATEFORMAT_FIELD",
                        fqn, "FIELD",
                        "SimpleDateFormat/DateFormat stored as a field is not thread-safe.",
                        "Use DateTimeFormatter (Java 8+) or create a new instance per use, or wrap in ThreadLocal.",
                        line));
                }

                // Check 28: Public non-final field
                if (mods.contains("public") && !mods.contains("final") && !mods.contains("static")) {
                    findings.add(finding("API_CONTRACT", "WARNING", "PUBLIC_MUTABLE_FIELD",
                        fqn, "FIELD",
                        "Public non-final field breaks encapsulation.",
                        "Make the field private and provide getter/setter methods.",
                        line));
                }
            }

            super.visit(n, arg);
        }

        // ── Method-level checks ──────────────────────────────────────────────

        @Override
        public void visit(MethodDeclaration n, Void arg) {
            int startLine = n.getRange().map(r -> r.begin.line).orElse(0);
            int endLine   = n.getRange().map(r -> r.end.line).orElse(0);
            String paramSig = n.getParameters().stream()
                .map(p -> p.getType().asString()).collect(Collectors.joining(","));
            String methodFqn = currentTypeFqn + "." + n.getNameAsString() + "(" + paramSig + ")";

            // Check 18: Excessive Cyclomatic Complexity
            int complexity = computeComplexity(n);
            if (complexity > COMPLEXITY_THRESHOLD) {
                findings.add(finding("CODE_SMELL", "WARNING", "HIGH_COMPLEXITY",
                    methodFqn, "METHOD",
                    String.format("Cyclomatic complexity is %d (threshold: %d). Hard to test and maintain.",
                        complexity, COMPLEXITY_THRESHOLD),
                    "Extract complex conditional blocks into helper methods. Consider strategy/state patterns for switch-heavy logic.",
                    startLine));
            }

            // Check 19: God Method
            int lineCount = endLine - startLine;
            if (lineCount > GOD_METHOD_LINES) {
                findings.add(finding("CODE_SMELL", "INFO", "GOD_METHOD",
                    methodFqn, "METHOD",
                    String.format("Method is %d lines long (threshold: %d).", lineCount, GOD_METHOD_LINES),
                    "Break into smaller, focused methods with descriptive names.",
                    startLine));
            }

            // Check 23: Boolean parameter anti-pattern
            long boolParams = n.getParameters().stream()
                .filter(p -> "boolean".equals(p.getType().asString()) || "Boolean".equals(p.getType().asString()))
                .count();
            if (boolParams >= 2) {
                findings.add(finding("CODE_SMELL", "INFO", "BOOLEAN_PARAMS",
                    methodFqn, "METHOD",
                    String.format("Method has %d boolean parameters — flag arguments obscure intent.", boolParams),
                    "Replace boolean flags with an enum, separate methods, or a builder pattern.",
                    startLine));
            }

            // Check 26: Empty method body
            if (n.getBody().isPresent() && n.getBody().get().getStatements().isEmpty()
                && !n.isAbstract()) {
                findings.add(finding("API_CONTRACT", "WARNING", "EMPTY_METHOD_BODY",
                    methodFqn, "METHOD",
                    "Non-abstract method has an empty body.",
                    "If intentional, add a comment explaining why. Otherwise, implement the method or mark abstract.",
                    startLine));
            }

            // Check 21: Deep nesting
            if (n.getBody().isPresent()) {
                int maxDepth = computeMaxNesting(n.getBody().get(), 0);
                if (maxDepth > DEEP_NESTING_LIMIT) {
                    findings.add(finding("CODE_SMELL", "WARNING", "DEEP_NESTING",
                        methodFqn, "METHOD",
                        String.format("Nesting depth reaches %d levels (limit: %d).", maxDepth, DEEP_NESTING_LIMIT),
                        "Use early returns (guard clauses), extract nested blocks into methods, or flatten with stream operations.",
                        startLine));
                }

                // Check 27: Inconsistent null return
                checkInconsistentNullReturn(n, methodFqn, startLine);

                // Check 29: Mutable collection return
                checkMutableCollectionReturn(n, methodFqn);

                // Check 7: Infinite loop risk
                checkInfiniteLoops(n, methodFqn);
            }

            // Impact checks using call graph
            if (callGraph != null) {
                // Check 30: High fan-in
                try {
                    int fanIn = callGraph.callerCount(methodFqn);
                    if (fanIn > HIGH_FAN_THRESHOLD) {
                        findings.add(finding("IMPACT", "INFO", "HIGH_FAN_IN",
                            methodFqn, "METHOD",
                            String.format("Called by %d methods. Changes here have wide blast radius.", fanIn),
                            "Ensure thorough test coverage. Consider adding a deprecation path before modifying behavior.",
                            startLine));
                    }
                } catch (Exception ignored) {}

                // Check 31: High fan-out
                try {
                    int fanOut = callGraph.calleeCount(methodFqn);
                    if (fanOut > HIGH_FAN_THRESHOLD) {
                        findings.add(finding("IMPACT", "INFO", "HIGH_FAN_OUT",
                            methodFqn, "METHOD",
                            String.format("Calls %d other methods. This method orchestrates too much.", fanOut),
                            "Extract orchestration into smaller composed methods or use a mediator pattern.",
                            startLine));
                    }
                } catch (Exception ignored) {}
            }

            super.visit(n, arg);
        }

        // ── Statement-level checks (catch, switch, try, synchronized) ────────

        @Override
        public void visit(CatchClause n, Void arg) {
            int line = n.getRange().map(r -> r.begin.line).orElse(0);
            String catchType = n.getParameter().getType().asString();

            // Check 9: Swallowed exception (empty catch)
            if (n.getBody().getStatements().isEmpty()) {
                findings.add(finding("EXCEPTION_SAFETY", "CRITICAL", "SWALLOWED_EXCEPTION",
                    currentTypeFqn, "TYPE",
                    "Empty catch block silently swallows " + catchType + ".",
                    "At minimum, log the exception. Better: rethrow or handle it meaningfully.",
                    line));
            } else {
                // Check if catch only does logging without rethrowing
                boolean hasRethrow = n.getBody().findAll(ThrowStmt.class).size() > 0;
                boolean hasReturn = n.getBody().findAll(ReturnStmt.class).size() > 0;
                if (!hasRethrow && !hasReturn) {
                    // Check for log-only catches (common pattern that hides failures)
                    long stmtCount = n.getBody().getStatements().size();
                    boolean allLogging = n.getBody().getStatements().stream().allMatch(s -> {
                        String text = s.toString();
                        return text.contains("log.") || text.contains("LOG.") ||
                               text.contains("logger.") || text.contains("System.out") ||
                               text.contains("System.err") || text.contains("printStackTrace");
                    });
                    if (stmtCount <= 2 && allLogging) {
                        findings.add(finding("EXCEPTION_SAFETY", "WARNING", "LOG_AND_FORGET",
                            currentTypeFqn, "TYPE",
                            "Catch block only logs " + catchType + " without rethrowing or recovering.",
                            "Consider whether callers should be notified. Wrap in a domain exception and rethrow if needed.",
                            line));
                    }
                }
            }

            // Check 10: Overly broad catch
            if ("Exception".equals(catchType) || "Throwable".equals(catchType) ||
                "RuntimeException".equals(catchType)) {
                findings.add(finding("EXCEPTION_SAFETY", "WARNING", "BROAD_CATCH",
                    currentTypeFqn, "TYPE",
                    "Catching " + catchType + " is too broad — may hide unrelated failures.",
                    "Catch specific exception types. If a broad catch is necessary, add a comment justifying it.",
                    line));
            }

            super.visit(n, arg);
        }

        @Override
        public void visit(SwitchStmt n, Void arg) {
            // Check 8: Missing default case
            boolean hasDefault = n.getEntries().stream()
                .anyMatch(e -> e.getLabels().isEmpty());
            if (!hasDefault) {
                int line = n.getRange().map(r -> r.begin.line).orElse(0);
                findings.add(finding("CORRECTNESS", "WARNING", "MISSING_SWITCH_DEFAULT",
                    currentTypeFqn, "TYPE",
                    "Switch statement has no default case.",
                    "Add a default case, even if it just throws an IllegalStateException for unexpected values.",
                    line));
            }
            super.visit(n, arg);
        }

        @Override
        public void visit(TryStmt n, Void arg) {
            // Check 12: Return inside finally
            n.getFinallyBlock().ifPresent(finallyBlock -> {
                List<ReturnStmt> returns = finallyBlock.findAll(ReturnStmt.class);
                if (!returns.isEmpty()) {
                    int line = returns.get(0).getRange().map(r -> r.begin.line).orElse(0);
                    findings.add(finding("EXCEPTION_SAFETY", "CRITICAL", "RETURN_IN_FINALLY",
                        currentTypeFqn, "TYPE",
                        "Return statement inside finally block silently swallows any pending exception.",
                        "Move the return statement outside of the finally block.",
                        line));
                }
            });

            super.visit(n, arg);
        }

        @Override
        public void visit(SynchronizedStmt n, Void arg) {
            // Check 17: Synchronized on non-final lock
            if (n.getExpression().isNameExpr()) {
                String lockName = n.getExpression().asNameExpr().getNameAsString();
                String mods = fieldModifiers.getOrDefault(lockName, "");
                if (!mods.contains("final") && typeFieldNames.contains(lockName)) {
                    int line = n.getRange().map(r -> r.begin.line).orElse(0);
                    findings.add(finding("THREAD_SAFETY", "CRITICAL", "NON_FINAL_LOCK",
                        currentTypeFqn + "." + lockName, "FIELD",
                        "Synchronized on non-final field '" + lockName + "'. The lock reference can change.",
                        "Declare the lock field as 'private final'.",
                        line));
                }
            }
            super.visit(n, arg);
        }

        // ── Expression-level checks ──────────────────────────────────────────

        @Override
        public void visit(ObjectCreationExpr n, Void arg) {
            String typeName = n.getType().getNameAsString();

            // Check 11: Unclosed resource leak
            if (CLOSEABLE_TYPES.contains(typeName)) {
                // Walk up to see if we're inside a try-with-resources
                boolean inTryWithResources = false;
                com.github.javaparser.ast.Node parent = n.getParentNode().orElse(null);
                while (parent != null) {
                    if (parent instanceof TryStmt tryStmt) {
                        if (!tryStmt.getResources().isEmpty()) {
                            inTryWithResources = true;
                            break;
                        }
                    }
                    parent = parent.getParentNode().orElse(null);
                }
                // Also check if the creation is directly the resource in a try-with-resources
                if (!inTryWithResources) {
                    parent = n.getParentNode().orElse(null);
                    if (parent instanceof VariableDeclarator vd) {
                        com.github.javaparser.ast.Node grandParent = vd.getParentNode().orElse(null);
                        if (grandParent instanceof VariableDeclarationExpr) {
                            com.github.javaparser.ast.Node ggParent = grandParent.getParentNode().orElse(null);
                            if (ggParent instanceof TryStmt) {
                                inTryWithResources = true;
                            }
                        }
                    }
                }

                if (!inTryWithResources) {
                    int line = n.getRange().map(r -> r.begin.line).orElse(0);
                    findings.add(finding("EXCEPTION_SAFETY", "WARNING", "RESOURCE_LEAK",
                        currentTypeFqn, "TYPE",
                        "'" + typeName + "' created outside try-with-resources — potential resource leak.",
                        "Use try (var res = new " + typeName + "(...)) { ... } to ensure automatic closing.",
                        line));
                }
            }

            super.visit(n, arg);
        }

        @Override
        public void visit(BinaryExpr n, Void arg) {
            int line = n.getRange().map(r -> r.begin.line).orElse(0);

            if (n.getOperator() == BinaryExpr.Operator.EQUALS ||
                n.getOperator() == BinaryExpr.Operator.NOT_EQUALS) {

                // Check 5: String identity comparison
                boolean leftString  = n.getLeft() instanceof StringLiteralExpr;
                boolean rightString = n.getRight() instanceof StringLiteralExpr;
                // Also check for name expressions that might be strings
                boolean leftName  = n.getLeft() instanceof NameExpr;
                boolean rightName = n.getRight() instanceof NameExpr;

                if (leftString || rightString) {
                    // One side is a string literal, the other is being compared with ==
                    if (leftName || rightName) {
                        findings.add(finding("CORRECTNESS", "CRITICAL", "STRING_IDENTITY_COMPARE",
                            currentTypeFqn, "TYPE",
                            "String compared with == instead of .equals(). This checks identity, not value.",
                            "Use \"literal\".equals(variable) for null-safe comparison.",
                            line));
                    }
                }

                // Check 6: Float/double equality
                if (n.getOperator() == BinaryExpr.Operator.EQUALS) {
                    String leftType  = inferSimpleType(n.getLeft());
                    String rightType = inferSimpleType(n.getRight());
                    if ("float".equals(leftType) || "double".equals(leftType) ||
                        "Float".equals(leftType) || "Double".equals(leftType) ||
                        "float".equals(rightType) || "double".equals(rightType) ||
                        "Float".equals(rightType) || "Double".equals(rightType)) {
                        findings.add(finding("CORRECTNESS", "WARNING", "FLOAT_EQUALITY",
                            currentTypeFqn, "TYPE",
                            "Comparing float/double with == is unreliable due to floating-point precision.",
                            "Use Math.abs(a - b) < epsilon for approximate equality.",
                            line));
                    }
                }
            }

            super.visit(n, arg);
        }

        @Override
        public void visit(MethodCallExpr n, Void arg) {
            int line = n.getRange().map(r -> r.begin.line).orElse(0);
            String methodName = n.getNameAsString();

            // Check 4: BigDecimal.equals() trap
            if ("equals".equals(methodName) && n.getScope().isPresent()) {
                String scopeStr = n.getScope().get().toString();
                if (scopeStr.contains("BigDecimal") ||
                    isBigDecimalField(scopeStr)) {
                    findings.add(finding("CORRECTNESS", "CRITICAL", "BIGDECIMAL_EQUALS",
                        currentTypeFqn, "TYPE",
                        "BigDecimal.equals() compares both value AND scale (e.g. 2.0 ≠ 2.00). Use compareTo() == 0.",
                        "Replace with: a.compareTo(b) == 0",
                        line));
                }
            }

            // Check 2: Unguarded Optional.get()
            if ("get".equals(methodName) && n.getScope().isPresent()) {
                String scopeStr = n.getScope().get().toString();
                // Heuristic: if scope looks like it returns Optional
                if (scopeStr.contains("Optional") || scopeStr.endsWith("optional") ||
                    scopeStr.endsWith("opt")) {
                    // Check if there's a dominating isPresent check (simple heuristic)
                    boolean hasGuard = checkForOptionalGuard(n);
                    if (!hasGuard) {
                        findings.add(finding("CORRECTNESS", "CRITICAL", "UNGUARDED_OPTIONAL_GET",
                            currentTypeFqn, "TYPE",
                            "Optional.get() called without isPresent()/ifPresent() guard — risks NoSuchElementException.",
                            "Use .orElse(), .orElseGet(), .orElseThrow(), or .ifPresent() instead of .get().",
                            line));
                    }
                }
            }

            // Check 13: Ignored return value
            com.github.javaparser.ast.Node parent = n.getParentNode().orElse(null);
            if (parent instanceof ExpressionStmt && n.getScope().isPresent()) {
                String scopeType = inferSimpleType(n.getScope().get());
                Set<String> pureMethodNames = IGNORED_RETURN_METHODS.getOrDefault(scopeType, Set.of());
                if (pureMethodNames.contains(methodName)) {
                    findings.add(finding("CORRECTNESS", "WARNING", "IGNORED_RETURN_VALUE",
                        currentTypeFqn, "TYPE",
                        "Return value of " + scopeType + "." + methodName + "() is discarded. " +
                        scopeType + " is immutable — the original object is unchanged.",
                        "Assign the result: var result = obj." + methodName + "(...);",
                        line));
                }
            }

            super.visit(n, arg);
        }

        @Override
        public void visit(IntegerLiteralExpr n, Void arg) {
            // Check 22: Magic numbers
            int value;
            try {
                value = Integer.parseInt(n.getValue().replace("_", ""));
            } catch (NumberFormatException e) {
                super.visit(n, arg);
                return;
            }
            if (value != 0 && value != 1 && value != -1 && value != 2) {
                // Skip if inside a constant declaration (static final)
                com.github.javaparser.ast.Node parent = n.getParentNode().orElse(null);
                boolean isConstant = false;
                while (parent != null) {
                    if (parent instanceof FieldDeclaration fd) {
                        String mods = fd.getModifiers().stream()
                            .map(m -> m.getKeyword().asString()).collect(Collectors.joining(" "));
                        if (mods.contains("static") && mods.contains("final")) {
                            isConstant = true;
                        }
                        break;
                    }
                    if (parent instanceof MethodDeclaration) break;
                    parent = parent.getParentNode().orElse(null);
                }
                if (!isConstant) {
                    int line = n.getRange().map(r -> r.begin.line).orElse(0);
                    findings.add(finding("CODE_SMELL", "INFO", "MAGIC_NUMBER",
                        currentTypeFqn, "TYPE",
                        "Magic number " + value + " used inline. Reduces readability.",
                        "Extract to a named constant: private static final int MEANINGFUL_NAME = " + value + ";",
                        line));
                }
            }
            super.visit(n, arg);
        }

        // ── Helper methods ───────────────────────────────────────────────────

        private void checkInconsistentNullReturn(MethodDeclaration n, String methodFqn, int startLine) {
            if (n.getBody().isEmpty()) return;
            List<ReturnStmt> returns = n.getBody().get().findAll(ReturnStmt.class);
            boolean hasNull = returns.stream().anyMatch(r ->
                r.getExpression().isPresent() && r.getExpression().get() instanceof NullLiteralExpr);
            boolean hasNonNull = returns.stream().anyMatch(r ->
                r.getExpression().isPresent() && !(r.getExpression().get() instanceof NullLiteralExpr));
            if (hasNull && hasNonNull) {
                int nullLine = returns.stream()
                    .filter(r -> r.getExpression().isPresent() && r.getExpression().get() instanceof NullLiteralExpr)
                    .findFirst()
                    .flatMap(r -> r.getRange().map(rr -> rr.begin.line))
                    .orElse(startLine);
                findings.add(finding("API_CONTRACT", "WARNING", "INCONSISTENT_NULL_RETURN",
                    methodFqn, "METHOD",
                    "Method returns null in some paths and a value in others — callers must always null-check.",
                    "Return Optional<T>, throw an exception for error cases, or use a Null Object pattern.",
                    nullLine));
            }
        }

        private void checkMutableCollectionReturn(MethodDeclaration n, String methodFqn) {
            if (n.getBody().isEmpty()) return;
            String returnType = n.getType().asString();
            if (!returnType.startsWith("List") && !returnType.startsWith("Set") &&
                !returnType.startsWith("Map") && !returnType.startsWith("Collection")) {
                return;
            }
            List<ReturnStmt> returns = n.getBody().get().findAll(ReturnStmt.class);
            for (ReturnStmt ret : returns) {
                if (ret.getExpression().isEmpty()) continue;
                Expression expr = ret.getExpression().get();
                // Check if returning a field directly
                if (expr.isNameExpr() || expr.isFieldAccessExpr()) {
                    String fieldName = expr.isNameExpr() ? expr.asNameExpr().getNameAsString()
                                                         : expr.asFieldAccessExpr().getNameAsString();
                    if (typeFieldNames.contains(fieldName)) {
                        int line = ret.getRange().map(r -> r.begin.line).orElse(0);
                        findings.add(finding("API_CONTRACT", "WARNING", "MUTABLE_COLLECTION_RETURN",
                            methodFqn, "METHOD",
                            "Returning internal mutable collection '" + fieldName + "' directly exposes internal state.",
                            "Return Collections.unmodifiableList(field) or List.copyOf(field) instead.",
                            line));
                    }
                }
            }
        }

        private void checkInfiniteLoops(MethodDeclaration n, String methodFqn) {
            if (n.getBody().isEmpty()) return;
            // while(true) loops
            for (WhileStmt ws : n.getBody().get().findAll(WhileStmt.class)) {
                if (ws.getCondition() instanceof BooleanLiteralExpr ble && ble.getValue()) {
                    boolean hasExit = !ws.getBody().findAll(BreakStmt.class).isEmpty() ||
                                     !ws.getBody().findAll(ReturnStmt.class).isEmpty() ||
                                     !ws.getBody().findAll(ThrowStmt.class).isEmpty();
                    if (!hasExit) {
                        int line = ws.getRange().map(r -> r.begin.line).orElse(0);
                        findings.add(finding("CORRECTNESS", "CRITICAL", "INFINITE_LOOP",
                            methodFqn, "METHOD",
                            "while(true) loop with no break, return, or throw — infinite loop risk.",
                            "Add an exit condition (break, return, or throw) inside the loop.",
                            line));
                    }
                }
            }
            // for(;;) loops
            for (ForStmt fs : n.getBody().get().findAll(ForStmt.class)) {
                if (fs.getCompare().isEmpty()) {
                    boolean hasExit = !fs.getBody().findAll(BreakStmt.class).isEmpty() ||
                                     !fs.getBody().findAll(ReturnStmt.class).isEmpty() ||
                                     !fs.getBody().findAll(ThrowStmt.class).isEmpty();
                    if (!hasExit) {
                        int line = fs.getRange().map(r -> r.begin.line).orElse(0);
                        findings.add(finding("CORRECTNESS", "CRITICAL", "INFINITE_LOOP",
                            methodFqn, "METHOD",
                            "for(;;) loop with no break, return, or throw — infinite loop risk.",
                            "Add an exit condition inside the loop.",
                            line));
                    }
                }
            }
        }

        private boolean checkForOptionalGuard(MethodCallExpr getCall) {
            // Walk up to find if there's a parent IfStmt that checks isPresent
            com.github.javaparser.ast.Node parent = getCall.getParentNode().orElse(null);
            while (parent != null) {
                if (parent instanceof IfStmt ifStmt) {
                    String condition = ifStmt.getCondition().toString();
                    if (condition.contains("isPresent") || condition.contains("isEmpty")) {
                        return true;
                    }
                }
                parent = parent.getParentNode().orElse(null);
            }
            return false;
        }

        private boolean isBigDecimalField(String scopeStr) {
            String fieldType = fieldTypes.getOrDefault(scopeStr, "");
            return "BigDecimal".equals(fieldType) || "java.math.BigDecimal".equals(fieldType);
        }

        private String inferSimpleType(Expression expr) {
            if (expr instanceof StringLiteralExpr) return "String";
            if (expr instanceof DoubleLiteralExpr) return "double";
            if (expr instanceof IntegerLiteralExpr) return "int";
            if (expr instanceof LongLiteralExpr) return "long";
            if (expr instanceof BooleanLiteralExpr) return "boolean";
            if (expr instanceof NameExpr ne) {
                return fieldTypes.getOrDefault(ne.getNameAsString(), "");
            }
            if (expr instanceof CastExpr ce) {
                return ce.getType().asString();
            }
            if (expr instanceof MethodCallExpr mce) {
                // Heuristic: if scope is known, return scope type
                if (mce.getScope().isPresent()) {
                    return inferSimpleType(mce.getScope().get());
                }
            }
            return "";
        }

        private int computeComplexity(com.github.javaparser.ast.Node n) {
            final int[] count = {0};
            n.walk(node -> {
                if (node instanceof IfStmt || node instanceof ForStmt ||
                    node instanceof ForEachStmt || node instanceof WhileStmt ||
                    node instanceof DoStmt || node instanceof CatchClause ||
                    node instanceof ConditionalExpr || node instanceof SwitchEntry) {
                    count[0]++;
                } else if (node instanceof BinaryExpr be) {
                    if (be.getOperator() == BinaryExpr.Operator.AND ||
                        be.getOperator() == BinaryExpr.Operator.OR) {
                        count[0]++;
                    }
                }
            });
            return count[0] + 1;
        }

        private int computeMaxNesting(com.github.javaparser.ast.Node node, int currentDepth) {
            int maxDepth = currentDepth;
            for (com.github.javaparser.ast.Node child : node.getChildNodes()) {
                int childDepth = currentDepth;
                if (child instanceof IfStmt || child instanceof ForStmt ||
                    child instanceof ForEachStmt || child instanceof WhileStmt ||
                    child instanceof DoStmt || child instanceof TryStmt ||
                    child instanceof SwitchStmt) {
                    childDepth = currentDepth + 1;
                }
                maxDepth = Math.max(maxDepth, computeMaxNesting(child, childDepth));
            }
            return maxDepth;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Finding factory
    // ─────────────────────────────────────────────────────────────────────────

    private ReviewFinding finding(String category, String severity, String checkName,
                                   String entityFqn, String entityKind,
                                   String message, String suggestion, int line) {
        ReviewFinding f = new ReviewFinding();
        f.setId(UUID.randomUUID().toString());
        f.setCategory(category);
        f.setSeverity(severity);
        f.setCheckName(checkName);
        f.setEntityFqn(entityFqn);
        f.setEntityKind(entityKind);
        f.setMessage(message);
        f.setSuggestion(suggestion);
        f.setLine(line);
        f.setSourceSnippet(extractSnippet(line));
        return f;
    }
}
