package mes.app.account_management.service;

import com.baroservice.api.BarobillApiService;
import com.baroservice.ws.CardApprovalLog;
import com.baroservice.ws.PagedCardApprovalLog;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import mes.app.account_management.util.BarobillErrorUtil;
import mes.app.common.TenantContext;
import mes.domain.model.AjaxResult;
import mes.domain.services.SqlRunner;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
public class CardHistoryService {

	@Autowired
	@Qualifier("mainSqlRunner")
	SqlRunner mainSqlRunner;

	@Autowired
	SqlRunner sqlRunner;

	@Value("${barobill.certKey}")
//	@Value("${barobill.testkey}")
	private String certKey;

	@Autowired
	BarobillApiService barobillApiService;

	@Autowired
	private ObjectMapper objectMapper;

	public List<Map<String, Object>> getCardHistoryList(String start, String end, String cardNo,String accflag) {
		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("start", start);
		param.addValue("end", end);
		String tenantId = TenantContext.get();
		param.addValue("spjangcd", tenantId);
		param.addValue("card_no", cardNo);
		param.addValue("accflag", accflag);

		String sql = """
			select
					biz_no,
					bnkcode,
					card_no, 
					-- 승인정보
					STUFF(STUFF(apv_dt,5,0,'-'),8,0,'-') as apv_dt,
					apv_no,
					STUFF(STUFF(apv_tm,3,0,':'),6,0,':') as apv_tm,	
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
					paydate,
					paynum,
						acccd2,
					accnm2,
					pay_cancel_yn,
					-- 화면 표시용
					case
							when mijdate is not null and mijnum is not null
							then STUFF(STUFF(mijdate,5,0,'-'),8,0,'-') + '/' + mijnum
							else ''
					end as slip_info
	
			from tb_bank_cdsave
			where spjangcd = :spjangcd
			and apv_dt between :start and :end
			""";

		if (cardNo != null && !cardNo.trim().isEmpty()) {
			sql += """
        and replace(card_no, '-', '') like :card_no
    """;

			param.addValue("card_no", "%" + cardNo.trim().replace("-", "") + "%");
		}

		if (accflag != null && !accflag.trim().isEmpty()) {
			sql += """
        and CASE
              WHEN ISNULL(NULLIF(mijdate, ''), '') <> ''
               AND ISNULL(NULLIF(mijnum, ''), '') <> ''
              THEN '1'
              ELSE '0'
            END = :accflag
    """;
			param.addValue("accflag", accflag.trim());
		}

		sql += """
			order by apv_dt desc, apv_tm desc;
			""";

		return sqlRunner.getRows(sql, param);

	}

	public List<Map<String, Object>> getbaroCardList() {
		MapSqlParameterSource param = new MapSqlParameterSource();
		String tenantId = TenantContext.get();
		param.addValue("spjangcd", tenantId);

		String sql = """
			select
					a.cardnum ,
					a.cardnm,
					c.cdcode,
					a.spjangcd, 
			  	c.nm as cardco,
					a.baroid
					from tb_iz010 a
					left join tb_xcard c on a.cardco = c.cd
					where a.useyn ='1' and a.cdflag ='1'
			""";
		return sqlRunner.getRows(sql, param);
	}

	@Transactional
	public AjaxResult requestCardHistory(Map<String, Object> param, Authentication auth) {
		AjaxResult result = new AjaxResult();
		try {
			String spjangcd = TenantContext.get();
			String searchfrdate = (String) param.get("searchfrdate");
			String searchtodate = (String) param.get("searchtodate");
			String cardsJson = (String) param.get("cards");

			if (spjangcd == null || spjangcd.isEmpty()) {
				result.success = false;
				result.message = "사업장 정보가 없습니다.";
				return result;
			}

			if (searchfrdate == null || searchfrdate.isEmpty()
						|| searchtodate == null || searchtodate.isEmpty()) {
				result.success = false;
				result.message = "수집기간이 없습니다.";
				return result;
			}

			if (cardsJson == null || cardsJson.isEmpty()) {
				result.success = false;
				result.message = "선택된 카드가 없습니다.";
				return result;
			}

			Map<String, String> bizInfo = getBizInfoBySpjangcd(spjangcd);

			String custcd = bizInfo.get("custcd");
			String corpNum = bizInfo.get("saupnum").replace("-", "").trim();

			corpNum = corpNum.replace("-", "").trim();

			if (corpNum.isEmpty()) {
				result.success = false;
				result.message = "사업자번호를 찾을 수 없습니다.";
				return result;
			}

			ObjectMapper objectMapper = new ObjectMapper();

			List<Map<String, Object>> cards = objectMapper.readValue(
				cardsJson,
				new TypeReference<List<Map<String, Object>>>() {}
			);

			if (cards == null || cards.isEmpty()) {
				result.success = false;
				result.message = "선택된 카드가 없습니다.";
				return result;
			}

			int totalSavedCount = 0;

			for (Map<String, Object> card : cards) {
				String cardNum = (String) card.get("cardnum");
				String id = (String) card.get("baroid");

				if (cardNum == null || cardNum.isEmpty() || id == null || id.isEmpty()) {
					log.warn("카드정보 누락 - cardNum={}, id={}", cardNum, id);
					continue;
				}

				cardNum = cardNum.replace("-", "").replace(" ", "");

				int savedCount = collectPeriodCardApprovalLog(
					spjangcd,
					custcd,
					corpNum,
					id,
					cardNum,
					searchfrdate,
					searchtodate
				);

				totalSavedCount += savedCount;
			}

			result.success = true;
			result.message = "카드내역 수집이 완료되었습니다. 저장건수: " + totalSavedCount + "건";

		} catch (Exception e) {
			log.error("카드내역 수집 중 오류", e);
			result.success = false;
			result.message = "카드내역 수집 중 오류가 발생했습니다.";
		}

		return result;
	}

