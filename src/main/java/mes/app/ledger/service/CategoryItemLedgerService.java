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

@Service
@Slf4j
public class CategoryItemLedgerService {

	@Autowired
	SqlRunner sqlRunner;

	// 공통: 테넌트 + 날짜
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
		param.addValue("indate",   frdate.substring(0, 4) + "00");  // 202500 (yyyymm)
		return param;
	}

	// ============================================================
	// tab1 좌측 : 관(계정) 단위 집계  (왼쪽 쿼리)
	// ============================================================
	public Object searchSummary(String start, String end, String mssec, String acccd, String it1cd) {
		MapSqlParameterSource param = buildBaseParam(start, end);

		// mssec : 전표블록(B 별칭) LIKE
		String mssecCond = "";
		if (mssec != null && !mssec.trim().isEmpty()) {
			mssecCond = " AND ( B.mssec LIKE :mssec + '%' OR B.mssec IS NULL ) ";
			param.addValue("mssec", mssec.trim());
		}

		// acccd : LIKE (없으면 전체)
		param.addValue("acccd", (acccd == null || acccd.trim().isEmpty()) ? "%" : acccd.trim() + "%");

		String sql = """
        SELECT  A.acccd, A.accnm, A.drcr,
                SUM(A.dramt) AS dramt,
                SUM(A.cramt) AS cramt,
                SUM(A.bfamt) AS bfamt,
                CASE WHEN A.drcr = '1'
                     THEN SUM(A.bfamt) + SUM(A.dramt) - SUM(A.cramt)
                     ELSE SUM(A.bfamt) + SUM(A.cramt) - SUM(A.dramt)
                END AS balamt,
                STUFF(STUFF(:frdate, 5, 0, '-'), 8, 0, '-') AS frdate,
                STUFF(STUFF(:todate, 5, 0, '-'), 8, 0, '-') AS todate
          FROM
        (
        SELECT '00000000' AS yymmdd,
                A.acccd, B.accnm, B.drcr,
                0 AS dramt, 0 AS cramt,
                CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt
          FROM TB_AB001 A, TB_AC001 B
         WHERE A.custcd   = B.custcd
           AND A.acccd    = B.acccd
           AND B.spyn     = '1'
           AND A.custcd   = :custcd
           AND A.spjangcd = :spjangcd
           AND A.yymmdd  >= :indate
           AND A.yymmdd  <  :frdate
           AND NOT (A.dramt = 0 AND A.cramt = 0)

        UNION ALL

        SELECT A.spdate AS yymmdd,
                B.acccd, C.accnm, C.drcr,
                B.dramt, B.cramt,
                0 AS bfamt
          FROM TB_AA009 A, TB_AA010 B, TB_AC001 C
         WHERE A.spdate   = B.spdate
           AND A.spnum    = B.spnum
           AND B.acccd    = C.acccd
           AND A.custcd   = :custcd
           AND A.spjangcd = :spjangcd
           AND A.spdate BETWEEN :frdate AND :todate
           """ + mssecCond + """
           AND ( B.iwolflag <> '1' OR B.iwolflag IS NULL )
        ) A
         WHERE A.acccd LIKE :acccd
         GROUP BY A.acccd, A.accnm, A.drcr
         ORDER BY A.acccd
        """;

		return sqlRunner.getRows(sql, param);
	}

	// ============================================================
	// tab1 우측 : 관+항 단위 집계  (오른쪽 쿼리)
	// ============================================================
	public Object searchItem(String start, String end, String mssec, String acccd, String it1cd) {
		MapSqlParameterSource param = buildBaseParam(start, end);

		// mssec : 전표블록 A.mssec LIKE (없으면 '%')
		param.addValue("mssec", (mssec == null || mssec.trim().isEmpty()) ? "" : mssec.trim());

		// acccd : LIKE (없으면 전체)
		param.addValue("acccd", (acccd == null || acccd.trim().isEmpty()) ? "%" : acccd.trim() + "%");

		// it1cd : LIKE (없으면 전체)
		param.addValue("it1cd", (it1cd == null || it1cd.trim().isEmpty()) ? "%" : it1cd.trim() + "%");

		// busipur : 입력칸 없음 → 전체
		param.addValue("busipur", "%");

		String sql = """
        SELECT A.acccd, A.accnm, A.it1cd, A.it1nm, A.drcr,
               MAX(A.tiosec) AS tiosec,
               SUM(A.dramt) AS dramt,
               SUM(A.cramt) AS cramt,
               SUM(A.bfamt) AS bfamt,
               CASE WHEN A.drcr = '1'
                    THEN SUM(A.bfamt) + SUM(A.dramt) - SUM(A.cramt)
                    ELSE SUM(A.bfamt) + SUM(A.cramt) - SUM(A.dramt)
               END AS balamt,
               STUFF(STUFF(:frdate, 5, 0, '-'), 8, 0, '-') AS frdate,
               STUFF(STUFF(:todate, 5, 0, '-'), 8, 0, '-') AS todate
          FROM
        (
        SELECT '00000000' AS yymmdd,
               A.acccd, B.accnm, A.it1cd, A.it1nm, B.drcr,
               '' AS tiosec, 0 AS dramt, 0 AS cramt,
               CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt
          FROM TB_AB014 A, TB_AC001 B
         WHERE A.custcd   = B.custcd
           AND A.acccd    = B.acccd
           AND B.spyn     = '1'
           AND A.custcd   = :custcd
           AND A.spjangcd = :spjangcd
           AND A.yymm     = :indate
           AND LEFT(A.acccd, 1) IN ('1', '2', '3')
           AND NOT (A.dramt = 0 AND A.cramt = 0)

        UNION ALL

        SELECT '00000000',
               A.acccd, B.accnm, A.it1cd, D.it1nm, B.drcr,
               '' AS tiosec, 0 AS dramt, 0 AS cramt,
               CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt
          FROM TB_AA010 A, TB_AC001 B, TB_AA009 C, VW_X0003 D
         WHERE A.custcd   = B.custcd
           AND A.acccd    = B.acccd
           AND A.custcd   = C.custcd
           AND A.spjangcd = C.spjangcd
           AND A.spdate   = C.spdate
           AND A.spnum    = C.spnum
           AND A.it1cd    = D.it1cd
           AND A.tiosec   = D.tiosec
           AND B.spyn     = '1'
           AND A.custcd   = :custcd
           AND A.spjangcd = :spjangcd
           AND A.spdate   > :indate + '00'
           AND A.spdate   < :frdate
           AND A.mssec LIKE :mssec + '%'
           AND NOT (A.dramt = 0 AND A.cramt = 0)
           AND ( A.iwolflag <> '1' OR A.iwolflag IS NULL )
           AND C.busipur LIKE :busipur

        UNION ALL

        SELECT A.spdate,
               A.acccd, B.accnm, A.it1cd, D.it1nm, B.drcr,
               A.tiosec, A.dramt, A.cramt,
               0 AS bfamt
          FROM TB_AA010 A, TB_AC001 B, TB_AA009 C, VW_X0003 D
         WHERE A.custcd   = B.custcd
           AND A.acccd    = B.acccd
           AND A.custcd   = C.custcd
           AND A.spjangcd = C.spjangcd
           AND A.spdate   = C.spdate
           AND A.spnum    = C.spnum
           AND A.it1cd    = D.it1cd
           AND A.tiosec   = D.tiosec
           AND B.spyn     = '1'
           AND A.custcd   = :custcd
           AND A.spjangcd = :spjangcd
           AND A.spdate BETWEEN :frdate AND :todate
           AND A.mssec LIKE :mssec + '%'
           AND NOT (A.dramt = 0 AND A.cramt = 0)
           AND ( A.iwolflag <> '1' OR A.iwolflag IS NULL )
           AND C.busipur LIKE :busipur
        ) A
         WHERE A.acccd LIKE :acccd
           AND A.it1cd LIKE :it1cd
         GROUP BY A.acccd, A.accnm, A.it1cd, A.it1nm, A.drcr
         ORDER BY A.acccd, A.it1cd
        """;

		return sqlRunner.getRows(sql, param);
	}

	// ============================================================
