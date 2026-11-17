package com.ssk.erp.dao;

import com.ssk.erp.dto.ProductDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** SQL은 resources/mapper/ProductMapper.xml */
@Mapper
public interface ProductDAO {

    List<ProductDTO> findAll();

    List<ProductDTO> findLow();

    ProductDTO findById(@Param("id") Long id);

    ProductDTO findByCode(@Param("code") String code);

    List<ProductDTO> searchByKeyword(@Param("keyword") String keyword);

    /** SELECT ... FOR UPDATE : 같은 상품을 동시에 입출고하지 못하게 행 잠금 */
    ProductDTO findByIdForUpdate(@Param("id") Long id);

    int updateStock(@Param("id") Long id, @Param("stockQty") int stockQty);

    int insert(ProductDTO product);

    int count();
}
