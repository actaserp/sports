package mes.app.PaymentList.service;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import mes.app.files.NcpObjectStorageService;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class ApprovalFilePDFService {

  @Autowired
  SqlRunner sqlRunner;

  @Autowired
  NcpObjectStorageService storageService;

  // ✅ 전표 PDF 바이너리 조회 (TB_AA010PDF)
  public byte[] getPdfByKey(String key) {
    try {
      MapSqlParameterSource params = new MapSqlParameterSource();
      params.addValue("file_key", key.trim());

      Map<String, Object> result = sqlRunner.getRow("""
                SELECT CAST(pdf_data AS VARBINARY(MAX)) AS pdf_data
                FROM TB_AA010PDF
                WHERE LTRIM(RTRIM(spdate)) = :file_key
            """, params);

      if (result != null && result.containsKey("pdf_data")) {
        return (byte[]) result.get("pdf_data");
      }
      return null;
    } catch (Exception e) {
      log.error("전표 PDF 조회 오류: key={}, error={}", key, e.getMessage(), e);
      return null;
    }
  }

  // ✅ 첨부파일 바이너리 조회 (TB_AA010ATCH)
  public byte[] getPdfByKeyForA(String key) {
    try {
      MapSqlParameterSource params = new MapSqlParameterSource();
      params.addValue("file_key", key.trim());

      Map<String, Object> result = sqlRunner.getRow("""
                SELECT CAST(pdf_data AS VARBINARY(MAX)) AS pdf_data
                FROM TB_AA010ATCH
                WHERE LTRIM(RTRIM(spdate)) = :file_key
            """, params);

      if (result != null && result.containsKey("pdf_data")) {
        return (byte[]) result.get("pdf_data");
      }
      log.warn("첨부파일 데이터 없음: key={}", key);
      return null;
    } catch (Exception e) {
      log.error("첨부파일 조회 오류: key={}, error={}", key, e.getMessage(), e);
      return null;
    }
  }

  // ✅ 전표 파일명 조회 (TB_AA010PDF)
  public String getFilenameByKey(String key) {
    try {
      MapSqlParameterSource params = new MapSqlParameterSource();
      params.addValue("file_key", key.trim());

      Map<String, Object> result = sqlRunner.getRow("""
                SELECT filename
                FROM TB_AA010PDF
                WHERE LTRIM(RTRIM(spdate)) = :file_key
            """, params);

      if (result != null && result.containsKey("filename")) {
        Object value = result.get("filename");
        return value != null ? value.toString() : null;
      }
      return null;
    } catch (Exception e) {
      log.error("전표 파일명 조회 오류: key={}, error={}", key, e.getMessage(), e);
      return null;
    }
  }

  // ✅ 첨부 파일명 조회 (TB_AA010ATCH)
  public String getFilenameByKeyForA(String key) {
    try {
      MapSqlParameterSource params = new MapSqlParameterSource();
      params.addValue("file_key", key.trim());

      Map<String, Object> result = sqlRunner.getRow("""
                SELECT filename
                FROM TB_AA010ATCH
                WHERE LTRIM(RTRIM(spdate)) = :file_key
            """, params);

      if (result != null && result.containsKey("filename")) {
        Object value = result.get("filename");
        return value != null ? value.toString() : null;
      }
      return null;
    } catch (Exception e) {
      log.error("첨부 파일명 조회 오류: key={}, error={}", key, e.getMessage(), e);
      return null;
    }
  }

  // ✅ 기존 테이블 filepath NCP 경로로 업데이트
  private void updateFilepath(String key, String fileType, String ncpPath) {
    try {
      MapSqlParameterSource params = new MapSqlParameterSource();
      params.addValue("filepath", ncpPath);
      params.addValue("file_key", key.trim());

      String sql = fileType.equals("voucher")
                     ? "UPDATE TB_AA010PDF SET filepath = :filepath WHERE LTRIM(RTRIM(spdate)) = :file_key"
                     : "UPDATE TB_AA010ATCH SET filepath = :filepath WHERE LTRIM(RTRIM(spdate)) = :file_key";

      sqlRunner.getRow(sql, params); // execute 대신 getRow 사용
      log.info("filepath 업데이트 완료: key={}, path={}", key, ncpPath);
    } catch (Exception e) {
      log.error("filepath 업데이트 오류: key={}, error={}", key, e.getMessage(), e);
    }
  }

  // ✅ NCP 업로드
  public String uploadToNcp(String originalKey, byte[] pdfData, String filename, String fileType) {
    try {
      if (filename == null || filename.isBlank()) {
        filename = originalKey + ".pdf";
        log.warn("파일명 없음 → 기본값 사용: {}", filename);
      } else if (!filename.toLowerCase().endsWith(".pdf")) {
        filename += ".pdf";
      }

      // ✅ custcd 조회 제거 → TenantContext에서 spjangcd 가져오기
      String spjangcd = TenantContext.get();
      if (spjangcd == null || spjangcd.isBlank()) {
        log.error("spjangcd 조회 실패: key={}", originalKey);
        return null;
      }

      // ✅ NCP 경로: sports/{spjangcd}/{fileType}/{uuid}.pdf
      String uuidFileName = UUID.randomUUID().toString() + ".pdf";
      String filePrefix   = storageService.getFilePrefix(spjangcd, fileType);   // sports/A001/voucher
      String objectKey    = storageService.buildObjectKey(spjangcd, fileType, uuidFileName); // sports/A001/voucher/uuid.pdf

      try (ByteArrayInputStream bis = new ByteArrayInputStream(pdfData)) {
        storageService.upload(objectKey, bis, pdfData.length, "application/pdf");
        log.info("NCP 업로드 완료: key={}, objectKey={}", originalKey, objectKey);
      }

      String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
      MapSqlParameterSource paramMap = new MapSqlParameterSource();
      paramMap.addValue("filedate", today);
      paramMap.addValue("checkseq", NcpObjectStorageService.toCheckseq(fileType)); // "voucher" or "attachment"
      paramMap.addValue("bbsseq", originalKey);
      paramMap.addValue("filepath", filePrefix);
      paramMap.addValue("filesvnm", uuidFileName);
      paramMap.addValue("fileextns", "pdf");
      paramMap.addValue("fileornm", filename);
      paramMap.addValue("filesize", pdfData.length);
      paramMap.addValue("inuserid", "SYSTEM");

      Map<String, Object> row = sqlRunner.getRow("""
            INSERT INTO TB_FILEINFO
                (FILEDATE, CHECKSEQ, bbsseq, FILEPATH, FILESVNM, FILEEXTNS, FILEORNM, FILESIZE, INDATEM, INUSERID)
            OUTPUT INSERTED.fileseq
            VALUES
                (:filedate, :checkseq, :bbsseq, :filepath, :filesvnm, :fileextns, :fileornm, :filesize, GETDATE(), :inuserid)
        """, paramMap);

      if (row == null) {
        log.error("TB_FILEINFO 저장 실패: key={}", originalKey);
        return null;
      }

      updateFilepath(originalKey, fileType, objectKey);

      log.info("전체 완료: key={}, fileseq={}, objectKey={}", originalKey, row.get("fileseq"), objectKey);
      return objectKey;

    } catch (Exception e) {
      log.error("NCP 업로드 오류: key={}, error={}", originalKey, e.getMessage(), e);
      return null;
    }
  }

}