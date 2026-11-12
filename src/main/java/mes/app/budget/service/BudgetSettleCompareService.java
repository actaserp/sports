package mes.app.budget.service;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/**
 * 예산 및 결산대조표 — PowerBuilder w_tb_aa009_sh12 이식.
 *  탭1 관1        : d_tb_aa009_sh12_1(수입) · sh12_2(지출) · sh12_3(전기이월 보정) → 화면 sh12_4
 *  탭2 수입        : d_tb_aa009_sh12_11 → 화면 sh12_5
 *  탭3 지출        : d_tb_aa009_sh12_21 → 화면 sh12_6
 *  탭4 사업별대조표 : d_tb_aa009_sh12_1be · sh12_2be · sh12_3 → 화면 sh12_7
 *  합계 · 전기이월금 · 차기이월금 · 총계 행은 원본 ue_retrieve / wf_iwolamt 식 그대로 계산한다.
 *  SQL 은 원본 그대로(주석 처리된 조건은 뺐다). 정렬은 원본 Sort 설정을 받기 전까지 accseq → 관코드 순.
 */
@Slf4j
@Service
public class BudgetSettleCompareService {

	@Autowired
	SqlRunner sqlRunner;

	// ── d_tb_aa009_sh12_1 수입 ──
	private static final String SQL_REV = """
		SELECT x.acccd, x.seq, x.amt1, x.amt2,
		       (SELECT TOP 1 accnm FROM TB_AC001 WHERE custcd = :custcd AND acccd = x.acccd) AS accnm
		  FROM (
		  SELECT a.acccd, a.seq, SUM(a.amt1) AS amt1, SUM(a.amt2) AS amt2
		    FROM ( SELECT a.acccd, b.accseq AS seq, SUM(a.price) AS amt1, CAST(0 AS numeric(13,0)) AS amt2
		             FROM TB_X0007 a WITH (NOLOCK)
		             LEFT OUTER JOIN tb_ac001 b WITH (NOLOCK) ON a.acccd = b.acccd
		            WHERE a.tiosec = '1'
		              AND a.custcd = :custcd
		              AND a.spjangcd = :spjangcd
		              AND a.yyyy = :yyyy
		              AND ISNULL(a.bsdate + a.bseccd + a.busicd, '') LIKE :busicd
		            GROUP BY a.acccd, b.accseq
		           UNION
		           SELECT b.acccd AS acccd, c.accseq AS seq, CAST(0 AS numeric(13,0)) AS amt1,
		                  SUM(b.cramt) - SUM(b.dramt) AS amt2
		             FROM TB_AA009 a WITH (NOLOCK), TB_AA010 b WITH (NOLOCK), TB_AC001 c WITH (NOLOCK)
		            WHERE a.custcd = b.custcd AND a.spjangcd = b.spjangcd AND a.spdate = b.spdate AND a.spnum = b.spnum
		              AND b.acccd = c.acccd
		              AND b.bumuncd <> 'ZZ'
		              AND b.mssec LIKE :mssec + '%'
		              AND ((b.acccd BETWEEN '5000' AND '5599') OR (b.acccd BETWEEN '8100' AND '8299'))
		              AND a.custcd = :custcd
		              AND a.spjangcd = :spjangcd
		              AND SUBSTRING(a.spdate, 1, 6) BETWEEN :mm1 AND :mm2
		              AND ISNULL(a.bsdate + a.bseccd + a.busicd, '') LIKE :busicd
		            GROUP BY b.acccd, c.accseq ) a
		   GROUP BY a.acccd, a.seq
		  UNION ALL
		  SELECT a.acccd, a.seq AS seq, SUM(a.amt1) AS amt1, SUM(a.amt2) AS amt2
		    FROM ( SELECT a.acccd, b.accseq AS seq, SUM(a.price) AS amt1, CAST(0 AS numeric(13,0)) AS amt2
		             FROM TB_X0007 a WITH (NOLOCK)
		             LEFT OUTER JOIN tb_ac001 b WITH (NOLOCK) ON a.acccd = b.acccd
		            WHERE a.custcd = :custcd
		              AND a.spjangcd = :spjangcd
		              AND a.yyyy = :yyyy
		              AND a.acccd BETWEEN '1000' AND '3999'
		              AND ISNULL(a.bsdate + a.bseccd + a.busicd, '') LIKE :busicd + '%'
		              AND a.acccd IN (SELECT acccd FROM tb_ac001 WHERE ipflag = '1')
		            GROUP BY a.acccd, b.accseq
		           UNION
		           SELECT A.acccd, a.seq, 0 AS amt1,
		                  CASE WHEN A.drcr = '1' THEN SUM(A.ndramt) - SUM(A.ncramt) ELSE SUM(A.ncramt) - SUM(A.ndramt) END AS amt2
		             FROM ( SELECT A.custcd AS custcd, A.acccd AS acccd, A.accnm AS accnm, A.drcr AS drcr, A.accseq AS seq,
		                           SUM(ISNULL(B.dramt, 0)) AS ndramt, SUM(ISNULL(B.cramt, 0)) AS ncramt
		                      FROM TB_AC001 A WITH (NOLOCK), TB_AB001 B WITH (NOLOCK)
		                     WHERE A.custcd = B.custcd AND A.acccd = B.acccd
		                       AND A.dcpl = '1' AND A.ipflag = '1'
		                       AND B.yymmdd = :yyyy + '0000'
		                       AND a.custcd = :custcd
		                     GROUP BY A.custcd, A.acccd, A.accnm, A.drcr, A.accseq
		                    UNION ALL
		                    SELECT a.custcd, b.acccd, b.accnm AS accnm, c.drcr, c.accseq, B.dramt AS ndramt, 0 AS ncramt
		                      FROM TB_AA009 a WITH (NOLOCK), TB_AA010 b WITH (NOLOCK), TB_AC001 c WITH (NOLOCK)
		                     WHERE a.custcd = b.custcd AND a.spjangcd = b.spjangcd AND a.spdate = b.spdate AND a.spnum = b.spnum
		                       AND a.custcd = c.custcd AND b.acccd = c.acccd
		                       AND c.ipflag = '1'
		                       AND a.custcd = :custcd
		                       AND a.spjangcd = :spjangcd
		                       AND SUBSTRING(a.spdate, 1, 4) = :yyyy
		                       AND b.bumuncd <> 'ZZ'
		                       AND B.dramt <> 0 ) A
		            GROUP BY A.custcd, A.acccd, A.accnm, A.drcr, a.seq ) A
		   GROUP BY a.acccd, a.seq
		  ) x
		 ORDER BY x.seq, x.acccd
		""";

