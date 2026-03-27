package mes.app.system.service;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.util.StringUtils;
import mes.domain.services.SqlRunner;

@Service
public class MenuSetupService {

	@Autowired
	SqlRunner sqlRunner;

	// 메뉴폴더 리스트 조회
	public List<Map<String, Object>> getFolderTreeList() {

		String sql = """
				with recursive m1 as(
                select id
                ,"Parent_id"
                ,"FolderName"
                ,"IconCSS"
                ,"_order"
                , 1 as lvl
            from menu_folder mf where "Parent_id" is null
            union all
            select m_sub.id
                ,m_sub."Parent_id"
                ,m_sub."FolderName"
                ,m_sub."IconCSS"
                ,m_sub."_order"
                ,m1.lvl + 1 as lvl
                from menu_folder m_sub
                inner join m1 on m1.id = m_sub."Parent_id"
            ) select id
              , coalesce("Parent_id", 0) as "Parent_id"
              ,"FolderName"
              ,"IconCSS"
              ,"_order"
             from m1
             order by _order
			""";

        List<Map<String, Object>> items = this.sqlRunner.getRows(sql, null);

        return items;
	}

	// 메뉴항목 리스트 조회
	public List<Map<String, Object>> getMenuList(Integer folder_id) {

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("folder_id", folder_id);

		String sql = """
				select mi."MenuCode"
		        , mi."MenuName"
		        , mi."Url"
		        , mi."MenuFolder_id"
		        , mi._order
		        from menu_item mi
		        inner join menu_folder mf on mf.id = mi."MenuFolder_id"
		        where mi."MenuFolder_id" = :folder_id
		        order by mi."_order"
			""";

        List<Map<String, Object>> items = this.sqlRunner.getRows(sql, paramMap);

        return items;
	}

	// 소스메뉴 리스트 조회 (menu_item.template이 설정된 항목 기준)
	public List<Map<String, Object>> getGuiUseList(String unset, String keyword) {

		MapSqlParameterSource paramMap = new MapSqlParameterSource();

		StringBuilder sql = new StringBuilder("""
				SELECT mi."MenuCode",
				       COALESCE(mi."MenuName", mi."MenuCode") AS "MenuName",
				       mi."MenuFolder_id",
				       COALESCE(mf."FolderName", '') AS "FolderName",
				       CASE WHEN mi."MenuFolder_id" IS NOT NULL THEN true ELSE false END AS exists
				FROM menu_item mi
				LEFT JOIN menu_folder mf ON mf.id = mi."MenuFolder_id"
				WHERE mi.template IS NOT NULL
				""");

		if (!StringUtils.isEmpty(keyword)) {
			sql.append(" AND (mi.\"MenuCode\" = :keyword OR mi.\"MenuName\" = :keyword)");
			paramMap.addValue("keyword", keyword);
		}

		if (!StringUtils.isEmpty(unset)) {
			sql.append(" AND mi.\"MenuFolder_id\" IS NULL");
		}

		sql.append(" ORDER BY mi.\"MenuName\"");

		return this.sqlRunner.getRows(sql.toString(), paramMap);
	}
}
