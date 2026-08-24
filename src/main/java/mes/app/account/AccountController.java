package mes.app.account;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javax.annotation.Resource;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.transaction.Transactional;

import mes.app.MailService;
import mes.app.transaction.service.SalesInvoiceService;
import mes.domain.entity.Tb_xa012;
import mes.domain.repository.Tb_xa012Repository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.SingleColumnRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import mes.domain.repository.UserRepository;
import mes.domain.security.CustomAuthenticationToken;
import mes.domain.security.Pbkdf2Sha256;
import mes.domain.services.AccountService;
import mes.domain.services.SqlRunner;


@RestController
public class AccountController {
	
	@Autowired
	AccountService accountService;
		
    @Autowired
    UserRepository userRepository;
	
	@Autowired
	SqlRunner sqlRunner;

	@Autowired
	MailService emailService;

	@Autowired
	SalesInvoiceService salesInvoiceService;

	@Autowired
	Tb_xa012Repository xa012Repository;

	private final ConcurrentHashMap<String, String> tokenStore = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, Long> tokenExpiry = new ConcurrentHashMap<>();
	private Boolean flag;
	private Boolean flag_pw;
	
	@Resource(name="authenticationManager")
    private AuthenticationManager authManager;
	
	/* ----------------------------------------------------------------------------
   [1] GET /login — 자동로그인 쿠키 경로에 상태 검사 추가
   ---------------------------------------------------------------------------- */

	@GetMapping("/login")
	public ModelAndView loginPage(
		HttpServletRequest request,
		HttpServletResponse response,
		HttpSession session,
		Authentication auth) {

		// 1) 자동로그인 쿠키 검사
		if (auth == null) {
			Cookie[] cookies = request.getCookies();
			if (cookies != null) {
				for (Cookie cookie : cookies) {
					if (!"MES_AUTO_LOGIN".equals(cookie.getName())) {
						continue;
					}

					String username = cookie.getValue();
					User user = userRepository.findByUsername(username).orElse(null);

					// ▼ 변경: getActive() 만 보던 것 → 사업장 상태까지 검사
					String state = checkTenantState(user);

					if ("OK".equals(state)) {
						UsernamePasswordAuthenticationToken token =
							new UsernamePasswordAuthenticationToken(
								user, null, Collections.emptyList());

						SecurityContextHolder.getContext().setAuthentication(token);
						session.setAttribute("SPRING_SECURITY_CONTEXT", SecurityContextHolder.getContext());

						// ▼ 추가: 자동로그인 시에도 테넌트 세션 세팅 (기존 누락분)
						bindTenantSession(session, user);

						return new ModelAndView("redirect:/");
					} else {
						// 중지/미승인/비활성 → 쿠키 폐기하고 로그인 화면으로
						clearAutoLoginCookie(request, response);
						break;
					}
				}
			}
		}

		// 2) User-Agent 기반 모바일 여부 감지
		String uaHeader = request.getHeader("User-Agent");
		String userAgent = (uaHeader == null) ? "" : uaHeader.toLowerCase();   // ▼ NPE 방어
		boolean isMobile = userAgent.contains("mobile")
												 || userAgent.contains("android")
												 || userAgent.contains("iphone");

		ModelAndView mv = new ModelAndView(isMobile ? "mlogin" : "login");

		mv.addObject("userinfo", new HashMap<String, Object>());
		mv.addObject("gui", new HashMap<String, Object>());

		if (auth != null) {
			new SecurityContextLogoutHandler().logout(request, response, auth);
		}

		return mv;
	}


	/* ----------------------------------------------------------------------------
   [3] GET /logout — cookiePath 헬퍼 적용 (동작 동일, 중복 제거)
   ---------------------------------------------------------------------------- */

	@GetMapping("/logout")
	public void logout(HttpServletRequest request, HttpServletResponse response) throws IOException {

		Authentication auth = SecurityContextHolder.getContext().getAuthentication();

		this.accountService.saveLoginLog("logout", auth, request);
		new SecurityContextLogoutHandler().logout(request, response, auth);

		clearAutoLoginCookie(request, response);

		response.sendRedirect(request.getContextPath() + "/login");
	}


