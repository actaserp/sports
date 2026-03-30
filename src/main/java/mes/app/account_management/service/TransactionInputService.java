package mes.app.account_management.service;

import mes.app.common.TenantContext;
import mes.app.util.UtilClass;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.thymeleaf.util.StringUtils;

import java.util.List;
import java.util.Map;

@Service("accountMgmtTransactionInputService")
public class TransactionInputService {

	@Autowired
	SqlRunner sqlRunner;
	public Object getAccountList(String spjangcd) {
		MapSqlParameterSource parameterSource = new MapSqlParameterSource();
		String tenantId = TenantContext.get();
		parameterSource.addValue("spjangcd", tenantId);

		String sql = """
			SELECT
				 NULL AS accountid,
				 b.banknm AS bankname,
				 b.bnkcode AS managementnum,
				 b.bankcd AS bankid,
				 a.accnum AS accountNumber,
				 a.banknm AS accountName,
				 null AS onlineBankId,
				 a.bnkid AS viewid,
				 a.bnkpw AS viewpw,
				 NULL AS paymentPw,
				 NULL AS birth,
				 CAST(0 AS bit) AS popyn,
				 CASE
						 WHEN a.spacc = '1' THEN '개인'
						 WHEN a.spacc = '0' THEN '법인'
						 ELSE NULL
				 END AS accountType
		 from tb_aa040 a
				 left join tb_xbank b on a.bank = b.bankcd
		 WHERE a.spjangcd = :spjangcd
		 ORDER BY a.accnum ASC
       """;

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, parameterSource);

		return items;
	}

	public List<Map<String, Object>> getTransactionHistory(Map<String, Object> param){

		MapSqlParameterSource parameterSource = new MapSqlParameterSource();

		String searchfrdate = UtilClass.getStringSafe(param.get("searchfrdate"));
		String searchtodate = UtilClass.getStringSafe(param.get("searchtodate"));
		Integer parsedAccountId = UtilClass.parseInteger(param.get("parsedAccountId"));
		Integer parsedCompanyId = UtilClass.parseInteger(param.get("parsedCompanyId"));
		String ioflag = UtilClass.getStringSafe(param.get("tradetype"));
		String cltflag = UtilClass.getStringSafe(param.get("cltflag"));
		String spjangcd = UtilClass.getStringSafe(param.get("spjangcd"));

		parameterSource.addValue("searchfrdate", searchfrdate);
		parameterSource.addValue("searchtodate", searchtodate);
		parameterSource.addValue("accid", parsedAccountId);
		parameterSource.addValue("ioflag", ioflag);
		parameterSource.addValue("cltcd", parsedCompanyId);
		parameterSource.addValue("cltflag", cltflag);
		parameterSource.addValue("spjangcd", spjangcd);

		String sql = """
			SELECT
					CONVERT(varchar(10), CONVERT(date, b.tran_date, 112), 23) AS trade_date,
					SUBSTRING(b.tran_time, 9, 2) + ':' + SUBSTRING(b.tran_time, 11, 2) AS transactionHour,
					CASE
							WHEN b.tran_type = '0' THEN N'입금'
							ELSE N'출금'
					END AS inoutFlag,
			--    b.ioid AS id,
					b.inout_type AS transactionTypeId,
					b.after_balance_amt as balance,
					b.tran_amt AS input_money,
					b.wdr_amt AS output_money,
					b.bank_tran_id AS tid,
					CASE
							WHEN b.bfeeamt IS NOT NULL THEN CAST(1 AS bit)
							ELSE CAST(0 AS bit)
					END AS commission, ---수수료
					b.bfeeamt AS feeamt,
					d.mijamt AS mijamt,
					b.remark1 AS remark,
					CASE
							WHEN b.tran_type = '0' THEN c.[Code]
							WHEN b.tran_type = '1' THEN p.[Code]
							WHEN b.tran_type = '2' THEN d2.accname
							WHEN b.tran_type = '3' THEN i.cardco
							ELSE NULL
					END AS code,
					CASE
							WHEN b.tran_type = '0' THEN c.[Name]
							WHEN b.tran_type = '1' THEN p.[Name]
							WHEN b.tran_type = '2' THEN d2.accnum
							WHEN b.tran_type = '3' THEN i.cardnum
							ELSE NULL
					END AS clientName,
					t.tradenm AS trade_type,
					b.banknm AS bankname,
					b.accnum AS account,
					s.[Value] AS depositAndWithdrawalType,
					s.[Code] AS iotype,
					b.cltcd AS cltcd,
					b.eumnum AS bill,
					b.etcremark AS etc,
					b.print_content AS memo,
					b.cltflag AS cltflag,
					b.bank_cd AS accountId,
					b.eumtodt AS expiration
			FROM tb_bank_accsave b
			--LEFT JOIN tb_trade t
			--    ON t.trid = b.trid
			LEFT JOIN sys_code s
					ON s.[Code] = b.tran_type
				 AND s.[CodeType] = 'deposit_type'
			LEFT JOIN tb_xclient c
					ON c.cltcd = b.cltcd
			LEFT JOIN tb_ja001 p
					ON p.perid = b.cltcd
			LEFT JOIN tb_aa040 d
					ON d.bankcd = b.bank_cd
			LEFT JOIN tb_aa040 d2
					ON d2.bankcd = b.cltcd
			LEFT JOIN tb_iz010 i
					ON i.cardnum = b.cltcd
			WHERE b.tran_date BETWEEN :searchfrdate AND :searchtodate
				AND b.spjangcd = :spjangcd
				 """;

		if(!StringUtils.isEmpty(ioflag)){
			sql += """
                    AND b.ioflag = :ioflag
                    """;
		}

		if(parsedAccountId != null){
			sql += """
                    AND b.accid = :accid
                    """;
		}

		if(parsedCompanyId != null){
			sql += """
                    AND b.cltcd = :cltcd
                    AND b.cltflag = :cltflag
                    """;
		}

		sql += """
                ORDER BY trdt asc
                """;

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, parameterSource);

		return items;
	}
}
