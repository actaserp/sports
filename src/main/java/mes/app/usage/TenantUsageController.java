package mes.app.usage;

import lombok.extern.slf4j.Slf4j;
import mes.app.usage.service.TenantUsageService;
import mes.app.common.TenantContext;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.security.core.Authentication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.function.Supplier;

/**
 * 사업장별 사용량.
 *
 * 전 사업장 데이터를 보는 화면이다. 진입은 메뉴 권한(user_group_menu)으로만 막으므로
 * 이 메뉴는 내부(ZZ) 그룹에만 부여한다.
 *
 * 기간은 'YYYY-MM' 두 개로 받고, 종료월은 그 달을 포함한다.
 * exAdmin = 'Y'(기본) 이면 admin · superuser 계정을, exDev = 'Y'(기본) 이면
 * 사업장별 개발자 계정(…_admin)을 집계에서 뺀다.
 */
@Slf4j
@RestController
@RequestMapping("/api/usage")
public class TenantUsageController {

	@Autowired
	TenantUsageService usageService;

	// ── 사업장 목록 ─────────────────────────────────────────
	@GetMapping("/sites")
	public AjaxResult sites(Authentication auth) {
		if (!internal(auth)) return denied();
		return run("사업장 목록", () -> usageService.getSites());
	}

	// ── 요약 : 사업장 × 월 ──────────────────────────────────
	@GetMapping("/summary")
	public AjaxResult summary(@RequestParam("from") String from,
														@RequestParam("to") String to,
														@RequestParam(value = "dbKey", required = false) String dbKey,
														@RequestParam(value = "exAdmin", defaultValue = "Y") String exAdmin,
														@RequestParam(value = "exDev", defaultValue = "Y") String exDev,
														Authentication auth) {
		if (!internal(auth)) return denied();
		return run("월별 사용량", () -> usageService.getMonthly(from, to, dbKey, !"N".equals(exAdmin), !"N".equals(exDev)));
	}

	// ── 상세 : 메뉴별 ───────────────────────────────────────
	@GetMapping("/menus")
	public AjaxResult menus(@RequestParam("from") String from,
													@RequestParam("to") String to,
													@RequestParam("dbKey") String dbKey,
													@RequestParam(value = "exAdmin", defaultValue = "Y") String exAdmin,
													@RequestParam(value = "exDev", defaultValue = "Y") String exDev,
													Authentication auth) {
		if (!internal(auth)) return denied();
		return run("메뉴별 사용량", () -> usageService.getMenus(from, to, dbKey, !"N".equals(exAdmin), !"N".equals(exDev)));
	}

	// ── 상세 : 계정별 ───────────────────────────────────────
	@GetMapping("/users")
	public AjaxResult users(@RequestParam("from") String from,
													@RequestParam("to") String to,
													@RequestParam("dbKey") String dbKey,
													@RequestParam(value = "exAdmin", defaultValue = "Y") String exAdmin,
													@RequestParam(value = "exDev", defaultValue = "Y") String exDev,
													Authentication auth) {
		if (!internal(auth)) return denied();
		return run("계정별 사용량", () -> usageService.getUsers(from, to, dbKey, !"N".equals(exAdmin), !"N".equals(exDev)));
	}

	// ── 도입 이력 : 등록일 · 첫 사용 · 첫 업무 사용 · 마지막 사용 (기간 무관) ──
	@GetMapping("/lifecycle")
	public AjaxResult lifecycle(@RequestParam(value = "exAdmin", defaultValue = "Y") String exAdmin,
															@RequestParam(value = "exDev", defaultValue = "Y") String exDev,
															Authentication auth) {
		if (!internal(auth)) return denied();
		return run("도입 이력", () -> usageService.getLifecycle(!"N".equals(exAdmin), !"N".equals(exDev)));
	}

	// ── 권한 ────────────────────────────────────────────────
	//  메뉴 권한은 화면만 가린다. 이 API 는 전 사업장 사용량을 돌려주므로 서버에서도 막는다.
	//  내부 사업장(ZZ) 계정이나 superuser 만 통과한다.

	private boolean internal(Authentication auth) {
		try {
			User u = (User) auth.getPrincipal();
			return Boolean.TRUE.equals(u.getSuperUser()) || "ZZ".equals(TenantContext.getDbKey());
		} catch (Exception e) {
			return false;
		}
	}

	private AjaxResult denied() {
		AjaxResult r = new AjaxResult();
		r.success = false;
		r.message = "권한이 없습니다.";
		return r;
	}

	// ── 내부 ────────────────────────────────────────────────

	private AjaxResult run(String what, Supplier<Object> work) {
		AjaxResult r = new AjaxResult();
		try {
			r.data = work.get();
			r.success = true;
			r.message = "데이터 조회 성공";
		} catch (IllegalArgumentException e) {
			// 조회 조건 오류 — 사용자에게 그대로 보여준다
			r.success = false;
			r.message = e.getMessage();
		} catch (Exception e) {
			log.error("[Usage] {} 조회 오류", what, e);
			r.success = false;
			r.message = "데이터 조회 중 오류 발생: " + rootMessage(e);
		}
		return r;
	}

	private String rootMessage(Throwable e) {
		Throwable c = e;
		while (c.getCause() != null) c = c.getCause();
		return c.getMessage() != null ? c.getMessage() : e.toString();
	}
}
