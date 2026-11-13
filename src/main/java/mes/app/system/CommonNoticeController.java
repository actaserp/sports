package mes.app.system;

import lombok.extern.slf4j.Slf4j;
import mes.app.common.TenantContext;
import mes.app.files.NcpObjectStorageService;
import mes.app.system.service.CommonNoticeService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import javax.servlet.http.HttpServletResponse;
import java.io.BufferedOutputStream;
import java.net.URLEncoder;
import java.util.*;

/**
 * 전체공지 (거래처 공통 공지) — 2026-11-13
 *
 *  운영사(superuser) 관리 — 시스템관리 > 전체공지 관리
 *   GET  /api/system/common_notice/list?keyword=
 *   GET  /api/system/common_notice/detail?id=
 *   GET  /api/system/common_notice/tenants        대상 선택용 거래처 목록
 *   POST /api/system/common_notice/save           multipart: id, title, content, from_date, to_date, target_all, popup_yn, targets[], files[]
 *   POST /api/system/common_notice/delete         id
 *   POST /api/system/common_notice/deleteFile     file_id
 *
 *  거래처 사용자 (로그인한 누구나)
 *   GET  /api/system/common_notice/popup          로그인 팝업용 전체공지 + 내가 안보기 한 사업장 공지 번호
 *   POST /api/system/common_notice/hide           kind(C/T), id, mode(today/forever)
 *   GET  /api/system/common_notice/download?file_id=   첨부 받기 (대상 거래처만)
 */
@Slf4j
@RestController
@RequestMapping("/api/system/common_notice")
public class CommonNoticeController {

	@Autowired
	CommonNoticeService service;

	@Autowired
	NcpObjectStorageService storageService;

	// ── 운영사 관리 ─────────────────────────────────────────

	@GetMapping("/list")
	public AjaxResult list(@RequestParam(value = "keyword", required = false) String keyword, Authentication auth) {
		AjaxResult r = new AjaxResult();
		if (!isSuper(auth)) return deny(r);
		r.data = service.list(keyword);
		return r;
	}

	@GetMapping("/detail")
	public AjaxResult detail(@RequestParam("id") int id, Authentication auth) {
		AjaxResult r = new AjaxResult();
		if (!isSuper(auth)) return deny(r);
		r.data = service.detail(id);
		return r;
	}

	@GetMapping("/tenants")
	public AjaxResult tenants(Authentication auth) {
		AjaxResult r = new AjaxResult();
		if (!isSuper(auth)) return deny(r);
		r.data = service.tenants();
		return r;
	}

	@PostMapping("/save")
	public AjaxResult save(@RequestParam(value = "id", required = false) Integer id,
												 @RequestParam(value = "title", required = false) String title,
												 @RequestParam(value = "content", required = false) String content,
												 @RequestParam(value = "from_date", required = false) String fromDate,
												 @RequestParam(value = "to_date", required = false) String toDate,
												 @RequestParam(value = "target_all", required = false) String targetAll,
												 @RequestParam(value = "popup_yn", required = false) String popupYn,
												 @RequestParam(value = "targets", required = false) List<String> targets,
												 @RequestParam(value = "files", required = false) List<MultipartFile> files,
												 Authentication auth) {
		AjaxResult r = new AjaxResult();
		if (!isSuper(auth)) return deny(r);
		try {
			r.data = service.save(id, title, content, fromDate, toDate, targetAll, popupYn, targets, files,
				((User) auth.getPrincipal()).getId());
		} catch (RuntimeException e) {
			log.error("[CommonNotice] 저장 오류", e);
			r.success = false;
			r.message = e.getMessage();
		}
		return r;
	}

	@PostMapping("/delete")
	public AjaxResult delete(@RequestParam("id") int id, Authentication auth) {
		AjaxResult r = new AjaxResult();
		if (!isSuper(auth)) return deny(r);
		service.delete(id);
		return r;
	}

	@PostMapping("/deleteFile")
	public AjaxResult deleteFile(@RequestParam("file_id") int fileId, Authentication auth) {
		AjaxResult r = new AjaxResult();
		if (!isSuper(auth)) return deny(r);
		service.deleteFile(fileId);
		return r;
	}

	// ── 거래처 사용자 ───────────────────────────────────────

	@GetMapping("/popup")
	public AjaxResult popup(Authentication auth) {
		AjaxResult r = new AjaxResult();
		User u = user(auth);
		if (u == null) { r.success = false; return r; }
		String key = TenantContext.getDbKey();
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("common", service.popupNotices(key, u.getId()));
		d.put("hiddenTenant", service.hiddenTenantIds(key, u.getId()));
		r.data = d;
		return r;
	}

	@PostMapping("/hide")
	public AjaxResult hide(@RequestParam("kind") String kind, @RequestParam("id") int id,
												 @RequestParam(value = "mode", defaultValue = "forever") String mode, Authentication auth) {
		AjaxResult r = new AjaxResult();
		User u = user(auth);
		if (u == null) { r.success = false; return r; }
		try {
			service.hide(u.getId(), kind, TenantContext.getDbKey(), id, mode);
		} catch (RuntimeException e) {
			r.success = false;
			r.message = e.getMessage();
		}
		return r;
	}

	@GetMapping("/download")
	public void download(@RequestParam("file_id") int fileId, Authentication auth, HttpServletResponse response) throws Exception {
		User u = user(auth);
		Map<String, Object> f = u == null ? null
															: service.fileForDownload(fileId, TenantContext.getDbKey(), Boolean.TRUE.equals(u.getSuperUser()));
		if (f == null) {
			response.sendError(HttpServletResponse.SC_NOT_FOUND, "파일을 찾을 수 없습니다.");
			return;
		}
		String key = String.valueOf(f.get("object_key"));
		try (ResponseInputStream<GetObjectResponse> in = storageService.download(key);
				 BufferedOutputStream out = new BufferedOutputStream(response.getOutputStream())) {
			response.setContentType("application/octet-stream");
			response.setHeader("Content-Disposition",
				"attachment; filename*=UTF-8''" + URLEncoder.encode(String.valueOf(f.get("file_name")), "UTF-8").replace("+", "%20"));
			in.transferTo(out);
			out.flush();
		} catch (Exception e) {
			log.error("[CommonNotice] 다운로드 오류 (key={}): {}", key, e.getMessage(), e);
			if (!response.isCommitted()) response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "다운로드 오류");
		}
	}

	// ────────────────────────────────────────────────────

	private static User user(Authentication auth) {
		return auth != null && auth.getPrincipal() instanceof User u ? u : null;
	}

	private static boolean isSuper(Authentication auth) {
		User u = user(auth);
		return u != null && Boolean.TRUE.equals(u.getSuperUser());
	}

	private static AjaxResult deny(AjaxResult r) {
		r.success = false;
		r.message = "운영사 관리자만 사용할 수 있습니다.";
		return r;
	}
}