package mes.app.payslip.Service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 급여명세서 데이터 조회 (레거시 d_p3012_2 이식).
 *
 * DTO 없이 Map 으로 다루며 SQL 은 이 클래스에만 둔다.
 *
 * 레거시 대비 달라진 점
 *  - custcd / spjangcd 는 화면 파라미터가 아니라 세션에서 가져온다
 *  - a01~a18 / b01~b18 자리표시자 컬럼을 뽑아두고 PB 코드로 채우던 방식 대신,
 *    지급·공제 내역을 별도 쿼리로 행 단위 조회한다
 */
@Slf4j
@Service
public class PayslipService {

	@Autowired
	SqlRunner sqlRunner;

	/** spjangcd → custcd. 값이 바뀌지 않으므로 한 번만 조회한다. */
	private static final java.util.concurrent.ConcurrentHashMap<String, String> CUSTCD_CACHE =
		new java.util.concurrent.ConcurrentHashMap<>();

	/** 급여구분 코드 → 명칭 (레거시 DW 헤더 case 문과 동일) */
	public static String paytypeName(String paytype) {
		if ("001".equals(paytype)) return "급여명세서";
		if ("002".equals(paytype)) return "상여명세서";
		if ("003".equals(paytype)) return "급상여명세서";
		return "급여명세서";
	}

	/** 수당지급명세서 선택값. 화면 select 의 value 와 같아야 한다. */
	public static final String DOC_ALLOWANCE = "ALW";

	/**
	 * 출력 문서명.
	 *
	 * 화면에서 고른 값이 우선이고, 비어 있으면 급여구분을 따른다.
	 * 비었을 때 급여명세서로 고정하지 않는 이유는 상여(002) · 급상여(003) 회차의
	 * 제목이 조용히 바뀌기 때문이다.
	 */
	public static String docName(String doctype, String paytype) {
		if (DOC_ALLOWANCE.equals(doctype)) return "수당지급명세서";
		return paytypeName(paytype);
	}

	// ─────────────────────────────────────────────────────────
	//  조회조건 팝업 : 급여 회차 목록
	//  레거시는 이 그리드에서 한 행을 골라
	//  strCondition[7] paytype, [8] paybasic, [9] paydate 를 한꺼번에 채운다.
	//  paydate 는 완전일치 조건이라 사용자가 직접 입력할 수 없다.
	// ─────────────────────────────────────────────────────────

	public List<Map<String, Object>> getPayBatches(String spjangcd) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("custcd", getCustcdBySpjangcd(spjangcd));
		p.addValue("spjangcd", spjangcd);

