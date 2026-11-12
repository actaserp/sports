package mes.app.budget;

import lombok.extern.slf4j.Slf4j;
import mes.app.budget.service.IncomeLedgerService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/** 수지결산서 - 세입부 */
@Slf4j
@RestController
@RequestMapping("/api/settlement/income_ledger")
public class IncomeLedgerController {

	@Autowired
	IncomeLedgerService service;

	/** fr, to: yyyymm */
	@GetMapping("/read")
	public AjaxResult read(@RequestParam("fr") String fr, @RequestParam("to") String to,
												 @RequestParam(value = "mssec", required = false) String mssec) {
		AjaxResult result = new AjaxResult();
		try {
			result.data = service.read(fr, to, mssec);
		} catch (Exception e) {
			log.error("세입부 조회 오류", e);
			result.success = false;
			result.message = e.getMessage();
		}
		return result;
	}
}