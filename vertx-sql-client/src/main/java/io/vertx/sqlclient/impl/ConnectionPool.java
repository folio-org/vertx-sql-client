/*
 * Copyright (C) 2017 Julien Viet
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.vertx.sqlclient.impl;

import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.impl.command.CommandBase;
import io.vertx.sqlclient.spi.DatabaseMetadata;
import io.vertx.core.*;
import io.vertx.core.impl.NoStackTraceThrowable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class ConnectionPool {

  private final Consumer<Handler<AsyncResult<Connection>>> connector;
  private final Context context;
  private final int maxSize;
  private final ArrayDeque<Promise<Connection>> waiters = new ArrayDeque<>();
  private final Set<PooledConnection> all = new HashSet<>();
  private final ArrayDeque<PooledConnection> available = new ArrayDeque<>();
  private int size;
  private final int maxWaitQueueSize;
  private final int connectionReleaseDelay;
  private boolean checkInProgress;
  private boolean closed;

  /*
<<<<<<< HEAD:vertx-sql-client/src/main/java/io/vertx/sqlclient/impl/ConnectionPool.java
  public ConnectionPool(Consumer<Handler<AsyncResult<Connection>>> connector) {
    this(connector, PoolOptions.DEFAULT_MAX_SIZE, PoolOptions.DEFAULT_MAX_WAIT_QUEUE_SIZE);
=======
  public ConnectionPool(ConnectionFactory connector, int maxSize) {
    this(connector, null, new PoolOptions().setMaxSize(maxSize));
>>>>>>> 1a5953b9... connectionReleaseDelay PoolOption:vertx-sql-client/src/main/java/io/vertx/sqlclient/impl/pool/ConnectionPool.java
  }

  public ConnectionPool(Consumer<Handler<AsyncResult<Connection>>> connector, int maxSize) {
    this(connector, maxSize, PoolOptions.DEFAULT_MAX_WAIT_QUEUE_SIZE);
  }

<<<<<<< HEAD:vertx-sql-client/src/main/java/io/vertx/sqlclient/impl/ConnectionPool.java
  public ConnectionPool(Consumer<Handler<AsyncResult<Connection>>> connector, int maxSize, int maxWaitQueueSize) {
    this.maxSize = maxSize;
    this.maxWaitQueueSize = maxWaitQueueSize;
=======
  public ConnectionPool(ConnectionFactory connector, Context context, int maxSize, int maxWaitQueueSize) {
    this(connector, context, new PoolOptions().setMaxSize(maxSize).setMaxWaitQueueSize(maxWaitQueueSize));
  }
*/
  public ConnectionPool(Consumer<Handler<AsyncResult<Connection>>> connector, Context context, PoolOptions poolOptions) {
    Objects.requireNonNull(connector, "No null connector");
    this.context = context;
    this.maxSize = poolOptions.getMaxSize();
    if (maxSize < 1) {
      throw new IllegalArgumentException("Pool max size must be > 0");
    }
    this.maxWaitQueueSize = poolOptions.getMaxWaitQueueSize();
    this.connectionReleaseDelay = poolOptions.getConnectionReleaseDelay();
    if (connectionReleaseDelay > 0 && context == null) {
      throw new NullPointerException("context must not be null for connectionReleaseDelay");
    }
    this.connector = connector;
  }

  public ConnectionPool(Consumer<Handler<AsyncResult<Connection>>> connector) {
    this(connector, null, new PoolOptions());
  }

  public ConnectionPool(Consumer<Handler<AsyncResult<Connection>>> connector, int maxSize) {
    this(connector, null, new PoolOptions().setMaxSize(maxSize));
  }

  public ConnectionPool(Consumer<Handler<AsyncResult<Connection>>> connector, int maxSize, int maxWaitQueueSize) {
    this(connector, null, new PoolOptions().setMaxSize(maxSize).setMaxWaitQueueSize(maxWaitQueueSize));
  }

  public int allSize() {
    return all.size();
  }

  public int available() {
    return available.size();
  }

  public int size() {
    return size;
  }

  public void acquire(Handler<AsyncResult<Connection>> holder) {
    if (closed) {
      throw new IllegalStateException("Connection pool closed");
    }
    Promise<Connection> promise = Promise.promise();
    promise.future().setHandler(holder);
    waiters.add(promise);
    check();
  }

  public void close() {
    if (closed) {
      throw new IllegalStateException("Connection pool already closed");
    }
    closed = true;
    for (PooledConnection pooled : new ArrayList<>(all)) {
      pooled.close();
    }
    Future<Connection> failure = Future.failedFuture("Connection pool closed");
    for (Promise<Connection> pending : waiters) {
      try {
        pending.handle(failure);
      } catch (Exception ignore) {
      }
    }
  }

  private class PooledConnection implements Connection, Connection.Holder  {

    private final Connection conn;
    private Holder holder;
    private Long idleTimer;

    PooledConnection(Connection conn) {
      this.conn = conn;
    }

    @Override
    public boolean isSsl() {
      return conn.isSsl();
    }

    @Override
    public DatabaseMetadata getDatabaseMetaData() {
      return conn.getDatabaseMetaData();
    }

    @Override
    public void schedule(CommandBase<?> cmd) {
      conn.schedule(cmd);
    }

    /**
     * Close the underlying connection
     */
    private void close() {
      cancelIdleTimer();
      conn.close(this);
    }

    @Override
    public void init(Holder holder) {
      if (this.holder != null) {
        throw new IllegalStateException();
      }
      this.holder = holder;
    }

    @Override
    public void close(Holder holder) {
      if (holder != this.holder) {
        throw new IllegalStateException();
      }
      this.holder = null;
      addToPool();
    }

    @Override
    public void handleClosed() {
      if (all.remove(this)) {
        size--;
        if (holder == null) {
          available.remove(this);
        } else {
          holder.handleClosed();
        }
        check();
      } else {
        throw new IllegalStateException();
      }
    }

    @Override
    public void handleNotification(int processId, String channel, String payload) {
      if (holder != null) {
        holder.handleNotification(processId, channel, payload);
      }
    }

    @Override
    public void handleException(Throwable err) {
      if (holder != null) {
        holder.handleException(err);
      }
    }

    @Override
    public int getProcessId() {
      return conn.getProcessId();
    }

    @Override
    public int getSecretKey() {
      return conn.getSecretKey();
    }

    private void addToPool() {
      if (! all.contains(this)) {
        return;
      }
      available.add(this);
      check();
      if (connectionReleaseDelay <= 0 || ! this.equals(available.peekLast())) {
        return;
      }
      idleTimer = context.owner().setTimer(connectionReleaseDelay, t -> expire());
    }

    /**
     * Remove from {@code available} and {@code all} and close connection.
     */
    private void expire() {
      if (idleTimer == null) {
        return;
      }
      idleTimer = null;
      available.remove(this);
      all.remove(this);
      close();
    }

    private void cancelIdleTimer() {
      if (idleTimer != null) {
        context.owner().cancelTimer(idleTimer);
        idleTimer = null;
      }
    }
  }

  private void check() {
    if (closed) {
      return;
    }
    if (!checkInProgress) {
      checkInProgress = true;
      try {
        while (waiters.size() > 0) {
          if (available.size() > 0) {
            PooledConnection proxy = available.poll();
            proxy.cancelIdleTimer();
            Promise<Connection> waiter = waiters.poll();
            waiter.complete(proxy);
          } else {
            if (size < maxSize) {
              Promise<Connection> waiter = waiters.poll();
              size++;
              connector.accept(ar -> {
                if (ar.succeeded()) {
                  Connection conn = ar.result();
                  PooledConnection proxy = new PooledConnection(conn);
                  all.add(proxy);
                  conn.init(proxy);
                  waiter.complete(proxy);
                } else {
                  size--;
                  waiter.fail(ar.cause());
                  check();
                }
              });
            } else {
              if (maxWaitQueueSize >= 0) {
                int numInProgress = size - all.size();
                int numToFail = waiters.size() - (maxWaitQueueSize + numInProgress);
                while (numToFail-- > 0) {
                  Promise<Connection> waiter = waiters.pollLast();
                  waiter.fail(new NoStackTraceThrowable("Max waiter size reached"));
                }
              }
              break;
            }
          }
        }
      } finally {
        checkInProgress = false;
      }
    }
  }
}
