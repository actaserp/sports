package mes.domain.services.impl;


import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.DataException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import mes.domain.services.LogWriter;
import mes.domain.services.SqlRunner;

/**
 * 사업장(테넌트) DB 전용 SqlRunner.
 * RoutingDataSource를 통해 TenantContext.dbKey에 해당하는 사업장 DB에 쿼리를 실행합니다.
 * 메인 DB 테이블(menu_item, auth_user 등)에는 @Qualifier("mainSqlRunner")를 사용하세요.
 */
@Slf4j
@Primary
@Repository
public class SqlRunQueryImpl implements SqlRunner {

	@Autowired
	@Qualifier("namedParameterJdbcTemplate")
    private NamedParameterJdbcTemplate jdbcTemplate;

	@Autowired
	LogWriter logWriter;

    public List<Map<String, Object>> getRows(String sql, MapSqlParameterSource dicParam) {
    	try {
    		return this.jdbcTemplate.queryForList(sql, dicParam);
		} catch(DataAccessException de) {
    		log.error("[SqlRunner] getRows 오류: {}", de.getMessage());
    		return null;
    	} catch (Exception e) {
			logWriter.addDbLog("error", "SqlRunQueryImpl.getRows", e);
			return null;
		}
    }

    public Map<String, Object> getRow(String sql, MapSqlParameterSource dicParam) {
    	try {
    		return this.jdbcTemplate.queryForMap(sql, dicParam);
		} catch(DataAccessException de) {
			return null;
    	} catch (Exception e) {
			logWriter.addDbLog("error", "SqlRunQueryImpl.getRow", e);
			return null;
		}
    }

    public int execute(String sql, MapSqlParameterSource dicParam) {
    	try {
    		return this.jdbcTemplate.update(sql, dicParam);
		} catch (Exception e) {
			logWriter.addDbLog("error", "SqlRunQueryImpl.execute", e);
			return 0;
		}
    }

    public int queryForCount(String sql, MapSqlParameterSource dicParam) {
    	return this.jdbcTemplate.queryForObject(sql, dicParam, int.class);
    }

    public <T> T queryForObject(String sql, MapSqlParameterSource dicParam, RowMapper<T> mapper) throws DataException {
    	return this.jdbcTemplate.queryForObject(sql, dicParam, mapper);
    }

	public int[] batchUpdate(String sql, SqlParameterSource[] batchArgs) {
		try {
			return this.jdbcTemplate.batchUpdate(sql, batchArgs);
		} catch (DataAccessException de) {
			log.error("[SqlRunner] batchUpdate 오류: {}", de.getMessage());
			return new int[0];
		} catch (Exception e) {
			logWriter.addDbLog("error", "SqlRunQueryImpl.batchUpdate", e);
			return new int[0];
		}
	}
}
