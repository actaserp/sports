package mes.app.account_management.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import mes.app.common.TenantContext;
import mes.domain.model.AjaxResult;
import mes.domain.services.SqlRunner;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class BankAssignmentService {

	@Autowired
	SqlRunner sqlRunner;

	@Autowired
	private ObjectMapper objectMapper;

	public List<Map<String, Object>> getBankHistoryList(String start, String end, String cboCompanyHidden,
																											String cltflag, String accnum, String accountNameHidden,
																											String accflag) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		String spjangcd = TenantContext.get();

		Map<String, String> bizInfo = getBizInfoBySpjangcd(spjangcd);

		String custcd = bizInfo.get("custcd");
		String corpNum = bizInfo.get("saupnum").replace("-", "").trim();

		param.addValue("custcd", custcd);
		param.addValue("saupnum", corpNum);
		param.addValue("spjangcd", spjangcd);
		param.addValue("start", start);
		param.addValue("end", end);
		param.addValue("cboCompanyHidden", cboCompanyHidden);
		param.addValue("cltflag", cltflag);
		param.addValue("accnum", accnum);
		param.addValue("accflag", accflag);

		String sql = """
			select
					 b.custcd,
					 b.spjangcd,
					 b.bnkcode,
					 b.fintech_use_num,
					 b.bank_tran_id,
					 STUFF(STUFF(b.tran_date,5,0,'-'),8,0,'-') as tran_date,
					 CASE
							 WHEN b.inout_type = '0' THEN N'입금'
							 WHEN b.inout_type = '1' THEN N'출금'
							 ELSE ''
					 END AS inout_type,
					 b.tran_amt,
					 b.wdr_amt,
					 b.cltcd,
					 b.acccd,
					 ac.accnm,
					 b.it1cd,
					 it1.it1nm,
					 b.it2cd,
					 it2.it2nm,
					 b.subject,
					 b.bsdate,
					 b.bseccd,
					 b.buiscd,
					 b.busim,
					 b.mssec, 
					 b.cltcd,
					 b.contra_acccd as acccd2,
					 ac2.accnm as accnm2,
					 STUFF(STUFF(b.acc_spdate,5,0,'-'),8,0,'-') as acc_spdate,
					 b.acc_spnum,
					 CASE
							 WHEN ISNULL(NULLIF(b.acc_spdate, ''), '') <> ''
								AND ISNULL(NULLIF(b.acc_spnum, ''), '') <> ''
							 THEN CONCAT(STUFF(STUFF(b.acc_spdate,5,0,'-'),8,0,'-'), '/', b.acc_spnum)
							 ELSE ''
					 END as acc_spdate_num,
					 b.summy
			 from TB_bank_accsave b
			 outer apply (
					 select top 1 ac.accnm
					 from tb_ac001 ac
					 where ac.custcd = b.custcd
						 and ac.acccd = b.acccd
			 ) ac
			 outer apply (
					 select top 1 ac2.accnm
					 from tb_ac001 ac2
					 where ac2.custcd = b.custcd
						 and ac2.acccd = b.contra_acccd
			 ) ac2
			 outer apply (
					 select top 1 it1.it1nm
					 from tb_x0003 it1
					 where it1.custcd = b.custcd
						 and it1.it1cd = b.it1cd
			 ) it1
			 outer apply (
					 select top 1 it2.it2nm
					 from tb_x0004 it2
					 where it2.custcd = b.custcd
						 and it2.it2cd = b.it2cd
			 ) it2
			 where 1=1
				 and b.spjangcd = :spjangcd
				 and b.custcd = :custcd
				 and b.tran_date between :start and :end
			""";

		if (accnum != null && !accnum.trim().isEmpty()) {
			sql += """
        and replace(b.accnum, '-', '') like :accnum
    """;

			param.addValue("accnum", "%" + accnum.trim().replace("-", "") + "%");
		}

		if (cboCompanyHidden != null && !cboCompanyHidden.trim().isEmpty()) {
			sql += """
        and b.cltcd like :cboCompanyHidden
    """;

			param.addValue("cboCompanyHidden", "%" + cboCompanyHidden.trim() + "%");
		}

		if (accflag != null && !accflag.trim().isEmpty()) {
			sql += """
        and CASE
              WHEN ISNULL(NULLIF(b.acc_spdate, ''), '') <> ''
               AND ISNULL(NULLIF(b.acc_spnum, ''), '') <> ''
              THEN '1'
              ELSE '0'
            END = :accflag
    """;
			param.addValue("accflag", accflag.trim());
		}

		return sqlRunner.getRows(sql, param);
	}

	@Transactional
	public AjaxResult updateSelected(String items) {

		AjaxResult result = new AjaxResult();

		try {
			if (items == null || items.trim().isEmpty()) {
				result.success = false;
				result.message = "저장할 데이터가 없습니다.";
				return result;
			}

			List<Map<String, Object>> itemList = objectMapper.readValue(
				items, new TypeReference<List<Map<String, Object>>>() {}
			);

			if (itemList == null || itemList.isEmpty()) {
				result.success = false;
				result.message = "선택된 데이터가 없습니다.";
				return result;
			}

			// 필수값 검증
			for (Map<String, Object> item : itemList) {
				String busim = getString(item, "busim");   // 사업명
				String accnm = getString(item, "accnm");   // 관
				String it1nm = getString(item, "it1nm");   // 항
				String it2nm = getString(item, "it2nm");   // 목
				String mssec = getString(item, "mssec");   // 재원

				if (isBlank(busim) || isBlank(accnm) || isBlank(it1nm) || isBlank(it2nm) || isBlank(mssec)) {
					result.success = false;
					result.message = "필수값(사업명, 관명, 항, 목, 재원)이 없는 데이터가 있습니다.";
					return result;
				}
			}

			String spjangcd = TenantContext.get();
			Map<String, String> bizInfo = getBizInfoBySpjangcd(spjangcd);
			String custcd = bizInfo.get("custcd");

			// 각 항목별로 업데이트
			for (Map<String, Object> item : itemList) {

				MapSqlParameterSource param = new MapSqlParameterSource();

				param.addValue("custcd", custcd);
				param.addValue("spjangcd", spjangcd);
				param.addValue("bnkcode", getString(item, "bnkcode"));   // 있으면 반드시 넣는 게 좋음
				param.addValue("fintech_use_num", getString(item, "fintech_use_num"));
				param.addValue("bank_tran_id", getString(item, "bank_tran_id"));
				param.addValue("tran_date", getString(item, "tran_date"));

				param.addValue("subject", getString(item, "subject"));
				param.addValue("acccd", getString(item, "acccd"));
				param.addValue("it1cd", getString(item, "it1cd"));
				param.addValue("it2cd", getString(item, "it2cd"));
				param.addValue("summy", getString(item, "summy"));
				param.addValue("mssec", getString(item, "mssec"));
				param.addValue("bsdate", getString(item, "bsdate"));
				param.addValue("bseccd", getString(item, "bseccd"));
				param.addValue("buiscd", getString(item, "buiscd"));
				param.addValue("busim", getString(item, "busim"));
				param.addValue("contra_acccd", getString(item, "acccd2"));

				String sql = """
        UPDATE TB_bank_accsave
           SET subject       = :subject,
               acccd         = :acccd,
               it1cd         = :it1cd,
               it2cd         = :it2cd,
               summy         = :summy,
               mssec         = :mssec,
               bsdate        = :bsdate,
               bseccd        = :bseccd,
               buiscd        = :buiscd,
               busim         = :busim,
               contra_acccd  = :contra_acccd
         WHERE custcd          = :custcd
           AND spjangcd        = :spjangcd
           AND bnkcode         = :bnkcode
           AND fintech_use_num = :fintech_use_num
           AND bank_tran_id    = :bank_tran_id
           AND tran_date       = :tran_date
    		""";

				sqlRunner.execute(sql, param);
			}

			result.success = true;
			result.message = "저장되었습니다.";
			return result;

		} catch (Exception e) {
			e.printStackTrace();
			result.success = false;
			result.message = "저장 중 오류가 발생했습니다. " + e.getMessage();
			return result;
		}
	}

	// ============================================
