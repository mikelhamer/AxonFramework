/*
 * Copyright (c) 2010-2021. Axon Framework
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

package org.axonframework.eventhandling.tokenstore.jpa;

import org.axonframework.common.jpa.EntityManagerProvider;
import org.axonframework.common.jpa.SimpleEntityManagerProvider;
import org.axonframework.common.transaction.Transaction;
import org.axonframework.common.transaction.TransactionManager;
import org.axonframework.eventhandling.GlobalSequenceTrackingToken;
import org.axonframework.eventhandling.TrackingToken;
import org.axonframework.eventhandling.tokenstore.ConfigToken;
import org.axonframework.eventhandling.tokenstore.UnableToClaimTokenException;
import org.axonframework.serialization.TestSerializer;
import org.hibernate.dialect.HSQLDialect;
import org.hibernate.jpa.HibernatePersistenceProvider;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableMBeanExport;
import org.springframework.jmx.support.RegistrationPolicy;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.persistence.EntityManager;
import javax.persistence.LockModeType;
import javax.persistence.PersistenceContext;
import javax.sql.DataSource;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ContextConfiguration
@ExtendWith(SpringExtension.class)
@EnableMBeanExport(registration = RegistrationPolicy.IGNORE_EXISTING)
public class JpaTokenStoreTest {

    @Autowired
    @Qualifier("jpaTokenStore")
    private JpaTokenStore jpaTokenStore;

    @Autowired
    @Qualifier("concurrentJpaTokenStore")
    private JpaTokenStore concurrentJpaTokenStore;

    @Autowired
    @Qualifier("stealingJpaTokenStore")
    private JpaTokenStore stealingJpaTokenStore;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;
    private TransactionTemplate txTemplate;

    @Transactional
    @BeforeEach
    public void setUp() {
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional
    @Test
    public void testUpdateNullToken() {
        jpaTokenStore.initializeTokenSegments("test", 1);
        jpaTokenStore.fetchToken("test", 0);
        jpaTokenStore.storeToken(null, "test", 0);
        List<TokenEntry> tokens = entityManager.createQuery("SELECT t FROM TokenEntry t " +
                                                                    "WHERE t.processorName = :processorName",
                                                            TokenEntry.class)
                                               .setParameter("processorName", "test")
                                               .getResultList();
        assertEquals(1, tokens.size());
        assertNotNull(tokens.get(0).getOwner());
        assertNull(tokens.get(0).getToken(TestSerializer.XSTREAM.getSerializer()));
    }

    @Transactional
    @Test
    void testUpdateAndLoadNullToken() {
        jpaTokenStore.initializeTokenSegments("test", 1);
        jpaTokenStore.fetchToken("test", 0);
        entityManager.flush();
        jpaTokenStore.storeToken(null, "test", 0);
        entityManager.flush();
        entityManager.clear();
        TrackingToken token = jpaTokenStore.fetchToken("test", 0);
        assertNull(token);
    }

    @Transactional
    @Test
    void testIdentifierInitializedOnDemand() {
        Optional<String> id1 = jpaTokenStore.retrieveStorageIdentifier();
        assertTrue(id1.isPresent());
        Optional<String> id2 = jpaTokenStore.retrieveStorageIdentifier();
        assertTrue(id2.isPresent());
        assertEquals(id1.get(), id2.get());
    }
    @Transactional
    @Test
    void testIdentifierReadIfAvailable() {
        entityManager.persist(new TokenEntry("__config", 0, new ConfigToken(Collections.singletonMap("id", "test")), jpaTokenStore.serializer() ));
        Optional<String> id1 = jpaTokenStore.retrieveStorageIdentifier();
        assertTrue(id1.isPresent());
        Optional<String> id2 = jpaTokenStore.retrieveStorageIdentifier();
        assertTrue(id2.isPresent());
        assertEquals(id1.get(), id2.get());

        assertEquals("test", id1.get());
    }

    @Transactional
    @Test
    public void testCustomLockMode() {
        EntityManager spyEntityManager = mock(EntityManager.class);

        JpaTokenStore testSubject = JpaTokenStore.builder()
                                                 .serializer(TestSerializer.XSTREAM.getSerializer())
                                                 .loadingLockMode(LockModeType.NONE)
                                                 .entityManagerProvider(new SimpleEntityManagerProvider(spyEntityManager))
                                                 .nodeId("test")
                                                 .build();

        try {
            testSubject.fetchToken("processorName", 1);
        } catch (Exception e) {
            // ignore. This fails
        }
        verify(spyEntityManager).find(eq(TokenEntry.class), any(), eq(LockModeType.NONE));
    }

    @Transactional
    @Test
    public void testInitializeTokens() {
        jpaTokenStore.initializeTokenSegments("test1", 7);

        int[] actual = jpaTokenStore.fetchSegments("test1");
        Arrays.sort(actual);
        assertArrayEquals(new int[]{0, 1, 2, 3, 4, 5, 6}, actual);
    }

    @SuppressWarnings("Duplicates")
    @Transactional
    @Test
    public void testInitializeTokensAtGivenPosition() {
        jpaTokenStore.initializeTokenSegments("test1", 7, new GlobalSequenceTrackingToken(10));

        int[] actual = jpaTokenStore.fetchSegments("test1");
        Arrays.sort(actual);
        assertArrayEquals(new int[]{0, 1, 2, 3, 4, 5, 6}, actual);

        for (int segment : actual) {
            assertEquals(new GlobalSequenceTrackingToken(10), jpaTokenStore.fetchToken("test1", segment));
        }
    }

    @Transactional
    @Test
    public void testInitializeTokensWhileAlreadyPresent() {
        assertThrows(UnableToClaimTokenException.class, () -> jpaTokenStore.fetchToken("test1", 1));
    }

    @Transactional
    @Test
    public void testDeleteTokenRejectedIfNotClaimedOrNotInitialized() {
        jpaTokenStore.initializeTokenSegments("test", 2);

        try {
            jpaTokenStore.deleteToken("test", 0);
            fail("Expected delete to fail");
        } catch (UnableToClaimTokenException e) {
            // expected
        }

        try {
            jpaTokenStore.deleteToken("unknown", 0);
            fail("Expected delete to fail");
        } catch (UnableToClaimTokenException e) {
            // expected
        }
    }

    @Transactional
    @Test
    public void testDeleteToken() {
        jpaTokenStore.initializeSegment(null, "delete", 0);
        jpaTokenStore.fetchToken("delete", 0);

        entityManager.flush();
        jpaTokenStore.deleteToken("delete", 0);

        assertEquals(0L, (long) entityManager.createQuery("SELECT count(t) FROM TokenEntry t " +
                                                                  "WHERE t.processorName = :processorName", Long.class)
                                             .setParameter("processorName", "delete")
                                             .getSingleResult());
    }

    @Transactional
    @Test
    public void testClaimAndUpdateToken() {
        jpaTokenStore.initializeTokenSegments("test", 1);

        assertNull(jpaTokenStore.fetchToken("test", 0));
        jpaTokenStore.storeToken(new GlobalSequenceTrackingToken(1L), "test", 0);

        List<TokenEntry> tokens = entityManager.createQuery("SELECT t FROM TokenEntry t " +
                                                                    "WHERE t.processorName = :processorName",
                                                            TokenEntry.class)
                                               .setParameter("processorName", "test")
                                               .getResultList();
        assertEquals(1, tokens.size());
        assertNotNull(tokens.get(0).getOwner());
        jpaTokenStore.releaseClaim("test", 0);

        entityManager.flush();
        entityManager.clear();

        TokenEntry token = entityManager.find(TokenEntry.class, new TokenEntry.PK("test", 0));
        assertNull(token.getOwner());
    }

    @Transactional
    @Test
    public void testQuerySegments() {
        jpaTokenStore.initializeTokenSegments("test", 1);
        jpaTokenStore.initializeTokenSegments("proc1", 2);
        jpaTokenStore.initializeTokenSegments("proc2", 1);

        assertNull(jpaTokenStore.fetchToken("test", 0));

        jpaTokenStore.storeToken(new GlobalSequenceTrackingToken(1L), "proc1", 0);
        jpaTokenStore.storeToken(new GlobalSequenceTrackingToken(2L), "proc1", 1);
        jpaTokenStore.storeToken(new GlobalSequenceTrackingToken(2L), "proc2", 0);

        {
            final int[] segments = jpaTokenStore.fetchSegments("proc1");
            assertThat(segments.length, is(2));
        }
        {
            final int[] segments = jpaTokenStore.fetchSegments("proc2");
            assertThat(segments.length, is(1));
        }

        {
            final int[] segments = jpaTokenStore.fetchSegments("proc3");
            assertThat(segments.length, is(0));
        }


        entityManager.flush();
        entityManager.clear();
    }

    @Transactional
    @Test
    public void testClaimTokenConcurrently() {
        jpaTokenStore.initializeTokenSegments("concurrent", 1);
        jpaTokenStore.fetchToken("concurrent", 0);
        try {
            concurrentJpaTokenStore.fetchToken("concurrent", 0);
            fail("Expected UnableToClaimTokenException");
        } catch (UnableToClaimTokenException e) {
            // expected
        }
    }

    @Transactional
    @Test
    public void testStealToken() {
        jpaTokenStore.initializeTokenSegments("stealing", 1);

        jpaTokenStore.fetchToken("stealing", 0);
        stealingJpaTokenStore.fetchToken("stealing", 0);

        try {
            jpaTokenStore.storeToken(new GlobalSequenceTrackingToken(0), "stealing", 0);
            fail("Expected UnableToClaimTokenException");
        } catch (UnableToClaimTokenException e) {
            // expected
        }
        jpaTokenStore.releaseClaim("stealing", 0);
        // claim should still be on stealingJpaTokenStore:
        stealingJpaTokenStore.storeToken(new GlobalSequenceTrackingToken(1), "stealing", 0);
    }

    @Transactional
    @Test
    public void testExtendingLostClaimFails() {
        jpaTokenStore.initializeTokenSegments("processor", 1);
        jpaTokenStore.fetchToken("processor", 0);

        try {
            stealingJpaTokenStore.extendClaim("processor", 0);
            fail("Expected claim extension to fail");
        } catch (UnableToClaimTokenException e) {
            // expected
        }
    }

    @Transactional
    @Test
    public void testStealingFromOtherThreadFailsWithRowLock() throws Exception {
        jpaTokenStore.initializeTokenSegments("processor", 1);

        ExecutorService executor1 = Executors.newSingleThreadExecutor();
        CountDownLatch cdl = new CountDownLatch(1);
        try {
            jpaTokenStore.fetchToken("processor", 0);
            Future<?> result = executor1.submit(() -> {

                DefaultTransactionDefinition txDef = new DefaultTransactionDefinition();
                txDef.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                TransactionStatus tx = transactionManager.getTransaction(txDef);
                cdl.countDown();
                try {
                    stealingJpaTokenStore.fetchToken("processor", 0);
                } finally {
                    transactionManager.rollback(tx);
                }
            });
            cdl.await();
            try {
                result.get(250, TimeUnit.MILLISECONDS);
                fail("Expected task to time out on the write lock");
            } catch (TimeoutException e) {
                // we expect this;
            }
            assertFalse(result.isDone());

            // we cancel the task
            result.cancel(true);

            // and make sure the token is still owned
            TokenEntry tokenEntry = entityManager.find(TokenEntry.class, new TokenEntry.PK("processor", 0));
            assertEquals("local", tokenEntry.getOwner());
        } finally {
            executor1.shutdown();
        }
    }

    @Test
    public void testStoreAndLoadAcrossTransactions() {
        txTemplate.execute(status -> {
            jpaTokenStore.initializeTokenSegments("multi", 1);
            return null;
        });

        txTemplate.execute(status -> {
            jpaTokenStore.fetchToken("multi", 0);
            jpaTokenStore.storeToken(new GlobalSequenceTrackingToken(1), "multi", 0);
            return null;
        });

        txTemplate.execute(status -> {
            TrackingToken actual = jpaTokenStore.fetchToken("multi", 0);
            assertEquals(new GlobalSequenceTrackingToken(1), actual);
            jpaTokenStore.storeToken(new GlobalSequenceTrackingToken(2), "multi", 0);
            return null;
        });

        txTemplate.execute(status -> {
            TrackingToken actual = jpaTokenStore.fetchToken("multi", 0);
            assertEquals(new GlobalSequenceTrackingToken(2), actual);
            return null;
        });
    }

    @Configuration
    public static class Context {

        @SuppressWarnings("Duplicates")
        @Bean
        public DataSource dataSource() {
            JDBCDataSource dataSource = new JDBCDataSource();
            dataSource.setUrl("jdbc:hsqldb:mem:testdb");
            dataSource.setUser("sa");
            dataSource.setPassword("");
            return dataSource;
        }

        @Bean
        public LocalContainerEntityManagerFactoryBean sessionFactory() {
            LocalContainerEntityManagerFactoryBean sessionFactory = new LocalContainerEntityManagerFactoryBean();
            sessionFactory.setPersistenceProvider(new HibernatePersistenceProvider());
            sessionFactory.setPackagesToScan(TokenEntry.class.getPackage().getName());
            sessionFactory.setJpaPropertyMap(Collections.singletonMap("hibernate.dialect", new HSQLDialect()));
            sessionFactory.setJpaPropertyMap(Collections.singletonMap("hibernate.hbm2ddl.auto", "create-drop"));
            sessionFactory.setJpaPropertyMap(Collections.singletonMap("hibernate.show_sql", "false"));
            sessionFactory.setJpaPropertyMap(Collections.singletonMap("hibernate.connection.url",
                                                                      "jdbc:hsqldb:mem:testdb"));
            return sessionFactory;
        }

        @Bean
        public PlatformTransactionManager txManager() {
            return new JpaTransactionManager();
        }

        @Bean
        public JpaTokenStore jpaTokenStore(EntityManagerProvider entityManagerProvider) {
            return JpaTokenStore.builder()
                                .entityManagerProvider(entityManagerProvider)
                                .serializer(TestSerializer.XSTREAM.getSerializer())
                                .nodeId("local")
                                .build();
        }

        @Bean
        public JpaTokenStore concurrentJpaTokenStore(EntityManagerProvider entityManagerProvider) {
            return JpaTokenStore.builder()
                                .entityManagerProvider(entityManagerProvider)
                                .serializer(TestSerializer.XSTREAM.getSerializer())
                                .claimTimeout(Duration.ofSeconds(2))
                                .nodeId("concurrent")
                                .build();
        }

        @Bean
        public JpaTokenStore stealingJpaTokenStore(EntityManagerProvider entityManagerProvider) {
            return JpaTokenStore.builder()
                                .entityManagerProvider(entityManagerProvider)
                                .serializer(TestSerializer.XSTREAM.getSerializer())
                                .claimTimeout(Duration.ofSeconds(-1))
                                .nodeId("stealing")
                                .build();
        }

        @Bean
        public TransactionManager transactionManager(PlatformTransactionManager txManager) {
            //noinspection Duplicates
            return () -> {
                TransactionStatus transaction = txManager.getTransaction(new DefaultTransactionDefinition());
                return new Transaction() {
                    @Override
                    public void commit() {
                        txManager.commit(transaction);
                    }

                    @Override
                    public void rollback() {
                        txManager.rollback(transaction);
                    }
                };
            };
        }

        @Configuration
        public static class PersistenceConfig {

            @PersistenceContext
            private EntityManager entityManager;

            @Bean
            public EntityManagerProvider entityManagerProvider() {
                return new SimpleEntityManagerProvider(entityManager);
            }

        }
    }
}
