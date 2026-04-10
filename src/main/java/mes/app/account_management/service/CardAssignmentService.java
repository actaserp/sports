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
public class CardAssignmentService {
	@Autowired
	SqlRunner sqlRunner;

	@Autowired
	private ObjectMapper objectMapper;

	public List<Map<String, Object>> getCardAssignmentList(String start, String end, String accountName, String accflag) {
		MapSqlParameterSource param = new MapSqlParameterSource();
		String tenantId = TenantContext.get();
		param.addValue("spjangcd", tenantId);
		param.addValue("start", start);
		param.addValue("end", end);
		param.addValue("card_no", accountName);
		param.addValue("accflag", accflag);	//발행구분

		String sql = """
			select
				biz_no,
				bnkcode,
				card_no,
				STUFF(STUFF(apv_dt,5,0,'-'),8,0,'-') as apv_dt,
				apv_no,
				STUFF(STUFF(mijdate,5,0,'-'),8,0,'-') as mijdate,
				buy_sum,
				sply_amt,
				vat_amt,
				mest_nm,	
				-- 사업
				bsdate,
				bseccd,
				buiscd,
				busim,	
				-- 관
				acccd,
				accnm,	
				-- 항
				it1cd,
				it1nm,	
				-- 목
				it2cd,
				it2nm,
				-- 재원
				mssec,
				mssecnm,	
				-- 산출내역
				summy,	
				-- 전표정보
				mijdate,
				mijnum,
				remark,
				subject,
				pay_acccd2 as acccd2,
			  pay_accnm2 as accnm2,
				STUFF(STUFF(paydate,5,0,'-'),8,0,'-') as paydate, 
				paynum,
				pay_cancel_yn,
				-- 화면 표시용
				case
						when mijdate is not null and mijnum is not null
						then STUFF(STUFF(mijdate,5,0,'-'),8,0,'-') + '/' + mijnum
						else ''
					end as slip_info
			from tb_bank_cdsave
			where spjangcd = :spjangcd
			and mijdate between :start and :end
			""";

		if (accountName != null && !accountName.trim().isEmpty()) {
			sql += """
        and replace(card_no, '-', '') like :card_no
    """;

			param.addValue("card_no", "%" + accountName.trim().replace("-", "") + "%");
		}

		if (accflag != null && !accflag.trim().isEmpty()) {
			sql += """
        and CASE
              WHEN ISNULL(NULLIF(paydate, ''), '') <> ''
               AND ISNULL(NULLIF(paynum, ''), '') <> ''
              THEN '1'
              ELSE '0'
            END = :accflag
    """;
			param.addValue("accflag", accflag.trim());
		}

		return sqlRunner.getRows(sql, param);

	}

	//지급 전표 생성
	@Transactional
	public AjaxResult createPaymentSlip(String items, String userId) {

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
				String busim = getString(item, "busim");
				String accnm = getString(item, "accnm");
				String it1nm = getString(item, "it1nm");
				String it2nm = getString(item, "it2nm");
				String mssec = getString(item, "mssec");

				if (isBlank(busim) || isBlank(accnm) || isBlank(it1nm) || isBlank(it2nm) || isBlank(mssec)) {
					result.success = false;
					result.message = "필수값(사업명, 관명, 항, 목, 재원)이 없는 데이터가 있습니다.";
					return result;
				}
			}

			Map<String, Object> cardAccount = getAccountInfo("미지급금(카드)");
			String cardAcccd = (String) cardAccount.get("acccd");
			String cardAccnm = (String) cardAccount.get("accnm");

			Map<String, Object> bankAccount = getAccountInfo("보통예금");
			String bankAcccd = (String) bankAccount.get("acccd");
			String bankAccnm = (String) bankAccount.get("accnm");

			if (isBlank(cardAcccd)) {
				result.success = false;
				result.message = "미지급금(카드) 계정을 찾을 수 없습니다.";
				return result;
			}
			if (isBlank(bankAcccd)) {
				result.success = false;
				result.message = "보통예금 계정을 찾을 수 없습니다.";
				return result;
			}

			String spjangcd = TenantContext.get();
			Map<String, String> bizInfo = getBizInfoBySpjangcd(spjangcd);
			String custcd = bizInfo.get("custcd");
			String spjangnm = bizInfo.get("spjangnm");

