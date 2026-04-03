package mes.app.system.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mes.app.common.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.util.StringUtils;
import mes.domain.services.SqlRunner;

@Service
public class UserService {
	
	@Autowired
	@Qualifier("mainSqlRunner")
	SqlRunner mainSqlRunner;   // Main DB 전용 (auth_user, user_profile, user_group 등)

	@Autowired
	SqlRunner tenantSqlRunner; // 테넌트 DB 전용 (TB_XUSERS 등 사업장 DB 테이블)


	// 사용자 리스트 조회
	public List<Map<String, Object>> getUserList(boolean superUser, Integer group, String keyword, String username, Integer departId, String spjangcd){
		
		MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("group", group);
        dicParam.addValue("keyword", keyword);
        dicParam.addValue("username", username);
        dicParam.addValue("departId", departId);
				dicParam.addValue("spjangcd", spjangcd);
        
        String sql = """
			select au.id
			  , au.first_name
              , up."Name"
              , au.username as login_id
              , up."UserGroup_id"
              , au.email
              , au.tel
              , ug."Name" as group_name
              , up."Factory_id"
              , f."Name" as factory_name
              , up."Depart_id"
              , up.lang_code
              , au.is_active
              , to_char(au.date_joined ,'yyyy-mm-dd hh24:mi') as date_joined
              , au.spjangcd as spjangcd
              , au.personid as personid
              , p."Code" as personcode
            from auth_user au
            left join user_profile up on up."User_id" = au.id and up.spjangcd = au.db_key
            left join user_group ug on ug.id = up."UserGroup_id" and ug.spjangcd = up.spjangcd
            left join factory f on f.id = up."Factory_id" and f.spjangcd = up.spjangcd
            left join person p on p.id = au.personid
            where is_superuser = false
            AND au.db_key = :spjangcd
		    """;
        
        if (superUser != true) {
        	sql += "  and ug.\"Code\" <> 'dev' ";
        }
        
        if (group!=null){            	
            sql+= " and ug.\"id\" = :group ";
        }
        
        if (StringUtils.isEmpty(keyword)==false) {
        	sql += " and up.\"Name\" like concat('%%', :keyword, '%%') ";
        }
        
        if (StringUtils.isEmpty(username)==false) {
        	sql += " and au.\"username\" = :username ";
        }
        if (departId != null) {
        	sql += " and up.\"Depart_id\" = :departId ";
        }
        
        sql += "order by ug.\"Name\", up.\"Name\"";
        
        List<Map<String, Object>> items = this.mainSqlRunner.getRows(sql, dicParam);

        return items;
	}

	// 사용자 상세정보 조회
	public Map<String, Object> getUserDetail(Integer id) {

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("id", id);

		String mainSql = """
        SELECT au.id
             , au.personid
             , up."Name"
             , au.username      AS login_id
             , au.email
             , au.tel
             , ug."Name"        AS group_name
             , up."UserGroup_id"
             , up."Factory_id"
             , f."Name"         AS factory_name
             , up.lang_code
             , au.is_active
             , to_char(au.date_joined, 'yyyy-mm-dd hh24:mi') AS date_joined
        FROM auth_user au
        LEFT JOIN user_profile up ON up."User_id" = au.id
        LEFT JOIN user_group   ug ON up."UserGroup_id" = ug.id
        LEFT JOIN factory       f ON up."Factory_id"   = f.id
        WHERE au.id = :id
        """;

		Map<String, Object> item = this.mainSqlRunner.getRow(mainSql, dicParam);

		if (item == null) return null;

		Object personIdObj = item.get("personid");

		if (personIdObj != null) {
			// ── 1단계: person 테이블에서 Code 조회 ──────────────────
			MapSqlParameterSource personParam = new MapSqlParameterSource();
			personParam.addValue("personid", Integer.valueOf(personIdObj.toString()));

			String personSql = """
            SELECT id, "Code", "Name" AS person_name
            FROM person
            WHERE id = :personid
            AND spjangcd = :spjangcd
        """;
			personParam.addValue("spjangcd", TenantContext.get());

			Map<String, Object> personInfo = this.tenantSqlRunner.getRow(personSql, personParam);

			if (personInfo != null) {
				String personCode = personInfo.get("Code") != null
															? personInfo.get("Code").toString()
															: null;

				item.put("person_code", personCode);
				item.put("person_name", personInfo.get("person_name"));

				// ── 2단계: person.Code → tb_ja001.perid 로 테넌트 조회 ──
				if (personCode != null) {
					MapSqlParameterSource tenantParam = new MapSqlParameterSource();
					tenantParam.addValue("perid", personCode);

					String tenantSql = """
                    SELECT ja.perid as person_code
                         , ja.pernm  AS personname
                         , ja.telnum AS tel
                         , jc.divicd
                         , jc.divinm AS dept_name
                    FROM tb_ja001 ja
                    LEFT JOIN tb_jc002 jc ON jc.divicd = ja.divicd
                    WHERE ja.perid = :perid
                    """;

					Map<String, Object> tenantInfo = this.tenantSqlRunner.getRow(tenantSql, tenantParam);

					if (tenantInfo != null) {
						item.put("personname", tenantInfo.get("personname"));
						item.put("tel",        tenantInfo.get("tel"));
						item.put("dept_name",  tenantInfo.get("dept_name"));
						item.put("divicd",     tenantInfo.get("divicd"));
					}
				}
			}
		}

		return item;
	}
	
	// 사용자 그룹 조회
	public List<Map<String, Object>> getUserGrpList(Integer id) {
		String spjangcd = TenantContext.getDbKey();
		MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("id", id);
		dicParam.addValue("spjangcd", spjangcd);
        String sql = """
        		select ug.id as grp_id
	            , ug."Name" as grp_name
	            ,rd."Char1" as grp_check
	            from user_group ug 
	            left join rela_data rd on rd."DataPk2" = ug.id 
	            and "RelationName" = 'auth_user-user_group' 
	            and rd."DataPk1" = :id
	            where coalesce(ug."Code",'') <> 'dev'
	            and ug.spjangcd = :spjangcd
        		""";
        
        List<Map<String, Object>> items = this.mainSqlRunner.getRows(sql, dicParam);
        return items;
	}

	public List<Map<String, Object>> getPSearchitem(String code, String name, String spjangcd) {
		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("code", code);
		dicParam.addValue("name", name);
		dicParam.addValue("spjangcd", spjangcd);

		String sql = """
			select
				a.perid as code,
				a.pernm as name,
				b.divicd as dept_id,
				b.divinm as dept_name
				from tb_ja001 a
				INNER JOIN tb_jc002 b ON a.divicd = b.divicd and a.spjangcd = b.spjangcd and a.custcd = b.custcd
				where a.spjangcd = :spjangcd
				and a.perid like concat('%', :code, '%') AND a.pernm like concat('%',:name,'%')
      """;

		List<Map<String, Object>> items = this.tenantSqlRunner.getRows(sql, dicParam);
		return items;
	}

	// 테넌트 DB의 TB_XUSERS에서 직원 목록 조회 (사번 선택 팝업용)
	public List<Map<String, Object>> getXusersList(String perid, String pernm) {
		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("perid", "%" + (perid == null ? "" : perid) + "%");
		dicParam.addValue("pernm", "%" + (pernm == null ? "" : pernm) + "%");

		String sql = """
			SELECT userid, perid, pernm, spjangcd, useyn
			FROM TB_XUSERS
			WHERE perid LIKE :perid
			  AND pernm LIKE :pernm
			ORDER BY perid
			""";

		return this.tenantSqlRunner.getRows(sql, dicParam);
	}

}
