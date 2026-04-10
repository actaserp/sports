package mes.app.PaymentList;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import mes.app.PaymentList.service.PaymentDetailService;
import mes.app.common.TenantContext;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
//import mes.domain.repository.approval.TB_AA010ATCHRepository;
//import mes.domain.repository.approval.tb_aa010Repository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.util.IOUtils;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@RestController
@RequestMapping("/api/PaymentDetail")
public class PaymentDetailController {

  @Autowired
  PaymentDetailService paymentDetailService;

//  @Autowired
//  tb_aa010Repository tbAa010PdfRepository;
//
//  @Autowired
//  TB_AA010ATCHRepository tbAa010AtchRepository;

  @GetMapping("/read")
  public AjaxResult getPaymentList(@RequestParam(value = "startDate") String startDate,
                                   @RequestParam(value = "endDate") String endDate,
                                   @RequestParam(value = "SearchPayment", required = false) String SearchPayment,
                                   @RequestParam(value = "searchText", required = false) String searchText,
                                   Authentication auth) {
    AjaxResult result = new AjaxResult();
    String spjangcd = TenantContext.get();
    log.info("결재 내역 read 들어온 데이터:startDate{}, endDate{}, spjangcd {}, SearchPayment {} ,searchUserNm {} ", startDate, endDate, spjangcd, SearchPayment, searchText);

    try {
      // 데이터 조회
      User user = (User) auth.getPrincipal();
      Integer personid = user.getPersonid();
      List<Map<String, Object>> getPaymentList = paymentDetailService.getPaymentList(spjangcd, startDate, endDate, SearchPayment,searchText, personid);

      ObjectMapper mapper = new ObjectMapper();

      for (Map<String, Object> item : getPaymentList) {
        //날짜 포맷 변환 (repodate)
        formatDateField(item, "repodate");
        //날짜 포맷 변환 (appdate)
        formatDateField(item, "indate");

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

        item.put("fileList", fileList);                  // ✅ 항상 넣고
        item.put("isdownload", !fileList.isEmpty());     // ✅ 상태 표시

      }

      // 데이터가 있을 경우 성공 메시지
      result.success = true;
      result.message = "데이터 조회 성공";
      result.data = getPaymentList;

    } catch (Exception e) {
      // 예외 처리
      result.success = false;
      result.message = "데이터 조회 중 오류 발생: " + e.getMessage();
    }

    return result;
  }

