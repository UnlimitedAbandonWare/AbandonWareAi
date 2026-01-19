package com.example.lms.boot;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Locale;

/**
 * Startup DB schema safety-net.
 *
 * <p>We persist large system meta messages (e.g., Search Trace HTML, rolling summaries).
 * If {@code chat_message.content} is created as {@code TEXT} (max ~64KB bytes), MariaDB/MySQL can throw:
 * <pre>
 *   Data too long for column 'content' at row 1
 * </pre>
 * especially when utf8mb4 multi-byte characters are present.
 *
 * <p>This runner detects MariaDB/MySQL and upgrades the column to {@code LONGTEXT} when needed.
 * It is fail-soft: if the DB user lacks DDL privileges, the app will still start.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "lms.db.schema-autofix.chat-message-content.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ChatMessageContentColumnAutoFix implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageContentColumnAutoFix.class);

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        String product;
        try (Connection c = dataSource.getConnection()) {
            product = c.getMetaData().getDatabaseProductName();
        } catch (Exception e) {
            log.debug("[DB] chat_message.content autofix: cannot resolve DB product: {}", e.toString());
            return;
        }

        if (product == null) {
            return;
        }

        String p = product.toLowerCase(Locale.ROOT);
        boolean isMy = p.contains("mariadb") || p.contains("mysql");
        if (!isMy) {
            // H2(learning) 등은 스킵
            log.debug("[DB] chat_message.content autofix: skipped (db={})", product);
            return;
        }

        try {
            // data_type: text / longtext / mediumtext ...
            String dataType = jdbcTemplate.queryForObject(
                    "select data_type " +
                            "from information_schema.columns " +
                            "where table_schema = database() " +
                            "  and table_name = 'chat_message' " +
                            "  and column_name = 'content'",
                    String.class);

            if (dataType == null || dataType.isBlank()) {
                log.debug("[DB] chat_message.content autofix: column not found (skip)");
                return;
            }

            String dt = dataType.toLowerCase(Locale.ROOT).trim();
            if (dt.contains("longtext") || dt.contains("mediumtext")) {
                log.info("[DB] chat_message.content type={} (OK)", dataType);
                return;
            }

            // TEXT/TINYTEXT/VARCHAR 등 -> LONGTEXT로 승격
            log.warn("[DB] chat_message.content type={} -> upgrading to LONGTEXT (prevent 'Data too long')", dataType);
            jdbcTemplate.execute("ALTER TABLE chat_message MODIFY COLUMN content LONGTEXT NOT NULL");
            log.info("[DB] chat_message.content upgraded to LONGTEXT");
        } catch (Exception e) {
            log.warn("[DB] chat_message.content autofix failed: {}", e.toString());
        }
    }
}
