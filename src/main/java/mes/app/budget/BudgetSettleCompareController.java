package mes.app.budget;

import lombok.extern.slf4j.Slf4j;
import mes.app.budget.service.BudgetSettleCompareService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/** 수지결산서 - 예산 및 결산대조표 (w_tb_aa009_sh12) */
@Slf4j
@RestController
@RequestMapping("/api/settlement/budget_compare")
public class BudgetSettleCompareController {

	@Autowired
	BudgetSettleCompareService service;

	/** tab: 1 관1 · 2 수입 · 3 지출 · 4 사업별대조표 */
	@GetMapping("/read")
	public AjaxResult read(@RequestParam(value = "tab", defaultValue = "1") int tab,
												 @RequestParam("yyyy") String yyyy,
												 @RequestParam(value = "mm1", required = false) String mm1,
												 @RequestParam(value = "mm2", required = false) String mm2,
												 @RequestParam(value = "busicd", required = false) String busicd,
												 @RequestParam(value = "businm", required = false) String businm,
												 @RequestParam(value = "mssec", required = false) String mssec) {
		AjaxResult result = new AjaxResult();
		try {
			result.data = service.read(tab, yyyy, mm1, mm2, busicd, businm, mssec);
		} catch (Exception e) {
			log.error("예산 및 결산대조표 조회 오류", e);
			result.success = false;
			result.message = e.getMessage();
		}
		return result;
	}
}