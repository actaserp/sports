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
public class CategoryLedgerService {

	@Autowired
	SqlRunner sqlRunner;

	/**
	 * 공통 파라미터(테넌트 + 날짜)만 세팅한다.
	 * mssec / acccd / busipur 등 탭마다 다른 조건은 각 메서드에서 직접 추가한다.
	 * - frdate, todate : 'yyyymmdd' (하이픈 제거)
	 * - indate         : 'yyyymm'   (전기이월 기준연월 = 조회시작연도 + '00')
	 */
	private MapSqlParameterSource buildBaseParam(String start, String end) {
		MapSqlParameterSource param = new MapSqlParameterSource();

		String spjangcd = TenantContext.get();
		String custcd   = getBizInfoBySpjangcd(spjangcd).get("custcd");

		String frdate = start.replace("-", "");   // 20250601
		String todate = end.replace("-", "");     // 20250630

		param.addValue("spjangcd", spjangcd);
		param.addValue("custcd",   custcd);
		param.addValue("frdate",   frdate);
		param.addValue("todate",   todate);
		param.addValue("indate",   frdate.substring(0, 4) + "00");  // 202500

		return param;
	}

	// =====================================================================
	// 탭1 : 집계내역 (계정별 합계)
	// =====================================================================
	public Object selectCategoryLedgerList(String start, String end, String mssec, String accnm) {
		MapSqlParameterSource param = buildBaseParam(start, end);

		// mssec : 전표블록(C 별칭)의 mssec LIKE
		String mssecCond = "";
		if (mssec != null && !mssec.trim().isEmpty()) {
			mssecCond = " AND ( C.mssec LIKE :mssec + '%' OR C.mssec IS NULL ) ";
			param.addValue("mssec", mssec.trim());
		}

		// acccd : LIKE (부분일치). 값이 없으면 조건 없음 = 전체
		String acccdCond = "";
		if (accnm != null && !accnm.trim().isEmpty()) {
			acccdCond = " AND (acccd LIKE :acccd) ";
			param.addValue("acccd", accnm.trim() + "%");
		}

		String sql = """
         SELECT  A.acccd ,
                 A.accnm ,
                 A.drcr  ,
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
           FROM TB_AB002 A, TB_AC001 B
          WHERE A.custcd   = B.custcd
            AND A.acccd    = B.acccd
            AND B.spyn     = '1'
            AND A.custcd   = :custcd
            AND A.spjangcd = :spjangcd
            AND A.yymm     = :indate
            AND NOT (A.dramt = 0 AND A.cramt = 0)

         UNION ALL

         SELECT '00000000' AS yymmdd,
                 A.acccd, B.accnm, B.drcr,
                 0 AS dramt, 0 AS cramt,
                 CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt
           FROM TB_AA010 A, TB_AC001 B
          WHERE A.custcd   = B.custcd
            AND A.acccd    = B.acccd
            AND B.spyn     = '1'
            AND A.custcd   = :custcd
            AND A.spjangcd = :spjangcd
            AND A.spdate  >= :indate + '00'
            AND A.spdate  <  :frdate
            AND NOT (A.dramt = 0 AND A.cramt = 0)

         UNION ALL

         SELECT  C.spdate AS yymmdd,
                 B.acccd, D.accnm, D.drcr,
                 B.dramt, B.cramt,
                 0 AS bfamt
           FROM TB_AA009 C, TB_AA010 B, TB_AC001 D
          WHERE C.custcd   = B.custcd
            AND C.spjangcd = B.spjangcd
            AND C.spdate   = B.spdate
            AND C.spnum    = B.spnum
            AND B.acccd    = D.acccd
            AND C.custcd   = :custcd
            AND C.spjangcd = :spjangcd
            AND C.spdate BETWEEN :frdate AND :todate
            """ + mssecCond + """
            AND ( B.iwolflag <> '1' OR B.iwolflag IS NULL )
         ) A
          WHERE 1=1
          """ + acccdCond + """
          GROUP BY A.acccd, A.accnm, A.drcr
         """;

		return sqlRunner.getRows(sql, param);
	}

