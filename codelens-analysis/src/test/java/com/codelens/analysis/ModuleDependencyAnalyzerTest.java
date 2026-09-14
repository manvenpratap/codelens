package com.codelens.analysis;

import com.codelens.core.model.CodeField;
import com.codelens.core.model.CodeMethod;
import com.codelens.core.model.CodePackage;
import com.codelens.core.model.CodeRelationship;
import com.codelens.core.model.CodeType;

import java.util.*;

public class ModuleDependencyAnalyzerTest {

    private static void assertTrue(boolean condition, String msg) {
        if (!condition) throw new AssertionError("Assertion failed: " + msg);
    }

    private static void assertEquals(int expected, int actual, String msg) {
        if (expected != actual) throw new AssertionError(msg + " (expected: " + expected + ", got: " + actual + ")");
    }

    private static void assertEquals(int expected, int actual) {
        assertEquals(expected, actual, "Values should be equal");
    }

    private static void assertEquals(double expected, double actual, double delta, String msg) {
        if (Math.abs(expected - actual) > delta) throw new AssertionError(msg + " (expected: " + expected + ", got: " + actual + ")");
    }

    private static void assertEquals(String expected, String actual, String msg) {
        if (!Objects.equals(expected, actual)) throw new AssertionError(msg + " (expected: \"" + expected + "\", got: \"" + actual + "\")");
    }

    private static void assertEquals(String expected, String actual) {
        assertEquals(expected, actual, "Values should be equal");
    }

    private static void assertNotNull(Object obj, String msg) {
        if (obj == null) throw new AssertionError("Assertion failed (expected non-null): " + msg);
    }

