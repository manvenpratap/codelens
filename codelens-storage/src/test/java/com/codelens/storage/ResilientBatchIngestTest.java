package com.codelens.storage;

import com.codelens.core.model.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Verifies that all batch storage operations gracefully skip failed/corrupted records
 * without failing fatally, persisting all valid records and tracking skipped counts.
 */
public class ResilientBatchIngestTest {

    private static void assertTrue(boolean condition, String msg) {
        if (!condition) throw new AssertionError("Assertion failed: " + msg);
    }

    private static void assertEquals(int expected, int actual, String msg) {
        if (expected != actual) throw new AssertionError(msg + " (expected: " + expected + ", got: " + actual + ")");
    }

    private static void assertEquals(long expected, long actual, String msg) {
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

    public void testResilientChunkBatchInsert() throws Exception {
        Path tempDir = Files.createTempDirectory("codelens-resilient-chunk-test-");
        DatabaseManager db = new DatabaseManager(tempDir.toString());
        try {
            db.initialize();
            EntityDao dao = new EntityDao(db);
            dao.resetSkippedRecordCount();

            // Construct mixed lists: valid items + invalid items (null IDs violate PRIMARY KEY NOT NULL)
            List<CodePackage> pkgs = new ArrayList<>();
            pkgs.add(new CodePackage("com.test.pkg1"));
            pkgs.add(new CodePackage("com.test.pkg2"));
            CodePackage badPkg = new CodePackage(); // null id and null fqn
            pkgs.add(badPkg);
            pkgs.add(new CodePackage("com.test.pkg3"));

            List<CodeType> types = new ArrayList<>();
            CodeType t1 = new CodeType();
            t1.setId("com.test.Class1");
            t1.setFqn("com.test.Class1");
            t1.setSimpleName("Class1");
            t1.setPackageFqn("com.test");
            t1.setKind("CLASS");
            types.add(t1);

            CodeType badType = new CodeType(); // null ID -> constraint failure
            types.add(badType);

            CodeType t2 = new CodeType();
            t2.setId("com.test.Class2");
            t2.setFqn("com.test.Class2");
            t2.setSimpleName("Class2");
            t2.setPackageFqn("com.test");
            t2.setKind("CLASS");
            types.add(t2);

            List<CodeMethod> methods = new ArrayList<>();
            CodeMethod m1 = new CodeMethod();
            m1.setId("com.test.Class1.m1()");
            m1.setFqn("com.test.Class1.m1()");
            m1.setSimpleName("m1");
            m1.setDeclaringTypeFqn("com.test.Class1");
            methods.add(m1);

            CodeMethod badMethod = new CodeMethod(); // null ID -> constraint failure
            methods.add(badMethod);

            CodeMethod m2 = new CodeMethod();
            m2.setId("com.test.Class2.m2()");
            m2.setFqn("com.test.Class2.m2()");
            m2.setSimpleName("m2");
            m2.setDeclaringTypeFqn("com.test.Class2");
            methods.add(m2);

            List<CodeField> fields = new ArrayList<>();
            CodeField f1 = new CodeField();
            f1.setId("com.test.Class1.f1");
            f1.setFqn("com.test.Class1.f1");
            f1.setSimpleName("f1");
            f1.setDeclaringTypeFqn("com.test.Class1");
            fields.add(f1);

            CodeField badField = new CodeField(); // null ID -> constraint failure
            fields.add(badField);

            CodeField f2 = new CodeField();
            f2.setId("com.test.Class2.f2");
            f2.setFqn("com.test.Class2.f2");
            f2.setSimpleName("f2");
            f2.setDeclaringTypeFqn("com.test.Class2");
            fields.add(f2);

            List<CodeRelationship> rels = new ArrayList<>();
            CodeRelationship r1 = new CodeRelationship();
            r1.setId("r1");
            r1.setFromEntityFqn("com.test.Class1.m1()");
            r1.setToEntityFqn("com.test.Class2.m2()");
            r1.setKind("CALLS");
            rels.add(r1);

            CodeRelationship badRel = new CodeRelationship(); // null ID -> constraint failure
            rels.add(badRel);

            CodeRelationship r2 = new CodeRelationship();
            r2.setId("r2");
            r2.setFromEntityFqn("com.test.Class1.m1()");
            r2.setToEntityFqn("com.test.Class1.f1");
            r2.setKind("READS_FIELD");
            rels.add(r2);

            List<FileMeta> metas = new ArrayList<>();
            FileMeta fm1 = new FileMeta("src/Class1.java", 1000L, 500L, 1);
            FileMeta badFm = new FileMeta(null, 0L, 0L, 0); // null primary key
            FileMeta fm2 = new FileMeta("src/Class2.java", 2000L, 600L, 1);
            metas.add(fm1);
            metas.add(badFm);
            metas.add(fm2);

            // Execute batchInsertChunkFast - MUST NOT throw fatal exception!
            dao.batchInsertChunkFast(pkgs, types, fields, methods, rels, metas);

            // Verify skipped counts incremented
            long skipped = dao.getSkippedRecordCount();
            assertTrue(skipped >= 6, "Must have recorded at least 6 skipped records, got: " + skipped);

            // Verify all valid records were successfully persisted!
            List<CodePackage> savedPkgs = dao.findAllPackages();
            assertEquals(3, savedPkgs.size(), "All 3 valid packages must be saved");

            List<CodeType> savedTypes = dao.findAllTypes();
            assertEquals(2, savedTypes.size(), "All 2 valid types must be saved");

            List<CodeMethod> savedMethods = dao.findAllMethods();
            assertEquals(2, savedMethods.size(), "All 2 valid methods must be saved");

            List<CodeField> savedFields = dao.findAllFields();
            assertEquals(2, savedFields.size(), "All 2 valid fields must be saved");

            List<CodeRelationship> savedRels = dao.findAllRelationships();
            assertEquals(2, savedRels.size(), "All 2 valid relationships must be saved");

            Map<String, FileMeta> savedMetas = dao.getAllFileMeta();
            assertEquals(2, savedMetas.size(), "All 2 valid file metas must be saved");
            assertTrue(savedMetas.containsKey("src/Class1.java"), "Contains Class1.java");
            assertTrue(savedMetas.containsKey("src/Class2.java"), "Contains Class2.java");
        } finally {
            db.close();
            deleteRecursively(tempDir.toFile());
        }
    }

    public void testResilientIndividualBatches() throws Exception {
        Path tempDir = Files.createTempDirectory("codelens-resilient-batches-test-");
        DatabaseManager db = new DatabaseManager(tempDir.toString());
        try {
            db.initialize();
            EntityDao dao = new EntityDao(db);
            dao.resetSkippedRecordCount();

            // 1. Packages batch with bad record
            List<CodePackage> pkgs = new ArrayList<>();
            pkgs.add(new CodePackage("com.alpha"));
            pkgs.add(new CodePackage()); // bad
            pkgs.add(new CodePackage("com.beta"));
            dao.batchInsertPackagesFast(pkgs);
            assertEquals(2, dao.findAllPackages().size(), "2 valid packages saved");
            assertTrue(dao.getSkippedRecordCount() >= 1, "Recorded skipped package");

            // 2. Types batch with bad record
            List<CodeType> types = new ArrayList<>();
            CodeType t1 = new CodeType();
            t1.setId("com.alpha.Foo");
            t1.setFqn("com.alpha.Foo");
            t1.setSimpleName("Foo");
            t1.setKind("CLASS");
            types.add(t1);
            types.add(new CodeType()); // bad
            dao.batchInsertTypesFast(types);
            assertEquals(1, dao.findAllTypes().size(), "1 valid type saved");

            // 3. Methods batch with bad record
            List<CodeMethod> methods = new ArrayList<>();
            CodeMethod m1 = new CodeMethod();
            m1.setId("com.alpha.Foo.bar()");
            m1.setFqn("com.alpha.Foo.bar()");
            m1.setSimpleName("bar");
            m1.setDeclaringTypeFqn("com.alpha.Foo");
            methods.add(m1);
            methods.add(new CodeMethod()); // bad
            dao.batchInsertMethodsFast(methods);
            assertEquals(1, dao.findAllMethods().size(), "1 valid method saved");

            // 4. Fields batch with bad record
            List<CodeField> fields = new ArrayList<>();
            CodeField f1 = new CodeField();
            f1.setId("com.alpha.Foo.baz");
            f1.setFqn("com.alpha.Foo.baz");
            f1.setSimpleName("baz");
            f1.setDeclaringTypeFqn("com.alpha.Foo");
            fields.add(f1);
            fields.add(new CodeField()); // bad
            dao.batchInsertFieldsFast(fields);
            assertEquals(1, dao.findAllFields().size(), "1 valid field saved");

            // 5. Relationships batch with bad record
            List<CodeRelationship> rels = new ArrayList<>();
            CodeRelationship r1 = new CodeRelationship();
            r1.setId("r_alpha");
            r1.setFromEntityFqn("com.alpha.Foo.bar()");
            r1.setToEntityFqn("com.alpha.Foo.baz");
            r1.setKind("READS_FIELD");
            rels.add(r1);
            rels.add(new CodeRelationship()); // bad
            dao.batchInsertRelationshipsFast(rels);
            assertEquals(1, dao.findAllRelationships().size(), "1 valid relationship saved");

            // 6. Inconsistencies batch with bad record
            List<InconsistencyReport> inconsts = new ArrayList<>();
            InconsistencyReport inc1 = new InconsistencyReport();
            inc1.setId("INC1");
            inc1.setKind("DIVERGENT_SIGNATURE");
            inc1.setEntity1Fqn("com.alpha.Foo.m1");
            inc1.setEntity1Kind("METHOD");
            inc1.setEntity2Fqn("com.alpha.Foo.m2");
            inc1.setEntity2Kind("METHOD");
            inc1.setReason("Signature mismatch");
            inconsts.add(inc1);
            inconsts.add(new InconsistencyReport()); // bad: null id
            dao.batchInsertInconsistencies(inconsts);
            assertEquals(1, dao.findAllInconsistencies().size(), "1 valid inconsistency saved");

            // 7. GitMeta batch with bad record
            List<GitMeta> gitMetas = new ArrayList<>();
            GitMeta gm1 = new GitMeta();
            gm1.setEntityFqn("com.alpha.Foo");
            gm1.setLastAuthorName("Alice");
            gm1.setLastAuthorEmail("alice@example.com");
            gm1.setLastCommitTime(1600000000L);
            gm1.setLastCommitHash("a1b2c3d");
            gm1.setLastCommitMsg("Initial commit");
            gm1.setCommitCount(5);
            gitMetas.add(gm1);
            gitMetas.add(new GitMeta()); // bad: null entityFqn
            dao.batchInsertGitMeta(gitMetas);
            assertEquals(1, dao.findAllGitMeta().size(), "1 valid git meta saved");

        } finally {
            db.close();
            deleteRecursively(tempDir.toFile());
        }
    }

    public void testResilientLuceneBatch() throws Exception {
        Path tempDir = Files.createTempDirectory("codelens-resilient-lucene-test-");
        LuceneService lucene = new LuceneService(tempDir.toString());
        try {
            lucene.initialize();
            lucene.resetSkippedDocCount();

            List<CodeType> types = new ArrayList<>();
            CodeType t1 = new CodeType();
            t1.setId("com.example.Alpha");
            t1.setFqn("com.example.Alpha");
            t1.setSimpleName("Alpha");
            t1.setKind("CLASS");
            types.add(t1);

            // Corrupt type with null ID
            types.add(new CodeType());

            List<CodeMethod> methods = new ArrayList<>();
            CodeMethod m1 = new CodeMethod();
            m1.setId("com.example.Alpha.run()");
            m1.setFqn("com.example.Alpha.run()");
            m1.setSimpleName("run");
            m1.setDeclaringTypeFqn("com.example.Alpha");
            methods.add(m1);

            // Corrupt method with null ID
            methods.add(new CodeMethod());

            List<CodeField> fields = new ArrayList<>();
            CodeField f1 = new CodeField();
            f1.setId("com.example.Alpha.counter");
            f1.setFqn("com.example.Alpha.counter");
            f1.setSimpleName("counter");
            f1.setDeclaringTypeFqn("com.example.Alpha");
            fields.add(f1);

            // Corrupt field with null ID
            fields.add(new CodeField());

            // Add batch directly
            lucene.addBatch(types, methods, fields);
            lucene.finishIndexRebuild();

            // Verify search finds valid documents
            List<LuceneService.SearchHit> results = lucene.search("Alpha", 10);
            assertTrue(!results.isEmpty(), "Should find indexed Alpha entities in Lucene");
            assertTrue(results.stream().anyMatch(r -> "com.example.Alpha".equals(r.fqn)), "Contains Alpha class");
            assertTrue(results.stream().anyMatch(r -> "com.example.Alpha.run()".equals(r.fqn)), "Contains Alpha.run()");
            assertTrue(results.stream().anyMatch(r -> "com.example.Alpha.counter".equals(r.fqn)), "Contains Alpha.counter");

        } finally {
            lucene.close();
            deleteRecursively(tempDir.toFile());
        }
    }
}