	// =====================================================================
	// 탭2 : 보통예금 (전표 단위 상세)
	// =====================================================================
	public Object selectDepositList(String start, String end, String mssec, String accnm) {
		MapSqlParameterSource param = buildBaseParam(start, end);

		String mssecCond = "";
		if (mssec != null && !mssec.trim().isEmpty()) {
			mssecCond = " AND ( B.mssec LIKE :mssec + '%' OR B.mssec IS NULL ) ";
			param.addValue("mssec", mssec.trim());
		}

		String acccdCond = "";
		if (accnm != null && !accnm.trim().isEmpty()) {
			acccdCond = " AND (acccd LIKE :acccd) ";
			param.addValue("acccd", accnm.trim() + "%");
		}

		String sql = """
         SELECT  A.yymmdd, A.spnum, A.acccd, A.accnm, A.drcr,
                 SUM(A.dramt) AS dramt,
                 SUM(A.cramt) AS cramt,
                 SUM(A.bfamt) AS bfamt,
                 MAX(A.summy) AS summy,
                 CASE WHEN A.drcr = '1'
                      THEN SUM(A.bfamt) + SUM(A.dramt) - SUM(A.cramt)
                      ELSE SUM(A.bfamt) + SUM(A.cramt) - SUM(A.dramt)
                 END AS balamt,
                 STUFF(STUFF(:frdate, 5, 0, '-'), 8, 0, '-') AS frdate,
                 STUFF(STUFF(:todate, 5, 0, '-'), 8, 0, '-') AS todate
           FROM
         (
         SELECT '00000000' AS yymmdd, '' AS spnum,
                 A.acccd, B.accnm, B.drcr,
                 A.dramt, A.cramt, '' AS summy,
                 CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt
           FROM TB_AB002 A, TB_AC001 B
          WHERE A.custcd   = B.custcd
            AND A.acccd    = B.acccd
            AND B.spyn     = '1'
            AND A.custcd   = :custcd
            AND A.spjangcd = :spjangcd
            AND A.yymm     = :indate
            AND LEFT(A.acccd, 1) IN ('1', '2', '3')
            AND NOT (A.dramt = 0 AND A.cramt = 0)

         UNION ALL

         SELECT C.spdate AS yymmdd, C.spnum,
                 B.acccd, D.accnm, D.drcr,
                 B.dramt, B.cramt, B.summy,
                 CASE WHEN D.drcr = '1' THEN B.dramt - B.cramt ELSE B.cramt - B.dramt END AS bfamt
           FROM TB_AA009 C, TB_AA010 B, TB_AC001 D
          WHERE C.custcd   = B.custcd
            AND C.spjangcd = B.spjangcd
            AND C.spdate   = B.spdate
            AND C.spnum    = B.spnum
            AND B.acccd    = D.acccd
            AND C.custcd   = :custcd
            AND C.spjangcd = :spjangcd
            AND C.spdate  >= LEFT(:frdate, 4) + '0101'
            AND C.spdate  <  :frdate
            """ + mssecCond + """
            AND ( B.iwolflag <> '1' OR B.iwolflag IS NULL )

         UNION ALL

         SELECT C.spdate AS yymmdd, C.spnum,
                 B.acccd, D.accnm, D.drcr,
                 B.dramt, B.cramt, B.summy,
                 CASE WHEN D.drcr = '1' THEN B.dramt - B.cramt ELSE B.cramt - B.dramt END AS bfamt
           FROM TB_AA009 C, TB_AA010 B, TB_AC001 D
          WHERE C.custcd   = B.custcd
            AND C.spjangcd = B.spjangcd
            AND C.spdate   = B.spdate
            AND C.spnum    = B.spnum
            AND B.acccd    = D.acccd
            AND C.custcd   = :custcd
            AND C.spjangcd = :spjangcd
            AND C.spdate BETWEEN :frdate AND :todate
            """ + mssecCond + """
            AND ( B.iwolflag <> '1' OR B.iwolflag IS NULL )
         ) A
          WHERE 1=1
          """ + acccdCond + """
          GROUP BY A.yymmdd, A.spnum, A.acccd, A.accnm, A.drcr
         """;

		return sqlRunner.getRows(sql, param);
	}

