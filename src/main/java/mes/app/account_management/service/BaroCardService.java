package mes.app.account_management.service;

import com.baroservice.api.BarobillApiService;
import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import mes.domain.model.AjaxResult;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Service
public class BaroCardService {

	@Autowired
	SqlRunner sqlRunner;

	@Value("${barobill.certKey}")
//	@Value("${barobill.testkey}")
	private String certKey;

	@Autowired
	BarobillApiService barobillApiService;

	//카드 등록
	public AjaxResult baroNewCardSave(Map<String, Object> param, Authentication auth) {

		AjaxResult result = new AjaxResult();

		try {
			log.info("========== 카드등록 시작 ==========");

			String spjangcd = TenantContext.get();
			String cardType  = getString(param.get("cardType"));
			String cardnum   = getString(param.get("cardnum")).trim();
			String cardwebid = getString(param.get("cardwebid")).trim();
			String cardwebpw = getString(param.get("cardwebpw")).trim();
			String baroid    = getString(param.get("baroid"));
			String barocd    = getString(param.get("barocd"));
			String remark    = getString(param.get("remark"));

			log.info("입력 파라미터 spjangcd={}, cardType={}, cardnum={}, barocd={}," +
								 " baroid={}, cardwebid={}, cardwebpw=***",
				spjangcd, cardType, cardnum, barocd, baroid, cardwebid);

//			String alias = remark;
			String alias = "";
			String usage = "";
			String collectCycle = "DAY1";

			if (isEmpty(spjangcd)) {
				result.success = false;
				result.message = "사업장 코드가 없습니다.";
				return result;
			}

			if (isEmpty(barocd)) {
				result.success = false;
				result.message = "카드등록 화면에서 바로빌 기관 코드를 먼저 등록하세요.";
				return result;
			}

			String cardTypeBaro;
			if ("1".equals(cardType)) {
				cardTypeBaro = "C";
			} else if ("2".equals(cardType)) {
				cardTypeBaro = "P";
			} else {
				result.success = false;
				result.message = "카드유형 값이 올바르지 않습니다.";
				return result;
			}

			String cardNumOnly = getString(param.get("cardnum")).replaceAll("[-\\s]", "");

			log.info("카드타입 변환 cardTypeBaro={}", cardTypeBaro);
			log.info("카드번호 변환 cardNumOnly={}", maskCardNum(cardNumOnly));

			String saupnum = getSaupnumBySpjangcd(spjangcd);
			log.info("사업자번호 조회 saupnum={}", saupnum);

			if (isEmpty(saupnum)) {
				result.success = false;
				result.message = "사업장에 해당하는 사업자번호를 찾을 수 없습니다.";
				return result;
			}

			String corpNum = saupnum.replace("-", "").trim();
			String cardCompany = barocd.trim().toUpperCase();

			log.info("API 요청값 certKey={}, corpNum={}, collectCycle={}, cardCompany={}, cardTypeBaro={}, cardNumOnly={}, webId={}, alias={}",
				certKey != null ? "SET" : "NULL",
				corpNum,
				collectCycle,
				cardCompany,
				cardTypeBaro,
				maskCardNum(cardNumOnly),
				cardwebid,
				alias
			);

			int callResult = barobillApiService.card.registCardEx(
				certKey,
				corpNum,
				collectCycle,
				cardCompany,
				cardTypeBaro,
				cardNumOnly,
				cardwebid,
				cardwebpw,
				alias,
				usage
			);

			log.info("바로빌 API 결과 callResult={}", callResult);

			if (callResult == 1) {
				int updateCnt = updateCdFlag(spjangcd, cardNumOnly);
				log.info("DB 업데이트 결과 updateCnt={}", updateCnt);

				if (updateCnt > 0) {
					result.success = true;
					result.message = "카드등록이 완료되었습니다.";
				} else {
					result.success = false;
					result.message = "바로빌 카드등록은 성공했지만 카드 상태 갱신에 실패했습니다.";
				}
			} else {
				log.error("바로빌 카드등록 실패 code={}, message={}", callResult, getBarobillErrorMessage(callResult));
				result.success = false;
				result.message = getBarobillErrorMessage(callResult);
			}

			log.info("========== 카드등록 종료 ==========");

		} catch (Exception e) {
			log.error("baroNewCardSave error", e);
			result.success = false;
			result.message = "카드등록 중 오류가 발생했습니다. " + e.getMessage();
		}

		return result;
	}

