package mes.app.payslip.Service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

/**
 * 급여명세서 렌더링 및 PDF 변환.
 *
 * 템플릿이 두 개다. 하나로 합치지 말 것.
 *
 *   payroll/payslip_pdf   PDF · 인쇄 전용.
 *                         openhtmltopdf 가 처리하므로 flex · CSS 변수 · 웹폰트를 못 쓴다.
 *                         레거시 DataWindow 서식을 그대로 재현한다(빈 16행 포함).
 *
 *   payroll/payslip_view  화면 미리보기 전용.
 *                         브라우저만 보므로 제약이 없다. 서식 재현이 아니라
 *                         발송 전 확인이 목적이라 실제 항목만 찍고,
 *                         메일주소 유효성 같은 화면에서만 필요한 정보를 덧붙인다.
 *
 * 파일은 디스크에 쓰지 않고 byte[] 로만 다룬다.
 */
@Slf4j
@Service
public class PayslipPdfService {

	private static final String TPL_PDF  = "payroll/payslip_pdf";
	private static final String TPL_VIEW = "payroll/payslip_view";

	@Autowired
	TemplateEngine templateEngine;

	@Autowired
	PayslipService payslipService;

	/** 화면 미리보기(VIEW) 전용. PDF 서식과 무관하게 자유롭게 바꿔도 된다. */
	public String renderView(Map<String, Object> data, String companyName) {
		return render(TPL_VIEW, data, companyName);
	}

	/** PDF · 인쇄 전용. 이쪽을 고치면 실제 발송물이 바뀐다. */
	public String renderPdfHtml(Map<String, Object> data, String companyName) {
		return render(TPL_PDF, data, companyName);
	}

	/**
	 * 두 템플릿이 같은 변수 집합을 쓴다.
	 * 어느 한쪽에만 필요한 값이 생기면 여기 말고 해당 render 메서드에서 따로 넣을 것.
	 */
	private String render(String template, Map<String, Object> data, String companyName) {
		@SuppressWarnings("unchecked")
		Map<String, Object> head = (Map<String, Object>) data.get("head");

		Context ctx = new Context();
		ctx.setVariable("head", head);
		ctx.setVariable("payDateText", payslipService.formatYmd(str(head.get("paydate"))));
		ctx.setVariable("birthText", formatBirth(str(head.get("birthday"))));
		ctx.setVariable("payItems", data.get("payItems"));
		ctx.setVariable("deductItems", data.get("deductItems"));
		ctx.setVariable("calcItems", data.get("calcItems"));
		ctx.setVariable("companyName", companyName);

		// 레거시 서식의 고정 16행. PDF 만 쓰지만 변수는 공통으로 넘긴다.
		int payCnt = size(data.get("payItems"));
		int dedCnt = size(data.get("deductItems"));
		ctx.setVariable("rowCount", Math.max(16, Math.max(payCnt, dedCnt)));

		return templateEngine.process(template, ctx);
	}

	/**
	 * PDF 바이트 생성.
	 *
	 * @param password 열람 비밀번호. null 이면 암호화하지 않는다.
	 */
	public byte[] toPdfBytes(Map<String, Object> data, String companyName, String password) throws Exception {

		String html = renderPdfHtml(data, companyName);

		ByteArrayOutputStream raw = new ByteArrayOutputStream();
		PdfRendererBuilder builder = new PdfRendererBuilder();
		builder.useFastMode();
		builder.useFont(() -> {
			try {
				return new ClassPathResource("fonts/NanumGothic.ttf").getInputStream();
			} catch (Exception e) {
				throw new IllegalStateException(
					"resources/fonts/NanumGothic.ttf 가 없습니다. 없으면 한글이 전부 깨집니다.", e);
			}
		}, "NanumGothic");
		builder.withHtmlContent(html, null);
		builder.toStream(raw);
		builder.run();

		byte[] bytes = raw.toByteArray();

		if (password != null && !password.isBlank()) {
			try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(bytes))) {
				AccessPermission ap = new AccessPermission();
				ap.setCanModify(false);
				ap.setCanExtractContent(false);

				// user = 직원 열람용 / owner = 관리자용. 반드시 다른 값이어야 한다
				StandardProtectionPolicy spp =
					new StandardProtectionPolicy(password + "#adm", password, ap);
				spp.setEncryptionKeyLength(128);
				doc.protect(spp);

				ByteArrayOutputStream enc = new ByteArrayOutputStream();
				doc.save(enc);
				bytes = enc.toByteArray();
			}
		}
		return bytes;
	}

	public String buildFileName(Map<String, Object> head) {
		return String.format("%s_%s_%s_%s.pdf",
			PayslipService.paytypeName(str(head.get("paytype"))),
			str(head.get("paybasic")),
			str(head.get("peridview")),
			str(head.get("pernm")));
	}

	/** 19770406 → 1977.04.06 */
	private String formatBirth(String s) {
		String d = s.replaceAll("[^0-9]", "");
		if (d.length() < 8) return s;
		return d.substring(0, 4) + "." + d.substring(4, 6) + "." + d.substring(6, 8);
	}

	/** 생년월일에서 비밀번호 6자리 추출 (1977.04.06 / 1977-04-06 / 19770406 모두 대응) */
	public String toPassword(Object birthday) {
		String s = str(birthday).replaceAll("[^0-9]", "");
		if (s.length() >= 8) return s.substring(2, 8);
		if (s.length() == 6) return s;
		return null;
	}

	private int size(Object o) {
		return (o instanceof List) ? ((List<?>) o).size() : 0;
	}

	private String str(Object o) {
		return o == null ? "" : String.valueOf(o).trim();
	}
}