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
public class BusinessInOutService { // 사업별입출현황

	@Autowired
	SqlRunner sqlRunner;

	private MapSqlParameterSource buildBaseParam(String start, String end,
																							 String bsdate, String bseccd, String busicd) {
		MapSqlParameterSource param = new MapSqlParameterSource();
		String spjangcd = TenantContext.get();
		Map<String, String> biz = getBizInfoBySpjangcd(spjangcd);
		String custcd   = biz.get("custcd");
		String frdate = (start == null ? "" : start.replace("-", ""));
		String todate = (end   == null ? "" : end.replace("-", ""));
		param.addValue("spjangcd", spjangcd);
		param.addValue("custcd",   custcd);
		// 출력물 머리글(PB gf_spjangnm())
		param.addValue("spjangnm", biz.get("spjangnm"));
		param.addValue("frdate",   frdate);
		param.addValue("todate",   todate);
		// gf_chk_null → '%'
		param.addValue("bsdate", (bsdate == null || bsdate.trim().isEmpty()) ? "%" : bsdate.trim());
		param.addValue("bseccd", (bseccd == null || bseccd.trim().isEmpty()) ? "%" : bseccd.trim());
		param.addValue("busicd", (busicd == null || busicd.trim().isEmpty()) ? "%" : busicd.trim());
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
	// tab2 : 세입내역 (tiosec = '1')
	//   집행:  TB_AA009 + TB_AA010 (Left(acccd,1) in ('5','8'))  → amt
	//   예산:  TB_X0007 (연초 예산)                               → price
	//   그룹:  mssec/acccd/accnm/it1nm/it2nm/businm + bsdate/bseccd/busicd + spdate/spnum
	// ============================================================
	public Object searchInList(String start, String end,
														 String bsdate, String bseccd, String busicd) {
		MapSqlParameterSource param = buildBaseParam(start, end, bsdate, bseccd, busicd);

		String sql = """
        SELECT Z.mssec,
               Z.acccd,
               Z.accnm,
               Z.it1nm,
               Z.it2nm,
               MAX(Z.summy) AS summy,
               Z.businm,
               SUM(Z.amt)   AS amt,
               SUM(Z.price) AS price,
               Z.bsdate,
               Z.bseccd,
               Z.busicd,
               Z.spdate,
               Z.spnum,
               STUFF(STUFF(:frdate,5,0,'-'),8,0,'-') AS frdate,
               STUFF(STUFF(:todate,5,0,'-'),8,0,'-') AS todate
          FROM (
            SELECT b.mssec, b.acccd, b.accnm, d.it1nm, e.it2nm, b.summy, c.businm,
                   (CASE b.drcr WHEN '1' THEN b.dramt WHEN '2' THEN b.cramt ELSE 0 END) AS amt,
                   0 AS price,
                   a.bsdate, a.bseccd, a.busicd, a.spdate, a.spnum
              FROM TB_AA009 a, TB_AA010 b, tb_x0002 c, tb_x0003 d, tb_x0004 e
             WHERE a.custcd   = b.custcd
               AND a.spjangcd = b.spjangcd
               AND a.spdate   = b.spdate
               AND a.spnum    = b.spnum
               AND a.bsdate   = c.bsdate
               AND a.bseccd   = c.bseccd
               AND a.busicd   = c.busicd
               AND a.tiosec   = d.tiosec
               AND Right(b.it1cd,3) = d.it1cd
               AND a.tiosec   = e.tiosec
               AND b.it2cd    = e.it2cd
               AND a.custcd   = :custcd
               AND a.spjangcd = :spjangcd
               AND b.tiosec IN ('1')
               AND a.spdate BETWEEN :frdate AND :todate
               AND a.bsdate LIKE :bsdate + '%'
               AND a.bseccd LIKE :bseccd + '%'
               AND a.busicd LIKE :busicd + '%'
               AND Left(b.acccd,1) IN ('5','8')

            UNION ALL

            SELECT a.mssec, a.acccd, b.accnm, d.it1nm, e.it2nm, a.summy, c.businm,
                   0 AS amt,
                   a.price,
                   a.bsdate, a.bseccd, a.busicd,
                   '' AS spdate, '' AS spnum
              FROM TB_X0007 a, TB_AC001 b, tb_x0002 c, tb_x0003 d, tb_x0004 e
             WHERE a.acccd  = b.acccd
               AND a.bsdate = c.bsdate
               AND a.bseccd = c.bseccd
               AND a.busicd = c.busicd
               AND a.tiosec = d.tiosec
               AND Right(a.it1cd,3) = d.it1cd
               AND a.tiosec = e.tiosec
               AND a.it2cd  = e.it2cd
               AND a.tiosec = '1'
               AND a.custcd = :custcd
               AND a.yyyy   = Left(:frdate, 4)
               AND a.bsdate LIKE :bsdate + '%'
               AND a.bseccd LIKE :bseccd + '%'
               AND a.busicd LIKE :busicd + '%'
          ) Z
         GROUP BY Z.mssec, Z.acccd, Z.accnm, Z.it1nm, Z.it2nm, Z.businm,
                  Z.bsdate, Z.bseccd, Z.busicd, Z.spdate, Z.spnum
         ORDER BY Z.businm, Z.acccd, Z.spdate, Z.spnum
        """;
		return sqlRunner.getRows(sql, param);
	}

	// ============================================================
	// tab5 : 세출내역 (tiosec = '2')
	//   집행:  TB_AA009 + TB_AA010 (Left(acccd,1) = '7')  → amt
	//   예산:  TB_X0007                                    → price
	//   그룹:  mssec/acccd/accnm/it1nm/it2nm/businm + bsdate/bseccd/busicd + spdate/spnum
	// ============================================================
	public Object searchOutList(String start, String end,
															String bsdate, String bseccd, String busicd) {
		MapSqlParameterSource param = buildBaseParam(start, end, bsdate, bseccd, busicd);

		String sql = """
        SELECT Z.mssec,
               Z.acccd,
               Z.accnm,
               Z.it1nm,
               Z.it2nm,
               MAX(Z.summy) AS summy,
               Z.businm,
               SUM(Z.amt)   AS amt,
               SUM(Z.price) AS price,
               Z.bsdate,
               Z.bseccd,
               Z.busicd,
               Z.spdate,
               Z.spnum,
               :spjangnm AS spjangnm,
               STUFF(STUFF(:frdate,5,0,'-'),8,0,'-') AS frdate,
               STUFF(STUFF(:todate,5,0,'-'),8,0,'-') AS todate
          FROM (
            SELECT b.mssec, b.acccd, b.accnm, d.it1nm, e.it2nm, b.summy, c.businm,
                   (CASE b.drcr WHEN '1' THEN b.dramt WHEN '2' THEN b.cramt ELSE 0 END) AS amt,
                   0 AS price,
                   a.bsdate, a.bseccd, a.busicd, a.spdate, a.spnum
              FROM TB_AA009 a, TB_AA010 b, tb_x0002 c, tb_x0003 d, tb_x0004 e
             WHERE a.custcd   = b.custcd
               AND a.spjangcd = b.spjangcd
               AND a.spdate   = b.spdate
               AND a.spnum    = b.spnum
               AND a.bsdate   = c.bsdate
               AND a.bseccd   = c.bseccd
               AND a.busicd   = c.busicd
               AND a.tiosec   = d.tiosec
               AND Right(b.it1cd,3) = d.it1cd
               AND a.tiosec   = e.tiosec
               AND b.it2cd    = e.it2cd
               AND a.custcd   = :custcd
               AND a.spjangcd = :spjangcd
               AND a.tiosec IN ('2')
               AND a.spdate BETWEEN :frdate AND :todate
               AND a.bsdate LIKE :bsdate + '%'
               AND a.bseccd LIKE :bseccd + '%'
               AND a.busicd LIKE :busicd + '%'
               AND Left(b.acccd,1) = '7'

            UNION ALL

            SELECT a.mssec, a.acccd, b.accnm, d.it1nm, e.it2nm, a.summy, c.businm,
                   0 AS amt,
                   a.price,
                   a.bsdate, a.bseccd, a.busicd,
                   '' AS spdate, '' AS spnum
              FROM TB_X0007 a, TB_AC001 b, tb_x0002 c, tb_x0003 d, tb_x0004 e
             WHERE a.acccd  = b.acccd
               AND a.bsdate = c.bsdate
               AND a.bseccd = c.bseccd
               AND a.busicd = c.busicd
               AND a.tiosec = d.tiosec
               AND Right(a.it1cd,3) = d.it1cd
               AND a.tiosec = e.tiosec
               AND a.it2cd  = e.it2cd
               AND a.tiosec = '2'
               AND a.custcd = :custcd
               AND a.yyyy   = Left(:frdate, 4)
               AND a.bsdate LIKE :bsdate + '%'
               AND a.bseccd LIKE :bseccd + '%'
               AND a.busicd LIKE :busicd + '%'
          ) Z
         GROUP BY Z.mssec, Z.acccd, Z.accnm, Z.it1nm, Z.it2nm, Z.businm,
                  Z.bsdate, Z.bseccd, Z.busicd, Z.spdate, Z.spnum
         ORDER BY Z.businm, Z.acccd, Z.spdate, Z.spnum
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

	// ============================================================
// tab3 : 관련전표 (PB w_tb_a009_popup 조회 로직 이식)
//   TB_AA009 + TB_AA010, acccd 전체('%'), tiosec 선택(전체/세입/세출)
//   금액 = dramt<>0 이면 dramt, 아니면 cramt
// ============================================================
	public Object searchRelatedList(String start, String end,
																	String bsdate, String bseccd, String busicd,
																	String tiosec) {
		MapSqlParameterSource param = buildBaseParam(start, end, bsdate, bseccd, busicd);
		param.addValue("acccd", "%");
		param.addValue("tiosec", (tiosec == null || tiosec.trim().isEmpty()) ? "%" : tiosec.trim());

		String sql = """
        SELECT A.custcd,
               A.spjangcd,
               A.spdate,
               A.spnum,
               A.tiosec,
               A.cashyn,
               A.busipur,
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
               A.remark,
               A.taxdate,
               A.taxnum,
               A.subject,
               SUM(ISNULL(B.dramt,0)) AS dramt,
               SUM(ISNULL(B.cramt,0)) AS cramt,
               MIN(B.comnote)         AS summy
          FROM TB_AA009 A, TB_AA010 B
         WHERE A.custcd   = B.custcd
           AND A.spjangcd = B.spjangcd
           AND A.spdate   = B.spdate
           AND A.spnum    = B.spnum
           AND A.custcd   = :custcd
           AND A.spjangcd = :spjangcd
           AND A.spdate   BETWEEN :frdate AND :todate
           AND A.bsdate  LIKE :bsdate + '%'
           AND A.bseccd  LIKE :bseccd + '%'
           AND A.busicd  LIKE :busicd + '%'
           AND B.acccd   LIKE :acccd
           AND A.tiosec  LIKE :tiosec
         GROUP BY A.custcd, A.spjangcd, A.spdate, A.spnum, A.tiosec, A.cashyn,
                  A.busipur, A.spoccu, A.remark, A.taxdate, A.taxnum, A.subject
         ORDER BY A.spdate, A.spnum
        """;
		return sqlRunner.getRows(sql, param);
	}

	// ============================================================
// tab4 : 계좌현황 (PB w_tb_a010_acc 조회 로직 이식)
//   TB_AA010 + TB_AC001 + TB_AA040(계좌) + TB_AA009, acccd='1014' 고정(PB 원본과 동일)
//   dramt/cramt는 계좌별 원장 합계, bfamt는 차대구분에 따른 부호가 적용된 순증감액
//   (잔액 누적합(cumulativeSum)은 프론트에서 계좌 그룹 단위로 계산)
// ============================================================
	public Object searchAccountList(String start, String end,
																	String bsdate, String bseccd, String busicd) {
		MapSqlParameterSource param = buildBaseParam(start, end, bsdate, bseccd, busicd);

		String sql = """
        SELECT Z.yymmdd,
               Z.acccd,
               Z.accnm,
               Z.it1cd,
               Z.it1nm,
               Z.drcr,
               SUM(Z.dramt) AS dramt,
               SUM(Z.cramt) AS cramt,
               SUM(Z.bfamt) AS bfamt,
               Z.spnum
          FROM (
            SELECT A.spdate AS yymmdd,
                   A.acccd,
                   B.accnm,
                   A.bankcd AS it1cd,
                   C.banknm AS it1nm,
                   B.drcr,
                   A.dramt,
                   A.cramt,
                   CASE WHEN B.drcr = '1' THEN A.dramt - A.cramt ELSE A.cramt - A.dramt END AS bfamt,
                   A.spnum
              FROM TB_AA010 A, TB_AC001 B, TB_AA040 C, TB_AA009 D
             WHERE A.custcd   = B.custcd
               AND A.acccd    = B.acccd
               AND A.custcd   = C.custcd
               AND A.bankcd   = C.bank + C.bankcd
               AND A.spdate   = D.spdate
               AND A.spnum    = D.spnum
               AND B.spyn     = '1'
               AND A.custcd   = :custcd
               AND A.spjangcd = :spjangcd
               AND A.spdate   BETWEEN :frdate AND :todate
               AND NOT (A.dramt = 0 AND A.cramt = 0)
               AND D.bsdate = :bsdate AND D.bseccd = :bseccd AND D.busicd = :busicd
          ) Z
         WHERE Z.acccd = '1014'
         GROUP BY Z.yymmdd, Z.acccd, Z.accnm, Z.it1cd, Z.it1nm, Z.drcr, Z.spnum
         ORDER BY Z.accnm, Z.it1nm, Z.yymmdd, Z.spnum
        """;
		return sqlRunner.getRows(sql, param);
	}

	// ============================================================
// tab6 : 사업손익집계현황 - 사업상세 (PB d_book23_3 이식)
//   TB_AA009 + TB_AA010 + TB_AC001, Left(acccd,1) in ('5','7','8')
//   구분(수입/영업외수익/지출)은 프론트에서 acccd 첫글자로 계산
//   순이익 = sum(cramt) - sum(dramt) (프론트에서 계산)
// ============================================================
	public Object searchProfitDetailList(String start, String end,
																			 String bsdate, String bseccd, String busicd) {
		MapSqlParameterSource param = buildBaseParam(start, end, bsdate, bseccd, busicd);
		// buildBaseParam이 만드는 :busicd 등은 그대로 재사용, businm은 이 쿼리에서 미사용

		String sql = """
        SELECT C.acccd,
               C.formnm,
               SUM(B.dramt) AS dramt,
               SUM(B.cramt) AS cramt
          FROM TB_AA009 A, TB_AA010 B, TB_AC001 C
         WHERE A.custcd   = B.custcd
           AND A.spjangcd = B.spjangcd
           AND A.spdate   = B.spdate
           AND A.spnum    = B.spnum
           AND B.acccd    = C.acccd
           AND A.custcd   = :custcd
           AND A.spjangcd = :spjangcd
           AND A.bsdate   = :bsdate
           AND A.bseccd   = :bseccd
           AND A.busicd   = :busicd
           AND Left(C.acccd, 1) IN ('5', '7', '8')
           AND A.spdate BETWEEN :frdate AND :todate
         GROUP BY C.acccd, C.accnm, C.formnm
         ORDER BY C.acccd
        """;
		return sqlRunner.getRows(sql, param);
	}

	// ============================================================
// tab6 : 사업손익집계현황 - 사업별현황 (PB d_book23_4 이식)
//   TB_AA009 + TB_AA010 + TB_AC001 + tb_x0002(사업명), Left(acccd,1) in ('5','7','8')
//   전 사업 대상 — 특정 사업(bsdate/bseccd/busicd) 필터 없음
//   조회기간은 연 단위로 확장: frdate년 1/1 ~ todate년 12/31
//   구분/계/순이익은 프론트에서 acccd 기준으로 계산
// ============================================================
	public Object searchProfitSummaryList(String start, String end) {
		String spjangcd = TenantContext.get();
		String custcd   = getBizInfoBySpjangcd(spjangcd).get("custcd");
		String frdate = (start == null ? "" : start.replace("-", ""));
		String todate = (end   == null ? "" : end.replace("-", ""));

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("custcd", custcd);
		param.addValue("spjangcd", spjangcd);
		param.addValue("frdate", frdate);
		param.addValue("todate", todate);

		String sql = """
        SELECT C.acccd,
               C.formnm,
               SUM(B.dramt) AS dramt,
               SUM(B.cramt) AS cramt,
               D.businm
          FROM TB_AA009 A, TB_AA010 B, TB_AC001 C, tb_x0002 D
         WHERE A.custcd   = B.custcd
           AND A.spjangcd = B.spjangcd
           AND A.spdate   = B.spdate
           AND A.spnum    = B.spnum
           AND B.acccd    = C.acccd
           AND A.bsdate   = D.bsdate
           AND A.bseccd   = D.bseccd
           AND A.busicd   = D.busicd
           AND A.custcd   = :custcd
           AND A.spjangcd = :spjangcd
           AND Left(C.acccd, 1) IN ('5', '7', '8')
           AND A.spdate BETWEEN LEFT(:frdate, 4) + '0101' AND LEFT(:todate, 4) + '1231'
         GROUP BY C.acccd, C.accnm, C.formnm, D.businm
         ORDER BY D.businm, C.acccd
        """;
		return sqlRunner.getRows(sql, param);
	}

	// ============================================================
	// 사업손익상세 (PB d_book23_5 이식)
	//   TB_AA009 + TB_AA010 + TB_AC001 + tb_x0002/x0003/x0004
	//   Left(acccd,1) in ('5','7','8'), 전표일자(spdate) 단위 상세
	//   구분(수입/영업외수익/지출)·계·순이익은 프론트에서 acccd 첫글자로 계산
	//   순이익 = sum(cramt) - sum(dramt)  (5/7/8 전체 대상, PB 식 전개 시 동일)
	// ============================================================
	public Object searchProfitLossList(String start, String end,
																		 String bsdate, String bseccd, String busicd, String businm) {
		MapSqlParameterSource param = buildBaseParam(start, end, bsdate, bseccd, busicd);
		param.addValue("businm", (businm == null) ? "" : businm.trim());

		String sql = """
        SELECT B.spdate,
               C.acccd,
               C.accnm,
               C.formnm,
               E.it1nm,
               F.it2nm,
               SUM(B.dramt) AS dramt,
               SUM(B.cramt) AS cramt,
               CAST(:businm AS VARCHAR(100)) AS businm
          FROM TB_AA009 A, TB_AA010 B, TB_AC001 C,
               tb_x0002 D, tb_x0003 E, tb_x0004 F
         WHERE A.custcd   = B.custcd
           AND A.spjangcd = B.spjangcd
           AND A.spdate   = B.spdate
           AND A.spnum    = B.spnum
           AND B.acccd    = C.acccd
           AND A.bsdate   = D.bsdate
           AND A.bseccd   = D.bseccd
           AND A.busicd   = D.busicd
           AND A.tiosec   = E.tiosec
           AND Right(B.it1cd,3) = E.it1cd
           AND A.tiosec   = F.tiosec
           AND B.it2cd    = F.it2cd
           AND A.custcd   = :custcd
           AND A.spjangcd = :spjangcd
           AND A.bsdate   = :bsdate
           AND A.bseccd   = :bseccd
           AND A.busicd   = :busicd
           AND Left(C.acccd, 1) IN ('5', '7', '8')
           AND A.spdate BETWEEN :frdate AND :todate
         GROUP BY C.acccd, C.accnm, C.formnm, B.spdate, E.it1nm, F.it2nm
         ORDER BY Left(C.acccd,1), B.spdate, C.acccd, E.it1nm, F.it2nm
        """;
		return sqlRunner.getRows(sql, param);
	}
}