    public void testAnalyzeModuleDependenciesForAM() {
        ModuleDependencyAnalyzer analyzer = new ModuleDependencyAnalyzer();
        List<CodePackage> packages = new ArrayList<>();
        List<CodeType> types = new ArrayList<>();
        List<CodeMethod> methods = new ArrayList<>();
        List<CodeField> fields = new ArrayList<>();
        List<CodeRelationship> relationships = new ArrayList<>();

        // Module 1: AM (Account Management)
        CodePackage pkgAM = new CodePackage("com.tcs.bancs.AM");
        pkgAM.setName("AM");
        pkgAM.setFileCount(2);
        pkgAM.setTypeCount(2);
        packages.add(pkgAM);

        CodeType typeAccount = new CodeType();
        typeAccount.setFqn("com.tcs.bancs.AM.Account");
        typeAccount.setSimpleName("Account");
        typeAccount.setPackageFqn("com.tcs.bancs.AM");
        types.add(typeAccount);

        CodeType typeAccountService = new CodeType();
        typeAccountService.setFqn("com.tcs.bancs.AM.AccountService");
        typeAccountService.setSimpleName("AccountService");
        typeAccountService.setPackageFqn("com.tcs.bancs.AM");
        types.add(typeAccountService);

        CodeMethod mGetBalance = new CodeMethod();
        mGetBalance.setFqn("com.tcs.bancs.AM.Account.getBalance()");
        mGetBalance.setDeclaringTypeFqn("com.tcs.bancs.AM.Account");
        methods.add(mGetBalance);

        CodeMethod mCloseAccount = new CodeMethod();
        mCloseAccount.setFqn("com.tcs.bancs.AM.AccountService.closeAccount(java.lang.String)");
        mCloseAccount.setDeclaringTypeFqn("com.tcs.bancs.AM.AccountService");
        methods.add(mCloseAccount);

        CodeField fBalance = new CodeField();
        fBalance.setFqn("com.tcs.bancs.AM.Account.balance");
        fBalance.setDeclaringTypeFqn("com.tcs.bancs.AM.Account");
        fields.add(fBalance);

        // Module 2: GL (General Ledger)
        CodePackage pkgGL = new CodePackage("com.tcs.bancs.GL");
        pkgGL.setName("GL");
        pkgGL.setFileCount(2);
        pkgGL.setTypeCount(2);
        packages.add(pkgGL);

        CodeType typeLedgerService = new CodeType();
        typeLedgerService.setFqn("com.tcs.bancs.GL.LedgerService");
        typeLedgerService.setSimpleName("LedgerService");
        typeLedgerService.setPackageFqn("com.tcs.bancs.GL");
        types.add(typeLedgerService);

        CodeMethod mPostEntry = new CodeMethod();
        mPostEntry.setFqn("com.tcs.bancs.GL.LedgerService.postEntry(java.lang.String)");
        mPostEntry.setDeclaringTypeFqn("com.tcs.bancs.GL.LedgerService");
        methods.add(mPostEntry);

        CodeField fLedgerStatus = new CodeField();
        fLedgerStatus.setFqn("com.tcs.bancs.GL.LedgerService.ledgerStatus");
        fLedgerStatus.setDeclaringTypeFqn("com.tcs.bancs.GL.LedgerService");
        fields.add(fLedgerStatus);

        // Module 3: PM (Payment Management)
        CodePackage pkgPM = new CodePackage("com.tcs.bancs.PM");
        pkgPM.setName("PM");
        pkgPM.setFileCount(1);
        pkgPM.setTypeCount(1);
        packages.add(pkgPM);

        CodeType typePaymentProcessor = new CodeType();
        typePaymentProcessor.setFqn("com.tcs.bancs.PM.PaymentProcessor");
        typePaymentProcessor.setSimpleName("PaymentProcessor");
        typePaymentProcessor.setPackageFqn("com.tcs.bancs.PM");
        types.add(typePaymentProcessor);

        CodeMethod mProcessPayment = new CodeMethod();
        mProcessPayment.setFqn("com.tcs.bancs.PM.PaymentProcessor.processPayment(double)");
        mProcessPayment.setDeclaringTypeFqn("com.tcs.bancs.PM.PaymentProcessor");
        methods.add(mProcessPayment);

        // Relationships:
        // 1. AM.AccountService.closeAccount calls GL.LedgerService.postEntry (Outbound from AM -> GL, CALLS)
        CodeRelationship r1 = new CodeRelationship();
        r1.setFromEntityFqn("com.tcs.bancs.AM.AccountService.closeAccount(java.lang.String)");
        r1.setToEntityFqn("com.tcs.bancs.GL.LedgerService.postEntry(java.lang.String)");
        r1.setKind("CALLS");
        r1.setSourceLine(42);
        relationships.add(r1);

        // 2. AM.AccountService.closeAccount reads GL.LedgerService.ledgerStatus (Outbound from AM -> GL, READS_FIELD)
        CodeRelationship r2 = new CodeRelationship();
        r2.setFromEntityFqn("com.tcs.bancs.AM.AccountService.closeAccount(java.lang.String)");
        r2.setToEntityFqn("com.tcs.bancs.GL.LedgerService.ledgerStatus");
        r2.setKind("READS_FIELD");
        r2.setSourceLine(48);
        relationships.add(r2);

        // 3. PM.PaymentProcessor.processPayment calls AM.Account.getBalance (Inbound to AM from PM, CALLS)
        CodeRelationship r3 = new CodeRelationship();
        r3.setFromEntityFqn("com.tcs.bancs.PM.PaymentProcessor.processPayment(double)");
        r3.setToEntityFqn("com.tcs.bancs.AM.Account.getBalance()");
        r3.setKind("CALLS");
        r3.setSourceLine(115);
        relationships.add(r3);

        // 4. Internal touch point: AM.AccountService.closeAccount calls AM.Account.getBalance()
        CodeRelationship r4 = new CodeRelationship();
        r4.setFromEntityFqn("com.tcs.bancs.AM.AccountService.closeAccount(java.lang.String)");
        r4.setToEntityFqn("com.tcs.bancs.AM.Account.getBalance()");
        r4.setKind("CALLS");
        r4.setSourceLine(30);
        relationships.add(r4);

        ModuleDependencyAnalyzer.ModuleDependencyInsights insights = analyzer.analyzeModule(
            "AM", packages, types, methods, fields, relationships
        );

        assertNotNull(insights, "insights should not be null");
        assertEquals("AM", insights.moduleName, "moduleName should be AM");
        assertEquals("com.tcs.bancs.AM", insights.packageFqn, "packageFqn should match");
        assertEquals(2, insights.fileCount, "fileCount should match");
        assertEquals(2, insights.typeCount, "typeCount should match");

        // Coupling metrics
        assertEquals(1, insights.efferentCoupling, "AM depends on 1 module (GL)");
        assertEquals(1, insights.afferentCoupling, "AM is depended on by 1 module (PM)");
        assertEquals(3, insights.totalTouchPoints, "Total inter-module touch points should be 3");
        assertEquals(2, insights.totalOutboundTouchPoints, "2 outgoing touch points to GL");
        assertEquals(1, insights.totalInboundTouchPoints, "1 incoming touch point from PM");
        assertEquals(1, insights.internalTouchPoints, "1 intra-module touch point");

        // Instability: 1 / (1 + 1) = 0.5
        assertEquals(0.5, insights.instability, 0.01, "Instability should be 0.5");
        assertEquals("Balanced", insights.stabilityRating, "Stability rating should be Balanced");

        // Touch point kinds
        assertEquals(2, insights.totalByKind.get("CALLS").intValue(), "Total CALLS should be 2");
        assertEquals(1, insights.totalByKind.get("READS_FIELD").intValue(), "Total READS_FIELD should be 1");
        assertEquals(1, insights.inboundByKind.get("CALLS").intValue(), "Inbound CALLS should be 1");
        assertEquals(1, insights.outboundByKind.get("CALLS").intValue(), "Outbound CALLS should be 1");
        assertEquals(1, insights.outboundByKind.get("READS_FIELD").intValue(), "Outbound READS_FIELD should be 1");

        // Outgoing modules check
        assertEquals(1, insights.outgoingModules.size(), "Should have 1 outgoing module");
        ModuleDependencyAnalyzer.ConnectedModule outGL = insights.outgoingModules.get(0);
        assertEquals("GL", outGL.moduleName, "Outgoing module should be GL");
        assertEquals(2, outGL.totalTouchPoints, "GL touch points should be 2");
        assertEquals(1, outGL.functionCallCount, "GL function call count should be 1");
        assertEquals(1, outGL.kinds.get("CALLS").intValue(), "GL CALLS kind count");
        assertEquals(1, outGL.kinds.get("READS_FIELD").intValue(), "GL READS_FIELD kind count");

        // Class usage check
        assertEquals(1, outGL.classUsages.size(), "Should have 1 class usage pair");
        ModuleDependencyAnalyzer.ClassUsageSummary classUsage = outGL.classUsages.get(0);
        assertEquals("com.tcs.bancs.AM.AccountService", classUsage.sourceClassFqn, "Source class FQN");
        assertEquals("com.tcs.bancs.GL.LedgerService", classUsage.targetClassFqn, "Target class FQN");
        assertEquals(2, classUsage.touchPointCount, "Class usage touch point count");
        assertEquals(1, classUsage.kinds.get("CALLS").intValue(), "Class usage CALLS kind");
        assertEquals(1, classUsage.kinds.get("READS_FIELD").intValue(), "Class usage READS_FIELD kind");

        // Incoming modules check
        assertEquals(1, insights.incomingModules.size(), "Should have 1 incoming module");
        ModuleDependencyAnalyzer.ConnectedModule inPM = insights.incomingModules.get(0);
        assertEquals("PM", inPM.moduleName, "Incoming module should be PM");
        assertEquals(1, inPM.totalTouchPoints, "PM touch points should be 1");
        assertEquals(1, inPM.functionCallCount, "PM function call count should be 1");
        assertEquals(1, inPM.kinds.get("CALLS").intValue(), "PM CALLS kind");
    }

