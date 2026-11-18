package mes.app.basecode.service;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 사업계획등록 (원본 w_tb_x0002 : d_tb_x0002_1 사업등록 / d_tb_x0002_3 + d_tb_x0002_cnt 회차등록)
 *
 * ⚠ 원본 open 이벤트의 "DELETE FROM TB_X0002 WHERE custcd <> :gs_custcd" 는 옮기지 않았다.
 *    한 DB 를 여러 회사가 쓰면 다른 회사 사업이 지워진다.
 */
@Slf4j
@Service
public class BusinessPlanService {

	@Autowired
	SqlRunner sqlRunner;

	private String custcd() {
		Map<String, Object> row = sqlRunner.getRow(
			"SELECT custcd FROM tb_xa012 WHERE spjangcd = :spjangcd",
			new MapSqlParameterSource().addValue("spjangcd", TenantContext.get()));
		return row == null || row.get("custcd") == null ? "" : String.valueOf(row.get("custcd")).trim();
	}

	private String str(Map<String, Object> m, String k) {
		Object v = m.get(k);
		return v == null ? "" : v.toString().trim();
	}

	private int count(String sql, MapSqlParameterSource p) {
		Map<String, Object> r = sqlRunner.getRow(sql, p);
		return r == null || r.get("cnt") == null ? 0 : Integer.parseInt(String.valueOf(r.get("cnt")).trim());
	}

