/* 
 * All content copyright Terracotta, Inc., unless otherwise indicated. All rights reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not 
 * use this file except in compliance with the License. You may obtain a copy 
 * of the License at 
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0 
 *   
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT 
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the 
 * License for the specific language governing permissions and limitations 
 * under the License.
 * 
 */

package org.quartz.simpl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.quartz.SchedulerConfigException;
import org.quartz.spi.ThreadPool;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <p>
 * This is class is a simple implementation of a thread pool, based on the
 * <code>{@link org.quartz.spi.ThreadPool}</code> interface.
 * </p>
 * 
 * <p>
 * <CODE>Runnable</CODE> objects are sent to the pool with the <code>{@link #runInThread(Runnable)}</code>
 * method, which blocks until a <code>Thread</code> becomes available.
 * </p>
 * 
 * <p>
 * The pool has a fixed number of <code>Thread</code>s, and does not grow or
 * shrink based on demand.
 * </p>
 * 这是一个基于ThreadPool接口的线程池的简单实现。
 * 可运行对象通过runInThread(Runnable)方法被发送到池中，该方法会阻塞，直到线程可用为止。
 * 线程池有固定数量的线程，不会根据需求增长或收缩。
 * 
 * @author James House
 * @author Juergen Donnerstag
 *
 *
 * SimpleThreadPool 是Quartz实现的 线程池，这个线程池并不是Java中的线程池
 * SimpleThreadPool实现的内容是 创建线程，通过runInThread方法接受外部传过来的任务Runnable,交给内部线程执行
 *
 * 在spring-quartz中 提供了ThreadPool的实现类 LocalTaskExecutorThreadPool 和SimpleThreadPoolTaskExecutor。
 * 其中SimpleThreadPoolTaskExecutor继承自 SimpleThreadPool。
 * public class SimpleThreadPoolTaskExecutor extends SimpleThreadPool
 *
 * 而LocalTaskExecutorThreadPool内部有一个Executor taskExecutor;属性，也就是LocalTaskExecutorThreadPool通过runInThread对外接收任务，然后
 * 又交给内部的taskExecutor线程池来执行:this.taskExecutor.execute(runnable)
 *
 *
 */
public class SimpleThreadPool implements ThreadPool {

    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * 
     * Data members.
     * 
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */

    private int count = -1;

    private int prio = Thread.NORM_PRIORITY;

    private boolean isShutdown = false;
    private boolean handoffPending = false;

    private boolean inheritLoader = false;

    private boolean inheritGroup = true;

    private boolean makeThreadsDaemons = false;

    private ThreadGroup threadGroup;

    private final Object nextRunnableLock = new Object();

    private List<WorkerThread> workers;
    private LinkedList<WorkerThread> availWorkers = new LinkedList<WorkerThread>();
    private LinkedList<WorkerThread> busyWorkers = new LinkedList<WorkerThread>();

    private String threadNamePrefix;

    private final Logger log = LoggerFactory.getLogger(getClass());
    
    private String schedulerInstanceName;

    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * 
     * Constructors.
     * 
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */

    /**
     * <p>
     * Create a new (unconfigured) <code>SimpleThreadPool</code>.
     * </p>
     * 
     * @see #setThreadCount(int)
     * @see #setThreadPriority(int)
     */
    public SimpleThreadPool() {
    }

    /**
     * <p>
     * Create a new <code>SimpleThreadPool</code> with the specified number
     * of <code>Thread</code> s that have the given priority.
     * </p>
     * 
     * @param threadCount
     *          the number of worker <code>Threads</code> in the pool, must
     *          be > 0.
     * @param threadPriority
     *          the thread priority for the worker threads.
     * 
     * @see java.lang.Thread
     */
    public SimpleThreadPool(int threadCount, int threadPriority) {
        setThreadCount(threadCount);
        setThreadPriority(threadPriority);
    }

    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * 
     * Interface.
     * 
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */

    public Logger getLog() {
        return log;
    }

    public int getPoolSize() {
        return getThreadCount();
    }

    /**
     * <p>
     * Set the number of worker threads in the pool - has no effect after
     * <code>initialize()</code> has been called.
     * </p>
     */
    public void setThreadCount(int count) {
        this.count = count;
    }

    /**
     * <p>
     * Get the number of worker threads in the pool.
     * </p>
     */
    public int getThreadCount() {
        return count;
    }

    /**
     * <p>
     * Set the thread priority of worker threads in the pool - has no effect
     * after <code>initialize()</code> has been called.
     * </p>
     */
    public void setThreadPriority(int prio) {
        this.prio = prio;
    }

    /**
     * <p>
     * Get the thread priority of worker threads in the pool.
     * </p>
     */
    public int getThreadPriority() {
        return prio;
    }

    public void setThreadNamePrefix(String prfx) {
        this.threadNamePrefix = prfx;
    }

    public String getThreadNamePrefix() {
        return threadNamePrefix;
    }

    /**
     * @return Returns the
     *         threadsInheritContextClassLoaderOfInitializingThread.
     */
    public boolean isThreadsInheritContextClassLoaderOfInitializingThread() {
        return inheritLoader;
    }

    /**
     * @param inheritLoader
     *          The threadsInheritContextClassLoaderOfInitializingThread to
     *          set.
     */
    public void setThreadsInheritContextClassLoaderOfInitializingThread(
            boolean inheritLoader) {
        this.inheritLoader = inheritLoader;
    }

    public boolean isThreadsInheritGroupOfInitializingThread() {
        return inheritGroup;
    }

    public void setThreadsInheritGroupOfInitializingThread(
            boolean inheritGroup) {
        this.inheritGroup = inheritGroup;
    }


    /**
     * @return Returns the value of makeThreadsDaemons.
     */
    public boolean isMakeThreadsDaemons() {
        return makeThreadsDaemons;
    }

    /**
     * @param makeThreadsDaemons
     *          The value of makeThreadsDaemons to set.
     */
    public void setMakeThreadsDaemons(boolean makeThreadsDaemons) {
        this.makeThreadsDaemons = makeThreadsDaemons;
    }
    
    public void setInstanceId(String schedInstId) {
    }

    public void setInstanceName(String schedName) {
        schedulerInstanceName = schedName;
    }

    public void initialize() throws SchedulerConfigException {

        if(workers != null && workers.size() > 0) // already initialized...
            return;
        
        if (count <= 0) {
            throw new SchedulerConfigException(
                    "Thread count must be > 0");
        }
        if (prio <= 0 || prio > 9) {
            throw new SchedulerConfigException(
                    "Thread priority must be > 0 and <= 9");
        }

        if(isThreadsInheritGroupOfInitializingThread()) {
            threadGroup = Thread.currentThread().getThreadGroup();
        } else {
            // follow the threadGroup tree to the root thread group.
            threadGroup = Thread.currentThread().getThreadGroup();
            ThreadGroup parent = threadGroup;
            while ( !parent.getName().equals("main") ) {
                threadGroup = parent;
                parent = threadGroup.getParent();
            }
            threadGroup = new ThreadGroup(parent, schedulerInstanceName + "-SimpleThreadPool");
            if (isMakeThreadsDaemons()) {
                threadGroup.setDaemon(true);
            }
        }


        if (isThreadsInheritContextClassLoaderOfInitializingThread()) {
            getLog().info(
                    "Job execution threads will use class loader of thread: "
                            + Thread.currentThread().getName());
        }

        // create the worker threads and start them
        /**
         * 创建指定数量的线程
         */
        Iterator<WorkerThread> workerThreads = createWorkerThreads(count).iterator();
        while(workerThreads.hasNext()) {
            WorkerThread wt = workerThreads.next();
            wt.start();
            availWorkers.add(wt);
        }
    }

    protected List<WorkerThread> createWorkerThreads(int createCount) {
        workers = new LinkedList<WorkerThread>();
        for (int i = 1; i<= createCount; ++i) {
            String threadPrefix = getThreadNamePrefix();
            if (threadPrefix == null) {
                threadPrefix = schedulerInstanceName + "_Worker";
            }
            WorkerThread wt = new WorkerThread(this, threadGroup,
                threadPrefix + "-" + i,
                getThreadPriority(),
                isMakeThreadsDaemons());
            if (isThreadsInheritContextClassLoaderOfInitializingThread()) {
                wt.setContextClassLoader(Thread.currentThread()
                        .getContextClassLoader());
            }
            workers.add(wt);
        }

        return workers;
    }

    /**
     * <p>
     * Terminate any worker threads in this thread group.
     * </p>
     * 
     * <p>
     * Jobs currently in progress will complete.
     * </p>
     */
    public void shutdown() {
        shutdown(true);
    }

    /**
     * <p>
     * Terminate any worker threads in this thread group.
     * </p>
     * 
     * <p>
     * Jobs currently in progress will complete.
     * </p>
     */
    public void shutdown(boolean waitForJobsToComplete) {

        synchronized (nextRunnableLock) {
            getLog().debug("Shutting down threadpool...");

            isShutdown = true;

            if(workers == null) // case where the pool wasn't even initialize()ed
                return;

            // signal each worker thread to shut down
            Iterator<WorkerThread> workerThreads = workers.iterator();
            while(workerThreads.hasNext()) {
                WorkerThread wt = workerThreads.next();
                wt.shutdown();
                availWorkers.remove(wt);
            }

            // Give waiting (wait(1000)) worker threads a chance to shut down.
            // Active worker threads will shut down after finishing their
            // current job.
            nextRunnableLock.notifyAll();

            if (waitForJobsToComplete == true) {

                boolean interrupted = false;
                try {
                    // wait for hand-off in runInThread to complete...
                    while(handoffPending) {
                        try {
                            nextRunnableLock.wait(100);
                        } catch(InterruptedException _) {
                            interrupted = true;
                        }
                    }

                    // Wait until all worker threads are shut down
                    while (busyWorkers.size() > 0) {
                        WorkerThread wt = (WorkerThread) busyWorkers.getFirst();
                        try {
                            getLog().debug(
                                    "Waiting for thread " + wt.getName()
                                            + " to shut down");

                            // note: with waiting infinite time the
                            // application may appear to 'hang'.
                            nextRunnableLock.wait(2000);
                        } catch (InterruptedException _) {
                            interrupted = true;
                        }
                    }

                    workerThreads = workers.iterator();
                    while(workerThreads.hasNext()) {
                        WorkerThread wt = (WorkerThread) workerThreads.next();
                        try {
                            wt.join();
                            workerThreads.remove();
                        } catch (InterruptedException _) {
                            interrupted = true;
                        }
                    }
                } finally {
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }

                getLog().debug("No executing jobs remaining, all threads stopped.");
            }
            getLog().debug("Shutdown of threadpool complete.");
        }
    }

    /**
     * <p>
     * Run the given <code>Runnable</code> object in the next available
     * <code>Thread</code>. If while waiting the thread pool is asked to
     * shut down, the Runnable is executed immediately within a new additional
     * thread.
     * </p>
     * 在下一个可用的线程中运行给定的Runnable对象。如果在等待线程池关闭时，Runnable会立即在一个新的附加线程中执行。
     * @param runnable
     *          the <code>Runnable</code> to be added.
     */
    public boolean runInThread(Runnable runnable) {
        if (runnable == null) {
            return false;
        }

        synchronized (nextRunnableLock) {

            handoffPending = true;

            // Wait until a worker thread is available
            while ((availWorkers.size() < 1) && !isShutdown) {
                try {
                    nextRunnableLock.wait(500);
                } catch (InterruptedException ignore) {
                }
            }

            if (!isShutdown) {
                WorkerThread wt = (WorkerThread)availWorkers.removeFirst();
                busyWorkers.add(wt);
                wt.run(runnable);
            } else {
                // If the thread pool is going down, execute the Runnable
                // within a new additional worker thread (no thread from the pool).
                /**
                 * //如果线程池下降，执行Runnable
                 * //在一个新的附加工作线程内(没有来自池的线程)。
                 */
                WorkerThread wt = new WorkerThread(this, threadGroup,
                        "WorkerThread-LastJob", prio, isMakeThreadsDaemons(), runnable);
                busyWorkers.add(wt);
                workers.add(wt);
                wt.start();
            }
            nextRunnableLock.notifyAll();
            handoffPending = false;
        }

        return true;
    }

    public int blockForAvailableThreads() {
        synchronized(nextRunnableLock) {

            while((availWorkers.size() < 1 || handoffPending) && !isShutdown) {
                try {
                    nextRunnableLock.wait(500);
                } catch (InterruptedException ignore) {
                }
            }

            return availWorkers.size();
        }
    }

    protected void makeAvailable(WorkerThread wt) {
        synchronized(nextRunnableLock) {
            if(!isShutdown) {
                availWorkers.add(wt);
            }
            busyWorkers.remove(wt);
            nextRunnableLock.notifyAll();
        }
    }

    protected void clearFromBusyWorkersList(WorkerThread wt) {
        synchronized(nextRunnableLock) {
            busyWorkers.remove(wt);
            nextRunnableLock.notifyAll();
        }
    }

    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * 
     * WorkerThread Class.
     * 
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */

    /**
     * <p>
     * A Worker loops, waiting to execute tasks.
     * </p>
     */
    class WorkerThread extends Thread {

        private final Object lock = new Object();

        // A flag that signals the WorkerThread to terminate.
        private AtomicBoolean run = new AtomicBoolean(true);

        private SimpleThreadPool tp;

        private Runnable runnable = null;
        
        private boolean runOnce = false;

        /**
         * <p>
         * Create a worker thread and start it. Waiting for the next Runnable,
         * executing it, and waiting for the next Runnable, until the shutdown
         * flag is set.
         * </p>
         * 创建一个工作线程并启动它。等待下一个Runnable，执行它，并等待下一个Runnable，直到设置了shutdown标志。
         */
        WorkerThread(SimpleThreadPool tp, ThreadGroup threadGroup, String name,
                     int prio, boolean isDaemon) {

            this(tp, threadGroup, name, prio, isDaemon, null);
        }

        /**
         * <p>
         * Create a worker thread, start it, execute the runnable and terminate
         * the thread (one time execution).
         * </p>
         */
        WorkerThread(SimpleThreadPool tp, ThreadGroup threadGroup, String name,
                     int prio, boolean isDaemon, Runnable runnable) {

            super(threadGroup, name);
            this.tp = tp;
            this.runnable = runnable;
            if(runnable != null)
                runOnce = true;
            setPriority(prio);
            setDaemon(isDaemon);
        }

        /**
         * <p>
         * Signal the thread that it should terminate.
         * </p>
         */
        void shutdown() {
            run.set(false);
        }

        /**
         * 可执行任务有两种设置方式（1）创建WorkThread的时候通过构造器指定
         * （2） 通过WorkThread的run方法设置可执行的runable，  wt.run(runnable);比如在QuartzSchedulerThread的run方法中
         * 会获取可以执行的trigger，然后创建一个Runnable：JobRunShell对象，然后交给ThreadPool ：qsRsrcs.getThreadPool().runInThread(shell)
         * 在ThreadPool的runInThread方法中实际上会获取availWorkers中的WorkThread，然后调用workThread的run（Runnable）方法设置runnable
         *
         *
         */
        public void run(Runnable newRunnable) {
            synchronized(lock) {
                if(runnable != null) {
                    throw new IllegalStateException("Already running a Runnable!");
                }

                runnable = newRunnable;
                lock.notifyAll();
            }
        }

        /**
         * <p>
         * Loop, executing targets as they are received.
         * </p>
         * 我们知道Workthread继承自Thread ，thread实现了Runnable接口，因此Thread中存在run
         * 方法，这里workThread重写了Thread的run方法.
         *
         *
         * 在Java中也有线程池的实现，比如ThreadPoolExecutor ，在这个类中,java.util.concurrent.ThreadPoolExecutor#execute(java.lang.Runnable)
         * 方法用于在线程池的线程中执行Runnable,execute方法的逻辑是判断当前线程池中的线程数量是否小于核心线程数，如果小于则创建新的线程。
         * 在Java的线程池的实现中使用了Work类表示线程池中的线程，java.util.concurrent.ThreadPoolExecutor.Worker,
         * 这个Work类实现了Runnable方法，而不是继承Thread类。这一点不同于这里的WorkThread。
         * 只不过在Work的实现中，在Work的构造器中会创建后一个Thead，   this.thread = getThreadFactory().newThread(this);放置到属性中。
         * 因此一个Work就是一个Thead，值得注意的是Work是Runnable，我们在未Work创建Thread的时候讲 work对象作为参数传入了therad
         * 因此Work中的Thread 执行的时候将会运行work的run方法。在work的run方法中会通过getTask方法从任务队列中取出任务Runnable，然后调用该任务的run方法
         * 从而实现执行任务。
         *
         *
         * WorkThread作为Thread，重写了run方法
         *
         */
        @Override
        public void run() {
            boolean ran = false;

            /**
             *  判断线程是否需要停止，在WorkThread的shutdown方法中会将其设置为false。
             *
             *
             */
            while (run.get()) {
                try {
                    /**
                     *获取到lock上的锁， 典型使用方式 synchronized(obj){  while(condition) obj.wait ;  execute bussiness}
                     *
                     * 为什么要加锁，因为我们想当线程没有Runnable的时候 使得当前线程阻塞等待。
                     * 为什么线程会没有Runnable可运行？ 考虑这样一种情况： 线程池创建的时候 可能一把创建10个线程，然后这10个线程都启动开始执行run方法。
                     * 在WorkThread中存在一个Runnable属性，这个属性时真正的线程要运行的业务逻辑，但是线程启动时并不是每一个线程都会有任务逻辑需要执行。
                     * 因此有些线程没有任务逻辑需要执行，此时我们需要将其设置为等待状态，等到有了任务逻辑的时候再执行。
                     * 这个时候怎么实现呢？一种方式是WorkThread内有一个阻塞队列，WorkThrea的run方法在队列中取任务执行，当没有任务的时候必然阻塞run方法所在线程。
                     * 但是这里并没有使用阻塞队列，而是使用了 Object的wait方法实现线程的阻塞。因此就出现了下面的逻辑： 首先获取lock上的锁，判断有没有任务需要执行，如果没有
                     * 则阻塞当前线程lock.wait; 另外在WorkThread的run(Runnable run)方法中 会在给属性runnable赋值后 调用lock的notifyAll方法唤醒线程。因此我们就看到了run(Runnable)
                     * 方法中也会使用synchronized(lock){lock.notifyALl}
                     *
                     *
                     *
                     *
                     */
                    synchronized(lock) {
                        /**
                         * 判断是否有任务可执行，可执行任务有两种设置方式（1）创建WorkThread的时候通过构造器指定
                         * （2） 通过WorkThread的run方法设置可执行的runable，  wt.run(runnable);比如在QuartzSchedulerThread的run方法中
                         * 会获取可以执行的trigger，然后创建一个Runnable：JobRunShell对象，然后交给ThreadPool ：qsRsrcs.getThreadPool().runInThread(shell)
                         * 在ThreadPool的runInThread方法中实际上会获取availWorkers中的WorkThread，然后调用workThread的run（Runnable）方法设置runnable
                         */
                        while (runnable == null && run.get()) {
                            lock.wait(500);
                        }

                        /**
                         * 当runnable不为null时退出while,执行runnable的逻辑
                         */
                        if (runnable != null) {
                            /**
                             *ran表示业务任务逻辑是否在执行
                             */
                            ran = true;
                            runnable.run();
                        }
                    }
                } catch (InterruptedException unblock) {
                    // do nothing (loop will terminate if shutdown() was called
                    try {
                        getLog().error("Worker thread was interrupt()'ed.", unblock);
                    } catch(Exception e) {
                        // ignore to help with a tomcat glitch
                    }
                } catch (Throwable exceptionInRunnable) {
                    try {
                        getLog().error("Error while executing the Runnable: ",
                            exceptionInRunnable);
                    } catch(Exception e) {
                        // ignore to help with a tomcat glitch
                    }
                } finally {
                    synchronized(lock) {
                        /**
                         * runnable执行完之后将其清空，这样就会是的WorkThread可以继续执行下一个任务。
                         */
                        runnable = null;
                    }
                    // repair the thread in case the runnable mucked it up...
                    if(getPriority() != tp.getThreadPriority()) {
                        setPriority(tp.getThreadPriority());
                    }

                    /**
                     * runOnce 表示当前线程WorkThread只执行一次
                     */
                    if (runOnce) {
                        run.set(false);
                        clearFromBusyWorkersList(this);
                    } else if(ran) {
                        /**
                         * 执行runnable的时候会将ran设置为true
                         */
                        ran = false;
                        /**
                         * makeAvailable会将当前线程从busyWorkers移除，同时添加到availableWorkers中
                         *  busyWorkers.remove(wt);
                         *  availWorkers.add(wt);
                         *  问题：当前线程什么时候被放置到 busyWorkers中？
                         *  在ThreadPool的runInThread方法中 会从availWorkers中取出一个WorkThread，然后将其放置到busyWorkers中，同时将runInThread
                         *  方法接收的参数Runnable交给WorkThread
                         *  WorkerThread wt = (WorkerThread)availWorkers.removeFirst();
                         *  busyWorkers.add(wt);
                         *  wt.run(runnable);
                         */
                        makeAvailable(this);
                    }

                }
            }

            //if (log.isDebugEnabled())
            try {
                getLog().debug("WorkerThread is shut down.");
            } catch(Exception e) {
                // ignore to help with a tomcat glitch
            }
        }
    }
}
