package mes.app.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import mes.app.approval.service.ApprovalService;
import mes.app.common.TenantContext;
import mes.config.Settings;
import mes.domain.entity.User;
import mes.domain.entity.approval.TB_E063;
import mes.domain.entity.approval.TB_E063_PK;
import mes.domain.entity.approval.TB_E064;
import mes.domain.entity.approval.TB_E064_PK;
import mes.domain.model.AjaxResult;
import mes.domain.repository.approval.E063Repository;
import mes.domain.repository.approval.E064Repository;
import mes.domain.repository.approval.E080Repository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;


@Slf4j
@RestController
@RequestMapping("/api/approval")
public class ApprovalController {   //결재라인등록
    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private E063Repository e063Repository;

    @Autowired
    private E064Repository e064Repository;

    @Autowired
    private E080Repository e080Repository;

    @Autowired
    Settings settings;

    /**
     * 등록사원 결정
     * - 화면에서 선택한 사원(person.id)이 있으면 그 사원
     * - 없으면 로그인 사용자
     */
    private Integer resolvePersonid(String paramPersonid, User user) {
        if (paramPersonid != null && !paramPersonid.isBlank()) {
            return Integer.valueOf(paramPersonid.trim());
        }
        return user.getPersonid();
    }

    // 결재라인등록 그리드 read
    @GetMapping("/read")
    public AjaxResult getList(@RequestParam Map<String, String> params,
                              Authentication auth) {
        AjaxResult result = new AjaxResult();

        User user = (User) auth.getPrincipal();
        String papercd = params.get("papercd");
        String spjangcd = TenantContext.get();
        Integer personid = resolvePersonid(params.get("personid"), user);
        String comcd = params.get("comcd");

        List<Map<String, Object>> items =
          this.approvalService.getCheckPaymentList(personid, papercd, spjangcd, comcd);
        result.data = items;

        return result;
    }

    // 결재라인 사원 그리드 read
    @GetMapping("/readPapercd")
    public AjaxResult getListPapercd(@RequestParam Map<String, String> params
      , Authentication auth) {
        String papercd = params.get("papercd");
        String spjangcd = params.get("spjangcd");

        List<Map<String, Object>> items = this.approvalService.getListPapercd(papercd, spjangcd);
        AjaxResult result = new AjaxResult();
        result.data = items;

        return result;
    }

    // 결재자 옵션 불러오기 (사원선택)
    @GetMapping("/getKcperid")
    public AjaxResult getKcperid(){
        List<Map<String, Object>> items = this.approvalService.getKcperid();

        AjaxResult result = new AjaxResult();
        result.data = items;
        return result;
    }

    // 로그인 사용자 정보 (등록사원 기본 선택용)
    @GetMapping("/getUserInfo")
    public AjaxResult getUserInfo(Authentication auth){
        User user = (User) auth.getPrincipal();

        Map<String, Object> info = new HashMap<>();
        Map<String, Object> row = approvalService.getUserInfo(user.getUsername());
        if (row != null) {
            info.putAll(row);
        }
        info.put("personid", user.getPersonid());

        AjaxResult result = new AjaxResult();
        result.data = info;
        return result;
    }

    // 문서코드 옵션 불러오기
    @GetMapping("/getComcd")
    public AjaxResult getListHgrb(){
        List<Map<String, Object>> items = this.approvalService.getComcd();

        AjaxResult result = new AjaxResult();
        result.data = items;
        return result;
    }

    // 삭제 메서드
    @PostMapping("/delete")
    public AjaxResult deleteBody(@RequestParam Map<String, String> params,
                                 Authentication auth) {
        AjaxResult result = new AjaxResult();

        Map<String, String> deleteParams = new HashMap<>(params);
        deleteParams.put("spjangcd", TenantContext.get());

        try {
            approvalService.deleteApprovalLine(deleteParams);
            result.success = true;
            result.message = "삭제하였습니다.";
        } catch (Exception e) {
            result.success = false;
            result.message = "삭제에 실패하였습니다.";
        }
        return result;
    }

    // 063 삭제메서드
    @PostMapping("/deleteHead")
    public AjaxResult deleteHead(@RequestParam Map<String, String> params,
                                 Authentication auth) {
        AjaxResult result = new AjaxResult();
        // 063table PK - spjangcd,personid,papercd
        TB_E063_PK e063PK = new TB_E063_PK();
        e063PK.setPapercd(params.get("papercd"));
        e063PK.setSpjangcd(TenantContext.get());
        e063PK.setPersonid(Integer.valueOf(params.get("personid")));

        try {
            e063Repository.deleteById(e063PK);
            result.success = true;
            result.message = "삭제하였습니다.";
        }
        catch (Exception e){
            result.success = false;
            result.message = "삭제에 실패하였습니다.";
        }
        return result;
    }

    // 결재라인 등록
    @PostMapping("/save")
    public AjaxResult saveOrder(@RequestParam Map<String, String> params,
                                Authentication auth) throws IOException {
        AjaxResult result = new AjaxResult();
        LocalDateTime createdAt = LocalDateTime.now();
        String indate = createdAt.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        User user = (User) auth.getPrincipal();
        String spjangcd = TenantContext.get();
        Integer personid = resolvePersonid(params.get("personid"), user);

        // custcd 조회
        Map<String, String> bizInfo = approvalService.getBizInfoBySpjangcd(spjangcd);
        String custcd = bizInfo.get("custcd");

        String papercd    = params.get("papercd");
        String gubun      = params.get("gubun");
        String seq        = params.get("seq");
        String kcpersonid = params.get("kcpersonid");
        String no         = params.get("no");

        if (no == null || no.isEmpty()) {
            no = approvalService.getNextNo(spjangcd, personid, papercd);
        }

        Map<String, Object> saveParams = new HashMap<>();
        saveParams.put("spjangcd",   spjangcd);
        saveParams.put("custcd",     custcd);
        saveParams.put("personid",   personid);
        saveParams.put("papercd",    papercd);
        saveParams.put("gubun",      gubun);
        saveParams.put("seq",        seq);
        saveParams.put("kcpersonid", kcpersonid);
        saveParams.put("no",         no);
        saveParams.put("indate",     indate);

        try {
            approvalService.saveApprovalLine(saveParams);
            result.success = true;
            result.message = "저장을 성공했습니다.";
        } catch (Exception e) {
            log.error("[결재라인 저장 실패] params={}", saveParams, e);
            result.success = false;
            result.message = "저장 실패(" + e.getMessage() + ")";
        }
        return result;
    }

    @GetMapping("/detail")
    public AjaxResult getDetail(@RequestParam("no") String no,
                                @RequestParam("papercd") String papercd,
                                @RequestParam("perid") String perid,
                                Authentication auth) {
        AjaxResult result = new AjaxResult();
        try {
            String spjangcd = TenantContext.get();
            Map<String, Object> detail = approvalService.getCheckPaymentDetail(no, papercd, perid, spjangcd);
            result.success = true;
            result.data = detail;
        } catch (Exception e) {
            result.success = false;
            result.message = "상세 조회 중 오류 발생: " + e.getMessage();
        }
        return result;
    }


}