  @GetMapping("/read1")
  public AjaxResult getPaymentList1(@RequestParam(value = "startDate") String startDate,
                                    @RequestParam(value = "endDate") String endDate,
                                    @RequestParam(value = "search_spjangcd", required = false) String spjangcd,
                                    Authentication auth) {
    AjaxResult result = new AjaxResult();
//    log.info("결재목록_문서현황 read 들어온 데이터:startDate{}, endDate{}, spjangcd {} ", startDate, endDate, spjangcd);

    try {

      User user = (User) auth.getPrincipal();
//      String agencycd = user.getAgencycd().replaceFirst("^p", "");
      String userName = user.getFirst_name();
      Integer personid = user.getPersonid();
      // 데이터 조회
      List<Map<String, Object>> getPaymentList = paymentDetailService.getPaymentList1(spjangcd, startDate, endDate, personid);


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


  // 날짜 포맷
  private void formatDateField(Map<String, Object> item, String fieldName) {
    Object dateValue = item.get(fieldName);
    if (dateValue instanceof String) {
      String dateStr = (String) dateValue;
      try {
        if (dateStr.length() == 8) { // "yyyyMMdd" 형식인지 확인
          String formattedDate = dateStr.substring(0, 4) + "-" + dateStr.substring(4, 6) + "-" + dateStr.substring(6, 8);
          item.put(fieldName, formattedDate);
        } else {
          item.put(fieldName, "잘못된 날짜 형식");
        }
      } catch (Exception ex) {
        log.error("{} 변환 중 오류 발생: {}", fieldName, ex.getMessage());
        item.put(fieldName, "잘못된 날짜 형식");
      }
    }
  }

  @RequestMapping(value = "/pdf", method = RequestMethod.GET)
  public ResponseEntity<Resource> getPdf(@RequestParam("appnum") String appnum) {
    try {
    //  log.info("PDF 조회 요청: appnum={}", appnum);

      // DB에서 PDF 파일명 조회
      Optional<String> optionalPdfFileName = paymentDetailService.findPdfFilenameByRealId(appnum);
      if (optionalPdfFileName.isEmpty()) {
        log.warn("PDF 파일명을 찾을 수 없음: appnum={}", appnum);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
      }

      // 파일명 그대로 사용
      String pdfFileName = optionalPdfFileName.get();
   //   log.info("사용 파일명: {}", pdfFileName);

      // 운영체제별 저장 경로 설정
      String osName = System.getProperty("os.name").toLowerCase();
      String uploadDir = osName.contains("win") ? "C:\\Temp\\APP\\S_KRU\\"
          : System.getProperty("user.home") + "/APP/S_KRU";

      // PDF 파일 경로 설정 및 존재 여부 확인
      Path pdfPath = Paths.get(uploadDir, pdfFileName);
    //  log.info("PDF 파일 경로: {}", pdfPath.toString());

      if (!Files.exists(pdfPath)) {
        log.warn("파일이 존재하지 않음: {}", pdfPath.toString());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
      }

      // 파일 정보 로깅
      File file = pdfPath.toFile();
    //  log.info("파일 존재 확인 완료 - 파일 크기: {} bytes", file.length());

      // PDF 파일을 Resource로 변환 후 응답
      Resource resource = new FileSystemResource(file);
   //   log.info("Resource 변환 완료, 파일 응답 준비 시작");

      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_PDF);
      headers.setContentDisposition(ContentDisposition.inline().filename(pdfFileName, StandardCharsets.UTF_8).build());

      // `X-Frame-Options` 제거 (필요한 경우 추가 가능)
      headers.add("X-Frame-Options", "ALLOW-FROM http://localhost:8020");
      headers.add("Access-Control-Allow-Origin", "*");  // 모든 도메인 허용
      headers.add("Access-Control-Allow-Methods", "GET, OPTIONS");
      headers.add("Access-Control-Allow-Headers", "Content-Type, Authorization");

     // log.info("PDF 응답 완료 - 파일명: {}, 크기: {} bytes", pdfFileName, file.length());

      return ResponseEntity.ok()
          .headers(headers)
          .contentLength(file.length())
          .body(resource);

    } catch (Exception e) {
      log.error("서버 내부 오류 발생: appnum={}, message={}", appnum, e.getMessage(), e);
      return ResponseEntity.internalServerError().build();
    }
  }

  //첨부파일
  @RequestMapping(value = "/pdf2", method = RequestMethod.GET)
  public ResponseEntity<Resource> getPdf2(@RequestParam("appnum") String appnum) {
    try {
     // log.info("PDF 조회 요청: appnum={}", appnum);

      // DB에서 PDF 파일명 조회
      Optional<String> optionalPdfFileName = paymentDetailService.findPdfFilenameByRealId2(appnum);
      if (optionalPdfFileName.isEmpty()) {
        log.warn("PDF 파일명을 찾을 수 없음: appnum={}", appnum);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
      }

      // 파일명 그대로 사용
      String pdfFileName = optionalPdfFileName.get();
      log.info("사용 파일명: {}", pdfFileName);

      // 운영체제별 저장 경로 설정
      String osName = System.getProperty("os.name").toLowerCase();
      String uploadDir = osName.contains("win") ? "C:\\Temp\\APP\\S_KRU\\"
          : System.getProperty("user.home") + "/APP/S_KRU";

      // PDF 파일 경로 설정 및 존재 여부 확인
      Path pdfPath = Paths.get(uploadDir, pdfFileName);
     // log.info("PDF 파일 경로: {}", pdfPath.toString());

      if (!Files.exists(pdfPath)) {
        log.warn("파일이 존재하지 않음: {}", pdfPath.toString());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
      }

      // 파일 정보 로깅
      File file = pdfPath.toFile();
     // log.info("파일 존재 확인 완료 - 파일 크기: {} bytes", file.length());

      // PDF 파일을 Resource로 변환 후 응답
      Resource resource = new FileSystemResource(file);
      //log.info("Resource 변환 완료, 파일 응답 준비 시작");

      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_PDF);
      headers.setContentDisposition(ContentDisposition.inline().filename(pdfFileName, StandardCharsets.UTF_8).build());

      // `X-Frame-Options` 제거 (필요한 경우 추가 가능)
      headers.add("X-Frame-Options", "ALLOW-FROM http://localhost:8020");
      headers.add("Access-Control-Allow-Origin", "*");  // 모든 도메인 허용
      headers.add("Access-Control-Allow-Methods", "GET, OPTIONS");
      headers.add("Access-Control-Allow-Headers", "Content-Type, Authorization");

      //log.info("PDF 응답 완료 - 파일명: {}, 크기: {} bytes", pdfFileName, file.length());

      return ResponseEntity.ok()
          .headers(headers)
          .contentLength(file.length())
          .body(resource);

    } catch (Exception e) {
      log.error("서버 내부 오류 발생: appnum={}, message={}", appnum, e.getMessage(), e);
      return ResponseEntity.internalServerError().build();
    }
  }

