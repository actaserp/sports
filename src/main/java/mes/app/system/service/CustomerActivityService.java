package mes.app.system.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 고객사 활동 이력 (교육 · 요청·문의 · 계약·영업 · 장애·오류).
 *
 * 마스터 DB 의 tb_customer_activity 하나만 다룬다.
 * 승인 · 중지 버튼이 남긴 자동 행(auto_yn = 'Y')은 고칠 수는 있어도 지울 수 없다.
 * 재승인할 때 그 행의 ref(중지 때 끈 계정 목록)를 읽기 때문이다.
 */
@Slf4j
@Service
public class CustomerActivityService {

    @Autowired
    @Qualifier("mainSqlRunner")
    SqlRunner sqlRunner;

    /** 유형 코드 → 이름. 화면도 이 순서로 보여준다. */
    public static final Map<String, String> TYPES = new LinkedHashMap<>();
    /** 처리 상태 코드 → 이름 */
    public static final Map<String, String> STATUSES = new LinkedHashMap<>();
    static {
        TYPES.put("EDU", "교육");
        TYPES.put("REQ", "요청·문의");
        TYPES.put("SALES", "계약·영업");
        TYPES.put("ISSUE", "장애·오류");
        STATUSES.put("OPEN", "접수");
        STATUSES.put("PROG", "진행");
        STATUSES.put("DONE", "완료");
    }

    /** 한 번에 돌려주는 최대 건수. 넘으면 화면에 '조건을 좁혀 주세요' 를 띄운다. */
    public static final int MAX_ROWS = 1000;

    // ─────────────────────────────────────────────────────────
    //  조회
    // ─────────────────────────────────────────────────────────

    /**
     * @param spjangcd 고객사. 비우면 전체
     * @param from,to  활동일 'YYYY-MM-DD'. 비우면 그쪽 끝은 열어 둔다
     * @param types    유형 코드 쉼표 구분. 비우면 전체
     * @param status   '' 전체 / OPEN · PROG · DONE / UNDONE(접수+진행)
     * @param keyword  제목 · 내용 · 참석자 · 담당자 부분일치
     */
    public List<Map<String, Object>> getList(String spjangcd, String from, String to,
                                             String types, String status, String keyword) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("limit", MAX_ROWS + 1);   // 하나 더 읽어서 넘쳤는지 안다

        StringBuilder sql = new StringBuilder("""
                select a.id                                         as id
                     , trim(a.spjangcd)                             as spjangcd
                     , x.spjangnm                                   as spjangnm
                     , a.act_type                                   as act_type
                     , to_char(a.act_date, 'YYYY-MM-DD')            as act_date
                     , a.title                                      as title
                     , a.content                                    as content
                     , a.attendee                                   as attendee
                     , a.manager                                    as manager
                     , a.status                                     as status
                     , to_char(a.due_date, 'YYYY-MM-DD')            as due_date
                     , to_char(a.done_date, 'YYYY-MM-DD')           as done_date
                     , a.auto_yn                                    as auto_yn
                     -- 기한이 지났는데 완료가 아니면 지연
                     , (a.status <> 'DONE' and a.due_date is not null
                        and a.due_date < cast(now() at time zone 'Asia/Seoul' as date)) as overdue
                     , to_char(a."_created" at time zone 'Asia/Seoul', 'YYYY-MM-DD HH24:MI') as created_at
                  from tb_customer_activity a
                  left join tb_xa012 x on x.spjangcd = a.spjangcd
                 where 1 = 1
                """);

        String k = trim(spjangcd);
        if (!k.isEmpty()) {
            p.addValue("k", k);
            sql.append("   and a.spjangcd = :k\n");
        }
        LocalDate f = date(from, "시작일"), t = date(to, "종료일");
        if (f != null && t != null && f.isAfter(t)) {
            throw new IllegalArgumentException("시작일이 종료일보다 늦습니다.");
        }
        // 빈 값을 cast 하면 오류라 값이 있을 때만 조건을 붙인다
        if (f != null) { p.addValue("f", f.toString()); sql.append("   and a.act_date >= cast(:f as date)\n"); }
        if (t != null) { p.addValue("t", t.toString()); sql.append("   and a.act_date <= cast(:t as date)\n"); }

