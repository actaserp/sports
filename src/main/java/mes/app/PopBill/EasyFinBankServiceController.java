package mes.app.PopBill;

import com.popbill.api.EasyFinBankService;
import com.popbill.api.PopbillException;
import com.popbill.api.Response;
import com.popbill.api.easyfin.*;
import lombok.extern.slf4j.Slf4j;
import mes.Encryption.EncryptionKeyProvider;
import mes.Encryption.EncryptionUtil;
import mes.app.PopBill.service.EasyFinBankAccountQueryService;
import mes.app.common.TenantContext;
import mes.app.util.UtilClass;
import mes.domain.model.AjaxResult;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpSession;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("EasyFinBankService")
public class EasyFinBankServiceController {

	@Autowired
	private EasyFinBankService easyFinBankService;

	@Autowired
	private EasyFinBankAccountQueryService easyFinBankAccountQueryService;


	// ──────────────────────────────────────────────
	// 1. 팝빌 계좌 등록
	// ──────────────────────────────────────────────
	@RequestMapping(value = "registBankAccount", method = RequestMethod.POST)
	public AjaxResult registBankAccount(
		@RequestParam Map<String, Object> params) throws Exception {

		AjaxResult result = new AjaxResult();

		// 1. 파라미터 추출
		String spjangcd       = TenantContext.get();
		String bank           = (String) params.get("BankName");    // 2자리 "07"
		String bankcd         = (String) params.get("accountid");   // ERP 체번 ex:"B001"
		String paymentPw      = (String) params.get("PaymentPw");
		String viewid         = (String) params.get("viewid");
		String viewpw         = (String) params.get("viewpw");
		String bankId         = (String) params.get("BankId");
		String identityNumber = (String) params.get("identityNumber");
		String accountAlias   = (String) params.get("AccountAlias");
		String accountNumber  = (String) params.get("AccountNumber");

		// 2. spjangcd로 custcd 조회
		if (StringUtils.isEmpty(spjangcd)) return fail(result, "사업장 코드가 없습니다.");
		Map<String, String> bizInfo = easyFinBankAccountQueryService.getBizInfoBySpjangcd(spjangcd);
		String custcd = bizInfo.get("custcd");

		if (StringUtils.isEmpty(custcd)) return fail(result, "회사코드를 찾을 수 없습니다.");

		// 3. 필수값 체크
		if (StringUtils.isEmpty(bank))           return fail(result, "은행 코드가 없습니다.");
		if (StringUtils.isEmpty(bankcd))         return fail(result, "계좌 체번이 없습니다.");
		if (StringUtils.isEmpty(identityNumber)) return fail(result, "사업자번호(혹은 생년월일)가 비어있습니다.");

		// 4. tb_aa040 조회
		Map<String, Object> acc = easyFinBankAccountQueryService.getAccountInfo(custcd, bank, bankcd);
		if (acc == null) return fail(result, "계좌 정보를 찾을 수 없습니다.");

		String popUserId = (String) acc.get("popuserid");

		// 5. 이미 연동된 계좌 체크
		if ("Y".equals(acc.get("popflag"))) {
			return fail(result, "이미 팝빌에 연동된 계좌입니다.");
		}

		// 6. 비밀번호 처리 (평문)
		String resolvedPaymentPw = resolvePassword(paymentPw, (String) acc.get("bnkpaypw"));
		String resolvedViewpw    = resolvePassword(viewpw,    (String) acc.get("cmspw"));

		// 7. bank 2자리 → 팝빌 BankCode 4자리 변환 ("07" → "0007")
		String popBillBankCode = String.format("%04d", Integer.parseInt(bank.trim()));

		// 8. 은행별 필수항목 검증
		EasyFinBankAccountForm bankInfo = new EasyFinBankAccountForm();
		if (!validateBankRequirement(popBillBankCode, bankId, viewid, resolvedViewpw, result, bankInfo)) {
			return result;
		}

		// 9. 계좌번호 처리 (평문, 하이픈만 제거)
		String accnum = (String) acc.get("accnum");
		String plainAccountNum = (accnum != null && !accnum.isBlank())
															 ? accnum.replaceAll("-", "")          // DB값 하이픈 제거
															 : accountNumber.replaceAll("-", "");  // DB에 없으면 폼 입력값 사용

		try {
			// 10. 팝빌 API 파라미터 세팅
			bankInfo.setBankCode(popBillBankCode);
			bankInfo.setAccountNumber(plainAccountNum);
			bankInfo.setAccountPWD(resolvedPaymentPw); // 평문 그대로

			// popsort: "1"=개인, 나머지=법인
			String accountType = "1".equals(acc.get("popsort")) ? "개인" : "법인";
			bankInfo.setAccountType(accountType);
			bankInfo.setIdentityNumber(identityNumber.replaceAll("-", ""));
			bankInfo.setAccountName((String) acc.get("accname"));

			// 11. 사업자번호(saupnum)로 팝빌 API 호출
			String corpNum = bizInfo.get("saupnum").replaceAll("-", "");
			log.info("사업자번호(saupnum)로 팝빌 API 호출 corpNum: {}", corpNum);
			if (StringUtils.isEmpty(corpNum)) return fail(result, "사업자번호를 찾을 수 없습니다.");

			// 12. 팝빌 API 호출
			Response response;
			response = easyFinBankService.registBankAccount(corpNum, bankInfo, popUserId);
			// UserID 아예 안 넘기기
//			response = easyFinBankService.registBankAccount(corpNum, bankInfo);

			if (response.getCode() == 1) {
				// 13. 성공 시 tb_aa040 UPDATE
				Map<String, Object> updateParams = new HashMap<>();
				updateParams.put("custcd",  custcd);
				updateParams.put("bank",    bank);
				updateParams.put("bankcd",  bankcd);
				updateParams.put("bnkid",   bankId);
				updateParams.put("cmsid",   viewid);
				updateParams.put("cmspw",   resolvedViewpw);
				updateParams.put("accname", accountAlias);
				updateParams.put("popflag",  "1");	//1 연동 /0 :미연동

				easyFinBankAccountQueryService.saveRegistAccount(updateParams);

				// OnitErp 과금 등록
				String saupnum = bizInfo.get("saupnum").replaceAll("-", "");
				String banknm  = (String) acc.get("banknm");
				String today   = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
				easyFinBankAccountQueryService.registerOnitErpPcode(saupnum, plainAccountNum, banknm, today);

				result.success = true;
				result.message = response.getMessage();
			} else {
				result.success = false;
				result.message = response.getMessage();
			}

		} catch (Exception e) {
			log.error("registBankAccount error: {}", e.getMessage());
			result.success = false;
			result.message = e.getMessage();
		}

		return result;
	}

