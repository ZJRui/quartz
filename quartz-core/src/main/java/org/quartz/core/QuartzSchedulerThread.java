
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

package org.quartz.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import org.quartz.JobPersistenceException;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.Trigger.CompletedExecutionInstruction;
import org.quartz.spi.JobStore;
import org.quartz.spi.OperableTrigger;
import org.quartz.spi.TriggerFiredBundle;
import org.quartz.spi.TriggerFiredResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>
 * The thread responsible for performing the work of firing <code>{@link Trigger}</code>
 * s that are registered with the <code>{@link QuartzScheduler}</code>.
 * </p>
 *负责执行触发已注册到QuartzScheduler的触发器的工作的线程。
 * @see QuartzScheduler
 * @see org.quartz.Job
 * @see Trigger
 *
 * @author James House
 */
public class QuartzSchedulerThread extends Thread {
    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     *
     * Data members.
     *
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */
    private QuartzScheduler qs;

    private QuartzSchedulerResources qsRsrcs;

    private final Object sigLock = new Object();

    private boolean signaled;
    private long signaledNextFireTime;

    private boolean paused;

    private AtomicBoolean halted;

    private Random random = new Random(System.currentTimeMillis());

    // When the scheduler finds there is no current trigger to fire, how long
    // it should wait until checking again...
    /**
     * //当调度程序发现当前没有触发器要触发时，多长时间
     * //它应该等到再次检查…
     */
    private static long DEFAULT_IDLE_WAIT_TIME = 30L * 1000L;

    private long idleWaitTime = DEFAULT_IDLE_WAIT_TIME;

    private int idleWaitVariablness = 7 * 1000;

    private final Logger log = LoggerFactory.getLogger(getClass());

    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     *
     * Constructors.
     *
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */

    /**
     * <p>
     * Construct a new <code>QuartzSchedulerThread</code> for the given
     * <code>QuartzScheduler</code> as a non-daemon <code>Thread</code>
     * with normal priority.
     * </p>
     */
    QuartzSchedulerThread(QuartzScheduler qs, QuartzSchedulerResources qsRsrcs) {
        this(qs, qsRsrcs, qsRsrcs.getMakeSchedulerThreadDaemon(), Thread.NORM_PRIORITY);
    }

    /**
     * <p>
     * Construct a new <code>QuartzSchedulerThread</code> for the given
     * <code>QuartzScheduler</code> as a <code>Thread</code> with the given
     * attributes.
     * </p>
     */
    QuartzSchedulerThread(QuartzScheduler qs, QuartzSchedulerResources qsRsrcs, boolean setDaemon, int threadPrio) {
        super(qs.getSchedulerThreadGroup(), qsRsrcs.getThreadName());
        this.qs = qs;
        this.qsRsrcs = qsRsrcs;
        this.setDaemon(setDaemon);
        if(qsRsrcs.isThreadsInheritInitializersClassLoadContext()) {
            log.info("QuartzSchedulerThread Inheriting ContextClassLoader of thread: " + Thread.currentThread().getName());
            this.setContextClassLoader(Thread.currentThread().getContextClassLoader());
        }

        this.setPriority(threadPrio);

        // start the underlying thread, but put this object into the 'paused'
        // state
        // so processing doesn't start yet...
        /**
         * //启动底层线程，但将这个对象放到'paused'中
         * / /状态
         * //处理还没有开始…
         */
        paused = true;
        halted = new AtomicBoolean(false);
    }

    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     *
     * Interface.
     *
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */

    void setIdleWaitTime(long waitTime) {
        idleWaitTime = waitTime;
        idleWaitVariablness = (int) (waitTime * 0.2);
    }

    private long getRandomizedIdleWaitTime() {
        return idleWaitTime - random.nextInt(idleWaitVariablness);
    }