		return normalizeAll(sqlRunner.getRows("""
				select top 36
				       A.paytype                as paytype
				     , A.paybasic               as paybasic
				     , A.paydate                as paydate
				     , count(*)                 as percnt
				     , sum(A.paysum - A.dedsum) as netsum
				  from TB_JB001 A
				 where A.custcd   = :custcd
				   and A.spjangcd = :spjangcd
				   and A.spcheck  = '1'
				   -- paybasic / paydate 가 비어 있는 잔여 데이터가 섞여 있어 제외한다
				   and LTRIM(RTRIM(IsNull(A.paybasic, ''))) <> ''
				   and LTRIM(RTRIM(IsNull(A.paydate , ''))) <> ''
				 group by A.paytype, A.paybasic, A.paydate
				 order by A.paybasic desc, A.paydate desc, A.paytype
				""", p));
	}

	// ─────────────────────────────────────────────────────────
	//  좌측 그리드 : 대상자 목록
	// ─────────────────────────────────────────────────────────

	/**
	 * @param cond 검색 조건. 비어 있으면 '%' 로 채워 전체 조회한다.
	 *             레거시 strCondition[1]~[6] 에 대응.
	 */
	public List<Map<String, Object>> getEmployeeList(String spjangcd, Map<String, String> cond) {

		MapSqlParameterSource p = baseParams(spjangcd, cond);

		// 고정 조건까지만 만들어 두고, 선택 조건은 값이 있을 때만 아래에서 덧붙인다.
		// 빈 조건에 like '%' 를 거는 방식은 인덱스를 못 타고,
		// NULL 인 행이 조용히 빠지는 문제도 있다.
		StringBuilder sql = new StringBuilder("""
                select  A.perid                       as perid
                     ,  substring(A.perid, 2, len(A.perid)) as peridview
                     ,  B.pernm                       as pernm
                     ,  B.email                       as email
                     ,  B.birthday                    as birthday
                     ,  A.paytype                     as paytype
                     ,  A.paybasic                    as paybasic
                     ,  A.paydate                     as paydate
                     ,  A.paysum                      as paysum
                     ,  A.dedsum                      as dedsum
                     ,  (A.paysum - A.dedsum)         as netpay
                     ,  D.divinm                      as divinm
                     ,  E.rspnm                       as rspnm
                     ,  D.prtorder                    as prtorder
                     ,  B.rtdate                      as rtdate
                     ,  s.SENDSTATUS                  as sendstatus
                     ,  s.TESTYN                      as testyn
                     ,  s.SENDDT                      as senddt
                  from  TB_JB001 A
                  join  TB_JA001 B  on  B.custcd   = A.custcd
                                    and B.spjangcd = A.spjangcd
                                    and B.perid    = A.perid
                  join  TB_PA200 C  on  C.custcd   = A.custcd
                                    and C.spjangcd = A.spjangcd
                                    and C.perid    = A.perid
                                    and C.prodate  = ( select max(x.prodate)
                                                         from TB_PA200 x
                                                        where x.custcd   = A.custcd
                                                          and x.spjangcd = A.spjangcd
                                                          and x.perid    = A.perid
                                                          and x.prodate <= :paybasic99 )
                  join  TB_JC002 D  on  D.custcd   = A.custcd
                                    and D.spjangcd = A.spjangcd
                                    and D.divicd   = C.divicd
                  join  TB_PZ001 E  on  E.custcd   = A.custcd
                                    and E.spjangcd = A.spjangcd
                                    and E.rspcd    = C.rspcd
                  outer apply ( select top 1 x.SENDSTATUS, x.TESTYN, x.SENDDT
                                  from TB_PAYSLIP_SEND x
                                 where x.SPJANGCD = A.spjangcd
                                   and x.PAYYM    = A.paybasic
                                   and x.PAYTYPE  = A.paytype
                                   and x.EMPNO    = A.perid
                                 order by x.SENDSEQ desc ) s
                 where  A.spcheck  = '1'
                   and  A.custcd   = :custcd
                   and  A.spjangcd = :spjangcd
                   and  A.paytype  = :paytype
                   and  A.paybasic = :paybasic
                   and  A.paydate  = :paydate
                   -- 해당 귀속월에 하루라도 재직한 사람 (월중 퇴사자 포함)
                   and  B.entdate <= :paybasic99
                   and  IsNull(B.rtdate, '99999999') >= :paybasic00
                """);

		// ── 선택 조건 ──────────────────────────────────────
		//  부서 · 직위 · 사원은 코드가 아니라 이름으로 찾는다.

		String divinm = get(cond, "divinm", "");
		if (!divinm.isEmpty()) {
			p.addValue("divinm", contains(divinm));
			sql.append("   and  D.divinm like :divinm\n");
		}

		String rspnm = get(cond, "rspnm", "");
		if (!rspnm.isEmpty()) {
			p.addValue("rspnm", contains(rspnm));
			sql.append("   and  E.rspnm like :rspnm\n");
		}

		String pernm = get(cond, "pernm", "");
		if (!pernm.isEmpty()) {
			p.addValue("pernm", contains(pernm));
			sql.append("   and  B.pernm like :pernm\n");
		}

		// 근무형태 · 재직구분은 화면이 코드값을 보내는 셀렉트박스다.
		String mpclafi = get(cond, "mpclafi", "");
		if (!mpclafi.isEmpty()) {
			p.addValue("mpclafi", mpclafi);
			sql.append("   and  B.mpclafi = :mpclafi\n");
		}

		String rtclafi = get(cond, "rtclafi", "");
		if (!rtclafi.isEmpty()) {
			p.addValue("rtclafi", rtclafi);
			sql.append("   and  B.rtclafi = :rtclafi\n");
		}

		// 사번은 화면에서 뺐지만 API 로 들어오면 계속 받아준다.
		// perid 는 'p003' 처럼 앞 한 자리가 접두어라 substring 과 비교한다.
		String perid = normalizePerid(get(cond, "perid", ""));
		if (!perid.isEmpty()) {
			p.addValue("peridLike", perid + "%");
			sql.append("   and  substring(B.perid, 2, len(B.perid)) like :peridLike\n");
		}

		sql.append(" order by D.prtorder, A.perid");

		return normalizeAll(sqlRunner.getRows(sql.toString(), p));
	}

	// ─────────────────────────────────────────────────────────
	//  우측 미리보기 : 명세서 1건
	// ─────────────────────────────────────────────────────────

	/** doctype 을 넘기지 않는 기존 호출부 호환용. 제목은 급여구분을 따른다. */
	public Map<String, Object> getPayslip(String spjangcd, String paytype, String paybasic, String paydate, String perid) {
		return getPayslip(spjangcd, paytype, paybasic, paydate, perid, null);
	}

	/**
	 * @param doctype 화면에서 고른 문서 구분. null · 빈 값이면 급여구분(paytype)을 따른다.
	 */
	public Map<String, Object> getPayslip(String spjangcd, String paytype, String paybasic,
																				String paydate, String perid, String doctype) {

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("custcd", getCustcdBySpjangcd(spjangcd));
		p.addValue("spjangcd", spjangcd);
		p.addValue("paytype", paytype);
		p.addValue("paybasic", paybasic);
		p.addValue("paydate", paydate);
		p.addValue("perid", perid);
		p.addValue("paybasic99", paybasic + "99");

		String headSql = """
                select  A.perid                       as perid
                     ,  substring(A.perid, 2, len(A.perid)) as peridview
                     ,  B.pernm                       as pernm
                     ,  B.birthday                    as birthday
                     ,  B.accnum                      as accnum
                     ,  B.email                       as email
                     ,  A.paytype                     as paytype
                     ,  A.paybasic                    as paybasic
                     ,  A.paydate                     as paydate
                     ,  A.paysum                      as paysum
                     ,  A.dedsum                      as dedsum
                     ,  (A.paysum - A.dedsum)         as netpay
                     ,  D.divinm                      as divinm
                     ,  E.rspnm                       as rspnm
                     ,  H.spjangnm                    as spjangnm
                     ,  ( select workday  from TB_PB203
                           where spjangcd = B.spjangcd and workym = A.paybasic and perid = B.perid ) as workday
                     ,  ( select worktime from TB_PB203
                           where spjangcd = B.spjangcd and workym = A.paybasic and perid = B.perid ) as worktime
                  from  TB_JB001 A
                  join  TB_JA001 B  on  B.custcd   = A.custcd
                                    and B.spjangcd = A.spjangcd
                                    and B.perid    = A.perid
                  join  TB_PA200 C  on  C.custcd   = A.custcd
                                    and C.spjangcd = A.spjangcd
                                    and C.perid    = A.perid
                                    and C.prodate  = ( select max(x.prodate)
                                                         from TB_PA200 x
                                                        where x.custcd   = A.custcd
                                                          and x.spjangcd = A.spjangcd
                                                          and x.perid    = A.perid
                                                          and x.prodate <= :paybasic99 )
                  join  TB_JC002 D  on  D.custcd   = A.custcd
                                    and D.spjangcd = A.spjangcd
                                    and D.divicd   = C.divicd
                  join  TB_PZ001 E  on  E.custcd   = A.custcd
                                    and E.spjangcd = A.spjangcd
                                    and E.rspcd    = C.rspcd
                  join  TB_XA012 H  on  H.custcd   = A.custcd
                                    and H.spjangcd = A.spjangcd
                 where  A.spcheck  = '1'
                   and  A.custcd   = :custcd
                   and  A.spjangcd = :spjangcd
                   and  A.paytype  = :paytype
                   and  A.paybasic = :paybasic
                   and  A.paydate  = :paydate
                   and  A.perid    = :perid
                """;

		Map<String, Object> head = normalize(sqlRunner.getRow(headSql, p));
		if (head == null || head.isEmpty()) return null;

		// 헤더 문구: "2026년 08월 급여명세서" / "2026년 08월 수당지급명세서"
		// docnm 은 PDF 파일명에서도 쓴다. 제목과 파일명이 어긋나지 않게 한곳에서 정한다.
		String docnm = docName(doctype, str(head.get("paytype")));
		head.put("docnm", docnm);
		head.put("title", formatYm(str(head.get("paybasic"))) + " " + docnm);

		// pmtype 'A' 지급 / 'B' 공제
		List<Map<String, Object>> payItems = new ArrayList<>();
		List<Map<String, Object>> deductItems = new ArrayList<>();
		for (Map<String, Object> row : getPayItems(p, perid)) {
			if ("A".equalsIgnoreCase(str(row.get("pmtype")))) payItems.add(row);
			else                                              deductItems.add(row);
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("head", head);
		result.put("payItems", payItems);
		result.put("deductItems", deductItems);
		result.put("calcItems", getCalcItems());
		return result;
	}

	// ══════════════════════════════════════════════════════════
	//  지급 / 공제 내역
	//
	//  레거시는 d_p3012_2 의 a01~a18 / b01~b18 자리를 비워두고,
	//  ue_retrieve / 명세서저장 스크립트에서 DataStore 3개를 돌며 채워 넣는다.
	//    d_pb300_ds : 내역   TB_PB300 (perid, pmtype, pmitemcd, payamount)
	//    d_pb100_ds : 항목   TB_PB100 (pmtype, pmitemcd, pmitemnm, inputno, atdcd, kapplyn, sapplyn)
	//                        기간 컬럼이 없어 조인만으로 유일하게 결정된다.
	//                        useyn 은 걸지 않는다 — 폐지 항목도 과거 급여에는 남아야 한다.
	//    d_pb203_ds : 근태   TB_PB203 (perid, workday, worktime, atdnum**)
	//
	//  여기서는 같은 결과를 SQL 조인 한 번으로 만든다.
	// ══════════════════════════════════════════════════════════
	private List<Map<String, Object>> getPayItems(MapSqlParameterSource p, String perid) {

		// pmtype : 'A' 지급 / 'B' 공제
		// 급여구분별 적용 여부 — 레거시 CHOOSE CASE strCondition[7] 과 동일
		//   001 급여   : kapplyn = '0' 이면 제외
		//   002 상여   : sapplyn = '0' 이면 제외
		//   003 급상여 : 제외 없음
		String sql = """
				select  D.pmtype     as pmtype
				     ,  D.pmitemcd   as pmitemcd
				     ,  M.pmitemnm   as itemnm
				     ,  D.payamount  as amount
				     ,  M.atdcd      as atdcd
				     ,  M.inputno    as inputno
				  from  TB_PB300 D
				  join  TB_PB100 M  on  M.custcd   = D.custcd
				                    and M.spjangcd = D.spjangcd
				                    and M.pmtype   = D.pmtype
				                    and M.pmitemcd = D.pmitemcd
				 where  D.custcd    = :custcd
				   and  D.spjangcd  = :spjangcd
				   and  D.paytype   = :paytype
				   and  D.paybasic  = :paybasic
				   and  D.paydate   = :paydate
				   and  D.perid     = :perid
				   and  D.payamount <> 0
				   and  ( :paytype = '003'
				          or ( :paytype = '001' and IsNull(M.kapplyn, '1') <> '0' )
				          or ( :paytype = '002' and IsNull(M.sapplyn, '1') <> '0' ) )
				 -- 레거시 dsPb300.SetSort("perid A, inputno A, pmtype A, pmitemcd A") 와 동일.
				 -- inputno 는 TB_PB100(항목마스터)의 출력순서 컬럼이다.
				 order by M.inputno, D.pmtype, D.pmitemcd
				""";

		List<Map<String, Object>> items = normalizeAll(sqlRunner.getRows(sql, p));
		if (items.isEmpty()) return items;

		// 근태는 한 번만 읽어 두고 atdcd 로 컬럼을 찾아간다.
		// atdnum** 은 컬럼명이 코드에 따라 달라져 SQL 로 풀기 어려우므로 레거시와 같은 방식을 쓴다.
		Map<String, Object> atd = getAttendance(p, perid);

		for (Map<String, Object> it : items) {
			String atdcd = str(it.get("atdcd"));
			if (atdcd.isEmpty() || atd == null) {
				it.put("workhour", null);
				continue;
			}
			Object v;
			if ("WD".equalsIgnoreCase(atdcd))      v = atd.get("workday");
			else if ("WT".equalsIgnoreCase(atdcd)) v = atd.get("worktime");
			else                                   v = atd.get(("atdnum" + atdcd).toLowerCase());

			// 0 이면 레거시도 찍지 않는다
			it.put("workhour", isZero(v) ? null : v);
		}
		return items;
	}

	/**
	 * TB_PB203 근태 한 행. atdnum** 컬럼을 통째로 들고 온다.
	 *
	 * 근태가 있는 달과 없는 달이 섞여 있는 것이 정상이므로 0건은 오류가 아니다.
	 * getRow 는 0건이면 EmptyResultDataAccessException 을 던지고, SqlRunQueryImpl 이
	 * 던지기 전에 ERROR 로 스택을 통째로 남긴다. 직원을 클릭할 때마다 쌓이므로 getRows 를 쓴다.
	 * 여기서 null 을 돌려주면 호출부가 workhour 를 비운다.
	 */
	private Map<String, Object> getAttendance(MapSqlParameterSource p, String perid) {
		try {
			MapSqlParameterSource q = new MapSqlParameterSource();
			q.addValue("custcd", p.getValue("custcd"));
			q.addValue("spjangcd", p.getValue("spjangcd"));
			q.addValue("workym", p.getValue("paybasic"));
			q.addValue("perid", perid);

			List<Map<String, Object>> rows = sqlRunner.getRows("""
					select * from TB_PB203
					 where custcd = :custcd and spjangcd = :spjangcd
					   and workym = :workym and perid = :perid
					""", q);

			// 그 달 근태가 없는 것뿐이다. 로그를 남기지 않는다.
			if (rows == null || rows.isEmpty()) return null;

			return normalize(rows.get(0));
		} catch (Exception e) {
			// 여기까지 오면 진짜 오류다 (테이블 부재 · 접속 실패 등)
			log.warn("[Payslip] 근태 조회 실패 perid={}", perid, e);
			return null;
		}
	}

	private boolean isZero(Object v) {
		if (v == null) return true;
		try {
			return Double.parseDouble(String.valueOf(v).trim()) == 0d;
		} catch (Exception e) {
			return false;
		}
	}

	/** 항목별 계산방법. 근로기준법상 급여명세서 필수 기재사항이다. */
	public List<Map<String, Object>> getCalcItems() {
		return List.of(
			calc("연장근로수당", "연장근로시간 × 통상시급 × 1.5",               "국민연금",     "기준소득월액 × 4.5%"),
			calc("야간근로수당", "야간근로시간수 × 통상시급 × 0.5",             "건강보험",     "보수월액 × 3.545%"),
			calc("휴일근로수당", "휴일근로시간수 × 통상시급 × 1.5 (8시간이내)",  "장기요양보험", "건강보험료 × 12.95%"),
			calc("",           "휴일근로시간수 × 통상시급 × 2 (8시간초과)",      "고용보험",     "과세대상임금 × 0.9%"),
			calc("연차수당",    "미사용연차일수 × 통상시급 × 8시간 (1일근로시간)", "",           "")
		);
	}

	// ─────────────────────────────────────────────────────────
	//  발송 이력
	// ─────────────────────────────────────────────────────────

	public void saveSendLog(String spjangcd, String paytype, String paybasic, String perid, String email,
													String status, Map<String, Object> head, String errMsg,
													boolean testMode, String userId) {

		if (userId != null && userId.length() > 20) userId = userId.substring(0, 20);

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("custcd", getCustcdBySpjangcd(spjangcd));
		p.addValue("spjangcd", spjangcd);
		p.addValue("paytype", paytype);
		p.addValue("payym", paybasic);
		p.addValue("empno", perid);
		p.addValue("email", email);
		p.addValue("status", status);
		p.addValue("paysum", num(head, "paysum"));
		p.addValue("dedsum", num(head, "dedsum"));
		p.addValue("netpay", num(head, "netpay"));
		p.addValue("errmsg", cut(errMsg, 500));
		p.addValue("testyn", testMode ? "Y" : "N");
		p.addValue("inuserid", userId);

		sqlRunner.execute("""
                insert into TB_PAYSLIP_SEND
                       (CUSTCD, SPJANGCD, PAYTYPE, PAYYM, EMPNO, EMAIL, SENDSTATUS,
                        PAYSUM, DEDSUM, NETPAY, ERRMSG, TESTYN, INUSERID, SENDDT)
                values (:custcd, :spjangcd, :paytype, :payym, :empno, :email, :status,
                        :paysum, :dedsum, :netpay, :errmsg, :testyn, :inuserid, GETDATE())
                """, p);
	}

	public List<Map<String, Object>> getSendHistory(String spjangcd, String paytype, String paybasic, String perid) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("custcd", getCustcdBySpjangcd(spjangcd));
		p.addValue("spjangcd", spjangcd);
		p.addValue("paytype", paytype);
		p.addValue("payym", paybasic);
		p.addValue("empno", perid);

		return normalizeAll(sqlRunner.getRows("""
                select SENDSEQ as sendseq, EMAIL as email, SENDSTATUS as sendstatus
                     , PAYSUM as paysum, DEDSUM as dedsum, NETPAY as netpay
                     , ERRMSG as errmsg, TESTYN as testyn, SENDDT as senddt, INUSERID as inuserid
                  from TB_PAYSLIP_SEND
                 where CUSTCD = :custcd and SPJANGCD = :spjangcd
                   and PAYTYPE = :paytype and PAYYM = :payym and EMPNO = :empno
                 order by SENDSEQ desc
                """, p));
	}

	// ─────────────────────────────────────────────────────────
	//  내부
	// ─────────────────────────────────────────────────────────

	private MapSqlParameterSource baseParams(String spjangcd, Map<String, String> cond) {
		String paybasic = get(cond, "paybasic", "");

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("custcd", getCustcdBySpjangcd(spjangcd));
		p.addValue("spjangcd", spjangcd);
		p.addValue("paytype", get(cond, "paytype", "001"));
		p.addValue("paybasic", paybasic);
		p.addValue("paydate", get(cond, "paydate", ""));

		// 선택 조건(부서명·직위명·성명·근무형태·재직구분·사번)은
		// getEmployeeList 가 값이 있을 때만 직접 바인딩한다.

		// 재직 판정 경계값
		p.addValue("paybasic99", paybasic + "99");
		p.addValue("paybasic00", paybasic + "00");
		return p;
	}

	/**
	 * 사번 검색어 정규화.
	 *   "003"  -> "003"
	 *   "p003" -> "003"   (접두 문자를 떼어낸다)
	 *   ""     -> ""      (전체 조회)
	 */
	private String normalizePerid(String v) {
		String s = v == null ? "" : v.trim();
		if (s.isEmpty()) return "";
		// 첫 글자가 숫자가 아니면 접두어로 보고 제거
		if (!Character.isDigit(s.charAt(0))) s = s.substring(1);
		return s;
	}

	/** 빈 값이면 전체(%), 값이 있으면 정확히 그 값 */
	/** 이름 검색용 부분일치. 앞뒤 공백은 버린다. */
	private String contains(String v) {
		return "%" + (v == null ? "" : v.trim()) + "%";
	}

	private String like(String v) {
		String s = v == null ? "" : v.trim();
		return s.isEmpty() ? "%" : s;
	}

	private String get(Map<String, String> m, String k, String def) {
		if (m == null) return def;
		String v = m.get(k);
		return (v == null || v.isBlank()) ? def : v.trim();
	}

	/** 202608 → "2026년 08월" (레거시 string(paybasic, '@@@@년 @@월')) */
	public String formatYm(String yyyymm) {
		if (yyyymm == null || yyyymm.length() < 6) return yyyymm;
		return yyyymm.substring(0, 4) + "년 " + yyyymm.substring(4, 6) + "월";
	}

	/** 20260825 → "2026년 08월 25일" */
	public String formatYmd(String yyyymmdd) {
		if (yyyymmdd == null || yyyymmdd.length() < 8) return yyyymmdd;
		return yyyymmdd.substring(0, 4) + "년 " + yyyymmdd.substring(4, 6) + "월 " + yyyymmdd.substring(6, 8) + "일";
	}

	/**
	 * 사업장코드로 회사코드를 조회한다.
	 * 레거시 gs_custcd 에 대응하며, ApprovalStatusService.getCustcdBySpjangcd 와 같은 방식이다.
	 *
	 * 발송 루프에서 인원수만큼 반복 호출되므로 결과를 캐시한다.
	 * spjangcd 에 대한 custcd 는 바뀌지 않는 값이다.
	 */
	public String getCustcdBySpjangcd(String spjangcd) {
		if (spjangcd == null || spjangcd.isBlank()) {
			throw new IllegalStateException("사업장코드(spjangcd)가 없습니다. 세션이 만료되었을 수 있습니다.");
		}

		String cached = CUSTCD_CACHE.get(spjangcd);
		if (cached != null) return cached;

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("spjangcd", spjangcd);

		Map<String, Object> row = sqlRunner.getRow("""
				SELECT custcd FROM tb_xa012 WHERE spjangcd = :spjangcd
				""", p);

		if (row == null || row.isEmpty() || row.get("custcd") == null) {
			throw new IllegalStateException("tb_xa012 에 사업장이 없습니다. spjangcd=" + spjangcd);
		}

		String custcd = String.valueOf(row.get("custcd")).trim();
		CUSTCD_CACHE.put(spjangcd, custcd);
		return custcd;
	}

	private Map<String, Object> calc(String lgb, String lformula, String rgb, String rformula) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("lgb", lgb);
		m.put("lformula", lformula);
		m.put("rgb", rgb);
		m.put("rformula", rformula);
		return m;
	}

	/**
	 * 컬럼명을 소문자로 통일한다.
	 * 테넌트마다 MSSQL / PostgreSQL / Oracle 이 섞여 있어 대소문자가 달라지는 것을 여기서 흡수한다.
	 */
	private Map<String, Object> normalize(Map<String, Object> row) {
		if (row == null) return null;
		Map<String, Object> out = new LinkedHashMap<>();
		row.forEach((k, v) -> out.put(k.toLowerCase(), v));
		return out;
	}

	private List<Map<String, Object>> normalizeAll(List<Map<String, Object>> rows) {
		if (rows == null) return new ArrayList<>();
		List<Map<String, Object>> out = new ArrayList<>(rows.size());
		for (Map<String, Object> r : rows) out.add(normalize(r));
		return out;
	}

	private String str(Object o) {
		return o == null ? "" : String.valueOf(o).trim();
	}

	private Long num(Map<String, Object> m, String key) {
		if (m == null || m.get(key) == null) return null;
		try {
			return (long) Double.parseDouble(String.valueOf(m.get(key)).trim());
		} catch (Exception e) {
			return null;
		}
	}

	private String cut(String s, int len) {
		if (s == null) return null;
		return s.length() <= len ? s : s.substring(0, len);
	}

	/** 발송 팝업의 회신 주소 기본값. 없으면 "" 를 돌려준다 — 화면은 빈 칸으로 둔다. */
	public String getSpjangEmail(String spjangcd) {
		return getBizInfoBySpjangcd(spjangcd).get("emailadres");
	}

	private Map<String, String> getBizInfoBySpjangcd(String spjangcd) {
		MapSqlParameterSource param = new MapSqlParameterSource().addValue("spjangcd", spjangcd);
		String sql = """
       SELECT saupnum, custcd, spjangnm, emailadres
       FROM tb_xa012 WHERE spjangcd = :spjangcd
       """;
		Map<String, Object> row = sqlRunner.getRow(sql, param);
		Map<String, String> result = new HashMap<>();
		result.put("custcd", "");
		result.put("spjangnm", "");
		result.put("emailadres", "");
		if (row == null || row.isEmpty()) return result;
		result.put("custcd",     row.get("custcd")     == null ? "" : String.valueOf(row.get("custcd")).trim());
		result.put("spjangnm",   row.get("spjangnm")   == null ? "" : String.valueOf(row.get("spjangnm")).trim());
		result.put("emailadres", row.get("emailadres") == null ? "" : String.valueOf(row.get("emailadres")).trim());
		return result;
	}
	/** 발송 팝업의 메일 제목 기본 문안. 없으면 "" 를 돌려준다 — 화면이 치환어로 대체한다. */
	public String getSpjangName(String spjangcd) {
		return getBizInfoBySpjangcd(spjangcd).get("spjangnm");
	}
}