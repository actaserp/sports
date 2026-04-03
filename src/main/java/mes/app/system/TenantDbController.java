package mes.app.system;

import lombok.extern.slf4j.Slf4j;
import mes.app.system.service.TenantDbService;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/system/tenant_db")
public class TenantDbController {

    @Autowired
    TenantDbService tenantDbService;

    @GetMapping("/read")
    public AjaxResult getList(@RequestParam(required = false) String keyword) {
        AjaxResult result = new AjaxResult();
        List<Map<String, Object>> items = tenantDbService.getList(keyword);
        result.data = items;
        return result;
    }

    @PostMapping("/save")
    public AjaxResult save(@RequestBody Map<String, Object> param) {
        AjaxResult result = new AjaxResult();
        try {
            Map<String, Object> stats = tenantDbService.save(param);
            boolean connected      = Boolean.TRUE.equals(stats.get("connected"));
            int totalConnected     = (int) stats.get("totalConnected");
            int totalFailed        = (int) stats.get("totalFailed");
            String errorMsg        = (String) stats.get("errorMsg");

            result.success = true;
            result.message = buildSaveMessage(connected, totalConnected, totalFailed, errorMsg);
        } catch (Exception e) {
            log.error("tenant_db 저장 실패", e);
            result.success = false;
            result.message = e.getMessage();
        }
        return result;
    }

    @PostMapping("/reconnect")
    public AjaxResult reconnect(@RequestBody Map<String, Object> param) {
        AjaxResult result = new AjaxResult();
        try {
            long id = Long.parseLong(param.get("id").toString());
            Map<String, Object> stats = tenantDbService.reconnectOne(id);
            boolean connected  = Boolean.TRUE.equals(stats.get("connected"));
            int totalConnected = (int) stats.get("totalConnected");
            int totalFailed    = (int) stats.get("totalFailed");
            String errorMsg    = (String) stats.get("errorMsg");

            result.success = true;
            result.message = buildConnectMessage(connected, totalConnected, totalFailed, errorMsg);
        } catch (Exception e) {
            log.error("tenant_db 재연결 실패", e);
            result.success = false;
            result.message = e.getMessage();
        }
        return result;
    }

    @PostMapping("/reload")
    public AjaxResult reload() {
        AjaxResult result = new AjaxResult();
        try {
            Map<String, Object> stats = tenantDbService.reloadAll();
            int totalConnected = (int) stats.get("totalConnected");
            int totalFailed    = (int) stats.get("totalFailed");
            result.success = true;
            result.message = String.format("전체 재로드 완료 — 연결 성공 %d개 / 실패 %d개", totalConnected, totalFailed);
        } catch (Exception e) {
            log.error("tenant_db 재로드 실패", e);
            result.success = false;
            result.message = e.getMessage();
        }
        return result;
    }

    private String buildSaveMessage(boolean connected, int totalConnected, int totalFailed, String errorMsg) {
        if (connected) {
            return "저장되었습니다. 연결 성공";
        }
        return "저장되었습니다. 연결 실패: " + shortenError(errorMsg);
    }

    private String buildConnectMessage(boolean connected, int totalConnected, int totalFailed, String errorMsg) {
        if (connected) {
            return "연결 성공";
        }
        return "연결 실패: " + shortenError(errorMsg);
    }

    private String shortenError(String msg) {
        if (msg == null) return "";
        return msg.length() > 80 ? msg.substring(0, 80) + "..." : msg;
    }
}
