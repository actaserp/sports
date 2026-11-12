package mes.app.budget;

import lombok.extern.slf4j.Slf4j;
import mes.app.budget.service.BudgetReportService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/** 예산서 출력 */
@Slf4j
@RestController
@RequestMapping("/api/budget/report")
public class BudgetReportController {

	@Autowired
	BudgetReportService budgetReportService;

	/** 결산서및예산안대조표 */
	@GetMapping("/comparison")
	public AjaxResult comparison(@RequestParam("yyyy") String yyyy) {
		AjaxResult result = new AjaxResult();
		try {
			result.data = budgetReportService.getComparison(yyyy);
		} catch (Exception e) {
			log.error("결산서및예산안대조표 조회 오류", e);
			result.success = false;
			result.message = e.getMessage();
		}
		return result;
	}

	/** 세입내역서 (관항목 · 재원표시 공용) */
	@GetMapping("/revenueDetail")
	public AjaxResult revenueDetail(@RequestParam("yyyy") String yyyy,
																	@RequestParam(value = "mssec", required = false) String mssec) {
		AjaxResult result = new AjaxResult();
		try {
			result.data = budgetReportService.getRevenueDetail(yyyy, mssec);
		} catch (Exception e) {
			log.error("세입내역서 조회 오류", e);
			result.success = false;
			result.message = e.getMessage();
		}
		return result;
	}

	/** 세입의부 */
	@GetMapping("/revenueLedger")
	public AjaxResult revenueLedger(@RequestParam("yyyy") String yyyy,
																	@RequestParam(value = "mssec", required = false) String mssec) {
		AjaxResult result = new AjaxResult();
		try {
			result.data = budgetReportService.getRevenueLedger(yyyy, mssec);
		} catch (Exception e) {
			log.error("세입의부 조회 오류", e);
			result.success = false;
			result.message = e.getMessage();
		}
		return result;
	}

	/** 사업계획서(월별) */
	@GetMapping("/planByMonth")
	public AjaxResult planByMonth(@RequestParam("yyyy") String yyyy) {
		AjaxResult result = new AjaxResult();
		try {
			result.data = budgetReportService.getPlanByMonth(yyyy);
		} catch (Exception e) {
			log.error("사업계획서(월별) 조회 오류", e);
			result.success = false;
			result.message = e.getMessage();
		}
		return result;
	}

	/** 사업계획서(사업별) */
	@GetMapping("/planByProject")
	public AjaxResult planByProject(@RequestParam("yyyy") String yyyy) {
		AjaxResult result = new AjaxResult();
		try {
			result.data = budgetReportService.getPlanByProject(yyyy);
		} catch (Exception e) {
			log.error("사업계획서(사업별) 조회 오류", e);
			result.success = false;
			result.message = e.getMessage();
		}
		return result;
	}

	/** 세출의부 */
	@GetMapping("/expenditureLedger")
	public AjaxResult expenditureLedger(@RequestParam("yyyy") String yyyy,
																			@RequestParam(value = "mssec", required = false) String mssec) {
		AjaxResult result = new AjaxResult();
		try {
			result.data = budgetReportService.getExpenditureLedger(yyyy, mssec);
		} catch (Exception e) {
			log.error("세출의부 조회 오류", e);
			result.success = false;
			result.message = e.getMessage();
		}
		return result;
	}

	/** 세출내역서 */
	@GetMapping("/expenditureDetail")
	public AjaxResult expenditureDetail(@RequestParam("yyyy") String yyyy,
																			@RequestParam(value = "mssec", required = false) String mssec) {
		AjaxResult result = new AjaxResult();
		try {
			result.data = budgetReportService.getExpenditureDetail(yyyy, mssec);
		} catch (Exception e) {
			log.error("세출내역서 조회 오류", e);
			result.success = false;
			result.message = e.getMessage();
		}
		return result;
	}
}