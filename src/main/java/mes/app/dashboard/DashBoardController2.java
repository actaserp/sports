package mes.app.dashboard;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import mes.app.common.TenantContext;
import mes.app.dashboard.service.DashBoardService2;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

@RestController
@RequestMapping("/api/dashboard2")
public class DashBoardController2 {
    @Autowired
    private DashBoardService2 dashBoardService2;

    // 작년 1월1일부터 작년오늘날자까지 상태별 건수
    @GetMapping("/LastYearCnt")
    private AjaxResult LastYearCnt(Authentication auth) {
        // 관리자 사용가능 페이지 사업장 코드 선택 로직
        User user = (User) auth.getPrincipal();
        String username = user.getUsername();
        String spjangcd = TenantContext.get();
        String perid = dashBoardService2.getPerid(username);
        String splitPerid = perid.replaceFirst("p", ""); // ✅ 첫 번째 "p"만 제거
        // 올해 진행구분(appgubun)별 데이터 개수
        List<Map<String, Object>> ThisYearCnt = this.dashBoardService2.ThisYearCnt(spjangcd, splitPerid);

        // 결재요청받은(일별) 데이터
        List<Map<String, Object>> ThisMonthResCntOfDate = this.dashBoardService2.ThisMonthResCntOfDate(spjangcd, splitPerid);
        // 결재요청받은(월별) 데이터
        List<Map<String, Object>> ThisYearResCntOfMonth = this.dashBoardService2.ThisYearResCntOfMonth(spjangcd, splitPerid);
        // 결재 올린(일별) 데이터 개수
        List<Map<String, Object>> ThisMonthReqCntOfDate = this.dashBoardService2.ThisMonthReqCntOfDate(spjangcd, splitPerid);
        // 결재 올린(월별) 데이터 개수
        List<Map<String, Object>> ThisYearReqCntOfMonth = this.dashBoardService2.ThisYearReqCntOfMonth(spjangcd, splitPerid);

        AjaxResult result = new AjaxResult();
        Map<String, Object> items = new HashMap<String, Object>();
        items.put("ThisYearCnt", ThisYearCnt);
        items.put("ThisMonthResCntOfDate", ThisMonthResCntOfDate);
        items.put("ThisYearResCntOfMonth", ThisYearResCntOfMonth);
        items.put("ThisMonthReqCntOfDate", ThisMonthReqCntOfDate);
        items.put("ThisYearReqCntOfMonth", ThisYearReqCntOfMonth);
        result.data = items;

        return result;
    }
    @GetMapping("/bindSpjangcd")
    public AjaxResult bindSpjangcd(Authentication auth) {
        // 관리자 사용가능 페이지 사업장 코드 선택 로직
        User user = (User) auth.getPrincipal();
        String username = user.getUsername();
        String spjangcd = TenantContext.get();
        // 사업장 코드 선택 로직 종료 반환 spjangcd 활용
        AjaxResult result = new AjaxResult();
        result.data = spjangcd;
        return result;
    }
    @GetMapping("/isNotice")
    public AjaxResult isNotice(Authentication auth) {
        // 관리자 사용가능 페이지 사업장 코드 선택 로직
        User user = (User) auth.getPrincipal();
        String username = user.getUsername();
        List<Map<String, Object>> notice = dashBoardService2.isNotice();
        for(Map<String, Object> item : notice){
            item.put("no", notice.indexOf(item)+1);

            // 날짜 형식 변환 (BBSDATE)
            if (item.containsKey("BBSDATE")) {
                String setupdt = (String) item.get("BBSDATE");
                if (setupdt != null && setupdt.length() == 8) {
                    String formattedDate = setupdt.substring(0, 4) + "-" + setupdt.substring(4, 6) + "-" + setupdt.substring(6, 8);
                    item.put("BBSDATE", formattedDate);
                }
            }
            // 날짜 형식 변환 (BBSFRDATE)
            if (item.containsKey("BBSFRDATE")) {
                String setupdt = (String) item.get("BBSFRDATE");
                if (setupdt != null && setupdt.length() == 8) {
                    String formattedDate = setupdt.substring(0, 4) + "-" + setupdt.substring(4, 6) + "-" + setupdt.substring(6, 8);
                    item.put("BBSFRDATE", formattedDate);
                }
            }
            // 날짜 형식 변환 (BBSTODATE)
            if (item.containsKey("BBSTODATE")) {
                String setupdt = (String) item.get("BBSTODATE");
                if (setupdt != null && setupdt.length() == 8) {
                    String formattedDate = setupdt.substring(0, 4) + "-" + setupdt.substring(4, 6) + "-" + setupdt.substring(6, 8);
                    item.put("BBSTODATE", formattedDate);
                }
            }
            ObjectMapper objectMapper = new ObjectMapper();
            if (item.get("fileinfos") != null) {
                try {
                    // JSON 문자열을 List<Map<String, Object>>로 변환
                    List<Map<String, Object>> fileitems = objectMapper.readValue((String) item.get("fileinfos"), new TypeReference<List<Map<String, Object>>>() {});

                    for (Map<String, Object> fileitem : fileitems) {
                        if (fileitem.get("filepath") != null && fileitem.get("fileornm") != null) {
                            String filenames = (String) fileitem.get("fileornm");
                            String filepaths = (String) fileitem.get("filepath");
                            String filesvnms = (String) fileitem.get("filesvnm");

                            List<String> fileornmList = filenames != null ? Arrays.asList(filenames.split(",")) : Collections.emptyList();
                            List<String> filepathList = filepaths != null ? Arrays.asList(filepaths.split(",")) : Collections.emptyList();
                            List<String> filesvnmList = filesvnms != null ? Arrays.asList(filesvnms.split(",")) : Collections.emptyList();

                            item.put("isdownload", !fileornmList.isEmpty() && !filepathList.isEmpty());
                        } else {
                            item.put("isdownload", false);
                        }
                    }

                    // fileitems를 다시 item에 넣어 업데이트
                    item.remove("fileinfos");
                    item.put("filelist", fileitems);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        AjaxResult result = new AjaxResult();
        result.data = notice;
        return result;
    }
}
