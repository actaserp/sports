package mes.app.PaymentLine;

import mes.app.PaymentLine.Service.PaymentLineService;
import mes.config.Settings;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/paymentLine")
public class PaymentLineController { // 결재라인현황
    @Autowired
    PaymentLineService paymentLineService;

    @Autowired
    private Settings settings;

    // 문서코드 리스트
    @GetMapping("/read")
    public AjaxResult read(@RequestParam String comcd
            , Authentication auth){

        User user = (User) auth.getPrincipal();
        Integer personid = user.getPersonid();

        List<Map<String, Object>> items = this.paymentLineService.getPaymentList(personid, comcd);
        AjaxResult result = new AjaxResult();
        result.data = items;

        return result;
    }
    // 문서코드 리스트
    @GetMapping("/readLine")
    public AjaxResult readLine(@RequestParam String comcd,
                               @RequestParam(required = false) String personid,
                               Authentication auth){
        User user = (User) auth.getPrincipal();
        String username = user.getUsername();

        List<Map<String, Object>> items = this.paymentLineService.getCheckPaymentList(personid, comcd);
        AjaxResult result = new AjaxResult();
        result.data = items;

        return result;
    }
}
