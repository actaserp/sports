package mes.app.douzone_service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "douzone")
public class DouzoneProperties {

 private String callerName;
 private String accessToken;
 private String hashKey;
 private String baseUrl;
 private String groupSeq;   // 옵션

 public String getCallerName() {
  return callerName;
 }

 public void setCallerName(String callerName) {
  this.callerName = callerName;
 }

 public String getAccessToken() {
  return accessToken;
 }

 public void setAccessToken(String accessToken) {
  this.accessToken = accessToken;
 }

 public String getHashKey() {
  return hashKey;
 }

 public void setHashKey(String hashKey) {
  this.hashKey = hashKey;
 }

 public String getBaseUrl() {
  return baseUrl;
 }

 public void setBaseUrl(String baseUrl) {
  this.baseUrl = baseUrl;
 }

 public String getGroupSeq() {
  return groupSeq;
 }

 public void setGroupSeq(String groupSeq) {
  this.groupSeq = groupSeq;
 }
}

