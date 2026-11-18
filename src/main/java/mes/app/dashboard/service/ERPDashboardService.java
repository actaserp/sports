package mes.app.dashboard.service;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 대시보드 집계.
 *
 * 다루는 테이블이 전부 테넌트 DB(SQL Server)라 기본 SqlRunner 를 쓴다.
 * 마스터 테이블은 건드리지 않으므로 @Qualifier("mainSqlRunner") 는 필요 없다.
 *
 * ── 여기서 지킨 규칙 ────────────────────────────────────────
 * 세입·세출을 TB_AA009.tiosec 이 아니라 TB_AA010.acccd 범위로 가른다.
 * IncomeStatementService · ExpenseStatementService 가 그렇게 하고 있고,
 * 대시보드가 다른 기준을 쓰면 세입내역서·세출내역서와 숫자가 어긋난다.
 * 담당자가 두 화면을 비교하는 순간 대시보드는 신뢰를 잃는다.
 *
 * 계좌 잔액은 다루지 않는다. 용도별 계좌를 54개 쓰고 본계좌에서 목적계좌로 옮겨 쓰는
 * 구조라 계좌 하나의 순액이 통장 잔액을 뜻하지 않는다. 재원 단위로만 본다.
 */
@Slf4j
@Service
public class ERPDashboardService {

	@Autowired
	SqlRunner sqlRunner;

	/** 세입 계정 범위. IncomeStatementService 와 같아야 한다. */
	private static final String INCOME_ACC =
		"((B.acccd BETWEEN '5000' AND '5599') OR (B.acccd BETWEEN '8100' AND '8299'))";

	/** 세출 계정 범위. ExpenseStatementService.searchTab1 과 같아야 한다. */
	private static final String EXPENSE_ACC =
		"((B.acccd BETWEEN '7000' AND '7999') OR (B.acccd BETWEEN '8300' AND '8599'))";

	/**
	 * 세입 금액. 대변이 본값이고 차변은 취소분이라 뺀다.
	 * 세출은 반대다. 두 결산서의 UNION ALL 구조를 한 줄로 접은 것이다.
	 */
	private static final String INCOME_AMT =
		"SUM(CASE WHEN " + INCOME_ACC + " THEN ISNULL(B.cramt,0) - ISNULL(B.dramt,0) ELSE 0 END)";

	private static final String EXPENSE_AMT =
		"SUM(CASE WHEN " + EXPENSE_ACC + " THEN ISNULL(B.dramt,0) - ISNULL(B.cramt,0) ELSE 0 END)";

	/**
	 * 전표 헤더 + 분개 조인과 공통 조건.
	 *
	 * iwolflag = '1' 은 이월 분개다. 결산서가 전부 빼고 있으므로 여기서도 뺀다.
	 * 넣으면 연초에 작년 잔액이 올해 세입으로 잡혀 금액이 두 배로 보인다.
	 */
	private static final String FROM_SLIP = """
		  FROM TB_AA009 A WITH (NOLOCK),
		       TB_AA010 B WITH (NOLOCK)
		 WHERE A.custcd   = B.custcd
		   AND A.spjangcd = B.spjangcd
		   AND A.spdate   = B.spdate
		   AND A.spnum    = B.spnum
		   AND A.custcd   = :custcd
		   AND A.spjangcd = :spjangcd
		   AND A.spdate   BETWEEN :frdate AND :todate
		   AND (B.iwolflag <> '1' OR B.iwolflag IS NULL)
		""";

	// ══════════════════════════════════════════════════════════
	//  1. 손대야 할 것
	// ══════════════════════════════════════════════════════════

	/**
	 * 확정하지 않은 전표 건수.
	 *
	 * fixflag 는 '0' 미확정 / '1' 확정 (SlipStatusService 의 CASE 문에서 확인).
	 * appgubun(결재상태)은 코드값이 어디에도 정의돼 있지 않아 세지 않는다.
	 */
	public int getUnfixedSlipCount(String year) {
		Map<String, Object> row = sqlRunner.getRow("""
			SELECT COUNT(*) AS cnt
			  FROM TB_AA009 WITH (NOLOCK)
			 WHERE custcd   = :custcd
			   AND spjangcd = :spjangcd
			   AND spdate   BETWEEN :frdate AND :todate
			   AND ISNULL(fixflag, '0') = '0'
			""", base(year));
		return (int) longOf(row, "cnt");
	}

