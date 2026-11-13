package mes.app.system.service;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 메일 발송 계정 — 사업장(테넌트)별 (시스템관리 > 메일 설정, SaaS 판 2026-11-13)
 *
 *  - 사업장마다 SMTP 계정 1건을 마스터 DB(PostgreSQL) mail_smtp 에 둔다.
 *    마스터 테이블 규칙대로 spjangcd 컬럼에 db_key 를 넣는다 (TenantContext.getDbKey()).
 *    → 반드시 mainSqlRunner 로 다룬다. 기본 sqlRunner 는 테넌트 DB 로 가서 테이블이 없다.
 *  - 비밀번호는 AES-256-GCM 으로 암호화해 저장한다. 키 = SHA-256(비밀 키).
 *    비밀 키: mes.mail.secret 설정이 있으면 그 값, 없으면 서버 파일(mes.mail.key-file).
 *    파일이 없으면 처음 쓸 때 무작위로 만든다 → 따로 설정할 것 없음, git 에도 안 올라감.
 *    ★ 이 파일을 지우거나 서버를 옮기면서 빠뜨리면 모든 사업장이 비밀번호를 다시 넣어야 한다.
 *  - 인증 실패가 연속 LOCK_AFTER 회면 LOCK_MINUTES 동안 그 사업장 계정으로 보내지 않는다.
 *  - 사업장에 설정이 없으면 기존 시스템 계정(application.properties spring.mail.*)으로 보낸다
 *    (account() 가 null 을 돌려주고, 부르는 쪽이 기본 JavaMailSender 를 쓴다).
 */
@Slf4j
@Service
public class MailSettingService {

	public static final int LOCK_AFTER = 3;
	public static final int LOCK_MINUTES = 30;

	@Autowired
	@Qualifier("mainSqlRunner")
	SqlRunner mainSqlRunner;

	@Value("${mes.mail.secret:}")
	private String configSecret;

	/** 비밀 키 파일 (기본: 사용자 홈/.mes/mail.key) */
	@Value("${mes.mail.key-file:${user.home}/.mes/mail.key}")
	private String keyFile;

	private volatile String secret;

	/** 사업장(db_key)별 연결 캐시. 설정이 바뀌면 그 사업장 것만 비운다 */
	private final Map<String, JavaMailSenderImpl> senderCache = new ConcurrentHashMap<>();

	/** 메일 서비스별 기본값 (화면 「메일 서비스」 선택용) */
	public static final List<Map<String, Object>> PRESETS = List.of(
		preset("hiworks", "하이웍스", "smtps.hiworks.com", 465, "ssl"),
		preset("worksmobile", "네이버웍스", "smtp.worksmobile.com", 465, "ssl"),
		preset("google", "구글 워크스페이스 / Gmail", "smtp.gmail.com", 465, "ssl"),
		preset("ms365", "Microsoft 365 / Outlook", "smtp.office365.com", 587, "starttls"),
		preset("daouoffice", "다우오피스", "smtp.daouoffice.com", 465, "ssl"),
		preset("naver", "네이버 메일", "smtp.naver.com", 465, "ssl"),
		preset("daum", "다음 메일", "smtp.daum.net", 465, "ssl"),
		preset("custom", "직접 입력", "", 465, "ssl"));

