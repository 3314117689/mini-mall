-- L8-4 建按月分区表 orders_p，并把 orders 的数据原样导入
-- 执行库：db_order
-- 说明：
--   1. 分区表要求"每个唯一键都必须包含分区列"，所以主键从 (id) 改成 (id, create_time)；
--   2. 二级索引 idx_user_create 保留，MySQL 会为每个分区各建一棵；
--   3. 数据覆盖 2024-12 ~ 2026-11，分区建到 2026-12，末尾 pmax 兜底。

drop table if exists orders_p;

create table orders_p (
    id bigint(20) not null auto_increment,
    user_id bigint(20) not null comment '下单用户ID（来自mall-user）',
    product_id bigint(20) not null comment '商品ID（来自mall-product）',
    num int(11) not null comment '购买数量',
    amount decimal(10,2) not null comment '订单总金额（DECIMAL对应Java BigDecimal）',
    create_time datetime not null default current_timestamp comment '下单时间',
    primary key (id, create_time),
    key idx_user_create (user_id, create_time)
) engine=innodb default charset=utf8mb4 comment='订单表（按月分区）'
partition by range columns(create_time) (
    partition p202412 values less than ('2025-01-01'),
    partition p202501 values less than ('2025-02-01'),
    partition p202502 values less than ('2025-03-01'),
    partition p202503 values less than ('2025-04-01'),
    partition p202504 values less than ('2025-05-01'),
    partition p202505 values less than ('2025-06-01'),
    partition p202506 values less than ('2025-07-01'),
    partition p202507 values less than ('2025-08-01'),
    partition p202508 values less than ('2025-09-01'),
    partition p202509 values less than ('2025-10-01'),
    partition p202510 values less than ('2025-11-01'),
    partition p202511 values less than ('2025-12-01'),
    partition p202512 values less than ('2026-01-01'),
    partition p202601 values less than ('2026-02-01'),
    partition p202602 values less than ('2026-03-01'),
    partition p202603 values less than ('2026-04-01'),
    partition p202604 values less than ('2026-05-01'),
    partition p202605 values less than ('2026-06-01'),
    partition p202606 values less than ('2026-07-01'),
    partition p202607 values less than ('2026-08-01'),
    partition p202608 values less than ('2026-09-01'),
    partition p202609 values less than ('2026-10-01'),
    partition p202610 values less than ('2026-11-01'),
    partition p202611 values less than ('2026-12-01'),
    partition p202612 values less than ('2027-01-01'),
    partition pmax values less than (maxvalue)
);

insert into orders_p (id, user_id, product_id, num, amount, create_time)
select id, user_id, product_id, num, amount, create_time from orders;

select count(*) as total_orders_p from orders_p;

select partition_name, partition_description, table_rows
from information_schema.partitions
where table_schema = 'db_order' and table_name = 'orders_p'
order by partition_ordinal_position;

-- 回滚（在执行 06-rename-orders.sql 之前，直接 drop 即可）：
-- drop table orders_p;