    public void testQueryByPackageFqn() {
        ModuleDependencyAnalyzer analyzer = new ModuleDependencyAnalyzer();
        List<CodePackage> packages = new ArrayList<>();
        List<CodeType> types = new ArrayList<>();
        List<CodeMethod> methods = new ArrayList<>();
        List<CodeField> fields = new ArrayList<>();
        List<CodeRelationship> relationships = new ArrayList<>();

        CodePackage pkgGL = new CodePackage("com.tcs.bancs.GL");
        pkgGL.setName("GL");
        packages.add(pkgGL);

        CodePackage pkgAM = new CodePackage("com.tcs.bancs.AM");
        pkgAM.setName("AM");
        packages.add(pkgAM);

        CodeType typeLedgerService = new CodeType();
        typeLedgerService.setFqn("com.tcs.bancs.GL.LedgerService");
        typeLedgerService.setPackageFqn("com.tcs.bancs.GL");
        types.add(typeLedgerService);

        CodeRelationship r1 = new CodeRelationship();
        r1.setFromEntityFqn("com.tcs.bancs.AM.AccountService.closeAccount(java.lang.String)");
        r1.setToEntityFqn("com.tcs.bancs.GL.LedgerService.postEntry(java.lang.String)");
        r1.setKind("CALLS");
        relationships.add(r1);

        ModuleDependencyAnalyzer.ModuleDependencyInsights insights = analyzer.analyzeModule(
            "com.tcs.bancs.GL", packages, types, methods, fields, relationships
        );

        assertNotNull(insights, "insights should not be null");
        assertEquals("GL", insights.moduleName, "moduleName should be GL");
        assertEquals(1, insights.afferentCoupling, "GL is depended on by 1 module");
        assertEquals(0, insights.efferentCoupling, "GL has 0 outbound dependencies");
        assertEquals(0.0, insights.instability, 0.01, "Instability should be 0.0 (Highly stable)");
        assertEquals("Highly Stable", insights.stabilityRating, "Stability rating");
        assertEquals(1, insights.totalInboundTouchPoints, "1 incoming touch point");
    }

