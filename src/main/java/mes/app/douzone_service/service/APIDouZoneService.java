package mes.app.douzone_service.service;

import lombok.extern.slf4j.Slf4j;
import mes.app.douzone_service.DouzoneClient;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;
import java.util.*;

@Slf4j
@Service
public class APIDouZoneService {

	@Autowired
	SqlRunner sqlRunner;

	@Autowired
	DouzoneClient douzoneClient;

	public List<Map<String, Object>> getSalesRead(String start,
																								String end,
																								String company,
																								String sale_type,
																								String spjangcd) {

		MapSqlParameterSource param = new MapSqlParameterSource();
		String frDt = start != null ? start.replaceAll("-", "") : "";
		String toDt = end   != null ? end.replaceAll("-", "")   : "";
		param.addValue("as_cltcd", company);
		param.addValue("as_stdate", frDt);
		param.addValue("as_enddate",toDt);
		param.addValue("spjangcd", spjangcd);
		param.addValue("as_custcd","samjung");
		param.addValue("as_spjangcd", spjangcd);

		String sql = """
			SELECT '' as DEL_CHK,
			   TB_DA023.spjangcd as WORKAREA_CD,
			   FORMAT(CONVERT(date, TB_DA023.misdate, 112), 'yy.MM.dd')
			    + ' - '
			    + RIGHT('0000' + CAST(
			            ROW_NUMBER() OVER (
			                PARTITION BY TB_DA023.misdate
			                ORDER BY TB_DA023.misdate, TB_DA023.misnum
			            ) AS varchar(4)
			      ), 4) AS saleDate,
			   TB_DA023.misdate,
			   TB_DA023.misdate + TB_DA023.misnum as relation_no,
				 TB_DA023.misnum as RELATION_SEQ,
				 CASE WHEN TB_DA023.gubun = '11' THEN 'MT' ELSE 'OD' END as relation_division,
			   TB_XCLIENT.emcltcd as CUSTOMER_CD,
			   TB_XCLIENT.cltcd as custCode,
			    TB_XCLIENT.cltnm as custName,
				(select emactcd from tb_e601 where custcd=TB_DA023.custcd and spjangcd=TB_DA023.spjangcd and actcd=TB_DA023.actcd) as FIELD_CD,
				 TB_DA023.actcd as actcd,
				 TB_E601.actnm as FIELD_NAME,
			    TB_DA023.misdate as RELATION_DATE,
			    TB_XCLIENT.saupnum as REGISTRATION_NO,
			    TB_DA023.amt as supplyAmt,
			    TB_DA023.addamt as vatAmt,
			    TB_DA023.misamt as totalAmt,
			    ISNULL(TB_DA023.misamt,0) - ISNULL(TB_DA023.chaamt,0) - ISNULL(TB_DA023.hamt,0) - ISNULL(TB_DA023.bamt,0) -
			    ISNULL(TB_DA023.jamt,0) - ISNULL(TB_DA023.jmar,0) - ISNULL(TB_DA023.eamt,0) - ISNULL(TB_DA023.samt,0) -
			    ISNULL(TB_DA023.damt,0) - ISNULL(TB_DA023.gamt,0) - ISNULL(TB_DA023.dcamt,0) - ISNULL(TB_DA023.camt,0) as UNCOLLECT_AMOUNT,
			   TB_DA023.remark as SUBJECT,
				 TB_DA023.DATASEND_DIVISION,
				 TB_XCLIENT.prenum as COPORATE_NO,
			    TB_XCLIENT.prenm as REPRESENTATIVE_NAME,
			    TB_XCLIENT.biztypenm as BUSINESS_TYPE,
			    TB_XCLIENT.bizitemnm as BUSINESS_CONDITIONS,
			   TB_XCLIENT.cltadres as RELATION_ADDRESS,
				 TB_XCLIENT.taxtelnum as TEL_NO,
				TB_DA023.acccd as djacccd ,
				TB_DA023.divicd as divicd ,
				TB_E601.actcd    AS pjcd_OR, -- 현장코드(기존 더존 연결용)
			  TB_E601.emactcd  AS pjcd_PR, -- 현장코드(현재 더존연결)
				TB_E601.actnm    AS pjnm, -- 현장명
				 TB_DA023.gubun ,
				(select dzdivicd from tb_jc002 where custcd=:as_custcd and spjangcd=:as_spjangcd and divicd=TB_DA023.divicd) as dzdivicd,
				(select divinm from tb_jc002 where custcd=:as_custcd and spjangcd=:as_spjangcd and divicd=TB_DA023.divicd) as divinm,
				ac.accnm as acccd,
				d.artnm AS saleType,
				TB_DA023.IN_DT ,
			FORMAT(CONVERT(date, TB_DA023.IN_DT, 112), 'yyyy-MM-dd') AS issueDate,
			TB_DA023.IN_SQ,
			FORMAT(CONVERT(date, TB_DA023.IN_DT, 112), 'MM.dd')
			  + ' - '
			  + RIGHT('0000' + CAST(TB_DA023.IN_SQ AS varchar(4)), 4) AS doozenNo
			  FROM TB_DA023 WITH(NOLOCK)
			  LEFT OUTER JOIN TB_XCLIENT WITH(NOLOCK) ON (TB_DA023.custcd = TB_XCLIENT.custcd AND TB_DA023.cltcd = TB_XCLIENT.cltcd)
			  LEFT OUTER JOIN TB_E601 WITH(NOLOCK) ON (TB_DA023.custcd = TB_E601.custcd AND TB_DA023.spjangcd = TB_E601.spjangcd AND TB_DA023.actcd = TB_E601.actcd)
			  LEFT JOIN TB_AC001 ac ON TB_DA023.custcd = ac.custcd AND TB_DA023.acccd  = ac.acccd
			  LEFT JOIN TB_DA020 d ON TB_DA023.custcd = d.custcd AND TB_DA023.gubun  = d.artcd and TB_DA023.spjangcd = d.spjangcd
			 WHERE TB_DA023.custcd = :as_custcd
			   AND TB_DA023.spjangcd = :as_spjangcd
			     AND TB_DA023.misdate BETWEEN :as_stdate AND :as_enddate
        """;
// 🔹 company(거래처) 있을 때만 조건 추가
		if (company != null && !company.isBlank() && !"%".equals(company)) {
			sql += """
				AND TB_XCLIENT.cltnm like :as_cltcd
				""";
			param.addValue("as_cltcd", "%" + company + "%");
		}

		// ✅ DATASEND_DIVISION 필터
		if (sale_type != null && !sale_type.isBlank() && !"all".equalsIgnoreCase(sale_type)) {
			if ("Y".equalsIgnoreCase(sale_type)) {
				sql += """
                AND TB_DA023.DATASEND_DIVISION = :SendDiv
                """;
				param.addValue("SendDiv", "Y");
			} else if ("N".equalsIgnoreCase(sale_type)) {
				sql += """
                AND ISNULL(TB_DA023.DATASEND_DIVISION, 'N') <> 'Y'
                """;
			}
		}

		return sqlRunner.getRows(sql, param);
	}


