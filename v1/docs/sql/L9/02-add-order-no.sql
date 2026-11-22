-- L9-3 给订单表加业务订单号列
-- 执行库：db_order
-- 说明：
--   1. 允许 NULL：已有的 10 万条 L8 测试数据没有订单号，不强行回填；
--   2. 不加唯一索引：orders 是分区表，唯一键必须包含分区列 create_time；
--      订单号唯一性由号段发号器保证，不靠数据库约束。
-- 回滚：alter table orders drop column order_no;

alter table orders
    add column order_no varchar(32) null comment '订单号（号段生成）' after id;

select count(*) as total,
       sum(case when order_no is null then 1 else 0 end) as null_order_no
from orders;
