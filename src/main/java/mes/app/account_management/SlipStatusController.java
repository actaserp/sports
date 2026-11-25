package mes.app.account_management;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.extern.slf4j.Slf4j;
import mes.app.account_management.service.SlipEntryService;
import mes.app.account_management.service.SlipStatusService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import javax.servlet.http.HttpServletResponse;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/account_management/slip_status")
public class SlipStatusController {  //전표입력현황

	@Autowired
	SlipStatusService slipStatusService;

	@Autowired
	SlipEntryService slipEntryService;

	/** 전표입력에서 거래처 바로 등록 (구분: 공통) */
	@PostMapping("/client/save")
	public AjaxResult saveClient(@RequestBody Map<String, Object> payload) {
		AjaxResult result = new AjaxResult();
		try {
			result.data = slipEntryService.registerClient(payload);
		} catch (IllegalArgumentException | IllegalStateException e) {
			result.success = false;
			result.message = e.getMessage();
		} catch (Exception e) {
			log.error("거래처 등록 오류", e);
			result.success = false;
			result.message = "거래처 등록 중 오류가 발생했습니다.";
		}
		return result;
	}

	@Autowired
	private TemplateEngine templateEngine;

//	@Autowired
//	private FontLoader fontLoader;

	@GetMapping("/envInfo")
	public AjaxResult getEnvInfo() {
		Map<String, Object> info = slipStatusService.getSettleInfo();
		AjaxResult result = new AjaxResult();
		result.data = info;
		return result;
	}

	@GetMapping("/read")
	public AjaxResult getSlipList(@RequestParam("start") String start,
																@RequestParam("end") String end,
																@RequestParam(value = "mssec", required = false) String mssec,
																@RequestParam(value = "tiosec", required = false) String tiosec,
																@RequestParam(value = "sbuject", required = false) String sbuject,
																@RequestParam(value = "remark", required = false) String remark,
																@RequestParam(value = "amtFrom", required = false) String amtFrom,
																@RequestParam(value = "amtTo", required = false) String amtTo,
																@RequestParam(value = "dateType", required = false) String dateType) {

		List<Map<String, Object>> items = this.slipStatusService.getSlipList(
			start, end, mssec, tiosec, sbuject, remark, amtFrom, amtTo, dateType);

		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;
	}

	/** 출력 양식 이름 (모달 선택 목록 · PDF 파일명) — 괄호 안은 원본 DataWindow */
	private static final Map<String, String> FORM_NAMES = Map.of(
		"1", "전표(산출내역)",            // d_aa009_bill2_og01
		"2", "결의양식1",                 // d_aa009_bill3
		"3", "결의양식2(하단)",           // d_aa009_bill4
		"4", "결의양식2(상단)",           // d_aa009_bill5
		"5", "결의양식3(세입세출)",       // d_aa009_bill6
		"6", "결의양식3(대체)",           // d_aa009_bill7
		"7", "전표(산출내역):하단결재",   // d_aa009_bill2_og02 계열
		"8", "지출결의서(한자)",          // d_aa009_bill9
		"9", "결의양식3(전체)"            // d_aa009_bill10
	);

	@GetMapping("/print")
	public void printSlip(
		@RequestParam("printType") String printType,
		@RequestParam("keys") String keys,
		@RequestParam(value = "approvers", required = false) String approvers,
		@RequestParam(value = "fontSize", required = false) Integer fontSize,
		HttpServletResponse response) throws Exception {

		try {
			String html = renderPrint(printType, keys, approvers, fontSize);
			response.setContentType("text/html; charset=UTF-8");
			response.getWriter().write(html);
			response.getWriter().flush();
		} catch (Exception e) {
			log.error("[SlipPrint] 오류: {}", e.getMessage(), e);
			response.sendError(500, "출력 오류");
		}
	}

