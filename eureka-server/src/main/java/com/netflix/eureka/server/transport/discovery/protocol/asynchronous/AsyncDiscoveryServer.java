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

package com.netflix.eureka.server.transport.discovery.protocol.asynchronous;

import java.util.Arrays;

import com.netflix.eureka.interests.Interest;
import com.netflix.eureka.protocol.Heartbeat;
import com.netflix.eureka.protocol.discovery.InterestSetNotification;
import com.netflix.eureka.protocol.discovery.RegisterInterestSet;
import com.netflix.eureka.protocol.discovery.UnregisterInterestSet;
import com.netflix.eureka.server.transport.Context;
import com.netflix.eureka.server.transport.TransportServer;
import com.netflix.eureka.server.transport.discovery.DiscoveryHandler;
import com.netflix.eureka.transport.MessageBroker;
import com.netflix.eureka.transport.MessageBrokerServer;
import rx.Observable;
import rx.Subscriber;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func1;

/**
 * @author Tomasz Bak
 */
public class AsyncDiscoveryServer implements TransportServer {

    private final MessageBrokerServer brokerServer;

    public AsyncDiscoveryServer(MessageBrokerServer brokerServer, final DiscoveryHandler handler) {
        this.brokerServer = brokerServer;
        brokerServer.clientConnections().doOnTerminate(new Action0() {
            @Override
            public void call() {
                try {
                    shutdown();
                } catch (InterruptedException e) {
                    // IGNORE
                }
            }
        }).forEach(new Action1<MessageBroker>() {
            @Override
            public void call(final MessageBroker messageBroker) {
                // FIXME What is the best way to identify active client connection?
                Context clientContext = new Context();
                Observable.amb(
                        handler.updates(clientContext).flatMap(new NotificationForwarder(messageBroker)),
                        messageBroker.incoming().flatMap(new ClientMessageDispatcher(messageBroker, clientContext, handler))
                ).subscribe(new Subscriber<Void>() {
                    @Override
                    public void onCompleted() {
                        messageBroker.shutdown();
                    }

                    @Override
                    public void onError(Throwable e) {
                    }

                    @Override
                    public void onNext(Void aVoid) {
                        messageBroker.shutdown();
                    }
                });
            }
        });
    }

    @Override
    public void shutdown() throws InterruptedException {
        brokerServer.shutdown();
    }

    static class ClientMessageDispatcher implements Func1<Object, Observable<Void>> {

        private final MessageBroker messageBroker;
        private final Context clientContext;
        private final DiscoveryHandler handler;

        ClientMessageDispatcher(MessageBroker messageBroker, Context clientContext, DiscoveryHandler handler) {
            this.messageBroker = messageBroker;
            this.clientContext = clientContext;
            this.handler = handler;
        }

        @Override
        public Observable<Void> call(final Object message) {
            Observable<Void> response;
            if (message instanceof RegisterInterestSet) {
                Interest[] interests = ((RegisterInterestSet) message).getInterestSet();
                response = handler.registerInterestSet(clientContext, Arrays.asList(interests));
            } else if (message instanceof UnregisterInterestSet) {
                response = handler.unregisterInterestSet(clientContext);
            } else if (message instanceof Heartbeat) {
                return handler.heartbeat(clientContext);
            } else {
                return Observable.empty();
            }
            return response.doOnCompleted(new Action0() {
                @Override
                public void call() {
                    messageBroker.acknowledge(message);
                }
            });
        }
    }

    static class NotificationForwarder implements Func1<InterestSetNotification, Observable<Void>> {
        private final MessageBroker messageBroker;

        NotificationForwarder(MessageBroker messageBroker) {
            this.messageBroker = messageBroker;
        }

        @Override
        public Observable<Void> call(InterestSetNotification notification) {
            return messageBroker.submit(notification);
        }
    }
}