	public List<Map<String, Object>> getPriceRead(String start, String end, String company, String price_type, String spjangcd) {
		MapSqlParameterSource param = new MapSqlParameterSource();

		// 날짜 파라미터
		String frDt = start != null ? start.replaceAll("-", "") : "";
		String toDt = end   != null ? end.replaceAll("-", "")   : "";
		param.addValue("as_stdate", frDt);
		param.addValue("as_enddate", toDt);
		param.addValue("SendDiv", price_type);
		param.addValue("as_cltcd", company);
		param.addValue("spjangcd", spjangcd);
		param.addValue("as_custcd", "samjung");

		String sql = """
			SELECT
			     -- 전체 순번
			ROW_NUMBER() OVER (ORDER BY TB_CA640.mijdate, TB_CA640.mijnum) AS rownum,
			-- 날짜별 순번
			ROW_NUMBER() OVER (
			    PARTITION BY TB_CA640.mijdate
			    ORDER BY TB_CA640.mijdate, TB_CA640.mijnum
			) AS dateSeq,	
			-- 일자-순번: MM.dd - 0001 형식
			FORMAT(CONVERT(date, TB_CA640.mijdate, 112), 'yy.MM.dd')
			    + ' - '
			    + RIGHT('0000' + CAST(
			            ROW_NUMBER() OVER (
			                PARTITION BY TB_CA640.mijdate
			                ORDER BY TB_CA640.mijdate, TB_CA640.mijnum
			            ) AS varchar(4)
			      ), 4) AS mijDate,
			     TB_XCLIENT.emcltcd   AS emcltcd,     -- 거래처코드
			     TB_XCLIENT.cltcd   AS cltcd,         -- 거래처코드
			     TB_CA640.actcd AS actcd,         		-- ACTS 코드
			     TB_XCLIENT.cltnm  AS cltnm,         	-- 거래처명
			     FORMAT(CONVERT(date, TB_CA640.mijdate, 112), 'yyyy-MM-dd') AS PriceDate, -- 비용일자
			     TB_CA640.gubun AS gubun,         -- 계정과목(구분)
			     ISNULL(a.amt, 0) AS amt,           -- 공급가
			     ISNULL(a.addamt, 0) AS addamt,        -- 부과세
			     ISNULL(TB_CA640.mijamt, 0) AS totalAmt,      -- 합계(미지급액)
			     FORMAT(CONVERT(date, TB_CA640.IN_DT, 112), 'MM.dd')
			+ ' - '
			+ RIGHT('0000' + CAST(TB_CA640.IN_SQ AS varchar(4)), 4) AS dzIssueNo,		-- 더존발행번호
			     ''  AS DEL_CHK,
			     ''  AS SELCHK,
			     TB_CA640.spjangcd AS spjangcd,
			     TB_CA640.mijdate + TB_CA640.mijnum AS relation_no,
			     TB_CA640.mijnum   AS RELATION_SEQ,
			     CASE WHEN TB_CA640.gubun = '11' THEN 'MT' ELSE 'OD' END AS relation_division,
			     TB_XCLIENT.emcltcd AS CUSTOMER_CD,
			     (SELECT emactcd FROM tb_e601
			       WHERE custcd = TB_CA640.custcd AND spjangcd = TB_CA640.spjangcd AND actcd = TB_CA640.actcd) AS FIELD_CD,
			     (SELECT actnm FROM tb_e601
			       WHERE custcd = TB_CA640.custcd AND spjangcd = TB_CA640.spjangcd AND actcd = TB_CA640.actcd) AS FIELD_NAME,
			     TB_XCLIENT.saupnum AS REGISTRATION_NO,
			     ISNULL(TB_CA640.mijamt,0) - ISNULL(TB_CA640.chaamt,0) - ISNULL(TB_CA640.hamt,0)
			             - ISNULL(TB_CA640.bamt,0) - ISNULL(TB_CA640.jamt,0) - ISNULL(TB_CA640.jmar,0)
			             - ISNULL(TB_CA640.eamt,0) - ISNULL(TB_CA640.samt,0) - ISNULL(TB_CA640.damt,0)
			             - ISNULL(TB_CA640.gamt,0) - ISNULL(TB_CA640.camt,0) AS UNCOLLECT_AMOUNT,
			     TB_CA640.remark            AS SUBJECT,
			     TB_CA640.DATASEND_DIVISION,
			     TB_XCLIENT.prenum          AS COPORATE_NO,
			     TB_XCLIENT.prenm           AS REPRESENTATIVE_NAME,
			     TB_XCLIENT.biztypenm       AS BUSINESS_TYPE,
			     TB_XCLIENT.bizitemnm       AS BUSINESS_CONDITIONS,
			     TB_XCLIENT.cltadres        AS RELATION_ADDRESS,
			     TB_XCLIENT.taxtelnum       AS TEL_NO,
			     TB_CA640.acccd,
			     TB_AC001.djacccd   AS djacccd,        
					 TB_AC001.accnm     AS accnm,  
			     TB_CA640.IN_DT,
			     TB_CA640.IN_SQ,
			     TB_CA640.divicd            AS divicd,           
			     TB_CA640.actcd             AS actcd_org,
			     a.actcd         AS pjcd_OR,   -- 라인 현장코드
			     TB_E601.emactcd AS pjcd,      -- 더존 프로젝트 코드
			     TB_E601.actnm   AS pjnm,       -- 현장명
			     TB_CA640.mijdate AS MISDATE,
					 TB_CA640.mijnum  AS MISNUM,
			     TB_CA640.divicd as divicd ,
			     		(select dzdivicd from tb_jc002 where custcd=:as_custcd and spjangcd=:spjangcd and divicd=TB_CA640.divicd) as dzdivicd,
			     		(select divinm from tb_jc002 where custcd=:as_custcd and spjangcd=:spjangcd and divicd=TB_CA640.divicd) as divinm,
							
							-- ✅ NULL이면 빈 문자열('')로 반환
							 ISNULL(TB_XCLIENT_SPJANGCD.spjangnum, '') AS vatDivCd,
							 ISNULL(TB_XCLIENT_SPJANGCD.spjangnm, '') AS vatDivNm,
							
							a.pname  AS pname,
							a.unit  AS punit,
							a.uamt  AS puamt,
							a.qty   AS pqty
			 FROM TB_CA640 WITH (NOLOCK)
			 LEFT OUTER JOIN TB_AC001 WITH (NOLOCK)
			     ON (TB_CA640.custcd = TB_AC001.custcd AND TB_CA640.acccd = TB_AC001.acccd)
			 LEFT OUTER JOIN TB_XCLIENT WITH (NOLOCK)
			     ON (TB_CA640.custcd = TB_XCLIENT.custcd AND TB_CA640.cltcd = TB_XCLIENT.cltcd)
			 /*LEFT OUTER JOIN TB_E601 WITH (NOLOCK)
			     ON (TB_CA640.custcd = TB_E601.custcd AND TB_CA640.spjangcd = TB_E601.spjangcd AND TB_CA640.actcd = TB_E601.actcd)*/
			 -- ✅ TB_XCLIENT_SPJANGCD 조인
				LEFT OUTER JOIN TB_XCLIENT_SPJANGCD WITH (NOLOCK)
						ON (TB_CA640.custcd = TB_XCLIENT_SPJANGCD.custcd 
					 AND TB_CA640.cltcd = TB_XCLIENT_SPJANGCD.cltcd
					 AND TB_CA640.spjangcd = TB_XCLIENT_SPJANGCD.spjangcd)    
			 LEFT OUTER JOIN (
			     SELECT custcd, spjangcd, cltcd, mijdate, mijnum,
									 samt AS amt,
									 tamt AS addamt,
									 unit, actcd, uamt, qty,
									 remark AS pname
						FROM TB_CA641 WITH (NOLOCK)
			 ) a
			     ON (TB_CA640.custcd = a.custcd
			     AND TB_CA640.spjangcd = a.spjangcd
			     AND TB_CA640.mijdate = a.mijdate
			     AND TB_CA640.mijnum  = a.mijnum)
			     LEFT JOIN TB_E601 WITH (NOLOCK)
			       ON a.custcd   = TB_E601.custcd
			      AND a.spjangcd = TB_E601.spjangcd
			      AND a.actcd    = TB_E601.actcd
			 WHERE TB_CA640.custcd   = :as_custcd
			   AND TB_CA640.spjangcd = :spjangcd
			   AND TB_CA640.mijdate BETWEEN :as_stdate AND :as_enddate
			""";

		// 🔹 company(거래처) 있을 때만 조건 추가
		if (company != null && !company.isBlank() && !"%".equals(company)) {
			sql += """
				AND TB_XCLIENT.cltnm like :as_cltcd
				""";
			param.addValue("as_cltcd", "%" + company + "%");
		}

		// 🔹 price_type(Y/N/all) 에 따라 DATASEND_DIVISION 조건 추가
		if (price_type != null && !price_type.isBlank() && !"all".equalsIgnoreCase(price_type)) {
			if ("Y".equalsIgnoreCase(price_type)) {
				sql += """
					AND TB_CA640.DATASEND_DIVISION = :SendDiv
					""";
				param.addValue("SendDiv", "Y");
			} else if ("N".equalsIgnoreCase(price_type)) {
				sql += """
					AND ISNULL(NULLIF(LTRIM(RTRIM(TB_CA640.DATASEND_DIVISION)), ''), 'N') <> 'Y'
					""";
				// N은 상수 조건이라 파라미터 불필요
			}
		}
//		log.info("비용 read SQL: {}", sql);
//    log.info("SQL Parameters: {}", param.getValues());
		return sqlRunner.getRows(sql, param);
	}

