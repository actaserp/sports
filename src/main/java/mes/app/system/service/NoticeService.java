package mes.app.system.service;

import io.micrometer.core.instrument.util.StringUtils;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Service
public class NoticeService {

	@Autowired
	SqlRunner sqlRunner;

	private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

	// 공지사항 목록 조회
	public List<Map<String, Object>> getBoardList(String keyword, String srchStartDt, String srchEndDt) {

		String today = LocalDate.now().format(YYYYMMDD);

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("srchStartDt", srchStartDt);
		paramMap.addValue("srchEndDt", srchEndDt);
		paramMap.addValue("keyword", keyword);
		paramMap.addValue("today", today);

		// A: 공지 (BBSTODATE 설정되고 오늘 이후인 것)
		// B: 일반 게시글 (날짜 범위 내, 공지 제외)
		String sql = """
				with A as (
				    select BBSSEQ as id
				    , BBSSUBJECT as title
				    , BBSDATE as write_date_time
				    , BBSTODATE as notice_end_date
				    , 'Y' as notice_yn
				    from tb_bbsinfo
				    where BBSTODATE is not null
				    and BBSTODATE != ''
				    and BBSTODATE >= :today
				), B as (
				    select B.BBSSEQ as id
				    , B.BBSSUBJECT as title
				    , B.BBSDATE as write_date_time
				    , null as notice_end_date
				    , 'N' as notice_yn
				    from tb_bbsinfo B
				    left join A on A.id = B.BBSSEQ
				    where B.BBSDATE between :srchStartDt and :srchEndDt
				    and A.id is null
				""";

		if (!StringUtils.isEmpty(keyword)) {
			sql += """
					    and (B.BBSSUBJECT like CONCAT('%', :keyword, '%')
					        or B.BBSTEXT like CONCAT('%', :keyword, '%'))
					""";
		}

		sql += """
				)
				select 1 as data_group, id, title, write_date_time, notice_end_date, notice_yn
				from A
				union all
				select 2 as data_group, id, title, write_date_time, notice_end_date, notice_yn
				from B
				order by data_group, write_date_time desc
				""";

		return this.sqlRunner.getRows(sql, paramMap);
	}

	// 공지사항 상세 조회
	public Map<String, Object> getBoardDetail(Integer bbsseq) {

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("bbsseq", bbsseq);

		String sql = """
				select BBSSEQ as id
				, BBSSUBJECT as title
				, BBSTEXT as content
				, BBSTODATE as notice_end_date
				, case when BBSTODATE is not null and BBSTODATE != '' then 'Y' else 'N' end as notice_yn
				from tb_bbsinfo
				where BBSSEQ = :bbsseq
				""";

		return this.sqlRunner.getRow(sql, paramMap);
	}

	// 공지사항 저장 (신규/수정), 저장된 BBSSEQ 반환
	public Integer saveNotice(Integer bbsseq, String subject, String text,
	                          String noticeYn, String noticeEndDate, String userId) {

		String today = LocalDate.now().format(YYYYMMDD);
		boolean isNotice = "Y".equals(noticeYn);

		// notice_end_date 는 yyyy-MM-dd 또는 yyyyMMdd 형태로 올 수 있으므로 정규화
		String bbstodate = null;
		if (isNotice && noticeEndDate != null && !noticeEndDate.isEmpty()) {
			bbstodate = noticeEndDate.replace("-", "");
		}
		String bbsfrdate = isNotice ? today : null;

		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("bbssubject", subject);
		paramMap.addValue("bbstext", text);
		paramMap.addValue("bbsfrdate", bbsfrdate);
		paramMap.addValue("bbstodate", bbstodate);
		paramMap.addValue("userid", userId);

		if (bbsseq == null) {
			// 신규 등록 - MSSQL OUTPUT 절로 생성된 PK 수신
			paramMap.addValue("bbsdate", today);
			String sql = """
					insert into tb_bbsinfo
					    (BBSDATE, BBSSUBJECT, BBSUSER, BBSTEXT, BBSFRDATE, BBSTODATE, INDATEM, INUSERID)
					output INSERTED.BBSSEQ
					values
					    (:bbsdate, :bbssubject, :userid, :bbstext, :bbsfrdate, :bbstodate, GETDATE(), :userid)
					""";
			Map<String, Object> row = this.sqlRunner.getRow(sql, paramMap);
			if (row == null) return null;
			return ((Number) row.get("BBSSEQ")).intValue();
		} else {
			// 수정
			paramMap.addValue("bbsseq", bbsseq);
			String sql = """
					update tb_bbsinfo
					set BBSSUBJECT = :bbssubject
					, BBSTEXT     = :bbstext
					, BBSFRDATE   = :bbsfrdate
					, BBSTODATE   = :bbstodate
					where BBSSEQ = :bbsseq
					""";
			int affected = this.sqlRunner.execute(sql, paramMap);
			return affected > 0 ? bbsseq : null;
		}
	}

	// 현재 활성 공지 조회 (index 팝업용)
	public List<Map<String, Object>> getActiveNotices() {
		String today = LocalDate.now().format(YYYYMMDD);
		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("today", today);

		String sql = """
				select BBSSEQ as id
				, BBSSUBJECT as title
				, BBSTEXT as content
				, BBSTODATE as notice_end_date
				from tb_bbsinfo
				where BBSTODATE is not null
				and BBSTODATE != ''
				and BBSTODATE >= :today
				order by BBSSEQ desc
				""";
		return this.sqlRunner.getRows(sql, paramMap);
	}

	// 공지사항 삭제 (첨부파일 포함)
	public void deleteNotice(Integer bbsseq) {
		MapSqlParameterSource paramMap = new MapSqlParameterSource();
		paramMap.addValue("bbsseq", bbsseq);

		this.sqlRunner.execute("delete from tb_fileinfo where bbsseq = :bbsseq", paramMap);
		this.sqlRunner.execute("delete from tb_bbsinfo where BBSSEQ = :bbsseq", paramMap);
	}
}
