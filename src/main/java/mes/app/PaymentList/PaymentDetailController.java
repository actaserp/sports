package mes.app.PaymentList;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import mes.app.PaymentList.service.PaymentDetailService;
import mes.app.common.TenantContext;
import mes.app.files.NcpObjectStorageService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.util.IOUtils;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

@Slf4j
@RestController
@RequestMapping("/api/PaymentDetail")
public class PaymentDetailController {  //결재 할 내역

  @Autowired
  PaymentDetailService paymentDetailService;

  @Autowired
  NcpObjectStorageService storageService;

  @GetMapping("/read")
  public AjaxResult getPaymentList(@RequestParam(value = "startDate") String startDate,
                                   @RequestParam(value = "endDate") String endDate,
                                   @RequestParam(value = "SearchPayment", required = false) String SearchPayment,
                                   @RequestParam(value = "searchText", required = false) String searchText,
                                   Authentication auth) {
    AjaxResult result = new AjaxResult();
    String spjangcd = TenantContext.get();
//    log.info("결재 내역 read 들어온 데이터:startDate{}, endDate{}, spjangcd {}, SearchPayment {} ,searchUserNm {} ", startDate, endDate, spjangcd, SearchPayment, searchText);

    try {
      User user = (User) auth.getPrincipal();
      Integer personid = user.getPersonid(); // main DB의 personid → tenant DB person.id 와 매핑

      LocalDate dateStart = LocalDate.parse(startDate);
      String formattedStartDate = dateStart.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
      LocalDate dateEnd = LocalDate.parse(endDate);
      String formattedEndDate = dateEnd.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

      // 서비스단에서 tenant DB person 조회 + 결재 내역 조회 모두 처리
      List<Map<String, Object>> getPaymentList = paymentDetailService.getPaymentList(
        spjangcd, formattedStartDate, formattedEndDate, SearchPayment, searchText, personid);

      // null 방지
      if (getPaymentList == null) getPaymentList = new ArrayList<>();

      ObjectMapper mapper = new ObjectMapper();

      for (Map<String, Object> item : getPaymentList) {
        // fileListJson → fileList
        List<Map<String, Object>> fileList = new ArrayList<>();
        String fileListJson = (String) item.get("fileListJson");

        try {
          if (fileListJson != null && !fileListJson.isBlank()) {
            fileList = mapper.readValue(fileListJson, new TypeReference<>() {});
          }
        } catch (JsonProcessingException e) {
          log.warn("📄 파일 리스트 JSON 파싱 실패: {}", fileListJson);
        }

        item.put("fileList", fileList);              // ✅ 항상 넣고
        item.put("isdownload", !fileList.isEmpty()); // ✅ 상태 표시
      }

      result.success = true;
      result.message = "데이터 조회 성공";
      result.data = getPaymentList;

    } catch (Exception e) {
      result.success = false;
      result.message = "데이터 조회 중 오류 발생: " + e.getMessage();
    }

    return result;
  }

  @GetMapping("/read1")
  public AjaxResult getPaymentList1(@RequestParam(value = "startDate") String startDate,
                                    @RequestParam(value = "endDate") String endDate,
                                    Authentication auth) {
    AjaxResult result = new AjaxResult();
//    log.info("결재목록_문서현황 read 들어온 데이터:
//    startDate{}, endDate{}, spjangcd {} ", startDate, endDate, spjangcd);
    String spjangcd = TenantContext.get();
    try {

      User user = (User) auth.getPrincipal();
      String userName = user.getFirst_name();
      Integer personid = user.getPersonid();
      // 데이터 조회
      List<Map<String, Object>> getPaymentList =
        paymentDetailService.getPaymentList1(spjangcd, startDate, endDate, personid);


      // 데이터가 있을 경우 성공 메시지
      result.success = true;
      result.message = "데이터 조회 성공";
      result.data = Map.of(
          "userName", userName,  // 사용자 이름
          "paymentList", getPaymentList // 결재 목록 리스트
      );

    } catch (Exception e) {
      // 예외 처리
      result.success = false;
      result.message = "데이터 조회 중 오류 발생: " + e.getMessage();
    }

    return result;
  }