    public void testAnalyzeAllOverview() {
        ModuleDependencyAnalyzer analyzer = new ModuleDependencyAnalyzer();
        List<CodePackage> packages = new ArrayList<>();
        List<CodeType> types = new ArrayList<>();
        List<CodeMethod> methods = new ArrayList<>();
        List<CodeField> fields = new ArrayList<>();
        List<CodeRelationship> relationships = new ArrayList<>();

        CodePackage pkgA = new CodePackage("com.example.modA");
        pkgA.setName("modA");
        packages.add(pkgA);

        CodePackage pkgB = new CodePackage("com.example.modB");
        pkgB.setName("modB");
        packages.add(pkgB);

        CodeRelationship r = new CodeRelationship();
        r.setFromEntityFqn("com.example.modA.ServiceA.run()");
        r.setToEntityFqn("com.example.modB.ServiceB.run()");
        r.setKind("CALLS");
        relationships.add(r);

        ModuleDependencyAnalyzer.ModuleOverviewPayload overview = analyzer.analyzeAll(
            packages, types, methods, fields, relationships
        );

        assertNotNull(overview, "overview should not be null");
        assertEquals(2, overview.totalModules, "2 modules");
        assertEquals(1, overview.totalInterModuleTouchPoints, "1 inter-module touch point");
        assertEquals(1, overview.totalByKind.get("CALLS").intValue(), "1 CALLS touch point");
    }