	private int collectPeriodCardApprovalLog(
		String spjangcd, String custcd, String corpNum, String id, String cardNum, String startDate, String endDate) {

		int totalSavedCount = 0;

		try {

			int countPerPage = 100;
			int currentPage = 1;
			int orderDirection = 2;

//			log.info("========== 카드내역 조회 시작 ==========");
//			log.info("요청 파라미터 spjangcd={}, custcd={}, corpNum={}, id={}, cardNum={}, startDate={}, endDate={}, countPerPage={}, orderDirection={}",
//				spjangcd, custcd, corpNum, id, maskCardNum(cardNum), startDate, endDate, countPerPage, orderDirection);

			while (true) {

				/*log.info("[바로빌 요청] getPeriodCardApprovalLog 호출 - page={}, corpNum={}, id={}, cardNum={}, startDate={}, endDate={}",
					currentPage, corpNum, id, maskCardNum(cardNum), startDate, endDate);*/

				PagedCardApprovalLog result =
					barobillApiService.card.getPeriodCardApprovalLog(
						certKey,
						corpNum,
						id,
						cardNum,
						startDate,
						endDate,
						countPerPage,
						currentPage,
						orderDirection
					);

				if (result == null) {
//					log.error("[바로빌 응답] result is null - cardNum={}, page={}", maskCardNum(cardNum), currentPage);
					break;
				}

				/*log.info("[바로빌 응답] currentPage={}, maxPageNum={}, countPerPage={}, maxIndex={}",
					result.getCurrentPage(), result.getMaxPageNum(), result.getCountPerPage(), result.getMaxIndex());*/

				if (result.getCurrentPage() < 0) {
					int errorCode = result.getCurrentPage();
					String errorMessage = BarobillErrorUtil.getErrorMessage(errorCode);

					log.error("[바로빌 응답 실패] cardNum={}, page={}, errorCode={}, errorMessage={}",
						maskCardNum(cardNum), currentPage, errorCode, errorMessage);
					break;
				}

				List<CardApprovalLog> logs = result.getCardLogList() != null
																			 ? result.getCardLogList().getCardApprovalLog()
																			 : null;

				int fetchedCount = (logs == null) ? 0 : logs.size();
//				log.info("[바로빌 응답 성공] cardNum={}, page={}, fetchedCount={}",maskCardNum(cardNum), currentPage, fetchedCount);

				if (logs == null || logs.isEmpty()) {
//					log.info("[처리 종료] 조회 결과 없음 - cardNum={}, page={}", maskCardNum(cardNum), currentPage);
					break;
				}

				for (CardApprovalLog logItem : logs) {

					// 취소 / 거절 건 제외
					if ("취소".equals(logItem.getApprovalType()) ||
							"거절".equals(logItem.getApprovalType())) {
						continue;
					}

					String useDT = logItem.getUseDT(); // YYYYMMDDHHMMSS
					String apvDt = "";
					String apvTm = "";

					if (useDT != null && useDT.length() >= 14) {
						apvDt = useDT.substring(0, 8);
						apvTm = useDT.substring(8, 14);
					}

					// 중복 체크
					MapSqlParameterSource dicParam = new MapSqlParameterSource();
					dicParam.addValue("custcd", custcd);
					dicParam.addValue("spjangcd", spjangcd);
					dicParam.addValue("bnkcode", logItem.getApprovalNum());
					dicParam.addValue("bizNo", logItem.getUseKey());

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
						log.info("[중복 제외] 이미 저장된 데이터 - useKey={}, approvalNum={}, cardNum={}",
								nvl(logItem.getUseKey()),
								nvl(logItem.getApprovalNum()),
								maskCardNum(logItem.getCardNum()));
						continue;
					}

					// INSERT
					dicParam.addValue("cardNo", logItem.getCardNum());
					dicParam.addValue("apvNo", logItem.getApprovalNum());
					dicParam.addValue("apvDt", apvDt);
					dicParam.addValue("apvTm", apvTm);
					dicParam.addValue("apvCanYn", "Y");
					dicParam.addValue("buySum", toBigDecimal(logItem.getApprovalAmount()));
					dicParam.addValue("splyAmt", toBigDecimal(logItem.getAmount()));
					dicParam.addValue("vatAmt", toBigDecimal(logItem.getTax()));
					dicParam.addValue("srvFee", toBigDecimal(logItem.getServiceCharge()));
					dicParam.addValue("comm", toBigDecimal(logItem.getTotalAmount()));
					dicParam.addValue("currCd", logItem.getCurrencyCode());
					dicParam.addValue("currAmt", toBigDecimal(logItem.getForeignApprovalAmount()));
					dicParam.addValue("itlmMmsCnt", logItem.getInstallmentMonths());
					dicParam.addValue("mestNm", logItem.getUseStoreName());
					dicParam.addValue("mestBizNo", logItem.getUseStoreCorpNum());
					dicParam.addValue("mestReprNm", logItem.getUseStoreCeo());
					dicParam.addValue("mestTelNo", logItem.getUseStoreTel());
					dicParam.addValue("mestAddr1", logItem.getUseStoreAddr());
					dicParam.addValue("cardTpbzCd", logItem.getUseStoreBizType());
					dicParam.addValue("cardTpbzNm", logItem.getApprovalType());
					dicParam.addValue("flag", "0");

					String insertSql = """
        INSERT INTO TB_bank_cdsave (
            custcd, spjangcd, bnkcode, biz_no,
            card_no, apv_no, apv_dt, apv_tm,
            apv_can_yn, buy_sum, sply_amt, vat_amt,
            srv_fee, comm, curr_cd, curr_amt,
            itlm_mms_cnt, mest_nm, mest_biz_no, mest_repr_nm,
            mest_tel_no, mest_addr_1, card_tpbz_cd, card_tpbz_nm,
            flag
        ) VALUES (
            :custcd, :spjangcd, :bnkcode, :bizNo,
            :cardNo, :apvNo, :apvDt, :apvTm,
            :apvCanYn, :buySum, :splyAmt, :vatAmt,
            :srvFee, :comm, :currCd, :currAmt,
            :itlmMmsCnt, :mestNm, :mestBizNo, :mestReprNm,
            :mestTelNo, :mestAddr1, :cardTpbzCd, :cardTpbzNm,
            :flag
        )
        """;

					try {
						this.sqlRunner.execute(insertSql, dicParam);
						totalSavedCount++;
					} catch (Exception e) {
						log.error("[DB 저장 실패] approvalNum={}, useKey={}, cardNum={}",
								nvl(logItem.getApprovalNum()),
								nvl(logItem.getUseKey()),
								maskCardNum(logItem.getCardNum()),
								e);
						throw e;
					}
				}
				if (currentPage >= result.getMaxPageNum()) {
					log.info("[페이지 종료] 마지막 페이지 도달 - currentPage={}, maxPageNum={}",
						currentPage, result.getMaxPageNum());
					break;
				}

				currentPage++;
			}

			log.info("========== 카드내역 조회 종료 - 총 저장건수: {} ==========", totalSavedCount);

		} catch (Exception e) {
			log.error("카드내역 조회 오류 - spjangcd={}, corpNum={}, id={}, cardNum={}, startDate={}, endDate={}",
				spjangcd, corpNum, id, maskCardNum(cardNum), startDate, endDate, e);
		}

