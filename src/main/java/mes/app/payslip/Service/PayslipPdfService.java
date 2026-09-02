package mes.app.payslip.Service;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 급여명세서 렌더링 및 PDF 변환.
 *
 * 템플릿이 두 개다. 하나로 합치지 말 것.
 *
 *   payroll/payslip_pdf   PDF · 인쇄 전용. openhtmltopdf 가 처리하므로
 *                         flex · CSS 변수를 못 쓰고, style 은 head 안에 있어야 한다.
 *   payroll/payslip_view  화면 미리보기 전용. 브라우저만 보므로 제약이 없다.
 *
 * 폰트는 SlipStatusController 가 쓰는 것과 같은 파일을 재사용한다.
 * resources/static/font/ 에 이미 들어 있어 별도로 넣을 것이 없다.
 * 파일은 디스크에 쓰지 않고 byte[] 로만 다룬다.
 */
@Slf4j
@Service
public class PayslipPdfService {

	private static final String TPL_PDF  = "payroll/payslip_pdf";
	private static final String TPL_VIEW = "payroll/payslip_view";

	/**
	 * 전표 인쇄(SlipStatusController)와 동일한 파일을 재사용한다.
	 *
	 * Bold 가 필요해 맑은 고딕을 쓴다. NotoSansKR 은 Bold 가 .otf(CFF) 뿐이라
	 * 이 렌더러가 로드하지 못하고, 굵은 한글만 # 으로 찍힌다.
	 * 맑은 고딕은 Regular · Bold 둘 다 TTF 이고 전표 인쇄에서 이미 검증됐다.
	 *
	 * ※ 맑은 고딕은 Windows 동봉 폰트다. 외부 배포판에 담아야 하는 상황이면
	 *   라이선스를 확인하고 아래 세 상수만 NotoSansKR 계열로 바꿀 것.
	 *   (그 경우 템플릿에서 font-weight:bold 를 모두 제거해야 한다)
	 */
	private static final String FONT_REGULAR = "static/font/malgun.ttf";
	private static final String FONT_BOLD    = "static/font/malgunbd.ttf";
	private static final String FONT_FAMILY  = "MalgunGothic";

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

		// openhtmltopdf 는 XML 파서로 읽는다. &nbsp; 는 XML 이 모르는 엔티티라 파싱이 깨진다.
		html = html.replace("&nbsp;", "&#160;");

		ByteArrayOutputStream raw = new ByteArrayOutputStream();
		PdfRendererBuilder builder = new PdfRendererBuilder();
		builder.useFastMode();

		builder.useFont(() -> font(FONT_REGULAR), FONT_FAMILY);
		builder.useFont(() -> font(FONT_BOLD),
			FONT_FAMILY, 700, BaseRendererBuilder.FontStyle.NORMAL, true);

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

	/**
	 * 폰트 스트림.
	 *
	 * getResourceAsStream 은 파일이 없으면 조용히 null 을 돌려주고,
	 * 그러면 렌더링 도중 알아보기 어려운 예외가 난다. 여기서 먼저 끊는다.
	 */
	private InputStream font(String path) {
		InputStream in = getClass().getClassLoader().getResourceAsStream(path);
		if (in == null) {
			throw new IllegalStateException(
				"resources/" + path + " 가 없습니다. 없으면 한글이 전부 깨집니다. "
					+ "전표 인쇄(SlipStatusController)와 같은 파일을 씁니다.");
		}
		return in;
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