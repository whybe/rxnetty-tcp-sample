package io.reactivex.netty.samples;

import io.reactivex.netty.RxNetty;
import io.reactivex.netty.channel.ConnectionHandler;
import io.reactivex.netty.channel.ObservableConnection;
import io.reactivex.netty.pipeline.PipelineConfigurators;
import io.reactivex.netty.server.RxServer;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observer;
import rx.functions.Func1;

public class SimpleTcpServer {
	private final static Logger logger = LoggerFactory.getLogger(SimpleTcpServer.class);

	public static final int DEFAULT_PORT = 8001;

	private final Object waitMonitor;
	private final RxServer<String, String> rxServer;

	private final FutureConnection futureConnection = new FutureConnection();
	private final FutureTask<ObservableConnection<String, String>> future = new FutureTask<>(futureConnection);
	
	private static class FutureConnection implements Callable<ObservableConnection<String, String>> {

		private ObservableConnection<String, String> connection;
		
		public void setConnection(final ObservableConnection<String, String> connection) {
			if (connection == null)
			      throw new IllegalArgumentException();
			if (this.connection != null)
				throw new IllegalStateException("Immutable variable");
			this.connection = connection;
		}

		@Override public ObservableConnection<String, String> call() throws Exception {
			return connection;
		}
	}

	public SimpleTcpServer(int port, Object waitMonitor) {

		this.waitMonitor = waitMonitor;
		
		rxServer = RxNetty.createTcpServer(
				port,
				PipelineConfigurators.stringMessageConfigurator(),
				new ConnectionHandler<String, String>() {

					@Override public Observable<Void> handle(
							ObservableConnection<String, String> connection) {

//						if (futureConnection != null) {
//							connection.close(true);
//							logger.error("Not allowed one more client connection");
////							logger.error("Not allowed one more client connection", new IOException("Already the other client connected."));
////							return Observable.error(new IOException("Not allowed one more client connection"));
//							return Observable.empty();
//						}
						
						logger.info("connect client {}", connection.getChannel()
								.remoteAddress());

						futureConnection.setConnection(connection);
						future.run();

						return Observable.never();
					}
				});
	}

	protected void close() {
		try {
			rxServer.shutdown();
			synchronized (waitMonitor) {
				waitMonitor.notify();
			}
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public SimpleTcpServer start() {
		rxServer.start();
		logger.info("server start and waiting client...");

		return this;
	}

	public SimpleTcpServer receiveMessages() {	
		Observable.from(future)
				.flatMap(new Func1<ObservableConnection<String, String>, Observable<String>>() {

					@Override public Observable<String> call(
							ObservableConnection<String, String> connection) {

						return connection.getInput();
					}
				})
				.subscribe(new Observer<String>() {

					@Override public void onNext(String s) {
						logger.info("received : \"{}\"", s);
//						sendMessage(s);
					}

					@Override public void onError(Throwable e) {
						logger.error(e.getMessage(), e);
					}

					@Override public void onCompleted() {
						logger.info("received onComplete");
						
					}
				});

		return this;
	}

	public SimpleTcpServer sendMessage(String sendMessage) {
		Observable.from(future)
				.flatMap(new Func1<ObservableConnection<String, String>, Observable<Void>>() {

					@Override public Observable<Void> call(
							ObservableConnection<String, String> connection) {
						return connection.writeStringAndFlush(sendMessage + "\n");
					}
				})
				.subscribe(new Observer<Void>() {

					@Override public void onNext(Void t) {
						logger.info("onNext : " + t);
					}

					@Override public void onError(Throwable e) {
						logger.error(e.getMessage(), e);
					}

					@Override public void onCompleted() {
						logger.info("sent : \"{}\"", sendMessage);
					}
				});

		return this;
	}

	public static void main(String[] args) {
		int port = DEFAULT_PORT;
		if (args.length > 0) {
			port = Integer.parseInt(args[0]);
		}

		try {
			Object waitMonitor = new Object();
			synchronized (waitMonitor) {

				new SimpleTcpServer(port, waitMonitor)
						.start()
						.receiveMessages()
        				.sendMessage("1");

				waitMonitor.wait();
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		System.exit(0);
	}

}
