package com.arca;

public class CancelCert { 
	private String certNo;
	private String cancelReason;
	private String  userCode;
	public String getCertNo() {
		return certNo;
	}
	public void setCertNo(String certNo) {
		this.certNo = certNo;
	}
	public String getCancelReason() {
		return cancelReason;
	}
	public void setCancelReason(String reason) {
		this.cancelReason = reason;
	}
	public String getUserCode() {
		return userCode;
	}
	public void setUserCode(String userCode) {
		this.userCode = userCode;
	}
	
}