	// ── d_tb_aa009_sh12_2 지출 ──
	private static final String SQL_EXP = """
		SELECT x.acccd, x.seq, x.amt1, x.amt2,
		       (SELECT TOP 1 accnm FROM TB_AC001 WHERE custcd = :custcd AND acccd = x.acccd) AS accnm
		  FROM (
		  SELECT a.acccd, a.seq, SUM(a.amt1) AS amt1, SUM(a.amt2) AS amt2
		    FROM ( SELECT a.acccd, b.accseq AS seq, SUM(a.price) AS amt1, CAST(0 AS numeric(13,0)) AS amt2
		             FROM TB_X0007 a WITH (NOLOCK)
		             LEFT OUTER JOIN tb_ac001 b WITH (NOLOCK) ON a.acccd = b.acccd
		            WHERE a.tiosec = '2'
		              AND a.custcd = :custcd
		              AND a.spjangcd = :spjangcd
		              AND a.acccd BETWEEN '7000' AND '7999'
		              AND a.yyyy = :yyyy
		              AND ISNULL(a.bsdate + a.bseccd + a.busicd, '') LIKE :busicd + '%'
		            GROUP BY a.acccd, b.accseq
		           UNION
		           SELECT b.acccd AS acccd, c.accseq AS seq, CAST(0 AS numeric(13,0)) AS amt1,
		                  SUM(b.dramt) - SUM(b.cramt) AS amt2
		             FROM TB_AA009 a WITH (NOLOCK), TB_AA010 b WITH (NOLOCK), TB_AC001 c WITH (NOLOCK)
		            WHERE a.custcd = b.custcd AND a.spjangcd = b.spjangcd AND a.spdate = b.spdate AND a.spnum = b.spnum
		              AND b.acccd = c.acccd
		              AND b.bumuncd <> 'ZZ'
		              AND b.mssec LIKE :mssec + '%'
		              AND a.custcd = :custcd
		              AND a.spjangcd = :spjangcd
		              AND SUBSTRING(a.spdate, 1, 6) BETWEEN :mm1 AND :mm2
		              AND ISNULL(a.bsdate + a.bseccd + a.busicd, '') LIKE :busicd + '%'
		              AND ((b.acccd BETWEEN '7000' AND '7999') OR (b.acccd BETWEEN '8300' AND '8499') OR (b.acccd = '1360'))
		            GROUP BY b.acccd, c.accseq ) a
		   WHERE amt1 <> 0 OR amt2 <> 0
		   GROUP BY a.acccd, a.seq
		  UNION ALL
		  SELECT a.acccd, a.seq AS seq, SUM(a.amt1) AS amt1, SUM(a.amt2) AS amt2
		    FROM ( SELECT a.acccd, b.accseq AS seq, SUM(a.price) AS amt1, CAST(0 AS numeric(13,0)) AS amt2
		             FROM TB_X0007 a WITH (NOLOCK)
		             LEFT OUTER JOIN tb_ac001 b WITH (NOLOCK) ON a.acccd = b.acccd
		            WHERE b.custcd = :custcd
		              AND a.spjangcd = :spjangcd
		              AND a.yyyy = :yyyy
		              AND a.acccd BETWEEN '1000' AND '3999'
		              AND ISNULL(a.bsdate + a.bseccd + a.busicd, '') LIKE :busicd + '%'
		              AND a.acccd IN (SELECT acccd FROM tb_ac001 WHERE chulflag = '1')
		            GROUP BY a.acccd, b.accseq
		           UNION
		           SELECT A.acccd, a.seq, 0 AS amt1, SUM(a.ncramt) AS amt2
		             FROM ( SELECT a.custcd, b.acccd, b.accnm AS accnm, c.drcr, c.accseq AS seq, 0 AS ndramt, B.cramt AS ncramt
		                      FROM TB_AA009 a WITH (NOLOCK), TB_AA010 b WITH (NOLOCK), TB_AC001 c WITH (NOLOCK)
		                     WHERE a.custcd = b.custcd AND a.spjangcd = b.spjangcd AND a.spdate = b.spdate AND a.spnum = b.spnum
		                       AND a.custcd = c.custcd AND b.acccd = c.acccd
		                       AND c.chulflag = '1'
		                       AND a.custcd = :custcd
		                       AND a.spjangcd = :spjangcd
		                       AND SUBSTRING(a.spdate, 1, 4) = :yyyy
		                       AND b.bumuncd <> 'ZZ'
		                       AND B.cramt <> 0 ) A
		            GROUP BY A.custcd, A.acccd, A.accnm, A.drcr, A.seq ) A
		   GROUP BY a.acccd, a.seq
		  ) x
		 ORDER BY x.seq, x.acccd
		""";

