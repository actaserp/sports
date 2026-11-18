package mes.app.basecode;

import lombok.extern.slf4j.Slf4j;
import mes.app.basecode.service.AccountCodeService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** 계정과목등록 */
@Slf4j
@RestController
@RequestMapping("/api/basecode/account")
public class AccountCodeController {

	@Autowired
	AccountCodeService service;

	private AjaxResult run(Supplier<Object> f, String what) {
		AjaxResult r = new AjaxResult();
		try {
			r.data = f.get();
		} catch (IllegalArgumentException | IllegalStateException e) {
			r.success = false; r.message = e.getMessage();
		} catch (Exception e) {
			log.error(what + " 오류", e);
			r.success = false; r.message = what + " 중 오류가 발생했습니다.";
		}
		return r;
	}

	@GetMapping("/read")
	public AjaxResult read(@RequestParam(value = "stext", required = false) String stext,
												 @RequestParam(value = "drcr", required = false) String drcr,
												 @RequestParam(value = "dcpl", required = false) String dcpl,
												 @RequestParam(value = "spyn", required = false) String spyn,
												 @RequestParam(value = "useyn", required = false) String useyn) {
		AjaxResult r = run(() -> service.list(stext, drcr, dcpl, spyn, useyn), "조회");
		if (r.success) r.message = service.spjangnm();   // 출력 머리글용
		return r;
	}

	@SuppressWarnings("unchecked")
	@PostMapping("/save")
	public AjaxResult save(@RequestBody Map<String, Object> body) {
		return run(() -> service.save((List<Map<String, Object>>) body.get("rows")), "저장");
	}

	@PostMapping("/delete")
	public AjaxResult delete(@RequestBody Map<String, Object> body) {
		return run(() -> { service.delete(String.valueOf(body.get("acccd")).trim()); return null; }, "삭제");
	}
}