			String paydate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
			String paynum = String.format("%04d", getNextSpnumInt());

			log.info("========== 지급전표 생성 시작 - 건수: {} ==========", itemList.size());

			// =========================
			// 1. 지급 전표 헤더 생성
			// =========================
			Map<String, Object> firstItem = itemList.get(0);
// 1. 헤더 저장
			savePayHeader(custcd, spjangcd, spjangnm, paydate, paynum, firstItem, userId);

			// 2. 상세 저장 (차변)
			int rowseq = 1;
			BigDecimal totalAmount = BigDecimal.ZERO;

			for (Map<String, Object> item : itemList) {
				BigDecimal buySum = getBigDecimal(item, "buy_sum");
				totalAmount = totalAmount.add(buySum);

				saveDebit(custcd, spjangcd, spjangnm, paydate, paynum, rowseq, item, buySum, cardAcccd, cardAccnm);

				String bizNo = getString(item, "biz_no");
				String bankCd = getString(item, "bnkcode");

				if (!isBlank(bizNo)) {
					try {
						updateCardPayInfo(custcd, spjangcd, bankCd, bizNo, paydate, paynum);
						log.info("[카드내역 업데이트 완료] bizNo: {}", bizNo);
					} catch (Exception e) {
						log.error("[카드내역 업데이트 실패] bizNo: {}", bizNo, e);
						throw e;
					}
				}
				rowseq++;
			}

			// 3. 대변 저장
			saveCredit(custcd, spjangcd, spjangnm, paydate, paynum, rowseq, totalAmount, bankAcccd, bankAccnm);

			log.info("========== 지급전표 생성 완료 - 총액: {} ==========", totalAmount);
			result.success = true;
			result.message = "지급 전표가 생성되었습니다.";
			return result;

		} catch (Exception e) {
			log.error("========== 지급전표 생성 오류 ==========", e);
			result.success = false;
			result.message = "전표 생성 중 오류가 발생했습니다. " + e.getMessage();
			return result;
		}
	}

	// ──────────────────────────────────────────
// 헤더 저장
// ──────────────────────────────────────────
	private void savePayHeader(String custcd, String spjangcd, String spjangnm,
							   String paydate, String paynum,
							   Map<String, Object> firstItem, String userId) {
		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("custcd", custcd);
		dicParam.addValue("spjangcd", spjangcd);
		dicParam.addValue("spdate", paydate);
		dicParam.addValue("spnum", paynum);
		dicParam.addValue("tiosec", "3");
		dicParam.addValue("busipur", "3");
		dicParam.addValue("spoccu", "AA");
		dicParam.addValue("cashyn", "0");
		dicParam.addValue("bsdate", getString(firstItem, "bsdate"));
		dicParam.addValue("bseccd", getString(firstItem, "bseccd"));
		dicParam.addValue("busicd", getString(firstItem, "buiscd"));
		dicParam.addValue("remark", getString(firstItem, "remark"));
		dicParam.addValue("subject", getString(firstItem, "subject"));
		dicParam.addValue("spjangnm", spjangnm);
		dicParam.addValue("fixflag", "1");
		dicParam.addValue("inputdate", LocalDateTime.now());
		dicParam.addValue("inputid", userId);

		String checkSql = """
        SELECT COUNT(*) AS cnt
        FROM tb_aa009
        WHERE custcd   = :custcd
          AND spjangcd = :spjangcd
          AND spdate   = :spdate
          AND spnum    = :spnum
        """;

		List<Map<String, Object>> checkResult = this.sqlRunner.getRows(checkSql, dicParam);
		int count = ((Number) checkResult.get(0).get("cnt")).intValue();

		try {
			if (count > 0) {
				String updateSql = """
                UPDATE tb_aa009
                SET
                    tiosec    = :tiosec,
                    busipur   = :busipur,
                    spoccu    = :spoccu,
                    cashyn    = :cashyn,
                    bsdate    = :bsdate,
                    bseccd    = :bseccd,
                    busicd    = :busicd,
                    remark    = :remark,
                    subject   = :subject,
                    spjangnm  = :spjangnm,
                    fixflag   = :fixflag,
                    inputdate = :inputdate,
                    inputid   = :inputid
                WHERE custcd   = :custcd
                  AND spjangcd = :spjangcd
                  AND spdate   = :spdate
                  AND spnum    = :spnum
                """;
				this.sqlRunner.execute(updateSql, dicParam);
			} else {
				String insertSql = """
                INSERT INTO tb_aa009 (
                    custcd, spjangcd, spdate, spnum,
                    tiosec, busipur, spoccu, cashyn,
                    bsdate, bseccd, busicd, remark,
                    subject, spjangnm, fixflag,
                    inputdate, inputid
                ) VALUES (
                    :custcd, :spjangcd, :spdate, :spnum,
                    :tiosec, :busipur, :spoccu, :cashyn,
                    :bsdate, :bseccd, :busicd, :remark,
                    :subject, :spjangnm, :fixflag,
                    :inputdate, :inputid
                )
                """;
				this.sqlRunner.execute(insertSql, dicParam);
			}
			log.info("[헤더 저장 완료] paydate: {}, paynum: {}", paydate, paynum);
		} catch (Exception e) {
			log.error("[헤더 저장 실패]", e);
			throw e;
		}
	}

	// ──────────────────────────────────────────