	// ── d_tb_aa009_sh12_3 전기이월 보정 ──
	private static final String SQL_CARRY = """
		SELECT a.acccd AS acccd, a.cramt AS amt1, a.dramt AS amt2
		  FROM TB_AB002 a WITH (NOLOCK), TB_AC001 b WITH (NOLOCK)
		 WHERE a.acccd = b.acccd
		   AND b.dcpl = '2'
		   AND a.custcd = :custcd
		   AND a.spjangcd = :spjangcd
		   AND a.yymm = :yyyy + '00'
		""";

	// ── d_tb_aa009_sh12_11 수입(관 · 항 · 목) ──
	private static final String SQL_REV_ITEM = """
		SELECT x.acccd, x.it1cd, x.it2cd, x.amt1, x.amt2,
		       (SELECT TOP 1 accnm FROM TB_AC001 WHERE custcd = :custcd AND acccd = x.acccd) AS accnm,
		       (SELECT TOP 1 accseq FROM TB_AC001 WHERE custcd = :custcd AND acccd = x.acccd) AS seq,
		       (SELECT TOP 1 it1nm FROM VW_X0003 WHERE custcd = :custcd AND tiosec = '1' AND it1cd = x.it1cd) AS it1nm,
		       (SELECT TOP 1 it2nm FROM TB_X0004 WHERE custcd = :custcd AND tiosec = '1' AND it2cd = x.it2cd) AS it2nm
		  FROM (
		  SELECT a.acccd, a.it1cd, a.it2cd, SUM(a.amt1) AS amt1, SUM(a.amt2) AS amt2
		    FROM ( SELECT a.acccd, a.it1cd, a.it2cd, SUM(a.price) AS amt1, CAST(0 AS numeric(13,0)) AS amt2
		             FROM TB_X0007 a
		            WHERE a.tiosec = '1'
		              AND a.custcd = :custcd
		              AND a.spjangcd = :spjangcd
		              AND a.yyyy = :yyyy
		              AND a.acccd BETWEEN '5000' AND '5999'
		              AND ISNULL(a.bsdate + a.bseccd + a.busicd, '') LIKE :busicd + '%'
		            GROUP BY a.acccd, a.it1cd, a.it2cd
		           UNION
		           SELECT b.acccd AS acccd, b.it1cd, b.it2cd, CAST(0 AS numeric(13,0)) AS amt1,
		                  SUM(b.cramt) - SUM(b.dramt) AS amt2
		             FROM TB_AA009 a, TB_AA010 b
		            WHERE a.custcd = b.custcd AND a.spjangcd = b.spjangcd AND a.spdate = b.spdate AND a.spnum = b.spnum
		              AND b.bumuncd <> 'ZZ'
		              AND ((b.acccd BETWEEN '5000' AND '5599') OR (b.acccd BETWEEN '8100' AND '8299'))
		              AND a.custcd = :custcd
		              AND a.spjangcd = :spjangcd
		              AND SUBSTRING(a.spdate, 1, 6) BETWEEN :mm1 AND :mm2
		              AND ISNULL(a.bsdate + a.bseccd + a.busicd, '') LIKE :busicd + '%'
		            GROUP BY b.acccd, b.it1cd, b.it2cd ) a
		   GROUP BY a.acccd, a.it1cd, a.it2cd
		  UNION ALL
		  SELECT a.acccd, a.it1cd, a.it2cd, SUM(a.amt1) AS amt1, SUM(a.amt2) AS amt2
		    FROM ( SELECT a.acccd, a.it1cd, a.it2cd, SUM(a.price) AS amt1, CAST(0 AS numeric(13,0)) AS amt2
		             FROM TB_X0007 a
		            WHERE a.custcd = :custcd
		              AND a.spjangcd = :spjangcd
		              AND a.yyyy = :yyyy
		              AND a.acccd BETWEEN '1000' AND '3999'
		              AND ISNULL(a.bsdate + a.bseccd + a.busicd, '') LIKE :busicd + '%'
		              AND a.acccd IN (SELECT acccd FROM tb_ac001 WHERE ipflag = '1')
		            GROUP BY a.acccd, a.it1cd, a.it2cd
		           UNION
		           SELECT A.acccd, a.it1cd, a.it2cd, 0 AS amt1,
		                  CASE WHEN A.drcr = '1' THEN SUM(A.ndramt) - SUM(A.ncramt) ELSE SUM(A.ncramt) - SUM(A.ndramt) END AS amt2
		             FROM ( SELECT A.custcd AS custcd, A.acccd AS acccd, A.accnm AS accnm, '' AS it1cd, '' AS it2cd, A.drcr AS drcr,
		                           SUM(ISNULL(B.dramt, 0)) AS ndramt, SUM(ISNULL(B.cramt, 0)) AS ncramt
		                      FROM TB_AC001 A, TB_AB001 B
		                     WHERE A.custcd = B.custcd AND A.acccd = B.acccd
		                       AND B.custcd = :custcd
		                       AND B.spjangcd = :spjangcd
		                       AND A.dcpl = '1' AND A.ipflag = '1'
		                       AND B.yymmdd = :yyyy + '0000'
		                     GROUP BY A.custcd, A.acccd, A.accnm, A.drcr
		                    UNION ALL
		                    SELECT a.custcd, b.acccd, b.accnm AS accnm, b.it1cd, b.it2cd, c.drcr, B.dramt AS ndramt, 0 AS ncramt
		                      FROM TB_AA009 a, TB_AA010 b, TB_AC001 c
		                     WHERE a.custcd = b.custcd AND a.spjangcd = b.spjangcd AND a.spdate = b.spdate AND a.spnum = b.spnum
		                       AND a.custcd = c.custcd AND b.acccd = c.acccd
		                       AND c.ipflag = '1'
		                       AND a.custcd = :custcd
		                       AND A.spjangcd = :spjangcd
		                       AND SUBSTRING(a.spdate, 1, 4) = :yyyy
		                       AND b.bumuncd <> 'ZZ'
		                       AND B.dramt <> 0 ) A
		            GROUP BY A.custcd, A.acccd, A.accnm, A.drcr, a.it1cd, a.it2cd ) A
		   GROUP BY a.acccd, a.it1cd, a.it2cd
		  ) x
		 ORDER BY x.acccd, x.it1cd, x.it2cd   -- 원본 수입 탭: 관코드 → 항코드 → 목코드
		""";

