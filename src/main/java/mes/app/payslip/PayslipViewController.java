package mes.app.payslip;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.ModelAndView;

/**
 * 급여명세서 발송 화면.
 *
 * 데이터 API 는 PayslipController(@RestController)가 담당하고,
 * 이 클래스는 템플릿만 반환한다.
 *
 * ※ URL 은 프로젝트의 메뉴 테이블에 등록된 경로와 맞춰야 한다.
 *   다른 화면들이 어떤 규칙으로 매핑돼 있는지 확인 후 수정할 것.
 */
@Controller
public class PayslipViewController {

	@GetMapping("/payslip/payslip")
	public ModelAndView payslip() {
		return new ModelAndView("payroll/payslip");
	}
}