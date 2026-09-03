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

    private static void assertEquals(String expected, String actual, String msg) {
        if (!Objects.equals(expected, actual)) throw new AssertionError(msg + " (expected: " + expected + ", got: " + actual + ")");
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

    public void testParallelIdempotencyAndDuplicates() throws Exception {
        Path tempDir = Files.createTempDirectory("codelens-idempotency-test-");
        DatabaseManager db = new DatabaseManager(tempDir.toString());
        try {
            db.initialize();
            EntityDao dao = new EntityDao(db);

            // 1. Test duplicate packages in the SAME batch
            List<CodePackage> pkgs = new ArrayList<>();
            pkgs.add(new CodePackage("com.example.dup"));
            pkgs.add(new CodePackage("com.example.dup"));
            dao.batchInsertPackagesFast(pkgs);

            // 2. Test duplicate types in the SAME batch
            List<CodeType> types = new ArrayList<>();
            CodeType t1 = new CodeType();
            t1.setId("com.example.dup.MyClass");
            t1.setFqn("com.example.dup.MyClass");
            t1.setSimpleName("MyClass");
            t1.setPackageFqn("com.example.dup");
            t1.setKind("CLASS");
            t1.setSourceFile("/src/MyClass.java");
            types.add(t1);

            CodeType t2 = new CodeType();
            t2.setId("com.example.dup.MyClass");
            t2.setFqn("com.example.dup.MyClass");
            t2.setSimpleName("MyClass");
            t2.setPackageFqn("com.example.dup");
            t2.setKind("CLASS");
            t2.setSourceFile("/src/MyClass.java");
            types.add(t2);
            dao.batchInsertTypesFast(types);

            // 3. Test duplicate methods in the SAME batch
            List<CodeMethod> methods = new ArrayList<>();
            CodeMethod m1 = new CodeMethod();
            m1.setId("com.example.dup.MyClass.doWork()");
            m1.setFqn("com.example.dup.MyClass.doWork()");
            m1.setSimpleName("doWork");
            m1.setDeclaringTypeFqn("com.example.dup.MyClass");
            methods.add(m1);

            CodeMethod m2 = new CodeMethod();
            m2.setId("com.example.dup.MyClass.doWork()");
            m2.setFqn("com.example.dup.MyClass.doWork()");
            m2.setSimpleName("doWork");
            m2.setDeclaringTypeFqn("com.example.dup.MyClass");
            methods.add(m2);
            dao.batchInsertMethodsFast(methods);

            // 4. Test duplicate relationships in the SAME batch
            List<CodeRelationship> rels = new ArrayList<>();
            CodeRelationship r1 = new CodeRelationship();
            r1.setId("rel-dup");
            r1.setFromEntityFqn("com.example.dup.MyClass.doWork()");
            r1.setToEntityFqn("com.example.other.Worker.run()");
            r1.setKind("CALLS");
            rels.add(r1);

            CodeRelationship r2 = new CodeRelationship();
            r2.setId("rel-dup");
            r2.setFromEntityFqn("com.example.dup.MyClass.doWork()");
            r2.setToEntityFqn("com.example.other.Worker.run()");
            r2.setKind("CALLS");
            rels.add(r2);
            dao.batchInsertRelationshipsFast(rels);

            // 5. Test inserting the exact same lists again (re-scan scenario)
            dao.batchInsertPackages(pkgs);
            dao.batchInsertTypes(types);
            dao.batchInsertMethods(methods);
            dao.batchInsertRelationships(rels);

            Map<String, Object> stats = dao.getStats();
            assertEquals(1, ((Number) stats.get("packages")).intValue(), "packages should deduplicate to 1");
            assertEquals(1, ((Number) stats.get("types")).intValue(), "types should deduplicate to 1");
            assertEquals(1, ((Number) stats.get("methods")).intValue(), "methods should deduplicate to 1");
            assertEquals(1, ((Number) stats.get("relationships")).intValue(), "relationships should deduplicate to 1");
        } finally {
            db.close();
            deleteRecursively(tempDir.toFile());
        }
    }

    public void testChunkedConnectionsAndCallRelationshipPairs() throws Exception {
        Path tempDir = Files.createTempDirectory("codelens-chunked-test-");
        DatabaseManager db = new DatabaseManager(tempDir.toString());
        try {
            db.initialize();
            EntityDao dao = new EntityDao(db);

            // Generate 3,000 call relationships (exceeds BATCH_CHUNK_SIZE = 2500)
            int totalRels = 3000;
            List<CodeRelationship> rels = new ArrayList<>(totalRels);
            for (int i = 0; i < totalRels; i++) {
                CodeRelationship r = new CodeRelationship();
                r.setId("chunk-rel-" + i);
                r.setFromEntityFqn("com.example.Caller.m" + (i % 50) + "()");
                r.setToEntityFqn("com.example.Callee.target" + i + "()");
                r.setKind("CALLS");
                r.setSourceLine(100 + i);
                rels.add(r);
            }

            // Test batch insertion spanning multiple connection chunks
            dao.batchInsertRelationshipsFast(rels);

            // Verify findCallRelationshipPairs retrieves all 3,000 pairs with immediate connection close
            List<String[]> pairs = dao.findCallRelationshipPairs();
            assertEquals(totalRels, pairs.size(), "call relationship pairs count");
            assertEquals("com.example.Caller.m0()", pairs.get(0)[0], "first pair from");
            assertEquals("com.example.Callee.target0()", pairs.get(0)[1], "first pair to");

            // Verify streamCallRelationships works seamlessly
            List<String> streamedTargets = new ArrayList<>();
            dao.streamCallRelationships((from, to) -> streamedTargets.add(to));
            assertEquals(totalRels, streamedTargets.size(), "streamed targets count");

            // Verify file metadata batch chunking
            List<FileMeta> metas = new ArrayList<>();
            for (int i = 0; i < 3000; i++) {
                metas.add(new FileMeta("/src/File" + i + ".java", 1000L + i, 2048L, 2));
            }
            dao.saveFileMetaBatch(metas);
            Map<String, FileMeta> allMeta = dao.getAllFileMeta();
            assertEquals(3000, allMeta.size(), "file meta count");

            // Verify chunked deleteBySourceFiles
            List<String> filesToDelete = new ArrayList<>();
            for (int i = 0; i < 600; i++) {
                filesToDelete.add("/src/File" + i + ".java");
            }
            dao.deleteBySourceFiles(filesToDelete);
            Map<String, FileMeta> remainingMeta = dao.getAllFileMeta();
            assertEquals(2400, remainingMeta.size(), "remaining file meta after chunked delete");
        } finally {
            db.close();
            deleteRecursively(tempDir.toFile());
        }
    }
}