    /* ----------------------------------------------------------------------------
   [2] POST /login — 상태 검사 기준을 db_key 로 변경
   ---------------------------------------------------------------------------- */

	@PostMapping("/login")
	public AjaxResult postLogin(
		@RequestParam("username") final String username,
		@RequestParam("password") final String password,
		@RequestParam(value = "autoLogin", required = false) String autoLogin,
		final HttpServletRequest request,
		HttpServletResponse response) {

		AjaxResult result = new AjaxResult();
		HashMap<String, Object> data = new HashMap<>();
		result.data = data;

		UsernamePasswordAuthenticationToken authReq =
			new UsernamePasswordAuthenticationToken(username, password);
		authReq.setDetails(new mes.domain.security.TenantWebAuthenticationDetails(request));

		CustomAuthenticationToken auth = null;

		try {
			auth = (CustomAuthenticationToken) authManager.authenticate(authReq);
		} catch (InsufficientAuthenticationException e) {
			data.put("code", "null");
			return result;
		} catch (AuthenticationException e) {
			data.put("code", "NOUSER");
			return result;
		}

		if (auth == null) {
			result.success = false;
			data.put("code", "NOID");
			return result;
		}

		User user = (User) auth.getPrincipal();

		// ▼ 핵심 변경: spjangcd(항상 'ZZ') 대신 db_key 기준으로 사업장 상태 판정
		String state = checkTenantState(user);
		data.put("code", state);

		if (!"OK".equals(state)) {
			result.success = false;   // 중지/미승인/비활성은 실패로 처리
			return result;            // SecurityContext 세팅 없이 종료
		}

		// --- 인증 성공 ---
		this.accountService.saveLoginLog("login", auth, request);

		SecurityContext sc = SecurityContextHolder.getContext();
		sc.setAuthentication(auth);
		HttpSession session = request.getSession(true);
		session.setAttribute("SPRING_SECURITY_CONTEXT", sc);

		bindTenantSession(session, user);

		// 자동 로그인 쿠키 발급
		if ("on".equals(autoLogin)) {
			Cookie autoLoginCookie = new Cookie("MES_AUTO_LOGIN", username);
			autoLoginCookie.setHttpOnly(true);
			autoLoginCookie.setPath(cookiePath(request));
			autoLoginCookie.setMaxAge(60 * 60 * 24 * 365);
			response.addCookie(autoLoginCookie);
		}

		return result;
	}

	@GetMapping("/account/myinfo")
	public AjaxResult getUserInfo(Authentication auth){
		User user = (User)auth.getPrincipal();
		AjaxResult result = new AjaxResult();

		Map<String, Object> dicData = new HashMap<String, Object>();
		dicData.put("login_id", user.getUsername());
		dicData.put("name", user.getUserProfile().getName());
		dicData.put("userHp", user.getTel());
		dicData.put("email", user.getEmail());
		result.data = dicData;
		return result;
	}

	@Transactional
    @PostMapping("/account/myinfo/password_change")
    public AjaxResult userPasswordChange(
    		@RequestParam("name") final String name,
    		@RequestParam("loginPwd") final String loginPwd,
    		@RequestParam("loginPwd2") final String loginPwd2,
    		Authentication auth
    		) {

    	User user = (User)auth.getPrincipal();
        AjaxResult result = new AjaxResult();


        if (StringUtils.hasText(loginPwd)==false | StringUtils.hasText(loginPwd2)==false) {
        	result.success=false;
        	result.message="The verification password is incorrect.";
        	return result;
        }

        if(loginPwd.equals(loginPwd2)==false) {
        	result.success=false;
        	result.message="The verification password is incorrect.";
        	return result;
        }

		String pwSql = """
			UPDATE auth_user SET password=:password 
			WHERE id=:id AND spjangcd=:spjangcd
		""";
		MapSqlParameterSource pwParam = new MapSqlParameterSource();
		pwParam.addValue("password", Pbkdf2Sha256.encode(loginPwd2));
		pwParam.addValue("id", user.getId());
		pwParam.addValue("spjangcd", user.getSpjangcd());
		this.sqlRunner.execute(pwSql, pwParam);

        String sql = """
        	update user_profile set 
        	"Name"=:name, _modified = now(), _modifier_id=:id 
        	WHERE "User_id"=:userId AND spjangcd=:spjangcd
        """;

        MapSqlParameterSource dicParam = new MapSqlParameterSource();
        dicParam.addValue("name", name);
        dicParam.addValue("userId", user.getId());
		dicParam.addValue("spjangcd", user.getSpjangcd());
        this.sqlRunner.execute(sql, dicParam);


        return result;
    }

