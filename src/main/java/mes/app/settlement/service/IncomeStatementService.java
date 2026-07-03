package mes.app.settlement.service;

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
public class IncomeStatementService { // 세입내역서

	@Autowired
	SqlRunner sqlRunner;

	private Map<String, String> getBizInfoBySpjangcd(String spjangcd) {
		MapSqlParameterSource param = new MapSqlParameterSource().addValue("spjangcd", spjangcd);
		String sql = """
        SELECT saupnum, custcd, spjangnm
        FROM tb_xa012
        WHERE spjangcd = :spjangcd
        """;
		Map<String, Object> row = sqlRunner.getRow(sql, param);
		Map<String, String> result = new HashMap<>();
		result.put("custcd", "");
		result.put("spjangnm", "");
		if (row == null || row.isEmpty()) return result;
		result.put("custcd",   row.get("custcd")   == null ? "" : String.valueOf(row.get("custcd")).trim());
		result.put("spjangnm", row.get("spjangnm") == null ? "" : String.valueOf(row.get("spjangnm")).trim());
		return result;
	}

	// ============================================================
	// 세입내역서 (PB w_bill_view 조회 이식)
	//   1) 세입 계정(5000~5599, 8100~8299) cramt
	//   2) 동일 계정의 dramt(취소분) → cramt에 -로 반영
	//   3) 전년이월 + 당기 세입 집계(전표번호 없는 요약행)
	//   항 표기(businm/it1nm) 및 합계는 프론트에서 처리
	// ============================================================
	public Object searchList(String spdate1, String spdate2,
													 String mssec, String acccd, String it1cd, String it2cd, String flag) {
		String spjangcd = TenantContext.get();
		String custcd   = getBizInfoBySpjangcd(spjangcd).get("custcd");

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("custcd", custcd);
		param.addValue("spjangcd", spjangcd);
		param.addValue("spdate1", (spdate1 == null ? "" : spdate1.replace("-", "")));  // yyyyMM
		param.addValue("spdate2", (spdate2 == null ? "" : spdate2.replace("-", "")));  // yyyyMM
		param.addValue("mssec",  (mssec == null || mssec.trim().isEmpty()) ? "%" : mssec.trim());
		param.addValue("acccd",  (acccd == null) ? "" : acccd.trim());
		param.addValue("it1cd",  (it1cd == null) ? "" : it1cd.trim());
		param.addValue("it2cd",  (it2cd == null) ? "" : it2cd.trim());
		param.addValue("flag",   (flag == null || flag.trim().isEmpty()) ? "0" : flag.trim());

		String sql = """
        SELECT B.acccd,
               B.accnm,
               B.it1cd,
               B.it2cd,
               B.summy,
               B.dramt,
               B.cramt,
               CAST(:mssec AS CHAR(30)) AS mssecnm,
               CAST(:spdate1 AS CHAR(6)) + CAST(:spdate2 AS CHAR(6)) AS yyyy,
               B.mssec,
               (SELECT mssecnm FROM tb_x0005 WHERE mssec = B.mssec) AS re_mssecnm,
               (SELECT businm FROM tb_x0002 WHERE bsdate = A.bsdate AND bseccd = A.bseccd AND busicd = A.busicd) AS businm,
               (SELECT it1nm FROM TB_X0003 WHERE it1cd = Substring(B.it1cd,3,3) AND tiosec = '1') AS it1nm,
               CAST(:flag AS CHAR(1)) AS flag,
               A.bsdate + '.' + A.bseccd + '.' + A.busicd AS code,
               A.spdate,
               A.spnum
          FROM TB_AA009 A, TB_AA010 B
         WHERE A.custcd   = B.custcd
           AND A.spjangcd = B.spjangcd
           AND A.spdate   = B.spdate
           AND A.spnum    = B.spnum
           AND ( (B.acccd BETWEEN '5000' AND '5599') OR (B.acccd BETWEEN '8100' AND '8299') )
           AND A.custcd   = :custcd
           AND A.spjangcd = :spjangcd
           AND Substring(A.spdate,1,6) BETWEEN :spdate1 AND :spdate2
           AND IsNull(B.mssec,'') LIKE :mssec
           AND B.acccd LIKE :acccd + '%'
           AND B.it1cd LIKE :it1cd + '%'
           AND B.it2cd LIKE :it2cd + '%'
           AND B.cramt <> 0
           AND (B.iwolflag <> '1' OR B.iwolflag IS NULL)

        UNION ALL

        SELECT B.acccd,
               B.accnm,
               B.it1cd,
               B.it2cd,
               B.summy,
               B.dramt,
               B.dramt * -1 AS cramt,
               CAST(:mssec AS CHAR(30)) AS mssecnm,
               CAST(:spdate1 AS CHAR(6)) + CAST(:spdate2 AS CHAR(6)) AS yyyy,
               B.mssec,
               (SELECT mssecnm FROM tb_x0005 WHERE mssec = B.mssec) AS re_mssecnm,
               (SELECT businm FROM tb_x0002 WHERE bsdate = A.bsdate AND bseccd = A.bseccd AND busicd = A.busicd) AS businm,
               (SELECT it1nm FROM TB_X0003 WHERE it1cd = Substring(B.it1cd,3,3) AND tiosec = '1') AS it1nm,
               CAST(:flag AS CHAR(1)) AS flag,
               A.bsdate + '.' + A.bseccd + '.' + A.busicd AS code,
               A.spdate,
               A.spnum
          FROM TB_AA009 A, TB_AA010 B
         WHERE A.custcd   = B.custcd
           AND A.spjangcd = B.spjangcd
           AND A.spdate   = B.spdate
           AND A.spnum    = B.spnum
           AND ( (B.acccd BETWEEN '5000' AND '5599') OR (B.acccd BETWEEN '8100' AND '8299') )
           AND A.custcd   = :custcd
           AND A.spjangcd = :spjangcd
           AND Substring(A.spdate,1,6) BETWEEN :spdate1 AND :spdate2
           AND IsNull(B.mssec,'') LIKE :mssec
           AND B.acccd LIKE :acccd + '%'
           AND B.it1cd LIKE :it1cd + '%'
           AND B.it2cd LIKE :it2cd + '%'
           AND B.dramt <> 0
           AND (B.iwolflag <> '1' OR B.iwolflag IS NULL)

        UNION ALL

        SELECT A.acccd,
               A.accnm,
               '' AS it1cd,
               '' AS it2cd,
               A.it1nm AS summy,
               0 AS dramt,
               CASE WHEN A.drcr = '1' THEN SUM(A.ndramt) - SUM(A.ncramt) ELSE SUM(A.ncramt) - SUM(A.ndramt) END AS cramt,
               CAST(:mssec AS CHAR(30)) AS mssecnm,
               CAST(:spdate1 AS CHAR(6)) + CAST(:spdate2 AS CHAR(6)) AS yyyy,
               A.mssec,
               (SELECT mssecnm FROM tb_x0005 WHERE mssec = A.mssec) AS re_mssecnm,
               '' AS businm,
               A.it1nm AS it1nm,
               CAST(:flag AS CHAR(1)) AS flag,
               A.bsdate + '.' + A.bseccd + '.' + A.busicd AS code,
               '' AS spdate,
               '' AS spnum
          FROM (
            SELECT A.custcd AS custcd,
                   A.acccd  AS acccd,
                   A.accnm  AS accnm,
                   A.drcr   AS drcr,
                   SUM(IsNull(B.dramt,0)) AS ndramt,
                   SUM(IsNull(B.cramt,0)) AS ncramt,
                   '전년이월' AS it1nm,
                   '' AS mssec, '' AS bsdate, '' AS bseccd, '' AS busicd
              FROM TB_AC001 A, TB_AB001 B
             WHERE A.custcd = B.custcd
               AND A.acccd  = B.acccd
               AND B.custcd   = :custcd
               AND B.spjangcd = :spjangcd
               AND A.dcpl   = '1'
               AND A.ipflag = '1'
               AND B.yymmdd = Left(:spdate1,4) + '0000'
             GROUP BY A.custcd, A.acccd, A.accnm, A.drcr

            UNION ALL

            SELECT A.custcd,
                   B.acccd,
                   B.accnm AS accnm,
                   C.drcr,
                   B.dramt AS ndramt,
                   0 AS ncramt,
                   B.summy AS it1nm,
                   B.mssec, A.bsdate, A.bseccd, A.busicd
              FROM TB_AA009 A, TB_AA010 B, TB_AC001 C
             WHERE A.custcd   = B.custcd
               AND A.spjangcd = B.spjangcd
               AND A.spdate   = B.spdate
               AND A.spnum    = B.spnum
               AND A.custcd   = C.custcd
               AND B.acccd    = C.acccd
               AND C.ipflag   = '1'
               AND A.custcd   = :custcd
               AND A.spjangcd = :spjangcd
               AND Substring(A.spdate,1,6) BETWEEN :spdate1 AND :spdate2
               AND IsNull(B.mssec,'') LIKE :mssec
               AND B.bumuncd <> 'ZZ'
               AND B.dramt <> 0
               AND (B.iwolflag <> '1' OR B.iwolflag IS NULL)
          ) A
         GROUP BY A.custcd, A.acccd, A.accnm, A.drcr, A.it1nm, A.mssec, A.bsdate, A.bseccd, A.busicd
        """;
		return sqlRunner.getRows(sql, param);
	}

