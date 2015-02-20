package com.vladmihalcea.hibernate.masterclass.laboratory.util;

import net.ttddyy.dsproxy.listener.SLF4JQueryLoggingListener;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import org.hibernate.Interceptor;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.After;
import org.junit.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.*;

public abstract class AbstractTest {

    protected enum LockType {
        LOCKS,
        MVLOCKS,
        MVCC
    }

    private final ExecutorService executorService = Executors.newFixedThreadPool(1);

    protected final Logger LOGGER = LoggerFactory.getLogger(getClass());

    @FunctionalInterface
    protected static interface TransactionCallable<T> {

        T execute(Session session);
    }

    protected static abstract class TransactionLifecycleCallable<T> implements TransactionCallable<T> {

        protected void beforeTransactionCompletion() {

        }

        protected void afterTransactionCompletion() {

        }
    }

    private SessionFactory sf;

    @Before
    public void init() {
        sf = newSessionFactory();
    }

    @After
    public void destroy() {
        sf.close();
    }

    public SessionFactory getSessionFactory() {
        return sf;
    }

    protected abstract Class<?>[] entities();

    protected String[] packages() {
        return null;
    }

    protected Interceptor interceptor() {
        return null;
    }

    private SessionFactory newSessionFactory() {
        Properties properties = getProperties();
        Configuration configuration = new Configuration().addProperties(properties);
        for(Class<?> entityClass : entities()) {
            configuration.addAnnotatedClass(entityClass);
        }
        String[] packages = packages();
        if(packages != null) {
            for(String scannedPackage : packages) {
                configuration.addPackage(scannedPackage);
            }
        }
        Interceptor interceptor = interceptor();
        if(interceptor != null) {
            configuration.setInterceptor(interceptor);
        }
        return configuration.buildSessionFactory(
                new StandardServiceRegistryBuilder()
                        .applySettings(properties)
                        .build()
        );
    }

    protected Properties getProperties() {
        Properties properties = new Properties();
        properties.put("hibernate.dialect", hibernateDialect());
        //log settings
        properties.put("hibernate.hbm2ddl.auto", "create-drop");
        //data source settings
        properties.put("hibernate.connection.datasource", newDataSource());
        return properties;
    }

    private ProxyDataSource newDataSource() {
        ProxyDataSource proxyDataSource = new ProxyDataSource();
        proxyDataSource.setDataSource(dataSource());
        proxyDataSource.setListener(new SLF4JQueryLoggingListener());
        return proxyDataSource;
    }

    protected String hibernateDialect() {
        return "org.hibernate.dialect.HSQLDialect";
    }

    protected DataSource dataSource() {
        JDBCDataSource dataSource = new JDBCDataSource();
        dataSource.setUrl("jdbc:hsqldb:mem:test;hsqldb.tx=" + lockType().name().toLowerCase());
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    protected <T> T doInTransaction(TransactionCallable<T> callable) {
        T result = null;
        Session session = null;
        Transaction txn = null;
        TransactionLifecycleCallable lifecycleCallable = (callable instanceof TransactionLifecycleCallable)
                ? (TransactionLifecycleCallable) callable : null;
        try {
            session = sf.openSession();
            if (lifecycleCallable != null) {
                lifecycleCallable.beforeTransactionCompletion();
            }
            txn = session.beginTransaction();

            result = callable.execute(session);
            txn.commit();
        } catch (RuntimeException e) {
            if ( txn != null && txn.isActive() ) txn.rollback();
            throw e;
        } finally {
            if (lifecycleCallable != null) {
                lifecycleCallable.afterTransactionCompletion();
            }
            if (session != null) {
                session.close();
            }
        }
        return result;
    }

    protected  <T> void executeAndWait(Callable<T> callable) {
        executeAndWait(Collections.singleton(callable));
    }

    protected  <T> void executeAndWait(Collection<Callable<T>> callables) {
        try {
            List<Future<T>> futures = executorService.invokeAll(callables);
            for (Future<T> future : futures) {
                future.get();
            }
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    protected <T> void executeNoWait(Callable<T> callable, final Callable<Void> completionCallback) {
        final Future<T> future = executorService.submit(callable);
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (!future.isDone()) {
                    sleep(100);
                }
                try {
                    completionCallback.call();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        }).start();
    }

    protected <T> Future<T> executeNoWait(Callable<T> callable) {
        return executorService.submit(callable);
    }

    protected LockType lockType() {
        return LockType.LOCKS;
    }

    protected void awaitOnLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    protected void sleep(int millis) {
        sleep(millis, null);
    }

    protected <V> V sleep(int millis, Callable<V> callable) {
        V result = null;
        try {
            LOGGER.info("Wait {} ms!", millis);
            if (callable != null) {
                result = callable.call();
            }
            Thread.sleep(millis);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return result;
    }
}
