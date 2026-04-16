package mes.app.PopBill;

import com.popbill.api.PopbillException;
import com.popbill.api.Response;
import com.popbill.api.TaxinvoiceService;
import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import mes.app.util.UtilClass;
import mes.domain.model.AjaxResult;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/BaseService")
public class BaseServiceServiceController {

    @Autowired
    private TaxinvoiceService taxinvoiceService;

    @Value("${popbill.linkId}")
    private String LinkId;

    @Autowired
    SqlRunner sqlRunner;

    /*@RequestMapping(value = "checkIsMember", method = RequestMethod.GET)
    public String checkIsMember(@RequestParam String corpNum) throws PopbillException {
        *//**
         * 사업자번호를 조회하여 연동회원 가입여부를 확인합니다.
         * - LinkID는 연동신청 시 팝빌에서 발급받은 링크아이디 값입니다.
         * - https://developers.popbill.com/reference/taxinvoice/java/api/member#CheckIsMember
         *//*

        AjaxResult result = new AjaxResult();


        // 조회할 사업자번호, '-' 제외 10자리

        try {
            Response response = taxinvoiceService.checkIsMember(corpNum, LinkId);
            System.out.println("");
        } catch (PopbillException e) {
            return "exception";
        }

        return "response";
    }*/

    @RequestMapping(value = "checkIsMember", method = RequestMethod.GET)
    public AjaxResult checkIsMember(HttpSession session) {
        AjaxResult result = new AjaxResult();
        result.success = false;

        String spjangcd = TenantContext.get();

        try {
            // 1차: 세션에서 조회
            String corpNum = UtilClass.getsaupnumInfoFromSession(spjangcd, session);

            // 2차: 세션에 없으면 DB에서 조회
            if (corpNum == null || corpNum.isBlank()) {
                Map<String, String> bizInfo = getBizInfoBySpjangcd(spjangcd);
                corpNum = bizInfo.get("saupnum");
            }

            if (corpNum == null || corpNum.isBlank()) {
                result.message = "사업자번호를 찾을 수 없습니다.";
                return result;
            }
            log.info("checkIsMember corpNum: {}", corpNum);
            Response response = taxinvoiceService.checkIsMember(corpNum, LinkId);
            log.info("response: {}, LinkId:{}", corpNum, LinkId);
            if (response != null) {
                result.success = true;
                log.info("checkIsMember code: {}, message: {}", response.getCode(), response.getMessage());  // 추가
                result.message = (response.getCode() == 1L && "가입".equals(response.getMessage()))
                                   ? "연동회원에 등록되어 있습니다."
                                   : "연동회원에 등록되어 있지 않습니다.";
            } else {
                result.message = "API 통신 오류 발생";
            }

        } catch (PopbillException e) {
            result.message = e.getMessage();
        }

        return result;
    }

    @RequestMapping(value = "getBalance", method = RequestMethod.GET)
    public String getBalance(Model m) throws PopbillException {
        /**
         * 연동회원의 잔여포인트를 확인합니다.
         * - 과금방식이 파트너과금인 경우 파트너 잔여포인트 확인(GetPartnerBalance API) 함수를 통해 확인하시기 바랍니다.
         * - https://developers.popbill.com/reference/taxinvoice/java/api/point#GetBalance
         */
        String CorpNum = "1778602466";
        try {
            double remainPoint = taxinvoiceService.getBalance(CorpNum);

            m.addAttribute("Result", remainPoint);

        } catch (PopbillException e) {
            m.addAttribute("Exception", e);
            return "exception";
        }

        return "result";
    }

    private Map<String, String> getBizInfoBySpjangcd(String spjangcd) {
        MapSqlParameterSource sqlParam = new MapSqlParameterSource();
        sqlParam.addValue("spjangcd", spjangcd);

        String sql = """
         select saupnum, custcd, spjangnm
         from tb_xa012
         where spjangcd = :spjangcd
     """;

        Map<String, Object> row = sqlRunner.getRow(sql, sqlParam);

        Map<String, String> result = new HashMap<>();
        result.put("saupnum", "");
        result.put("custcd", "");
        result.put("spjangnm", "");

        if (row == null || row.isEmpty()) {
            return result;
        }

        Object saupnum = row.get("saupnum");   // 사업자번호
        Object custcd  = row.get("custcd");    // 회사코드
        Object spjangnm = row.get("spjangnm"); // 사업장명

        result.put("saupnum",  saupnum  == null ? "" : String.valueOf(saupnum).trim());
        result.put("custcd",   custcd   == null ? "" : String.valueOf(custcd).trim());
        result.put("spjangnm", spjangnm == null ? "" : String.valueOf(spjangnm).trim()); // ✅ 수정

        return result;
    }

}
