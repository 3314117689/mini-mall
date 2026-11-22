-- L9-3 给 orders 加 order_no 前，先整表备份一份数据
-- 执行库：db_order
-- 注意：CTAS 建出来的是普通表，不保留分区定义，只用于数据恢复。
-- 回滚：drop table orders_bak_l9;

drop table if exists orders_bak_l9;
create table orders_bak_l9 as select * from orders;

select count(*) as bak_rows from orders_bak_l9;
