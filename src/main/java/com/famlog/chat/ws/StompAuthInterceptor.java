package com.famlog.chat.ws;

import java.security.Principal;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.server.PathContainer;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import com.famlog.auth.jwt.AccessTokenValidators;
import com.famlog.auth.jwt.FamlogJwtClaims;
import com.famlog.auth.service.SessionRevocationStore;
import com.famlog.chat.relay.LocalDelivery;
import com.famlog.common.error.BusinessException;
import com.famlog.common.error.ErrorCode;
import com.famlog.group.service.GroupAccessChecker;

import tools.jackson.databind.json.JsonMapper;

/**
 * STOMP 인증·인가 (사양서 9.1 WebSocket 무단 구독, 9.9 SEC-T4).
 * <ul>
 * <li>CONNECT: Authorization 헤더의 Access 토큰을 REST와 같은 규칙으로 검증한다</li>
 * <li>SUBSCRIBE: /sub/groups/{groupId}(구성원만), /user/queue/events 만 허용</li>
 * <li>SEND: /pub/groups/{groupId}/messages|read (구성원만) 만 허용. 브로커 주소(/sub)로 직접 보내기는 막는다</li>
 * </ul>
 * SUBSCRIBE·SEND 때마다 세션 폐기와 구성원 여부를 다시 확인한다 (권한은 매번 DB 기준, 사양서 9.2).
 * 거부하면 그 프레임을 버리고 ERROR 프레임(message = 에러 코드)을 보낸 뒤 연결을 끊는다.
 * (연결별 순서 보장 모드에서는 여기서 던진 예외가 오류 처리기에 닿지 않아, 직접 clientOutboundChannel 로 보낸다)
 */
@Component
public class StompAuthInterceptor implements ChannelInterceptor {

    private static final String BEARER = "Bearer ";
    private static final String USER_EVENTS = "/user" + LocalDelivery.USER_EVENTS;
    private static final PathPattern GROUP_TOPIC = PathPatternParser.defaultInstance.parse("/sub/groups/{groupId}");
    private static final PathPattern GROUP_SEND =
            PathPatternParser.defaultInstance.parse("/pub/groups/{groupId}/{action:messages|read}");

    private final JwtDecoder jwtDecoder;
    private final SessionRevocationStore revocationStore;
    private final GroupAccessChecker groupAccessChecker;
    private final WsSessionRegistry sessionRegistry;
    private final MessageChannel clientOutboundChannel;
    private final JsonMapper jsonMapper;

    public StompAuthInterceptor(JwtDecoder jwtDecoder, SessionRevocationStore revocationStore,
            GroupAccessChecker groupAccessChecker, WsSessionRegistry sessionRegistry,
            @Lazy @Qualifier("clientOutboundChannel") MessageChannel clientOutboundChannel, JsonMapper jsonMapper) {
        this.jwtDecoder = jwtDecoder;
        this.revocationStore = revocationStore;
        this.groupAccessChecker = groupAccessChecker;
        this.sessionRegistry = sessionRegistry;
        this.clientOutboundChannel = clientOutboundChannel;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        try {
            check(accessor);
            return message;
        } catch (StompDeniedException e) {
            clientOutboundChannel.send(
                    StompErrorHandler.errorFrame(jsonMapper, e.getErrorCode(), accessor.getSessionId()));
            return null;
        }
    }

    private void check(StompHeaderAccessor accessor) {
        StompCommand command = accessor.getCommand();
        if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
            StompPrincipal principal = authenticate(accessor.getFirstNativeHeader("Authorization"));
            accessor.setUser(principal);
            sessionRegistry.bind(accessor.getSessionId(), principal);
        } else if (command == StompCommand.SUBSCRIBE) {
            StompPrincipal principal = requireActive(accessor.getUser());
            String destination = accessor.getDestination();
            if (!USER_EVENTS.equals(destination)) {
                requireMember(principal, groupIdOf(GROUP_TOPIC, destination));
            }
        } else if (command == StompCommand.SEND) {
            StompPrincipal principal = requireActive(accessor.getUser());
            requireMember(principal, groupIdOf(GROUP_SEND, accessor.getDestination()));
        }
    }

    private StompPrincipal authenticate(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER)) {
            throw new StompDeniedException(ErrorCode.AUTH_INVALID);
        }
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(authorization.substring(BEARER.length()).strip());
        } catch (JwtValidationException e) {
            boolean expired = e.getErrors().stream()
                    .anyMatch(error -> AccessTokenValidators.TOKEN_EXPIRED.equals(error.getErrorCode()));
            throw new StompDeniedException(expired ? ErrorCode.AUTH_TOKEN_EXPIRED : ErrorCode.AUTH_INVALID);
        } catch (JwtException e) {
            throw new StompDeniedException(ErrorCode.AUTH_INVALID);
        }
        try {
            return new StompPrincipal(Long.parseLong(jwt.getSubject()),
                    Long.parseLong(String.valueOf(jwt.getClaims().get(FamlogJwtClaims.SESSION_ID))));
        } catch (NumberFormatException e) {
            throw new StompDeniedException(ErrorCode.AUTH_INVALID);
        }
    }

    /** CONNECT 이후 세션이 폐기됐으면 거부한다 (Redis를 확인하지 못하면 REST와 같이 거부) */
    private StompPrincipal requireActive(Principal user) {
        if (!(user instanceof StompPrincipal principal)) {
            throw new StompDeniedException(ErrorCode.AUTH_INVALID);
        }
        boolean revoked;
        try {
            revoked = revocationStore.isRevoked(principal.sessionId());
        } catch (RuntimeException e) {
            revoked = true;
        }
        if (revoked) {
            throw new StompDeniedException(ErrorCode.AUTH_INVALID);
        }
        return principal;
    }

    private void requireMember(StompPrincipal principal, long groupId) {
        try {
            groupAccessChecker.requireMember(groupId, principal.userId());
        } catch (BusinessException e) {
            throw new StompDeniedException(e.getErrorCode());
        }
    }

    private static long groupIdOf(PathPattern pattern, String destination) {
        PathPattern.PathMatchInfo match = destination == null ? null
                : pattern.matchAndExtract(PathContainer.parsePath(destination));
        if (match == null) {
            throw new StompDeniedException(ErrorCode.NOT_FOUND);
        }
        try {
            return Long.parseLong(match.getUriVariables().get("groupId"));
        } catch (NumberFormatException e) {
            throw new StompDeniedException(ErrorCode.NOT_FOUND);
        }
    }
}
