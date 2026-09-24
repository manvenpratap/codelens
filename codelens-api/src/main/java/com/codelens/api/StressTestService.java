package com.codelens.api;

import com.codelens.core.model.*;
import com.codelens.storage.DatabaseManager;
import com.codelens.storage.EntityDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service to execute on-demand or background scale and stress tests,
 * reporting real-time telemetry to the CodeLens UI (Process Hub & Database Manager).
 */
public class StressTestService {

    private static final Logger log = LoggerFactory.getLogger(StressTestService.class);

    private final AtomicReference<StressTestProgress> progress = new AtomicReference<>(new StressTestProgress(StressTestProgress.Status.IDLE));
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "codelens-stress-worker");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private Future<?> activeTask = null;

    public StressTestProgress getProgress() {
        return progress.get();
    }

    public synchronized boolean isRunning() {
        StressTestProgress p = progress.get();
        return p != null && p.getStatus() == StressTestProgress.Status.RUNNING;
    }

    public synchronized void stopStressTest() {
        cancelRequested.set(true);
        if (activeTask != null && !activeTask.isDone()) {
            activeTask.cancel(true);
        }
        StressTestProgress p = progress.get();
        if (p != null && p.getStatus() == StressTestProgress.Status.RUNNING) {
            p.setStatus(StressTestProgress.Status.CANCELLED);
            p.setActiveStage("CANCELLED");
            p.setCurrentPhase("Cancelled by user");
            p.setMessage("Stress test cancelled.");
            p.setEndTime(System.currentTimeMillis());
        }
        log.info("Stress test execution cancelled by user request");
    }

    public synchronized boolean startStressTest(int totalClasses, int totalFields, long totalRels, String dbPathStr) {
        if (isRunning()) {
            return false;
        }

        cancelRequested.set(false);
        final String targetPath = (dbPathStr != null && !dbPathStr.isBlank()) 
            ? dbPathStr 
            : "/Volumes/Study/Projects/codelens/codelens-stress-data";

        StressTestProgress p = new StressTestProgress(StressTestProgress.Status.RUNNING);
        p.setTargetClasses(totalClasses);
        p.setTargetFields(totalFields);
        p.setTargetRelationships(totalRels);
        p.setTargetMethods(totalClasses * 5);
        p.setTargetDir(targetPath);
        p.setStartTime(System.currentTimeMillis());
        p.setActiveStage("PREPARE");
        p.setCurrentPhase("Preparing Storage");
        p.setCurrentDetail("Configuring high-throughput bulk mode & clearing tables");
        p.setPercentage(0);
        progress.set(p);

        activeTask = executor.submit(() -> {
            try {
                executeStressPipeline(totalClasses, totalFields, totalRels, targetPath, p);
            } catch (Exception e) {
                if (cancelRequested.get() || Thread.currentThread().isInterrupted()) {
                    p.setStatus(StressTestProgress.Status.CANCELLED);
                    p.setActiveStage("CANCELLED");
                    p.setCurrentPhase("Cancelled");
                    p.setMessage("Stress test cancelled.");
                } else {
                    log.error("Stress test failed", e);
                    p.setStatus(StressTestProgress.Status.ERROR);
                    p.setActiveStage("ERROR");
                    p.setCurrentPhase("Failed");
                    p.setErrorDetail(e.getMessage() != null ? e.getMessage() : e.toString());
                    p.setMessage("Stress test encountered an error: " + e.getMessage());
                }
                p.setEndTime(System.currentTimeMillis());
            }
        });

        return true;
    }

    private void executeStressPipeline(int totalClasses, int totalFields, long totalRels, String dbPathStr, StressTestProgress p) throws Exception {
        Path dbDir = Paths.get(dbPathStr);
        deleteRecursively(dbDir.toFile());
        Files.createDirectories(dbDir);

        DatabaseManager db = new DatabaseManager(dbDir.toString());
        try {
            db.initialize();
            EntityDao dao = new EntityDao(db);
            db.prepareForBulkLoad();

            checkCancel();

            // ─────────────────────────────────────────────────────────────────
            // Phase 2: Ingest Packages, Classes, Fields, Methods
            // ─────────────────────────────────────────────────────────────────
            p.setActiveStage("INGEST_ENTITIES");
            p.setCurrentPhase("Ingesting Classes & Fields");
            p.setPercentage(5);

            int numPackages = Math.max(10, totalClasses / 100);
            int classesPerPkg = totalClasses / numPackages;
            int fieldsPerClass = Math.max(1, totalFields / totalClasses);
            int methodsPerClass = 5;

            List<CodePackage> pkgs = new ArrayList<>(numPackages);
            for (int i = 0; i < numPackages; i++) {
                pkgs.add(new CodePackage(String.format("com.enterprise.module%03d", i)));
            }
            dao.batchInsertPackagesFast(pkgs);

            // Classes
            int typeBatchSize = 2500;
            List<CodeType> typeBatch = new ArrayList<>(typeBatchSize);
            int classIdx = 0;
            for (int pkgIdx = 0; pkgIdx < numPackages; pkgIdx++) {
                String pkgName = String.format("com.enterprise.module%03d", pkgIdx);
                int countForThisPkg = (pkgIdx == numPackages - 1) ? (totalClasses - classIdx) : classesPerPkg;
                for (int c = 0; c < countForThisPkg; c++) {
                    String simpleName = String.format("ServiceComponent_%04d", c);
                    String fqn = pkgName + "." + simpleName;
                    CodeType t = new CodeType();
                    t.setId(fqn); t.setFqn(fqn); t.setSimpleName(simpleName);
                    t.setPackageFqn(pkgName);
                    t.setKind((c % 10 == 0) ? "INTERFACE" : (c % 15 == 0) ? "RECORD" : "CLASS");
                    t.setModifiers("public");
                    t.setSourceFile("/src/" + pkgName.replace('.', '/') + "/" + simpleName + ".java");
                    t.setLineCount(200);
                    t.setFieldCount(fieldsPerClass);
                    t.setMethodCount(methodsPerClass);
                    typeBatch.add(t);
                    classIdx++;

                    if (typeBatch.size() >= typeBatchSize) {
                        checkCancel();
                        dao.batchInsertTypesFast(typeBatch);
                        typeBatch.clear();
                        p.setIngestedClasses(classIdx);
                        p.setCurrentDetail(String.format("%,d / %,d classes ingested", classIdx, totalClasses));
                    }
                }
            }
            if (!typeBatch.isEmpty()) {
                dao.batchInsertTypesFast(typeBatch);
                typeBatch.clear();
                p.setIngestedClasses(totalClasses);
            }

            // Fields
            int fieldBatchSize = 5000;
            List<CodeField> fieldBatch = new ArrayList<>(fieldBatchSize);
            String[] fieldNames = {"id", "state", "config", "auditTracker", "dataSource", "handler", "cacheManager", "retryPolicy"};
            int fieldCount = 0;
            for (int c = 0; c < totalClasses && fieldCount < totalFields; c++) {
                int pkgIdx = c / classesPerPkg;
                String pkgName = String.format("com.enterprise.module%03d", Math.min(pkgIdx, numPackages - 1));
                String classFqn = pkgName + "." + String.format("ServiceComponent_%04d", c % classesPerPkg);
                int fieldsForThisClass = Math.min(fieldsPerClass, totalFields - fieldCount);
                for (int f = 0; f < fieldsForThisClass; f++) {
                    String fName = fieldNames[f % fieldNames.length] + "_" + (f / fieldNames.length);
                    String fieldFqn = classFqn + "." + fName;
                    CodeField field = new CodeField();
                    field.setId(fieldFqn); field.setFqn(fieldFqn); field.setSimpleName(fName);
                    field.setDeclaringTypeFqn(classFqn);
                    field.setFieldType((f % 3 == 0) ? "String" : (f % 3 == 1) ? "Long" : "Map<String,Object>");
                    field.setModifiers("private");
                    fieldBatch.add(field);
                    fieldCount++;

                    if (fieldBatch.size() >= fieldBatchSize) {
                        checkCancel();
                        dao.batchInsertFieldsFast(fieldBatch);
                        fieldBatch.clear();
                        p.setIngestedFields(fieldCount);
                        p.setCurrentDetail(String.format("%,d / %,d fields ingested", fieldCount, totalFields));
                    }
                }
            }
            if (!fieldBatch.isEmpty()) {
                dao.batchInsertFieldsFast(fieldBatch);
                fieldBatch.clear();
                p.setIngestedFields(fieldCount);
            }

            // Methods
            int totalMethods = totalClasses * methodsPerClass;
            p.setTargetMethods(totalMethods);
            int methodBatchSize = 5000;
            List<CodeMethod> methodBatch = new ArrayList<>(methodBatchSize);
            String[] methodNames = {"init", "execute", "process", "validate", "persist"};
            int methodCount = 0;
            for (int c = 0; c < totalClasses; c++) {
                int pkgIdx = c / classesPerPkg;
                String pkgName = String.format("com.enterprise.module%03d", Math.min(pkgIdx, numPackages - 1));
                String classFqn = pkgName + "." + String.format("ServiceComponent_%04d", c % classesPerPkg);
                for (int m = 0; m < methodsPerClass; m++) {
                    String mName = methodNames[m % methodNames.length];
                    String mSig = (m % 2 == 0) ? "()" : "(String,int)";
                    String methodFqn = classFqn + "." + mName + mSig;
                    CodeMethod method = new CodeMethod();
                    method.setId(methodFqn); method.setFqn(methodFqn); method.setSimpleName(mName);
                    method.setDeclaringTypeFqn(classFqn);
                    method.setReturnType((m == 0) ? "void" : (m == 3) ? "boolean" : "String");
                    method.setModifiers("public");
                    methodBatch.add(method);
                    methodCount++;

                    if (methodBatch.size() >= methodBatchSize) {
                        checkCancel();
                        dao.batchInsertMethodsFast(methodBatch);
                        methodBatch.clear();
                        p.setIngestedMethods(methodCount);
                    }
                }
            }
            if (!methodBatch.isEmpty()) {
                dao.batchInsertMethodsFast(methodBatch);
                methodBatch.clear();
                p.setIngestedMethods(totalMethods);
            }

            p.setPercentage(15);

            // ─────────────────────────────────────────────────────────────────
            // Phase 3: Streaming Bulk Ingestion of Relationships
            // ─────────────────────────────────────────────────────────────────
            p.setActiveStage("STREAM_RELS");
            p.setCurrentPhase("Streaming Relationships");

            long callsTarget = (totalRels * 2) / 3;
            long fieldsTarget = totalRels - callsTarget;

            long relsStart = System.currentTimeMillis();
            long lastLogTime = relsStart;
            long lastLogCount = 0;

            final int streamBatchSize = 10_000;
            List<CodeRelationship> streamBatch = new ArrayList<>(streamBatchSize);

            long generatedCount = 0;
            long callRelsCount = 0;
            long fieldRelsCount = 0;
            long idCounter = 1;

            while (generatedCount < totalRels) {
                checkCancel();

                CodeRelationship rel = new CodeRelationship();
                long curId = idCounter++;
                rel.setId(String.format("rel-%012d", curId));

                int callerClassIdx = (int) (curId % totalClasses);
                int callerPkg = callerClassIdx / classesPerPkg;
                String callerPkgName = String.format("com.enterprise.module%03d", Math.min(callerPkg, numPackages - 1));
                String callerClassFqn = callerPkgName + "." + String.format("ServiceComponent_%04d", callerClassIdx % classesPerPkg);
                int callerMethodIdx = (int) ((curId >> 3) % methodsPerClass);
                String callerMethodName = methodNames[callerMethodIdx];
                String callerMethodSig = (callerMethodIdx % 2 == 0) ? "()" : "(String,int)";
                String fromFqn = callerClassFqn + "." + callerMethodName + callerMethodSig;
                rel.setFromEntityFqn(fromFqn);

                if (callRelsCount < callsTarget && (fieldRelsCount >= fieldsTarget || (curId % 3 != 0))) {
                    int calleeClassOffset = 1 + (int) ((curId * 31) % (totalClasses - 1));
                    int calleeClassIdx = (callerClassIdx + calleeClassOffset) % totalClasses;
                    int calleePkg = calleeClassIdx / classesPerPkg;
                    String calleePkgName = String.format("com.enterprise.module%03d", Math.min(calleePkg, numPackages - 1));
                    String calleeClassFqn = calleePkgName + "." + String.format("ServiceComponent_%04d", calleeClassIdx % classesPerPkg);
                    int calleeMethodIdx = (int) ((curId >> 5) % methodsPerClass);
                    String calleeMethodName = methodNames[calleeMethodIdx];
                    String calleeMethodSig = (calleeMethodIdx % 2 == 0) ? "()" : "(String,int)";
                    String toFqn = calleeClassFqn + "." + calleeMethodName + calleeMethodSig;

                    rel.setToEntityFqn(toFqn);
                    rel.setKind("CALLS");
                    rel.setSourceLine((int) (10 + (curId % 180)));
                    callRelsCount++;
                } else {
                    int fIdx = (int) (curId % fieldsPerClass);
                    String fName = fieldNames[fIdx % fieldNames.length] + "_" + (fIdx / fieldNames.length);
                    String toFieldFqn = callerClassFqn + "." + fName;

                    rel.setToEntityFqn(toFieldFqn);
                    rel.setKind((curId % 2 == 0) ? "READS_FIELD" : "WRITES_FIELD");
                    rel.setSourceLine((int) (10 + (curId % 180)));
                    fieldRelsCount++;
                }

                streamBatch.add(rel);
                generatedCount++;

                if (streamBatch.size() >= streamBatchSize) {
                    dao.batchInsertRelationshipsFast(streamBatch);
                    streamBatch.clear();
                    p.setIngestedRelationships(generatedCount);
                }

                if (generatedCount % 200_000 == 0 || generatedCount == totalRels) {
                    long now = System.currentTimeMillis();
                    long intervalMs = Math.max(1, now - lastLogTime);
                    long intervalCount = generatedCount - lastLogCount;
                    double rate = (intervalCount * 1000.0) / intervalMs;

                    File mvFile = new File(dbDir.toFile(), "codelens_db.mv.db");
                    double dbMb = mvFile.exists() ? (mvFile.length() / (1024.0 * 1024.0)) : 0;
                    long heapMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);

                    p.setRateRowsPerSec(rate);
                    p.setDbSizeMb(dbMb);
                    p.setHeapUsedMb(heapMb);

                    int relPct = 15 + (int) ((generatedCount * 65.0) / totalRels); // 15% -> 80%
                    p.setPercentage(relPct);
                    p.setCurrentDetail(String.format("%,d / %,d rels (%,.0f rows/s) · DB: %,.1f MB",
                            generatedCount, totalRels, rate, dbMb));

                    lastLogTime = now;
                    lastLogCount = generatedCount;
                }
            }

            if (!streamBatch.isEmpty()) {
                dao.batchInsertRelationshipsFast(streamBatch);
                streamBatch.clear();
                p.setIngestedRelationships(totalRels);
            }

            long relsDuration = System.currentTimeMillis() - relsStart;
            double avgRate = (totalRels * 1000.0) / Math.max(1, relsDuration);

            // ─────────────────────────────────────────────────────────────────
            // Phase 4: Secondary Index Rebuilding & MVStore Compaction
            // ─────────────────────────────────────────────────────────────────
            p.setActiveStage("REBUILD_INDEXES");
            p.setCurrentPhase("Rebuilding Secondary Indexes");
            p.setPercentage(82);

            long indexStart = System.currentTimeMillis();
            db.finishBulkLoad((step, total, indexName, tableName, desc) -> {
                int pct = 82 + (int) ((step * 13.0) / total); // 82% -> 95%
                p.setPercentage(pct);
                p.setCurrentDetail(String.format("[%d/%d] %s on %s", step, total, indexName, tableName));
            });
            long indexDuration = System.currentTimeMillis() - indexStart;

            // ─────────────────────────────────────────────────────────────────
            // Phase 5: Verification & Health Audit
            // ─────────────────────────────────────────────────────────────────
            p.setActiveStage("AUDIT_HEALTH");
            p.setCurrentPhase("Auditing Database & Queries");
            p.setPercentage(96);

            File mvFile = new File(dbDir.toFile(), "codelens_db.mv.db");
            double finalDbMb = mvFile.exists() ? (mvFile.length() / (1024.0 * 1024.0)) : 0;
            p.setDbSizeMb(finalDbMb);

            Map<String, Object> health = db.runHealthCheck();

            // Point query benchmark
            long q0 = System.nanoTime();
            int callerCount = 0;
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                     "SELECT from_entity_fqn FROM relationships WHERE kind='CALLS' AND to_entity_fqn = ? LIMIT 50")) {
                ps.setString(1, "com.enterprise.module000.ServiceComponent_0000.init()");
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) callerCount++;
                }
            }
            double qTimeMs = (System.nanoTime() - q0) / 1_000_000.0;

            // Streaming cursor benchmark (1M+ rows/sec covering index test)
            long streamStart = System.currentTimeMillis();
            AtomicLong streamedCalls = new AtomicLong();
            dao.streamCallRelationships((from, to) -> streamedCalls.incrementAndGet());
            long streamDuration = System.currentTimeMillis() - streamStart;
            double streamRate = (streamedCalls.get() * 1000.0) / Math.max(1, streamDuration);

            // Final Report
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("totalClasses", totalClasses);
            report.put("totalFields", totalFields);
            report.put("totalMethods", totalMethods);
            report.put("totalRelationships", totalRels);
            report.put("relsDurationMs", relsDuration);
            report.put("avgThroughputRowsPerSec", avgRate);
            report.put("indexDurationMs", indexDuration);
            report.put("streamedCallsCount", streamedCalls.get());
            report.put("streamDurationMs", streamDuration);
            report.put("streamThroughputEdgesPerSec", streamRate);
            report.put("dbSizeMb", finalDbMb);
            report.put("dbSizeGb", finalDbMb / 1024.0);
            report.put("bytesPerRelationship", (finalDbMb * 1024.0 * 1024.0) / totalRels);
            report.put("pingLatencyMs", health.get("pingMs"));
            report.put("pointQueryLatencyMs", qTimeMs);
            report.put("databaseStatus", health.get("status"));
            report.put("verifiedIndexes", health.get("indexes"));

            p.setBenchmarkReport(report);
            p.setStatus(StressTestProgress.Status.COMPLETE);
            p.setActiveStage("COMPLETE");
            p.setCurrentPhase("Benchmark Complete");
            p.setCurrentDetail(String.format("%,d rels in %.1fs (%,.0f rows/s) · DB Size: %,.1f MB",
                    totalRels, relsDuration / 1000.0, avgRate, finalDbMb));
            p.setMessage(String.format("Stress test completed successfully: %,d classes, %,d fields, %,d rels in %.1f min.",
                    totalClasses, totalFields, totalRels, p.getDurationMs() / 60000.0));
            p.setPercentage(100);
            p.setEndTime(System.currentTimeMillis());

            // Persist scan metadata so any loaded stress dataset is immediately recognized
            try {
                ScanProgress sp = new ScanProgress(ScanProgress.Status.COMPLETE);
                sp.setSourcePath(dbPathStr);
                sp.setTotalFiles(totalClasses);
                sp.setProcessedFiles(totalClasses);
                sp.setParsedFiles(totalClasses);
                sp.setTypesFound(totalClasses);
                sp.setMethodsFound(totalMethods);
                sp.setFieldsFound(totalFields);
                sp.setRelationshipsFound((int) Math.min(Integer.MAX_VALUE, totalRels));
                sp.setCurrentPhase("Complete");
                sp.setCurrentDetail(String.format("Benchmark dataset: %,d classes, %,d rels", totalClasses, totalRels));
                sp.setMessage("Scale stress test dataset loaded");
                sp.setStartTime(p.getStartTime());
                sp.setEndTime(p.getEndTime());
                dao.saveScanMeta(sp);
            } catch (Exception ex) {
                log.warn("Failed to save scan metadata for stress dataset: {}", ex.getMessage());
            }

        } finally {
            db.close();
        }
    }

    private void checkCancel() throws InterruptedException {
        if (cancelRequested.get() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Stress test was cancelled.");
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursively(c);
            }
        }
        file.delete();
    }
}
