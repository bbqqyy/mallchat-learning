package com.bqy.common.websocket.domain.vo.resp;

import lombok.Data;

@Data
public class WSBaseResp<T> {
    private Integer type;
    private T data;
}