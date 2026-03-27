package mes.app.system;

import mes.domain.model.AjaxResult;
import mes.domain.services.CommonUtil;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import javax.transaction.Transactional;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/system/tenantmenu")
public class TenantMenuController {

    @Autowired
    @Qualifier("mainSqlRunner")
    SqlRunner sqlRunner;

    /**
     * 사업장별 메뉴 목록 조회 (전체 메뉴 + 활성화 여부)
     */
    @GetMapping("/read")
    public AjaxResult getTenantMenuList(
            @RequestParam(value = "spjangcd") String spjangcd,
            @RequestParam(value = "folder_id", required = false) Integer folderId) {

        String sql = """
    select null as id
         , mi."MenuFolder_id" as pid
         , mi."MenuCode"::text as menu_code
         , mf."FolderName" as folder_name
         , mi."MenuName" as name
         , 2 as depth
         , mi._order as ord
         , mf.id as folder_id
         , false as is_folder
         , (tm.id is not null) as enabled
         , tm.id as tm_id
    from menu_item mi
    inner join menu_folder mf on mf.id = mi."MenuFolder_id"
        and mf."Parent_id" is null
        and mf."FrontFolder_id" is not null
    left join tenant_menu tm on tm.menu_code = mi."MenuCode"::text
        and tm.spjangcd = :spjangcd
    """;

        if (folderId != null) {
            sql += " where mi.\"MenuFolder_id\" = :folder_id";
        }

        sql += " order by mf._order, mi._order";

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("spjangcd", spjangcd);
        dicParam.addValue("folder_id", folderId);

        AjaxResult result = new AjaxResult();
        result.data = sqlRunner.getRows(sql, dicParam);
        return result;
    }

    /**
     * 사업장별 메뉴 저장 (enabled=true인 것만 tenant_menu에 등록)
     */
    @PostMapping("/save")
    @Transactional
    public AjaxResult saveTenantMenu(
            @RequestParam("cbospjangcd") String spjangcd,
            @RequestParam("Q") String strItems,
            Authentication auth) {

        AjaxResult result = new AjaxResult();

        // 해당 사업장 기존 tenant_menu 전체 삭제
        MapSqlParameterSource delParam = new MapSqlParameterSource("spjangcd", spjangcd);
        sqlRunner.execute("delete from tenant_menu where spjangcd = :spjangcd", delParam);

        // enabled인 메뉴만 insert
        List<Map<String, Object>> items = CommonUtil.loadJsonListMap(strItems);
        for (Map<String, Object> item : items) {
            Boolean enabled = Boolean.parseBoolean(String.valueOf(item.get("enabled")));
            if (!enabled) continue;

            String menuCode = (String) item.get("menu_code");
            if (menuCode == null || menuCode.isBlank()) continue;

            String sql = """
                insert into tenant_menu (spjangcd, menu_code)
                values (:spjangcd, :menu_code)
                on conflict (spjangcd, menu_code) do nothing
            """;
            MapSqlParameterSource param = new MapSqlParameterSource();
            param.addValue("spjangcd", spjangcd);
            param.addValue("menu_code", menuCode);
            sqlRunner.execute(sql, param);
        }

        // tenant_menu에서 빠진 메뉴는 user_group_menu에서도 삭제
        String cleanupSql = """
            delete from user_group_menu
            where spjangcd = :spjangcd
            and "MenuCode" not in (
                select menu_code from tenant_menu where spjangcd = :spjangcd
            )
        """;
        sqlRunner.execute(cleanupSql, delParam);

        return result;
    }
}