	/***
	 *  아이디 중복 확인
	 * **/
	@PostMapping("/useridchk")
	public AjaxResult IdChk(@RequestParam("userid") final String userid){

		AjaxResult result = new AjaxResult();


		Optional<User> user = userRepository.findByUsername(userid);


		if(!user.isPresent()){

			result.success = true;
			result.message = "사용할 수 있는 계정입니다.";
			return result;

		}else {
			result.success = false;
			result.message = "중복된 계정이 존재합니다.";
			return result;
		}


	}

	@PostMapping("/authentication")
	public AjaxResult Authentication(@RequestParam(value = "AuthenticationCode") String AuthenticationCode,
									 @RequestParam(value = "email", required = false) String email,
									 @RequestParam String type
	){

		AjaxResult result = verifyAuthenticationCode(AuthenticationCode, email);

		if(type.equals("new")){
			if(result.success){
				flag = true;
				result.message = "인증되었습니다.";

			}

		}else{
			if(result.success){
				flag_pw = true;
				result.message = "인증되었습니다.";
			}
		}

		return result;
	}

	private AjaxResult verifyAuthenticationCode(String code, String mail){

		AjaxResult result = new AjaxResult();

		String storedToken = tokenStore.get(mail);
		if(storedToken != null && storedToken.equals(code)){
			long expiryTime = tokenExpiry.getOrDefault(mail, 0L);
			if(System.currentTimeMillis() > expiryTime){
				result.success = false;
				result.message = "인증 코드가 만료되었습니다.";
				tokenStore.remove(mail);
				tokenExpiry.remove(mail);
			} else {
				result.success = true;
				result.message = "비밀번호가 변경되었습니다.";
			}
		}else{
			result.success = false;
			result.message = "인증 코드가 유효하지 않습니다.";
		}
		return result;
	}


	@PostMapping("/user-auth/AuthenticationEmail")
	public AjaxResult PwSearch(@RequestParam(value = "usernm", required = false) final String usernm,
							   @RequestParam("mail") final String mail,
							   @RequestParam("content") final String content,
							   @RequestParam String type
	){

		AjaxResult result = new AjaxResult();

		if(type.equals("new")){
			if(!usernm.isEmpty() && type.equals("new")){
				sendEmailLogic(mail, usernm, content);

				result.success = true;
				result.message = "인증 메일이 발송되었습니다.";
				return result;
			}
			return result;
		}else{
			boolean flag = userRepository.existsByUsernameAndEmail(usernm, mail);

			if(flag) {
				sendEmailLogic(mail, usernm, content);

				result.success = true;
				result.message = "인증 메일이 발송되었습니다.";
			}else {
				result.success = false;
				result.message = "해당 사용자가 존재하지 않습니다.";
			}

			return result;
		}


	}

	private void sendEmailLogic(String mail, String usernm, String content){
		Random random = new Random();
		int randomNum = 100000 + random.nextInt(900000); // 100000부터 999999까지의 랜덤 난수 생성
		String verificationCode = String.valueOf(randomNum); // 정수를 문자열로 변환
		emailService.sendVerificationEmail(mail, usernm, verificationCode, content);

		tokenStore.put(mail, verificationCode);
		tokenExpiry.put(mail, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(3));

	}

