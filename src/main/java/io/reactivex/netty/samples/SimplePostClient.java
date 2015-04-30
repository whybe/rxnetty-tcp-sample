/*
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.reactivex.netty.samples;

import static io.reactivex.netty.samples.SimplePostServer.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.base64.Base64;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.logging.LogLevel;
import io.netty.util.CharsetUtil;
import io.reactivex.netty.RxNetty;
import io.reactivex.netty.channel.StringTransformer;
import io.reactivex.netty.pipeline.PipelineConfigurator;
import io.reactivex.netty.pipeline.PipelineConfigurators;
import io.reactivex.netty.protocol.http.client.HttpClient;
import io.reactivex.netty.protocol.http.client.HttpClientRequest;
import io.reactivex.netty.protocol.http.client.HttpClientResponse;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.glass.ui.Timer;

import rx.Notification;
import rx.Observable;
import rx.Scheduler;
import rx.Observable.OnSubscribe;
import rx.Subscriber;
import rx.functions.Func0;
import rx.functions.Func1;
import rx.functions.Func2;
import rx.schedulers.Schedulers;

/**
 * @author Tomasz Bak
 */
public class SimplePostClient {
    private final static Logger logger = LoggerFactory.getLogger(SimplePostClient.class);

    static final String DEFAULT_HOST = "localhost";

    static final String MESSAGE =
        "TID_DT=20150101&TID_SEQ=20150424&TID_PORT=8080&EXEC_ID=kswTest&SUBSYSTEM=4&OPERATION_CODE=5&CONTEXT_ID=6&TIMESTAMP=20150404&CUST=1920673039&UPDATE_TIME=1427446520&DATA_ROAMING_AVAILABLE=0&UNIQUE_TIMESTAMP=1427446519";

    private static final String USERNAME = "uapscds";

    private static final String PASSWORD = "uaprofile";

    private final int port;

    private final String host;

    private final QueryStringDecoder queryStringDecoder;

    private final String path;

    public static class Tuple {
        Long count;
        Throwable n;

        Tuple(Long c, Throwable n) {
            count = c;
            this.n = n;
        }
    }

    public static class HttpStatusNotOKException extends IOException {
        public HttpStatusNotOKException() {
            super("http response status code is not 200(OK).");
        }
    }

    // public SimplePostClient(String host, int port) {
    // this.host = host;
    // this.port = port;
    // }

    public SimplePostClient(URI url) {
        host = url.getHost();
        port = url.getPort();
        path = url.getPath();
        queryStringDecoder = new QueryStringDecoder(url);
        Map<String, List<String>> map = queryStringDecoder.parameters();
        for (String key : map.keySet()) {
            logger.info("{} : {}", key, map.get(key));
        }
    }

    public Observable<String> postMessage() {

        HttpClient<String, ByteBuf> client = RxNetty
            .<String, ByteBuf> newHttpClientBuilder(host, port)
            .pipelineConfigurator(PipelineConfigurators.httpClientConfigurator())
            .enableWireLogging(LogLevel.ERROR).build();

        HttpClientRequest<String> request = HttpClientRequest.create(HttpMethod.POST, path);

        request.withHeader(HttpHeaders.Names.CONTENT_TYPE, "application/x-www-form-urlencoded");

        String authString = USERNAME + ":" + PASSWORD;
        ByteBuf authByteBuf = Unpooled.copiedBuffer(authString.toCharArray(), CharsetUtil.UTF_8);
        ByteBuf encodedAuthByteBuf = Base64.encode(authByteBuf);
        request.withHeader(HttpHeaders.Names.AUTHORIZATION, "Basic " + encodedAuthByteBuf.toString(CharsetUtil.UTF_8));

        request.withRawContentSource(Observable.just(MESSAGE), StringTransformer.DEFAULT_INSTANCE);

        return client.submit(request)
            .flatMap(new Func1<HttpClientResponse<ByteBuf>, Observable<String>>() {

                @Override public Observable<String> call(HttpClientResponse<ByteBuf> response) {

                    if (!response.getStatus().equals(HttpResponseStatus.OK)) {
                        return Observable.<String> error(new HttpStatusNotOKException());
                    }

                    return response.getContent()
                        // .defaultIfEmpty(Unpooled.EMPTY_BUFFER)
                        .map(new Func1<ByteBuf, ByteBuf>() {

                            @Override public ByteBuf call(ByteBuf buf) {
                                return Unpooled.copiedBuffer(buf);
                            }
                        })
                        .reduce(new Func2<ByteBuf, ByteBuf, ByteBuf>() {

                            @Override public ByteBuf call(ByteBuf buf1, ByteBuf buf2) {
                                ByteBuf buf3 = Unpooled.copiedBuffer(buf1, buf2);
                                buf1.release();
                                buf2.release();
                                return buf3;
                            }
                        })
                        .map(new Func1<ByteBuf, String>() {

                            @Override public String call(ByteBuf buf4) {

                                String str = buf4.toString(Charset.defaultCharset());
                                buf4.release();

                                return str;
                            }
                        });
                }
            })
            .retryWhen(new Func1<Observable<? extends Throwable>, Observable<?>>() {

                @Override public Observable<?> call(Observable<? extends Throwable> notificationHandler) {
                    return notificationHandler
                        .flatMap(new Func1<Throwable, Observable<Throwable>>() {

                            @Override public Observable<Throwable> call(Throwable e) {

                                if ((e instanceof ConnectException && e.getMessage().subSequence(0, 18).equals("Connection refused"))
                                    || (e instanceof HttpStatusNotOKException)
                                    || (e instanceof NoSuchElementException && e.getMessage().equals("Sequence contains no elements"))) {
                                    // logger.error(e.getMessage(), e);
                                    return Observable.<Throwable> just(e);
                                }

                                return Observable.<Throwable> error(e);
                            }
                        })
                        .zipWith(Observable.range(1, 4), (e, i) -> {
                            // TODO create tuple class to contain both e, i
                            if (i < 4) {
                                return new Throwable(String.valueOf(i));
                            } else {
                                return e;
                            }
                        })
                        .flatMap((e) -> {
                            try {
                                int i = Integer.valueOf(e.getMessage());
                                logger.info("retry({}{}) after {}sec", i, (i == 1) ? "st" : (i == 2) ? "nd" : (i == 3) ? "rd" : "th", 1);
                                return Observable.timer(3, TimeUnit.SECONDS);
                            } catch (NumberFormatException nfe) {
                                return Observable.<Throwable> error(e);
                            }
                        });
                }

            });
        // .toBlocking().singleOrDefault("No Data");
    }

    public static void main(String[] args) throws URISyntaxException {
        URI url = new URI("http://" + DEFAULT_HOST + ":" + DEFAULT_PORT + "/test/post");
        if (args.length == 1) {
            url = new URI(args[0]);
        }

        logger.info(url.toString());

        logger.info("Sending POST request to the server...");
        // String replyMessage = new SimplePostClient(url).postMessage();
        // logger.info("Received : {}", replyMessage);
        Object o = new Object();
        synchronized (o) {
            new SimplePostClient(url).postMessage()
                .finallyDo(() -> {
                    synchronized (o) {
                        o.notify();
                    }
                })
                .subscribeOn(Schedulers.trampoline())
                .subscribe((s) -> {
                    logger.info("Received : {}", s);
                }, (e) -> {
                    logger.info(e.getMessage(), e);
                    logger.info("when exception");
                }, () -> {
                    logger.info("onComplete");
                });

            try {
                o.wait();
            } catch (InterruptedException e) {
                logger.info(e.getMessage(), e);
            }
        }
    }
}