package mes.app.account_management.service;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class SlipStatusService {

	@Autowired
	SqlRunner sqlRunner;

	public List<Map<String, Object>> getSlipList(String start, String end, String mssec, String sbuject) {
		String spjangcd = TenantContext.get();

		Map<String, String> bizInfo = getBizInfoBySpjangcd(spjangcd);
		String custcd = bizInfo.get("custcd");

		MapSqlParameterSource sqlParam = new MapSqlParameterSource();
		sqlParam.addValue("as_custcd",   custcd);
		sqlParam.addValue("as_spjangcd", spjangcd);
		sqlParam.addValue("as_frdate", start.replace("-", "").trim());
		sqlParam.addValue("as_todate", end.replace("-", "").trim());

		String sql = """
        SELECT A.custcd,
               A.spjangcd,
               STUFF(STUFF(A.spdate,5,0,'-'),8,0,'-') as spdate,
               A.spnum,
               CASE A.tiosec
									WHEN '1' THEN '세입'
									WHEN '2' THEN '세출'
									WHEN '3' THEN '대체'
									ELSE A.tiosec
							END AS tiosec,
               A.cashyn,
               A.busipur,
               CASE A.spoccu
									 WHEN 'AA' THEN '전표일반'
									 ELSE A.spoccu
							 END AS spoccu,
               A.remark,
               A.taxdate,
               A.taxnum,
               SUM(B.dramt) AS dramt,
               SUM(B.cramt) AS cramt,
               ISNULL(SUM(B.dramt), SUM(B.cramt)) AS amt,
               MIN(B.comnote) AS summy,
               A.subject,
               A.regdate,
               A.bsdate,
               A.bseccd,
               A.busicd,
               (SELECT businm FROM tb_x0002
                WHERE bsdate = A.bsdate AND bseccd = A.bseccd AND busicd = A.busicd) AS businm,
               MAX(A.setnum) AS setnum,
               CAST('0' AS CHAR(1)) AS prtchk,
               A.appdate,
               A.appperid,
               (SELECT pernm FROM TB_JA001
                WHERE perid = 'p' + A.appperid AND spjangcd = A.spjangcd) AS apppernm,
               A.appgubun,
               A.appnum,
               CASE A.fixflag
			            WHEN '0' THEN '미확정'
			            WHEN '1' THEN '확정'
			            ELSE A.fixflag
			        END AS fixflag,
               (SELECT TOP 1 it2nm FROM tb_x0004
                WHERE tiosec = MAX(B.tiosec) AND it2cd = MAX(B.it2cd)) AS it2nm,
               (SELECT cntname FROM TB_X0002_CNT
                WHERE bsdate = A.bsdate AND bseccd = A.bseccd AND busicd = A.busicd AND seq = A.busicd_cnt) AS busicd_cnt,
               (SELECT mssecnm FROM tb_x0005
                WHERE mssec = MIN(B.mssec)) AS mssec,
               (SELECT banknm FROM tb_aa040
                WHERE custcd = :as_custcd AND spjangcd = A.spjangcd AND bank + bankcd = MAX(B.bankcd)) AS banknm,
               (SELECT cltnm FROM tb_xclient
                WHERE cltcd = MAX(B.cltcd)) AS cltnm,
               (SELECT cardnm FROM tb_iz010
                WHERE custcd = :as_custcd AND cardnum = MAX(B.cardnum)) AS cardnm,
               (SELECT filename FROM TB_AA010ATCH
                WHERE spdate = 'AJ' + A.spdate + A.spnum + A.spjangcd) AS filepath
          FROM TB_AA009 A WITH (NOLOCK),
               TB_AA010 B WITH (NOLOCK)
         WHERE A.custcd   = B.custcd
           AND A.spjangcd = B.spjangcd
           AND A.spdate   = B.spdate
           AND A.spnum    = B.spnum
           AND A.custcd   = :as_custcd
           AND A.spjangcd = :as_spjangcd
           AND A.spdate   BETWEEN :as_frdate AND :as_todate
        """;

		// mssec 조건
		if (mssec != null && !mssec.trim().isEmpty()) {
			sql += " AND B.mssec LIKE :as_spoccu ";
			sqlParam.addValue("as_spoccu", mssec.trim());
		}

		// 제목 조건
		if (sbuject != null && !sbuject.trim().isEmpty()) {
			sql += " AND isnull(A.subject, '') LIKE '%' + :as_subject + '%' ";
			sqlParam.addValue("as_subject", sbuject.trim());
		}

		sql += """
         GROUP BY A.custcd, A.spjangcd, A.spdate, A.spnum, A.tiosec,
						A.cashyn, A.busipur, A.spoccu, A.remark, A.taxdate,
						A.taxnum, A.subject, A.regdate, A.bsdate, A.bseccd,
						A.busicd, A.appdate, A.appperid, A.appgubun, A.appnum,
						A.busicd_cnt, A.fixflag
        """;

//		log.info("전표입력 현황 read sql: {}, param: {}", sql, sqlParam.getValues());
		return sqlRunner.getRows(sql, sqlParam);
	}

	private Map<String, String> getBizInfoBySpjangcd(String spjangcd) {
		MapSqlParameterSource sqlParam = new MapSqlParameterSource();
		sqlParam.addValue("spjangcd", spjangcd);

		String sql = """
        select saupnum, custcd, spjangnm
        from tb_xa012
        where spjangcd = :spjangcd
    """;

		Map<String, Object> row = sqlRunner.getRow(sql, sqlParam);

		Map<String, String> result = new HashMap<>();
		result.put("saupnum", "");
		result.put("custcd", "");
		result.put("spjangnm", "");

		if (row == null || row.isEmpty()) {
			return result;
		}

		Object saupnum = row.get("saupnum");
		Object custcd = row.get("custcd");
		Object spjangnm = row.get("spjangnm");

		result.put("saupnum", saupnum == null ? "" : String.valueOf(saupnum).trim());
		result.put("custcd", custcd == null ? "" : String.valueOf(custcd).trim());
		result.put("spjangnm", custcd == null ? "" : String.valueOf(spjangnm).trim());

		return result;
	}

	public List<Map<String, Object>> printSlip(String printType, String keys) {
		String spjangcd = TenantContext.get();
		Map<String, String> bizInfo = getBizInfoBySpjangcd(spjangcd);
		String custcd = bizInfo.get("custcd");

		// "202603030018,202603030019" → List로 변환
		List<String> keyList = Arrays.asList(keys.split(","));

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("as_custcd",   custcd);
		param.addValue("as_spjangcd", spjangcd);
		param.addValue("as_keys",     keyList);  // ← List로 넘겨야 IN절 동작

		//전표(산출내역)
		String sql = """ 
         SELECT a.spdate,
					a.spnum,
					a.remark,
					b.acccd,
					b.accnm,
					b.it1cd,
					b.it2cd,
					b.mssec,
					(SELECT mssecnm FROM tb_x0005 WHERE mssec = b.mssec) AS mssecnm,
					sum(b.dramt) as dramt,
					sum(b.cramt) as cramt,
					(select it1nm from VW_X0003 where it1cd=B.it1cd and tiosec=B.tiosec) as it1nm,
					(select it2nm from TB_X0004 where it2cd=B.it2cd and tiosec=B.tiosec) as it2nm,
					Max(a.tiosec) as tiosec,
					Max(a.subject) as subject,
					(select businm From tb_x0002 where bsdate=Max(A.bsdate) and bseccd=Max(A.bseccd) and busicd=Max(A.busicd)) as businm,
					(select cntname From TB_X0002_CNT where bsdate=Max(A.bsdate) and bseccd=Max(A.bseccd) and busicd=Max(A.busicd) and seq=max(A.busicd_cnt)) as cntname,
					CASE WHEN b.acccd BETWEEN '1000' AND '1999' THEN '자산'
							 WHEN b.acccd BETWEEN '5600' AND '7999' THEN '비용'
							 WHEN b.acccd BETWEEN '8300' AND '8499' THEN '비용'
							 WHEN b.acccd BETWEEN '8650' AND '8699' THEN '비용'
							 WHEN b.acccd BETWEEN '8750' AND '8799' THEN '비용'
							 WHEN b.acccd BETWEEN '2000' AND '2999' THEN '부채'
							 WHEN b.acccd BETWEEN '3000' AND '3999' THEN '자본'
							 WHEN b.acccd BETWEEN '5000' AND '5599' THEN '수입'
							 WHEN b.acccd BETWEEN '8000' AND '8299' THEN '수입'
							 WHEN b.acccd BETWEEN '8500' AND '8649' THEN '수입'
							 ELSE '기타' END AS upnm,
					b.drcr,
					b.summy,
					a.setnum,
					(select accnum from TB_AA040 where custcd=a.custcd and spjangcd=a.spjangcd and bank + bankcd = B.bankcd) as accnum,
					(select banknm from TB_AA040 where custcd=a.custcd and spjangcd=a.spjangcd and bank + bankcd = B.bankcd) as banknm,
					(select cardnum from TB_IZ010 where custcd=a.custcd and spjangcd=a.spjangcd and cardnum = B.cardnum) as cardnum,
					(select cardnm from TB_IZ010 where custcd=a.custcd and spjangcd=a.spjangcd and cardnum = B.cardnum) as cardnm,
					(SELECT top 1 max(datechk) from tb_xenv where custcd=a.custcd) AS jichuldate,
					CAST('' AS CHAR(1)) AS spnumchk,
					a.spjangnm,
					a.REGDATE,
					b.rowseq,
					a.spoccu,
					b.spjangnm
	 FROM TB_AA009 a,
				TB_AA010 b,
				TB_AC001 c
	 WHERE a.custcd   = b.custcd
		 AND a.spjangcd = b.spjangcd
		 AND a.spdate   = b.spdate
		 AND a.spnum    = b.spnum
		 AND b.custcd   = c.custcd
		 AND b.acccd    = c.acccd
		 AND a.custcd   = :as_custcd
		 AND a.spjangcd = :as_spjangcd
		 AND a.spdate + a.spnum IN (:as_keys)
	 GROUP BY a.custcd, a.spjangcd, a.spdate, a.spnum, b.spseq,
						a.remark, b.acccd, b.accnm, b.it1cd, b.it2cd,
						b.mssec, A.tiosec, b.drcr, b.summy, a.setnum,
						b.bankcd, b.cardnum, B.tiosec, b.dramt, b.cramt,
						a.regdate, a.spjangnm, B.rowseq, a.spoccu, b.spjangnm
	 ORDER BY a.spdate, a.spnum, b.spseq
    """;

		return sqlRunner.getRows(sql, param);
	}

	public List<Map<String, Object>> printGyeolui1(String keys) {

		String spjangcd = TenantContext.get();
		Map<String, String> bizInfo = getBizInfoBySpjangcd(spjangcd);
		String custcd = bizInfo.get("custcd");

		// "202603030018,202603030019" → List로 변환
		List<String> keyList = Arrays.asList(keys.split(","));

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("as_custcd",   custcd);
		param.addValue("as_spjangcd", spjangcd);
		param.addValue("as_keys",     keyList);

		String sql = """
			SELECT   Z.spdate,
				 Z.spnum,
				 max(Z.remark)    as remark,
				 max(Z.acccd)     as acccd,
				 max(Z.accnm)     as accnm,
				 max(Z.it1cd)     as it1cd,
				 max(Z.it2cd)     as it2cd,
				 max(Z.mssec)     as mssec,
				 max(Z.mssecnm)   as mssecnm,   -- ★ 추가
				 SUM(Z.dramt)     AS dramt,
				 SUM(Z.cramt)     AS cramt,
				 max(Z.it1nm)     as it1nm,
				 max(Z.it2nm)     as it2nm,
				 max(Z.it2nm_t)   as it2nm_t,
				 max(Z.tiosec)    as tiosec,
				 max(Z.subject)   as subject,
				 max(Z.businm)    as businm,
				 max(Z.upnm)      as upnm,
				 max(Z.drcr)      as drcr,
				 max(Z.summy)     as summy,
				 max(Z.setnum)    as setnum,
				 max(Z.accnum)    as accnum,
				 max(Z.banknm)    as banknm,
				 max(Z.cardnum)   as cardnum,
				 max(Z.cardnm)    as cardnm,
				 max(Z.cltnm)     as cltnm,
				 max(Z.rowseq)    as rowseq,
				 max(Z.regdate)   as regdate,
				 (SELECT top 1 max(datechk) from tb_xenv) AS jichuldate,
				 CAST('' AS CHAR(1)) AS spnumchk
	FROM
	(
		-- ── 1번 UNION : acccd 앞자리 7, 5 (비용 계정) ──────────────────────────
		SELECT   max(a.spdate)   as spdate,
						 max(a.spnum)    as spnum,
						 max(a.remark)   as remark,
						 max(b.acccd)    as acccd,
						 max(b.accnm)    as accnm,
						 max(b.it1cd)    as it1cd,
						 max(b.it2cd)    as it2cd,
						 max(b.mssec)    AS mssec,
						 (SELECT mssecnm FROM tb_x0005 WHERE mssec = max(b.mssec)) AS mssecnm,   -- ★ 추가
						 sum(b.dramt)    as dramt,
						 sum(b.cramt)    as cramt,
						 (select it1nm from VW_X0003 where it1cd = max(B.it1cd) and tiosec = max(A.tiosec)) as it1nm,
						 (select it2nm from TB_X0004 where it2cd = max(B.it2cd) and tiosec = max(A.tiosec)) as it2nm,
						 '' as it2nm_t,
						 Max(a.tiosec)   as tiosec,
						 Max(a.subject)  as subject,
						 (select businm from tb_x0002 where bsdate = Max(A.bsdate) and bseccd = Max(A.bseccd) and busicd = Max(A.busicd)) as businm,
						 CASE WHEN max(b.acccd) BETWEEN '1000' AND '1999' THEN '자산'
									WHEN max(b.acccd) BETWEEN '5600' AND '7999' THEN '비용'
									WHEN max(b.acccd) BETWEEN '8300' AND '8499' THEN '비용'
									WHEN max(b.acccd) BETWEEN '8650' AND '8699' THEN '비용'
									WHEN max(b.acccd) BETWEEN '8750' AND '8799' THEN '비용'
									WHEN max(b.acccd) BETWEEN '2000' AND '2999' THEN '부채'
									WHEN max(b.acccd) BETWEEN '3000' AND '3999' THEN '자본'
									WHEN max(b.acccd) BETWEEN '5000' AND '5599' THEN '수입'
									WHEN max(b.acccd) BETWEEN '8000' AND '8299' THEN '수입'
									WHEN max(b.acccd) BETWEEN '8500' AND '8649' THEN '수입'
									ELSE '기타' END AS upnm,
						 max(b.drcr)     as drcr,
						 max(b.summy)    as summy,
						 max(a.setnum)   as setnum,
						 max(a.regdate)  as regdate,
						 (select accnum from TB_AA040 where custcd = a.custcd and spjangcd = a.spjangcd and bank + bankcd = max(B.bankcd)) as accnum,
						 (select banknm from TB_AA040 where custcd = a.custcd and spjangcd = a.spjangcd and bank + bankcd = max(B.bankcd)) as banknm,
						 (select cardnum from TB_IZ010 where custcd = a.custcd and spjangcd = a.spjangcd and cardnum = max(B.cardnum)) as cardnum,
						 (select cardnm  from TB_IZ010 where custcd = a.custcd and spjangcd = a.spjangcd and cardnum = max(B.cardnum)) as cardnm,
						 (select cltnm from tb_xclient where cltcd = max(B.cltcd)) as cltnm,
						 max(b.rowseq)   as rowseq
		FROM TB_AA009 a,
				 TB_AA010 b,
				 TB_AC001 c
		WHERE a.custcd   = b.custcd
			AND a.spjangcd = b.spjangcd
			AND a.spdate   = b.spdate
			AND a.spnum    = b.spnum
			AND b.custcd   = c.custcd
			AND b.acccd    = c.acccd
			AND a.custcd   = :as_custcd
			AND a.spjangcd = :as_spjangcd
			AND a.spdate + a.spnum IN (:as_keys)
			AND Left(b.acccd, 1) IN ('7', '5')
		GROUP BY a.custcd, a.spjangcd, a.spdate, a.spnum
		UNION ALL
		-- ── 2번 UNION : acccd 앞자리 7, 5 외 (자산·부채 등 계정) ───────────────
		SELECT   max(a.spdate)   as spdate,
						 max(a.spnum)    as spnum,
						 max(a.remark)   as remark,
						 max(b.acccd)    as acccd,
						 ''              as accnm,
						 max(b.it1cd)    as it1cd,
						 max(b.it2cd)    as it2cd,
						 max(b.mssec)    as mssec,
						 (SELECT mssecnm FROM tb_x0005 WHERE mssec = max(b.mssec)) AS mssecnm,   -- ★ 추가
						 sum(b.dramt)    as dramt,
						 sum(b.cramt)    as cramt,
						 ''              as it1nm,
						 ''              as it2nm,
						 (select it2nm from TB_X0004 where it2cd = max(B.it2cd) and tiosec = max(A.tiosec)) as it2nm_t,
						 Max(a.tiosec)   as tiosec,
						 Max(a.subject)  as subject,
						 (select businm from tb_x0002 where bsdate = Max(A.bsdate) and bseccd = Max(A.bseccd) and busicd = Max(A.busicd)) as businm,
						 CASE WHEN max(b.acccd) BETWEEN '1000' AND '1999' THEN '자산'
									WHEN max(b.acccd) BETWEEN '5600' AND '7999' THEN '비용'
									WHEN max(b.acccd) BETWEEN '8300' AND '8499' THEN '비용'
									WHEN max(b.acccd) BETWEEN '8650' AND '8699' THEN '비용'
									WHEN max(b.acccd) BETWEEN '8750' AND '8799' THEN '비용'
									WHEN max(b.acccd) BETWEEN '2000' AND '2999' THEN '부채'
									WHEN max(b.acccd) BETWEEN '3000' AND '3999' THEN '자본'
									WHEN max(b.acccd) BETWEEN '5000' AND '5599' THEN '수입'
									WHEN max(b.acccd) BETWEEN '8000' AND '8299' THEN '수입'
									WHEN max(b.acccd) BETWEEN '8500' AND '8649' THEN '수입'
									ELSE '기타' END AS upnm,
						 max(b.drcr)     as drcr,
						 max(b.summy)    as summy,
						 max(a.setnum)   as setnum,
						 max(a.regdate)  as regdate,
						 (select accnum from TB_AA040 where custcd = a.custcd and spjangcd = a.spjangcd and bank + bankcd = max(B.bankcd)) as accnum,
						 (select banknm from TB_AA040 where custcd = a.custcd and spjangcd = a.spjangcd and bank + bankcd = max(B.bankcd)) as banknm,
						 (select cardnum from TB_IZ010 where custcd = a.custcd and spjangcd = a.spjangcd and cardnum = max(B.cardnum)) as cardnum,
						 (select cardnm  from TB_IZ010 where custcd = a.custcd and spjangcd = a.spjangcd and cardnum = max(B.cardnum)) as cardnm,
						 (select cltnm from tb_xclient where cltcd = max(B.cltcd)) as cltnm,
						 max(b.rowseq)   as rowseq
		FROM TB_AA009 a,
				 TB_AA010 b,
				 TB_AC001 c
		WHERE a.custcd   = b.custcd
			AND a.spjangcd = b.spjangcd
			AND a.spdate   = b.spdate
			AND a.spnum    = b.spnum
			AND b.custcd   = c.custcd
			AND b.acccd    = c.acccd
			AND a.custcd   = :as_custcd
			AND a.spjangcd = :as_spjangcd
			AND a.spdate + a.spnum IN (:as_keys)
			AND Left(b.acccd, 1) NOT IN ('7', '5')
		GROUP BY a.custcd, a.spjangcd, a.spdate, a.spnum
	) Z
	GROUP BY Z.spdate, Z.spnum
		""";
		return sqlRunner.getRows(sql, param);
	}

	public List<Map<String, Object>> printGyeolui2(String keys) {
		String spjangcd = TenantContext.get();
		Map<String, String> bizInfo = getBizInfoBySpjangcd(spjangcd);
		String custcd = bizInfo.get("custcd");

		List<String> keyList = Arrays.asList(keys.split(","));
		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("as_custcd",   custcd);
		param.addValue("as_spjangcd", spjangcd);
		param.addValue("as_keys",     keyList);
		//결의양식2(하단)
		String sql = """
			  SELECT   Z.spdate,
			         Z.spnum,
			         Z.remark,
			         Z.acccd,
			         Z.accnm,
			         Z.it1cd,
			         Z.it2cd,
			         Z.mssec,
			         Z.mssecnm,
			         Z.dramt,
			         Z.cramt,
			         Z.it1nm,
			         Z.it2nm,
			         Z.it2nm_t,
			         Z.tiosec,
			         Z.subject,
			         Z.businm,
			         Z.upnm,
			         Z.drcr,
			         Z.summy,
			         Z.setnum,
			         Z.accnum,
			         Z.banknm,
			         Z.cardnum,
			         Z.cardnm,
			         Z.tiosec,
			         CAST('' AS CHAR(1)) AS jichuldate,
			         CAST('' AS CHAR(1)) AS spnumchk
			FROM
			(
			      SELECT   max(a.spdate) as spdate,
			               max(a.spnum) as spnum,
			               max(a.remark) as remark,
			               max(b.acccd) as acccd,
			               max(b.accnm) as accnm,
			               max(b.it1cd) as it1cd,
			               max(b.it2cd) as it2cd,
			               max(b.mssec) AS mssec,
			               (SELECT mssecnm
			                  FROM tb_x0005
			                 WHERE mssec = max(b.mssec)
			               ) AS mssecnm,
			               sum(b.dramt) as dramt,
			               sum(b.cramt) as cramt,
			               (select it1nm
			                  from VW_X0003 
			                 where it1cd = max(B.it1cd)
			                   and tiosec = max(A.tiosec)
			               ) as it1nm,
			               (select it2nm
			                  from TB_X0004
			                 where it2cd = max(B.it2cd)
			                   and tiosec = max(A.tiosec)
			               ) as it2nm,
			               '' as it2nm_t,
			               Max(a.tiosec) as tiosec,
			               Max(a.subject) as subject,
			               (select businm
			                  From tb_x0002
			                 where bsdate = Max(A.bsdate)
			                   and bseccd = Max(A.bseccd)
			                   and busicd = Max(A.busicd)
			               ) as businm,
			               CASE
			                    WHEN max(b.acccd) BETWEEN '1000' AND '1999' THEN '자산'
			                    WHEN max(b.acccd) BETWEEN '5600' AND '7999' THEN '비용'
			                    WHEN max(b.acccd) BETWEEN '8300' AND '8499' THEN '비용'
			                    WHEN max(b.acccd) BETWEEN '8650' AND '8699' THEN '비용'
			                    WHEN max(b.acccd) BETWEEN '8750' AND '8799' THEN '비용'
			                    WHEN max(b.acccd) BETWEEN '2000' AND '2999' THEN '부채'
			                    WHEN max(b.acccd) BETWEEN '3000' AND '3999' THEN '자본'
			                    WHEN max(b.acccd) BETWEEN '5000' AND '5599' THEN '수입'
			                    WHEN max(b.acccd) BETWEEN '8000' AND '8299' THEN '수입'
			                    WHEN max(b.acccd) BETWEEN '8500' AND '8649' THEN '수입'
			                    ELSE '기타'
			               END AS upnm,
			               max(b.drcr) as drcr,
			               max(b.summy) as summy,
			               max(a.setnum) as setnum,
			               (select accnum
			                  from TB_AA040
			                 where custcd = a.custcd
			                   and spjangcd = a.spjangcd
			                   and bank + bankcd = max(B.bankcd)
			               ) as accnum,
			               (select banknm
			                  from TB_AA040
			                 where custcd = a.custcd
			                   and spjangcd = a.spjangcd
			                   and bank + bankcd = max(B.bankcd)
			               ) as banknm,
			               (select cardnum
			                  from TB_IZ010
			                 where custcd = a.custcd
			                   and spjangcd = a.spjangcd
			                   and cardnum = max(B.cardnum)
			               ) as cardnum,
			               (select cardnm
			                  from TB_IZ010
			                 where custcd = a.custcd
			                   and spjangcd = a.spjangcd
			                   and cardnum = max(B.cardnum)
			               ) as cardnm
			      FROM TB_AA009 a,
			           TB_AA010 b,
			           TB_AC001 c
			      WHERE a.custcd   = b.custcd
			        AND a.spjangcd = b.spjangcd
			        AND a.spdate   = b.spdate
			        AND a.spnum    = b.spnum
			        AND b.custcd   = c.custcd
			        AND b.acccd    = c.acccd
			        AND a.custcd   = :as_custcd
			        AND a.spjangcd = :as_spjangcd
			        AND a.spdate + a.spnum IN (:as_keys)
			        AND Left(b.acccd, 1) IN ('7', '5')
			      GROUP BY a.custcd, a.spjangcd, a.spdate, a.spnum
			
			      UNION ALL
			
			      SELECT   max(a.spdate) as spdate,
			               max(a.spnum) as spnum,
			               max(a.remark) as remark,
			               max(b.acccd) as acccd,
			               max(b.accnm) as accnm,
			               max(b.it1cd) as it1cd,
			               max(b.it2cd) as it2cd,
			               max(b.mssec) as mssec,
			               (SELECT mssecnm
			                  FROM tb_x0005
			                 WHERE mssec = max(b.mssec)
			               ) AS mssecnm,
			               sum(b.dramt) as dramt,
			               sum(b.cramt) as cramt,
			               (select it1nm
			                  from VW_X0003
			                 where it1cd = max(B.it1cd)
			                   and tiosec = max(A.tiosec)
			               ) as it1nm,
			               '' as it2nm,
			               (select it2nm
			                  from TB_X0004
			                 where it2cd = max(B.it2cd)
			                   and tiosec = max(A.tiosec)
			               ) as it2nm_t,
			               Max(a.tiosec) as tiosec,
			               Max(a.subject) as subject,
			               (select businm
			                  From tb_x0002
			                 where bsdate = Max(A.bsdate)
			                   and bseccd = Max(A.bseccd)
			                   and busicd = Max(A.busicd)
			               ) as businm,
			               CASE
			                    WHEN max(b.acccd) BETWEEN '1000' AND '1999' THEN '자산'
			                    WHEN max(b.acccd) BETWEEN '5600' AND '7999' THEN '비용'
			                    WHEN max(b.acccd) BETWEEN '8300' AND '8499' THEN '비용'
			                    WHEN max(b.acccd) BETWEEN '8650' AND '8699' THEN '비용'
			                    WHEN max(b.acccd) BETWEEN '8750' AND '8799' THEN '비용'
			                    WHEN max(b.acccd) BETWEEN '2000' AND '2999' THEN '부채'
			                    WHEN max(b.acccd) BETWEEN '3000' AND '3999' THEN '자본'
			                    WHEN max(b.acccd) BETWEEN '5000' AND '5599' THEN '수입'
			                    WHEN max(b.acccd) BETWEEN '8000' AND '8299' THEN '수입'
			                    WHEN max(b.acccd) BETWEEN '8500' AND '8649' THEN '수입'
			                    ELSE '기타'
			               END AS upnm,
			               max(b.drcr) as drcr,
			               max(b.summy) as summy,
			               max(a.setnum) as setnum,
			               (select accnum
			                  from TB_AA040
			                 where custcd = a.custcd
			                   and spjangcd = a.spjangcd
			                   and bank + bankcd = max(B.bankcd)
			               ) as accnum,
			               (select banknm
			                  from TB_AA040
			                 where custcd = a.custcd
			                   and spjangcd = a.spjangcd
			                   and bank + bankcd = max(B.bankcd)
			               ) as banknm,
			               (select cardnum
			                  from TB_IZ010
			                 where custcd = a.custcd
			                   and spjangcd = a.spjangcd
			                   and cardnum = max(B.cardnum)
			               ) as cardnum,
			               (select cardnm
			                  from TB_IZ010
			                 where custcd = a.custcd
			                   and spjangcd = a.spjangcd
			                   and cardnum = max(B.cardnum)
			               ) as cardnm
			      FROM TB_AA009 a,
			           TB_AA010 b,
			           TB_AC001 c
			      WHERE a.custcd   = b.custcd
			        AND a.spjangcd = b.spjangcd
			        AND a.spdate   = b.spdate
			        AND a.spnum    = b.spnum
			        AND b.custcd   = c.custcd
			        AND b.acccd    = c.acccd
			        AND a.custcd   = :as_custcd
			        AND a.spjangcd = :as_spjangcd
			        AND a.spdate + a.spnum IN (:as_keys)
			        AND Left(b.acccd, 1) NOT IN ('7', '5')
			      GROUP BY a.custcd, a.spjangcd, a.spdate, a.spnum
			) Z
			""";
		return sqlRunner.getRows(sql, param);
	}

	public List<Map<String, Object>> printGyeolui3(String keys) {
		String spjangcd = TenantContext.get();
		Map<String, String> bizInfo = getBizInfoBySpjangcd(spjangcd);
		String custcd = bizInfo.get("custcd");

		List<String> keyList = Arrays.asList(keys.split(","));
		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("as_custcd",   custcd);
		param.addValue("as_spjangcd", spjangcd);
		param.addValue("as_keys",     keyList);
		//결의양식3(세입세출결의서)
		String sql = """
		SELECT  
				Z.spdate,
				Z.spnum,
				Z.remark,
				Z.acccd,
				Z.accnm,
				Z.it1cd,
				Z.it2cd,
				Z.mssec,
				Z.mssecnm,
				Z.dramt,
				Z.cramt,
				Z.it1nm ,
				Z.it2nm ,
				Z.it2nm_t,
				Z.tiosec,
				Z.subject,
				Z.businm,
				Z.upnm,
				Z.drcr,
				Z.summy,
				Z.setnum,
				Z.cltnm,
				Z.accnum,
				Z.banknm,
				Z.cardnum,
				Z.cardnm,
				Z.tiosec,
				Z.rowseq,
				Z.regdate,
				Z.spjangnm,
				CAST('' AS CHAR(1) ) AS jichuldate,
				CAST('' AS CHAR(1) ) AS spnumchk
			FROM
			(
				SELECT    a.spdate as spdate ,
						 a.spnum as spnum,
						 a.remark as remark,
						 b.acccd as acccd,
						 b.accnm as accnm,
						 b.it1cd as it1cd,
						 b.it2cd as it2cd,
						 b.mssec  AS mssec,
						 b.dramt as dramt,
						 b.cramt as cramt,
						 (select it1nm from VW_X0003  where it1cd= B.it1cd  and tiosec= b.tiosec ) as it1nm      ,
						 (select it2nm from TB_X0004 where it2cd= B.it2cd  and tiosec=b.tiosec ) as it2nm      ,
						'' as it2nm_t,
					a.tiosec as tiosec,
					a.subject as subject,
				 (select businm From tb_x0002 where bsdate=A.bsdate and bseccd=A.bseccd and busicd=A.busicd) as businm,
					CASE WHEN  b.acccd  BETWEEN '1000' AND '1999' THEN '자산'
							WHEN  b.acccd  BETWEEN '5600' AND '7999' THEN '비용'
							WHEN  b.acccd  BETWEEN '8300' AND '8499' THEN '비용'
							WHEN  b.acccd  BETWEEN '8650' AND '8699' THEN '비용'
							WHEN  b.acccd  BETWEEN '8750' AND '8799' THEN '비용'              
							WHEN  b.acccd  BETWEEN '2000' AND '2999' THEN '부채'
							WHEN  b.acccd  BETWEEN '3000' AND '3999' THEN '자본'
							WHEN  b.acccd  BETWEEN '5000' AND '5599' THEN '수입' 
							WHEN  b.acccd  BETWEEN '8000' AND '8299' THEN '수입' 
							WHEN  b.acccd  BETWEEN '8500' AND '8649' THEN '수입' 
							ELSE '기타' END AS upnm,
					b.drcr as drcr,
					b.summy as summy,
					a.setnum as setnum,
					(select cltnm from tb_xclient where cltcd = B.cltcd) as cltnm,
					(select accnum from TB_AA040 where custcd=a.custcd and spjangcd=a.spjangcd and   bank + bankcd =  B.bankcd ) as accnum,
					(select banknm from TB_AA040 where custcd=a.custcd and spjangcd=a.spjangcd and   bank + bankcd = B.bankcd ) as banknm,
					(select cardnum from TB_IZ010 where custcd=a.custcd and spjangcd=a.spjangcd and   cardnum = B.cardnum)  as cardnum,
					(select cardnm from TB_IZ010 where custcd=a.custcd and spjangcd=a.spjangcd and   cardnum = B.cardnum)  as cardnm,
					(SELECT mssecnm FROM tb_x0005  WHERE mssec = b.mssec) AS mssecnm,
					b.rowseq,
					a.regdate,
					b.spjangnm
			 FROM TB_AA009  a,
					TB_AA010  b,
					TB_AC001  c
			WHERE a.custcd   = b.custcd
				AND a.spjangcd = b.spjangcd
				AND a.spdate   = b.spdate
				AND a.spnum    = b.spnum
				AND b.custcd   = c.custcd
				AND b.acccd    = c.acccd
				AND a.custcd   = :as_custcd
				AND a.spjangcd = :as_spjangcd
				AND a.spdate + a.spnum IN (:as_keys)
				AND ( Left(b.acccd,1) IN ( '7' , '5', '3') or b.acccd = '1360')  
				-- 부가세 대급금 포함
				--, a.remark, b.acccd, b.accnm, b.it1cd, b.it2cd, b.mssec , A.tiosec, b.drcr, b.summy,    a.setnum,  b.BANKCD, b.CARDNUM
				--ORDER BY upnm, b.acccd, b.it1cd, b.it2cd
			/*
			UNION all
				SELECT   max( a.spdate) as spdate ,
						max( a.spnum) as spnum,
						max( a.remark) as remark,
						max( b.acccd) as acccd,
						max( b.accnm) as accnm,
						max( b.it1cd) as it1cd,
						max( b.it2cd) as it2cd,
						max( b.mssec) as messec,
						sum(b.dramt) as dramt,
						sum(b.cramt) as cramt,
							(select it1nm from VW_X0003 where it1cd=max( B.it1cd ) and tiosec=max( A.tiosec) ) as it1nm      ,
								'' as it2nm      ,
							(select it2nm from TB_X0004 where it2cd=max( B.it2cd ) and tiosec=max( A.tiosec) ) as it2nm_t,
						Max(a.tiosec) as tiosec,
						Max(a.subject) as subject,
					 (select businm From tb_x0002 where bsdate=Max(A.bsdate) and bseccd=Max(A.bseccd) and busicd=Max(A.busicd)) as businm,
						CASE WHEN max( b.acccd ) BETWEEN '1000' AND '1999' THEN '자산'
								WHEN max( b.acccd ) BETWEEN '5600' AND '7999' THEN '비용'
										WHEN max( b.acccd ) BETWEEN '8300' AND '8499' THEN '비용'
										WHEN max( b.acccd ) BETWEEN '8650' AND '8699' THEN '비용'
										WHEN max( b.acccd ) BETWEEN '8750' AND '8799' THEN '비용'              
										WHEN max( b.acccd ) BETWEEN '2000' AND '2999' THEN '부채'
										WHEN max( b.acccd ) BETWEEN '3000' AND '3999' THEN '자본'
										WHEN max( b.acccd ) BETWEEN '5000' AND '5599' THEN '수입' 
										WHEN max( b.acccd ) BETWEEN '8000' AND '8299' THEN '수입' 
										WHEN max( b.acccd ) BETWEEN '8500' AND '8649' THEN '수입' 
										ELSE '기타' END AS upnm,
						max( b.drcr) as drcr,
							max( b.summy) as summy,
										 max( a.setnum) as setnum,
							 (select accnum from TB_AA040 where bank + bankcd = max( B.bankcd )) as accnum,
						(select banknm from TB_AA040 where bank + bankcd = max(B.bankcd )) as banknm,
						(select cardnum from TB_IZ010 where cardnum = max(B.cardnum) ) as cardnum,
						(select cardnm from TB_IZ010 where cardnum = max(B.cardnum) ) as cardnm
				 FROM TB_AA009  a,
						TB_AA010  b,
						TB_AC001  c
				WHERE a.custcd   = b.custcd
					AND a.spjangcd = b.spjangcd
					AND a.spdate   = b.spdate
					AND a.spnum    = b.spnum
					AND b.custcd   = c.custcd
					AND b.acccd    = c.acccd
					AND a.custcd   = :as_custcd
					AND a.spjangcd = :as_spjangcd
					AND a.spdate + a.spnum IN (:as_keys)
					AND Left(b.acccd,1) NOT IN ( '7' , '5')
			GROUP BY a.spdate, a.spnum --, a.remark, b.acccd, b.accnm, b.it1cd, b.it2cd, b.mssec , A.tiosec, b.drcr, b.summy,    a.setnum, b.BANKCD, b.CARDNUM ORDER BY upnm, b.acccd, b.it1cd, b.it2cd
			*/
			 ) Z
			order by z.rowseq			
			""";
		return sqlRunner.getRows(sql, param);
	}

	public List<Map<String, Object>> printGyeolui6(String keys) {
		String spjangcd = TenantContext.get();
		Map<String, String> bizInfo = getBizInfoBySpjangcd(spjangcd);
		String custcd = bizInfo.get("custcd");

		List<String> keyList = Arrays.asList(keys.split(","));
		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("as_custcd",   custcd);
		param.addValue("as_spjangcd", spjangcd);
		param.addValue("as_keys",     keyList);

		//결의양식3(대체)
		String sql = """
		 SELECT  
			 	Z.spdate,
			 	Z.spnum,
			 	Z.remark,
			 	Z.acccd,
			 	Z.accnm,
			 	Z.it1cd,
			 	Z.it2cd,
			 	Z.mssec,
			 	Z.dramt,
			 	Z.cramt,
			 	Z.it1nm,
			 	Z.it2nm,
			 	Z.it2nm_t,
			 	Z.tiosec,
			 	Z.subject,
			 	Z.businm,
			 	Z.upnm,
			 	Z.drcr,
			 	Z.summy,
			 	Z.setnum,
			 	Z.cltnm,
			 	Z.accnum,
			 	Z.banknm,
			 	Z.cardnum,
			 	Z.cardnm,
			 	Z.tiosec,
			 	Z.rowseq,
			 	Z.regdate,
			 	Z.spjangnm,
			 	Z.mssecnm,
			 CAST('' AS CHAR(1) ) AS jichuldate,
			 CAST('' AS CHAR(1) ) AS spnumchk
			 FROM
			 (
			   SELECT   
			   	a.spdate as spdate ,
			   	a.spnum as spnum,
			   	a.remark as remark,
			   	b.acccd as acccd,
			   	b.accnm as accnm,
			   	b.it1cd as it1cd,
			   	b.it2cd as it2cd,
			   	b.mssec  AS mssec,
			   	b.dramt as dramt,
			   	b.cramt as cramt,
			   	(select it1nm from VW_X0003  where it1cd= B.it1cd  and tiosec= b.tiosec ) as it1nm,
			   	(select it2nm from TB_X0004 where it2cd= B.it2cd  and tiosec=b.tiosec ) as it2nm,
			   	'' as it2nm_t,
			 	a.tiosec as tiosec,
			 	a.subject as subject,
			  (select businm From tb_x0002 where bsdate=A.bsdate and bseccd=A.bseccd and busicd=A.busicd) as businm,
			 	CASE WHEN  b.acccd  BETWEEN '1000' AND '1999' THEN '자산'
			 		  WHEN  b.acccd  BETWEEN '5600' AND '7999' THEN '비용'
			 		  WHEN  b.acccd  BETWEEN '8300' AND '8499' THEN '비용'
			 		  WHEN  b.acccd  BETWEEN '8650' AND '8699' THEN '비용'
			 		  WHEN  b.acccd  BETWEEN '8750' AND '8799' THEN '비용'              
			 		  WHEN  b.acccd  BETWEEN '2000' AND '2999' THEN '부채'
			 		  WHEN  b.acccd  BETWEEN '3000' AND '3999' THEN '자본'
			 		  WHEN  b.acccd  BETWEEN '5000' AND '5599' THEN '수입' 
			 		  WHEN  b.acccd  BETWEEN '8000' AND '8299' THEN '수입' 
			 		  WHEN  b.acccd  BETWEEN '8500' AND '8649' THEN '수입' 
			  	ELSE '기타' END AS upnm,
			 		 b.drcr as drcr,
			 		 b.summy as summy,
			 		 a.setnum as setnum,
			 		(select cltnm from tb_xclient where cltcd = B.cltcd) as cltnm,
			 		(select accnum from TB_AA040 where custcd=a.custcd and spjangcd=a.spjangcd and   bank + bankcd =  B.bankcd ) as accnum,
			 		(select banknm from TB_AA040 where custcd=a.custcd and spjangcd=a.spjangcd and   bank + bankcd = B.bankcd ) as banknm,
			 		(select cardnum from TB_IZ010 where custcd=a.custcd and spjangcd=a.spjangcd and   cardnum = B.cardnum)  as cardnum,
			 		(select cardnm from TB_IZ010 where custcd=a.custcd and spjangcd=a.spjangcd and   cardnum = B.cardnum)  as cardnm,
			 		(SELECT mssecnm FROM tb_x0005  WHERE mssec = b.mssec) AS mssecnm,
			 		b.rowseq,
			 		a.regdate,
			 		b.spjangnm
			 FROM TB_AA009 a,
			  	TB_AA010 b,
			  	TB_AC001 c
			 WHERE a.custcd   = b.custcd
			   AND a.spjangcd = b.spjangcd
			   AND a.spdate   = b.spdate
			   AND a.spnum    = b.spnum
			   AND b.custcd   = c.custcd
			   AND b.acccd    = c.acccd
			   AND a.custcd   = :as_custcd
			   AND a.spjangcd = :as_spjangcd
			   AND a.spdate + a.spnum IN (:as_keys)
			   AND ( Left(b.acccd,1) NOT IN ( '7' , '5') )  
			   -- 부가세 대급금 포함
			 --, a.remark, b.acccd, b.accnm, b.it1cd, b.it2cd, b.mssec , A.tiosec, b.drcr, b.summy,    a.setnum,  b.BANKCD, b.CARDNUM
			 --ORDER BY upnm, b.acccd, b.it1cd, b.it2cd
			 /*
			 UNION all
			   SELECT   max( a.spdate) as spdate ,
			 			max( a.spnum) as spnum,
			 			max( a.remark) as remark,
			 			max( b.acccd) as acccd,
			 			max( b.accnm) as accnm,
			 			max( b.it1cd) as it1cd,
			 			max( b.it2cd) as it2cd,
			 			max( b.mssec) as messec,
			 			sum(b.dramt) as dramt,
			 			sum(b.cramt) as cramt,
			 		    (select it1nm from VW_X0003 where it1cd=max( B.it1cd ) and tiosec=max( A.tiosec) ) as it1nm      ,
			 		      '' as it2nm      ,
			 		    (select it2nm from TB_X0004 where it2cd=max( B.it2cd ) and tiosec=max( A.tiosec) ) as it2nm_t,
			 			Max(a.tiosec) as tiosec,
			 			Max(a.subject) as subject,
			 		 (select businm From tb_x0002 where bsdate=Max(A.bsdate) and bseccd=Max(A.bseccd) and busicd=Max(A.busicd)) as businm,
			 			CASE WHEN max( b.acccd ) BETWEEN '1000' AND '1999' THEN '자산'
			 				  WHEN max( b.acccd ) BETWEEN '5600' AND '7999' THEN '비용'
			               WHEN max( b.acccd ) BETWEEN '8300' AND '8499' THEN '비용'
			               WHEN max( b.acccd ) BETWEEN '8650' AND '8699' THEN '비용'
			               WHEN max( b.acccd ) BETWEEN '8750' AND '8799' THEN '비용'              
			               WHEN max( b.acccd ) BETWEEN '2000' AND '2999' THEN '부채'
			               WHEN max( b.acccd ) BETWEEN '3000' AND '3999' THEN '자본'
			               WHEN max( b.acccd ) BETWEEN '5000' AND '5599' THEN '수입' 
			               WHEN max( b.acccd ) BETWEEN '8000' AND '8299' THEN '수입' 
			               WHEN max( b.acccd ) BETWEEN '8500' AND '8649' THEN '수입' 
			               ELSE '기타' END AS upnm,
			 			max( b.drcr) as drcr,
			 		    max( b.summy) as summy,
			                max( a.setnum) as setnum,
			 		     (select accnum from TB_AA040 where bank + bankcd = max( B.bankcd )) as accnum,
			 			(select banknm from TB_AA040 where bank + bankcd = max(B.bankcd )) as banknm,
			 			(select cardnum from TB_IZ010 where cardnum = max(B.cardnum) ) as cardnum,
			 			(select cardnm from TB_IZ010 where cardnum = max(B.cardnum) ) as cardnm
			 	 FROM TB_AA009  a,
			 			TB_AA010  b,
			 			TB_AC001  c
			 	WHERE a.custcd   = b.custcd
			 	  AND a.spjangcd = b.spjangcd
			 	  AND a.spdate   = b.spdate
			 	  AND a.spnum    = b.spnum
			 	  AND b.custcd   = c.custcd
			 	  AND b.acccd    = c.acccd
			 	  AND a.custcd   = :as_custcd
			 	  AND a.spjangcd = :as_spjangcd
			 	  AND a.spdate + a.spnum IN (:as_keys)
			 	  AND Left(b.acccd,1) NOT IN ( '7' , '5')
			 GROUP BY a.spdate, a.spnum --, a.remark, b.acccd, b.accnm, b.it1cd, b.it2cd, b.mssec , A.tiosec, b.drcr, b.summy,    a.setnum, b.BANKCD, b.CARDNUM ORDER BY upnm, b.acccd, b.it1cd, b.it2cd
			 */
			  ) Z
			 order by z.rowseq
		""";
		return sqlRunner.getRows(sql, param);
	}
}
