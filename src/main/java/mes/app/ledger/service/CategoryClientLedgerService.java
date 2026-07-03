package mes.app.ledger.service;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class CategoryClientLedgerService {

	@Autowired
	SqlRunner sqlRunner;

	private MapSqlParameterSource buildBaseParam(String start, String end) {
		MapSqlParameterSource param = new MapSqlParameterSource();
		String spjangcd = TenantContext.get();
		String custcd   = getBizInfoBySpjangcd(spjangcd).get("custcd");
		String frdate = start.replace("-", "");
		String todate = end.replace("-", "");
		param.addValue("spjangcd", spjangcd);
		param.addValue("custcd",   custcd);
		param.addValue("frdate",   frdate);
		param.addValue("todate",   todate);
		param.addValue("indate",   frdate.substring(0, 4) + "00");
		return param;
	}

	private Map<String, String> getBizInfoBySpjangcd(String spjangcd) {
		MapSqlParameterSource param = new MapSqlParameterSource().addValue("spjangcd", spjangcd);
		String sql = """
        SELECT saupnum, custcd, spjangnm
        FROM tb_xa012
        WHERE spjangcd = :spjangcd
        """;
		Map<String, Object> row = sqlRunner.getRow(sql, param);
		Map<String, String> result = new HashMap<>();
		result.put("saupnum", "");
		result.put("custcd", "");
		result.put("spjangnm", "");
		if (row == null || row.isEmpty()) return result;
		result.put("saupnum",  row.get("saupnum")  == null ? "" : String.valueOf(row.get("saupnum")).trim());
		result.put("custcd",   row.get("custcd")   == null ? "" : String.valueOf(row.get("custcd")).trim());
		result.put("spjangnm", row.get("spjangnm") == null ? "" : String.valueOf(row.get("spjangnm")).trim());
		return result;
	}

	// ============================================================
	// tab1 좌측 : 관 단위 (왼쪽 쿼리)
	// ============================================================
	public Object searchSummary(String start, String end, String acccd) {
		MapSqlParameterSource param = buildBaseParam(start, end);
		param.addValue("acccd", (acccd == null || acccd.trim().isEmpty()) ? "%" : acccd.trim() + "%");
		// 왼쪽 쿼리에 mssec 조건이 있으므로 전체('%') 고정
		param.addValue("mssec", "");

		String sql = """
        SELECT A.acccd, A.accnm, A.drcr,
               SUM(A.dramt) AS dramt, SUM(A.cramt) AS cramt, SUM(A.bfamt) AS bfamt,
               CASE WHEN A.drcr = '1'
                    THEN SUM(A.bfamt) + SUM(A.dramt) - SUM(A.cramt)
                    ELSE SUM(A.bfamt) + SUM(A.cramt) - SUM(A.dramt)
               END AS balamt,
               STUFF(STUFF(:frdate,5,0,'-'),8,0,'-') AS frdate,
               STUFF(STUFF(:todate,5,0,'-'),8,0,'-') AS todate
          FROM
        (
        SELECT '00000000' AS yymmdd,
               A.acccd, B.accnm, B.drcr,
               0 AS dramt, 0 AS cramt,
               CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt
          FROM TB_AB001 A, TB_AC001 B
         WHERE A.custcd = B.custcd AND A.acccd = B.acccd AND B.spyn = '1'
           AND A.custcd = :custcd AND A.spjangcd = :spjangcd
           AND A.yymmdd >= :indate AND A.yymmdd < :frdate
           AND NOT (A.dramt = 0 AND A.cramt = 0)

        UNION ALL

        SELECT A.spdate AS yymmdd,
               B.acccd, C.accnm, C.drcr,
               B.dramt, B.cramt, 0 AS bfamt
          FROM TB_AA009 A, TB_AA010 B, TB_AC001 C
         WHERE A.spdate = B.spdate AND A.spnum = B.spnum
           AND B.acccd = C.acccd
           AND A.custcd = :custcd AND A.spjangcd = :spjangcd
           AND A.spdate BETWEEN :frdate AND :todate
           AND ( B.mssec LIKE :mssec + '%' OR B.mssec IS NULL )
           AND ( B.iwolflag <> '1' OR B.iwolflag IS NULL )
        ) A
         WHERE A.acccd LIKE :acccd
         GROUP BY A.acccd, A.accnm, A.drcr
         ORDER BY A.acccd
        """;
		return sqlRunner.getRows(sql, param);
	}

	// ============================================================
	// tab1 우측 : 관+거래처 단위 (오른쪽 쿼리)
	//   거래처코드 = cltcd → it1cd, 거래처명 = cltnm → it1nm
	// ============================================================
	public Object searchClient(String start, String end, String acccd, String it1cd) {
		MapSqlParameterSource param = buildBaseParam(start, end);
		param.addValue("acccd", (acccd == null || acccd.trim().isEmpty()) ? "%" : acccd.trim() + "%");
		param.addValue("it1cd", (it1cd == null || it1cd.trim().isEmpty()) ? "" : it1cd.trim());

		String sql = """
        SELECT A.acccd, A.accnm, A.it1cd, A.it1nm, A.drcr,
               SUM(A.dramt) AS dramt, SUM(A.cramt) AS cramt, SUM(A.bfamt) AS bfamt,
               CASE WHEN A.drcr = '1'
                    THEN SUM(A.bfamt) + SUM(A.dramt) - SUM(A.cramt)
                    ELSE SUM(A.bfamt) + SUM(A.cramt) - SUM(A.dramt)
               END AS balamt,
               STUFF(STUFF(:frdate,5,0,'-'),8,0,'-') AS frdate,
               STUFF(STUFF(:todate,5,0,'-'),8,0,'-') AS todate
          FROM
        (
        -- 이월 (TB_AB010, 거래처별 이월)
        SELECT '00000000' AS yymmdd,
               A.acccd, B.accnm, A.cltcd AS it1cd,
               (SELECT cltnm FROM tb_xclient WHERE custcd = A.custcd AND cltcd = A.cltcd) AS it1nm,
               B.drcr, 0 AS dramt, 0 AS cramt,
               CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt
          FROM TB_AB010 A, TB_AC001 B
         WHERE A.custcd = B.custcd AND A.acccd = B.acccd AND B.spyn = '1'
           AND A.custcd = :custcd AND A.spjangcd = :spjangcd
           AND A.yymm = :indate
           AND NOT (A.dramt = 0 AND A.cramt = 0)
           AND LEN(A.cltcd) > 0

        UNION ALL

        -- 연초~시작전
        SELECT '00000000',
               A.acccd, B.accnm, A.cltcd, C.cltnm, B.drcr,
               0, 0,
               CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt
          FROM TB_AA010 A, TB_AC001 B, TB_XCLIENT C
         WHERE A.custcd = B.custcd AND A.acccd = B.acccd
           AND A.custcd = C.custcd AND A.cltcd = C.cltcd
           AND B.spyn = '1' AND A.custcd = :custcd AND A.spjangcd = :spjangcd
           AND A.spdate > :indate + '00' AND A.spdate < :frdate
           AND NOT (A.dramt = 0 AND A.cramt = 0)
           AND ( A.iwolflag <> '1' OR A.iwolflag IS NULL )
           AND LEN(C.cltcd) > 0

        UNION ALL

        -- 조회기간
        SELECT A.spdate,
               A.acccd, B.accnm, A.cltcd, C.cltnm, B.drcr,
               A.dramt, A.cramt, 0 AS bfamt
          FROM TB_AA010 A, TB_AC001 B, TB_XCLIENT C
         WHERE A.custcd = B.custcd AND A.acccd = B.acccd
           AND A.custcd = C.custcd AND A.cltcd = C.cltcd
           AND B.spyn = '1' AND A.custcd = :custcd AND A.spjangcd = :spjangcd
           AND A.spdate BETWEEN :frdate AND :todate
           AND NOT (A.dramt = 0 AND A.cramt = 0)
           AND ( A.iwolflag <> '1' OR A.iwolflag IS NULL )
           AND LEN(C.cltcd) > 0
        ) A
         WHERE A.acccd LIKE :acccd
           AND A.it1cd LIKE :it1cd + '%'
           AND LEN(A.it1nm) > 0
         GROUP BY A.acccd, A.accnm, A.it1cd, A.it1nm, A.drcr
         ORDER BY A.acccd, A.it1cd
        """;
		return sqlRunner.getRows(sql, param);
	}

	// ============================================================
// tab2 : 상세내역 마스터 (관+거래처+전표일자 단위)
// ============================================================
	public Object selectDetailMasterList(String start, String end, String acccd, String it1cd) {
		MapSqlParameterSource param = buildBaseParam(start, end);
		param.addValue("acccd", (acccd == null || acccd.trim().isEmpty()) ? "%" : acccd.trim() + "%");
		param.addValue("it1cd", (it1cd == null || it1cd.trim().isEmpty()) ? "%" : it1cd.trim());

		String sql = """
        SELECT A.yymmdd, A.acccd, A.accnm, A.it1cd, A.it1nm, A.drcr,
               SUM(A.dramt) AS dramt, SUM(A.cramt) AS cramt, SUM(A.bfamt) AS bfamt,
               CASE WHEN A.drcr = '1'
                    THEN SUM(A.bfamt) + SUM(A.dramt) - SUM(A.cramt)
                    ELSE SUM(A.bfamt) + SUM(A.cramt) - SUM(A.dramt)
               END AS balamt,
               STUFF(STUFF(:frdate,5,0,'-'),8,0,'-') AS frdate,
               STUFF(STUFF(:todate,5,0,'-'),8,0,'-') AS todate
          FROM
        (
        -- 이월 (TB_AB010)
        SELECT '00000000' AS yymmdd,
               A.acccd, B.accnm, A.cltcd AS it1cd, A.cltnm AS it1nm, B.drcr,
               A.dramt, A.cramt,
               CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt
          FROM TB_AB010 A, TB_AC001 B
         WHERE A.custcd = B.custcd AND A.acccd = B.acccd AND B.spyn = '1'
           AND A.custcd = :custcd AND A.spjangcd = :spjangcd
           AND A.yymm = :indate
           AND NOT (A.dramt = 0 AND A.cramt = 0)

        UNION ALL

        -- 연초~시작전
        SELECT '00000000',
               A.acccd, B.accnm, A.cltcd, C.cltnm, B.drcr,
               A.dramt, A.cramt,
               CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt
          FROM TB_AA010 A, TB_AC001 B, TB_XCLIENT C
         WHERE A.custcd = B.custcd AND A.acccd = B.acccd
           AND A.custcd = C.custcd AND A.cltcd = C.cltcd
           AND B.spyn = '1' AND A.custcd = :custcd AND A.spjangcd = :spjangcd
           AND A.spdate > :indate + '00' AND A.spdate < :frdate
           AND NOT (A.dramt = 0 AND A.cramt = 0)
           AND ( A.iwolflag <> '1' OR A.iwolflag IS NULL )

        UNION ALL

        -- 조회기간
        SELECT A.spdate,
               A.acccd, B.accnm, A.cltcd, C.cltnm, B.drcr,
               A.dramt, A.cramt,
               CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt
          FROM TB_AA010 A, TB_AC001 B, TB_XCLIENT C
         WHERE A.custcd = B.custcd AND A.acccd = B.acccd
           AND A.custcd = C.custcd AND A.cltcd = C.cltcd
           AND B.spyn = '1' AND A.custcd = :custcd AND A.spjangcd = :spjangcd
           AND A.spdate BETWEEN :frdate AND :todate
           AND NOT (A.dramt = 0 AND A.cramt = 0)
           AND ( A.iwolflag <> '1' OR A.iwolflag IS NULL )
        ) A
         WHERE A.acccd LIKE :acccd
           AND A.it1cd LIKE :it1cd
         GROUP BY A.yymmdd, A.acccd, A.accnm, A.it1cd, A.it1nm, A.drcr
         ORDER BY A.acccd, A.it1cd, A.yymmdd
        """;
		return sqlRunner.getRows(sql, param);
	}

	// ============================================================
// tab3 : 상세내역 (전표 단위 + 97/98 소계, 누적잔액 계산)
// ============================================================
	public Object selectDetailList(String start, String end, String acccd, String it1cd) {
		MapSqlParameterSource param = buildBaseParam(start, end);
		param.addValue("acccd", (acccd == null) ? "" : acccd.trim());
		param.addValue("it1cd", (it1cd == null) ? "" : it1cd.trim());

		String sql = """
        WITH base AS (
          SELECT A.yymmdd, A.spnum, A.acccd, A.accnm, A.it1cd, A.it1nm,
                 A.summy, A.drcr,
                 SUM(A.dramt) AS dramt, SUM(A.cramt) AS cramt, SUM(A.bfamt) AS bfamt,
                 MAX(A.rowseq) AS rowseq
            FROM
          (
          -- 이월 (TB_AB010)
          SELECT '00000000' AS yymmdd, '0000' AS spnum,
                 A.acccd, B.accnm, A.cltcd AS it1cd, A.cltnm AS it1nm, '' AS summy, B.drcr,
                 A.dramt, A.cramt,
                 CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt,
                 '0' AS rowseq
            FROM TB_AB010 A, TB_AC001 B
           WHERE A.custcd = B.custcd AND A.acccd = B.acccd AND B.spyn = '1'
             AND A.custcd = :custcd AND A.spjangcd = :spjangcd
             AND A.yymm = :indate
             AND NOT (A.dramt = 0 AND A.cramt = 0)

          UNION ALL

          -- 연초~시작전
          SELECT '00000000', '0000',
                 A.acccd, B.accnm, A.cltcd, C.cltnm, '' AS summy, B.drcr,
                 A.dramt, A.cramt,
                 CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt,
                 A.rowseq
            FROM TB_AA010 A, TB_AC001 B, TB_XCLIENT C
           WHERE A.custcd = B.custcd AND A.acccd = B.acccd
             AND A.custcd = C.custcd AND A.cltcd = C.cltcd
             AND B.spyn = '1' AND A.custcd = :custcd AND A.spjangcd = :spjangcd
             AND A.spdate > :indate + '00' AND A.spdate < :frdate
             AND NOT (A.dramt = 0 AND A.cramt = 0)
             AND ( A.iwolflag <> '1' OR A.iwolflag IS NULL )

          UNION ALL

          -- 조회기간
          SELECT A.spdate, A.spnum,
                 A.acccd, B.accnm, A.cltcd, C.cltnm, A.summy, B.drcr,
                 A.dramt, A.cramt,
                 CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt,
                 A.rowseq
            FROM TB_AA010 A, TB_AC001 B, TB_XCLIENT C
           WHERE A.custcd = B.custcd AND A.acccd = B.acccd
             AND A.custcd = C.custcd AND A.cltcd = C.cltcd
             AND B.spyn = '1' AND A.custcd = :custcd AND A.spjangcd = :spjangcd
             AND A.spdate BETWEEN :frdate AND :todate
             AND NOT (A.dramt = 0 AND A.cramt = 0)
             AND ( A.iwolflag <> '1' OR A.iwolflag IS NULL )
          ) A
           WHERE A.acccd = :acccd AND A.it1cd = :it1cd
           GROUP BY A.yymmdd, A.spnum, A.acccd, A.accnm, A.it1cd, A.it1nm, A.summy, A.drcr

          UNION ALL

          -- 소계 (97 + 98)
          SELECT A.yymmdd, A.spnum, A.acccd, A.accnm, A.it1cd, A.it1nm, '' AS summy, '' AS drcr,
                 SUM(A.dramt), SUM(A.cramt), SUM(A.bfamt), MAX(A.rowseq)
            FROM
          (
          SELECT LEFT(A.spdate,6) + '97' AS yymmdd, '9999' AS spnum,
                 A.acccd, B.accnm, A.cltcd AS it1cd, C.cltnm AS it1nm,
                 A.dramt, A.cramt,
                 CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt,
                 A.rowseq
            FROM TB_AA010 A, TB_AC001 B, TB_XCLIENT C
           WHERE A.custcd = B.custcd AND A.acccd = B.acccd
             AND A.custcd = C.custcd AND A.cltcd = C.cltcd
             AND B.spyn = '1' AND A.custcd = :custcd AND A.spjangcd = :spjangcd
             AND A.spdate BETWEEN :frdate AND :todate
             AND ( A.iwolflag <> '1' OR A.iwolflag IS NULL )

          UNION ALL

          SELECT LEFT(A.spdate,6) + '98', '9999',
                 A.acccd, B.accnm, A.cltcd, C.cltnm,
                 A.dramt, A.cramt,
                 CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt,
                 A.rowseq
            FROM TB_AA010 A, TB_AC001 B, TB_XCLIENT C
           WHERE A.custcd = B.custcd AND A.acccd = B.acccd
             AND A.custcd = C.custcd AND A.cltcd = C.cltcd
             AND B.spyn = '1' AND A.custcd = :custcd AND A.spjangcd = :spjangcd
             AND A.spdate BETWEEN :frdate AND :todate
             AND ( A.iwolflag <> '1' OR A.iwolflag IS NULL )
          ) A
           WHERE A.acccd = :acccd AND A.it1cd = :it1cd
           GROUP BY A.yymmdd, A.spnum, A.acccd, A.accnm, A.it1cd, A.it1nm
        )
        SELECT yymmdd, spnum, acccd, accnm, it1cd, it1nm, summy, drcr,
               dramt, cramt, bfamt, rowseq,
               -- 잔액 누적 : 소계행(97/98)은 제외하고 이월+당기증감 누적
               SUM(CASE WHEN RIGHT(yymmdd,2) IN ('97','98') THEN 0
                        WHEN drcr = '1' THEN bfamt + dramt - cramt
                        ELSE bfamt + cramt - dramt END)
                   OVER (PARTITION BY acccd, it1cd ORDER BY yymmdd, spnum
                         ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS balamt,
               STUFF(STUFF(:frdate,5,0,'-'),8,0,'-') AS frdate,
               STUFF(STUFF(:todate,5,0,'-'),8,0,'-') AS todate
          FROM base
         ORDER BY acccd, it1cd, yymmdd, spnum
        """;
		return sqlRunner.getRows(sql, param);
	}
}