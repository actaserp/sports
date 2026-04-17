package mes.app.account_management;

import lombok.extern.slf4j.Slf4j;
import mes.app.account_management.service.TransactionInputService;
import mes.app.common.TenantContext;
import mes.app.util.UtilClass;
import mes.domain.dto.BankAccsaveRequestDto;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController("accountMgmtTransactionInputController")
@RequestMapping("/api/account_management/input")
@Slf4j
public class TransactionInputController { //입출금 입력

	@Autowired
	@Qualifier("accountMgmtTransactionInputService")
	TransactionInputService transactionInputService;

	@GetMapping("/registerAccount")
	public AjaxResult registerAccount(@RequestParam String spjangcd){

		AjaxResult result = new AjaxResult();

		result.data = transactionInputService.getAccountList(spjangcd);

		Map<String, Object> status = new LinkedHashMap<>();


		return  result;
	}

	@GetMapping("/history")
	public AjaxResult TransactionHistory(@RequestParam String searchfrdate,
																			 @RequestParam String searchtodate,
																			 @RequestParam String tradetype,
																			 @RequestParam String spjangcd,
																			 @RequestParam(required = false) String accountNameHidden,
																			 @RequestParam(required = false) String cltflag,
																			 @RequestParam(required = false) String cboCompanyHidden) throws InterruptedException {
		long start = System.currentTimeMillis();

		AjaxResult result = new AjaxResult();

		searchfrdate = searchfrdate.replaceAll("-", "");
		searchtodate = searchtodate.replaceAll("-", "");

		Integer parsedAccountId = null;
		if(accountNameHidden != null && !accountNameHidden.isEmpty()){
			parsedAccountId = UtilClass.parseInteger(accountNameHidden);
		}

		Integer parsedCompanyId = null;
		if(cboCompanyHidden != null && !cboCompanyHidden.isEmpty()){
			parsedCompanyId = UtilClass.parseInteger(cboCompanyHidden);
		}

		Map<String, Object> param = new HashMap<>();

		param.put("searchfrdate", searchfrdate);
		param.put("searchtodate", searchtodate); // searchtodate가 null이더라도 문제없이 추가됨
		param.put("parsedAccountId", parsedAccountId);
		param.put("parsedCompanyId", parsedCompanyId);
		param.put("spjangcd", spjangcd);
		param.put("cltflag", cltflag);
		param.put("tradetype", tradetype);

		result.data = transactionInputService.getTransactionHistory(param);


		long end = System.currentTimeMillis();
		System.out.println("끝남시간: " + end);
		System.out.println("[/history] 처리 시간: " + (end - start) + " ms");
		return result;
	}

	//팝업 계좌정보 저장
	@PostMapping("/AccountEdit")
	public AjaxResult AccountEdit(@RequestBody Object list){

		AjaxResult result = new AjaxResult();

		try {
			transactionInputService.editAccountList((List<Map<String, Object>>)list);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

		result.message = "수정되었습니다.";
		return  result;

	}

	//입출금 팝업 저장
	@PostMapping("/transactionForm")
	public AjaxResult transactionForm(@RequestBody BankAccsaveRequestDto data) {

		AjaxResult result = new AjaxResult();

		String msg = validateTransactionForm(data);
		if (msg != null) {
			result.success = false;
			result.message = msg;
			return result;
		}

		try {
			transactionInputService.saveBankAccsave(data);
			result.success = true;
			result.message = "저장하였습니다.";
		} catch (Exception e) {
			result.success = false;
			result.message = e.getMessage();
		}

		return result;
	}

	//입출금 삭제[단건]
	@PostMapping("/delete")
	public AjaxResult deleteBankAccsave(@RequestParam String custcd,
																			@RequestParam String spjangcd,
																			@RequestParam String bnkcode,
																			@RequestParam String fintechUseNum) {

		AjaxResult result = new AjaxResult();

		try {
			transactionInputService.deleteBankAccsave(custcd, spjangcd, bnkcode, fintechUseNum);
			result.success = true;
			result.message = "삭제하였습니다.";
		} catch (Exception e) {
			result.success = false;
			result.message = e.getMessage();
		}

		return result;
	}

	@GetMapping("/searchDetail")
	public AjaxResult searchDetail(@RequestParam Map<String, Object> params) {
		AjaxResult result = new AjaxResult();

		String spjangcd    = TenantContext.get();
		String searchfrdate = (String) params.get("searchfrdate");
		String searchtodate = (String) params.get("searchtodate");
		String bankcd       = (String) params.get("bankcd");

		Map<String, String> bizInfo = transactionInputService.getBizInfoBySpjangcd(spjangcd);
		String custcd = bizInfo.get("custcd");

		List<Map<String, Object>> list = transactionInputService
																			 .searchDetail(custcd, spjangcd, bankcd, searchfrdate, searchtodate);

		result.success = true;
		result.data    = list;
		return result;
	}

	private String validateTransactionForm(BankAccsaveRequestDto data) {
		if (data == null) return "요청 데이터가 없습니다.";
		if (isBlank(data.getTransactionDate())) return "거래일자는 필수입니다.";
		if (isBlank(data.getInoutFlag())) return "입출금구분은 필수입니다.";
		if (isBlank(data.getMoney())) return "금액은 필수입니다.";
		if (isBlank(data.getAccountNumber())) return "계좌번호는 필수입니다.";
		return null;
	}

	private boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}


}
