package com.ssk.erp.dao;

import com.ssk.erp.dto.InboundPlanDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/** SQL은 resources/mapper/InboundPlanMapper.xml */
@Mapper
public interface InboundPlanDAO {

    InboundPlanDTO findByProductAndDate(@Param("productId") Long productId, @Param("planDate") LocalDate planDate);

    List<InboundPlanDTO> findBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    InboundPlanDTO findByIdForUpdate(@Param("id") Long id);

    int addReceived(@Param("id") Long id, @Param("qty") int qty);

    int updatePlan(@Param("id") Long id, @Param("plannedQty") int plannedQty, @Param("source") String source);

    int insert(InboundPlanDTO plan);
}