// tab2 : 상세내역 (관+항+전표일자 단위) — d_book11_1
// ============================================================
	public Object selectItemDetailList(String start, String end, String mssec, String acccd, String it1cd) {
		MapSqlParameterSource param = buildBaseParam(start, end);

		// mssec : 2·3번째 전표블록은 A.mssec 조건이 원본엔 없음(주의) → 파라미터만 준비
		// (원본 tab2 쿼리엔 mssec 필터가 없으므로 여기서도 걸지 않음)

		// acccd : LIKE (+ '%'). 없으면 전체
		param.addValue("acccd", (acccd == null) ? "" : acccd.trim());
		// it1cd : LIKE (+ '%'). 없으면 전체
		param.addValue("it1cd", (it1cd == null) ? "" : it1cd.trim());
		// tiosec : LIKE (+ '%'). 입력칸 없음 → 빈값(=전체)
		param.addValue("tiosec", "");
		// busipur : 입력칸 없음 → 전체
		param.addValue("busipur", "%");

		String sql = """
        SELECT A.yymmdd, A.acccd, A.accnm, A.it1cd, A.it1nm, A.drcr, A.tiosec,
               SUM(A.dramt) AS dramt,
               SUM(A.cramt) AS cramt,
               SUM(A.bfamt) AS bfamt,
               CASE WHEN A.drcr = '1'
                    THEN SUM(A.bfamt) + SUM(A.dramt) - SUM(A.cramt)
                    ELSE SUM(A.bfamt) + SUM(A.cramt) - SUM(A.dramt)
               END AS balamt,
               STUFF(STUFF(:frdate, 5, 0, '-'), 8, 0, '-') AS frdate,
               STUFF(STUFF(:todate, 5, 0, '-'), 8, 0, '-') AS todate
          FROM
        (
        SELECT '00000000' AS yymmdd,
               A.acccd, B.accnm, A.it1cd, A.it1nm, B.drcr,
               '' AS tiosec, A.dramt, A.cramt,
               CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt
          FROM TB_AB014 A, TB_AC001 B
         WHERE A.custcd   = B.custcd
           AND A.acccd    = B.acccd
           AND B.spyn     = '1'
           AND A.custcd   = :custcd
           AND A.spjangcd = :spjangcd
           AND A.yymm     = :indate
           AND LEFT(A.acccd, 1) IN ('1', '2', '3')
           AND NOT (A.dramt = 0 AND A.cramt = 0)

        UNION ALL

        SELECT '00000000',
               A.acccd, B.accnm, A.it1cd, D.it1nm, B.drcr,
               '' AS tiosec, A.dramt, A.cramt,
               CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt
          FROM TB_AA010 A, TB_AC001 B, TB_AA009 C, VW_X0003 D
         WHERE A.custcd   = B.custcd
           AND A.acccd    = B.acccd
           AND A.custcd   = C.custcd
           AND A.spjangcd = C.spjangcd
           AND A.spdate   = C.spdate
           AND A.spnum    = C.spnum
           AND A.it1cd    = D.it1cd
           AND A.tiosec   = D.tiosec
           AND B.spyn     = '1'
           AND A.custcd   = :custcd
           AND A.spjangcd = :spjangcd
           AND A.spdate   > :indate + '00'
           AND A.spdate   < :frdate
           AND NOT (A.dramt = 0 AND A.cramt = 0)
           AND ( A.iwolflag <> '1' OR A.iwolflag IS NULL )
           AND C.busipur LIKE :busipur

        UNION ALL

        SELECT A.spdate,
               A.acccd, B.accnm, A.it1cd, D.it1nm, B.drcr,
               C.tiosec, A.dramt, A.cramt,
               CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt
          FROM TB_AA010 A, TB_AC001 B, TB_AA009 C, VW_X0003 D
         WHERE A.custcd   = B.custcd
           AND A.acccd    = B.acccd
           AND A.custcd   = C.custcd
           AND A.spjangcd = C.spjangcd
           AND A.spdate   = C.spdate
           AND A.spnum    = C.spnum
           AND A.it1cd    = D.it1cd
           AND A.tiosec   = D.tiosec
           AND B.spyn     = '1'
           AND A.custcd   = :custcd
           AND A.spjangcd = :spjangcd
           AND A.spdate BETWEEN :frdate AND :todate
           AND NOT (A.dramt = 0 AND A.cramt = 0)
           AND ( A.iwolflag <> '1' OR A.iwolflag IS NULL )
           AND C.busipur LIKE :busipur
        ) A
         WHERE A.acccd  LIKE :acccd  + '%'
           AND A.it1cd  LIKE :it1cd  + '%'
           AND A.tiosec LIKE :tiosec + '%'
         GROUP BY A.yymmdd, A.acccd, A.accnm, A.it1cd, A.it1nm, A.drcr, A.tiosec
         ORDER BY A.acccd, A.it1cd, A.yymmdd
        """;

		return sqlRunner.getRows(sql, param);
	}

	// ============================================================
	// tab3 : 상세내역 (관+항 고정, 전표 단위) — d_book11_2
	// ============================================================
	public Object selectDetailList(String start, String end, String acccd,
																 String it1cd, String tiosec, String mssec) {
		MapSqlParameterSource param = buildBaseParam(start, end);

		param.addValue("acccd",  (acccd  == null) ? "" : acccd.trim());
		param.addValue("it1cd",  (it1cd  == null) ? "" : it1cd.trim());
		param.addValue("tiosec", (tiosec == null) ? "" : tiosec.trim());
		param.addValue("mssec",  (mssec == null || mssec.trim().isEmpty()) ? "%" : mssec.trim());
		param.addValue("busipur", "%");

		String sql = """
        SELECT A.yymmdd, A.spnum, A.acccd, A.accnm, A.it1cd, A.it1nm,
               A.summy, A.drcr, A.tiosec,
               SUM(A.dramt) AS dramt, SUM(A.cramt) AS cramt, SUM(A.bfamt) AS bfamt,
               A.mssec AS mssec,
               MAX(A.cardnum) AS cardnum, MAX(A.cardnm) AS cardnm,
               MAX(A.rowseq) AS rowseq,
               CASE WHEN A.drcr = '1'
                    THEN SUM(A.bfamt) + SUM(A.dramt) - SUM(A.cramt)
                    ELSE SUM(A.bfamt) + SUM(A.cramt) - SUM(A.dramt)
               END AS balamt,
               STUFF(STUFF(:frdate,5,0,'-'),8,0,'-') AS frdate,
               STUFF(STUFF(:todate,5,0,'-'),8,0,'-') AS todate
          FROM
        (
        -- ① 이월 (TB_AB014) : mssec 조건 없음
        SELECT '00000000' AS yymmdd, '0000' AS spnum,
               A.acccd, B.accnm, A.it1cd, A.it1nm, '' AS summy, B.drcr, '' AS tiosec,
               A.dramt, A.cramt,
               CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt,
               '' AS mssec, '' AS cardnum, '' AS cardnm, '0' AS rowseq
          FROM TB_AB014 A, TB_AC001 B
         WHERE A.custcd = B.custcd AND A.acccd = B.acccd AND B.spyn = '1'
           AND A.custcd = :custcd AND A.spjangcd = :spjangcd
           AND A.yymm = :indate
           AND LEFT(A.acccd,1) IN ('1','2','3')
           AND NOT (A.dramt = 0 AND A.cramt = 0)

        UNION ALL

        -- ② 연초~조회시작 직전 : C.tiosec 기준
        SELECT '00000000', '0000',
               A.acccd, B.accnm, A.it1cd, D.it1nm, '', B.drcr, '',
               A.dramt, A.cramt,
               CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt,
               A.mssec, A.cardnum,
               (SELECT cardnm FROM tb_iz010 WHERE custcd = :custcd AND spjangcd = A.spjangcd AND cardnum = A.cardnum),
               A.rowseq
          FROM TB_AA010 A, TB_AC001 B, TB_AA009 C, VW_X0003 D
         WHERE A.custcd = B.custcd AND A.acccd = B.acccd
           AND A.custcd = C.custcd AND A.spjangcd = C.spjangcd
           AND A.spdate = C.spdate AND A.spnum = C.spnum
           AND A.it1cd = D.it1cd AND A.tiosec = D.tiosec
           AND B.spyn = '1' AND A.custcd = :custcd AND A.spjangcd = :spjangcd
           AND A.spdate > :indate + '00' AND A.spdate < :frdate
           AND NOT (A.dramt = 0 AND A.cramt = 0)
           AND ( A.mssec LIKE :mssec OR A.mssec IS NULL )
           AND ( A.iwolflag <> '1' OR A.iwolflag IS NULL )
           AND C.tiosec = :tiosec
           AND C.busipur LIKE :busipur

        UNION ALL

        -- ③ 조회기간 : A.tiosec 기준
        SELECT A.spdate, A.spnum,
               A.acccd, B.accnm, A.it1cd, D.it1nm, A.summy, B.drcr, A.tiosec,
               A.dramt, A.cramt,
               CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt,
               A.mssec, A.cardnum,
               (SELECT cardnm FROM tb_iz010 WHERE custcd = :custcd AND spjangcd = A.spjangcd AND cardnum = A.cardnum),
               A.rowseq
          FROM TB_AA010 A, TB_AC001 B, TB_AA009 C, VW_X0003 D
         WHERE A.custcd = B.custcd AND A.acccd = B.acccd
           AND A.custcd = C.custcd AND A.spjangcd = C.spjangcd
           AND A.spdate = C.spdate AND A.spnum = C.spnum
           AND A.it1cd = D.it1cd AND A.tiosec = D.tiosec
           AND B.spyn = '1' AND A.custcd = :custcd AND A.spjangcd = :spjangcd
           AND A.spdate BETWEEN :frdate AND :todate
           AND NOT (A.dramt = 0 AND A.cramt = 0)
           AND ( A.mssec LIKE :mssec OR A.mssec IS NULL )
           AND ( A.iwolflag <> '1' OR A.iwolflag IS NULL )
           AND A.tiosec = :tiosec
           AND C.busipur LIKE :busipur
        ) A
         WHERE A.acccd = :acccd AND A.it1cd = :it1cd AND A.tiosec = :tiosec
         GROUP BY A.yymmdd, A.spnum, A.acccd, A.accnm, A.it1cd, A.it1nm,
                  A.summy, A.drcr, A.mssec, A.tiosec

        UNION ALL

        -- ④ 계정소계 (98만)
        SELECT A.yymmdd, A.spnum, A.acccd, A.accnm, A.it1cd, A.it1nm,
               '' AS summy, '' AS drcr, '' AS tiosec,
               SUM(A.dramt), SUM(A.cramt), SUM(A.bfamt),
               A.mssec AS mssec,
               MAX(A.cardnum), MAX(A.cardnm), MAX(A.rowseq),
               CASE WHEN '' = '1' THEN 0 ELSE 0 END AS balamt,
               STUFF(STUFF(:frdate,5,0,'-'),8,0,'-'),
               STUFF(STUFF(:todate,5,0,'-'),8,0,'-')
          FROM
        (
        SELECT LEFT(A.spdate,6) + '98' AS yymmdd, '9999' AS spnum,
               A.acccd, B.accnm, A.it1cd, D.it1nm,
               A.dramt, A.cramt,
               CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt,
               A.mssec, A.cardnum,
               (SELECT cardnm FROM tb_iz010 WHERE custcd = :custcd AND spjangcd = A.spjangcd AND cardnum = A.cardnum) AS cardnm,
               A.rowseq, A.tiosec
          FROM TB_AA010 A, TB_AC001 B, TB_AA009 C, VW_X0003 D
         WHERE A.custcd = B.custcd AND A.acccd = B.acccd
           AND A.custcd = C.custcd AND A.spjangcd = C.spjangcd
           AND A.spdate = C.spdate AND A.spnum = C.spnum
           AND A.it1cd = D.it1cd AND A.tiosec = D.tiosec
           AND B.spyn = '1' AND A.custcd = :custcd AND A.spjangcd = :spjangcd
           AND A.spdate BETWEEN :frdate AND :todate
           AND ( A.mssec LIKE :mssec OR A.mssec IS NULL )
           AND ( A.iwolflag <> '1' OR A.iwolflag IS NULL )
           AND C.busipur LIKE :busipur
        ) A
         WHERE A.acccd = :acccd AND A.it1cd = :it1cd AND A.tiosec = :tiosec
         GROUP BY A.yymmdd, A.spnum, A.acccd, A.accnm, A.it1cd, A.it1nm, A.mssec, A.tiosec
         ORDER BY yymmdd, spnum
        """;

		return sqlRunner.getRows(sql, param);
	}

	// ============================================================
	// 전표 팝업 : 헤더(TB_AA009) + 분개(TB_AA010)
	//   (계좌별원장과 동일)
	// ============================================================
	public Object selectSlip(String spdate, String spnum) {
		String spjangcd = TenantContext.get();
		String custcd   = getBizInfoBySpjangcd(spjangcd).get("custcd");

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("custcd", custcd);
		param.addValue("spjangcd", spjangcd);
		param.addValue("spdate", spdate);
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
}