package mes.app.usage.service;

import lombok.extern.slf4j.Slf4j;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * 사업장별 사용량 조회.
 *
 * 다루는 테이블이 전부 마스터 DB(PostgreSQL)에 있다.
 *   login_log · menu_use_log · auth_user · tenant_menu · tb_xa012
 * 그래서 mainSqlRunner 로만 나간다. 테넌트 DB 는 보지 않는다.
 *
 * 사업장 구분은 auth_user.db_key 로만 한다.
 * login_log.spjangcd · auth_user.spjangcd 는 기본값이 'ZZ' 라 구분에 쓸 수 없다.
 * tenant_menu.spjangcd 에는 사업장 코드가 아니라 db_key 값이 들어 있다.
 *
 * 시각은 timestamptz 로 쌓이므로 월 경계는 한국 시간 기준으로 자른다.
 */
@Slf4j
@Service
public class TenantUsageService {

	@Autowired
	@Qualifier("mainSqlRunner")
	SqlRunner sqlRunner;

	/**
	 * 업무 사용으로 치지 않는 설정·관리 메뉴.
	 *
	 * 이 메뉴만 쓴 사업장은 '세팅만 하고 실사용은 아직' 인 상태다.
	 * 접두어(wm_)로 거르지 않는 이유는 wm_payslip 처럼 업무 메뉴도 wm_ 로 시작하기 때문이다.
	 * 메뉴가 추가되면 여기에 넣는다.
	 */
	public static final List<String> SYSTEM_MENUS = List.of(
		"wm_user", "wm_user_group", "wm_user_group_menu", "wm_workplace",
		"wm_login_log", "wm_menu_log", "wm_system_log",
		"wm_code", "wm_preferences", "wm_company", "wm_company_management",
		"wm_api_service",
		// CustomerCurrentService.batchApprove 가 개설 때 기본으로 넣는 메뉴 중 나머지
		"wm_amount_used", "wm_storyboard_config", "wm_prop_master"
	);

	/**
	 * 사업장별 개발자 계정 판정. 개설할 때 만든 'kya_admin', 'san_admin' 같은 계정이다.
	 * LIKE 에서 '_' 는 한 글자 와일드카드라 '\_' 로 막는다.
	 * 고객이 직접 '…_admin' 계정을 만들면 그것도 빠지므로, 규칙이 바뀌면 여기만 고친다.
	 */
	public static final String DEV_ACCOUNT_LIKE = "%\\_admin";

	/** 한 번에 조회할 수 있는 최대 개월 수. 월이 열로 펼쳐지므로 화면 폭을 넘지 않게 막는다. */
	private static final int MAX_MONTHS = 24;

	private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	// ─────────────────────────────────────────────────────────
	//  사업장 목록 : 조회 조건 셀렉트 + 요약 표의 행 목록
	//  사용 기록이 없는 사업장도 '미사용' 으로 보여야 하므로 로그가 아니라 계정·허용메뉴에서 뽑는다.
	// ─────────────────────────────────────────────────────────
	public List<Map<String, Object>> getSites() {
		List<Map<String, Object>> sites = sqlRunner.getRows("""
				select k.db_key                       as db_key
				     , coalesce(t.allowed_cnt, 0)     as allowed_cnt
				     , coalesce(a.user_cnt, 0)        as user_cnt
				     , coalesce(a.dev_cnt, 0)         as dev_cnt
				  from ( select db_key   from auth_user   where db_key   is not null
				         union
				         select spjangcd from tenant_menu where spjangcd is not null ) k
				  left join ( select spjangcd, count(distinct menu_code) as allowed_cnt
				                from tenant_menu
				               group by spjangcd ) t on t.spjangcd = k.db_key
				  -- user_cnt 는 개발자 계정을 뺀 수, dev_cnt 는 개발자 계정 수. 화면이 체크에 따라 더한다.
				  left join ( select db_key
				                   , count(*) filter (where username not like :devLike escape '\\') as user_cnt
				                   , count(*) filter (where username     like :devLike escape '\\') as dev_cnt
				                from auth_user
				               where is_active
				                 and username <> 'admin'
				                 and is_superuser = false
				               group by db_key ) a on a.db_key = k.db_key
				 order by k.db_key
				""", new MapSqlParameterSource("devLike", DEV_ACCOUNT_LIKE));

		// 사업장명 · 계약 정보는 따로 붙인다. 조회가 실패해도 목록·허용메뉴·계정 수는 그대로 나가야 한다.
		Map<String, Map<String, String>> info = getSiteInfo();
		for (Map<String, Object> s : sites) {
			Map<String, String> i = info.getOrDefault(String.valueOf(s.get("db_key")), Map.of());
			s.put("spjangnm", i.getOrDefault("spjangnm", ""));
			s.put("plan", i.getOrDefault("plan", ""));
			s.put("state", i.getOrDefault("state", ""));
			s.put("join_at", ymd(i.get("subscriptiondate")));
			s.put("exp_at", ymd(i.get("expirationdate")));
		}
		return sites;
	}

