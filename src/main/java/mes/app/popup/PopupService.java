package mes.app.popup;

import lombok.extern.slf4j.Slf4j;
import mes.app.util.UtilClass;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class PopupService {

    @Autowired
    SqlRunner sqlRunner;

	public Object getCltCombineList(String spjangcd, String item, String item2) {
		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("spjangcd", spjangcd);
		String sql = """
			WITH A AS (	select cltcd as id
				, '0' as cltflag
				, '업체' as cltflagnm
			    , cltcd as item
			    , cltnm as item2
			    from tb_xclient
			    WHERE (useyn = '0' OR useyn IS NULL)
			    union all 
			select
			  a.perid
			  , '1' as cltflag
			  , '직원정보' as cltflagnm
			  ,a.entdate as item
			  ,a.pernm as item2
			  from tb_ja001 a
			  left join tb_jc002 d on d.divicd = a.divicd
			  where rtclafi = '001'
			  and a.spjangcd = :spjangcd
			  union all	
			  select 
			a.bankcd as id
			,'2' as cltflag
			, '은행계좌' as cltflagnm
			,a.accnum as item
			,a.banknm as item2
			from
			tb_aa040 a -- 계좌 테이블
			left join tb_xbank b on b.bankcd = a.bank
			where a.spjangcd = :spjangcd
					union all
					select 		
					cardnum as id
					,'3' as cltflag
					, '카드사' as cltflagnm
					,cardnum as item
					,cardnm as item2
					from TB_IZ010
					where useyn = '1'
					and spjangcd = :spjangcd
					)
				SELECT * FROM A
				where 1=1
			""";
		List<Map<String, Object>> rows = sqlRunner.getRows(sql, param);
		return rows;
	}
}
