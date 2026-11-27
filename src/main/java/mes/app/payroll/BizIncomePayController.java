package mes.app.payroll;

import lombok.extern.slf4j.Slf4j;
import mes.app.payroll.service.BizIncomePayService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** 사업소득급여등록 (원본 w_j3011) · 일괄입력 (원본 w_j3011_pop) */
@Slf4j
@RestController
@RequestMapping("/api/payroll/bizinc_pay")
public class BizIncomePayController {

	@Autowired
	BizIncomePayService service;

	private AjaxResult run(String what, Supplier<Object> fn) {
		AjaxResult r = new AjaxResult();
		try {
			r.data = fn.get();
		} catch (IllegalArgumentException | IllegalStateException e) {
			r.success = false; r.message = e.getMessage();
		} catch (Exception e) {
			log.error("사업소득급여 {} 오류", what, e);
			r.success = false; r.message = what + " 중 오류가 발생했습니다.";
		}
		return r;
	}

	@GetMapping("/codes")
	public AjaxResult codes() { return run("조회", service::codes); }

	@GetMapping("/read")
	public AjaxResult read(@RequestParam(defaultValue = "0") String flag,
	                       @RequestParam(required = false) String ym1,
	                       @RequestParam(required = false) String ym2,
	                       @RequestParam(required = false) String gubun1,
	                       @RequestParam(required = false) String gubun2,
	                       @RequestParam(required = false) String pernm) {
		return run("조회", () -> service.list(flag, ym1, ym2, gubun1, gubun2, pernm));
	}

	@GetMapping("/totals")
	public AjaxResult totals(@RequestParam(defaultValue = "0") String flag,
	                         @RequestParam(required = false) String ym1,
	                         @RequestParam(required = false) String ym2) {
		return run("조회", () -> service.totals(flag, ym1, ym2));
	}

	@GetMapping("/history")
	public AjaxResult history(@RequestParam String seq,
	                          @RequestParam(defaultValue = "0") String flag,
	                          @RequestParam(required = false) String ym1,
	                          @RequestParam(required = false) String ym2) {
		return run("조회", () -> service.history(seq, flag, ym1, ym2));
	}

	@GetMapping("/one")
	public AjaxResult one(@RequestParam String seq, @RequestParam String paybasic,
	                      @RequestParam String paydate, @RequestParam(required = false) String payclafi) {
		return run("조회", () -> service.one(seq, paybasic, paydate, payclafi));
	}

	@GetMapping("/excel")
	public AjaxResult excel(@RequestParam String ym1, @RequestParam String ym2) {
		return run("조회", () -> service.excelList(ym1, ym2));
	}

	@SuppressWarnings("unchecked")
	@PostMapping("/save")
	public AjaxResult save(@RequestBody Map<String, Object> body) {
		return run("저장", () -> {
			service.save((Map<String, Object>) body.get("row"), (Map<String, Object>) body.get("org"));
			return null;
		});
	}

	@PostMapping("/delete")
	public AjaxResult delete(@RequestBody Map<String, Object> body) {
		return run("삭제", () -> { service.delete(body); return null; });
	}

	// ── 일괄입력 팝업 ─────────────────────────────

	@GetMapping("/pop/paydates")
	public AjaxResult paydates() { return run("조회", service::paydates); }

	@GetMapping("/pop/read")
	public AjaxResult popRead(@RequestParam String paydate) { return run("조회", () -> service.popList(paydate)); }

	@GetMapping("/pop/earners")
	public AjaxResult earners() { return run("조회", service::earners); }

	@GetMapping("/pop/copy/check")
	public AjaxResult copyCheck(@RequestParam String srcPaydate, @RequestParam String paybasic, @RequestParam String paydate) {
		return run("조회", () -> service.copyCheck(srcPaydate, paybasic, paydate));
	}

	@PostMapping("/pop/copy")
	public AjaxResult copy(@RequestBody Map<String, Object> b) {
		return run("복사", () -> service.copy(String.valueOf(b.get("srcPaydate")), String.valueOf(b.get("srcPaybasic")),
			String.valueOf(b.get("paybasic")), String.valueOf(b.get("paydate")), Boolean.TRUE.equals(b.get("replace"))));
	}

	@SuppressWarnings("unchecked")
	@PostMapping("/pop/save")
	public AjaxResult popSave(@RequestBody Map<String, Object> body) {
		return run("저장", () -> service.saveBatch(
			(List<Map<String, Object>>) body.get("rows"), (List<Map<String, Object>>) body.get("deleted")));
	}
}