    /**
     * <p>
     * Signals the main processing loop to pause at the next possible point.
     * 指示主处理循环在下一个可能的点暂停。
     * 1,QuartzScheduler的start的时候会将其设置为false
     * 2，QuartzScheduler的standBy的时候会将其设置为true
     * 3.QuartzSchedulerThread的构造器中将其设置为true
     * </p>
     */
    void togglePause(boolean pause) {
        synchronized (sigLock) {
            paused = pause;

            if (paused) {
                signalSchedulingChange(0);
            } else {
                sigLock.notifyAll();
            }
        }
    }

    /**
     * <p>
     * Signals the main processing loop to pause at the next possible point.
     * 指示主处理循环在下一个可能的点暂停。
     * org.quartz.core.QuartzScheduler#shutdown()会调用halt方法
     * </p>
     */
    void halt(boolean wait) {
        synchronized (sigLock) {
            halted.set(true);

            if (paused) {
                sigLock.notifyAll();
            } else {
                signalSchedulingChange(0);
            }
        }
        
        if (wait) {
            boolean interrupted = false;
            try {
                while (true) {
                    try {
                        join();
                        break;
                    } catch (InterruptedException _) {
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    boolean isPaused() {
        return paused;
    }

    /**
     * <p>
     * Signals the main processing loop that a change in scheduling has been
     * made - in order to interrupt any sleeping that may be occuring while
     * waiting for the fire time to arrive.
     * </p>
     *
     * @param candidateNewNextFireTime the time (in millis) when the newly scheduled trigger
     * will fire.  If this method is being called do to some other even (rather
     * than scheduling a trigger), the caller should pass zero (0).<br>
     *
     *
     *
     * <p>向主处理循环发出调度发生变化的信号——以便在等待触发时间到来时中断可能发生的任何睡眠。
     *
     * 参数:
     * candidateNewNextFireTime——新调度的触发器触发的时间(单位为毫秒)。如果这个方法被调用到其他方法(而不是调度触发器)，调用者应该传递0(0)。
     * 石英核心</p>
     */
    public void signalSchedulingChange(long candidateNewNextFireTime) {
        synchronized(sigLock) {
            signaled = true;
            signaledNextFireTime = candidateNewNextFireTime;
            sigLock.notifyAll();
        }
    }

    public void clearSignaledSchedulingChange() {
        synchronized(sigLock) {
            signaled = false;
            signaledNextFireTime = 0;
        }
    }

    public boolean isScheduleChanged() {
        synchronized(sigLock) {
            return signaled;
        }
    }

    public long getSignaledNextFireTime() {
        synchronized(sigLock) {
            return signaledNextFireTime;
        }
    }

    /**
     * <p>
     * The main processing loop of the <code>QuartzSchedulerThread</code>.
     * </p>
     */
    @Override
    public void run() {
        int acquiresFailed = 0;
        // 只有调用了halt()方法，才会退出这个死循环,halted为false则执行while.
        /**
         * 提供了halt方法，在halt方法中会将 halted设置为true 此时会导致退出while.
         * org.quartz.core.QuartzScheduler#shutdown()会调用halt方法
         */
        while (!halted.get()) {
            try {
                // check if we're supposed to pause...
                /**
                 * 问题：为什么要synchronized？因为后面我们调用sigLock的wait方法，调用wait方法必须要先保证线程能够获取到对象上的锁.
                 * The Object.wait method is used to make a thread wait for some condition. It must be
                 * invoked inside a synchronized region that locks the object on which it is invoked. This is the
                 * standard idiom for using the wait method:
                 *
                 * 问题2： 在Java的 Object的wait方法的官方文档说明中有提到 wait方法的标准使用方式，就是下面的这种 step1 首先获取对象的锁
                 * step2：使用while循环锁定条件  step3 while循环内调用对象的wait方法。  因此问题就是 为什么标准方式中推荐是在while循环内调用wait
                 * 而不是  synchronized(object){  objec.wait() }
                 *  官方文档中有提到 wait方法将会使得当前线程阻塞并释放对象的锁，同时当 出现（1）其他线程通过object对象调用了notify或者notifyAll （2）当前线程被interrupt抛出interruptException
                 *  （3）wait等待超过指定的超时时间  这三种情况会导致线程被唤醒。
                 *  官方文档：线程也可以在没有被通知、中断或超时的情况下唤醒，即所谓的虚假唤醒。虽然这在实践中很少发生，但应用程序必须通过测试应该引起线程被唤醒的条件来防止它，如果条件不满足，则继续等待。换句话说，等待应该总是在循环中发生，就像下面这个:
                 *
                 *
                 *
                 *
                 *
                 */
                synchronized (sigLock) {

                    /**
                     *  //1.等待QuartzScheduler启动
                     *     1,QuartzScheduler的start的时候会将其设置为false
                     *      * 2，QuartzScheduler的standBy的时候会将其设置为true
                     *      * 3.QuartzSchedulerThread的构造器中将其设置为true
                     *
                     *      paused为true表示暂停，因此paused为true的时候不退出while
                     *
                     * 循环检查paused && !halted.get()条件是否满足，否则释放sigLock对象的锁，并等待，一秒后重试。
                     * 当QuartzScheduler对象创建并调用start()方法时，将唤醒QuartzSchedulerThread线程，即可跳出阻塞块，继续执行。
                     *
                     * Q1:halted 表示是否调用了shutdown 来终止，在shutdown中会调用halt方法来设置halted遍历为true。halted变量在构造器中默认设置为了false
                     * Q2:paused 表示是否调用了start方法来启动，start方法中会将paused设置为false；paused 变量在构造器中默认设置为了true
                     *Q3：QuartzSchedulerThread本身是一个Runnable，他被放置到了线程池中，所以其run方法会立即执行，但是我们要等待调用了start方法之后才会执行具体的业务逻辑，因此这里通过paused和halted变量来实现控制
                     *
                     */
                    while (paused && !halted.get()) {
                        try {
                            // Part A：如果是暂停状态，那么循环超时等待1000毫秒
                            // wait until togglePause(false) is called...
                            sigLock.wait(1000L);
                            /**
                             * 对于 SIGLock对象的wait方法， 什么时候调用notify方法唤醒？
                             *QuartzScheduler的start方法会调用schedThread.togglePause(false);，在togglePause方法中
                             * 如果pause为false ，意味着不暂停，会通过sigLock.notifyAll()唤醒在sigLock上等待的所有线程
                             */
                        } catch (InterruptedException ignore) {
                        }

                        //当暂停时重置失败计数器，这样我们就不会
                        //在取消暂停后再次等待
                        // reset failure counter when paused, so that we don't
                        // wait again after unpausing
                        /**
                         * acquiresFailed在下面的获取trigger acquireNextTriggers的catch中会增加，因此
                         * acquiresFailed表示的是获取trigger失败的次数。这里是重置获取trigger的失败次数
                         *
                         */
                        acquiresFailed = 0;
                    }
                    /**
                     * 如果halted为true则 退出， 一般shutdown的时候会设置halted为true，参考halt方法：QuartzSchedulerThread#halt(boolean)
                     */
                    if (halted.get()) {
                        break;
                    }
                }

                // wait a bit, if reading from job store is consistently
                // failing (e.g. DB is down or restarting)..
                /**
                 * //等待一段时间，如果从作业存储读取是一致的
                 * //失败(例如DB关闭或重新启动)。
                 *                 //**********在前几次的循环中如果触发器的读取出现问题，
                 *                 //**********则可能是数据库重启一类的原因引发的故障
                 */
                if (acquiresFailed > 1) {
                    try {
                        /**
                         * computeDelayForRepeatedErrors 计算一个延迟重试等待时间，等待指定的时间后再尝试重新获取trigger
                         */
                        long delay = computeDelayForRepeatedErrors(qsRsrcs.getJobStore(), acquiresFailed);
                        Thread.sleep(delay);
                    } catch (Exception ignore) {
                    }
                }

                /**
                 * 确定池中当前可用的线程数。用于确定在返回false之前调用runInThread(Runnable)的次数。
                 * 这个方法的实现应该阻塞，直到至少有一个可用的线程。
                 * QuartzSchedulerThread.run()主要是在有可用线程的时候获取需要执行Trigger并出触发进行任务的调度！
                 */
                int availThreadCount = qsRsrcs.getThreadPool().blockForAvailableThreads();
                if(availThreadCount > 0) { // will always be true, due to semantics of blockForAvailableThreads...

                    List<OperableTrigger> triggers;

                    long now = System.currentTimeMillis();

                    clearSignaledSchedulingChange();
                    try {
                        // Part B：获取acquire状态的Trigger列表
                        /**查询待触发的Trigger
                         *  //2.2 从jobStore中获取下次要触发的触发器集合
                         *                     //idleWaitTime == 30L * 1000L; 当调度程序发现没有当前触发器要触发，它应该等待多长时间再检查...
                         *
                         *
                         *
                         *  Quartz未雨绸缪，从JobStore中获取当前时间后移一段时间内（idle time + time window）将要触发的Triggers，
                         *  以及在当前时间前移一段时间内（misfireThreshold）错过触发的Triggers(这里仅查询Trigger的主要信息)。
                         *  被查询到的Trggers状态变化：STATE_WAITING-->STATE_ACQUIRED。结果集是以触发时间升序、优先级降序的集合。
                         *
                         *
                         *    //**********acquireNextTriggers方法获取一批即将执行的触发器
                         *                 //**********参数idleWaitTime默认为30s,即当前时间后30s内即将被触发执行的触发器就会被取出
                         *
                         *
                         *                 //**********此外在acquireNextTriggers方法内部还有一个参数misfireThreshold
                         *                 //**********misfireThreshold是一个时间范围，用于判定触发器是否延时触发
                         *                 //**********misfireThreshold默认值是60秒，它相对的实际意义就是:
                         *                 //**********在当前时间的60秒之前本应执行但尚未执行的触发器不被认为是延迟触发,
                         *                 //**********这些触发器同样会被acquireNextTriggers发现
                         *                 //**********有时由于工程线程繁忙、程序重启等原因，原本预定要触发的任务可能延迟
                         *                 //**********我们可以在每个触发器中可以设置MISFIRE_INSTRUCTION,用于指定延迟触发后使用的策略
                         *                 //**********举例，对于CronTrigger,延迟处理的策略主要有3种：
                         *                 //**********（1）一个触发器无论延迟多少次，这些延迟都会被程序尽可能补回来
                         *                 //**********（2）检测到触发器延迟后，该触发器会在尽可能短的时间内被立即执行一次(只有一次)，然后恢复正常
                         *                 //**********（3）检测到延迟后不采取任何动作，触发器以现在时间为基准，根据自身的安排等待下一次被执行或停止，
                         *                 //**********     比如有些触发器只执行一次，一旦延迟后，该触发器也不会被触发
                         */
                        triggers = qsRsrcs.getJobStore().acquireNextTriggers(
                                now + idleWaitTime, Math.min(availThreadCount, qsRsrcs.getMaxBatchSize()), qsRsrcs.getBatchTimeWindow());
                        acquiresFailed = 0;
                        if (log.isDebugEnabled())
                            log.debug("batch acquisition of " + (triggers == null ? 0 : triggers.size()) + " triggers");
                    } catch (JobPersistenceException jpe) {
                        if (acquiresFailed == 0) {
                            qs.notifySchedulerListenersError(
                                "An error occurred while scanning for the next triggers to fire.",
                                jpe);
                        }
                        if (acquiresFailed < Integer.MAX_VALUE)
                            acquiresFailed++;
                        /**
                         * 获取trigger出现异常，但是不会退出整个while循环，这里使用的是continue
                         */
                        continue;
                    } catch (RuntimeException e) {
                        if (acquiresFailed == 0) {
                            getLog().error("quartzSchedulerThreadLoop: RuntimeException "
                                    +e.getMessage(), e);
                        }
                        if (acquiresFailed < Integer.MAX_VALUE)
                            acquiresFailed++;
                        /**
                         * 获取trigger出现异常，但是不会退出整个while循环，这里使用的是continue
                         */
                        continue;
                    }

                    if (triggers != null && !triggers.isEmpty()) {

                        now = System.currentTimeMillis();
                        long triggerTime = triggers.get(0).getNextFireTime().getTime();
                        long timeUntilTrigger = triggerTime - now;
                        /**
                         * 下次触发的时间举例当前时间大于2毫秒
                         */
                        while(timeUntilTrigger > 2) {
                            synchronized (sigLock) {
                                if (halted.get()) {
                                    break;
                                }
                                /**
                                 * 值得注意的是isCandidateNewTimeEarlierWithinReason(triggerTime, false)这个方法非常有趣，
                                 * 还记得上一篇文章中，我们提到当一个Scheduler添加Job时，我们会将改变该Scheduler的下次点火时间与signaled状态吗，
                                 * 这个方法，位于时间轮循环中，正是为了应对循环中又添加了新的触发器这种情况的，该方法如果检测到状态变化，
                                 * 会做当前点火时间与新增的下次点火时间的对比。同时还会判断我们的新时间是否足够早----早到需要重新创建轮询队列，
                                 * 根据是否进行持久化做的判断，如果做持久化就是70ms,不做则是7ms，emmm这个时间是怎么想出来的？
                                 */
                                if (!isCandidateNewTimeEarlierWithinReason(triggerTime, false)) {
                                    try {
                                        // we could have blocked a long while
                                        // on 'synchronize', so we must recompute
                                        /**
                                         * 列表不为空才需要进行下一步，此处是Quartz能够在指定时间执行任务的关键，首先计算出下次点火时间，
                                         * 然后取出列表中点火时间最近的那个任务，然后开始while循环，这一循环唯一的意义就是等待时间足够近，
                                         * 近到能够为这个Trigger点火。这里，实际上是时间轮算法的应用，
                                         * 通过该算法，允许我们通过一个调度器来监控多个调度任务，而不是任其自身进行调用。
                                         */
                                        now = System.currentTimeMillis();
                                        timeUntilTrigger = triggerTime - now;
                                        if(timeUntilTrigger >= 1)
                                            sigLock.wait(timeUntilTrigger);
                                    } catch (InterruptedException ignore) {
                                    }
                                }
                            }
                            if(releaseIfScheduleChangedSignificantly(triggers, triggerTime)) {
                                break;
                            }
                            now = System.currentTimeMillis();
                            timeUntilTrigger = triggerTime - now;
                        }

                        // this happens if releaseIfScheduleChangedSignificantly decided to release triggers
                        if(triggers.isEmpty())
                            continue;

                        // set triggers to 'executing'
                        List<TriggerFiredResult> bndles = new ArrayList<TriggerFiredResult>();

                        boolean goAhead = true;
                        synchronized(sigLock) {
                            goAhead = !halted.get();
                        }
                        if(goAhead) {
                            try {
                                List<TriggerFiredResult> res = qsRsrcs.getJobStore().triggersFired(triggers);
                                if(res != null)
                                    bndles = res;
                            } catch (SchedulerException se) {
                                qs.notifySchedulerListenersError(
                                        "An error occurred while firing triggers '"
                                                + triggers + "'", se);
                                //QTZ-179 : a problem occurred interacting with the triggers from the db
                                //we release them and loop again
                                for (int i = 0; i < triggers.size(); i++) {
                                    qsRsrcs.getJobStore().releaseAcquiredTrigger(triggers.get(i));
                                }
                                continue;
                            }

                        }

                        for (int i = 0; i < bndles.size(); i++) {
                            TriggerFiredResult result =  bndles.get(i);
                            TriggerFiredBundle bndle =  result.getTriggerFiredBundle();
                            Exception exception = result.getException();

                            if (exception instanceof RuntimeException) {
                                getLog().error("RuntimeException while firing trigger " + triggers.get(i), exception);
                                qsRsrcs.getJobStore().releaseAcquiredTrigger(triggers.get(i));
                                continue;
                            }

                            // it's possible to get 'null' if the triggers was paused,
                            // blocked, or other similar occurrences that prevent it being
                            // fired at this time...  or if the scheduler was shutdown (halted)
                            if (bndle == null) {
                                qsRsrcs.getJobStore().releaseAcquiredTrigger(triggers.get(i));
                                continue;
                            }

                            JobRunShell shell = null;
                            try {
                                shell = qsRsrcs.getJobRunShellFactory().createJobRunShell(bndle);
                                shell.initialize(qs);
                            } catch (SchedulerException se) {
                                qsRsrcs.getJobStore().triggeredJobComplete(triggers.get(i), bndle.getJobDetail(), CompletedExecutionInstruction.SET_ALL_JOB_TRIGGERS_ERROR);
                                continue;
                            }

                            /**
                             * 这里将shell对象放入线程中执行
                             * runInThread是java中ThreadPool中提供的方法：在下一个可用的线程中执行给定的Runnable。
                             * 这个接口的实现不应该抛出异常，除非有严重的问题(例如，严重的配置错误)。如果没有立即可用的线程，则返回false。
                             *
                             * runInThread 返回false表示没有立即可用的线程,在上面我们是有尝试获取可用线程数量的
                             */
                            if (qsRsrcs.getThreadPool().runInThread(shell) == false) {
                                // this case should never happen, as it is indicative of the
                                // scheduler being shutdown or a bug in the thread pool or
                                // a thread pool being used concurrently - which the docs
                                // say not to do...
                                /**
                                 * //这种情况不应该发生，因为它表明
                                 * //调度程序正在关闭或线程池中的错误
                                 * //当前正在使用的线程池
                                 * //说不做…
                                 */
                                getLog().error("ThreadPool.runInThread() return false!");
                                qsRsrcs.getJobStore().triggeredJobComplete(triggers.get(i), bndle.getJobDetail(), CompletedExecutionInstruction.SET_ALL_JOB_TRIGGERS_ERROR);
                            }

                        }

                        continue; // while (!halted)
                    }
                } else { // if(availThreadCount > 0) QuartzSchedulerThread.run()主要是在有可用线程的时候获取需要执行Trigger并出触发进行任务的调度！
                    // should never happen, if threadPool.blockForAvailableThreads() follows contract
                    continue; // while (!halted)
                }

                long now = System.currentTimeMillis();
                long waitTime = now + getRandomizedIdleWaitTime();
                long timeUntilContinue = waitTime - now;
                synchronized(sigLock) {
                    try {
                      if(!halted.get()) {
                        // QTZ-336 A job might have been completed in the mean time and we might have
                        // missed the scheduled changed signal by not waiting for the notify() yet
                        // Check that before waiting for too long in case this very job needs to be
                        // scheduled very soon
                        if (!isScheduleChanged()) {
                          sigLock.wait(timeUntilContinue);
                        }
                      }
                    } catch (InterruptedException ignore) {
                    }
                }

            } catch(RuntimeException re) {
                getLog().error("Runtime error occurred in main trigger firing loop.", re);
            }
        } // while (!halted)

        // drop references to scheduler stuff to aid garbage collection...
        qs = null;
        qsRsrcs = null;
    }

    private static final long MIN_DELAY = 20;
    private static final long MAX_DELAY = 600000;

    private static long computeDelayForRepeatedErrors(JobStore jobStore, int acquiresFailed) {
        long delay;
        try {
            delay = jobStore.getAcquireRetryDelay(acquiresFailed);
        } catch (Exception ignored) {
            // we're trying to be useful in case of error states, not cause
            // additional errors..
            delay = 100;
        }


        // sanity check per getAcquireRetryDelay specification
        if (delay < MIN_DELAY)
            delay = MIN_DELAY;
        if (delay > MAX_DELAY)
            delay = MAX_DELAY;

        return delay;
    }

    private boolean releaseIfScheduleChangedSignificantly(
            List<OperableTrigger> triggers, long triggerTime) {
        if (isCandidateNewTimeEarlierWithinReason(triggerTime, true)) {
            // above call does a clearSignaledSchedulingChange()
            for (OperableTrigger trigger : triggers) {
                qsRsrcs.getJobStore().releaseAcquiredTrigger(trigger);
            }
            triggers.clear();
            return true;
        }
        return false;
    }

    private boolean isCandidateNewTimeEarlierWithinReason(long oldTime, boolean clearSignal) {

        // So here's the deal: We know due to being signaled that 'the schedule'
        // has changed.  We may know (if getSignaledNextFireTime() != 0) the
        // new earliest fire time.  We may not (in which case we will assume
        // that the new time is earlier than the trigger we have acquired).
        // In either case, we only want to abandon our acquired trigger and
        // go looking for a new one if "it's worth it".  It's only worth it if
        // the time cost incurred to abandon the trigger and acquire a new one
        // is less than the time until the currently acquired trigger will fire,
        // otherwise we're just "thrashing" the job store (e.g. database).
        //
        // So the question becomes when is it "worth it"?  This will depend on
        // the job store implementation (and of course the particular database
        // or whatever behind it).  Ideally we would depend on the job store
        // implementation to tell us the amount of time in which it "thinks"
        // it can abandon the acquired trigger and acquire a new one.  However
        // we have no current facility for having it tell us that, so we make
        // a somewhat educated but arbitrary guess ;-).
        /**
         * //这是交易:我们知道，因为被告知' schedule'
         * / /已经发生了改变。我们可能知道(如果getSignaledNextFireTime() != 0)
         * //新的最早的火灾时间。我们可能不会(在这种情况下我们将假定)
         * //新的时间比我们已经获得的触发器早)。
         * //在这两种情况下，我们只想放弃我们获得的触发器和
         * //如果“值得”，就去找一个新的吧。只有这样才值得
         * //放弃触发器并获取新触发器所需的时间
         * //小于当前获取的触发器触发的时间，
         * //否则我们只是在“抖动”作业存储(例如数据库)。
         * //
         * //所以问题就变成了什么时候它“值得”?这取决于
         * //作业存储实现(当然还有特定的数据库
         * //或它后面的任何东西)。理想情况下，我们将依赖于作业存储
         * //实现告诉我们它“思考”的时间
         * //它可以放弃已获取的触发器并获取一个新的触发器。然而
         * //我们目前没有让它告诉我们的工具，所以我们做
         * //一个有教养但随意的猜测;-)。
         */

        synchronized(sigLock) {

            if (!isScheduleChanged())
                return false;

            boolean earlier = false;

            if(getSignaledNextFireTime() == 0)
                earlier = true;
            else if(getSignaledNextFireTime() < oldTime )
                earlier = true;

            if(earlier) {
                // so the new time is considered earlier, but is it enough earlier?
                long diff = oldTime - System.currentTimeMillis();
                if(diff < (qsRsrcs.getJobStore().supportsPersistence() ? 70L : 7L))
                    earlier = false;
            }

            if(clearSignal) {
                clearSignaledSchedulingChange();
            }

            return earlier;
        }
    }

    public Logger getLog() {
        return log;
    }

} // end of QuartzSchedulerThread
