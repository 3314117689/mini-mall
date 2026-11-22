package com.mall.order.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * L9 补丁3：数据库号段水位。
 * Redis 正常时它只是一个备份水位（每次领段顺带推进）；
 * Redis 不可用时它就是发号的唯一依据，靠 for update 行锁保证多实例不重号。
 */
@Mapper
public interface SegmentMapper {

    @Select("select max_id from id_segment where biz = #{biz}")
    Long selectMaxId(String biz);

    /** 行锁：同一时刻只有一个实例能读到并推进水位 */
    @Select("select max_id from id_segment where biz = #{biz} for update")
    Long selectMaxIdForUpdate(String biz);

    @Insert("insert ignore into id_segment(biz, max_id) values(#{biz}, #{maxId})")
    int insertIgnore(@Param("biz") String biz, @Param("maxId") long maxId);

    @Update("update id_segment set max_id = #{maxId} where biz = #{biz}")
    int updateMaxId(@Param("biz") String biz, @Param("maxId") long maxId);

    /** 只允许水位前进：并发下小的值不能覆盖大的 */
    @Update("update id_segment set max_id = #{maxId} where biz = #{biz} and max_id < #{maxId}")
    int raiseMaxId(@Param("biz") String biz, @Param("maxId") long maxId);
}