		return totalSavedCount;
	}

	private BigDecimal toBigDecimal(String value) {
		if (value == null || value.trim().isEmpty()) {
			return BigDecimal.ZERO;
		}
		return new BigDecimal(value);
	}

	private String maskCardNum(String cardNum) {
		if (cardNum == null || cardNum.length() < 8) {
			return "INVALID";
		}

		return cardNum.substring(0, 4)
						 + "********"
						 + cardNum.substring(cardNum.length() - 4);
	}

	private String nvl(String s) {
		return s == null ? "" : s;
	}

	private int len(String s) {
		return s == null ? 0 : s.length();
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

	public List<Map<String, Object>> getBusim(String busim) {
		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("busim", busim);

		String sql = """
			select
					bsdate,
					bseccd,
					busicd as buiscd,
					businm as busim
			from tb_x0002
			where replace(businm,' ','')
						like '%' + replace(:busim,' ','') + '%'
			order by bsdate DESC
			""";

		return sqlRunner.getRows(sql, param);
	}

	public List<Map<String, Object>> getAccnm(String accnm, String type) {
		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("accnm", accnm);

		String typeCondition = "card".equals(type) ? "and acccd like '7%'" : "";

		String sql = """
    select
      acccd,
      accnm
    from tb_ac001
    where useyn = '1' and spyn='1'
    and replace(isnull(accnm, ''), ' ', '') like '%' + replace(:accnm, ' ', '') + '%'
    """
		+ typeCondition;

		return sqlRunner.getRows(sql, param);
	}

	public List<Map<String, Object>> getIt1nm(String it1nm) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("it1nm", it1nm);

		String sql = """
			select it1cd ,it1nm from tb_x0003 
			where useyn='1'
			 and replace(isnull(it1nm, ''), ' ', '') like '%' + replace(:it1nm, ' ', '') + '%'
			""";
		return sqlRunner.getRows(sql, param);
	}

	public List<Map<String, Object>> getIt2nm(String it2nm) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("it2nm", it2nm);

		String sql = """
			select it2cd ,it2nm from tb_x0004
			where useyn='1'
			and replace(isnull(it2nm, ''), ' ', '') like '%' + replace(:it2nm, ' ', '') + '%'
			""";
		return sqlRunner.getRows(sql, param);

	}

	@Transactional
	public AjaxResult updateSelected(String itemsJson) {
		AjaxResult result = new AjaxResult();

		try {

//			log.info("========== 카드내역 저장 시작 ==========");
//			log.info("입력 JSON: {}", itemsJson);

			if (itemsJson == null || itemsJson.trim().isEmpty()) {
//				log.warn("itemsJson이 비어있음");
				result.success = false;
				result.message = "저장할 데이터가 없습니다.";
				return result;
			}

			List<Map<String, Object>> items = objectMapper.readValue(
				itemsJson,
				new TypeReference<List<Map<String, Object>>>() {}
			);

//			log.info("파싱된 items 개수: {}", items.size());

			if (items == null || items.isEmpty()) {
				result.success = false;
				result.message = "저장할 데이터가 없습니다.";
				return result;
			}

			int updateCount = 0;
			String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));

			for (Map<String, Object> item : items) {

//				log.info("처리 대상 item: {}", item);

				String spjangcd = TenantContext.get();
				Map<String, String> bizInfo = getBizInfoBySpjangcd(spjangcd);

				String custcd = bizInfo.get("custcd");
				String bnkcode = getString(item.get("bnkcode"));
				String bizNo = getString(item.get("biz_no"));

				/*log.info("PK 조회 정보 custcd={}, spjangcd={}, bnkcode={}, bizNo={}",
					custcd, spjangcd, bnkcode, bizNo);

				if (isEmpty(custcd) || isEmpty(spjangcd) || isEmpty(bizNo)) {
					log.warn("PK 정보 부족 → skip");
					continue;
				}*/

				MapSqlParameterSource dicParam = new MapSqlParameterSource();
				dicParam.addValue("custcd", custcd);
				dicParam.addValue("spjangcd", spjangcd);
				dicParam.addValue("bnkcode", bnkcode);
				dicParam.addValue("bizNo", bizNo);
				dicParam.addValue("bsdate", getString(item.get("bsdate")));
				dicParam.addValue("bseccd", getString(item.get("bseccd")));
				dicParam.addValue("buiscd", getString(item.get("buiscd")));
				dicParam.addValue("busim", getString(item.get("busim")));
				dicParam.addValue("acccd", getString(item.get("acccd")));
				dicParam.addValue("accnm", getString(item.get("accnm")));
				dicParam.addValue("it1cd", getString(item.get("it1cd")));
				dicParam.addValue("it1nm", getString(item.get("it1nm")));
				dicParam.addValue("it2cd", getString(item.get("it2cd")));
				dicParam.addValue("it2nm", getString(item.get("it2nm")));
				dicParam.addValue("mssec", getString(item.get("mssec")));
				dicParam.addValue("mssecnm", getString(item.get("mssecnm")));
				dicParam.addValue("summy", getString(item.get("summy")));
				dicParam.addValue("remark", getString(item.get("remark")));
				dicParam.addValue("subject", getString(item.get("subject")));
				dicParam.addValue("lstModDt", now);
				dicParam.addValue("acccd2", getString(item.get("acccd2")));
				dicParam.addValue("accnm2", getString(item.get("accnm2")));

				String updateSql = """
    UPDATE TB_bank_cdsave
    SET
        bsdate   = :bsdate,
        bseccd   = :bseccd,
        buiscd   = :buiscd,
        busim    = :busim,
        acccd    = :acccd,
        accnm    = :accnm,
        it1cd    = :it1cd,
        it1nm    = :it1nm,
        it2cd    = :it2cd,
        it2nm    = :it2nm,
        mssec    = :mssec,
        mssecnm  = :mssecnm,
        summy    = :summy,
        remark   = :remark,
        subject  = :subject,
        lst_mod_dt = :lstModDt,
        acccd2 = :acccd2,
        accnm2 = :accnm2
    WHERE custcd   = :custcd
      AND spjangcd = :spjangcd
      AND bnkcode  = :bnkcode
      AND biz_no   = :bizNo
    """;

				this.sqlRunner.execute(updateSql, dicParam);

				log.info("저장 완료 PK={} / {}", bnkcode, bizNo);

				updateCount++;
			}

