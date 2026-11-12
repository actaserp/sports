package mes.app.budget.service;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 세출부 — PowerBuilder w_tb_aa009_sh6 (화면 d_tb_aa009_sh6 / 출력 d_tb_aa009_prt6) 이식.
 *  기간(시작 년월 ~ 끝 년월) · 재원(전체 '%') 으로 전표 지출(7000~7999, 8300~8499)을 관 · 항 단위로 모은다.
 *   ① 차변 합계  ② 대변 합계 × -1  ③ 지출 이월 계정(chulflag = '1') 대변
 *  항 코드 앞 2자리 = 부문(TB_X0001) 과 내부 조인. SQL 은 원본 그대로.
 *  화면용 · 출력용 SQL 은 ③ 의 금액 식만 달라서 둘 다 조회해 rows(화면) · prtRows(출력) 로 돌려준다.
 *  정렬은 원본 그룹(관)에 맞춰 관코드 → 부문순서 → 항코드. 원본 Sort 설정을 받으면 맞춘다.
 */
@Slf4j
@Service
public class ExpenseLedgerService {

	@Autowired
	SqlRunner sqlRunner;

	// 화면 d_tb_aa009_sh6
	private static final String SQL = """
		SELECT x.acccd, x.accnm, x.it1cd, x.seqno, x.dramt, x.businm, x.it1nm
		  FROM (
		  SELECT a.custcd, b.acccd, MAX(b.accnm) AS accnm, b.it1cd, c.seqno, SUM(b.dramt) AS dramt,
		         (SELECT businm FROM tb_x0002 WHERE bsdate = MAX(a.bsdate) AND bseccd = MAX(a.bseccd) AND busicd = MAX(a.busicd)) AS businm,
		         (SELECT it1nm FROM TB_X0003 WHERE it1cd = SUBSTRING(b.it1cd, 3, 3) AND tiosec = '2') AS it1nm
		    FROM TB_AA009 a, TB_AA010 b, TB_X0001 c
		   WHERE a.custcd = b.custcd AND a.spjangcd = b.spjangcd AND a.spdate = b.spdate AND a.spnum = b.spnum
		     AND b.custcd = c.custcd
		     AND SUBSTRING(b.it1cd, 1, 2) = c.bseccd
		     AND ((b.acccd BETWEEN '7000' AND '7999') OR (b.acccd BETWEEN '8300' AND '8499'))
		     AND a.custcd = :custcd
		     AND a.spjangcd = :spjangcd
		     AND SUBSTRING(a.spdate, 1, 6) BETWEEN :fr AND :to
		     AND ISNULL(b.mssec, '') LIKE :mssec
		     AND b.bumuncd <> 'ZZ'
		     AND b.dramt <> 0
		   GROUP BY c.seqno, a.custcd, b.acccd, b.it1cd
		  UNION ALL
		  SELECT a.custcd, b.acccd, MAX(b.accnm) AS accnm, b.it1cd, c.seqno, SUM(b.cramt) * -1 AS dramt,
		         (SELECT businm FROM tb_x0002 WHERE bsdate = MAX(a.bsdate) AND bseccd = MAX(a.bseccd) AND busicd = MAX(a.busicd)) AS businm,
		         (SELECT it1nm FROM TB_X0003 WHERE it1cd = SUBSTRING(b.it1cd, 3, 3) AND tiosec = '2') AS it1nm
		    FROM TB_AA009 a, TB_AA010 b, TB_X0001 c
		   WHERE a.custcd = b.custcd AND a.spjangcd = b.spjangcd AND a.spdate = b.spdate AND a.spnum = b.spnum
		     AND b.custcd = c.custcd
		     AND SUBSTRING(b.it1cd, 1, 2) = c.bseccd
		     AND ((b.acccd BETWEEN '7000' AND '7999') OR (b.acccd BETWEEN '8300' AND '8499'))
		     AND a.custcd = :custcd
		     AND a.spjangcd = :spjangcd
		     AND SUBSTRING(a.spdate, 1, 6) BETWEEN :fr AND :to
		     AND ISNULL(b.mssec, '') LIKE :mssec
		     AND b.bumuncd <> 'ZZ'
		     AND b.cramt <> 0
		   GROUP BY c.seqno, a.custcd, b.acccd, b.it1cd
		  UNION ALL
		  SELECT A.custcd, A.acccd, A.accnm, '' AS it1cd, NULL AS seqno,
		         CASE WHEN A.drcr = '1' THEN SUM(A.ndramt) ELSE SUM(A.ncramt) END AS dramt,
		         '' AS businm, A.it1nm AS it1nm
		    FROM ( SELECT a.custcd, b.acccd, b.accnm AS accnm, c.drcr,
		                  CASE WHEN b.drcr = '1' THEN b.dramt ELSE b.cramt END AS ndramt,
		                  CASE WHEN b.drcr = '1' THEN b.dramt ELSE b.cramt END AS ncramt,
		                  B.summy AS it1nm
		             FROM TB_AA009 a, TB_AA010 b, TB_AC001 c
		            WHERE a.custcd = b.custcd AND a.spjangcd = b.spjangcd AND a.spdate = b.spdate AND a.spnum = b.spnum
		              AND a.custcd = c.custcd AND b.acccd = c.acccd
		              AND c.chulflag = '1'
		              AND a.custcd = :custcd
		              AND a.spjangcd = :spjangcd
		              AND SUBSTRING(a.spdate, 1, 6) BETWEEN :fr AND :to
		              AND ISNULL(b.mssec, '') LIKE :mssec
		              AND b.bumuncd <> 'ZZ'
		              AND B.cramt <> 0 ) A
		   GROUP BY A.custcd, A.acccd, A.accnm, A.drcr, A.it1nm
		  ) x
		 ORDER BY x.acccd, x.seqno, x.it1cd, x.dramt DESC
		""";

