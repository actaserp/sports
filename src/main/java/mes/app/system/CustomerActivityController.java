package mes.app.system;

import lombok.extern.slf4j.Slf4j;
import mes.app.system.service.CustomerActivityService;
import mes.app.system.service.CustomerCurrentService;
import mes.app.common.TenantContext;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 고객사 활동 이력 API.
 * 고객사현황 화면(선택한 고객사의 이력)과 활동 이력 화면(전체) 이 함께 쓴다.
 * 전 고객사 데이터라 메뉴 권한은 내부(ZZ) 그룹에만 준다.
 */
@Slf4j
@RestController
@RequestMapping("/api/system/customer_activity")
public class CustomerActivityController {

    @Autowired
    CustomerActivityService activityService;

    /** 유형 · 상태 코드표. 화면이 이 값으로 선택지를 그린다. */
    @GetMapping("/codes")
    public AjaxResult codes(Authentication auth) {
        AjaxResult denied = deny(auth);
        if (denied != null) return denied;
        AjaxResult r = new AjaxResult();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("types", CustomerActivityService.TYPES);
        m.put("statuses", CustomerActivityService.STATUSES);
        r.data = m;
        r.success = true;
        return r;
    }

    @GetMapping("/sites")
    public AjaxResult sites(Authentication auth) {
        AjaxResult denied = deny(auth);
        if (denied != null) return denied;
        AjaxResult r = new AjaxResult();
        try {
            r.data = activityService.getSites();
            r.success = true;
        } catch (Exception e) {
            log.error("[CustomerActivity] 고객사 목록 오류", e);
            r.success = false;
            r.message = "고객사 목록을 불러오지 못했습니다: " + rootMessage(e);
        }
        return r;
    }

    // 고객사를 고르는 파라미터는 일부러 spjangcd 가 아닌 이름(site · site_cd)을 쓴다.
    // SpjangSecurityInterceptor 가 spjangcd 파라미터를 세션 사업장과 비교해 막기 때문이다.
    // 이 API 는 전 고객사를 다루는 내부용이고, 권한은 deny() 가 따로 확인한다.
    @GetMapping("/list")
    public AjaxResult list(@RequestParam(value = "site", required = false) String site,
                           @RequestParam(required = false) String from,
                           @RequestParam(required = false) String to,
                           @RequestParam(required = false) String types,
                           @RequestParam(required = false) String status,
                           @RequestParam(required = false) String keyword,
                           Authentication auth) {
        AjaxResult denied = deny(auth);
        if (denied != null) return denied;
        AjaxResult r = new AjaxResult();
        try {
            List<Map<String, Object>> rows = activityService.getList(site, from, to, types, status, keyword);
            boolean over = rows.size() > CustomerActivityService.MAX_ROWS;
            if (over) rows = rows.subList(0, CustomerActivityService.MAX_ROWS);
            r.data = rows;
            r.success = true;
            r.message = over ? "최근 " + CustomerActivityService.MAX_ROWS + "건만 표시합니다. 조건을 좁혀 주세요." : "";
        } catch (IllegalArgumentException e) {
            r.success = false;
            r.message = e.getMessage();
        } catch (Exception e) {
            log.error("[CustomerActivity] 조회 오류", e);
            r.success = false;
            r.message = "조회 중 오류가 발생했습니다: " + rootMessage(e);
        }
        return r;
    }

    @PostMapping("/save")
    public AjaxResult save(@RequestBody Map<String, Object> body, Authentication auth) {
        AjaxResult denied = deny(auth);
        if (denied != null) return denied;
        AjaxResult r = new AjaxResult();
        try {
            r.data = activityService.save(body, username(auth), userId(auth));
            r.success = true;
            r.message = "저장했습니다.";
        } catch (IllegalArgumentException e) {
            r.success = false;
            r.message = e.getMessage();
        } catch (Exception e) {
            log.error("[CustomerActivity] 저장 오류", e);
            r.success = false;
            r.message = "저장 중 오류가 발생했습니다: " + rootMessage(e);
        }
        return r;
    }

    @PostMapping("/delete")
    public AjaxResult delete(@RequestBody Map<String, Object> body,
                             Authentication auth) {
        AjaxResult denied = deny(auth);
        if (denied != null) return denied;
        AjaxResult r = new AjaxResult();
        try {
            Object id = body == null ? null : body.get("id");
            activityService.delete(Long.parseLong(String.valueOf(id)));
            r.success = true;
            r.message = "삭제했습니다.";
        } catch (NumberFormatException e) {
            r.success = false;
            r.message = "잘못된 이력 번호입니다.";
        } catch (IllegalArgumentException e) {
            r.success = false;
            r.message = e.getMessage();
        } catch (Exception e) {
            log.error("[CustomerActivity] 삭제 오류", e);
            r.success = false;
            r.message = "삭제 중 오류가 발생했습니다: " + rootMessage(e);
        }
        return r;
    }

    // ── 권한 ────────────────────────────────────────────────
    //  메뉴 권한은 화면만 가린다. 이 API 는 전 고객사를 다루므로 서버에서도 막는다.
    //  내부 사업장(ZZ) 계정이나 superuser 만 통과한다.

    /** 내부 사용자면 null, 아니면 거절 응답 */
    private AjaxResult deny(Authentication auth) {
        boolean ok = false;
        try {
            User u = (User) auth.getPrincipal();
            ok = Boolean.TRUE.equals(u.getSuperUser())
                   || CustomerCurrentService.INTERNAL_DB_KEY.equals(TenantContext.getDbKey());
        } catch (Exception e) {
            ok = false;
        }
        if (ok) return null;
        AjaxResult r = new AjaxResult();
        r.success = false;
        r.message = "권한이 없습니다.";
        return r;
    }

    // ── 내부 ────────────────────────────────────────────────

    private String username(Authentication auth) {
        try {
            return ((User) auth.getPrincipal()).getUsername();
        } catch (Exception e) {
            return null;
        }
    }

    private Object userId(Authentication auth) {
        try {
            return ((User) auth.getPrincipal()).getId();
        } catch (Exception e) {
            return null;
        }
    }

    private String rootMessage(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null) c = c.getCause();
        return c.getMessage() != null ? c.getMessage() : e.toString();
    }
}