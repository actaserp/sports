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

	public String buildBody(Map<String, Object> head, String companyName, String replyTo) {
		String contact = StringUtils.hasText(replyTo)
											 ? "내용에 확인이 필요한 부분이 있으면 이 메일에 그대로 답장해 주세요."
											 : "내용에 확인이 필요한 부분이 있으면 회계부로 연락 주세요.";

		return "<div style=\"font-family:'맑은 고딕',sans-serif;font-size:14px;line-height:1.7;color:#222\">"
						 + "<p>안녕하세요, <b>" + esc(str(head.get("pernm"))) + "</b>님.</p>"
						 + "<p>" + esc(str(head.get("title"))) + "를 첨부해 드립니다.</p>"
						 + "<div style='background:#f4f6f3;padding:12px 14px;border-left:3px solid #4a6741;margin:14px 0'>"
						 + "첨부된 PDF는 비밀번호로 보호되어 있습니다.<br/>"
						 + "비밀번호는 <b>본인 생년월일 6자리</b>입니다. (1977년 4월 6일 → 770406)"
						 + "</div>"
						 + "<p>" + contact + "</p>"
						 + "<p style='color:#888;font-size:12px;margin-top:20px'>" + esc(companyName) + "</p></div>";
	}

	private String str(Object o) {
		return o == null ? "" : String.valueOf(o).trim();
	}

	private String esc(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}