	/**
	 * db_key → 사업장 정보 (spjangnm, plan, state, subscriptiondate, expirationdate). 실패하면 빈 맵.
	 *
	 * 마스터 tb_xa012 에는 db_key 컬럼이 없다. 다른 마스터 테이블처럼
	 * spjangcd 컬럼에 db_key 값(4Z, ZZ …)이 들어 있으므로 그 컬럼으로 잇는다.
	 *
	 * 계약 정보는 고객사현황(CustomerCurrentService)과 같은 곳에서 읽는다.
	 *   subscriptiondate : 가입일 (YYYYMMDD)
	 *   state            : 'O' 승인 / '중지'
	 *   expirationdate   : 중지하면 그날, 승인하면 비운다 (YYYYMMDD)
	 *   bill_plans.name  : 요금제 (Premium, Standard …)
	 * 요금제 조인이 실패하면 이름만이라도 읽는다.
	 */
	private Map<String, Map<String, String>> getSiteInfo() {
		Map<String, Map<String, String>> out = new java.util.HashMap<>();
		List<Map<String, Object>> rows;
		try {
			rows = sqlRunner.getRows("""
					select trim(x.spjangcd)   as db_key
					     , trim(x.spjangnm)   as spjangnm
					     , trim(x.state)      as state
					     , trim(x.subscriptiondate) as subscriptiondate
					     , trim(x.expirationdate)   as expirationdate
					     , b.name             as plan
					  from tb_xa012 x
					  left join bill_plans b on b.id = x.bill_plans_id
					 where x.spjangcd is not null
					""", new MapSqlParameterSource());
		} catch (Exception e) {
			log.warn("[Usage] 계약 정보 조회 실패 — 사업장명만 읽습니다: {}", e.getMessage());
			try {
				rows = sqlRunner.getRows("""
						select trim(spjangcd) as db_key, trim(spjangnm) as spjangnm
						  from tb_xa012 where spjangcd is not null
						""", new MapSqlParameterSource());
			} catch (Exception e2) {
				log.warn("[Usage] 사업장명 조회 실패 — 이름 없이 표시합니다: {}", e2.getMessage());
				return out;
			}
		}
		for (Map<String, Object> r : rows) {
			Object k = r.get("db_key");
			if (k == null) continue;
			Map<String, String> m = new java.util.HashMap<>();
			for (String c : List.of("spjangnm", "state", "subscriptiondate", "expirationdate", "plan")) {
				Object v = r.get(c);
				m.put(c, v == null ? "" : String.valueOf(v).trim());
			}
			// 한 코드에 여러 행이면 이름만 합친다
			Map<String, String> prev = out.get(String.valueOf(k).trim());
			if (prev != null && !prev.get("spjangnm").equals(m.get("spjangnm"))) {
				m.put("spjangnm", prev.get("spjangnm") + ", " + m.get("spjangnm"));
			}
			out.put(String.valueOf(k).trim(), m);
		}
		return out;
	}

