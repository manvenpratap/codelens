package com.codelens.parser;

import com.codelens.core.model.CodeRelationship;
import com.codelens.core.model.CodeType;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class PolyglotSourceScannerTest {

    private static void assertTrue(boolean condition, String msg) {
        if (!condition) throw new AssertionError("Assertion failed: " + msg);
    }

    private static void assertNotNull(Object obj, String msg) {
        if (obj == null) throw new AssertionError("Assertion failed (expected non-null): " + msg);
    }

    public void testPolyglotSourceScannerJsAndSql() throws IOException {
        Path tempDir = Files.createTempDirectory("codelens_polyglot_test");
        try {
            Path jsFile = tempDir.resolve("apiClient.ts");
            Files.writeString(jsFile,
                "import axios from 'axios';\n" +
                "export async function getOrders() {\n" +
                "    const res = await fetch('/api/v1/orders', { method: 'GET' });\n" +
                "    return res.json();\n" +
                "}\n" +
                "export async function createCustomer(data) {\n" +
                "    return axios.post('/api/v1/customers', data);\n" +
                "}\n"
            );

            Path sqlFile = tempDir.resolve("schema.sql");
            Files.writeString(sqlFile,
                "CREATE TABLE user_accounts (\n" +
                "    id BIGINT PRIMARY KEY,\n" +
                "    email VARCHAR(255) NOT NULL\n" +
                ");\n" +
                "CREATE VIEW active_users AS SELECT * FROM user_accounts WHERE email IS NOT NULL;\n"
            );

            PolyglotSourceScanner scanner = new PolyglotSourceScanner();
            PolyglotSourceScanner.PolyglotScanResult result = scanner.scanDirectory(tempDir, java.util.Collections.emptyList());

            assertNotNull(result, "Scan result should not be null");
            assertTrue(result.types.size() >= 4, "Should extract endpoints and tables: found " + result.types.size());

            boolean hasOrdersGet = result.types.stream().anyMatch(t -> t.getFqn().equals("endpoint:GET /api/v1/orders"));
            boolean hasCustomersPost = result.types.stream().anyMatch(t -> t.getFqn().equals("endpoint:POST /api/v1/customers"));
            boolean hasUserAccountsTable = result.types.stream().anyMatch(t -> t.getFqn().equals("table:USER_ACCOUNTS"));
            boolean hasActiveUsersView = result.types.stream().anyMatch(t -> t.getFqn().equals("table:ACTIVE_USERS"));

            assertTrue(hasOrdersGet, "Endpoint GET /api/v1/orders should be extracted");
            assertTrue(hasCustomersPost, "Endpoint POST /api/v1/customers should be extracted");
            assertTrue(hasUserAccountsTable, "Table USER_ACCOUNTS should be extracted");
            assertTrue(hasActiveUsersView, "View ACTIVE_USERS should be extracted");

            assertTrue(result.relationships.size() >= 2, "Should create CALLS/CONSUMES relationships");
        } finally {
            deleteDirectory(tempDir.toFile());
        }
    }

    private void deleteDirectory(File dir) {
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) deleteDirectory(f);
            }
        }
        dir.delete();
    }
}
