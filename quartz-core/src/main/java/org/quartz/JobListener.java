
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

package org.quartz;

/**
 * The interface to be implemented by classes that want to be informed when a
 * <code>{@link org.quartz.JobDetail}</code> executes. In general,
 * applications that use a <code>Scheduler</code> will not have use for this
 * mechanism.
 * 
 * @see ListenerManager#addJobListener(JobListener, Matcher)
 * @see Matcher
 * @see Job
 * @see JobExecutionContext
 * @see JobExecutionException
 * @see TriggerListener
 *
 * 由希望在JobDetail执行时得到通知的类实现的接口。通常，使用Scheduler的应用程序不会使用这种机制。
 * 
 * @author James House
 */
public interface JobListener {

    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * 
     * Interface.
     * 
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */

    /**
     * <p>
     * Get the name of the <code>JobListener</code>.
     * </p>
     */
    String getName();

    /**
     * <p>
     * Called by the <code>{@link Scheduler}</code> when a <code>{@link org.quartz.JobDetail}</code>
     * is about to be executed (an associated <code>{@link Trigger}</code>
     * has occurred).
     * </p>
     * 
     * <p>
     * This method will not be invoked if the execution of the Job was vetoed
     * by a <code>{@link TriggerListener}</code>.
     * </p>
     * 当JobDetail即将执行时(发生了关联的触发器)，Scheduler将调用该函数。
     * 如果作业的执行被TriggerListener否决，则不会调用此方法。
     * 
     * @see #jobExecutionVetoed(JobExecutionContext)
     */
    void jobToBeExecuted(JobExecutionContext context);

    /**
     * <p>
     * Called by the <code>{@link Scheduler}</code> when a <code>{@link org.quartz.JobDetail}</code>
     * was about to be executed (an associated <code>{@link Trigger}</code>
     * has occurred), but a <code>{@link TriggerListener}</code> vetoed it's 
     * execution.
     * </p>
     *
     * 当JobDetail即将被执行时(关联的触发器已经发生)，Scheduler会调用它，但是TriggerListener否决了它的执行。
     * @see #jobToBeExecuted(JobExecutionContext)
     */
    void jobExecutionVetoed(JobExecutionContext context);

    
    /**
     * <p>
     * Called by the <code>{@link Scheduler}</code> after a <code>{@link org.quartz.JobDetail}</code>
     * has been executed, and be for the associated <code>Trigger</code>'s
     * <code>triggered(xx)</code> method has been called.
     * 在执行JobDetail之后由Scheduler调用，并且对于关联的Trigger的triggered(xx)方法已经被调用。
     * </p>
     */
    void jobWasExecuted(JobExecutionContext context,
            JobExecutionException jobException);

}
