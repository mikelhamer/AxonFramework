/*
 * Copyright (c) 2010-2020. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.axonserver.connector;

import io.axoniq.axonserver.connector.AxonServerConnection;
import io.axoniq.axonserver.grpc.control.ClientIdentification;
import io.axoniq.axonserver.grpc.control.PlatformInfo;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.MethodDescriptor;
import io.grpc.stub.StreamObserver;
import org.axonframework.axonserver.connector.event.StubServer;
import org.axonframework.axonserver.connector.util.TcpUtil;
import org.axonframework.common.AxonConfigurationException;
import org.axonframework.config.TagsConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.axonframework.axonserver.connector.utils.AssertUtils.assertWithin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AxonServerConnectionManager}.
 *
 * @author Milan Savic
 */
class AxonServerConnectionManagerTest {

    private static final String TEST_CONTEXT = "default";

    private StubServer stubServer;
    private StubServer secondNode;
    private AxonServerConfiguration testConfig;

    @BeforeEach
    void setUp() throws IOException {
        int port1 = TcpUtil.findFreePort();
        int port2 = TcpUtil.findFreePort();
        stubServer = new StubServer(port1, port2);
        secondNode = new StubServer(port2, port2);
        stubServer.start();
        secondNode.start();
        testConfig = AxonServerConfiguration.builder()
                                            .context(TEST_CONTEXT)
                                            .servers("localhost:" + stubServer.getPort())
                                            .build();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        stubServer.shutdown();
        secondNode.shutdown();
    }

    @Test
    void testWhetherConnectionPreferenceIsSent() {
        TagsConfiguration testTags = new TagsConfiguration(Collections.singletonMap("key", "value"));

        AxonServerConnectionManager testSubject = AxonServerConnectionManager.builder()
                                                                             .axonServerConfiguration(testConfig)
                                                                             .tagsConfiguration(testTags)
                                                                             .build();

        assertNotNull(testSubject.getConnection(TEST_CONTEXT));

        List<ClientIdentification> clientIdentificationRequests = stubServer.getPlatformService()
                                                                            .getClientIdentificationRequests();
        assertEquals(1, clientIdentificationRequests.size());
        Map<String, String> expectedTags = clientIdentificationRequests.get(0).getTagsMap();
        assertNotNull(expectedTags);
        assertEquals(1, expectedTags.size());
        assertEquals("value", expectedTags.get("key"));

        assertWithin(
                1, TimeUnit.SECONDS,
                () -> assertEquals(1, secondNode.getPlatformService().getClientIdentificationRequests().size())
        );

        List<ClientIdentification> clients = secondNode.getPlatformService().getClientIdentificationRequests();
        Map<String, String> connectionExpectedTags = clients.get(0).getTagsMap();
        assertNotNull(connectionExpectedTags);
        assertEquals(1, connectionExpectedTags.size());
        assertEquals("value", connectionExpectedTags.get("key"));
    }

    @Test
    void testConnectionTimeout() throws IOException, InterruptedException {
        stubServer.shutdown();
        stubServer = new StubServer(TcpUtil.findFreePort(), new PlatformService(TcpUtil.findFreePort()) {
            @Override
            public void getPlatformServer(ClientIdentification request, StreamObserver<PlatformInfo> responseObserver) {
                // ignore calls
            }
        });
        stubServer.start();

        AxonServerConfiguration testConfig = AxonServerConfiguration.builder()
                                                                    .servers("localhost:" + stubServer.getPort())
                                                                    .connectTimeout(50)
                                                                    .build();

        AxonServerConnectionManager testSubject = AxonServerConnectionManager.builder()
                                                                             .axonServerConfiguration(testConfig)
                                                                             .build();

        try {
            AxonServerConnection connection = testSubject.getConnection();
            connection.commandChannel();
            assertWithin(
                    2, TimeUnit.SECONDS,
                    () -> assertTrue(connection.isConnectionFailed(), "Was not expecting to get a connection")
            );
        } catch (AxonServerException e) {
            assertTrue(e.getMessage().contains("connection"));
        }
    }