  @PostMapping("/changeState")
  public AjaxResult ChangeState(@RequestBody Map<String, Object> request
  , Authentication auth) {
    AjaxResult result = new AjaxResult();

    User user = (User) auth.getPrincipal();
    String username = user.getUsername();
    Integer userid = user.getPersonid();
    String appnum = (String) request.get("appnum");
    String appgubun = (String) request.get("appgubun");
    // action = 결재변경 상태값
    String action = (String) request.get("action");
    String remark = (String) request.get("remark");
    Integer appperid = userid;
    String papercd = (String) request.get("papercd");

    log.info("📥 결재 상태 변경 요청: appnum={}, appgubun={}, action={}, remark={} ,appperid={}, papercd={}",
        appnum, appgubun, action, remark, appperid, papercd);

    // 📌 action 문자열 → 상태코드로 변환
    Map<String, String> actionCodeMap = Map.of(
        "reject", "131",
        "hold", "201",
        "approve", "101",
        "cancel", "001"
    );

    String stateCode = actionCodeMap.get(action);
    if (stateCode == null) {
      result.success = false;
      result.message = "유효하지 않은 상태 변경 요청입니다.";
      return result;
    }


    try {
      boolean updated = false;

      // 분기 처리 (전표, 파일별로 구분)
//      if (appnum.startsWith("S")) {
//        updated = paymentDetailService.updateStateForS(appnum, appgubun, stateCode, remark, appperid, papercd);
//      } else if (appnum.matches("^[0-9].*ZZ$")) {
//        updated = paymentDetailService.updateStateForNumberZZ(appnum, appgubun, stateCode, remark, appperid, papercd);
//      } else if (appnum.startsWith("V")) {
        updated = paymentDetailService.updateStateForV(appnum, appgubun, stateCode, remark, appperid, papercd);
//      } else {
//        result.success = false;
//        result.message = "지원되지 않는 문서번호 형식입니다.";
//        return result;
//      }

      if (updated) {
        result.success = true;
        result.message = "상태가 성공적으로 변경되었습니다.";
      } else {
        result.success = false;
        result.message = "상태 변경 실패: 대상 문서가 없거나 조건 불일치";
      }

    } catch (Exception e) {
      log.error("❌ 상태 변경 중 예외 발생", e);
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
      Object appnumObj = request.get("appnum");
      String appnum;

      if (appnumObj instanceof String) {
        appnum = (String) appnumObj;
      } else if (appnumObj instanceof Map) {
        Map<?, ?> appnumMap = (Map<?, ?>) appnumObj;
        appnum = String.valueOf(appnumMap.get("value")); // 프론트 구조 확인 필요
      } else {
        throw new IllegalArgumentException("올바르지 않은 appnum 값");
      }

      User user = (User) auth.getPrincipal();
//      String appperid = user.getAgencycd().replaceFirst("^p", "");
      Integer personid = user.getPersonid();
      personid = 8;

      boolean canCancel = paymentDetailService.canCancelApproval(appnum, personid);
      boolean isApproved = paymentDetailService.isAlreadyApproved(appnum);

      result.success = true;
      result.message = "";
      result.data = Map.of(
          "canCancel", canCancel,
          "isApproved", isApproved
      );

    } catch (Exception e) {
      result.success = false;
      result.message = "결재자 정보 확인 중 오류 발생";
    }

    return result;
  }


//  private boolean fileExistsInPdfTable(String appnum) {
//    return tbAa010PdfRepository.existsBySpdateAndFilenameIsNotNull(appnum);
//  }

//  private boolean fileExistsInAtchTable(String appnum) {
//    return tbAa010AtchRepository.existsBySpdateAndFilenameIsNotNull(appnum);
//  }

//  private Map<String, Object> createFileMapFromPdf(String appnum, String label) {
//    var entity = tbAa010PdfRepository.findBySpdate(appnum);
//    return Map.of(
//        "filepath", entity.getFilepath(),
//        "filesvnm", entity.getFilename(),
//        "fileornm", label
//    );
//  }

//  private Map<String, Object> createFileMapFromAtch(String appnum, String label) {
//    var entity = tbAa010AtchRepository.findBySpdate(appnum);
//    return Map.of(
//        "filepath", entity.getFilepath(),
//        "filesvnm", entity.getFilename(),
//        "fileornm", label
//    );
//  }

