package com.ssk.erp.dao;

import com.ssk.erp.dto.PendingActionDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/** SQL은 resources/mapper/PendingActionMapper.xml */
@Mapper
public interface PendingActionDAO {

    int insert(PendingActionDTO action);

    PendingActionDTO findById(@Param("id") String id);

    /** PENDING 이고 만료 전일 때만 상태를 바꾼다. 반환값 1 = 성공, 0 = 이미 처리됐거나 만료 */
    int transition(@Param("id") String id, @Param("to") String to, @Param("now") LocalDateTime now);
}
