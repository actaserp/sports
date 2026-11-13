package mes.app.system;

import mes.app.system.service.PreferencesService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 시스템관리 > 환경 설정 — 결재라인 (2026-11-13)
 *   GET  /api/system/preferences/settle        결재명 · 숨김 · 전결 · 사선 1~8
 *   POST /api/system/preferences/settle/save   같은 이름의 값들 (체크는 '1' / '0')
 */
@RestController
@RequestMapping("/api/system/preferences")
public class PreferencesController {

	@Autowired
	PreferencesService service;

	@GetMapping("/settle")
	public AjaxResult settle() {
		AjaxResult r = new AjaxResult();
		try {
			r.data = service.readSettle();
		} catch (RuntimeException e) {
			r.success = false;
			r.message = e.getMessage();
		}
		return r;
	}

	@PostMapping("/settle/save")
	public AjaxResult saveSettle(@RequestParam Map<String, String> body) {
		AjaxResult r = new AjaxResult();
		try {
			service.saveSettle(body);
		} catch (RuntimeException e) {
			r.success = false;
			r.message = e.getMessage();
		}
		return r;
	}
}