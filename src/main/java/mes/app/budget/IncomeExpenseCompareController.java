package mes.app.budget;

import lombok.extern.slf4j.Slf4j;
import mes.app.budget.service.IncomeExpenseCompareService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/** 수지결산서 - 세입세출대조표 (w_tb_aa009_sh11) */
@Slf4j
@RestController
@RequestMapping("/api/settlement/income_expense")
public class IncomeExpenseCompareController {

	@Autowired
	IncomeExpenseCompareService service;

	@GetMapping("/read")
	public AjaxResult read(@RequestParam("yyyy") String yyyy) {
		AjaxResult result = new AjaxResult();
		try {
			result.data = service.read(yyyy);
		} catch (Exception e) {
			log.error("세입세출대조표 조회 오류", e);
			result.success = false;
			result.message = e.getMessage();
		}
		return result;
	}
}