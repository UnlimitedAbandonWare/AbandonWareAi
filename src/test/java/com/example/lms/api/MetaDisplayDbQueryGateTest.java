package com.example.lms.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MetaDisplayDbQueryController.SqlGate} 정책 계약:
 * 읽기 전용 SELECT 계열만 통과하고 쓰기/DDL/파일함수/다중문장/우회 패턴은 거부한다.
 * 파이썬 레인(scripts/meta_display_db_export.py check_sql)과 동일 규칙을 유지한다.
 */
class MetaDisplayDbQueryGateTest {

    private static String rejectReason(String sql) {
        return MetaDisplayDbQueryController.SqlGate.check(sql);
    }

    @Test
    void acceptsReadOnlyStarts() {
        assertNull(rejectReason("SELECT * FROM chat_message"));
        assertNull(rejectReason("select id, content from chat_message where id = 1"));
        assertNull(rejectReason("WITH recent AS (SELECT * FROM chat_message) SELECT * FROM recent"));
        assertNull(rejectReason("TABLE chat_message"));
        assertNull(rejectReason("VALUES (1, 'a')"));
        assertNull(rejectReason("EXPLAIN SELECT * FROM chat_message"));
        assertNull(rejectReason("SELECT * FROM t WHERE note = 'a; drop table t'")); // 리터럴 내 토큰은 무시
        assertNull(rejectReason("  -- 주석\nSELECT 1"));
        assertNull(rejectReason("SELECT 1;")); // 후행 세미콜론 하나는 허용
    }

    @Test
    void rejectsWritesAndDdl() {
        assertNotNull(rejectReason("DELETE FROM chat_message"));
        assertNotNull(rejectReason("UPDATE chat_message SET content='x'"));
        assertNotNull(rejectReason("INSERT INTO chat_message(id) VALUES(1)"));
        assertNotNull(rejectReason("DROP TABLE chat_message"));
        assertNotNull(rejectReason("ALTER TABLE chat_message ADD COLUMN x INT"));
        assertNotNull(rejectReason("CREATE TABLE x(id INT)"));
        assertNotNull(rejectReason("TRUNCATE TABLE chat_message"));
        assertNotNull(rejectReason("MERGE INTO chat_message USING t ON a=b WHEN MATCHED THEN UPDATE SET x=1"));
        assertNotNull(rejectReason("GRANT SELECT ON t TO u"));
        assertNotNull(rejectReason("SET LOCK_MODE 0"));
        assertNotNull(rejectReason("SHUTDOWN"));
    }

    @Test
    void rejectsMultiStatementAndBypass() {
        assertNotNull(rejectReason("SELECT 1; SELECT 2"));
        assertNotNull(rejectReason("SELECT * FROM t WHERE x='a'; DROP TABLE t"));
        assertNotNull(rejectReason("-- 주석\nDELETE FROM t"));
        assertNotNull(rejectReason("/* bypass */ DELETE FROM t"));
    }

    @Test
    void rejectsFileAndServerFunctions() {
        assertNotNull(rejectReason("CALL CSVWRITE('x.csv','SELECT 1')"));
        assertNotNull(rejectReason("SELECT CSVREAD('etc/passwd')"));
        assertNotNull(rejectReason("SELECT FILE_READ('x') FROM dual"));
        assertNotNull(rejectReason("RUNSCRIPT FROM 'x.sql'"));
    }

    @Test
    void rejectsEmptyAndOversized() {
        assertNotNull(rejectReason(null));
        assertNotNull(rejectReason(""));
        assertNotNull(rejectReason("   "));
        String big = "SELECT " + "1,".repeat(9000);
        String reason = rejectReason(big);
        assertNotNull(reason);
        assertTrue(reason.startsWith("sql-too-long"));
    }

    @Test
    void rejectsNonSelectFirstToken() {
        assertNotNull(rejectReason("SHOW TABLES"));
        assertNotNull(rejectReason("DESCRIBE chat_message"));
        assertNotNull(rejectReason("? what"));
    }
}