	private static Map<String, Object> preset(String code, String name, String host, int port, String sec) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("code", code); m.put("name", name); m.put("host", host); m.put("port", port); m.put("security", sec);
		return m;
	}

	// =================================================================
	// 조회 · 저장 (로그인한 사업장 것만)
	// =================================================================

	/** 화면용: 비밀번호는 내려보내지 않고 저장 여부만 준다 */
	public Map<String, Object> getForView() {
		Map<String, Object> row = row(dbKey());
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("presets", PRESETS);
		out.put("secretReady", StringUtils.hasText(secret()));
		if (row == null) { out.put("setting", null); return out; }
		Map<String, Object> s = new LinkedHashMap<>(row);
		s.put("has_password", StringUtils.hasText((String) row.get("password_enc")));
		s.remove("password_enc");
		out.put("setting", s);
		return out;
	}

	/** password 가 비어 있으면 기존 비밀번호를 그대로 둔다 */
	public void save(Map<String, Object> in, Integer userId) {
		String key = dbKey();
		String host = str(in.get("host"));
		String username = str(in.get("username"));
		String fromAddr = str(in.get("from_addr"));
		if (!StringUtils.hasText(fromAddr)) fromAddr = username;
		Integer port = toInt(in.get("port"));
		String security = str(in.get("security"));
		if (!List.of("ssl", "starttls", "none").contains(security)) security = "ssl";

		if (!StringUtils.hasText(host)) throw new IllegalArgumentException("SMTP 서버 주소를 입력하세요.");
		if (port == null || port <= 0) throw new IllegalArgumentException("포트를 입력하세요.");
		if (!StringUtils.hasText(username)) throw new IllegalArgumentException("아이디(메일 주소)를 입력하세요.");
		if (fromAddr == null || !fromAddr.contains("@")) throw new IllegalArgumentException("보내는 주소가 메일 주소 형식이 아닙니다.");

		String password = (String) in.get("password");
		String enc = null;
		if (StringUtils.hasText(password)) {
			if (!StringUtils.hasText(secret()))
				throw new IllegalStateException("암호화 키 파일(" + keyFile + ")을 만들지 못해 비밀번호를 저장할 수 없습니다. 서버 폴더 쓰기 권한을 확인하세요.");
			enc = encrypt(password);
		}

		MapSqlParameterSource p = new MapSqlParameterSource()
																.addValue("key", key)
																.addValue("provider", str(in.get("provider")))
																.addValue("host", host.trim())
																.addValue("port", port)
																.addValue("security", security)
																.addValue("username", username.trim())
																.addValue("enc", enc)
																.addValue("fromAddr", fromAddr.trim())
																.addValue("fromName", str(in.get("from_name")))
																.addValue("nameWithUser", yn(in.get("name_with_user"), "N"))
																.addValue("bccSender", yn(in.get("bcc_sender"), "N"))
																.addValue("useYn", yn(in.get("use_yn"), "Y"))
																.addValue("uid", userId);

		if (row(key) == null) {
			if (enc == null) throw new IllegalArgumentException("비밀번호(앱 비밀번호)를 입력하세요.");
			mainSqlRunner.execute("""
					INSERT INTO mail_smtp (spjangcd, provider, host, port, security, username, password_enc, from_addr, from_name,
					                       name_with_user, bcc_sender, use_yn, _created, _modified, _modifier_id)
					VALUES (:key, :provider, :host, :port, :security, :username, :enc, :fromAddr, :fromName,
					        :nameWithUser, :bccSender, :useYn, now(), now(), :uid)
					""", p);
		} else {
			// 계정 정보를 고치면 잠금·실패 횟수를 풀어 준다
			mainSqlRunner.execute("""
					UPDATE mail_smtp SET provider = :provider, host = :host, port = :port, security = :security,
					       username = :username, password_enc = COALESCE(:enc, password_enc),
					       from_addr = :fromAddr, from_name = :fromName, name_with_user = :nameWithUser,
					       bcc_sender = :bccSender, use_yn = :useYn,
					       fail_count = 0, locked_until = NULL, last_error = NULL,
					       _modified = now(), _modifier_id = :uid
					 WHERE spjangcd = :key
					""", p);
		}
		senderCache.remove(key);
	}

	// =================================================================
	// 발송에 쓰는 계정
	// =================================================================

	/**
	 * 로그인한 사업장의 발송 계정.
	 *  - 설정 자체가 없으면 null → 부르는 쪽이 시스템 기본 계정으로 보낸다.
	 *  - 설정은 있는데 쓸 수 없으면(꺼짐 · 잠김 · 비밀번호 없음) 사유와 함께 예외.
	 *    이때 시스템 계정으로 몰래 돌리지 않는다 — 사업장이 고른 계정이 아닌 주소로 급여가 나가면 안 된다.
	 */
	public Account account() {
		String key = dbKey();
		Map<String, Object> row = row(key);
		if (row == null) return null;
		if (!"Y".equals(row.get("use_yn"))) throw new IllegalStateException("메일 발송이 꺼져 있습니다. 시스템관리 > 메일 설정을 확인하세요.");
		Object lu = row.get("locked_until");
		if (lu instanceof java.sql.Timestamp t && t.after(new java.util.Date()))
			throw new IllegalStateException("메일 계정 인증이 연속으로 실패해 " + t.toLocalDateTime().toLocalTime().withNano(0)
																				+ " 까지 발송을 멈췄습니다. 메일 설정에서 비밀번호를 확인하세요. (마지막 오류: " + row.get("last_error") + ")");
		if (!StringUtils.hasText((String) row.get("password_enc")))
			throw new IllegalStateException("메일 계정 비밀번호가 없습니다. 시스템관리 > 메일 설정에서 입력하세요.");

		Account a = new Account();
		a.key = key;
		a.fromAddr = (String) row.get("from_addr");
		a.fromName = (String) row.get("from_name");
		a.nameWithUser = "Y".equals(row.get("name_with_user"));
		a.bccSender = "Y".equals(row.get("bcc_sender"));
		a.sender = senderCache.computeIfAbsent(key, k -> build(row));
		return a;
	}

	/** 발송 팝업 「보내는 계정」 표시용 — 예외 대신 상태로 준다 */
	public Map<String, Object> status() {
		Map<String, Object> out = new LinkedHashMap<>();
		try {
			Account a = account();
			out.put("ok", true);
			out.put("system", a == null);   // true 면 사업장 설정 없음 → 시스템 기본 계정
			if (a != null) {
				out.put("from_addr", a.fromAddr);
				out.put("from_name", a.fromName);
				out.put("name_with_user", a.nameWithUser);
				out.put("bcc_sender", a.bccSender);
			}
		} catch (RuntimeException e) {
			out.put("ok", false);
			out.put("message", e.getMessage());
		}
		return out;
	}

	public void markOk(String key) {
		if (key == null) return;
		mainSqlRunner.execute("UPDATE mail_smtp SET fail_count = 0, locked_until = NULL, last_error = NULL, last_ok_at = now() WHERE spjangcd = :key",
			new MapSqlParameterSource("key", key));
	}

	/** 인증 실패만 센다 (받는 주소 오류 같은 건 계정 문제가 아니다) */
	public void markFail(String key, Throwable e) {
		if (key == null) return;
		MapSqlParameterSource p = new MapSqlParameterSource("key", key).addValue("err", cut(rootMessage(e), 500))
																.addValue("after", LOCK_AFTER).addValue("min", LOCK_MINUTES);
		if (isAuthFailure(e)) {
			mainSqlRunner.execute("""
					UPDATE mail_smtp SET fail_count = fail_count + 1, last_error = :err,
					       locked_until = CASE WHEN fail_count + 1 >= :after THEN now() + make_interval(mins => :min) ELSE locked_until END
					 WHERE spjangcd = :key
					""", p);
			senderCache.remove(key);
		} else {
			mainSqlRunner.execute("UPDATE mail_smtp SET last_error = :err WHERE spjangcd = :key", p);
		}
	}

	public static boolean isAuthFailure(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof javax.mail.AuthenticationFailedException) return true;
			if (t instanceof org.springframework.mail.MailAuthenticationException) return true;
			String m = t.getMessage();
			if (m != null && (m.contains("535") || m.contains("534") || m.toLowerCase().contains("authentication failed"))) return true;
		}
		return false;
	}

	/** 저장 전 시험용: 화면 값으로 바로 연결 (비밀번호가 비어 있으면 이 사업장에 저장된 것 사용) */
	public JavaMailSenderImpl senderForTest(Map<String, Object> in) {
		Map<String, Object> row = new HashMap<>();
		row.put("host", str(in.get("host")));
		row.put("port", toInt(in.get("port")));
		row.put("security", str(in.get("security")));
		row.put("username", str(in.get("username")));
		String pw = (String) in.get("password");
		if (StringUtils.hasText(pw)) {
			row.put("plain", pw);
		} else {
			Map<String, Object> saved = row(dbKey());
			if (saved == null || !StringUtils.hasText((String) saved.get("password_enc")))
				throw new IllegalArgumentException("비밀번호(앱 비밀번호)를 입력하세요.");
			row.put("password_enc", saved.get("password_enc"));
		}
		return build(row);
	}

	// =================================================================
	// 내부
	// =================================================================

	/** 로그인한 사업장의 db_key. 없으면 어느 사업장 설정인지 모르므로 막는다 */
	private String dbKey() {
		String k = TenantContext.getDbKey();
		if (!StringUtils.hasText(k)) throw new IllegalStateException("사업장 정보(db_key)가 없습니다. 다시 로그인하세요.");
		return k.trim();
	}

	private Map<String, Object> row(String key) {
		return mainSqlRunner.getRow("""
				SELECT id, spjangcd, provider, host, port, security, username, password_enc, from_addr, from_name,
				       name_with_user, bcc_sender, use_yn, fail_count, locked_until, last_ok_at, last_error, _modified
				  FROM mail_smtp WHERE spjangcd = :key
				""", new MapSqlParameterSource("key", key));
	}

	private JavaMailSenderImpl build(Map<String, Object> row) {
		JavaMailSenderImpl s = new JavaMailSenderImpl();
		s.setHost((String) row.get("host"));
		s.setPort(row.get("port") instanceof Number n ? n.intValue() : 465);
		s.setUsername((String) row.get("username"));
		s.setPassword(row.containsKey("plain") ? (String) row.get("plain") : decrypt((String) row.get("password_enc")));
		s.setDefaultEncoding("UTF-8");
		Properties p = s.getJavaMailProperties();
		p.put("mail.transport.protocol", "smtp");
		p.put("mail.smtp.auth", "true");
		String sec = String.valueOf(row.get("security"));
		if ("ssl".equals(sec)) {
			p.put("mail.smtp.ssl.enable", "true");
			p.put("mail.smtp.ssl.checkserveridentity", "true");
		} else if ("starttls".equals(sec)) {
			p.put("mail.smtp.starttls.enable", "true");
			p.put("mail.smtp.starttls.required", "true");
		}
		p.put("mail.smtp.connectiontimeout", "10000");
		p.put("mail.smtp.timeout", "20000");
		p.put("mail.smtp.writetimeout", "20000");
		return s;
	}

	/** 설정값 → 키 파일 → (없으면) 새로 만들어 파일에 저장 */
	private synchronized String secret() {
		if (secret != null) return secret;
		if (StringUtils.hasText(configSecret)) return secret = configSecret.trim();
		try {
			java.nio.file.Path f = java.nio.file.Paths.get(keyFile);
			if (java.nio.file.Files.isRegularFile(f)) {
				String v = java.nio.file.Files.readString(f, StandardCharsets.UTF_8).trim();
				if (!v.isEmpty()) return secret = v;
			}
			byte[] b = new byte[32];
			new SecureRandom().nextBytes(b);
			String v = Base64.getEncoder().encodeToString(b);
			if (f.getParent() != null) java.nio.file.Files.createDirectories(f.getParent());
			java.nio.file.Files.writeString(f, v, StandardCharsets.UTF_8,
				java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
			try {   // 리눅스면 소유자만 읽게 (윈도는 무시)
				java.nio.file.Files.setPosixFilePermissions(f, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
			} catch (UnsupportedOperationException ignore) { }
			log.info("메일 비밀번호 암호화 키 파일을 만들었습니다: {}", f.toAbsolutePath());
			return secret = v;
		} catch (java.nio.file.FileAlreadyExistsException e) {
			secret = null;
			return secret();   // 동시에 다른 요청이 만들었다
		} catch (Exception e) {
			log.error("메일 비밀번호 암호화 키 파일을 읽거나 만들지 못했습니다: {}", keyFile, e);
			return null;
		}
	}

	private byte[] key() {
		try {
			return MessageDigest.getInstance("SHA-256").digest(secret().getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) { throw new IllegalStateException(e); }
	}

	private String encrypt(String plain) {
		try {
			byte[] iv = new byte[12];
			new SecureRandom().nextBytes(iv);
			Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
			c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, iv));
			byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
			return "v1:" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
		} catch (Exception e) { throw new IllegalStateException("비밀번호 암호화 실패", e); }
	}

	private String decrypt(String enc) {
		if (!StringUtils.hasText(enc)) return null;
		if (!StringUtils.hasText(secret())) throw new IllegalStateException("암호화 키 파일(" + keyFile + ")을 읽지 못해 메일 계정 비밀번호를 풀 수 없습니다.");
		try {
			byte[] all = Base64.getDecoder().decode(enc.startsWith("v1:") ? enc.substring(3) : enc);
			Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
			c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, all, 0, 12));
			return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
		} catch (Exception e) {
			throw new IllegalStateException("메일 계정 비밀번호를 풀 수 없습니다. 암호화 키 파일이 바뀌었거나 지워졌으면 메일 설정에서 비밀번호를 다시 저장하세요.");
		}
	}

	public static String rootMessage(Throwable e) {
		Throwable t = e;
		while (t.getCause() != null && t.getCause() != t) t = t.getCause();
		String m = t.getMessage();
		return m == null ? t.getClass().getSimpleName() : m;
	}

	private static String cut(String s, int n) { return s == null ? null : (s.length() > n ? s.substring(0, n) : s); }
	private static String str(Object o) { return o == null ? null : o.toString().trim(); }
	private static Integer toInt(Object o) {
		if (o instanceof Number n) return n.intValue();
		try { return o == null ? null : Integer.valueOf(o.toString().trim()); } catch (NumberFormatException e) { return null; }
	}
	private static String yn(Object o, String def) {
		if (o == null) return def;
		String s = o.toString().trim();
		if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("Y")) return "Y";
		if (s.equalsIgnoreCase("false") || s.equalsIgnoreCase("N")) return "N";
		return def;
	}

	/** 사업장 발송 계정 */
	public static class Account {
		public String key;          // db_key
		public String fromAddr;
		public String fromName;
		public boolean nameWithUser;
		public boolean bccSender;
		public JavaMailSenderImpl sender;
	}
}