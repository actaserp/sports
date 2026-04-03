package mes.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class TenantDataSourceManager {

    @Autowired
    @Qualifier("mainDataSource")
    private DataSource mainDataSource;

    @Autowired
    private RoutingDataSource routingDataSource;

    /** 성공적으로 연결된 라우팅 키 */
    private final Set<String> connectedKeys = Collections.synchronizedSet(new HashSet<>());

    /** 연결 실패한 라우팅 키 */
    private final Set<String> failedKeys = Collections.synchronizedSet(new HashSet<>());

    /** 등록된 DataSource 전체 (성공한 것만) */
    private final Map<String, DataSource> managedSources = Collections.synchronizedMap(new HashMap<>());

    public Set<String> getConnectedKeys() {
        return connectedKeys;
    }

    public Set<String> getFailedKeys() {
        return failedKeys;
    }

    public int getManagedCount() {
        return connectedKeys.size() + failedKeys.size();
    }

    @PostConstruct
    public void init() {
        loadTenantDataSources();
    }

    /**
     * 전체 재로드: tb_tenant_db 전체를 다시 읽어 DataSource를 재생성합니다.
     * 기존 연결은 닫힌 후 재생성되므로, 진행 중인 쿼리가 없을 때 사용하세요.
     */
    public synchronized int loadTenantDataSources() {
        JdbcTemplate jdbc = new JdbcTemplate(mainDataSource);

        String sql = """
                SELECT spjangcd, db_alias, db_url, db_username, db_password, db_type, pool_size
                FROM tb_tenant_db
                WHERE db_url IS NOT NULL AND db_url <> ''
                """;

        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(sql);
        } catch (Exception e) {
            log.error("tb_tenant_db 테넌트 DB 정보 로드 실패", e);
            return 0;
        }

        // 기존 DataSource 닫기
        for (DataSource ds : managedSources.values()) {
            closeQuietly(ds);
        }
        managedSources.clear();
        connectedKeys.clear();
        failedKeys.clear();

        for (Map<String, Object> row : rows) {
            String spjangcd   = (String) row.get("spjangcd");
            String dbAlias    = (String) row.get("db_alias");
            String dbUrl      = (String) row.get("db_url");
            String dbUsername = (String) row.get("db_username");
            String dbPassword = (String) row.get("db_password");
            String dbType     = (String) row.get("db_type");
            Integer poolSize  = row.get("pool_size") != null ? ((Number) row.get("pool_size")).intValue() : 5;

            if (dbUrl == null || dbUrl.isBlank()) continue;

            String routingKey = buildRoutingKey(spjangcd, dbAlias);
            log.info("테넌트 DB 연결 시도: key={}, db_type={}, url={}", routingKey, dbType, dbUrl);

            try {
                DataSource ds = createDataSource(dbUrl, dbUsername, dbPassword, dbType, poolSize);
                managedSources.put(routingKey, ds);
                connectedKeys.add(routingKey);
                log.info("테넌트 DataSource 로드 완료: key={}", routingKey);
            } catch (Exception e) {
                failedKeys.add(routingKey);
                log.error("테넌트 DataSource 생성 실패: key={}", routingKey, e);
            }
        }

        applyToRoutingDataSource();
        log.info("전체 재로드 완료 — 성공: {}개 / 전체: {}개", connectedKeys.size(), rows.size());
        return connectedKeys.size();
    }

    /**
     * 개별 재연결: 해당 키만 교체합니다. 다른 테넌트 연결은 영향 없습니다.
     *
     * @return null(성공) 또는 에러 메시지(실패)
     */
    public synchronized String addOrReplaceTenantDataSource(
            String spjangcd, String dbAlias,
            String dbUrl, String dbUsername, String dbPassword,
            String dbType, int poolSize) {

        String routingKey = buildRoutingKey(spjangcd, dbAlias);

        // 기존 DataSource만 닫기 (다른 테넌트 영향 없음)
        DataSource old = managedSources.get(routingKey);
        if (old != null) {
            closeQuietly(old);
            managedSources.remove(routingKey);
        }
        connectedKeys.remove(routingKey);
        failedKeys.remove(routingKey);

        try {
            DataSource ds = createDataSource(dbUrl, dbUsername, dbPassword, dbType, poolSize);
            managedSources.put(routingKey, ds);
            connectedKeys.add(routingKey);
            applyToRoutingDataSource();
            log.info("테넌트 DataSource 개별 등록 완료: key={}", routingKey);
            return null; // 성공
        } catch (Exception e) {
            failedKeys.add(routingKey);
            applyToRoutingDataSource();
            log.error("테넌트 DataSource 개별 등록 실패: key={}", routingKey, e);
            return rootCauseMessage(e);
        }
    }

    private String buildRoutingKey(String spjangcd, String dbAlias) {
        return "main".equalsIgnoreCase(dbAlias) ? spjangcd : spjangcd + ":" + dbAlias;
    }

    private void applyToRoutingDataSource() {
        routingDataSource.setTargetDataSources(new HashMap<>(managedSources));
        routingDataSource.afterPropertiesSet();
    }

    /** HikariCP 래퍼 예외를 벗기고 실제 DB 예외 메시지만 반환 */
    private String rootCauseMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() != null ? cause.getMessage() : e.getMessage();
    }

    private void closeQuietly(DataSource ds) {
        if (ds instanceof HikariDataSource hds) {
            try { hds.close(); } catch (Exception ignore) {}
        }
    }

    private DataSource createDataSource(String url, String username, String password, String dbType, int poolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(buildJdbcUrl(url, dbType));
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(1);
        config.setMaxLifetime(1800000);
        config.setIdleTimeout(600000);
        config.setConnectionTimeout(10000);

        if ("mssql".equalsIgnoreCase(dbType)) {
            config.setDriverClassName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        } else if ("oracle".equalsIgnoreCase(dbType)) {
            config.setDriverClassName("oracle.jdbc.OracleDriver");
        } else {
            config.setDriverClassName("org.postgresql.Driver");
        }

        return new HikariDataSource(config);
    }

    public static String buildJdbcUrl(String url, String dbType) {
        if (url.startsWith("jdbc:")) return url;

        if ("oracle".equalsIgnoreCase(dbType)) {
            if (!url.contains("/")) {
                return "jdbc:oracle:thin:@" + url;
            }
            String[] parts = url.split("/", 2);
            return "jdbc:oracle:thin:@//" + parts[0] + "/" + parts[1];
        }

        String[] parts = url.split("/", 2);
        String hostPort = parts[0];
        String dbName   = parts.length > 1 ? parts[1] : "";

        if ("mssql".equalsIgnoreCase(dbType)) {
            return "jdbc:sqlserver://" + hostPort + ";databaseName=" + dbName
                    + ";encrypt=false;trustServerCertificate=false";
        } else {
            return "jdbc:postgresql://" + hostPort + "/" + dbName;
        }
    }
}
