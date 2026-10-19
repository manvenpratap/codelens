package com.codelens.parser;

import com.codelens.core.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Polyglot Source Scanner for Enterprise Estates.
 * Extracts web frontend components/functions (JavaScript/TypeScript) and database DDL (SQL)
 * to link full-stack execution topologies from UI endpoints to SQL database tables.
 */
public class PolyglotSourceScanner {

    private static final Logger log = LoggerFactory.getLogger(PolyglotSourceScanner.class);

    // Frontend function/class patterns
    private static final Pattern JS_CLASS_PAT = Pattern.compile("(?:export\\s+)?class\\s+([A-Za-z0-9_$]+)");
    private static final Pattern JS_FUNCTION_PAT = Pattern.compile("(?:export\\s+)?(?:async\\s+)?function\\s+([A-Za-z0-9_$]+)\\s*\\(");
    private static final Pattern JS_CONST_FUNC_PAT = Pattern.compile("(?:export\\s+)?const\\s+([A-Za-z0-9_$]+)\\s*=\\s*(?:async\\s*)?\\([^)]*\\)\\s*=>");

    // HTTP / API call patterns: fetch('/api/...'), axios.get('/api/...'), api.post('/api/...')
    private static final Pattern FETCH_CALL_PAT = Pattern.compile("fetch\\(\\s*[`'\"]([^`'\"]+)[`'\"](?:\\s*,\\s*\\{[^}]*method:\\s*[`'\"]([A-Za-z]+)[`'\"])?");
    private static final Pattern AXIOS_CALL_PAT = Pattern.compile("(?:axios|api|http)\\.(get|post|put|delete|patch)\\(\\s*[`'\"]([^`'\"]+)[`'\"]");

    // SQL DDL patterns
    private static final Pattern SQL_CREATE_TABLE_PAT = Pattern.compile("CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(?:[A-Za-z0-9_]+\\.)?[\"`\\[]?([A-Za-z0-9_]+)[\"`\\]]?", Pattern.CASE_INSENSITIVE);
    private static final Pattern SQL_CREATE_VIEW_PAT = Pattern.compile("CREATE\\s+VIEW\\s+(?:[A-Za-z0-9_]+\\.)?[\"`\\[]?([A-Za-z0-9_]+)[\"`\\]]?", Pattern.CASE_INSENSITIVE);

    public static class PolyglotScanResult {
        public final List<CodePackage> packages = new ArrayList<>();
        public final List<CodeType> types = new ArrayList<>();
        public final List<CodeMethod> methods = new ArrayList<>();
        public final List<CodeRelationship> relationships = new ArrayList<>();
        public final List<FileMeta> fileMetas = new ArrayList<>();
        public int parsedFiles = 0;
    }

