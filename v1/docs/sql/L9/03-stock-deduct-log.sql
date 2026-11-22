-- L9 修复：库存扣减流水，order_no 做幂等键
-- 执行库：db_product
-- status：0 初始化 / 1 处理中 / 2 成功 / 3 失败 / 4 已释放
-- 回滚：drop table stock_deduct_log;

create table if not exists stock_deduct_log (
    id bigint auto_increment primary key,
    order_no varchar(32) not null comment '订单号，幂等键',
    product_id bigint not null comment '商品ID',
    num int not null comment '扣减数量',
    status tinyint not null default 0 comment '0初始化 1处理中 2成功 3失败 4已释放',
    remaining int null comment '扣减成功后的剩余库存',
    error_code int null comment '失败业务码',
    error_msg varchar(255) null comment '失败原因',
    create_time datetime not null default current_timestamp,
    update_time datetime not null default current_timestamp on update current_timestamp,
    unique key uk_order_no (order_no),
    key idx_create_time (create_time)
) engine=innodb default charset=utf8mb4 comment='库存扣减流水（L9 幂等）';
