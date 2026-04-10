package mes.domain.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.util.List;
import java.util.Map;

/**
 * 메인 DB 전용 SqlRunner.
 * mainDataSource에 직접 연결되어 항상 메인 DB에 쿼리를 실행합니다.
 * auth_user, menu_item, menu_folder, menu_front_folder, user_group, user_group_menu,
 * user_profile, tenant_menu, sys_log, login_log, menu_use_log 등 메인 DB 테이블 전용.
 */
@Slf4j
@RequiredArgsConstructor
public class MainSqlRunnerImpl implements SqlRunner {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Override
    public List<Map<String, Object>> getRows(String sql, MapSqlParameterSource dicParam) {
        try {
            return jdbcTemplate.queryForList(sql, toParam(dicParam));
        } catch (DataAccessException e) {
            log.error("[MainSqlRunner] getRows 오류: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public Map<String, Object> getRow(String sql, MapSqlParameterSource dicParam) {
        try {
            return jdbcTemplate.queryForMap(sql, toParam(dicParam));
        } catch (EmptyResultDataAccessException e) {
            return null;
        } catch (DataAccessException e) {
            log.error("[MainSqlRunner] getRow 오류: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public int execute(String sql, MapSqlParameterSource dicParam) {
        try {
            return jdbcTemplate.update(sql, toParam(dicParam));
        } catch (DataAccessException e) {
            log.error("[MainSqlRunner] execute 오류: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public int queryForCount(String sql, MapSqlParameterSource dicParam) {
        Integer count = jdbcTemplate.queryForObject(sql, toParam(dicParam), Integer.class);
        return count != null ? count : 0;
    }

    @Override
    public <T> T queryForObject(String sql, MapSqlParameterSource dicParam, RowMapper<T> mapper) {
        return jdbcTemplate.queryForObject(sql, toParam(dicParam), mapper);
    }

    @Override
    public Number executeAndReturnKey(String sql, MapSqlParameterSource paramMap, String keyColumnName) {
        try {
            GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(sql, paramMap, keyHolder, new String[]{keyColumnName});
            return keyHolder.getKey();
        } catch (DataAccessException e) {
            log.error("[MainSqlRunner] executeAndReturnKey 오류: {}", e.getMessage(), e);
            return null;
        }
    }

    @Override
    public int[] batchUpdate(String sql, SqlParameterSource[] batchArgs) {
        try {
            return jdbcTemplate.batchUpdate(sql, batchArgs);
        } catch (DataAccessException e) {
            log.error("[MainSqlRunner] batchUpdate 오류: {}", e.getMessage());
            return new int[0];
        }
    }

    private SqlParameterSource toParam(MapSqlParameterSource dicParam) {
        return dicParam != null ? dicParam : new MapSqlParameterSource();
    }
}
