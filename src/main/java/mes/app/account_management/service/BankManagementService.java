package mes.app.account_management.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.model.AjaxResult;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class BankManagementService {

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


	@Transactional
	public AjaxResult saveBankAccount(Map<String, Object> param, Authentication auth) {

		AjaxResult result = new AjaxResult();

		try {
			String bankid = (String) param.get("bankid");
			String accountNumber = (String) param.get("accountNumber");
			String accountPw = (String) param.get("accountPw");
			String accountName = (String) param.get("accountName");//계좌명칭
			String accountType = (String) param.get("accountType");
			String onlineid = (String) param.get("onlineid");//인터넷뱅킹id
			String viewid = (String) param.get("viewid");	//
			String viewpw = (String) param.get("viewpw");
			String mijamt = (String) param.get("mijamt");	//수수료
			String spjangcd = (String) param.get("spjangcd");	//사업장코드

			String custcd = getCustcdBySpjangcd(spjangcd);

			if (custcd.isEmpty()) {
				result.success = false;
				result.message = "거래처코드가 없습니다.";
				return result;
			}

			if (bankid.isEmpty()) {
				result.success = false;
				result.message = "은행코드가 없습니다.";
				return result;
			}

			if (accountNumber.isEmpty()) {
				result.success = false;
				result.message = "계좌번호가 없습니다.";
				return result;
			}

			MapSqlParameterSource dicParam = new MapSqlParameterSource();
			dicParam.addValue("custcd", custcd);
			dicParam.addValue("bank", bankid);
			dicParam.addValue("bankcd", bankid);
			dicParam.addValue("spjangcd", spjangcd);
			dicParam.addValue("accnum", accountNumber);
			dicParam.addValue("accname", accountName);
			dicParam.addValue("bnkpaypw", accountPw);
			dicParam.addValue("bnkid", onlineid);
			dicParam.addValue("cmsid", viewid);
			dicParam.addValue("cmspw", viewpw);
			dicParam.addValue("acccd", accountType);

// 수수료
			double intrate = 0.0;
			if (!mijamt.isEmpty()) {
				try {
					intrate = Double.parseDouble(mijamt);
				} catch (NumberFormatException e) {
					result.success = false;
					result.message = "지급수수료 형식이 올바르지 않습니다.";
					return result;
				}
			}
			dicParam.addValue("intrate", intrate);

			String checkSql = """
    SELECT COUNT(*) AS cnt
    FROM tb_aa040
    WHERE custcd = :custcd
      AND bank   = :bank
      AND bankcd = :bankcd
    """;

			List<Map<String, Object>> checkResult = this.sqlRunner.getRows(checkSql, dicParam);
			int count = ((Number) checkResult.get(0).get("cnt")).intValue();

			if (count > 0) {
				String updateSql = """
        UPDATE tb_aa040
        SET
            spjangcd  = :spjangcd,
            accnum    = :accnum,
            accname   = :accname,
            bnkpaypw  = :bnkpaypw,
            bnkid     = :bnkid,
            cmsid     = :cmsid,
            cmspw     = :cmspw,
            acccd     = :acccd,
            intrate   = :intrate
        WHERE custcd = :custcd
          AND bank   = :bank
          AND bankcd = :bankcd
        """;
				this.sqlRunner.execute(updateSql, dicParam);
			} else {
				dicParam.addValue("useyn", "1");

				String insertSql = """
        INSERT INTO tb_aa040 (
            custcd, bank, bankcd,
            spjangcd, accnum, accname,
            bnkpaypw, bnkid, cmsid, cmspw,
            acccd, intrate, useyn
        ) VALUES (
            :custcd, :bank, :bankcd,
            :spjangcd, :accnum, :accname,
            :bnkpaypw, :bnkid, :cmsid, :cmspw,
            :acccd, :intrate, :useyn
        )
        """;
				this.sqlRunner.execute(insertSql, dicParam);
			}

			result.success = true;
			result.message = "저장되었습니다.";
			return result;

		} catch (Exception e) {
			log.error("saveBankAccount error", e);
			result.success = false;
			result.message = "저장 중 오류가 발생했습니다.";
			return result;
		}
	}

	private String getCustcdBySpjangcd(String spjangcd) {
		MapSqlParameterSource sqlParam = new MapSqlParameterSource();
		sqlParam.addValue("spjangcd", spjangcd);

		String sql = """
        select custcd
        from tb_xa012
        where spjangcd = :spjangcd
    """;

		Map<String, Object> row = sqlRunner.getRow(sql, sqlParam);

		if (row == null || row.isEmpty()) {
			return "";
		}

		Object custcd = row.get("custcd");
		return custcd == null ? "" : String.valueOf(custcd).trim();
	}

}
