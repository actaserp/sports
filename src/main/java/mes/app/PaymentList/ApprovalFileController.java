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
		try {
			StringBuilder result = new StringBuilder();

			if (key.startsWith("A")) {
				// ── 1. 첨부파일 (TB_AA010ATCH) — 전체 key 그대로 ──
				byte[] atchData = pdfService.getPdfByKeyForA(key);
				String atchFilename = pdfService.getFilenameByKeyForA(key);

				if (atchData != null) {
					String objKey = pdfService.uploadToNcp(key, atchData, atchFilename, "attachment");
					result.append(objKey != null ? "첨부파일 완료: " + objKey : "첨부파일 업로드 실패").append("\n");
				} else {
					result.append("첨부파일 데이터 없음\n");
				}

				// ── 2. 전표 PDF (TB_AA010PDF) — 접두사 제거 후 ──
				String pdfKey = key.startsWith("AJ") ? key.substring(2) : key.substring(1);
				byte[] pdfData = pdfService.getPdfByKey(pdfKey);
				String pdfFilename = pdfService.getFilenameByKey(pdfKey);

				log.info("전표 조회 key: {} → {}", key, pdfKey);

				if (pdfData != null) {
					String objKey = pdfService.uploadToNcp(pdfKey, pdfData, pdfFilename, "voucher");
					result.append(objKey != null ? "전표 완료: " + objKey : "전표 업로드 실패");
				} else {
					result.append("전표 데이터 없음: " + pdfKey);
				}

			} else {
				String pdfKey = key.startsWith("J") ? key.substring(1) : key;
				byte[] pdfData = pdfService.getPdfByKey(pdfKey);
				String pdfFilename = pdfService.getFilenameByKey(pdfKey);

				if (pdfData == null) {
					return ResponseEntity.status(HttpStatus.NOT_FOUND).body("PDF 데이터 없음");
				}
				String objKey = pdfService.uploadToNcp(pdfKey, pdfData, pdfFilename, "voucher");
				if (objKey == null) {
					return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("전표 업로드 실패");
				}
				result.append("전표 완료: ").append(objKey);
			}

			return ResponseEntity.ok(result.toString());

		} catch (Exception e) {
			log.error("오류: key={}, error={}", key, e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("오류: " + e.getMessage());
		}
	}
}

/*	@GetMapping
	public ResponseEntity<String> generatePdf(@RequestParam String key,
																						@RequestParam(required = false) String spjangcd,
																						@RequestParam(required = false) String dbKey) {
		try {
			if (spjangcd != null && !spjangcd.isBlank()) TenantContext.set(spjangcd);
			if (dbKey != null && !dbKey.isBlank()) TenantContext.setDbKey(dbKey);

			String resultMsg = "";

			if (key.startsWith("A")) {
				// ── 1. 첨부파일 처리 (TB_AA010ATCH) ──────────────────────
				byte[] atchData    = pdfService.getPdfByKeyForA(key);
				String atchFilename = pdfService.getFilenameByKeyForA(key);

				if (atchData != null) {
					String atchObjectKey = pdfService.uploadToNcp(key, atchData, atchFilename, "attachment");
					if (atchObjectKey == null) {
						return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("첨부파일 NCP 업로드 실패");
					}
					resultMsg += "첨부파일 완료: " + atchObjectKey + "\n";
				} else {
					log.warn("첨부파일 데이터 없음: key={}", key);
				}

				// ── 2. PDF 전표 처리 (TB_AA010PDF) ───────────────────────
				// AJ → J 제거, A → 제거 후 일반 key 추출
				String pdfKey = key.startsWith("AJ") ? key.substring(2) : key.substring(1);
				byte[] pdfData    = pdfService.getPdfByKey(pdfKey);
				String pdfFilename = pdfService.getFilenameByKey(pdfKey);

				if (pdfData != null) {
					String pdfObjectKey = pdfService.uploadToNcp(pdfKey, pdfData, pdfFilename, "voucher");
					if (pdfObjectKey == null) {
						return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("PDF 전표 NCP 업로드 실패");
					}
					resultMsg += "PDF 전표 완료: " + pdfObjectKey;
				} else {
					log.warn("PDF 전표 데이터 없음: key={}", pdfKey);
					resultMsg += "PDF 전표 데이터 없음";
				}

			} else {
				// ── A 없는 경우 PDF 전표만 처리 ──────────────────────────
				String pdfKey = key.startsWith("J") ? key.substring(1) : key;
				byte[] pdfData    = pdfService.getPdfByKey(pdfKey);
				String pdfFilename = pdfService.getFilenameByKey(pdfKey);

				if (pdfData == null) {
					return ResponseEntity.status(HttpStatus.NOT_FOUND).body("PDF 데이터 없음");
				}

				String pdfObjectKey = pdfService.uploadToNcp(pdfKey, pdfData, pdfFilename, "voucher");
				if (pdfObjectKey == null) {
					return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("PDF 전표 NCP 업로드 실패");
				}
				resultMsg = "PDF 전표 완료: " + pdfObjectKey;
			}

			return ResponseEntity.ok(resultMsg);

		} catch (Exception e) {
			log.error("오류: key={}, error={}", key, e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("오류: " + e.getMessage());
		} finally {
			TenantContext.clear();
		}
	}*/
