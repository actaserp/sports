package mes.app.ledger.service;

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
public class AccountLedgerService { //계좌별원장

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
// tab1 : 계좌별 집계 (계좌번호 정확일치 기반)
//   it1cd = bankcd, it1nm = 계좌명(banknm), accnum = 계좌번호
// ============================================================
	public Object searchSummary(String start, String end, String accnum,
															String accid, String bankid, String useyn) {
		MapSqlParameterSource param = buildBaseParam(start, end);
		// 계좌번호 정확일치. 비어있으면 전체(NULL 처리)
		boolean hasAccnum = (accnum != null && !accnum.trim().isEmpty());
		param.addValue("accnum", hasAccnum ? accnum.trim() : null);
		param.addValue("useyn",  (useyn == null || useyn.trim().isEmpty()) ? "" : useyn.trim());

		String sql = """
        SELECT A.it1cd, A.it1nm, A.accnum,
               MAX(A.useyn) AS useyn,
               MAX(A.sortnum) AS sortnum,
               MAX(A.mssec) AS mssec,
               (SELECT mssecnm FROM tb_x0005 WHERE mssec = MAX(A.mssec)) AS mssecnm,
               MAX(A.Bankname) AS Bankname,
               SUM(A.dramt) AS dramt, SUM(A.cramt) AS cramt, SUM(A.bfamt) AS bfamt,
               SUM(A.bfamt) + SUM(A.dramt) - SUM(A.cramt) AS balamt,
               STUFF(STUFF(:frdate,5,0,'-'),8,0,'-') AS frdate,
               STUFF(STUFF(:todate,5,0,'-'),8,0,'-') AS todate
          FROM
        (
        -- 이월 (TB_AB015)
        SELECT '00000000' AS yymmdd,
               A.acccd, B.accnm, D.banknm AS Bankname,
               A.bankcd AS it1cd, C.banknm AS it1nm, C.accnum AS accnum,
               B.drcr, C.mssec, C.sortnum, C.useyn,
               0 AS dramt, 0 AS cramt,
               CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt
          FROM TB_AB015 A, TB_AC001 B, TB_AA040 C, TB_XBANK D
         WHERE A.custcd = B.custcd AND A.acccd = B.acccd AND B.spyn = '1'
           AND A.bankcd = C.bank + C.bankcd
           AND A.custcd = C.custcd AND A.spjangcd = C.spjangcd
           AND C.bank = D.bankcd
           AND A.custcd = :custcd AND A.spjangcd = :spjangcd
           AND A.yymm = :indate
           AND NOT (A.dramt = 0 AND A.cramt = 0)
           AND ( :accnum IS NULL OR C.accnum = :accnum )

        UNION ALL

        -- 연초~시작전
        SELECT '00000000',
               A.acccd, B.accnm, C.banknm AS Bankname,
               A.bankcd, C.banknm, C.accnum,
               B.drcr, C.mssec, C.sortnum, C.useyn,
               0, 0,
               CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt
          FROM TB_AA010 A, TB_AC001 B, TB_AA040 C, TB_XBANK D
         WHERE A.custcd = B.custcd AND A.acccd = B.acccd
           AND A.custcd = C.custcd AND A.spjangcd = C.spjangcd
           AND A.bankcd = C.bank + C.bankcd
           AND B.spyn = '1' AND C.bank = D.bankcd
           AND A.custcd = :custcd AND A.spjangcd = :spjangcd
           AND A.spdate > :indate + '00' AND A.spdate < :frdate
           AND NOT (A.dramt = 0 AND A.cramt = 0)
           AND ( A.iwolflag <> '1' OR A.iwolflag IS NULL )
           AND ( :accnum IS NULL OR C.accnum = :accnum )

        UNION ALL

        -- 조회기간
        SELECT A.spdate,
               A.acccd, B.accnm, E.banknm AS Bankname,
               A.bankcd, C.banknm, C.accnum,
               B.drcr, C.mssec, C.sortnum, C.useyn,
               A.dramt, A.cramt, 0 AS bfamt
          FROM TB_AA010 A, TB_AC001 B, TB_AA040 C, TB_AA009 D, TB_XBANK E
         WHERE A.custcd = B.custcd AND A.acccd = B.acccd
           AND A.custcd = C.custcd AND A.spjangcd = C.spjangcd
           AND A.bankcd = C.bank + C.bankcd
           AND A.custcd = D.custcd AND A.spjangcd = D.spjangcd
           AND A.spdate = D.spdate AND A.spnum = D.spnum
           AND B.spyn = '1' AND C.bank = E.bankcd
           AND A.custcd = :custcd AND A.spjangcd = :spjangcd
           AND A.spdate BETWEEN :frdate AND :todate
           AND NOT (A.dramt = 0 AND A.cramt = 0)
           AND ( A.iwolflag <> '1' OR A.iwolflag IS NULL )
           AND ( :accnum IS NULL OR C.accnum = :accnum )
        ) A
         WHERE A.useyn LIKE :useyn + '%'
         GROUP BY A.it1cd, A.it1nm, A.accnum
         ORDER BY MAX(A.sortnum), A.it1cd
        """;
		return sqlRunner.getRows(sql, param);
	}

