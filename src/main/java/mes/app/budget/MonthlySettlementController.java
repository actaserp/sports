package mes.app.budget;

import lombok.extern.slf4j.Slf4j;
import mes.app.budget.service.BudgetSettleCompareService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/** 수지결산서 - 월결산서 (w_tb_aa009_sh17) */
@Slf4j
@RestController
@RequestMapping("/api/settlement/monthly")
public class MonthlySettlementController {

	@Autowired
	BudgetSettleCompareService service;

	/** tab: 1 관1 · 2 수입 · 3 지출 */
	@GetMapping("/read")
	public AjaxResult read(@RequestParam(value = "tab", defaultValue = "1") int tab,
												 @RequestParam("yyyy") String yyyy,
												 @RequestParam(value = "mm", required = false) String mm) {
		AjaxResult result = new AjaxResult();
		try {
			result.data = service.readMonthly(tab, yyyy, mm);
		} catch (Exception e) {
			log.error("월결산서 조회 오류", e);
			result.success = false;
			result.message = e.getMessage();
		}
		return result;
	}
}