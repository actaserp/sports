package mes.app.account_management.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AccountNumberManagementService {

	@Autowired
	SqlRunner sqlRunner;

	public List<Map<String, Object>> getAccountList(String bankid, String accnum, String spjangcd) {
		MapSqlParameterSource param = new MapSqlParameterSource();

		param.addValue("spjangcd", spjangcd);

		String sql = """
			    select
			         a.bank,
			         b.banknm as bankname,
			         a.bankcd,
			         a.accnum as accountNumber,
			         a.banknm as accountName,	
			         a.bnkpaypw as accountPw,
			         a.spacc as accountType
			    from tb_aa040 a
			    left join tb_xbank b on a.bank = b.bankcd
			    where 1=1
			      and a.spjangcd = :spjangcd
			      and a.useyn = '1'
			""";

		if (bankid != null && !bankid.trim().isEmpty()) {
			sql += """
				    and a.bank = :bankid
				""";
			param.addValue("bankid", bankid.trim());
		}

		if (accnum != null && !accnum.trim().isEmpty()) {
			sql += """
        and replace(a.accnum, '-', '') like :accnum
    """;

			param.addValue("accnum", "%" + accnum.trim().replace("-", "") + "%");
		}

		sql += """
			ORDER BY a.contdate DESC
			""";
//		log.info("read SQL:{}", sql);
//		log.info("SQL Parameters: {}", param.getValues());
		return sqlRunner.getRows(sql, param);
	}
}