	@PostMapping("/biz/save")
	@Transactional
	public AjaxResult saveBiz(@RequestParam Map<String, Object> params) {
		AjaxResult result = new AjaxResult();

		try {
			// 1. 데이터 추출
			String bizName = (String) params.get("bizName");
			String bizNo = (String) params.get("bizNo"); // 000-00-00000 형식
			String bizType = (String) params.get("bizType");
			String bizItem = (String) params.get("bizItem");
			String bizAddr = (String) params.get("bizAddr");
			String billPlanId = (String) params.get("bill_plan_id");
			String corpNum = bizNo.replaceAll("-", ""); // 하이픈 제거

			// 2. 휴/폐업 및 유효성 체크
			// salesInvoiceService를 사용하여 국세청 기준 유효성 검사
			if (salesInvoiceService.validateSingleBusiness(corpNum) == null) {
				result.success = false;
				result.message = "휴/폐업 또는 유효하지 않은 사업자번호입니다.\n사업자 등록번호를 확인해주세요.";
				return result;
			}

			// 3. 이미 가입된 사업자인지 확인 (saupnum 컬럼 기준)
			String checkSql = "SELECT COUNT(*) FROM tb_xa012 WHERE saupnum = :saupnum";
			MapSqlParameterSource checkParam = new MapSqlParameterSource("saupnum", corpNum);

			// queryForObject를 사용하여 Integer 클래스로 결과를 받습니다.
			Integer count = this.sqlRunner.queryForObject(
					checkSql,
					checkParam,
					new SingleColumnRowMapper<Integer>(Integer.class)
			);

			if (count != null && count > 0) {
				result.success = false;
				result.message = "이미 등록된 사업자번호입니다.";
				return result;
			}

			// 4. spjangcd 결정
			String spjangcd = generateUniqueSpjangCd();

			// 5. tb_xa012 저장 SQL (엔티티 컬럼명 매핑)
			String sql = """
            INSERT INTO tb_xa012 
            (spjangcd, saupnum, spjangnm, biztype, item, adresa, bill_plans_id, state, subscriptiondate)
            VALUES 
            (:spjangcd, :saupnum, :spjangnm, :biztype, :item, :adresa, :bill_plans_id, :state, :subscriptiondate)
        """;

			MapSqlParameterSource dicParam = new MapSqlParameterSource();
			dicParam.addValue("spjangcd", spjangcd);
			dicParam.addValue("saupnum", corpNum); // 하이픈 제거된 번호
			dicParam.addValue("spjangnm", bizName);
			dicParam.addValue("biztype", bizType);
			dicParam.addValue("item", bizItem);
			dicParam.addValue("adresa", bizAddr);
			dicParam.addValue("bill_plans_id", Integer.parseInt(billPlanId));
			dicParam.addValue("state", "신청"); // 초기 상태값
			dicParam.addValue("subscriptiondate", LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")));

			this.sqlRunner.execute(sql, dicParam);

			// 6. 성공 시 반환 (유저 저장 시 spjangcd 연결)
			result.success = true;
			result.data = spjangcd;

		} catch (Exception e) {
			result.success = false;
			result.message = "사업장 정보 저장 중 오류가 발생했습니다: " + e.getMessage();
			e.printStackTrace();
		}

		return result;
	}

	// 중복되지 않는 2자리 코드 생성 함수 (내부 로직)
	private String generateUniqueSpjangCd() {
		String chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
		Random random = new Random();
		String newCd = "";
		boolean isDuplicate = true;

		while (isDuplicate) {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 2; i++) {
				sb.append(chars.charAt(random.nextInt(chars.length())));
			}
			newCd = sb.toString();

			// DB에 존재하는지 확인
			String checkSql = "SELECT COUNT(*) FROM tb_xa012 WHERE spjangcd = :spjangcd";
			MapSqlParameterSource param = new MapSqlParameterSource("spjangcd", newCd);

			// 이전에 해결한 RowMapper 방식 적용
			Integer count = this.sqlRunner.queryForObject(checkSql, param, (rs, rowNum) -> rs.getInt(1));

			if (count == null || count == 0) {
				isDuplicate = false; // 중복 아니면 탈출
			}
		}
		return newCd;
	}

