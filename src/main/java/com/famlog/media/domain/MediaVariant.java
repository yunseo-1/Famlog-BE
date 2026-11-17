package com.famlog.media.domain;

/** 내려받을 파일. 썸네일은 긴 변 256 / 720 / 1440px WebP (동영상은 미리보기 프레임). */
public enum MediaVariant {
    ORIGINAL,
    THUMB_S,
    THUMB_M,
    THUMB_L
}
