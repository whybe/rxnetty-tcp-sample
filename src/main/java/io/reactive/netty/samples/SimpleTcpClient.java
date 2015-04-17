package io.reactive.netty.samples;

import static io.reactive.netty.samples.SimpleTcpServer.DEFAULT_PORT;
import io.reactivex.netty.RxNetty;
import io.reactivex.netty.channel.ObservableConnection;
import io.reactivex.netty.client.RxClient;
import io.reactivex.netty.pipeline.PipelineConfigurators;

import java.net.ConnectException;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observer;
import rx.functions.Action0;
import rx.functions.Func1;
import rx.functions.Func2;

public class SimpleTcpClient {
	private final static Logger logger = LoggerFactory.getLogger(SimpleTcpClient.class);

	private final Object waitMonitor;
	private final RxClient<String, String> rxClient;

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

	public SimpleTcpClient(int port, Object waitMonitor) {

		this.waitMonitor = waitMonitor;
		
		rxClient = RxNetty.createTcpClient("localhost", port,
				PipelineConfigurators.stringMessageConfigurator());
	}

	public SimpleTcpClient connect() {
		logger.info("Waiting to connect server...");
		rxClient.connect()
        		.retry(new Func2<Integer, Throwable, Boolean>() {
        
        			@Override public Boolean call(Integer t1, Throwable t2) {
        				if (t2 instanceof ConnectException
        						&& t2.getMessage().substring(0, 18).equals("Connection refused")) {
        					return Boolean.TRUE;
        				}
        
        				return Boolean.FALSE;
        			}
        		})
				.subscribe(new Observer<ObservableConnection<String, String>>() {

					@Override public void onCompleted() {
						logger.info("connection coplete");
					}

					@Override public void onError(Throwable e) {
						logger.error(e.getMessage(), e);
						close();
					}

					@Override public void onNext(ObservableConnection<String, String> connection) {
						logger.info("connect server : {}", connection);
						
						futureConnection.setConnection(connection);
						future.run();
					}
				});
		
		return this;
	}

	public SimpleTcpClient receiveMessage() {
		Observable.from(future)
				.flatMap(new Func1<ObservableConnection<String, String>, Observable<String>>() {

					@Override public Observable<String> call(
							ObservableConnection<String, String> connection) {
//						if (connection.isCloseIssued()) {
//							return Observable.error(new ClosedChannelException());
//						}
						return connection.getInput();
					}
				})
				.subscribe(new Observer<String>() {

					@Override public void onNext(String s) {
						logger.info("received : \"{}\"", s);
						sendMessage(s);
					}

					@Override public void onError(Throwable e) {
						logger.error(e.getMessage(), e);
						close();
					}

					@Override public void onCompleted() {
						System.out.println("received complete");
					}
				});

		return this;
	}

	public SimpleTcpClient sendMessage(String sendMessage) {
		Observable.from(future)//.delay(1, TimeUnit.SECONDS)
				.flatMap(new Func1<ObservableConnection<String, String>, Observable<Void>>() {

					@Override public Observable<Void> call(
							ObservableConnection<String, String> connection) {
//						if (connection.isCloseIssued()) {
//							return Observable.error(new ClosedChannelException());
//						}
						return connection.writeStringAndFlush(sendMessage + "\n");
					}
				})
				.subscribe(new Observer<Void>() {

					@Override public void onNext(Void aVoid) {
						logger.info("onNext : {}", aVoid);
					}

					@Override public void onError(Throwable e) {
						logger.error(e.getMessage(), e);
						close();
					}

					@Override public void onCompleted() {
						logger.info("sent : \"{}\"", sendMessage);
					}
				});

		return this;
	}

	public void close() {
		Observable.from(future)
				.flatMap(new Func1<ObservableConnection<String, String>, Observable<Void>>() {

					@Override public Observable<Void> call(ObservableConnection<String, String> newConnection) {
						return newConnection.close();
					}
				})
				.finallyDo(new Action0() {
					
					@Override public void call() {

						logger.info("rxClient shutdown");
						rxClient.shutdown();
						synchronized (waitMonitor) {
							waitMonitor.notify();
						}
					}
				})
				.subscribe(new Observer<Void>() {

					@Override public void onCompleted() {
						logger.info("connection close");
					}

					@Override public void onError(Throwable e) {
						logger.error(e.getMessage(), e);
					}

					@Override public void onNext(Void t) {
						logger.info("close onNext : {}", t);
					}
				});
	}

	public static void main(String[] args) {
		int port = DEFAULT_PORT;
		if (args.length > 0) {
			port = Integer.parseInt(args[0]);
		}

		try {
			Object waitMonitor = new Object();
			synchronized (waitMonitor) {
				new SimpleTcpClient(port, waitMonitor)
						.connect()
						.receiveMessage()
						.sendMessage("1001");

				waitMonitor.wait();
			}
		} catch (InterruptedException e) {
			logger.error(e.getMessage(), e);
		}

		System.exit(0);
	}
}
