package mes.domain.security;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import mes.config.RoutingDataSource;
import mes.config.TenantDataSourceManager;
import mes.domain.entity.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import mes.domain.model.AjaxResult;
import mes.domain.services.AccountService;

@Slf4j
@Component
public class CustomAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

	@Autowired
	AccountService accountService;

	@Autowired
	RoutingDataSource routingDataSource;

	@Autowired
	TenantDataSourceManager tenantDataSourceManager;

	@Autowired
	@org.springframework.beans.factory.annotation.Qualifier("mainSqlRunner")
	mes.domain.services.SqlRunner mainSqlRunner;

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) throws IOException, ServletException {

		User user = (User) authentication.getPrincipal();
		String dbKey = user.getDbKey(); // DB 라우팅 전용 키

		// 테넌트 DB 라우팅 키 설정
		TenantContext.setDbKey(dbKey);

		// 테넌트 DB에서 사업장 목록 조회 (단일 DB이므로 대부분 1개)
		List<Map<String, Object>> spjangList = loadSpjangList(dbKey);

		// 세션 저장
		HttpSession session = request.getSession(true);
		session.setAttribute("db_key", dbKey);
		session.setAttribute("spjangcd_login", dbKey); // 로그인 URL 복원용
		session.setAttribute("is_superuser", Boolean.TRUE.equals(user.getSuperUser()));
		session.setAttribute("userid", user.getUsername());
		session.setAttribute("username", user.getFirst_name());
		try {
			Integer groupId = user.getUserProfile() != null && user.getUserProfile().getUserGroup() != null
					? user.getUserProfile().getUserGroup().getId() : null;
			session.setAttribute("groupid", groupId);
		} catch (Exception e) {
			log.warn("groupid 세션 저장 실패: {}", e.getMessage());
			session.setAttribute("groupid", null);
		}

		if (spjangList.size() == 1) {
			String tenantSpjangcd = (String) spjangList.get(0).get("spjangcd");
			session.setAttribute("spjangcd", tenantSpjangcd);
			TenantContext.set(tenantSpjangcd);
		} else if (spjangList.size() > 1) {
			log.info("사업장 복수 선택 필요: dbKey={}, count={}", dbKey, spjangList.size());
		}

		// 로그인 로그
		this.accountService.saveLoginLog("login", authentication, request);

		// 응답
		AjaxResult result = new AjaxResult();
		result.success = true;
		result.message = "OK";
		result.data = spjangList.size() > 1 ? spjangList : "OK";

		response.setCharacterEncoding("UTF-8");
		response.setStatus(HttpServletResponse.SC_OK);
		response.getWriter().print(new ObjectMapper().writeValueAsString(result));
		response.getWriter().flush();

		TenantContext.clear();
	}

	/**
	 * Main DB의 tb_xa012에서 db_key로 사업장 목록 조회
	 * mainSqlRunner는 항상 Main DB에 직접 연결 (라우팅 없음)
	 */
	private List<Map<String, Object>> loadSpjangList(String dbKey) {
		try {
			org.springframework.jdbc.core.namedparam.MapSqlParameterSource param =
				new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
			param.addValue("dbKey", dbKey);
			return mainSqlRunner.getRows(
				"SELECT spjangcd, spjangnm FROM tb_xa012 WHERE db_key = :dbKey ORDER BY spjangcd",
				param
			);
		} catch (Exception e) {
			log.error("사업장 목록 조회 실패: dbKey={}", dbKey, e);
			return List.of();
		}
	}
}
