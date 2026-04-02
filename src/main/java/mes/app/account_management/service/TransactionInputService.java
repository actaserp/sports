package mes.app.account_management.service;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import mes.app.util.UtilClass;
import mes.domain.dto.BankAccsaveRequestDto;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service("accountMgmtTransactionInputService")
public class TransactionInputService {

	@Autowired
	SqlRunner sqlRunner;

	public Object getAccountList(String spjangcd) {
		MapSqlParameterSource parameterSource = new MapSqlParameterSource();
		String tenantId = TenantContext.get();
		parameterSource.addValue("spjangcd", tenantId);

		String sql = """
			SELECT DISTINCT 
				 a.bankcd AS accountid,
				 b.banknm AS bankname,
				 b.bankcd AS managementnum,
				 b.bankcd AS bank,
				 a.accnum AS accountnumber,
				 a.banknm AS accountname,
				 null AS onlinebankid,
				 a.bnkid AS viewid,
				 a.bnkpw AS viewpw,
				 a.bnkpaypw AS paymentpw,
				 a.accbirthday AS birth,
				 CASE
							WHEN a.bnkflag = '1' THEN CAST(1 AS bit)
							ELSE CAST(0 AS bit)
					END AS popyn,
				 CASE
						 WHEN a.spacc = '1' THEN '개인'
						 WHEN a.spacc = '0' THEN '법인'
						 ELSE NULL
				 END AS accounttype
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
		String parsedAccountId = UtilClass.getStringSafe(param.get("parsedAccountId"));
		Integer parsedCompanyId = UtilClass.parseInteger(param.get("parsedCompanyId"));
		String ioflag = UtilClass.getStringSafe(param.get("tradetype"));
		String cltflag = UtilClass.getStringSafe(param.get("cltflag"));
		String spjangcd = TenantContext.get();

		parameterSource.addValue("searchfrdate", searchfrdate);
		parameterSource.addValue("searchtodate", searchtodate);
		parameterSource.addValue("accountId", parsedAccountId);
		parameterSource.addValue("ioflag", ioflag);
		parameterSource.addValue("cltcd", parsedCompanyId);
		parameterSource.addValue("cltflag", cltflag);
		parameterSource.addValue("spjangcd", spjangcd);

		String sql = """
        SELECT
            b.custcd,
            b.spjangcd,
            b.bnkcode,
            b.fintech_use_num,
            b.bank_tran_id AS tid,

            CONVERT(varchar(10), CONVERT(date, b.tran_date, 112), 23) AS trade_date,

            CASE
                WHEN LEN(ISNULL(b.tran_time, '')) = 6
                    THEN SUBSTRING(b.tran_time, 1, 2) + ':' + SUBSTRING(b.tran_time, 3, 2)
                ELSE ''
            END AS transactionHour,

            CASE
                WHEN b.inout_type = '0' THEN N'입금'
                WHEN b.inout_type = '1' THEN N'출금'
                ELSE ''
            END AS [inoutFlag],

            b.after_balance_amt AS balance,
            b.tran_amt AS input_money,
            b.wdr_amt AS output_money,
            b.bank_tran_id AS tid,

            CASE
                WHEN ISNULL(b.bfeeamt, 0) > 0 THEN CAST(1 AS bit)
                ELSE CAST(0 AS bit)
            END AS commission,

            b.bfeeamt AS feeamt,
            b.remark1 AS remark,

            CASE
                WHEN b.flag = '0' THEN c.cltcd
                WHEN b.flag = '1' THEN p.perid
                WHEN b.flag = '2' THEN d2.accnum
                WHEN b.flag = '3' THEN i.cardnum
                ELSE NULL
            END AS code,

            CASE
                WHEN b.flag = '0' THEN c.cltnm
                WHEN b.flag = '1' THEN p.pernm
                WHEN b.flag = '2' THEN d2.banknm
                WHEN b.flag = '3' THEN i.cardnm
                ELSE NULL
            END AS clientName,

            b.tradecd AS trade_type,
            b.bank_nm AS bankname,
            b.accnum AS account,
            s.[Value] AS depositandwithdrawaltype,
            s.[Code] AS iotype,
            b.cltcd AS cltcd,
            b.eumnum AS bill,
            b.etcremark AS etc,
            b.print_content AS memo,
            b.flag AS cltflag,
            a.bankcd AS accountId,
            b.eumtodt AS expiration,
            a.accbirthday AS birth

        FROM tb_bank_accsave b
        LEFT JOIN sys_code s
               ON s.[Code] = b.tran_type
              AND s.[CodeType] = 'deposit_type'
        LEFT JOIN tb_xclient c
               ON c.cltcd = b.cltcd
        LEFT JOIN tb_ja001 p
               ON p.perid = b.cltcd
        LEFT JOIN tb_aa040 a
               ON a.spjangcd = b.spjangcd
              AND a.accnum = b.accnum
        LEFT JOIN tb_aa040 d2
               ON d2.bankcd = b.cltcd
        LEFT JOIN tb_iz010 i
               ON i.cardnum = b.cltcd
        WHERE b.tran_date BETWEEN :searchfrdate AND :searchtodate
          AND b.spjangcd = :spjangcd
    """;

		if(!StringUtils.isEmpty(ioflag)){
			sql += """
            AND b.inout_type = :ioflag
        """;
		}

		if(!StringUtils.isEmpty(parsedAccountId)){
			sql += """
            AND a.bankcd = :accountId
        """;
		}

		if(parsedCompanyId != null){
			sql += """
            AND b.cltcd = :cltcd
            AND b.flag = :cltflag
        """;
		}

		sql += """
        ORDER BY b.tran_date ASC, b.tran_time ASC
    """;

		return this.sqlRunner.getRows(sql, parameterSource);
	}

	@Transactional
	public void editAccountList(List<Map<String, Object>> list) {

		String checkSql = """
        SELECT COUNT(*)
        FROM tb_aa040
        WHERE bankcd = :accountid
          AND accnum = :accountnumber
        """;

		String sql = """
        UPDATE tb_aa040
           SET banknm      = :accountname,
               bnkid       = :viewid,
               bnkpw       = :viewpw,
               bnkpaypw    = :paymentpw,
               accbirthday = :birth,
               bnkflag     = :popyn,
               spacc       = :accounttype
         WHERE bankcd      = :accountid
           AND accnum      = :accountnumber
        """;

		log.info("계좌 수정 시작 - 대상 건수: {}", list != null ? list.size() : 0);

		for (Map<String, Object> item : list) {
			MapSqlParameterSource param = new MapSqlParameterSource();

			String accountid = getString(item, "accountid");
			String accountnumber = getString(item, "accountnumber");
			String accountname = getString(item, "accountname");
			String viewid = getString(item, "viewid");
			String viewpw = getString(item, "viewpw");
			String paymentpw = getString(item, "paymentpw");
			String birth = getString(item, "birth");
			String popyn = toFlag(item.get("popyn"));
			String accounttype = toSpacc(item.get("accounttype"));

			param.addValue("accountid", accountid);
			param.addValue("accountnumber", accountnumber);
			param.addValue("accountname", accountname);
			param.addValue("viewid", viewid);
			param.addValue("viewpw", viewpw);
			param.addValue("paymentpw", paymentpw);
			param.addValue("birth", birth);
			param.addValue("popyn", popyn);
			param.addValue("accounttype", accounttype);

			log.info("계좌 수정 시도 - accountid={}, accountnumber={}", accountid, accountnumber);
			log.info("변경값 - accountname={}, viewid={}, viewpw={}, paymentpw={}, birth={}, popyn={}, accounttype={}",
				accountname, viewid, viewpw, paymentpw, birth, popyn, accounttype);

			int existCnt = sqlRunner.queryForCount(checkSql, param);
			log.info("수정 전 조회 건수 - accountid={}, accountnumber={}, existCnt={}",
				accountid, accountnumber, existCnt);

			int cnt = sqlRunner.execute(sql, param);
			log.info("계좌 수정 결과 - accountid={}, accountnumber={}, updateCount={}",
				accountid, accountnumber, cnt);
		}

		log.info("계좌 수정 종료");
	}

	@Transactional
	public void saveBankAccsave(BankAccsaveRequestDto dto) {

		String spjangcd = TenantContext.get();

		Map<String, String> bizInfo = getBizInfoBySpjangcd(spjangcd);
		String custcd = bizInfo.get("custcd");

		if (isBlank(custcd)) {
			throw new IllegalArgumentException("사업장 정보가 없습니다.");
		}

		BigDecimal money = toBigDecimal(dto.getMoney());
		BigDecimal commission = toBigDecimal(dto.getCommission());

		// 프론트 : 0=입금, 1=출금
		// DB     : 0=입금, 1=출금
		String inoutType = "0".equals(dto.getInoutFlag()) ? "0" : "1";

		BigDecimal tranAmt = BigDecimal.ZERO;
		BigDecimal wdrAmt = BigDecimal.ZERO;

		if ("1".equals(inoutType)) {
			tranAmt = money;
		} else {
			wdrAmt = money;
		}

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("custcd", custcd);
		param.addValue("spjangcd", spjangcd);
		param.addValue("bnkcode", (dto.getAccountId()));              // accountId -> bnkcode
		param.addValue("fintech_use_num", (dto.getAccountNumber()));  // accountNumber -> fintech_use_num
//		param.addValue("bank_tran_id", "");

		param.addValue("tran_date", removeDash(dto.getTransactionDate()));
		param.addValue("tran_time", removeColon(dto.getTransactionHour()));
		param.addValue("inout_type", inoutType);   // inoutFlag -> inout_type
		param.addValue("tran_type", (dto.getDepositAndWithdrawalType()));
		param.addValue("print_content", (dto.getMemo()));

		param.addValue("tran_amt", tranAmt);
		param.addValue("wdr_amt", wdrAmt);

		param.addValue("bfeeamt", commission);  // commission -> bfeeamt
		param.addValue("bfee", commission.compareTo(BigDecimal.ZERO) > 0 ? "1" : "0");

		param.addValue("bank_nm", (dto.getBankName()));
		param.addValue("accnum", (dto.getAccountNumber()));

		param.addValue("cltcd", (dto.getClientId()));
		param.addValue("flag", (dto.getClientFlag()));
		param.addValue("dipflag", (dto.getTransactionTypeId()));

		param.addValue("subject", (dto.getNote1()));	//적요
		param.addValue("etcremark", (dto.getEtc()));
		param.addValue("eumnum", (dto.getBill()));
		param.addValue("eumtodt", removeDash(dto.getExpiration()));

		param.addValue("trn_dv", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));

		String sql = """
        INSERT INTO TB_bank_accsave (
            custcd,
            spjangcd,
            bnkcode,
            fintech_use_num,
            tran_date,
            tran_time,
            inout_type,
            tran_type,
            print_content,
            tran_amt,
            wdr_amt,
            bfeeamt,
            bank_nm,
            trn_dv,
            cltcd,
            flag,
            bfee,
            subject,
            accnum,
            eumnum,
            eumtodt,
            etcremark
        ) VALUES (
            :custcd,
            :spjangcd,
            :bnkcode,
            :fintech_use_num,
            :tran_date,
            :tran_time,
            :inout_type,
            :tran_type,
            :print_content,
            :tran_amt,
            :wdr_amt,
            :bfeeamt,
            :bank_nm,
            :trn_dv,
            :cltcd,
            :flag,
            :bfee,
            :subject,
            :accnum,
            :eumnum,
            :eumtodt,
            :etcremark
        )
    """;

		int row = sqlRunner.execute(sql, param);

		if (row <= 0) {
			throw new RuntimeException("저장에 실패했습니다.");
		}
	}

	@Transactional
	public void deleteBankAccsave(String custcd, String spjangcd, String bnkcode, String fintechUseNum) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("custcd", custcd);
		param.addValue("spjangcd", spjangcd);
		param.addValue("bnkcode", bnkcode);
		param.addValue("fintechUseNum", fintechUseNum);

		String sql = """
        DELETE FROM TB_bank_accsave
        WHERE custcd = :custcd
          AND spjangcd = :spjangcd
          AND bnkcode = :bnkcode
          AND fintech_use_num = :fintechUseNum
    """;

		sqlRunner.execute(sql, param);
	}

	/*유틸*/
	private Map<String, String> getBizInfoBySpjangcd(String spjangcd) {
		MapSqlParameterSource sqlParam = new MapSqlParameterSource();
		sqlParam.addValue("spjangcd", spjangcd);

		String sql = """
        SELECT saupnum, custcd, spjangnm
        FROM tb_xa012
        WHERE spjangcd = :spjangcd
    """;

		Map<String, Object> row = sqlRunner.getRow(sql, sqlParam);

		Map<String, String> result = new HashMap<>();
		result.put("saupnum", "");
		result.put("custcd", "");
		result.put("spjangnm", "");

		if (row == null || row.isEmpty()) {
			return result;
		}

		result.put("saupnum", row.get("saupnum") == null ? "" : String.valueOf(row.get("saupnum")).trim());
		result.put("custcd", row.get("custcd") == null ? "" : String.valueOf(row.get("custcd")).trim());
		result.put("spjangnm", row.get("spjangnm") == null ? "" : String.valueOf(row.get("spjangnm")).trim());

		return result;
	}

	private String removeDash(String value) {
		return value == null ? "" : value.replace("-", "");
	}

	private BigDecimal toBigDecimal(String value) {
		if (value == null || value.trim().isEmpty()) {
			return BigDecimal.ZERO;
		}
		return new BigDecimal(value.replace(",", "").trim());
	}

	private boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}

	private String removeColon(String value) {
		return value == null ? "" : value.replace(":", "");
	}

	private String getString(Map<String, Object> item, String key) {
		Object val = item.get(key);
		return val != null ? val.toString() : null;
	}

	private String toFlag(Object value) {
		if (value == null) return "0";

		if (value instanceof Boolean) {
			return (Boolean) value ? "1" : "0";
		}

		String str = value.toString();
		if ("true".equalsIgnoreCase(str) || "1".equals(str)) {
			return "1";
		}
		return "0";
	}

	private String toSpacc(Object value) {
		if (value == null) return null;

		String str = value.toString();

		if ("개인".equals(str)) return "1";
		if ("법인".equals(str)) return "0";

		if ("1".equals(str) || "0".equals(str)) return str;

		return null;
	}

}