	/**
	 * 승인일. batchApprove 는 승인 날짜를 따로 남기지 않는다.
	 * 승인할 때 그 사업장의 user_group(마스터 그룹)을 처음 만들므로, 그 생성 시각을 승인일로 본다.
	 * 실패하면 빈 맵 — 화면은 가입일을 기준으로 대신 센다.
	 */
	private Map<String, String> getApprovedAt() {
		Map<String, String> out = new java.util.HashMap<>();
		try {
			List<Map<String, Object>> rows = sqlRunner.getRows("""
					select trim(spjangcd) as db_key
					     , to_char(min(_created) at time zone 'Asia/Seoul', 'YYYY-MM-DD') as approved_at
					  from user_group
					 where spjangcd is not null
					 group by trim(spjangcd)
					""", new MapSqlParameterSource());
			for (Map<String, Object> r : rows) {
				if (r.get("db_key") != null && r.get("approved_at") != null) {
					out.put(String.valueOf(r.get("db_key")), String.valueOf(r.get("approved_at")));
				}
			}
		} catch (Exception e) {
			log.warn("[Usage] 승인일 조회 실패 — 가입일 기준으로 표시합니다: {}", e.getMessage());
		}
		return out;
	}

	/** 'YYYYMMDD' → 'YYYY-MM-DD'. 형식이 다르면 그대로, 비었으면 "" */
	private static String ymd(String v) {
		if (v == null) return "";
		String t = v.trim();
		return t.matches("\\d{8}") ? t.substring(0, 4) + "-" + t.substring(4, 6) + "-" + t.substring(6) : t;
	}

	// ─────────────────────────────────────────────────────────
	//  요약 : 사업장 × 월
	// ─────────────────────────────────────────────────────────
	public List<Map<String, Object>> getMonthly(String from, String to, String dbKey, boolean exAdmin, boolean exDev) {
		MapSqlParameterSource p = period(from, to, exAdmin, exDev);
		p.addValue("dbKey", dbKey == null ? "" : dbKey.trim());

		return sqlRunner.getRows("""
				with u as (
				    select u.id, u.db_key
				      from auth_user u
				     where u.db_key is not null
				       and (:dbKey = '' or u.db_key = :dbKey)
				       and (:exAdmin = 'N' or (u.username <> 'admin' and u.is_superuser = false))
				       and (:exDev   = 'N' or u.username not like :devLike escape '\\')
				), lg as (
				    select u.db_key
				         , to_char(l._created at time zone 'Asia/Seoul', 'YYYY-MM') as ym
				         , count(*)                    as login_cnt
				         , count(distinct l."User_id") as login_users
				      from login_log l
				      join u on u.id = l."User_id"
				     where l."Type" = 'login'
				       and l._created >= cast(:fromDt as timestamp) at time zone 'Asia/Seoul'
				       and l._created <  cast(:toDt   as timestamp) at time zone 'Asia/Seoul'
				     group by 1, 2
				), mu as (
				    select u.db_key
				         , to_char(m._created at time zone 'Asia/Seoul', 'YYYY-MM') as ym
				         , count(*)                                                    as menu_cnt
				         , count(*) filter (where m."MenuCode" not in (:sysMenus))     as work_cnt
				         , count(distinct m."User_id")                                 as menu_users
				      from menu_use_log m
				      join u on u.id = m."User_id"
				     where m._created >= cast(:fromDt as timestamp) at time zone 'Asia/Seoul'
				       and m._created <  cast(:toDt   as timestamp) at time zone 'Asia/Seoul'
				     group by 1, 2
				)
				select coalesce(lg.db_key, mu.db_key)  as db_key
				     , coalesce(lg.ym, mu.ym)          as ym
				     , coalesce(lg.login_cnt, 0)       as login_cnt
				     , coalesce(lg.login_users, 0)     as login_users
				     , coalesce(mu.menu_cnt, 0)        as menu_cnt
				     , coalesce(mu.work_cnt, 0)        as work_cnt
				     , coalesce(mu.menu_users, 0)      as menu_users
				  from lg
				  full outer join mu on mu.db_key = lg.db_key and mu.ym = lg.ym
				 order by 1, 2
				""", p);
	}