	// ── d_tb_aa009_sh12_21 지출(관 · 항 · 목) ──
	private static final String SQL_EXP_ITEM = """
		SELECT x.acccd, x.seq, x.it1cd, x.it2cd, x.amt1, x.amt2,
		       (SELECT TOP 1 accnm FROM TB_AC001 WHERE custcd = :custcd AND acccd = x.acccd) AS accnm,
		       (SELECT TOP 1 it1nm FROM VW_X0003 WHERE custcd = :custcd AND tiosec = '2' AND it1cd = x.it1cd) AS it1nm,
		       (SELECT TOP 1 it2nm FROM TB_X0004 WHERE custcd = :custcd AND tiosec = '2' AND it2cd = x.it2cd) AS it2nm
		  FROM (
		  SELECT a.acccd, a.seq, a.it1cd, a.it2cd, SUM(a.amt1) AS amt1, SUM(a.amt2) AS amt2
		    FROM ( SELECT a.acccd, b.accseq AS seq, a.it1cd, a.it2cd, SUM(a.price) AS amt1, CAST(0 AS numeric(13,0)) AS amt2
		             FROM TB_X0007 a WITH (NOLOCK), tb_ac001 b WITH (NOLOCK)
		            WHERE a.acccd = b.acccd
		              AND a.tiosec = '2'
		              AND a.custcd = :custcd
		              AND a.spjangcd = :spjangcd
		              AND a.acccd BETWEEN '7000' AND '7999'
		              AND a.yyyy = :yyyy
		              AND a.bsdate + a.bseccd + a.busicd LIKE :busicd + '%'
		            GROUP BY a.acccd, a.it1cd, a.it2cd, b.accseq
		           UNION
		           SELECT b.acccd AS acccd, c.accseq AS seq, b.it1cd, b.it2cd, CAST(0 AS numeric(13,0)) AS amt1,
		                  SUM(b.dramt) - SUM(b.cramt) AS amt2
		             FROM TB_AA009 a WITH (NOLOCK), TB_AA010 b WITH (NOLOCK), TB_AC001 c WITH (NOLOCK)
		            WHERE a.custcd = b.custcd AND a.spjangcd = b.spjangcd AND a.spdate = b.spdate AND a.spnum = b.spnum
		              AND b.acccd = c.acccd
		              AND b.bumuncd <> 'ZZ'
		              AND ((b.acccd BETWEEN '7000' AND '7999') OR (b.acccd BETWEEN '8300' AND '8399'))
		              AND a.custcd = :custcd
		              AND a.spjangcd = :spjangcd
		              AND SUBSTRING(a.spdate, 1, 6) BETWEEN :mm1 AND :mm2
		              AND a.bsdate + a.bseccd + a.busicd LIKE :busicd + '%'
		            GROUP BY b.acccd, b.it1cd, b.it2cd, c.accseq ) a
		   GROUP BY a.acccd, a.it1cd, a.it2cd, a.seq
		  UNION ALL
		  SELECT a.acccd, a.seq AS seq, a.it1cd, a.it2cd, SUM(a.amt1) AS amt1, SUM(a.amt2) AS amt2
		    FROM ( SELECT a.acccd, b.accseq AS seq, a.it1cd, a.it2cd, SUM(a.price) AS amt1, CAST(0 AS numeric(13,0)) AS amt2
		             FROM TB_X0007 a WITH (NOLOCK), tb_ac001 b WITH (NOLOCK)
		            WHERE a.acccd = b.acccd
		              AND a.custcd = :custcd
		              AND a.spjangcd = :spjangcd
		              AND a.yyyy = :yyyy
		              AND a.acccd BETWEEN '1000' AND '3999'
		              AND a.bsdate + a.bseccd + a.busicd LIKE :busicd + '%'
		              AND a.acccd IN (SELECT acccd FROM tb_ac001 WHERE chulflag = '1')
		            GROUP BY a.acccd, a.it1cd, a.it2cd, b.accseq
		           UNION
		           SELECT A.acccd, a.seq, a.it1cd, a.it2cd, 0 AS amt1, SUM(A.ncramt) AS amt2
		             FROM ( SELECT a.custcd, b.acccd, b.accnm AS accnm, b.it1cd, b.it2cd, c.drcr, c.accseq AS seq,
		                           0 AS ndramt, B.cramt AS ncramt
		                      FROM TB_AA009 a, TB_AA010 b, TB_AC001 c
		                     WHERE a.custcd = b.custcd AND a.spjangcd = b.spjangcd AND a.spdate = b.spdate AND a.spnum = b.spnum
		                       AND a.custcd = c.custcd AND b.acccd = c.acccd
		                       AND c.chulflag = '1'
		                       AND a.custcd = :custcd
		                       AND a.spjangcd = :spjangcd
		                       AND SUBSTRING(a.spdate, 1, 4) = :yyyy
		                       AND b.bumuncd <> 'ZZ'
		                       AND B.cramt <> 0 ) A
		            GROUP BY A.custcd, A.acccd, A.accnm, A.drcr, a.it1cd, a.it2cd, A.seq ) A
		   GROUP BY a.acccd, a.it1cd, a.it2cd, a.seq
		  ) x
		 ORDER BY x.seq, x.acccd, x.it1cd, x.it2cd
		""";