// 개별전표: 각 항목마다 별도 전표번호 생성
// ============================================
	@Transactional
	public AjaxResult createSlipIndividual(String items, String userId) {

		AjaxResult result = new AjaxResult();

		try {
			if (items == null || items.trim().isEmpty()) {
				result.success = false;
				result.message = "전표 데이터가 없습니다.";
				return result;
			}

			List<Map<String, Object>> itemList = objectMapper.readValue(
				items, new TypeReference<List<Map<String, Object>>>() {}
			);

			if (itemList == null || itemList.isEmpty()) {
				result.success = false;
				result.message = "선택된 데이터가 없습니다.";
				return result;
			}

			// 필수값 검증
			for (Map<String, Object> item : itemList) {
				String busim  = getString(item, "busim");
				String acccd  = getString(item, "acccd");
				String acccd2 = getString(item, "acccd2");
				String it1cd  = getString(item, "it1cd");
				String it2cd  = getString(item, "it2cd");
				String summy  = getString(item, "summy");

				if (isBlank(busim) || isBlank(acccd) || isBlank(acccd2) || isBlank(it1cd) || isBlank(it2cd) || isBlank(summy)) {
					result.success = false;
					result.message = "필수값(사업명, 관명, 상대계정명, 항, 목, 산출내역)이 없는 데이터가 있습니다.";
					return result;
				}
			}

			String spjangcd = TenantContext.get();
			Map<String, String> bizInfo = getBizInfoBySpjangcd(spjangcd);
			String custcd   = bizInfo.get("custcd");
			String spjangnm = bizInfo.get("spjangnm");
			String spdate   = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

			String aa009Sql = """
        INSERT INTO tb_aa009 (
            custcd, spjangcd, spdate, spnum,
            tiosec, busipur, spoccu, cashyn,
            subject, spjangnm, inputdate, inputid,
            bsdate, bseccd, buiscd
        ) VALUES (
            :custcd, :spjangcd, :spdate, :spnum,
            :tiosec, :busipur, :spoccu, :cashyn,
            :subject, :spjangnm, :inputdate, :inputid,
            :bsdate, :bseccd, :buiscd
        )
        """;

			String aa010Sql = """
        INSERT INTO tb_aa010 (
            custcd, spjangcd, spdate, spnum, spseq,
            spjangnm, bumuncd, acccd, accnm,
            it1cd, it2cd,
            drcr, dramt, cramt,
            tiosec, summy, spoccu,
            bankcd, inputdate, rowseq,
            cltcd
        ) VALUES (
            :custcd, :spjangcd, :spdate, :spnum, :spseq,
            :spjangnm, :bumuncd, :acccd, :accnm,
            :it1cd, :it2cd,
            :drcr, :dramt, :cramt,
            :tiosec, :summy, :spoccu,
            :bankcd, :inputdate, :rowseq,
            :cltcd
        )
        """;

			for (Map<String, Object> item : itemList) {

				String spnum     = String.format("%04d", getNextSpnumInt());
				String inoutType = getString(item, "inout_type");

				// ========================
				// 헤더 INSERT (tb_aa009)
				// ========================
				MapSqlParameterSource headerParams = new MapSqlParameterSource();
				headerParams.addValue("custcd",    custcd);
				headerParams.addValue("spjangcd",  spjangcd);
				headerParams.addValue("spdate",    spdate);
				headerParams.addValue("spnum",     spnum);
				headerParams.addValue("tiosec",    inoutType.equals("입금") ? "0" : "1");
				headerParams.addValue("busipur",   "3");
				headerParams.addValue("spoccu",    "AA");
				headerParams.addValue("cashyn",    "0");
				headerParams.addValue("subject",   getString(item, "subject"));
				headerParams.addValue("spjangnm",  spjangnm);
				headerParams.addValue("inputdate", LocalDateTime.now());
				headerParams.addValue("inputid",   userId);
				headerParams.addValue("cltcd",     getString(item, "cltcd"));
				headerParams.addValue("bsdate",    getString(item, "bsdate"));
				headerParams.addValue("bseccd",    getString(item, "bseccd"));
				headerParams.addValue("buiscd",    getString(item, "buiscd"));

				sqlRunner.execute(aa009Sql, headerParams);

				// 상세 공통 변수
				BigDecimal tranAmt   = getBigDecimal(item, "tran_amt");
				String summy         = getString(item, "summy");
				String bankTranId    = getString(item, "bank_tran_id");
				String fintechUseNum = getString(item, "fintech_use_num");
				String bnkcode       = getString(item, "bnkcode");
				String acccd         = getString(item, "acccd");
				String accnm         = getString(item, "accnm");
				String acccd2        = getString(item, "acccd2");
				String accnm2        = getString(item, "accnm2");
				String it1cd         = StringUtils.leftPad(getString(item, "it1cd"), 5, "0");
				String it2cd         = getString(item, "it2cd");
				String cltcd = getString(item, "cltcd");

				if (inoutType.equals("입금")) {
					// ============================
					// 입금 - 차변: 보통예금 (acccd2)
					// ============================
					sqlRunner.execute(aa010Sql, buildDetailParams(
						custcd, spjangcd, spdate, spnum, spjangnm,
						it1cd, it2cd, summy,
						String.format("%04d", 1),
						acccd2, accnm2,      // 보통예금
						"1",                 // 차변
						tranAmt, BigDecimal.ZERO,
						"1",                 // 세입
						bnkcode,             // bankcd
						1,
						cltcd
					));

					// 입금 - 대변: 수입계정 (acccd)
					sqlRunner.execute(aa010Sql, buildDetailParams(
						custcd, spjangcd, spdate, spnum, spjangnm,
						it1cd, it2cd, summy,
						String.format("%04d", 2),
						acccd, accnm,        // 수입계정
						"2",                 // 대변
						BigDecimal.ZERO, tranAmt,
						"1",                 // 세입
						null,                // bankcd 없음
						2,
						cltcd
					));

				} else {
					// ============================
					// 출금 - 차변: 지출계정 (acccd)
					// ============================
					sqlRunner.execute(aa010Sql, buildDetailParams(
						custcd, spjangcd, spdate, spnum, spjangnm,
						it1cd, it2cd, summy,
						String.format("%04d", 1),
						acccd, accnm,        // 지출계정
						"1",                 // 차변
						tranAmt, BigDecimal.ZERO,
						"2",                 // 세출
						null,                // bankcd 없음
						1,
						cltcd
					));

					// 출금 - 대변: 보통예금 (acccd2)
					sqlRunner.execute(aa010Sql, buildDetailParams(
						custcd, spjangcd, spdate, spnum, spjangnm,
						it1cd, it2cd, summy,
						String.format("%04d", 2),
						acccd2, accnm2,      // 보통예금
						"2",                 // 대변
						BigDecimal.ZERO, tranAmt,
						"2",                 // 세출
						bnkcode,             // bankcd
						2,
						cltcd
					));
				}

				// 은행거래 내역 업데이트
				updateBankSlipInfo(custcd, spjangcd, fintechUseNum, bankTranId,
					getString(item, "tran_date"), spdate, spnum);
			}

			result.success = true;
			result.message = "개별전표가 생성되었습니다.";
			return result;

		} catch (Exception e) {
			e.printStackTrace();
			result.success = false;
			result.message = "전표 생성 중 오류가 발생했습니다. " + e.getMessage();
			return result;
		}
	}

	// ============================================
