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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.quartz.Calendar;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.JobPersistenceException;
import org.quartz.ObjectAlreadyExistsException;
import org.quartz.Trigger;
import org.quartz.TriggerKey;
import org.quartz.Trigger.CompletedExecutionInstruction;
import org.quartz.Trigger.TriggerState;
import org.quartz.Trigger.TriggerTimeComparator;
import org.quartz.impl.JobDetailImpl;
import org.quartz.impl.matchers.GroupMatcher;
import org.quartz.impl.matchers.StringMatcher;
import org.quartz.spi.ClassLoadHelper;
import org.quartz.spi.JobStore;
import org.quartz.spi.OperableTrigger;
import org.quartz.spi.SchedulerSignaler;
import org.quartz.spi.TriggerFiredBundle;
import org.quartz.spi.TriggerFiredResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.quartz.impl.matchers.EverythingMatcher.allTriggers;

/**
 * <p>
 * This class implements a <code>{@link org.quartz.spi.JobStore}</code> that
 * utilizes RAM as its storage device.
 * </p>
 * 
 * <p>
 * As you should know, the ramification of this is that access is extrememly
 * fast, but the data is completely volatile - therefore this <code>JobStore</code>
 * should not be used if true persistence between program shutdowns is
 * required.
 * </p>
 * 这个类实现了一个利用RAM作为其存储设备的JobStore。
 * 您应该知道，这样做的后果是访问速度非常快，但数据是完全不稳定的——因此，如果需要在程序关闭之间实现真正的持久性，就不应该使用这个JobStore。
 * 
 * @author James House
 * @author Sharada Jambula
 * @author Eric Mueller
 */
public class RAMJobStore implements JobStore {

    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * 
     * Data members.
     * 
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */

    protected HashMap<JobKey, JobWrapper> jobsByKey = new HashMap<JobKey, JobWrapper>(1000);

    protected HashMap<TriggerKey, TriggerWrapper> triggersByKey = new HashMap<TriggerKey, TriggerWrapper>(1000);

    protected HashMap<String, HashMap<JobKey, JobWrapper>> jobsByGroup = new HashMap<String, HashMap<JobKey, JobWrapper>>(25);

    protected HashMap<String, HashMap<TriggerKey, TriggerWrapper>> triggersByGroup = new HashMap<String, HashMap<TriggerKey, TriggerWrapper>>(25);

    protected TreeSet<TriggerWrapper> timeTriggers = new TreeSet<TriggerWrapper>(new TriggerWrapperComparator());

    protected HashMap<String, Calendar> calendarsByName = new HashMap<String, Calendar>(25);

    protected Map<JobKey, List<TriggerWrapper>> triggersByJob = new HashMap<JobKey, List<TriggerWrapper>>(1000);

    protected final Object lock = new Object();

    protected HashSet<String> pausedTriggerGroups = new HashSet<String>();

    protected HashSet<String> pausedJobGroups = new HashSet<String>();

    protected HashSet<JobKey> blockedJobs = new HashSet<JobKey>();
    
    protected long misfireThreshold = 5000l;

    protected SchedulerSignaler signaler;

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
     * Create a new <code>RAMJobStore</code>.
     * </p>
     */
    public RAMJobStore() {
    }

    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * 
     * Interface.
     * 
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */

    protected Logger getLog() {
        return log;
    }

    /**
     * <p>
     * Called by the QuartzScheduler before the <code>JobStore</code> is
     * used, in order to give the it a chance to initialize.
     * </p>
     */
    public void initialize(ClassLoadHelper loadHelper, SchedulerSignaler schedSignaler) {

        this.signaler = schedSignaler;

        getLog().info("RAMJobStore initialized.");
    }

    public void schedulerStarted() {
        // nothing to do
    }

    public void schedulerPaused() {
        // nothing to do
    }
    
    public void schedulerResumed() {
        // nothing to do
    }
    
    public long getMisfireThreshold() {
        return misfireThreshold;
    }

    /**
     * The number of milliseconds by which a trigger must have missed its
     * next-fire-time, in order for it to be considered "misfired" and thus
     * have its misfire instruction applied.
     * 一个触发器错过下一次发射时间的毫秒数，以使它被认为是“未发射”，
     * 从而应用它的未发射指令。
     * 
     * @param misfireThreshold the new misfire threshold
     */
    @SuppressWarnings("UnusedDeclaration")
    public void setMisfireThreshold(long misfireThreshold) {
        if (misfireThreshold < 1) {
            throw new IllegalArgumentException("Misfire threshold must be larger than 0");
        }
        this.misfireThreshold = misfireThreshold;
    }

    /**
     * <p>
     * Called by the QuartzScheduler to inform the <code>JobStore</code> that
     * it should free up all of it's resources because the scheduler is
     * shutting down.
     * </p>
     */
    public void shutdown() {
    }

    public boolean supportsPersistence() {
        return false;
    }

    /**
     * Clear (delete!) all scheduling data - all {@link Job}s, {@link Trigger}s
     * {@link Calendar}s.
     * 
     * @throws JobPersistenceException
     */
    public void clearAllSchedulingData() throws JobPersistenceException {

        synchronized (lock) {
            // unschedule jobs (delete triggers)
            List<String> lst = getTriggerGroupNames();
            for (String group: lst) {
                Set<TriggerKey> keys = getTriggerKeys(GroupMatcher.triggerGroupEquals(group));
                for (TriggerKey key: keys) {
                    removeTrigger(key);
                }
            }
            // delete jobs
            lst = getJobGroupNames();
            for (String group: lst) {
                Set<JobKey> keys = getJobKeys(GroupMatcher.jobGroupEquals(group));
                for (JobKey key: keys) {
                    removeJob(key);
                }
            }
            // delete calendars
            lst = getCalendarNames();
            for(String name: lst) {
                removeCalendar(name);
            }
        }
    }
    
    /**
     * <p>
     * Store the given <code>{@link org.quartz.JobDetail}</code> and <code>{@link org.quartz.Trigger}</code>.
     * </p>
     * 
     * @param newJob
     *          The <code>JobDetail</code> to be stored.
     * @param newTrigger
     *          The <code>Trigger</code> to be stored.
     * @throws ObjectAlreadyExistsException
     *           if a <code>Job</code> with the same name/group already
     *           exists.
     */
    public void storeJobAndTrigger(JobDetail newJob,
            OperableTrigger newTrigger) throws JobPersistenceException {
        storeJob(newJob, false);
        storeTrigger(newTrigger, false);
    }

    /**
     * <p>
     * Store the given <code>{@link org.quartz.Job}</code>.
     * </p>
     * 
     * @param newJob
     *          The <code>Job</code> to be stored.
     * @param replaceExisting
     *          If <code>true</code>, any <code>Job</code> existing in the
     *          <code>JobStore</code> with the same name & group should be
     *          over-written.
     * @throws ObjectAlreadyExistsException
     *           if a <code>Job</code> with the same name/group already
     *           exists, and replaceExisting is set to false.
     */
    public void storeJob(JobDetail newJob,
            boolean replaceExisting) throws ObjectAlreadyExistsException {
        JobWrapper jw = new JobWrapper((JobDetail)newJob.clone());

        boolean repl = false;

        synchronized (lock) {
            if (jobsByKey.get(jw.key) != null) {
                if (!replaceExisting) {
                    throw new ObjectAlreadyExistsException(newJob);
                }
                repl = true;
            }

            if (!repl) {
                // get job group
                HashMap<JobKey, JobWrapper> grpMap = jobsByGroup.get(newJob.getKey().getGroup());
                if (grpMap == null) {
                    grpMap = new HashMap<JobKey, JobWrapper>(100);
                    jobsByGroup.put(newJob.getKey().getGroup(), grpMap);
                }
                // add to jobs by group
                grpMap.put(newJob.getKey(), jw);
                // add to jobs by FQN map
                jobsByKey.put(jw.key, jw);
            } else {
                // update job detail
                JobWrapper orig = jobsByKey.get(jw.key);
                orig.jobDetail = jw.jobDetail; // already cloned
            }
        }
    }

    /**
     * <p>
     * Remove (delete) the <code>{@link org.quartz.Job}</code> with the given
     * name, and any <code>{@link org.quartz.Trigger}</code> s that reference
     * it.
     * </p>
     *
     * @return <code>true</code> if a <code>Job</code> with the given name &
     *         group was found and removed from the store.
     */
    public boolean removeJob(JobKey jobKey) {

        boolean found = false;

        synchronized (lock) {
            List<OperableTrigger> triggersOfJob = getTriggersForJob(jobKey);
            for (OperableTrigger trig: triggersOfJob) {
                this.removeTrigger(trig.getKey());
                found = true;
            }
            
            found = (jobsByKey.remove(jobKey) != null) | found;
            if (found) {

                HashMap<JobKey, JobWrapper> grpMap = jobsByGroup.get(jobKey.getGroup());
                if (grpMap != null) {
                    grpMap.remove(jobKey);
                    if (grpMap.size() == 0) {
                        jobsByGroup.remove(jobKey.getGroup());
                    }
                }
            }
        }

        return found;
    }