	// ──────────────────────────────────────────────
	// 2. 거래내역 수집
	// ──────────────────────────────────────────────
	@RequestMapping(value = "requestJob", method = RequestMethod.POST)
	public AjaxResult requestJob(
		@RequestParam Map<String, Object> params,
		HttpSession session) {

		AjaxResult result = new AjaxResult();
		result.success = false;

		String frdate        = (String) params.get("frdate");
		String todate        = (String) params.get("todate");
		String managementnum = (String) params.get("managementnum");
		String accountnumber = (String) params.get("accountnumber");
		String spjangcd      = (String) params.get("spjangcd");
		String bankname      = (String) params.get("bankname");
		String custcd        = (String) params.get("custcd");
		String bank          = (String) params.get("bank");
		String bankcd        = (String) params.get("bankcd");

		// 계좌번호, 은행코드 없으면 리턴
		if (!validateRequest(accountnumber, managementnum, result)) return result;

		frdate = frdate.replaceAll("-", "");
		todate = todate.replaceAll("-", "");

		try {
			// tb_aa040 조회 후 계좌번호 복호화
			Map<String, Object> acc = easyFinBankAccountQueryService.getAccountInfo(custcd, bank, bankcd);
			if (acc == null) return fail(result, "계좌 정보를 찾을 수 없습니다.");

			String encryptedAccnum = (String) acc.get("accnum");
			String plainAccountNum = EncryptionUtil.decrypt(encryptedAccnum, EncryptionKeyProvider.getKey());

			String corpNum = UtilClass.getsaupnumInfoFromSession(spjangcd, session);
			String jobID   = easyFinBankService.requestJob(corpNum, managementnum, plainAccountNum, frdate, todate);

			String jobState = waitForJobComplete(corpNum, jobID);

			if (!jobState.equals("3")) { // 3 = COMPLETE
				result.message = jobState;
				return result;
			}

			EasyFinBankSearchResult searchInfo = easyFinBankService.search(
				corpNum, jobID, null, null, null, null, null);

			if (searchInfo.getCode() != 1) {
				result.message = searchInfo.getMessage();
				return result;
			}

			List<EasyFinBankSearchDetail> list = searchInfo.getList();

			// 비동기로 TB_bank_accsave 저장
			easyFinBankAccountQueryService.saveBankDataAsync(
				list, jobID, plainAccountNum, custcd, bank, bankcd, bankname, spjangcd);

			result.success = true;
			result.data = list;

		} catch (Exception e) {
			log.error("requestJob error: {}", e.getMessage());
			result.message = e.getMessage();
		}

		return result;
	}