	public List<Map<String, Object>> getReceiptRead(String start, String end, String company, String receiptType, String spjangcd) {
		MapSqlParameterSource param = new MapSqlParameterSource();

		// 날짜 파라미터
		String frDt = start != null ? start.replaceAll("-", "") : "";
		String toDt = end   != null ? end.replaceAll("-", "")   : "";
		param.addValue("as_stdate", frDt);
		param.addValue("as_enddate", toDt);
		param.addValue("spjangcd", spjangcd);
		param.addValue("as_custcd","samjung" );
		param.addValue("receiptType", receiptType);

		String sql = """
			SELECT
						'' AS DEL_CHK,
						TB_DA026h.rcvdate,
						TB_DA026h.rcvnum AS rcvnum,
						TB_DA026h.spjangcd AS WORKAREA_CD,
						'T' AS RELATION_DIVISION,
						FORMAT(CONVERT(date, TB_DA026h.rcvdate, 112), 'yy.MM.dd')
								+ ' - '
								+ TB_DA026h.rcvnum AS relation_no,
						'01' AS RELATION_SEQ,
						CASE
								WHEN ISNULL(TB_DA023.misamt, 0)
									 - ISNULL(TB_DA023.chaamt, 0)
									 - ISNULL(TB_DA023.hamt, 0)
									 - ISNULL(TB_DA023.bamt, 0)
									 - ISNULL(TB_DA023.jamt, 0)
									 - ISNULL(TB_DA023.jmar, 0)
									 - ISNULL(TB_DA023.csamt, 0)
									 - ISNULL(TB_DA023.cmar, 0)
									 - ISNULL(TB_DA023.eamt, 0)
									 - ISNULL(TB_DA023.samt, 0)
									 - ISNULL(TB_DA023.cdmar, 0)
									 - ISNULL(TB_DA023.damt, 0)
									 - ISNULL(TB_DA023.gamt, 0)
									 - ISNULL(TB_DA023.dcamt, 0)
									 - ISNULL(TB_DA023.camt, 0) = 0
								THEN 'Y'
								ELSE 'N'
						END AS RELATION_CHOICE,
						CASE
								WHEN TB_DA026h.bamt > 0 THEN '03'
								ELSE '05'
						END AS BILL_COLLECT_TYPE,
						TB_XCLIENT.cltcd       AS cltcd,
						TB_XCLIENT.emcltcd     AS CUSTOMER_CD,
						TB_XCLIENT.cltnm       AS CUSTOMER_NAME,
						-- 🔹 현장(프로젝트) 정보: TB_E601 JOIN 사용
						TB_E601.emactcd AS FIELD_CD,    -- 현장 외부코드
						TB_E601.actnm   AS FIELD_NAME,  -- 현장명
						TB_DA023.actcd  AS actcd,       -- 내부 현장코드
						TB_E601.actnm   AS actnm,       -- 현장명(기존 alias 유지용)
						FORMAT(CONVERT(date, TB_DA026h.rcvdate, 112), 'yyyy-MM-dd') AS RELATION_DATE,
						TB_XCLIENT.saupnum  AS REGISTRATION_NO,
						-- 수금액 / 수수료 / 합계
						ISNULL(TB_DA026h.hamt, 0) + ISNULL(TB_DA026h.bamt, 0) + ISNULL(TB_DA026h.jamt, 0)
							+ ISNULL(TB_DA026h.eamt, 0) + ISNULL(TB_DA026h.samt, 0) + ISNULL(TB_DA026h.damt, 0)
							+ ISNULL(TB_DA026h.csamt, 0) + ISNULL(TB_DA026h.dcamt, 0) AS SUPPLY_PRICE,
						ISNULL(TB_DA026h.jmar, 0) + ISNULL(TB_DA026h.bmar, 0) + ISNULL(TB_DA026h.gamt, 0)
							+ ISNULL(TB_DA026h.cmar, 0) + ISNULL(TB_DA026h.cdmar, 0) AS SURTAX,
						ISNULL(TB_DA026h.hamt, 0) + ISNULL(TB_DA026h.bamt, 0) + ISNULL(TB_DA026h.jamt, 0)
							+ ISNULL(TB_DA026h.jmar, 0) + ISNULL(TB_DA026h.csamt, 0) + ISNULL(TB_DA026h.cmar, 0)
							+ ISNULL(TB_DA026h.eamt, 0) + ISNULL(TB_DA026h.samt, 0) + ISNULL(TB_DA026h.cdmar, 0)
							+ ISNULL(TB_DA026h.damt, 0) + ISNULL(TB_DA026h.gamt, 0) + ISNULL(TB_DA026h.dcamt, 0)
							+ ISNULL(TB_DA026h.bmar, 0) AS TOTAL_AMOUNT,
						(SELECT TOP 1 d.remark
						 FROM TB_DA026 d WITH (NOLOCK)
						 WHERE d.custcd  = TB_DA026h.custcd
							 AND d.spjangcd= TB_DA026h.spjangcd
							 AND d.cltcd   = TB_DA026h.cltcd
							 AND d.rcvdate = TB_DA026h.rcvdate
							 AND d.rcvnum  = TB_DA026h.rcvnum
						 ORDER BY LEN(ISNULL(d.remark, '')) DESC, d.remark DESC
						) AS SUBJECT,
						TB_DA026h.DATASEND_DIVISION,
						TB_XCLIENT.prenum      AS COPORATE_NO,
						TB_XCLIENT.prenm       AS REPRESENTATIVE_NAME,
						TB_XCLIENT.biztypenm   AS BUSINESS_TYPE,
						TB_XCLIENT.bizitemnm   AS BUSINESS_CONDITIONS,
						TB_XCLIENT.cltadres    AS RELATION_ADDRESS,
						TB_XCLIENT.taxtelnum   AS TEL_NO,
						-- 🔹 계좌/은행 정보
						CASE
								WHEN LEN(TB_DA026h.bankcd) > 0 AND TB_DA026h.bankcd IS NOT NULL
										THEN (SELECT reference_cd FROM STD_REFERENCE_ICUBE WHERE STD_REFERENCE_ICUBE.bankcd = TB_DA026h.bankcd)
								WHEN LEN(TB_DA026h.cbankcd) > 0 AND TB_DA026h.cbankcd IS NOT NULL
										THEN (SELECT reference_cd FROM STD_REFERENCE_ICUBE WHERE STD_REFERENCE_ICUBE.bankcd = TB_DA026h.cbankcd)
								ELSE
										(SELECT reference_cd FROM STD_REFERENCE_ICUBE WHERE STD_REFERENCE_ICUBE.bankcd = TB_DA026h.jbankcd)
						END AS ACCOUNT_NO,
						CASE
								WHEN LEN(TB_DA026h.bankcd) > 0 AND TB_DA026h.bankcd IS NOT NULL
										THEN (SELECT reference_cd FROM STD_REFERENCE_ICUBE WHERE STD_REFERENCE_ICUBE.bankcd = TB_DA026h.bankcd)
								WHEN LEN(TB_DA026h.cbankcd) > 0 AND TB_DA026h.cbankcd IS NOT NULL
										THEN (SELECT reference_cd FROM STD_REFERENCE_ICUBE WHERE STD_REFERENCE_ICUBE.bankcd = TB_DA026h.cbankcd)
								ELSE
										(SELECT reference_cd FROM STD_REFERENCE_ICUBE WHERE STD_REFERENCE_ICUBE.bankcd = TB_DA026h.jbankcd)
						END AS BANK_TR_CD,   -- 은행 거래처코드(A1)
						CASE
								WHEN LEN(TB_DA026h.bankcd) > 0 AND TB_DA026h.bankcd IS NOT NULL
										THEN (SELECT bank_cd FROM STD_REFERENCE_ICUBE WHERE STD_REFERENCE_ICUBE.bankcd = TB_DA026h.bankcd)
								WHEN LEN(TB_DA026h.cbankcd) > 0 AND TB_DA026h.cbankcd IS NOT NULL
										THEN (SELECT bank_cd FROM STD_REFERENCE_ICUBE WHERE STD_REFERENCE_ICUBE.bankcd = TB_DA026h.cbankcd)
								ELSE
										(SELECT bank_cd FROM STD_REFERENCE_ICUBE WHERE STD_REFERENCE_ICUBE.bankcd = TB_DA026h.jbankcd)
						END AS BANK_ACCT_CD,  -- 더존 계좌코드(bank_cd)
						CASE
								WHEN LEN(TB_DA026h.bankcd) > 0 AND TB_DA026h.bankcd IS NOT NULL
										THEN (SELECT bank_name FROM STD_REFERENCE_ICUBE WHERE STD_REFERENCE_ICUBE.bankcd = TB_DA026h.bankcd)
								WHEN LEN(TB_DA026h.cbankcd) > 0 AND TB_DA026h.cbankcd IS NOT NULL
										THEN (SELECT bank_name FROM STD_REFERENCE_ICUBE WHERE STD_REFERENCE_ICUBE.bankcd = TB_DA026h.cbankcd)
								ELSE
										(SELECT bank_name FROM STD_REFERENCE_ICUBE WHERE STD_REFERENCE_ICUBE.bankcd = TB_DA026h.jbankcd)
						END AS BANK_NAME,
						(select dzdivicd from tb_jc002 where custcd=:as_custcd and spjangcd=:spjangcd and divicd=TB_DA023.divicd) as dzdivicd,
			   	(select divinm from tb_jc002 where custcd=:as_custcd and spjangcd=:spjangcd and divicd=TB_DA023.divicd) as divinm,
						TB_DA023.acccd AS djacccd,
						TB_AC001.accnm AS accnm,
						TB_DA026h.IN_DT,
						TB_DA026h.IN_SQ,
						FORMAT(CONVERT(date, TB_DA026h.IN_DT, 112), 'yy.MM.dd')
								+ ' - '
								+ RIGHT('0000' + CAST(TB_DA026h.IN_SQ AS varchar(4)), 4) AS IN_DT_SEQ,
						'' AS divicd,
						TB_DA026h.spjangcd AS SPJANGCD,
						TB_DA026h.misdate  AS MISDATE,
						TB_DA026h.misnum   AS MISNUM
				FROM TB_DA026h WITH (NOLOCK)
				LEFT OUTER JOIN TB_XCLIENT WITH (NOLOCK)
						ON TB_DA026h.custcd = TB_XCLIENT.custcd
					 AND TB_DA026h.cltcd  = TB_XCLIENT.cltcd
				LEFT OUTER JOIN TB_DA023 WITH (NOLOCK)
						ON TB_DA026h.custcd   = TB_DA023.custcd
					 AND TB_DA026h.spjangcd = TB_DA023.spjangcd
					 AND TB_DA026h.misdate  = TB_DA023.misdate
					 AND TB_DA026h.misnum   = TB_DA023.misnum
					 AND TB_DA026h.cltcd    = TB_DA023.cltcd
				LEFT OUTER JOIN TB_AC001 WITH (NOLOCK)
						ON TB_DA026h.custcd   = TB_AC001.custcd
					 AND TB_DA023.acccd     = TB_AC001.acccd
				LEFT OUTER JOIN TB_E601 WITH (NOLOCK)
						ON TB_E601.custcd     = TB_DA023.custcd
					 AND TB_E601.spjangcd   = TB_DA023.spjangcd
					 AND TB_E601.actcd      = TB_DA023.actcd
				WHERE TB_DA026h.custcd   = :as_custcd
					AND TB_DA026h.spjangcd = :spjangcd
					AND TB_DA026h.rcvdate BETWEEN :as_stdate AND :as_enddate
			""";

		if (company != null && !company.isBlank() && !"%".equals(company)) {
			sql += """
            AND TB_XCLIENT.cltnm like :as_cltcd
            """;
			param.addValue("as_cltcd", "%" + company + "%");
		}

		// 🔹 receiptType(Y/N/all) 필터
		if (receiptType != null && !receiptType.isBlank() && !"all".equalsIgnoreCase(receiptType)) {
			if ("Y".equalsIgnoreCase(receiptType)) {
				sql += """
                AND TB_DA026h.DATASEND_DIVISION = :SendDiv
                """;
				param.addValue("SendDiv", "Y");
			} else if ("N".equalsIgnoreCase(receiptType)) {
				sql += """
                AND ISNULL(NULLIF(LTRIM(RTRIM(TB_DA026h.DATASEND_DIVISION)), ''), 'N') <> 'Y'
                """;
			}
		}
//		log.info("수금 read SQL: {}", sql);
//    log.info("SQL Parameters: {}", param.getValues());
		return sqlRunner.getRows(sql, param);
	}