// 차변 저장
// ──────────────────────────────────────────
	private void saveDebit(String custcd, String spjangcd, String spjangnm,
						   String paydate, String paynum, int rowseq,
						   Map<String, Object> item, BigDecimal buySum, String acccd, String accnm) {
		String paddedIt1cd = StringUtils.leftPad(getString(item, "it1cd"), 5, "0");

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("custcd", custcd);
		dicParam.addValue("spjangcd", spjangcd);
		dicParam.addValue("spdate", paydate);
		dicParam.addValue("spnum", paynum);
		dicParam.addValue("spseq", String.format("%04d", rowseq));
		dicParam.addValue("spjangnm", spjangnm);
		dicParam.addValue("bumuncd", "AA");
		dicParam.addValue("acccd", acccd);   // "2152" 하드코딩 제거
		dicParam.addValue("accnm", accnm);

		dicParam.addValue("cardnum", getString(item, "card_no"));
		dicParam.addValue("it1cd", paddedIt1cd);
		dicParam.addValue("it2cd", getString(item, "it2cd"));
		dicParam.addValue("drcr", "1");
		dicParam.addValue("dramt", buySum);
		dicParam.addValue("cramt", BigDecimal.ZERO);
		dicParam.addValue("mssec", getString(item, "mssec"));
		dicParam.addValue("tiosec", "3");
		dicParam.addValue("summy", getString(item, "summy"));
		dicParam.addValue("spoccu", "AA");
		dicParam.addValue("inputdate", LocalDateTime.now());
		dicParam.addValue("rowseq", new BigDecimal(rowseq));

		upsertAa010(dicParam, rowseq, buySum);
	}

	// ──────────────────────────────────────────
// 대변 저장
// ──────────────────────────────────────────
	private void saveCredit(String custcd, String spjangcd, String spjangnm,
		String paydate, String paynum, int rowseq,
		BigDecimal totalAmount, String acccd, String accnm) {
		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("custcd", custcd);
		dicParam.addValue("spjangcd", spjangcd);
		dicParam.addValue("spdate", paydate);
		dicParam.addValue("spnum", paynum);
		dicParam.addValue("spseq", String.format("%04d", rowseq));
		dicParam.addValue("spjangnm", spjangnm);
		dicParam.addValue("bumuncd", "AA");
		dicParam.addValue("acccd", acccd);   // "1014" 하드코딩 제거
		dicParam.addValue("accnm", accnm);
		dicParam.addValue("cardnum", "");
		dicParam.addValue("it1cd", "000");
		dicParam.addValue("it2cd", "000");
		dicParam.addValue("drcr", "2");
		dicParam.addValue("dramt", BigDecimal.ZERO);
		dicParam.addValue("cramt", totalAmount);
		dicParam.addValue("mssec", "");
		dicParam.addValue("tiosec", "3");
		dicParam.addValue("summy", "카드대금 지급");
		dicParam.addValue("spoccu", "AA");
		dicParam.addValue("inputdate", LocalDateTime.now());
		dicParam.addValue("rowseq", new BigDecimal(rowseq));

		try {
			upsertAa010(dicParam, rowseq, totalAmount);
		} catch (Exception e) {
			log.error("[대변 저장 실패] seq: {}", rowseq);
			throw e;
		}
	}

	// ──────────────────────────────────────────
