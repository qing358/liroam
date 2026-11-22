package com.liroam.fulfillment.worker;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.liroam.fulfillment.poll.PollScheduler;

/** 每个节拍调对应组件、带上批量与 worker 标识；一轮失败不往外抛（下轮照跑）。 */
class FulfillmentSchedulesTest {

    private final FulfillmentWorker worker = mock(FulfillmentWorker.class);
    private final CommandExecutor executor = mock(CommandExecutor.class);
    private final PollScheduler polls = mock(PollScheduler.class);
    private final FulfillmentSchedules schedules = new FulfillmentSchedules(worker, executor, polls, 7, "host:1");

    @Test
    void eachTickDrivesItsComponent() {
        schedules.expandLines();
        schedules.executeCommands();
        schedules.schedulePolls();

        verify(worker).runOnce(7);
        verify(executor).runOnce(7, "host:1");
        verify(polls).runOnce();
    }

    @Test
    void failedTickIsSwallowed() {
        when(worker.runOnce(7)).thenThrow(new IllegalStateException("db down"));
        when(executor.runOnce(7, "host:1")).thenThrow(new IllegalStateException("db down"));
        when(polls.runOnce()).thenThrow(new IllegalStateException("db down"));

        assertThatCode(schedules::expandLines).doesNotThrowAnyException();
        assertThatCode(schedules::executeCommands).doesNotThrowAnyException();
        assertThatCode(schedules::schedulePolls).doesNotThrowAnyException();
    }
}