    public boolean removeJobs(List<JobKey> jobKeys)
            throws JobPersistenceException {
        boolean allFound = true;

        synchronized (lock) {
            for(JobKey key: jobKeys)
                allFound = removeJob(key) && allFound;
        }

        return allFound;
    }

    public boolean removeTriggers(List<TriggerKey> triggerKeys)
            throws JobPersistenceException {
        boolean allFound = true;

        synchronized (lock) {
            for(TriggerKey key: triggerKeys)
                allFound = removeTrigger(key) && allFound;
        }

        return allFound;
    }

    public void storeJobsAndTriggers(
            Map<JobDetail, Set<? extends Trigger>> triggersAndJobs, boolean replace)
            throws JobPersistenceException {

        synchronized (lock) {
            // make sure there are no collisions...
            if(!replace) {
                for(Entry<JobDetail, Set<? extends Trigger>> e: triggersAndJobs.entrySet()) {
                    if(checkExists(e.getKey().getKey()))
                        throw new ObjectAlreadyExistsException(e.getKey());
                    for(Trigger trigger: e.getValue()) {
                        if(checkExists(trigger.getKey()))
                            throw new ObjectAlreadyExistsException(trigger);
                    }
                }
            }
            // do bulk add...
            for(Entry<JobDetail, Set<? extends Trigger>> e: triggersAndJobs.entrySet()) {
                storeJob(e.getKey(), true);
                for(Trigger trigger: e.getValue()) {
                    storeTrigger((OperableTrigger) trigger, true);
                }
            }
        }
        
    }

    /**
     * <p>
     * Store the given <code>{@link org.quartz.Trigger}</code>.
     * </p>
     *
     * @param newTrigger
     *          The <code>Trigger</code> to be stored.
     * @param replaceExisting
     *          If <code>true</code>, any <code>Trigger</code> existing in
     *          the <code>JobStore</code> with the same name & group should
     *          be over-written.
     * @throws ObjectAlreadyExistsException
     *           if a <code>Trigger</code> with the same name/group already
     *           exists, and replaceExisting is set to false.
     *
     * @see #pauseTriggers(org.quartz.impl.matchers.GroupMatcher)
     */
    public void storeTrigger(OperableTrigger newTrigger,
            boolean replaceExisting) throws JobPersistenceException {

        /**
         * 参数是OperableTrigger 比如SimpleTriggerImpl,
         * Trigger中包含了triggerKey和JObKey
         * TriggerKey getKey();  TriggerKey有两个参数信息：TriggerKey(String name, String group)
         * JobKey getJobKey();JobKey也有两个信息： JobKey(String name, String group)
         */
        TriggerWrapper tw = new TriggerWrapper((OperableTrigger)newTrigger.clone());

        synchronized (lock) {
            if (triggersByKey.get(tw.key) != null) {
                if (!replaceExisting) {
                    throw new ObjectAlreadyExistsException(newTrigger);
                }
    
                removeTrigger(newTrigger.getKey(), false);
            }
    
            if (retrieveJob(newTrigger.getJobKey()) == null) {
                throw new JobPersistenceException("The job ("
                        + newTrigger.getJobKey()
                        + ") referenced by the trigger does not exist.");
            }

            // add to triggers by job
            /**
             * 获取Job的的trigger List
             */
            List<TriggerWrapper> jobList = triggersByJob.get(tw.jobKey);
            if(jobList == null) {
                jobList = new ArrayList<TriggerWrapper>(1);
                triggersByJob.put(tw.jobKey, jobList);
            }
            jobList.add(tw);
            
            // add to triggers by group
            HashMap<TriggerKey, TriggerWrapper> grpMap = triggersByGroup.get(newTrigger.getKey().getGroup());
            if (grpMap == null) {
                grpMap = new HashMap<TriggerKey, TriggerWrapper>(100);
                triggersByGroup.put(newTrigger.getKey().getGroup(), grpMap);
            }
            grpMap.put(newTrigger.getKey(), tw);
            // add to triggers by FQN map
            triggersByKey.put(tw.key, tw);

            /**
             * 判断 trigger的TriggerKey是否在pausedTriggerGroups， jobkey是否在pausedJobGroups
             * jobKey是否在blockedJobs中
             *
             * 如果都不在，此时将trigger添加到timeTriggers中。
             *
             * 注意点： 我们storeTrigger的时候 会将这个trigger放置到很多地方，比如放置到 triggersByKey，triggersByGroup，triggersByJob等地方
             *
             * 但是在QuartzSchedulerThread的run方法中，会通过acquireNextTriggers方法获取 需要执行的trigger，这个trigger的获取是遍历timeTriggers中的tigger
             * 因此也就是说假设Trigger不在timeTriggers中，则不会被触发。所以关键点是storeTrigger的时候将Trigger放置到timeTriggers中。
             * 但是我们发现将Trigger放置到timeTriggers中是有前提条件的：triggerKey不在pausedTriggerGroups、pausedJobGroups、blockedJobs中
             *
             */
            if (pausedTriggerGroups.contains(newTrigger.getKey().getGroup())
                    || pausedJobGroups.contains(newTrigger.getJobKey().getGroup())) {
                tw.state = TriggerWrapper.STATE_PAUSED;
                if (blockedJobs.contains(tw.jobKey)) {
                    tw.state = TriggerWrapper.STATE_PAUSED_BLOCKED;
                }
            } else if (blockedJobs.contains(tw.jobKey)) {
                tw.state = TriggerWrapper.STATE_BLOCKED;
            } else {
                timeTriggers.add(tw);
            }
        }
    }

    /**
     * <p>
     * Remove (delete) the <code>{@link org.quartz.Trigger}</code> with the
     * given name.
     * </p>
     *
     * @return <code>true</code> if a <code>Trigger</code> with the given
     *         name & group was found and removed from the store.
     */
    public boolean removeTrigger(TriggerKey triggerKey) {
        return removeTrigger(triggerKey, true);
    }
    
    private boolean removeTrigger(TriggerKey key, boolean removeOrphanedJob) {

        boolean found;

        synchronized (lock) {
            // remove from triggers by FQN map
            TriggerWrapper tw = triggersByKey.remove(key);
            found = tw != null;
            if (found) {
                // remove from triggers by group
                HashMap<TriggerKey, TriggerWrapper> grpMap = triggersByGroup.get(key.getGroup());
                if (grpMap != null) {
                    grpMap.remove(key);
                    if (grpMap.size() == 0) {
                        triggersByGroup.remove(key.getGroup());
                    }
                }
                //remove from triggers by job
                List<TriggerWrapper> jobList = triggersByJob.get(tw.jobKey);
                if(jobList != null) {
                    jobList.remove(tw);
                    if(jobList.isEmpty()) {
                        triggersByJob.remove(tw.jobKey);
                    }
                }
               
                timeTriggers.remove(tw);

                /**
                 * removeOrphanedJob是否连带删除Job
                 *
                 */
                if (removeOrphanedJob) {
                    JobWrapper jw = jobsByKey.get(tw.jobKey);
                    List<OperableTrigger> trigs = getTriggersForJob(tw.jobKey);
                    if ((trigs == null || trigs.size() == 0) && !jw.jobDetail.isDurable()) {
                        if (removeJob(jw.key)) {
                            signaler.notifySchedulerListenersJobDeleted(jw.key);
                        }
                    }
                }
            }
        }

        return found;
    }


    /**
     * @see org.quartz.spi.JobStore#replaceTrigger(TriggerKey triggerKey, OperableTrigger newTrigger)
     */
    public boolean replaceTrigger(TriggerKey triggerKey, OperableTrigger newTrigger) throws JobPersistenceException {

        boolean found;

        synchronized (lock) {
            // remove from triggers by FQN map
            TriggerWrapper tw = triggersByKey.remove(triggerKey);
            found = (tw != null);

            if (found) {

                if (!tw.getTrigger().getJobKey().equals(newTrigger.getJobKey())) {
                    throw new JobPersistenceException("New trigger is not related to the same job as the old trigger.");
                }

                // remove from triggers by group
                HashMap<TriggerKey, TriggerWrapper> grpMap = triggersByGroup.get(triggerKey.getGroup());
                if (grpMap != null) {
                    grpMap.remove(triggerKey);
                    if (grpMap.size() == 0) {
                        triggersByGroup.remove(triggerKey.getGroup());
                    }
                }
                
                //remove from triggers by job
                List<TriggerWrapper> jobList = triggersByJob.get(tw.jobKey);
                if(jobList != null) {
                    jobList.remove(tw);
                    if(jobList.isEmpty()) {
                        triggersByJob.remove(tw.jobKey);
                    }
                }
                
                timeTriggers.remove(tw);

                try {
                    storeTrigger(newTrigger, false);
                } catch(JobPersistenceException jpe) {
                    storeTrigger(tw.getTrigger(), false); // put previous trigger back...
                    throw jpe;
                }
            }
        }

        return found;
    }

