package mes.app.payroll.service;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 사업소득급여등록 (원본 w_j3011 / d_j3011_list2 · d_j3011_input · d_j3012_preview2 · d_j3011_input_pop_exl4)
 * 일괄입력 팝업   (원본 w_j3011_pop / d_j3011_input_pop · d_j3014_condition)
 * 테이블 TB_J3011  PK (custcd, seq, paybasic, paydate, payclafi)
 */
@Slf4j
@Service
public class BizIncomePayService {

	private static final String CLAFI = BizIncomeEarnerService.CLAFI;   // '1' 사업소득

	@Autowired
	SqlRunner sqlRunner;

	// ── 공통 ─────────────────────────────────────────────

	private String custcd() {
		Map<String, Object> row = sqlRunner.getRow("SELECT custcd FROM tb_xa012 WHERE spjangcd = :s",
			new MapSqlParameterSource().addValue("s", TenantContext.get()));
		return row == null || row.get("custcd") == null ? "" : String.valueOf(row.get("custcd")).trim();
	}

	private static String str(Map<String, Object> m, String k) {
		Object v = m.get(k);
		return v == null ? "" : v.toString().trim();
	}

	private static String digits(String v) { return v == null ? "" : v.replaceAll("[^0-9]", ""); }

	private static String likeVal(String v) { return v == null || v.trim().isEmpty() ? "%" : v.trim(); }

	private static BigDecimal dec(Map<String, Object> m, String k) {
		String v = str(m, k).replace(",", "");
		if (v.isEmpty()) return BigDecimal.ZERO;
		try { return new BigDecimal(v); } catch (Exception e) { return BigDecimal.ZERO; }
	}

	private static String nullIfEmpty(String v) { return v == null || v.isEmpty() ? null : v; }

	// ── 콤보 ─────────────────────────────────────────────

	/** 구분(com_cls 100) · 상세(com_cls 101) · 사업장 · 부서 · 팝업 지급일 목록 */
	public Map<String, Object> codes() {
		String custcd = custcd();
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("custcd", custcd);
		Map<String, Object> m = new HashMap<>();
		m.put("gubun1", sqlRunner.getRows(
			"SELECT com_code AS v, com_cnam AS n FROM tb_ca510 WHERE com_cls = '100' AND com_code <> '00' ORDER BY com_code", p));
		m.put("gubun2", sqlRunner.getRows(
			"SELECT com_code AS v, com_cnam AS n FROM tb_ca510 WHERE com_cls = '101' AND com_code <> '00' ORDER BY com_code", p));
		m.put("spjang", sqlRunner.getRows(
			"SELECT spjangcd AS v, spjangnm AS n FROM tb_xa012 WHERE custcd = :custcd ORDER BY spjangcd", p));
		m.put("divi", sqlRunner.getRows(
			"SELECT spjangcd AS s, divicd AS v, divinm AS n FROM tb_jc002 WHERE custcd = :custcd ORDER BY spjangcd, divicd", p));
		m.put("spjangcd", TenantContext.get());
		return m;
	}

	/** 팝업 지급일 드롭다운 — 등록된 지급일, 최근 순 */
	public List<Map<String, Object>> paydates() {
		return sqlRunner.getRows(
			"SELECT DISTINCT paydate FROM TB_J3011 WHERE custcd = :custcd AND ISNULL(paydate, '') <> '' ORDER BY paydate DESC",
			new MapSqlParameterSource().addValue("custcd", custcd()));
	}

	// ── 메인 : 왼쪽 소득자 목록 (d_j3011_list2) ─────────────

