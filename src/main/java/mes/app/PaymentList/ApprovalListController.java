package mes.app.PaymentList;

import lombok.extern.slf4j.Slf4j;
import mes.app.PaymentList.service.ApprovalListService;
import mes.app.common.TenantContext;
import mes.app.files.NcpObjectStorageService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import mes.domain.repository.UserCodeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@RestController
@RequestMapping("/api/PaymentList")
public class ApprovalListController { //결재목록

  @Autowired
  private ApprovalListService approvalListService;

  @Autowired
  private NcpObjectStorageService storageService;

  @GetMapping("/read")
  public AjaxResult getPaymentList(@RequestParam(value = "startDate") String startDate,
                                   @RequestParam(value = "endDate") String endDate,
                                   @RequestParam(value = "SearchPayment", required = false) String SearchPayment,
                                   @RequestParam(value = "searchUserNm", required = false) String searchUserNm,
                                   Authentication auth) {
    AjaxResult result = new AjaxResult();
    log.info("주문 확인 read 들어온 데이터:startDate{}, endDate{},SearchPayment {} ,searchUserNm {} ", startDate, endDate, SearchPayment, searchUserNm);
    String spjangcd = TenantContext.get();
    try {

      User user = (User) auth.getPrincipal();
      Integer personid = user.getPersonid(); // main DB의 personid → tenant DB person.id 와 매핑

      LocalDate dateStart = LocalDate.parse(startDate);
      String formattedStartDate = dateStart.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
      LocalDate dateEnd = LocalDate.parse(endDate);
      String formattedEndDate = dateEnd.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

      // 서비스단에서 tenant DB person 조회 + 결재 목록 조회 모두 처리
      List<Map<String, Object>> getPaymentList = approvalListService.getPaymentList(
        spjangcd, formattedStartDate, formattedEndDate, SearchPayment, searchUserNm, personid);

      //log.info("📦 [조회결과] 결재 목록 건수: {}", getPaymentList.size());

      for (Map<String, Object> item : getPaymentList) {
        String appnum = (String) item.get("appnum");
        List<Map<String, Object>> fileList = new ArrayList<>();

        if (appnum != null) {
          if (appnum.startsWith("AS")) {
            // AS: 첨부파일 + 전표(지출결의서) 둘 다 조회
//          if (fileExistsInAtchTable(appnum)) {
//            Map<String, Object> atch = new HashMap<>(createFileMapFromAtch(appnum, "첨부파일"));
//            atch.put("fileType", "첨부");
//            fileList.add(atch);
//            log.debug("📎 AS 첨부파일 추가: {}", atch);
//          }
//          if (fileExistsInPdfTable(appnum)) {
//            Map<String, Object> pdf = new HashMap<>(createFileMapFromPdf(appnum, "지출결의서"));
//            pdf.put("fileType", "전표");
//            fileList.add(pdf);
//            log.debug("📄 AS 전표파일 추가: {}", pdf);
//          }

          } else if (appnum.startsWith("A")) {
            // A: 첨부파일만 조회
//          if (fileExistsInAtchTable(appnum)) {
//            Map<String, Object> atch = new HashMap<>(createFileMapFromAtch(appnum, "첨부파일"));
//            atch.put("fileType", "첨부");
//            fileList.add(atch);
//            log.debug("📎 A 첨부파일 추가: {}", atch);
//          }

          } else if (appnum.startsWith("S")) {
            // S: 전표(지출결의서)만 조회
//          if (fileExistsInPdfTable(appnum)) {
//            Map<String, Object> pdf = new HashMap<>(createFileMapFromPdf(appnum, "지출결의서"));
//            pdf.put("fileType", "전표");
//            fileList.add(pdf);
//            log.debug("📄 S 전표파일 추가: {}", pdf);
//          }

          } else {
            // 기타: 전표파일만 조회
//          if (fileExistsInPdfTable(appnum)) {
//            Map<String, Object> pdf = new HashMap<>(createFileMapFromPdf(appnum, "전표파일"));
//            pdf.put("fileType", "전표");
//            fileList.add(pdf);
//            log.debug("📄 기타 전표파일 추가: {}", pdf);
//          }
          }
        }

        item.put("fileList", fileList);              // ✅ 항상 넣고
        item.put("isdownload", !fileList.isEmpty()); // ✅ 상태 표시
      }

      result.success = true;
      result.message = "데이터 조회 성공";
      result.data = getPaymentList;

    } catch (Exception e) {
      log.error("❌ [에러] 결재 목록 조회 중 예외 발생", e);
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
    String spjangcd = TenantContext.get();

    try {

      User user = (User) auth.getPrincipal();
      Integer personid = user.getPersonid(); // main DB의 personid → tenant DB person.id 와 매핑
      String userName = user.getFirst_name();

      LocalDate dateStart = LocalDate.parse(startDate);
      String formattedStartDate = dateStart.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
      LocalDate dateEnd = LocalDate.parse(endDate);
      String formattedEndDate = dateEnd.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

      // 서비스단에서 tenant DB person 조회 + 문서현황 조회 모두 처리
      List<Map<String, Object>> getPaymentList = approvalListService.getPaymentList1(
        spjangcd, formattedStartDate, formattedEndDate, personid);

      // getPaymentList null 방지
      if (getPaymentList == null) getPaymentList = new ArrayList<>();

      result.success = true;
      result.message = "데이터 조회 성공";
      result.data = Map.of(
        "userName", userName,   // 사용자 이름
        "paymentList", getPaymentList  // 결재 목록 리스트
      );

    } catch (Exception e) {
      result.success = false;
      result.message = "데이터 조회 중 오류 발생: " + e.getMessage();
    }

    return result;
  }

  @GetMapping("/detail")
  public AjaxResult getPaymentList2(@RequestParam(value = "appnum", required = false) String appnum) {
    AjaxResult result = new AjaxResult();
    String spjangcd = TenantContext.get();
//    log.info("더블클릭(결재목록) 들어온 데이터:spjangcd {}, appnum: {} ", spjangcd, appnum);

    try {

      List<Map<String, Object>> getPaymentList2 = approvalListService.getPaymentList2(spjangcd,appnum);

      result.success = true;
      result.message = "데이터 조회 성공";
      result.data = getPaymentList2;
    } catch (Exception e) {
      // 예외 처리
      result.success = false;
      result.message = "데이터 조회 중 오류 발생: " + e.getMessage();
    }

    return result;
  }


//  @GetMapping("/payType")
//  public AjaxResult ordFlagType(
//      @RequestParam(value = "parentCode", required = false) String parentCode) {
//    AjaxResult result = new AjaxResult();
//
//    try {
//      // parentCode를 기준으로 하위 그룹 필터링
//      List<UserCode> data = (parentCode != null)
//          ? userCodeRepository.findByParentId(userCodeRepository.findByCode(parentCode).stream().findFirst().get().getId())
//          : userCodeRepository.findAll();
//
//      // 성공 시 데이터와 메시지 설정
//      result.success = true;
//      result.message = "데이터 조회 성공";
//      result.data = data;
//
//    } catch (Exception e) {
//      // 예외 발생 시 처리
//      result.success = false;
//      result.message = "데이터 조회 중 오류 발생: " + e.getMessage();
//    }
//
//    return result;
//  }

//  private boolean fileExistsInPdfTable(String appnum) {
//    return tbAa010PdfRepository.existsBySpdateAndFilenameIsNotNull(appnum);
//  }
//
//  private boolean fileExistsInAtchTable(String appnum) {
//    return tbAa010AtchRepository.existsBySpdateAndFilenameIsNotNull(appnum);
//  }
//
//  private Map<String, Object> createFileMapFromPdf(String appnum, String label) {
//    var entity = tbAa010PdfRepository.findBySpdate(appnum);
//    return Map.of(
//        "filepath", entity.getFilepath(),
//        "filesvnm", entity.getFilename(),
//        "fileornm", label
//    );
//  }
//
//  private Map<String, Object> createFileMapFromAtch(String appnum, String label) {
//    var entity = tbAa010AtchRepository.findBySpdate(appnum);
//    return Map.of(
//        "filepath", entity.getFilepath(),
//        "filesvnm", entity.getFilename(),
//        "fileornm", label
//    );
//  }

  @PostMapping("/downloader")
  public ResponseEntity<?> downloadFile(@RequestBody List<Map<String, Object>> downloadList) throws IOException {

    log.info("다운로드 요청: {}건", downloadList.size());

    // 각 파일의 바이트 데이터와 파일명을 담을 리스트
    List<byte[]> fileDataList = new ArrayList<>();
    List<String> fileNames = new ArrayList<>();

    for (Map<String, Object> fileInfo : downloadList) {
      String filePath = (String) fileInfo.get("filepath");
      String fileName = (String) fileInfo.get("fileornm");

      if (filePath == null || filePath.isBlank()) continue;

      try {
        byte[] data;
        if (filePath.startsWith("sports/")) {
          // NCP Object Storage에서 다운로드
          try (ResponseInputStream<GetObjectResponse> s3Stream = storageService.download(filePath)) {
            data = s3Stream.readAllBytes();
          }
        } else {
          // 로컬 파일
          File file = new File(filePath);
          if (!file.exists()) {
            log.warn("로컬 파일 없음: {}", filePath);
            continue;
          }
          data = Files.readAllBytes(file.toPath());
        }
        fileDataList.add(data);
        fileNames.add(fileName != null ? fileName : new File(filePath).getName());
      } catch (Exception e) {
        log.error("파일 읽기 실패: {}", filePath, e);
      }
    }

    if (fileDataList.isEmpty()) {
      return ResponseEntity.notFound().build();
    }

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);

    // 단일 파일: 그대로 반환
    if (fileDataList.size() == 1) {
      String encodedFileName = URLEncoder.encode(fileNames.get(0), StandardCharsets.UTF_8).replace("+", "%20");
      headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedFileName);
      return ResponseEntity.ok().headers(headers).body(new ByteArrayResource(fileDataList.get(0)));
    }

    // 복수 파일: ZIP으로 묶어서 반환
    ByteArrayOutputStream zipBaos = new ByteArrayOutputStream();
    try (ZipOutputStream zipOut = new ZipOutputStream(zipBaos)) {
      Set<String> usedNames = new HashSet<>();
      for (int i = 0; i < fileDataList.size(); i++) {
        String name = fileNames.get(i);
        String uniqueName = name;
        int count = 1;
        while (usedNames.contains(uniqueName)) {
          int dot = name.lastIndexOf('.');
          uniqueName = (dot > 0) ? name.substring(0, dot) + "_" + count++ + name.substring(dot) : name + "_" + count++;
        }
        usedNames.add(uniqueName);
        zipOut.putNextEntry(new ZipEntry(uniqueName));
        zipOut.write(fileDataList.get(i));
        zipOut.closeEntry();
      }
    }

    String encodedZipName = URLEncoder.encode("download.zip", StandardCharsets.UTF_8).replace("+", "%20");
    headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedZipName);
    return ResponseEntity.ok().headers(headers).body(new ByteArrayResource(zipBaos.toByteArray()));
  }

}