        List<String> ts = new java.util.ArrayList<>();
        for (String c : trim(types).split(",")) {
            if (TYPES.containsKey(c.trim())) ts.add(c.trim());
        }
        if (!ts.isEmpty()) {
            p.addValue("types", ts);
            sql.append("   and a.act_type in (:types)\n");
        }

        String st = trim(status);
        if ("UNDONE".equals(st)) {
            sql.append("   and a.status <> 'DONE'\n");
        } else if (STATUSES.containsKey(st)) {
            p.addValue("st", st);
            sql.append("   and a.status = :st\n");
        }

        String kw = trim(keyword);
        if (!kw.isEmpty()) {
            p.addValue("kw", "%" + kw + "%");
            sql.append("   and (a.title ilike :kw or a.content ilike :kw or a.attendee ilike :kw or a.manager ilike :kw)\n");
        }

        // 미완료가 위로, 그 안에서는 기한이 급한 순, 나머지는 최근 활동 순
        sql.append("""
                 order by case when a.status <> 'DONE' then 0 else 1 end
                        , case when a.status <> 'DONE' then a.due_date end asc nulls last
                        , a.act_date desc, a.id desc
                 limit :limit
                """);
        return sqlRunner.getRows(sql.toString(), p);
    }

    /** 등록 화면의 고객사 선택지 */
    public List<Map<String, Object>> getSites() {
        return sqlRunner.getRows("""
                select trim(spjangcd) as spjangcd, spjangnm, state
                  from tb_xa012
                 order by spjangnm
                """, new MapSqlParameterSource());
    }

    // ─────────────────────────────────────────────────────────
    //  저장 · 삭제
    // ─────────────────────────────────────────────────────────

    /**
     * id 가 없으면 새로 만들고, 있으면 고친다.
     * @return 저장한 행의 id
     */
    public long save(Map<String, Object> in, String actor, Object actorId) {
        long id = parseId(in.get("id"));
        // 요청 본문의 고객사 키는 site_cd 다 (spjangcd 는 보안 인터셉터가 세션 값과 비교한다)
        String spjangcd = trim(str(in.get("site_cd")));
        String type = trim(str(in.get("act_type")));
        String status = trim(str(in.get("status")));
        String title = trim(str(in.get("title")));
        LocalDate actDate = date(str(in.get("act_date")), "활동일");
        LocalDate due = date(str(in.get("due_date")), "기한");
        LocalDate done = date(str(in.get("done_date")), "완료일");

        if (spjangcd.isEmpty()) throw new IllegalArgumentException("고객사를 선택하세요.");
        if (!TYPES.containsKey(type)) throw new IllegalArgumentException("유형을 선택하세요.");
        if (actDate == null) throw new IllegalArgumentException("날짜를 입력하세요.");
        if (title.isEmpty()) throw new IllegalArgumentException("제목을 입력하세요.");
        if (title.length() > 200) throw new IllegalArgumentException("제목은 200자까지 입력할 수 있습니다.");
        if (status.isEmpty()) status = "DONE";
        if (!STATUSES.containsKey(status)) throw new IllegalArgumentException("처리 상태가 올바르지 않습니다.");

        // 완료로 바꿨는데 완료일이 비었으면 오늘, 완료가 아니면 완료일을 비운다
        if ("DONE".equals(status) && done == null) done = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        if (!"DONE".equals(status)) done = null;

        List<Map<String, Object>> site = sqlRunner.getRows(
          "select 1 from tb_xa012 where spjangcd = :k", new MapSqlParameterSource("k", spjangcd));
        if (site.isEmpty()) throw new IllegalArgumentException("고객사를 찾을 수 없습니다: " + spjangcd);

        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("k", spjangcd);
        p.addValue("type", type);
        p.addValue("d", actDate.toString());
        p.addValue("title", title);
        p.addValue("content", nullIfBlank(str(in.get("content"))));
        p.addValue("attendee", cut(nullIfBlank(str(in.get("attendee"))), 200));
        p.addValue("manager", cut(firstNonBlank(str(in.get("manager")), actor), 50));
        p.addValue("status", status);
        p.addValue("due", due == null ? null : due.toString());
        p.addValue("done", done == null ? null : done.toString());
        p.addValue("uid", actorId);

        if (id <= 0) {
            List<Map<String, Object>> r = sqlRunner.getRows("""
                    insert into tb_customer_activity
                           (spjangcd, act_type, act_date, title, content, attendee, manager,
                            status, due_date, done_date, auto_yn, "_created", "_creater_id")
                    values (:k, :type, cast(:d as date), :title, :content, :attendee, :manager,
                            :status, cast(:due as date), cast(:done as date), 'N', now(), :uid)
                    returning id
                    """, p);
            return ((Number) r.get(0).get("id")).longValue();
        }

        p.addValue("id", id);
        Map<String, Object> cur = row(id);
        // 자동 행은 고객사 · 유형 · 제목을 바꾸지 못하게 한다. 재승인이 이 행의 ref 를 읽고,
        // 이력을 볼 때 '승인 · 재승인 · 중지' 가 그대로 남아야 한다. 내용 · 날짜 · 담당자는 고칠 수 있다.
        if ("Y".equals(str(cur.get("auto_yn")))) {
            p.addValue("k", trim(str(cur.get("spjangcd"))));
            p.addValue("type", str(cur.get("act_type")));
            p.addValue("title", str(cur.get("title")));
        }
        sqlRunner.execute("""
                update tb_customer_activity
                   set spjangcd = :k, act_type = :type, act_date = cast(:d as date),
                       title = :title, content = :content, attendee = :attendee, manager = :manager,
                       status = :status, due_date = cast(:due as date), done_date = cast(:done as date),
                       "_modified" = now(), "_modifier_id" = :uid
                 where id = :id
                """, p);
        return id;
    }

    public void delete(long id) {
        Map<String, Object> cur = row(id);
        if ("Y".equals(str(cur.get("auto_yn")))) {
            throw new IllegalArgumentException("승인 · 중지 때 자동으로 남은 이력은 지울 수 없습니다.");
        }
        sqlRunner.execute("delete from tb_customer_activity where id = :id and auto_yn = 'N'",
          new MapSqlParameterSource("id", id));
    }

    // ─────────────────────────────────────────────────────────
    //  내부
    // ─────────────────────────────────────────────────────────

    private Map<String, Object> row(long id) {
        List<Map<String, Object>> r = sqlRunner.getRows(
          "select id, trim(spjangcd) as spjangcd, act_type, title, auto_yn from tb_customer_activity where id = :id",
          new MapSqlParameterSource("id", id));
        if (r.isEmpty()) throw new IllegalArgumentException("이력을 찾을 수 없습니다. 이미 지워졌을 수 있습니다.");
        return r.get(0);
    }

    private static LocalDate date(String s, String what) {
        String v = trim(s);
        if (v.isEmpty()) return null;
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(what + " 형식이 올바르지 않습니다. (예: 2026-11-04)");
        }
    }

    private static long parseId(Object o) {
        if (o == null || String.valueOf(o).isBlank()) return 0;
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("잘못된 이력 번호입니다.");
        }
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static String trim(String s) { return s == null ? "" : s.trim(); }
    private static String nullIfBlank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a.trim() : (b == null || b.isBlank() ? null : b.trim());
    }
    private static String cut(String s, int n) { return s == null || s.length() <= n ? s : s.substring(0, n); }
}