	// ── d_tb_aa009_sh12_1be 사업별 수입 ──
	private static final String SQL_REV_BIZ = """
		SELECT x.*, (SELECT TOP 1 accnm FROM TB_AC001 WHERE custcd = :custcd AND acccd = x.acccd) AS accnm
		  FROM (
		  SELECT a.acccd, a.seq, SUM(a.amt1) AS amt1, SUM(a.amt2) AS amt2, a.businm, a.bsdate, a.bseccd, a.busicd
		    FROM ( SELECT a.acccd, b.accseq AS seq, SUM(a.price) AS amt1, a.bsdate, a.bseccd, a.busicd,
		                  CAST(0 AS numeric(13,0)) AS amt2,
		                  (SELECT businm FROM tb_x0002 WHERE bsdate = a.bsdate AND bseccd = a.bseccd AND busicd = a.busicd) AS businm
		             FROM TB_X0007 a, tb_ac001 b
		            WHERE a.acccd = b.acccd
		              AND a.tiosec = '1'
		              AND a.custcd = :custcd
		              AND a.spjangcd = :spjangcd
		              AND a.yyyy = :yyyy
		              AND a.acccd BETWEEN '5000' AND '5999'
		              AND LEN(a.bsdate) > 0
		            GROUP BY a.acccd, b.accseq, a.bsdate, a.bseccd, a.busicd
		           UNION
		           SELECT b.acccd AS acccd, c.accseq AS seq, CAST(0 AS numeric(13,0)) AS amt1, a.bsdate, a.bseccd, a.busicd,
		                  SUM(b.cramt) - SUM(b.dramt) AS amt2,
		                  (SELECT businm FROM tb_x0002 WHERE bsdate = a.bsdate AND bseccd = a.bseccd AND busicd = a.busicd) AS businm
		             FROM TB_AA009 a, TB_AA010 b, TB_AC001 c
		            WHERE a.custcd = b.custcd AND a.spjangcd = b.spjangcd AND a.spdate = b.spdate AND a.spnum = b.spnum
		              AND b.acccd = c.acccd
		              AND b.bumuncd <> 'ZZ'
		              AND b.mssec LIKE :mssec + '%'
		              AND ((b.acccd BETWEEN '5000' AND '5599') OR (b.acccd BETWEEN '8100' AND '8299'))
		              AND a.custcd = :custcd
		              AND a.spjangcd = :spjangcd
		              AND SUBSTRING(a.spdate, 1, 6) BETWEEN :mm1 AND :mm2
		              AND LEN(a.bsdate) > 0
		            GROUP BY b.acccd, c.accseq, a.bsdate, a.bseccd, a.busicd ) a
		   WHERE amt1 <> 0 OR amt2 <> 0
		   GROUP BY a.acccd, a.seq, a.businm, a.bsdate, a.bseccd, a.busicd
		  ) x
		 ORDER BY x.bsdate, x.bseccd, x.busicd, x.seq, x.acccd
		""";

