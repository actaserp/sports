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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class TenantDataSourceManager {

    /**
     *  * 애플리케이션 시작 시(@PostConstruct), 메인 DB의 tb_tenant_db 테이블에서 테넌트별 DB 접속 정보(URL, 계정, DB 타입 등)를 읽어옵니다.
     *        * 읽어온 정보를 바탕으로 각 테넌트용 HikariDataSource를 동적으로 생성하여 RoutingDataSource에 등록합니다.
     * **/

    @Autowired
    @Qualifier("mainDataSource")
    private DataSource mainDataSource;

    @Autowired
    private RoutingDataSource routingDataSource;

    @PostConstruct
    public void init() {
        loadTenantDataSources();
    }

    public void loadTenantDataSources() {
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
            return;
        }

        Map<Object, Object> targets = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String spjangcd   = (String) row.get("spjangcd");
            String dbAlias    = (String) row.get("db_alias");
            String dbUrl      = (String) row.get("db_url");
            String dbUsername = (String) row.get("db_username");
            String dbPassword = (String) row.get("db_password");
            String dbType     = (String) row.get("db_type");
            Integer poolSize  = row.get("pool_size") != null ? ((Number) row.get("pool_size")).intValue() : 5;

            if (dbUrl == null || dbUrl.isBlank()) continue;

            // 라우팅 키: main이면 spjangcd, 보조 DB면 "spjangcd:db_alias"
            String routingKey = "main".equalsIgnoreCase(dbAlias) ? spjangcd : spjangcd + ":" + dbAlias;

            log.info("테넌트 DB 연결 시도: key={}, db_type={}, url={}", routingKey, dbType, dbUrl);

            try {
                DataSource ds = createDataSource(dbUrl, dbUsername, dbPassword, dbType, poolSize);
                targets.put(routingKey, ds);
                log.info("테넌트 DataSource 로드 완료: key={}", routingKey);
            } catch (Exception e) {
                log.error("테넌트 DataSource 생성 실패: key={}", routingKey, e);
            }
        }

        routingDataSource.setTargetDataSources(targets);
        routingDataSource.afterPropertiesSet();
        log.info("총 {}개 테넌트 DataSource 등록 완료", targets.size());
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
        return new HikariDataSource(config);
    }

    /**
     * db_url + db_type → JDBC URL 조립 (완전한 JDBC URL이면 그대로 반환)
     *
     * postgresql : host:port/dbname        → jdbc:postgresql://host:port/dbname
     * mssql      : host:port/dbname        → jdbc:sqlserver://host:port;databaseName=dbname;...
     * oracle SID : host:port:SID           → jdbc:oracle:thin:@host:port:SID
     * oracle SVC : host:port/serviceName   → jdbc:oracle:thin:@//host:port/serviceName
     */
    public static String buildJdbcUrl(String url, String dbType) {
        if (url.startsWith("jdbc:")) return url;

        if ("oracle".equalsIgnoreCase(dbType)) {
            // 슬래시 없으면 SID 형식 (host:port:SID)
            if (!url.contains("/")) {
                return "jdbc:oracle:thin:@" + url;
            }
            // 슬래시 있으면 Service Name 형식 (host:port/serviceName)
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
