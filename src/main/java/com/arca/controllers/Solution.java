package com.arca.controllers;

import java.util.*;
import java.lang.*;
public class Solution {
	
	public static void main(String[] args) {
		int num = 1994;
		System.out.println("Roman "+intToRoman(num));
	}
    public static String intToRoman(int num) {
        if(num<1 || num >3999){
            throw new IllegalArgumentException("The number is invalid");
        }
//        String[] mySymbols = {"I","V","X","L","C","D","M"};
//        int[] myValues = {1,5,10,50,100,500,1000};
        String[] mySymbols = {"I","IV","V","IX","X","XL","L","XC","C","CD","D","CM","M"};
        
        int[] myValues = {1,4,5,9,10,40,50,90,100,400,500,900,1000};
        StringBuilder myString = new StringBuilder();
        int i = myValues.length-1;
        while(num>0){
            if(num>=myValues[i]){
            	//System.out.println(mySymbols[i]);
                myString.append(mySymbols[i]);
                num -= myValues[i];

            	//System.out.println(num);
            }else{
                i--;
            }

        }
        return myString.toString();
    }
}