	// ─────────────────────────────────────────────────────────
	//  상세 : 한 사업장의 메뉴별 사용
	//  허용 메뉴(tenant_menu)는 0건이어도 나오고,
	//  허용 목록 밖에서 접속한 메뉴도 allowed = false 로 함께 나온다.
	// ─────────────────────────────────────────────────────────
	public List<Map<String, Object>> getMenus(String from, String to, String dbKey, boolean exAdmin, boolean exDev) {
		MapSqlParameterSource p = period(from, to, exAdmin, exDev);
		p.addValue("dbKey", requireKey(dbKey));

		return sqlRunner.getRows("""
				with u as (
				    select u.id
				      from auth_user u
				     where u.db_key = :dbKey
				       and (:exAdmin = 'N' or (u.username <> 'admin' and u.is_superuser = false))
				       and (:exDev   = 'N' or u.username not like :devLike escape '\\')
				), mu as (
				    select m."MenuCode"                as menu_code
				         , count(*)                    as cnt
				         , count(distinct m."User_id") as users
				         , max(m._created)             as last_at
				      from menu_use_log m
				      join u on u.id = m."User_id"
				     where m._created >= cast(:fromDt as timestamp) at time zone 'Asia/Seoul'
				       and m._created <  cast(:toDt   as timestamp) at time zone 'Asia/Seoul'
				     group by m."MenuCode"
				), tm as (
				    select distinct menu_code
				      from tenant_menu
				     where spjangcd = :dbKey
				)
				select x.menu_code
				     , coalesce(mi."MenuName", '')                             as menu_nm
				     , x.allowed, x.is_sys, x.cnt, x.users, x.last_at
				  from (
				    select coalesce(tm.menu_code, mu.menu_code)                    as menu_code
				         , (tm.menu_code is not null)                              as allowed
				         , (coalesce(tm.menu_code, mu.menu_code) in (:sysMenus))   as is_sys
				         , coalesce(mu.cnt, 0)                                     as cnt
				         , coalesce(mu.users, 0)                                   as users
				         , to_char(mu.last_at at time zone 'Asia/Seoul', 'YYYY-MM-DD HH24:MI') as last_at
				      from tm
				      full outer join mu on mu.menu_code = tm.menu_code
				  ) x
				  -- menu_item 에 같은 코드가 사업장별로 여러 행 있을 수 있다.
				  -- 이 사업장 것을 먼저, 없으면 아무 행이나 하나 쓴다.
				  left join lateral (
				    select m."MenuName"
				      from menu_item m
				     where m."MenuCode" = x.menu_code
				     order by (m.spjangcd = :dbKey) desc nulls last, m._order nulls last
				     limit 1
				  ) mi on true
				 order by x.cnt desc, coalesce(mi."MenuName", x.menu_code)
				""", p);
	}

