package com.codelens.storage;

import com.codelens.core.model.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class StoragePerformanceTest {

    private static void assertTrue(boolean condition, String msg) {
        if (!condition) throw new AssertionError("Assertion failed: " + msg);
    }

    private static void assertEquals(int expected, int actual, String msg) {
        if (expected != actual) throw new AssertionError(msg + " (expected: " + expected + ", got: " + actual + ")");
    }

    private void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursively(c);
            }
        }
        file.delete();
    }

    public void testFastBulkLoadPipelineAndCompaction() throws Exception {
        Path tempDir = Files.createTempDirectory("codelens-perf-test-");
        DatabaseManager db = new DatabaseManager(tempDir.toString());
        try {
            db.initialize();
            EntityDao dao = new EntityDao(db);

            // 1. Prepare for bulk load
            db.prepareForBulkLoad();

            // 2. Fast batch inserts
            List<CodePackage> pkgs = new ArrayList<>();
            pkgs.add(new CodePackage("com.example.service"));
            dao.batchInsertPackagesFast(pkgs);

            List<CodeType> types = new ArrayList<>();
            CodeType type = new CodeType();
            type.setId("com.example.service.UserService");
            type.setFqn("com.example.service.UserService");
            type.setSimpleName("UserService");
            type.setPackageFqn("com.example.service");
            type.setKind("CLASS");
            type.setModifiers("public");
            type.setSourceFile("/src/UserService.java");
            types.add(type);
            dao.batchInsertTypesFast(types);

            List<CodeField> fields = new ArrayList<>();
            CodeField field = new CodeField();
            field.setId("com.example.service.UserService.repo");
            field.setFqn("com.example.service.UserService.repo");
            field.setSimpleName("repo");
            field.setDeclaringTypeFqn("com.example.service.UserService");
            field.setFieldType("UserRepository");
            fields.add(field);
            dao.batchInsertFieldsFast(fields);

            List<CodeMethod> methods = new ArrayList<>();
            CodeMethod method = new CodeMethod();
            method.setId("com.example.service.UserService.findUser(String)");
            method.setFqn("com.example.service.UserService.findUser(String)");
            method.setSimpleName("findUser");
            method.setDeclaringTypeFqn("com.example.service.UserService");
            method.setReturnType("User");
            methods.add(method);
            dao.batchInsertMethodsFast(methods);

            List<CodeRelationship> rels = new ArrayList<>();
            CodeRelationship rel = new CodeRelationship();
            rel.setId("rel-1");
            rel.setFromEntityFqn("com.example.service.UserService.findUser(String)");
            rel.setToEntityFqn("com.example.repo.UserRepository.findById(String)");
            rel.setKind("CALLS");
            rel.setSourceLine(42);
            rels.add(rel);
            dao.batchInsertRelationshipsFast(rels);

            // 3. Finish bulk load
            db.finishBulkLoad();

            // 4. Verify entities present
            Map<String, Object> stats = dao.getStats();
            assertEquals(1, ((Number) stats.get("packages")).intValue(), "packages count");
            assertEquals(1, ((Number) stats.get("types")).intValue(), "types count");
            assertEquals(1, ((Number) stats.get("fields")).intValue(), "fields count");
            assertEquals(1, ((Number) stats.get("methods")).intValue(), "methods count");
            assertEquals(1, ((Number) stats.get("relationships")).intValue(), "relationships count");

            // 5. Test compaction
            db.compactDatabase();

            // Re-check stats after compaction to ensure pool was re-established seamlessly
            Map<String, Object> statsAfterCompact = dao.getStats();
            assertEquals(1, ((Number) statsAfterCompact.get("types")).intValue(), "types count after compaction");

            // 6. Test fast TRUNCATE
            db.clearAll();
            Map<String, Object> statsAfterTruncate = dao.getStats();
            assertEquals(0, ((Number) statsAfterTruncate.get("types")).intValue(), "types count after truncate");
            assertEquals(0, ((Number) statsAfterTruncate.get("methods")).intValue(), "methods count after truncate");
            assertEquals(0, ((Number) statsAfterTruncate.get("relationships")).intValue(), "relationships count after truncate");
        } finally {
            db.close();
            deleteRecursively(tempDir.toFile());
        }
    }
}
