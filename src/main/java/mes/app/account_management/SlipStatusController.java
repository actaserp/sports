package mes.app.account_management;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.extern.slf4j.Slf4j;
import mes.app.account_management.service.SlipStatusService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/account_management/slip_status")
public class SlipStatusController {  //전표입력현황

	@Autowired
	SlipStatusService slipStatusService;

	@Autowired
	private TemplateEngine templateEngine;

	@GetMapping("/read")
	public AjaxResult getSlipList(@RequestParam("start") String start,
																@RequestParam("end") String end,
																@RequestParam(value = "mssec", required = false) String mssec,
																@RequestParam(value = "sbuject", required = false) String sbuject) {

		List<Map<String, Object>> items = this.slipStatusService.getSlipList(start, end, mssec, sbuject);

		AjaxResult result = new AjaxResult();
		result.data = items;

		return result;
	}

	@GetMapping("/print")
	public void printSlip(
		@RequestParam("printType") String printType,
		@RequestParam("keys") String keys,
		HttpServletResponse response) throws Exception {

		try {
			// rows, slipList 선언만 - 초기화는 switch 안에서
			List<Map<String, Object>> rows;
			List<Map<String, Object>> slipList = null;

			switch (printType) {
				case "1":
					// 전표(산출내역) - 상단결재
					rows = slipStatusService.printSlip(printType, keys);
					slipList = groupBySlip(rows);
					break;

				case "2":
					// 결의양식1 - 한자금액 + 한글금액
					rows = slipStatusService.printGyeolui1(keys);
					slipList = groupByGyeolui(rows);
					slipList.forEach(slip -> {
						long amt = slip.get("amt") != null ? ((Number) slip.get("amt")).longValue() : 0L;
						slip.put("amtHanja", toHanjaAmount(amt));
						slip.put("amtKorean", toKoreanAmount(amt));
					});
					break;

				case "3":
					// 결의양식2(하단) - 결재란 하단, 한글금액
					rows     = slipStatusService.printGyeolui2(keys);
					slipList = groupBySlip(rows);
					slipList.forEach(slip -> {
						long amt = slip.get("amt") != null ? ((Number) slip.get("amt")).longValue() : 0L;
						slip.put("amtKorean", toKoreanAmount(amt));
					});
				break;

				case "4":
					// 결의양식2(상단) - 결재란 상단, 한글금액
					rows     = slipStatusService.printGyeolui2(keys);
					slipList = groupBySlip(rows);
					slipList.forEach(slip -> {
						long amt = slip.get("amt") != null ? ((Number) slip.get("amt")).longValue() : 0L;
						slip.put("amtKorean", toKoreanAmount(amt));
					});
				break;

				case "5":
					rows = slipStatusService.printGyeolui3(keys);
					slipList = groupBySlip(rows);
					slipList.forEach(slip -> {
						long amt = slip.get("amt") != null ? ((Number) slip.get("amt")).longValue() : 0L;
						slip.put("amtKorean", toKoreanAmount(amt));
					});
				break;

				case "6":
					// 결의양식3(대체)
					rows     = slipStatusService.printGyeolui6(keys);
					slipList = groupBySlip(rows);  // groupBySlip 안에서 debit/amt 처리됨
					fillDebitFromCredit(slipList);
					slipList.forEach(slip -> {
						long amt = slip.get("amt") != null ? ((Number) slip.get("amt")).longValue() : 0L;
						slip.put("amtKorean", toKoreanAmount(amt));
					});
					break;

				case "7":
					// 전표(산출내역) - 하단결재
					rows = slipStatusService.printSlip(printType, keys);
					slipList = groupBySlip(rows);
					break;

				/*case "8":
					// 결의양식(한자) - 한자금액 + 한글금액
					rows     = slipStatusService.printGyeolui8(keys);
					slipList = groupBySlip(rows);
					slipList.forEach(slip -> {
						long amt = slip.get("amt") != null ? ((Number) slip.get("amt")).longValue() : 0L;
						slip.put("amtHanja",  toHanjaAmount(amt));
						slip.put("amtKorean", toKoreanAmount(amt));
					});
					break;*/

				default:
					log.warn("[SlipPrint] 알 수 없는 printType: {}", printType);
					rows = slipStatusService.printSlip(printType, keys);
					slipList = groupBySlip(rows);
					break;
			}

			log.info("[SlipPrint] 그룹핑된 slip 수: {}", slipList.size());

			com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
			try {
				log.info("[SlipPrint] slipList JSON:\n{}", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(slipList));
			} catch (Exception ex) {
				log.warn("[SlipPrint] JSON 직렬화 실패: {}", ex.getMessage());
			}

			// Thymeleaf 렌더링
			Context context = new Context();
			context.setVariable("slipList", slipList);
			context.setVariable("printType", printType);

			String html = templateEngine.process(
				"accountCardManagement/slip_print", context
			);

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
		HttpServletResponse response) throws Exception {

		try {
			List<Map<String, Object>> rows;
			List<Map<String, Object>> slipList = null;

			switch (printType) {
				case "1":
					rows = slipStatusService.printSlip(printType, keys);
					slipList = groupBySlip(rows);
					break;
				case "2":
					rows = slipStatusService.printGyeolui1(keys);
					slipList = groupByGyeolui(rows);
					slipList.forEach(slip -> {
						long amt = slip.get("amt") != null ? ((Number) slip.get("amt")).longValue() : 0L;
						slip.put("amtHanja", toHanjaAmount(amt));
						slip.put("amtKorean", toKoreanAmount(amt));
					});
					break;
				case "3":
				case "4":
					rows = slipStatusService.printGyeolui2(keys);
					slipList = groupBySlip(rows);
					slipList.forEach(slip -> {
						long amt = slip.get("amt") != null ? ((Number) slip.get("amt")).longValue() : 0L;
						slip.put("amtKorean", toKoreanAmount(amt));
					});
					break;
				case "5":
					rows = slipStatusService.printGyeolui3(keys);
					slipList = groupBySlip(rows);
					slipList.forEach(slip -> {
						long amt = slip.get("amt") != null ? ((Number) slip.get("amt")).longValue() : 0L;
						slip.put("amtKorean", toKoreanAmount(amt));
					});
					break;
				case "6":
					rows = slipStatusService.printGyeolui6(keys);
					slipList = groupBySlip(rows);
					fillDebitFromCredit(slipList);
					slipList.forEach(slip -> {
						long amt = slip.get("amt") != null ? ((Number) slip.get("amt")).longValue() : 0L;
						slip.put("amtKorean", toKoreanAmount(amt));
					});
					break;
				case "7":
					rows = slipStatusService.printSlip(printType, keys);
					slipList = groupBySlip(rows);
					break;
				default:
					rows = slipStatusService.printSlip(printType, keys);
					slipList = groupBySlip(rows);
					break;
			}

			Context context = new Context();
			context.setVariable("slipList", slipList);
			context.setVariable("printType", printType);

			String html = templateEngine.process("accountCardManagement/slip_print", context);
			html = html.replace("&nbsp;", "&#160;");

			// 한글 폰트 적용 CSS 주입
			String fontOverride = "<style>* { font-family: 'Malgun Gothic', sans-serif !important; }</style>";
			html = html.replace("</head>", fontOverride + "</head>");

			String filename = URLEncoder.encode("전표.pdf", StandardCharsets.UTF_8);
			response.setContentType("application/pdf");
			response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + filename);

			try (OutputStream os = response.getOutputStream()) {
				PdfRendererBuilder builder = new PdfRendererBuilder();

				// 윈도우 맑은고딕 폰트 등록 (한글 지원)
				File malgunFont = new File("C:/Windows/Fonts/malgun.ttf");
				if (malgunFont.exists()) {
					builder.useFont(malgunFont, "Malgun Gothic");
				}

				builder.withHtmlContent(html, null);
				builder.toStream(os);
				builder.run();
			}

		} catch (Exception e) {
			log.error("[SlipPdf] PDF 생성 오류: {}", e.getMessage(), e);
			response.sendError(500, "PDF 생성 오류");
		}
	}

