package mes.app.system;

import mes.app.system.service.MailSettingService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.util.Map;

/**
 * 시스템관리 > 메일 설정 — 사업장(테넌트)별 발송 계정 (SaaS 판 2026-11-13)
 *   GET  /api/system/mail_setting/read    내 사업장 SMTP 설정 (비밀번호 제외) + 메일 서비스 기본값 목록
 *   POST /api/system/mail_setting/save    저장 (password 가 비면 기존 비밀번호 유지)
 *   POST /api/system/mail_setting/test    저장 전 값으로 시험 발송 (to 생략 시 로그인 사용자 이메일)
 *   GET  /api/system/mail_setting/status  발송 계정 상태 (급여명세서 발송 팝업 「보내는 계정」 표시용, 누구나)
 *
 *  사업장 구분은 세션 db_key 로만 한다. 다른 사업장 설정은 조회 · 저장할 길이 없다.
 *  read/save/test 는 superuser 이거나 이 메뉴(mail_setting)에 쓰기 권한(W)이 있는 그룹만 허용한다.
 */
@RestController
@RequestMapping("/api/system/mail_setting")
public class MailSettingController {

	public static final String MENU_CODE = "mail_setting";

	@Autowired
	MailSettingService mailSettingService;

	@Autowired
	@Qualifier("mainSqlRunner")
	SqlRunner mainSqlRunner;

	@GetMapping("/read")
	public AjaxResult read(Authentication auth) {
		AjaxResult r = new AjaxResult();
		if (!canManage(auth)) { r.success = false; r.message = "메일 설정 권한이 없습니다."; return r; }
		try {
			r.data = mailSettingService.getForView();
		} catch (RuntimeException e) {
			r.success = false;
			r.message = e.getMessage();
		}
		return r;
	}

	@GetMapping("/status")
	public AjaxResult status() {
		AjaxResult r = new AjaxResult();
		r.data = mailSettingService.status();
		return r;
	}

	@PostMapping("/save")
	public AjaxResult save(@RequestBody Map<String, Object> body, Authentication auth) {
		AjaxResult r = new AjaxResult();
		if (!canManage(auth)) { r.success = false; r.message = "메일 설정 권한이 없습니다."; return r; }
		try {
			mailSettingService.save(body, ((User) auth.getPrincipal()).getId());
		} catch (RuntimeException e) {
			r.success = false;
			r.message = e.getMessage();
		}
		return r;
	}

	@PostMapping("/test")
	public AjaxResult test(@RequestBody Map<String, Object> body, Authentication auth) {
		AjaxResult r = new AjaxResult();
		if (!canManage(auth)) { r.success = false; r.message = "메일 설정 권한이 없습니다."; return r; }
		User user = (User) auth.getPrincipal();
		String to = body.get("to") == null ? null : body.get("to").toString().trim();
		if (!StringUtils.hasText(to)) to = user.getEmail();
		if (!StringUtils.hasText(to) || !to.contains("@")) {
			r.success = false;
			r.message = "시험 메일을 받을 주소를 입력하세요. (사용자 관리에 이메일이 없습니다)";
			return r;
		}
		try {
			JavaMailSenderImpl s = mailSettingService.senderForTest(body);
			MimeMessage m = s.createMimeMessage();
			MimeMessageHelper h = new MimeMessageHelper(m, false, "UTF-8");
			String from = body.get("from_addr") == null || body.get("from_addr").toString().isBlank()
											? String.valueOf(body.get("username")) : body.get("from_addr").toString().trim();
			String name = body.get("from_name") == null ? null : body.get("from_name").toString().trim();
			h.setFrom(new InternetAddress(from, StringUtils.hasText(name) ? name : null, "UTF-8"));
			h.setTo(to);
			h.setSubject("[ERP] 메일 설정 시험 발송");
			h.setText("메일 설정 시험 발송입니다.\n\n서버: " + s.getHost() + ":" + s.getPort() + "\n계정: " + s.getUsername()
									+ "\n\n이 메일이 도착했으면 설정이 맞습니다.", false);
			s.send(m);
			r.message = to + " 로 시험 메일을 보냈습니다. 메일함(스팸함 포함)을 확인하세요.";
		} catch (Exception e) {
			r.success = false;
			r.message = friendly(e);
		}
		return r;
	}

	/** superuser 또는 이 메뉴에 쓰기(W) 권한이 있는 사용자그룹 */
	private boolean canManage(Authentication auth) {
		if (auth == null || !(auth.getPrincipal() instanceof User u)) return false;
		if (Boolean.TRUE.equals(u.getSuperUser())) return true;
		try {
			Integer gid = u.getUserProfile().getUserGroup().getId();
			Map<String, Object> row = mainSqlRunner.getRow("""
					SELECT "AuthCode" FROM user_group_menu
					 WHERE "MenuCode" = :menu AND "UserGroup_id" = :gid
					""", new MapSqlParameterSource("menu", MENU_CODE).addValue("gid", gid));
			return row != null && String.valueOf(row.get("AuthCode")).contains("W");
		} catch (Exception e) {
			return false;
		}
	}

	/** 사용자에게 보여 줄 실패 사유 */
	public static String friendly(Throwable e) {
		String raw = MailSettingService.rootMessage(e);
		if (MailSettingService.isAuthFailure(e))
			return "메일 계정 인증 실패: 아이디·비밀번호(앱 비밀번호)를 확인하세요. 연속 " + MailSettingService.LOCK_AFTER
							 + "회 실패하면 " + MailSettingService.LOCK_MINUTES + "분 동안 발송을 멈춥니다. (" + raw + ")";
		String low = raw.toLowerCase();
		if (low.contains("unknownhost") || low.contains("couldn't connect") || low.contains("connect timed out") || low.contains("connection refused"))
			return "메일 서버에 연결하지 못했습니다: 서버 주소·포트·보안 방식을 확인하세요. (" + raw + ")";
		if (low.contains("invalid addresses") || low.contains("550") || low.contains("553"))
			return "받는 주소가 거부됐습니다: 메일 주소를 확인하세요. (" + raw + ")";
		if (low.contains("552") || low.contains("size"))
			return "첨부 파일이 메일 서버 한도를 넘었습니다. (" + raw + ")";
		return "메일 발송 실패: " + raw;
	}
}