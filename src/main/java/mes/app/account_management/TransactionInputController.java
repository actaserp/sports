package mes.app.account_management;

import lombok.extern.slf4j.Slf4j;
import mes.app.account_management.service.TransactionInputService;
import mes.app.util.UtilClass;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController("accountMgmtTransactionInputController")
@RequestMapping("/api/account_management/input")
@Slf4j
public class TransactionInputController {

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

}