	// =====================================================================
	// 탭3 : 상세내역 (acccd 정확일치, 계정 미선택이면 결과 없음)
	// =====================================================================
	public Object selectDetailList(String start, String end, String mssec, String accnm) {
		MapSqlParameterSource param = buildBaseParam(start, end);

		// mssec : isnull(A.mssec,'') LIKE :mssec  (값 없으면 '%')
		param.addValue("mssec", (mssec == null || mssec.trim().isEmpty()) ? "%" : mssec.trim() + "%");

		// acccd : 정확일치. 값 없으면 '' → 결과 없음(원본 동작)
		param.addValue("acccd", (accnm == null) ? "" : accnm.trim());

		// 표시용 재원명 (입력칸 없으므로 공백)
		param.addValue("messnm", "");

		String sql = """
         SELECT A.yymmdd, A.spnum, A.acccd, A.accnm, A.summy, A.drcr,
                SUM(A.dramt) AS dramt,
                SUM(A.cramt) AS cramt,
                SUM(A.bfamt) AS bfamt,
                MAX(A.rowseq) AS rowseq,
                CAST(:frdate AS CHAR(8)) AS frdate,
                CAST(:todate AS CHAR(8)) AS todate,
                CAST(:messnm AS VARCHAR(50)) AS messnm
           FROM
         (
         SELECT '00000000' AS yymmdd, '0000' AS spnum,
                A.acccd, B.accnm, '' AS summy, B.drcr,
                A.dramt, A.cramt,
                CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt,
                '0' AS rowseq
           FROM TB_AB002 A, TB_AC001 B
          WHERE A.custcd   = B.custcd
            AND A.acccd    = B.acccd
            AND B.spyn     = '1'
            AND A.custcd   = :custcd
            AND A.spjangcd = :spjangcd
            AND A.yymm     = LEFT(:indate, 6)
            AND LEFT(A.acccd, 1) IN ('1', '2', '3')
            AND NOT (A.dramt = 0 AND A.cramt = 0)

         UNION ALL

         SELECT '00000000', '0000',
                A.acccd, B.accnm, '', B.drcr,
                A.dramt, A.cramt,
                CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt,
                A.rowseq
           FROM TB_AA010 A, TB_AC001 B, TB_AA009 C
          WHERE A.custcd   = B.custcd
            AND A.acccd    = B.acccd
            AND A.custcd   = C.custcd
            AND A.spjangcd = C.spjangcd
            AND A.spdate   = C.spdate
            AND A.spnum    = C.spnum
            AND B.spyn     = '1'
            AND A.custcd   = :custcd
            AND A.spjangcd = :spjangcd
            AND A.spdate  >= LEFT(:frdate, 4) + '0101'
            AND A.spdate  <  :frdate
            AND ( ISNULL(A.mssec, '') LIKE :mssec )
            AND ( A.iwolflag <> '1' OR A.iwolflag IS NULL )
            AND A.acccd = :acccd

         UNION ALL

         SELECT A.spdate, A.spnum,
                A.acccd, B.accnm, A.summy, B.drcr,
                A.dramt, A.cramt,
                CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt,
                A.rowseq
           FROM TB_AA010 A, TB_AC001 B, TB_AA009 C
          WHERE A.custcd   = B.custcd
            AND A.acccd    = B.acccd
            AND A.custcd   = C.custcd
            AND A.spjangcd = C.spjangcd
            AND A.spdate   = C.spdate
            AND A.spnum    = C.spnum
            AND B.spyn     = '1'
            AND A.custcd   = :custcd
            AND A.spjangcd = :spjangcd
            AND A.spdate BETWEEN :frdate AND :todate
            AND NOT (A.dramt = 0 AND A.cramt = 0)
            AND ( ISNULL(A.mssec, '') LIKE :mssec )
            AND ( A.iwolflag <> '1' OR A.iwolflag IS NULL )
            AND A.acccd = :acccd
         ) A
          WHERE acccd = :acccd
          GROUP BY A.yymmdd, A.spnum, A.acccd, A.accnm, A.summy, A.drcr
         """;

		return sqlRunner.getRows(sql, param);
	}