	// ──────────────────────────────────────────────
	// 3. 팝빌 등록 계좌정보 확인
	// ──────────────────────────────────────────────
	@RequestMapping(value = "getBankAccountInfo", method = RequestMethod.GET)
	public AjaxResult getBankAccountInfo(
		@RequestParam Map<String, Object> params,
		HttpSession session) {

		AjaxResult result = new AjaxResult();

		String custcd   = (String) params.get("custcd");
		String bank     = (String) params.get("bank");    // 2자리
		String bankcd   = (String) params.get("bankcd");
		String spjangcd = (String) params.get("spjangcd");

		// 2자리 → 4자리 변환
		String popBillBankCode = String.format("%04d", Integer.parseInt(bank.trim()));

		try {
			Map<String, Object> acc = easyFinBankAccountQueryService.getAccountInfo(custcd, bank, bankcd);
			if (acc == null) return fail(result, "계좌 정보를 찾을 수 없습니다.");

			String accountNumber = EncryptionUtil.decrypt((String) acc.get("accnum"));
			String corpNum = UtilClass.getsaupnumInfoFromSession(spjangcd, session);

			EasyFinBankAccount bankAccountInfo = easyFinBankService.getBankAccountInfo(
				corpNum.replaceAll("-", ""), popBillBankCode, accountNumber);

			log.info("계좌정보 객체 : {}", bankAccountInfo);
			result.success = true;
			result.data = bankAccountInfo;

		} catch (IllegalArgumentException | PopbillException e) {
			result.success = false;
			result.message = e.getMessage();
		} catch (Exception e) {
			throw new RuntimeException("복호화에 실패하였습니다.", e);
		}

		return result;
	}

//	 ──────────────────────────────────────────────
//	 4. 정액제 해지
//	 ──────────────────────────────────────────────
	@RequestMapping(value = "closeBankAccount", method = RequestMethod.GET)
	public AjaxResult closeBankAccount(
		@RequestParam Map<String, Object> params,
		HttpSession session) {

		AjaxResult result = new AjaxResult();

		String custcd     = (String) params.get("custcd");
		String bank       = (String) params.get("bank");       // 2자리
		String bankcd     = (String) params.get("bankcd");
		String spjangcd   = (String) params.get("spjangcd");
		String closeType  = (String) params.get("closeType");  // "일반" or "중도"

		if (StringUtils.isEmpty(closeType)) return fail(result, "해지구분을 선택해주세요.");

		// 2자리 → 4자리 변환
		String popBillBankCode = String.format("%04d", Integer.parseInt(bank.trim()));

		try {
			Map<String, Object> acc = easyFinBankAccountQueryService.getAccountInfo(custcd, bank, bankcd);
			if (acc == null) return fail(result, "계좌 정보를 찾을 수 없습니다.");

			String accountNumber = EncryptionUtil.decrypt((String) acc.get("accnum"));
			String corpNum = UtilClass.getsaupnumInfoFromSession(spjangcd, session);

			Response response = easyFinBankService.closeBankAccount(
				corpNum, popBillBankCode, accountNumber, closeType);

			log.info("해제신청 api 리턴객체 : {}", response);

			if (response.getCode() == 1) {
				// 성공 시 tb_aa040.popflag = '0' 업데이트
				easyFinBankAccountQueryService.updatePopflag(custcd, bank, bankcd, "0");
				result.success = true;
			} else {
				result.success = false;
			}
			result.message = response.getMessage();

		} catch (PopbillException e) {
			result.success = false;
			result.message = e.getMessage();
		} catch (IllegalArgumentException e) {
			result.success = false;
			result.message = e.getMessage();
		} catch (Exception e) {
			throw new RuntimeException("팝빌 계좌 해지 요청 중 오류 발생", e);
		}

		return result;
	}

