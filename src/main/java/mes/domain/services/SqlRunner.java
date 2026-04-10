package mes.domain.services;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

public interface SqlRunner {

	public List<Map<String, Object>> getRows(String sql, MapSqlParameterSource mapParam);
	
	public Map<String, Object> getRow(String sql, MapSqlParameterSource mapParam);
	
	public int execute(String sql, MapSqlParameterSource mapParam);
	
	public int queryForCount(String sql,  MapSqlParameterSource mapParam);
	
	public <T> T queryForObject(String sql,  MapSqlParameterSource mapParam, RowMapper<T> mapper);

	public int[]  batchUpdate(String sql,  SqlParameterSource[] batchArgs);

	/** INSERT 후 자동생성된 키(IDENTITY) 반환. 실패 시 null. */
	public Number executeAndReturnKey(String sql, MapSqlParameterSource paramMap, String keyColumnName);
}