  /*@GetMapping("/pdf")
  public void getPdf(@RequestParam("filepath") String filepath, HttpServletResponse response) {
    try {
      log.info("전표 PDF 요청: filepath={}", filepath);  // ← 추가

      if (filepath == null || filepath.isBlank()) {
        log.warn("filepath 없음");  // ← 추가
        response.sendError(HttpServletResponse.SC_NOT_FOUND, "파일 없음");
        return;
      }

      if (filepath.startsWith("sports/")) {
        log.info("NCP 다운로드 시작: {}", filepath);  // ← 추가
        try (ResponseInputStream<GetObjectResponse> s3Stream = storageService.download(filepath)) {
          response.setContentType("application/pdf");
          response.setHeader("Content-Disposition", "inline; filename=\"file.pdf\"");
          s3Stream.transferTo(response.getOutputStream());
          response.flushBuffer();
          log.info("NCP 다운로드 완료: {}", filepath);  // ← 추가
        }
      } else {
        log.info("로컬 파일 읽기: {}", filepath);  // ← 추가
        Path pdfPath = Paths.get(filepath);
        if (!Files.exists(pdfPath)) {
          log.warn("로컬 파일 없음: {}", pdfPath);  // ← 추가
          response.sendError(HttpServletResponse.SC_NOT_FOUND, "파일 없음");
          return;
        }
        String filename = pdfPath.getFileName().toString();
        response.setContentType("application/pdf");
        response.setHeader("Content-Disposition", "inline; filename*=UTF-8''" +
                                                    URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20"));
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(pdfPath.toFile()));
             BufferedOutputStream out = new BufferedOutputStream(response.getOutputStream())) {
          byte[] buffer = new byte[8192];
          int bytesRead;
          while ((bytesRead = in.read(buffer)) != -1) out.write(buffer, 0, bytesRead);
          out.flush();
          log.info("로컬 파일 전송 완료: {}", filepath);  // ← 추가
        }
      }
    } catch (Exception e) {
      log.error("전표 PDF 오류: filepath={}, error={}", filepath, e.getMessage(), e);
      try { response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR); } catch (Exception ignored) {}
    }
  }*/