    @Test
    void testEnablingHeartbeatsEnsuresHeartbeatMessagesAreSent() {
        testConfig.getHeartbeat().setEnabled(true);
        AxonServerConnectionManager testSubject = AxonServerConnectionManager.builder()
                                                                             .axonServerConfiguration(testConfig)
                                                                             .build();
        testSubject.start();

        assertNotNull(testSubject.getConnection(testConfig.getContext()));

        assertWithin(
                250, TimeUnit.MILLISECONDS,
                // Retrieving the messages from the secondNode, as the stubServer forwards all messages to this instance
                () -> assertEquals(1, secondNode.getPlatformService().getHeartbeatMessages(testConfig.getContext()).size())
        );
    }

    @Test
    void testEnablingHeartbeatsEnsuresHeartbeatMessagesAreSentOnOtherContexts() {
        testConfig.getHeartbeat().setEnabled(true);
        AxonServerConnectionManager testSubject = AxonServerConnectionManager.builder()
                                                                             .axonServerConfiguration(testConfig)
                                                                             .build();
        testSubject.start();

        assertNotNull(testSubject.getConnection(testConfig.getContext()));
        assertNotNull(testSubject.getConnection("context2"));

        assertWithin(
                250, TimeUnit.MILLISECONDS,
                // Retrieving the messages from the secondNode, as the stubServer forwards all messages to this instance
                () -> {
                    assertFalse(secondNode.getPlatformService().getHeartbeatMessages(testConfig.getContext()).isEmpty());
                    assertFalse(secondNode.getPlatformService().getHeartbeatMessages("context2").isEmpty());
                }
        );
    }

    @Test
    void testDisablingHeartbeatsEnsuresNoHeartbeatMessagesAreSent() {
        testConfig.getHeartbeat().setEnabled(false);
        AxonServerConnectionManager testSubject = AxonServerConnectionManager.builder()
                                                                             .axonServerConfiguration(testConfig)
                                                                             .build();
        testSubject.start();

        assertNotNull(testSubject.getConnection(testConfig.getContext()));

        assertWithin(
                250, TimeUnit.MILLISECONDS,
                // Retrieving the messages from the secondNode, as the stubServer forwards all messages to this instance
                () -> assertTrue(secondNode.getPlatformService().getHeartbeatMessages().isEmpty())
        );
    }

    @Test
    void testChannelCustomization() {
        AtomicBoolean interceptorCalled = new AtomicBoolean();
        AxonServerConnectionManager testSubject =
                AxonServerConnectionManager.builder()
                                           .axonServerConfiguration(testConfig)
                                           .channelCustomizer(
                                                   builder -> builder.intercept(new ClientInterceptor() {
                                                       @Override
                                                       public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                                                               MethodDescriptor<ReqT, RespT> methodDescriptor,
                                                               CallOptions callOptions,
                                                               Channel channel
                                                       ) {
                                                           interceptorCalled.set(true);
                                                           return channel.newCall(methodDescriptor, callOptions);
                                                       }
                                                   })
                                           )
                                           .build();

