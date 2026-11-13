package mes.app.system;

import lombok.extern.slf4j.Slf4j;
import mes.app.system.service.PersonListService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/hr/PersonList")
@Slf4j
public class PersonListController { // 사원관리

	@Autowired
	PersonListService personListService;

	// 목록 조회
	@GetMapping("/read")
	public AjaxResult read(
		@RequestParam(value = "pernm", required = false) String pernm,
		@RequestParam(value = "divicd", required = false) String divicd,
		@RequestParam(value = "retire", required = false) String retire) {
		AjaxResult result = new AjaxResult();
		result.data = personListService.selectList(pernm, divicd, retire);
		return result;
	}

	// 단건 조회 (상세 폼)
	@GetMapping("/readOne")
	public AjaxResult readOne(@RequestParam(value = "perid") String perid) {
		AjaxResult result = new AjaxResult();
		result.data = personListService.selectOne(perid);
		return result;
	}

	// 신규 사번 채번
	@GetMapping("/nextPerid")
	public AjaxResult nextPerid() {
		AjaxResult result = new AjaxResult();
		result.data = personListService.nextPerid();
		return result;
	}

	// 저장 (신규/수정)
	@PostMapping("/save")
	public AjaxResult save(@RequestBody Map<String, Object> body) {
		AjaxResult result = new AjaxResult();
		try {
			result.data = personListService.save(body);
			result.success = true;
			result.message = "저장되었습니다.";
		} catch (Exception e) {
			log.error("[PersonList] save 오류", e);
			result.success = false;
			result.message = "저장 중 오류가 발생했습니다.";
		}
		return result;
	}

	// 삭제
	@PostMapping("/delete")
	public AjaxResult delete(@RequestBody Map<String, Object> body) {
		AjaxResult result = new AjaxResult();
		try {
			String perid = body.get("perid") == null ? "" : String.valueOf(body.get("perid")).trim();
			personListService.delete(perid);
			result.success = true;
			result.message = "삭제되었습니다.";
		} catch (Exception e) {
			log.error("[PersonList] delete 오류", e);
			result.success = false;
			result.message = "삭제 중 오류가 발생했습니다.";
		}
		return result;
	}
}