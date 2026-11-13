package mes.app.system.service;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class PersonListService { // 사원관리 (tb_ja001)

	@Autowired
	SqlRunner sqlRunner;

	// custcd 조회 (사업장코드 → 회사코드)
	private String getCustcd(String spjangcd) {
		MapSqlParameterSource param = new MapSqlParameterSource().addValue("spjangcd", spjangcd);
		String sql = "SELECT custcd FROM tb_xa012 WHERE spjangcd = :spjangcd";
		Map<String, Object> row = sqlRunner.getRow(sql, param);
		if (row == null || row.get("custcd") == null) return "";
		return String.valueOf(row.get("custcd")).trim();
	}

	private MapSqlParameterSource baseParam() {
		MapSqlParameterSource param = new MapSqlParameterSource();
		String spjangcd = TenantContext.get();
		String custcd = getCustcd(spjangcd);
		param.addValue("spjangcd", spjangcd);
		param.addValue("custcd", custcd);
		return param;
	}

	// ============================================================
	// 목록 조회
	//   pernm   : 성명 LIKE (없으면 전체)
	//   divicd  : 부서 정확일치 (없으면 전체)
	//   retire  : 재직여부 - '0' 재직 / '1' 퇴사 / '' 전체
	// ============================================================
	public Object selectList(String pernm, String divicd, String retire) {
		MapSqlParameterSource param = baseParam();
		param.addValue("pernm", (pernm == null || pernm.trim().isEmpty()) ? "%" : "%" + pernm.trim() + "%");
		param.addValue("divicd", (divicd == null) ? "" : divicd.trim());

		String retireCond = "";
		if (retire != null && !retire.trim().isEmpty()) {
			if ("1".equals(retire.trim())) {
				retireCond = " AND A.retireflag = '1' ";
			} else {
				retireCond = " AND ( A.retireflag <> '1' OR A.retireflag IS NULL ) ";
			}
		}

		String diviCond = "";
		if (divicd != null && !divicd.trim().isEmpty()) {
			diviCond = " AND A.divicd = :divicd ";
		}

		String sql = """
        SELECT A.custcd, A.spjangcd, A.perid, A.pernm, A.pernme, A.pernmc,
               A.sex,
               CASE A.sex WHEN '1' THEN '남' WHEN '2' THEN '여' ELSE '' END AS sexnm,
               A.rnum, A.birthday,
               A.divicd, A.divinm, A.clacd, A.clanm,
               A.rspcd, A.stpcd, A.gracd,
               A.telnum, A.tel, A.handphone, A.email,
               A.entdate,
               STUFF(STUFF(A.entdate,5,0,'-'),8,0,'-') AS entdate_fmt,
               A.rtdate, A.retiredate,
               A.retireflag,
               CASE WHEN A.retireflag = '1' THEN '퇴사' ELSE '재직' END AS retirenm,
               A.bankcd, A.banknm, A.accnum,
               A.zipcd, A.zipadres, A.adres,
               A.foreryn, A.remark
          FROM tb_ja001 A WITH (NOLOCK)
         WHERE A.custcd = :custcd
           AND A.spjangcd = :spjangcd
           AND ( A.pernm LIKE :pernm OR :pernm = '%' )
           """ + diviCond + retireCond + """
         ORDER BY A.perid
        """;
		return sqlRunner.getRows(sql, param);
	}

	// ============================================================
	// 단건 조회 (상세 폼)
	// ============================================================
	public Object selectOne(String perid) {
		MapSqlParameterSource param = baseParam();
		param.addValue("perid", (perid == null) ? "" : perid.trim());

		String sql = """
        SELECT A.custcd, A.spjangcd, A.perid, A.pernm, A.pernme, A.pernmc,
               A.sex, A.rnum, A.birthday,
               A.divicd, A.divinm, A.clacd, A.clanm,
               A.telnum, A.tel, A.handphone, A.email,
               A.entdate, A.rtdate, A.retiredate, A.retireflag,
               A.bankcd, A.banknm, A.accnum,
               A.zipcd, A.zipadres, A.adres,
               A.rzipcd, A.rzipadres, A.radres,
               A.foreryn, A.forerrnum, A.mrgyn, A.remark
          FROM tb_ja001 A WITH (NOLOCK)
         WHERE A.custcd = :custcd
           AND A.spjangcd = :spjangcd
           AND A.perid = :perid
        """;
		return sqlRunner.getRow(sql, param);
	}

	// ============================================================
	// 신규 사번 채번 (해당 사업장 내 최대 + 1, 4자리 zero-pad)
	// ============================================================
	public String nextPerid() {
		MapSqlParameterSource param = baseParam();
		String sql = """
        SELECT ISNULL(MAX(CAST(perid AS INT)), 0) + 1 AS nextno
          FROM tb_ja001
         WHERE custcd = :custcd AND spjangcd = :spjangcd
           AND ISNUMERIC(perid) = 1
        """;
		Map<String, Object> row = sqlRunner.getRow(sql, param);
		int no = 1;
		if (row != null && row.get("nextno") != null) {
			no = Integer.parseInt(String.valueOf(row.get("nextno")));
		}
		return String.format("%04d", no);
	}

	// ============================================================
	// 저장 (신규/수정) - perid 존재 여부로 분기
	// ============================================================
	public Map<String, Object> save(Map<String, Object> p) {
		MapSqlParameterSource param = baseParam();

		String perid = str(p.get("perid"));
		param.addValue("perid", perid);
		param.addValue("pernm", str(p.get("pernm")));
		param.addValue("sex", str(p.get("sex")));
		param.addValue("rnum", str(p.get("rnum")));
		param.addValue("birthday", digits(p.get("birthday")));
		param.addValue("divicd", str(p.get("divicd")));
		param.addValue("divinm", str(p.get("divinm")));
		param.addValue("clacd", str(p.get("clacd")));
		param.addValue("clanm", str(p.get("clanm")));
		param.addValue("telnum", str(p.get("telnum")));
		param.addValue("handphone", str(p.get("handphone")));
		param.addValue("email", str(p.get("email")));
		param.addValue("entdate", digits(p.get("entdate")));
		param.addValue("retiredate", digits(p.get("retiredate")));
		param.addValue("retireflag", str(p.get("retireflag")).isEmpty() ? "0" : str(p.get("retireflag")));
		param.addValue("bankcd", str(p.get("bankcd")));
		param.addValue("banknm", str(p.get("banknm")));
		param.addValue("accnum", str(p.get("accnum")));
		param.addValue("zipcd", str(p.get("zipcd")));
		param.addValue("zipadres", str(p.get("zipadres")));
		param.addValue("adres", str(p.get("adres")));
		param.addValue("remark", str(p.get("remark")));

		boolean exists = false;
		if (!perid.isEmpty()) {
			String chkSql = """
            SELECT COUNT(*) AS cnt FROM tb_ja001
             WHERE custcd = :custcd AND spjangcd = :spjangcd AND perid = :perid
            """;
			Map<String, Object> chk = sqlRunner.getRow(chkSql, param);
			exists = chk != null && chk.get("cnt") != null
								 && Integer.parseInt(String.valueOf(chk.get("cnt"))) > 0;
		}

		// 신규면 사번 채번
		if (perid.isEmpty()) {
			perid = nextPerid();
			param.addValue("perid", perid);
		}

		if (exists) {
			String updSql = """
            UPDATE tb_ja001 SET
                   pernm = :pernm, sex = :sex, rnum = :rnum, birthday = :birthday,
                   divicd = :divicd, divinm = :divinm, clacd = :clacd, clanm = :clanm,
                   telnum = :telnum, handphone = :handphone, email = :email,
                   entdate = :entdate, retiredate = :retiredate, retireflag = :retireflag,
                   bankcd = :bankcd, banknm = :banknm, accnum = :accnum,
                   zipcd = :zipcd, zipadres = :zipadres, adres = :adres,
                   remark = :remark
             WHERE custcd = :custcd AND spjangcd = :spjangcd AND perid = :perid
            """;
			sqlRunner.execute(updSql, param);
		} else {
			String insSql = """
            INSERT INTO tb_ja001
                   (custcd, spjangcd, perid, pernm, sex, rnum, birthday,
                    divicd, divinm, clacd, clanm,
                    telnum, handphone, email,
                    entdate, retiredate, retireflag,
                    bankcd, banknm, accnum,
                    zipcd, zipadres, adres, remark)
            VALUES (:custcd, :spjangcd, :perid, :pernm, :sex, :rnum, :birthday,
                    :divicd, :divinm, :clacd, :clanm,
                    :telnum, :handphone, :email,
                    :entdate, :retiredate, :retireflag,
                    :bankcd, :banknm, :accnum,
                    :zipcd, :zipadres, :adres, :remark)
            """;
			sqlRunner.execute(insSql, param);
		}

		Map<String, Object> result = new HashMap<>();
		result.put("perid", perid);
		result.put("mode", exists ? "update" : "insert");
		return result;
	}

	// ============================================================
	// 삭제
	// ============================================================
	public void delete(String perid) {
		MapSqlParameterSource param = baseParam();
		param.addValue("perid", (perid == null) ? "" : perid.trim());
		String sql = """
        DELETE FROM tb_ja001
         WHERE custcd = :custcd AND spjangcd = :spjangcd AND perid = :perid
        """;
		sqlRunner.execute(sql, param);
	}

	// ── helpers ──
	private String str(Object o) {
		return (o == null) ? "" : String.valueOf(o).trim();
	}

	private String digits(Object o) {
		if (o == null) return "";
		return String.valueOf(o).replaceAll("[^0-9]", "");
	}
}