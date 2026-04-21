package mes.app.account_management.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
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
import java.util.*;

@Slf4j
@Service
public class BankAssignmentService {

	@Autowired
	SqlRunner sqlRunner;

	@Autowired
	private ObjectMapper objectMapper;

	public List<Map<String, Object>> getBankHistoryList(String start, String end, String accnum, String accountNameHidden, String accflag,
																											String search_businm, String bsdate,String bseccd,String busicd) {

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
		param.addValue("accnum", "%" + accnum.trim().replace("-", "") + "%");

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
				   x5.mssecnm, 
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
			 outer apply (
					select top 1 x5.mssecnm 
					from tb_x0005 x5
					where x5.custcd = b.custcd
					 and x5.mssec = b.mssec
			 ) x5
			 where 1=1
				 and b.spjangcd = :spjangcd
				 and b.custcd = :custcd
				 and b.tran_date between :start and :end
				 and replace(b.accnum, '-', '') like :accnum
			""";

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

// ✅ 사업 검색 조건 추가
		if (bsdate != null && !bsdate.trim().isEmpty()
					&& bseccd != null && !bseccd.trim().isEmpty()
					&& busicd != null && !busicd.trim().isEmpty()) {

			// 팝업에서 코드 3개 선택한 경우 → 정확히 검색
			sql += """
        and b.bsdate = :bsdate
        and b.bseccd = :bseccd
        and b.buiscd = :busicd
    """;
			param.addValue("bsdate",  bsdate.trim());
			param.addValue("bseccd",  bseccd.trim());
			param.addValue("busicd",  busicd.trim());

		} else if (search_businm != null && !search_businm.trim().isEmpty()) {

			// 텍스트만 입력한 경우 → 사업명 LIKE 검색
			sql += """
        and b.busim like :search_businm
    """;
			param.addValue("search_businm", "%" + search_businm.trim() + "%");
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

				// inout_type 변환: 입금 → 0, 출금 → 1
				String inoutTypeRaw = getString(item, "inout_type");
				String inoutType;
				if ("입금".equals(inoutTypeRaw)) {
					inoutType = "0";
				} else if ("출금".equals(inoutTypeRaw)) {
					inoutType = "1";
				} else {
					inoutType = inoutTypeRaw; // 이미 0/1로 넘어오는 경우 대비
				}

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
               contra_acccd  = :contra_acccd,
               tiosec    = :inout_type
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

			String aa009Sql = """
				INSERT INTO tb_aa009 (
						custcd, spjangcd, spdate, spnum,
						tiosec, busipur, spoccu, cashyn,
						subject, spjangnm, inputdate, inputid,
						bsdate, bseccd, busicd
				) VALUES (
						:custcd, :spjangcd, :spdate, :spnum,
						:tiosec, :busipur, :spoccu, :cashyn,
						:subject, :spjangnm, :inputdate, :inputid,
						:bsdate, :bseccd, :busicd
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
        cltcd, mssec
    ) VALUES (
        :custcd, :spjangcd, :spdate, :spnum, :spseq,
        :spjangnm, :bumuncd, :acccd, :accnm,
        :it1cd, :it2cd,
        :drcr, :dramt, :cramt,
        :tiosec, :summy, :spoccu,
        :bankcd, :inputdate, :rowseq,
        :cltcd, :mssec
    )
    """;

			for (Map<String, Object> item : itemList) {

				String spdate    = getString(item, "tran_date").replace("-", "");
				String spnum     = String.format("%04d", getNextSpnumInt());
				String inoutType = getString(item, "inout_type");
				String acccd     = getString(item, "acccd");
				String tiosec    = getTiosecByAcccd(acccd);

				// ========================
				// 헤더 INSERT (tb_aa009)
				// ========================
				MapSqlParameterSource headerParams = new MapSqlParameterSource();
				headerParams.addValue("custcd",    custcd);
				headerParams.addValue("spjangcd",  spjangcd);
				headerParams.addValue("spdate",    spdate);
				headerParams.addValue("spnum",     spnum);
				headerParams.addValue("tiosec",    tiosec);
				headerParams.addValue("busipur",   "3");
				headerParams.addValue("spoccu",    "AA");
				headerParams.addValue("cashyn",    "0");
				headerParams.addValue("subject",   getString(item, "subject"));
				headerParams.addValue("spjangnm",  spjangnm);
				headerParams.addValue("inputdate", LocalDateTime.now());
				headerParams.addValue("inputid",   userId);
				headerParams.addValue("cltcd",     getString(item, "cltcd"));
				headerParams.addValue("bsdate",  getString(item, "bsdate"));
				headerParams.addValue("bseccd",  getString(item, "bseccd"));
				headerParams.addValue("busicd",  getString(item, "buiscd"));

				sqlRunner.execute(aa009Sql, headerParams);

				// 상세 공통 변수
				BigDecimal tranAmt = inoutType.equals("출금")
															 ? getBigDecimal(item, "wdr_amt")
															 : getBigDecimal(item, "tran_amt");
				String summy         = getString(item, "summy");
				String bankTranId    = getString(item, "bank_tran_id");
				String fintechUseNum = getString(item, "fintech_use_num");
				String bnkcode       = getString(item, "bnkcode");
				String accnm         = getString(item, "accnm");
				String acccd2        = getString(item, "acccd2");
				String accnm2        = getString(item, "accnm2");
				String it1cd         = StringUtils.leftPad(getString(item, "it1cd"), 5, "0");
				String it2cd         = getString(item, "it2cd");
				String cltcd 				 = getString(item, "cltcd");
				String mssec         = getString(item, "mssec");

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
						cltcd,
						mssec
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
						cltcd,
						mssec
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
						cltcd,
						mssec
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
						cltcd,
						mssec
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
		String cltcd,
		String mssec) {

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
		params.addValue("mssec",     mssec);
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

			// 통합전표는 전체가 하나의 spnum 공유
			String spnum = String.format("%04d", getNextSpnumInt());

			// 첫 번째 item 기준으로 헤더 값 결정
			Map<String, Object> firstItem = itemList.get(0);
			String inoutTypeFirst = getString(firstItem, "inout_type");
			String tiosecFirst    = getTiosecByAcccd(getString(firstItem, "acccd"));
			String spdate         = getString(firstItem, "tran_date").replace("-", "");

			// 입금 차변용 첫 번째 item 변수 (firstItem 기준)
			String acccd2First  = getString(firstItem, "acccd2");
			String accnm2First  = getString(firstItem, "accnm2");
			String bnkcodeFirst = getString(firstItem, "bnkcode");
			String it1cdFirst   = StringUtils.leftPad(getString(firstItem, "it1cd"), 5, "0");
			String it2cdFirst   = getString(firstItem, "it2cd");
			String cltcdFirst   = getString(firstItem, "cltcd");
			String summyFirst   = getString(firstItem, "summy");
			String mssecFirst   = getString(firstItem, "mssec");

			String aa009Sql = """
            INSERT INTO tb_aa009 (
                custcd, spjangcd, spdate, spnum,
                tiosec, busipur, spoccu, cashyn,
                subject, spjangnm, inputdate, inputid,
                bsdate, bseccd, busicd
            ) VALUES (
                :custcd, :spjangcd, :spdate, :spnum,
                :tiosec, :busipur, :spoccu, :cashyn,
                :subject, :spjangnm, :inputdate, :inputid,
                :bsdate, :bseccd, :busicd
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
                cltcd, mssec
            ) VALUES (
                :custcd, :spjangcd, :spdate, :spnum, :spseq,
                :spjangnm, :bumuncd, :acccd, :accnm,
                :it1cd, :it2cd,
                :drcr, :dramt, :cramt,
                :tiosec, :summy, :spoccu,
                :bankcd, :inputdate, :rowseq,
                :cltcd, :mssec
            )
            """;

			// ========================
			// 헤더 INSERT (tb_aa009)
			// ========================
			MapSqlParameterSource headerParams = new MapSqlParameterSource();
			headerParams.addValue("custcd",    custcd);
			headerParams.addValue("spjangcd",  spjangcd);
			headerParams.addValue("spdate",    spdate);
			headerParams.addValue("spnum",     spnum);
			headerParams.addValue("tiosec",    tiosecFirst); // ✅ acccd 기준 단일 세팅
			headerParams.addValue("busipur",   "3");
			headerParams.addValue("spoccu",    "AA");
			headerParams.addValue("cashyn",    "0");
			headerParams.addValue("subject",   getString(firstItem, "subject"));
			headerParams.addValue("spjangnm",  spjangnm);
			headerParams.addValue("inputdate", LocalDateTime.now());
			headerParams.addValue("inputid",   userId);
			headerParams.addValue("bsdate",    getString(firstItem, "bsdate"));
			headerParams.addValue("bseccd",    getString(firstItem, "bseccd"));
			headerParams.addValue("busicd",    getString(firstItem, "buiscd"));

			sqlRunner.execute(aa009Sql, headerParams);

			// ========================
			// 상세 INSERT (tb_aa010)
			// ========================
			int seq = 1;
			BigDecimal totalInAmt  = BigDecimal.ZERO; // 입금 합산
			BigDecimal totalOutAmt = BigDecimal.ZERO; // 출금 합산
			Map<String, Object> firstOutItem = null;  // 출금 차변용 첫 번째 출금 item

			for (Map<String, Object> item : itemList) {

				String inoutType   = getString(item, "inout_type");
				String acccd       = getString(item, "acccd");
				String accnm       = getString(item, "accnm");
				String tiosec      = getTiosecByAcccd(acccd);
				BigDecimal tranAmt = inoutType.equals("출금")
															 ? getBigDecimal(item, "wdr_amt")
															 : getBigDecimal(item, "tran_amt");
				String summy = getString(item, "summy");
				String it1cd = StringUtils.leftPad(getString(item, "it1cd"), 5, "0");
				String it2cd = getString(item, "it2cd");
				String cltcd = getString(item, "cltcd");
				String mssec = getString(item, "mssec");

				// 출금 첫 번째 item 저장
				if (inoutType.equals("출금") && firstOutItem == null) {
					firstOutItem = item;
				}

				// 대변 1행
				// 입금: 대변 = 수입계정(acccd) / 출금: 대변 = 보통예금(acccd2)
				String creditAcccd = inoutType.equals("입금") ? acccd : getString(item, "acccd2");
				String creditAccnm = inoutType.equals("입금") ? accnm : getString(item, "accnm2");

				sqlRunner.execute(aa010Sql, buildDetailParams(
					custcd, spjangcd, spdate, spnum, spjangnm,
					it1cd, it2cd, summy,
					String.format("%04d", seq),
					creditAcccd, creditAccnm,
					"2",
					BigDecimal.ZERO, tranAmt,
					tiosec,
					null,
					seq,
					cltcd,
					mssec
				));

				seq++;

				// 입금/출금 합산 분리
				if (inoutType.equals("입금")) {
					totalInAmt = totalInAmt.add(tranAmt);
				} else {
					totalOutAmt = totalOutAmt.add(tranAmt);
				}

				updateBankSlipInfo(custcd, spjangcd,
					getString(item, "fintech_use_num"),
					getString(item, "bank_tran_id"),
					getString(item, "tran_date"),
					spdate, spnum);
			}

			// 입금 합산 차변 (보통예금)
			if (totalInAmt.compareTo(BigDecimal.ZERO) > 0) {
				sqlRunner.execute(aa010Sql, buildDetailParams(
					custcd, spjangcd, spdate, spnum, spjangnm,
					it1cdFirst, it2cdFirst, summyFirst,
					String.format("%04d", seq),
					acccd2First, accnm2First,
					"1",
					totalInAmt, BigDecimal.ZERO,
					"1",         // 세입
					bnkcodeFirst,
					seq,
					cltcdFirst,
					mssecFirst
				));
				seq++;
			}

			// 출금 합산 차변 (지출계정)
			if (totalOutAmt.compareTo(BigDecimal.ZERO) > 0 && firstOutItem != null) {
				String outIt1cd = StringUtils.leftPad(getString(firstOutItem, "it1cd"), 5, "0");
				String outIt2cd = getString(firstOutItem, "it2cd");
				String outCltcd = getString(firstOutItem, "cltcd");
				String outMssec = getString(firstOutItem, "mssec");
				String outSummy = getString(firstOutItem, "summy");

				sqlRunner.execute(aa010Sql, buildDetailParams(
					custcd, spjangcd, spdate, spnum, spjangnm,
					outIt1cd, outIt2cd, outSummy,
					String.format("%04d", seq),
					getString(firstOutItem, "acccd"),
					getString(firstOutItem, "accnm"),
					"1",
					totalOutAmt, BigDecimal.ZERO,
					"2",         // 세출
					null,
					seq,
					outCltcd,
					outMssec
				));
				seq++;
			}

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

			Set<String> deletedSlips = new HashSet<>();

			for (Map<String, Object> item : itemList) {

				String accSpdateNum = getString(item, "acc_spdate_num");

				if (isBlank(accSpdateNum)) {
					result.success = false;
					result.message = "전표 정보가 없는 데이터가 있습니다.";
					return result;
				}

				String[] spInfo = accSpdateNum.split("/");
				if (spInfo.length != 2) {
					result.success = false;
					result.message = "전표 정보 형식이 올바르지 않습니다.";
					return result;
				}

				String spdate  = spInfo[0].replace("-", "");
				String spnum   = spInfo[1];
				String slipKey = spdate + "_" + spnum;

				// ✅ 같은 전표번호는 한 번만 삭제
				if (!deletedSlips.contains(slipKey)) {

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
					sqlRunner.execute(deleteHeaderSql, checkParam);

					deletedSlips.add(slipKey);
				}

				// ✅ bank 초기화는 모든 item마다 각각 실행
				String fintechUseNum = getString(item, "fintech_use_num");
				String bankTranId    = getString(item, "bank_tran_id");
				String tranDate      = getString(item, "tran_date").replace("-", "");

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

	public List<Map<String, Object>> bankAccHistory() {
		MapSqlParameterSource sqlParam = new MapSqlParameterSource();
		String spjangcd = TenantContext.get();
		Map<String, String> bizInfo = getBizInfoBySpjangcd(spjangcd);
		String custcd = bizInfo.get("custcd");

		sqlParam.addValue("custcd", custcd);
		sqlParam.addValue("spjangcd", spjangcd);

		String sql = """
    SELECT
        remark1,
        acccd,
        accnm,
        it1cd,
        it1nm,
        it2cd,
        it2nm,
        contra_acccd AS acccd2,
        accnm2
    FROM (
        SELECT
            a.remark1,
            a.acccd,
            ac.accnm,
            a.it1cd,
            it1.it1nm,
            a.it2cd,
            it2.it2nm,
            a.contra_acccd,
            ac2.accnm AS accnm2,
            ROW_NUMBER() OVER (
                PARTITION BY a.remark1
                ORDER BY a.tran_date DESC
            ) AS rn
        FROM TB_bank_accsave a
        OUTER APPLY (
            SELECT TOP 1 ac.accnm
            FROM tb_ac001 ac
            WHERE ac.custcd = a.custcd
              AND ac.acccd  = a.acccd
        ) ac
        OUTER APPLY (
            SELECT TOP 1 ac2.accnm
            FROM tb_ac001 ac2
            WHERE ac2.custcd = a.custcd
              AND ac2.acccd  = a.contra_acccd
        ) ac2
        OUTER APPLY (
            SELECT TOP 1 it1.it1nm
            FROM tb_x0003 it1
            WHERE it1.custcd = a.custcd
              AND it1.it1cd  = a.it1cd
        ) it1
        OUTER APPLY (
            SELECT TOP 1 it2.it2nm
            FROM tb_x0004 it2
            WHERE it2.custcd = a.custcd
              AND it2.it2cd  = a.it2cd
        ) it2
        WHERE a.custcd   = :custcd
          AND a.spjangcd = :spjangcd
          AND a.acccd    IS NOT NULL AND a.acccd   != ''
          AND a.it1cd    IS NOT NULL AND a.it1cd   != ''
          AND a.it2cd    IS NOT NULL AND a.it2cd   != ''
          AND a.remark1  IS NOT NULL AND a.remark1 != ''
    ) t
    WHERE rn = 1
    """;
		return sqlRunner.getRows(sql, sqlParam);
	}

	public List<Map<String, Object>> getIt1nm(String it1nm, String inoutType) {
		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("it1nm", it1nm);
		param.addValue("tiosec", inoutType);

		String sql = """
			select it1cd ,it1nm from tb_x0003 
			where useyn='1'
			 and replace(isnull(it1nm, ''), ' ', '') like '%' + replace(:it1nm, ' ', '') + '%'
			  AND (:tiosec IS NULL OR :tiosec = '' OR tiosec = :tiosec)
			""";
		log.info("전표분개[항코드]:", sql, param);
		return sqlRunner.getRows(sql, param);
	}
	// 관코드(acccd) 앞자리로 세입세출구분(tiosec) 결정
	private String getTiosecByAcccd(String acccd) {
		if (acccd == null || acccd.trim().isEmpty()) return "3";
		if (acccd.trim().startsWith("5")) return "1"; // 세입
		if (acccd.trim().startsWith("7")) return "2"; // 세출
		return "3"; // 대체 (1000, 2000, 3000번대)
	}
}
