-- L8-4 切换：旧 orders 改名留作回滚，分区表 orders_p 改名为 orders
-- 执行库：db_order
-- rename 是原子的，业务代码里的表名 orders 不用改。

rename table orders to orders_bak_l8_part,
             orders_p to orders;

-- 回滚 SQL（把两张表换回去）：
-- rename table orders to orders_p_bad,
--             orders_bak_l8_part to orders;