    /**
     * <p>
     * Retrieve the <code>{@link org.quartz.JobDetail}</code> for the given
     * <code>{@link org.quartz.Job}</code>.
     * </p>
     *
     * @return The desired <code>Job</code>, or null if there is no match.
     * 检索给定Job的JobDetail。
     */
    public JobDetail retrieveJob(JobKey jobKey) {
        synchronized(lock) {
            JobWrapper jw = jobsByKey.get(jobKey);
            return (jw != null) ? (JobDetail)jw.jobDetail.clone() : null;
        }
    }

    /**
     * <p>
     * Retrieve the given <code>{@link org.quartz.Trigger}</code>.
     * </p>
     *
     * @return The desired <code>Trigger</code>, or null if there is no
     *         match.
     */
    public OperableTrigger retrieveTrigger(TriggerKey triggerKey) {
        synchronized(lock) {
            TriggerWrapper tw = triggersByKey.get(triggerKey);
    
            return (tw != null) ? (OperableTrigger)tw.getTrigger().clone() : null;
        }
    }
    
    /**
     * Determine whether a {@link Job} with the given identifier already 
     * exists within the scheduler.
     * 
     * @param jobKey the identifier to check for
     * @return true if a Job exists with the given identifier
     * @throws JobPersistenceException
     */
    public boolean checkExists(JobKey jobKey) throws JobPersistenceException {
        synchronized(lock) {
            JobWrapper jw = jobsByKey.get(jobKey);
            return (jw != null);
        }
    }
    
    /**
     * Determine whether a {@link Trigger} with the given identifier already 
     * exists within the scheduler.
     * 
     * @param triggerKey the identifier to check for
     * @return true if a Trigger exists with the given identifier
     * @throws JobPersistenceException
     */
    public boolean checkExists(TriggerKey triggerKey) throws JobPersistenceException {
        synchronized(lock) {
            TriggerWrapper tw = triggersByKey.get(triggerKey);
    
            return (tw != null);
        }
    }
 
    /**
     * <p>
     * Get the current state of the identified <code>{@link Trigger}</code>.
     * </p>
     *
     * @see TriggerState#NORMAL
     * @see TriggerState#PAUSED
     * @see TriggerState#COMPLETE
     * @see TriggerState#ERROR
     * @see TriggerState#BLOCKED
     * @see TriggerState#NONE
     */
    public TriggerState getTriggerState(TriggerKey triggerKey) throws JobPersistenceException {
        synchronized(lock) {
            TriggerWrapper tw = triggersByKey.get(triggerKey);
            
            if (tw == null) {
                return TriggerState.NONE;
            }
    
            if (tw.state == TriggerWrapper.STATE_COMPLETE) {
                return TriggerState.COMPLETE;
            }
    
            if (tw.state == TriggerWrapper.STATE_PAUSED) {
                return TriggerState.PAUSED;
            }
    
            if (tw.state == TriggerWrapper.STATE_PAUSED_BLOCKED) {
                return TriggerState.PAUSED;
            }
    
            if (tw.state == TriggerWrapper.STATE_BLOCKED) {
                return TriggerState.BLOCKED;
            }
    
            if (tw.state == TriggerWrapper.STATE_ERROR) {
                return TriggerState.ERROR;
            }
    
            return TriggerState.NORMAL;
        }
    }

    /**
     * Reset the current state of the identified <code>{@link Trigger}</code>
     * from {@link TriggerState#ERROR} to {@link TriggerState#NORMAL} or
     * {@link TriggerState#PAUSED} as appropriate.
     *
     * <p>Only affects triggers that are in ERROR state - if identified trigger is not
     * in that state then the result is a no-op.</p>
     *
     * <p>The result will be the trigger returning to the normal, waiting to
     * be fired state, unless the trigger's group has been paused, in which
     * case it will go into the PAUSED state.</p>
     *
     * 将已识别的触发器的当前状态从Trigger. triggerstate . error重置为Trigger. triggerstate . normal或Trigger. triggerstate . paused。
     * 只影响处于ERROR状态的触发器——如果标识的触发器不在该状态，则结果为no-op。
     * 结果将是触发器返回到正常状态，等待被触发，除非触发器的组已经暂停，在这种情况下它将进入暂停状态。
     */
    public void resetTriggerFromErrorState(final TriggerKey triggerKey) throws JobPersistenceException {

        synchronized (lock) {

            TriggerWrapper tw = triggersByKey.get(triggerKey);
            // does the trigger exist?
            if (tw == null || tw.trigger == null) {
                return;
            }
            // is the trigger in error state?
            /**
             * 如果trigger的状态不等于error则返回
             */
            if (tw.state != TriggerWrapper.STATE_ERROR) {
                return;
            }

            /**
             * 如果在paused中则设置为paused，否则将其设置为waiting，并将其添加到timeTriggers中。
             * 从这里我们发现 timeTriggers中的trigger应该都是 waiting状态的
             */
            if(pausedTriggerGroups.contains(triggerKey.getGroup())) {
                tw.state = TriggerWrapper.STATE_PAUSED;
            }
            else {
                tw.state = TriggerWrapper.STATE_WAITING;
                timeTriggers.add(tw);
            }
        }
    }

    /**
     * <p>
     * Store the given <code>{@link org.quartz.Calendar}</code>.
     * </p>
     *
     * @param calendar
     *          The <code>Calendar</code> to be stored.
     * @param replaceExisting
     *          If <code>true</code>, any <code>Calendar</code> existing
     *          in the <code>JobStore</code> with the same name & group
     *          should be over-written.
     * @param updateTriggers
     *          If <code>true</code>, any <code>Trigger</code>s existing
     *          in the <code>JobStore</code> that reference an existing
     *          Calendar with the same name with have their next fire time
     *          re-computed with the new <code>Calendar</code>.
     * @throws ObjectAlreadyExistsException
     *           if a <code>Calendar</code> with the same name already
     *           exists, and replaceExisting is set to false.
     */
    public void storeCalendar(String name,
            Calendar calendar, boolean replaceExisting, boolean updateTriggers)
        throws ObjectAlreadyExistsException {

        calendar = (Calendar) calendar.clone();
        
        synchronized (lock) {
    
            Object obj = calendarsByName.get(name);
    
            if (obj != null && !replaceExisting) {
                throw new ObjectAlreadyExistsException(
                    "Calendar with name '" + name + "' already exists.");
            } else if (obj != null) {
                calendarsByName.remove(name);
            }
    
            calendarsByName.put(name, calendar);
    
            if(obj != null && updateTriggers) {
                for (TriggerWrapper tw : getTriggerWrappersForCalendar(name)) {
                    OperableTrigger trig = tw.getTrigger();
                    boolean removed = timeTriggers.remove(tw);

                    trig.updateWithNewCalendar(calendar, getMisfireThreshold());

                    if (removed) {
                        timeTriggers.add(tw);
                    }
                }
            }
        }
    }

    /**
     * <p>
     * Remove (delete) the <code>{@link org.quartz.Calendar}</code> with the
     * given name.
     * </p>
     *
     * <p>
     * If removal of the <code>Calendar</code> would result in
     * <code>Trigger</code>s pointing to non-existent calendars, then a
     * <code>JobPersistenceException</code> will be thrown.</p>
     *       *
     * @param calName The name of the <code>Calendar</code> to be removed.
     * @return <code>true</code> if a <code>Calendar</code> with the given name
     * was found and removed from the store.
     */
    public boolean removeCalendar(String calName)
        throws JobPersistenceException {
        int numRefs = 0;

        synchronized (lock) {
            for (TriggerWrapper trigger : triggersByKey.values()) {
                OperableTrigger trigg = trigger.trigger;
                if (trigg.getCalendarName() != null
                        && trigg.getCalendarName().equals(calName)) {
                    numRefs++;
                }
            }
        }

        if (numRefs > 0) {
            throw new JobPersistenceException(
                    "Calender cannot be removed if it referenced by a Trigger!");
        }

        return (calendarsByName.remove(calName) != null);
    }

    /**
     * <p>
     * Retrieve the given <code>{@link org.quartz.Trigger}</code>.
     * </p>
     *检索给定的触发器。
     * @param calName
     *          The name of the <code>Calendar</code> to be retrieved.
     * @return The desired <code>Calendar</code>, or null if there is no
     *         match.
     */
    public Calendar retrieveCalendar(String calName) {
        synchronized (lock) {
            Calendar cal = calendarsByName.get(calName);
            if(cal != null)
                return (Calendar) cal.clone();
            return null;
        }
    }

    /**
     * <p>
     * Get the number of <code>{@link org.quartz.JobDetail}</code> s that are
     * stored in the <code>JobsStore</code>.
     * </p>
     */
    public int getNumberOfJobs() {
        synchronized (lock) {
            return jobsByKey.size();
        }
    }

    /**
     * <p>
     * Get the number of <code>{@link org.quartz.Trigger}</code> s that are
     * stored in the <code>JobsStore</code>.
     * </p>
     */
    public int getNumberOfTriggers() {
        synchronized (lock) {
            return triggersByKey.size();
        }
    }