	// =====================================================================
	// 탭4 : 사업구분-사업별 (acccd LIKE, businm/busipur)
	// =====================================================================
	public Object selectBusinessList(String start, String end, String mssec, String accnm) {
		MapSqlParameterSource param = buildBaseParam(start, end);

		// mssec : 전표블록(B 별칭) mssec LIKE
		String mssecCond = "";
		if (mssec != null && !mssec.trim().isEmpty()) {
			mssecCond = " AND ( B.mssec LIKE :mssec + '%' OR B.mssec IS NULL ) ";
			param.addValue("mssec", mssec.trim());
		}

		// acccd : LIKE
		String acccdCond = "";
		if (accnm != null && !accnm.trim().isEmpty()) {
			acccdCond = " AND (acccd LIKE :acccd) ";
			param.addValue("acccd", accnm.trim() + "%");
		}

		// busipur : 사업용도 (입력칸 없음 → 전체)
		param.addValue("busipur", "%");

		String sql = """
         SELECT  A.acccd, A.accnm, A.drcr,
                 SUM(A.dramt) AS dramt,
                 SUM(A.cramt) AS cramt,
                 SUM(A.bfamt) AS bfamt,
                 MAX(A.businm) AS businm,
                 MAX(A.bsdate) AS bsdate,
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
                 '' AS businm, '' AS bsdate,
                 CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt
           FROM TB_AB002 A, TB_AC001 B
          WHERE A.custcd   = B.custcd
            AND A.acccd    = B.acccd
            AND B.spyn     = '1'
            AND A.custcd   = :custcd
            AND A.spjangcd = :spjangcd
            AND A.yymm     = :indate
            AND NOT (A.dramt = 0 AND A.cramt = 0)

         UNION ALL

         SELECT '00000000' AS yymmdd,
                 A.acccd, B.accnm, B.drcr,
                 0 AS dramt, 0 AS cramt,
                 (SELECT businm FROM tb_x0002
                   WHERE bsdate = C.bsdate AND bseccd = C.bseccd AND busicd = C.busicd) AS businm,
                 C.bsdate + C.bseccd + C.busicd AS bsdate,
                 CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt
           FROM TB_AA010 A WITH (NOLOCK),
                 TB_AC001 B WITH (NOLOCK),
                 TB_AA009 C WITH (NOLOCK)
          WHERE A.custcd   = B.custcd
            AND A.acccd    = B.acccd
            AND A.spdate   = C.spdate
            AND A.spnum    = C.spnum
            AND B.spyn     = '1'
            AND A.custcd   = :custcd
            AND A.spjangcd = :spjangcd
            AND A.spdate  >= :indate + '00'
            AND A.spdate  <  :frdate
            AND NOT (A.dramt = 0 AND A.cramt = 0)
            AND LEN(C.bsdate + C.bseccd) > 0
            AND C.busipur LIKE :busipur

         UNION ALL

         SELECT  C.spdate AS yymmdd,
                 B.acccd, D.accnm, D.drcr,
                 B.dramt, B.cramt,
                 (SELECT businm FROM tb_x0002
                   WHERE bsdate = C.bsdate AND bseccd = C.bseccd AND busicd = C.busicd) AS businm,
                 C.bsdate + C.bseccd + C.busicd AS bsdate,
                 0 AS bfamt
           FROM TB_AA009 C WITH (NOLOCK),
                 TB_AA010 B WITH (NOLOCK),
                 TB_AC001 D WITH (NOLOCK)
          WHERE C.custcd   = B.custcd
            AND C.spjangcd = B.spjangcd
            AND C.spdate   = B.spdate
            AND C.spnum    = B.spnum
            AND B.acccd    = D.acccd
            AND C.custcd   = :custcd
            AND C.spjangcd = :spjangcd
            AND C.spdate BETWEEN :frdate AND :todate
            """ + mssecCond + """
            AND ( B.iwolflag <> '1' OR B.iwolflag IS NULL )
            AND C.busipur LIKE :busipur
            AND LEN(C.bsdate + C.bseccd) > 0
         ) A
          WHERE 1=1
          """ + acccdCond + """
          GROUP BY A.acccd, A.accnm, A.drcr
         """;

		return sqlRunner.getRows(sql, param);
	}

