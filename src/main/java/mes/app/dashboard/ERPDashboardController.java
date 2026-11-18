package mes.app.dashboard;

import lombok.extern.slf4j.Slf4j;
import mes.app.dashboard.service.ERPDashboardService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 대시보드 데이터.
 *
 * 판마다 따로 부르지 않고 한 번에 내려준다. 화면이 열릴 때 한 번만 도는 조회라
 * 요청을 쪼개면 스피너만 여러 개 생기고 이득이 없다.
 *
 * 판 하나가 실패해도 나머지는 그린다. 대시보드는 첫 화면이라
 * 한 곳이 깨졌다고 통째로 빈 화면이 되면 시스템이 죽은 것처럼 보인다.
 */
@Slf4j
@RestController
@RequestMapping("/api/dashboard")
public class ERPDashboardController {

	@Autowired
	ERPDashboardService erpDashboardService;

	@GetMapping("/data")
	public AjaxResult data(@RequestParam(value = "year", required = false) String year) {

		AjaxResult r = new AjaxResult();
		String yyyy = (year == null || year.isBlank())
										? String.valueOf(LocalDate.now().getYear())
										: year.trim();

		Map<String, Object> data = new LinkedHashMap<>();
		Map<String, String> failed = new LinkedHashMap<>();
		data.put("year", yyyy);

		try {
			data.put("unfixedSlip", erpDashboardService.getUnfixedSlipCount(yyyy));
		} catch (Exception e) {
			log.error("[Dashboard] 미확정 전표 조회 오류", e);
			failed.put("확정하지 않은 전표", rootMessage(e));
		}

		try {
			data.put("fundSource", erpDashboardService.getByFundSource(yyyy));
		} catch (Exception e) {
			log.error("[Dashboard] 재원별 세입·세출 조회 오류", e);
			failed.put("재원별 세입·세출", rootMessage(e));
		}

		try {
			data.put("recentSlips", erpDashboardService.getRecentSlips(yyyy, 8));
		} catch (Exception e) {
			log.error("[Dashboard] 최근 전표 조회 오류", e);
			failed.put("최근 전표", rootMessage(e));
		}

		try {
			data.put("monthly", erpDashboardService.getMonthly(yyyy));
		} catch (Exception e) {
			log.error("[Dashboard] 월별 세입·세출 조회 오류", e);
			failed.put("월별 세입·세출", rootMessage(e));
		}

		try {
			data.put("account", erpDashboardService.getByAccount(yyyy, 8));
		} catch (Exception e) {
			log.error("[Dashboard] 관별 수지 조회 오류", e);
			failed.put("관별 수지", rootMessage(e));
		}

		try {
			data.put("business", erpDashboardService.getByBusiness(yyyy, 5));
		} catch (Exception e) {
			log.error("[Dashboard] 사업별 수지 조회 오류", e);
			failed.put("사업별 수지", rootMessage(e));
		}

		try {
			data.put("noSlipCard", erpDashboardService.getNoSlipCardCount(yyyy));
			data.put("noSlipCardList", erpDashboardService.getNoSlipCardList(yyyy, 6));
			data.put("unpaidCard", erpDashboardService.getUnpaidCardCount(yyyy));
		} catch (Exception e) {
			log.error("[Dashboard] 카드 미처리 조회 오류", e);
			failed.put("전표 없는 카드 사용", rootMessage(e));
		}

		try {
			data.put("payroll",
				erpDashboardService.getPayrollSummary(erpDashboardService.getLatestPayBatch()));
		} catch (Exception e) {
			log.error("[Dashboard] 급여 요약 조회 오류", e);
			failed.put("이번 달 급여", rootMessage(e));
		}

		data.put("failed", failed);
		r.data = data;
		r.success = true;
		r.message = failed.isEmpty()
									? "데이터 조회 성공"
									: "일부 항목을 불러오지 못했습니다 (" + failed.size() + "건)";
		return r;
	}

	private String rootMessage(Throwable e) {
		Throwable c = e;
		while (c.getCause() != null) c = c.getCause();
		return c.getMessage() != null ? c.getMessage() : e.toString();
	}
}