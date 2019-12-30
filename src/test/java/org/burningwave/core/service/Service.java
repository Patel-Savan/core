package org.burningwave.core.service;

import org.burningwave.core.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class Service implements Component {
	private final static Logger LOGGER = LoggerFactory.getLogger(Service.class);
	
	public static Object retrieve() {
		LOGGER.info("Runnable");
		return new Object();
	}
	
	public static void consume(Integer obj) {
		LOGGER.info("Consumer Integer: " + obj.toString());
	}
	
	public void consume(String obj) {
		LOGGER.info("Consumer String: " + obj);
	}
	
	public String apply(String obj) {
		LOGGER.info("Function String execute String: " + obj);
		return obj;
	}
	
	public Long apply(Long obj) {
		LOGGER.info("Function Long execute String: " + obj);
		return obj;
	}
	
	public void run() {
		LOGGER.info("run");
	}		
	
	public String apply(String value_01, String value_02) {
		LOGGER.info("BiFunction: " + value_01 + " " + value_02);
		return "";
	}
	
	public String apply(String value_01, String value_02, String value_03) {
		LOGGER.info("TriFunction: " + value_01 + " " + value_02 + " " + value_03);
		return "";
	}
	
	public String apply(Object value_01, String value_02, String value_03) {
		LOGGER.info("TriFunction: " + value_01 + " " + value_02 + " " + value_03);
		return "";
	}
	
	public void accept(String value_01, String value_02) {
		LOGGER.info("BiConsumer: " + value_01 + " " + value_02);
	}
	
	public void accept(String value_01, String value_02, String value_03) {
		LOGGER.info("TriConsumer: " + value_01 + " " + value_02 + " " + value_03);
	}
	
}