	// ─────────────────────────────────────────────────────────
	//  상세 : 한 사업장의 계정별 사용
	//  기간 중 기록이 없는 계정도 0 으로 나온다 — 누가 안 쓰는지가 더 궁금한 경우가 많다.
	// ─────────────────────────────────────────────────────────
	public List<Map<String, Object>> getUsers(String from, String to, String dbKey, boolean exAdmin, boolean exDev) {
		MapSqlParameterSource p = period(from, to, exAdmin, exDev);
		p.addValue("dbKey", requireKey(dbKey));

		return sqlRunner.getRows("""
				with lg as (
				    select l."User_id" as uid, count(*) as c, max(l._created) as d
				      from login_log l
				     where l."Type" = 'login'
				       and l._created >= cast(:fromDt as timestamp) at time zone 'Asia/Seoul'
				       and l._created <  cast(:toDt   as timestamp) at time zone 'Asia/Seoul'
				     group by l."User_id"
				), mu as (
				    select m."User_id" as uid
				         , count(*) as c
				         , count(*) filter (where m."MenuCode" not in (:sysMenus)) as w
				         , max(m._created) as d
				      from menu_use_log m
				     where m._created >= cast(:fromDt as timestamp) at time zone 'Asia/Seoul'
				       and m._created <  cast(:toDt   as timestamp) at time zone 'Asia/Seoul'
				     group by m."User_id"
				)
				select u.username                                                    as username
				     , trim(coalesce(u.last_name, '') || coalesce(u.first_name, '')) as usernm
				     , u.is_active                                                   as is_active
				     , coalesce(lg.c, 0)                                             as login_cnt
				     , coalesce(mu.c, 0)                                             as menu_cnt
				     , coalesce(mu.w, 0)                                             as work_cnt
				     , to_char(greatest(lg.d, mu.d) at time zone 'Asia/Seoul', 'YYYY-MM-DD HH24:MI') as last_at
				     -- 아래 두 값은 조회 기간과 관계없는 전체 기간 기준이다
				     , to_char(u.date_joined at time zone 'Asia/Seoul', 'YYYY-MM-DD')               as joined_at
				     , to_char(fa.first_at   at time zone 'Asia/Seoul', 'YYYY-MM-DD')               as first_at
				  from auth_user u
				  left join lg on lg.uid = u.id
				  left join mu on mu.uid = u.id
				  -- 계정이 처음 쓴 날 : 로그인과 메뉴 접속 중 이른 쪽 (least 는 null 을 건너뛴다)
				  left join lateral (
				    select least(
				             ( select min(x._created) from login_log x
				                where x."User_id" = u.id and x."Type" = 'login' ),
				             ( select min(y._created) from menu_use_log y
				                where y."User_id" = u.id ) ) as first_at
				  ) fa on true
				 where u.db_key = :dbKey
				   and (:exAdmin = 'N' or (u.username <> 'admin' and u.is_superuser = false))
				   and (:exDev   = 'N' or u.username not like :devLike escape '\\')
				 order by menu_cnt desc, login_cnt desc, u.username
				""", p);
	}

	// ─────────────────────────────────────────────────────────
	//  도입 이력 : 사업장별 등록 → 첫 로그인 → 첫 사용 → 첫 업무 사용 → 마지막 사용
	//
	//  조회 기간과 관계없이 전체 기간에서 찾는다.
	//  등록일은 그 사업장에서 처음 만든 계정의 생성일이다. 개설 승인(batchApprove) 때
	//  개발자 계정이 먼저 만들어지므로 사실상 개설일이 된다. 그래서 등록일만은 필터를 걸지 않는다.
	//  나머지는 관리자 · 개발자 계정 제외 설정을 따른다 — 실무자가 언제 처음 썼는지를 본다.
	// ─────────────────────────────────────────────────────────
	public List<Map<String, Object>> getLifecycle(boolean exAdmin, boolean exDev) {
		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("exAdmin", exAdmin ? "Y" : "N");
		p.addValue("exDev", exDev ? "Y" : "N");
		p.addValue("devLike", DEV_ACCOUNT_LIKE);
		p.addValue("sysMenus", SYSTEM_MENUS);

		List<Map<String, Object>> rows = sqlRunner.getRows("""
				with reg as (
				    select db_key, min(date_joined) as reg_at
				      from auth_user
				     where db_key is not null
				     group by db_key
				), f as (
				    select u.id, u.db_key
				      from auth_user u
				     where u.db_key is not null
				       and (:exAdmin = 'N' or (u.username <> 'admin' and u.is_superuser = false))
				       and (:exDev   = 'N' or u.username not like :devLike escape '\\')
				), lg as (
				    select f.db_key, min(l._created) as first_login
				      from login_log l
				      join f on f.id = l."User_id"
				     where l."Type" = 'login'
				     group by f.db_key
				), mu as (
				    select f.db_key
				         , min(m._created)                                                as first_use
				         , min(m._created) filter (where m."MenuCode" not in (:sysMenus)) as first_work
				         , max(m._created)                                                as last_use
				      from menu_use_log m
				      join f on f.id = m."User_id"
				     group by f.db_key
				)
				select reg.db_key                                                               as db_key
				     , to_char(reg.reg_at     at time zone 'Asia/Seoul', 'YYYY-MM-DD')          as reg_at
				     , to_char(lg.first_login at time zone 'Asia/Seoul', 'YYYY-MM-DD')          as first_login
				     , to_char(mu.first_use   at time zone 'Asia/Seoul', 'YYYY-MM-DD')          as first_use
				     , to_char(mu.first_work  at time zone 'Asia/Seoul', 'YYYY-MM-DD')          as first_work
				     , to_char(mu.last_use    at time zone 'Asia/Seoul', 'YYYY-MM-DD')          as last_use
				     -- 등록부터 첫 업무 사용까지 걸린 날 / 마지막 사용 후 지난 날
				     , cast(mu.first_work at time zone 'Asia/Seoul' as date)
				       - cast(reg.reg_at  at time zone 'Asia/Seoul' as date)                    as days_to_work
				     , cast(now()       at time zone 'Asia/Seoul' as date)
				       - cast(mu.last_use at time zone 'Asia/Seoul' as date)                    as days_idle
				  from reg
				  left join lg on lg.db_key = reg.db_key
				  left join mu on mu.db_key = reg.db_key
				 order by reg.db_key
				""", p);

		// 가입일 · 승인일 · 요금제 · 상태를 붙인다. 날 수 계산(승인 후 N일)은 화면이 한다.
		Map<String, Map<String, String>> info = getSiteInfo();
		Map<String, String> approved = getApprovedAt();
		List<Map<String, Object>> out = new java.util.ArrayList<>(rows.size());
		for (Map<String, Object> r : rows) {
			Map<String, Object> m = new java.util.LinkedHashMap<>(r);
			String k = String.valueOf(r.get("db_key")).trim();
			Map<String, String> i = info.getOrDefault(k, Map.of());
			m.put("join_at", ymd(i.get("subscriptiondate")));
			m.put("approved_at", approved.getOrDefault(k, ""));
			m.put("plan", i.getOrDefault("plan", ""));
			m.put("state", i.getOrDefault("state", ""));
			m.put("exp_at", ymd(i.get("expirationdate")));
			out.add(m);
		}
		return out;
	}

