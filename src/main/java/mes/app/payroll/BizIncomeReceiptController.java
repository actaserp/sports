package mes.app.payroll;

import lombok.extern.slf4j.Slf4j;
import mes.app.payroll.service.BizIncomeReceiptService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.function.Supplier;

/** 사업소득원천징수영수증 (원본 w_j3013) */
@Slf4j
@RestController
@RequestMapping("/api/payroll/bizinc_receipt")
public class BizIncomeReceiptController {

	@Autowired
	BizIncomeReceiptService service;

	private AjaxResult run(Supplier<Object> fn) {
		AjaxResult r = new AjaxResult();
		try {
			r.data = fn.get();
		} catch (IllegalArgumentException | IllegalStateException e) {
			r.success = false; r.message = e.getMessage();
		} catch (Exception e) {
			log.error("사업소득원천징수영수증 조회 오류", e);
			r.success = false; r.message = "조회 중 오류가 발생했습니다.";
		}
		return r;
	}

	@GetMapping("/read")
	public AjaxResult read(@RequestParam String acumy, @RequestParam String acumm,
	                       @RequestParam(defaultValue = "0") String yearly) {
		return run(() -> service.list(acumy, acumm, yearly));
	}

	@SuppressWarnings("unchecked")
	@PostMapping("/print")
	public AjaxResult print(@RequestBody java.util.Map<String, Object> b) {
		return run(() -> service.printRows((java.util.List<String>) b.get("seqs"),
			String.valueOf(b.get("acumy")), String.valueOf(b.get("acumm")), String.valueOf(b.get("yearly"))));
	}

	@GetMapping("/detail")
	public AjaxResult detail(@RequestParam String seq, @RequestParam String acumy, @RequestParam String acumm,
	                         @RequestParam(defaultValue = "0") String yearly) {
		return run(() -> service.detail(seq, acumy, acumm, yearly));
	}
}