        assertNotNull(testSubject.getConnection());
        assertTrue(interceptorCalled.get());
    }

    @Test
    void testIsConnected() {
        AxonServerConnectionManager testSubject = AxonServerConnectionManager.builder()
                                                                             .axonServerConfiguration(testConfig)
                                                                             .build();

        // Creates a connection for the default context
        AxonServerConnection result = testSubject.getConnection();
        assertWithin(250, TimeUnit.MILLISECONDS, () -> assertTrue(result.isReady()));

        assertTrue(testSubject.isConnected(TEST_CONTEXT));
        assertFalse(testSubject.isConnected("unknown-context"));
    }

    @Test
    void testShutdownClosesAllConnections() {
        AxonServerConnectionManager testSubject =
                AxonServerConnectionManager.builder()
                                           .axonServerConfiguration(testConfig)
                                           .build();

        // Creates several connections
        AxonServerConnection channelOne = testSubject.getConnection(TEST_CONTEXT);
        AxonServerConnection channelTwo = testSubject.getConnection("some-other-context");
        assertWithin(250, TimeUnit.MILLISECONDS, () -> {
            assertTrue(channelOne.isReady());
            assertTrue(channelTwo.isReady());
        });

        assertTrue(testSubject.isConnected(TEST_CONTEXT));
        assertTrue(testSubject.isConnected("some-other-context"));

        // Shutdown the connection manager
        testSubject.shutdown();

        assertFalse(testSubject.isConnected(TEST_CONTEXT));
        assertFalse(testSubject.isConnected("some-other-context"));
        assertEquals(2, secondNode.getPlatformService().getNumberOfCompletedStreams());
    }

    @Test
    void testDisconnectClosesAllConnections() {
        AxonServerConnectionManager testSubject = AxonServerConnectionManager.builder()
                                                                             .axonServerConfiguration(testConfig)
                                                                             .build();

        // Creates several connections
        AxonServerConnection channelOne = testSubject.getConnection(TEST_CONTEXT);
        AxonServerConnection channelTwo = testSubject.getConnection("some-other-context");
        assertWithin(250, TimeUnit.MILLISECONDS, () -> {
            assertTrue(channelOne.isReady());
            assertTrue(channelTwo.isReady());
        });

        assertTrue(testSubject.isConnected(TEST_CONTEXT));
        assertTrue(testSubject.isConnected("some-other-context"));

        // Close all connections
        testSubject.disconnect();

        assertFalse(testSubject.isConnected(TEST_CONTEXT));
        assertFalse(testSubject.isConnected("some-other-context"));
        assertEquals(2, secondNode.getPlatformService().getNumberOfCompletedStreams());
    }

    @Test
    void testDisconnectSingleConnection() {
        AxonServerConnectionManager testSubject = AxonServerConnectionManager.builder()
                                                                             .axonServerConfiguration(testConfig)
                                                                             .build();

        // Creates a connection for the default context
        AxonServerConnection channelOne = testSubject.getConnection(TEST_CONTEXT);
        AxonServerConnection channelTwo = testSubject.getConnection("some-other-context");
        assertWithin(250, TimeUnit.MILLISECONDS, () -> {
            assertTrue(channelOne.isReady());
            assertTrue(channelTwo.isReady());
        });

        assertTrue(testSubject.isConnected(TEST_CONTEXT));
        assertTrue(testSubject.isConnected("some-other-context"));

        // Will close the default connection only
        testSubject.disconnect(TEST_CONTEXT);

        assertFalse(testSubject.isConnected(TEST_CONTEXT));
        assertTrue(testSubject.isConnected("some-other-context"));
        assertEquals(1, secondNode.getPlatformService().getNumberOfCompletedStreams());
    }

    @Test
    void testBuildWithNullAxonServerConfigurationThrowsAxonConfigurationException() {
        AxonServerConnectionManager.Builder builderTestSubject = AxonServerConnectionManager.builder();
        assertThrows(AxonConfigurationException.class, () -> builderTestSubject.axonServerConfiguration(null));
    }

    @Test
    void testBuildWithNullTagsConfigurationThrowsAxonConfigurationException() {
        AxonServerConnectionManager.Builder builderTestSubject = AxonServerConnectionManager.builder();
        assertThrows(AxonConfigurationException.class, () -> builderTestSubject.tagsConfiguration(null));
    }

    @Test
    void testBuildWithoutAxonServerConfigurationThrowsAxonConfigurationException() {
        AxonServerConnectionManager.Builder builderTestSubject = AxonServerConnectionManager.builder();
        assertThrows(AxonConfigurationException.class, builderTestSubject::build);
    }
}
