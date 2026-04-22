package mes.app.account_management.service;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class SlipStatusService {

	@Autowired
	SqlRunner sqlRunner;

	public List<Map<String, Object>> getSlipList(String start, String end, String mssec, String sbuject) {
		String spjangcd = TenantContext.get();

		Map<String, String> bizInfo = getBizInfoBySpjangcd(spjangcd);
		String custcd = bizInfo.get("custcd");

		MapSqlParameterSource sqlParam = new MapSqlParameterSource();
		sqlParam.addValue("as_custcd",   custcd);
		sqlParam.addValue("as_spjangcd", spjangcd);
		sqlParam.addValue("as_frdate", start.replace("-", "").trim());
		sqlParam.addValue("as_todate", end.replace("-", "").trim());

		String sql = """
        SELECT A.custcd,
               A.spjangcd,
               STUFF(STUFF(A.spdate,5,0,'-'),8,0,'-') as spdate,
               A.spnum,
               CASE A.tiosec
									WHEN '1' THEN '세입'
									WHEN '2' THEN '세출'
									WHEN '3' THEN '대체'
									ELSE A.tiosec
							END AS tiosec,
               A.cashyn,
               A.busipur,
               CASE A.spoccu
									 WHEN 'AA' THEN '전표일반'
									 ELSE A.spoccu
							 END AS spoccu,
               A.remark,
               A.taxdate,
               A.taxnum,
               SUM(B.dramt) AS dramt,
               SUM(B.cramt) AS cramt,
               ISNULL(SUM(B.dramt), SUM(B.cramt)) AS amt,
               MIN(B.comnote) AS summy,
               A.subject,
               A.regdate,
               A.bsdate,
               A.bseccd,
               A.busicd,
               (SELECT businm FROM tb_x0002
                WHERE bsdate = A.bsdate AND bseccd = A.bseccd AND busicd = A.busicd) AS businm,
               MAX(A.setnum) AS setnum,
               CAST('0' AS CHAR(1)) AS prtchk,
               A.appdate,
               A.appperid,
               (SELECT pernm FROM TB_JA001
                WHERE perid = 'p' + A.appperid AND spjangcd = A.spjangcd) AS apppernm,
               A.appgubun,
               A.appnum,
               CASE A.fixflag
			            WHEN '0' THEN '미확정'
			            WHEN '1' THEN '확정'
			            ELSE A.fixflag
			        END AS fixflag,
               (SELECT TOP 1 it2nm FROM tb_x0004
                WHERE tiosec = MAX(B.tiosec) AND it2cd = MAX(B.it2cd)) AS it2nm,
               (SELECT cntname FROM TB_X0002_CNT
                WHERE bsdate = A.bsdate AND bseccd = A.bseccd AND busicd = A.busicd AND seq = A.busicd_cnt) AS busicd_cnt,
               (SELECT mssecnm FROM tb_x0005
                WHERE mssec = MIN(B.mssec)) AS mssec,
               (SELECT banknm FROM tb_aa040
                WHERE custcd = :as_custcd AND spjangcd = A.spjangcd AND bank + bankcd = MAX(B.bankcd)) AS banknm,
               (SELECT cltnm FROM tb_xclient
                WHERE cltcd = MAX(B.cltcd)) AS cltnm,
               (SELECT cardnm FROM tb_iz010
                WHERE custcd = :as_custcd AND cardnum = MAX(B.cardnum)) AS cardnm,
               (SELECT filename FROM TB_AA010ATCH
                WHERE spdate = 'AJ' + A.spdate + A.spnum + A.spjangcd) AS filepath
          FROM TB_AA009 A WITH (NOLOCK),
               TB_AA010 B WITH (NOLOCK)
         WHERE A.custcd   = B.custcd
           AND A.spjangcd = B.spjangcd
           AND A.spdate   = B.spdate
           AND A.spnum    = B.spnum
           AND A.custcd   = :as_custcd
           AND A.spjangcd = :as_spjangcd
           AND A.spdate   BETWEEN :as_frdate AND :as_todate
        """;

		// mssec 조건
		if (mssec != null && !mssec.trim().isEmpty()) {
			sql += " AND B.mssec LIKE :as_spoccu ";
			sqlParam.addValue("as_spoccu", mssec.trim());
		}

		// 제목 조건
		if (sbuject != null && !sbuject.trim().isEmpty()) {
			sql += " AND isnull(A.subject, '') LIKE '%' + :as_subject + '%' ";
			sqlParam.addValue("as_subject", sbuject.trim());
		}

		sql += """
         GROUP BY A.custcd, A.spjangcd, A.spdate, A.spnum, A.tiosec,
						A.cashyn, A.busipur, A.spoccu, A.remark, A.taxdate,
						A.taxnum, A.subject, A.regdate, A.bsdate, A.bseccd,
						A.busicd, A.appdate, A.appperid, A.appgubun, A.appnum,
						A.busicd_cnt, A.fixflag
        """;

//		log.info("전표입력 현황 read sql: {}, param: {}", sql, sqlParam.getValues());
		return sqlRunner.getRows(sql, sqlParam);
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

		Object saupnum = row.get("saupnum");
		Object custcd = row.get("custcd");
		Object spjangnm = row.get("spjangnm");

		result.put("saupnum", saupnum == null ? "" : String.valueOf(saupnum).trim());
		result.put("custcd", custcd == null ? "" : String.valueOf(custcd).trim());
		result.put("spjangnm", custcd == null ? "" : String.valueOf(spjangnm).trim());

		return result;
	}


}
