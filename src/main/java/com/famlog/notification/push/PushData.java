package com.famlog.notification.push;

/**
 * FCM data 키와 값 (앱과의 계약, HANDOFF "푸시 규칙"). 값은 모두 문자열.
 * 미리보기를 숨긴 사람에게는 body 키 자체를 넣지 않는다 (본문이 FCM으로 나가지 않게).
 */
public final class PushData {

    public static final String TYPE = "type";
    public static final String CHANNEL = "channel";
    public static final String GROUP_ID = "groupId";
    public static final String GROUP_NAME = "groupName";
    public static final String MESSAGE_ID = "messageId";
    public static final String SEQ = "seq";
    public static final String MESSAGE_TYPE = "messageType";
    public static final String SENDER_ID = "senderId";
    public static final String SENDER_NAME = "senderName";
    public static final String MEDIA_COUNT = "mediaCount";
    public static final String BODY = "body";
    public static final String SENT_AT = "sentAt";
    public static final String SILENT = "silent";
    public static final String SESSION_ID = "sessionId";
    public static final String DEVICE_NAME = "deviceName";
    public static final String PLATFORM = "platform";
    public static final String SCHEDULE_ID = "scheduleId";
    public static final String TITLE = "title";
    public static final String STARTS_AT = "startsAt";
    public static final String ALL_DAY = "allDay";
    public static final String REMINDER = "reminder";
    public static final String EXPORT_ID = "exportId";

    /** type 값 */
    public static final String CHAT_MESSAGE = "CHAT_MESSAGE";
    public static final String MEMBER_REMOVED = "MEMBER_REMOVED";
    public static final String NEW_DEVICE_LOGIN = "NEW_DEVICE_LOGIN";
    public static final String SCHEDULE_REMINDER = "SCHEDULE_REMINDER";
    public static final String DATA_EXPORT_READY = "DATA_EXPORT_READY";

    /** channel 값 (앱 알림 채널 4개. AI는 S16~에서 쓴다) */
    public static final String CHANNEL_CHAT = "CHAT";
    public static final String CHANNEL_SCHEDULE = "SCHEDULE";
    public static final String CHANNEL_AI = "AI";
    public static final String CHANNEL_SYSTEM = "SYSTEM";

    private PushData() {
    }
}
