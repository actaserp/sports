package mes.app.payslip.Service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeUtility;
import java.util.Map;

/**
 * 급여명세서 메일 발송.
 *
 * 핵심 원칙: 수신자 1명 = 메일 1통.
 * 여러 명을 한 통에 담는 경로는 이 클래스에 존재하지 않는다.
 *
 * 제목과 본문은 화면에서 받는다. 레거시 w_sendmail_pop 과 같은 방식이다.
 * 사람마다 달라지는 부분은 치환어로 처리한다.
 *
 * SMTP 접속 정보는 application.properties 와 MailConfig 를 그대로 쓴다.
 * 네이버는 발신 주소가 로그인 계정과 반드시 같아야 하므로 사업장별 발신 계정은 의미가 없고,
 * 답장 받을 주소만 화면에서 받아 Reply-To 로 넣는다. (balju_order 와 동일한 방식)
 */
@Slf4j
@Service
public class PayslipMailService {

	@Autowired
	private JavaMailSender mailSender;

	@Value("${spring.mail.username}")
	private String fromEmail;

	/** 발신자 표시명. 비우면 계정 주소가 그대로 보인다. */
	@Value("${payslip.sender-name:}")
	private String senderName;

	/**
	 * 단 한 명에게만 보낸다.
	 *
	 * @param to       수신자 1명. 배열/List 를 받지 않는 것이 이 메서드의 요점이다.
	 * @param pdfBytes 첨부할 PDF. 디스크를 거치지 않고 메모리에서 바로 붙인다.
	 * @param replyTo  답장 받을 주소. 비어 있으면 넣지 않는다.
	 */
	public void sendToOne(String to, String subject, String htmlBody,
												byte[] pdfBytes, String attachName, String replyTo) throws Exception {

		MimeMessage message = mailSender.createMimeMessage();
		MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

		helper.setTo(to);
		helper.setSubject(subject);
		helper.setText(htmlBody, true);

		// 네이버 SMTP 는 발신 주소가 로그인 계정과 반드시 일치해야 한다
		if (StringUtils.hasText(senderName)) {
			helper.setFrom(fromEmail, senderName);
		} else {
			helper.setFrom(fromEmail);
		}

		if (StringUtils.hasText(replyTo)) {
			helper.setReplyTo(replyTo);
		}

		if (pdfBytes != null && pdfBytes.length > 0) {
			// 한글 파일명이 깨지는 메일 클라이언트 대응
			helper.addAttachment(
				MimeUtility.encodeText(attachName, "UTF-8", "B"),
				new ByteArrayResource(pdfBytes),
				"application/pdf");
		}

		mailSender.send(message);
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