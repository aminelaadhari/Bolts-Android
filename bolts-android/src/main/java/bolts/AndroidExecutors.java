/*
 *  Copyright (c) 2014, Facebook, Inc.
 *  All rights reserved.
 *
 *  This source code is licensed under the BSD-style license found in the
 *  LICENSE file in the root directory of this source tree. An additional grant
 *  of patent rights can be found in the PATENTS file in the same directory.
 *
 */
package bolts;

import android.annotation.SuppressLint;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * This was created because the helper methods in {@link java.util.concurrent.Executors} do not work
 * as people would normally expect.
 * Normally, you would think that a cached thread pool would create new threads when necessary,
 * queue them when the pool is full, and kill threads when they've been inactive for a certain
 * period of time. This is not how {@link java.util.concurrent.Executors#newCachedThreadPool()}
 * works.
 * Instead, {@link java.util.concurrent.Executors#newCachedThreadPool()} executes all tasks on
 * a new or cached thread immediately because corePoolSize is 0, SynchronousQueue is a queue with
 * size 0 and maxPoolSize is Integer.MAX_VALUE. This is dangerous because it can create an unchecked
 * amount of threads.
 */
public final class AndroidExecutors implements BoltsExecutors {

    /* package */ static final long KEEP_ALIVE_TIME = 1L;
    private static final AndroidExecutors INSTANCE = new AndroidExecutors();
    /**
     * Nexus 5: Quad-Core
     * Moto X: Dual-Core
     * AsyncTask:
     * CORE_POOL_SIZE = CPU_COUNT + 1
     * MAX_POOL_SIZE = CPU_COUNT * 2 + 1
     * https://github.com/android/platform_frameworks_base/commit/719c44e03b97e850a46136ba336d729f5fbd1f47
     */
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    /* package */ static final int CORE_POOL_SIZE = CPU_COUNT + 1;
    /* package */ static final int MAX_POOL_SIZE = CPU_COUNT * 2 + 1;

    private final Executor uiThread;
    private final ExecutorService background;
    private final ScheduledExecutorService scheduled;
    private final Executor immediate;

    private AndroidExecutors() {
        uiThread = new UIThreadExecutor();
        background = newCachedThreadPool();
        scheduled = Executors.newSingleThreadScheduledExecutor();
        immediate = new ImmediateExecutor(background);
    }

    /**
     * Creates a proper Cached Thread Pool. Tasks will reuse cached threads if available
     * or create new threads until the core pool is full. tasks will then be queued. If an
     * task cannot be queued, a new thread will be created unless this would exceed max pool
     * size, then the task will be rejected. Threads will time out after 1 second.
     * Core thread timeout is only available on android-9+.
     *
     * @return the newly created thread pool
     */
    public static ExecutorService newCachedThreadPool() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                CORE_POOL_SIZE,
                MAX_POOL_SIZE,
                KEEP_ALIVE_TIME, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>());

        allowCoreThreadTimeout(executor, true);

        return executor;
    }

    /**
     * Creates a proper Cached Thread Pool. Tasks will reuse cached threads if available
     * or create new threads until the core pool is full. tasks will then be queued. If an
     * task cannot be queued, a new thread will be created unless this would exceed max pool
     * size, then the task will be rejected. Threads will time out after 1 second.
     * Core thread timeout is only available on android-9+.
     *
     * @param threadFactory the factory to use when creating new threads
     * @return the newly created thread pool
     */
    public static ExecutorService newCachedThreadPool(ThreadFactory threadFactory) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                CORE_POOL_SIZE,
                MAX_POOL_SIZE,
                KEEP_ALIVE_TIME, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(),
                threadFactory);

        allowCoreThreadTimeout(executor, true);

        return executor;
    }

    /**
     * Compatibility helper function for
     * {@link java.util.concurrent.ThreadPoolExecutor#allowCoreThreadTimeOut(boolean)}
     * Only available on android-9+.
     *
     * @param executor the {@link java.util.concurrent.ThreadPoolExecutor}
     * @param value    true if should time out, else false
     */
    @SuppressLint("NewApi")
    public static void allowCoreThreadTimeout(ThreadPoolExecutor executor, boolean value) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
            executor.allowCoreThreadTimeOut(value);
        }
    }

    @Override
    public ScheduledExecutorService scheduled() {
        return scheduled;
    }

    @Override
    public Executor immediate() {
        return immediate;
    }

    @Override
    public Executor background() {
        return background;
    }

    @Override
    public Executor uiTread() {
        return uiThread;
    }

    /**
     * An {@link java.util.concurrent.Executor} that runs tasks on the UI thread.
     */
    private static class UIThreadExecutor implements Executor {
        @Override
        public void execute(Runnable command) {
            new Handler(Looper.getMainLooper()).post(command);
        }
    }

    private static class ImmediateExecutor implements Executor {
        private static final int MAX_DEPTH = 15;
        private ThreadLocal<Integer> executionDepth = new ThreadLocal<>();
        private ExecutorService background;

        public ImmediateExecutor(ExecutorService background) {
            this.background = background;
        }

        /**
         * Increments the depth.
         *
         * @return the new depth value.
         */
        private int incrementDepth() {
            Integer oldDepth = executionDepth.get();
            if (oldDepth == null) {
                oldDepth = 0;
            }
            int newDepth = oldDepth + 1;
            executionDepth.set(newDepth);
            return newDepth;
        }

        /**
         * Decrements the depth.
         *
         * @return the new depth value.
         */
        private int decrementDepth() {
            Integer oldDepth = executionDepth.get();
            if (oldDepth == null) {
                oldDepth = 0;
            }
            int newDepth = oldDepth - 1;
            if (newDepth == 0) {
                executionDepth.remove();
            } else {
                executionDepth.set(newDepth);
            }
            return newDepth;
        }

        @Override
        public void execute(Runnable command) {
            int depth = incrementDepth();
            try {
                if (depth <= MAX_DEPTH) {
                    command.run();
                } else {
                    background.execute(command);
                }
            } finally {
                decrementDepth();
            }
        }
    }
}
