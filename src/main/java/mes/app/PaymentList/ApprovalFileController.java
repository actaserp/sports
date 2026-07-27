package mes.app.PaymentList;

import lombok.extern.slf4j.Slf4j;
import mes.app.PaymentList.service.ApprovalFilePDFService;
import mes.app.common.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/appkey")
public class ApprovalFileController {

	@Autowired
	private ApprovalFilePDFService pdfService;

	@GetMapping
	public ResponseEntity<String> generatePdf(@RequestParam String key) {

		key = key.trim();

		if (key.length() <= 10) {
			return ResponseEntity.badRequest().body("FAIL: key 형식 오류 (길이 부족): " + key);
		}

		// 뒤 10자리 = 사업자번호 (dbKey 조회용)
		String saupnum = key.substring(key.length() - 10);
		if (!saupnum.matches("\\d{10}")) {
			return ResponseEntity.badRequest().body("FAIL: 사업자번호 형식 오류: " + saupnum);
		}

		try {
			// 1) dbKey 조회 + 세팅 (첫 DB 접근보다 먼저)
			String dbKey = pdfService.findDbKeyBySaupnum(saupnum);
			if (dbKey == null || dbKey.isBlank()) {
				return ResponseEntity.badRequest().body("FAIL: 사업장 없음 saupnum=" + saupnum);
			}
			TenantContext.setDbKey(dbKey);

			StringBuilder result = new StringBuilder();
			boolean atchOk = false;
			boolean voucherOk = false;

			// ── 첨부: 원본 key 그대로 (AJ 접두어 + 사업자번호 포함) ──
			byte[] atchData = pdfService.getPdfByKeyForA(key);   // AJ202606300124ZZ2158204851
			if (atchData != null) {
				String fn = pdfService.getFilenameByKeyForA(key);
				String objKey = pdfService.uploadToNcp(key, atchData, fn, "attachment");
				atchOk = (objKey != null);
				result.append(atchOk ? "첨부완료:" + objKey : "첨부실패").append(" / ");
			} else {
				result.append("첨부없음 / ");
			}

			// ── 전표: AJ/AS/A 접두어 제거 후 조회 ──
			String pdfKey = key;
			if (key.startsWith("AJ") || key.startsWith("AS")) {
				pdfKey = key.substring(2);                 // AJ, AS 제거
			} else if (key.startsWith("A")) {
				pdfKey = key.substring(1);                 // A 제거
			}
			// pdfKey = 202606300124ZZ2158204851 (접두어 없으면 원본 그대로)

			byte[] pdfData = pdfService.getPdfByKey(pdfKey);
			if (pdfData != null) {
				String fn = pdfService.getFilenameByKey(pdfKey);
				String objKey = pdfService.uploadToNcp(pdfKey, pdfData, fn, "voucher");
				voucherOk = (objKey != null);
				result.append(voucherOk ? "전표완료:" + objKey : "전표업로드실패");
			} else {
				result.append("전표데이터없음:" + pdfKey);
			}

			// ★ 첨부 / 전표 각각 상태 표시
			String atchStatus    = atchOk    ? "첨부 SUCCESS" : "첨부 FAIL";
			String voucherStatus = voucherOk ? "전표 SUCCESS" : "전표 FAIL";

			return ResponseEntity.ok(atchStatus + " / " + voucherStatus + " | " + result);

		} catch (Exception e) {
			log.error("오류: key={}", key, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
							 .body("FAIL: " + e.getMessage());
		} finally {
			TenantContext.clear();
		}
	}

}