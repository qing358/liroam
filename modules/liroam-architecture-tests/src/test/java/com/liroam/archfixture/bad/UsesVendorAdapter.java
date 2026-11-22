package com.liroam.archfixture.bad;

import com.liroam.supplier.adapter.archfixture.FakeVendorAdapter;

/** 违例夹具：业务代码直接依赖某个供应商适配器。 */
public class UsesVendorAdapter {
    public Object pick() {
        return new FakeVendorAdapter();
    }
}
