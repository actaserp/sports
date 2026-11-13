package mes.app.system.service;

import lombok.extern.slf4j.Slf4j;
import mes.app.files.NcpObjectStorageService;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 전체공지 (거래처 공통 공지) + 계정별 공지 안보기 — 2026-11-13
 *
 *  - 모든 거래처가 함께 보는 데이터라 마스터 DB(PostgreSQL)에 둔다 → mainSqlRunner 만 쓴다.
 *  - 대상: target_all = 'Y' 면 모든 거래처, 'N' 이면 common_notice_target 의 db_key 거래처만.
 *  - 첨부: NCP 에 {프로젝트}/common/COMMON_NOTICE/{uuid}.{ext} 로 올린다. 사업장 폴더가 아니라서 모든 거래처가 받을 수 있다.
 *    받을 때는 그 공지를 볼 수 있는 거래처인지 확인한다.
 *  - @Transactional 은 붙이지 않는다. 기본 트랜잭션 관리자가 테넌트 DB 쪽이라 마스터 DB 작업에는 걸리지 않는다.
 *  - 안보기는 계정(auth_user.id)별로 notice_hide 에 남긴다. 브라우저가 바뀌어도 유지된다.
 *    사업장 공지(테넌트 tb_bbsinfo)도 같은 테이블을 쓴다 (kind = 'T', db_key 로 사업장 구분).
 */
@Slf4j
@Service
public class CommonNoticeService {

	public static final String FEATURE = "COMMON_NOTICE";
	public static final String COMMON_FOLDER = "common";

	private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");
	private static final List<String> BLOCKED_EXT = Arrays.asList(
		"py", "js", "aspx", "asp", "jsp", "php", "cs", "ini", "htaccess", "exe", "dll", "bat", "cmd", "sh");
	private static final long MAX_FILE = 20L * 1024 * 1024;

	@Autowired
	@Qualifier("mainSqlRunner")
	SqlRunner mainSqlRunner;

	@Autowired
	NcpObjectStorageService storageService;

	// =================================================================
	// 운영사 관리 (superuser)
	// =================================================================

	public List<Map<String, Object>> list(String keyword) {
		MapSqlParameterSource p = new MapSqlParameterSource("kw", keyword == null ? "" : keyword.trim());
		return mainSqlRunner.getRows("""
				SELECT n.id, n.title, n.from_date, n.to_date, n.target_all, n.popup_yn,
				       to_char(n._created, 'YYYY-MM-DD') AS created,
				       (SELECT COUNT(*) FROM common_notice_target t WHERE t.notice_id = n.id) AS target_cnt,
				       (SELECT COUNT(*) FROM common_notice_file f WHERE f.notice_id = n.id) AS file_cnt
				  FROM common_notice n
				 WHERE (:kw = '' OR n.title LIKE '%' || :kw || '%' OR n.content LIKE '%' || :kw || '%')
				 ORDER BY n.id DESC
				""", p);
	}

	public Map<String, Object> detail(int id) {
		MapSqlParameterSource p = new MapSqlParameterSource("id", id);
		Map<String, Object> n = mainSqlRunner.getRow(
			"SELECT id, title, content, from_date, to_date, target_all, popup_yn FROM common_notice WHERE id = :id", p);
		if (n == null) return null;
		Map<String, Object> out = new LinkedHashMap<>(n);
		List<String> targets = new ArrayList<>();
		for (Map<String, Object> r : mainSqlRunner.getRows(
			"SELECT db_key FROM common_notice_target WHERE notice_id = :id ORDER BY db_key", p))
			targets.add(String.valueOf(r.get("db_key")));
		out.put("targets", targets);
		out.put("files", files(id));
		return out;
	}

	/**
	 * 거래처 목록 (대상 선택용) = 테넌트 사업장.
	 *  테넌트는 tb_tenant_db 에 등록된 접속 키(spjangcd = 로그인 세션의 db_key)로 구분한다 (테넌트 DB 관리 화면과 같은 기준).
	 *  이름은 마스터 tb_xa012 에서 붙인다. db_alias 가 main 이 아닌 보조 접속은 같은 거래처라 뺀다.
	 */
	public List<Map<String, Object>> tenants() {
		return mainSqlRunner.getRows("""
				SELECT t.spjangcd AS db_key,
				       COALESCE(MAX(x.spjangnm), t.spjangcd) AS spjangnm
				  FROM tb_tenant_db t
				  LEFT JOIN tb_xa012 x ON x.spjangcd = t.spjangcd
				 WHERE COALESCE(t.spjangcd, '') <> ''
				   AND COALESCE(t.db_alias, 'main') = 'main'
				 GROUP BY t.spjangcd
				 ORDER BY 2
				""", new MapSqlParameterSource());
	}

