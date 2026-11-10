package mes.app.system.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.entity.User;
import mes.domain.entity.UserGroup;
import mes.domain.repository.Tb_xa012Repository;
import mes.domain.repository.UserGroupRepository;
import mes.domain.repository.UserRepository;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 고객사현황 : 목록 · 승인 · 중지.
 *
 * 전부 마스터 DB 다. tb_xa012 의 spjangcd 에는 사업장 코드가 아니라 db_key 값이 들어 있다.
 *
 * 승인 · 중지는 tb_customer_activity 에 '계약·영업' 이력으로 자동 기록한다(auto_yn = 'Y').
 * tb_xa012.approvedate 에는 마지막 승인일만 남고, 재승인 · 중지까지의 전체 흐름은 이력에 남는다.
 *
 * tb_xa012 의 상태는 JPA 가 아니라 잠금을 건 JDBC 한 문장으로 바꾼다.
 * 같은 고객사에 승인 요청이 겹치거나 버튼을 두 번 눌러도 한 번만 처리된다.
 *
 * 중지하면 그 사업장 계정을 비활성으로 바꿔 로그인을 막는다.
 * 그때 바꾼 계정 id 를 이력의 ref 에 적어 두었다가, 재승인할 때 그 계정만 되살린다.
 * 중지 전부터 일부러 꺼 둔 계정이 재승인으로 살아나지 않게 하기 위해서다.
 */
@Slf4j
@Service
public class CustomerCurrentService {

    @Autowired
    @Qualifier("mainSqlRunner")
    SqlRunner sqlRunner;

    /** 예전 코드와의 호환용으로 남겨 둔다. tb_xa012 는 이제 잠금이 필요해 JDBC 로만 바꾼다. */
    @Autowired
    Tb_xa012Repository xa012Repository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    UserGroupRepository userGroupRepository;

    /** 상태값. 기존 데이터와 맞춘다. */
    public static final String STATE_APPROVED = "O";
    public static final String STATE_STOPPED  = "중지";

    /** 내부 사업장(엑타스). 중지하면 관리자도 잠기므로 막는다. */
    public static final String INTERNAL_DB_KEY = "ZZ";

    /** 승인 때 기본으로 여는 시스템 메뉴 */
    public static final List<String> DEFAULT_MENUS = List.of(
            "wm_user_group",
            "wm_amount_used",
            "wm_user",
            "wm_user_group_menu",
            "wm_storyboard_config",
            "wm_prop_master",
            "wm_login_log",
            "wm_menu_log"
    );

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // ─────────────────────────────────────────────────────────
    //  목록
    // ─────────────────────────────────────────────────────────

    /**
     * @param srchStartDt · srchEndDt 가입일 범위 'YYYY-MM-DD'. 둘 다 있을 때만 건다 — 비우면 전체.
     * @param keyword     회원명 · 사업장코드 · 등록번호 부분일치. null 이어도 된다.
     * @param state       '' 전체 / WAIT 승인 대기 / O 승인 / STOP 중지
     */
    public List<Map<String, Object>> getCustomerList(String srchStartDt, String srchEndDt,
                                                     String keyword, String state) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("approved", STATE_APPROVED);
        p.addValue("stopped", STATE_STOPPED);

        // 컬럼을 명시한다. select * 로 bill_plans 를 붙이면 id · name 같은 같은 이름 컬럼이 겹친다.
        StringBuilder sql = new StringBuilder("""
                select trim(x.spjangcd)          as spjangcd
                     , x.spjangnm                as spjangnm
                     , x.saupnum                 as saupnum
                     , x.prenm                   as prenm
                     , x.tel1                    as tel1
                     , x.emailadres              as emailadres
                     , b.name                    as plan_nm
                     , x.subscribeunit           as subscribeunit
                     , x.billingdate             as billingdate
                     , x.expirationdate          as expirationdate
                     , x.state                   as state
                     , x.subscriptiondate        as subscriptiondate
                     , x.approvedate             as approvedate
                     , coalesce(u.user_cnt, 0)   as user_cnt
                     , coalesce(u.active_cnt, 0) as active_cnt
                     , to_char(lu.last_use at time zone 'Asia/Seoul', 'YYYY-MM-DD') as last_use
                     , coalesce(ac.open_cnt, 0)  as open_cnt
                     , to_char(ac.last_act, 'YYYY-MM-DD')                         as last_act
                  from tb_xa012 x
                  left join bill_plans b on b.id = x.bill_plans_id
                  -- 계정 수 : admin · superuser 는 빼고 센다
                  left join ( select db_key
                                   , count(*)                         as user_cnt
                                   , count(*) filter (where is_active) as active_cnt
                                from auth_user
                               where db_key is not null
                                 and username <> 'admin'
                                 and is_superuser = false
                               group by db_key ) u on u.db_key = trim(x.spjangcd)
                  -- 마지막 사용 : 개발자 계정(…_admin)은 빼고 실무자 기준으로 본다
                  left join ( select au.db_key, max(m._created) as last_use
                                from menu_use_log m
                                join auth_user au on au.id = m."User_id"
                               where au.username <> 'admin'
                                 and au.is_superuser = false
                                 and au.username not like '%\\_admin' escape '\\'
                               group by au.db_key ) lu on lu.db_key = trim(x.spjangcd)
                  -- 활동 이력 : 미완료 건수 · 마지막 활동일
                  left join ( select spjangcd
                                   , count(*) filter (where status <> 'DONE') as open_cnt
                                   , max(act_date)                           as last_act
                                from tb_customer_activity
                               group by spjangcd ) ac on ac.spjangcd = x.spjangcd
                 where 1 = 1
                """);

