-- L9 补丁2：库存补偿任务，失败可定时重试、重试耗尽转人工
-- 执行库：db_order
-- status：0 待处理(PENDING) / 1 成功(SUCCESS) / 2 需人工(MANUAL)
-- 回滚：drop table stock_compensate_task;

create table if not exists stock_compensate_task (
    id bigint auto_increment primary key,
    order_no varchar(32) not null comment '订单号，幂等键',
    product_id bigint not null comment '商品ID',
    num int not null comment '需要回补的数量',
    status tinyint not null default 0 comment '0待处理 1成功 2需人工',
    retry_count int not null default 0 comment '已重试次数',
    next_retry_time datetime not null comment '下次重试时间',
    last_error varchar(500) null comment '最近一次失败原因',
    create_time datetime not null default current_timestamp,
    update_time datetime not null default current_timestamp on update current_timestamp,
    unique key uk_order_no (order_no),
    key idx_status_next (status, next_retry_time)
) engine=innodb default charset=utf8mb4 comment='库存补偿任务（L9 补丁2）';
