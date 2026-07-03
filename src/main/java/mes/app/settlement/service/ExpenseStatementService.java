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
public class ExpenseStatementService { // 세출내역서

	@Autowired
	SqlRunner sqlRunner;

	private Map<String, String> getBizInfoBySpjangcd(String spjangcd) {
		MapSqlParameterSource param = new MapSqlParameterSource().addValue("spjangcd", spjangcd);
		String sql = """
        SELECT saupnum, custcd, spjangnm
        FROM tb_xa012 WHERE spjangcd = :spjangcd
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

	private MapSqlParameterSource baseParam(String spdate1, String spdate2,
																					String mssec, String acccd, String it1cd, String it2cd, String flag) {
		String spjangcd = TenantContext.get();
		String custcd   = getBizInfoBySpjangcd(spjangcd).get("custcd");
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("custcd", custcd);
		p.addValue("spjangcd", spjangcd);
		p.addValue("spdate1", (spdate1 == null ? "" : spdate1.replace("-", "")));  // yyyyMM
		p.addValue("spdate2", (spdate2 == null ? "" : spdate2.replace("-", "")));  // yyyyMM
		p.addValue("mssec",  (mssec == null || mssec.trim().isEmpty()) ? "%" : mssec.trim());
		p.addValue("acccd",  (acccd == null) ? "" : acccd.trim());
		p.addValue("it1cd",  (it1cd == null) ? "" : it1cd.trim());
		p.addValue("it2cd",  (it2cd == null) ? "" : it2cd.trim());
		p.addValue("flag",   (flag == null || flag.trim().isEmpty()) ? "0" : flag.trim());
		return p;
	}

	// ============================================================
	// 탭1 : 관별내역서 (PB w_bill_view 탭1 이식)
	//   세출 계정(7000~7999, 8300~8599) dramt
	//   취소분(cramt) → dramt에 -로 반영
	//   전년이월/당기 세출 집계행
	//   항 표기·소계/합계/총계는 프론트에서 처리 (spdate/spnum은 전표팝업용으로 추가)
	// ============================================================
	public Object searchTab1(String spdate1, String spdate2,
													 String mssec, String acccd, String it1cd, String it2cd, String flag) {
		MapSqlParameterSource param = baseParam(spdate1, spdate2, mssec, acccd, it1cd, it2cd, flag);

		String sql = """
        SELECT B.acccd, B.accnm, B.it1cd, B.it2cd, B.summy,
               B.dramt, B.cramt, C.seqno, B.spdate, B.spnum,
               CAST(:mssec AS CHAR(30)) AS mssecnm,
               CAST(:spdate1 AS CHAR(6)) AS yyyy,
               CAST(:spdate2 AS CHAR(6)) AS yyyy2,
               (SELECT businm FROM tb_x0002 WHERE bsdate = A.bsdate AND bseccd = A.bseccd AND busicd = A.busicd) AS businm,
               (SELECT it1nm FROM TB_X0003 WHERE it1cd = Substring(B.it1cd,3,3) AND tiosec = '2') AS it1nm,
               CAST(:flag AS CHAR(1)) AS flag
          FROM TB_AA009 A, TB_AA010 B, TB_X0001 C
         WHERE A.custcd   = B.custcd
           AND A.spjangcd = B.spjangcd
           AND A.spdate   = B.spdate
           AND A.spnum    = B.spnum
           AND B.custcd   = C.custcd
           AND Substring(B.it1cd,1,2) = C.bseccd
           AND ( (B.acccd BETWEEN '7000' AND '7999') OR (B.acccd BETWEEN '8300' AND '8599') )
           AND A.custcd   = :custcd
           AND A.spjangcd = :spjangcd
           AND Substring(A.spdate,1,6) BETWEEN :spdate1 AND :spdate2
           AND IsNull(B.mssec,'') LIKE :mssec
           AND B.acccd LIKE :acccd + '%'
           AND B.it1cd LIKE :it1cd + '%'
           AND B.it2cd LIKE :it2cd + '%'
           AND (B.iwolflag <> '1' OR B.iwolflag IS NULL)

        UNION ALL

        SELECT B.acccd, B.accnm, B.it1cd, B.it2cd, B.summy,
               B.cramt * -1 AS dramt, B.dramt AS cramt, C.seqno, B.spdate, B.spnum,
               CAST(:mssec AS CHAR(30)) AS mssecnm,
               CAST(:spdate1 AS CHAR(6)) AS yyyy,
               CAST(:spdate2 AS CHAR(6)) AS yyyy2,
               (SELECT businm FROM tb_x0002 WHERE bsdate = A.bsdate AND bseccd = A.bseccd AND busicd = A.busicd) AS businm,
               (SELECT it1nm FROM TB_X0003 WHERE it1cd = Substring(B.it1cd,3,3) AND tiosec = '2') AS it1nm,
               CAST(:flag AS CHAR(1)) AS flag
          FROM TB_AA009 A, TB_AA010 B, TB_X0001 C
         WHERE A.custcd   = B.custcd
           AND A.spjangcd = B.spjangcd
           AND A.spdate   = B.spdate
           AND A.spnum    = B.spnum
           AND B.custcd   = C.custcd
           AND Substring(B.it1cd,1,2) = C.bseccd
           AND ( (B.acccd BETWEEN '7000' AND '7999') OR (B.acccd BETWEEN '8300' AND '8599') )
           AND A.custcd   = :custcd
           AND A.spjangcd = :spjangcd
           AND Substring(A.spdate,1,6) BETWEEN :spdate1 AND :spdate2
           AND IsNull(B.mssec,'') LIKE :mssec
           AND B.acccd LIKE :acccd + '%'
           AND B.it1cd LIKE :it1cd + '%'
           AND B.it2cd LIKE :it2cd + '%'
           AND (B.iwolflag <> '1' OR B.iwolflag IS NULL)

        UNION ALL

        SELECT A.acccd, A.accnm,
               '' AS it1cd, '' AS it2cd,
               A.it1nm AS summy,
               CASE WHEN A.drcr = '1' THEN SUM(A.ndramt) - SUM(A.ncramt) ELSE SUM(A.ncramt) - SUM(A.ndramt) END AS dramt,
               SUM(A.ncramt) AS cramt,
               '' AS seqno, A.spdate, '' AS spnum,
               CAST(:mssec AS CHAR(30)) AS mssecnm,
               CAST(:spdate1 AS CHAR(6)) AS yyyy,
               CAST(:spdate2 AS CHAR(6)) AS yyyy2,
               (SELECT businm FROM tb_x0002 WHERE bsdate = A.bsdate AND bseccd = A.bseccd AND busicd = A.busicd) AS businm,
               '' AS it1nm,
               CAST(:flag AS CHAR(1)) AS flag
          FROM (
            SELECT A.spdate, B.acccd, B.accnm AS accnm, C.drcr,
                   B.cramt AS ndramt, 0 AS ncramt,
                   B.summy AS it1nm, B.mssec, A.bsdate, A.bseccd, A.busicd
              FROM TB_AA009 A, TB_AA010 B, TB_AC001 C
             WHERE A.custcd   = B.custcd
               AND A.spjangcd = B.spjangcd
               AND A.spdate   = B.spdate
               AND A.spnum    = B.spnum
               AND A.custcd   = C.custcd
               AND B.acccd    = C.acccd
               AND C.chulflag = '1'
               AND A.custcd   = :custcd
               AND A.spjangcd = :spjangcd
               AND Substring(A.spdate,1,6) BETWEEN :spdate1 AND :spdate2
               AND IsNull(B.mssec,'') LIKE :mssec
               AND B.bumuncd <> 'ZZ'
               AND B.cramt <> 0
               AND (B.iwolflag <> '1' OR B.iwolflag IS NULL)
          ) A
         GROUP BY A.spdate, A.acccd, A.accnm, A.drcr, A.it1nm, A.mssec, A.bsdate, A.bseccd, A.busicd
        """;
		return sqlRunner.getRows(sql, param);
	}

	// ============================================================
	// 탭2/3/4 : 재원별현황 (PB w_bill_view 탭2 이식)
	//   세출 계정(7000~7999, 8300~8499) 분개 상세 + 재원(mssecnm)
	//   화면에서 관항목/관항/사업별 그룹으로 표시
	//   spdate1/spdate2 는 yyyyMM (Left(spdate,6) BETWEEN)
	// ============================================================
	public Object searchFund(String spdate1, String spdate2,
													 String mssec, String acccd, String it1cd, String it2cd) {
		MapSqlParameterSource param = baseParam(spdate1, spdate2, mssec, acccd, it1cd, it2cd, "0");
		param.addValue("yyyy1", param.getValue("spdate1"));
		param.addValue("yyyy2", param.getValue("spdate2"));

		String sql = """
        SELECT A.custcd, A.spdate, A.spnum, A.tiosec, A.acccd,
               (SELECT accnm FROM tb_ac001 WHERE acccd = A.acccd) AS accnm,
               A.it1cd,
               (SELECT it1nm FROM tb_x0003 WHERE '00' + it1cd = A.it1cd AND tiosec = A.tiosec) AS it1nm,
               A.it2cd,
               (SELECT it2nm FROM tb_x0004 WHERE it2cd = A.it2cd AND tiosec = A.tiosec) AS it2nm,
               A.summy, A.dramt,
               (SELECT dramt FROM TB_AA010 WHERE custcd = A.custcd AND spjangcd = A.spjangcd AND spdate = A.spdate AND spnum = A.spnum AND spseq = A.spseq) AS amt1,
               (SELECT dramt FROM TB_AA010 WHERE custcd = A.custcd AND spjangcd = A.spjangcd AND spdate = A.spdate AND spnum = A.spnum AND spseq = A.spseq AND acccd = A.acccd AND it1cd = A.it1cd AND it2cd = A.it2cd AND mssec = A.mssec) AS amt2,
               CAST(:yyyy1 AS CHAR(5)) + '년도' AS as_yyyy,
               CAST(:mssec AS CHAR(30)) AS mssecnm,
               A.mssec,
               (SELECT mssecnm FROM tb_x0005 WHERE mssec = A.mssec) AS re_mssecnm,
               C.bsdate, C.bseccd, C.busicd,
               (SELECT businm FROM tb_x0002 WHERE bsdate = C.bsdate AND bseccd = C.bseccd AND busicd = C.busicd) AS businm,
               C.bsdate + '.' + C.bseccd + '.' + C.busicd AS code
          FROM TB_AA010 A, TB_AA009 C
         WHERE A.spdate   = C.spdate
           AND A.spnum    = C.spnum
           AND A.spjangcd = C.spjangcd
           AND ( (A.acccd BETWEEN '7000' AND '7999') OR (A.acccd BETWEEN '8300' AND '8499') )
           AND A.custcd   = :custcd
           AND A.spjangcd = :spjangcd
           AND Left(A.spdate,6) BETWEEN :yyyy1 AND :yyyy2
           AND IsNull(A.mssec,'') LIKE :mssec
           AND A.acccd LIKE :acccd + '%'
           AND A.it1cd LIKE :it1cd + '%'
           AND A.it2cd LIKE :it2cd + '%'
           AND (A.iwolflag <> '1' OR A.iwolflag IS NULL)

        UNION ALL

        SELECT A.custcd, A.spdate, A.spnum, A.tiosec, A.acccd,
               (SELECT accnm FROM tb_ac001 WHERE acccd = A.acccd) AS accnm,
               A.it1cd,
               (SELECT it1nm FROM tb_x0003 WHERE '00' + it1cd = A.it1cd AND tiosec = A.tiosec) AS it1nm,
               A.it2cd,
               (SELECT it2nm FROM tb_x0004 WHERE it2cd = A.it2cd AND tiosec = A.tiosec) AS it2nm,
               A.summy, A.cramt * -1 AS dramt,
               (SELECT dramt FROM TB_AA010 WHERE custcd = A.custcd AND spjangcd = A.spjangcd AND spdate = A.spdate AND spnum = A.spnum AND spseq = A.spseq) AS amt1,
               (SELECT cramt * -1 FROM TB_AA010 WHERE custcd = A.custcd AND spjangcd = A.spjangcd AND spdate = A.spdate AND spnum = A.spnum AND spseq = A.spseq AND acccd = A.acccd AND it1cd = A.it1cd AND it2cd = A.it2cd AND mssec = A.mssec) AS amt2,
               CAST(:yyyy1 AS CHAR(5)) + '년도' AS as_yyyy,
               CAST(:mssec AS CHAR(30)) AS mssecnm,
               A.mssec,
               (SELECT mssecnm FROM tb_x0005 WHERE mssec = A.mssec) AS re_mssecnm,
               C.bsdate, C.bseccd, C.busicd,
               (SELECT businm FROM tb_x0002 WHERE bsdate = C.bsdate AND bseccd = C.bseccd AND busicd = C.busicd) AS businm,
               C.bsdate + '.' + C.bseccd + '.' + C.busicd AS code
          FROM TB_AA010 A, TB_AA009 C
         WHERE A.spdate   = C.spdate
           AND A.spnum    = C.spnum
           AND A.spjangcd = C.spjangcd
           AND ( (A.acccd BETWEEN '7000' AND '7999') OR (A.acccd BETWEEN '8300' AND '8499') )
           AND A.custcd   = :custcd
           AND A.spjangcd = :spjangcd
           AND Left(A.spdate,6) BETWEEN :yyyy1 AND :yyyy2
           AND IsNull(A.mssec,'') LIKE :mssec
           AND A.acccd LIKE :acccd + '%'
           AND A.it1cd LIKE :it1cd + '%'
           AND A.it2cd LIKE :it2cd + '%'
           AND (A.iwolflag <> '1' OR A.iwolflag IS NULL)
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

	// ============================================================
	// 탭3(관항) / 탭4(사업별) 공용 : 재원별현황
	//   세출 계정(7000~7999, 8300~8499) 분개 상세 + 재원(mssecnm)
	//   사업(bsdate/bseccd/busicd) 필터 + 사업 지정건만(LEN(busicd) > 0)
	//   ── 탭3, 탭4 쿼리가 동일하므로 이 메서드를 공유한다.
	//      탭3은 화면에서 관/항 그룹, 탭4는 사업명 그룹으로 표시.
	// ============================================================
	public Object searchFundBusi(String spdate1, String spdate2,
															 String mssec, String acccd, String it1cd, String it2cd,
															 String bsdate, String bseccd, String busicd) {
		MapSqlParameterSource param = baseParam(spdate1, spdate2, mssec, acccd, it1cd, it2cd, "0");
		param.addValue("yyyy1", param.getValue("spdate1"));
		param.addValue("yyyy2", param.getValue("spdate2"));
		param.addValue("bsdate", (bsdate == null) ? "" : bsdate.trim());
		param.addValue("bseccd", (bseccd == null) ? "" : bseccd.trim());
		param.addValue("busicd", (busicd == null) ? "" : busicd.trim());

		String sql = """
        SELECT A.custcd, A.spdate, A.spnum, A.tiosec, A.acccd,
               (SELECT accnm FROM tb_ac001 WHERE acccd = A.acccd) AS accnm,
               A.it1cd,
               (SELECT it1nm FROM tb_x0003 WHERE '00' + it1cd = A.it1cd AND tiosec = A.tiosec) AS it1nm,
               A.it2cd,
               (SELECT it2nm FROM tb_x0004 WHERE it2cd = A.it2cd AND tiosec = A.tiosec) AS it2nm,
               A.summy, A.dramt,
               CAST(:yyyy1 AS CHAR(5)) + '년도' AS as_yyyy,
               CAST(:mssec AS CHAR(30)) AS mssecnm,
               A.mssec,
               (SELECT mssecnm FROM tb_x0005 WHERE mssec = A.mssec) AS re_mssecnm,
               C.bsdate, C.bseccd, C.busicd,
               (SELECT businm FROM tb_x0002 WHERE bsdate = C.bsdate AND bseccd = C.bseccd AND busicd = C.busicd) AS businm,
               C.bsdate + '.' + C.bseccd + '.' + C.busicd AS code
          FROM TB_AA010 A, TB_AA009 C
         WHERE A.spdate   = C.spdate
           AND A.spnum    = C.spnum
           AND A.spjangcd = C.spjangcd
           AND ( (A.acccd BETWEEN '7000' AND '7999') OR (A.acccd BETWEEN '8300' AND '8499') )
           AND A.custcd   = :custcd
           AND A.spjangcd = :spjangcd
           AND Left(A.spdate,6) BETWEEN :yyyy1 AND :yyyy2
           AND IsNull(A.mssec,'') LIKE :mssec
           AND A.acccd  LIKE :acccd  + '%'
           AND A.it1cd  LIKE :it1cd  + '%'
           AND A.it2cd  LIKE :it2cd  + '%'
           AND C.bsdate LIKE :bsdate + '%'
           AND C.bseccd LIKE :bseccd + '%'
           AND C.busicd LIKE :busicd + '%'
           AND (A.iwolflag <> '1' OR A.iwolflag IS NULL)
           AND LEN(C.busicd) > 0

        UNION ALL

        SELECT A.custcd, A.spdate, A.spnum, A.tiosec, A.acccd,
               (SELECT accnm FROM tb_ac001 WHERE acccd = A.acccd) AS accnm,
               A.it1cd,
               (SELECT it1nm FROM tb_x0003 WHERE '00' + it1cd = A.it1cd AND tiosec = A.tiosec) AS it1nm,
               A.it2cd,
               (SELECT it2nm FROM tb_x0004 WHERE it2cd = A.it2cd AND tiosec = A.tiosec) AS it2nm,
               A.summy, A.cramt * -1 AS dramt,
               CAST(:yyyy1 AS CHAR(5)) + '년도' AS as_yyyy,
               CAST(:mssec AS CHAR(30)) AS mssecnm,
               A.mssec,
               (SELECT mssecnm FROM tb_x0005 WHERE mssec = A.mssec) AS re_mssecnm,
               C.bsdate, C.bseccd, C.busicd,
               (SELECT businm FROM tb_x0002 WHERE bsdate = C.bsdate AND bseccd = C.bseccd AND busicd = C.busicd) AS businm,
               C.bsdate + '.' + C.bseccd + '.' + C.busicd AS code
          FROM TB_AA010 A, TB_AA009 C
         WHERE A.spdate   = C.spdate
           AND A.spnum    = C.spnum
           AND A.spjangcd = C.spjangcd
           AND ( (A.acccd BETWEEN '7000' AND '7999') OR (A.acccd BETWEEN '8300' AND '8499') )
           AND A.custcd   = :custcd
           AND A.spjangcd = :spjangcd
           AND Left(A.spdate,6) BETWEEN :yyyy1 AND :yyyy2
           AND IsNull(A.mssec,'') LIKE :mssec
           AND A.acccd  LIKE :acccd  + '%'
           AND A.it1cd  LIKE :it1cd  + '%'
           AND A.it2cd  LIKE :it2cd  + '%'
           AND C.bsdate LIKE :bsdate + '%'
           AND C.bseccd LIKE :bseccd + '%'
           AND C.busicd LIKE :busicd + '%'
           AND (A.iwolflag <> '1' OR A.iwolflag IS NULL)
           AND LEN(C.busicd) > 0
        """;
		return sqlRunner.getRows(sql, param);
	}

}