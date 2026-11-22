-- L8 造 10 万条测试订单（跨 2024-12 ~ 2026-11，共 24 个月）
-- 执行库：db_order
-- 只执行一次；重跑会重复追加。
-- 设计：
--   1. 用 0~9 的数字表交叉连接生成 0~99999 的序列，不需要存储过程；
--   2. user_id=1 占 80%（8 万行），保证同一用户有足够深的页可翻；
--      其余 2 万行分散到 user_id 2~200；
--   3. create_time 从 2024-12-01 起，每条间隔 630 秒，覆盖 24 个月。

drop table if exists l8_seq;
create table l8_seq (n int primary key);
insert into l8_seq values (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);

insert into orders(user_id, product_id, num, amount, create_time)
select
    case when n % 10 < 8 then 1 else 2 + (n % 199) end as user_id,
    1 + (n % 50) as product_id,
    1 + (n % 5) as num,
    round((1 + (n % 5)) * 9.90, 2) as amount,
    date_add('2024-12-01 00:00:00', interval n * 630 second) as create_time
from (
    select a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 as n
    from l8_seq a, l8_seq b, l8_seq c, l8_seq d, l8_seq e
) t;

drop table l8_seq;

select count(*) as total, min(create_time) as min_time, max(create_time) as max_time
from orders;

select user_id, count(*) as cnt from orders group by user_id order by cnt desc limit 5;
