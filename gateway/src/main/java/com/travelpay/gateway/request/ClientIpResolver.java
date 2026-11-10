package com.travelpay.gateway.request;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.stream.Stream;

import io.netty.handler.ipfilter.IpFilterRuleType;
import io.netty.handler.ipfilter.IpSubnetFilterRule;
import io.netty.util.NetUtil;
import org.jspecify.annotations.Nullable;

/** 실제 클라이언트 IP를 정합니다. Cloudflare나 우리 쪽 프록시에서 온 연결일 때만 CF-Connecting-IP를 믿습니다. */
class ClientIpResolver {

    // CF-Connecting-IP를 믿을 연결 상대 대역: Cloudflare 공개 대역 + 우리 쪽 프록시(ALB·cloudflared) 대역
    private final List<IpSubnetFilterRule> trustedRanges;

    ClientIpResolver(ClientIpProperties properties) {
        this.trustedRanges = Stream.concat(properties.cloudflareAddresses().stream(), properties.proxyAddresses().stream())
                .map(ClientIpResolver::subnet)
                .toList();
    }

    // 연결 주소를 모르면(테스트용 요청 등) null
    @Nullable String resolve(@Nullable InetSocketAddress remoteAddress, List<String> cloudflareClientIps) {
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            return null;
        }
        // 값이 정확히 하나일 때만 봄. 여러 개면 어느 값을 Cloudflare가 넣었는지 알 수 없음
        if (cloudflareClientIps.size() == 1 && fromTrustedRange(remoteAddress)) {
            var cloudflareClientIp = cloudflareClientIps.getFirst().strip();
            if (isIp(cloudflareClientIp)) {
                return cloudflareClientIp;
            }
        }
        return remoteAddress.getAddress().getHostAddress();
    }

    private boolean fromTrustedRange(InetSocketAddress remoteAddress) {
        return trustedRanges.stream().anyMatch(range -> range.matches(remoteAddress));
    }

    // 주소 조회(DNS) 없이 IP 형식만 확인
    private static boolean isIp(String value) {
        return NetUtil.isValidIpV4Address(value) || NetUtil.isValidIpV6Address(value);
    }

    private static IpSubnetFilterRule subnet(String cidr) {
        try {
            return new IpSubnetFilterRule(cidr, IpFilterRuleType.ACCEPT);
        }
        catch (IllegalArgumentException | IndexOutOfBoundsException exception) {
            throw new IllegalArgumentException("travelpay.gateway.client-ip 값이 IP 대역(CIDR)이 아닙니다: " + cidr, exception);
        }
    }
}
