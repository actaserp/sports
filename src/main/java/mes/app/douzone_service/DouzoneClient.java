package mes.app.douzone_service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

@Slf4j
@Component
public class DouzoneClient {

	private static final String API_PATH_AUTO_DOCU = "/apiproxy/api11A16"; //자동전표데이터조회 API
	private static final String API_PATH_AUTO_DOCU_SAVE = "/apiproxy/api11A10"; // 등록(저장)
	private static final String API_PATH_ACCT_SEARCH = "/apiproxy/api11A02"; //계정과목조회 (acctNm LIKE)
	private static final String API_PATH_AUTO_DOCU_DELETE = "/apiproxy/api11A17";	//삭제
	private static final String API_PATH_TR_SEARCH = "/apiproxy/api16S11"; // 거래처조회

	private final RestTemplate restTemplate;
	private final DouzoneProperties props;

	public DouzoneClient(DouzoneProperties props) {
		this.restTemplate = new RestTemplate();
		this.props = props;
	}

	// 자동전표데이터조회 (11A16)
	public String callAutoDocuList(String coCd,
																 String divCd,
																 String frDt,
																 String toDt,
																 String docuTy,
																 int viewPage,
																 int viewCount) {

		try {
			// 1) transaction-id, timestamp 생성
			String transactionId = generateTransactionId();
			String timestamp     = String.valueOf(System.currentTimeMillis() / 1000); // Unix time (초)

			// 2) wehago-sign = Base64( HMACSHA256(hashKey, accessToken + transactionId + timestamp + url(path) ) )
			String value      = props.getAccessToken()
														+ transactionId
														+ timestamp
														+ API_PATH_AUTO_DOCU;
			String wehagoSign = hmacSha256Base64(props.getHashKey(), value);

			// 3) Header 세팅
			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);

			headers.set("callerName",    props.getCallerName());
			headers.set("Authorization", "Bearer " + props.getAccessToken());
			headers.set("transaction-id", transactionId);
			headers.set("timestamp",      timestamp);
			if (props.getGroupSeq() != null) {
				headers.set("groupSeq", props.getGroupSeq());
			}
			headers.set("wehago-sign", wehagoSign);

			// 4) Body 세팅 (컨트롤러에서 넘긴 값 그대로 사용)
			Map<String, Object> body = new HashMap<>();
			body.put("coCd",      coCd);      // 회사코드
			body.put("divCd",     divCd);     // 사업장
			body.put("frDt",      frDt);      // 조회시작일 (yyyyMMdd)
			body.put("toDt",      toDt);      // 조회종료일 (yyyyMMdd)
			body.put("viewPage",  viewPage);   // 몇 페이지
			body.put("viewCount", viewCount);  // 페이지당 건수

			String url = props.getBaseUrl() + API_PATH_AUTO_DOCU;

			// 5) 로그
//			log.info("[Douzone] 11A16 요청 URL   : {}", url);
//			log.info("[Douzone] 11A16 Header    : callerName={}, groupSeq={}, txId={}, ts={}",
//				props.getCallerName(), props.getGroupSeq(), transactionId, timestamp);
//			log.info("[Douzone] 11A16 Body      : {}", body);

			// 6) 호출
			HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

			ResponseEntity<String> response =
				restTemplate.postForEntity(url, entity, String.class);

//			log.info("[Douzone] 11A16 응답 코드 : {}", response.getStatusCodeValue());
//			log.info("[Douzone] 11A16 응답 바디 : {}", response.getBody());

			return response.getBody();

		} catch (Exception e) {
			log.error("[Douzone] 11A16 호출 중 오류", e);
			throw new RuntimeException("Douzone autoDocu list error", e);
		}
	}

	public Map<String, Object> callAutoDocuSave(Map<String, Object> payload) {

		try {
			String transactionId = generateTransactionId();
			String timestamp = String.valueOf(System.currentTimeMillis() / 1000);

			String value = props.getAccessToken()
											 + transactionId
											 + timestamp
											 + API_PATH_AUTO_DOCU_SAVE;
			String wehagoSign = hmacSha256Base64(props.getHashKey(), value);

			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);
			headers.set("callerName", props.getCallerName());
			headers.set("Authorization", "Bearer " + props.getAccessToken());
			headers.set("transaction-id", transactionId);
			headers.set("timestamp", timestamp);
			if (props.getGroupSeq() != null) {
				headers.set("groupSeq", props.getGroupSeq());
			}
			headers.set("wehago-sign", wehagoSign);

			HttpEntity<Map<String, Object>> entity =
				new HttpEntity<>(payload, headers);

			String url = props.getBaseUrl() + API_PATH_AUTO_DOCU_SAVE;

			// ✅ JSON 문자열로 로깅 (예외 방어)
			try {
				ObjectMapper om = new ObjectMapper();
				String json = om.writeValueAsString(payload);
//				log.info("[Douzone] 자동전표등록 payload(JSON) = {}", json);
			} catch (com.fasterxml.jackson.core.JsonProcessingException e) {
//				log.warn("[Douzone] payload JSON 직렬화 실패, map.toString()으로 대신 로깅", e);
//				log.info("[Douzone] 자동전표등록 payload(Map) = {}", payload);
			}

//			log.info("[Douzone] 자동전표등록 URL   : {}", url);
//			log.info("[Douzone] 자동전표등록 Header: callerName={}, groupSeq={}, txId={}, ts={}",
//				props.getCallerName(), props.getGroupSeq(), transactionId, timestamp);
//			log.info("[Douzone] 자동전표등록 Body  : {}", payload);

			ResponseEntity<Map> response =
				restTemplate.postForEntity(url, entity, Map.class);

//			log.info("[Douzone] 자동전표등록 응답 코드 : {}", response.getStatusCodeValue());
//			log.info("[Douzone] 자동전표등록 응답 바디 : {}", response.getBody());

			return (Map<String, Object>) response.getBody();

		} catch (Exception e) {
			log.error("[Douzone] 자동전표등록 API 호출 중 오류", e);
			throw new RuntimeException("Douzone autoDocu save error", e);
		}
	}

	public Map<String, Object> callAccountList(String coCd) {

		try {
			String path = "/apiproxy/api11A02";

			String transactionId = generateTransactionId();
			String timestamp = String.valueOf(System.currentTimeMillis() / 1000);

			String value = props.getAccessToken() + transactionId + timestamp + path;
			String wehagoSign = hmacSha256Base64(props.getHashKey(), value);

			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);
			headers.set("callerName", props.getCallerName());
			headers.set("Authorization", "Bearer " + props.getAccessToken());
			headers.set("transaction-id", transactionId);
			headers.set("timestamp", timestamp);
			headers.set("wehago-sign", wehagoSign);
			headers.set("groupSeq", props.getGroupSeq());

			// 요청 body
			Map<String, Object> body = new HashMap<>();
			body.put("coCd", coCd);
			body.put("viewPage", 1);
			body.put("viewCount", 5000);

			HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
			String url = props.getBaseUrl() + path;

			ResponseEntity<String> response =
				restTemplate.postForEntity(url, entity, String.class);

//			log.info("[Douzone] 계정과목조회 응답: {}", response.getBody());

			ObjectMapper mapper = new ObjectMapper();
			return mapper.readValue(response.getBody(), Map.class);

		} catch (Exception e) {
			throw new RuntimeException("계정과목조회 호출 오류", e);
		}
	}

	// 계정별관리항목조회 (/apiproxy/api11A03)
	public Map<String, Object> callAccountCtrlList(String coCd, String acctCd) {

		try {
			String path = "/apiproxy/api11A03";

			// 1) transaction-id, timestamp
			String transactionId = generateTransactionId();
			String timestamp = String.valueOf(System.currentTimeMillis() / 1000);

			// 2) wehago-sign 생성
			String value = props.getAccessToken() + transactionId + timestamp + path;
			String wehagoSign = hmacSha256Base64(props.getHashKey(), value);

			// 3) Header 세팅
			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);
			headers.set("callerName", props.getCallerName());
			headers.set("Authorization", "Bearer " + props.getAccessToken());
			headers.set("transaction-id", transactionId);
			headers.set("timestamp", timestamp);
			headers.set("wehago-sign", wehagoSign);
			headers.set("groupSeq", props.getGroupSeq());

			// 4) Body 세팅
			Map<String, Object> body = new HashMap<>();
			body.put("coCd", coCd);
			body.put("acctCd", acctCd);
			body.put("viewPage", 1);
			body.put("viewCount", 100);   // 적당히, 필요하면 더 크게

			HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
			String url = props.getBaseUrl() + path;

			ResponseEntity<String> response =
				restTemplate.postForEntity(url, entity, String.class);

//			log.info("[Douzone] 계정별관리항목조회 응답: {}", response.getBody());

			ObjectMapper mapper = new ObjectMapper();
			return mapper.readValue(response.getBody(), Map.class);

		} catch (Exception e) {
			throw new RuntimeException("계정별관리항목조회 호출 오류", e);
		}
	}

	/**
	 * 계정과목조회 API (11A02)
	 * - acctNm LIKE 검색으로 계정목록 조회
	 * - resultData(List<Map>) 만 그대로 리턴
	 */
	public List<Map<String, Object>> searchAccountByName(String coCd, String acctNm) {

		try {
			// 1) transaction-id, timestamp 생성 (callAutoDocuList와 동일 패턴)
			String transactionId = generateTransactionId(); // 30자리 랜덤
			String timestamp = String.valueOf(System.currentTimeMillis() / 1000); // Unix time (초)

			// 2) wehago-sign = Base64( HMACSHA256(hashKey, accessToken + transactionId + timestamp + url(path) ) )
			//    🔹 여기서 path는 11A02의 PATH 사용
			String value = props.getAccessToken() + transactionId + timestamp + API_PATH_ACCT_SEARCH;
			String wehagoSign = hmacSha256Base64(props.getHashKey(), value);

			// 3) Header 세팅 (callAutoDocuList와 완전히 동일 포맷)
			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);

			headers.set("callerName", props.getCallerName());
			headers.set("Authorization", "Bearer " + props.getAccessToken());
			headers.set("transaction-id", transactionId);
			headers.set("timestamp", timestamp);
			if (props.getGroupSeq() != null) {
				headers.set("groupSeq", props.getGroupSeq());
			}
			headers.set("wehago-sign", wehagoSign);

			// 4) Body 세팅 (계정과목 조회용 파라미터)
			Map<String, Object> body = new HashMap<>();
			body.put("coCd", coCd);
			body.put("acctNm", acctNm);   // LIKE 검색어
			body.put("viewCount", 200);
			body.put("viewPage", 1);

			HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

			// 5) 최종 URL
			String url = props.getBaseUrl() + API_PATH_ACCT_SEARCH;

//			log.info("[Douzone] 11A02 요청 URL   : {}", url);
//			log.info("[Douzone] 11A02 Header    : callerName={}, groupSeq={}, txId={}, ts={}",
//				props.getCallerName(), props.getGroupSeq(), transactionId, timestamp);
//			log.info("[Douzone] 11A02 Body      : {}", body);

			ResponseEntity<Map> response =
				restTemplate.postForEntity(url, entity, Map.class);

//			log.info("[Douzone] 11A02 응답 코드 : {}", response.getStatusCodeValue());
//			log.info("[Douzone] 11A02 응답 바디 : {}", response.getBody());

			Map<String, Object> resBody = response.getBody();
			if (resBody == null) {
				throw new IllegalStateException("11A02 응답이 null 입니다.");
			}

			Object rcObj = resBody.get("resultCode");
			int resultCode;
			if (rcObj instanceof Number) {
				resultCode = ((Number) rcObj).intValue();
			} else if (rcObj != null) {
				resultCode = Integer.parseInt(rcObj.toString());
			} else {
				throw new IllegalStateException("11A02 resultCode 없음: " + resBody);
			}

			if (resultCode != 0) {
				String msg = (String) resBody.get("resultMsg");
				throw new IllegalStateException("더존 11A02 오류: " + msg);
			}

			@SuppressWarnings("unchecked")
			List<Map<String, Object>> resultData =
				(List<Map<String, Object>>) resBody.get("resultData");

			if (resultData == null) {
				resultData = Collections.emptyList();
			}

//			log.info("[Douzone] 계정과목조회({}) '{}' 결과 {}건", coCd, acctNm, resultData.size());

			return resultData;

		} catch (Exception e) {
			throw new RuntimeException("계정과목조회(11A02) 호출 중 오류", e);
		}
	}

	//삭제
	public Map<String, Object> callAutoDocuDelete(Map<String, Object> payload) {
		try {
			String transactionId = generateTransactionId();
			String timestamp = String.valueOf(System.currentTimeMillis() / 1000);

			String value = props.getAccessToken()
											 + transactionId
											 + timestamp
											 + API_PATH_AUTO_DOCU_DELETE;
			String wehagoSign = hmacSha256Base64(props.getHashKey(), value);

			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);
			headers.set("callerName", props.getCallerName());
			headers.set("Authorization", "Bearer " + props.getAccessToken());
			headers.set("transaction-id", transactionId);
			headers.set("timestamp", timestamp);
			if (props.getGroupSeq() != null) {
				headers.set("groupSeq", props.getGroupSeq());
			}
			headers.set("wehago-sign", wehagoSign);

			HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
			String url = props.getBaseUrl() + API_PATH_AUTO_DOCU_DELETE;

//			log.info("[Douzone] 자동전표삭제 URL   : {}", url);
//			log.info("[Douzone] 자동전표삭제 Body  : {}", payload);

			ResponseEntity<Map> response =
				restTemplate.postForEntity(url, entity, Map.class);

//			log.info("[Douzone] 자동전표삭제 응답 코드 : {}", response.getStatusCodeValue());
//			log.info("[Douzone] 자동전표삭제 응답 바디 : {}", response.getBody());

			return (Map<String, Object>) response.getBody();
		} catch (Exception e) {
			log.error("[Douzone] 자동전표삭제 API 호출 중 오류", e);
			throw new RuntimeException("Douzone autoDocu delete error", e);
		}
	}

	/**
	 * 거래처조회 (/apiproxy/api16S11)
	 * - coCd 필수
	 * - trNm(거래처명), regNb(사업자번호) 선택
	 * - 결과 Map 그대로 반환 (DTO 사용 안함)
	 */
	public Map<String, Object> callTradeClientList(String coCd, String trNm, String regNb) {
		try {
			String transactionId = generateTransactionId();
			String timestamp = String.valueOf(System.currentTimeMillis() / 1000);

			String value = props.getAccessToken()
											 + transactionId
											 + timestamp
											 + API_PATH_TR_SEARCH;

			String wehagoSign = hmacSha256Base64(props.getHashKey(), value);

			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);
			headers.set("callerName", props.getCallerName());
			headers.set("Authorization", "Bearer " + props.getAccessToken());
			headers.set("transaction-id", transactionId);
			headers.set("timestamp", timestamp);
			if (props.getGroupSeq() != null) headers.set("groupSeq", props.getGroupSeq());
			headers.set("wehago-sign", wehagoSign);

			Map<String, Object> body = new HashMap<>();
			body.put("coCd", coCd); // ✅ 필수

			// ✅ 선택 검색조건(요청하신 것)
			if (trNm != null && !trNm.isBlank()) body.put("trNm", trNm);
			if (regNb != null && !regNb.isBlank()) body.put("regNb", regNb);

			// (선택) 데이터 많으면 페이징 켜세요
			// body.put("usePagination", true);
			// body.put("pagingOffset", 0);
			// body.put("pagingCount", 1000);

			HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
			String url = props.getBaseUrl() + API_PATH_TR_SEARCH;

			ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

			ObjectMapper mapper = new ObjectMapper();
			return mapper.readValue(response.getBody(), Map.class);

		} catch (Exception e) {
			log.error("[Douzone] api16S11 호출 중 오류", e);
			throw new RuntimeException("Douzone api16S11 error", e);
		}
	}


	// ===== 유틸 메서드들 =====

	// transaction-id 30자리 랜덤 문자열 생성
	private String generateTransactionId() {
		String chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
		SecureRandom random = new SecureRandom();
		StringBuilder sb = new StringBuilder(30);
		for (int i = 0; i < 30; i++) {
			sb.append(chars.charAt(random.nextInt(chars.length())));
		}
		return sb.toString();
	}

	// HMAC-SHA256 후 Base64 인코딩
	private String hmacSha256Base64(String key, String value) {
		try {
			SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(keySpec);
			byte[] encrypted = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
			return Base64.getEncoder().encodeToString(encrypted);
		} catch (Exception e) {
			log.error("[Douzone] HMAC 생성 실패", e);
			throw new RuntimeException("hmacSha256Base64 error", e);
		}
	}

}