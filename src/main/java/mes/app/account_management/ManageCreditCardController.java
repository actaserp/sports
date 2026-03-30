package mes.app.account_management;

import lombok.extern.slf4j.Slf4j;
import mes.app.account_management.service.BaroCardService;
import mes.app.account_management.service.ManageCreditCardService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@Slf4j
@RequestMapping("/api/account_management/manageCreditCard") //신용카드 등록
public class ManageCreditCardController {

	@Autowired
	ManageCreditCardService cardService;

	@Autowired
	BaroCardService baroCardService;

	@GetMapping("/read")
	public AjaxResult getList (@RequestParam(value = "txtcardnm", required = false) String txtcardnm,
														 @RequestParam(value = "txtcardnum", required = false) String txtcardnum) {

		List<Map<String, Object>> items = this.cardService.getList(txtcardnm, txtcardnum);

		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;
	}

	@PostMapping("/RegistCardEx")
	public AjaxResult baroNewCardSave(@RequestParam Map<String,Object> param,
																		Authentication auth){

		AjaxResult result = new AjaxResult();

		try {
			result = baroCardService.baroNewCardSave(param, auth);
		} catch (Exception e) {
			result.success = false;
			result.message = "카드등록 중 오류가 발생했습니다. " + e.getMessage();
			log.error("RegistCardEx error", e);
		}

		return result;
	}

	@GetMapping("/baroUrl")
	public AjaxResult getCardManagementURL(@RequestParam Map<String, Object> param) {
		AjaxResult result = new AjaxResult();

		try {
			result = baroCardService.getCardManagementURL(param);
		} catch (Exception e) {
			result.success = false;
			result.message ="카드관리 URL 조회 중 오류가 발생했습니다. " + e.getMessage();
		}

		return result;
	}

	@PostMapping("/StopCard")
	public AjaxResult StopCard(@RequestParam Map<String, Object> param) {
		AjaxResult result = new AjaxResult();

		try {
			result = baroCardService.getStopCard(param);
		} catch (Exception e) {
			result.success = false;
			result.message = "카드연동 해지 중 오류가 발생했습니다. " + e.getMessage();
		}

		return result;
	}

	@PostMapping("/save")
	@ResponseBody
	public AjaxResult cardSave(@RequestParam Map<String, Object> param) {
		AjaxResult result = new AjaxResult();

		try {
			cardService.save(param);
			result.success = true;
			result.message = "저장되었습니다.";
		} catch (Exception e) {
			result.success = false;
			result.message = "저장 실패: " + e.getMessage();
		}

		return result;
	}
}
