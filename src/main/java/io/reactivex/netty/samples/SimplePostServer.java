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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.logging.LogLevel;
import io.netty.util.CharsetUtil;
import io.reactivex.netty.RxNetty;
import io.reactivex.netty.protocol.http.server.HttpServer;
import io.reactivex.netty.protocol.http.server.HttpServerPipelineConfigurator;
import io.reactivex.netty.protocol.http.server.HttpServerRequest;
import io.reactivex.netty.protocol.http.server.HttpServerResponse;
import io.reactivex.netty.protocol.http.server.RequestHandler;
import rx.Observable;
import rx.functions.Action2;
import rx.functions.Func0;
import rx.functions.Func1;
import rx.functions.Func2;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Tomasz Bak
 */
public class SimplePostServer {
    private final static Logger logger = LoggerFactory.getLogger(SimplePostServer.class);

    static final int DEFAULT_PORT = 8102;

    private final int port;

    public SimplePostServer(int port) {
        this.port = port;
    }

    public HttpServer<ByteBuf, ByteBuf> createServer() {
        HttpServer<ByteBuf, ByteBuf> server = RxNetty.newHttpServerBuilder(port, new RequestHandler<ByteBuf, ByteBuf>() {
            @Override public Observable<Void> handle(HttpServerRequest<ByteBuf> request, final HttpServerResponse<ByteBuf> response) {

                return request.getContent()
                    .map(new Func1<ByteBuf, ByteBuf>() {

                        @Override public ByteBuf call(ByteBuf buf) {
                            ByteBuf buf0 = Unpooled.copiedBuffer(buf);
                            logger.info("buf0 {} refCnt() : {}", buf0.toString(), buf0.refCnt());
                            return buf0;
                        }
                    })
                    .reduce(new Func2<ByteBuf, ByteBuf, ByteBuf>() {

                        @Override public ByteBuf call(ByteBuf buf1, ByteBuf buf2) {
                            logger.info("reduce");
                            logger.info("buf1 {} refCnt() : {}", buf1.toString(), buf1.refCnt());
                            logger.info("buf2 {} refCnt() : {}", buf2.toString(), buf2.refCnt());

                            ByteBuf buf3 = Unpooled.copiedBuffer(buf1, buf2);

                            buf1.release();
                            logger.info("buf1 release");
                            logger.info("buf1 {} refCnt() : {}", buf1.toString(), buf1.refCnt());

                            buf2.release();
                            logger.info("buf2 release");
                            logger.info("buf2 {} refCnt() : {}", buf2.toString(), buf2.refCnt());

                            logger.info("buf3 {} refCnt() : {}", buf3.toString(), buf3.refCnt());

                            return buf3;
                        }
                    })
                    .map(new Func1<ByteBuf, Void>() {

                        @Override public Void call(ByteBuf buf4) {

                            String str = buf4.toString(Charset.defaultCharset());
                            QueryStringDecoder decoder = new QueryStringDecoder(str, false);
                            Map<String, List<String>> map = decoder.parameters();
                            for (String key : map.keySet()) {
                                System.out.println(key + " : " + map.get(key).get(0));
                            }
//                            response.setStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR);
                            response.setStatus(HttpResponseStatus.OK);
                            response.writeStringAndFlush("1");

                            logger.info("buf4 {} refCnt() : {}", buf4.toString(), buf4.refCnt());
                            buf4.release();
                            logger.info("buf4 release");
                            logger.info("buf4 {} refCnt() : {}", buf4.toString(), buf4.refCnt());

                            return null;
                        }
                    })
                    // .collect(new Func0<List<String>>() {
                    //
                    // @Override public List<String> call() {
                    // return new ArrayList<>();
                    // }
                    // }, new Action2<List<String>, ByteBuf>() {
                    //
                    // @Override public void call(List<String> list, ByteBuf buf) {
                    // // System.out.println(list.size() + " , " + buf.toString(Charset.defaultCharset()));
                    // list.add(buf.toString(Charset.defaultCharset()));
                    // }
                    // })
                    // .map(new Func1<List<String>, Void>() {
                    //
                    // @Override public Void call(List<String> list) {
                    // String str = "";
                    // for (String s : list) {
                    // str += s;
                    // }
                    // // System.out.println(str);
                    // QueryStringDecoder decoder = new QueryStringDecoder(str, false);
                    // Map<String, List<String>> map = decoder.parameters();
                    // for (String key : map.keySet()) {
                    // System.out.println(key + " : " + map.get(key).get(0));
                    // }
                    // response.writeStringAndFlush("1");
                    // return null;
                    // }
                    // })
                    .ignoreElements();
            }
        }).pipelineConfigurator(new HttpServerPipelineConfigurator<ByteBuf, ByteBuf>())
            // .enableWireLogging(LogLevel.ERROR)
            .build();
        logger.info("Simple POST server started...");
        return server;
    }

    public static void main(final String[] args) {
        new SimplePostServer(DEFAULT_PORT).createServer().startAndWait();
    }
}