  @GetMapping("/pdf")
  public void getPdf(@RequestParam("filepath") String filepath, HttpServletResponse response) {
    try {
      log.info("PDF 요청 filepath: {}", filepath);

      if (filepath == null || filepath.isBlank()) {
        response.sendError(HttpServletResponse.SC_NOT_FOUND, "파일 없음");
        return;
      }

      if (filepath.startsWith("sports/")) {
        // ✅ NCP 처리
//        log.info("NCP에서 다운로드 시도: {}", filepath);
        try (ResponseInputStream<GetObjectResponse> s3Stream = storageService.download(filepath)) {
//          log.info("NCP 다운로드 성공");
          response.setContentType("application/pdf");
          response.setHeader("Content-Disposition", "inline; filename=\"file.pdf\"");
          response.setHeader("X-Frame-Options", "SAMEORIGIN");
          s3Stream.transferTo(response.getOutputStream());
          response.flushBuffer();
        }
      } else {
        // ✅ 로컬 파일 처리 추가
        log.info("로컬 파일 읽기: {}", filepath);
        Path pdfPath = Paths.get(filepath);
        if (!Files.exists(pdfPath)) {
//          log.warn("로컬 파일 없음: {}", pdfPath);
          response.sendError(HttpServletResponse.SC_NOT_FOUND, "파일 없음");
          return;
        }
        String filename = pdfPath.getFileName().toString();
        response.setContentType("application/pdf");
        response.setHeader("Content-Disposition", "inline; filename*=UTF-8''" +
                                                    URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20"));
        response.setHeader("X-Frame-Options", "SAMEORIGIN");
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(pdfPath.toFile()));
             BufferedOutputStream out = new BufferedOutputStream(response.getOutputStream())) {
          byte[] buffer = new byte[8192];
          int bytesRead;
          while ((bytesRead = in.read(buffer)) != -1) out.write(buffer, 0, bytesRead);
          out.flush();
//          log.info("로컬 파일 전송 완료: {}", filepath);
        }
      }
    } catch (Exception e) {
      log.error("PDF 오류: filepath={}, error={}", filepath, e.getMessage(), e);
      try { response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR); } catch (Exception ignored) {}
    }
  }

  @GetMapping("/pdf2")
  public void getPdf2(@RequestParam("filepath") String filepath, HttpServletResponse response) {
    try {
      log.info("첨부 PDF 요청 filepath: {}", filepath);

      if (filepath == null || filepath.isBlank()) {
        response.sendError(HttpServletResponse.SC_NOT_FOUND, "파일 없음");
        return;
      }

      if (filepath.startsWith("sports/")) {
//        log.info("NCP에서 다운로드 시도: {}", filepath);
        try (ResponseInputStream<GetObjectResponse> s3Stream = storageService.download(filepath)) {
//          log.info("NCP 다운로드 성공");
          response.setContentType("application/pdf");
          response.setHeader("Content-Disposition", "inline; filename=\"file.pdf\"");
          response.setHeader("X-Frame-Options", "SAMEORIGIN"); // ✅ 추가
          s3Stream.transferTo(response.getOutputStream());
          response.flushBuffer();
        }
      } else {
        // 로컬 파일 처리
        Path pdfPath = Paths.get(filepath);
        if (!Files.exists(pdfPath)) {
          response.sendError(HttpServletResponse.SC_NOT_FOUND, "파일 없음");
          return;
        }
        String filename = pdfPath.getFileName().toString();
        response.setContentType("application/pdf");
        response.setHeader("Content-Disposition", "inline; filename*=UTF-8''" +
                                                    URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20"));
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(pdfPath.toFile()));
             BufferedOutputStream out = new BufferedOutputStream(response.getOutputStream())) {
          byte[] buffer = new byte[8192];
          int bytesRead;
          while ((bytesRead = in.read(buffer)) != -1) out.write(buffer, 0, bytesRead);
          out.flush();
        }
      }
    } catch (Exception e) {
      log.error("첨부 PDF 오류: filepath={}, error={}", filepath, e.getMessage(), e);
      try { response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR); } catch (Exception ignored) {}
    }
  }

  @PostMapping("/changeState")
  public AjaxResult ChangeState(@RequestBody Map<String, Object> request,
                                Authentication auth) {
    AjaxResult result = new AjaxResult();

    User user = (User) auth.getPrincipal();
    String spjangcd = TenantContext.get();

    String appnum   = (String) request.get("appnum");
    String appgubun = (String) request.get("appgubun");
    String action   = (String) request.get("action");   // 결재변경 상태값
    String remark   = (String) request.get("remark");
    String papercd  = (String) request.get("papercd");

    // 📌 personid(숫자 PK) → appperid(문자 사번코드) 변환
    String appperid = paymentDetailService.getPersonCode(spjangcd, user.getPersonid());
    if (appperid == null) {
      log.warn("결재자 코드 조회 실패: personid={}, spjangcd={}", user.getPersonid(), spjangcd);
      result.success = false;
      result.message = "결재자 정보를 찾을 수 없습니다.";
      return result;
    }

//    log.info("📥 결재 상태 변경 요청: appnum={}, appgubun={}, action={}, remark={}, papercd={}, appperid={} (personid={})",
//      appnum, appgubun, action, remark, papercd, appperid, user.getPersonid());

    // 📌 action 문자열 → 상태코드로 변환
    Map<String, String> actionCodeMap = Map.of(
      "reject",  "131",
      "hold",    "201",
      "approve", "101",
      "cancel",  "001"
    );

    String stateCode = actionCodeMap.get(action);
    if (stateCode == null) {
      result.success = false;
      result.message = "유효하지 않은 상태 변경 요청입니다.";
      return result;
    }

    try {
      boolean updated;

      // 분기 처리 (문서 종류별)
      if (appnum.startsWith("S")) {
        updated = paymentDetailService.updateStateForS(appnum, appgubun, stateCode, remark, appperid, papercd);
      } else if (appnum.matches("^[0-9].*ZZ$")) {
        updated = paymentDetailService.updateStateForNumberZZ(appnum, appgubun, stateCode, remark, appperid, papercd);
      } else if (appnum.startsWith("V")) {
        updated = paymentDetailService.updateStateForV(appnum, appgubun, stateCode, remark, appperid, papercd);
      } else {
        result.success = false;
        result.message = "지원되지 않는 문서번호 형식입니다: " + appnum;
        return result;
      }

      if (updated) {
        result.success = true;
        result.message = "상태가 성공적으로 변경되었습니다.";
      } else {
        result.success = false;
        result.message = "상태 변경 실패: 대상 문서가 없거나 조건 불일치";
      }

    } catch (Exception e) {
      log.error("❌ 상태 변경 중 예외 발생: appnum={}, appperid={}", appnum, appperid, e);
      result.success = false;
      result.message = "상태 변경 중 오류 발생: " + e.getMessage();
    }

    return result;
  }


  @PostMapping("/currentApprovalInfo")
  public AjaxResult currentAppperid(@RequestBody Map<String, Object> request,
                                    Authentication auth) {
    AjaxResult result = new AjaxResult();
    try {
      String appnum = String.valueOf(request.get("appnum"));

      User user = (User) auth.getPrincipal();
      String appperid = paymentDetailService.getPersonCode(TenantContext.get(), user.getPersonid());
      if (appperid == null) {
        result.success = false;
        result.message = "결재자 정보를 찾을 수 없습니다.";
        return result;
      }

      boolean canCancel  = paymentDetailService.canCancelApproval(appnum, appperid);
      boolean isApproved = paymentDetailService.isAlreadyApproved(appnum, appperid);

//      log.info("결재자 상태: appnum={}, appperid={}, canCancel={}, isApproved={}",
//        appnum, appperid, canCancel, isApproved);

      result.success = true;
      result.data = Map.of("canCancel", canCancel, "isApproved", isApproved);

    } catch (Exception e) {
      log.error("결재자 정보 확인 오류", e);
      result.success = false;
      result.message = "결재자 정보 확인 중 오류 발생";
    }
    return result;
  }

 /* @GetMapping("/agencyName")
  public AjaxResult getAgencyName(Authentication auth) {
    AjaxResult result = new AjaxResult();
    try {
      String agencyName = paymentDetailService.getAgencyName();  // ✅ 서비스 호출
      result.success = true;
      result.data = agencyName;
    } catch (Exception e) {
      result.success = false;
      result.message = "기관명 조회 실패";
    }
    return result;
  }*/

  @PostMapping("/downloadFiles")
  public void downloadFiles(@RequestBody List<Map<String, String>> fileList,
                            HttpServletResponse response) throws IOException {
    if (fileList == null || fileList.isEmpty()) {
      response.sendError(HttpServletResponse.SC_NOT_FOUND, "파일 없음");
      return;
    }

    // 단일 파일 → 그대로 스트리밍
    if (fileList.size() == 1) {
      Map<String, String> f = fileList.get(0);
      String filepath = f.get("filepath");
      String filename = pickName(f, "download.pdf");

      try (InputStream in = openStream(filepath)) {
        if (in == null) {
          response.sendError(HttpServletResponse.SC_NOT_FOUND, "파일 없음");
          return;
        }
        response.setContentType("application/pdf");
        response.setHeader("Content-Disposition",
          "attachment; filename*=UTF-8''" + encode(filename));
        in.transferTo(response.getOutputStream());
        response.flushBuffer();
      }
      return;
    }

    // 복수 파일 → zip
    response.setContentType("application/zip");
    response.setHeader("Content-Disposition",
      "attachment; filename*=UTF-8''" + encode("결재파일.zip"));

    Set<String> used = new HashSet<>();
    try (ZipOutputStream zos = new ZipOutputStream(response.getOutputStream(), StandardCharsets.UTF_8)) {
      for (Map<String, String> f : fileList) {
        try (InputStream in = openStream(f.get("filepath"))) {
          if (in == null) {
            log.warn("zip 대상 파일 없음: {}", f.get("filepath"));
            continue;
          }
          String name = uniqueName(used, pickName(f, "file.pdf"));
          zos.putNextEntry(new ZipEntry(name));
          in.transferTo(zos);
          zos.closeEntry();
        }
      }
    }
    response.flushBuffer();
  }

  private void setCell(Sheet sheet, int rowIdx, int colIdx, String value) {
    Row row = sheet.getRow(rowIdx);
    if (row == null) row = sheet.createRow(rowIdx);
    Cell cell = row.getCell(colIdx);
    if (cell == null) cell = row.createCell(colIdx);
    cell.setCellValue(value);
  }

  /** NCP / 로컬 구분해서 InputStream 반환. 없으면 null */
  private InputStream openStream(String filepath) throws IOException {
    if (filepath == null || filepath.isBlank()) return null;

    if (filepath.startsWith("sports/")) {
      try {
        return storageService.download(filepath);
      } catch (NoSuchKeyException e) {
        log.warn("NCP 오브젝트 없음: {}", filepath);
        return null;
      }
    }
    Path p = Paths.get(filepath);
    return Files.exists(p) ? new BufferedInputStream(new FileInputStream(p.toFile())) : null;
  }

  /** fileornm → filesvnm 순으로 파일명 결정, 없으면 fallback */
  private String pickName(Map<String, String> f, String fallback) {
    for (String k : new String[]{"fileornm", "filesvnm"}) {
      String v = f.get(k);
      if (v != null && !v.isBlank()) {
        return v.toLowerCase().endsWith(".pdf") ? v : v + ".pdf";
      }
    }
    return fallback;
  }

  private String encode(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
  }

  /** zip 내부 파일명 중복 방지: a.pdf, a(1).pdf ... */
  private String uniqueName(Set<String> used, String name) {
    String base = name, ext = "";
    int dot = name.lastIndexOf('.');
    if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
    String candidate = name;
    int i = 1;
    while (!used.add(candidate)) candidate = base + "(" + (i++) + ")" + ext;
    return candidate;
  }
}