	/**
	 * flag 0 = 귀속월(paybasic) · 1 = 지급월(paydate 앞 6자리) 로 기간을 건다.
	 * 원본 +'01'/'99' 비교 때문에 시작월이 빠지던 것은 6자리끼리 비교로 바로잡음.
	 * 지급액이 있으면 status 1 (회색), 계약만료인데 지급이 없으면 목록에서 뺌.
	 */
	public List<Map<String, Object>> list(String flag, String ym1, String ym2, String gubun1, String gubun2, String pernm) {
		String col = "1".equals(flag) ? "LEFT(A.paydate, 6)" : "A.paybasic";
		MapSqlParameterSource p = new MapSqlParameterSource()
			.addValue("custcd", custcd()).addValue("clafi", CLAFI)
			.addValue("ym1", digits(ym1)).addValue("ym2", digits(ym2))
			.addValue("gubun1", likeVal(gubun1)).addValue("gubun2", likeVal(gubun2))
			.addValue("pernm", likeVal(pernm));
		return sqlRunner.getRows("""
			SELECT * FROM (
			  SELECT B.seq, B.comname, B.saupnum, B.rnum, B.forernum, B.foreryn, B.resclafi,
			         ISNULL(B.conyn, '0') AS conyn,
			         ISNULL((SELECT SUM(A.paysum) FROM TB_J3011 A
			                  WHERE A.custcd = B.custcd AND A.seq = B.seq
			                    AND %s BETWEEN :ym1 AND :ym2), 0) AS paysum
			    FROM TB_J3010 B
			   WHERE B.custcd = :custcd AND B.clafi = :clafi
			     AND B.comname LIKE :pernm + '%%'
			     AND ISNULL(B.gubun1, '') LIKE :gubun1 + '%%'
			     AND ISNULL(B.gubun2, '') LIKE :gubun2 + '%%'
			) T
			 WHERE NOT (T.conyn = '1' AND T.paysum = 0)
			 ORDER BY CASE WHEN T.paysum > 0 THEN 1 ELSE 0 END DESC, T.comname
			""".formatted(col), p);
	}

	/** 왼쪽 아래 합계 (원본 d_j3012_preview5) : 기준과 상관없이 늘 지급월(paydate 앞 6자리)로 모음 */
	public Map<String, Object> totals(String flag, String ym1, String ym2) {
		String col = "LEFT(A.paydate, 6)";
		return sqlRunner.getRow("""
			SELECT ISNULL(SUM(A.incotax), 0) AS incotax, ISNULL(SUM(A.rtax), 0) AS rtax,
			       ISNULL(SUM(A.paysum), 0) AS paysum, COUNT(*) AS cnt,
			       ISNULL(SUM(A.paysum - ISNULL(A.incotax, 0) - ISNULL(A.rtax, 0)), 0) AS netamt
			  FROM TB_J3011 A
			  JOIN TB_J3010 B ON B.custcd = A.custcd AND B.seq = A.seq AND B.clafi = :clafi
			 WHERE A.custcd = :custcd AND %s BETWEEN :ym1 AND :ym2
			""".formatted(col), new MapSqlParameterSource().addValue("custcd", custcd()).addValue("clafi", CLAFI)
			.addValue("ym1", digits(ym1)).addValue("ym2", digits(ym2)));
	}

	// ── 메인 : 아래 지급내역 (d_j3012_preview2) ─────────────

	public List<Map<String, Object>> history(String seq, String flag, String ym1, String ym2) {
		String col = "1".equals(flag) ? "LEFT(paydate, 6)" : "paybasic";
		return sqlRunner.getRows("""
			SELECT custcd, seq, paybasic, paydate, payclafi, paysum, taxrate, incotax, rtax,
			       ISNULL(incotax, 0) + ISNULL(rtax, 0) AS taxsum
			  FROM TB_J3011
			 WHERE custcd = :custcd AND seq = :seq
			   AND %s BETWEEN :ym1 AND :ym2
			 ORDER BY paydate DESC, paybasic DESC, payclafi
			""".formatted(col), new MapSqlParameterSource()
			.addValue("custcd", custcd()).addValue("seq", seq)
			.addValue("ym1", digits(ym1)).addValue("ym2", digits(ym2)));
	}

	private static final String ONE_COLS = """
		SELECT custcd, seq, paybasic, paydate, payclafi, paysum, taxrate, incotax, rtax, chagamt,
		       spjangcd, divicd,
		       (SELECT TOP 1 divinm FROM tb_jc002 WHERE custcd = TB_J3011.custcd AND spjangcd = TB_J3011.spjangcd AND divicd = TB_J3011.divicd) AS divinm,
		       spdate, spnum, spcheck, remark, rowseq
		  FROM TB_J3011
		""";

	/** 입력폼 (d_j3011_input) */
	public Map<String, Object> one(String seq, String paybasic, String paydate, String payclafi) {
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("custcd", custcd())
			.addValue("seq", seq).addValue("paybasic", digits(paybasic)).addValue("paydate", digits(paydate))
			.addValue("payclafi", payclafi == null ? "" : payclafi);
		return sqlRunner.getRow(ONE_COLS + """
			 WHERE custcd = :custcd AND seq = :seq AND paybasic = :paybasic AND paydate = :paydate
			   AND (:payclafi = '' OR payclafi = :payclafi)
			""", p);
	}

	// ── 메인 엑셀 (d_j3011_input_pop_exl4) : 지급월 기준 ─────

