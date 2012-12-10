package org.usergrid.vx.server;

import org.apache.cassandra.service.AbstractCassandraDaemon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.usergrid.vx.experimental.IntraHandlerXml;
import org.usergrid.vx.experimental.IntraHandlerJson;
import org.usergrid.vx.handler.http.HelloHandler;
import org.usergrid.vx.handler.http.NoMatchHandler;
import org.usergrid.vx.handler.http.ThriftHandler;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.http.RouteMatcher;

public class IntravertDaemon extends AbstractCassandraDaemon {
	
	private final Logger logger = LoggerFactory.getLogger(IntravertDaemon.class);
	private static Vertx vertx;
	private static RouteMatcher rm;
	
	@Override
	protected void startServer() {
		vertx = Vertx.newVertx();
		rm = new RouteMatcher();
		rm.put("/:appid/hello", new HelloHandler());
		rm.get("/:appid/hello", new HelloHandler());
		rm.post("/:appid/hello", new HelloHandler());
		rm.post("/:appid/thriftjson", new ThriftHandler());
		rm.post("/:appid/intrareq-xml", new IntraHandlerXml());
		rm.post("/:appid/intrareq-json", new IntraHandlerJson());
		rm.noMatch(new NoMatchHandler() );
		vertx.createHttpServer().requestHandler(rm).listen(8080);
		logger.info("startServer...");
	}

	@Override
	protected void stopServer() {
		logger.info("stopServer...");		
	}

}