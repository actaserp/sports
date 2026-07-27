package mes.app.PaymentList.service;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import mes.app.files.NcpObjectStorageService;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class ApprovalFilePDFService {

  @Autowired
  SqlRunner sqlRunner;

  @Autowired
  @Qualifier("mainSqlRunner")
  SqlRunner mainSqlRunner;

  @Autowired
  NcpObjectStorageService storageService;

  public String findDbKeyBySaupnum(String saupnum) {
    String normalized = saupnum.replaceAll("[^0-9]", "");   // 하이픈 제거
    MapSqlParameterSource params = new MapSqlParameterSource();
    params.addValue("saupnum", normalized);

    Map<String, Object> row = mainSqlRunner.getRow("""
        SELECT spjangcd FROM tb_xa012
        WHERE REPLACE(saupnum, '-', '') = :saupnum
        """, params);

    if (row == null || row.get("spjangcd") == null) return null;
    return row.get("spjangcd").toString().trim();
  }

  // 전표 PDF 바이너리 조회 (TB_AA010PDF)
  public byte[] getPdfByKey(String key) {
    try {
      MapSqlParameterSource params = new MapSqlParameterSource();
      params.addValue("file_key", key.trim());
      Map<String, Object> result = sqlRunner.getRow("""
          SELECT CAST(pdf_data AS VARBINARY(MAX)) AS pdf_data
          FROM TB_AA010PDF
          WHERE LTRIM(RTRIM(spdate)) = :file_key
          """, params);
      if (result != null && result.containsKey("pdf_data")) return (byte[]) result.get("pdf_data");
      return null;
    } catch (Exception e) {
      log.error("전표 PDF 조회 오류: key={}, error={}", key, e.getMessage(), e);
      return null;
    }
  }

  // 첨부파일 바이너리 조회 (TB_AA010ATCH)
  public byte[] getPdfByKeyForA(String key) {
    try {
      MapSqlParameterSource params = new MapSqlParameterSource();
      params.addValue("file_key", key.trim());
      Map<String, Object> result = sqlRunner.getRow("""
          SELECT CAST(pdf_data AS VARBINARY(MAX)) AS pdf_data
          FROM TB_AA010ATCH
          WHERE LTRIM(RTRIM(spdate)) = :file_key
          """, params);
      if (result != null && result.containsKey("pdf_data")) return (byte[]) result.get("pdf_data");
      log.warn("첨부파일 데이터 없음: key={}", key);
      return null;
    } catch (Exception e) {
      log.error("첨부파일 조회 오류: key={}, error={}", key, e.getMessage(), e);
      return null;
    }
  }

  // 전표 파일명 조회 (TB_AA010PDF)
  public String getFilenameByKey(String key) {
    try {
      MapSqlParameterSource params = new MapSqlParameterSource();
      params.addValue("file_key", key.trim());
      Map<String, Object> result = sqlRunner.getRow("""
          SELECT filename FROM TB_AA010PDF
          WHERE LTRIM(RTRIM(spdate)) = :file_key
          """, params);
      if (result != null && result.containsKey("filename")) {
        Object v = result.get("filename");
        return v != null ? v.toString() : null;
      }
      return null;
    } catch (Exception e) {
      log.error("전표 파일명 조회 오류: key={}, error={}", key, e.getMessage(), e);
      return null;
    }
  }

  // 첨부 파일명 조회 (TB_AA010ATCH)
  public String getFilenameByKeyForA(String key) {
    try {
      MapSqlParameterSource params = new MapSqlParameterSource();
      params.addValue("file_key", key.trim());
      Map<String, Object> result = sqlRunner.getRow("""
          SELECT filename FROM TB_AA010ATCH
          WHERE LTRIM(RTRIM(spdate)) = :file_key
          """, params);
      if (result != null && result.containsKey("filename")) {
        Object v = result.get("filename");
        return v != null ? v.toString() : null;
      }
      return null;
    } catch (Exception e) {
      log.error("첨부 파일명 조회 오류: key={}, error={}", key, e.getMessage(), e);
      return null;
    }
  }

  // filepath 업데이트 — TB_AA010PDF 또는 TB_AA010ATCH
  private boolean updateFilepath(String key, String fileType, String objectKey) {
    try {
      String sql = fileType.equals("attachment")
                     ? "UPDATE TB_AA010ATCH SET filepath = :filepath WHERE LTRIM(RTRIM(spdate)) = :file_key"
                     : "UPDATE TB_AA010PDF  SET filepath = :filepath WHERE LTRIM(RTRIM(spdate)) = :file_key";

      MapSqlParameterSource params = new MapSqlParameterSource();
      params.addValue("filepath", objectKey);
      params.addValue("file_key", key.trim());

      int updatedRows = sqlRunner.execute(sql, params);
//      log.info("filepath 업데이트 완료: key={}, objectKey={}, updatedRows={}", key, objectKey, updatedRows);
      return updatedRows > 0;
    } catch (Exception e) {
      log.error("filepath 업데이트 오류: key={}, error={}", key, e.getMessage(), e);
      return false;
    }
  }

  // NCP 업로드 + filepath 업데이트
  public String uploadToNcp(String key, byte[] pdfData, String filename, String fileType) {
    try {
      // 파일명 보정
      if (filename == null || filename.isBlank()) {
        filename = key + ".pdf";
//        log.warn("파일명 없음 → 기본값 사용: {}", filename);
      } else if (!filename.toLowerCase().endsWith(".pdf")) {
        filename += ".pdf";
      }

      // dbKey 조회
      String dbKey = TenantContext.getDbKey();
      if (dbKey == null || dbKey.isBlank()) {
        log.error("dbKey 없음: key={}", key);
        return null;
      }

      // 이미 NCP에 업로드된 경우 스킵
      String existingPath = getFilepath(key, fileType);
      if (existingPath != null && existingPath.startsWith("sports/")) {
//        log.info("이미 NCP 업로드됨, 스킵: key={}, path={}", key, existingPath);
        return existingPath;
      }

      // NCP 업로드
      String uuidFileName = UUID.randomUUID() + ".pdf";
      String objectKey    = storageService.buildObjectKey(dbKey, fileType, uuidFileName);

      try (ByteArrayInputStream bis = new ByteArrayInputStream(pdfData)) {
        storageService.upload(objectKey, bis, pdfData.length, "application/pdf");
//        log.info("NCP 업로드 완료: key={}, objectKey={}", key, objectKey);
      }

      // filepath 업데이트
      boolean updated = updateFilepath(key, fileType, objectKey);
      if (!updated) {
        log.warn("filepath 업데이트 실패: key={}", key);
      }

      return objectKey;

    } catch (Exception e) {
      log.error("NCP 업로드 오류: key={}, error={}", key, e.getMessage(), e);
      return null;
    }
  }

  // filepath 조회
  private String getFilepath(String key, String fileType) {
    try {
      String sql = fileType.equals("attachment")
                     ? "SELECT filepath FROM TB_AA010ATCH WHERE LTRIM(RTRIM(spdate)) = :file_key"
                     : "SELECT filepath FROM TB_AA010PDF  WHERE LTRIM(RTRIM(spdate)) = :file_key";

      MapSqlParameterSource params = new MapSqlParameterSource();
      params.addValue("file_key", key.trim());

      Map<String, Object> result = sqlRunner.getRow(sql, params);
      if (result != null && result.containsKey("filepath")) {
        Object v = result.get("filepath");
        return v != null ? v.toString() : null;
      }
      return null;
    } catch (Exception e) {
      log.error("filepath 조회 오류: key={}, error={}", key, e.getMessage(), e);
      return null;
    }
  }


}