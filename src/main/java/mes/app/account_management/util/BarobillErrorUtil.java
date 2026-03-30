package mes.app.account_management.util;

import java.util.Map;

public class BarobillErrorUtil {

	private static final Map<Integer, String> BAROBILL_ERRORS = Map.ofEntries(

		// ===== 기본 오류코드 =====
		Map.entry(-10000, "알 수 없는 오류가 발생했습니다. 바로빌로 문의 바랍니다."),
		Map.entry(-10003, "연동서비스가 점검 중입니다."),
		Map.entry(-10004, "해당 기능은 더 이상 사용되지 않습니다."),
		Map.entry(-10007, "해당 기능을 사용할 수 없습니다."),
		Map.entry(-10005, "최대 100건까지만 사용 가능합니다."),
		Map.entry(-10006, "최대 1000건까지만 사용 가능합니다."),
		Map.entry(-10008, "날짜 형식이 잘못되었습니다."),
		Map.entry(-10010, "입력된 건이 없습니다."),
		Map.entry(-10011, "조회 가능 기간을 초과했습니다."),
		Map.entry(-10148, "조회 기간이 잘못되었습니다."),
		Map.entry(-40001, "파일을 찾을 수 없습니다."),
		Map.entry(-40002, "빈 파일입니다 (0byte)."),

		// ===== 연동정보 오류 =====
		Map.entry(-10002, "해당 인증키를 찾을 수 없습니다."),
		Map.entry(-10001, "해당 인증키와 연결된 연계사가 아닙니다."),
		Map.entry(-24005, "사업자번호와 아이디가 일치하지 않습니다."),

		// ===== 카드 관련 오류 =====
		Map.entry(-50101, "카드를 찾을 수 없습니다."),
		Map.entry(-50102, "카드를 조회할 권한이 없습니다."),
		Map.entry(-50111, "카드사 코드가 잘못 입력되었습니다."),
		Map.entry(-50112, "카드유형이 잘못 입력되었습니다."),
		Map.entry(-50113, "카드번호가 잘못 입력되었습니다."),
		Map.entry(-50114, "카드사 홈페이지 아이디가 잘못 입력되었습니다."),
		Map.entry(-50115, "카드사 홈페이지 비밀번호가 잘못 입력되었습니다."),
		Map.entry(-50116, "유효한 카드정보가 아닙니다."),
		Map.entry(-50117, "카드정보 검증에 실패했습니다."),
		Map.entry(-50118, "이미 등록된 카드번호입니다."),
		Map.entry(-50119, "수집주기가 잘못 입력되었습니다."),
		Map.entry(-50120, "중복된 카드번호는 등록할 수 없습니다."),
		Map.entry(-50151, "사용내역 키(UseKey)가 잘못 입력되었습니다."),
		Map.entry(-50152, "카드 사용내역을 찾을 수 없습니다."),
		Map.entry(-50161, "현대카드(개인)은 카드사 보안으로 서비스가 중단되었습니다.")
	);

	public static String getErrorMessage(int code) {
		return BAROBILL_ERRORS.getOrDefault(
			code,
			"바로빌 요청 실패. 오류코드: " + code
		);
	}
}