// tb_aa010 상세 행 파라미터 공통 빌더
// ============================================
	private MapSqlParameterSource buildDetailParams(
		String custcd, String spjangcd, String spdate, String spnum, String spjangnm,
		String it1cd, String it2cd, String summy,
		String spseq,
		String acccd, String accnm,
		String drcr,
		BigDecimal dramt, BigDecimal cramt,
		String tiosec,
		String bankcd,
		int rowseq,
		String cltcd) {

		MapSqlParameterSource params = new MapSqlParameterSource();
		params.addValue("custcd",    custcd);
		params.addValue("spjangcd",  spjangcd);
		params.addValue("spdate",    spdate);
		params.addValue("spnum",     spnum);
		params.addValue("spseq",     spseq);
		params.addValue("spjangnm",  spjangnm);
		params.addValue("bumuncd",   "AA");
		params.addValue("acccd",     acccd);
		params.addValue("accnm",     accnm);
		params.addValue("it1cd",     it1cd);
		params.addValue("it2cd",     it2cd);
		params.addValue("drcr",      drcr);
		params.addValue("dramt",     dramt);
		params.addValue("cramt",     cramt);
		params.addValue("tiosec",    tiosec);
		params.addValue("summy",     summy);
		params.addValue("spoccu",    "AA");
		params.addValue("bankcd",    bankcd);
		params.addValue("inputdate", LocalDateTime.now());
		params.addValue("rowseq",    rowseq);
		params.addValue("cltcd",     cltcd);
		return params;
	}

	// ============================================