	//바로빌 카드 관리 URL
	public AjaxResult getCardManagementURL(Map<String, Object> param) {

		AjaxResult result = new AjaxResult();

		log.info("===== 카드관리 URL 조회 시작 =====");
		log.info("param: {}", param);

		try {

			String spjangcd = TenantContext.get();
			String corpNum = getSaupnumBySpjangcd(spjangcd);
			String id       = "cycling409";
			String pwd      = "";

//			log.info("spjangcd = {}", spjangcd);
//			log.info("corpNum = {}", corpNum);
//			log.info("id = {}", id);
//			log.info("pwd 존재 여부 = {}", pwd != null);

//			log.info("BarobillApiService 생성 시작");

//			log.info("BarobillApiService 생성 완료");

//			log.info("카드관리 URL 요청 시작");

			String apiResult =
				barobillApiService.card.getCardManagementURL(certKey, corpNum, id, pwd);

//			log.info("Barobill API 결과: {}", apiResult);

			if (Pattern.compile("^-[0-9]{5}$").matcher(apiResult).matches()) {
				int errorCode = Integer.parseInt(apiResult);
				log.error("Barobill 오류코드: {}", errorCode);

				result.success = false;
				result.message = getBarobillErrorMessage(errorCode);
				return result;
			}

			result.success = true;
			result.message = "카드관리 URL 조회 성공";
			result.data = apiResult;

		} catch (Exception e) {

			log.error("카드관리 URL 조회 중 오류 발생", e);

			result.success = false;
			result.message = "카드관리 URL 조회 중 오류: " + e.getMessage();
		}

		log.info("===== 카드관리 URL 조회 종료 =====");

		return result;
	}

	// 바로빌 카드 해지
	@Transactional
	public AjaxResult getStopCard(Map<String, Object> param) {

		AjaxResult result = new AjaxResult();

		log.info("===== 카드해지 시작 =====");
		log.info("param: {}", param);

		try {
			String spjangcd = TenantContext.get();
			String cardnum  = getString(param.get("cardnum"));

			if (spjangcd.isEmpty()) {
				result.success = false;
				result.message = "사업장 코드가 없습니다.";
				return result;
			}

			if (cardnum.isEmpty()) {
				result.success = false;
				result.message = "카드번호가 없습니다.";
				return result;
			}

			String corpNum = getSaupnumBySpjangcd(spjangcd);
			String cardNumOnly = cardnum.replaceAll("-", "").replaceAll("\\s", "");

			log.info("spjangcd = {}", spjangcd);
			log.info("corpNum = {}", corpNum);
			log.info("cardNum = {}", maskCardNum(cardNumOnly));

			/*BarobillApiService barobillApiService =
				new BarobillApiService(BarobillApiProfile.RELEASE_SSL);*/

			int apiResult =
				barobillApiService.card.stopCard(certKey, corpNum, cardNumOnly);

			log.info("Barobill API 결과: {}", apiResult);

			if (apiResult < 0) {
				log.error("Barobill 오류코드: {}", apiResult);

				result.success = false;
				result.message = getBarobillErrorMessage(apiResult);
				return result;
			}

			// ✅ API 성공 → DB 상태 변경
			int updateCount = updateCdFlagStop(spjangcd, cardNumOnly);

			log.info("카드 cdflag 업데이트 건수: {}", updateCount);

			result.success = true;
			result.message = "카드연동 해지가 완료되었습니다.";
			result.data = apiResult;

		} catch (Exception e) {
			log.error("카드해지 중 오류 발생", e);

			result.success = false;
			result.message = "카드연동 해지 중 오류: " + e.getMessage();
		}

		log.info("===== 카드해지 종료 =====");

		return result;
	}

	private String getSaupnumBySpjangcd(String spjangcd) {
		MapSqlParameterSource sqlParam = new MapSqlParameterSource();
		sqlParam.addValue("spjangcd", spjangcd);

		String sql = """
            select saupnum
            from tb_xa012
            where spjangcd = :spjangcd
        """;

		Map<String, Object> row = sqlRunner.getRow(sql, sqlParam);

		if (row == null || row.isEmpty()) {
			return "";
		}

		Object saupnum = row.get("saupnum");
		return saupnum == null ? "" : String.valueOf(saupnum).trim();
	}