	private List<Map<String, Object>> groupBySlip(List<Map<String, Object>> rows) {
		Map<String, Map<String, Object>> groupMap = new LinkedHashMap<>();

		for (Map<String, Object> row : rows) {
			String key = row.get("spdate") + "" + row.get("spnum");

			// ✅ drcr 값 확인
			Object drcrRaw = row.get("drcr");
			Object dramtRaw = row.get("dramt");
			Object cramtRaw = row.get("cramt");

			log.info("[SlipPrint] key={} | drcr=[{}] type={} | dramt={} | cramt={}",
				key,
				drcrRaw,
				drcrRaw != null ? drcrRaw.getClass().getSimpleName() : "null",
				dramtRaw,
				cramtRaw);

			// spoccu 변환
			String spoccuRaw = row.get("spoccu") != null ? row.get("spoccu").toString() : "";
			String spoccuNm = "AA".equals(spoccuRaw) ? "전표일반" : spoccuRaw;

			groupMap.computeIfAbsent(key, k -> {
				Map<String, Object> slip = new LinkedHashMap<>();
				slip.put("spdate", row.get("spdate"));
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
				log.info("[SlipPrint]   → 차변 추가 | accnm={} | dramt={}", row.get("accnm"), dramt);
				((List<Map<String, Object>>) group.get("debit")).add(entry);
				group.put("amt", ((Long) group.get("amt")) + dramt);
			} else if ("2".equals(drcr) || "C".equals(drcr)) {
				log.info("[SlipPrint]   → 대변 추가 | accnm={} | cramt={}", row.get("accnm"), cramt);
				((List<Map<String, Object>>) group.get("credit")).add(entry);
			} else {
				// drcr 값이 없을 경우 dramt로 판단 (fallback)
				if (dramt > 0) {
					((List<Map<String, Object>>) group.get("debit")).add(entry);
					group.put("amt", ((Long) group.get("amt")) + dramt);
				} else {
					((List<Map<String, Object>>) group.get("credit")).add(entry);
				}
			}
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
				creditEntry.put("cltnm", "cltnm");

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

		return (negative ? "△" : "") + sb;
	}



}
