package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;

/**
 * @author: wj
 * @create_time: 2023/3/22 19:13
 * @explain:
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {
    Result seckillVoucher(Long voucherId);

    Result creatVoucherOrder(Long voucherId);

    void creatVoucherOrder(VoucherOrder voucherOrder);
}
