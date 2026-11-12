package mes.app.budget.service;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 세입부 — PowerBuilder 세입부 창 (dw_2 d_tb_aa009_sh1 / 출력 d_tb_aa009_prt1) 이식.
 *  기간(시작 년월 ~ 끝 년월) · 재원(전체 '%') 으로 전표 수입(5000~5599, 8100~8299)을 관 · 항 단위로 모은다.
 *   ① 대변 합계  ② 차변 합계 × -1  ③ 이월 계정(ipflag = '1') 전년이월 + 기간 차변
 *  SQL 은 원본 그대로. 화면용 · 출력용 두 SQL 은 ③ 의 금액 식만 다른데,
 *  TB_AC001 에 ipflag = '1' 계정이 없어 ③ 은 지금 아무 행도 내지 않는다 → 화면용(sh1) SQL 하나로 쓴다.
 *  정렬은 원본 그룹(관)에 맞춰 관코드 → 항코드. 원본 Sort 설정을 받으면 맞춘다.
 */
@Slf4j
@Service
public class IncomeLedgerService {

	@Autowired
	SqlRunner sqlRunner;

	private static final String SQL = """
		SELECT x.acccd, x.accnm, x.it1cd, x.cramt, x.businm, x.it1nm
		  FROM (
		  SELECT a.custcd, b.acccd, MAX(b.accnm) AS accnm, b.it1cd, SUM(b.cramt) AS cramt,
		         (SELECT businm FROM tb_x0002 WHERE bsdate = MAX(a.bsdate) AND bseccd = MAX(a.bseccd) AND busicd = MAX(a.busicd)) AS businm,
		         (SELECT it1nm FROM TB_X0003 WHERE it1cd = SUBSTRING(b.it1cd, 3, 3) AND tiosec = '1') AS it1nm
		    FROM TB_AA009 a, TB_AA010 b
		   WHERE a.custcd = b.custcd AND a.spjangcd = b.spjangcd AND a.spdate = b.spdate AND a.spnum = b.spnum
		     AND ((b.acccd BETWEEN '5000' AND '5599') OR (b.acccd BETWEEN '8100' AND '8299'))
		     AND a.custcd = :custcd
		     AND a.spjangcd = :spjangcd
		     AND SUBSTRING(a.spdate, 1, 6) BETWEEN :fr AND :to
		     AND ISNULL(b.mssec, '') LIKE :mssec
		     AND b.bumuncd <> 'ZZ'
		     AND b.cramt <> 0
		     AND (b.iwolflag <> '1' OR b.iwolflag IS NULL)
		   GROUP BY a.custcd, b.acccd, b.it1cd
		  UNION ALL
		  SELECT a.custcd, b.acccd, MAX(b.accnm) AS accnm, b.it1cd, SUM(b.dramt) * -1 AS cramt,
		         (SELECT businm FROM tb_x0002 WHERE bsdate = MAX(a.bsdate) AND bseccd = MAX(a.bseccd) AND busicd = MAX(a.busicd)) AS businm,
		         (SELECT it1nm FROM TB_X0003 WHERE it1cd = SUBSTRING(b.it1cd, 3, 3) AND tiosec = '1') AS it1nm
		    FROM TB_AA009 a, TB_AA010 b
		   WHERE a.custcd = b.custcd AND a.spjangcd = b.spjangcd AND a.spdate = b.spdate AND a.spnum = b.spnum
		     AND ((b.acccd BETWEEN '5000' AND '5599') OR (b.acccd BETWEEN '8100' AND '8299'))
		     AND a.custcd = :custcd
		     AND a.spjangcd = :spjangcd
		     AND SUBSTRING(a.spdate, 1, 6) BETWEEN :fr AND :to
		     AND ISNULL(b.mssec, '') LIKE :mssec
		     AND b.bumuncd <> 'ZZ'
		     AND b.dramt <> 0
		     AND (b.iwolflag <> '1' OR b.iwolflag IS NULL)
		   GROUP BY a.custcd, b.acccd, b.it1cd
		  UNION ALL
		  SELECT A.custcd, A.acccd, A.accnm, '' AS it1cd,
		         CASE WHEN A.drcr = '1' THEN SUM(A.ndramt) - SUM(A.ncramt) ELSE SUM(A.ncramt) - SUM(A.ndramt) END AS cramt,
		         '' AS businm, A.it1nm AS it1nm
		    FROM ( SELECT A.custcd AS custcd, A.acccd AS acccd, A.accnm AS accnm, A.drcr AS drcr,
		                  SUM(ISNULL(B.dramt, 0)) AS ndramt, SUM(ISNULL(B.cramt, 0)) AS ncramt, '전년이월' AS it1nm
		             FROM TB_AC001 A, TB_AB001 B
		            WHERE A.custcd = B.custcd AND A.acccd = B.acccd
		              AND b.custcd = :custcd
		              AND b.spjangcd = :spjangcd
		              AND A.dcpl = '1' AND A.ipflag = '1'
		              AND B.yymmdd = LEFT(:fr, 4) + '0000'
		            GROUP BY A.custcd, A.acccd, A.accnm, A.drcr
		           UNION ALL
		           SELECT a.custcd, b.acccd, b.accnm AS accnm, c.drcr, B.dramt AS ndramt, 0 AS ncramt, B.summy AS it1nm
		             FROM TB_AA009 a, TB_AA010 b, TB_AC001 c
		            WHERE a.custcd = b.custcd AND a.spjangcd = b.spjangcd AND a.spdate = b.spdate AND a.spnum = b.spnum
		              AND a.custcd = c.custcd AND b.acccd = c.acccd
		              AND c.ipflag = '1'
		              AND a.custcd = :custcd
		              AND a.spjangcd = :spjangcd
		              AND SUBSTRING(a.spdate, 1, 6) BETWEEN :fr AND :to
		              AND ISNULL(b.mssec, '') LIKE :mssec
		              AND b.bumuncd <> 'ZZ'
		              AND B.dramt <> 0
		              AND (b.iwolflag <> '1' OR b.iwolflag IS NULL) ) A
		   GROUP BY A.custcd, A.acccd, A.accnm, A.drcr, A.it1nm
		  ) x
		 ORDER BY x.acccd, x.it1cd, x.cramt DESC
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

		List<Map<String, Object>> rows = sqlRunner.getRows(SQL, new MapSqlParameterSource()
																															.addValue("custcd", custcd).addValue("spjangcd", spjangcd)
																															.addValue("fr", fr).addValue("to", to).addValue("mssec", ms));

		Map<String, Object> result = new HashMap<>();
		result.put("fr", fr);
		result.put("to", to);
		result.put("spjangnm", biz == null || biz.get("spjangnm") == null ? "" : String.valueOf(biz.get("spjangnm")).trim());
		result.put("rows", rows);
		return result;
	}
}