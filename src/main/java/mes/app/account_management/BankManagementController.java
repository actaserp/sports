package mes.app.account_management;

import lombok.extern.slf4j.Slf4j;
import mes.app.account_management.service.BankManagementService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/account_management/bank_management")
public class BankManagementController {

	@Autowired
	BankManagementService bankManagementService;

	@GetMapping("/read")
	public AjaxResult getRegiAccountList(@RequestParam(value = "bankid", required = false)  String bankid,
																			 @RequestParam(value = "accountnum", required = false)  String accountnum
	){

		AjaxResult result = new AjaxResult();

		result.data = bankManagementService.getAccountList(bankid, accountnum);

		return result;
	}

	@PostMapping("/save")
	public AjaxResult BankManagementSave(@RequestParam Map<String, Object> param,
																			 Authentication auth) {

		AjaxResult result = new AjaxResult();

		try {

			result = bankManagementService.saveBankAccount(param, auth);

		} catch (Exception e) {
			result.success = false;
			result.message = "저장 중 오류가 발생했습니다.";
			log.error("BankManagementSave error", e);
		}

		return result;
	}

	@PostMapping("/delete")
	public AjaxResult BankManagementDelete(){
		AjaxResult result = new AjaxResult();
		return result;
	}


}