	public List<Map<String, Object>> excelList(String ym1, String ym2) {
		return sqlRunner.getRows("""
			SELECT A.seq, A.paybasic, A.paydate, B.comname, B.saupnum, B.rnum, B.forernum, B.foreryn,
			       A.paysum, A.incotax, A.rtax, 1 AS cnt
			  FROM TB_J3011 A
			  JOIN TB_J3010 B ON B.custcd = A.custcd AND B.seq = A.seq AND B.clafi = :clafi
			 WHERE A.custcd = :custcd AND A.spjangcd = :spjangcd
			   AND LEFT(A.paydate, 6) BETWEEN :ym1 AND :ym2
			 ORDER BY A.paydate, B.comname
			""", new MapSqlParameterSource().addValue("custcd", custcd()).addValue("clafi", CLAFI)
			.addValue("spjangcd", TenantContext.get())
			.addValue("ym1", digits(ym1)).addValue("ym2", digits(ym2)));
	}

	// ── 팝업 조회 (d_j3011_input_pop) ──────────────────────

	public List<Map<String, Object>> popList(String paydate) {
		return sqlRunner.getRows("""
			SELECT A.custcd, A.seq, B.comname, B.saupnum, B.forernum, B.rnum, B.foreryn, B.resclafi,
			       A.paybasic, A.paydate, A.payclafi, A.paysum, A.taxrate, A.incotax, A.rtax, A.chagamt,
			       A.spjangcd, A.divicd,
			       (SELECT TOP 1 divinm FROM tb_jc002 WHERE custcd = A.custcd AND spjangcd = A.spjangcd AND divicd = A.divicd) AS divinm,
			       A.spdate, A.spnum, A.remark, A.rowseq
			  FROM TB_J3011 A
			  LEFT JOIN TB_J3010 B ON B.custcd = A.custcd AND B.seq = A.seq AND B.clafi = :clafi
			 WHERE A.custcd = :custcd AND A.paydate = :paydate
			 ORDER BY A.rowseq, B.comname
			""", new MapSqlParameterSource().addValue("custcd", custcd()).addValue("clafi", CLAFI)
			.addValue("paydate", digits(paydate)));
	}

	/** 팝업에서 사업자를 고를 때 쓰는 계약중 사업소득자 목록 */
	public List<Map<String, Object>> earners() {
		return sqlRunner.getRows("""
			SELECT seq, comname, saupnum, rnum, forernum, foreryn, ISNULL(resclafi, '1') AS resclafi
			  FROM TB_J3010
			 WHERE custcd = :custcd AND clafi = :clafi AND ISNULL(conyn, '0') = '0'
			 ORDER BY comname
			""", new MapSqlParameterSource().addValue("custcd", custcd()).addValue("clafi", CLAFI));
	}

	// ── 저장 · 삭제 ───────────────────────────────────────

	private static void checkYm(String v, String name) {
		if (v.length() != 6) throw new IllegalArgumentException(name + "을(를) 입력하십시오.");
		int m = Integer.parseInt(v.substring(4));
		if (m < 1 || m > 12) throw new IllegalArgumentException(name + "이(가) 올바르지 않습니다.");
	}

