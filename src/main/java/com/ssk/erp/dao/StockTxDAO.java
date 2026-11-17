package com.ssk.erp.dao;

import com.ssk.erp.dto.StockTxDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** SQL은 resources/mapper/StockTxMapper.xml */
@Mapper
public interface StockTxDAO {

    List<StockTxDTO> findRecent(@Param("limit") int limit);

    List<StockTxDTO> findSince(@Param("from") LocalDateTime from);

    /** from 이상 ~ to 미만, 오래된 순 */
    List<StockTxDTO> findBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    Double averageInQty(@Param("productId") Long productId, @Param("from") LocalDateTime from);

    int insert(StockTxDTO tx);
}
