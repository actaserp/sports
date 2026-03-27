package mes.app.definition.service;

import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.util.StringUtils;
import mes.domain.services.SqlRunner;

@Slf4j
@Service
public class CompanyService {

	@Autowired
	SqlRunner sqlRunner;

	//업체 목록 조회
	public List<Map<String, Object>> getCompnayList(String compType, String keyword) {

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("comp_type", compType);
		paramMap.addValue("keyword", keyword);

		String sql = """
				select
				cltcd ,
				custcd,
				cltnm,
        clttype,
        CASE clttype
						WHEN '1' THEN '매입처'
						WHEN '2' THEN '매출처'
						WHEN '3' THEN '공통'
						ELSE '기타'
				END AS clttype_name,
				saupnum ,
				prenm as ceo_name,
				clttype,
				telnum,
				zipcd,
				cltadres,
				relyn,
				perid,
				agnernm,
				prenum,
				faxnum,
				biztypenm,
				bizitem,
				agnernm,
				agntel,
				taxmail,
				taxpernm,
				taxtelnum,
				remarks
				from TB_XCLIENT
				where 1 = 1 
				
			""";
		if (compType != null && !compType.isBlank()) {
			sql += """
				and clttype = :comp_type
			""";
		}

		if (StringUtils.isEmpty(keyword)==false) sql+="and upper(cltnm) like concat('%%',upper(:keyword),'%%')";

		sql += "order by cltcd desc";

		List<Map<String,Object>> items = this.sqlRunner.getRows(sql, paramMap);

//		log.info("업체정보 l;ist 데이터 SQL: {}", sql);
//    log.info("SQL Parameters: {}", paramMap.getValues());
		return items;
	}

	// 업체 상세정보 조회
	public Map<String, Object> getCompanyDetail(int companyId) {

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("company_id", companyId);

		String sql = """
				select 
				cltcd as comp_code,
				cltnm as name,
				prenm as ceo_name,
				saupnum,
				biztypenm  as business_type,
				bizitemnm as business_item,
				prenum as comp_code2,
				telnum as tel_number,
				faxnum as fax_number,
				taxmail as email,
				zipcd as zip_code,
				cltadres as placeAddress,
				relyn ,
				remarks as description 
				from TB_XCLIENT
				where cltcd = :company_id
			""";

		Map<String, Object> item = this.sqlRunner.getRow(sql, paramMap);

		return item;
	}

	// 업체 단가정보
	public List<Map<String, Object>> getPriceListByCompany(int companyId) {

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("comp_id", companyId);

		String sql = """
			with A as 
            (
                select mcu.id 
                , mcu."Material_id" 
                , mcu."UnitPrice" 
                , mcu."FormerUnitPrice" 
                , mcu."ApplyStartDate"
                , mcu."ApplyEndDate"
                , mcu."ChangeDate"
                , mcu."ChangerName" 
                , row_number() over (partition by mcu."Company_id" order by mcu."ApplyStartDate" desc) as g_idx
                , now() between mcu."ApplyStartDate" and mcu."ApplyEndDate" as current_check
                , now() < mcu."ApplyStartDate" as future_check
                from mat_comp_uprice mcu 
                where mcu."Company_id" = :comp_id
            )
            select A.id as mcu_id
            , A."Material_id" as mat_id
            , fn_code_name('mat_type', mg."MaterialType") as mat_type_name
            , mg."Name" as mat_grp_name
            , m."Code" as mat_code
            , m."Name" as mat_name
            , u."Name" as unit_name
            , A."UnitPrice" as unit_price
            , A."FormerUnitPrice" as former_unit_price
            , A."ApplyStartDate"::date as apply_start_date
            , A."ApplyEndDate"::date as apply_end_date
            , A."ChangeDate" as change_date
            , A."ChangerName" as changer_name 
            from A 
            inner join material m on m.id = A."Material_id"
            left join mat_grp mg on mg.id = m."MaterialGroup_id"
            left join unit u on u.id = m."Unit_id"
            where ( A.current_check = true or A.future_check = true or A.g_idx = 1)
            order by m."Name", A."ApplyStartDate" 
			""";

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, paramMap);

		return items;
	}

	// 단가 상세 조회
	public Map<String, Object> getMaterialPriceDetail(int priceId) {

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("price_id", priceId);

		String sql = """
			select mcu.id as price_id
			, m."MaterialGroup_id"
            , mcu."Material_id" 
            , mcu."Company_id" 
            , mcu."UnitPrice"
            , "FormerUnitPrice"
            , to_char(mcu."ApplyStartDate", 'yyyy-mm-dd') as "ApplyStartDate"
            , to_char(mcu."ApplyEndDate", 'yyyy-mm-dd') as "ApplyEndDate"
            from mat_comp_uprice mcu 
            inner join material m on m.id = mcu."Material_id" 
            where 1 = 1
            and mcu.id = :price_id
			""";

		Map<String, Object> item = this.sqlRunner.getRow(sql, dicParam);

		return item;
	}


	// 품목별 단가 히스토리 리스트 조회
	public List<Map<String, Object>> getPriceHistoryByComp(int companyId) {

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("comp_id", companyId);

		String sql = """
			select mcu.id 
            , mcu."Material_id" as mat_id
            , fn_code_name('mat_type', mg."MaterialType") as mat_type_name
            , mg."Name" as mat_grp_name
            , m."Code" as mat_code
            , m."Name" as mat_name
            , u."Name" as unit_name
            , mcu."UnitPrice" as unit_price
            , mcu."FormerUnitPrice" as former_unit_price
            , mcu."ApplyStartDate"::date as apply_start_date
            , mcu."ApplyEndDate"::date as apply_end_date
            , mcu."ChangeDate" as change_date
            , mcu."ChangerName" as changer_name 
            from mat_comp_uprice mcu 
            inner join material m on m.id = mcu."Material_id"
            left join mat_grp mg on mg.id = m."MaterialGroup_id"
            left join unit u on u.id = m."Unit_id"
            where 1=1
            and mcu."Company_id" = :comp_id
            order by m."Name", mcu."ApplyStartDate" desc 
			""";

		List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);

		return items;
	}
}