	public int save(Integer id, String title, String content, String fromDate, String toDate,
									String targetAll, String popupYn, List<String> targets, List<MultipartFile> files, Integer userId) {
		if (!StringUtils.hasText(title)) throw new IllegalArgumentException("제목을 입력하세요.");
		String fr = ymd(fromDate), to = ymd(toDate);
		if (fr == null || to == null) throw new IllegalArgumentException("게시 기간을 입력하세요.");
		if (fr.compareTo(to) > 0) throw new IllegalArgumentException("게시 시작일이 종료일보다 늦습니다.");
		boolean all = !"N".equals(targetAll);
		List<String> keys = new ArrayList<>(new LinkedHashSet<>(targets == null ? List.of() : targets));
		keys.removeIf(k -> !StringUtils.hasText(k));
		if (!all && keys.isEmpty()) throw new IllegalArgumentException("공지를 받을 거래처를 하나 이상 고르세요.");
		if (files != null) for (MultipartFile f : files) checkFile(f);

		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("title", title.trim()).addValue("content", content)
																.addValue("fr", fr).addValue("to", to)
																.addValue("all", all ? "Y" : "N").addValue("popup", "N".equals(popupYn) ? "N" : "Y")
																.addValue("uid", userId);
		if (id == null) {
			Map<String, Object> r = mainSqlRunner.getRow("""
					INSERT INTO common_notice (title, content, from_date, to_date, target_all, popup_yn, _created, _creater_id, _modified, _modifier_id)
					VALUES (:title, :content, :fr, :to, :all, :popup, now(), :uid, now(), :uid)
					RETURNING id
					""", p);
			id = ((Number) r.get("id")).intValue();
		} else {
			p.addValue("id", id);
			int n = mainSqlRunner.execute("""
					UPDATE common_notice SET title = :title, content = :content, from_date = :fr, to_date = :to,
					       target_all = :all, popup_yn = :popup, _modified = now(), _modifier_id = :uid
					 WHERE id = :id
					""", p);
			if (n == 0) throw new IllegalArgumentException("공지를 찾을 수 없습니다.");
		}

		MapSqlParameterSource ip = new MapSqlParameterSource("id", id);
		mainSqlRunner.execute("DELETE FROM common_notice_target WHERE notice_id = :id", ip);
		if (!all)
			for (String k : keys)
				mainSqlRunner.execute("INSERT INTO common_notice_target (notice_id, db_key) VALUES (:id, :k) ON CONFLICT DO NOTHING",
					new MapSqlParameterSource("id", id).addValue("k", k.trim()));

		if (files != null)
			for (MultipartFile f : files) {
				if (f == null || f.isEmpty()) continue;
				String name = f.getOriginalFilename();
				String ext = ext(name);
				String key = storageService.buildObjectKey(COMMON_FOLDER, FEATURE, UUID.randomUUID() + (ext.isEmpty() ? "" : "." + ext));
				try (java.io.InputStream in = f.getInputStream()) {
					storageService.upload(key, in, f.getSize(),
						f.getContentType() != null ? f.getContentType() : "application/octet-stream");
				} catch (java.io.IOException e) {
					throw new IllegalStateException("첨부파일 업로드 실패: " + name, e);
				}
				mainSqlRunner.execute("""
						INSERT INTO common_notice_file (notice_id, object_key, file_name, file_size)
						VALUES (:id, :key, :name, :size)
						""", new MapSqlParameterSource("id", id).addValue("key", key).addValue("name", name).addValue("size", f.getSize()));
			}
		return id;
	}

	/** 공지 삭제: NCP 파일 → DB (대상 · 파일 · 안보기 기록은 함께 지운다) */
	public void delete(int id) {
		MapSqlParameterSource p = new MapSqlParameterSource("id", id);
		for (Map<String, Object> f : mainSqlRunner.getRows("SELECT object_key FROM common_notice_file WHERE notice_id = :id", p))
			deleteObject(String.valueOf(f.get("object_key")));
		mainSqlRunner.execute("DELETE FROM notice_hide WHERE kind = 'C' AND notice_id = :id", p);
		mainSqlRunner.execute("DELETE FROM common_notice WHERE id = :id", p);   // target · file 은 CASCADE
	}

	public void deleteFile(int fileId) {
		MapSqlParameterSource p = new MapSqlParameterSource("id", fileId);
		Map<String, Object> f = mainSqlRunner.getRow("SELECT object_key FROM common_notice_file WHERE id = :id", p);
		if (f == null) return;
		deleteObject(String.valueOf(f.get("object_key")));
		mainSqlRunner.execute("DELETE FROM common_notice_file WHERE id = :id", p);
	}

	// =================================================================
	// 거래처 사용자
	// =================================================================

