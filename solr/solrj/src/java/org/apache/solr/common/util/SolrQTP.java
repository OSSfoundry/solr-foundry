package org.apache.solr.common.util;

import org.apache.solr.common.ParWork;
import org.apache.solr.common.SolrThread;
import org.apache.solr.logging.MDCLoggingContext;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ReservedThreadExecutor;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

@ManagedObject("A thread pool")
public class SolrQTP extends QueuedThreadPool {

  public SolrQTP() {
    this("jetty", Integer.MAX_VALUE, 12, null);
  }

  public SolrQTP(String name) {
    this(name, Integer.MAX_VALUE, 12, null);
  }

  public SolrQTP(String name, int maxThreads, int minThreads) {
    this(name, maxThreads, minThreads, null);
  }

  public SolrQTP(String name, int maxThreads, int minThreads, BlockingQueue<Runnable> queue) {
    super(maxThreads, minThreads, 3000, -1, queue, new ThreadGroup("jetty"), new MyThreadFactory());
    setName(name);
  }

  @Override public Thread newThread(Runnable runnable) {
    return new JettyThread(getName(), runnable);
  }

  @Override
  protected void doStop() throws Exception
  {
//    ReservedThreadExecutor exec = getBean(ReservedThreadExecutor.class);
//    exec.stop();
    super.doStop();
  }

  public static class JettyThread extends Thread {
    private final Runnable runnable;
    private final String name;
    private volatile Future runnableFuture;

    JettyThread(String name, Runnable runnable) {
      this.runnable = runnable;
      this.name = name;
    }

    @Override
    public synchronized void start() {
      runnableFuture = ParWork.submitIO(name + Thread.currentThread().getId(), () -> {
        try {
          runnable.run();
        } finally {
          JavaBinCodec.THREAD_LOCAL_ARR.remove();
          JavaBinCodec.THREAD_LOCAL_BRR.remove();
          //FastInputStream.THREAD_LOCAL_BYTEARRAY.remove();
          MDCLoggingContext.reset();
        }
      });
    }

    @Override
    public void interrupt() {
      if (runnableFuture != null) {
        runnableFuture.cancel(true);
      }
    }
  }

  private static class MyThreadFactory implements ThreadFactory {
    @Override public Thread newThread(Runnable r) {
      Runnable runnable = new MyRunnable(r);

      SecurityManager s = System.getSecurityManager();
      ThreadGroup group = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();

      return new SolrThread(group, runnable, "jetty");
    }

    private static class MyRunnable implements Runnable {
      private final Runnable r;

      public MyRunnable(Runnable r) {
        this.r = r;
      }

      @Override public void run() {
        try {
          r.run();
        } finally {
          JavaBinCodec.THREAD_LOCAL_ARR.remove();
          JavaBinCodec.THREAD_LOCAL_BRR.remove();
          MDCLoggingContext.reset();
        }
      }
    }
  }
}
