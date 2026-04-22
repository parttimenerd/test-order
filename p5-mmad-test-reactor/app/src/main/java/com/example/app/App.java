package com.example.app;
import com.example.service.a.ServiceA;
import com.example.service.b.ServiceB;
public class App { public static void main(String[] args) { System.out.println(new ServiceA().execute()); System.out.println(new ServiceB().execute()); } }
