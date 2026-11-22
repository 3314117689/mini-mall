-- L8 造数前备份：把 orders 原表（9 行）原样复制一份
-- 执行库：db_order
-- 回滚方式（恢复到造数前）：
--   truncate table orders;
--   insert into orders select * from orders_bak_l8;

drop table if exists orders_bak_l8;
create table orders_bak_l8 as select * from orders;

select count(*) as bak_rows from orders_bak_l8;
