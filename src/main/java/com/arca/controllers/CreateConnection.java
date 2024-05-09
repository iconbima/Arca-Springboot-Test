package com.arca.controllers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import com.arca.ArcaSpringbootApplication;

public class CreateConnection {
	static Connection oraConn = null;
	static Connection oraConnTest = null;
	static Connection mySqlConn = null;

	public static void setOraConn() throws SQLException {
		try {
			Class.forName("oracle.jdbc.OracleDriver");
		} catch (ClassNotFoundException e) {
			System.out.println(e.getMessage());
		}
//		oraConn = DriverManager.getConnection("jdbc:oracle:thin:@192.168.50.10:1521:icon", "icon", "icon");

		oraConn = DriverManager.getConnection("jdbc:oracle:thin:@192.168.50.11:1527:test19c", "ICON", "B1MA");
		// oraConn =
		// DriverManager.getConnection("jdbc:oracle:thin:@127.0.0.1:1521:icon", "ke",
		// "ke");
		oraConnTest.setAutoCommit(true);
	}

	public static Connection getOraConn() throws SQLException {
		Connection oraConn = null;
		try {
			Class.forName("oracle.jdbc.OracleDriver");
		} catch (ClassNotFoundException e) {
			System.out.println(e.getMessage());
		}

		if (ArcaSpringbootApplication.ENVIRONMENT.equals("DRC_TEST")) {

			oraConn = DriverManager.getConnection("jdbc:oracle:thin:@192.168.50.11:1527:test19c", "ICON", "B1MA");

		} else if (ArcaSpringbootApplication.ENVIRONMENT.equals("DRC")) {

			oraConn = DriverManager.getConnection("jdbc:oracle:thin:@192.168.50.10:1527:bima19c", "ICON", "B1MA");

		}else {

			oraConn = DriverManager.getConnection("jdbc:oracle:thin:@192.168.50.10:1527:bima19c", "ICON", "B1MA");

		}

//		oraConn = DriverManager.getConnection("jdbc:oracle:thin:@192.168.1.27:1521:bima", "drc", "drc");
//		oraConn = DriverManager.getConnection("jdbc:oracle:thin:@localhost:1521:icon", "icon", "icon");
//		System.out.println("jdbc:oracle:thin:@192.168.200.251:1527:icon");
//		oraConn = DriverManager.getConnection("jdbc:oracle:thin:@192.168.50.11:1527:icon", "bima_tst", "bima_tst");
//		oraConn = DriverManager.getConnection("jdbc:oracle:thin:@192.168.50.10:1521:icon", "icon", "icon");

		oraConn.setAutoCommit(true);
		return oraConn;
	}
}
