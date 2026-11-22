-- L8-3 深翻页三版对比（执行库：db_order）
-- 统一取 offset=39990（第 4000 页，每页 10 条）。
-- 游标边界 = 第 39991~40000 条的最后一条：id=50015, create_time='2025-11-30 14:52:30'
-- 换数据后边界会变，可用下面这条重新取：
--   select id, create_time from orders where user_id=1 order by create_time desc, id desc limit 39999,1;

-- A. offset 分页：/order/page 用的就是它
select id, user_id, product_id, num, amount, create_time
from orders
where user_id = 1
order by create_time desc, id desc
limit 39990, 10;

-- B. 延迟关联：子查询只取 id（能走覆盖索引），外层只回表 10 行
select o.id, o.user_id, o.product_id, o.num, o.amount, o.create_time
from orders o
join (
    select id from orders
    where user_id = 1
    order by create_time desc, id desc
    limit 39990, 10
) t on o.id = t.id
order by o.create_time desc, o.id desc;

-- C. 游标分页：/order/cursor 用的就是它，边界由客户端带回
select id, user_id, product_id, num, amount, create_time
from orders
where user_id = 1
  and (create_time < '2025-11-30 14:52:30'
       or (create_time = '2025-11-30 14:52:30' and id < 50015))
order by create_time desc, id desc
limit 10;