    /**
     * <p>
     * Get the number of <code>{@link org.quartz.Calendar}</code> s that are
     * stored in the <code>JobsStore</code>.
     * </p>
     */
    public int getNumberOfCalendars() {
        synchronized (lock) {
            return calendarsByName.size();
        }
    }

    /**
     * <p>
     * Get the names of all of the <code>{@link org.quartz.Job}</code> s that
     * match the given groupMatcher.
     * </p>
     */
    public Set<JobKey> getJobKeys(GroupMatcher<JobKey> matcher) {
        Set<JobKey> outList = null;
        synchronized (lock) {

            StringMatcher.StringOperatorName operator = matcher.getCompareWithOperator();
            String compareToValue = matcher.getCompareToValue();

            switch(operator) {
                case EQUALS:
                    HashMap<JobKey, JobWrapper> grpMap = jobsByGroup.get(compareToValue);
                    if (grpMap != null) {
                        outList = new HashSet<JobKey>();

                        for (JobWrapper jw : grpMap.values()) {

                            if (jw != null) {
                                outList.add(jw.jobDetail.getKey());
                            }
                        }
                    }
                    break;

                default:
                    for (Map.Entry<String, HashMap<JobKey, JobWrapper>> entry : jobsByGroup.entrySet()) {
                        if(operator.evaluate(entry.getKey(), compareToValue) && entry.getValue() != null) {
                            if(outList == null) {
                                outList = new HashSet<JobKey>();
                            }
                            for (JobWrapper jobWrapper : entry.getValue().values()) {
                                if(jobWrapper != null) {
                                    outList.add(jobWrapper.jobDetail.getKey());
                                }
                            }
                        }
                    }
            }
        }

        return outList == null ? java.util.Collections.<JobKey>emptySet() : outList;
    }

    /**
     * <p>
     * Get the names of all of the <code>{@link org.quartz.Calendar}</code> s
     * in the <code>JobStore</code>.
     * </p>
     *
     * <p>
     * If there are no Calendars in the given group name, the result should be
     * a zero-length array (not <code>null</code>).
     * </p>
     */
    public List<String> getCalendarNames() {
        synchronized(lock) {
            return new LinkedList<String>(calendarsByName.keySet());
        }
    }

    /**
     * <p>
     * Get the names of all of the <code>{@link org.quartz.Trigger}</code> s
     * that match the given groupMatcher.
     * </p>
     */
    public Set<TriggerKey> getTriggerKeys(GroupMatcher<TriggerKey> matcher) {
        Set<TriggerKey> outList = null;
        synchronized (lock) {

            StringMatcher.StringOperatorName operator = matcher.getCompareWithOperator();
            String compareToValue = matcher.getCompareToValue();

            switch(operator) {
                case EQUALS:
                    HashMap<TriggerKey, TriggerWrapper> grpMap = triggersByGroup.get(compareToValue);
                    if (grpMap != null) {
                        outList = new HashSet<TriggerKey>();

                        for (TriggerWrapper tw : grpMap.values()) {

                            if (tw != null) {
                                outList.add(tw.trigger.getKey());
                            }
                        }
                    }
                    break;

                default:
                    for (Map.Entry<String, HashMap<TriggerKey, TriggerWrapper>> entry : triggersByGroup.entrySet()) {
                        if(operator.evaluate(entry.getKey(), compareToValue) && entry.getValue() != null) {
                            if(outList == null) {
                                outList = new HashSet<TriggerKey>();
                            }
                            for (TriggerWrapper triggerWrapper : entry.getValue().values()) {
                                if(triggerWrapper != null) {
                                    outList.add(triggerWrapper.trigger.getKey());
                                }
                            }
                        }
                    }
            }
        }

        return outList == null ? Collections.<TriggerKey>emptySet() : outList;
    }

    /**
     * <p>
     * Get the names of all of the <code>{@link org.quartz.Job}</code>
     * groups.
     * </p>
     */
    public List<String> getJobGroupNames() {
        List<String> outList;

        synchronized (lock) {
            outList = new LinkedList<String>(jobsByGroup.keySet());
        }

        return outList;
    }

    /**
     * <p>
     * Get the names of all of the <code>{@link org.quartz.Trigger}</code>
     * groups.
     * </p>
     */
    public List<String> getTriggerGroupNames() {
        LinkedList<String> outList;

        synchronized (lock) {
            outList = new LinkedList<String>(triggersByGroup.keySet());
        }

        return outList;
    }

    /**
     * <p>
     * Get all of the Triggers that are associated to the given Job.
     * </p>
     *
     * <p>
     * If there are no matches, a zero-length array should be returned.
     * </p>
     */
    public List<OperableTrigger> getTriggersForJob(JobKey jobKey) {
        ArrayList<OperableTrigger> trigList = new ArrayList<OperableTrigger>();

        synchronized (lock) {
            List<TriggerWrapper> jobList = triggersByJob.get(jobKey);
            if(jobList != null) {
                for(TriggerWrapper tw : jobList) {
                    trigList.add((OperableTrigger) tw.trigger.clone());
                }
            }
        }

        return trigList;
    }

    protected ArrayList<TriggerWrapper> getTriggerWrappersForJob(JobKey jobKey) {
        ArrayList<TriggerWrapper> trigList = new ArrayList<TriggerWrapper>();

        synchronized (lock) {
            List<TriggerWrapper> jobList = triggersByJob.get(jobKey);
            if(jobList != null) {
                for(TriggerWrapper trigger : jobList) {
                    trigList.add(trigger);
                }
            }
        }

        return trigList;
    }

    protected ArrayList<TriggerWrapper> getTriggerWrappersForCalendar(String calName) {
        ArrayList<TriggerWrapper> trigList = new ArrayList<TriggerWrapper>();

        synchronized (lock) {
            for (TriggerWrapper tw : triggersByKey.values()) {
                String tcalName = tw.getTrigger().getCalendarName();
                if (tcalName != null && tcalName.equals(calName)) {
                    trigList.add(tw);
                }
            }
        }

        return trigList;
    }

    /**
     * <p>
     * Pause the <code>{@link Trigger}</code> with the given name.
     * </p>
     *使用给定的名称暂停触发器。
     */
    public void pauseTrigger(TriggerKey triggerKey) {

        synchronized (lock) {
            TriggerWrapper tw = triggersByKey.get(triggerKey);
    
            // does the trigger exist?
            if (tw == null || tw.trigger == null) {
                return;
            }
    
            // if the trigger is "complete" pausing it does not make sense...
            /**
             * 如果触发器是“完成的”暂停，它没有意义…
             * 如果trigger是完成状态则返回
             */
            if (tw.state == TriggerWrapper.STATE_COMPLETE) {
                return;
            }

            /**
             * 如果触发器是block 则设置为paused_block。 否则设置为paused。
             *
             */
            if(tw.state == TriggerWrapper.STATE_BLOCKED) {
                tw.state = TriggerWrapper.STATE_PAUSED_BLOCKED;
            } else {
                tw.state = TriggerWrapper.STATE_PAUSED;
            }

            /**
             * 注意从这里我们看到处于paused状态的trigger将会被从timeTriggers中移除
             */
            timeTriggers.remove(tw);
        }
    }

    /**
     * <p>
     * Pause all of the known <code>{@link Trigger}s</code> matching.
     * </p>
     *
     * <p>
     * The JobStore should "remember" the groups paused, and impose the
     * pause on any new triggers that are added to one of these groups while the group is
     * paused.
     * </p>
     *暂停所有已知的匹配触发器。
     * JobStore应该“记住”暂停的组，并对在暂停组时添加到其中一个组的任何新触发器强制暂停。
     */
    public List<String> pauseTriggers(GroupMatcher<TriggerKey> matcher) {

        List<String> pausedGroups;
        synchronized (lock) {
            pausedGroups = new LinkedList<String>();

            StringMatcher.StringOperatorName operator = matcher.getCompareWithOperator();
            switch (operator) {
                case EQUALS:
                    if(pausedTriggerGroups.add(matcher.getCompareToValue())) {
                        pausedGroups.add(matcher.getCompareToValue());
                    }
                    break;
                default :
                    for (String group : triggersByGroup.keySet()) {
                        if(operator.evaluate(group, matcher.getCompareToValue())) {
                            if(pausedTriggerGroups.add(matcher.getCompareToValue())) {
                                pausedGroups.add(group);
                            }
                        }
                    }
            }

            for (String pausedGroup : pausedGroups) {
                Set<TriggerKey> keys = getTriggerKeys(GroupMatcher.triggerGroupEquals(pausedGroup));

                for (TriggerKey key: keys) {
                    pauseTrigger(key);
                }
            }
        }

        return pausedGroups;
    }