	// 지급 read
	public List<Map<String, Object>> getPaymentRead(String start,
																									String end,
																									String company,
																									String payment_type,
																									String spjangcd) {
		MapSqlParameterSource param = new MapSqlParameterSource();

		// 날짜/기본 파라미터
		String frDt = start != null ? start.replaceAll("-", "") : "";
		String toDt = end   != null ? end.replaceAll("-", "")   : "";
		param.addValue("as_stdate", frDt);
		param.addValue("as_enddate", toDt);
		param.addValue("as_spjangcd", spjangcd);
		param.addValue("as_custcd", "samjung");
		 param.addValue("payment_type", payment_type);

		String sql = """
        SELECT '' as DEL_CHK,
               TB_CA642.spjangcd as WORKAREA_CD,
               TB_CA642.mijdate, 
                FORMAT(CONVERT(date, TB_CA642.mijdate, 112), 'yy.MM.dd')
			       					 + ' - '
			       					 + TB_CA642.mijnum AS relation_no,
               TB_CA642.mijnum as RELATION_SEQ,
               CASE WHEN TB_CA642.gubun = '11' THEN 'MT' ELSE 'OD' END as relation_division,
               TB_XCLIENT.cltcd as cltcd,
               TB_XCLIENT.emcltcd as CUSTOMER_CD,
               TB_XCLIENT.cltnm as CUSTOMER_NAME,
               '' as FIELD_CD,
               '' as FIELD_NAME,
               FORMAT(CONVERT(date, TB_CA642.mijdate, 112), 'yyyy-MM-dd') as RELATION_DATE,
               TB_XCLIENT.saupnum as REGISTRATION_NO,
               a.amt as SUPPLY_PRICE,
               a.addamt as SURTAX,
               (select mijamt
                  from tb_ca640
                 where spjangcd = TB_CA642.spjangcd
                   and mijdate + mijnum = TB_CA642.mijdate + TB_CA642.mijnum) as TOTAL_AMOUNT,
               (select mijamt
                  from tb_ca640
                 where spjangcd = TB_CA642.spjangcd
                   and mijdate + mijnum = TB_CA642.mijdate + TB_CA642.mijnum)
                 - ISNULL(TB_CA642.hamt,0)
                 - ISNULL(TB_CA642.bamt,0)
                 - ISNULL(TB_CA642.eamt,0)
                 - ISNULL(TB_CA642.samt,0)
                 - ISNULL(TB_CA642.damt,0)
                 - ISNULL(TB_CA642.gamt,0)  as UNCOLLECT_AMOUNT,
               TB_CA642.remark as SUBJECT,
               TB_CA642.DATASEND_DIVISION,
               TB_XCLIENT.prenum as COPORATE_NO,
               TB_XCLIENT.prenm as REPRESENTATIVE_NAME,
               TB_XCLIENT.biztypenm as BUSINESS_TYPE,
               TB_XCLIENT.bizitemnm as BUSINESS_CONDITIONS,
               TB_XCLIENT.cltadres as RELATION_ADDRESS,
               TB_XCLIENT.taxtelnum as TEL_NO,
               TB_CA642.acccd,
               TB_AC001.djacccd,
               TB_CA642.IN_DT,
               TB_CA642.IN_SQ,
               FORMAT(CONVERT(date, TB_CA642.IN_DT, 112), 'yy.MM.dd')
			       						+ ' - '
			       						+ RIGHT('0000' + CAST(TB_CA642.IN_SQ AS varchar(4)), 4) AS IN_DT_SEQ,
               TB_CA642.divicd as divicd,
               (select dzdivicd
                  from tb_jc002
                 where custcd = :as_custcd
                   and spjangcd = :as_spjangcd
                   and divicd = TB_CA642.divicd) as dzdivicd,
               (select divinm
                  from tb_jc002
                 where custcd = :as_custcd
                   and spjangcd = :as_spjangcd
                   and divicd = TB_CA642.divicd) as divinm,
               '' as actcd,
               '' as pname,
               '' as psize
          FROM TB_CA642 WITH(NOLOCK)
          LEFT OUTER JOIN TB_AC001 WITH(NOLOCK)
            ON (TB_CA642.custcd = TB_AC001.custcd AND TB_CA642.acccd = TB_AC001.acccd)
          LEFT OUTER JOIN TB_XCLIENT WITH(NOLOCK)
            ON (TB_CA642.custcd = TB_XCLIENT.custcd AND TB_CA642.cltcd = TB_XCLIENT.cltcd)
          LEFT OUTER JOIN TB_E601 WITH(NOLOCK)
            ON (TB_CA642.custcd = TB_E601.custcd AND TB_CA642.spjangcd = TB_E601.spjangcd AND TB_CA642.actcd = TB_E601.actcd)
          LEFT OUTER JOIN (
               SELECT custcd, spjangcd, cltcd, mijdate, mijnum,
                      SUM(samt) as amt,
                      SUM(tamt) as addamt
                 FROM TB_CA641 WITH(NOLOCK)
                GROUP BY custcd, spjangcd, cltcd, mijdate, mijnum
          ) a
            ON (TB_CA642.custcd   = a.custcd
            AND TB_CA642.spjangcd = a.spjangcd
            AND TB_CA642.mijdate  = a.mijdate
            AND TB_CA642.mijnum   = a.mijnum)
         WHERE TB_CA642.custcd   = :as_custcd
           AND TB_CA642.spjangcd = :as_spjangcd
           AND TB_CA642.mijdate BETWEEN :as_stdate AND :as_enddate
        """;
		// 🔻 여기서부터 동적 조건 추가

		// 🔹 company(거래처) 있을 때만 지급 거래처 필터
		if (company != null && !company.isBlank() && !"%".equals(company)) {
			sql += """
            AND TB_XCLIENT.cltnm like :as_cltcd
            """;
			param.addValue("as_cltcd", "%" + company + "%");
		}

		// 🔹 payment_type(Y/N/all) → DATASEND_DIVISION 필터
		if (payment_type != null && !payment_type.isBlank() && !"all".equalsIgnoreCase(payment_type)) {
			if ("Y".equalsIgnoreCase(payment_type)) {
				// 전송된 건만
				sql += """
                AND TB_CA642.DATASEND_DIVISION = :SendDiv
                """;
				param.addValue("SendDiv", "Y");
			} else if ("N".equalsIgnoreCase(payment_type)) {
				// Y가 아닌 모든 건 (NULL, '', N, 기타값 포함)
				sql += """
                AND ISNULL(NULLIF(LTRIM(RTRIM(TB_CA642.DATASEND_DIVISION)), ''), 'N') <> 'Y'
                """;
			}
		}
//		log.info("지급 read SQL: {}", sql);
//    log.info("SQL Parameters: {}", param.getValues());
		return sqlRunner.getRows(sql, param);
	}


	@Transactional
	public int generateMenuSqFromDb(String coCd, String menuDt) {
		MapSqlParameterSource param = new MapSqlParameterSource()
																		.addValue("regdate", menuDt);

		// 1) 먼저 UPDATE 시도 (해당 regdate 있으면 +1)
		String updateSql = """
        UPDATE TB_SEQ
           SET regseq = regseq + 1
         WHERE regdate = :regdate
        """;

		int updated = sqlRunner.execute(updateSql, param);

		if (updated == 0) {
			// 2-1) 없으면 INSERT 1
			String insertSql = """
            INSERT INTO TB_SEQ(regdate, regseq)
            VALUES (:regdate, 1)
            """;
			sqlRunner.execute(insertSql, param);
		}

		// 3) 최종 값 다시 조회
		String selectSql = """
        SELECT regseq
          FROM TB_SEQ
         WHERE regdate = :regdate
        """;

		Map<String, Object> row = sqlRunner.getRow(selectSql, param);

		if (row == null) {
			// 이 경우는 거의 없겠지만 방어 코드
			return 1;
		}

		return ((Number) row.get("regseq")).intValue();
	}