	// ══════════════════════════════════════════════════════════
	//  2. 재원별 세입 · 세출
	// ══════════════════════════════════════════════════════════

	/**
	 * 재원(mssec) 단위 집계.
	 *
	 * 계좌 잔액을 쓰지 않는 이유가 있다. 이 협회는 용도별 계좌를 54개 운영하고
	 * 본계좌에서 목적계좌로 옮겨 쓰기 때문에, 계좌 하나의 순액은 자금 흐름의 조각일 뿐
	 * 통장 잔액이 아니다. 실제로 연도가 바뀌며 계좌를 갈아탄 건도 있어
	 * (광주-25법인화기금 → 광주-26법인화기금) 계좌별로 보면 한쪽이 큰 음수로 보인다.
	 *
	 * 재원은 그런 계좌 간 이동에 흔들리지 않고, 목적 외 사용이 막혀 있어
	 * 담당자가 실제로 판단에 쓰는 축이다.
	 */
	public List<Map<String, Object>> getByFundSource(String year) {
		return sqlRunner.getRows("""
			SELECT B.mssec AS mssec,
			       (SELECT mssecnm FROM tb_x0005 WHERE mssec = B.mssec) AS mssecnm,
			""" + INCOME_AMT + """
			        AS income,
			""" + EXPENSE_AMT + """
			        AS expense
			""" + FROM_SLIP + """
			   AND ISNULL(B.mssec, '') <> ''
			   AND (""" + INCOME_ACC + " OR " + EXPENSE_ACC + """
			       )
			 GROUP BY B.mssec
			HAVING """ + " " + INCOME_AMT + " <> 0 OR " + EXPENSE_AMT + """
			        <> 0
			 ORDER BY """ + " " + EXPENSE_AMT + """
			        DESC
			""", base(year));
	}

	// ══════════════════════════════════════════════════════════
	//  2-1. 최근 전표
	// ══════════════════════════════════════════════════════════

	/**
	 * 최근 등록된 전표.
	 *
	 * 금액은 SlipStatusService.getSlipList 와 같은 방식으로 센다.
	 * 헤더:라인이 1:N 이라 GROUP BY 로 전표 단위로 접는다.
	 */
	public List<Map<String, Object>> getRecentSlips(String year, int top) {
		MapSqlParameterSource p = base(year);
		p.addValue("top", top);

		return sqlRunner.getRows("""
			SELECT TOP (:top)
			       STUFF(STUFF(A.spdate, 5, 0, '-'), 8, 0, '-') AS spdate,
			       A.spnum,
			       A.subject,
			       CASE A.tiosec WHEN '1' THEN '세입' WHEN '2' THEN '세출'
			                     WHEN '3' THEN '대체' ELSE A.tiosec END AS tiosec,
			       CASE ISNULL(A.fixflag, '0') WHEN '1' THEN '확정' ELSE '미확정' END AS fixflag,
			       ISNULL(SUM(B.dramt), SUM(B.cramt)) AS amt
			""" + FROM_SLIP + """
			 GROUP BY A.spdate, A.spnum, A.subject, A.tiosec, A.fixflag
			 ORDER BY A.spdate DESC, A.spnum DESC
			""", p);
	}

	// ══════════════════════════════════════════════════════════
	//  3. 월별 세입 · 세출
	// ══════════════════════════════════════════════════════════