	private static void checkDate(String v, String name) {
		try { LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE); }
		catch (Exception e) { throw new IllegalArgumentException(name + "가 올바르지 않습니다."); }
	}

	/** 한 줄 저장. org 가 있으면 그 키의 기존 행을 고친다 (키가 바뀌면 지우고 새로 넣음). */
	private void saveOne(String custcd, Map<String, Object> r, Map<String, Object> org, String label) {
		String seq      = str(r, "seq");
		String paybasic = digits(str(r, "paybasic"));
		String paydate  = digits(str(r, "paydate"));
		String payclafi = str(r, "payclafi").isEmpty() ? "1" : str(r, "payclafi");
		if (seq.isEmpty()) throw new IllegalArgumentException(label + "사업소득자를 선택하십시오.");
		checkYm(paybasic, label + "귀속년월");
		checkDate(paydate, label + "지급일자");

		Map<String, Object> e = sqlRunner.getRow(
			"SELECT 1 AS x FROM TB_J3010 WHERE custcd = :c AND clafi = :clafi AND seq = :seq",
			new MapSqlParameterSource().addValue("c", custcd).addValue("clafi", CLAFI).addValue("seq", seq));
		if (e == null) throw new IllegalArgumentException(label + "등록되지 않은 사업소득자입니다.");

		BigDecimal paysum  = dec(r, "paysum");
		BigDecimal incotax = dec(r, "incotax");
		BigDecimal rtax    = dec(r, "rtax");
		BigDecimal chagamt = paysum.subtract(incotax.add(rtax));

		MapSqlParameterSource p = new MapSqlParameterSource()
			.addValue("custcd", custcd).addValue("seq", seq).addValue("paybasic", paybasic)
			.addValue("paydate", paydate).addValue("payclafi", payclafi)
			.addValue("paysum", paysum).addValue("taxrate", dec(r, "taxrate"))
			.addValue("incotax", incotax).addValue("rtax", rtax).addValue("chagamt", chagamt)
			.addValue("spjangcd", str(r, "spjangcd").isEmpty() ? TenantContext.get() : str(r, "spjangcd"))
			.addValue("divicd", nullIfEmpty(str(r, "divicd")))
			.addValue("remark", str(r, "remark"))
			.addValue("rowseq", str(r, "rowseq").isEmpty() ? null : Integer.valueOf(digits(str(r, "rowseq")).isEmpty() ? "0" : digits(str(r, "rowseq"))));

		boolean sameKey = org != null
			&& seq.equals(str(org, "seq")) && paybasic.equals(digits(str(org, "paybasic")))
			&& paydate.equals(digits(str(org, "paydate"))) && payclafi.equals(str(org, "payclafi"));

		if (!sameKey) {
			Map<String, Object> dup = sqlRunner.getRow("""
				SELECT 1 AS x FROM TB_J3011
				 WHERE custcd = :custcd AND seq = :seq AND paybasic = :paybasic AND paydate = :paydate AND payclafi = :payclafi
				""", p);
			if (dup != null) throw new IllegalArgumentException(label + "같은 귀속년월 · 지급일자 · 지급구분의 자료가 이미 있습니다.");
		}

		if (org != null && sameKey) {
			sqlRunner.execute("""
				UPDATE TB_J3011
				   SET paysum = :paysum, taxrate = :taxrate, incotax = :incotax, rtax = :rtax, chagamt = :chagamt,
				       spjangcd = :spjangcd, divicd = :divicd, remark = :remark, rowseq = ISNULL(:rowseq, rowseq)
				 WHERE custcd = :custcd AND seq = :seq AND paybasic = :paybasic AND paydate = :paydate AND payclafi = :payclafi
				""", p);
			return;
		}
		if (org != null) deleteKey(custcd, org);   // 키가 바뀐 행 : 옛 키 지우고 새로 넣음 (PB 의 UPDATE 와 같은 결과)
		sqlRunner.execute("""
			INSERT INTO TB_J3011 (custcd, seq, paybasic, paydate, payclafi, paysum, taxrate, incotax, rtax, chagamt,
			                      spjangcd, divicd, remark, rowseq)
			VALUES (:custcd, :seq, :paybasic, :paydate, :payclafi, :paysum, :taxrate, :incotax, :rtax, :chagamt,
			        :spjangcd, :divicd, :remark, :rowseq)
			""", p);
	}

	private void deleteKey(String custcd, Map<String, Object> k) {
		sqlRunner.execute("""
			DELETE FROM TB_J3011
			 WHERE custcd = :custcd AND seq = :seq AND paybasic = :paybasic AND paydate = :paydate AND payclafi = :payclafi
			""", new MapSqlParameterSource().addValue("custcd", custcd).addValue("seq", str(k, "seq"))
			.addValue("paybasic", digits(str(k, "paybasic"))).addValue("paydate", digits(str(k, "paydate")))
			.addValue("payclafi", str(k, "payclafi")));
	}

	/** 메인 입력폼 저장 */
	@Transactional
	public void save(Map<String, Object> row, Map<String, Object> org) {
		saveOne(custcd(), row, org, "");
	}

	/** 메인 삭제 — PK 전체(지급구분 포함)로 한 건만 */
	@Transactional
	public void delete(Map<String, Object> key) {
		if (str(key, "seq").isEmpty()) throw new IllegalArgumentException("삭제할 자료를 선택하십시오.");
		deleteKey(custcd(), key);
	}

	/** 팝업 일괄 저장 : rows (새 줄 · 고친 줄, 고친 줄은 _org 에 원래 키) + deleted (원래 키) */
	@SuppressWarnings("unchecked")
	@Transactional
	public int saveBatch(List<Map<String, Object>> rows, List<Map<String, Object>> deleted) {
		String custcd = custcd();
		if (deleted != null) for (Map<String, Object> d : deleted) deleteKey(custcd, d);
		int n = 0;
		if (rows != null) for (Map<String, Object> r : rows) {
			Map<String, Object> org = (Map<String, Object>) r.get("_org");
			saveOne(custcd, r, org, (str(r, "rowseq").isEmpty() ? "" : str(r, "rowseq") + "번째 줄 : "));
			n++;
		}
		return n;
	}

	// ── 급여소득복사 (원본 wf_paycopy) ─────────────────────

	/** 복사 전 확인 : 원본 귀속년월(그 지급일의 귀속년월 하나) · 대상 지급일에 이미 있는 건수 */
	public Map<String, Object> copyCheck(String srcPaydate, String newPaybasic, String newPaydate) {
		MapSqlParameterSource p = new MapSqlParameterSource().addValue("spjangcd", TenantContext.get())
			.addValue("src", digits(srcPaydate)).addValue("nb", digits(newPaybasic)).addValue("nd", digits(newPaydate));
		Map<String, Object> s = sqlRunner.getRow(
			"SELECT TOP 1 paybasic FROM TB_J3011 WHERE spjangcd = :spjangcd AND paydate = :src", p);
		Map<String, Object> c = sqlRunner.getRow(
			"SELECT COUNT(*) AS cnt FROM TB_J3011 WHERE spjangcd = :spjangcd AND paybasic = :nb AND paydate = :nd", p);
		Map<String, Object> m = new HashMap<>();
		m.put("srcPaybasic", s == null ? "" : str(s, "paybasic"));
		m.put("exists", c == null ? 0 : Integer.parseInt(str(c, "cnt").isEmpty() ? "0" : str(c, "cnt")));
		return m;
	}

	/**
	 * 원본 지급일 · 귀속년월의 자료를 새 지급일 · 귀속년월로 복사.
	 * replace = true 면 대상에 있던 자료를 지우고 복사 (원본 「이미 등록되어있습니다. 삭제후 진행하시겠습니까?」 [예]).
	 * 전표 연결(spdate · spnum · spcheck)은 새 지급자료에 옮기지 않는다 — 원본은 옛 전표번호까지 복사했음.
	 */
	@Transactional
	public int copy(String srcPaydate, String srcPaybasic, String newPaybasic, String newPaydate, boolean replace) {
		String nb = digits(newPaybasic), nd = digits(newPaydate);
		checkYm(nb, "귀속년월");
		checkDate(nd, "지급일자");
		if (digits(srcPaydate).isEmpty() || digits(srcPaybasic).isEmpty())
			throw new IllegalArgumentException("복사하고자 하는 지급일자를 다시 선택하세요.");
		if (nd.equals(digits(srcPaydate)) && nb.equals(digits(srcPaybasic)))
			throw new IllegalArgumentException("원본과 같은 귀속년월 · 지급일자로는 복사할 수 없습니다.");
		MapSqlParameterSource p = new MapSqlParameterSource()
			.addValue("custcd", custcd()).addValue("spjangcd", TenantContext.get())
			.addValue("sb", digits(srcPaybasic)).addValue("sd", digits(srcPaydate))
			.addValue("nb", nb).addValue("nd", nd);
		Map<String, Object> c = sqlRunner.getRow(
			"SELECT COUNT(*) AS cnt FROM TB_J3011 WHERE spjangcd = :spjangcd AND paybasic = :nb AND paydate = :nd", p);
		if (c != null && !"0".equals(str(c, "cnt"))) {
			if (!replace) throw new IllegalStateException("이미 등록되어있습니다.");
			sqlRunner.execute("DELETE FROM TB_J3011 WHERE spjangcd = :spjangcd AND paybasic = :nb AND paydate = :nd", p);
		}
		return sqlRunner.execute("""
			INSERT INTO TB_J3011 (custcd, seq, paybasic, paydate, payclafi, paysum, taxrate, incotax, rtax, chagamt,
			                      spjangcd, divicd, rowseq)
			SELECT custcd, seq, :nb, :nd, payclafi, paysum, taxrate, incotax, rtax, chagamt,
			       spjangcd, divicd, rowseq
			  FROM TB_J3011
			 WHERE custcd = :custcd AND spjangcd = :spjangcd AND paybasic = :sb AND paydate = :sd
			""", p);
	}

	/** PB 와 같은 세금 계산 (참고용 — 화면에서 계산해 보냄) */
	public static BigDecimal rtaxOf(BigDecimal incotax) {
		return incotax.divide(BigDecimal.TEN, 0, RoundingMode.DOWN);
	}
}
