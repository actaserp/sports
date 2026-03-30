package mes.app.account_management.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class AccountNumberManagementService {

	@Autowired
	SqlRunner sqlRunner;

	public Object getAccountList(String bankid, String accountnum, String spjangcd) {
		MapSqlParameterSource param = new MapSqlParameterSource();

		param.addValue("spjangcd", spjangcd);
		param.addValue("bankid", bankid);
		param.addValue("accnum", "%" + accountnum + "%");

		String sql = """
			
			""";

		return sqlRunner.getRows(sql, param);
	}
}
