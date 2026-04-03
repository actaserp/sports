package mes.app.system.service;

import lombok.extern.slf4j.Slf4j;
import mes.config.TenantDataSourceManager;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class TenantDbService {

    @Autowired
    @Qualifier("mainSqlRunner")
    SqlRunner sqlRunner;

    @Autowired
    TenantDataSourceManager tenantDataSourceManager;

    public List<Map<String, Object>> getList(String keyword) {
        MapSqlParameterSource param = new MapSqlParameterSource();

        String sql = """
                SELECT t.id, t.spjangcd, x.spjangnm, t.db_alias, t.db_type, t.db_url, t.db_username, t.db_password, t.pool_size, t.custcd
                FROM tb_tenant_db t
                LEFT JOIN tb_xa012 x ON x.spjangcd = t.spjangcd
                WHERE 1=1
                """;

        if (keyword != null && !keyword.isBlank()) {
            sql += " AND spjangcd ILIKE :keyword";
            param.addValue("keyword", "%" + keyword + "%");
        }

        sql += " ORDER BY id";

        List<Map<String, Object>> rows = sqlRunner.getRows(sql, param);

        Set<String> connectedKeys = tenantDataSourceManager.getConnectedKeys();

        for (Map<String, Object> row : rows) {
            String spjangcd = (String) row.get("spjangcd");
            String dbAlias  = (String) row.get("db_alias");
            String routingKey = "main".equalsIgnoreCase(dbAlias) ? spjangcd : spjangcd + ":" + dbAlias;
            row.put("connected", connectedKeys.contains(routingKey) ? "연결됨" : "실패");
        }

        return rows;
    }

    /**
     * 저장 후 해당 DataSource만 개별 재연결.
     * @return Map { connected, errorMsg, totalConnected, totalFailed }
     */
    public Map<String, Object> save(Map<String, Object> param) {
        String id          = param.get("id") != null ? param.get("id").toString() : null;
        String spjangcd    = (String) param.get("spjangcd");
        String dbAlias     = param.getOrDefault("db_alias", "main").toString();
        String dbType      = param.getOrDefault("db_type", "postgresql").toString();
        String dbUrl       = (String) param.get("db_url");
        String dbUsername  = (String) param.get("db_username");
        String dbPassword  = (String) param.get("db_password");
        int    poolSize    = param.get("pool_size") != null ? ((Number) param.get("pool_size")).intValue() : 5;
        String custcd      = extractCustcd(dbUrl);

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("spjangcd",    spjangcd);
        p.addValue("db_alias",    dbAlias);
        p.addValue("db_type",     dbType);
        p.addValue("db_url",      dbUrl);
        p.addValue("db_username", dbUsername);
        p.addValue("pool_size",   poolSize);
        p.addValue("custcd",      custcd);

        if (id == null || id.isBlank()) {
            if (dbPassword == null || dbPassword.isBlank()) {
                throw new IllegalArgumentException("신규 등록 시 비밀번호는 필수입니다.");
            }
            p.addValue("db_password", dbPassword);
            sqlRunner.execute("""
                    INSERT INTO tb_tenant_db (spjangcd, db_alias, db_type, db_url, db_username, db_password, pool_size, custcd)
                    VALUES (:spjangcd, :db_alias, :db_type, :db_url, :db_username, :db_password, :pool_size, :custcd)
                    """, p);
        } else {
            p.addValue("id", Long.parseLong(id));
            if (dbPassword != null && !dbPassword.isBlank()) {
                p.addValue("db_password", dbPassword);
                sqlRunner.execute("""
                        UPDATE tb_tenant_db
                        SET db_alias=:db_alias, db_type=:db_type, db_url=:db_url,
                            db_username=:db_username, db_password=:db_password, pool_size=:pool_size, custcd=:custcd
                        WHERE id=:id
                        """, p);
            }
        }

        // 해당 DataSource만 개별 재연결 (다른 테넌트 영향 없음)
        String connectError = tenantDataSourceManager.addOrReplaceTenantDataSource(
                spjangcd, dbAlias, dbUrl, dbUsername,
                dbPassword != null ? dbPassword : getStoredPassword(id),
                dbType, poolSize);

        return buildStats(connectError);
    }

    /** 특정 row만 재연결 */
    public Map<String, Object> reconnectOne(long id) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("id", id);
        Map<String, Object> row = sqlRunner.getRow("""
                SELECT spjangcd, db_alias, db_type, db_url, db_username, db_password, pool_size
                FROM tb_tenant_db WHERE id = :id
                """, p);

        if (row == null) throw new IllegalArgumentException("해당 레코드를 찾을 수 없습니다: " + id);

        String connectError = tenantDataSourceManager.addOrReplaceTenantDataSource(
                (String) row.get("spjangcd"),
                (String) row.get("db_alias"),
                (String) row.get("db_url"),
                (String) row.get("db_username"),
                (String) row.get("db_password"),
                (String) row.get("db_type"),
                row.get("pool_size") != null ? ((Number) row.get("pool_size")).intValue() : 5
        );

        return buildStats(connectError);
    }

    /** 전체 재로드 */
    public Map<String, Object> reloadAll() {
        tenantDataSourceManager.loadTenantDataSources();
        return buildStats(null);
    }

    private Map<String, Object> buildStats(String connectError) {
        int totalConnected = tenantDataSourceManager.getConnectedKeys().size();
        int totalFailed    = tenantDataSourceManager.getFailedKeys().size();
        return Map.of(
                "connected",      connectError == null,
                "errorMsg",       connectError != null ? connectError : "",
                "totalConnected", totalConnected,
                "totalFailed",    totalFailed
        );
    }

    private String getStoredPassword(String id) {
        if (id == null || id.isBlank()) return null;
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("id", Long.parseLong(id));
        Map<String, Object> row = sqlRunner.getRow("SELECT db_password FROM tb_tenant_db WHERE id=:id", p);
        return row != null ? (String) row.get("db_password") : null;
    }

    private String extractCustcd(String dbUrl) {
        if (dbUrl == null || dbUrl.isBlank()) return null;
        if (dbUrl.startsWith("jdbc:")) {
            int lastSlash = dbUrl.lastIndexOf('/');
            if (lastSlash >= 0) return dbUrl.substring(lastSlash + 1).split("[?;]")[0];
            return null;
        }
        if (dbUrl.contains("/")) return dbUrl.split("/", 2)[1].split("[?;]")[0];
        String[] parts = dbUrl.split(":");
        return parts.length >= 3 ? parts[parts.length - 1] : null;
    }
}