    public PolyglotScanResult scanDirectory(Path root, List<String> userExcludes) throws IOException {
        PolyglotScanResult result = new PolyglotScanResult();
        if (!Files.exists(root)) return result;

        List<String> effectiveExcludes = (userExcludes != null && !userExcludes.isEmpty())
            ? userExcludes
            : JavaSourceScanner.DEFAULT_EXCLUDE_PATTERNS;
        List<PathMatcher> matchers = JavaSourceScanner.compileMatchers(effectiveExcludes);

        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) return FileVisitResult.CONTINUE;
                Path rel = root.relativize(dir);
                if (JavaSourceScanner.isExcluded(rel, matchers, effectiveExcludes)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                Path rel = root.relativize(file);
                if (JavaSourceScanner.isExcluded(rel, matchers, effectiveExcludes)) {
                    return FileVisitResult.CONTINUE;
                }

                String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
                try {
                    if (fileName.endsWith(".js") || fileName.endsWith(".jsx") ||
                        fileName.endsWith(".ts") || fileName.endsWith(".tsx")) {
                        scanFrontendFile(file, root, result);
                    } else if (fileName.endsWith(".sql")) {
                        scanSqlFile(file, root, result);
                    }
                } catch (Exception e) {
                    log.debug("Error scanning polyglot file {}: {}", file, e.getMessage());
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return result;
    }

    private void scanFrontendFile(Path file, Path root, PolyglotScanResult result) throws IOException {
        String absPath = file.toAbsolutePath().toString();
        String relPath = root.relativize(file).toString().replace('\\', '/');
        String baseName = file.getFileName().toString();
        int dot = baseName.lastIndexOf('.');
        String moduleName = dot > 0 ? baseName.substring(0, dot) : baseName;
        String typeFqn = "web." + relPath.replace('/', '.').replace(".js", "").replace(".ts", "").replace(".jsx", "").replace(".tsx", "");

        CodeType type = new CodeType();
        type.setId(typeFqn);
        type.setFqn(typeFqn);
        type.setSimpleName(moduleName);
        type.setPackageFqn("web." + (file.getParent() != null ? root.relativize(file.getParent()).toString().replace('/', '.') : ""));
        type.setKind("UI_MODULE");
        type.setSourceFile(absPath);
        type.setStartLine(1);

        List<String> lines = Files.readAllLines(file);
        type.setEndLine(Math.max(1, lines.size()));
        result.types.add(type);
        result.parsedFiles++;

        String currentFunction = moduleName + ".default()";
        int lineNum = 0;

        for (String line : lines) {
            lineNum++;
            String trimmed = line.trim();

            Matcher mClass = JS_CLASS_PAT.matcher(trimmed);
            if (mClass.find()) {
                currentFunction = typeFqn + "." + mClass.group(1);
            }

            Matcher mFunc = JS_FUNCTION_PAT.matcher(trimmed);
            if (mFunc.find()) {
                String fnName = mFunc.group(1);
                currentFunction = typeFqn + "." + fnName + "()";
                CodeMethod method = new CodeMethod();
                method.setId(currentFunction);
                method.setFqn(currentFunction);
                method.setSimpleName(fnName);
                method.setDeclaringTypeFqn(typeFqn);
                method.setStartLine(lineNum);
                method.setEndLine(lineNum);
                result.methods.add(method);
            }

            Matcher mConstFunc = JS_CONST_FUNC_PAT.matcher(trimmed);
            if (mConstFunc.find()) {
                String fnName = mConstFunc.group(1);
                currentFunction = typeFqn + "." + fnName + "()";
                CodeMethod method = new CodeMethod();
                method.setId(currentFunction);
                method.setFqn(currentFunction);
                method.setSimpleName(fnName);
                method.setDeclaringTypeFqn(typeFqn);
                method.setStartLine(lineNum);
                method.setEndLine(lineNum);
                result.methods.add(method);
            }

            // HTTP Endpoint Calls (fetch)
            Matcher mFetch = FETCH_CALL_PAT.matcher(trimmed);
            if (mFetch.find()) {
                String path = mFetch.group(1);
                String verb = mFetch.group(2) != null ? mFetch.group(2).toUpperCase(Locale.ROOT) : "GET";
                if (path.startsWith("/")) {
                    String epFqn = "endpoint:" + verb + " " + path;
                    registerEndpointType(result, epFqn, verb + " " + path, absPath, lineNum);
                    result.relationships.add(new CodeRelationship(
                        UUID.randomUUID().toString(), currentFunction, epFqn, CodeRelationship.KIND_CALLS, lineNum
                    ));
                }
            }

            // HTTP Endpoint Calls (axios.get/post etc.)
            Matcher mAxios = AXIOS_CALL_PAT.matcher(trimmed);
            if (mAxios.find()) {
                String verb = mAxios.group(1).toUpperCase(Locale.ROOT);
                String path = mAxios.group(2);
                if (path.startsWith("/")) {
                    String epFqn = "endpoint:" + verb + " " + path;
                    registerEndpointType(result, epFqn, verb + " " + path, absPath, lineNum);
                    result.relationships.add(new CodeRelationship(
                        UUID.randomUUID().toString(), currentFunction, epFqn, CodeRelationship.KIND_CALLS, lineNum
                    ));
                }
            }
        }
    }

    private void scanSqlFile(Path file, Path root, PolyglotScanResult result) throws IOException {
        String absPath = file.toAbsolutePath().toString();
        List<String> lines = Files.readAllLines(file);
        int lineNum = 0;

        for (String line : lines) {
            lineNum++;
            String trimmed = line.trim();
            if (trimmed.startsWith("--") || trimmed.isBlank()) continue;

            Matcher mTable = SQL_CREATE_TABLE_PAT.matcher(trimmed);
            if (mTable.find()) {
                String tableName = mTable.group(1).toUpperCase(Locale.ROOT);
                String tableFqn = "table:" + tableName;
                CodeType t = new CodeType();
                t.setId(tableFqn);
                t.setFqn(tableFqn);
                t.setSimpleName(tableName);
                t.setPackageFqn("database.schema");
                t.setKind(CodeType.KIND_TABLE);
                t.setSourceFile(absPath);
                t.setStartLine(lineNum);
                t.setEndLine(lineNum);
                result.types.add(t);
                result.parsedFiles++;
            }

            Matcher mView = SQL_CREATE_VIEW_PAT.matcher(trimmed);
            if (mView.find()) {
                String viewName = mView.group(1).toUpperCase(Locale.ROOT);
                String viewFqn = "table:" + viewName;
                CodeType t = new CodeType();
                t.setId(viewFqn);
                t.setFqn(viewFqn);
                t.setSimpleName(viewName);
                t.setPackageFqn("database.view");
                t.setKind(CodeType.KIND_TABLE);
                t.setSourceFile(absPath);
                t.setStartLine(lineNum);
                t.setEndLine(lineNum);
                result.types.add(t);
            }
        }
    }

    private void registerEndpointType(PolyglotScanResult result, String epFqn, String simpleName, String srcFile, int line) {
        for (CodeType existing : result.types) {
            if (epFqn.equals(existing.getFqn())) return;
        }
        CodeType ep = new CodeType();
        ep.setId(epFqn);
        ep.setFqn(epFqn);
        ep.setSimpleName(simpleName);
        ep.setPackageFqn("endpoint");
        ep.setKind(CodeType.KIND_ENDPOINT);
        ep.setSourceFile(srcFile);
        ep.setStartLine(line);
        ep.setEndLine(line);
        result.types.add(ep);
    }
}
