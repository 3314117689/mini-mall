-- L8-4 分区维护：pmax 兜底分区存在时，不能直接 ADD PARTITION，
-- 必须用 REORGANIZE 把 pmax 拆成"新月份分区 + 新 pmax"。
-- 执行库：db_order
-- 下面以新增 2027-01 为例（执行后可用 drop partition 删除空分区，不影响已有数据）。

alter table orders reorganize partition pmax into (
    partition p202701 values less than ('2027-02-01'),
    partition pmax values less than (maxvalue)
);

-- 验证：p202701 出现且 pmax 仍是最后一个
select partition_name, partition_description
from information_schema.partitions
where table_schema = 'db_order' and table_name = 'orders'
order by partition_ordinal_position;

-- 回滚：删除刚加的空分区（有数据时会连数据一起删，生产慎用）
-- alter table orders drop partition p202701;