	@PostMapping("/user-auth/save")
	@Transactional
	public AjaxResult saveUser(
			@RequestParam(value="idx", required = false) Integer id,
			@RequestParam(value="name") String Name,		//이름 (user_profile.Name)
			@RequestParam(value="id") String login_id, //사번 (auth_user.username)
			@RequestParam(value="email", required = false, defaultValue = "") String email,
			@RequestParam(value="Factory_id", required = false) Integer Factory_id,
			@RequestParam(value="Depart_id", required = false) Integer Depart_id,
			@RequestParam(value="UserGroup_id", required = false) Integer UserGroup_id,
			@RequestParam(value="lang_code", required = false) String lang_code,
			@RequestParam(value="is_active", required = false) Boolean is_active,
			@RequestParam(value="password") String password,
			@RequestParam(value="tel", required = false) String tel,
			@RequestParam(value="spjangcd") String spjangcd,
			HttpServletRequest request,
			Authentication auth
	) {

		AjaxResult result = new AjaxResult();

		// 기본값 지정
		if (Factory_id == null) {
			Factory_id = 1;
		}
		if (Depart_id == null) {
			Depart_id = 1;
		}
		if (UserGroup_id == null) {
			UserGroup_id = 2;
		}


		String sql = null;
		User user = null;

		Timestamp today = new Timestamp(System.currentTimeMillis());
		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		boolean username_chk = this.userRepository.findByUsername(login_id).isEmpty();

		if(is_active == null) {
			is_active = false;
		}


		// new data일 경우
		if (id==null) {
			if (username_chk == false) {
				result.success = false;
				result.message="중복된 사번이 존재합니다.";
				return result;
			}
			user = new User();
			String encodedPassword = Pbkdf2Sha256.encode(password);
			user.setPassword(encodedPassword);
			user.setSuperUser(false);
			user.setLast_name("");
			user.setIs_staff(false);

		}

		user.setUsername(login_id);
		user.setFirst_name(Name);
		user.setEmail(email);
		user.setTel(tel);
		user.setDate_joined(today);
		user.setActive(is_active);
		user.setSpjangcd("ZZ");
		user.setDbKey(spjangcd);


		user = this.userRepository.save(user);

		result.data = user;

		return result;
	}


	@PostMapping("/user-auth/searchAccount")
	public AjaxResult IdSearch(@RequestParam("usernm") final String usernm,
							   @RequestParam("mail") final String mail){

		AjaxResult result = new AjaxResult();

		List<String> user = userRepository.findByFirstNameAndEmailNative(usernm, mail);

		if(!user.isEmpty()){
			result.success = true;
			result.data = user;
		}else {
			result.success = false;
			result.message = "해당 사용자가 존재하지 않습니다.";
		}
		return result;
	}

