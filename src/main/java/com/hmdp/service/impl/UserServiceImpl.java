package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
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
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author wj
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result sedCode(String phone, HttpSession session) {
        //1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误");
        }

        //3. 符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4. 保存验证码到session
        //session.setAttribute(CODE,code);
        //4.保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5. 发送验证码
        log.debug("发送短信验证码成功，验证码:{}", code);
        //返回ok
        return Result.ok();
    }

    @Override
    public Result logout(HttpServletRequest request) {
        String token = request.getHeader("authorization");
        String key = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().delete(key, "id", "icon", "nickName");
        return Result.ok("退出登录");
    }

    /**
     * 用户签到
     *
     * @return
     */
    @Override
    public Result sign() {
        Long id = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String suffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        int dayOfMonth = now.getDayOfMonth();
        String key = SIGN_KEY + id + suffix;
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);

        return Result.ok();
    }

    /**
     * 用户连续签到次数
     *
     * @return
     */
    @Override
    public Result signCount() {
        Long id = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String suffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        int dayOfMonth = now.getDayOfMonth();
        String key = SIGN_KEY + id + suffix;
        List<Long> list = stringRedisTemplate.opsForValue().bitField(key, BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (list == null || list.isEmpty()) {
            return Result.ok(0);
        }
        Long signNum = list.get(0);
        if (signNum == null || signNum == 0L) {
            return Result.ok(0);
        }
        int count = 0;
        while (true) {
            if ((signNum & 1) == 0) {
                break;
            } else {
                count++;
            }
            signNum = signNum >>> 1;
        }
        return Result.ok(count);
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1. 校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        //2. 校验验证码
        //Object cacheCode = session.getAttribute(CODE);
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) {
            //3. 不一致，报错
            return Result.fail("验证码错误");
        }

        //4.一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();

        //5. 判断用户是否存在
        if (user == null) {
            //6. 不存在，创建新用户
            user = createUserWithPhone(phone);
        }

        //7.保存用户信息到session
        //session.setAttribute("user",BeanUtil.copyProperties(user,UserDTO.class));
        //7.保存用户信息到redis
        //生成随机token，作为登入令牌
        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userToMap = BeanUtil.beanToMap(
                userDTO, new HashMap<>(), CopyOptions.create().setIgnoreNullValue(true)
                        .setFieldValueEditor(
                                (filedKey, filedValue) -> filedValue.toString()
                        )
        );
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userToMap);
        //设置有效期,但这有一个问题不管用户有没有访问30min后都会被剔除，所以得刷新有效期时间，只要用户在请求就得刷新有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);
        //将token返回给前端
        return Result.ok(token);
    }


    private User createUserWithPhone(String phone) {
        // 1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 2.保存用户
        save(user);
        return user;
    }
}
