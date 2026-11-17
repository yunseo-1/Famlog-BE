package com.famlog.media.service;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * 파일 앞부분(매직 바이트)으로 실제 형식을 판별한다 (사양서 9.1 악성 파일 업로드, SEC-T6).
 * 앱이 신고한 MIME이나 확장자는 믿지 않는다. 허용 목록에 있는 형식만 알아본다.
 */
public final class MagicBytes {

    /** 판별에 필요한 앞부분 길이 */
    public static final int HEAD_LENGTH = 32;

    private static final byte[] JPEG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };
    private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A };
    private static final Set<String> HEIF_BRANDS = Set.of("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1",
            "msf1");
    private static final Set<String> QUICKTIME_BRANDS = Set.of("qt  ");
    private static final Set<String> QUICKTIME_ATOMS = Set.of("moov", "mdat", "wide", "free", "skip", "pnot");

    private MagicBytes() {
    }

    public static Optional<String> detect(byte[] head) {
        if (startsWith(head, 0, JPEG)) {
            return Optional.of("image/jpeg");
        }
        if (startsWith(head, 0, PNG)) {
            return Optional.of("image/png");
        }
        if (ascii(head, 0, 4).equals("RIFF") && ascii(head, 8, 4).equals("WEBP")) {
            return Optional.of("image/webp");
        }
        String box = ascii(head, 4, 4);
        if (box.equals("ftyp")) {
            String brand = ascii(head, 8, 4);
            if (HEIF_BRANDS.contains(brand)) {
                return Optional.of(brand.startsWith("he") ? "image/heic" : "image/heif");
            }
            if (QUICKTIME_BRANDS.contains(brand)) {
                return Optional.of("video/quicktime");
            }
            // isom, iso2, mp41, mp42, avc1, M4V, 3gp 등 ISO 기반 동영상
            return Optional.of("video/mp4");
        }
        if (QUICKTIME_ATOMS.contains(box)) {
            return Optional.of("video/quicktime");
        }
        return Optional.empty();
    }

    /** 신고한 형식과 실제 형식이 같은지. HEIC/HEIF, MP4/QuickTime 은 서로 같은 것으로 본다 (기기마다 다르게 신고) */
    public static boolean matches(String declared, String detected) {
        return family(declared).equals(family(detected));
    }

    private static String family(String mime) {
        return switch (mime) {
            case "image/heic", "image/heif" -> "heif";
            case "video/mp4", "video/quicktime" -> "video";
            default -> mime;
        };
    }

    private static boolean startsWith(byte[] data, int offset, byte[] prefix) {
        return data.length >= offset + prefix.length
                && Arrays.equals(data, offset, offset + prefix.length, prefix, 0, prefix.length);
    }

    private static String ascii(byte[] data, int offset, int length) {
        if (data.length < offset + length) {
            return "";
        }
        return new String(data, offset, length, StandardCharsets.ISO_8859_1);
    }
}