	public List<String> validateAccountsAndControls(String coCd, List<Map<String, Object>> lines) {

		List<String> errors = new ArrayList<>();

		if (lines == null || lines.isEmpty()) {
			return errors;
		}

		// 1) 이 전표에 사용된 acctCd 목록 뽑기
		Set<String> acctSet = new HashSet<>();
		for (Map<String, Object> line : lines) {
			Object acctObj = line.get("acctCd");
			if (acctObj != null) {
				String acctCd = String.valueOf(acctObj).trim();
				if (!acctCd.isEmpty()) {
					acctSet.add(acctCd);
				}
			}
		}

		if (acctSet.isEmpty()) {
			return errors;
		}

		// 2) 11A02 한 번만 호출해서 전체 계정 목록 가져오기
		Map<String, Object> acctRes = douzoneClient.callAccountList(coCd);

		if (!"0".equals(String.valueOf(acctRes.get("resultCode")))) {
			errors.add("계정과목조회(11A02) 실패: " + acctRes.get("resultMsg"));
			return errors;
		}

		List<Map<String, Object>> acctData =
			(List<Map<String, Object>>) acctRes.get("resultData");

		if (acctData == null) {
			errors.add("계정과목조회(11A02) 결과가 비어 있습니다.");
			return errors;
		}

		// 2-1) 유효한 계정코드 Set / Map 구성
		Set<String> validAcctSet = new HashSet<>();
		Map<String, Map<String, Object>> acctMapByCd = new HashMap<>();

		for (Map<String, Object> row : acctData) {
			if (row == null) continue;
			String cd = String.valueOf(row.get("acctCd"));
			if (cd != null) {
				String acctCd = cd.trim();
				if (!acctCd.isEmpty()) {
					validAcctSet.add(acctCd);
					acctMapByCd.put(acctCd, row);
				}
			}
		}

		// 3) 먼저 존재하지 않는 계정코드부터 걸러내기
		for (String acctCd : acctSet) {
			if (!validAcctSet.contains(acctCd)) {
				errors.add("계정코드[" + acctCd + "] 는 더존 계정마스터에 존재하지 않습니다.");
			}
		}

		// 4) 계정이 존재하는 애들에 대해서만 11A03 + 필수값 체크
		for (String acctCd : acctSet) {

			if (!validAcctSet.contains(acctCd)) {
				continue;
			}

			Map<String, Object> ctrlRes = douzoneClient.callAccountCtrlList(coCd, acctCd);

			if (!"0".equals(String.valueOf(ctrlRes.get("resultCode")))) {
				errors.add("계정코드[" + acctCd + "] 관리항목 조회 실패: " + ctrlRes.get("resultMsg"));
				continue;
			}

			List<Map<String, Object>> ctrlList =
				(List<Map<String, Object>>) ctrlRes.get("resultData");

			Set<String> requiredCtrl = new HashSet<>();
			if (ctrlList != null) {
				for (Map<String, Object> c : ctrlList) {
					if (c == null) continue;
					String ctrlCd = String.valueOf(c.get("ctrlCd"));
					String attrFg = String.valueOf(c.get("attrFg"));
					if ("1".equals(attrFg) || "3".equals(attrFg) || "4".equals(attrFg)) {
						requiredCtrl.add(ctrlCd);
					}
				}
			}

			// 이 계정코드 사용하는 모든 line 검사
			for (Map<String, Object> line : lines) {
				if (line == null) continue;

				String lineAcct = String.valueOf(line.get("acctCd")).trim();
				if (!acctCd.equals(lineAcct)) {
					continue;
				}

				String menuDt = String.valueOf(line.get("menuDt"));
				Object menuLnSq = line.get("menuLnSq");

				// A1: 거래처 필수 → trCd, trNm 확인
				if (requiredCtrl.contains("A1")) {
					String trCd = (String) line.getOrDefault("trCd", "");
					String trNm = (String) line.getOrDefault("trNm", "");
					if (trCd.isEmpty() || trNm.isEmpty()) {
						errors.add(String.format(
							"계정[%s] line[%s-%s]: A1(거래처) 필수인데 trCd/trNm 누락",
							acctCd, menuDt, menuLnSq
						));
					}
				}

				// C1: 사용부서 필수 → ctDept 확인
				if (requiredCtrl.contains("C1")) {
					String ctDept = String.valueOf(line.getOrDefault("ctDept", ""));
					if (ctDept.isEmpty()) {
						errors.add(String.format(
							"계정[%s] line[%s-%s]: C1(사용부서) 필수인데 ctDept 누락",
							acctCd, menuDt, menuLnSq
						));
					}
				}

				// TODO: C2→cashCd, E6→itemCd, H1→ctQt 등 필요한 관리항목 계속 추가
			}
		}

		return errors;
	}

	public List<String> applyAccountCodeByAcctNm(String coCd, List<Map<String, Object>> lines) {
		List<String> errors = new ArrayList<>();

		for (Map<String, Object> line : lines) {
			String acctCd = (String) line.get("acctCd");  // 현재 들어온 값 (내부코드일 수 있음)
			String acctNm = (String) line.get("acctNm");  // 화면에서 넣어준 계정명 (상품매출, 부가세예수 등)
			Object lnSq = line.get("menuLnSq");

			// 이미 더존 최종 코드(예: 7~8자리)면 스킵 (필요시 자리수 조정)
			if (acctCd != null && acctCd.trim().length() >= 7) {
				continue;
			}

			if (acctNm == null || acctNm.isBlank()) {
				errors.add("menuLnSq=" + lnSq + " : acctNm(계정명)이 없어 더존 계정 검색 불가");
				continue;
			}

			List<Map<String, Object>> candidates =
				douzoneClient.searchAccountByName(coCd, acctNm);

			if (candidates == null || candidates.isEmpty()) {
				errors.add("menuLnSq=" + lnSq + " : acctNm=[" + acctNm + "] 로 더존 계정 없음");
				continue;
			}

			Map<String, Object> dzAcct;

			if (candidates.size() == 1) {
				dzAcct = candidates.get(0);
			} else {
				// 🔹 여러 건이면 우리 규칙으로 한 건 선택
				dzAcct = chooseBestAccount(acctNm, candidates);

				if (dzAcct == null) {
					errors.add("menuLnSq=" + lnSq + " : acctNm=[" + acctNm + "] 결과 여러 건("
											 + candidates.size() + "건) - 자동 선택 규칙 없음");
					continue;
				}
			}

			String dzAcctCd = (String) dzAcct.get("acctCd");
			String dzAcctNm = (String) dzAcct.get("acctNm");

			line.put("acctCd", dzAcctCd);
			line.put("acctNm", dzAcctNm);
		}

		return errors;
	}

	// 🔹 11A02 결과가 여러 건일 때, 우리 규칙으로 "한 건" 선택
	private Map<String, Object> chooseBestAccount(String acctNm, List<Map<String, Object>> candidates) {

		if (candidates == null || candidates.isEmpty()) {
			return null;
		}

		if (acctNm != null && !acctNm.isBlank()) {
			String target = acctNm.trim();

			List<Map<String, Object>> exactMatches = candidates.stream()
			 .filter(c -> {
				 Object nmObj = c.get("acctNm");
				 String nm = (nmObj != null ? nmObj.toString().trim() : "");
				 return target.equals(nm);
			 })
			 .collect(java.util.stream.Collectors.toList());

			if (exactMatches.size() == 1) {
				// 👉 예: '원재료' 검색 결과 6건 중 acctNm이 정확히 '원재료'인 1490000만 골라짐
				return exactMatches.get(0);
			}
			// 0건이거나 2건 이상이면 → 아래 개별 룰로 넘어감
		}

		// 1) 상품매출 → 국내상품매출액(4010001) 우선 (필요시 코드 수정)
		if ("상품매출".equals(acctNm)) {
			for (Map<String, Object> c : candidates) {
				String cd = String.valueOf(c.get("acctCd"));
				if ("4010001".equals(cd)) { // 🔸 회사에서 실제로 쓰는 코드로 맞춰 주세요
					return c;
				}
			}
			// 못 찾으면 groupCd=4110(매출액) & drcrFg=2 우선 (추측이므로 필요 없으면 삭제 가능)
			for (Map<String, Object> c : candidates) {
				String groupCd = String.valueOf(c.get("groupCd"));
				String drcrFg = String.valueOf(c.get("drcrFg"));
				if ("4110".equals(groupCd) && "2".equals(drcrFg)) {
					return c;
				}
			}
		}

		// 2) 외상매출금 → 단기 외상매출금(1080000) 우선
		if ("외상매출금".equals(acctNm)) {
			for (Map<String, Object> c : candidates) {
				String cd = String.valueOf(c.get("acctCd"));
				if ("1080000".equals(cd)) { // 🔸 실제 코드로 조정
					return c;
				}
			}
			// 못 찾으면 groupCd=1110(당좌자산) 우선 선택 (옵션)
			for (Map<String, Object> c : candidates) {
				String groupCd = String.valueOf(c.get("groupCd"));
				if ("1110".equals(groupCd)) {
					return c;
				}
			}
		}

		// 3) 부가세예수는 보통 한 건만 나오지만, 혹시나 여러 건이면 첫 건 사용 (원하면 더 정교화 가능)
		if ("부가세예수".equals(acctNm)) {
			return candidates.get(0);
		}

		// 4) 비용용 계정들에 대한 여유 규칙 (필요하면)
		// 부가세대급금
		if ("부가세대급금".equals(acctNm)) {
			// 보통 한 건이지만, 여러 건이면 1350000 우선
			for (Map<String, Object> c : candidates) {
				String cd = String.valueOf(c.get("acctCd"));
				if ("1350000".equals(cd)) {
					return c;
				}
			}
			return candidates.get(0);
		}

		// 외상매입금
		if ("외상매입금".equals(acctNm)) {
			for (Map<String, Object> c : candidates) {
				String cd = String.valueOf(c.get("acctCd"));
				if ("2510000".equals(cd)) {
					return c;
				}
			}
			return candidates.get(0);
		}

		// 원재료
		if ("원재료".equals(acctNm)) {
			for (Map<String, Object> c : candidates) {
				String cd = String.valueOf(c.get("acctCd"));
				if ("1490000".equals(cd)) {
					return c;
				}
			}
			return candidates.get(0);
		}

		if ("지급수수료".equals(acctNm)) {
			// 1순위: acctCd = 8310000
			for (Map<String, Object> c : candidates) {
				String cd = String.valueOf(c.get("acctCd"));
				if ("8310000".equals(cd)) {
					return c;
				}
			}
			return candidates.get(0);
		}

		// 그 외는 아직 규칙 없음 → null 리턴해서 에러 처리하게 둠
		return null;
	}

