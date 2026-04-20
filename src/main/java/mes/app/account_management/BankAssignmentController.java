package mes.app.account_management;

import lombok.extern.slf4j.Slf4j;
import mes.app.account_management.service.BankAssignmentService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/account_management/bank_assignment")
@Slf4j
public class BankAssignmentController {	//전표 분개

	@Autowired
	BankAssignmentService bankAssignmentService;

	@GetMapping("/read")
	public AjaxResult getBankList(@RequestParam(value = "start") String start,
																@RequestParam(value = "end") String end,
																@RequestParam(value = "accountNameHidden", required = false) String accountNameHidden,
																@RequestParam(value = "accountName", required = false) String accnum,
																@RequestParam(value = "accflag")String accflag,
																@RequestParam(value = "search_businm",required = false) String search_businm,
																@RequestParam(value = "bsdate", required = false) String bsdate,
																@RequestParam(value = "bseccd",required = false) String bseccd,
																@RequestParam(value = "busicd", required = false) String busicd
																) {

		List<Map<String, Object>> items =
			this.bankAssignmentService.getBankHistoryList(start, end,accnum,accountNameHidden, accflag,search_businm,bsdate,bseccd, busicd);

		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;

	}

	//입출금내역 저장
	@PostMapping("/saveSelected")
	public AjaxResult saveSelected(@RequestParam("items") String items) {
		return bankAssignmentService.updateSelected(items);
	}

	//전표등록
	@PostMapping("/createSlip")
	public AjaxResult createSlip(
		@RequestParam("items") String items,
		@RequestParam("slipType") String slipType,  // 발행구분 추가
		Authentication auth) {

		AjaxResult result = new AjaxResult();

		try {
			User user = (User) auth.getPrincipal();
			String userId = user.getUsername();

			if (userId == null || userId.trim().isEmpty()) {
				result.success = false;
				result.message = "로그인 정보가 없습니다.";
				return result;
			}

			// 발행구분에 따라 Service 호출 분기
			if ("0".equals(slipType)) {
				// 개별전표: 각 항목마다 별도 전표번호 생성
				result = bankAssignmentService.createSlipIndividual(items, userId);
			} else if ("1".equals(slipType)) {
				// 통합전표: 모든 항목을 하나의 전표번호로 통합
				result = bankAssignmentService.createSlipIntegrated(items, userId);
			} else {
				result.success = false;
				result.message = "잘못된 발행구분입니다.";
			}

		} catch (Exception e) {
			e.printStackTrace();
			result.success = false;
			result.message = "전표 등록 중 오류가 발생했습니다: " + e.getMessage();
		}

		return result;
	}

	//전표생성취소
	@PostMapping("/cancelSlip")
	public AjaxResult cancelSlip(@RequestParam("items") String items, Authentication auth) {

		AjaxResult result = new AjaxResult();

		try {
			User user = (User) auth.getPrincipal();
			String userId = user.getUsername();
			if (userId == null || userId.trim().isEmpty()) {
				result.success = false;
				result.message = "로그인 정보가 없습니다.";
				return result;
			}

			// Service 호출
			result = bankAssignmentService.cancelSlip(items, userId);

		} catch (Exception e) {
			e.printStackTrace();
			result.success = false;
			result.message = "전표 취소 중 오류가 발생했습니다: " + e.getMessage();
		}

		return result;
	}

	@GetMapping("/bankAccHistory")
	public AjaxResult bankAccHistory(){
		List<Map<String, Object>> items = this.bankAssignmentService.bankAccHistory();
		AjaxResult result = new AjaxResult();
		result.data = items;
		return result;
	}

	//전표 생성[항 코드]
	@GetMapping("/findIt1nm")
	public AjaxResult getIt1nm(@RequestParam(value = "it1nm") String it1nm,
														 @RequestParam(value = "inout_type") String inout_type){

		List<Map<String, Object>> items = this.bankAssignmentService.getIt1nm(it1nm, inout_type);

		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;

	}


}