	// ─────────────────────────────────────────────────────────
	//  내부
	// ─────────────────────────────────────────────────────────

	/**
	 * 기간 · 관리자 제외 · 시스템 메뉴 목록을 담은 공통 파라미터.
	 *
	 * from · to 는 'YYYY-MM' 이고 to 는 그 달을 포함한다.
	 * SQL 에는 [from 1일 0시, to 다음달 1일 0시) 를 한국 시간 문자열로 넘긴다.
	 */
	private MapSqlParameterSource period(String from, String to, boolean exAdmin, boolean exDev) {
		YearMonth f = parseYm(from, "시작월");
		YearMonth t = parseYm(to, "종료월");
		if (f.isAfter(t)) {
			throw new IllegalArgumentException("시작월이 종료월보다 늦습니다.");
		}
		if (ChronoUnit.MONTHS.between(f, t) + 1 > MAX_MONTHS) {
			throw new IllegalArgumentException("기간은 최대 " + MAX_MONTHS + "개월까지 조회할 수 있습니다.");
		}

		MapSqlParameterSource p = new MapSqlParameterSource();
		p.addValue("fromDt", f.atDay(1).atStartOfDay().format(TS));
		p.addValue("toDt", t.plusMonths(1).atDay(1).atStartOfDay().format(TS));
		p.addValue("exAdmin", exAdmin ? "Y" : "N");
		p.addValue("exDev", exDev ? "Y" : "N");
		p.addValue("devLike", DEV_ACCOUNT_LIKE);
		p.addValue("sysMenus", SYSTEM_MENUS);
		return p;
	}

	private YearMonth parseYm(String s, String what) {
		try {
			return YearMonth.parse(s == null ? "" : s.trim());
		} catch (DateTimeParseException e) {
			throw new IllegalArgumentException(what + " 형식이 올바르지 않습니다. (예: 2026-10)");
		}
	}

	private String requireKey(String dbKey) {
		String k = dbKey == null ? "" : dbKey.trim();
		if (k.isEmpty() || k.length() > 20) {
			throw new IllegalArgumentException("사업장(db_key)을 선택하세요.");
		}
		return k;
	}
}