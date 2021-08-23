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
 * A <code>{@link Trigger}</code> that is used to fire a <code>Job</code>
 * at a given moment in time, and optionally repeated at a specified interval.
 * 
 * @see TriggerBuilder
 * @see SimpleScheduleBuilder
 * 
 * @author James House
 * @author contributions by Lieven Govaerts of Ebitec Nv, Belgium.
 * 二、激活失败处理
 *
 * 激活失败指令（Misfire Instructions）是触发器的一个重要属性，它指定了misfire发生时调度器应当如何处理。所有类型的触发器都有一个默认的指令，叫做Trigger.MISFIRE_INSTRUCTION_SMART_POLICY，但是这个这个“聪明策略”对于不同类型的触发器其具体行为是不同的。对于SimpleTrigger，这个“聪明策略”将根据触发器实例的状态和配置来决定其行为。具体如下[]：
 *
 * 如果Repeat Count=0：
 *
 * instruction selected = MISFIRE_INSTRUCTION_FIRE_NOW;
 *
 * 如果Repeat Count=REPEAT_INDEFINITELY：
 *
 * instruction selected = MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_REMAINING_COUNT;
 *
 * 如果Repeat Count>0：
 *
 * instruction selected = MISFIRE_INSTRUCTION_RESCHEDULE_NOW_WITH_EXISTING_REPEAT_COUNT;
 *
 * 下面解释SimpleTrigger常见策略：
 *
 * MISFIRE_INSTRUCTION_FIRE_NOW
 *
 * 立刻执行。对于不会重复执行的任务，这是默认的处理策略。
 *
 * MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_REMAINING_COUNT
 *
 * NEXT指以现在为基准，以repeat interval为周期，延时到下一个激活点执行。WITH_REMAINING_COUNT指超时期内错过的执行机会作废。因此该策略的含义是，在下一个激活点执行，且超时期内错过的执行机会作废。
 *
 * MISFIRE_INSTRUCTION_RESCHEDULE_NOW_WITH_REMAINING_COUNT
 *
 * 立即执行，且超时期内错过的执行机会作废。
 *
 * MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_EXISTING_COUNT
 *
 * WITH_EXISTING_COUNT指，根据已设置的repeat count进行执行。也就是说错过的执行机会不作废，保证实际执行次数满足设置。因此本策略的含义是，在下一个激活点执行，并重复到指定的次数。
 *
 * MISFIRE_INSTRUCTION_RESCHEDULE_NOW_WITH_EXISTING_COUNT
 *
 * 立即执行，并重复到指定的次数。
 *
 * MISFIRE_INSTRUCTION_IGNORE_MISFIRE_POLICY
 *
 * 忽略所有的超时状态，按照触发器的策略执行。
 *
 * 对于CronTrigger，该“聪明策略”默认选择MISFIRE_INSTRUCTION_FIRE_ONCE_NOW以指导其行为。
 *
 * 下面解释CronTrigger常见策略：
 *
 * MISFIRE_INSTRUCTION_FIRE_ONCE_NOW
 *
 * 立刻执行一次，然后就按照正常的计划执行。
 *
 * MISFIRE_INSTRUCTION_DO_NOTHING
 *
 * 目前不执行，然后就按照正常的计划执行。这意味着如果下次执行时间超过了end time，实际上就没有执行机会了。
 */
public interface SimpleTrigger extends Trigger {

    public static final long serialVersionUID = -3735980074222850397L;
    
    /**
     * <p>
     * Instructs the <code>{@link Scheduler}</code> that upon a mis-fire
     * situation, the <code>{@link SimpleTrigger}</code> wants to be fired
     * now by <code>Scheduler</code>.
     * </p>
     * 
     * <p>
     * <i>NOTE:</i> This instruction should typically only be used for
     * 'one-shot' (non-repeating) Triggers. If it is used on a trigger with a
     * repeat count > 0 then it is equivalent to the instruction <code>{@link #MISFIRE_INSTRUCTION_RESCHEDULE_NOW_WITH_REMAINING_REPEAT_COUNT}
     * </code>.
     * </p>
     */
    public static final int MISFIRE_INSTRUCTION_FIRE_NOW = 1;
    