	// ── d_tb_aa009_sh12_2be 사업별 지출 ──
	private static final String SQL_EXP_BIZ = """
		SELECT x.*, (SELECT TOP 1 accnm FROM TB_AC001 WHERE custcd = :custcd AND acccd = x.acccd) AS accnm
		  FROM (
		  SELECT a.acccd, a.seq, SUM(a.amt1) AS amt1, SUM(a.amt2) AS amt2, a.businm
		    FROM ( SELECT a.acccd, b.accseq AS seq, SUM(a.price) AS amt1, CAST(0 AS numeric(13,0)) AS amt2,
		                  (SELECT businm FROM tb_x0002 WHERE bsdate = a.bsdate AND bseccd = a.bseccd AND busicd = a.busicd) AS businm
		             FROM TB_X0007 a, tb_ac001 b
		            WHERE a.acccd = b.acccd
		              AND a.tiosec = '2'
		              AND a.custcd = :custcd
		              AND a.spjangcd = :spjangcd
		              AND a.acccd BETWEEN '7000' AND '7999'
		              AND a.yyyy = :yyyy
		              AND LEN(a.bsdate) > 0
		            GROUP BY a.acccd, b.accseq, a.bsdate, a.bseccd, a.busicd
		           UNION ALL
		           SELECT b.acccd AS acccd, c.accseq AS seq, CAST(0 AS numeric(13,0)) AS amt1,
		                  SUM(b.dramt) - SUM(b.cramt) AS amt2,
		                  (SELECT businm FROM tb_x0002 WHERE bsdate = a.bsdate AND bseccd = a.bseccd AND busicd = a.busicd) AS businm
		             FROM TB_AA009 a, TB_AA010 b, TB_AC001 c
		            WHERE a.custcd = b.custcd AND a.spjangcd = b.spjangcd AND a.spdate = b.spdate AND a.spnum = b.spnum
		              AND b.acccd = c.acccd
		              AND b.bumuncd <> 'ZZ'
		              AND b.mssec LIKE :mssec + '%'
		              AND a.custcd = :custcd
		              AND a.spjangcd = :spjangcd
		              AND SUBSTRING(a.spdate, 1, 6) BETWEEN :mm1 AND :mm2
		              AND ((b.acccd BETWEEN '7000' AND '7999') OR (b.acccd BETWEEN '8300' AND '8499') OR (b.acccd = '1360'))
		              AND LEN(a.bsdate) > 0
		            GROUP BY b.acccd, c.accseq, a.bsdate, a.bseccd, a.busicd ) a
		   WHERE amt1 <> 0 OR amt2 <> 0
		   GROUP BY a.acccd, a.seq, a.businm
		  ) x
		 ORDER BY x.businm, x.seq, x.acccd
		""";

	// 원본: 전기이월금 기초액 (TB_AB002 이월 계정 차변 - 대변)
	private static final String SQL_PREAMT = """
		SELECT SUM(A.dramt) - SUM(A.cramt) AS amt
		  FROM TB_AB002 A, TB_AC001 B
		 WHERE A.acccd = B.acccd AND B.ewolyn = '1'
		   AND a.yymm = :yyyy + '00'
		   AND a.custcd = :custcd AND a.spjangcd = :spjangcd
		 GROUP BY a.custcd, a.spjangcd
		""";

	/**
	 * @param tab 1 관1 · 2 수입 · 3 지출 · 4 사업별대조표
	 */
	public Map<String, Object> read(int tab, String yyyy, String mm1, String mm2, String busicd, String businm, String mssec) {
		if (yyyy == null || !yyyy.trim().matches("\\d{4}")) throw new IllegalArgumentException("사업년도를 4자리로 입력하십시오.");
		yyyy = yyyy.trim();
		mm1 = mm(mm1, "01");
		mm2 = mm(mm2, "12");
		String spjangcd = TenantContext.get();
		Map<String, Object> biz = sqlRunner.getRow(
			"SELECT custcd, spjangnm FROM tb_xa012 WHERE spjangcd = :spjangcd",
			new MapSqlParameterSource().addValue("spjangcd", spjangcd));
		String custcd = biz == null || biz.get("custcd") == null ? "" : String.valueOf(biz.get("custcd")).trim();
		boolean noBiz = businm == null || businm.trim().isEmpty();
		String bz = noBiz || busicd == null || busicd.trim().isEmpty() ? "%" : busicd.trim();
		String ms = mssec == null || mssec.trim().isEmpty() ? "%" : mssec.trim();

		MapSqlParameterSource ps = new MapSqlParameterSource()
																 .addValue("custcd", custcd).addValue("spjangcd", spjangcd).addValue("yyyy", yyyy)
																 .addValue("mm1", yyyy + mm1).addValue("mm2", yyyy + mm2)
																 .addValue("busicd", bz).addValue("mssec", ms);

		BigDecimal preamt = n(sqlRunner.getRow(SQL_PREAMT, ps), "amt");
		BigDecimal iwol = iwolamt(ps, yyyy, mm1, preamt);
		if (iwol.signum() == 0) iwol = preamt;   // 원본: ld_iwolamt = 0 이면 기초액

		List<Map<String, Object>> rows;
		switch (tab) {
			case 2: rows = itemTab(sqlRunner.getRows(SQL_REV_ITEM, ps), null, noBiz, preamt, iwol, true); break;
			case 3: rows = itemTab(sqlRunner.getRows(SQL_EXP_ITEM, ps), sum(sqlRunner.getRows(SQL_REV_ITEM, ps), "amt2"),
				noBiz, preamt, iwol, false); break;
			case 4: rows = sideTab(sqlRunner.getRows(SQL_REV_BIZ, ps), sqlRunner.getRows(SQL_EXP_BIZ, ps),
				sqlRunner.getRows(SQL_CARRY, ps), noBiz, preamt, iwol, true, noBiz ? "" : businm.trim()); break;
			default: rows = sideTab(sqlRunner.getRows(SQL_REV, ps), sqlRunner.getRows(SQL_EXP, ps),
				sqlRunner.getRows(SQL_CARRY, ps), noBiz, preamt, iwol, false, "");
		}

		Map<String, Object> result = new HashMap<>();
		result.put("yyyy", yyyy);
		result.put("mm1", mm1);
		result.put("mm2", mm2);
		result.put("spjangnm", biz == null || biz.get("spjangnm") == null ? "" : String.valueOf(biz.get("spjangnm")).trim());
		result.put("rows", rows);
		return result;
	}

