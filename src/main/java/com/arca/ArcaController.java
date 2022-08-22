package com.arca;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.arca.controllers.CreateConnection;
import com.arca.controllers.SubmitPolicy;

@RestController
@RequestMapping("arca")
public class ArcaController {

	private static Connection oraConn = null;
	public static Statement stmt = null;

	public ArcaController() {
		try {
			
			System.out.println("Connecting To Database");
			oraConn = CreateConnection.getOraConn();
			System.out.println("Database Connected!");

		} catch (SQLException e) {
			System.out.println("Errors Connecting to Database\n" + e.getMessage());
		} catch (Exception e) {
			System.out.println("Errors Connecting to Database\n" + e.getMessage());
		}

	}

	@GetMapping(path = "sendArcaRequest/{pl_index}/{end_index}/{created_by}")
	public String sendMessage(@PathVariable("pl_index") int pl_index, @PathVariable("end_index") int end_index,
			@PathVariable("created_by") String created_by) throws Exception {
		SubmitPolicy sp = new SubmitPolicy();

		return (sp.sendArcaMessage(pl_index, end_index, created_by)) ;
		
		
	}
}
