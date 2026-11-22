-- L8-2 联合索引：等值条件列 user_id 在前，排序列 create_time 在后
-- 执行库：db_order
-- 加索引后同一条 SQL 的 type 从 ALL 变 ref，Extra 里的 Using filesort 消失。

alter table orders add index idx_user_create(user_id, create_time);

-- 回滚 SQL：
-- alter table orders drop index idx_user_create;
