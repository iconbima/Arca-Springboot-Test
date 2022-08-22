package com.arca.controllers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

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
		oraConn = DriverManager.getConnection("jdbc:oracle:thin:@192.168.0.210:1521:icon", "icon_tst", "icon_tst");
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
		
		
//		oraConn = DriverManager.getConnection("jdbc:oracle:thin:@192.168.1.27:1521:bima", "drc", "drc");
//		oraConn = DriverManager.getConnection("jdbc:oracle:thin:@localhost:1521:icon", "icon", "icon");
//		System.out.println("jdbc:oracle:thin:@192.168.200.251:1527:icon");
		oraConn = DriverManager.getConnection("jdbc:oracle:thin:@192.168.200.251:1527:icon", "bima_tst", "bima_tst");

		oraConn.setAutoCommit(true);
		return oraConn;
	}
}
