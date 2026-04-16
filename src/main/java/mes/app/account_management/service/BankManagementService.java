package mes.app.account_management.service;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
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

	public List<Map<String, Object>> getAccountList(String bankid, String accnum) {
		MapSqlParameterSource param = new MapSqlParameterSource();
		String spjangcd = TenantContext.get();
		param.addValue("spjangcd", spjangcd);

		String sql = """
			     SELECT
							 a.bank,
							 a.bankcd,
							 b.banknm     AS bankname,
							 a.accnum     AS accountNumber,
							 a.accname    AS accountName,
							 a.bnkid      AS onlineid,
							 a.cmsid      AS viewid,
							 a.cmspw      AS viewpw,
							 a.bnkpaypw   AS accountPw,
							 a.popsort    AS accountType,
							 a.intrate    AS mijamt,
							 a.popflag    AS popyn,
							 a.accbirthday,
							 a.popuserid, 
							 a.spacc as accountType
					 FROM tb_aa040 a
					 LEFT JOIN tb_xbank b ON a.bank = b.bankcd
					 WHERE a.spjangcd = :spjangcd
						 AND a.useyn = '1'
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
			String bankid         = (String) param.get("bankid");
			String bankcd         = (String) param.get("bankcd");       // 추가
			String accountNumber  = (String) param.get("accountNumber");
			String accountPw      = (String) param.get("accountPw");
			String accountName    = (String) param.get("accountName");
			String accountType    = (String) param.get("accountType");
			String onlineid       = (String) param.get("onlineid");
			String viewid         = (String) param.get("viewid");
			String viewpw         = (String) param.get("viewpw");
			String mijamt         = (String) param.get("mijamt");
			String popuserid      = (String) param.get("popuserid");
			String spjangcd       = TenantContext.get();               // 세션에서 가져오기

			String custcd = getCustcdBySpjangcd(spjangcd);

			if (custcd.isEmpty())       { result.success = false; result.message = "회사코드가 없습니다.";  return result; }
			if (bankid.isEmpty())       { result.success = false; result.message = "은행코드가 없습니다.";  return result; }
			if (bankcd.isEmpty())       { result.success = false; result.message = "계좌 체번이 없습니다."; return result; }
			if (accountNumber.isEmpty()){ result.success = false; result.message = "계좌번호가 없습니다.";  return result; }

			// 수수료
			double intrate = 0.0;
			if (mijamt != null && !mijamt.isEmpty()) {
				try {
					intrate = Double.parseDouble(mijamt);
				} catch (NumberFormatException e) {
					result.success = false;
					result.message = "지급수수료 형식이 올바르지 않습니다.";
					return result;
				}
			}

			MapSqlParameterSource dicParam = new MapSqlParameterSource();
			dicParam.addValue("custcd",   custcd);
			dicParam.addValue("bank",     bankid);   // 은행코드 2자리
			dicParam.addValue("bankcd",   bankcd);   // ERP 체번
			dicParam.addValue("spjangcd", spjangcd);
			dicParam.addValue("accnum",   accountNumber);
			dicParam.addValue("accname",  accountName);
			dicParam.addValue("bnkpaypw", accountPw);
			dicParam.addValue("bnkid",    onlineid);
			dicParam.addValue("cmsid",    viewid);
			dicParam.addValue("cmspw",    viewpw);
			dicParam.addValue("acccd",    accountType);
			dicParam.addValue("intrate",  intrate);
			dicParam.addValue("popuserid",  popuserid);

			String updateSql = """
            UPDATE tb_aa040
            SET
                accnum   = :accnum,
                accname  = :accname,
                bnkpaypw = :bnkpaypw,
                bnkid    = :bnkid,
                cmsid    = :cmsid,
                cmspw    = :cmspw,
                acccd    = :acccd,
                intrate  = :intrate,
                popuserid = :popuserid
            WHERE custcd = :custcd
              AND bank   = :bank
              AND bankcd = :bankcd
            """;

			int updated = this.sqlRunner.execute(updateSql, dicParam);

			if (updated == 0) {
				result.success = false;
				result.message = "해당 계좌를 찾을 수 없습니다.";
				return result;
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
