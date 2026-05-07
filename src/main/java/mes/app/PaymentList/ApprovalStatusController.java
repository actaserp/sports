package mes.app.PaymentList;

import lombok.extern.slf4j.Slf4j;
import mes.app.PaymentList.service.ApprovalStatusService;
import mes.app.common.TenantContext;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/approval_status")
public class ApprovalStatusController {//전자결재 현황

	@Autowired
	ApprovalStatusService approvalStatusService;

	@GetMapping("/readCalenderGrid")
	public AjaxResult getList(
		@RequestParam(value = "search_startDate", required = false) String searchStartDate,
		@RequestParam(value = "search_endDate",   required = false) String searchEndDate,
		@RequestParam(value = "search_type",      required = false) String searchType,
		Authentication auth) {

		User user        = (User) auth.getPrincipal();
		Integer personid = user.getPersonid();
		String spjangcd  = TenantContext.get();

		AjaxResult result = new AjaxResult();
		result.data = approvalStatusService.getCalendarGridList(
			personid, spjangcd, searchStartDate, searchEndDate, searchType);

		return result;
	}

	@GetMapping("/initDatas")
	public AjaxResult initDatas(
		@RequestParam(value = "search_startDate", required = false) String searchStartDate,
		@RequestParam(value = "search_endDate",   required = false) String searchEndDate,
		Authentication auth) {

		User user        = (User) auth.getPrincipal();
		Integer personid = user.getPersonid();
		String spjangcd  = TenantContext.get();

		AjaxResult result = new AjaxResult();
		result.data = approvalStatusService.initDatas(personid, spjangcd, searchStartDate, searchEndDate);

		return result;
	}

	@GetMapping("/readCalenderGrid2")
	public AjaxResult getCalendarGrid2(
		@RequestParam(value = "search_startDate", required = false) String searchStartDate,
		@RequestParam(value = "search_endDate",   required = false) String searchEndDate,
		Authentication auth) {

		User user        = (User) auth.getPrincipal();
		Integer personid = user.getPersonid();
		String spjangcd  = TenantContext.get();

		AjaxResult result = new AjaxResult();
		result.data = approvalStatusService.getCalendarGridList2(
			personid, spjangcd, searchStartDate, searchEndDate);

		return result;
	}

}
