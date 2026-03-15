package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        //3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4.保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5.发送验证码
        log.debug("发送短信验证码成功，验证码：{}", code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回报错
            return Result.fail("手机号格式错误！");
        }
        // 3.校验验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if(cacheCode == null || !cacheCode.equals(code)){
            // 4.不一致，报错
            return Result.fail("验证码错误");
        }
        // 5.一致，根据手机号查询用户
        User user = query().eq("phone",phone).one();
        // 6.判断用户是否存在
        if (user == null) {
            user = createUserWithPhone(phone);
        }

        // 7.保存用户信息到redis中
        //7.1.随机生成token，作为登陆令牌
        String token = UUID.randomUUID().toString(true);
        //7.2.将User对象转为Hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        // 核心：把字段值全转成 String，解决 Long 无法强转 String 的报错
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        //7.3.存储
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        //7.4.设置token有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);


        //8.返回token
        return Result.ok(token);
    }

    @Override
    public Result logout(String token) {
        // 1. 校验 token 是否为空（虽然拦截器可能已经校验过，但 Service 层建议保持严谨）
        if (StrUtil.isBlank(token)) {
            return Result.ok();
        }

        // 2. 构建 Redis 中存储用户信息的 Key
        String key = LOGIN_USER_KEY + token;

        // 3. 直接从 Redis 中删除该 Key
        // delete 方法如果返回 true，表示该 Key 原本存在且已被成功删除
        Boolean isDeleted = stringRedisTemplate.delete(key);

        if (Boolean.TRUE.equals(isDeleted)) {
            log.debug("用户登出成功，Token 已从 Redis 销毁: {}", token);
        }

        // 4. 清理当前线程的 ThreadLocal 信息
        UserHolder.removeUser();

        return Result.ok();
    }

    private User createUserWithPhone(String phone){
        // 1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 2.保存用户
        save(user);
        return user;
    }
}