	@GetMapping("/print/pdf")
	public void downloadSlipPdf(
		@RequestParam("printType") String printType,
		@RequestParam("keys") String keys,
		@RequestParam(value = "approvers", required = false) String approvers,
		@RequestParam(value = "fontSize", required = false) Integer fontSize,
		HttpServletResponse response) throws Exception {

		try {
			String html = renderPrint(printType, keys, approvers, fontSize);
			html = html.replace("&nbsp;", "&#160;");

			// 한글 폰트 적용 CSS 주입
			String fontOverride = "<style>body { font-family: 'Malgun Gothic', sans-serif; }</style>";
			html = html.replace("</head>", fontOverride + "</head>");

			String pdfName = FORM_NAMES.getOrDefault(printType, "전표");
			String filename = URLEncoder.encode(pdfName + ".pdf", StandardCharsets.UTF_8);

			response.setContentType("application/pdf");
			response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + filename);

			try (OutputStream os = response.getOutputStream()) {
				PdfRendererBuilder builder = new PdfRendererBuilder();
				builder.useFont(() -> getClass().getClassLoader().getResourceAsStream("static/font/Hangeuljaemin4-Regular.ttf"), "HangeuljaeMin4");
				builder.useFont(() -> getClass().getClassLoader().getResourceAsStream("static/font/NanumMyeongjo-Bold.ttf"), "NanumMyeongjo-Bold");
				builder.useFont(() -> getClass().getClassLoader().getResourceAsStream("static/font/malgun.ttf"), "Malgun Gothic");
				builder.useFont(() -> getClass().getClassLoader().getResourceAsStream("static/font/malgunbd.ttf"), "Malgun Gothic", 700, BaseRendererBuilder.FontStyle.NORMAL, true);
				builder.useFont(() -> getClass().getClassLoader().getResourceAsStream("static/font/NotoSansKR-Regular.ttf"), "NotoSansKR");
				builder.useFont(() -> getClass().getClassLoader().getResourceAsStream("static/font/NotoSansKR-Bold.otf"), "NotoSansKR", 700, BaseRendererBuilder.FontStyle.NORMAL, true);
				builder.withHtmlContent(html, null);
				builder.toStream(os);
				builder.run();
			}
		} catch (Exception e) {
			log.error("[SlipPdf] PDF 생성 오류: {}", e.getMessage(), e);
			response.sendError(500, "PDF 생성 오류");
		}
	}

	/** 미리보기 · 인쇄 · PDF 가 같은 HTML 을 쓰도록 한곳에서 만든다. */
	private String renderPrint(String printType, String keys, String approvers, Integer fontSize) {
		Context context = new Context();
		context.setVariable("slipList", buildSlips(printType, keys));
		context.setVariable("printType", printType);
		context.setVariable("fontSize", fontSize == null ? null : Math.max(10, Math.min(18, fontSize)));   // 출력 글자 크기(px), 없으면 양식 기본 12
		context.setVariable("approverList", resolveApproverList(approvers));
		// 지출결의서(한자, 8번)는 원본 결재란이 擔當·課長·事務局長·專務理事·會長 고정. 화면에서 직접 입력했을 때만 그 값으로 바꾼다.
		context.setVariable("approverInput", approvers != null && !approvers.trim().isEmpty());
		return templateEngine.process("accountCardManagement/slip_print", context);
	}

	// ══════════════════════════════════════════════════════════
	//  양식별 데이터 — 원본 DataWindow(d_aa009_bill*) 규칙
	// ══════════════════════════════════════════════════════════
	private List<Map<String, Object>> buildSlips(String printType, String keys) {
		List<Map<String, Object>> rows = slipStatusService.printSource(keys);

		// 전표별로 묶기 (SQL 이 전표 → rowseq 순으로 준다)
		Map<String, List<Map<String, Object>>> bySlip = new LinkedHashMap<>();
		for (Map<String, Object> r : rows) {
			bySlip.computeIfAbsent(str(r.get("spdate")) + str(r.get("spnum")), k -> new ArrayList<>()).add(r);
		}

		List<Map<String, Object>> result = new ArrayList<>();
		for (List<Map<String, Object>> all : bySlip.values()) {
			Map<String, Object> f = all.get(0);
			String tiosec = str(f.get("tiosec"));

			Map<String, Object> slip = new LinkedHashMap<>();
			slip.put("spdate",   str(f.get("spdate")));
			slip.put("spnum",    str(f.get("spnum")));
			slip.put("tiosec",   tiosec);
			slip.put("subject",  str(f.get("subject")));
			slip.put("remark",   str(f.get("remark")));
			slip.put("setnum",   str(f.get("setnum")));
			// bill5 No. : IF( isnull(setnum) or Len(setnum) = 0, spnum, setnum )
			slip.put("no",       str(f.get("setnum")).isEmpty() ? str(f.get("spnum")) : str(f.get("setnum")));
			slip.put("spoccu",   "AA".equals(str(f.get("spoccu"))) ? "전표일반" : str(f.get("spoccu")));
			slip.put("spjangnm", str(f.get("spjangnm")));
			String businm = str(f.get("businm")), cntname = str(f.get("cntname"));
			slip.put("businm",   businm);
			slip.put("busiText", businm + (cntname.isEmpty() ? "" : "(" + cntname + ")"));
			slip.put("yyyy",     left(str(f.get("spdate")), 4));
			slip.put("dSp",      ymd(str(f.get("spdate"))));
			slip.put("dReg",     ymd(str(f.get("regdate"))));     // 작성일자 없으면 "년 월 일" 빈칸
			// 원본 비고 칸 : remark + '~r~r~r' + ' - 이 하 여 백 - '
			slip.put("remarkText", str(f.get("remark")) + "\n\n\n - 이 하 여 백 - ");

			// 예산계정 줄 (계정 앞자리 7 · 5)
			List<Map<String, Object>> budget = new ArrayList<>();
			for (Map<String, Object> r : all) if (startsWithAny(str(r.get("acccd")), "7", "5")) budget.add(r);

			// ── 양식별 명세 줄 ──
			List<Map<String, Object>> src;
			switch (printType) {
				case "1": case "7":            // 전표 : 전체 줄, 구분 → 관 → 항 → 라인 순
					// 원본 출력 순서 : 차변 줄 먼저(비용 → 부채 …), 대변 줄 뒤. 같은 구분 안에서는 관 → 항 → 라인 순번
					src = new ArrayList<>(all);
					src.sort(Comparator.comparing((Map<String, Object> r) -> isDebit(r) ? 0 : 1)
										 .thenComparing(r -> str(r.get("upnm")), Comparator.reverseOrder())
										 .thenComparing(r -> str(r.get("acccd")))
										 .thenComparing(r -> str(r.get("it1cd")))
										 .thenComparing(r -> str(r.get("spseq"))));
					break;
				case "5":                      // bill6 : 7 · 5 · 3 계정 + 부가세대급금(1360)
					src = new ArrayList<>();
					for (Map<String, Object> r : all) {
						String a = str(r.get("acccd"));
						if (startsWithAny(a, "7", "5", "3") || "1360".equals(a)) src.add(r);
					}
					break;
				case "6":                      // bill7 : 7 · 5 가 아닌 줄
					src = new ArrayList<>();
					for (Map<String, Object> r : all) if (!startsWithAny(str(r.get("acccd")), "7", "5")) src.add(r);
					break;
				case "9":                      // bill10 : 전체 줄
					src = new ArrayList<>(all);
					break;
				default:
					src = new ArrayList<>();
			}

			List<Map<String, Object>> lines = new ArrayList<>();
			long tSum = 0, crSum = 0, drSum = 0;   // 「계」 · 일금 : if(tiosec = '1', cramt, dramt) 의 합 (bill6 · bill7 · bill10 모두 같은 식)
			for (Map<String, Object> r : src) {
				Map<String, Object> e = lineOf(r);
				long dr = num(r.get("dramt")), cr = num(r.get("cramt"));
				String drcr = str(r.get("drcr"));
				boolean debit = "1".equals(drcr) || "D".equals(drcr) || (drcr.isEmpty() && dr != 0);
				e.put("side", debit ? "D" : "C");
				// 명세 금액 칸 — bill6 : if(tiosec='1', cramt, dramt) / bill7 · bill10 : if(cramt <> 0, cramt, dramt)
				long amt = "5".equals(printType) ? ("1".equals(tiosec) ? cr : dr) : (cr != 0 ? cr : dr);
				e.put("amt", amt);
				tSum  += "1".equals(tiosec) ? cr : dr;
				crSum += cr;
				drSum += dr;
				lines.add(e);
			}
			slip.put("lines", lines);

			// 전표(산출내역) : 원본처럼 한 장에 12줄씩 나누고, 장마다 머리를 다시 찍는다.
			// 같은 구분 · 관 · 항이 이어지면 한 칸으로 합친다(장을 넘어서는 합치지 않는다).
			if ("1".equals(printType) || "7".equals(printType)) {
				List<Map<String, Object>> pages = new ArrayList<>();
				int per = 12;
				int total = Math.max(1, (lines.size() + per - 1) / per);
				for (int pg = 0; pg < total; pg++) {
					List<Map<String, Object>> pl = new ArrayList<>();
					for (int i = pg * per; i < Math.min(lines.size(), (pg + 1) * per); i++) pl.add(new LinkedHashMap<>(lines.get(i)));
					setSpans(pl);
					Map<String, Object> page = new LinkedHashMap<>();
					page.put("lines", pl);
					page.put("no", pg + 1);
					page.put("last", pg == total - 1);
					pages.add(page);
				}
				slip.put("pages", pages);
				slip.put("pageCount", total);
				long tdr = 0, tcr = 0;
				for (Map<String, Object> e : lines) {
					if ("D".equals(e.get("side"))) tdr += (Long) e.get("dramt"); else tcr += (Long) e.get("cramt");
				}
				slip.put("sumDr", tdr);
				slip.put("sumCr", tcr);
			}

			// ── 머리(관 · 항 · 목 · 재원) ──
			Map<String, Object> head;
			if ("5".equals(printType) || "6".equals(printType) || "9".equals(printType)) {
				head = src.isEmpty() ? f : src.get(0);           // 줄 단위 SQL : 첫 줄 값이 머리에 찍힌다
			} else if (!budget.isEmpty()) {
				head = maxBy(budget);                            // 7 · 5 묶음의 max(acccd)
			} else {
				head = maxBy(all);
			}
			Map<String, Object> headLine = lineOf(head);
			if ("2".equals(printType) || "3".equals(printType) || "4".equals(printType) || "8".equals(printType)) {
				// 원본 SQL 은 전표 단위로 GROUP BY 하고 칸마다 따로 max() 를 잡는다.
				//   관 = max(accnm)  (bill5 는 max(acccd) 의 계정명)
				//   항 = max(it1cd) 의 항명,  목 = max(it2cd) 의 목명
				List<Map<String, Object>> grp = budget.isEmpty() ? all : budget;
				Map<String, Object> mIt1 = grp.get(0), mIt2 = grp.get(0);
				String mAcc = "";
				for (Map<String, Object> r : grp) {
					if (str(r.get("it1cd")).compareTo(str(mIt1.get("it1cd"))) > 0) mIt1 = r;
					if (str(r.get("it2cd")).compareTo(str(mIt2.get("it2cd"))) > 0) mIt2 = r;
					if (str(r.get("accnm")).compareTo(mAcc) > 0) mAcc = str(r.get("accnm"));
				}
				if (!"4".equals(printType)) headLine.put("accnm", mAcc);
				headLine.put("it1nm", str(mIt1.get("it1nm")));
				headLine.put("it2nm", str(mIt2.get("it2nm")));
			}
			slip.put("head", headLine);
			slip.put("mssec", str(head.get("mssecnm")).isEmpty() ? str(head.get("mssec")) : str(head.get("mssecnm")));
			slip.put("headAcccd", str(head.get("acccd")));
			// 결의양식1 차변 · 대변 칸 : 원본은 바깥 GROUP BY 에서 max(drcr) — 전표 안에 대변 줄이 하나라도 있으면 '2'(대변)
			String maxDrcr = "";
			for (Map<String, Object> r : all) if (str(r.get("drcr")).compareTo(maxDrcr) > 0) maxDrcr = str(r.get("drcr"));
			slip.put("headDrcr",  maxDrcr);

			// 결의양식1(bill3) 카드 · 계좌 · 거래처 : 전표 안 max 값
			slip.put("cardText", firstNonEmpty(all, "cardnm", "cardnum"));
			slip.put("bankText", firstNonEmpty(all, "banknm", "accnum"));
			slip.put("cltnm",    firstNonEmpty(all, "cltnm", null));

			// ── 금액 ──
			long bDr = 0, bCr = 0, allDr = 0, allCr = 0;
			for (Map<String, Object> r : budget) { bDr += num(r.get("dramt")); bCr += num(r.get("cramt")); }
			for (Map<String, Object> r : all)    { allDr += num(r.get("dramt")); allCr += num(r.get("cramt")); }
			long amt;
			switch (printType) {
				case "1": case "7": amt = allDr; break;                                   // 전표 합계 = 차변 합계
				case "4":           amt = budget.isEmpty() ? allDr : (bCr != 0 ? bCr : bDr); break;  // bill5 : if(cramt <> 0, cramt, dramt)
				case "5": case "6": case "9":
					amt = tSum != 0 ? tSum : (crSum != 0 ? crSum : drSum); break;                              // 명세 「계」 (대변만 있는 대체는 대변 합)
				default:            amt = budget.isEmpty() ? allDr : ("1".equals(tiosec) ? bCr : bDr);  // bill3 · bill4 · bill9
			}
			slip.put("amt", amt);
			slip.put("amtKorean", toKoreanAmount(amt));
			slip.put("amtHanja", toHanjaAmount(amt));                       // 세로(한 글자씩 줄바꿈) — 결의양식1
			slip.put("amtHanjaFlat", toHanjaAmount(amt).replace("\n", "")); // 가로 — 지출결의서(한자)

			// ── 「○○년도 ○○부」 줄 ──
			String y = left(str(f.get("spdate")), 4);
			String book;
			switch (printType) {
				case "3":  // bill4 : if(left(acccd,1) = '5', 세입부, 세출부)
					book = y + " 년도  " + (str(head.get("acccd")).startsWith("5") ? "세  입  부" : "세  출  부"); break;
				case "4":  // bill5 : SQL title (1 세입 / 2 세출 / 그 밖 대체)
					book = y + " 년도 " + ("1".equals(tiosec) ? "세 입 부" : "2".equals(tiosec) ? "세 출 부" : "대     체"); break;
				default:
					book = y + " 년도  " + ("1".equals(tiosec) ? "세  입  부" : "세  출  부");
			}
			slip.put("book", book);

			result.add(slip);
		}
		return result;
	}

	private Map<String, Object> lineOf(Map<String, Object> r) {
		Map<String, Object> e = new LinkedHashMap<>();
		e.put("acccd", str(r.get("acccd")));
		e.put("accnm", str(r.get("accnm")));
		e.put("it1nm", str(r.get("it1nm")));
		e.put("it2nm", str(r.get("it2nm")));
		e.put("upnm",  str(r.get("upnm")));
		e.put("mssec", str(r.get("mssecnm")).isEmpty() ? str(r.get("mssec")) : str(r.get("mssecnm")));
		e.put("summy", str(r.get("summy")));
		e.put("dramt", num(r.get("dramt")));
		e.put("cramt", num(r.get("cramt")));
		e.put("cltnm", str(r.get("cltnm")));
		String cardnm = str(r.get("cardnm")), cardnum = str(r.get("cardnum"));
		String banknm = str(r.get("banknm")), accnum = str(r.get("accnum"));
		// 전표(산출내역) 항 아래 줄 : 그 줄에 카드가 있으면 카드, 없으면 계좌
		e.put("sub", !cardnm.isEmpty() ? cardnm + " : " + cardnum : (!banknm.isEmpty() ? banknm + " : " + accnum : ""));
		// 결제방법 : if(len(cardnum) > 0, gf_cardflag(cardnum), if(len(accnum) > 0, '계좌이체', ''))
		// TODO gf_cardflag 스크립트를 받으면 같은 규칙으로 바꾼다. 지금은 카드명을 찍는다.
		e.put("pay", !cardnum.isEmpty() ? (cardnm.isEmpty() ? "카드" : cardnm) : (!accnum.isEmpty() ? "계좌이체" : ""));
		return e;
	}

	private static boolean isDebit(Map<String, Object> r) {
		String drcr = str(r.get("drcr"));
		return "1".equals(drcr) || "D".equals(drcr) || (drcr.isEmpty() && num(r.get("dramt")) != 0);
	}

	/** 이어지는 같은 값을 한 칸으로 : spUp(구분) · spAcc(관) · spIt1(항) = 합칠 줄 수, 0 이면 위 칸에 합쳐져 안 찍는다 */
	private static void setSpans(List<Map<String, Object>> pl) {
		String[][] keys = { {"upnm"}, {"upnm", "accnm"}, {"upnm", "accnm", "it1nm", "sub"} };
		String[] names = { "spUp", "spAcc", "spIt1" };
		for (int k = 0; k < keys.length; k++) {
			int i = 0;
			while (i < pl.size()) {
				int j = i + 1;
				while (j < pl.size() && sameKey(pl.get(i), pl.get(j), keys[k])) j++;
				pl.get(i).put(names[k], j - i);
				for (int x = i + 1; x < j; x++) pl.get(x).put(names[k], 0);
				i = j;
			}
		}
	}

	private static boolean sameKey(Map<String, Object> a, Map<String, Object> b, String[] ks) {
		for (String k : ks) if (!str(a.get(k)).equals(str(b.get(k)))) return false;
		return true;
	}

	private static Map<String, Object> maxBy(List<Map<String, Object>> list) {
		Map<String, Object> best = list.get(0);
		for (Map<String, Object> r : list) if (str(r.get("acccd")).compareTo(str(best.get("acccd"))) > 0) best = r;
		return best;
	}

	private static String firstNonEmpty(List<Map<String, Object>> list, String nameKey, String numKey) {
		for (Map<String, Object> r : list) {
			String nm = str(r.get(nameKey));
			if (!nm.isEmpty()) return numKey == null || str(r.get(numKey)).isEmpty() ? nm : nm + " : " + str(r.get(numKey));
		}
		return "";
	}

	private static boolean startsWithAny(String s, String... p) {
		for (String x : p) if (s.startsWith(x)) return true;
		return false;
	}

	private static String str(Object o) { return o == null ? "" : o.toString().trim(); }

	private static long num(Object o) {
		if (o == null) return 0L;
		if (o instanceof Number) return ((Number) o).longValue();
		try { return new java.math.BigDecimal(o.toString().trim()).longValue(); } catch (Exception e) { return 0L; }
	}

	private static String left(String s, int n) { return s.length() >= n ? s.substring(0, n) : s; }

	/** "20260303" → "2026 년 03 월 03 일" / 없으면 "　　　 년　　 월　　 일" */
	private static String ymd(String d) {
		if (d.length() < 8) return "　　　 년　　 월　　 일";
		return d.substring(0, 4) + " 년 " + d.substring(4, 6) + " 월 " + d.substring(6, 8) + " 일";
	}


	private List<Map<String, Object>> groupBySlip(List<Map<String, Object>> rows) {
		Map<String, Map<String, Object>> groupMap = new LinkedHashMap<>();

		for (Map<String, Object> row : rows) {
			String key = row.get("spdate") + "" + row.get("spnum");

			// ✅ drcr 값 확인
			Object drcrRaw = row.get("drcr");
			Object dramtRaw = row.get("dramt");
			Object cramtRaw = row.get("cramt");

			/*log.info("[SlipPrint] key={} | drcr=[{}] type={} | dramt={} | cramt={}",
				key,
				drcrRaw,
				drcrRaw != null ? drcrRaw.getClass().getSimpleName() : "null",
				dramtRaw,
				cramtRaw);*/

			// spoccu 변환
			String spoccuRaw = row.get("spoccu") != null ? row.get("spoccu").toString() : "";
			String spoccuNm = "AA".equals(spoccuRaw) ? "전표일반" : spoccuRaw;

			groupMap.computeIfAbsent(key, k -> {
				Map<String, Object> slip = new LinkedHashMap<>();
				slip.put("spdate", row.get("spdate"));
				slip.put("regdate", row.get("regdate"));
				slip.put("spnum", row.get("spnum"));
				slip.put("tiosec", row.get("tiosec"));
				slip.put("subject", row.get("subject"));
				slip.put("remark", row.get("remark"));
				slip.put("spoccu", spoccuNm);
				slip.put("setnum", row.get("setnum"));
				slip.put("mssec", row.get("mssecnm") != null ? row.get("mssecnm") : row.get("mssec"));
				slip.put("businm", row.get("businm"));
				slip.put("spjangnm", row.get("spjangnm"));
				slip.put("banknm", row.get("banknm"));
				slip.put("cardnm", row.get("cardnm"));
				slip.put("debit", new ArrayList<Map<String, Object>>());
				slip.put("credit", new ArrayList<Map<String, Object>>());
				slip.put("lines", new ArrayList<Map<String, Object>>());   // 차변 · 대변을 SQL 순서 그대로 (전표 출력용)
				slip.put("amt", 0L);
				slip.put("accnum", row.get("accnum"));   // 계좌번호
				slip.put("cardnum", row.get("cardnum"));  // 카드번호
				slip.put("cltnm", row.get("cltnm"));  // 카드번호
				return slip;
			});

			Map<String, Object> group = groupMap.get(key);

			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("accnm", row.get("accnm"));
			entry.put("it1nm", row.get("it1nm"));
			entry.put("it2nm", row.get("it2nm"));
			entry.put("upnm", row.get("upnm"));
			entry.put("mssec", row.get("mssecnm") != null ? row.get("mssecnm") : row.get("mssec"));
			entry.put("summy", row.get("summy"));
			entry.put("dramt", row.get("dramt"));
			entry.put("cramt", row.get("cramt"));
			entry.put("cltnm", row.get("cltnm"));

			// 차변/대변 분리
			String drcr = drcrRaw != null ? drcrRaw.toString().trim() : "";
			Long dramt = dramtRaw != null ? ((Number) dramtRaw).longValue() : 0L;
			Long cramt = cramtRaw != null ? ((Number) cramtRaw).longValue() : 0L;

			if ("1".equals(drcr) || "D".equals(drcr)) {
//				log.info("[SlipPrint]   → 차변 추가 | accnm={} | dramt={}", row.get("accnm"), dramt);
				((List<Map<String, Object>>) group.get("debit")).add(entry); entry.put("side", "D");
				group.put("amt", ((Long) group.get("amt")) + dramt);
			} else if ("2".equals(drcr) || "C".equals(drcr)) {
//				log.info("[SlipPrint]   → 대변 추가 | accnm={} | cramt={}", row.get("accnm"), cramt);
				((List<Map<String, Object>>) group.get("credit")).add(entry); entry.put("side", "C");
			} else {
				// drcr 값이 없을 경우 dramt로 판단 (fallback)
				if (dramt > 0) {
					((List<Map<String, Object>>) group.get("debit")).add(entry); entry.put("side", "D");
					group.put("amt", ((Long) group.get("amt")) + dramt);
				} else {
					((List<Map<String, Object>>) group.get("credit")).add(entry); entry.put("side", "C");
				}
			}
			((List<Map<String, Object>>) group.get("lines")).add(entry);
		}

		return new ArrayList<>(groupMap.values());
	}

	// 별도 메서드로 분리
	private void fillDebitFromCredit(List<Map<String, Object>> slipList) {
		for (Map<String, Object> slip : slipList) {
			List<Map<String, Object>> debitList  = (List<Map<String, Object>>) slip.get("debit");
			List<Map<String, Object>> creditList = (List<Map<String, Object>>) slip.get("credit");

			if (debitList.isEmpty() && !creditList.isEmpty()) {
				debitList.addAll(creditList);

				String tiosec = slip.get("tiosec") != null ? slip.get("tiosec").toString() : "2";
				long total = creditList.stream()
											 .mapToLong(e -> {
												 String amtKey = "1".equals(tiosec) ? "cramt" : "dramt";
												 return e.get(amtKey) != null ? ((Number) e.get(amtKey)).longValue() : 0L;
											 })
											 .sum();

				if (total == 0L) {
					total = creditList.stream()
										.mapToLong(e -> e.get("cramt") != null ? ((Number) e.get("cramt")).longValue() : 0L)
										.sum();
				}

				slip.put("amt", total);
			}
		}
	}

	private List<Map<String, Object>> groupByGyeolui(List<Map<String, Object>> rows) {
		List<Map<String, Object>> result = new ArrayList<>();

		for (Map<String, Object> row : rows) {
			Map<String, Object> slip = new LinkedHashMap<>();

			String drcr = row.get("drcr") != null ? row.get("drcr").toString().trim() : "";

			slip.put("spdate",   row.get("spdate"));
			slip.put("spnum",    row.get("spnum"));
			slip.put("regdate",  row.get("regdate"));   // 작성일자 (결의서 발의 · 작성 칸)
			slip.put("tiosec",   row.get("tiosec"));
			slip.put("subject",  row.get("subject"));
			slip.put("remark",   row.get("remark"));
			slip.put("mssec",    row.get("mssecnm") != null ? row.get("mssecnm") : row.get("mssec"));
			slip.put("businm",   row.get("businm"));
			slip.put("spjangnm", row.get("spjangnm"));
			slip.put("banknm",   row.get("banknm"));
			slip.put("cardnm",   row.get("cardnm"));
			slip.put("accnum",   row.get("accnum"));
			slip.put("cardnum",  row.get("cardnum"));
			slip.put("cltnm",    row.get("cltnm"));
			slip.put("setnum",   row.get("setnum"));
			slip.put("rowseq", row.get("rowseq"));

			Long dramt = row.get("dramt") != null ? ((Number) row.get("dramt")).longValue() : 0L;
			Long cramt = row.get("cramt") != null ? ((Number) row.get("cramt")).longValue() : 0L;
			slip.put("amt", dramt > 0 ? dramt : cramt);
			slip.put("drcr", drcr);

			Map<String, Object> debitEntry  = new LinkedHashMap<>();
			Map<String, Object> creditEntry = new LinkedHashMap<>();

			if ("1".equals(drcr) || "D".equals(drcr)) {
				// 차변: accnm 사용
				debitEntry.put("accnm", row.get("accnm"));
				debitEntry.put("it1nm", row.get("it1nm"));
				debitEntry.put("it2nm", row.get("it2nm"));
				debitEntry.put("upnm",  row.get("upnm"));
				debitEntry.put("mssec", row.get("mssecnm") != null ? row.get("mssecnm") : row.get("mssec"));
				debitEntry.put("summy", row.get("summy"));
				debitEntry.put("dramt", dramt);
				debitEntry.put("cramt", cramt);
				debitEntry.put("cltnm", row.get("cltnm"));

				// 대변: 빈칸
				creditEntry.put("accnm", "");
				creditEntry.put("it1nm", "");
				creditEntry.put("it2nm", "");
				creditEntry.put("upnm",  "");
				creditEntry.put("mssec", "");
				creditEntry.put("summy", "");
				creditEntry.put("dramt", 0L);
				creditEntry.put("cramt", cramt);
				creditEntry.put("cltnm", row.get("cltnm"));   // 글자 "cltnm" 이 그대로 들어가던 것 수정

			} else {
				// drcr=2 : 대변에 accnm, 차변은 빈칸
				debitEntry.put("accnm", row.get("accnm"));   // ← 차변 빈칸
				debitEntry.put("it1nm", row.get("it1nm"));
				debitEntry.put("it2nm", row.get("it2nm"));
				debitEntry.put("upnm",  row.get("upnm"));
				debitEntry.put("mssec", row.get("mssecnm") != null ? row.get("mssecnm") : row.get("mssec"));
				debitEntry.put("summy", row.get("summy"));
				debitEntry.put("dramt", dramt);
				debitEntry.put("cramt", cramt);
				debitEntry.put("cltnm", row.get("cltnm"));

				// 대변: accnm 사용
				creditEntry.put("accnm", row.get("it2nm"));  // ← 국내사업비
				debitEntry.put("it1nm", row.get("it1nm"));
				creditEntry.put("it2nm", row.get("it2nm_t"));
				creditEntry.put("upnm",  "부채");
				creditEntry.put("mssec", row.get("mssecnm") != null ? row.get("mssecnm") : row.get("mssec"));
				creditEntry.put("summy", "");
				creditEntry.put("dramt", 0L);
				creditEntry.put("cramt", cramt);
				creditEntry.put("cltnm", row.get("cltnm"));
			}

			slip.put("debit",  new ArrayList<>(List.of(debitEntry)));
			slip.put("credit", new ArrayList<>(List.of(creditEntry)));

			result.add(slip);
		}

		return result;
	}

	private String toKoreanAmount(long amount) {
		if (amount == 0) return "영";

		String[] digits = {"", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구"};
		String[] units1 = {"", "십", "백", "천"};
		String[] units2 = {"", "만", "억", "조", "경"};

		boolean negative = amount < 0;
		amount = Math.abs(amount);

		long[] parts = new long[5];
		for (int i = 0; i < 5; i++) {
			parts[i] = amount % 10_000;
			amount /= 10_000;
		}

		StringBuilder sb = new StringBuilder();
		for (int i = 4; i >= 0; i--) {
			if (parts[i] == 0) continue;
			StringBuilder chunk = new StringBuilder();
			long p = parts[i];
			for (int j = 3; j >= 0; j--) {
				long d = (p / (long) Math.pow(10, j)) % 10;
				if (d == 0) continue;
				// 일십·일백·일천 → 십·백·천 (일만은 "일만" 유지)
				if (d == 1 && j > 0) {
					chunk.append(units1[j]);
				} else {
					chunk.append(digits[(int) d]).append(units1[j]);
				}
			}
			sb.append(chunk).append(units2[i]);
		}

		return (negative ? "마이너스" : "") + sb;
	}

	// ══════════════════════════════════════════════════════════
//  한자 금액 변환  예) 43400 → "四萬參阡四百"
//  위조방지용 갖은자: 壹貳參四伍六七八九拾佰阡萬億
// ══════════════════════════════════════════════════════════
	private String toHanjaAmount(long amount) {
		if (amount == 0) return "零";

		String[] digits = {"", "壹", "貳", "參", "四", "伍", "六", "七", "八", "九"};
		String[] units1 = {"", "拾", "佰", "阡"};
		String[] units2 = {"", "萬", "億", "兆", "京"};

		boolean negative = amount < 0;
		amount = Math.abs(amount);

		long[] parts = new long[5];
		for (int i = 0; i < 5; i++) {
			parts[i] = amount % 10_000;
			amount /= 10_000;
		}

		StringBuilder sb = new StringBuilder();
		for (int i = 4; i >= 0; i--) {
			if (parts[i] == 0) continue;
			StringBuilder chunk = new StringBuilder();
			long p = parts[i];
			for (int j = 3; j >= 0; j--) {
				long d = (p / (long) Math.pow(10, j)) % 10;
				if (d == 0) continue;
				if (d == 1 && j > 0) {
					chunk.append(units1[j]);
				} else {
					chunk.append(digits[(int) d]).append(units1[j]);
				}
			}
			sb.append(chunk).append(units2[i]);
		}

		String result = (negative ? "△" : "") + sb;

		// 한 글자씩 \n으로 연결해서 세로 표시
		return result.chars()
						 .mapToObj(c -> String.valueOf((char) c))
						 .collect(Collectors.joining("\n"));
	}

	/**
	 * 결재란 칸 목록. 칸마다 {name 직위, jun 전결 표시, cro 사선 표시}.
	 *  1순위: 출력 팝업에서 직접 입력한 값(approvers, 쉼표 구분) — 입력한 개수만큼 칸이 생긴다. "직위:J" 전결, "직위:C" 사선
	 *  2순위: 환경설정 결재라인 tb_xenv (원본 d_confirm_001 과 같은 규칙)
	 *         settle1~8 직위, setchk = '1' 이면 그 칸을 숨김, junchk = '1' 이면 "전결", crolin = '1' 이면 사선
	 *  3순위: 기본값
	 */
	private List<Map<String, Object>> resolveApproverList(String approversParam) {
		// 1순위: 화면 직접 입력
		if (approversParam != null && !approversParam.trim().isEmpty()) {
			List<Map<String, Object>> paramList = java.util.Arrays.stream(approversParam.split(","))
																							.map(String::trim)
																							.filter(s -> !s.isEmpty())
																							.map(s -> {
																								// "직위:JC" — J 전결, C 사선 (출력 팝업에서 갱신 후 고른 값)
																								int k = s.lastIndexOf(':');
																								if (k > 0) {
																									String f = s.substring(k + 1).toUpperCase();
																									if (f.matches("[JC]+")) return apv(s.substring(0, k).trim(), f.contains("J"), f.contains("C"));
																								}
																								return apv(s, false, false);
																							})
																							.collect(Collectors.toList());
			if (!paramList.isEmpty()) return paramList;
		}

		// 2순위: 환경설정 결재라인
		Map<String, Object> st = slipStatusService.getSettleInfo();
		if (st != null) {
			List<Map<String, Object>> dbList = new ArrayList<>();
			for (int i = 1; i <= 8; i++) {
				if (on(st.get("setchk" + i))) continue;            // 숨김
				Object nm = st.get("settle" + i);
				if (nm == null || nm.toString().trim().isEmpty()) continue;   // 직위가 없는 칸은 그리지 않는다
				dbList.add(apv(nm.toString().trim(), on(st.get("junchk" + i)), on(st.get("crolin" + i))));
			}
			if (!dbList.isEmpty()) return dbList;
		}

		// 3순위: 기본값
		return java.util.Arrays.asList(apv("담당", false, false), apv("차장", false, false), apv("사무처장", false, false));
	}

	private static Map<String, Object> apv(String name, boolean jun, boolean cro) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("name", name);
		m.put("jun", jun);
		m.put("cro", cro);
		return m;
	}

	/** 원본 조건: null · 빈칸 · '0' 이면 꺼짐, 그 외 켜짐 */
	private static boolean on(Object v) {
		if (v == null) return false;
		String s = v.toString().trim();
		return !s.isEmpty() && !"0".equals(s);
	}

}