	@PostMapping("/account/myinfosave")
	public AjaxResult setUserInfo(
			@RequestParam("name") final String name,
			@RequestParam("loginPwd") final String loginPwd,
			@RequestParam("loginPwd2") final String loginPwd2,
			@RequestParam("userHp") final String userHp,
			Authentication auth
	) {
		MapSqlParameterSource dicParam = new MapSqlParameterSource();
		AjaxResult result = new AjaxResult();
		User user = (User)auth.getPrincipal();

		if (StringUtils.hasText(loginPwd)==false | StringUtils.hasText(loginPwd2)==false) {
			result.success=false;
			result.message="The verification password is incorrect.";
			return result;
		}

		if(loginPwd.equals(loginPwd2)==false) {
			result.success=false;
			result.message="비밀번호와 확인이 서로 맞지않습니다.";
			return result;
		}

		String encodedPWD = Pbkdf2Sha256.encode(loginPwd2);
		if(name != null && !name.isEmpty()) {
			dicParam.addValue("name", name);
		}
		if(userHp != null && !userHp.isEmpty()) {
			dicParam.addValue("userHp", userHp);
		}
		if(loginPwd2 != null && !loginPwd2.isEmpty()) {
			dicParam.addValue("encodedPWD", encodedPWD);
		}
		//user.getUserProfile().setName(name);
		String authSql = """
        	update auth_user set 
        	password = :encodedPWD, tel = :userHp, first_name = :name 
        	where id=:id 
        """;

		String profileSql = """
        	update user_profile set 
        	"Name"=:name, _modified = now(), _modifier_id=:id 
        	where "User_id"=:id 
        """;

		String personSql = """
        	update person set 
        	"Name"=:name, _modified = now(), _modifier_id=:id 
        	where id=:personid 
        """;


		dicParam.addValue("name", name);
		dicParam.addValue("id", user.getId());
		dicParam.addValue("personid", user.getPersonid());
		this.sqlRunner.execute(authSql, dicParam);
		this.sqlRunner.execute(profileSql, dicParam);
		this.sqlRunner.execute(personSql, dicParam);

		result.message="사용자 정보가 수정되었습니다.\n다시 로그인하여 주십시오";


		return result;
	}

	@GetMapping("/bill_plan_read") // Post로 통일
	public AjaxResult getBillPlans(){
		AjaxResult result = new AjaxResult();
		try {
			List<Map<String, Object>> list = accountService.getBillPlans();
			result.data = list;
			result.success = true; // 성공 플래그 명시
		} catch (Exception e) {
			result.success = false;
			result.message = "요금제 정보를 불러오는데 실패했습니다.";
		}
		return result;
	}

	/* ----------------------------------------------------------------------------
   [0] 공통 헬퍼 — 클래스 하단(private 메서드 영역)에 추가
   ---------------------------------------------------------------------------- */

	/** 사용자의 실제 소속 사업장 코드. db_key 우선, 없으면 spjangcd 폴백. */
	private String resolveTenantCd(User user) {
		if (user == null) return null;
		String dbKey = user.getDbKey();
		if (dbKey != null && !dbKey.isBlank()) return dbKey;
		return user.getSpjangcd();
	}

	/**
	 * 사업장 상태 판정.
	 * @return "OK" | "STOPPED" | "NOTCONFIRM" | "noactive"
	 */
	private String checkTenantState(User user) {
		if (user == null || !Boolean.TRUE.equals(user.getActive())) {
			return "noactive";
		}

		String tenantCd = resolveTenantCd(user);
		if (tenantCd == null || tenantCd.isBlank()) {
			return "noactive";
		}

		Tb_xa012 xa012 = xa012Repository.findById(tenantCd).orElse(null);
		if (xa012 == null) {
			return "noactive";
		}
		if ("중지".equals(xa012.getState())) {
			return "STOPPED";
		}
		if (!"O".equals(xa012.getState())) {
			return "NOTCONFIRM";
		}
		return "OK";
	}

	/** 컨텍스트 경로 기준 쿠키 path (쿠키 생성/삭제 시 반드시 동일해야 함). */
	private String cookiePath(HttpServletRequest request) {
		String ctx = request.getContextPath();
		return (ctx == null || ctx.isEmpty()) ? "/" : ctx;
	}

	/** 자동로그인 쿠키 제거. */
	private void clearAutoLoginCookie(HttpServletRequest request, HttpServletResponse response) {
		Cookie clearCookie = new Cookie("MES_AUTO_LOGIN", null);
		clearCookie.setMaxAge(0);
		clearCookie.setPath(cookiePath(request));
		response.addCookie(clearCookie);
	}

	/** 인증 성공 후 세션에 테넌트 정보 세팅. */
	private void bindTenantSession(HttpSession session, User user) {
		String tenantCd = resolveTenantCd(user);
		if (tenantCd != null && !tenantCd.isBlank()) {
			session.setAttribute("db_key", tenantCd);
			session.setAttribute("spjangcd", tenantCd);
		}
	}

}