	/**
	 * 로그인 팝업용: 오늘 게시 중 · 팝업 켬 · 내 거래처가 대상 · 내가 안보기 하지 않은 전체공지 (첨부 포함)
	 */
	public List<Map<String, Object>> popupNotices(String dbKey, int userId) {
		MapSqlParameterSource p = new MapSqlParameterSource("today", LocalDate.now().format(YMD))
																.addValue("key", dbKey == null ? "" : dbKey).addValue("uid", userId);
		List<Map<String, Object>> rows = mainSqlRunner.getRows("""
				SELECT n.id, n.title, n.content, n.from_date AS notice_from_date, n.to_date AS notice_end_date
				  FROM common_notice n
				 WHERE n.popup_yn = 'Y'
				   AND n.from_date <= :today AND n.to_date >= :today
				   AND (n.target_all = 'Y'
				        OR EXISTS (SELECT 1 FROM common_notice_target t WHERE t.notice_id = n.id AND t.db_key = :key))
				   AND NOT EXISTS (SELECT 1 FROM notice_hide h
				                    WHERE h.user_id = :uid AND h.kind = 'C' AND h.db_key = '*' AND h.notice_id = n.id
				                      AND (h.hide_until IS NULL OR h.hide_until > now()))
				 ORDER BY n.id DESC
				""", p);
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> r : rows) {
			Map<String, Object> m = new LinkedHashMap<>(r);
			m.put("kind", "C");
			m.put("files", files(((Number) r.get("id")).intValue()));
			out.add(m);
		}
		return out;
	}

	/** 사업장 공지 중 내가 안보기 한 번호 (index 팝업이 걸러 낸다) */
	public List<Integer> hiddenTenantIds(String dbKey, int userId) {
		List<Integer> out = new ArrayList<>();
		for (Map<String, Object> r : mainSqlRunner.getRows("""
				SELECT notice_id FROM notice_hide
				 WHERE user_id = :uid AND kind = 'T' AND db_key = :key
				   AND (hide_until IS NULL OR hide_until > now())
				""", new MapSqlParameterSource("uid", userId).addValue("key", dbKey == null ? "" : dbKey)))
			out.add(((Number) r.get("notice_id")).intValue());
		return out;
	}

	/**
	 * 안보기 기록
	 * @param kind C 전체공지 · T 사업장 공지
	 * @param mode today 오늘 하루(내일 0시까지) · forever 다시 안보기
	 */
	public void hide(int userId, String kind, String dbKey, int noticeId, String mode) {
		if (!"C".equals(kind) && !"T".equals(kind)) throw new IllegalArgumentException("공지 종류가 올바르지 않습니다.");
		String key = "C".equals(kind) ? "*" : (dbKey == null ? "" : dbKey);
		boolean today = "today".equals(mode);
		mainSqlRunner.execute("""
				INSERT INTO notice_hide (user_id, kind, db_key, notice_id, hide_until)
				VALUES (:uid, :kind, :key, :nid, CASE WHEN :today THEN date_trunc('day', now()) + interval '1 day' ELSE NULL END)
				ON CONFLICT (user_id, kind, db_key, notice_id)
				DO UPDATE SET hide_until = EXCLUDED.hide_until, _created = now()
				""", new MapSqlParameterSource("uid", userId).addValue("kind", kind).addValue("key", key)
																				 .addValue("nid", noticeId).addValue("today", today));
	}

	/** 첨부 받기: 운영사(superuser)거나, 그 공지의 대상 거래처일 때만 */
	public Map<String, Object> fileForDownload(int fileId, String dbKey, boolean superUser) {
		Map<String, Object> f = mainSqlRunner.getRow("""
				SELECT f.object_key, f.file_name, n.target_all, n.id AS notice_id
				  FROM common_notice_file f JOIN common_notice n ON n.id = f.notice_id
				 WHERE f.id = :id
				""", new MapSqlParameterSource("id", fileId));
		if (f == null) return null;
		if (superUser || "Y".equals(f.get("target_all"))) return f;
		Map<String, Object> ok = mainSqlRunner.getRow(
			"SELECT 1 AS ok FROM common_notice_target WHERE notice_id = :nid AND db_key = :key",
			new MapSqlParameterSource("nid", f.get("notice_id")).addValue("key", dbKey == null ? "" : dbKey));
		return ok == null ? null : f;
	}

	// =================================================================

	private List<Map<String, Object>> files(int noticeId) {
		return mainSqlRunner.getRows(
			"SELECT id AS \"fileId\", file_name AS \"fileNm\", file_size AS \"fileSize\" FROM common_notice_file WHERE notice_id = :id ORDER BY id",
			new MapSqlParameterSource("id", noticeId));
	}

	private void checkFile(MultipartFile f) {
		if (f == null || f.isEmpty()) return;
		if (f.getSize() > MAX_FILE) throw new IllegalArgumentException("파일 크기가 20MB를 넘습니다: " + f.getOriginalFilename());
		if (BLOCKED_EXT.contains(ext(f.getOriginalFilename()))) throw new IllegalArgumentException("허용되지 않는 파일 형식입니다: " + f.getOriginalFilename());
	}

	private void deleteObject(String key) {
		try {
			storageService.delete(key);
		} catch (Exception e) {
			log.error("[CommonNotice] NCP 파일 삭제 오류 (key={}): {}", key, e.getMessage(), e);
		}
	}

	private static String ext(String name) {
		if (name == null || !name.contains(".")) return "";
		return name.substring(name.lastIndexOf('.') + 1).toLowerCase();
	}

	private static String ymd(String s) {
		if (s == null) return null;
		String d = s.replaceAll("[^0-9]", "");
		return d.length() == 8 ? d : null;
	}
}