	/** 12개월치를 빠짐없이 돌려준다. 전표가 없는 달은 0 이 아니라 hasData=false 다. */
	public List<Map<String, Object>> getMonthly(String year) {
		List<Map<String, Object>> rows = sqlRunner.getRows("""
			SELECT LEFT(A.spdate, 6) AS ym,
			""" + INCOME_AMT + """
			        AS income,
			""" + EXPENSE_AMT + """
			        AS expense
			""" + FROM_SLIP + """
			 GROUP BY LEFT(A.spdate, 6)
			""", base(year));

		Map<String, Map<String, Object>> byMonth = new HashMap<>();
		for (Map<String, Object> r : rows) byMonth.put(str(r.get("ym")), r);

		List<Map<String, Object>> out = new ArrayList<>();
		for (int m = 1; m <= 12; m++) {
			String ym = year + String.format("%02d", m);
			Map<String, Object> r = byMonth.get(ym);
			Map<String, Object> o = new LinkedHashMap<>();
			o.put("ym", ym);
			o.put("month", m);
			o.put("income", r == null ? 0L : longOf(r, "income"));
			o.put("expense", r == null ? 0L : longOf(r, "expense"));
			o.put("hasData", r != null);
			out.add(o);
		}
		return out;
	}

	// ══════════════════════════════════════════════════════════
	//  4. 관별 수지
	// ══════════════════════════════════════════════════════════

	/**
	 * 계정과목(관) 단위 집계.
	 *
	 * 세입 계정과 세출 계정은 코드 범위가 겹치지 않으므로 한 행에 둘 다 차는 일은 없다.
	 * 그래서 관 하나는 세입 아니면 세출 한쪽만 값을 갖는다.
	 */
	public List<Map<String, Object>> getByAccount(String year, int top) {
		MapSqlParameterSource p = base(year);
		p.addValue("top", top);

		return sqlRunner.getRows("""
			SELECT TOP (:top)
			       B.acccd AS acccd,
			       MAX(B.accnm) AS accnm,
			""" + INCOME_AMT + """
			        AS income,
			""" + EXPENSE_AMT + """
			        AS expense
			""" + FROM_SLIP + """
			   AND (""" + INCOME_ACC + " OR " + EXPENSE_ACC + """
			       )
			 GROUP BY B.acccd
			HAVING """ + " " + INCOME_AMT + " <> 0 OR " + EXPENSE_AMT + """
			        <> 0
			 ORDER BY """ + " " + INCOME_AMT + " + " + EXPENSE_AMT + """
			        DESC
			""", p);
	}

	// ══════════════════════════════════════════════════════════
	//  5. 사업별 수지
	// ══════════════════════════════════════════════════════════

	/** 사업명은 tb_x0002 를 (bsdate, bseccd, busicd) 세 개로 찾는다. 결산서와 같은 조인이다. */
	public List<Map<String, Object>> getByBusiness(String year, int top) {
		MapSqlParameterSource p = base(year);
		p.addValue("top", top);

		return sqlRunner.getRows("""
			SELECT TOP (:top)
			       A.bsdate, A.bseccd, A.busicd,
			       (SELECT businm FROM tb_x0002
			         WHERE bsdate = A.bsdate AND bseccd = A.bseccd AND busicd = A.busicd) AS businm,
			""" + INCOME_AMT + """
			        AS income,
			""" + EXPENSE_AMT + """
			        AS expense
			""" + FROM_SLIP + """
			   AND ISNULL(A.busicd, '') <> ''
			   AND (""" + INCOME_ACC + " OR " + EXPENSE_ACC + """
			       )
			 GROUP BY A.bsdate, A.bseccd, A.busicd
			HAVING """ + " " + EXPENSE_AMT + """
			        <> 0
			 ORDER BY """ + " " + EXPENSE_AMT + """
			        DESC
			""", p);
	}

	// ══════════════════════════════════════════════════════════
	//  6. 전표 없는 카드 사용
	// ══════════════════════════════════════════════════════════

	/**
	 * 카드 사용내역 테이블은 tb_bank_cdsave 다. custcd 없이 spjangcd 로만 나뉜다.
	 *
	 * 전표 생성 여부는 mijdate + mijnum 이 채워졌는지로 본다.
	 * CardHistoryService.getCardHistoryList 의 accflag 조건과 같은 판정이다.
	 *
	 * ※ apv_dt 는 'yyyyMMdd' 로 저장돼 있다(조회 시 STUFF 로 하이픈을 끼워 넣는다).
	 *   그래서 여기서도 하이픈 없는 값으로 비교한다.
	 */
	private static final String CARD_NO_SLIP = """
		  FROM tb_bank_cdsave WITH (NOLOCK)
		 WHERE spjangcd = :spjangcd
		   AND apv_dt BETWEEN :frdate AND :todate
		   AND ( ISNULL(NULLIF(mijdate, ''), '') = ''
		      OR ISNULL(NULLIF(mijnum , ''), '') = '' )
		""";