  @PostMapping("/downloader")
  public void downloadFile(@RequestParam("appnum") String appnum, HttpServletResponse response) throws IOException, InterruptedException {
    Map<String, Object> vacData = paymentDetailService.getVacFileList(appnum);

    String frdateStr = vacData.get("frdate").toString();
    String todateStr = vacData.get("todate").toString();
    String daynum = vacData.get("daynum").toString();
    String reqdateStr = vacData.get("reqdate").toString();

    DateTimeFormatter ymdFormatter = DateTimeFormatter.ofPattern("yyyyMMdd");
    DateTimeFormatter displayFormatter = DateTimeFormatter.ofPattern("yyyy 년 MM 월 dd 일");

    LocalDate frDate = LocalDate.parse(frdateStr, ymdFormatter);
    LocalDate toDate = LocalDate.parse(todateStr, ymdFormatter);
    LocalDate reqDate = LocalDate.parse(reqdateStr, ymdFormatter);

    String repodateFormat = String.format("%s  ~  %s  ( %s ) 일간",
            frDate.format(displayFormatter),
            toDate.format(displayFormatter),
            daynum);

    String uuid = UUID.randomUUID().toString();
    Path tempXlsx = Files.createTempFile(uuid, ".xlsx");
    Path tempPdf = Path.of(tempXlsx.toString().replace(".xlsx", ".pdf"));

    try (FileInputStream fis = new FileInputStream("C:/Temp/mes21/문서/VacDemoFile.xlsx");
         Workbook workbook = new XSSFWorkbook(fis);
         FileOutputStream fos = new FileOutputStream(tempXlsx.toFile())) {

      Sheet sheet = workbook.getSheetAt(0);
      setCell(sheet, 2, 2, (String) vacData.get("worknm"));
      setCell(sheet, 9, 2, (String) vacData.get("repopernm"));
      setCell(sheet, 7, 2, (String) vacData.get("jiknm"));
      setCell(sheet, 5, 2, (String) vacData.get("departnm"));
      setCell(sheet, 16, 0, (String) vacData.get("remark"));
      setCell(sheet, 11, 2, repodateFormat);
      setCell(sheet, 24, 3, (String) vacData.get("worknm"));
      setCell(sheet, 27, 0, reqDate.format(displayFormatter));
      setCell(sheet, 30, 10, (String) vacData.get("repopernm"));

      workbook.write(fos);
    }

    ProcessBuilder pb = new ProcessBuilder(
            "C:/Program Files/LibreOffice/program/soffice.exe",
            "--headless",
            "--convert-to", "pdf",
            "--outdir", tempPdf.getParent().toString(),
            tempXlsx.toAbsolutePath().toString()
    );
    pb.inheritIO();
    Process process = pb.start();
    process.waitFor();

    int retries = 0;
    while ((!Files.exists(tempPdf) || Files.size(tempPdf) == 0) && retries++ < 100) {
      Thread.sleep(100); // 최대 10초 대기
    }
    if (!Files.exists(tempPdf) || Files.size(tempPdf) == 0) {
      throw new FileNotFoundException("PDF 변환 실패: " + tempPdf.toString());
    }

    try (FileInputStream fis = new FileInputStream(tempPdf.toFile())) {
      response.setContentType("application/pdf");
      response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''휴가신청서.pdf");
      response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
      response.setHeader("Pragma", "no-cache");
      response.setHeader("Expires", "0");

      IOUtils.copy(fis, response.getOutputStream());
      response.flushBuffer();
    }
    // ⬇ 여기서 executor 실행
    ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor();
    cleaner.schedule(() -> {
      try {
        Files.deleteIfExists(tempXlsx);
        Files.deleteIfExists(tempPdf);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }, 5, TimeUnit.MINUTES);
    cleaner.shutdown();
  }



  @GetMapping("/agencyName")
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
  }

