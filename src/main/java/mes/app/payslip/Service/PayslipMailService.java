package mes.app.payslip.Service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import mes.app.system.MailSettingController;
import mes.app.system.service.MailSettingService;

import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeUtility;
import java.util.Map;

/**
 * 급여명세서 메일 발송.
 *
 * 핵심 원칙: 수신자 1명 = 메일 1통.
 * 여러 명을 한 통에 담는 경로는 이 클래스에 존재하지 않는다. 참조(CC)도 넣지 않는다 — 급여는 본인만 봐야 한다.
 *
 * 제목과 본문은 화면에서 받는다. 레거시 w_sendmail_pop 과 같은 방식이다.
 * 사람마다 달라지는 부분은 치환어로 처리한다.
 *
 * 보내는 계정 (SaaS, 2026-11-13)
 *  - 사업장이 시스템관리 > 메일 설정에 등록한 계정이 있으면 그 계정으로 보낸다 (MailSettingService.account()).
 *    From = 사업장 계정, 보내는 이름 = 설정의 이름 (+ 설정에 따라 담당자 이름), Reply-To = 화면의 회신 주소.
 *    설정에 따라 회신 주소(담당자)를 숨은참조로 넣어 담당자 메일함에 사본을 남긴다.
 *  - 사업장 설정이 아예 없으면 지금까지처럼 시스템 계정(application.properties spring.mail.*)으로 보낸다.
 *  - 설정은 있는데 꺼짐 · 잠김이면 보내지 않고 사유를 돌려준다. 시스템 계정으로 몰래 돌리지 않는다.
 *  - 인증 실패가 연속되면 MailSettingService 가 그 사업장 계정을 잠시 멈춘다.
 */
@Slf4j
@Service
public class PayslipMailService {

	@Autowired
	private JavaMailSender mailSender;

	@Autowired
	private MailSettingService mailSettingService;

	@Value("${spring.mail.username}")
	private String fromEmail;

	/** 시스템 계정으로 보낼 때 발신자 표시명. 비우면 계정 주소가 그대로 보인다. */
	@Value("${payslip.sender-name:}")
	private String senderName;

	/** 사업장 설정 없이 시스템 계정으로 보낸 경우를 표시하는 값 (발송 이력 등에서 쓸 수 있다) */
	public static final String SYSTEM = "system";

	public void sendToOne(String to, String subject, String htmlBody,
												byte[] pdfBytes, String attachName, String replyTo) throws Exception {
		sendToOne(to, subject, htmlBody, pdfBytes, attachName, replyTo, null);
	}

	/**
	 * 단 한 명에게만 보낸다.
	 *
	 * @param to             수신자 1명. 배열/List 를 받지 않는 것이 이 메서드의 요점이다.
	 * @param pdfBytes       첨부할 PDF. 디스크를 거치지 않고 메모리에서 바로 붙인다.
	 * @param replyTo        답장 받을 주소. 비어 있으면 넣지 않는다.
	 * @param senderUserName 보낸 담당자 이름. 메일 설정에서 「담당자 이름 붙이기」를 켠 경우에만 쓴다.
	 * @return 실제로 쓴 보내는 주소
	 */
	public String sendToOne(String to, String subject, String htmlBody,
													byte[] pdfBytes, String attachName, String replyTo, String senderUserName) throws Exception {

		// 설정 없음이면 null, 꺼짐 · 잠김이면 여기서 사유와 함께 막힌다
		MailSettingService.Account acc = mailSettingService.account();
		JavaMailSender sender = acc != null ? acc.sender : mailSender;

		MimeMessage message = sender.createMimeMessage();
		MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

		helper.setTo(to);
		helper.setSubject(subject);
		helper.setText(htmlBody, true);

		String from;
		if (acc != null) {
			from = acc.fromAddr;
			String name = displayName(acc, senderUserName);
			helper.setFrom(new InternetAddress(from, StringUtils.hasText(name) ? name : null, "UTF-8"));
		} else {
			// 시스템 계정 — 네이버 SMTP 는 발신 주소가 로그인 계정과 반드시 일치해야 한다
			from = fromEmail;
			if (StringUtils.hasText(senderName)) helper.setFrom(fromEmail, senderName);
			else helper.setFrom(fromEmail);
		}

		if (StringUtils.hasText(replyTo)) {
			String r = replyTo.trim();
			helper.setReplyTo(r);
			// 담당자 사본: 사업장 설정에서 켠 경우만. 받는 사람 · 보내는 주소와 같으면 넣지 않는다
			if (acc != null && acc.bccSender && !r.equalsIgnoreCase(to) && !r.equalsIgnoreCase(acc.fromAddr))
				helper.setBcc(r);
		}

		if (pdfBytes != null && pdfBytes.length > 0) {
			// 한글 파일명이 깨지는 메일 클라이언트 대응
			helper.addAttachment(
				MimeUtility.encodeText(attachName, "UTF-8", "B"),
				new ByteArrayResource(pdfBytes),
				"application/pdf");
		}

		try {
			sender.send(message);
			if (acc != null) mailSettingService.markOk(acc.key);
		} catch (Exception e) {
			if (acc != null) mailSettingService.markFail(acc.key, e);
			// 컨트롤러는 가장 안쪽 원인 메시지를 화면에 보여 준다 → 사람이 읽을 사유만 담아 원인 없이 던지고, 원본은 로그로 남긴다
			log.error("[Payslip] 메일 발송 실패 (보내는 계정 {}): {}", acc != null ? acc.fromAddr : fromEmail, MailSettingService.rootMessage(e));
			throw new IllegalStateException(MailSettingController.friendly(e));
		}
		return acc != null ? from : SYSTEM;
	}