    /**
     * <p>
     * Pause the <code>{@link org.quartz.JobDetail}</code> with the given
     * name - by pausing all of its current <code>Trigger</code>s.
     * </p>
     *暂停指定名称的JobDetail—通过暂停它的所有当前触发器。
     */
    public void pauseJob(JobKey jobKey) {
        synchronized (lock) {
            List<OperableTrigger> triggersOfJob = getTriggersForJob(jobKey);
            for (OperableTrigger trigger: triggersOfJob) {
                pauseTrigger(trigger.getKey());
            }
        }
    }

    /**
     * <p>
     * Pause all of the <code>{@link org.quartz.JobDetail}s</code> in the
     * given group - by pausing all of their <code>Trigger</code>s.
     * </p>
     *
     *
     * <p>
     * The JobStore should "remember" that the group is paused, and impose the
     * pause on any new jobs that are added to the group while the group is
     * paused.
     * </p>
     */
    public List<String> pauseJobs(GroupMatcher<JobKey> matcher) {
        List<String> pausedGroups = new LinkedList<String>();
        synchronized (lock) {

            StringMatcher.StringOperatorName operator = matcher.getCompareWithOperator();
            switch (operator) {
                case EQUALS:
                    if (pausedJobGroups.add(matcher.getCompareToValue())) {
                        pausedGroups.add(matcher.getCompareToValue());
                    }
                    break;
                default :
                    for (String group : jobsByGroup.keySet()) {
                        if(operator.evaluate(group, matcher.getCompareToValue())) {
                            if (pausedJobGroups.add(group)) {
                                pausedGroups.add(group);
                            }
                        }
                    }
            }

            for (String groupName : pausedGroups) {
                for (JobKey jobKey: getJobKeys(GroupMatcher.jobGroupEquals(groupName))) {
                    List<OperableTrigger> triggersOfJob = getTriggersForJob(jobKey);
                    for (OperableTrigger trigger: triggersOfJob) {
                        pauseTrigger(trigger.getKey());
                    }
                }
            }
        }

        return pausedGroups;
    }

    /**
     * <p>
     * Resume (un-pause) the <code>{@link Trigger}</code> with the given
     * key.
     * </p>
     *
     * <p>
     * If the <code>Trigger</code> missed one or more fire-times, then the
     * <code>Trigger</code>'s misfire instruction will be applied.
     * </p>
     *使用给定键恢复(取消暂停)触发器。
     * 如果触发器错过一个或多个触发时间，那么触发器的失败指令将被应用。
     */
    public void resumeTrigger(TriggerKey triggerKey) {

        synchronized (lock) {
            TriggerWrapper tw = triggersByKey.get(triggerKey);
    
            // does the trigger exist?
            if (tw == null || tw.trigger == null) {
                return;
            }
    
            OperableTrigger trig = tw.getTrigger();
    
            // if the trigger is not paused resuming it does not make sense...
            /**
             * 如果触发器没有被暂停，那么恢复是没有意义的。
             */
            if (tw.state != TriggerWrapper.STATE_PAUSED &&
                    tw.state != TriggerWrapper.STATE_PAUSED_BLOCKED) {
                return;
            }

            /**
             * 如果block中包含，则将其你设置为blocked状态，否则将其设置为waiting状态
             */
            if(blockedJobs.contains( trig.getJobKey() )) {
                tw.state = TriggerWrapper.STATE_BLOCKED;
            } else {
                tw.state = TriggerWrapper.STATE_WAITING;
            }

            applyMisfire(tw);

            /**
             * 如果是waiting状态则将其添加到timeTriggers
             */
            if (tw.state == TriggerWrapper.STATE_WAITING) {

                timeTriggers.add(tw);
            }
        }
    }

    /**
     * <p>
     * Resume (un-pause) all of the <code>{@link Trigger}s</code> in the
     * given group.
     * </p>
     *
     * <p>
     * If any <code>Trigger</code> missed one or more fire-times, then the
     * <code>Trigger</code>'s misfire instruction will be applied.
     * </p>
     *
     */
    public List<String> resumeTriggers(GroupMatcher<TriggerKey> matcher) {
        Set<String> groups = new HashSet<String>();

        synchronized (lock) {
            Set<TriggerKey> keys = getTriggerKeys(matcher);

            for (TriggerKey triggerKey: keys) {
                groups.add(triggerKey.getGroup());
                if(triggersByKey.get(triggerKey) != null) {
                    String jobGroup = triggersByKey.get(triggerKey).jobKey.getGroup();
                    if(pausedJobGroups.contains(jobGroup)) {
                        continue;
                    }
                }
                resumeTrigger(triggerKey);
            }

            // Find all matching paused trigger groups, and then remove them.
            StringMatcher.StringOperatorName operator = matcher.getCompareWithOperator();
            LinkedList<String> pausedGroups = new LinkedList<String>();
            String matcherGroup = matcher.getCompareToValue();
            switch (operator) {
                case EQUALS:
                    if(pausedTriggerGroups.contains(matcherGroup)) {
                        pausedGroups.add(matcher.getCompareToValue());
                    }
                    break;
                default :
                    for (String group : pausedTriggerGroups) {
                        if(operator.evaluate(group, matcherGroup)) {
                            pausedGroups.add(group);
                        }
                    }
            }
            for (String pausedGroup : pausedGroups) {
                pausedTriggerGroups.remove(pausedGroup);
            }
        }

        return new ArrayList<String>(groups);
    }

    /**
     * <p>
     * Resume (un-pause) the <code>{@link org.quartz.JobDetail}</code> with
     * the given name.
     * </p>
     *
     * <p>
     * If any of the <code>Job</code>'s<code>Trigger</code> s missed one
     * or more fire-times, then the <code>Trigger</code>'s misfire
     * instruction will be applied.
     * </p>
     *
     */
    public void resumeJob(JobKey jobKey) {

        synchronized (lock) {
            List<OperableTrigger> triggersOfJob = getTriggersForJob(jobKey);
            for (OperableTrigger trigger: triggersOfJob) {
                resumeTrigger(trigger.getKey());
            }
        }
    }

    /**
     * <p>
     * Resume (un-pause) all of the <code>{@link org.quartz.JobDetail}s</code>
     * in the given group.
     * </p>
     *
     * <p>
     * If any of the <code>Job</code> s had <code>Trigger</code> s that
     * missed one or more fire-times, then the <code>Trigger</code>'s
     * misfire instruction will be applied.
     * </p>
     *
     */
    public Collection<String> resumeJobs(GroupMatcher<JobKey> matcher) {
        Set<String> resumedGroups = new HashSet<String>();
        synchronized (lock) {
            Set<JobKey> keys = getJobKeys(matcher);

            for (String pausedJobGroup : pausedJobGroups) {
                if(matcher.getCompareWithOperator().evaluate(pausedJobGroup, matcher.getCompareToValue())) {
                    resumedGroups.add(pausedJobGroup);
                }
            }

            for (String resumedGroup : resumedGroups) {
                pausedJobGroups.remove(resumedGroup);
            }

            for (JobKey key: keys) {
                List<OperableTrigger> triggersOfJob = getTriggersForJob(key);
                for (OperableTrigger trigger: triggersOfJob) {
                    resumeTrigger(trigger.getKey());
                }
            }
        }
        return resumedGroups;
    }

    /**
     * <p>
     * Pause all triggers - equivalent of calling <code>pauseTriggerGroup(group)</code>
     * on every group.
     * </p>
     *
     * <p>
     * When <code>resumeAll()</code> is called (to un-pause), trigger misfire
     * instructions WILL be applied.
     * </p>
     *
     * @see #resumeAll()
     * @see #pauseTrigger(org.quartz.TriggerKey)
     * @see #pauseTriggers(org.quartz.impl.matchers.GroupMatcher)
     */
    public void pauseAll() {

        synchronized (lock) {
            List<String> names = getTriggerGroupNames();

            for (String name: names) {
                pauseTriggers(GroupMatcher.triggerGroupEquals(name));
            }
        }
    }

    /**
     * <p>
     * Resume (un-pause) all triggers - equivalent of calling <code>resumeTriggerGroup(group)</code>
     * on every group.
     * </p>
     *
     * <p>
     * If any <code>Trigger</code> missed one or more fire-times, then the
     * <code>Trigger</code>'s misfire instruction will be applied.
     * </p>
     *
     * @see #pauseAll()
     */
    public void resumeAll() {

        synchronized (lock) {
            pausedJobGroups.clear();
            resumeTriggers(GroupMatcher.anyTriggerGroup());
        }
    }

