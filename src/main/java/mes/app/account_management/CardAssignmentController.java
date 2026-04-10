package mes.app.account_management;

import lombok.extern.slf4j.Slf4j;
import mes.app.account_management.service.CardAssignmentService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/account_management/card_assignment")
public class CardAssignmentController {	//카드지급

	@Autowired
	CardAssignmentService cardAssignmentService;

	@GetMapping("/read")
	public AjaxResult getCardAssignmentList(@RequestParam(value = "start") String start,
																					@RequestParam(value = "end") String end,
																					@RequestParam(value = "accountName", required = false) String accountName,
																					@RequestParam(value = "accflag") String accflag) {

		List<Map<String, Object>> items
			= this.cardAssignmentService.getCardAssignmentList(start, end,accountName, accflag);

		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;
	}

	@PostMapping("/createPaymentSlip")
	public AjaxResult createPaymentSlip(@RequestParam("items") String items, Authentication auth) {
		User user = (User) auth.getPrincipal();
		String userId = user.getUsername();
		return cardAssignmentService.createPaymentSlip(items, userId);
	}

	@PostMapping("/PaymentCancelSlip")
	public AjaxResult PaymentCancelSlip(@RequestParam("items") String items, Authentication auth) {
		User user = (User) auth.getPrincipal();
		String userId = user.getUsername();
		return cardAssignmentService.PaymentCancelSlip(items, userId);
	}

	@PostMapping("/save")
	public AjaxResult save(@RequestParam String items){
		return cardAssignmentService.updateSelected(items);
	}
}