    public void testClassUsageAndInheritanceBetweenModules() {
        ModuleDependencyAnalyzer analyzer = new ModuleDependencyAnalyzer();
        List<CodePackage> packages = new ArrayList<>();
        List<CodeType> types = new ArrayList<>();
        List<CodeMethod> methods = new ArrayList<>();
        List<CodeField> fields = new ArrayList<>();
        List<CodeRelationship> relationships = new ArrayList<>();

        CodePackage pkgCore = new CodePackage("com.example.core");
        pkgCore.setName("core");
        packages.add(pkgCore);

        CodePackage pkgImpl = new CodePackage("com.example.impl");
        pkgImpl.setName("impl");
        packages.add(pkgImpl);

        // Core interface and base class
        CodeType ifaceWorker = new CodeType();
        ifaceWorker.setFqn("com.example.core.Worker");
        ifaceWorker.setSimpleName("Worker");
        ifaceWorker.setPackageFqn("com.example.core");
        types.add(ifaceWorker);

        CodeType baseTask = new CodeType();
        baseTask.setFqn("com.example.core.BaseTask");
        baseTask.setSimpleName("BaseTask");
        baseTask.setPackageFqn("com.example.core");
        types.add(baseTask);

        // Impl class extending BaseTask and implementing Worker
        CodeType specialTask = new CodeType();
        specialTask.setFqn("com.example.impl.SpecialTask");
        specialTask.setSimpleName("SpecialTask");
        specialTask.setPackageFqn("com.example.impl");
        types.add(specialTask);

        // Relationships: EXTENDS and IMPLEMENTS
        CodeRelationship rExtends = new CodeRelationship();
        rExtends.setFromEntityFqn("com.example.impl.SpecialTask");
        rExtends.setToEntityFqn("com.example.core.BaseTask");
        rExtends.setKind("EXTENDS");
        relationships.add(rExtends);

        CodeRelationship rImplements = new CodeRelationship();
        rImplements.setFromEntityFqn("com.example.impl.SpecialTask");
        rImplements.setToEntityFqn("Worker"); // simple name resolution test
        rImplements.setKind("IMPLEMENTS");
        relationships.add(rImplements);

        ModuleDependencyAnalyzer.ModuleDependencyInsights implInsights = analyzer.analyzeModule(
            "impl", packages, types, methods, fields, relationships
        );

        assertNotNull(implInsights, "implInsights should not be null");
        assertEquals(1, implInsights.efferentCoupling, "impl depends on core");
        assertEquals(0, implInsights.afferentCoupling, "no one depends on impl");
        assertEquals(2, implInsights.totalTouchPoints, "2 touch points (EXTENDS and IMPLEMENTS)");
        assertEquals(1, implInsights.totalByKind.get("EXTENDS").intValue(), "1 EXTENDS");
        assertEquals(1, implInsights.totalByKind.get("IMPLEMENTS").intValue(), "1 IMPLEMENTS");

        ModuleDependencyAnalyzer.ConnectedModule outCore = implInsights.outgoingModules.get(0);
        assertEquals("core", outCore.moduleName);
        assertEquals(2, outCore.totalTouchPoints);
        assertEquals(0, outCore.functionCallCount);
        assertEquals(2, outCore.classUsageCount);
    }

