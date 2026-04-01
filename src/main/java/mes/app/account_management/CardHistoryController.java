package mes.app.account_management;

import lombok.extern.slf4j.Slf4j;
import mes.app.account_management.service.CardHistoryService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/account_management/card_history")
public class CardHistoryController {

	@Autowired
	CardHistoryService cardHistoryService;

	@GetMapping("/read")
	public AjaxResult getCardHistoryList(    @RequestParam("start") String start,
																					 @RequestParam("end") String end,
																					 @RequestParam(value = "CardNo", required = false) String cardNo,
																					 @RequestParam(value = "accflag", required = false) String accflag) {

		List<Map<String, Object>> items = this.cardHistoryService.getCardHistoryList(start, end, cardNo, accflag);


		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;
	}

	@GetMapping("/baroCardList")
	public AjaxResult getBaroCardList(){

		List<Map<String, Object>> items = this.cardHistoryService.getbaroCardList();

		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;
	}

	//카드 내역 조회
	@PostMapping("/requestCardHistory")
	public AjaxResult baroCardHistory(@RequestParam Map<String, Object> param, Authentication auth) {
		return cardHistoryService.requestCardHistory(param, auth);
	}

	@GetMapping("/findBusim")
	public AjaxResult getBusim(@RequestParam(value = "busim") String busim){
		List<Map<String, Object>> items = this.cardHistoryService.getBusim(busim);

		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;

	}

	@GetMapping("/findAccnm")
	public AjaxResult getAccnm(@RequestParam(value = "accnm") String accnm){

		List<Map<String, Object>> items = this.cardHistoryService.getAccnm(accnm);

		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;

	}

	@GetMapping("/findIt1nm")
	public AjaxResult getIt1nm(@RequestParam(value = "it1nm") String it1nm){

		List<Map<String, Object>> items = this.cardHistoryService.getIt1nm(it1nm);

		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;

	}

	@GetMapping("/findIt2nm")
	public AjaxResult getIt2nm(@RequestParam(value = "it2nm") String it2nm){

		List<Map<String, Object>> items = this.cardHistoryService.getIt2nm(it2nm);

		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;

	}

	@PostMapping("/saveSelected")
	public AjaxResult saveSelected(@RequestParam("items") String items) {
		return cardHistoryService.updateSelected(items);
	}

	//전표 생성
	@PostMapping("/createSlip")
	public AjaxResult save(@RequestParam("items") String items, Authentication auth) {
		User user = (User) auth.getPrincipal();
		String userId = user.getUsername();
		return cardHistoryService.createSlip(items, userId);
	}
	
	//전표취소
	@PostMapping("/cancelSlip")
	public AjaxResult cancelSlip(@RequestParam("items") String items, Authentication auth) {
		User user = (User) auth.getPrincipal();
		String userId = user.getUsername();
		return cardHistoryService.cancelSlip(items, userId);
	}

}