	// ──────────────────────────────────────────────
	// 5. 팝빌 등록 계좌정보 수정
	// ──────────────────────────────────────────────
	@RequestMapping(value = "updateBankAccount", method = RequestMethod.GET)
	public AjaxResult updateBankAccount(
		@RequestParam Map<String, Object> params,
		HttpSession session) {

		AjaxResult result = new AjaxResult();

		String custcd       = (String) params.get("custcd");
		String bank         = (String) params.get("bank");      // 2자리
		String bankcd       = (String) params.get("bankcd");
		String spjangcd     = (String) params.get("spjangcd");
		String paymentPw    = (String) params.get("paymentPw");
		String viewid       = (String) params.get("viewid");
		String viewpw       = (String) params.get("viewpw");
		String bankId       = (String) params.get("bankId");
		String accountAlias = (String) params.get("accountAlias");

		if (StringUtils.isEmpty(bank))      return fail(result, "은행코드가 없습니다.");
		if (StringUtils.isEmpty(paymentPw)) return fail(result, "계좌 비밀번호가 없습니다.");

		// 2자리 → 4자리 변환
		String popBillBankCode = String.format("%04d", Integer.parseInt(bank.trim()));

		try {
			Map<String, Object> acc = easyFinBankAccountQueryService.getAccountInfo(custcd, bank, bankcd);
			if (acc == null) return fail(result, "계좌 정보를 찾을 수 없습니다.");

			String resolvedPaymentPw = resolvePassword(paymentPw,
				EncryptionUtil.decrypt(UtilClass.getStringSafe((String) acc.get("bnkpaypw"))));

			UpdateEasyFinBankAccountForm edit = new UpdateEasyFinBankAccountForm();
			edit.setAccountPWD(resolvedPaymentPw);
			edit.setAccountName(accountAlias);

			// 은행별 조회전용 계정 처리
			switch (popBillBankCode) {
				case "0031": // 아이엠뱅크
				case "0088": // 신한은행
				case "0048": // 신협중앙회
					if (StringUtils.isEmpty(viewid) || StringUtils.isEmpty(viewpw)) {
						return fail(result, "해당 은행은 조회전용계정이 필수입니다.");
					}
					edit.setFastID(viewid);
					String resolvedViewpw = resolvePassword(viewpw,
						EncryptionUtil.decrypt(UtilClass.getStringSafe((String) acc.get("cmspw"))));
					edit.setFastPWD(resolvedViewpw);
					break;

				case "0004": // 국민은행
					if (StringUtils.isEmpty(bankId)) {
						return fail(result, "국민은행은 인터넷뱅킹 아이디가 필수입니다.");
					}
					edit.setBankID(bankId);
					break;

				default:
					break;
			}

			String accountNumber = EncryptionUtil.decrypt((String) acc.get("accnum"));
			String corpNum = UtilClass.getsaupnumInfoFromSession(spjangcd, session);

			Response response = easyFinBankService.updateBankAccount(
				corpNum, popBillBankCode, accountNumber, edit, null);

			if (response.getCode() == 1) {
				// 성공 시 tb_aa040 UPDATE
				Map<String, Object> updateParams = new HashMap<>();
				updateParams.put("custcd",  custcd);
				updateParams.put("bank",    bank);
				updateParams.put("bankcd",  bankcd);
				updateParams.put("bnkpaypw", EncryptionUtil.encrypt(edit.getAccountPWD()));
				updateParams.put("bnkid",   edit.getBankID());
				updateParams.put("accname", edit.getAccountName());
				updateParams.put("cmsid",   edit.getFastID());
				updateParams.put("cmspw",   StringUtils.isEmpty(edit.getFastPWD())
																			? null
																			: EncryptionUtil.encrypt(edit.getFastPWD()));

				easyFinBankAccountQueryService.updateAccountInfo(updateParams);
				result.success = true;
			}else {
				result.success = false;
			}
			result.message = response.getMessage();

		} catch (PopbillException e) {
			result.success = false;
			result.message = e.getMessage();
		} catch (Exception e) {
			throw new RuntimeException("팝빌 계좌 수정 중 오류 발생", e);
		}

		return result;
	}