	/**
	 * 월결산서 — PowerBuilder w_tb_aa009_sh17 이식.
	 *  예산 및 결산대조표와 같은 SQL(sh12_1 · sh12_2 · sh12_11 · sh12_21)을 한 달(yyyymm ~ yyyymm)로, 사업 · 재원은 전체('%')로 조회한다.
	 *  원본은 결산금액만 보여 주고, 지출 결산에 이월 보정을 하지 않으며(찾아도 같은 값을 넣는다), 이월금 행은 항상 넣는다.
	 * @param tab 1 관1 · 2 수입 · 3 지출
	 */
	public Map<String, Object> readMonthly(int tab, String yyyy, String mm) {
		if (yyyy == null || !yyyy.trim().matches("\\d{4}")) throw new IllegalArgumentException("사업년도를 4자리로 입력하십시오.");
		yyyy = yyyy.trim();
		mm = mm(mm, "01");
		String spjangcd = TenantContext.get();
		Map<String, Object> biz = sqlRunner.getRow(
			"SELECT custcd, spjangnm FROM tb_xa012 WHERE spjangcd = :spjangcd",
			new MapSqlParameterSource().addValue("spjangcd", spjangcd));
		String custcd = biz == null || biz.get("custcd") == null ? "" : String.valueOf(biz.get("custcd")).trim();
		MapSqlParameterSource ps = new MapSqlParameterSource()
																 .addValue("custcd", custcd).addValue("spjangcd", spjangcd).addValue("yyyy", yyyy)
																 .addValue("mm1", yyyy + mm).addValue("mm2", yyyy + mm)
																 .addValue("busicd", "%").addValue("mssec", "%");

		BigDecimal preamt = n(sqlRunner.getRow(SQL_PREAMT, ps), "amt");
		BigDecimal iwol = iwolamt(ps, yyyy, mm, preamt);
		if (iwol.signum() == 0) iwol = preamt;

		List<Map<String, Object>> rows;
		switch (tab) {
			case 2: rows = itemTab(sqlRunner.getRows(SQL_REV_ITEM, ps), null, true, preamt, iwol, true); break;
			case 3: rows = itemTab(sqlRunner.getRows(SQL_EXP_ITEM, ps), sum(sqlRunner.getRows(SQL_REV_ITEM, ps), "amt2"),
				true, preamt, iwol, false); break;
			default: rows = sideTab(sqlRunner.getRows(SQL_REV, ps), sqlRunner.getRows(SQL_EXP, ps),
				Collections.emptyList(), true, preamt, iwol, false, "");
		}

		Map<String, Object> result = new HashMap<>();
		result.put("yyyy", yyyy);
		result.put("mm", mm);
		result.put("spjangnm", biz == null || biz.get("spjangnm") == null ? "" : String.valueOf(biz.get("spjangnm")).trim());
		result.put("rows", rows);
		return result;
	}

	/**
	 * 원본 wf_iwolamt: 시작월이 1월이면 0, 아니면 1월 ~ (시작월-1) 각 달의 수입 결산 합 - 지출 결산 합 을 기초액에 더한다.
	 * 원본은 이 계산에서 사업 · 재원 조건을 넘기지 않으므로 전체('%')로 조회한다.
	 *
	 * 원본은 달마다 수입 · 지출 SQL 을 한 번씩 돌린다(11월이면 20번). 결과를 같게 두고 조회 수만 4번으로 줄였다.
	 *  - SQL 앞부분(전표 결산)은 기간 조건이 있어 달별 합 = 1월 ~ (시작월-1) 한 번 조회한 값
	 *  - SQL 뒷부분(이월 계정)은 기간 조건 없이 년도 전체라 달마다 같은 값이 더해진다 → (달 수) 배
	 *  - 뒷부분만의 값은 기간을 비워(시작 > 끝) 조회하면 앞부분이 0 이 되어 얻을 수 있다
	 *  범위 조회 = 앞부분(범위) + 뒷부분 이므로, 원본 합 = 범위 조회 + (달 수 - 1) × 뒷부분
	 */
	private BigDecimal iwolamt(MapSqlParameterSource base, String yyyy, String mm1, BigDecimal gicho) {
		int m = Integer.parseInt(mm1);
		if (m <= 1) return BigDecimal.ZERO;
		BigDecimal months = BigDecimal.valueOf(m - 1L);
		MapSqlParameterSource range = monthParams(base, yyyy, yyyy + "01", yyyy + String.format("%02d", m - 1));
		MapSqlParameterSource none = monthParams(base, yyyy, "999999", "000000");   // 앞부분(기간 조건)이 비도록
		BigDecimal su = sum(sqlRunner.getRows(SQL_REV, range), "amt2")
											.add(sum(sqlRunner.getRows(SQL_REV, none), "amt2").multiply(months.subtract(BigDecimal.ONE)));
		BigDecimal ji = sum(sqlRunner.getRows(SQL_EXP, range), "amt2")
											.add(sum(sqlRunner.getRows(SQL_EXP, none), "amt2").multiply(months.subtract(BigDecimal.ONE)));
		return gicho.add(su).subtract(ji);
	}

	private MapSqlParameterSource monthParams(MapSqlParameterSource base, String yyyy, String from, String to) {
		return new MapSqlParameterSource()
						 .addValue("custcd", base.getValue("custcd")).addValue("spjangcd", base.getValue("spjangcd"))
						 .addValue("yyyy", yyyy).addValue("mm1", from).addValue("mm2", to)
						 .addValue("busicd", "%").addValue("mssec", "%");
	}

