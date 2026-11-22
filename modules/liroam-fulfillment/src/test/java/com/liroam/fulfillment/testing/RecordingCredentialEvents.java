package com.liroam.fulfillment.testing;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.context.event.EventListener;

import com.liroam.fulfillment.CredentialDelivered;

/** 记下进程内发布的交码事件，供测试断言三通道都发了事件。 */
public final class RecordingCredentialEvents {

    private final List<CredentialDelivered> events = new CopyOnWriteArrayList<>();

    @EventListener
    public void on(CredentialDelivered event) {
        events.add(event);
    }

    public List<CredentialDelivered> events() {
        return List.copyOf(events);
    }

    public void reset() {
        events.clear();
    }
}