	// ============================================================
	// 전표 팝업 : 헤더(TB_AA009) + 분개(TB_AA010)
	// ============================================================
	public Object selectSlip(String spdate, String spnum) {
		String spjangcd = TenantContext.get();
		String custcd   = getBizInfoBySpjangcd(spjangcd).get("custcd");

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("custcd", custcd);
		param.addValue("spjangcd", spjangcd);
		param.addValue("spdate", spdate);
		param.addValue("spnum", spnum);

		String headSql = """
        SELECT A.custcd, A.spjangcd, A.spdate, A.spnum, A.tiosec, A.cashyn,
               A.busipur,
               CASE A.busipur WHEN '1' THEN '고유목적' WHEN '2' THEN '수익' WHEN '3' THEN '공통' ELSE '' END AS busipurnm,
               A.spoccu,
               CASE A.spoccu
                    WHEN 'AA' THEN '전표일반' WHEN 'I1' THEN '매출세금계산서' WHEN 'I2' THEN '매입세금계산서'
                    WHEN 'I3' THEN '매출계산서' WHEN 'I4' THEN '매입계산서' WHEN 'I5' THEN '매출카드'
                    WHEN 'I6' THEN '매입카드' WHEN 'I7' THEN '매출기타' WHEN 'I8' THEN '기타원천징수' ELSE '' END AS spoccunm,
               A.remark, A.subject,
               STUFF(STUFF(A.regdate,5,0,'-'),8,0,'-') AS regdate,
               A.bsdate, A.bseccd, A.busicd,
               (SELECT businm FROM tb_x0002 WHERE bsdate = A.bsdate AND bseccd = A.bseccd AND busicd = A.busicd) AS businm,
               A.spjangnm, A.inputdate
          FROM TB_AA009 A WITH (NOLOCK)
         WHERE A.custcd = :custcd AND A.spjangcd = :spjangcd
           AND A.spdate = :spdate AND A.spnum = :spnum
        """;
		Map<String, Object> head = sqlRunner.getRow(headSql, param);

		String lineSql = """
        SELECT A.custcd, A.spjangcd, A.spdate, A.spnum, A.spseq,
               A.acccd, A.accnm, A.drcr, A.dramt, A.cramt, A.summy,
               A.cltcd,
               (SELECT cltnm FROM tb_xclient WHERE cltcd = A.cltcd) AS cltnm,
               A.it1cd,
               (SELECT it1nm FROM TB_X0003 WHERE '00' + it1cd = A.it1cd AND tiosec = A.tiosec) AS it1nm,
               A.it2cd,
               (SELECT it2nm FROM TB_X0004 WHERE it2cd = A.it2cd AND tiosec = A.tiosec) AS it2nm,
               A.tiosec, A.mssec,
               (SELECT mssecnm FROM tb_x0005 WHERE mssec = A.mssec) AS mssecnm,
               A.bankcd,
               (SELECT banknm FROM tb_aa040 WHERE custcd = :custcd AND spjangcd = A.spjangcd AND bank + bankcd = A.bankcd) AS banknm,
               (SELECT accnum FROM tb_aa040 WHERE custcd = :custcd AND spjangcd = A.spjangcd AND bank + bankcd = A.bankcd) AS accnum,
               A.cardnum,
               (SELECT cardnm FROM tb_iz010 WHERE custcd = :custcd AND spjangcd = A.spjangcd AND cardnum = A.cardnum) AS cardnm,
               CASE WHEN A.drcr = '1' THEN '차변' ELSE '대변' END AS drcrnm
          FROM TB_AA010 A WITH (NOLOCK)
         WHERE A.custcd = :custcd AND A.spjangcd = :spjangcd
           AND A.spdate = :spdate AND A.spnum = :spnum
         ORDER BY A.spdate, A.spnum, A.spseq
        """;
		List<Map<String, Object>> lines = sqlRunner.getRows(lineSql, param);

		Map<String, Object> result = new HashMap<>();
		result.put("head", head);
		result.put("lines", lines);
		return result;
	}
}