        String st = digits(srchStartDt), en = digits(srchEndDt);
        if (st.length() == 8 && en.length() == 8) {
            p.addValue("st", st);
            p.addValue("en", en);
            sql.append("   and x.subscriptiondate between :st and :en\n");
        }

        String kw = keyword == null ? "" : keyword.trim();
        if (!kw.isEmpty()) {
            p.addValue("kw", "%" + kw + "%");
            sql.append("   and (x.spjangnm ilike :kw or x.spjangcd ilike :kw or x.saupnum like :kw)\n");
        }

        String sf = state == null ? "" : state.trim();
        switch (sf) {
            case "O"    -> sql.append("   and x.state = :approved\n");
            case "STOP" -> sql.append("   and x.state = :stopped\n");
            // 승인도 중지도 아니면 승인 대기로 본다 (가입 직후 상태값이 비어 있거나 다른 값)
            case "WAIT" -> sql.append("   and coalesce(trim(x.state), '') not in (:approved, :stopped)\n");
            default     -> { }
        }

        // 승인 대기가 위로, 그다음 최근 가입 순
        sql.append("""
                 order by case when coalesce(trim(x.state), '') not in (:approved, :stopped) then 0 else 1 end
                        , x.subscriptiondate desc nulls last
                        , x.spjangnm
                """);