	// 출력 d_tb_aa009_prt6 (이월 계정 부분의 금액 식만 다르다)
	private static final String SQL_PRT = """
		SELECT x.acccd, x.accnm, x.it1cd, x.seqno, x.dramt, x.businm, x.it1nm
		  FROM (
		  SELECT a.custcd, b.acccd, MAX(b.accnm) AS accnm, b.it1cd, c.seqno, SUM(b.dramt) AS dramt,
		         (SELECT businm FROM tb_x0002 WHERE bsdate = MAX(a.bsdate) AND bseccd = MAX(a.bseccd) AND busicd = MAX(a.busicd)) AS businm,
		         (SELECT it1nm FROM TB_X0003 WHERE it1cd = SUBSTRING(b.it1cd, 3, 3) AND tiosec = '2') AS it1nm
		    FROM TB_AA009 a, TB_AA010 b, TB_X0001 c
		   WHERE a.custcd = b.custcd AND a.spjangcd = b.spjangcd AND a.spdate = b.spdate AND a.spnum = b.spnum
		     AND b.custcd = c.custcd
		     AND SUBSTRING(b.it1cd, 1, 2) = c.bseccd
		     AND ((b.acccd BETWEEN '7000' AND '7999') OR (b.acccd BETWEEN '8300' AND '8499'))
		     AND a.custcd = :custcd
		     AND a.spjangcd = :spjangcd
		     AND SUBSTRING(a.spdate, 1, 6) BETWEEN :fr AND :to
		     AND ISNULL(b.mssec, '') LIKE :mssec
		     AND b.bumuncd <> 'ZZ'
		     AND b.dramt <> 0
		   GROUP BY c.seqno, a.custcd, b.acccd, b.it1cd
		  UNION ALL
		  SELECT a.custcd, b.acccd, MAX(b.accnm) AS accnm, b.it1cd, c.seqno, SUM(b.cramt) * -1 AS dramt,
		         (SELECT businm FROM tb_x0002 WHERE bsdate = MAX(a.bsdate) AND bseccd = MAX(a.bseccd) AND busicd = MAX(a.busicd)) AS businm,
		         (SELECT it1nm FROM TB_X0003 WHERE it1cd = SUBSTRING(b.it1cd, 3, 3) AND tiosec = '2') AS it1nm
		    FROM TB_AA009 a, TB_AA010 b, TB_X0001 c
		   WHERE a.custcd = b.custcd AND a.spjangcd = b.spjangcd AND a.spdate = b.spdate AND a.spnum = b.spnum
		     AND b.custcd = c.custcd
		     AND SUBSTRING(b.it1cd, 1, 2) = c.bseccd
		     AND ((b.acccd BETWEEN '7000' AND '7999') OR (b.acccd BETWEEN '8300' AND '8499'))
		     AND a.custcd = :custcd
		     AND a.spjangcd = :spjangcd
		     AND SUBSTRING(a.spdate, 1, 6) BETWEEN :fr AND :to
		     AND ISNULL(b.mssec, '') LIKE :mssec
		     AND b.bumuncd <> 'ZZ'
		     AND b.cramt <> 0
		   GROUP BY c.seqno, a.custcd, b.acccd, b.it1cd
		  UNION ALL
		  SELECT A.custcd, A.acccd, A.accnm, '' AS it1cd, NULL AS seqno,
		         CASE WHEN A.drcr = '1' THEN SUM(A.ndramt) - SUM(A.ncramt) ELSE SUM(A.ncramt) - SUM(A.ndramt) END AS dramt,
		         '' AS businm, A.it1nm AS it1nm
		    FROM ( SELECT a.custcd, b.acccd, b.accnm AS accnm, c.drcr,
		                  B.cramt AS ndramt,
		                  0 AS ncramt,
		                  B.summy AS it1nm
		             FROM TB_AA009 a, TB_AA010 b, TB_AC001 c
		            WHERE a.custcd = b.custcd AND a.spjangcd = b.spjangcd AND a.spdate = b.spdate AND a.spnum = b.spnum
		              AND a.custcd = c.custcd AND b.acccd = c.acccd
		              AND c.chulflag = '1'
		              AND a.custcd = :custcd
		              AND a.spjangcd = :spjangcd
		              AND SUBSTRING(a.spdate, 1, 6) BETWEEN :fr AND :to
		              AND ISNULL(b.mssec, '') LIKE :mssec
		              AND b.bumuncd <> 'ZZ'
		              AND B.cramt <> 0 ) A
		   GROUP BY A.custcd, A.acccd, A.accnm, A.drcr, A.it1nm
		  ) x
		 ORDER BY x.acccd, x.seqno, x.it1cd, x.dramt DESC
		""";