	/** 전표가 아직 안 붙은 카드 사용 건수. */
	public int getNoSlipCardCount(String year) {
		Map<String, Object> row = sqlRunner.getRow(
			"SELECT COUNT(*) AS cnt" + CARD_NO_SLIP, base(year));
		return (int) longOf(row, "cnt");
	}

	/** 전표가 아직 안 붙은 카드 사용 최근 건. 오래된 것부터 처리해야 하지만 목록은 최신순으로 본다. */
	public List<Map<String, Object>> getNoSlipCardList(String year, int top) {
		MapSqlParameterSource p = base(year);
		p.addValue("top", top);

		return sqlRunner.getRows("""
			SELECT TOP (:top)
			       STUFF(STUFF(apv_dt, 5, 0, '-'), 8, 0, '-') AS apv_dt,
			       mest_nm, buy_sum, card_no, busim, accnm
			""" + CARD_NO_SLIP + """
			 ORDER BY apv_dt DESC, apv_tm DESC
			""", p);
	}

	/**
	 * 매입전표는 만들었지만 지급전표를 아직 안 낸 건수.
	 *
	 * pay_cancel_yn = 'Y' 가 "지급전표 발행됨"이다.
	 * (CardHistoryService.newSlipCancel 이 이 값으로 취소를 막는다)
	 */
	public int getUnpaidCardCount(String year) {
		Map<String, Object> row = sqlRunner.getRow("""
			SELECT COUNT(*) AS cnt
			  FROM tb_bank_cdsave WITH (NOLOCK)
			 WHERE spjangcd = :spjangcd
			   AND apv_dt BETWEEN :frdate AND :todate
			   AND ISNULL(NULLIF(mijdate, ''), '') <> ''
			   AND ISNULL(NULLIF(mijnum , ''), '') <> ''
			   AND ISNULL(pay_cancel_yn, 'N') <> 'Y'
			""", base(year));
		return (int) longOf(row, "cnt");
	}

	// ══════════════════════════════════════════════════════════
	//  7. 이번 달 급여
	// ══════════════════════════════════════════════════════════

	/** 가장 최근 급여 회차. PayslipService.getPayBatches 의 첫 행과 같은 기준이다. */
	public Map<String, Object> getLatestPayBatch() {
		String spjangcd = TenantContext.get();
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("custcd", custcd(spjangcd));
		p.addValue("spjangcd", spjangcd);

		List<Map<String, Object>> rows = sqlRunner.getRows("""
			SELECT TOP 1 A.paytype, A.paybasic, A.paydate
			  FROM TB_JB001 A WITH (NOLOCK)
			 WHERE A.custcd   = :custcd
			   AND A.spjangcd = :spjangcd
			   AND A.spcheck  = '1'
			   AND LTRIM(RTRIM(IsNull(A.paybasic, ''))) <> ''
			   AND LTRIM(RTRIM(IsNull(A.paydate , ''))) <> ''
			 GROUP BY A.paytype, A.paybasic, A.paydate
			 ORDER BY A.paybasic DESC, A.paydate DESC, A.paytype
			""", p);

		return rows.isEmpty() ? null : rows.get(0);
	}