        return sqlRunner.getRows(sql.toString(), p);
    }

    // ─────────────────────────────────────────────────────────
    //  승인
    // ─────────────────────────────────────────────────────────

    /**
     * @param actor 처리한 계정(이력의 담당자). null 이어도 된다.
     * @return 실제로 처리한 건수 (이미 승인된 곳은 건너뛴다)
     */
    @Transactional
    public int batchApprove(List<String> spjangcds, String actor, Object actorId) {
        int done = 0;
        String today = LocalDate.now(KST).format(YMD);

        for (String spjangcd : spjangcds) {

            // 1. tb_xa012 : 승인 상태 · 청구 조건 · 승인일. 만료일(중지일)은 비운다 — 중지 이력은 활동 이력에 남는다.
            //    행을 잠그고(for update) '아직 승인이 아닐 때만' 바꾸는 한 문장이다.
            //    같은 고객사 승인 요청이 겹쳐도 하나만 통과하므로 그룹 · 메뉴가 두 벌 생기지 않는다.
            //    바뀌기 전 상태를 돌려받아 재승인인지 안다.
            List<Map<String, Object>> changed = sqlRunner.getRows("""
                    update tb_xa012 x
                       set state = :approved, expirationdate = null, subscribeunit = 'month',
                           billingdate = 25, approvedate = :d
                      from ( select spjangcd, state as old_state
                               from tb_xa012 where spjangcd = :k for update ) o
                     where x.spjangcd = o.spjangcd
                       and o.old_state is distinct from :approved
                    returning o.old_state as old_state
                    """, new MapSqlParameterSource()
                    .addValue("approved", STATE_APPROVED).addValue("d", today).addValue("k", spjangcd));

            if (changed.isEmpty()) {
                requireSite(spjangcd);   // 없는 코드면 여기서 예외, 있으면 이미 승인된 것
                log.info("이미 승인처리된 사업체입니다: {}", spjangcd);
                continue;
            }
            boolean wasStopped = STATE_STOPPED.equals(changed.get(0).get("old_state"));

            boolean isNew = userGroupRepository.findBySpjangcd(spjangcd).isEmpty();
            String title;
            String note;

            if (isNew) {
                openNewSite(spjangcd);
                title = "승인";
                note = "최초 승인 · 기본 메뉴 " + DEFAULT_MENUS.size() + "개 · 마스터 그룹 생성";
            } else if (wasStopped) {
                int n = restoreUsers(spjangcd);
                title = "재승인";
                note = "중지 해제 · 계정 " + n + "개 다시 활성";
            } else {
                // 사용자그룹은 있는데 상태만 승인이 아니던 경우 (예전 데이터)
                title = "승인";
                note = "상태만 승인으로 변경 (기존 사용자그룹 있음)";
            }

            logContract(spjangcd, title, note, null, actor, actorId);
            done++;
        }
        return done;
    }

    /** 처음 승인하는 사업장 : 계정 활성 · 마스터 그룹 · 프로필 · 기본 메뉴 · 권한 */
    private void openNewSite(String spjangcd) {
        // 2. auth_user : 가입 때 만든 계정을 활성으로
        User user = userRepository.findByDbKey(spjangcd)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + spjangcd));
        user.setActive(true);
        user.setSpjangcd(INTERNAL_DB_KEY);   // 기존 동작 유지 — 실제 구분은 db_key 로만 한다
        user.setDbKey(spjangcd);
        userRepository.save(user);

        // 3. user_group
        UserGroup userGroup = new UserGroup();
        userGroup.setCode("Master");
        userGroup.setName("마스터");
        userGroup.setDescription("마스터 그룹");
        userGroup.setGmenu("wm_user_group");
        userGroup.setSpjangcd(spjangcd);
        userGroup.set_audit(user);
        UserGroup savedUserGroup = userGroupRepository.save(userGroup);

        // 4. user_profile
        MapSqlParameterSource profileParam = new MapSqlParameterSource();
        profileParam.addValue("user_id", user.getId());
        profileParam.addValue("lang_code", "ko");
        profileParam.addValue("name", user.getUsername());
        profileParam.addValue("group_id", savedUserGroup.getId());
        profileParam.addValue("spjangcd", spjangcd);
        sqlRunner.execute("""
                INSERT INTO user_profile("_created", "_creater_id", "User_id", "lang_code", "Name",
                                         "Factory_id", "Depart_id", "UserGroup_id", "spjangcd")
                VALUES(now(), :user_id, :user_id, :lang_code, :name, null, null, :group_id, :spjangcd)
                """, profileParam);

        // 5. tenant_menu · 6. user_group_menu
        for (String menuCode : DEFAULT_MENUS) {
            sqlRunner.execute("""
                    INSERT INTO tenant_menu (spjangcd, menu_code)
                    VALUES (:spjangcd, :menu_code)
                    ON CONFLICT (spjangcd, menu_code) DO NOTHING
                    """, new MapSqlParameterSource()
                    .addValue("spjangcd", spjangcd)
                    .addValue("menu_code", menuCode));

            sqlRunner.execute("""
                    INSERT INTO user_group_menu("UserGroup_id", "MenuCode", "AuthCode", _creater_id, _created, spjangcd)
                    VALUES(:group_id, :menu_code, 'RW', :user_id, now(), :spjangcd)
                    """, new MapSqlParameterSource()
                    .addValue("spjangcd", spjangcd)
                    .addValue("menu_code", menuCode)
                    .addValue("group_id", savedUserGroup.getId())
                    .addValue("user_id", user.getId()));
        }
    }

    /**
     * 재승인 : 직전 중지 때 비활성으로 바꾼 계정만 되살린다.
     * 그 기록이 없으면(이 기능 이전에 중지된 곳) 그 사업장 계정을 모두 되살린다.
     * admin · superuser 는 처음부터 건드리지 않는다.
     */
    private int restoreUsers(String spjangcd) {
        MapSqlParameterSource k = new MapSqlParameterSource("k", spjangcd);
        List<Map<String, Object>> last = sqlRunner.getRows("""
                select ref
                  from tb_customer_activity
                 where spjangcd = :k and auto_yn = 'Y' and ref is not null
                 order by "_created" desc, id desc
                 limit 1
                """, k);

        List<Integer> ids = new ArrayList<>();
        // 중지 자동 행만 ref 를 가진다(끈 계정이 없으면 빈 문자열). 승인 행은 ref 가 null 이다.
        boolean recorded = !last.isEmpty() && last.get(0).get("ref") != null;

        if (recorded) {
            // 직전 중지 때 끈 계정. 빈 문자열이면 그때 활성 계정이 없었던 것이다.
            for (String t : String.valueOf(last.get(0).get("ref")).split(",")) {
                try {
                    if (!t.isBlank()) ids.add(Integer.parseInt(t.trim()));
                } catch (NumberFormatException ignore) {
                    // 손으로 고친 값이면 건너뛴다
                }
            }
            if (ids.isEmpty()) return 0;
            // 그 사이 다른 사업장으로 옮겼거나 권한이 바뀐 계정은 건드리지 않는다
            ids = idsOf(sqlRunner.getRows("""
                    select id from auth_user
                     where id in (:ids) and db_key = :k
                       and username <> 'admin' and is_superuser = false
                    """, new MapSqlParameterSource().addValue("ids", ids).addValue("k", spjangcd)));
        } else {
            ids = idsOf(sqlRunner.getRows("""
                    select id from auth_user
                     where db_key = :k and not is_active
                       and username <> 'admin' and is_superuser = false
                    """, k));
        }

        if (!ids.isEmpty()) {
            sqlRunner.execute("update auth_user set is_active = true where id in (:ids)",
                    new MapSqlParameterSource("ids", ids));
        }
        return ids.size();
    }

    private static List<Integer> idsOf(List<Map<String, Object>> rows) {
        List<Integer> out = new ArrayList<>();
        for (Map<String, Object> r : rows) out.add(((Number) r.get("id")).intValue());
        return out;
    }

    // ─────────────────────────────────────────────────────────
    //  중지
    // ─────────────────────────────────────────────────────────

    /** @return 실제로 처리한 건수 (이미 중지된 곳은 건너뛴다) */
    @Transactional
    public int btnStop(List<String> spjangcds, String actor, Object actorId) {
        int done = 0;
        String today = LocalDate.now(KST).format(YMD);

        for (String spjangcd : spjangcds) {
            if (INTERNAL_DB_KEY.equals(spjangcd == null ? null : spjangcd.trim())) {
                throw new IllegalArgumentException("내부 사업장(" + INTERNAL_DB_KEY + ")은 중지할 수 없습니다.");
            }

            // 1. tb_xa012 : 중지 상태 · 중지일. 승인과 같이 잠그고 '아직 중지가 아닐 때만' 바꾼다.
            List<Map<String, Object>> changed = sqlRunner.getRows("""
                    update tb_xa012 x
                       set state = :stopped, expirationdate = :d
                      from ( select spjangcd, state as old_state
                               from tb_xa012 where spjangcd = :k for update ) o
                     where x.spjangcd = o.spjangcd
                       and o.old_state is distinct from :stopped
                    returning o.old_state as old_state
                    """, new MapSqlParameterSource()
                    .addValue("stopped", STATE_STOPPED).addValue("d", today).addValue("k", spjangcd));

            if (changed.isEmpty()) {
                requireSite(spjangcd);
                log.info("이미 중지된 사업체입니다: {}", spjangcd);
                continue;
            }

            // 2. 로그인 차단 : 지금 활성인 계정만 끄고, 끈 id 를 기록해 둔다
            MapSqlParameterSource k = new MapSqlParameterSource("k", spjangcd);
            List<Map<String, Object>> active = sqlRunner.getRows("""
                    select id from auth_user
                     where db_key = :k and is_active
                       and username <> 'admin' and is_superuser = false
                     order by id
                    """, k);
            List<Integer> ids = idsOf(active);

            if (!ids.isEmpty()) {
                sqlRunner.execute("update auth_user set is_active = false where id in (:ids)",
                        new MapSqlParameterSource("ids", ids));
            }

            // 3. 이력 : ref 에 끈 계정 id 를 남긴다 (빈 문자열 = 끈 계정 없음)
            StringBuilder ref = new StringBuilder();
            for (Integer id : ids) {
                if (ref.length() > 0) ref.append(',');
                ref.append(id);
            }
            logContract(spjangcd, "중지", "계약 중지 · 계정 " + ids.size() + "개 로그인 차단",
                    ref.toString(), actor, actorId);
            done++;
        }
        return done;
    }

    // ─────────────────────────────────────────────────────────
    //  내부
    // ─────────────────────────────────────────────────────────

    private void requireSite(String spjangcd) {
        List<Map<String, Object>> r = sqlRunner.getRows("select 1 from tb_xa012 where spjangcd = :k",
                new MapSqlParameterSource("k", spjangcd));
        if (r.isEmpty()) throw new RuntimeException("사업체를 찾을 수 없습니다: " + spjangcd);
    }

    /** 승인 · 중지 자동 이력 (계약·영업, 완료, auto_yn = 'Y') */
    private void logContract(String spjangcd, String title, String content, String ref,
                             String actor, Object actorId) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("k", spjangcd);
        p.addValue("title", title);
        p.addValue("content", content);
        p.addValue("ref", ref);
        p.addValue("manager", actor == null ? null : cut(actor, 50));
        p.addValue("uid", actorId);
        sqlRunner.execute("""
                insert into tb_customer_activity
                       (spjangcd, act_type, act_date, title, content, manager,
                        status, done_date, auto_yn, ref, "_created", "_creater_id")
                values (:k, 'SALES', cast(now() at time zone 'Asia/Seoul' as date), :title, :content, :manager,
                        'DONE', cast(now() at time zone 'Asia/Seoul' as date), 'Y', :ref, now(), :uid)
                """, p);
    }

    private static String digits(String s) {
        return s == null ? "" : s.replaceAll("[^0-9]", "");
    }

    private static String cut(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