	// ──────────────────────────────────────────────
	// Private 메서드
	// ──────────────────────────────────────────────

	/**
	 * BankID   → 국민은행(0004)일 경우 인터넷뱅킹 아이디 필수
	 * FastID, FastPWD → 아이엠뱅크(0031), 신한은행(0088), 신협중앙회(0048)일 경우 조회전용계정 필수
	 */
	private boolean validateBankRequirement(
		String popBillBankCode,
		String bankId,
		String viewid,
		String viewpw,
		AjaxResult result,
		EasyFinBankAccountForm bankInfo) {

		switch (popBillBankCode) {
			case "0004": // 국민은행
				if (StringUtils.isEmpty(bankId)) {
					fail(result, "국민은행은 인터넷뱅킹 아이디가 필수입니다.");
					return false;
				}
				bankInfo.setBankID(bankId);
				break;

			case "0031": // 아이엠뱅크
			case "0088": // 신한은행
			case "0048": // 신협중앙회
				if (StringUtils.isEmpty(viewid) || StringUtils.isEmpty(viewpw)) {
					fail(result, "아이엠뱅크, 신한은행, 신협중앙회는 조회전용 계정이 필수입니다.");
					return false;
				}
				bankInfo.setFastID(viewid);
				try {
					bankInfo.setFastPWD(EncryptionUtil.decrypt(viewpw));
				} catch (Exception e) {
					log.error("복호화 실패: {}, 문자열: {}", e.getMessage(), viewpw);
					bankInfo.setFastPWD(viewpw);
				}
				break;

			default:
				break;
		}
		return true;
	}

	private boolean validateRequest(String accountnumber, String managementnum, AjaxResult result) {
		if (StringUtils.isEmpty(accountnumber) || StringUtils.isEmpty(managementnum)) {
			fail(result, "관리코드 및 계좌번호가 누락되었습니다.");
			return false;
		}
		return true;
	}

	private AjaxResult fail(AjaxResult result, String message) {
		result.success = false;
		result.message = message;
		return result;
	}

	public String waitForJobComplete(String corpNum, String jobId) throws InterruptedException, PopbillException {
		int maxRetry = 10;
		int interval = 1000;

		for (int i = 0; i < maxRetry; i++) {
			EasyFinBankJobState jobState = easyFinBankService.getJobState(corpNum, jobId);
			String jobStateCode = jobState.getJobState();
			long errorCode = jobState.getErrorCode();

			if (errorCode != 1 && errorCode != 0) {
				log.info("에러코드 발생: {}", errorCode);
				return "에러발생";
			}

			if ("3".equals(jobStateCode)) { // 3 = COMPLETE
				log.info("수집완료");
				return "3";
			}

			Thread.sleep(interval);
		}
		return "TIMEOUT";
	}

	private String resolvePassword(String input, String original) {
		if (StringUtils.isEmpty(input)) return original;
		return input.contains("⋆") ? original : input;
	}


}