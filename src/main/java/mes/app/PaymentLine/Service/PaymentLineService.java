package mes.app.PaymentLine.Service;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class PaymentLineService {
    @Autowired
    SqlRunner sqlRunner;

    //문서코드 그리드 리스트 불러오기
    public List<Map<String, Object>> getPaymentList(Integer personid, String comcd) {
        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        String tenantId = TenantContext.get();
        dicParam.addValue("spjangcd", tenantId);
        dicParam.addValue("perid", personid);

        String sql = """
          select\s
          e.perid as personid,
          a.pernm ,
          e.papercd ,
          c.com_cnam as papernm
          from tb_e063 e
          LEFT JOIN tb_ca510 c ON c.com_cls = '620' AND c.com_code = e.papercd AND c.com_code <> '00'
          LEFT JOIN tb_ja001 a ON a.spjangcd = e.spjangcd AND a.perid = 'p' + e.perid
          where e.spjangcd = :spjangcd
          """;
        if (comcd != null && !comcd.isEmpty()) {
            sql += (" and e.papercd = :comcd ");
            dicParam.addValue("comcd", comcd);
        }
        List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);
        return items;
    }
    // 사원별 결재라인 그리드 리스트 불러오기
    public List<Map<String, Object>> getCheckPaymentList(String personid, String comcd) {
        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        String tenantId = TenantContext.get();
        dicParam.addValue("spjangcd", tenantId);
        dicParam.addValue("personid", String.valueOf(personid));
        dicParam.addValue("papercd", comcd);

        String sql = """
        SELECT
            e.no,
            e.kcperid           AS kcpersonid,
            a.pernm             AS kcpernm,
            c.com_cnam          AS gubunnm,
            e.seq,
            e.remark
        FROM TB_E064 e
        LEFT JOIN tb_ja001 a
            ON a.spjangcd = e.spjangcd
            AND a.perid = 'p' + e.kcperid
        LEFT JOIN tb_ca510 c
            ON c.com_cls = '620' AND c.com_code = e.gubun AND c.com_code <> '00'
        WHERE e.spjangcd = :spjangcd
          AND e.perid = :personid
          AND e.papercd = :papercd
        ORDER BY CAST(e.seq AS INT) ASC
    """;
//        log.info("결재라인현황 더블클릭 SQL: {}", sql);
//        log.info("SQL Parameters: {}", dicParam.getValues());
        List<Map<String, Object>> items = this.sqlRunner.getRows(sql, dicParam);
        return items;
    }
}