    protected boolean applyMisfire(TriggerWrapper tw) {

        /**
         * 当前时间为10000，misfireThreshold为100，
         * misfireTime=misfireTime-misfireThreshold=19900
         */
        long misfireTime = System.currentTimeMillis();
        if (getMisfireThreshold() > 0) {
            misfireTime -= getMisfireThreshold();
        }

        Date tnft = tw.trigger.getNextFireTime();
        /**
         *  tnft.getTime() > misfireTime  下次触发的时间大于 误差时间。
         *  比如下次触发的时间是明天12点，误差允许时间为今天下午六点半前，这个时候这个trigger不符合条件，因此返回false
         */
        if (tnft == null || tnft.getTime() > misfireTime 
                || tw.trigger.getMisfireInstruction() == Trigger.MISFIRE_INSTRUCTION_IGNORE_MISFIRE_POLICY) { 
            return false; 
        }

        Calendar cal = null;
        if (tw.trigger.getCalendarName() != null) {
            cal = retrieveCalendar(tw.trigger.getCalendarName());
        }

        signaler.notifyTriggerListenersMisfired((OperableTrigger)tw.trigger.clone());

        tw.trigger.updateAfterMisfire(cal);

        if (tw.trigger.getNextFireTime() == null) {
            tw.state = TriggerWrapper.STATE_COMPLETE;
            signaler.notifySchedulerListenersFinalized(tw.trigger);
            synchronized (lock) {
                /**
                 * 当trigger的state为complete的时候会将其从timeTriggers中移除
                 */
                timeTriggers.remove(tw);
            }
        } else if (tnft.equals(tw.trigger.getNextFireTime())) {
            return false;
        }

        return true;
    }

    private static final AtomicLong ftrCtr = new AtomicLong(System.currentTimeMillis());

    protected String getFiredTriggerRecordId() {
        return String.valueOf(ftrCtr.incrementAndGet());
    }

    /**
     * <p>
     * Get a handle to the next trigger to be fired, and mark it as 'reserved'
     * by the calling scheduler.
     * </p>
     *获取下一个要触发的触发器的句柄，并由调用调度器将其标记为'reserved'。进入翻译页面
     *
     * SELECT
     *     TRIGGER_NAME,
     *     TRIGGER_GROUP,
     *     NEXT_FIRE_TIME,
     *     PRIORITY
     * FROM
     *     QRTZ_TRIGGERS
     * WHERE
     *     SCHED_NAME = 'TestScheduler'
     * AND TRIGGER_STATE = ?
     * AND NEXT_FIRE_TIME <= ?
     * AND (
     *     MISFIRE_INSTR = - 1
     *     OR (
     *         MISFIRE_INSTR != - 1
     *         AND NEXT_FIRE_TIME >= ?
     *     )
     * )
     * ORDER BY
     *     NEXT_FIRE_TIME ASC,
     *     PRIORITY DESC
     *   Sql:  org.quartz.impl.jdbcjobstore.StdJDBCConstants#SELECT_INSTANCES_FIRED_TRIGGERS
     * @see #releaseAcquiredTrigger(OperableTrigger)
     *
     * ==============================================================
     * 调度线程会一次性拉取距离现在一定时间窗口内的、一定数量内的、即将触发的trigger信息。那么，时间窗口和数量信息如何确定呢？我们先来看一下，以下几个参数：
     *
     * idleWaitTime： 默认30s，可通过配置属性org.quartz.scheduler.idleWaitTime设置。
     * availThreadCount：获取可用（空闲）的工作线程数量，总会大于1，因为该方法会一直阻塞，直到有工作线程空闲下来。
     * maxBatchSize：一次拉取trigger的最大数量，默认是1，可通过org.quartz.scheduler.batchTriggerAcquisitionMaxCount改写。
     * batchTimeWindow：时间窗口调节参数，默认是0，可通过org.quartz.scheduler.batchTriggerAcquisitionFireAheadTimeWindow改写。
     * misfireThreshold： 超过这个时间还未触发的trigger，被认为发生了misfire，默认60s，可通过org.quartz.jobStore.misfireThreshold设置。
     * 调度线程一次会拉取NEXT_FIRETIME小于（now + idleWaitTime +batchTimeWindow）,大于（now - misfireThreshold）的，
     * min(availThreadCount,maxBatchSize)个triggers，默认情况下，会拉取未来30s、过去60s之间还未fire的1个trigger。
     * 随后将这些triggers的状态由WAITING改为ACQUIRED，并插入firedtriggers表。
     *
     */
    public List<OperableTrigger> acquireNextTriggers(long noLaterThan, int maxCount, long timeWindow) {
        synchronized (lock) {
            List<OperableTrigger> result = new ArrayList<OperableTrigger>();
            Set<JobKey> acquiredJobKeysForNoConcurrentExec = new HashSet<JobKey>();
            Set<TriggerWrapper> excludedTriggers = new HashSet<TriggerWrapper>();
            long batchEnd = noLaterThan;
            
            // return empty list if store has no triggers.
            /**
             * 注意从这里我们看到acquireNextTriggers 获取将要被触发的trigger是从timeTriggers中获取的，因为
             * timeTriggers是一个TreeSet，按照trigger的下次触发时间排序的
             */
            if (timeTriggers.size() == 0)
                return result;
            while (true) {
                //遍历每一个trigger
                TriggerWrapper tw;

                try {
                    /**
                     * timeTriggers是一个TreeSet ，元素之间使用下次触发时间排序。
                     * 这里是获取第一个被触发的job，如果tw不为null，则执行remove， 然后下次循环的时候会又1取第一个，因此这就实现了对TreeSet的遍历
                     *
                     *
                     *
                     */
                    tw = timeTriggers.first();
                    if (tw == null)
                        break;
                    timeTriggers.remove(tw);
                } catch (java.util.NoSuchElementException nsee) {
                    break;
                }

                /**
                 * 返回触发器计划下一次触发的时间。如果触发器不会再次触发，则返回null。注意，返回的时间可能是过去的时间，如果为触发器计算的下一次触发的时间已经到达，但是调度器还不能触发触发器(这可能是由于缺乏资源，例如线程)。
                 * 在将Trigger添加到调度器之前，不能保证返回的值是有效的。
                 */
                if (tw.trigger.getNextFireTime() == null) {
                    continue;
                }

                /**
                 * applyMisfire 判断是否在误差延迟触发范围之内
                 *
                 * applyMisfire返回true表示trigger的下次触发时间 在允许误差时间内，比如下次触发时间为六点，允许误差时间Wie六点10秒
                 *
                 */
                if (applyMisfire(tw)) {
                    if (tw.trigger.getNextFireTime() != null) {
                        timeTriggers.add(tw);
                    }
                    continue;
                }

                /**
                 * trigger的触发时间是否在指定的时间范围内。
                 */
                if (tw.getTrigger().getNextFireTime().getTime() > batchEnd) {
                    timeTriggers.add(tw);
                    break;
                }
                
                // If trigger's job is set as @DisallowConcurrentExecution, and it has already been added to result, then
                // put it back into the timeTriggers set and continue to search for next trigger.
                /**
                 * //如果触发器的job被设置为@DisallowConcurrentExecution，并且它已经被添加到result中，则
                 * //将它放回timeTriggers集合并继续搜索下一个触发器。
                 */
                JobKey jobKey = tw.trigger.getJobKey();
                JobDetail job = jobsByKey.get(tw.trigger.getJobKey()).jobDetail;
                /**
                 * 判断jobDetail上是否存在@DisallowConcurrentExecution注解。该注解：将Job类标记为不能同时执行多个实例的注释(其中实例基于JobDetail定义——或者换句话说基于JobKey)。
                 * 存在注解返回true
                 */
                if (job.isConcurrentExectionDisallowed()) {
                    if (acquiredJobKeysForNoConcurrentExec.contains(jobKey)) {
                        /**
                         * 如果acquiredJobKeysForNoConcurrentExec中有jobKey，则将trigger放入excludedTriggers
                         * 在后面excludedTriggers中的trigger将会被放置到timeTriggers中
                         */
                        excludedTriggers.add(tw);
                        continue; // go to next trigger in store.
                    } else {
                        acquiredJobKeysForNoConcurrentExec.add(jobKey);
                    }
                }

                /**
                 * 将trigger的状态设置为State_acquired,
                 *
                 * 后面在triggersFired方法中会遍历当前acquireNextTriggers方法返回的每一个Trigger，
                 * 遍历过程中如果trigger的状态不是state_acquired则会跳过该trigger
                 *
                 *
                 * trigger的初始状态是WAITING，处于WAITING状态的trigger等待被触发。调度线程会不停地扫triggers表，
                 * 根据NEXTFIRETIME提前拉取即将触发的trigger，如果这个trigger被该调度线程拉取到，它的状态就会变为ACQUIRED。
                 * 因为是提前拉取trigger，并未到达trigger真正的触发时刻，所以调度线程会等到真正触发的时刻，再将trigger状态由ACQUIRED改为EXECUTING。
                 * 如果这个trigger不再执行，就将状态改为COMPLETE，否则为WAITING，开始新的周期。如果这个周期中的任何环节抛出异常，
                 * trigger的状态会变成ERROR。如果手动暂停这个trigger，状态会变成PAUSED。
                 *
                 *
                 *
                 *What does BLOCKED state mean for Quartz trigger
                 * Perhaps some search would help before posting a question here?
                 *
                 * WAITING = the normal state of a trigger, waiting for its fire time to arrive and be acquired for firing by a scheduler.
                 *
                 * PAUSED = means that one of the scheduler.pauseXXX() methods was used. The trigger is not eligible for being fired until it is resumed.
                 *
                 * ACQUIRED = a scheduler node has identified this trigger as the next trigger it will fire - may still be waiting for its fire time to arrive. After it fires the trigger will be updated (per its repeat settings, if any) and placed back into the WAITING state (or be deleted if it does not repeat again).
                 *
                 * BLOCKED = the trigger is prevented from being fired because it relates to a StatefulJob that is already executing. When the statefuljob completes its execution, all triggers relating to that job will return to the WAITING state.
                 *
                 * In other words, When a state is BLOCKED, another trigger (or an instance of this trigger) is already executing for the trigger's stateful job, so this trigger is blocked until the other trigger is finished.
                 *
                 * Link to documentation could be useful for your future reference.http://www.docjar.com/docs/api/org/quartz/Trigger.html
                 */
                tw.state = TriggerWrapper.STATE_ACQUIRED;
                tw.trigger.setFireInstanceId(getFiredTriggerRecordId());
                OperableTrigger trig = (OperableTrigger) tw.trigger.clone();
                if (result.isEmpty()) {
                    batchEnd = Math.max(tw.trigger.getNextFireTime().getTime(), System.currentTimeMillis()) + timeWindow;
                }
                /**
                 * 将Trigger放入result中，result中是将要被触发的trigger
                 * 从上面的内容中我们看到 result中的 trigger的state都应该是state_acquired
                 */
                result.add(trig);
                if (result.size() == maxCount)
                    break;
            }

            // If we did excluded triggers to prevent ACQUIRE state due to DisallowConcurrentExecution, we need to add them back to store.
            if (excludedTriggers.size() > 0)
                timeTriggers.addAll(excludedTriggers);
            return result;
        }
    }