    public void testUnresolvedWildcardImportTouchPoints() {
        ModuleDependencyAnalyzer analyzer = new ModuleDependencyAnalyzer();
        List<CodePackage> packages = new ArrayList<>();
        List<CodeType> types = new ArrayList<>();
        List<CodeMethod> methods = new ArrayList<>();
        List<CodeField> fields = new ArrayList<>();
        List<CodeRelationship> relationships = new ArrayList<>();

        // Module RK
        CodePackage pkgRK = new CodePackage("com.tcs.bancs.RK");
        pkgRK.setName("RK");
        packages.add(pkgRK);

        CodeType typeController = new CodeType();
        typeController.setFqn("com.tcs.bancs.RK.RiskAssessmentController");
        typeController.setSimpleName("RiskAssessmentController");
        typeController.setPackageFqn("com.tcs.bancs.RK");
        types.add(typeController);

        CodeMethod mCalcVaR = new CodeMethod();
        mCalcVaR.setFqn("com.tcs.bancs.RK.RiskAssessmentController.RKETCalculateVaR(java.lang.String)");
        mCalcVaR.setSimpleName("RKETCalculateVaR");
        mCalcVaR.setDeclaringTypeFqn("com.tcs.bancs.RK.RiskAssessmentController");
        methods.add(mCalcVaR);

        // Module common
        CodePackage pkgCommon = new CodePackage("com.tcs.bancs.common");
        pkgCommon.setName("common");
        packages.add(pkgCommon);

        CodeType typeAudit = new CodeType();
        typeAudit.setFqn("com.tcs.bancs.common.AuditTrailService");
        typeAudit.setSimpleName("AuditTrailService");
        typeAudit.setPackageFqn("com.tcs.bancs.common");
        types.add(typeAudit);

        CodeMethod mLogAudit = new CodeMethod();
        mLogAudit.setFqn("com.tcs.bancs.common.AuditTrailService.logAuditEvent(java.lang.String,java.lang.String,java.lang.String,java.lang.String)");
        mLogAudit.setSimpleName("logAuditEvent");
        mLogAudit.setDeclaringTypeFqn("com.tcs.bancs.common.AuditTrailService");
        methods.add(mLogAudit);

        // Unresolved target 1: AstVisitor prefixed with caller package because of wildcard import
        CodeRelationship r1 = new CodeRelationship();
        r1.setFromEntityFqn("com.tcs.bancs.RK.RiskAssessmentController.RKETCalculateVaR(java.lang.String)");
        r1.setToEntityFqn("~com.tcs.bancs.RK.AuditTrailService.logAuditEvent");
        r1.setKind("CALLS");
        relationships.add(r1);

        // Unresolved target 2: AstVisitor scoped with variable/field name
        CodeRelationship r2 = new CodeRelationship();
        r2.setFromEntityFqn("com.tcs.bancs.RK.RiskAssessmentController.RKETCalculateVaR(java.lang.String)");
        r2.setToEntityFqn("~auditTrailService.logAuditEvent");
        r2.setKind("CALLS");
        relationships.add(r2);

        ModuleDependencyAnalyzer.ModuleDependencyInsights rkInsights = analyzer.analyzeModule(
            "RK", packages, types, methods, fields, relationships
        );

        assertNotNull(rkInsights, "rkInsights should not be null");
        assertEquals("RK", rkInsights.moduleName);
        assertEquals(2, rkInsights.totalOutboundTouchPoints, "Should have 2 outbound touch points to common");
        assertEquals(2, rkInsights.totalTouchPoints, "Total touch points should be 2");
        assertEquals(1, rkInsights.outgoingModules.size(), "Should connect to 1 outgoing module");
        assertEquals("common", rkInsights.outgoingModules.get(0).moduleName, "Outgoing module should be common");
        assertEquals(2, rkInsights.outgoingModules.get(0).totalTouchPoints, "2 touch points to common");

        // Module common overview
        ModuleDependencyAnalyzer.ModuleDependencyInsights commonInsights = analyzer.analyzeModule(
            "common", packages, types, methods, fields, relationships
        );
        assertNotNull(commonInsights, "commonInsights should not be null");
        assertEquals(2, commonInsights.totalInboundTouchPoints, "common should have 2 inbound touch points from RK");
        assertEquals(1, commonInsights.incomingModules.size(), "common should have 1 incoming module");
        assertEquals("RK", commonInsights.incomingModules.get(0).moduleName);

        // Overview across all modules
        ModuleDependencyAnalyzer.ModuleOverviewPayload overview = analyzer.analyzeAll(
            packages, types, methods, fields, relationships
        );
        assertNotNull(overview, "overview should not be null");
        assertEquals(2, overview.totalInterModuleTouchPoints, "2 inter-module touch points total");
    }
}