	/** yyyymmdd 실제 날짜인지 (원본 fchkdate) */
	private boolean validDate(String d) {
		if (d == null || !d.matches("\\d{8}")) return false;
		try {
			LocalDate.parse(d, DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT));
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	/** 출력 머리글용 사업장명 (원본 gf_spjangnm()) */
	public String spjangnm() {
		Map<String, Object> row = sqlRunner.getRow(
			"SELECT spjangnm FROM tb_xa012 WHERE spjangcd = :spjangcd",
			new MapSqlParameterSource().addValue("spjangcd", TenantContext.get()));
		return row == null || row.get("spjangnm") == null ? "" : String.valueOf(row.get("spjangnm")).trim();
	}

	/** 조회줄 사업구분 콤보 */
	public List<Map<String, Object>> sections() {
		return sqlRunner.getRows("""
			SELECT bseccd, bsecnm FROM TB_X0001
			 WHERE custcd = :custcd AND bseccd <> '00'
			 ORDER BY ISNULL(seqno, 9999), bseccd
			""", new MapSqlParameterSource().addValue("custcd", custcd()));
	}

	/** 사업등록 / 회차등록 왼쪽 목록 (원본 d_tb_x0002_1 · _3 같은 SQL) */
	public List<Map<String, Object>> list(String yyyy, String bseccd, String keyword) {
		String kw = (keyword == null || keyword.trim().isEmpty()) ? "%" : keyword.trim();
		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("custcd", custcd())
																.addValue("bsdate", yyyy == null ? "" : yyyy.trim())
																.addValue("bseccd", bseccd == null ? "" : bseccd.trim())
																.addValue("busicd", kw);
		return sqlRunner.getRows("""
			SELECT a.custcd, a.bsdate, a.bseccd, a.busicd, a.businm,
			       a.frdate, a.todate, a.place, a.entno, a.useyn
			  FROM TB_X0002 a
			 WHERE a.custcd = :custcd
			   AND a.bsdate = :bsdate
			   AND a.bseccd = :bseccd
			   AND (a.busicd LIKE '%' + :busicd + '%' OR a.businm LIKE '%' + :busicd + '%')
			 ORDER BY a.busicd
			""", p);
	}

	/** wf_save : 한 건 저장. 새 건은 연도·사업구분 안에서 max(busicd)+1 (3자리, 없으면 001) */
	@Transactional
	public Map<String, Object> save(Map<String, Object> r) {
		String custcd = custcd();
		String bsdate = str(r, "bsdate");
		String bseccd = str(r, "bseccd");
		String busicd = str(r, "busicd");
		String businm = str(r, "businm");
		String frdate = str(r, "frdate").replace("-", "");
		String todate = str(r, "todate").replace("-", "");

		if (!bsdate.matches("\\d{4}")) throw new IllegalArgumentException("사업년도를 4자리로 입력하세요.");
		if (bseccd.isEmpty()) throw new IllegalArgumentException("사업구분을 선택하세요.");
		if (businm.isEmpty()) throw new IllegalArgumentException("사업명을 입력하세요.");
		if (!frdate.isEmpty() && !validDate(frdate)) throw new IllegalArgumentException("날자형식이 올바르지 않습니다. (시작)");
		if (!todate.isEmpty() && !validDate(todate)) throw new IllegalArgumentException("날자형식이 올바르지 않습니다. (종료)");
		if (!frdate.isEmpty() && !todate.isEmpty() && frdate.compareTo(todate) > 0)
			throw new IllegalArgumentException("사업기간이 올바르지 않습니다.");

		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("custcd", custcd).addValue("bsdate", bsdate).addValue("bseccd", bseccd)
																.addValue("businm", businm)
																.addValue("frdate", frdate.isEmpty() ? null : frdate)
																.addValue("todate", todate.isEmpty() ? null : todate)
																.addValue("place", str(r, "place").isEmpty() ? null : str(r, "place"))
																.addValue("entno", str(r, "entno").isEmpty() ? null : str(r, "entno"))
																.addValue("useyn", "0".equals(str(r, "useyn")) ? "0" : "1");

		if (busicd.isEmpty()) {
			Map<String, Object> mx = sqlRunner.getRow("""
				SELECT MAX(busicd) AS mx FROM TB_X0002 WITH (UPDLOCK, HOLDLOCK)
				 WHERE custcd = :custcd AND bsdate = :bsdate AND bseccd = :bseccd
				""", p);
			String last = mx == null || mx.get("mx") == null ? "" : String.valueOf(mx.get("mx")).trim();
			long next = last.isEmpty() ? 1 : Long.parseLong(last) + 1;
			if (next > 999) throw new IllegalStateException("사업코드(3자리)를 더 만들 수 없습니다.");
			busicd = String.format("%03d", next);
			p.addValue("busicd", busicd);
			sqlRunner.execute("""
				INSERT INTO TB_X0002 (custcd, bsdate, bseccd, busicd, businm, frdate, todate, place, entno, useyn)
				VALUES (:custcd, :bsdate, :bseccd, :busicd, :businm, :frdate, :todate, :place, :entno, :useyn)
				""", p);
		} else {
			p.addValue("busicd", busicd);
			sqlRunner.execute("""
				UPDATE TB_X0002
				   SET businm = :businm, frdate = :frdate, todate = :todate,
				       place = :place, entno = :entno, useyn = :useyn
				 WHERE custcd = :custcd AND bsdate = :bsdate AND bseccd = :bseccd AND busicd = :busicd
				""", p);
		}
		Map<String, Object> res = new HashMap<>();
		res.put("busicd", busicd);
		return res;
	}

	/** ue_delete : 예산 · 예/결산 · 전표에서 쓰는 사업이면 막는다 */
	@Transactional
	public void delete(String bsdate, String bseccd, String busicd) {
		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("custcd", custcd()).addValue("bsdate", bsdate).addValue("bseccd", bseccd)
																.addValue("busicd", busicd).addValue("it1cd", bseccd + busicd);

		if (count("SELECT COUNT(*) AS cnt FROM TB_X0007 WHERE tiosec = '2' AND custcd = :custcd AND it1cd = :it1cd", p) > 0)
			throw new IllegalStateException("선택하신 코드는 예산관리(세출내역서등록)에서 사용하고 있습니다.");
		if (count("SELECT COUNT(*) AS cnt FROM TB_X0008 WHERE tiosec = '2' AND custcd = :custcd AND it1cd = :it1cd", p) > 0)
			throw new IllegalStateException("선택하신 코드는 예/결산내역서(세출내역서등록)에서 사용하고 있습니다.");
		// 원본은 a.it1cd 로 썼지만 항 코드는 라인(TB_AA010)에 있다
		if (count("""
			SELECT COUNT(*) AS cnt FROM TB_AA009 a, TB_AA010 b
			 WHERE a.custcd = b.custcd AND a.spjangcd = b.spjangcd AND a.spdate = b.spdate AND a.spnum = b.spnum
			   AND b.tiosec = '2' AND a.custcd = :custcd AND b.it1cd = :it1cd
			""", p) > 0)
			throw new IllegalStateException("선택하신 코드는 전표입력에서 사용하고 있습니다.");

		sqlRunner.execute("""
			DELETE FROM TB_X0002
			 WHERE custcd = :custcd AND bsdate = :bsdate AND bseccd = :bseccd AND busicd = :busicd
			""", p);
	}

	/** 자료이월 : 고른 사업을 이월년도로 복사 (코드는 이월년도 · 사업구분 안에서 새로 매김, 날짜는 연도만 바꿈) */
	@Transactional
	public int carry(String syyyy, List<Map<String, Object>> rows) {
		if (syyyy == null || !syyyy.trim().matches("\\d{4}")) throw new IllegalArgumentException("이월년도를 입력하세요");
		syyyy = syyyy.trim();
		String custcd = custcd();
		int cnt = 0;
		for (Map<String, Object> r : rows) {
			String bseccd = str(r, "bseccd");
			MapSqlParameterSource p = new MapSqlParameterSource()
																	.addValue("custcd", custcd).addValue("bsdate", syyyy).addValue("bseccd", bseccd);
			Map<String, Object> mx = sqlRunner.getRow("""
				SELECT MAX(busicd) AS mx FROM TB_X0002 WITH (UPDLOCK, HOLDLOCK)
				 WHERE custcd = :custcd AND bsdate = :bsdate AND bseccd = :bseccd
				""", p);
			String last = mx == null || mx.get("mx") == null ? "" : String.valueOf(mx.get("mx")).trim();
			String busicd = String.format("%03d", (last.isEmpty() ? 0 : Long.parseLong(last)) + 1);
			String fr = str(r, "frdate").replace("-", ""), to = str(r, "todate").replace("-", "");
			if (fr.length() == 8) fr = syyyy + fr.substring(4);
			if (to.length() == 8) to = syyyy + to.substring(4);
			p.addValue("busicd", busicd).addValue("businm", str(r, "businm"))
				.addValue("frdate", fr.isEmpty() ? null : fr).addValue("todate", to.isEmpty() ? null : to)
				.addValue("place", str(r, "place").isEmpty() ? null : str(r, "place"))
				.addValue("entno", str(r, "entno").isEmpty() ? null : str(r, "entno"))
				.addValue("useyn", str(r, "useyn").isEmpty() ? null : str(r, "useyn"));
			sqlRunner.execute("""
				INSERT INTO TB_X0002 (custcd, bsdate, bseccd, busicd, businm, frdate, todate, place, entno, useyn)
				VALUES (:custcd, :bsdate, :bseccd, :busicd, :businm, :frdate, :todate, :place, :entno, :useyn)
				""", p);
			cnt++;
		}
		return cnt;
	}

	// ── 회차등록 (TB_X0002_CNT) ──
	private MapSqlParameterSource cntKey(String bsdate, String bseccd, String busicd) {
		return new MapSqlParameterSource()
						 .addValue("custcd", custcd()).addValue("spjangcd", TenantContext.get())
						 .addValue("bsdate", bsdate).addValue("bseccd", bseccd).addValue("busicd", busicd);
	}

	public List<Map<String, Object>> cntList(String bsdate, String bseccd, String busicd) {
		return sqlRunner.getRows("""
			SELECT A.custcd, A.spjangcd, A.bsdate, A.bseccd, A.busicd, A.seq, A.cntname, A.useyn
			  FROM TB_X0002_CNT A
			 WHERE A.custcd = :custcd AND A.spjangcd = :spjangcd
			   AND A.bsdate = :bsdate AND A.bseccd = :bseccd AND A.busicd = :busicd
			 ORDER BY A.seq
			""", cntKey(bsdate, bseccd, busicd));
	}

	/** 회차 저장 : 새 행(seq 비어 있음)은 max(seq)+1 (2자리, 없으면 01) */
	@Transactional
	public void cntSave(String bsdate, String bseccd, String busicd, List<Map<String, Object>> rows) {
		MapSqlParameterSource k = cntKey(bsdate, bseccd, busicd);
		Map<String, Object> mx = sqlRunner.getRow("""
			SELECT MAX(seq) AS mx FROM TB_X0002_CNT WITH (UPDLOCK, HOLDLOCK)
			 WHERE custcd = :custcd AND spjangcd = :spjangcd
			   AND bsdate = :bsdate AND bseccd = :bseccd AND busicd = :busicd
			""", k);
		String last = mx == null || mx.get("mx") == null ? "" : String.valueOf(mx.get("mx")).trim();
		long next = last.isEmpty() ? 1 : Long.parseLong(last) + 1;
		for (Map<String, Object> r : rows) {
			MapSqlParameterSource p = cntKey(bsdate, bseccd, busicd)
																	.addValue("cntname", str(r, "cntname"))
																	.addValue("useyn", "0".equals(str(r, "useyn")) ? "0" : "1");
			String seq = str(r, "seq");
			if (seq.isEmpty()) {
				if (next > 99) throw new IllegalStateException("회차코드(2자리)를 더 만들 수 없습니다.");
				p.addValue("seq", String.format("%02d", next++));
				sqlRunner.execute("""
					INSERT INTO TB_X0002_CNT (custcd, spjangcd, bsdate, bseccd, busicd, seq, cntname, useyn)
					VALUES (:custcd, :spjangcd, :bsdate, :bseccd, :busicd, :seq, :cntname, :useyn)
					""", p);
			} else {
				p.addValue("seq", seq);
				sqlRunner.execute("""
					UPDATE TB_X0002_CNT SET cntname = :cntname, useyn = :useyn
					 WHERE custcd = :custcd AND spjangcd = :spjangcd
					   AND bsdate = :bsdate AND bseccd = :bseccd AND busicd = :busicd AND seq = :seq
					""", p);
			}
		}
	}

	@Transactional
	public void cntDelete(String bsdate, String bseccd, String busicd, String seq) {
		sqlRunner.execute("""
			DELETE FROM TB_X0002_CNT
			 WHERE custcd = :custcd AND spjangcd = :spjangcd
			   AND bsdate = :bsdate AND bseccd = :bseccd AND busicd = :busicd AND seq = :seq
			""", cntKey(bsdate, bseccd, busicd).addValue("seq", seq));
	}
}