	/**
	 * 더존 전송 성공 후 TB_DA023(DATASEND_DIVISION / IN_DT / IN_SQ) 업데이트
	 *  - 매출 탭용
	 *
	 * @param lines   더존으로 전송한 전표 라인들
	 * @param menuDt  더존 전표 작성일자 (yyyyMMdd)
	 * @param menuSq  더존 전표 번호
	 */
	public void updateSalesSendFlag(List<Map<String, Object>> lines,
																	String menuDt,
																	int menuSq) {

		// 🔹 헤더 단위로만 업데이트: (misdate, misnum, spjangcd) 조합으로 중복 제거
		Set<String> keySet = new HashSet<>();
		List<Map<String, Object>> targets = new ArrayList<>();

		for (Map<String, Object> line : lines) {
			if (line == null) continue;

			String misdate  = asString(line.get("misdate"),  line.get("MISDATE"));
			String misnum   = asString(line.get("misnum"),   line.get("MISNUM"));
			String spjangcd = asString(line.get("spjangcd"), line.get("SPJANGCD"));

			if (misdate == null || misnum == null) {
				log.debug("updateSalesSendFlag: line에 misdate/misnum 없음 => {}", line);
				continue;
			}

			String key = misdate + "|" + misnum + "|" + (spjangcd != null ? spjangcd : "");
			if (keySet.add(key)) {
				Map<String, Object> m = new HashMap<>();
				m.put("misdate",  misdate);
				m.put("misnum",   misnum);
				m.put("spjangcd", spjangcd);
				targets.add(m);
			}
		}

		if (targets.isEmpty()) {
			log.warn("updateSalesSendFlag: MISDATE/MISNUM 정보가 없어 TB_DA023 업데이트 대상 없음");
			return;
		}

		String sql = """
        UPDATE TB_DA023
           SET DATASEND_DIVISION = 'Y',
               IN_DT             = :inDt,
               IN_SQ             = :inSq
         WHERE MISDATE  = :misdate
           AND MISNUM   = :misnum
           AND (:spjangcd IS NULL OR SPJANGCD = :spjangcd)
        """;

		for (Map<String, Object> t : targets) {

			String spjangcd = (String) t.get("spjangcd");

			MapSqlParameterSource params = new MapSqlParameterSource()
																			 .addValue("inDt",     menuDt)           // 🔹 더존 menuDt
																			 .addValue("inSq",     menuSq)           // 🔹 더존 menuSq
																			 .addValue("misdate",  t.get("misdate"))
																			 .addValue("misnum",   t.get("misnum"))
																			 .addValue("spjangcd", spjangcd);

			int updated = sqlRunner.execute(sql, params);

			// log.info("TB_DA023 업데이트: MISDATE={}, MISNUM={}, SPJANGCD={}, IN_DT={}, IN_SQ={}, updated={}",
			//          t.get("misdate"), t.get("misnum"), spjangcd, menuDt, menuSq, updated);
		}
	}


	private String asString(Object... objs) {
		for (Object o : objs) {
			if (o == null) continue;
			String s = String.valueOf(o).trim();
			if (!s.isEmpty()) return s;
		}
		return null;
	}

	/**
	 * 더존 전송 성공 후 TB_CA640(DATASEND_DIVISION / IN_DT / IN_SQ) 업데이트
	 *  - 비용/매입 탭용
	 *
	 * @param lines   더존으로 전송한 전표 라인들 (DouZoneSend에서 넘어오는 data)
	 * @param menuDt  더존 전표 작성일자 (yyyyMMdd)
	 * @param menuSq  더존 전표 번호
	 */
	public void updatePriceSendFlag(List<Map<String, Object>> lines,
																	String menuDt,
																	int menuSq) {

		String inDt = menuDt;   // 의미상 IN_DT ≒ menuDt

		// 🔹 헤더 단위로만 업데이트: (misdate, misnum, spjangcd) 조합으로 중복 제거
		Set<String> keySet = new HashSet<>();
		List<Map<String, Object>> targets = new ArrayList<>();

		for (Map<String, Object> line : lines) {
			if (line == null) continue;

			String misdate  = asString(line.get("misdate"),  line.get("MISDATE"));
			String misnum   = asString(line.get("misnum"),   line.get("MISNUM"));
			String spjangcd = asString(line.get("spjangcd"), line.get("SPJANGCD"));

			if (misdate == null || misnum == null) {
				log.debug("updatePriceSendFlag: line에 misdate/misnum 없음 => {}", line);
				continue;
			}

			String key = misdate + "|" + misnum + "|" + (spjangcd != null ? spjangcd : "");
			if (keySet.add(key)) {
				Map<String, Object> m = new HashMap<>();
				m.put("misdate",  misdate);    // TB_CA640.MIJDATE 와 매핑
				m.put("misnum",   misnum);     // TB_CA640.MIJNUM  와 매핑
				m.put("spjangcd", "ZZ");
				targets.add(m);
			}
		}

		if (targets.isEmpty()) {
			log.warn("updatePriceSendFlag: MISDATE/MISNUM 정보가 없어 TB_CA640 업데이트 대상 없음");
			return;
		}

		String sql = """
        UPDATE TB_CA640
           SET DATASEND_DIVISION = 'Y',
               IN_DT             = :inDt,
               IN_SQ             = :inSq
         WHERE MIJDATE  = :mijdate
           AND MIJNUM   = :mijnum
           AND (:spjangcd IS NULL OR SPJANGCD = :spjangcd)
        """;

		for (Map<String, Object> t : targets) {

			String spjangcd = (String) t.get("spjangcd");

			MapSqlParameterSource params = new MapSqlParameterSource()
																			 .addValue("inDt",     inDt)                 // = menuDt
																			 .addValue("inSq",     menuSq)               // = menuSq
																			 .addValue("mijdate",  t.get("misdate"))
																			 .addValue("mijnum",   t.get("misnum"))
																			 .addValue("spjangcd", spjangcd);

			int updated = sqlRunner.execute(sql, params);

//			 log.info("TB_CA640 업데이트: MIJDATE={}, MIJNUM={}, SPJANGCD={}, IN_DT={}, IN_SQ={}, updated={}",
//			          t.get("misdate"), t.get("misnum"), spjangcd, inDt, menuSq, updated);
		}
	}

	/**
	 * 더존 전송 성공 후 TB_DA026h(DATASEND_DIVISION / IN_DT / IN_SQ) 업데이트
	 *  - 수금 탭용
	 *
	 * @param lines   더존으로 전송한 전표 라인들 (DouZoneSend에서 넘어오는 data)
	 * @param menuDt  더존 전표 작성일자 (yyyyMMdd)
	 * @param menuSq  더존 전표 번호
	 */
	public void updateReceiptSendFlag(List<Map<String, Object>> lines,
																		String menuDt,
																		int menuSq) {

		// 🔹 헤더 단위로만 업데이트: (misdate, misnum, spjangcd) 조합으로 중복 제거
		Set<String> keySet = new HashSet<>();
		List<Map<String, Object>> targets = new ArrayList<>();

		for (Map<String, Object> line : lines) {
			String misdate  = asString(line.get("misdate"),  line.get("MISDATE"));
			String misnum   = asString(line.get("misnum"),   line.get("MISNUM"));
			String spjangcd = asString(line.get("spjangcd"), line.get("SPJANGCD"));

			if (misdate == null || misnum == null) {
				log.debug("updateReceiptSendFlag: line에 misdate/misnum 없음 => {}", line);
				continue;
			}

			String key = misdate + "|" + misnum + "|" + (spjangcd != null ? spjangcd : "");
			if (keySet.add(key)) {
				Map<String, Object> m = new HashMap<>();
				m.put("misdate",  misdate);
				m.put("misnum",   misnum);
				m.put("spjangcd", spjangcd);
				targets.add(m);
			}
		}

		if (targets.isEmpty()) {
			log.warn("updateReceiptSendFlag: MISDATE/MISNUM 정보가 없어 TB_DA026h 업데이트 대상 없음");
			return;
		}

		String sql = """
        UPDATE TB_DA026h
           SET DATASEND_DIVISION = 'Y',
               IN_DT             = :inDt,
               IN_SQ             = :inSq
         WHERE MISDATE  = :misdate
           AND MISNUM   = :misnum
           AND (:spjangcd IS NULL OR SPJANGCD = :spjangcd)
        """;

		for (Map<String, Object> t : targets) {

			String spjangcd = (String) t.get("spjangcd");

			MapSqlParameterSource params = new MapSqlParameterSource()
																			 .addValue("inDt",     menuDt)   // 🔹 더존 menuDt
																			 .addValue("inSq",     menuSq)   // 🔹 더존 menuSq
																			 .addValue("misdate",  t.get("misdate"))
																			 .addValue("misnum",   t.get("misnum"))
																			 .addValue("spjangcd", spjangcd);

			int updated = sqlRunner.execute(sql, params);

			// log.info("TB_DA026h 업데이트: MISDATE={}, MISNUM={}, SPJANGCD={}, IN_DT={}, IN_SQ={}, updated={}",
			//          t.get("misdate"), t.get("misnum"), spjangcd, menuDt, menuSq, updated);
		}
	}

