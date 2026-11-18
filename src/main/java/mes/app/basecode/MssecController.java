package mes.app.basecode;

import lombok.extern.slf4j.Slf4j;
import mes.app.basecode.service.MssecService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 재원구분등록 */
@Slf4j
@RestController
@RequestMapping("/api/basecode/mssec")
public class MssecController {

	@Autowired
	MssecService mssecService;

	@GetMapping("/read")
	public AjaxResult read(@RequestParam(value = "keyword", required = false) String keyword) {
		AjaxResult r = new AjaxResult();
		r.data = mssecService.list(keyword);
		return r;
	}

	@SuppressWarnings("unchecked")
	@PostMapping("/save")
	public AjaxResult save(@RequestBody Map<String, Object> body) {
		AjaxResult r = new AjaxResult();
		try {
			r.data = mssecService.save((List<Map<String, Object>>) body.get("rows"));
		} catch (IllegalArgumentException | IllegalStateException e) {
			r.success = false; r.message = e.getMessage();
		} catch (Exception e) {
			log.error("재원 저장 오류", e);
			r.success = false; r.message = "저장 중 오류가 발생했습니다.";
		}
		return r;
	}

	@PostMapping("/delete")
	public AjaxResult delete(@RequestBody Map<String, Object> body) {
		AjaxResult r = new AjaxResult();
		try {
			mssecService.delete(body.get("mssec") == null ? null : String.valueOf(body.get("mssec")));
		} catch (IllegalArgumentException | IllegalStateException e) {
			r.success = false; r.message = e.getMessage();
		} catch (Exception e) {
			log.error("재원 삭제 오류", e);
			r.success = false; r.message = "삭제 중 오류가 발생했습니다.";
		}
		return r;
	}
}