// tb_aa010 공통 upsert (차변/대변 공용)
// ──────────────────────────────────────────
	private void upsertAa010(MapSqlParameterSource dicParam, int rowseq, BigDecimal amount) {
		String checkSql = """
        SELECT COUNT(*) AS cnt
        FROM tb_aa010
        WHERE custcd   = :custcd
          AND spjangcd = :spjangcd
          AND spdate   = :spdate
          AND spnum    = :spnum
          AND spseq    = :spseq
        """;

		List<Map<String, Object>> checkResult = this.sqlRunner.getRows(checkSql, dicParam);
		int count = ((Number) checkResult.get(0).get("cnt")).intValue();

		if (count > 0) {
			String updateSql = """
            UPDATE tb_aa010
            SET
                spjangnm  = :spjangnm,
                bumuncd   = :bumuncd,
                acccd     = :acccd,
                accnm     = :accnm,
                cardnum   = :cardnum,
                it1cd     = :it1cd,
                it2cd     = :it2cd,
                drcr      = :drcr,
                dramt     = :dramt,
                cramt     = :cramt,
                mssec     = :mssec,
                tiosec    = :tiosec,
                summy     = :summy,
                spoccu    = :spoccu,
                inputdate = :inputdate,
                rowseq    = :rowseq
            WHERE custcd   = :custcd
              AND spjangcd = :spjangcd
              AND spdate   = :spdate
              AND spnum    = :spnum
              AND spseq    = :spseq
            """;
			this.sqlRunner.execute(updateSql, dicParam);
		} else {
			String insertSql = """
            INSERT INTO tb_aa010 (
                custcd, spjangcd, spdate, spnum, spseq,
                spjangnm, bumuncd, acccd, accnm, cardnum,
                it1cd, it2cd, drcr, dramt, cramt,
                mssec, tiosec, summy, spoccu,
                inputdate, rowseq
            ) VALUES (
                :custcd, :spjangcd, :spdate, :spnum, :spseq,
                :spjangnm, :bumuncd, :acccd, :accnm, :cardnum,
                :it1cd, :it2cd, :drcr, :dramt, :cramt,
                :mssec, :tiosec, :summy, :spoccu,
                :inputdate, :rowseq
            )
            """;
			this.sqlRunner.execute(insertSql, dicParam);
		}
		log.info("[aa010 저장 완료] seq: {}, amount: {}", rowseq, amount);
	}

	// 카드내역에 지급전표 정보 업데이트
	private void updateCardPayInfo(String custcd, String spjangcd, String bankCd, String bizNo, String paydate, String paynum) {

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("custcd", custcd);
		dicParam.addValue("spjangcd", spjangcd);
		dicParam.addValue("bnkcode", bankCd);
		dicParam.addValue("bizNo", bizNo);

		String checkSql = """
    SELECT COUNT(*) AS cnt
    FROM TB_bank_cdsave
    WHERE custcd   = :custcd
      AND spjangcd = :spjangcd
      AND bnkcode  = :bnkcode
      AND biz_no   = :bizNo
    """;

		List<Map<String, Object>> checkResult = this.sqlRunner.getRows(checkSql, dicParam);
		int count = ((Number) checkResult.get(0).get("cnt")).intValue();

		if (count > 0) {
			dicParam.addValue("paydate", paydate);
			dicParam.addValue("paynum", paynum);
			dicParam.addValue("payCancelYn", "Y");

			String updateSql = """
        UPDATE TB_bank_cdsave
        SET
            paydate        = :paydate,
            paynum         = :paynum,
            pay_cancel_yn  = :payCancelYn
        WHERE custcd   = :custcd
          AND spjangcd = :spjangcd
          AND bnkcode  = :bnkcode
          AND biz_no   = :bizNo
        """;
			this.sqlRunner.execute(updateSql, dicParam);

		} else {
			log.warn("[카드내역 없음] bizNo: {}", bizNo);
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

	//지급전표 취소
	@Transactional
	public AjaxResult PaymentCancelSlip(String items, String userId) {
		AjaxResult result = new AjaxResult();

		try {
			if (items == null || items.trim().isEmpty()) {
				result.success = false;
				result.message = "취소할 데이터가 없습니다.";
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

			int successCount = 0;
			int failCount = 0;
			StringBuilder errorMsg = new StringBuilder();

			// 전표번호별로 그룹화 (같은 지급전표는 한 번만 삭제)
			Map<String, List<Map<String, Object>>> groupedByPaySlip = new HashMap<>();

			for (Map<String, Object> item : itemList) {
				String bizNo = getString(item, "biz_no");
				String paydate = getString(item, "paydate");
				String paynum = getString(item, "paynum");

				// 필수값 검증
				if (isBlank(bizNo)) {
					failCount++;
					errorMsg.append("필수 데이터가 누락된 항목이 있습니다.\n");
					continue;
				}

				// paydate, paynum이 없으면 지급전표가 생성되지 않은 것
				if (isBlank(paydate) || isBlank(paynum)) {
					failCount++;
					errorMsg.append("지급전표가 생성되지 않은 항목입니다. (승인번호: ").append(getString(item, "apv_no")).append(")\n");
					continue;
				}

				// 날짜 형식 변환: "2026-03-23" → "20260323"
				paydate = paydate.replace("-", "");

				// 지급전표번호별로 그룹화 (paydate + paynum)
				String paySlipKey = paydate + "/" + paynum;
				if (!groupedByPaySlip.containsKey(paySlipKey)) {
					groupedByPaySlip.put(paySlipKey, new ArrayList<>());
				}
				groupedByPaySlip.get(paySlipKey).add(item);
			}

			// 지급전표번호별로 처리
			for (Map.Entry<String, List<Map<String, Object>>> entry : groupedByPaySlip.entrySet()) {
				String paySlipKey = entry.getKey();
				List<Map<String, Object>> slipItems = entry.getValue();

				try {
					// paySlipKey 파싱 (예: "20240320/0001")
					String[] parts = paySlipKey.split("/");
					if (parts.length != 2) {
						failCount += slipItems.size();
						errorMsg.append("지급전표번호 형식이 올바르지 않습니다: ").append(paySlipKey).append("\n");
						continue;
					}

					String paydate = parts[0];
					String paynum = parts[1];

					// 1. tb_aa010 삭제 (지급전표 상세)
					int deletedAa010 = deleteAa010ByPaySlip(custcd, spjangcd, paydate, paynum);
//					log.info("tb_aa010 삭제 완료: {} 건, paydate={}, paynum={}", deletedAa010, paydate, paynum);

					// 2. tb_aa009 삭제 (지급전표 헤더)
					MapSqlParameterSource dicParam = new MapSqlParameterSource();
					dicParam.addValue("custcd", custcd);
					dicParam.addValue("spjangcd", spjangcd);
					dicParam.addValue("spdate", paydate);
					dicParam.addValue("spnum", paynum);

					String deleteSql = """
						DELETE FROM tb_aa009
						WHERE custcd   = :custcd
						  AND spjangcd = :spjangcd
						  AND spdate   = :spdate
						  AND spnum    = :spnum
						""";

					this.sqlRunner.execute(deleteSql, dicParam);
//					log.info("tb_aa009 삭제 완료: custcd={}, spjangcd={}, paydate={}, paynum={}", custcd, spjangcd, paydate, paynum);

					// 3. tb_bank_cdsave 업데이트 (지급전표정보 초기화)
					for (Map<String, Object> item : slipItems) {
						String bizNo = getString(item, "biz_no");
						int updated = clearPaymentSlipInfo(bizNo);

						if (updated > 0) {
							successCount++;
//							log.info("카드내역 지급전표정보 초기화 완료: biz_no={}", bizNo);
						} else {
							failCount++;
							errorMsg.append("카드내역 업데이트 실패 (승인번호: ").append(getString(item, "apv_no")).append(")\n");
						}
					}

				} catch (Exception e) {
					log.error("지급전표 취소 중 오류 발생: paySlipKey={}", paySlipKey, e);
					failCount += slipItems.size();
					errorMsg.append("지급전표 취소 실패: ").append(paySlipKey).append(" - ").append(e.getMessage()).append("\n");
				}
			}

			if (failCount > 0) {
				result.success = false;
				result.message = "성공: " + successCount + "건, 실패: " + failCount + "건\n" + errorMsg.toString();
				return result;
			}

			result.success = true;
			result.message = "지급전표 취소가 완료되었습니다. (총 " + successCount + "건)";
			return result;

		} catch (Exception e) {
			log.error("지급전표 취소 중 오류 발생", e);
			result.success = false;
			result.message = "지급전표 취소 중 오류가 발생했습니다: " + e.getMessage();
			return result;
		}
	}

	// tb_aa010 삭제 (지급전표번호로)
	private int deleteAa010ByPaySlip(String custcd, String spjangcd, String paydate, String paynum) {
		MapSqlParameterSource sqlParam = new MapSqlParameterSource();
		sqlParam.addValue("custcd", custcd);
		sqlParam.addValue("spjangcd", spjangcd);
		sqlParam.addValue("spdate", paydate);
		sqlParam.addValue("spnum", paynum);

		String sql = """
        DELETE FROM TB_AA010
        WHERE custcd = :custcd
          AND spjangcd = :spjangcd
          AND spdate = :spdate
          AND spnum = :spnum
    """;

		return sqlRunner.execute(sql, sqlParam);
	}

	// tb_bank_cdsave 지급전표정보 초기화
	private int clearPaymentSlipInfo(String bizNo) {
		MapSqlParameterSource sqlParam = new MapSqlParameterSource();
		sqlParam.addValue("biz_no", bizNo);

		String sql = """
        UPDATE tb_bank_cdsave
        SET paydate = NULL,
            paynum = NULL,
            pay_cancel_yn = NULL
        WHERE biz_no = :biz_no
    """;

		return sqlRunner.execute(sql, sqlParam);
	}

	private Map<String, Object> getAccountInfo(String accnm) {
		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("accnm", accnm);

		String sql = """
        SELECT acccd, accnm
        FROM tb_ac001
        WHERE useyn = '1'
          AND replace(isnull(accnm, ''), ' ', '') LIKE '%' + replace(:accnm, ' ', '') + '%'
        """;

		List<Map<String, Object>> rows = sqlRunner.getRows(sql, param);

		if (rows != null && !rows.isEmpty()) {
			return rows.get(0);
		} else {
			return Collections.emptyMap();
		}
	}


	public AjaxResult updateSelected(String itemsJson) {
		AjaxResult result = new AjaxResult();
		try {
			ObjectMapper mapper = new ObjectMapper();
			List<Map<String, Object>> items = mapper.readValue(itemsJson, new TypeReference<>() {});

			String spjangcd = TenantContext.get();
			Map<String, String> bizInfo = getBizInfoBySpjangcd(spjangcd);
			String custcd = bizInfo.get("custcd");

			String sql = """
            UPDATE TB_bank_cdsave
            SET 
                pay_acccd2 = :pay_acccd2,
                pay_accnm2 = :pay_accnm2
            WHERE custcd   = :custcd
              AND spjangcd = :spjangcd
              AND bnkcode  = :bnkcode
              AND biz_no   = :biz_no
              AND apv_dt   = :apv_dt
              AND apv_no   = :apv_no
        """;

			for (Map<String, Object> item : items) {
				MapSqlParameterSource param = new MapSqlParameterSource();
				param.addValue("pay_acccd2", item.get("acccd2"));
				param.addValue("pay_accnm2", item.get("accnm2"));
				param.addValue("custcd",     custcd);
				param.addValue("spjangcd",   spjangcd);
				param.addValue("bnkcode",    item.get("bnkcode"));
				param.addValue("biz_no",     item.get("biz_no"));
				param.addValue("apv_dt", String.valueOf(item.get("apv_dt")).replace("-", ""));
				param.addValue("apv_no",     item.get("apv_no"));

//				log.info("[카드지급 저장] pay_acccd2={}, pay_accnm2={}, custcd={}, spjangcd={}, bnkcode={}, biz_no={}, apv_dt={}, apv_no={}",
//					item.get("acccd2"),
//					item.get("accnm2"),
//					custcd,
//					spjangcd,
//					item.get("bnkcode"),
//					item.get("biz_no"),
//					String.valueOf(item.get("apv_dt")).replace("-", ""),
//					item.get("apv_no")
//				);

				int updated = sqlRunner.execute(sql, param);
				log.info("[카드지급 저장] UPDATE 결과 = {} 건", updated);
			}

			result.success = true;
			result.message = "저장되었습니다.";

		} catch (Exception e) {
			result.success = false;
			result.message = e.getMessage();
		}
		return result;
	}

}