	// ============================================================
// tab2 : 상세내역 마스터 (관+계좌+전표일자 단위)
// ============================================================
	public Object selectDetailMasterList(String start, String end, String acccd,
																			 String it1cd, String spacc) {
		MapSqlParameterSource param = buildBaseParam(start, end);
		param.addValue("acccd", (acccd == null || acccd.trim().isEmpty()) ? "%" : acccd.trim() + "%");
		param.addValue("it1cd", (it1cd == null || it1cd.trim().isEmpty()) ? "%" : it1cd.trim());
		param.addValue("spacc", (spacc == null || spacc.trim().isEmpty()) ? "" : spacc.trim());

		String sql = """
			SELECT 
					CASE WHEN A.yymmdd = '00000000' THEN '00000000'
								ELSE STUFF(STUFF(A.yymmdd, 5, 0, '-'), 8, 0, '-')
					 END AS yymmdd,
					 A.acccd, A.accnm, A.it1cd, A.it1nm, A.drcr,
					 SUM(A.dramt) AS dramt, SUM(A.cramt) AS cramt, SUM(A.bfamt) AS bfamt,
					 CASE WHEN A.drcr = '1'
								THEN SUM(A.bfamt) + SUM(A.dramt) - SUM(A.cramt)
								ELSE SUM(A.bfamt) + SUM(A.cramt) - SUM(A.dramt)
					 END AS balamt,
					 STUFF(STUFF(:frdate,5,0,'-'),8,0,'-') AS frdate,
					 STUFF(STUFF(:todate,5,0,'-'),8,0,'-') AS todate
				FROM
			(
			-- 이월 (TB_AB015)
			SELECT '00000000' AS yymmdd,
               A.acccd, B.accnm, A.bankcd AS it1cd, C.banknm AS it1nm, B.drcr,
               A.dramt, A.cramt,
               CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt
          FROM TB_AB015 A, TB_AC001 B, TB_AA040 C
         WHERE A.custcd = B.custcd AND A.acccd = B.acccd
           AND A.custcd = C.custcd AND A.spjangcd = C.spjangcd
           AND A.bankcd = C.bank + C.bankcd
           AND B.spyn = '1' AND A.custcd = :custcd AND A.spjangcd = :spjangcd
           AND A.yymm = :indate
           AND NOT (A.dramt = 0 AND A.cramt = 0)
           AND A.bankcd IN (SELECT bank + bankcd FROM TB_AA040 WHERE spacc LIKE :spacc + '%')

        UNION ALL

        -- 연초~시작전
        SELECT '00000000',
               A.acccd, B.accnm, A.bankcd, C.banknm, B.drcr,
               A.dramt, A.cramt,
               CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt
          FROM TB_AA010 A, TB_AC001 B, TB_AA040 C
         WHERE A.custcd = B.custcd AND A.acccd = B.acccd
           AND A.custcd = C.custcd AND A.spjangcd = C.spjangcd
           AND A.bankcd = C.bank + C.bankcd
           AND B.spyn = '1' AND A.custcd = :custcd AND A.spjangcd = :spjangcd
           AND A.spdate > :indate + '00' AND A.spdate < :frdate
           AND NOT (A.dramt = 0 AND A.cramt = 0)
           AND (C.spacc LIKE :spacc + '%')
           AND ( A.iwolflag <> '1' OR A.iwolflag IS NULL )

        UNION ALL

        -- 조회기간
        SELECT A.spdate,
               A.acccd, B.accnm, A.bankcd, C.banknm, B.drcr,
               A.dramt, A.cramt,
               CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt
          FROM TB_AA010 A, TB_AC001 B, TB_AA040 C
         WHERE A.custcd = B.custcd AND A.acccd = B.acccd
           AND A.custcd = C.custcd AND A.spjangcd = C.spjangcd
           AND A.bankcd = C.bank + C.bankcd
           AND B.spyn = '1' AND A.custcd = :custcd AND A.spjangcd = :spjangcd
           AND A.spdate BETWEEN :frdate AND :todate
           AND NOT (A.dramt = 0 AND A.cramt = 0)
           AND (C.spacc LIKE :spacc + '%')
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
// tab3 : 일자별 내역 (전표 상세 + 월계(97), 누적잔액)
// ============================================================
	public Object selectDetailList(String start, String end, String acccd,
																 String it1cd, String accnm) {
		MapSqlParameterSource param = buildBaseParam(start, end);
		param.addValue("acccd", (acccd == null) ? "" : acccd.trim());
		param.addValue("it1cd", (it1cd == null) ? "" : it1cd.trim());
		param.addValue("it1nm", (accnm == null) ? "" : accnm.trim()); // 화면표시용

		String sql = """
        WITH base AS (
          SELECT A.yymmdd, A.spnum, A.spseq, A.acccd, A.accnm, A.it1cd, A.it1nm, A.it2nm,
                 A.summy, A.drcr,
                 SUM(A.dramt) AS dramt, SUM(A.cramt) AS cramt, SUM(A.bfamt) AS bfamt,
                 A.banknm, MAX(A.rowseq) AS rowseq
            FROM
          (
          -- 이월 (TB_AB015)
          SELECT '00000000' AS yymmdd, '0000' AS spnum, '0000' AS spseq,
                 A.acccd, B.accnm, A.bankcd AS it1cd, '' AS it1nm, '' AS it2nm,
                 '' AS summy, B.drcr, A.dramt, A.cramt,
                 CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt,
                 C.banknm, '0' AS rowseq
            FROM TB_AB015 A, TB_AC001 B, TB_AA040 C
           WHERE A.custcd = B.custcd AND A.acccd = B.acccd
             AND A.custcd = C.custcd AND A.spjangcd = C.spjangcd
             AND A.bankcd = C.bank + C.bankcd
             AND B.spyn = '1' AND A.custcd = :custcd AND A.spjangcd = :spjangcd
             AND A.yymm = :indate
             AND NOT (A.dramt = 0 AND A.cramt = 0)

          UNION ALL

          -- 연초~시작전
          SELECT '00000000', '0000', '0000',
                 A.acccd, B.accnm, A.bankcd, '' AS it1nm, '' AS it2nm,
                 '' AS summy, B.drcr, A.dramt, A.cramt,
                 CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt,
                 C.banknm, A.rowseq
            FROM TB_AA010 A, TB_AC001 B, TB_AA040 C
           WHERE A.custcd = B.custcd AND A.acccd = B.acccd
             AND A.custcd = C.custcd AND A.spjangcd = C.spjangcd
             AND A.bankcd = C.bank + C.bankcd
             AND B.spyn = '1' AND A.custcd = :custcd AND A.spjangcd = :spjangcd
             AND A.spdate > :indate + '00' AND A.spdate < :frdate
             AND NOT (A.dramt = 0 AND A.cramt = 0)
             AND ( A.iwolflag <> '1' OR A.iwolflag IS NULL )

          UNION ALL

          -- 조회기간 (항·목명 조인)
          SELECT A.spdate, A.spnum, A.spseq,
                 A.acccd, B.accnm, A.bankcd,
                 (SELECT it1nm FROM VW_X0003 WHERE it1cd = A.it1cd AND tiosec = E.tiosec) AS it1nm,
                 (SELECT it2nm FROM TB_X0004 WHERE it2cd = A.it2cd AND tiosec = E.tiosec) AS it2nm,
                 A.summy, B.drcr, A.dramt, A.cramt,
                 CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt,
                 C.banknm, A.rowseq
            FROM TB_AA010 A, TB_AC001 B, TB_AA040 C, TB_AA009 E
           WHERE A.custcd = B.custcd AND A.acccd = B.acccd
             AND A.custcd = C.custcd AND A.spjangcd = C.spjangcd
             AND A.custcd = E.custcd AND A.spjangcd = E.spjangcd
             AND A.spdate = E.spdate AND A.spnum = E.spnum
             AND A.bankcd = C.bank + C.bankcd
             AND B.spyn = '1' AND A.custcd = :custcd AND A.spjangcd = :spjangcd
             AND A.spdate BETWEEN :frdate AND :todate
             AND NOT (A.dramt = 0 AND A.cramt = 0)
             AND ( A.iwolflag <> '1' OR A.iwolflag IS NULL )
          ) A
           WHERE A.acccd = :acccd AND A.it1cd = :it1cd
           GROUP BY A.yymmdd, A.spnum, A.spseq, A.acccd, A.accnm, A.it1cd, A.it1nm, A.it2nm, A.summy, A.drcr, A.banknm

          UNION ALL

          -- 월계 (97)
          SELECT A.yymmdd, A.spnum, A.spseq, A.acccd, A.accnm, A.it1cd, '' AS it1nm, '' AS it2nm,
                 '' AS summy, '' AS drcr,
                 SUM(A.dramt), SUM(A.cramt), SUM(A.bfamt), MAX(A.banknm), MAX(A.rowseq)
            FROM
          (
          SELECT LEFT(A.spdate,6) + '97' AS yymmdd, '9999' AS spnum, '9999' AS spseq,
                 A.acccd, B.accnm, A.bankcd AS it1cd,
                 A.dramt, A.cramt,
                 CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt,
                 C.banknm, A.rowseq
            FROM TB_AA010 A, TB_AC001 B, TB_AA040 C
           WHERE A.custcd = B.custcd AND A.acccd = B.acccd
             AND A.custcd = C.custcd AND A.spjangcd = C.spjangcd
             AND A.bankcd = C.bank + C.bankcd
             AND B.spyn = '1' AND A.custcd = :custcd AND A.spjangcd = :spjangcd
             AND A.spdate BETWEEN :frdate AND :todate
             AND ( A.iwolflag <> '1' OR A.iwolflag IS NULL )
          ) A
           WHERE A.acccd = :acccd AND A.it1cd = :it1cd
           GROUP BY A.yymmdd, A.spnum, A.spseq, A.acccd, A.accnm, A.it1cd
        )
			SELECT CASE WHEN yymmdd = '00000000' THEN '00000000'
								WHEN RIGHT(yymmdd,2) IN ('97','98') THEN yymmdd
								ELSE STUFF(STUFF(yymmdd, 5, 0, '-'), 8, 0, '-')
					 END AS yymmdd,
					 spnum, spseq, acccd, accnm, it1cd, it1nm, it2nm, summy, drcr,
					 dramt, cramt, bfamt, banknm, rowseq,
					 SUM(CASE WHEN RIGHT(yymmdd,2) IN ('97','98') THEN 0
										WHEN drcr = '1' THEN bfamt + dramt - cramt
										ELSE bfamt + cramt - dramt END)
							 OVER (PARTITION BY acccd, it1cd ORDER BY yymmdd, spnum, spseq
										 ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS balamt,
					 CAST(:it1nm AS VARCHAR(50)) AS accnum,
					 STUFF(STUFF(:frdate,5,0,'-'),8,0,'-') AS frdate,
					 STUFF(STUFF(:todate,5,0,'-'),8,0,'-') AS todate
			FROM base
		 ORDER BY acccd, it1cd, yymmdd, spnum, spseq
        """;
		return sqlRunner.getRows(sql, param);
	}

	// ============================================================
	// tab4 : 계좌코드오류전표 (TB_AA040에 없는 은행코드 전표)
	// ============================================================
	public Object selectErrorSlipList(String start, String end, String acccd) {
		MapSqlParameterSource param = buildBaseParam(start, end);
		param.addValue("acccd", (acccd == null) ? "" : acccd.trim());

		String sql = """
			SELECT 
						CASE WHEN A.yymmdd = '00000000' THEN '00000000'
								WHEN RIGHT(A.yymmdd, 2) IN ('97','98') THEN A.yymmdd
								ELSE STUFF(STUFF(A.yymmdd, 5, 0, '-'), 8, 0, '-')
					 END AS yymmdd,
					 A.spnum, A.spseq, A.acccd, A.accnm, A.it1cd, A.it1nm, A.it2nm,
					 A.summy, A.drcr, A.dramt, A.cramt, A.bfamt, A.banknm, A.rowseq,
					 STUFF(STUFF(:frdate,5,0,'-'),8,0,'-') AS frdate,
					 STUFF(STUFF(:todate,5,0,'-'),8,0,'-') AS todate
          FROM
        (
        SELECT A.spdate AS yymmdd, A.spnum, A.spseq,
               A.acccd, B.accnm, A.bankcd,
               (SELECT it1nm FROM VW_X0003 WHERE it1cd = A.it1cd AND tiosec = E.tiosec) AS it1nm,
               (SELECT it2nm FROM TB_X0004 WHERE it2cd = A.it2cd AND tiosec = E.tiosec) AS it2nm,
               A.summy, B.drcr, A.dramt, A.cramt,
               CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt,
               (SELECT banknm FROM TB_AA040 WHERE bank + bankcd = A.bankcd) AS banknm,
               A.rowseq, A.it1cd
          FROM TB_AA010 A, TB_AC001 B, TB_AA009 E
         WHERE A.custcd = B.custcd AND A.acccd = B.acccd
           AND A.custcd = E.custcd AND A.spjangcd = E.spjangcd
           AND A.spdate = E.spdate AND A.spnum = E.spnum
           AND ( A.bankcd NOT IN (SELECT bank + bankcd FROM TB_AA040)
                 OR A.bankcd IS NULL OR LEN(A.bankcd) = 0 )
           AND B.spyn = '1' AND A.custcd = :custcd AND A.spjangcd = :spjangcd
           AND A.spdate BETWEEN :frdate AND :todate
           AND NOT (A.dramt = 0 AND A.cramt = 0)
           AND ( A.iwolflag <> '1' OR A.iwolflag IS NULL )
        ) A
         WHERE A.acccd = :acccd
         ORDER BY A.yymmdd, A.spnum, A.spseq
        """;
		return sqlRunner.getRows(sql, param);
	}

	// ============================================================
	// 전표 팝업 : 헤더(TB_AA009) + 분개(TB_AA010)
	// ============================================================
	public Object selectSlip(String spdate, String spnum) {
		String spjangcd = TenantContext.get();
		String custcd   = getBizInfoBySpjangcd(spjangcd).get("custcd");
		String spdateNorm = (spdate == null) ? "" : spdate.replaceAll("[^0-9]", "");

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("custcd", custcd);
		param.addValue("spjangcd", spjangcd);
		param.addValue("spdate", spdateNorm);
		param.addValue("spnum", spnum);

		// ── 헤더 ──
		String headSql = """
        SELECT A.custcd, A.spjangcd, A.spdate, A.spnum, A.tiosec, A.cashyn,
               A.busipur,
               CASE A.busipur
                    WHEN '1' THEN '고유목적'
                    WHEN '2' THEN '수익'
                    WHEN '3' THEN '공통'
                    ELSE '' END AS busipurnm,
               A.spoccu,
               CASE A.spoccu
                    WHEN 'AA' THEN '전표일반'
                    WHEN 'I1' THEN '매출세금계산서'
                    WHEN 'I2' THEN '매입세금계산서'
                    WHEN 'I3' THEN '매출계산서'
                    WHEN 'I4' THEN '매입계산서'
                    WHEN 'I5' THEN '매출카드'
                    WHEN 'I6' THEN '매입카드'
                    WHEN 'I7' THEN '매출기타'
                    WHEN 'I8' THEN '기타원천징수'
                    ELSE '' END AS spoccunm,
               A.remark, A.taxdate, A.taxnum, A.subject,
               STUFF(STUFF(A.regdate,5,0,'-'),8,0,'-') AS regdate,
               A.bsdate, A.bseccd, A.busicd,
               (SELECT businm FROM tb_x0002 WHERE bsdate = A.bsdate AND bseccd = A.bseccd AND busicd = A.busicd) AS businm,
               A.setnum, A.spjangnm, A.busicd_cnt, A.fixflag,
               A.appdate, A.appperid, A.appgubun, A.appnum,
               A.inputsabun, A.inputdate, A.inputid,
               (SELECT filename FROM TB_AA010ATCH WHERE spdate = 'AJ' + A.spdate + A.spnum + A.spjangcd) AS filepath,
               CAST('0' AS CHAR(1)) AS jichul,
               STUFF(STUFF(A.spdate,5,0,'-'),8,0,'-') AS spdate_fmt
          FROM TB_AA009 A WITH (NOLOCK)
         WHERE A.custcd = :custcd AND A.spjangcd = :spjangcd
           AND A.spdate = :spdate AND A.spnum = :spnum
        """;
		Map<String, Object> head = sqlRunner.getRow(headSql, param);

		// ── 분개 (그리드) ──
		String lineSql = """
        SELECT A.custcd, A.spjangcd, A.spdate, A.spnum, A.spseq, A.spjangnm,
               A.bumuncd, A.gubun, A.acccd, A.accnm, A.drcr, A.dramt, A.cramt, A.summy,
               A.cltcd,
               (SELECT cltnm FROM tb_xclient WHERE cltcd = A.cltcd) AS cltnm,
               A.it1cd,
               (SELECT it1nm FROM TB_X0003 WHERE '00' + it1cd = A.it1cd AND tiosec = A.tiosec) AS it1nm,
               A.it2cd,
               (SELECT it2nm FROM TB_X0004 WHERE it2cd = A.it2cd AND tiosec = A.tiosec) AS it2nm,
               A.tiosec, A.mssec,
               (SELECT mssecnm FROM tb_x0005 WHERE mssec = A.mssec) AS mssecnm,
               A.spoccu, A.inputdate, A.inputsabun, A.bankcd,
               (SELECT banknm FROM tb_aa040 WHERE custcd = :custcd AND spjangcd = A.spjangcd AND bank + bankcd = A.bankcd) AS banknm,
               (SELECT accnum FROM tb_aa040 WHERE custcd = :custcd AND spjangcd = A.spjangcd AND bank + bankcd = A.bankcd) AS accnum,
               A.cardnum,
               (SELECT cardnm FROM tb_iz010 WHERE custcd = :custcd AND spjangcd = A.spjangcd AND cardnum = A.cardnum) AS cardnm,
               B.cltflag, B.divflag, B.acnflag, B.cardflag,
               A.rowseq, A.taxdate, A.taxnum, B.vatflag,
               CASE WHEN A.drcr = '1' THEN '차변' ELSE '대변' END AS drcrnm
          FROM TB_AA010 A WITH (NOLOCK), TB_AA009 D WITH (NOLOCK), TB_AC001 B WITH (NOLOCK)
         WHERE A.custcd = D.custcd AND A.spjangcd = D.spjangcd
           AND A.spdate = D.spdate AND A.spnum = D.spnum
           AND A.custcd = B.custcd AND A.acccd = B.acccd
           AND A.custcd = :custcd AND A.spjangcd = :spjangcd
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