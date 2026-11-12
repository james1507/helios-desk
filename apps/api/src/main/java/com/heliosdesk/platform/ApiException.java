package com.heliosdesk.platform;

public class ApiException extends RuntimeException {
  public final int status;
  public final String code;
  public final Long currentVersion;

  public ApiException(int status, String code, String message) {
    this(status, code, message, null);
  }

  public ApiException(int status, String code, String message, Long version) {
    super(message);
    this.status = status;
    this.code = code;
    this.currentVersion = version;
  }

  public static ApiException missing() {
    return new ApiException(404, "RESOURCE_NOT_FOUND", "Resource not found.");
  }

  public static ApiException forbidden() {
    return new ApiException(403, "FORBIDDEN", "Your role does not permit this action.");
  }
}
