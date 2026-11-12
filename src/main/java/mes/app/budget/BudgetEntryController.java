package mes.app.budget;

import lombok.extern.slf4j.Slf4j;
import mes.app.budget.service.BudgetEntryService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 세입·세출내역서 등록 (예산)
 *   /api/budget/revenue/entry/...     세입 (tiosec 1)
 *   /api/budget/expenditure/entry/... 세출 (tiosec 2)
 */
@Slf4j
@RestController
@RequestMapping("/api/budget/{kind}/entry")
public class BudgetEntryController {

	@Autowired
	BudgetEntryService budgetEntryService;

	@GetMapping("/read")
	public AjaxResult read(@PathVariable("kind") String kind, @RequestParam("yyyy") String yyyy) {
		AjaxResult result = new AjaxResult();
		result.data = budgetEntryService.getList(tiosec(kind), yyyy);
		return result;
	}

	/** 세출 입력 탭: 헤더(관 · 항 · 사업) 단위 조회 */
	@GetMapping("/readEntry")
	public AjaxResult readEntry(@PathVariable("kind") String kind,
															@RequestParam("yyyy") String yyyy,
															@RequestParam("acccd") String acccd,
															@RequestParam("it1cd") String it1cd,
															@RequestParam(value = "bsdate", required = false) String bsdate,
															@RequestParam(value = "bseccd", required = false) String bseccd,
															@RequestParam(value = "busicd", required = false) String busicd) {
		AjaxResult result = new AjaxResult();
		result.data = budgetEntryService.getExpenditureList(yyyy, acccd, it1cd, bsdate, bseccd, busicd);
		return result;
	}

	@GetMapping("/searchIt1")
	public AjaxResult searchIt1(@PathVariable("kind") String kind,
															@RequestParam(value = "keyword", required = false) String keyword) {
		AjaxResult result = new AjaxResult();
		result.data = budgetEntryService.searchIt1(tiosec(kind), keyword);
		return result;
	}

	@GetMapping("/summary")
	public AjaxResult summary(@PathVariable("kind") String kind, @RequestParam("yyyy") String yyyy) {
		AjaxResult result = new AjaxResult();
		result.data = budgetEntryService.getSummary(yyyy);
		return result;
	}

	@SuppressWarnings("unchecked")
	@PostMapping("/save")
	public AjaxResult save(@PathVariable("kind") String kind, @RequestBody Map<String, Object> payload) {
		AjaxResult result = new AjaxResult();
		try {
			List<Map<String, Object>> rows = (List<Map<String, Object>>) payload.get("rows");
			Map<String, Object> header = (Map<String, Object>) payload.get("header");
			result.data = budgetEntryService.save(tiosec(kind), String.valueOf(payload.get("yyyy")),
				rows == null ? List.of() : rows, header);
		} catch (Exception e) {
			log.error("예산 내역 저장 오류", e);
			result.success = false;
			result.message = e.getMessage();
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	@PostMapping("/delete")
	public AjaxResult delete(@PathVariable("kind") String kind, @RequestBody Map<String, Object> payload) {
		AjaxResult result = new AjaxResult();
		try {
			List<Map<String, Object>> keys = (List<Map<String, Object>>) payload.get("keys");
			result.data = budgetEntryService.delete(tiosec(kind), keys == null ? List.of() : keys);
		} catch (Exception e) {
			log.error("예산 내역 삭제 오류", e);
			result.success = false;
			result.message = e.getMessage();
		}
		return result;
	}

	@GetMapping("/carryover/count")
	public AjaxResult carryoverCount(@PathVariable("kind") String kind, @RequestParam("yyyy") String yyyy) {
		AjaxResult result = new AjaxResult();
		result.data = budgetEntryService.carryoverCount(tiosec(kind), yyyy);
		return result;
	}

	@PostMapping("/carryover")
	public AjaxResult carryover(@PathVariable("kind") String kind, @RequestBody Map<String, Object> payload) {
		AjaxResult result = new AjaxResult();
		try {
			String yyyy = String.valueOf(payload.get("yyyy"));
			String mode = String.valueOf(payload.get("mode"));
			result.data = "cancel".equals(mode)
											? budgetEntryService.carryoverCancel(tiosec(kind), yyyy)
											: budgetEntryService.carryoverRun(tiosec(kind), yyyy);
		} catch (Exception e) {
			log.error("예산 이월 오류", e);
			result.success = false;
			result.message = e.getMessage();
		}
		return result;
	}

	private String tiosec(String kind) {
		switch (kind) {
			case "revenue": return "1";
			case "expenditure": return "2";
			default: throw new IllegalArgumentException("알 수 없는 구분: " + kind);
		}
	}
}