package com.yyh.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yyh.user.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
