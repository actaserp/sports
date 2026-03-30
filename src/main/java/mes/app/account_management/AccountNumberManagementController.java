package mes.app.account_management;

import lombok.extern.slf4j.Slf4j;
import mes.app.account_management.service.AccountNumberManagementService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
@RequestMapping("/api/account_management") //계좌번호관리
public class AccountNumberManagementController {

	@Autowired
	AccountNumberManagementService accountNumberManagementService;

	@GetMapping("/read")
	public AjaxResult getList(@RequestParam (value ="bankid") String bankid , //은행코드
														@RequestParam (value ="accountnum") String accountnum,
														@RequestParam (value ="spjangcd")String spjangcd){
		AjaxResult result = new AjaxResult();

		result.data = accountNumberManagementService.getAccountList(bankid, accountnum, spjangcd);

		return result;
	}
}
