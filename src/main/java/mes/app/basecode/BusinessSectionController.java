package mes.app.basecode;

import lombok.extern.slf4j.Slf4j;
import mes.app.basecode.service.BusinessSectionService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 사업구분등록 */
@Slf4j
@RestController
@RequestMapping("/api/basecode/business_section")
public class BusinessSectionController {

	@Autowired
	BusinessSectionService businessSectionService;

	@GetMapping("/read")
	public AjaxResult read(@RequestParam(value = "keyword", required = false) String keyword) {
		AjaxResult r = new AjaxResult();
		r.data = businessSectionService.list(keyword);
		r.message = businessSectionService.spjangnm();   // 출력 머리글용
		return r;
	}

	@SuppressWarnings("unchecked")
	@PostMapping("/save")
	public AjaxResult save(@RequestBody Map<String, Object> body) {
		AjaxResult r = new AjaxResult();
		try {
			r.data = businessSectionService.save((List<Map<String, Object>>) body.get("rows"));
		} catch (IllegalArgumentException | IllegalStateException e) {
			r.success = false; r.message = e.getMessage();
		} catch (Exception e) {
			log.error("사업구분 저장 오류", e);
			r.success = false; r.message = "저장 중 오류가 발생했습니다.";
		}
		return r;
	}

	@PostMapping("/delete")
	public AjaxResult delete(@RequestBody Map<String, Object> body) {
		AjaxResult r = new AjaxResult();
		try {
			businessSectionService.delete(body.get("bseccd") == null ? null : String.valueOf(body.get("bseccd")));
		} catch (IllegalArgumentException | IllegalStateException e) {
			r.success = false; r.message = e.getMessage();
		} catch (Exception e) {
			log.error("사업구분 삭제 오류", e);
			r.success = false; r.message = "삭제 중 오류가 발생했습니다.";
		}
		return r;
	}
}