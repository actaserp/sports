package mes.app.account_management;

import lombok.extern.slf4j.Slf4j;
import mes.app.account_management.service.SlipStatusService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/account_management/slip_status")
public class SlipStatusController {	//전표입력현황

	@Autowired
	SlipStatusService slipStatusService;

	@GetMapping("/read")
	public AjaxResult getSlipList(@RequestParam("start") String start,
																@RequestParam("end") String end,
																@RequestParam(value = "mssec", required = false) String mssec,
																@RequestParam(value = "sbuject", required = false) String sbuject) {

		List<Map<String, Object>> items = this.slipStatusService.getSlipList(start, end, mssec, sbuject);

		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;
	}
}
