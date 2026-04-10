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
																@RequestParam(value = "cboCompanyHidden", required = false) String cboCompanyHidden,
																@RequestParam(value = "cltflag", required = false) String cltflag,
																@RequestParam(value = "accountNameHidden", required = false) String accountNameHidden,
																@RequestParam(value = "accountName", required = false) String accnum,
																@RequestParam(value = "accflag")String accflag) {

		List<Map<String, Object>> items =
			this.bankAssignmentService.getBankHistoryList(start, end,cboCompanyHidden, cltflag, accnum,accountNameHidden, accflag);

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

}
