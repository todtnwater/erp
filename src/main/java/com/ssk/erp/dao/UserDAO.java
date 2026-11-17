package com.ssk.erp.dao;

import com.ssk.erp.dto.UserDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** SQL은 resources/mapper/UserMapper.xml */
@Mapper
public interface UserDAO {

    UserDTO findByLoginId(@Param("loginId") String loginId);

    UserDTO findById(@Param("id") Long id);

    List<UserDTO> findAll();

    int count();

    int countAdmins();

    int insert(UserDTO user);

    int updateLastLogin(@Param("id") Long id, @Param("now") LocalDateTime now);

    int updateAdmin(@Param("id") Long id, @Param("isAdmin") int isAdmin);

    int updateEnabled(@Param("id") Long id, @Param("enabled") int enabled);

    int updateName(@Param("id") Long id, @Param("name") String name);

    int updatePassword(@Param("id") Long id, @Param("passwordHash") String passwordHash);
}
