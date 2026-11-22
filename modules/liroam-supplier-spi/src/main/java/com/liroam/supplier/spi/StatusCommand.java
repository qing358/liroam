package com.liroam.supplier.spi;

import com.liroam.supplier.spi.SupplierCapabilities.StatusOp;

public record StatusCommand(String requestNo, CardRef card, StatusOp op) {
}