	/** 탭1 · 탭4: 수입(왼쪽) · 지출(오른쪽) 나란히 */
	private List<Map<String, Object>> sideTab(List<Map<String, Object>> rev, List<Map<String, Object>> exp,
																						List<Map<String, Object>> carry, boolean noBiz,
																						BigDecimal preamt, BigDecimal iwol, boolean byBiz, String bizName) {
		List<Map<String, Object>> out = new ArrayList<>();
		int n = Math.max(rev.size(), exp.size());
		if (n < 1) return out;
		Map<String, BigDecimal> carryAmt2 = new HashMap<>();
		for (Map<String, Object> c : carry) carryAmt2.putIfAbsent(s(c.get("acccd")), n(c, "amt2"));

		for (int i = 0; i < n; i++) {
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("kind", "d");
			if (i < rev.size()) {
				Map<String, Object> a = rev.get(i);
				if (byBiz) r.put("businm", s(a.get("businm")));
				r.put("acc1", name(a));
				r.put("amt1", n(a, "amt1"));
				r.put("amt2", n(a, "amt2"));
			}
			if (i < exp.size()) {
				Map<String, Object> b = exp.get(i);
				if (byBiz) r.put("businm2", s(b.get("businm")));
				r.put("acc2", name(b));
				r.put("amt3", n(b, "amt1"));
				BigDecimal a4 = n(b, "amt2");
				BigDecimal c = carryAmt2.get(s(b.get("acccd")));
				r.put("amt4", c == null ? a4 : a4.add(c));   // 원본: 이월 계정이면 결산에 이월 차변을 더한다
				// 원본 사업별대조표는 이때 수입 쪽 사업명을 조건의 사업명으로 덮어쓴다(조건이 비어 있으면 빈칸). 그대로 둔다.
				if (byBiz && c != null) r.put("businm", bizName);
			}
			out.add(r);
		}
		BigDecimal a1 = sum(rev, "amt1"), a2 = sum(rev, "amt2"), a3 = sum(exp, "amt1"), a4 = sum(exp, "amt2");
		out.add(side("s", "합계", a1, a2, "합계", a3, a4));
		BigDecimal next = iwol.add(a2).subtract(a4);
		if (noBiz) out.add(side("c", "전기이월금", preamt, iwol, "차기이월금", BigDecimal.ZERO, next));
		out.add(side("t", "총 계", a1.add(preamt), a2.add(iwol), "총 계", a3, a4.add(next)));
		return out;
	}

	/** 탭2 수입 · 탭3 지출: 관 · 항 · 목 */
	private List<Map<String, Object>> itemTab(List<Map<String, Object>> list, BigDecimal revAmt2, boolean noBiz,
																						BigDecimal preamt, BigDecimal iwol, boolean isRev) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (list.isEmpty()) return out;
		for (Map<String, Object> a : list) {
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("kind", "d");
			r.put("acccd", s(a.get("acccd")));
			r.put("acc1", name(a));
			r.put("it1cd", s(a.get("it1cd")));
			r.put("it1nm", nz(a.get("it1nm"), a.get("it1cd")));
			r.put("it2nm", nz(a.get("it2nm"), a.get("it2cd")));
			r.put("amt1", n(a, "amt1"));
			r.put("amt2", n(a, "amt2"));
			out.add(r);
		}
		BigDecimal a1 = sum(list, "amt1"), a2 = sum(list, "amt2");
		out.add(item("s", "합계", a1, a2));
		if (isRev) {
			if (noBiz) out.add(item("c", "전기이월금", preamt, iwol));
			out.add(item("t", "총 계", a1.add(preamt), a2.add(iwol)));
		} else {
			BigDecimal next = iwol.add(revAmt2).subtract(a2);
			if (noBiz) out.add(item("c", "차기이월금", BigDecimal.ZERO, next));
			out.add(item("t", "총 계", a1, a2.add(next)));
		}
		return out;
	}

	private Map<String, Object> side(String kind, String l1, BigDecimal a1, BigDecimal a2, String l2, BigDecimal a3, BigDecimal a4) {
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("kind", kind);
		r.put("acc1", l1); r.put("amt1", a1); r.put("amt2", a2);
		r.put("acc2", l2); r.put("amt3", a3); r.put("amt4", a4);
		return r;
	}

	private Map<String, Object> item(String kind, String label, BigDecimal a1, BigDecimal a2) {
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("kind", kind);
		r.put("acc1", label); r.put("amt1", a1); r.put("amt2", a2);
		return r;
	}

	private static String mm(String v, String def) {
		if (v == null || !v.trim().matches("\\d{1,2}")) return def;
		int m = Integer.parseInt(v.trim());
		return m < 1 || m > 12 ? def : String.format("%02d", m);
	}

	private static String s(Object o) { return o == null ? "" : String.valueOf(o).trim(); }

	private static String nz(Object a, Object b) { String x = s(a); return x.isEmpty() ? s(b) : x; }

	private static String name(Map<String, Object> m) { return nz(m.get("accnm"), m.get("acccd")); }

	private static BigDecimal n(Map<String, Object> m, String k) {
		if (m == null || m.get(k) == null) return BigDecimal.ZERO;
		Object v = m.get(k);
		return v instanceof BigDecimal ? (BigDecimal) v : new BigDecimal(String.valueOf(v));
	}

	private static BigDecimal sum(List<Map<String, Object>> list, String k) {
		BigDecimal s = BigDecimal.ZERO;
		for (Map<String, Object> m : list) s = s.add(n(m, k));
		return s;
	}
}