// 통합전표: 모든 항목을 하나의 전표번호로 통합
// ============================================
	@Transactional
	public AjaxResult createSlipIntegrated(String items, String userId) {

		AjaxResult result = new AjaxResult();

		try {
			if (items == null || items.trim().isEmpty()) {
				result.success = false;
				result.message = "전표 데이터가 없습니다.";
				return result;
			}

			List<Map<String, Object>> itemList = objectMapper.readValue(
				items, new TypeReference<List<Map<String, Object>>>() {}
			);

			if (itemList == null || itemList.isEmpty()) {
				result.success = false;
				result.message = "선택된 데이터가 없습니다.";
				return result;
			}

			// 필수값 검증
			for (Map<String, Object> item : itemList) {
				String busim  = getString(item, "busim");
				String acccd  = getString(item, "acccd");
				String acccd2 = getString(item, "acccd2");
				String it1cd  = getString(item, "it1cd");
				String it2cd  = getString(item, "it2cd");
				String summy  = getString(item, "summy");

				if (isBlank(busim) || isBlank(acccd) || isBlank(acccd2) || isBlank(it1cd) || isBlank(it2cd) || isBlank(summy)) {
					result.success = false;
					result.message = "필수값(사업명, 관명, 상대계정명, 항, 목, 산출내역)이 없는 데이터가 있습니다.";
					return result;
				}
			}

			String spjangcd = TenantContext.get();
			Map<String, String> bizInfo = getBizInfoBySpjangcd(spjangcd);
			String custcd   = bizInfo.get("custcd");
			String spjangnm = bizInfo.get("spjangnm");
			String spdate   = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

			// 통합전표는 전체가 하나의 spnum 공유
			String spnum = String.format("%04d", getNextSpnumInt());

			// 첫 번째 item 기준으로 헤더 값 결정
			Map<String, Object> firstItem = itemList.get(0);
			String inoutTypeFirst = getString(firstItem, "inout_type");

			String aa009Sql = """
						INSERT INTO tb_aa009 (
								custcd, spjangcd, spdate, spnum,
								tiosec, busipur, spoccu, cashyn,
								subject, spjangnm, inputdate, inputid,
								bsdate, bseccd, buiscd
						) VALUES (
								:custcd, :spjangcd, :spdate, :spnum,
								:tiosec, :busipur, :spoccu, :cashyn,
								:subject, :spjangnm, :inputdate, :inputid,
								:bsdate, :bseccd, :buiscd
						)
						""";

			String aa010Sql = """
						INSERT INTO tb_aa010 (
								custcd, spjangcd, spdate, spnum, spseq,
								spjangnm, bumuncd, acccd, accnm,
								it1cd, it2cd,
								drcr, dramt, cramt,
								tiosec, summy, spoccu,
								bankcd, inputdate, rowseq,
								cltcd
						) VALUES (
								:custcd, :spjangcd, :spdate, :spnum, :spseq,
								:spjangnm, :bumuncd, :acccd, :accnm,
								:it1cd, :it2cd,
								:drcr, :dramt, :cramt,
								:tiosec, :summy, :spoccu,
								:bankcd, :inputdate, :rowseq,
								:cltcd
						)
						""";

			// ========================
			// 헤더 INSERT (tb_aa009)
			// 첫 번째 item 기준
			// ========================
			MapSqlParameterSource headerParams = new MapSqlParameterSource();
			headerParams.addValue("custcd",    custcd);
			headerParams.addValue("spjangcd",  spjangcd);
			headerParams.addValue("spdate",    spdate);
			headerParams.addValue("spnum",     spnum);
			headerParams.addValue("tiosec",    inoutTypeFirst.equals("입금") ? "0" : "1");
			headerParams.addValue("busipur",   "3");
			headerParams.addValue("spoccu",    "AA");
			headerParams.addValue("cashyn",    "0");
			headerParams.addValue("subject",   getString(firstItem, "subject"));
			headerParams.addValue("spjangnm",  spjangnm);
			headerParams.addValue("inputdate", LocalDateTime.now());
			headerParams.addValue("inputid",   userId);
			headerParams.addValue("bsdate",    getString(firstItem, "bsdate"));
			headerParams.addValue("bseccd",    getString(firstItem, "bseccd"));
			headerParams.addValue("buiscd",    getString(firstItem, "buiscd"));

			sqlRunner.execute(aa009Sql, headerParams);

			// ========================
			// 상세 INSERT (tb_aa010)
			// item마다 대변 1행씩 + 마지막에 보통예금 합산 차변 1행
			// ========================
			int seq = 1;
			BigDecimal totalAmt = BigDecimal.ZERO;

			// 보통예금 정보는 모든 item 동일하다고 가정 (첫 번째 item 기준)
			String acccd2First = getString(firstItem, "acccd2");
			String accnm2First = getString(firstItem, "accnm2");
			String bnkcodeFirst = getString(firstItem, "bnkcode");
			String tiosecFirst  = inoutTypeFirst.equals("입금") ? "1" : "2";

			for (Map<String, Object> item : itemList) {

				String inoutType = getString(item, "inout_type");
				BigDecimal tranAmt = getBigDecimal(item, "tran_amt");
				String summy       = getString(item, "summy");
				String acccd       = getString(item, "acccd");
				String accnm       = getString(item, "accnm");
				String it1cd       = StringUtils.leftPad(getString(item, "it1cd"), 5, "0");
				String it2cd       = getString(item, "it2cd");
				String cltcd       = getString(item, "cltcd");
				String tiosec      = inoutType.equals("입금") ? "1" : "2";

				// item마다 대변 1행
				// 입금: 대변 = 수입계정(acccd) / 출금: 대변 = 보통예금(acccd2)
				String creditAcccd = inoutType.equals("입금") ? acccd  : getString(item, "acccd2");
				String creditAccnm = inoutType.equals("입금") ? accnm  : getString(item, "accnm2");

				sqlRunner.execute(aa010Sql, buildDetailParams(
					custcd, spjangcd, spdate, spnum, spjangnm,
					it1cd, it2cd, summy,
					String.format("%04d", seq++),
					creditAcccd, creditAccnm,
					"2",                     // 대변
					BigDecimal.ZERO, tranAmt,
					tiosec,
					null,                    // 대변 bankcd 없음
					seq - 1,
					cltcd
				));

				totalAmt = totalAmt.add(tranAmt);

				// 은행거래 내역 업데이트
				updateBankSlipInfo(custcd, spjangcd,
					getString(item, "fintech_use_num"),
					getString(item, "bank_tran_id"),
					getString(item, "tran_date"),
					spdate, spnum);
			}

			// 마지막에 보통예금 합산 차변 1행
			// 입금: 차변 = 보통예금(acccd2) / 출금: 차변 = 지출계정(acccd)
			String debitAcccd = inoutTypeFirst.equals("입금") ? acccd2First : getString(firstItem, "acccd");
			String debitAccnm = inoutTypeFirst.equals("입금") ? accnm2First : getString(firstItem, "accnm");
			String debitBankcd = inoutTypeFirst.equals("입금") ? bnkcodeFirst : null;
			String it1cdFirst  = StringUtils.leftPad(getString(firstItem, "it1cd"), 5, "0");
			String it2cdFirst  = getString(firstItem, "it2cd");
			String cltcdFirst  = getString(firstItem, "cltcd");
			String summyFirst  = getString(firstItem, "summy");

			sqlRunner.execute(aa010Sql, buildDetailParams(
				custcd, spjangcd, spdate, spnum, spjangnm,
				it1cdFirst, it2cdFirst, summyFirst,
				String.format("%04d", seq),
				debitAcccd, debitAccnm,
				"1",                         // 차변
				totalAmt, BigDecimal.ZERO,
				tiosecFirst,
				debitBankcd,
				seq,
				cltcdFirst
			));

			result.success = true;
			result.message = "통합전표가 생성되었습니다.";
			return result;

		} catch (Exception e) {
			e.printStackTrace();
			result.success = false;
			result.message = "전표 생성 중 오류가 발생했습니다. " + e.getMessage();
			return result;
		}
	}

	private Map<String, String> getBizInfoBySpjangcd(String spjangcd) {
		MapSqlParameterSource sqlParam = new MapSqlParameterSource();
		sqlParam.addValue("spjangcd", spjangcd);

		String sql = """
        select saupnum, custcd, spjangnm
        from tb_xa012
        where spjangcd = :spjangcd
    """;

		Map<String, Object> row = sqlRunner.getRow(sql, sqlParam);

		Map<String, String> result = new HashMap<>();
		result.put("saupnum", "");
		result.put("custcd", "");
		result.put("spjangnm", "");

		if (row == null || row.isEmpty()) {
			return result;
		}

		Object saupnum = row.get("saupnum");
		Object custcd = row.get("custcd");
		Object spjangnm = row.get("spjangnm");

		result.put("saupnum", saupnum == null ? "" : String.valueOf(saupnum).trim());
		result.put("custcd", custcd == null ? "" : String.valueOf(custcd).trim());
		result.put("spjangnm", custcd == null ? "" : String.valueOf(spjangnm).trim());

		return result;
	}

	private int getNextSpnumInt() {
		MapSqlParameterSource sqlParam = new MapSqlParameterSource();

		String sql = """
        SELECT ISNULL(MAX(CAST(spnum AS INT)), 0) + 1 AS next_spnum
        FROM tb_aa009
    """;

		Map<String, Object> row = sqlRunner.getRow(sql, sqlParam);

		if (row == null || row.isEmpty()) {
			return 1;
		}

		Object nextSpnum = row.get("next_spnum");

		try {
			return nextSpnum == null ? 1 : Integer.parseInt(String.valueOf(nextSpnum).trim());
		} catch (Exception e) {
			return 1;
		}
	}

	// 은행거래 내역 업데이트 메서드
	private void updateBankSlipInfo(String custcd, String spjangcd, String fintechUseNum,
																	String bankTranId, String tranDate, String spdate, String spnum) {

		MapSqlParameterSource param = new MapSqlParameterSource();

		String slipInfo = spdate + "/" + spnum;

		// tranDate 형식 변환 (2024-03-15 -> 20240315)
		String formattedTranDate = tranDate.replace("-", "");

		param.addValue("acc_spdate", spdate);
		param.addValue("acc_spnum", spnum);
		param.addValue("custcd", custcd);
		param.addValue("spjangcd", spjangcd);
		param.addValue("fintech_use_num", fintechUseNum);
		param.addValue("bank_tran_id", bankTranId);
		param.addValue("tran_date", formattedTranDate);

		String sql = """
        UPDATE TB_bank_accsave
        SET acc_spdate = :acc_spdate,
            acc_spnum = :acc_spnum
        WHERE custcd = :custcd
        AND spjangcd = :spjangcd
        AND fintech_use_num = :fintech_use_num
        AND bank_tran_id = :bank_tran_id
        AND tran_date = :tran_date
    """;

		sqlRunner.execute(sql, param);
	}

	@Transactional
	public AjaxResult cancelSlip(String items, String userId) {

		AjaxResult result = new AjaxResult();

		try {
			if (items == null || items.trim().isEmpty()) {
				result.success = false;
				result.message = "취소할 전표 데이터가 없습니다.";
				return result;
			}

			List<Map<String, Object>> itemList = objectMapper.readValue(
				items, new TypeReference<List<Map<String, Object>>>() {}
			);

			if (itemList == null || itemList.isEmpty()) {
				result.success = false;
				result.message = "선택된 데이터가 없습니다.";
				return result;
			}

			String spjangcd = TenantContext.get();
			Map<String, String> bizInfo = getBizInfoBySpjangcd(spjangcd);
			String custcd = bizInfo.get("custcd");

			for (Map<String, Object> item : itemList) {

				String accSpdateNum = getString(item, "acc_spdate_num");

				if (isBlank(accSpdateNum)) {
					result.success = false;
					result.message = "전표 정보가 없는 데이터가 있습니다.";
					return result;
				}

				// "2024-03-15/0001" 형식에서 분리
				String[] spInfo = accSpdateNum.split("/");
				if (spInfo.length != 2) {
					result.success = false;
					result.message = "전표 정보 형식이 올바르지 않습니다.";
					return result;
				}

				String spdate = spInfo[0].replace("-", ""); // 2024-03-15 -> 20240315
				String spnum  = spInfo[1];

				// 전표 헤더 존재 여부 확인
				MapSqlParameterSource checkParam = new MapSqlParameterSource();
				checkParam.addValue("custcd",   custcd);
				checkParam.addValue("spjangcd", spjangcd);
				checkParam.addValue("spdate",   spdate);
				checkParam.addValue("spnum",    spnum);

				String checkSql = """
                    SELECT COUNT(1) AS cnt
                    FROM tb_aa009
                    WHERE custcd   = :custcd
                    AND spjangcd   = :spjangcd
                    AND spdate     = :spdate
                    AND spnum      = :spnum
                    """;

				int cnt = sqlRunner.queryForCount(checkSql, checkParam);

				if (cnt == 0) {
					result.success = false;
					result.message = "전표를 찾을 수 없습니다. (전표일자: " + spdate + ", 전표번호: " + spnum + ")";
					return result;
				}

				// 전표 상세 삭제 (tb_aa010)
				deleteSlipDetail(custcd, spjangcd, spdate, spnum);

				// 전표 헤더 삭제 (tb_aa009)
				String deleteHeaderSql = """
                    DELETE FROM tb_aa009
                    WHERE custcd   = :custcd
                    AND spjangcd   = :spjangcd
                    AND spdate     = :spdate
                    AND spnum      = :spnum
                    """;

				sqlRunner.execute(deleteHeaderSql, checkParam); // checkParam 재사용

				// 은행거래 내역의 전표정보 초기화
				String fintechUseNum = getString(item, "fintech_use_num");
				String bankTranId    = getString(item, "bank_tran_id");
				String tranDate      = getString(item, "tran_date").replace("-", ""); // 2024-03-15 -> 20240315

				clearBankSlipInfo(custcd, spjangcd, fintechUseNum, bankTranId, tranDate);
			}

			result.success = true;
			result.message = "전표가 취소되었습니다.";
			return result;

		} catch (Exception e) {
			e.printStackTrace();
			result.success = false;
			result.message = "전표 취소 중 오류가 발생했습니다. " + e.getMessage();
			return result;
		}
	}

	// 전표 상세 삭제
	private void deleteSlipDetail(String custcd, String spjangcd, String spdate, String spnum) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("custcd", custcd);
		param.addValue("spjangcd", spjangcd);
		param.addValue("spdate", spdate);
		param.addValue("spnum", spnum);

		String sql = """
        DELETE FROM TB_AA010
        WHERE custcd = :custcd
        AND spjangcd = :spjangcd
        AND spdate = :spdate
        AND spnum = :spnum
    """;

		sqlRunner.execute(sql, param);
	}

	// 은행거래 내역의 전표정보 초기화
	private void clearBankSlipInfo(String custcd, String spjangcd, String fintechUseNum,
																 String bankTranId, String tranDate) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("custcd", custcd);
		param.addValue("spjangcd", spjangcd);
		param.addValue("fintech_use_num", fintechUseNum);
		param.addValue("bank_tran_id", bankTranId);
		param.addValue("tran_date", tranDate);

		String sql = """
        UPDATE TB_bank_accsave
        SET acc_spdate = NULL,
            acc_spnum = NULL
        WHERE custcd = :custcd
        AND spjangcd = :spjangcd
        AND fintech_use_num = :fintech_use_num
        AND bank_tran_id = :bank_tran_id
        AND tran_date = :tran_date
    """;

		sqlRunner.execute(sql, param);
	}

	private String getString(Map<String, Object> item, String key) {
		Object value = item.get(key);
		return value == null ? "" : value.toString().trim();
	}

	private BigDecimal getBigDecimal(Map<String, Object> item, String key) {
		Object value = item.get(key);

		if (value == null || value.toString().trim().isEmpty()) {
			return BigDecimal.ZERO;
		}

		try {
			return new BigDecimal(value.toString().replace(",", ""));
		} catch (Exception e) {
			return BigDecimal.ZERO;
		}
	}

	private boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}

}