  @GetMapping("/readVacFile")
  public void readVacFile(@RequestParam("appnum") String appnum, HttpServletResponse response) throws Exception {
    Map<String, Object> vacData = paymentDetailService.getVacFileList(appnum);

    String frdateStr = vacData.get("frdate").toString();  // "YYYYMMDD"
    String todateStr = vacData.get("todate").toString();  // "YYYYMMDD"
    String daynum = vacData.get("daynum").toString();     //
    String reqdateStr = vacData.get("reqdate").toString();

    DateTimeFormatter ymdFormatter = DateTimeFormatter.ofPattern("yyyyMMdd");
    DateTimeFormatter displayFormatter = DateTimeFormatter.ofPattern("yyyy 년 MM 월 dd 일");

    LocalDate frDate = LocalDate.parse(frdateStr, ymdFormatter);
    LocalDate toDate = LocalDate.parse(todateStr, ymdFormatter);
    LocalDate reqDate = LocalDate.parse(reqdateStr, ymdFormatter);

    String repodateFormat = String.format("%s  ~  %s  ( %s ) 일간",
            frDate.format(displayFormatter),
            toDate.format(displayFormatter),
            daynum);


    // 1. UUID 기반 임시 파일명 생성
    String uuid = UUID.randomUUID().toString();
    Path tempXlsx = Files.createTempFile(uuid, ".xlsx");
    Path tempPdf = Path.of(tempXlsx.toString().replace(".xlsx", ".pdf"));

    // 2. 엑셀 템플릿 불러오기 및 수정
    try (FileInputStream fis = new FileInputStream("C:/Temp/mes21/문서/VacDemoFile.xlsx");
         Workbook workbook = new XSSFWorkbook(fis);
         FileOutputStream fos = new FileOutputStream(tempXlsx.toFile())) {

      Sheet sheet = workbook.getSheetAt(0);
      // sheet.getRow(5).getCell(2).setCellValue((String) vacData.get("papernm")); // 서류구분 (휴가신청서)
//      sheet.getRow(2).getCell(2).setCellValue((String) vacData.get("worknm")); //  휴가구분 (연차, 반차, 병가 등)
//      sheet.getRow(9).getCell(2).setCellValue((String) vacData.get("repopernm")); // 휴가신청자 이름
//      sheet.getRow(7).getCell(2).setCellValue((String) vacData.get("jiknm")); // 직급명
//      sheet.getRow(5).getCell(2).setCellValue((String) vacData.get("departnm")); // 부서명
//      sheet.getRow(16).getCell(0).setCellValue((String) vacData.get("remark")); // 사유
//      sheet.getRow(11).getCell(2).setCellValue(repodateFormat); // 기간
//      sheet.getRow(24).getCell(3).setCellValue((String) vacData.get("worknm")); // 신청휴가구분
//      sheet.getRow(27).getCell(0).setCellValue(reqDate.format(displayFormatter)); // 신청일
//      sheet.getRow(29).getCell(10).setCellValue((String) vacData.get("repopernm")); // 제출인
      setCell(sheet, 2, 2, (String) vacData.get("worknm"));
      setCell(sheet, 9, 2, (String) vacData.get("repopernm"));
      setCell(sheet, 7, 2, (String) vacData.get("jiknm"));
      setCell(sheet, 5, 2, (String) vacData.get("departnm"));
      setCell(sheet, 16, 0, (String) vacData.get("remark"));
      setCell(sheet, 11, 2, repodateFormat);
      setCell(sheet, 24, 3, (String) vacData.get("worknm"));
      setCell(sheet, 27, 0, reqDate.format(displayFormatter));
      setCell(sheet, 30, 10, (String) vacData.get("repopernm"));

      workbook.write(fos);
    }

    // 3. LibreOffice로 PDF 변환
    ProcessBuilder pb = new ProcessBuilder(
            "C:/Program Files/LibreOffice/program/soffice.exe",
            "--headless",
            "--convert-to", "pdf",
            "--outdir", tempPdf.getParent().toString(),
            tempXlsx.toAbsolutePath().toString()
    );
    pb.inheritIO();
    Process process = pb.start();
    process.waitFor();

    // 4. PDF 응답 전송
    try (FileInputStream fis = new FileInputStream(tempPdf.toFile())) {
      response.setContentType("application/pdf");
      response.setHeader("Content-Disposition", "inline; filename=vacation.pdf");
      IOUtils.copy(fis, response.getOutputStream());
      response.flushBuffer();
    }

    // 5. 일정 시간 후 임시파일 자동 삭제 (5분)
    Executors.newSingleThreadScheduledExecutor().schedule(() -> {
      try {
        Files.deleteIfExists(tempXlsx);
        Files.deleteIfExists(tempPdf);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }, 5, TimeUnit.MINUTES);
  }
  private void setCell(Sheet sheet, int rowIdx, int colIdx, String value) {
    Row row = sheet.getRow(rowIdx);
    if (row == null) row = sheet.createRow(rowIdx);
    Cell cell = row.getCell(colIdx);
    if (cell == null) cell = row.createCell(colIdx);
    cell.setCellValue(value);
  }
}
