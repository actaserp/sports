package mes.app.payslip;

import lombok.extern.slf4j.Slf4j;
import mes.app.payslip.Service.PayslipMailService;
import mes.app.payslip.Service.PayslipPdfService;
import mes.app.payslip.Service.PayslipService;
import mes.app.common.TenantContext;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.BufferedOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 급여명세서 조회 · 인쇄 · 발송.
 *
 * PDF 는 어디에도 저장하지 않는다. 요청이 올 때마다 메모리에서 만들어
 * 브라우저로 흘려보내거나 메일에 붙이고 그대로 버린다.
 * 남는 것은 TB_PAYSLIP_SEND 의 발송 이력뿐이다.
 */
@Slf4j
@RestController
@RequestMapping("/api/payslip")
public class PayslipController {

	@Autowired
	PayslipService payslipService;
	@Autowired
	PayslipPdfService pdfService;
	@Autowired
	PayslipMailService mailService;

	/** 건당 발송 간격(ms). 짧으면 스팸으로 간주되어 계정이 차단될 수 있다. */
	@Value("${payslip.send-interval-ms:1500}")
	private long sendInterval;

	// ── 조회조건 팝업 : 급여 회차 목록 ──────────────────────
	//  paydate 가 완전일치 조건이라 사용자가 입력할 수 없다.
	//  화면 진입 시 이 목록에서 한 행을 골라 paytype/paybasic/paydate 를 확정한다.
	@GetMapping("/batches")
	public AjaxResult batches() {
		AjaxResult r = new AjaxResult();
		try {
			r.data = payslipService.getPayBatches(TenantContext.get());
			r.success = true;
			r.message = "데이터 조회 성공";
		} catch (Exception e) {
			log.error("[Payslip] 급여 회차 조회 오류", e);
			r.success = false;
			r.message = "데이터 조회 중 오류 발생: " + rootMessage(e);
		}
		return r;
	}

	// ── 조회 : 좌측 대상자 그리드 ────────────────────────────
	//  cond 키 : paytype, paybasic, paydate, mpclafi, divicd, rspcd, rtclafi, perid
	//  (레거시 strCondition[1]~[9] 에 대응. custcd/spjangcd 는 세션에서 온다)
	@GetMapping("/list")
	public AjaxResult list(@RequestParam Map<String, String> cond) {
		AjaxResult r = new AjaxResult();
		String spjangcd = TenantContext.get();
		try {
			r.data = payslipService.getEmployeeList(spjangcd, cond);
			r.success = true;
			r.message = "데이터 조회 성공";
		} catch (Exception e) {
			log.error("[Payslip] 대상자 조회 오류", e);
			r.success = false;
			r.message = "데이터 조회 중 오류 발생: " + rootMessage(e);
		}
		return r;
	}

	// ── VIEW : 우측 명세서 미리보기 (HTML 조각) ──────────────
	//  doctype : 화면에서 고른 문서명. 빈 값이면 급여구분(paytype)을 따른다.
	@GetMapping(value = "/preview", produces = MediaType.TEXT_HTML_VALUE)
	public String preview(@RequestParam("paytype") String paytype,
												@RequestParam("paybasic") String paybasic,
												@RequestParam("paydate") String paydate,
												@RequestParam("perid") String perid,
												@RequestParam(value = "doctype", required = false) String doctype) {
		try {
			Map<String, Object> data =
				payslipService.getPayslip(TenantContext.get(), paytype, paybasic, paydate, perid, doctype);
			if (data == null) {
				return "<div class='ps-empty'>해당 귀속월의 급여 자료가 없습니다.</div>";
			}
			return pdfService.renderView(data, null);
		} catch (Exception e) {
			log.error("[Payslip] 미리보기 오류 perid={}", perid, e);
			return "<div class='ps-empty'>명세서를 불러오지 못했습니다.<br/>" + rootMessage(e) + "</div>";
		}
	}

