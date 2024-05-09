package com.arca.controllers;

public class TestSubmission {

	public static void main(String[] args) {

		//SaveResponse.updateCertificate("52","5142321362","864884","5142321415-0000000006","00");

		SubmitGITPolicy sp = new SubmitGITPolicy();
		System.out.println(sp.sendArcaMessage(57134, 0,"1000000"));

	}

}