	/**
	 * 회차 한 건의 급여 요약.
	 *
	 * 대상자 범위를 PayslipService.getEmployeeList 와 똑같이 맞췄다
	 * (spcheck='1' + 그 귀속월에 하루라도 재직). 여기가 어긋나면 대시보드와
	 * 발송 화면의 인원수가 달라져 담당자가 어느 쪽을 믿어야 할지 모르게 된다.
	 *
	 * 발송 건수는 TESTYN='N' 인 실발송만 센다. 테스트 발송은 발송이 아니다.
	 */
	public Map<String, Object> getPayrollSummary(Map<String, Object> batch) {
		if (batch == null) return null;

		String spjangcd = TenantContext.get();
		String paybasic = str(batch.get("paybasic"));

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("custcd", custcd(spjangcd));
		p.addValue("spjangcd", spjangcd);
		p.addValue("paytype", str(batch.get("paytype")));
		p.addValue("paybasic", paybasic);
		p.addValue("paydate", str(batch.get("paydate")));
		p.addValue("paybasic00", paybasic + "00");
		p.addValue("paybasic99", paybasic + "99");

		Map<String, Object> row = sqlRunner.getRow("""
			SELECT COUNT(*) AS total,
			       SUM(CASE WHEN ISNULL(B.email, '') = ''
			                  OR CHARINDEX('@', B.email) = 0
			                THEN 1 ELSE 0 END)                         AS noEmail,
			       SUM(CASE WHEN s.SENDSTATUS = 'S' THEN 1 ELSE 0 END) AS sent,
			       SUM(CASE WHEN t.perid IS NULL THEN 1 ELSE 0 END)    AS noAttendance,
			       SUM(A.paysum - A.dedsum)                            AS netSum
			  FROM TB_JB001 A WITH (NOLOCK)
			  JOIN TB_JA001 B WITH (NOLOCK)
			    ON B.custcd = A.custcd AND B.spjangcd = A.spjangcd AND B.perid = A.perid
			 OUTER APPLY ( SELECT TOP 1 x.SENDSTATUS
			                 FROM TB_PAYSLIP_SEND x
			                WHERE x.SPJANGCD = A.spjangcd
			                  AND x.PAYYM    = A.paybasic
			                  AND x.PAYTYPE  = A.paytype
			                  AND x.EMPNO    = A.perid
			                  AND x.TESTYN   = 'N'
			                ORDER BY x.SENDSEQ DESC ) s
			 OUTER APPLY ( SELECT TOP 1 y.perid
			                 FROM TB_PB203 y
			                WHERE y.custcd   = A.custcd
			                  AND y.spjangcd = A.spjangcd
			                  AND y.workym   = A.paybasic
			                  AND y.perid    = A.perid ) t
			 WHERE A.spcheck  = '1'
			   AND A.custcd   = :custcd
			   AND A.spjangcd = :spjangcd
			   AND A.paytype  = :paytype
			   AND A.paybasic = :paybasic
			   AND A.paydate  = :paydate
			   AND B.entdate <= :paybasic99
			   AND IsNull(B.rtdate, '99999999') >= :paybasic00
			""", p);

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("paytype", str(batch.get("paytype")));
		out.put("paybasic", paybasic);
		out.put("paydate", str(batch.get("paydate")));
		out.put("total", (int) longOf(row, "total"));
		out.put("sent", (int) longOf(row, "sent"));
		out.put("noEmail", (int) longOf(row, "noEmail"));
		out.put("noAttendance", (int) longOf(row, "noAttendance"));
		out.put("netSum", longOf(row, "netSum"));
		return out;
	}

	// ══════════════════════════════════════════════════════════
	//  내부
	// ══════════════════════════════════════════════════════════

	/** 회계연도 한 해. 전표일자는 'yyyyMMdd' 문자열이다. */
	private MapSqlParameterSource base(String year) {
		String spjangcd = TenantContext.get();
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("custcd", custcd(spjangcd));
		p.addValue("spjangcd", spjangcd);
		p.addValue("frdate", year + "0101");
		p.addValue("todate", year + "1231");
		return p;
	}

	/** spjangcd → custcd. 장부 서비스들의 getBizInfoBySpjangcd 와 같은 조회다. */
	private String custcd(String spjangcd) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);
		List<Map<String, Object>> rows = sqlRunner.getRows(
			"select custcd from tb_xa012 where spjangcd = :spjangcd", p);
		return rows.isEmpty() ? "" : str(rows.get(0).get("custcd"));
	}

	private long longOf(Map<String, Object> row, String key) {
		if (row == null) return 0L;
		Object v = row.get(key);
		if (v == null) return 0L;
		try {
			return Long.parseLong(String.valueOf(v).trim().split("\\.")[0]);
		} catch (Exception e) {
			return 0L;
		}
	}

	private String str(Object o) {
		return o == null ? "" : String.valueOf(o).trim();
	}
}