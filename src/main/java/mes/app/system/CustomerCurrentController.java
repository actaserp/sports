package mes.app.system;

import lombok.extern.slf4j.Slf4j;
import mes.app.system.service.CustomerCurrentService;
import mes.app.common.TenantContext;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/system/customer_current")
public class CustomerCurrentController {

    @Autowired
    CustomerCurrentService customerCurrentService;

    /**
     * 고객사 목록.
     * srchStartDt · srchEndDt 는 가입일 범위이고 비우면 전체다.
     * state : '' 전체 / WAIT 승인 대기 / O 승인 / STOP 중지
     */
    @GetMapping("/read")
    public AjaxResult getList(@RequestParam(required = false) String srchStartDt,
                              @RequestParam(required = false) String srchEndDt,
                              @RequestParam(required = false) String keyword,
                              @RequestParam(required = false) String state,
                              Authentication auth) {
        AjaxResult denied = deny(auth);
        if (denied != null) return denied;
        AjaxResult result = new AjaxResult();
        try {
            result.data = customerCurrentService.getCustomerList(srchStartDt, srchEndDt, keyword, state);
            result.success = true;
        } catch (Exception e) {
            log.error("[CustomerCurrent] 목록 조회 오류", e);
            result.success = false;
            result.message = "조회 중 오류가 발생했습니다: " + rootMessage(e);
        }
        return result;
    }

    @PostMapping("/batchApprove")
    public AjaxResult batchApprove(@RequestBody Map<String, Object> param, Authentication auth) {
        AjaxResult denied = deny(auth);
        if (denied != null) return denied;
        AjaxResult result = new AjaxResult();
        List<String> ids = ids(param);
        if (ids.isEmpty()) {
            result.success = false;
            result.message = "승인할 사업체를 선택해주세요.";
            return result;
        }
        try {
            int n = customerCurrentService.batchApprove(ids, username(auth), userId(auth));
            result.success = true;
            result.message = n == ids.size()
                    ? "승인 처리가 완료되었습니다."
                    : "승인 처리가 완료되었습니다. (이미 승인된 " + (ids.size() - n) + "곳은 건너뜀)";
        } catch (Exception e) {
            log.error("[CustomerCurrent] 승인 오류 ids={}", ids, e);
            result.success = false;
            result.message = "승인 중 오류가 발생해 처리하지 않았습니다: " + rootMessage(e);
        }
        return result;
    }

    @PostMapping("/btnStop")
    public AjaxResult btnStop(@RequestBody Map<String, Object> param, Authentication auth) {
        AjaxResult denied = deny(auth);
        if (denied != null) return denied;
        AjaxResult result = new AjaxResult();
        List<String> ids = ids(param);
        if (ids.isEmpty()) {
            result.success = false;
            result.message = "중지할 사업체를 선택해주세요.";
            return result;
        }
        try {
            int n = customerCurrentService.btnStop(ids, username(auth), userId(auth));
            result.success = true;
            result.message = n == ids.size()
                    ? "중지 처리가 완료되었습니다. 해당 사업장 계정의 로그인이 차단됩니다."
                    : "중지 처리가 완료되었습니다. (이미 중지된 " + (ids.size() - n) + "곳은 건너뜀)";
        } catch (IllegalArgumentException e) {
            result.success = false;
            result.message = e.getMessage();
        } catch (Exception e) {
            log.error("[CustomerCurrent] 중지 오류 ids={}", ids, e);
            result.success = false;
            result.message = "중지 중 오류가 발생해 처리하지 않았습니다: " + rootMessage(e);
        }
        return result;
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

    private List<String> ids(Map<String, Object> param) {
        List<String> out = new ArrayList<>();
        Object v = param == null ? null : param.get("ids");
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !String.valueOf(o).isBlank()) out.add(String.valueOf(o).trim());
            }
        }
        return out;
    }

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
