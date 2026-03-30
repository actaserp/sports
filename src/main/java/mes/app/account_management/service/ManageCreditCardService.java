package mes.app.account_management.service;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ManageCreditCardService {

	@Autowired
	SqlRunner sqlRunner;

	public List<Map<String, Object>> getList(String txtcardnm, String txtcardnum) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("txtcardnm", "%" + txtcardnm + "%");	//카드명
		param.addValue("txtcardnum", "%" + txtcardnum + "%");	//카드번호
		String tenantId = TenantContext.get();
		param.addValue("spjangcd", tenantId);

		String sql = """
			select
				a.cardnum,
				a.cardnm,
				a.cardperson as cdpernm,
				a.cardnm,
				a.cardco,
				a.cardclafi ,
				a.isudate,
				a.expedate,
				a.cdflag as baroflag,
				a.stldate,
				a.useyn,
				a.baroid,
				a.remark,
				a.stlbank,
				b.accnum ,
				c.banknm,
				a.cardid as cardwebid,
			 	a.cardpw as cardwebpw,
				a.baroid,
				d.cdcode as barocd
				from tb_iz010 a
				left join tb_aa040 b on  a.stlbanknm =b.accnum
				join tb_xbank c on b.bank = c.bankcd
				join tb_xcard d on a.cardco = d.cd 
				where 1=1 and a.spjangcd =:spjangcd
			""";

		if(txtcardnm != null || !txtcardnm.isEmpty()){
			sql += """
       and cardnm like :txtcardnm
      """;
		}
		if(txtcardnum != null || !txtcardnum.isEmpty()){
			sql += """
						and cardnum like :txtcardnum
						""";
		}

		return sqlRunner.getRows(sql, param);

	}

	@Transactional
	public void save(Map<String, Object> param) {

		String spjangcd = TenantContext.get();
		Map<String, String> bizInfo = getBizInfoBySpjangcd(spjangcd);
		String custcd = bizInfo.get("custcd");

		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		dicParam.addValue("custcd", custcd);
		dicParam.addValue("spjangcd", spjangcd);
		dicParam.addValue("cardnum", (String) param.get("cardnum"));
		dicParam.addValue("cardnm", (String) param.get("cardnm"));
		dicParam.addValue("cardco", (String) param.get("cardco"));
		dicParam.addValue("cardclafi", (String) param.get("cardType"));
		dicParam.addValue("isudate", (String) param.get("regAsName"));
		dicParam.addValue("expedate", (String) param.get("expdate"));
		dicParam.addValue("stlbank", (String) param.get("bankid"));
		dicParam.addValue("useyn", (String) param.get("useYn"));
		dicParam.addValue("cdflag", (String) param.get("baroflag"));
		dicParam.addValue("stlbanknm", (String) param.get("ACCNUM"));
		dicParam.addValue("remark", (String) param.get("remark"));
		dicParam.addValue("cardperson", (String) param.get("cdpernm"));
		dicParam.addValue("cardperid", (String) param.get("cdperid"));
		dicParam.addValue("cardid", (String) param.get("cardwebid"));
		dicParam.addValue("cardpw", (String) param.get("cardwebpw"));
		dicParam.addValue("baroid", (String) param.get("baroid"));

		// 기존 레코드 존재 여부 확인 (PK: custcd + spjangcd + cardnum)
		String checkSql = """
        SELECT COUNT(*)
        FROM tb_iz010
        WHERE custcd   = :custcd
          AND spjangcd = :spjangcd
          AND cardnum  = :cardnum
        """;

		List<Map<String, Object>> checkResult = this.sqlRunner.getRows(checkSql, dicParam);
		int count = ((Number) checkResult.get(0).get("cnt")).intValue();

		if (count > 0) {
			// UPDATE
			String updateSql = """
            UPDATE tb_iz010
            SET
                cardnm     = :cardnm,
                cardco     = :cardco,
                cardclafi  = :cardclafi,
                isudate    = :isudate,
                expedate   = :expedate,
                stlbank    = :stlbank,
                useyn      = :useyn,
                cdflag     = :cdflag,
                stlbanknm  = :stlbanknm,
                remark     = :remark,
                cardperson = :cardperson,
                cardperid  = :cardperid,
                cardid     = :cardid,
                cardpw     = :cardpw,
                baroid     = :baroid
            WHERE custcd   = :custcd
              AND spjangcd = :spjangcd
              AND cardnum  = :cardnum
            """;
			this.sqlRunner.execute(updateSql, dicParam);

		} else {
			// INSERT
			String insertSql = """
            INSERT INTO tb_iz010 (
                custcd, spjangcd, cardnum,
                cardnm, cardco, cardclafi,
                isudate, expedate, stlbank,
                useyn, cdflag, stlbanknm,
                remark, cardperson, cardperid,
                cardid, cardpw, baroid
            ) VALUES (
                :custcd, :spjangcd, :cardnum,
                :cardnm, :cardco, :cardclafi,
                :isudate, :expedate, :stlbank,
                :useyn, :cdflag, :stlbanknm,
                :remark, :cardperson, :cardperid,
                :cardid, :cardpw, :baroid
            )
            """;
			this.sqlRunner.execute(insertSql, dicParam);
		}
	}

	private Map<String, String> getBizInfoBySpjangcd(String spjangcd) {
		MapSqlParameterSource sqlParam = new MapSqlParameterSource();
		sqlParam.addValue("spjangcd", spjangcd);

		String sql = """
        select saupnum, custcd
        from tb_xa012
        where spjangcd = :spjangcd
    """;

		Map<String, Object> row = sqlRunner.getRow(sql, sqlParam);

		Map<String, String> result = new HashMap<>();
		result.put("saupnum", "");
		result.put("custcd", "");

		if (row == null || row.isEmpty()) {
			return result;
		}

		Object saupnum = row.get("saupnum");
		Object custcd = row.get("custcd");

		result.put("saupnum", saupnum == null ? "" : String.valueOf(saupnum).trim());
		result.put("custcd", custcd == null ? "" : String.valueOf(custcd).trim());

		return result;
	}



}
