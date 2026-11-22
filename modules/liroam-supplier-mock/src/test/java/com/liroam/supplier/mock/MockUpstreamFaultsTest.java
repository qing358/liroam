package com.liroam.supplier.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.liroam.common.web.UserFacingException;
import com.liroam.supplier.mock.MockFaults.Failure;
import com.liroam.supplier.mock.MockFaults.Operation;
import com.liroam.supplier.mock.MockUpstreamController.FaultBody;

class MockUpstreamFaultsTest {

    private final MockFaults faults = new MockFaults(List.of());
    private final MockUpstreamController controller = new MockUpstreamController(null, faults);

    private java.util.Optional<Failure> nextProvision() {
        return faults.take(MockShapeAAdapter.ADAPTER_KEY, Operation.PROVISION, "r", "8986", "P");
    }

    @Test
    void injectsCountedFaultsThenRecovers() {
        controller.injectFaults(new FaultBody(MockShapeAAdapter.ADAPTER_KEY, "PROVISION", "CONNECT_FAILED", 2));

        assertThat(nextProvision()).contains(Failure.CONNECT_FAILED);
        assertThat(nextProvision()).contains(Failure.CONNECT_FAILED);
        assertThat(nextProvision()).isEmpty();
    }

    @Test
    void clearDropsPendingFaults() {
        controller.injectFaults(new FaultBody(MockShapeAAdapter.ADAPTER_KEY, "PROVISION", "SERVER_ERROR", 5));
        controller.clearFaults(new FaultBody(MockShapeAAdapter.ADAPTER_KEY, null, null, null));

        assertThat(nextProvision()).isEmpty();
    }

    @Test
    void rejectsUnknownSupplierOperationFailureAndCount() {
        assertThatThrownBy(() -> controller.injectFaults(new FaultBody("real-x", "PROVISION", "SERVER_ERROR", 1)))
                .isInstanceOf(UserFacingException.class).hasMessageContaining("real-x");
        assertThatThrownBy(() -> controller.injectFaults(new FaultBody(MockShapeAAdapter.ADAPTER_KEY, "NOPE", "SERVER_ERROR", 1)))
                .isInstanceOf(UserFacingException.class);
        assertThatThrownBy(() -> controller.injectFaults(new FaultBody(MockShapeAAdapter.ADAPTER_KEY, "PROVISION", "NOPE", 1)))
                .isInstanceOf(UserFacingException.class);
        assertThatThrownBy(() -> controller.injectFaults(new FaultBody(MockShapeAAdapter.ADAPTER_KEY, "PROVISION", "SERVER_ERROR", 0)))
                .isInstanceOf(UserFacingException.class);
        assertThatThrownBy(() -> controller.injectFaults(new FaultBody(MockShapeAAdapter.ADAPTER_KEY, "PROVISION", "SERVER_ERROR", 51)))
                .isInstanceOf(UserFacingException.class);
    }
}