	/**
	 * 더존 전송 성공 후 TB_CA642(DATASEND_DIVISION / IN_DT / IN_SQ) 업데이트
	 *  - 지급 탭용
	 *
	 * @param lines   더존으로 전송한 전표 라인들 (DouZoneSend에서 넘어오는 data)
	 * @param menuDt  더존 전표 작성일자 (yyyyMMdd)
	 * @param menuSq  더존 전표 번호
	 */
	public void updatePaymentSendFlag(List<Map<String, Object>> lines,
																		String menuDt,
																		int menuSq) {

		String inDt = menuDt;

		// 🔹 헤더 단위로만 업데이트: (misdate, misnum, spjangcd) 조합으로 중복 제거
		Set<String> keySet = new HashSet<>();
		List<Map<String, Object>> targets = new ArrayList<>();

		for (Map<String, Object> line : lines) {
			if (line == null) continue;

			// DouZoneSend 에서 세팅한 공통 키 사용 (misdate / misnum / spjangcd)
			String misdate  = asString(line.get("misdate"),  line.get("MISDATE"));
			String misnum   = asString(line.get("misnum"),   line.get("MISNUM"));
			String spjangcd = asString(line.get("spjangcd"), line.get("SPJANGCD"));

			if (misdate == null || misnum == null) {
				log.debug("updatePaymentSendFlag: line에 misdate/misnum 없음 => {}", line);
				continue;
			}

			String key = misdate + "|" + misnum + "|" + (spjangcd != null ? spjangcd : "");
			if (keySet.add(key)) {
				Map<String, Object> m = new HashMap<>();
				m.put("misdate",  misdate);   // TB_CA642.MIJDATE 와 매핑
				m.put("misnum",   misnum);    // TB_CA642.MIJNUM  와 매핑
				m.put("spjangcd", spjangcd);
				targets.add(m);
			}
		}

		if (targets.isEmpty()) {
			log.warn("updatePaymentSendFlag: MISDATE/MISNUM 정보가 없어 TB_CA642 업데이트 대상 없음");
			return;
		}

		String sql = """
        UPDATE TB_CA642
           SET DATASEND_DIVISION = 'Y',
               IN_DT             = :inDt,
               IN_SQ             = :inSq
         WHERE MIJDATE  = :mijdate
           AND MIJNUM   = :mijnum
           AND (:spjangcd IS NULL OR SPJANGCD = :spjangcd)
        """;

		for (Map<String, Object> t : targets) {

			String spjangcd = (String) t.get("spjangcd");

			MapSqlParameterSource params = new MapSqlParameterSource()
																			 .addValue("inDt",     inDt)                 // = menuDt
																			 .addValue("inSq",     menuSq)               // = menuSq
																			 .addValue("mijdate",  t.get("misdate"))
																			 .addValue("mijnum",   t.get("misnum"))
																			 .addValue("spjangcd", spjangcd);

			int updated = sqlRunner.execute(sql, params);

			// log.info("TB_CA642 업데이트: MIJDATE={}, MIJNUM={}, SPJANGCD={}, IN_DT={}, IN_SQ={}, updated={}",
			//          t.get("misdate"), t.get("misnum"), spjangcd, inDt, menuSq, updated);
		}
	}

	/**
	 * 더존 전표 삭제 후 TB_DA023 전송 플래그/IN_DT/IN_SQ 원복
	 * - 기준: IN_DT = menuDt, IN_SQ = menuSq
	 */
	@Transactional
	public void resetSalesSendFlag(String inDt, int inSq) {

		// 1) 대상 헤더 찾기 (여러 SPJANGCD 있을 수 있음)
		String selectSql = """
        SELECT MISDATE, MISNUM, SPJANGCD
          FROM TB_DA023
         WHERE IN_DT = :inDt
           AND IN_SQ = :inSq
        """;

		MapSqlParameterSource selParams = new MapSqlParameterSource()
																				.addValue("inDt", inDt)
																				.addValue("inSq", inSq);

		// 🔹 여기서는 getRows 사용
		List<Map<String, Object>> headers = sqlRunner.getRows(selectSql, selParams);

		if (headers == null || headers.isEmpty()) {
//			log.warn("resetSalesSendFlag: IN_DT={}, IN_SQ={} 에 해당하는 헤더 없음", inDt, inSq);
			return;
		}

		// 2) 플래그/IN_DT/IN_SQ 원복
		String updateSql = """
        UPDATE TB_DA023
           SET DATASEND_DIVISION = 'N',
               IN_DT             = NULL,
               IN_SQ             = NULL
         WHERE MISDATE  = :misdate
           AND MISNUM   = :misnum
           AND (:spjangcd IS NULL OR SPJANGCD = :spjangcd)
        """;

		for (Map<String, Object> h : headers) {

			// asString 재사용하고 싶으면 이렇게
			String misdate  = asString(h.get("MISDATE"),  h.get("misdate"));
			String misnum   = asString(h.get("MISNUM"),   h.get("misnum"));
			String spjangcd = asString(h.get("SPJANGCD"), h.get("spjangcd"));

			MapSqlParameterSource up = new MapSqlParameterSource()
																	 .addValue("misdate",  misdate)
																	 .addValue("misnum",   misnum)
																	 .addValue("spjangcd", spjangcd);

			int updated = sqlRunner.execute(updateSql, up);
//			log.info("resetSalesSendFlag: MISDATE={}, MISNUM={}, SPJANGCD={}, updated={}",misdate, misnum, spjangcd, updated);
		}
	}

	/**
	 * 더존 전표 삭제 후 TB_CA640(비용/매입) 전송 플래그/IN_DT/IN_SQ 원복
	 * - 기준: IN_DT = menuDt, IN_SQ = menuSq
	 */
	@Transactional
	public void resetPriceSendFlag(String inDt, int inSq) {

		// 1) 대상 헤더 찾기 (여러 SPJANGCD 있을 수 있음)
		String selectSql = """
        SELECT MIJDATE, MIJNUM, SPJANGCD
          FROM TB_CA640
         WHERE IN_DT = :inDt
           AND IN_SQ = :inSq
        """;

		MapSqlParameterSource selParams = new MapSqlParameterSource()
																				.addValue("inDt", inDt)
																				.addValue("inSq", inSq);

		List<Map<String, Object>> headers = sqlRunner.getRows(selectSql, selParams);

		if (headers == null || headers.isEmpty()) {
			// log.warn("resetPriceSendFlag: IN_DT={}, IN_SQ={} 에 해당하는 헤더 없음", inDt, inSq);
			return;
		}

		// 2) 플래그/IN_DT/IN_SQ 원복
		String updateSql = """
        UPDATE TB_CA640
           SET DATASEND_DIVISION = 'N',
               IN_DT             = NULL,
               IN_SQ             = NULL
         WHERE MIJDATE  = :mijdate
           AND MIJNUM   = :mijnum
           AND (:spjangcd IS NULL OR SPJANGCD = :spjangcd)
        """;

		for (Map<String, Object> h : headers) {

			String mijdate  = asString(h.get("MIJDATE"),  h.get("mijdate"));
			String mijnum   = asString(h.get("MIJNUM"),   h.get("mijnum"));
			String spjangcd = asString(h.get("SPJANGCD"), h.get("spjangcd"));

			MapSqlParameterSource up = new MapSqlParameterSource()
																	 .addValue("mijdate",  mijdate)
																	 .addValue("mijnum",   mijnum)
																	 .addValue("spjangcd", spjangcd);

			int updated = sqlRunner.execute(updateSql, up);
			// log.info("resetPriceSendFlag: MIJDATE={}, MIJNUM={}, SPJANGCD={}, updated={}", mijdate, mijnum, spjangcd, updated);
		}
	}

	/**
	 * 더존 전표 삭제 후 TB_DA026h(수금) 전송 플래그/IN_DT/IN_SQ 원복
	 * - 기준: IN_DT = menuDt, IN_SQ = menuSq
	 */
	@Transactional
	public void resetReceiptSendFlag(String inDt, int inSq) {

		String selectSql = """
        SELECT MISDATE, MISNUM, SPJANGCD
          FROM TB_DA026h
         WHERE IN_DT = :inDt
           AND IN_SQ = :inSq
        """;

		MapSqlParameterSource selParams = new MapSqlParameterSource()
																				.addValue("inDt", inDt)
																				.addValue("inSq", inSq);

		List<Map<String, Object>> headers = sqlRunner.getRows(selectSql, selParams);

		if (headers == null || headers.isEmpty()) {
			// log.warn("resetReceiptSendFlag: IN_DT={}, IN_SQ={} 에 해당하는 헤더 없음", inDt, inSq);
			return;
		}

		String updateSql = """
        UPDATE TB_DA026h
           SET DATASEND_DIVISION = 'N',
               IN_DT             = NULL,
               IN_SQ             = NULL
         WHERE MISDATE  = :misdate
           AND MISNUM   = :misnum
           AND (:spjangcd IS NULL OR SPJANGCD = :spjangcd)
        """;

		for (Map<String, Object> h : headers) {

			String misdate  = asString(h.get("MISDATE"),  h.get("misdate"));
			String misnum   = asString(h.get("MISNUM"),   h.get("misnum"));
			String spjangcd = asString(h.get("SPJANGCD"), h.get("spjangcd"));

			MapSqlParameterSource up = new MapSqlParameterSource()
																	 .addValue("misdate",  misdate)
																	 .addValue("misnum",   misnum)
																	 .addValue("spjangcd", spjangcd);

			int updated = sqlRunner.execute(updateSql, up);
			// log.info("resetReceiptSendFlag: MISDATE={}, MISNUM={}, SPJANGCD={}, updated={}", misdate, misnum, spjangcd, updated);
		}
	}