//			log.info("총 저장 건수: {}", updateCount);
//			log.info("========== 카드내역 저장 종료 ==========");

			result.success = true;
			result.message = updateCount + "건 저장되었습니다.";
			result.data = updateCount;
			return result;

		} catch (Exception e) {

			log.error("카드내역 저장 중 오류 발생", e);

			result.success = false;
			result.message = "저장 중 오류가 발생했습니다. " + e.getMessage();
			return result;
		}
	}

	private String getString(Object obj) {
		return obj == null ? "" : String.valueOf(obj).trim();
	}

	private boolean isEmpty(String str) {
		return str == null || str.trim().isEmpty();
	}

	// 카드내역 스케줄러
	/*public void collectCardHistoryByScheduler() {

		// 조회 기간 (어제 ~ 오늘)
		LocalDate today = LocalDate.now();
		LocalDate yesterday = today.minusDays(1);
		String startDate = yesterday.format(DateTimeFormatter.BASIC_ISO_DATE);
		String endDate = today.format(DateTimeFormatter.BASIC_ISO_DATE);

		// Main DB에서 전체 사업장 목록 조회 (dbKey=null → RoutingDataSource가 Main DB 사용)
		TenantContext.clear();
		List<Map<String, Object>> tenants = getAllTenants();

		if (tenants.isEmpty()) {
			log.info("스케줄러 - 등록된 사업장 없음");
			return;
		}

		for (Map<String, Object> tenant : tenants) {
			String spjangcd = getString(tenant.get("spjangcd"));
			String dbKey    = getString(tenant.get("db_key"));
			String custcd   = getString(tenant.get("custcd"));
			String corpNum  = getString(tenant.get("saupnum")).replace("-", "").trim();

			if (corpNum.isEmpty()) {
				log.warn("스케줄러 - 사업자번호 없음, spjangcd={}", spjangcd);
				continue;
			}

			try {
				// 사업장 DB로 라우팅
				TenantContext.set(spjangcd);
				TenantContext.setDbKey(dbKey);

				List<Map<String, Object>> cards = getbaroCardList();

				if (cards == null || cards.isEmpty()) {
					log.info("스케줄러 - 등록된 카드 없음, spjangcd={}", spjangcd);
					continue;
				}

				int totalSavedCount = 0;

				for (Map<String, Object> card : cards) {
					String cardNum = getString(card.get("cardnum"));
					String id      = getString(card.get("baroid"));
					String spjangcd1 = getString(card.get("spjangcd"));

					if (cardNum.isEmpty() || id.isEmpty()) {
						log.warn("스케줄러 카드정보 누락 cardNum={}, id={}", cardNum, id);
						continue;
					}

					cardNum = cardNum.replace("-", "").replace(" ", "");

					int savedCount = collectPeriodCardApprovalLog(
						spjangcd1, custcd, corpNum, id, cardNum, startDate, endDate
					);

					totalSavedCount += savedCount;
				}

				log.info("스케줄러 카드내역 수집 완료 - spjangcd={}, 저장건수={}", spjangcd, totalSavedCount);

			} catch (Exception e) {
				log.error("스케줄러 카드내역 수집 오류 - spjangcd={}", spjangcd, e);
			} finally {
				TenantContext.clear();
			}
		}
	}

	private List<Map<String, Object>> getAllTenants() {
		String sql = """
			SELECT spjangcd, db_key, custcd, saupnum
			FROM tb_xa012
			WHERE db_key IS NOT NULL AND db_key <> ''
		""";
		return sqlRunner.getRows(sql, new MapSqlParameterSource());
	}*/

	public void collectCardHistoryByScheduler() {

		LocalDate today = LocalDate.now();
		LocalDate yesterday = today.minusDays(1);
		String startDate = yesterday.format(DateTimeFormatter.BASIC_ISO_DATE);
		String endDate = today.format(DateTimeFormatter.BASIC_ISO_DATE);

		TenantContext.clear();
		List<Map<String, Object>> tenants = getTenantList();

		if (tenants.isEmpty()) {
			log.info("스케줄러 - 등록된 사업장 없음");
			return;
		}

		for (Map<String, Object> tenant : tenants) {
			String spjangcd = getString(tenant.get("spjangcd")); // "TO", "UV" - 라우팅용
			String custcd   = getString(tenant.get("custcd"));   // "S_KCF", "S_KALF" - 외부DB 조회용

			try {
				// 1. 외부 DB 라우팅
				TenantContext.set(spjangcd);
				TenantContext.setDbKey(spjangcd);

				// 2. 외부 DB에서 custcd 기준으로 사업장 목록 조회
				List<Map<String, String>> bizList = getBizInfoFromTenantDb(custcd);

				if (bizList.isEmpty()) {
					log.warn("스케줄러 - 외부DB 사업장 없음, custcd={}", custcd);
					continue;
				}

				for (Map<String, String> bizInfo : bizList) {
					String realSpjangcd = bizInfo.get("spjangcd");
					String corpNum      = bizInfo.get("saupnum").replace("-", "").trim();
					String realCustcd   = bizInfo.get("custcd");

					if (corpNum.isEmpty()) {
						log.warn("스케줄러 - 사업자번호 없음, spjangcd={}", realSpjangcd);
						continue;
					}

					// SQL 필터용 spjangcd를 외부 DB 실제 값으로 교체
					TenantContext.set(realSpjangcd);

					List<Map<String, Object>> cards = getbaroCardList();

					if (cards == null || cards.isEmpty()) {
						log.info("스케줄러 - 등록된 카드 없음, spjangcd={}", realSpjangcd);
						continue;
					}

					int totalSavedCount = 0;

					for (Map<String, Object> card : cards) {
						String cardNum = getString(card.get("cardnum"));
						String id      = getString(card.get("baroid"));

						if (cardNum.isEmpty() || id.isEmpty()) {
							log.warn("스케줄러 카드정보 누락 cardNum={}, id={}", cardNum, id);
							continue;
						}

						cardNum = cardNum.replace("-", "").replace(" ", "");

						int savedCount = collectPeriodCardApprovalLog(
							realSpjangcd, realCustcd, corpNum, id, cardNum, startDate, endDate
						);

						totalSavedCount += savedCount;
					}

					log.info("스케줄러 카드내역 수집 완료 - spjangcd={}, 저장건수={}", realSpjangcd, totalSavedCount);
				}

			} catch (Exception e) {
				log.error("스케줄러 카드내역 수집 오류 - spjangcd={}", spjangcd, e);
			} finally {
				TenantContext.clear();
			}
		}
	}

	// Main DB에서 spjangcd, custcd 조회
	private List<Map<String, Object>> getTenantList() {
		String sql = """
        SELECT spjangcd, custcd
        FROM tb_tenant_db
        WHERE spjangcd IS NOT NULL AND spjangcd <> ''
    """;
		return mainSqlRunner.getRows(sql, new MapSqlParameterSource());
	}

	// 외부 DB에서 custcd 기준으로 사업장 목록 조회
	private List<Map<String, String>> getBizInfoFromTenantDb(String custcd) {
		String sql = """
        SELECT spjangcd, custcd, saupnum
        FROM tb_xa012
        WHERE custcd = :custcd
    """;
		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("custcd", custcd);

		List<Map<String, Object>> rows = sqlRunner.getRows(sql, param);

		List<Map<String, String>> result = new ArrayList<>();

		for (Map<String, Object> row : rows) {
			Map<String, String> info = new HashMap<>();
			info.put("spjangcd", row.get("spjangcd") == null ? "" : String.valueOf(row.get("spjangcd")).trim());
			info.put("custcd",   row.get("custcd")   == null ? "" : String.valueOf(row.get("custcd")).trim());
			info.put("saupnum",  row.get("saupnum")  == null ? "" : String.valueOf(row.get("saupnum")).trim());
			result.add(info);
		}

		return result;
	}
	//전표생성
	@Transactional
	public AjaxResult createSlip(String items, String userId) {

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

			String spjangcd = TenantContext.get();
			Map<String, String> bizInfo = getBizInfoBySpjangcd(spjangcd);
			String custcd = bizInfo.get("custcd");
			String spjangnm = bizInfo.get("spjangnm");

			// =========================
			// 건별로 루프 처리
			// =========================
			for (Map<String, Object> item : itemList) {

				// ✅ spnum, spdate, seq 건별로 생성
				String apvDt = getString(item, "apv_dt");
				String spdate = (apvDt != null && !apvDt.isBlank())
													? apvDt.replace("-", "")
													: LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
				String spnum = String.format("%04d", getNextSpnumInt());
				int seq = 1;

				BigDecimal buySum = getBigDecimal(item, "buy_sum");
				String cardNum = getString(item, "card_no");
				String summy = getString(item, "summy");
				String bizNo = getString(item, "biz_no");

				// =========================
				// 헤더 건별 생성 (tb_aa009)
				// =========================
				MapSqlParameterSource dicParam = new MapSqlParameterSource();
				dicParam.addValue("custcd", custcd);
				dicParam.addValue("spjangcd", spjangcd);
				dicParam.addValue("spdate", spdate);
				dicParam.addValue("spnum", spnum);
				dicParam.addValue("tiosec", "2");
				dicParam.addValue("busipur", "3");
				dicParam.addValue("spoccu", "AA");
				dicParam.addValue("cashyn", "0");
				dicParam.addValue("bsdate", getString(item, "bsdate"));      // ✅ firstItem → item
				dicParam.addValue("bseccd", getString(item, "bseccd"));      // ✅ firstItem → item
				dicParam.addValue("busicd", getString(item, "buiscd"));      // ✅ firstItem → item
				dicParam.addValue("remark", getString(item, "remark"));      // ✅ firstItem → item
				dicParam.addValue("subject", getString(item, "subject"));    // ✅ firstItem → item
				dicParam.addValue("spjangnm", spjangnm);
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
                        subject, spjangnm, inputdate, inputid
                    ) VALUES (
                        :custcd, :spjangcd, :spdate, :spnum,
                        :tiosec, :busipur, :spoccu, :cashyn,
                        :bsdate, :bseccd, :busicd, :remark,
                        :subject, :spjangnm, :inputdate, :inputid
                    )
                    """;
					this.sqlRunner.execute(insertSql, dicParam);
				}

				// =============================
				// 차변 (tb_aa010)
				// =============================
				MapSqlParameterSource debitParam = new MapSqlParameterSource();
				debitParam.addValue("custcd", custcd);
				debitParam.addValue("spjangcd", spjangcd);
				debitParam.addValue("spdate", spdate);
				debitParam.addValue("spnum", spnum);
				debitParam.addValue("spseq", String.format("%04d", seq++));  // 0001
				debitParam.addValue("spjangnm", spjangnm);
				debitParam.addValue("bumuncd", "AA");
				debitParam.addValue("acccd", getString(item, "acccd"));
				debitParam.addValue("accnm", getString(item, "accnm"));
				debitParam.addValue("it1cd", StringUtils.leftPad(getString(item, "it1cd"), 5, "0"));
				debitParam.addValue("it2cd", getString(item, "it2cd"));
				debitParam.addValue("drcr", "1");
				debitParam.addValue("dramt", buySum);
				debitParam.addValue("cramt", BigDecimal.ZERO);
				debitParam.addValue("mssec", getString(item, "mssec"));
				debitParam.addValue("tiosec", "2");
				debitParam.addValue("summy", summy);
				debitParam.addValue("spoccu", "AA");
				debitParam.addValue("inputdate", LocalDateTime.now());
				debitParam.addValue("rowseq", new BigDecimal(1));
				debitParam.addValue("cardnum", null);

				String debitCheckSql = """
                SELECT COUNT(*) AS cnt
                FROM tb_aa010
                WHERE custcd   = :custcd
                  AND spjangcd = :spjangcd
                  AND spdate   = :spdate
                  AND spnum    = :spnum
                  AND spseq    = :spseq
                """;

				List<Map<String, Object>> debitCheck = this.sqlRunner.getRows(debitCheckSql, debitParam);
				int debitCount = ((Number) debitCheck.get(0).get("cnt")).intValue();

				if (debitCount > 0) {
					String updateSql = """
                    UPDATE tb_aa010
                    SET
                        spjangnm  = :spjangnm,
                        bumuncd   = :bumuncd,
                        acccd     = :acccd,
                        accnm     = :accnm,
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
					this.sqlRunner.execute(updateSql, debitParam);
				} else {
					String insertSql = """
                    INSERT INTO tb_aa010 (
                        custcd, spjangcd, spdate, spnum, spseq,
                        spjangnm, bumuncd, acccd, accnm,
                        it1cd, it2cd, drcr, dramt, cramt,
                        mssec, tiosec, summy, spoccu,
                        inputdate, rowseq
                    ) VALUES (
                        :custcd, :spjangcd, :spdate, :spnum, :spseq,
                        :spjangnm, :bumuncd, :acccd, :accnm,
                        :it1cd, :it2cd, :drcr, :dramt, :cramt,
                        :mssec, :tiosec, :summy, :spoccu,
                        :inputdate, :rowseq
                    )
                    """;
					this.sqlRunner.execute(insertSql, debitParam);
				}

				// =============================
				// 대변 (tb_aa010)
				// =============================
				MapSqlParameterSource creditParam = new MapSqlParameterSource();
				creditParam.addValue("custcd", custcd);
				creditParam.addValue("spjangcd", spjangcd);
				creditParam.addValue("spdate", spdate);
				creditParam.addValue("spnum", spnum);
				creditParam.addValue("spseq", String.format("%04d", seq++));  // 0002
				creditParam.addValue("spjangnm", spjangnm);
				creditParam.addValue("bumuncd", "AA");
				creditParam.addValue("acccd", "2152");
				creditParam.addValue("accnm", "미지급금(카드)");
				creditParam.addValue("it1cd", StringUtils.leftPad(getString(item, "it1cd"), 5, "0"));
				creditParam.addValue("it2cd", getString(item, "it2cd"));
				creditParam.addValue("drcr", "2");
				creditParam.addValue("dramt", BigDecimal.ZERO);
				creditParam.addValue("cramt", buySum);
				creditParam.addValue("mssec", getString(item, "mssec"));
				creditParam.addValue("tiosec", "2");
				creditParam.addValue("summy", summy);
				creditParam.addValue("cardnum", cardNum);
				creditParam.addValue("spoccu", "AA");
				creditParam.addValue("inputdate", LocalDateTime.now());
				creditParam.addValue("rowseq", new BigDecimal(2));

				String creditCheckSql = """
                SELECT COUNT(*) AS cnt
                FROM tb_aa010
                WHERE custcd   = :custcd
                  AND spjangcd = :spjangcd
                  AND spdate   = :spdate
                  AND spnum    = :spnum
                  AND spseq    = :spseq
                """;

				List<Map<String, Object>> creditCheck = this.sqlRunner.getRows(creditCheckSql, creditParam);
				int creditCount = ((Number) creditCheck.get(0).get("cnt")).intValue();

				if (creditCount > 0) {
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
					this.sqlRunner.execute(updateSql, creditParam);
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
					this.sqlRunner.execute(insertSql, debitParam);  // ✅ creditParam 으로 확인 필요
					this.sqlRunner.execute(insertSql, creditParam);
				}

				// 카드내역 업데이트
				updateCardSlipInfo(bizNo, spdate, spnum);
			}

			result.success = true;
			result.message = "전표가 생성되었습니다.";
			return result;

		} catch (Exception e) {
			e.printStackTrace();
			result.success = false;
			result.message = "전표 생성 중 오류가 발생했습니다. " + e.getMessage();
			return result;
		}
	}

	private int updateCardSlipInfo(String bizNo, String spdate, String spnum) {
		MapSqlParameterSource sqlParam = new MapSqlParameterSource();
		sqlParam.addValue("biz_no", bizNo);
		sqlParam.addValue("mijdate", spdate);
		sqlParam.addValue("mijnum", spnum);

		String sql = """
        UPDATE tb_bank_cdsave 
        SET mijdate = :mijdate, 
            mijnum = :mijnum
        WHERE biz_no = :biz_no
    """;

		return sqlRunner.execute(sql, sqlParam);
	}

	//전표 생성 취소
	@Transactional
	public AjaxResult cancelSlip(String items, String userId) {
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

			// 전표번호별로 그룹화 (같은 전표는 한 번만 삭제)
			Map<String, List<Map<String, Object>>> groupedBySlip = new HashMap<>();

			for (Map<String, Object> item : itemList) {
				String bizNo = getString(item, "biz_no");
				String slipInfo = getString(item, "slip_info");
				String payCancelYn = getString(item, "pay_cancel_yn");

				// 필수값 검증
				if (isBlank(bizNo)) {
					failCount++;
					errorMsg.append("필수 데이터가 누락된 항목이 있습니다.\n");
					continue;
				}

				// slip_info가 없으면 전표가 생성되지 않은 것
				if (isBlank(slipInfo)) {
					failCount++;
					errorMsg.append("전표가 생성되지 않은 항목입니다. (승인번호: ").append(getString(item, "apv_no")).append(")\n");
					continue;
				}

				// pay_cancel_yn 체크
				if ("Y".equals(payCancelYn)) {
					failCount++;
					errorMsg.append("지급전표가 발행된 항목은 취소할 수 없습니다. (승인번호: ").append(getString(item, "apv_no")).append(")\n");
					continue;
				}

				// 전표번호별로 그룹화
				if (!groupedBySlip.containsKey(slipInfo)) {
					groupedBySlip.put(slipInfo, new ArrayList<>());
				}
				groupedBySlip.get(slipInfo).add(item);
			}

			// 전표번호별로 처리
			for (Map.Entry<String, List<Map<String, Object>>> entry : groupedBySlip.entrySet()) {
				String slipInfo = entry.getKey();
				List<Map<String, Object>> slipItems = entry.getValue();

				try {
					// slip_info 파싱 (예: "2024-03-20/0001" → spdate="20240320", spnum="0001")
					String[] parts = slipInfo.split("/");
					if (parts.length != 2) {
						failCount += slipItems.size();
						errorMsg.append("전표번호 형식이 올바르지 않습니다: ").append(slipInfo).append("\n");
						continue;
					}

					String spdate = parts[0].replace("-", "");
					String spnum = parts[1];

					// 1. tb_aa010 삭제 (상세) - custcd, spjangcd, spdate, spnum으로 삭제
					int deletedAa010 = deleteAa010BySlip(custcd, spjangcd, spdate, spnum);
//					log.info("tb_aa010 삭제 완료: {} 건, spdate={}, spnum={}", deletedAa010, spdate, spnum);

					// 2. tb_aa009 삭제 (헤더) - 복합키로 삭제
					MapSqlParameterSource dicParam = new MapSqlParameterSource();
					dicParam.addValue("custcd", custcd);
					dicParam.addValue("spjangcd", spjangcd);
					dicParam.addValue("spdate", spdate);
					dicParam.addValue("spnum", spnum);

					String deleteSql = """
    DELETE FROM tb_aa009
    WHERE custcd   = :custcd
      AND spjangcd = :spjangcd
      AND spdate   = :spdate
      AND spnum    = :spnum
    """;

					this.sqlRunner.execute(deleteSql, dicParam);
//					log.info("tb_aa009 삭제 완료: custcd={}, spjangcd={}, spdate={}, spnum={}", custcd, spjangcd, spdate, spnum);

					// 3. tb_bank_cdsave 업데이트 (전표정보 초기화)
					for (Map<String, Object> item : slipItems) {
						String bizNo = getString(item, "biz_no");
						int updated = clearCardSlipInfo(bizNo);

						if (updated > 0) {
							successCount++;
//							log.info("카드내역 전표정보 초기화 완료: biz_no={}", bizNo);
						} else {
							failCount++;
							errorMsg.append("카드내역 업데이트 실패 (승인번호: ").append(getString(item, "apv_no")).append(")\n");
						}
					}

				} catch (Exception e) {
					log.error("전표 취소 중 오류 발생: slipInfo={}", slipInfo, e);
					failCount += slipItems.size();
					errorMsg.append("전표 취소 실패: ").append(slipInfo).append(" - ").append(e.getMessage()).append("\n");
				}
			}

			if (failCount > 0) {
				result.success = false;
				result.message = "성공: " + successCount + "건, 실패: " + failCount + "건\n" + errorMsg.toString();
				return result;
			}

			result.success = true;
			result.message = "전표 취소가 완료되었습니다. (총 " + successCount + "건)";
			return result;

		} catch (Exception e) {
			log.error("전표 취소 중 오류 발생", e);
			result.success = false;
			result.message = "전표 취소 중 오류가 발생했습니다: " + e.getMessage();
			return result;
		}
	}

	// tb_aa010 삭제 (전표번호로) - spseq 없이 custcd, spjangcd, spdate, spnum으로 삭제
	private int deleteAa010BySlip(String custcd, String spjangcd, String spdate, String spnum) {
		MapSqlParameterSource sqlParam = new MapSqlParameterSource();
		sqlParam.addValue("custcd", custcd);
		sqlParam.addValue("spjangcd", spjangcd);
		sqlParam.addValue("spdate", spdate);
		sqlParam.addValue("spnum", spnum);

		String sql = """
        DELETE FROM TB_AA010
        WHERE custcd = :custcd
          AND spjangcd = :spjangcd
          AND spdate = :spdate
          AND spnum = :spnum
    """;

		return sqlRunner.execute(sql, sqlParam);
	}

	// tb_bank_cdsave 전표정보 초기화
	private int clearCardSlipInfo(String bizNo) {
		MapSqlParameterSource sqlParam = new MapSqlParameterSource();
		sqlParam.addValue("biz_no", bizNo);

		String sql = """
        UPDATE tb_bank_cdsave
        SET mijdate = NULL,
            mijnum = NULL
        WHERE biz_no = :biz_no
    """;

		return sqlRunner.execute(sql, sqlParam);
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

	public List<Map<String, Object>> mestHistory() {
		MapSqlParameterSource sqlParam = new MapSqlParameterSource();
		String spjangcd = TenantContext.get();
		Map<String, String> bizInfo = getBizInfoBySpjangcd(spjangcd);
		String custcd = bizInfo.get("custcd");

		sqlParam.addValue("custcd",custcd);
		sqlParam.addValue("spjangcd",spjangcd );

		String sql = """
        SELECT
            mest_nm,
            acccd,
            accnm,
            it1cd,
            it1nm,
            it2cd,
            it2nm,
            acccd2,
            accnm2
        FROM (
            SELECT
                mest_nm,
                acccd,
                accnm,
                it1cd,
                it1nm,
                it2cd,
                it2nm,
                acccd2,
                accnm2,
                ROW_NUMBER() OVER (
                    PARTITION BY mest_nm
                    ORDER BY apv_dt DESC, apv_tm DESC
                ) AS rn
            FROM TB_bank_cdsave
            WHERE custcd   = :custcd
              AND spjangcd = :spjangcd
              AND acccd    IS NOT NULL AND acccd   != ''
              AND it1cd    IS NOT NULL AND it1cd   != ''
              AND it2cd    IS NOT NULL AND it2cd   != ''
              AND mest_nm  IS NOT NULL AND mest_nm != ''
        ) t
        WHERE rn = 1
        """;
		return sqlRunner.getRows(sql, sqlParam);
	}
}
