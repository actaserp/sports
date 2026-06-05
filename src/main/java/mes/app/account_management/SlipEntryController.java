package mes.app.account_management;

import lombok.extern.slf4j.Slf4j;
import mes.app.account_management.service.SlipEntryService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/account_management/slip_entry")
public class SlipEntryController {	//전표등록

	@Autowired
	SlipEntryService slipEntryService;

	@GetMapping("/findBusim")
	public AjaxResult getBusim(@RequestParam(value = "busim") String busim){
		List<Map<String, Object>> items = this.slipEntryService.getBusim(busim);

		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;

	}

	@GetMapping("/findAccnm")
	public AjaxResult getAccnm(@RequestParam(value = "accnm") String accnm,
														 @RequestParam(value = "type") String type){

		List<Map<String, Object>> items = this.slipEntryService.getAccnm(accnm, type);

		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;

	}

	@GetMapping("/findIt1nm")
	public AjaxResult getIt1nm(@RequestParam(value = "it1nm") String it1nm,
														 @RequestParam(value = "tiosec")String tiosec){

		List<Map<String, Object>> items = this.slipEntryService.getIt1nm(it1nm, tiosec);

		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;

	}

	@GetMapping("/findIt2nm")
	public AjaxResult getIt2nm(@RequestParam(value = "it2nm") String it2nm,
														 @RequestParam(value = "tiosec") String tiosec){

		List<Map<String, Object>> items = this.slipEntryService.getIt2nm(it2nm, tiosec);

		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;

	}

	@GetMapping("/mssec")
	public AjaxResult getMssec(){
		List<Map<String, Object>> items = this.slipEntryService.getMssec();

		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;
	}

	@GetMapping("/getHeader")
	public AjaxResult getHeader(@RequestParam(value="spdate")String spdate,
															@RequestParam(value="spnum") String spnum){
		List<Map<String, Object>> items = this.slipEntryService.getHeader(spdate, spnum);
		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;
	}

	@GetMapping("/getLines")
	public AjaxResult getLines(@RequestParam(value="spdate")String spdate,
														 @RequestParam(value="spnum") String spnum){

		List<Map<String, Object>> items = this.slipEntryService.getLines(spdate, spnum);
		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;
	}


	@PostMapping("/save")
	public AjaxResult slipEntrySave(@RequestBody Map<String, Object> payload,
																	HttpServletRequest request,
																	Authentication auth) {
		AjaxResult result = new AjaxResult();
		try {
			User user = (User) auth.getPrincipal();
			String userId = user.getUsername();

			if (userId == null || userId.trim().isEmpty()) {
				result.success = false;
				result.message = "로그인 정보가 없습니다.";
				return result;
			}

			Map<String, Object> savedData = slipEntryService.saveSlip(payload, request, userId );
			result.data = savedData;
			result.success = true;
		} catch (Exception e) {
			log.error("전표 저장 오류", e);
			result.success = false;
			result.message = e.getMessage();
		}
		return result;
	}


	@PostMapping("/delete")
	public AjaxResult slipEntryDelete(@RequestBody Map<String, Object> payload) {
		AjaxResult result = new AjaxResult();
//		log.info("==== 전표 삭제 요청 진입 ====");
//		log.info("payload = {}", payload);
//		log.info("spdate = {}, spnum = {}", payload.get("spdate"), payload.get("spnum"));
		try {
			slipEntryService.deleteSlip(payload);
			result.success = true;
//			log.info("==== 전표 삭제 성공 ====");
		} catch (Exception e) {
			log.error("전표 삭제 오류", e);
			result.success = false;
			result.message = e.getMessage();
		}
		return result;
	}

	@PostMapping("/copy")
	public AjaxResult slipEntryCopy(@RequestBody Map<String, Object> payload, Authentication auth) {
		AjaxResult result = new AjaxResult();

		User user = (User) auth.getPrincipal();
		String userId = user.getUsername();

		if (userId == null || userId.trim().isEmpty()) {
			result.success = false;
			result.message = "로그인 정보가 없습니다.";
			return result;
		}

		try {
			Map<String, Object> copied = slipEntryService.copySlip(payload, userId);
			result.data = copied;
			result.success = true;
		} catch (Exception e) {
			log.error("전표 복사 오류", e);
			result.success = false;
			result.message = e.getMessage();
		}
		return result;
	}

	//빈 전표번호 목록 조회
	@GetMapping("/availableSpnums")
	public AjaxResult getAvailableSpnums(@RequestParam("spdate")String spdate){
		AjaxResult result = new AjaxResult();
		try {
			List<Map<String, Object>> item = slipEntryService.getAvailableSpnums(spdate);
			result.data = item;
			result.success = true;
		} catch (Exception e) {
			log.error("전표 복사 오류", e);
			result.success = false;
			result.message = e.getMessage();
		}
		return result;
	}

	@PostMapping("/changeSpnum")
	public AjaxResult changeSpnum(@RequestBody Map<String, Object> payload) {
		AjaxResult result = new AjaxResult();
		try {
			slipEntryService.changeSpnum(payload);
			result.success = true;
		} catch (Exception e) {
			log.error("전표번호 변경 오류", e);
			result.success = false;
			result.message = e.getMessage();
		}
		return result;
	}

}