	/**
	 * 더존 전표 삭제 후 TB_CA642(지급) 전송 플래그/IN_DT/IN_SQ 원복
	 * - 기준: IN_DT = menuDt, IN_SQ = menuSq
	 */
	@Transactional
	public void resetPaymentSendFlag(String inDt, int inSq) {

		String selectSql = """
        SELECT MIJDATE, MIJNUM, SPJANGCD
          FROM TB_CA642
         WHERE IN_DT = :inDt
           AND IN_SQ = :inSq
        """;

		MapSqlParameterSource selParams = new MapSqlParameterSource()
																				.addValue("inDt", inDt)
																				.addValue("inSq", inSq);

		List<Map<String, Object>> headers = sqlRunner.getRows(selectSql, selParams);

		if (headers == null || headers.isEmpty()) {
			// log.warn("resetPaymentSendFlag: IN_DT={}, IN_SQ={} 에 해당하는 헤더 없음", inDt, inSq);
			return;
		}

		String updateSql = """
        UPDATE TB_CA642
           SET DATASEND_DIVISION = 'N',
               IN_DT             = NULL,
               IN_SQ             = NULL
         WHERE MIJDATE  = :mijdate
           AND MIJNUM   = :mijnum
           AND (:spjangcd IS NULL OR SPJANGCD = :spjangcd)
        """;

		for (Map<String, Object> h : headers) {

			String mijdate  = asString(h.get("MIJDATE"),  h.get("mijdate"));
			String mijnum   = asString(h.get("MIJNUM"),   h.get("mijnum"));
			String spjangcd = asString(h.get("SPJANGCD"), h.get("spjangcd"));

			MapSqlParameterSource up = new MapSqlParameterSource()
																	 .addValue("mijdate",  mijdate)
																	 .addValue("mijnum",   mijnum)
																	 .addValue("spjangcd", spjangcd);

			int updated = sqlRunner.execute(updateSql, up);
			// log.info("resetPaymentSendFlag: MIJDATE={}, MIJNUM={}, SPJANGCD={}, updated={}", mijdate, mijnum, spjangcd, updated);
		}
	}


	@Transactional
	public List<Map<String, Object>> receiptDetails(Map<String, Object> body) {
		MapSqlParameterSource p = new MapSqlParameterSource();

		String custcd   = String.valueOf(body.getOrDefault("custcd", "samjung"));
		String spjangcd = String.valueOf(body.getOrDefault("spjangcd", "ZZ"));

		List<Map<String, Object>> keys = (List<Map<String, Object>>) body.get("keys");
		if (keys == null || keys.isEmpty()) {
			throw new IllegalArgumentException("keys가 비었습니다.");
		}

		p.addValue("custcd", custcd);
		p.addValue("spjangcd", spjangcd);

		StringBuilder cond = new StringBuilder();
		int i = 0;

		for (Map<String, Object> k : keys) {
			String cltcd   = String.valueOf(k.getOrDefault("cltcd", "")).trim();
			String rcvdate = String.valueOf(k.getOrDefault("rcvdate", "")).replaceAll("[^0-9]", "");
			String rcvnum  = String.valueOf(k.getOrDefault("rcvnum", "")).trim();

			if (cltcd.isEmpty() || rcvdate.isEmpty() || rcvnum.isEmpty()) continue;

			if (cond.length() > 0) cond.append(" OR ");
			cond.append("""
            (d.cltcd = :cltcd%d AND d.rcvdate = :rcvdate%d AND d.rcvnum = :rcvnum%d)
        """.formatted(i, i, i));

			p.addValue("cltcd" + i, cltcd);
			p.addValue("rcvdate" + i, rcvdate);
			p.addValue("rcvnum" + i, rcvnum);
			i++;
		}

		if (i == 0) throw new IllegalArgumentException("유효한 key가 없습니다.");

		String sql = """
        SELECT
            d.custcd, d.spjangcd, d.cltcd, d.misdate, d.misnum, d.rcvdate, d.rcvnum, d.rcvseq,
            d.hamt, d.bamt, d.jamt, d.eamt, d.samt, d.damt,
            d.csamt, d.dcamt,
            d.jmar, d.bmar, d.gamt, d.cmar, d.cdmar,
            d.remark
        FROM TB_DA026 d WITH (NOLOCK)
        WHERE d.custcd = :custcd
          AND d.spjangcd = :spjangcd
          AND ( %s )
        ORDER BY d.rcvdate, d.rcvnum, d.rcvseq
    """.formatted(cond.toString());

		return sqlRunner.getRows(sql, p);
	}

	public List<Map<String, Object>> syncClientListFromDz(String trNm, String regNb) {

		// 1) ACTS 미동기화 목록 (TB_XCLIENT)
		List<Map<String, Object>> acts = sqlRunner.getRows("""
			SELECT emcltcd, cltcd, cltnm, saupnum
		 FROM SAMJUNG.dbo.TB_XCLIENT
		 WHERE relyn = 'X' 
		 		AND (emcltcd IS NULL OR LTRIM(RTRIM(emcltcd)) = '')
		 		AND (emcltcd IS NULL OR LTRIM(RTRIM(emcltcd)) = '')
  	""", new MapSqlParameterSource());

		// 2) 더존 거래처조회(회사코드 고정)
		Map<String, Object> dzRes = douzoneClient.callTradeClientList("1000", trNm, regNb);

		// resultCode 체크
		Object rc = dzRes.get("resultCode");
		int resultCode = (rc instanceof Number) ? ((Number) rc).intValue() : Integer.parseInt(String.valueOf(rc));
		if (resultCode != 0) {
			throw new IllegalStateException("더존 api16S11 오류: " + dzRes.get("resultMsg"));
		}

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> dzList = (List<Map<String, Object>>) dzRes.get("resultData");
		if (dzList == null) dzList = Collections.emptyList();

		// 3) 매칭 (regNb=saupnum, trNm=cltnm) 후 trCd -> dzcltcd
		/*for (Map<String, Object> a : acts) {
			String saup = normDigits((String) a.get("saupnum"));
			String name = normName((String) a.get("cltnm"));

			Map<String, Object> matched = null;
			for (Map<String, Object> dz : dzList) {
				String reg = normDigits((String) dz.get("regNb"));
				String nm  = normName((String) dz.get("trNm"));

				if (!saup.isEmpty() && saup.equals(reg) && !name.isEmpty() && name.equals(nm)) {
					matched = dz;
					break;
				}
			}

			if (matched != null) {
				String trCd = String.valueOf(matched.get("trCd"));
				a.put("dzcltcd", trCd);  // ✅ trCd -> dzcltcd
				a.put("matchYn", "Y");
			} else {
				a.put("matchYn", "N");
			}
		}*/
		// 3) 매칭 (UI 수정 없이: 확실한 것만 Y)
		for (Map<String, Object> a : acts) {
			String saup = normDigits((String) a.get("saupnum"));
			String name = normName((String) a.get("cltnm"));

			Map<String, Object> matched = null;

			// 1) 사업자번호로 유일 매칭
			if (!saup.isEmpty()) {
				List<Map<String, Object>> regCands = dzList.stream()
																							 .filter(dz -> saup.equals(normDigits((String) dz.get("regNb"))))
																							 .toList();

				if (regCands.size() == 1) {
					matched = regCands.get(0);
				} else if (regCands.size() > 1 && !name.isEmpty()) {
					// 사업자번호 중복이면 이름까지로 유일해질 때만 매칭
					List<Map<String, Object>> narrowed = regCands.stream()
																								 .filter(dz -> name.equals(normName((String) dz.get("trNm"))))
																								 .toList();
					if (narrowed.size() == 1) matched = narrowed.get(0);
				}
			}
			// 2) 사업자번호 없으면 이름으로 유일 매칭
			else if (!name.isEmpty()) {
				List<Map<String, Object>> nameCands = dzList.stream()
																								.filter(dz -> name.equals(normName((String) dz.get("trNm"))))
																								.toList();

				if (nameCands.size() == 1) {
					matched = nameCands.get(0);
				}
			}

			if (matched != null) {
				a.put("dzcltcd", String.valueOf(matched.get("trCd"))); // 아마란스 코드 칸(현재 UI 기준)
				a.put("matchYn", "Y");
			} else {
				a.put("matchYn", "N");
			}
		}


		return acts;
	}

	private String normDigits(String s) {
		if (s == null) return "";
		return s.replaceAll("[^0-9]", "");
	}
	private String normName(String s) {
		if (s == null) return "";
		return s.trim().replaceAll("\\s+", " ");
	}

	@Transactional
	public void syncClientEmcltcd(List<Map<String, Object>> rows) {

		if (rows == null || rows.isEmpty()) {
			return;
		}

		String updateSql = """
        UPDATE TB_XCLIENT
           SET emcltcd = :emcltcd,
               cltnm   = :cltnm
         WHERE custcd  = 'samjung'
           AND cltcd   = :cltcd
        """;

		for (Map<String, Object> r : rows) {

			String cltcd   = asString(r.get("cltcd"));
			String dzcltcd = asString(r.get("dzcltcd")); // 🔥 이 값이 emcltcd로 저장됨
			String cltnm   = asString(r.get("cltnm"));

			if (cltcd == null || cltcd.isEmpty()) {
				continue;
			}

			MapSqlParameterSource params = new MapSqlParameterSource()
																			 .addValue("cltcd",   cltcd)
																			 .addValue("emcltcd", dzcltcd)
																			 .addValue("cltnm",   cltnm);

			int updated = sqlRunner.execute(updateSql, params);

			// 필요하면 로그
			// log.info("syncClientEmcltcd: cltcd={}, emcltcd={}, updated={}", cltcd, dzcltcd, updated);
		}
	}

}