	/** fr, to: yyyymm */
	public Map<String, Object> read(String fr, String to, String mssec) {
		if (fr == null || !fr.trim().matches("\\d{6}") || to == null || !to.trim().matches("\\d{6}"))
			throw new IllegalArgumentException("기간을 년월(6자리)로 입력하십시오.");
		fr = fr.trim();
		to = to.trim();
		if (fr.compareTo(to) > 0) throw new IllegalArgumentException("시작 년월이 끝 년월보다 클 수 없습니다.");
		String spjangcd = TenantContext.get();
		Map<String, Object> biz = sqlRunner.getRow(
			"SELECT custcd, spjangnm FROM tb_xa012 WHERE spjangcd = :spjangcd",
			new MapSqlParameterSource().addValue("spjangcd", spjangcd));
		String custcd = biz == null || biz.get("custcd") == null ? "" : String.valueOf(biz.get("custcd")).trim();
		String ms = mssec == null || mssec.trim().isEmpty() ? "%" : mssec.trim();

		MapSqlParameterSource ps = new MapSqlParameterSource()
																 .addValue("custcd", custcd).addValue("spjangcd", spjangcd)
																 .addValue("fr", fr).addValue("to", to).addValue("mssec", ms);
		List<Map<String, Object>> rows = sqlRunner.getRows(SQL, ps);
		List<Map<String, Object>> prtRows = sqlRunner.getRows(SQL_PRT, ps);

		Map<String, Object> result = new HashMap<>();
		result.put("fr", fr);
		result.put("to", to);
		result.put("spjangnm", biz == null || biz.get("spjangnm") == null ? "" : String.valueOf(biz.get("spjangnm")).trim());
		result.put("rows", rows);
		result.put("prtRows", prtRows);
		return result;
	}
}