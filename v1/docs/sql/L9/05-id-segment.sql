-- L9 补丁3：数据库号段水位，Redis 不可用时兜底发号
-- 执行库：db_order
-- 回滚：drop table id_segment;

create table if not exists id_segment (
    biz varchar(32) not null primary key comment '业务标识，如 order_no',
    max_id bigint not null default 0 comment '已分配到的最大号',
    update_time datetime not null default current_timestamp on update current_timestamp
) engine=innodb default charset=utf8mb4 comment='号段水位（L9 补丁3）';

-- 种子取三处历史号的最大值，避免 DB 兜底时重发已用过的订单号：
-- orders 是真正对外可见的订单号；扣减流水和补偿任务里也可能有更靠后的号（订单没落成）。
insert into id_segment(biz, max_id)
select 'order_no',
       greatest(
           coalesce((select max(cast(substring(order_no, 4) as unsigned))
                     from db_order.orders where order_no is not null), 0),
           coalesce((select max(cast(substring(order_no, 4) as unsigned))
                     from db_product.stock_deduct_log), 0),
           coalesce((select max(cast(substring(order_no, 4) as unsigned))
                     from db_order.stock_compensate_task), 0)
       )
on duplicate key update max_id = greatest(max_id, values(max_id));