	// =====================================================================
	// 탭5 : 상세내역 (acccd 정확일치, busipur/bsdate)
	// =====================================================================
	public Object selectDetail2List(String start, String end, String mssec, String accnm) {
		MapSqlParameterSource param = buildBaseParam(start, end);

		// mssec : (A.mssec LIKE :mssec + '%') OR A.mssec IS NULL  → 값 없으면 '%'
		param.addValue("mssec", (mssec == null || mssec.trim().isEmpty()) ? "" : mssec.trim());

		// acccd : 정확일치. 값 없으면 '' → 결과 없음
		param.addValue("acccd", (accnm == null) ? "" : accnm.trim());

		// busipur / bsdate : 입력칸 없음 → 전체
		param.addValue("busipur", "%");
		param.addValue("bsdate",  "%");

		// 표시용 재원명
		param.addValue("messnm", "");

		String sql = """
         SELECT A.yymmdd, A.spnum, A.acccd, A.accnm, A.summy, A.drcr,
                SUM(A.dramt) AS dramt,
                SUM(A.cramt) AS cramt,
                SUM(A.bfamt) AS bfamt,
                MAX(A.rowseq) AS rowseq,
                CAST(:frdate AS CHAR(8)) AS frdate,
                CAST(:todate AS CHAR(8)) AS todate,
                CAST(:messnm AS VARCHAR(50)) AS messnm
           FROM
         (
         SELECT '00000000' AS yymmdd, '0000' AS spnum,
                A.acccd, B.accnm, '' AS summy, B.drcr,
                A.dramt, A.cramt,
                CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt,
                '0' AS rowseq
           FROM TB_AB001 A WITH (NOLOCK), TB_AC001 B WITH (NOLOCK)
          WHERE A.custcd   = B.custcd
            AND A.acccd    = B.acccd
            AND B.spyn     = '1'
            AND A.custcd   = :custcd
            AND A.spjangcd = :spjangcd
            AND A.yymmdd  >= :indate
            AND A.yymmdd  <  :frdate
            AND LEFT(A.acccd, 1) IN ('1', '2', '3')
            AND NOT (A.dramt = 0 AND A.cramt = 0)

         UNION ALL

         SELECT A.spdate, A.spnum,
                A.acccd, B.accnm, A.summy, B.drcr,
                A.dramt, A.cramt,
                CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt,
                A.rowseq
           FROM TB_AA010 A WITH (NOLOCK),
                TB_AC001 B WITH (NOLOCK),
                TB_AA009 C WITH (NOLOCK)
          WHERE A.custcd   = B.custcd
            AND A.acccd    = B.acccd
            AND B.spyn     = '1'
            AND A.spdate   = C.spdate
            AND A.spnum    = C.spnum
            AND A.custcd   = :custcd
            AND A.spjangcd = :spjangcd
            AND A.spdate BETWEEN :frdate AND :todate
            AND ( (A.mssec LIKE :mssec + '%') OR A.mssec IS NULL )
            AND NOT (A.dramt = 0 AND A.cramt = 0)
            AND ( A.iwolflag <> '1' OR A.iwolflag IS NULL )
            AND C.busipur LIKE :busipur
            AND ( C.bsdate + C.bseccd + C.busicd LIKE :bsdate )
         ) A
          WHERE acccd = :acccd
          GROUP BY A.yymmdd, A.spnum, A.acccd, A.accnm, A.summy, A.drcr
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

	// =====================================================================
	// 사업장 → custcd 매핑
	// =====================================================================
	private Map<String, String> getBizInfoBySpjangcd(String spjangcd) {
		MapSqlParameterSource param = new MapSqlParameterSource()
																		.addValue("spjangcd", spjangcd);

		String sql = """
        SELECT saupnum, custcd, spjangnm
        FROM tb_xa012
        WHERE spjangcd = :spjangcd
        """;

		Map<String, Object> row = sqlRunner.getRow(sql, param);

		Map<String, String> result = new HashMap<>();
		result.put("saupnum",  "");
		result.put("custcd",   "");
		result.put("spjangnm", "");

		if (row == null || row.isEmpty()) return result;

		result.put("saupnum",  row.get("saupnum")  == null ? "" : String.valueOf(row.get("saupnum")).trim());
		result.put("custcd",   row.get("custcd")   == null ? "" : String.valueOf(row.get("custcd")).trim());
		result.put("spjangnm", row.get("spjangnm") == null ? "" : String.valueOf(row.get("spjangnm")).trim());

		return result;
	}
}