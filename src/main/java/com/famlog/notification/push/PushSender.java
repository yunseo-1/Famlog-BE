package com.famlog.notification.push;

import java.util.List;
import java.util.Set;

/** 푸시 발송기. 운영은 FCM, 키가 없으면(CI·테스트·로컬) 로그만 남기는 구현을 쓴다. */
public interface PushSender {

    /**
     * 보낸다. 실패해도 예외를 던지지 않는다 (푸시는 최선을 다해 보내는 것이고, 놓친 메시지는 앱이 채팅 내역으로 받는다).
     *
     * @return 더 쓸 수 없는 토큰 (앱 삭제·다른 프로젝트 토큰). 호출한 쪽이 지운다
     */
    Set<String> send(List<PushMessage> messages);
}
