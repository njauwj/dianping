package com.hmdp.dto;

import lombok.Data;

import java.util.List;

/**
 * @author: wj
 * @create_time: 2023/3/30 10:57
 * @explain:
 */
@Data
public class ScrollResult {

    private List<?> list;

    private Long minTime;

    private Integer offset;

    public ScrollResult() {
    }

    public ScrollResult(List<?> list, Long minTime, Integer offset) {
        this.list = list;
        this.minTime = minTime;
        this.offset = offset;
    }
}
