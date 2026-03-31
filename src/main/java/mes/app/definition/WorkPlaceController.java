package mes.app.definition;

import mes.app.common.TenantContext;
import mes.app.definition.service.WorkPlaceService;
import mes.domain.entity.Tb_xa012;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import mes.domain.repository.Tb_xa012Repository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/workplace")
public class WorkPlaceController {
    @Autowired
    WorkPlaceService workPlaceService;
    @Autowired
    Tb_xa012Repository tbXa012Repository;
    
    // 사업장정보 리스트 조회
    @GetMapping("/read")
    public AjaxResult getSpjangInfo(
            HttpServletRequest request,
            Authentication auth) {
        AjaxResult result = new AjaxResult();
        User user = (User) auth.getPrincipal();
        Sort sort = Sort.by(Sort.Direction.DESC, "spjangcd");
        List<Tb_xa012> tbXa012 = Boolean.TRUE.equals(user.getSuperUser())
                ? tbXa012Repository.findAll(sort)
                : tbXa012Repository.findBySpjangcd(user.getDbKey(), sort);
        // 세무서명 조회(세무서 코드(comtaxoff) 와 세무서명(taxnm) 매핑)
        for (Tb_xa012 item : tbXa012) {
            if(item.getComtaxoff() != null && !item.getComtaxoff().isEmpty()) {
                item.setTaxnm(workPlaceService.getTaxnm(item.getComtaxoff()));
            }
        }

        result.data = tbXa012;
        return result;
    }
    // 사업장 등록
    @PostMapping("/save")
    public AjaxResult saveSpjangInfo(
            @ModelAttribute Tb_xa012 tbXa012,
            HttpServletRequest request,
            Authentication auth) {
        AjaxResult result = new AjaxResult();

        tbXa012.setSpjangcd(TenantContext.getDbKey());
        try {
            Tb_xa012 existing = tbXa012Repository.findById(tbXa012.getSpjangcd())
                    .orElse(null);

            if (existing != null) {
                // 프론트 formData에 있는 필드만 세팅 (bill_plans_id 등 나머지는 기존값 유지)
                existing.setSaupnum(tbXa012.getSaupnum());
                existing.setSpjangnm(tbXa012.getSpjangnm());
                existing.setCompnum(tbXa012.getCompnum());
                existing.setPrenm(tbXa012.getPrenm());
                existing.setZipcd(tbXa012.getZipcd());
                existing.setAdresa(tbXa012.getAdresa());
                existing.setAdresb(tbXa012.getAdresb());
                existing.setZipcd2(tbXa012.getZipcd2());
                existing.setAdres2a(tbXa012.getAdres2a());
                existing.setAdres2b(tbXa012.getAdres2b());
                existing.setBiztype(tbXa012.getBiztype());
                existing.setItem(tbXa012.getItem());
                existing.setTel1(tbXa012.getTel1());
                existing.setFax(tbXa012.getFax());
                existing.setEmailadres(tbXa012.getEmailadres());
                existing.setAgnertel1(tbXa012.getAgnertel1());
                existing.setAgnertel2(tbXa012.getAgnertel2());
                existing.setComtaxoff(tbXa012.getComtaxoff());
                existing.setTaxnm(tbXa012.getTaxnm());
                existing.setCustperclsf(tbXa012.getCustperclsf());
                existing.setTaxagentnm(tbXa012.getTaxagentnm());
                existing.setTaxagentcd(tbXa012.getTaxagentcd());
                existing.setTaxagenttel(tbXa012.getTaxagenttel());
                existing.setTaxagentsp(tbXa012.getTaxagentsp());
                existing.setTaxaccnum(tbXa012.getTaxaccnum());
                existing.setAjongcd(tbXa012.getAjongcd());
                existing.setOpenymd(tbXa012.getOpenymd());
                existing.setEddate(tbXa012.getEddate());

                tbXa012Repository.save(existing);
            } else {
                tbXa012Repository.save(tbXa012);
            }

            result.success = true;
            result.message = "저장되었습니다.";
        } catch (Exception e) {
            e.printStackTrace();
            result.success = false;
            result.message = "저장 중 오류 발생";
        }

        return result;
    }

    // 사업장 삭제
    @PostMapping("/delete")
    public AjaxResult deleteSpjangInfo(
            @RequestBody Map<String, Object> param,
            HttpServletRequest request,
            Authentication auth) {
        AjaxResult result = new AjaxResult();
        String spjangcd = (String) param.get("spjangcd");
        try {
            tbXa012Repository.deleteById(spjangcd);
            result.success = true;
            result.message = "삭제되었습니다.";
        } catch (Exception e) {
            e.printStackTrace();
            result.success = false;
            result.message = "삭제 중 오류 발생";
        }
        return result;
    }
    // 세무서 팝업 리스트 조회
    @GetMapping("/readPopup")
    public AjaxResult readPopup(
            @RequestParam String spjangcd,
            @RequestParam String taxcd,
            @RequestParam String taxnm2,
            @RequestParam String taxjiyuk,
            HttpServletRequest request,
            Authentication auth) {
        AjaxResult result = new AjaxResult();
        try {
            result.data = workPlaceService.getPopupList(taxcd, taxnm2, taxjiyuk);
            result.success = true;
            result.message = "팝업데이터 조회 성공";
        } catch (Exception e) {
            e.printStackTrace();
            result.success = false;
            result.message = "팝업조회 중 오류 발생";
        }
        return result;
    }
}
