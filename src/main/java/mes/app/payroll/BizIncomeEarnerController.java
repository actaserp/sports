package mes.app.payroll;

import lombok.extern.slf4j.Slf4j;
import mes.app.payroll.service.BizIncomeEarnerService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 사업소득자등록 (원본 w_j3010) · 계좌현황 (원본 w_j3010_list) */
@Slf4j
@RestController
@RequestMapping("/api/payroll/bizinc_earner")
public class BizIncomeEarnerController {

	@Autowired
	BizIncomeEarnerService service;

	@GetMapping("/codes")
	public AjaxResult codes() {
		AjaxResult r = new AjaxResult();
		r.data = service.codes();
		return r;
	}

	/** 목록 · 계좌현황 공용 조회 */
	@GetMapping("/read")
	public AjaxResult read(@RequestParam(value = "pernm",  required = false) String pernm,
	                       @RequestParam(value = "rnum",   required = false) String rnum,
	                       @RequestParam(value = "clafi",  required = false) String clafi,
	                       @RequestParam(value = "conyn",  required = false) String conyn,
	                       @RequestParam(value = "gubun1", required = false) String gubun1,
	                       @RequestParam(value = "gubun2", required = false) String gubun2) {
		AjaxResult r = new AjaxResult();
		r.data = service.list(pernm, rnum, clafi, conyn, gubun1, gubun2);
		return r;
	}

	@SuppressWarnings("unchecked")
	@PostMapping("/save")
	public AjaxResult save(@RequestBody Map<String, Object> body) {
		AjaxResult r = new AjaxResult();
		try {
			Map<String, Object> row = (Map<String, Object>) body.get("row");
			Object force = body.get("force");
			r.data = service.save(row, force == null ? null : String.valueOf(force));
		} catch (IllegalArgumentException | IllegalStateException e) {
			r.success = false; r.message = e.getMessage();
		} catch (Exception e) {
			log.error("사업소득자 저장 오류", e);
			r.success = false; r.message = "저장 중 오류가 발생했습니다.";
		}
		return r;
	}

	@PostMapping("/delete")
	public AjaxResult delete(@RequestBody Map<String, Object> body) {
		AjaxResult r = new AjaxResult();
		try {
			service.delete(body.get("seq") == null ? null : String.valueOf(body.get("seq")));
		} catch (IllegalArgumentException | IllegalStateException e) {
			r.success = false; r.message = e.getMessage();
		} catch (Exception e) {
			log.error("사업소득자 삭제 오류", e);
			r.success = false; r.message = "삭제 중 오류가 발생했습니다.";
		}
		return r;
	}

	/** 사업소득 ↔ 기타소득 복사 */
	@PostMapping("/copy")
	public AjaxResult copy(@RequestBody Map<String, Object> body) {
		AjaxResult r = new AjaxResult();
		try {
			r.data = service.copy(String.valueOf(body.get("clafi")), String.valueOf(body.get("seq")));
		} catch (IllegalArgumentException | IllegalStateException e) {
			r.success = false; r.message = e.getMessage();
		} catch (Exception e) {
			log.error("사업소득자 복사 오류", e);
			r.success = false; r.message = "복사 중 오류가 발생했습니다.";
		}
		return r;
	}
}
