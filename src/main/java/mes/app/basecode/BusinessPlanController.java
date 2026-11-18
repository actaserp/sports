package mes.app.basecode;

import lombok.extern.slf4j.Slf4j;
import mes.app.basecode.service.BusinessPlanService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** 사업계획등록 */
@Slf4j
@RestController
@RequestMapping("/api/basecode/business_plan")
public class BusinessPlanController {

	@Autowired
	BusinessPlanService service;

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

	private static String s(Map<String, Object> m, String k) {
		Object v = m.get(k);
		return v == null ? "" : String.valueOf(v).trim();
	}

	@GetMapping("/sections")
	public AjaxResult sections() { return run(service::sections, "사업구분 조회"); }

	@GetMapping("/read")
	public AjaxResult read(@RequestParam("yyyy") String yyyy,
												 @RequestParam(value = "bseccd", required = false) String bseccd,
												 @RequestParam(value = "keyword", required = false) String keyword) {
		AjaxResult r = run(() -> service.list(yyyy, bseccd, keyword), "조회");
		if (r.success) r.message = service.spjangnm();   // 출력 머리글용
		return r;
	}

	@PostMapping("/save")
	public AjaxResult save(@RequestBody Map<String, Object> body) {
		return run(() -> service.save(body), "저장");
	}

	@PostMapping("/delete")
	public AjaxResult delete(@RequestBody Map<String, Object> b) {
		return run(() -> { service.delete(s(b, "bsdate"), s(b, "bseccd"), s(b, "busicd")); return null; }, "삭제");
	}

	@SuppressWarnings("unchecked")
	@PostMapping("/carry")
	public AjaxResult carry(@RequestBody Map<String, Object> b) {
		return run(() -> service.carry(s(b, "syyyy"), (List<Map<String, Object>>) b.get("rows")), "자료이월");
	}

	@GetMapping("/cnt/read")
	public AjaxResult cntRead(@RequestParam String bsdate, @RequestParam String bseccd, @RequestParam String busicd) {
		return run(() -> service.cntList(bsdate, bseccd, busicd), "회차 조회");
	}

	@SuppressWarnings("unchecked")
	@PostMapping("/cnt/save")
	public AjaxResult cntSave(@RequestBody Map<String, Object> b) {
		return run(() -> { service.cntSave(s(b, "bsdate"), s(b, "bseccd"), s(b, "busicd"),
			(List<Map<String, Object>>) b.get("rows")); return null; }, "회차 저장");
	}

	@PostMapping("/cnt/delete")
	public AjaxResult cntDelete(@RequestBody Map<String, Object> b) {
		return run(() -> { service.cntDelete(s(b, "bsdate"), s(b, "bseccd"), s(b, "busicd"), s(b, "seq")); return null; }, "회차 삭제");
	}
}