    /**
     * <p>
     * Inform the <code>JobStore</code> that the scheduler no longer plans to
     * fire the given <code>Trigger</code>, that it had previously acquired
     * (reserved).
     * </p>
     */
    public void releaseAcquiredTrigger(OperableTrigger trigger) {
        synchronized (lock) {
            TriggerWrapper tw = triggersByKey.get(trigger.getKey());
            if (tw != null && tw.state == TriggerWrapper.STATE_ACQUIRED) {
                tw.state = TriggerWrapper.STATE_WAITING;
                timeTriggers.add(tw);
            }
        }
    }

    /**
     * <p>
     * Inform the <code>JobStore</code> that the scheduler is now firing the
     * given <code>Trigger</code> (executing its associated <code>Job</code>),
     * that it had previously acquired (reserved).
     * </p>
     * 通知JobStore调度器现在正在触发给定的Trigger(执行其关联的Job)，以及它之前获得的(保留的)。
     *
     * 返回:
     * 如果所有触发器或它们的日历不再存在，或者触发器没有被成功地放入“执行”状态，可能返回null。如果没有一个触发器可以触发，首选方法是返回一个空列表
     */
    public List<TriggerFiredResult> triggersFired(List<OperableTrigger> firedTriggers) {

        synchronized (lock) {
            List<TriggerFiredResult> results = new ArrayList<TriggerFiredResult>();

            for (OperableTrigger trigger : firedTriggers) {
                TriggerWrapper tw = triggersByKey.get(trigger.getKey());
                // was the trigger deleted since being acquired?
                if (tw == null || tw.trigger == null) {
                    continue;
                }
                // was the trigger completed, paused, blocked, etc. since being acquired?
                /**
                 * //触发器是否被完成，暂停，阻塞等?
                 *
                 * 如果Trigger的状态不是state_acuired则跳过该trigger，问题为什么？
                 *
                 */
                if (tw.state != TriggerWrapper.STATE_ACQUIRED) {
                    continue;
                }

                Calendar cal = null;
                if (tw.trigger.getCalendarName() != null) {
                    cal = retrieveCalendar(tw.trigger.getCalendarName());
                    if(cal == null)
                        continue;
                }
                Date prevFireTime = trigger.getPreviousFireTime();
                // in case trigger was replaced between acquiring and firing
                /**
                 * 以防在射击和射击之间更换触发器
                 */
                timeTriggers.remove(tw);
                /**
                 *调用我们的副本，还有调度程序的副本
                 *在trigger的trigger方法中，trigger会执行自身的更新操作，比如 将上一次触发的时间设置为trigger中保存的下一次触发的时间，然后计算更新下一次触发的时间，
                 */
                // call triggered on our copy, and the scheduler's copy
                tw.trigger.triggered(cal);
                trigger.triggered(cal);
                /**
                 * 这个地方有个问题，上面的代码中会判断当前的trigger的状态是否是acquired，如果不是则跳过。
                 * 因此这里trigger的状态必然是acquired，正常情况下下一个状态应该是executing，为什么下面的代码将状态设置为waiting？
                 *
                 *
                 * 这里将状态设置为 waiting，在后面的处理中 也就是下面的for循环中会遍历每一个Trigger，如果trigger的状态为waiting，则会将其更新为blocked，并从timeTriggers中移除。
                 *
                 * 也就是说： 处于执行状态的Job的该Trigger的state 将会是 blocked。 同时该Trigger对应的Job 会被放置到  blockedJobs.add(job.getKey());
                 *
                 */
                //tw.state = TriggerWrapper.STATE_EXECUTING;
                tw.state = TriggerWrapper.STATE_WAITING;

                TriggerFiredBundle bndle = new TriggerFiredBundle(retrieveJob(
                        tw.jobKey), trigger, cal,
                        false, new Date(), trigger.getPreviousFireTime(), prevFireTime,
                        trigger.getNextFireTime());

                JobDetail job = bndle.getJobDetail();

                /**
                 * 如果job不能同时执行多个实例，则将其他trigger的桩体设置为blocked
                 */
                if (job.isConcurrentExectionDisallowed()) {
                    /**
                     * 这里getTriggerWrappersForJob 是从triggersByJob中获取Job的所有Trigger，然后将该Job的处于waiting和state_paused状态的所有trigger设置为block状态
                     * 然后将Job放置到blockedJobs中。同时会将该Job的所有trigger从timeTriggers中移除，这样下次遍历timeTriggers的时候就拿不到该Job的trigger
                     * 然后在Job执行完成的时候 ，也就是JobRunShell的run方法的最后会执行notifyJobStoreJobComplete，此时会执行
                     *   resources.getJobStore().triggeredJobComplete(trigger, detail, instCode);
                     *   在RAMJobStore的triggeredJobComplete中执行 blockedJobs.remove(jd.getKey()); 将该Job从block队列中
                     *   移除，同时会将对应的JobTrigger的状态设置为waiting，并将trigger放置到 timeTriggers中。
                     *   ttw.state = TriggerWrapper.STATE_WAITING;
                     *                             timeTriggers.add(ttw);
                     */
                    ArrayList<TriggerWrapper> trigs = getTriggerWrappersForJob(job.getKey());
                    for (TriggerWrapper ttw : trigs) {
                        /**
                         * 注意这里遍历了该Job的所有trigger，在上面的代码中，对于当前外部的trigger，我们会判断其状态是否是acquired：tw.state != TriggerWrapper.STATE_ACQUIRED
                         * 如果不是acquired则会执行continue，也就走不到这里。
                         *
                         * 我们获取到的当前Job的所有trigger，对于这些trigger中处于waiting状态的设置为blocked， 处于paused状态的设置为paused_block。
                         * 处于acquired状态的trigger，比如for外部的tw并不更改其状态。
                         * 然后将该Job的所有trigger从timeTriggers中移除， 这样可以保证下次遍历timeTriggers的时候不会获取到当前Job的Trigger，从而不会并发执行该Job
                         */
                        if (ttw.state == TriggerWrapper.STATE_WAITING) {
                            ttw.state = TriggerWrapper.STATE_BLOCKED;
                        }
                        if (ttw.state == TriggerWrapper.STATE_PAUSED) {
                            ttw.state = TriggerWrapper.STATE_PAUSED_BLOCKED;
                        }
                        timeTriggers.remove(ttw);
                    }
                    /**
                     * 将job放置到blockedJobs中
                     */
                    blockedJobs.add(job.getKey());
                } else if (tw.trigger.getNextFireTime() != null) {
                    synchronized (lock) {
                        timeTriggers.add(tw);
                    }
                }

                results.add(new TriggerFiredResult(bndle));
            }
            return results;
        }
    }

