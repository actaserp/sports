package mes.app.dashboard;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.ModelAndView;

import java.time.LocalDate;

/**
 * 대시보드 화면.
 *
 * 데이터는 ERPDashboardController(@RestController)가 담당하고
 * 여기서는 템플릿과 화면 초기값만 넘긴다.
 *
 * ※ URL 은 메뉴 테이블(menu_item)에 등록된 경로와 맞춰야 한다.
 *   등록 전이면 화면이 떠도 사용자그룹 메뉴 권한이 없어 403 이 난다.
 */
@Slf4j
@Controller
public class ERPDashboardViewController {

	@GetMapping("/dashboard/dashboard")
	public ModelAndView dashboard() {
		ModelAndView mav = new ModelAndView("dashboard/dashboard");

		LocalDate today = LocalDate.now();
		mav.addObject("year", String.valueOf(today.getYear()));
		mav.addObject("today", today.toString());

		return mav;
	}
}