package mes.app.account_management.service;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import mes.domain.services.SqlRunner;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import javax.transaction.Transactional;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class SlipEntryService {

	@Autowired
	SqlRunner sqlRunner;


	public List<Map<String, Object>> getBusim(String busim) {
		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("busim", busim);

		String sql = """
			select
					bsdate,
					bseccd,
					busicd as buiscd,
					businm as busim
			from tb_x0002
			where replace(businm,' ','')
						like '%' + replace(:busim,' ','') + '%'
			order by bsdate DESC
			""";

		return sqlRunner.getRows(sql, param);
	}

	public List<Map<String, Object>> getAccnm(String accnm, String type) {
		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("accnm", accnm);

		String typeCondition = "card".equals(type) ? "and acccd like '7%'" : "";

		String sql = """
    select
      acccd,
      accnm, acnflag
    from tb_ac001
    where useyn = '1' and spyn='1'
    and replace(isnull(accnm, ''), ' ', '') like '%' + replace(:accnm, ' ', '') + '%'
    """
									 + typeCondition;

		return sqlRunner.getRows(sql, param);
	}

	public List<Map<String, Object>> getIt1nm(String it1nm) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("it1nm", it1nm);

		String sql = """
			select it1cd ,it1nm from tb_x0003 
			where useyn='1'
			 and replace(isnull(it1nm, ''), ' ', '') like '%' + replace(:it1nm, ' ', '') + '%'
			""";
		return sqlRunner.getRows(sql, param);
	}

	public List<Map<String, Object>> getIt2nm(String it2nm) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("it2nm", it2nm);

		String sql = """
			select it2cd ,it2nm from tb_x0004
			where useyn='1'
			and replace(isnull(it2nm, ''), ' ', '') like '%' + replace(:it2nm, ' ', '') + '%'
			""";
		return sqlRunner.getRows(sql, param);

	}

	@Transactional
	public Map<String, Object> saveSlip(Map<String, Object> payload, HttpServletRequest request) {

		// ── 테넌트/사업장 정보 ──
		String spjangcd = TenantContext.get();
		Map<String, String> bizInfo = getBizInfoBySpjangcd(spjangcd);
		String custcd   = bizInfo.get("custcd");
		String spjangnm = bizInfo.get("spjangnm");

		// ── 헤더 데이터 수집 ──
		String spdate  = getString(payload, "spdate").replace("-", "");
		String spnum   = getString(payload, "spnum");
		String tiosec  = getString(payload, "tiosec");
		String readate = getString(payload, "readate").replace("-", "");
		String busipur = getString(payload, "busipur");
		String spoccu  = getString(payload, "spoccu");
		String cashyn  = getString(payload, "cashyn");
		String subject = getString(payload, "subject"); //제목
		String remark  = getString(payload, "remark");	//비고
		String busicd  = getString(payload, "busicd");
		String bsdate  = getString(payload, "bsdate");
		String bseccd  = getString(payload, "bseccd");
		String today   = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

		boolean isNew = isBlank(spnum);

		// ── 신규 시 전표번호 채번 ──
		if (isNew) {
			spnum = generateSpnum(custcd, spjangcd, spdate);
		}

		// ── TB_AA009 헤더 저장 ──
		MapSqlParameterSource headerParam = new MapSqlParameterSource()
			.addValue("custcd",    custcd)		//회사코드
			.addValue("spjangcd",  spjangcd)	//사업장코드
			.addValue("spdate",    spdate)		//전표일자(발송일자)
			.addValue("spnum",     spnum)		//전표 번호
			.addValue("tiosec",    tiosec)		//
			.addValue("cashyn",    cashyn)
			.addValue("busipur",   busipur)
			.addValue("spoccu",    spoccu)
			.addValue("remark",    remark)
			.addValue("subject",   subject)
			.addValue("regdate",   isBlank(readate) ? today : readate)
			.addValue("bsdate",    bsdate)
			.addValue("bseccd",    bseccd)
			.addValue("busicd",    busicd)
			.addValue("spjangnm",  spjangnm)
			.addValue("inputdate", LocalDateTime.now());

		if (isNew) {
			String insertAa009 = """
            INSERT INTO TB_AA009 (
                custcd, spjangcd, spdate, spnum,
                tiosec, cashyn, busipur, spoccu,
                remark, subject, regdate,
                bsdate, bseccd, busicd,
                spjangnm, inputdate
            ) VALUES (
                :custcd, :spjangcd, :spdate, :spnum,
                :tiosec, :cashyn, :busipur, :spoccu,
                :remark, :subject, :regdate,
                :bsdate, :bseccd, :busicd,
                :spjangnm, :inputdate
            )
            """;
			sqlRunner.execute(insertAa009, headerParam);
		} else {
			String updateAa009 = """
            UPDATE TB_AA009 SET
                tiosec    = :tiosec,
                cashyn    = :cashyn,
                busipur   = :busipur,
                spoccu    = :spoccu,
                remark    = :remark,
                subject   = :subject,
                regdate   = :regdate,
                bsdate    = :bsdate,
                bseccd    = :bseccd,
                busicd    = :busicd,
                inputdate = :inputdate
            WHERE custcd   = :custcd
              AND spjangcd = :spjangcd
              AND spdate   = :spdate
              AND spnum    = :spnum
            """;
			sqlRunner.execute(updateAa009, headerParam);
		}

		// ── TB_AA010 기존 라인 삭제 후 재INSERT ──
		MapSqlParameterSource delParam = new MapSqlParameterSource()
		 .addValue("custcd",   custcd)
		 .addValue("spjangcd", spjangcd)
		 .addValue("spdate",   spdate)
		 .addValue("spnum",    spnum);

		sqlRunner.execute("""
        DELETE FROM TB_AA010
        WHERE custcd   = :custcd
          AND spjangcd = :spjangcd
          AND spdate   = :spdate
          AND spnum    = :spnum
        """, delParam);

		// ── 라인 INSERT ──
		List<Map<String, Object>> lines =
			(List<Map<String, Object>>) payload.get("lines");

		if (lines != null) {
			String insertAa010 = """
            INSERT INTO TB_AA010 (
                custcd, spjangcd, spdate, spnum, spseq,
                spjangnm, acccd, accnm, drcr,
                dramt, cramt, summy,
                it1cd, it2cd,
                tiosec, spoccu,
                inputdate, rowseq
            ) VALUES (
                :custcd, :spjangcd, :spdate, :spnum, :spseq,
                :spjangnm, :acccd, :accnm, :drcr,
                :dramt, :cramt, :summy,
                :it1cd, :it2cd,
                :tiosec, :spoccu,
                :inputdate, :rowseq
            )
            """;

			for (Map<String, Object> line : lines) {
				int lineSeq = ((Number) line.get("lineSeq")).intValue();

				BigDecimal dramt = getBigDecimal(line, "dramt");
				BigDecimal cramt = getBigDecimal(line, "cramt");

				// it1cd zero-padding (tb_aa010.it1cd = char(5))
				String it1cd = getString(line, "it1cd");
				if (!isBlank(it1cd)) {
					it1cd = StringUtils.leftPad(it1cd, 5, "0");
				}

				MapSqlParameterSource lineParam = new MapSqlParameterSource()
					.addValue("custcd",    custcd)
					.addValue("spjangcd",  spjangcd)
					.addValue("spdate",    spdate)
					.addValue("spnum",     spnum)
					.addValue("spseq",     String.format("%04d", lineSeq))
					.addValue("spjangnm",  spjangnm)
					.addValue("acccd",     getString(line, "acccd"))
					.addValue("accnm",     getString(line, "accnm"))
					.addValue("drcr",      getString(line, "drcr"))
					.addValue("dramt",     dramt)
					.addValue("cramt",     cramt)
					.addValue("summy",     getString(line, "summy"))
					.addValue("it1cd",     it1cd)
					.addValue("it2cd",     getString(line, "it2cd"))
					.addValue("tiosec",    tiosec)
					.addValue("spoccu",    spoccu)
					.addValue("inputdate", LocalDateTime.now())
					.addValue("rowseq",    lineSeq);

				sqlRunner.execute(insertAa010, lineParam);
			}
		}

		// ── 결과 반환 ──
		Map<String, Object> result = new HashMap<>();
		result.put("spnum",  spnum);
		result.put("spdate", spdate);
		return result;
	}

	// ── 전표번호 채번 (월별) ──
	private String generateSpnum(String custcd, String spjangcd, String spdate) {
		String yyyymm = spdate.substring(0, 6);

		MapSqlParameterSource param = new MapSqlParameterSource()
																		.addValue("custcd",   custcd)
																		.addValue("spjangcd", spjangcd)
																		.addValue("yyyymm",   yyyymm);

		String sql = """
        SELECT ISNULL(MAX(CAST(spnum AS INT)), 0) + 1 AS next_spnum
        FROM TB_AA009 WITH (NOLOCK)
        WHERE custcd   = :custcd
          AND spjangcd = :spjangcd
          AND LEN(spnum) = 4
          AND LEFT(spdate, 6) = :yyyymm
        """;

		Map<String, Object> row = sqlRunner.getRow(sql, param);
		int next = 1;
		if (row != null && row.get("next_spnum") != null) {
			try {
				next = Integer.parseInt(String.valueOf(row.get("next_spnum")).trim());
			} catch (Exception e) {
				next = 1;
			}
		}
		return String.format("%04d", next);
	}

	// ── 공통 유틸 (BankAssignmentService와 동일 패턴) ──
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

	private String getString(Map<String, Object> item, String key) {
		Object value = item.get(key);
		return value == null ? "" : value.toString().trim();
	}

	private BigDecimal getBigDecimal(Map<String, Object> item, String key) {
		Object value = item.get(key);
		if (value == null || value.toString().trim().isEmpty()) return BigDecimal.ZERO;
		try {
			return new BigDecimal(value.toString().replace(",", ""));
		} catch (Exception e) {
			return BigDecimal.ZERO;
		}
	}

	private boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}

	//전표 라인조회
	public List<Map<String, Object>> getLines(String spdate, String spnum) {
		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("spdate", spdate);
		param.addValue("spnum", spnum);

		String spjangcd = TenantContext.get();
		Map<String, String> bizInfo = getBizInfoBySpjangcd(spjangcd);
		String custcd   = bizInfo.get("custcd");
		param.addValue("custcd", custcd);
		param.addValue("spjangcd", spjangcd);

		String sql = """
			select * from tb_aa010
			        where custcd =:custcd
			        and spjangcd =:spjangcd
			        AND spdate   = :spdate
			        AND spnum    = :spnum
			""";
		return sqlRunner.getRows(sql ,param);
	}

	public List<Map<String, Object>> getHeader(String spdate, String spnum) {
		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("spdate", spdate);
		param.addValue("spnum", spnum);

		String spjangcd = TenantContext.get();
		Map<String, String> bizInfo = getBizInfoBySpjangcd(spjangcd);
		String custcd   = bizInfo.get("custcd");
		param.addValue("custcd", custcd);
		param.addValue("spjangcd", spjangcd);

		String sql = """
			select * from tb_aa009
			where custcd =:custcd 
			and spjangcd =:spjangcd
			AND spdate   = :spdate
			AND spnum    = :spnum
			""";
		return sqlRunner.getRows(sql ,param);
	}

	public List<Map<String, Object>> getMssec() {
		MapSqlParameterSource param = new MapSqlParameterSource();
		String sql = """
			select mssec as value, mssecnm as text from tb_x0005 where 1=1
			""";
		return sqlRunner.getRows(sql , param);
	}
}
