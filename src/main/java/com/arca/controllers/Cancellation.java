package com.arca.controllers;

public class Cancellation {
private int pl_index;
private int end_index;
private int risk_index;
private String reason;
private String cancelled_by;
private String cancelled_ip;
public int getPl_index() {
	return pl_index;
}
public void setPl_index(int pl_index) {
	this.pl_index = pl_index;
}
public int getEnd_index() {
	return end_index;
}
public void setEnd_index(int end_index) {
	this.end_index = end_index;
}
public int getRisk_index() {
	return risk_index;
}
public void setRisk_index(int risk_index) {
	this.risk_index = risk_index;
}
public String getReason() {
	return reason;
}
public void setReason(String reason) {
	this.reason = reason;
}
public String getCancelled_by() {
	return cancelled_by;
}
public void setCancelled_by(String cancelled_by) {
	this.cancelled_by = cancelled_by;
}
public String getCancelled_ip() {
	return cancelled_ip;
}
public void setCancelled_ip(String cancelled_ip) {
	this.cancelled_ip = cancelled_ip;
}


	
}
