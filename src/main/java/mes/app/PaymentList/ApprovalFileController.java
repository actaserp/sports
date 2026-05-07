package mes.app.PaymentList;

import lombok.extern.slf4j.Slf4j;
import mes.app.PaymentList.service.ApprovalFilePDFService;
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
			byte[] pdfData;
			String filename;
			String fileType;
			String originalKey = key;

			if (key.startsWith("A")) {
				pdfData = pdfService.getPdfByKeyForA(key);
				filename = pdfService.getFilenameByKeyForA(key);
				fileType = "attachment";  // 첨부

				if (pdfData == null) {
					key = key.startsWith("AJ") ? key.substring(2) : key.substring(1);
					pdfData = pdfService.getPdfByKey(key);
					filename = pdfService.getFilenameByKey(key);
					fileType = "voucher";  // 전표
				}
			} else {
				String lookupKey = key.startsWith("J") ? key.substring(1) : key;
				pdfData = pdfService.getPdfByKey(lookupKey);
				filename = pdfService.getFilenameByKey(lookupKey);
				fileType = "voucher";  // 전표
			}

			if (pdfData == null) {
				return ResponseEntity.status(HttpStatus.NOT_FOUND).body("PDF 데이터 없음");
			}

			String objectKey = pdfService.uploadToNcp(originalKey, pdfData, filename, fileType);
			if (objectKey == null) {
				return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("NCP 업로드 실패");
			}

			return ResponseEntity.ok("완료: " + objectKey);

		} catch (Exception e) {
			log.error("오류: key={}, error={}", key, e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("오류: " + e.getMessage());
		}
	}
}