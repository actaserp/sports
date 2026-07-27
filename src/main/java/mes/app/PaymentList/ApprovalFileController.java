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

		log.info("[APPKEY] 진입 key={}", key);
		key = key.trim();

		if (key.length() <= 10) {
			return ResponseEntity.badRequest().body("FAIL: key 형식 오류 (길이 부족): " + key);
		}

		// 사업자번호는 뒤 10자리 (dbKey 조회용으로만 사용)
		String saupnum = key.substring(key.length() - 10);
		if (!saupnum.matches("\\d{10}")) {
			return ResponseEntity.badRequest().body("FAIL: 사업자번호 형식 오류: " + saupnum);
		}
		log.info("[APPKEY] saupnum(뒤10자리)={}", saupnum);

		try {
			String dbKey = pdfService.findDbKeyBySaupnum(saupnum);
			if (dbKey == null || dbKey.isBlank()) {
				return ResponseEntity.badRequest().body("FAIL: 사업장 없음 saupnum=" + saupnum);
			}
			TenantContext.setDbKey(dbKey);
			log.info("[APPKEY] saupnum={} → dbKey={}", saupnum, dbKey);

			StringBuilder result = new StringBuilder();
			boolean voucherOk = false;

			// ★ 조회는 분해 안 한 원본 key 그대로 사용
			if (key.startsWith("A")) {
				byte[] atchData     = pdfService.getPdfByKeyForA(key);
				String atchFilename = pdfService.getFilenameByKeyForA(key);
				if (atchData != null) {
					String objKey = pdfService.uploadToNcp(key, atchData, atchFilename, "attachment");
					result.append(objKey != null ? "첨부완료:" + objKey : "첨부실패").append(" / ");
				} else {
					result.append("첨부없음 / ");
				}

				String pdfKey = key.startsWith("AJ") ? key.substring(2) : key.substring(1);
				log.info("전표 조회 key: {} → {}", key, pdfKey);

				byte[] pdfData     = pdfService.getPdfByKey(pdfKey);
				String pdfFilename = pdfService.getFilenameByKey(pdfKey);
				if (pdfData != null) {
					String objKey = pdfService.uploadToNcp(pdfKey, pdfData, pdfFilename, "voucher");
					voucherOk = (objKey != null);
					result.append(voucherOk ? "전표완료:" + objKey : "전표업로드실패");
				} else {
					result.append("전표데이터없음:" + pdfKey);
				}

			} else {
				String pdfKey      = key.startsWith("J") ? key.substring(1) : key;
				byte[] pdfData     = pdfService.getPdfByKey(pdfKey);
				String pdfFilename = pdfService.getFilenameByKey(pdfKey);
				if (pdfData == null) {
					return ResponseEntity.status(HttpStatus.NOT_FOUND)
									 .body("FAIL: 전표데이터없음 " + pdfKey);
				}
				String objKey = pdfService.uploadToNcp(pdfKey, pdfData, pdfFilename, "voucher");
				if (objKey == null) {
					return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
									 .body("FAIL: 전표업로드실패");
				}
				voucherOk = true;
				result.append("전표완료:").append(objKey);
			}

			return ResponseEntity.ok((voucherOk ? "SUCCESS: " : "FAIL: ") + result);

		} catch (Exception e) {
			log.error("오류: key={}", key, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
							 .body("FAIL: " + e.getMessage());
		} finally {
			TenantContext.clear();
		}
	}


}