    /**
     * <p>
     * Instructs the <code>{@link Scheduler}</code> that upon a mis-fire
     * situation, the <code>{@link SimpleTrigger}</code> wants to be
     * re-scheduled to 'now' (even if the associated <code>{@link Calendar}</code>
     * excludes 'now') with the repeat count left as-is.  This does obey the
     * <code>Trigger</code> end-time however, so if 'now' is after the
     * end-time the <code>Trigger</code> will not fire again.
     * </p>
     * 
     * <p>
     * <i>NOTE:</i> Use of this instruction causes the trigger to 'forget'
     * the start-time and repeat-count that it was originally setup with (this
     * is only an issue if you for some reason wanted to be able to tell what
     * the original values were at some later time).
     * </p>
     */
    public static final int MISFIRE_INSTRUCTION_RESCHEDULE_NOW_WITH_EXISTING_REPEAT_COUNT = 2;
    
    /**
     * <p>
     * Instructs the <code>{@link Scheduler}</code> that upon a mis-fire
     * situation, the <code>{@link SimpleTrigger}</code> wants to be
     * re-scheduled to 'now' (even if the associated <code>{@link Calendar}</code>
     * excludes 'now') with the repeat count set to what it would be, if it had
     * not missed any firings.  This does obey the <code>Trigger</code> end-time 
     * however, so if 'now' is after the end-time the <code>Trigger</code> will 
     * not fire again.
     * </p>
     * 
     * <p>
     * <i>NOTE:</i> Use of this instruction causes the trigger to 'forget'
     * the start-time and repeat-count that it was originally setup with.
     * Instead, the repeat count on the trigger will be changed to whatever
     * the remaining repeat count is (this is only an issue if you for some
     * reason wanted to be able to tell what the original values were at some
     * later time).
     * </p>
     * 
     * <p>
     * <i>NOTE:</i> This instruction could cause the <code>Trigger</code>
     * to go to the 'COMPLETE' state after firing 'now', if all the
     * repeat-fire-times where missed.
     * </p>
     */
    public static final int MISFIRE_INSTRUCTION_RESCHEDULE_NOW_WITH_REMAINING_REPEAT_COUNT = 3;
    
    /**
     * <p>
     * Instructs the <code>{@link Scheduler}</code> that upon a mis-fire
     * situation, the <code>{@link SimpleTrigger}</code> wants to be
     * re-scheduled to the next scheduled time after 'now' - taking into
     * account any associated <code>{@link Calendar}</code>, and with the
     * repeat count set to what it would be, if it had not missed any firings.
     * </p>
     * 
     * <p>
     * <i>NOTE/WARNING:</i> This instruction could cause the <code>Trigger</code>
     * to go directly to the 'COMPLETE' state if all fire-times where missed.
     * </p>
     */
    public static final int MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_REMAINING_COUNT = 4;
    
    /**
     * <p>
     * Instructs the <code>{@link Scheduler}</code> that upon a mis-fire
     * situation, the <code>{@link SimpleTrigger}</code> wants to be
     * re-scheduled to the next scheduled time after 'now' - taking into
     * account any associated <code>{@link Calendar}</code>, and with the
     * repeat count left unchanged.
     * </p>
     * 
     * <p>
     * <i>NOTE/WARNING:</i> This instruction could cause the <code>Trigger</code>
     * to go directly to the 'COMPLETE' state if the end-time of the trigger
     * has arrived.
     * </p>
     */
    public static final int MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_EXISTING_COUNT = 5;
    
    /**
     * <p>
     * Used to indicate the 'repeat count' of the trigger is indefinite. Or in
     * other words, the trigger should repeat continually until the trigger's
     * ending timestamp.
     * </p>
     */
    public static final int REPEAT_INDEFINITELY = -1;

    /**
     * <p>
     * Get the the number of times the <code>SimpleTrigger</code> should
     * repeat, after which it will be automatically deleted.
     * </p>
     * 
     * @see #REPEAT_INDEFINITELY
     */
    public int getRepeatCount();

    /**
     * <p>
     * Get the the time interval (in milliseconds) at which the <code>SimpleTrigger</code> should repeat.
     * </p>
     */
    public long getRepeatInterval();
    
    /**
     * <p>
     * Get the number of times the <code>SimpleTrigger</code> has already fired.
     * </p>
     */
    public int getTimesTriggered();

    TriggerBuilder<SimpleTrigger> getTriggerBuilder();
}
