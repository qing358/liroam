package com.liroam.fulfillment.testing;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.liroam.fulfillment.ShellOrderDelivery;

/** 假壳发货回写：记下每次调用；可让接下来几次失败以验证重试。 */
public final class RecordingShellOrderDelivery implements ShellOrderDelivery {

    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final AtomicInteger failures = new AtomicInteger();

    @Override
    public void markDelivered(String liOrderSn) {
        calls.add(liOrderSn);
        if (failures.getAndUpdate(n -> Math.max(n - 1, 0)) > 0) {
            throw new IllegalStateException("壳发货回写失败（测试注入）");
        }
    }

    public List<String> calls() {
        return List.copyOf(calls);
    }

    public void failNext(int times) {
        failures.set(times);
    }

    public void reset() {
        calls.clear();
        failures.set(0);
    }
}
