package cn.lili.modules.member.serviceimpl;

import cn.lili.cache.Cache;
import cn.lili.cache.CachePrefix;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.common.security.support.LoginFailLimitSupport;
import cn.lili.modules.member.mapper.MemberMapper;
import cn.lili.modules.member.support.MemberAccountSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;

/**
 * 会员密码重置与修改会话吊销测试
 * 验证密码变更后主动清理 Redis 中买家与商家角色的 AccessToken 和 RefreshToken 缓存
 *
 * @author Bulbasaur
 * @since 2026-11-11
 */
@ExtendWith(MockitoExtension.class)
public class MemberPasswordRevokeTest {

    @Mock
    private Cache cache;

    @InjectMocks
    private MemberServiceImpl memberService;

    /** 为会员服务装配真实账号组件，令牌撤销验证继续覆盖买家和商家缓存键。 */
    @BeforeEach
    void setUp() {
        var memberAccounts = new MemberAccountSupport(mock(MemberMapper.class), cache, mock(LoginFailLimitSupport.class));
        ReflectionTestUtils.setField(memberService, "memberAccounts", memberAccounts);
    }

    @Test
    @DisplayName("测试调用 revokeMemberTokens 时正确清理买家与商家端的全部会话缓存")
    public void testRevokeMemberTokens() {
        String memberId = "member_1001";
        List<String> ids = Collections.singletonList(memberId);

        memberService.revokeMemberTokens(ids);

        // 验证模糊删除买家端 AccessToken
        verify(cache).vagueDel(CachePrefix.ACCESS_TOKEN.getPrefix(UserEnums.MEMBER, memberId));
        // 验证模糊删除买家端 RefreshToken
        verify(cache).vagueDel(CachePrefix.REFRESH_TOKEN.getPrefix(UserEnums.MEMBER, memberId));
        // 验证模糊删除商家端 AccessToken
        verify(cache).vagueDel(CachePrefix.ACCESS_TOKEN.getPrefix(UserEnums.STORE, memberId));
        // 验证模糊删除商家端 RefreshToken
        verify(cache).vagueDel(CachePrefix.REFRESH_TOKEN.getPrefix(UserEnums.STORE, memberId));
    }
}