	private int updateCdFlag(String spjangcd, String cardnum) {
		MapSqlParameterSource sqlParam = new MapSqlParameterSource();
		sqlParam.addValue("spjangcd", spjangcd);
		sqlParam.addValue("cardnum", cardnum);

		String sql = """
            update tb_iz010
               set cdflag = '1'
             where spjangcd = :spjangcd
               and cardnum = :cardnum
        """;

		return sqlRunner.execute(sql, sqlParam);
	}

	private int updateCdFlagStop(String spjangcd, String cardnum) {

		MapSqlParameterSource sqlParam = new MapSqlParameterSource();
		sqlParam.addValue("spjangcd", spjangcd);
		sqlParam.addValue("cardnum", cardnum);

		String sql = """
        update tb_iz010
           set cdflag = '0'
         where spjangcd = :spjangcd
           and cardnum = :cardnum
    """;

		return sqlRunner.execute(sql, sqlParam);
	}

	private String getString(Object obj) {
		return obj == null ? "" : String.valueOf(obj).trim();
	}

	private boolean isEmpty(String str) {
		return str == null || str.trim().isEmpty();
	}

	private String maskCardNum(String cardNum) {
		if (cardNum == null || cardNum.length() < 8) {
			return "INVALID";
		}

		return cardNum.substring(0, 4)
						 + "********"
						 + cardNum.substring(cardNum.length() - 4);
	}

	//오류메시지
	private String getBarobillErrorMessage(int code) {
		return BAROBILL_ERRORS.getOrDefault(
			code,
			"바로빌 요청 실패. 오류코드: " + code
		);
	}

	private static final Map<Integer, String> BAROBILL_ERRORS = Map.ofEntries(

		// ===== 기본 오류코드 =====
		Map.entry(-10000, "알 수 없는 오류가 발생했습니다. 바로빌로 문의 바랍니다."),
		Map.entry(-10003, "연동서비스가 점검 중입니다."),
		Map.entry(-10004, "해당 기능은 더 이상 사용되지 않습니다."),
		Map.entry(-10007, "해당 기능을 사용할 수 없습니다."),
		Map.entry(-10005, "최대 100건까지만 사용 가능합니다."),
		Map.entry(-10006, "최대 1000건까지만 사용 가능합니다."),
		Map.entry(-10008, "날짜 형식이 잘못되었습니다."),
		Map.entry(-10010, "입력된 건이 없습니다."),
		Map.entry(-10011, "조회 가능 기간을 초과했습니다."),
		Map.entry(-10148, "조회 기간이 잘못되었습니다."),
		Map.entry(-40001, "파일을 찾을 수 없습니다."),
		Map.entry(-40002, "빈 파일입니다 (0byte)."),

		// ===== 연동정보 오류 =====
		Map.entry(-10002, "해당 인증키를 찾을 수 없습니다."),
		Map.entry(-10001, "해당 인증키와 연결된 연계사가 아닙니다."),
		Map.entry(-24005, "사업자번호와 아이디가 일치하지 않습니다."),

		// ===== 카드 관련 오류 =====
		Map.entry(-50101, "카드를 찾을 수 없습니다."),
		Map.entry(-50102, "카드를 조회할 권한이 없습니다."),
		Map.entry(-50111, "카드사 코드가 잘못 입력되었습니다."),
		Map.entry(-50112, "카드유형이 잘못 입력되었습니다."),
		Map.entry(-50113, "카드번호가 잘못 입력되었습니다."),
		Map.entry(-50114, "카드사 홈페이지 아이디가 잘못 입력되었습니다."),
		Map.entry(-50115, "카드사 홈페이지 비밀번호가 잘못 입력되었습니다."),
		Map.entry(-50116, "유효한 카드정보가 아닙니다. 카드정보를 확인해 주시기 바랍니다."),
		Map.entry(-50117, "카드정보 검증에 실패했습니다. 잠시 후 다시 시도해 주세요."),
		Map.entry(-50118, "이미 등록된 카드번호입니다."),
		Map.entry(-50119, "수집주기가 잘못 입력되었습니다."),
		Map.entry(-50120, "중복된 카드번호는 등록할 수 없습니다."),
		Map.entry(-50151, "사용내역 키(UseKey)가 잘못 입력되었습니다."),
		Map.entry(-50152, "카드 사용내역을 찾을 수 없습니다."),
		Map.entry(-50161, "현대카드(개인)은 카드사 보안으로 서비스가 중단되었습니다.")
	);

}