	private static String displayName(MailSettingService.Account acc, String userName) {
		String base = acc.fromName == null ? "" : acc.fromName.trim();
		String u = userName == null ? "" : userName.trim();
		if (acc.nameWithUser && !u.isEmpty()) return base.isEmpty() ? u : base + " " + u;
		return base;
	}

	/** 화면에서 받은 제목. 비어 있으면 기존 형식으로 되돌린다. */
	public String buildSubject(String template, Map<String, Object> head) {
		String t = StringUtils.hasText(template) ? template : "[{사업장}] {제목}";
		return fill(t, head);
	}

	/**
	 * 화면에서 받은 본문(평문)을 메일 HTML 로 감싼다.
	 *
	 * 담당자는 인사말과 안내 문구만 쓴다.
	 * 비밀번호 안내 · 연락처 안내 · 회사명은 실제 발송 조건에 맞춰 여기서 붙인다.
	 * 그래야 잠금 여부를 바꿔도 본문과 어긋나지 않는다.
	 *
	 * @param locked 첨부 PDF 에 비밀번호가 걸려 있는지
	 */
	public String buildBody(String template, Map<String, Object> head,
													String companyName, String replyTo, boolean locked) {

		String message = StringUtils.hasText(template)
											 ? template
											 : "안녕하세요, {성명}님.\n{귀속월} 급여명세서를 첨부해 드립니다.";

		String contact = StringUtils.hasText(replyTo)
											 ? "내용에 확인이 필요한 부분이 있으면 이 메일에 그대로 답장해 주세요."
											 : "내용에 확인이 필요한 부분이 있으면 회계부로 연락 주세요.";

		StringBuilder sb = new StringBuilder();
		sb.append("<div style=\"font-family:'맑은 고딕',sans-serif;font-size:14px;line-height:1.7;color:#222\">");

		// 담당자가 쓴 본문. 치환 후 이스케이프하고 줄바꿈만 <br/> 로 바꾼다.
		sb.append("<p>").append(nl2br(esc(fill(message, head)))).append("</p>");

		if (locked) {
			sb.append("<div style='background:#f4f6f3;padding:12px 14px;border-left:3px solid #4a6741;margin:14px 0'>")
				.append("첨부된 PDF는 비밀번호로 보호되어 있습니다.<br/>")
				.append("비밀번호는 <b>본인 생년월일 6자리</b>입니다. 예:(1977년 4월 6일 → 770406)")
				.append("</div>");
		}

		sb.append("<p>").append(contact).append("</p>");
		sb.append("<p style='color:#888;font-size:12px;margin-top:20px'>")
			.append(esc(companyName)).append("</p></div>");

		return sb.toString();
	}

	/**
	 * 치환어를 실제 값으로 바꾼다.
	 *
	 * 담당자가 모르는 치환어를 써도 그대로 남을 뿐 예외는 나지 않는다.
	 * 급여 발송이 문구 오타 하나로 멈추면 안 된다.
	 */
	private String fill(String s, Map<String, Object> head) {
		if (s == null) return "";
		return s
						 .replace("{성명}", str(head.get("pernm")))
						 .replace("{직위}", str(head.get("rspnm")))
						 .replace("{부서}", str(head.get("divinm")))
						 .replace("{사번}", str(head.get("peridview")))
						 .replace("{사업장}", str(head.get("spjangnm")))
						 .replace("{제목}", str(head.get("title")))
						 .replace("{귀속월}", ym(str(head.get("paybasic"))))
						 .replace("{지급일}", ymd(str(head.get("paydate"))));
	}

	/** 202608 → 2026년 08월 */
	private String ym(String s) {
		String d = s.replaceAll("[^0-9]", "");
		if (d.length() < 6) return s;
		return d.substring(0, 4) + "년 " + d.substring(4, 6) + "월";
	}

	/** 20260825 → 2026년 08월 25일 */
	private String ymd(String s) {
		String d = s.replaceAll("[^0-9]", "");
		if (d.length() < 8) return s;
		return d.substring(0, 4) + "년 " + d.substring(4, 6) + "월 " + d.substring(6, 8) + "일";
	}

	private String nl2br(String s) {
		return s.replace("\r\n", "\n").replace("\n", "<br/>");
	}

	private String str(Object o) {
		return o == null ? "" : String.valueOf(o).trim();
	}

	private String esc(String s) {
		if (s == null) return "";
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}