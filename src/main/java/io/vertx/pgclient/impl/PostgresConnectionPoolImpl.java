package io.vertx.pgclient.impl;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PostgresConnection;
import io.vertx.pgclient.PostgresConnectionPool;
import io.vertx.pgclient.Result;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
class PostgresConnectionPoolImpl implements PostgresConnectionPool {

  private final PostgresClientImpl client;
  private final Context context;
  private final ArrayDeque<Holder> available = new ArrayDeque<>();
  private final ArrayDeque<Waiter> waiters = new ArrayDeque<>();
  private final int maxSize;
  private int connCount;

  private static class Waiter {

    private final Handler<AsyncResult<PostgresConnection>> handler;
    private final Context context;

    Waiter(Handler<AsyncResult<PostgresConnection>> handler, Context context) {
      this.handler = handler;
      this.context = context;
    }

    void use(PostgresConnection conn) {
      Context current = Vertx.currentContext();
      if (current == context) {
        handler.handle(Future.succeededFuture(conn));
      } else {
        context.runOnContext(v -> {
          use(conn);
        });
      }
    }
  }

  PostgresConnectionPoolImpl(PostgresClientImpl client, int maxSize) {
    this.context = client.vertx.getOrCreateContext();
    this.client = client;
    this.maxSize = maxSize;
  }

  @Override
  public void getConnection(Handler<AsyncResult<PostgresConnection>> handler) {
    Context current = Vertx.currentContext();
    if (current == context) {
      Holder holder = available.poll();
      if (holder != null) {
        handler.handle(Future.succeededFuture(holder.createProxy()));
      } else if (connCount < maxSize) {
        openConnection(handler, current);
      } else {
        waiters.add(new Waiter(handler, current));
      }
    } else {
      this.context.runOnContext(v -> getConnection(handler));
    }
  }

  private class Holder {

    private final PostgresConnection conn;
    private boolean failed;
    private volatile Handler<Throwable> exceptionHandler;
    private volatile Handler<Void> closeHandler;

    private Holder(PostgresConnection conn) {
      this.conn = conn;
      conn.exceptionHandler(err -> {
        failed = true;
        Handler<Throwable> handler = exceptionHandler;
        if (handler != null) {
          handler.handle(err);
        }
      });
      conn.closeHandler(v -> {
        Handler<Void> handler = closeHandler;
        removeFromPool();
        if (handler != null) {
          handler.handle(null);
        }
      });
    }

    private void proxyClosed() {
      if (Vertx.currentContext() == context) {
        if (!failed) {
          returnToPool(this);
        }
      } else {
        context.runOnContext(v -> proxyClosed());
      }
    }

    PostgresConnection createProxy() {
      return new PostgresConnection() {
        final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public void execute(String sql, Handler<AsyncResult<Result>> handler) {
          if (!closed.get()) {
            conn.execute(sql, handler);
          }
        }

        @Override
        public void exceptionHandler(Handler<Throwable> handler) {
          if (!closed.get()) {
            exceptionHandler = handler;
          }
        }

        @Override
        public void closeHandler(Handler<Void> handler) {
          if (!closed.get()) {
            closeHandler = handler;
          }
        }

        @Override
        public void close() {
          if (closed.compareAndSet(false, true)) {
            closeHandler = null;
            exceptionHandler = null;
            proxyClosed();
          }
        }
      };
    }
  }

  private void openConnection(Handler<AsyncResult<PostgresConnection>> handler, Context handlerContext) {
    connCount++;
    client.connect(ar -> {
      if (ar.succeeded()) {
        PostgresConnection conn = ar.result();
        handler.handle(Future.succeededFuture(new Holder(conn).createProxy()));
      } else {
        // number of retry should be bounded
        openConnection(handler, handlerContext);
      }
    });
  }

  private void removeFromPool() {
    connCount--;
    Waiter waiter = waiters.poll();
    if (waiter != null) {
      openConnection(waiter.handler, waiter.context);
    }
  }

  private void returnToPool(Holder holder) {
    Waiter waiter = waiters.poll();
    if (waiter != null) {
      waiter.use(holder.createProxy());
    } else {
      available.add(holder);
    }
  }

  @Override
  public void close() {
    throw new UnsupportedOperationException();
  }
}