    /**
     * <p>
     * Inform the <code>JobStore</code> that the scheduler has completed the
     * firing of the given <code>Trigger</code> (and the execution its
     * associated <code>Job</code>), and that the <code>{@link org.quartz.JobDataMap}</code>
     * in the given <code>JobDetail</code> should be updated if the <code>Job</code>
     * is stateful.
     * </p>
     * 通知JobStore，调度器已经完成了触发给定的Trigger(以及执行其关联的Job)，如果Job是有状态的，则应该更新给定JobDetail中的jobdatmap。
     *
     * 在JobRunShell的run方法的最后会执行 :qs.notifyJobStoreJobComplete(trigger, jobDetail, instCode);
     * 其中qs是QuartzScheduler，然后会执行
     *  resources.getJobStore().triggeredJobComplete(trigger, detail, instCode);
     *  也就是这的triggeredJobComplete
     */
    public void triggeredJobComplete(OperableTrigger trigger,
            JobDetail jobDetail, CompletedExecutionInstruction triggerInstCode) {

        synchronized (lock) {

            JobWrapper jw = jobsByKey.get(jobDetail.getKey());
            TriggerWrapper tw = triggersByKey.get(trigger.getKey());

            // It's possible that the job is null if:
            //   1- it was deleted during execution
            //   2- RAMJobStore is being used only for volatile jobs / triggers
            //      from the JDBC job store
            /**
             * //如果:
             * //在执行过程中被删除
             * // 2- RAMJobStore只用于不稳定的作业/触发器
             * //从JDBC作业存储
             */
            if (jw != null) {
                JobDetail jd = jw.jobDetail;

                /**
                 *类上是否存在PersistJobDataAfterExecution注解
                 */
                if (jd.isPersistJobDataAfterExecution()) {
                    JobDataMap newData = jobDetail.getJobDataMap();
                    if (newData != null) {
                        newData = (JobDataMap)newData.clone();
                        newData.clearDirtyFlag();
                    }
                    jd = jd.getJobBuilder().setJobData(newData).build();
                    jw.jobDetail = jd;
                }
                if (jd.isConcurrentExectionDisallowed()) {
                    /**
                     * 参考org.quartz.simpl.RAMJobStore#triggersFired(java.util.List) 中的blockedJobs.add(job.getKey());
                     */
                    blockedJobs.remove(jd.getKey());
                    ArrayList<TriggerWrapper> trigs = getTriggerWrappersForJob(jd.getKey());
                    for(TriggerWrapper ttw : trigs) {
                        if (ttw.state == TriggerWrapper.STATE_BLOCKED) {
                            ttw.state = TriggerWrapper.STATE_WAITING;
                            timeTriggers.add(ttw);
                        }
                        if (ttw.state == TriggerWrapper.STATE_PAUSED_BLOCKED) {
                            ttw.state = TriggerWrapper.STATE_PAUSED;
                        }
                    }
                    signaler.signalSchedulingChange(0L);
                }
            } else { // even if it was deleted, there may be cleanup to do
                blockedJobs.remove(jobDetail.getKey());
            }
    
            // check for trigger deleted during execution...
            if (tw != null) {
                if (triggerInstCode == CompletedExecutionInstruction.DELETE_TRIGGER) {
                    
                    if(trigger.getNextFireTime() == null) {
                        // double check for possible reschedule within job 
                        // execution, which would cancel the need to delete...
                        if(tw.getTrigger().getNextFireTime() == null) {
                            removeTrigger(trigger.getKey());
                        }
                    } else {
                        removeTrigger(trigger.getKey());
                        signaler.signalSchedulingChange(0L);
                    }
                } else if (triggerInstCode == CompletedExecutionInstruction.SET_TRIGGER_COMPLETE) {
                    tw.state = TriggerWrapper.STATE_COMPLETE;
                    timeTriggers.remove(tw);
                    signaler.signalSchedulingChange(0L);
                } else if(triggerInstCode == CompletedExecutionInstruction.SET_TRIGGER_ERROR) {
                    getLog().info("Trigger " + trigger.getKey() + " set to ERROR state.");
                    tw.state = TriggerWrapper.STATE_ERROR;
                    signaler.signalSchedulingChange(0L);
                } else if (triggerInstCode == CompletedExecutionInstruction.SET_ALL_JOB_TRIGGERS_ERROR) {
                    getLog().info("All triggers of Job " 
                            + trigger.getJobKey() + " set to ERROR state.");
                    setAllTriggersOfJobToState(trigger.getJobKey(), TriggerWrapper.STATE_ERROR);
                    signaler.signalSchedulingChange(0L);
                } else if (triggerInstCode == CompletedExecutionInstruction.SET_ALL_JOB_TRIGGERS_COMPLETE) {
                    setAllTriggersOfJobToState(trigger.getJobKey(), TriggerWrapper.STATE_COMPLETE);
                    signaler.signalSchedulingChange(0L);
                }
            }
        }
    }

    @Override
    public long getAcquireRetryDelay(int failureCount) {
        return 20;
    }

    protected void setAllTriggersOfJobToState(JobKey jobKey, int state) {
        ArrayList<TriggerWrapper> tws = getTriggerWrappersForJob(jobKey);
        for (TriggerWrapper tw : tws) {
            tw.state = state;
            if (state != TriggerWrapper.STATE_WAITING) {
                timeTriggers.remove(tw);
            }
        }
    }
    
    @SuppressWarnings("UnusedDeclaration")
    protected String peekTriggers() {

        StringBuilder str = new StringBuilder();
        synchronized (lock) {
            for (TriggerWrapper triggerWrapper : triggersByKey.values()) {
                str.append(triggerWrapper.trigger.getKey().getName());
                str.append("/");
            }
        }
        str.append(" | ");

        synchronized (lock) {
            for (TriggerWrapper timeTrigger : timeTriggers) {
                str.append(timeTrigger.trigger.getKey().getName());
                str.append("->");
            }
        }

        return str.toString();
    }

    /** 
     * @see org.quartz.spi.JobStore#getPausedTriggerGroups()
     */
    public Set<String> getPausedTriggerGroups() throws JobPersistenceException {
        HashSet<String> set = new HashSet<String>();
        
        set.addAll(pausedTriggerGroups);
        
        return set;
    }

    public void setInstanceId(String schedInstId) {
        //
    }

    public void setInstanceName(String schedName) {
        //
    }

    public void setThreadPoolSize(final int poolSize) {
        //
    }

    public long getEstimatedTimeToReleaseAndAcquireTrigger() {
        return 5;
    }

    public boolean isClustered() {
        return false;
    }



}

/*******************************************************************************
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * 
 * Helper Classes. * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 */

class TriggerWrapperComparator implements Comparator<TriggerWrapper>, java.io.Serializable {
  
    private static final long serialVersionUID = 8809557142191514261L;

    TriggerTimeComparator ttc = new TriggerTimeComparator();
    
    public int compare(TriggerWrapper trig1, TriggerWrapper trig2) {
        return ttc.compare(trig1.trigger, trig2.trigger);
    }

    @Override
    public boolean equals(Object obj) {
        return (obj instanceof TriggerWrapperComparator);
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }
}

class JobWrapper {

    public JobKey key;

    public JobDetail jobDetail;

    JobWrapper(JobDetail jobDetail) {
        this.jobDetail = jobDetail;
        key = jobDetail.getKey();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof JobWrapper) {
            JobWrapper jw = (JobWrapper) obj;
            if (jw.key.equals(this.key)) {
                return true;
            }
        }

        return false;
    }
    
    @Override
    public int hashCode() {
        return key.hashCode(); 
    }

}

class TriggerWrapper {

    public final TriggerKey key;

    public final JobKey jobKey;

    public final OperableTrigger trigger;

    /**
     * public static final int state_normal
     * 表示触发器处于“正常”状态。
     *
     *
     * public static final int state_paused
     * 指示触发器处于“暂停”状态。
     *
     *
     * public static final int state_complete
     * 指示触发器处于“完成”状态。
     *
     * “完成”表示触发器的时间表中没有剩余的触发时间。
     *
     *
     * public static final int state_error
     * 指示触发器处于“错误”状态。
     *
     * 当调度程序试图触发触发器时，触发器将到达错误状态，但由于在创建和执行其相关作业时发生错误而不能。这通常是由于Job的类不在类路径中。
     *
     * 当触发器处于错误状态时，调度程序将不会尝试触发它。
     *
     *
     * public static final int state_blocked
     * 表示触发器处于“阻塞”状态。
     *
     * 当触发器与之关联的作业是一个StatefulJob并且它当前正在执行时，它就会到达阻塞状态。
     *
     * 还看到:
     * StatefulJob
     *
     * public static final int state_none
     * 表示触发器不存在。
     *
     *
     * public static final int default_priority优先级的默认值。
     */
    public int state = STATE_WAITING;

    public static final int STATE_WAITING = 0;

    public static final int STATE_ACQUIRED = 1;

    @SuppressWarnings("UnusedDeclaration")
    public static final int STATE_EXECUTING = 2;

    public static final int STATE_COMPLETE = 3;

    public static final int STATE_PAUSED = 4;

    public static final int STATE_BLOCKED = 5;

    public static final int STATE_PAUSED_BLOCKED = 6;

    public static final int STATE_ERROR = 7;
    
    TriggerWrapper(OperableTrigger trigger) {
        if(trigger == null)
            throw new IllegalArgumentException("Trigger cannot be null!");
        this.trigger = trigger;
        key = trigger.getKey();
        this.jobKey = trigger.getJobKey();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof TriggerWrapper) {
            TriggerWrapper tw = (TriggerWrapper) obj;
            if (tw.key.equals(this.key)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public int hashCode() {
        return key.hashCode(); 
    }

    
    public OperableTrigger getTrigger() {
        return this.trigger;
    }


}
