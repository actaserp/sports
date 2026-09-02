package mes.app.payslip;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import mes.app.payslip.Service.PayslipService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.ModelAndView;

/**
 * 급여명세서 발송 화면.
 *
 * 데이터 API 는 PayslipController(@RestController)가 담당하고,
 * 이 클래스는 템플릿과 화면 초기값만 반환한다.
 *
 * ※ URL 은 프로젝트의 메뉴 테이블에 등록된 경로와 맞춰야 한다.
 */
@Slf4j
@Controller
public class PayslipViewController {

	@Autowired
	PayslipService payslipService;

	@GetMapping("/payslip/payslip")
	public ModelAndView payslip() {
		ModelAndView mav = new ModelAndView("payroll/payslip");

		// 발송 팝업의 회신 주소 기본값. TB_XA012.emailadres.
		// 값이 없어도 화면은 그대로 떠야 한다. 회신 주소는 필수 입력이 아니고,
		// 여기서 막으면 급여 발송 자체가 멈춘다.
		String replyTo = "";
		try {
			replyTo = payslipService.getSpjangEmail(TenantContext.get());
		} catch (Exception e) {
			log.warn("[Payslip] 사업장 메일 조회 실패 — 회신 주소를 빈 칸으로 둔다", e);
		}
		mav.addObject("defaultReplyTo", replyTo == null ? "" : replyTo.trim());

		// 발송 팝업의 메일 제목 기본 문안에 쓴다. 없으면 화면이 치환어로 대체한다.
		String spjangnm = "";
		try {
			spjangnm = payslipService.getSpjangName(TenantContext.get());
		} catch (Exception e) {
			log.warn("[Payslip] 사업장명 조회 실패 — 제목은 치환어로 남긴다", e);
		}
		mav.addObject("spjangnm", spjangnm == null ? "" : spjangnm.trim());

		return mav;
	}
}