	// ── 인쇄 : 단건 PDF 를 브라우저로 바로 스트리밍 ──────────
	@GetMapping("/print")
	public void print(@RequestParam("paytype") String paytype,
										@RequestParam("paybasic") String paybasic,
										@RequestParam("paydate") String paydate,
										@RequestParam("perid") String perid,
										@RequestParam(value = "lock", defaultValue = "N") String lock,
										@RequestParam(value = "doctype", required = false) String doctype,
										HttpServletResponse response) throws Exception {

		Map<String, Object> data =
			payslipService.getPayslip(TenantContext.get(), paytype, paybasic, paydate, perid, doctype);
		if (data == null) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND, "급여 자료가 없습니다.");
			return;
		}

		@SuppressWarnings("unchecked")
		Map<String, Object> head = (Map<String, Object>) data.get("head");

		// 담당자가 화면에서 바로 확인하는 용도라 기본은 비밀번호를 걸지 않는다
		String pw = "Y".equals(lock) ? pdfService.toPassword(head.get("birthday")) : null;
		byte[] pdf = pdfService.toPdfBytes(data, null, pw);

		response.setContentType("application/pdf");
		response.setHeader("Content-Disposition",
			"inline; filename*=UTF-8''" + enc(pdfService.buildFileName(head)));
		response.setContentLength(pdf.length);

		try (BufferedOutputStream out = new BufferedOutputStream(response.getOutputStream())) {
			out.write(pdf);
			out.flush();
		}
	}

	// ── 명세서저장 : 선택 인원 PDF 를 ZIP 으로 내려받기 ──────
	//  서버에 남기지 않고 담당자 PC 로 바로 보낸다.
	@GetMapping("/downloadZip")
	public void downloadZip(@RequestParam("paytype") String paytype,
													@RequestParam("paybasic") String paybasic,
													@RequestParam("paydate") String paydate,
													@RequestParam("perids") List<String> perids,
													@RequestParam(value = "lock", defaultValue = "Y") String lock,
													@RequestParam(value = "doctype", required = false) String doctype,
													HttpServletResponse response) throws Exception {

		boolean withPw = !"N".equals(lock);

		response.setContentType("application/zip");
		response.setHeader("Content-Disposition",
			"attachment; filename*=UTF-8''" + enc(PayslipService.docName(doctype, paytype) + "_" + paybasic + ".zip"));

		// 한글 파일명 보존을 위해 UTF-8 로 ZIP 을 만든다
		String spjangcd = TenantContext.get();

		try (ZipOutputStream zip = new ZipOutputStream(
			new BufferedOutputStream(response.getOutputStream()), StandardCharsets.UTF_8)) {

			for (String perid : perids) {
				try {
					Map<String, Object> data =
						payslipService.getPayslip(spjangcd, paytype, paybasic, paydate, perid, doctype);
					if (data == null) continue;

					@SuppressWarnings("unchecked")
					Map<String, Object> head = (Map<String, Object>) data.get("head");

					String pw = withPw ? pdfService.toPassword(head.get("birthday")) : null;
					byte[] pdf = pdfService.toPdfBytes(data, null, pw);

					zip.putNextEntry(new ZipEntry(pdfService.buildFileName(head)));
					zip.write(pdf);
					zip.closeEntry();

				} catch (Exception e) {
					log.error("[Payslip] ZIP 생성 중 오류 perid={}", perid, e);
					// 한 명 실패해도 나머지는 담아 내려준다
				}
			}
			zip.finish();
		}
	}

	// ── MAIL : 선택 인원에게 1명씩 개별 발송 ─────────────────
	//  발송 모드와 회신 주소는 DB 설정이 아니라 화면에서 매번 받는다.
	//  저장된 플래그로 두면 지난달 실발송 설정을 잊고 그대로 보내는 사고가 난다.
	@PostMapping("/sendMail")
	public AjaxResult sendMail(@RequestBody Map<String, Object> body,
														 Authentication auth) {

		AjaxResult r = new AjaxResult();

		String paytype   = str(body.get("paytype"));
		String paybasic  = str(body.get("paybasic"));
		String paydate   = str(body.get("paydate"));
		String replyTo   = str(body.get("replyTo"));
		String testEmail = str(body.get("testEmail"));
		String doctype    = str(body.get("doctype"));
		String subjectTpl = str(body.get("subject"));
		String bodyTpl    = String.valueOf(body.get("body") == null ? "" : body.get("body"));
		// 첨부 잠금은 기본이 해제다. 걸려면 화면에서 명시적으로 'Y' 를 보내야 한다.
		boolean lock = "Y".equals(str(body.get("lockYn")));
		// 기본은 항상 테스트. 화면에서 명시적으로 'N' 을 보내야 실발송한다.
		boolean testMode = !"N".equals(str(body.get("testYn")));

		@SuppressWarnings("unchecked")
		List<String> perids = (List<String>) body.get("perids");

		if (perids == null || perids.isEmpty()) {
			r.success = false;
			r.message = "발송 대상이 없습니다.";
			return r;
		}
		if (testMode && !testEmail.contains("@")) {
			r.success = false;
			r.message = "테스트 수신 주소를 입력하세요.";
			return r;
		}
		if (!replyTo.isEmpty() && !replyTo.contains("@")) {
			r.success = false;
			r.message = "회신 주소 형식이 올바르지 않습니다.";
			return r;
		}

		String userId   = username(auth);
		String spjangcd = TenantContext.get();

		List<Map<String, Object>> results = new ArrayList<>();
		int ok = 0, fail = 0;

		for (String perid : perids) {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("perid", perid);

			Map<String, Object> head = null;
			String sentTo = null;

			try {
				Map<String, Object> data =
					payslipService.getPayslip(spjangcd, paytype, paybasic, paydate, perid, doctype);
				if (data == null) throw new IllegalStateException("급여 자료 없음");

				@SuppressWarnings("unchecked")
				Map<String, Object> h = (Map<String, Object>) data.get("head");
				head = h;
				out.put("peridview", head.get("peridview"));
				out.put("pernm", head.get("pernm"));

				validate(head, lock);

				sentTo = testMode ? testEmail : String.valueOf(head.get("email")).trim();

				// PDF 는 메모리에서만 만들어 바로 첨부하고 버린다
				String pw = lock ? pdfService.toPassword(head.get("birthday")) : null;
				byte[] pdf = pdfService.toPdfBytes(data, str(head.get("spjangnm")), pw);
				String fileName = pdfService.buildFileName(head);

				// 제목·본문은 화면에서 받은 문안을 사람마다 치환해 쓴다.
				String subject = mailService.buildSubject(subjectTpl, head);

				// 수신자 1명 = 메일 1통. 이 반복이 전체 설계의 핵심이다.
				mailService.sendToOne(sentTo, subject,
					mailService.buildBody(bodyTpl, head, str(head.get("spjangnm")), replyTo, lock),
					pdf, fileName, replyTo);

				payslipService.saveSendLog(spjangcd, paytype, paybasic, perid, sentTo, "S", head, null, testMode, userId);
				out.put("success", true);
				out.put("email", sentTo);
				ok++;

			} catch (Exception e) {
				log.error("[Payslip] 발송 실패 perid={}", perid, e);
				payslipService.saveSendLog(spjangcd, paytype, paybasic, perid, sentTo, "F", head, rootMessage(e), testMode, userId);
				out.put("success", false);
				out.put("message", rootMessage(e));
				fail++;
			}

			results.add(out);
			sleep(sendInterval);
		}

		Map<String, Object> res = new LinkedHashMap<>();
		res.put("testMode", testMode);
		res.put("sentTo", testMode ? testEmail : "");
		res.put("success", ok);
		res.put("fail", fail);
		res.put("results", results);
		r.data = res;
		r.success = true;
		r.message = String.format("성공 %d건 / 실패 %d건%s", ok, fail,
			testMode ? " (테스트 발송 — " + testEmail + " 로만 전송)" : "");
		return r;
	}

	// ── 발송 이력 ───────────────────────────────────────────
	@GetMapping("/sendHistory")
	public AjaxResult sendHistory(@RequestParam("paytype") String paytype,
																@RequestParam("paybasic") String paybasic,
																@RequestParam("perid") String perid) {
		AjaxResult r = new AjaxResult();
		r.data = payslipService.getSendHistory(TenantContext.get(), paytype, paybasic, perid);
		return r;
	}

	// ── 내부 ────────────────────────────────────────────────

	/** 발송 직전 최종 검증. 여기서 걸러야 오발송이 막힌다. */
	private void validate(Map<String, Object> head, boolean lock) {
		String email = String.valueOf(head.get("email"));
		if (email == null || email.isBlank() || "null".equals(email) || !email.contains("@")) {
			throw new IllegalArgumentException("메일주소가 없거나 형식이 올바르지 않습니다");
		}
		// 생년월일은 비밀번호를 걸 때만 필수다. 잠그지 않으면 없어도 발송된다.
		if (lock && pdfService.toPassword(head.get("birthday")) == null) {
			throw new IllegalArgumentException("생년월일이 없어 PDF 비밀번호를 만들 수 없습니다");
		}
		if (parseLong(head.get("netpay"), 0L) < 0) {
			throw new IllegalArgumentException("실수령액이 음수입니다");
		}
	}

	private String str(Object o) {
		return o == null ? "" : String.valueOf(o).trim();
	}

	private String username(Authentication auth) {
		try {
			return ((User) auth.getPrincipal()).getUsername();
		} catch (Exception e) {
			return null;
		}
	}

	private String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private long parseLong(Object o, long def) {
		try {
			return Long.parseLong(String.valueOf(o).trim());
		} catch (Exception e) {
			return def;
		}
	}

	private String rootMessage(Throwable e) {
		Throwable c = e;
		while (c.getCause() != null) c = c.getCause();
		return c.getMessage() != null ? c.getMessage() : e.toString();
	}

	private void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}