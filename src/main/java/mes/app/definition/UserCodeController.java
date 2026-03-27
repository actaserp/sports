package mes.app.definition;

import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import mes.app.definition.service.UserCodeService;
import mes.domain.entity.User;
import mes.domain.model.AjaxResult;
import mes.domain.services.SqlRunner;


@RestController
@RequestMapping("/api/definition/code")
public class UserCodeController {

	@Autowired
	private UserCodeService syscodeService;

	@Autowired
	SqlRunner sqlRunner;


	@GetMapping("/read")
	public AjaxResult getCodeList(
			@RequestParam("txtCode") String txtCode
	) {
		AjaxResult result = new AjaxResult();
		result.data = this.syscodeService.getCodeList(txtCode);
		return result;
	}

	@GetMapping("/SystemCoderead")
	public AjaxResult getSystemCodeList(
			@RequestParam("txtCode") String txtCode,
			@RequestParam("txtCodeType") String txtCodeType,
			@RequestParam(value = "txtDescription") String txtDescription
	) {
		AjaxResult result = new AjaxResult();
		result.data = this.syscodeService.getSystemCodeList(txtCode, txtCodeType, txtDescription);
		return result;
	}

	@GetMapping("/detail")
	public AjaxResult getCode(@RequestParam("id") int id) {
		AjaxResult result = new AjaxResult();
		result.data = this.syscodeService.getCode(id);
		return result;
	}

	@GetMapping("/Systemcodedetail")
	public AjaxResult getSystemCode(@RequestParam("id") int id) {
		AjaxResult result = new AjaxResult();
		result.data = this.syscodeService.getSystemcCode(id);
		return result;
	}


	@PostMapping("/save")
	public AjaxResult saveCode(
			@RequestParam(value = "id", required = false) Integer id,
			@RequestParam("name") String value,
			@RequestParam("code") String code,
			@RequestParam(value = "parent_id", required = false) Integer parent_id,
			@RequestParam("description") String description,
			HttpServletRequest request,
			Authentication auth) {

		User user = (User) auth.getPrincipal();

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("code", code);
		param.addValue("value", value);
		param.addValue("description", description);
		param.addValue("parent_id", parent_id);
		param.addValue("user_id", user.getId());

		if (id == null) {
			String sql = """
				insert into sys_code ("Code", "Value", "Description", "Parent_id", _creater_id, _created, _modifier_id, _modified)
				values (:code, :value, :description, :parent_id, :user_id, now(), :user_id, now())
				""";
			sqlRunner.execute(sql, param);
		} else {
			param.addValue("id", id);
			String sql = """
				update sys_code
				set "Code"=:code, "Value"=:value, "Description"=:description, "Parent_id"=:parent_id,
				    _modifier_id=:user_id, _modified=now()
				where id=:id
				""";
			sqlRunner.execute(sql, param);
		}

		AjaxResult result = new AjaxResult();
		result.success = true;
		return result;
	}

	@PostMapping("/Systemcodesave")
	public AjaxResult Systemcodesave(
			@RequestParam(value = "id", required = false) Integer id,
			@RequestParam("code_type") String code_type,
			@RequestParam("name") String value,
			@RequestParam("code") String code,
			@RequestParam("description") String description,
			@RequestParam(value = "spjangcd") String spjangcd,
			HttpServletRequest request,
			Authentication auth) {

		User user = (User) auth.getPrincipal();

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue("code_type", code_type);
		param.addValue("code", code);
		param.addValue("value", value);
		param.addValue("description", description);
		param.addValue("spjangcd", spjangcd);
		param.addValue("user_id", user.getId());

		if (id == null) {
			String sql = """
        insert into sys_code ("CodeType", "Code", "Value", "Description", _creater_id, _created, _modifier_id, _modified)
        values (:code_type, :code, :value, :description, :user_id, getdate(), :user_id, getdate())
        """;
			sqlRunner.execute(sql, param);
		} else {
			param.addValue("id", id);
			String sql = """
        update sys_code
        set "CodeType"=:code_type, "Code"=:code, "Value"=:value, "Description"=:description,
            _modifier_id=:user_id, _modified=getdate()
        where id=:id
        """;
			sqlRunner.execute(sql, param);
		}

		AjaxResult result = new AjaxResult();
		result.success = true;
		return result;
	}


	@PostMapping("/delete")
	public AjaxResult deleteCode(@RequestParam("id") Integer id) {
		MapSqlParameterSource param = new MapSqlParameterSource("id", id);
		sqlRunner.execute("delete from sys_code where id=:id", param);
		return new AjaxResult();
	}


	@PostMapping("/SystemCodedelete")
	public AjaxResult deleteSystemCode(@RequestParam("id") Integer id) {
		MapSqlParameterSource param = new MapSqlParameterSource("id", id);
		sqlRunner.execute("delete from sys_code where id=:id", param);
		return new AjaxResult();
	}

	@GetMapping("/getvalue")
	public AjaxResult getValue(@RequestParam("code") String code) {
		AjaxResult result = new AjaxResult();
		result.data = this.syscodeService.getValue(code);
		return result;
	}
}
